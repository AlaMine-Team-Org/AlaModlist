#!/usr/bin/env bash
# =============================================================================
# The GitHub Release of a tag, and the one set of bytes every platform gets.
#
# Once a jar is published under a name, that name means those bytes forever:
# consumers pin a download by its hash, and a replaced file breaks them without
# a word. So the release is the canonical store of a version's files:
#
#   resolve   for each jar, the copy already attached to the release wins; the
#             jar built by this run is used only where the release has none.
#             The chosen files go into <out-dir>, with SHA256SUMS.txt, and every
#             later upload - Modrinth, CurseForge - takes them from there. A
#             re-run whose fresh build differs byte for byte therefore cannot
#             spread a second version of the same release.
#   publish   creates the release from <dir>, or completes one that an earlier
#             attempt left without some of its files. It never replaces an
#             asset, and never edits a body it did not write.
#
# Usage:
#   publish-github-release.sh resolve <build-dir> <out-dir>
#   publish-github-release.sh publish <dir>
# Needs GH_TOKEN, GITHUB_REPOSITORY, RELEASE_TAG, MOD_ID, VERSION; publish
# also RELEASE_TITLE and NOTES. The jars are every Minecraft line times both
# loaders of the working tree (release-targets.sh): one release, one set of notes,
# one SHA256SUMS.txt for all of them.
# Exit: 0 done, 1 the release disagrees with what this run would publish, 2 bad input.
# =============================================================================

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-}"

need() {
    local name
    for name in "$@"; do
        [ -n "${!name:-}" ] || { echo "::error::$name is empty"; exit 2; }
    done
}

emit() {
    printf '%s=%s\n' "$1" "$2"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"
    fi
}

need GH_TOKEN GITHUB_REPOSITORY RELEASE_TAG MOD_ID VERSION
REPO="$GITHUB_REPOSITORY"
mapfile -t JARS < <(bash "$HERE/release-targets.sh" . | cut -f3)
[ "${#JARS[@]}" -gt 0 ] || { echo "::error::the tree has no release target"; exit 2; }
SUMS="SHA256SUMS.txt"

# release_json <out-file> -> 0 the release exists, 1 it does not, 2 cannot tell
release_json() {
    local out="$1" err rc
    err="$(mktemp)"
    gh api "repos/$REPO/releases/tags/$RELEASE_TAG" > "$out" 2> "$err"
    rc=$?
    if [ "$rc" -eq 0 ]; then rm -f "$err"; return 0; fi
    if grep -q 'HTTP 404' "$err"; then rm -f "$err"; return 1; fi
    echo "::error::cannot read the release $RELEASE_TAG:"
    cat "$err"
    rm -f "$err"
    return 2
}

# asset_digest <release-json> <name> -> "sha256:..." or empty
asset_digest() {
    jq -r --arg n "$2" '[.assets[]? | select(.name == $n) | (.digest // "missing")][0] // ""' "$1"
}

case "$MODE" in
    resolve)
        BUILD="${2:-}"
        OUT="${3:-}"
        [ -d "$BUILD" ] && [ -n "$OUT" ] || { echo "usage: publish-github-release.sh resolve <build-dir> <out-dir>" >&2; exit 2; }
        mkdir -p "$OUT"
        info="$(mktemp)"
        release_json "$info"
        state=$?
        [ "$state" -eq 2 ] && exit 1

        from_release=0
        for jar in "${JARS[@]}"; do
            [ -f "$BUILD/$jar" ] || { echo "::error::this run built no $jar"; exit 1; }
            digest=""
            [ "$state" -eq 0 ] && digest="$(asset_digest "$info" "$jar")"
            if [ -z "$digest" ]; then
                cp "$BUILD/$jar" "$OUT/$jar"
                echo "$jar: new - the jar built by this run."
                continue
            fi
            if ! gh release download "$RELEASE_TAG" --repo "$REPO" --pattern "$jar" --dir "$OUT" --clobber; then
                echo "::error::cannot download $jar from the existing release"
                exit 1
            fi
            actual="sha256:$(sha256sum "$OUT/$jar" | cut -d' ' -f1)"
            if [ "$digest" != missing ] && [ "$digest" != "$actual" ]; then
                echo "::error::the downloaded $jar ($actual) does not match the digest the release reports ($digest)"
                exit 1
            fi
            from_release=$((from_release + 1))
            fresh="sha256:$(sha256sum "$BUILD/$jar" | cut -d' ' -f1)"
            if [ "$fresh" = "$actual" ]; then
                echo "$jar: already published; this run rebuilt the same bytes."
            else
                echo "::notice::$jar is already published as $actual; this run built $fresh. The published file is kept and is what every platform receives."
            fi
        done

        (cd "$OUT" && sha256sum "${JARS[@]}" > "$SUMS")
        if [ "$state" -eq 0 ] && [ -n "$(asset_digest "$info" "$SUMS")" ]; then
            published="$(mktemp -d)"
            gh release download "$RELEASE_TAG" --repo "$REPO" --pattern "$SUMS" --dir "$published" \
                || { echo "::error::cannot download $SUMS from the existing release"; exit 1; }
            if ! diff -q "$published/$SUMS" "$OUT/$SUMS" > /dev/null; then
                echo "::error::the release already carries a $SUMS that disagrees with its own jars:"
                diff "$published/$SUMS" "$OUT/$SUMS"
                exit 1
            fi
            rm -rf "$published"
        fi
        cat "$OUT/$SUMS"
        emit from_release "$from_release"
        rm -f "$info"
        ;;

    publish)
        DIR="${2:-}"
        [ -d "$DIR" ] || { echo "usage: publish-github-release.sh publish <dir>" >&2; exit 2; }
        need RELEASE_TITLE NOTES
        [ -s "$NOTES" ] || { echo "::error::the notes file $NOTES is missing or empty"; exit 2; }
        FILES=()
        for name in "${JARS[@]}" "$SUMS"; do
            [ -f "$DIR/$name" ] || { echo "::error::$DIR/$name is missing"; exit 2; }
            FILES+=("$DIR/$name")
        done
        (cd "$DIR" && sha256sum --check --strict --quiet "$SUMS") \
            || { echo "::error::the files in $DIR do not match their $SUMS"; exit 1; }

        info="$(mktemp)"
        release_json "$info"
        state=$?
        case "$state" in
            1)
                gh release create "$RELEASE_TAG" --repo "$REPO" --verify-tag \
                    --title "$RELEASE_TITLE" --notes-file "$NOTES" "${FILES[@]}" \
                    || { echo "::error::gh release create failed"; exit 1; }
                echo "GitHub Release $RELEASE_TAG created with ${#FILES[@]} files."
                emit github_release created
                ;;
            0)
                added=0
                for file in "${FILES[@]}"; do
                    name="$(basename "$file")"
                    digest="$(asset_digest "$info" "$name")"
                    want="sha256:$(sha256sum "$file" | cut -d' ' -f1)"
                    if [ -z "$digest" ]; then
                        gh release upload "$RELEASE_TAG" "$file" --repo "$REPO" \
                            || { echo "::error::cannot attach $name"; exit 1; }
                        echo "$name: attached to the existing release."
                        added=$((added + 1))
                    elif [ "$digest" = "$want" ] || [ "$digest" = missing ]; then
                        echo "$name: already attached."
                    else
                        echo "::error::$name is attached as $digest, but this run would publish $want. A published file is never replaced."
                        exit 1
                    fi
                done
                body="$(jq -r '.body // ""' "$info" | tr -d '\r' | sed -e 's/[[:space:]]*$//')"
                want_body="$(tr -d '\r' < "$NOTES" | sed -e 's/[[:space:]]*$//')"
                if [ "$body" != "$want_body" ]; then
                    echo "::warning::the release body differs from $NOTES. It is left as it is: this job never edits a body it did not write."
                fi
                if [ "$added" -gt 0 ]; then emit github_release completed; else emit github_release present; fi
                ;;
            *) exit 1 ;;
        esac
        rm -f "$info"
        ;;

    *)
        echo "usage: publish-github-release.sh resolve <build-dir> <out-dir> | publish <dir>" >&2
        exit 2
        ;;
esac
