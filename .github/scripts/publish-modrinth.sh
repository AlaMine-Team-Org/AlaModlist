#!/usr/bin/env bash
# =============================================================================
# Modrinth: which versions of this release exist already, and uploading one.
#
# One release is one Modrinth version per loader and Minecraft line,
# X.Y.Z+<loader>-<line> (X.Y.Z+fabric-26.2, X.Y.Z+neoforge-26.3, ...), because
# Modrinth attaches dependencies and game versions to a version rather than to
# a file: the Fabric jar requires Fabric API, the NeoForge jar does not, and
# each jar supports exactly one line. The line is part of the number on
# purpose: Modrinth does not enforce unique version numbers, so the existence
# check below is the only thing keeping a re-run from publishing a duplicate,
# and a number without the line would let the first line's version stand in for
# the second's.
#
# The existence check comes from the official API, and it is asked again before
# every retry: an attempt whose answer was lost on the way back may still have
# created the version.
#
# Usage:
#   publish-modrinth.sh --check
#       prints <loader>_<line>_exists=true|false for every target (a dot in the
#       line becomes an underscore: fabric_26_2_exists), also into
#       GITHUB_OUTPUT. Needs MODRINTH_PROJECT_ID and VERSION; reads the targets
#       from the working tree.
#   publish-modrinth.sh <fabric|neoforge> <line>
#       uploads that jar. Needs MODRINTH_TOKEN, MODRINTH_PROJECT_ID, VERSION,
#       MOD_ID, MOD_NAME, MODRINTH_ENVIRONMENT, JAR_DIR and NOTES.
# Exit: 0 done, 1 the platform refused or kept failing, 2 bad input.
# =============================================================================

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Overridable only to point a rehearsal at a local stand-in; the workflow never sets it.
API="${MODRINTH_API:-https://api.modrinth.com/v2}"
UA="${GITHUB_REPOSITORY:-mod}-release/1.0"
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

label_of() { [ "$1" = fabric ] && echo Fabric || echo NeoForge; }

# list_versions <out-file> -> 0 on HTTP 200, 1 otherwise (the body is printed)
list_versions() {
    local out="$1" code attempt
    for attempt in 1 2 3; do
        code="$(curl -sS --connect-timeout 20 --max-time 120 -o "$out" -w '%{http_code}' \
            -H "User-Agent: $UA" "$API/project/$MODRINTH_PROJECT_ID/version")" || code=000
        [ "$code" = 200 ] && return 0
        echo "::warning::Modrinth version list: HTTP $code (attempt $attempt)"
        cat "$out" 2> /dev/null || true
        echo
        case "$code" in 404) return 1 ;; esac
        [ "$attempt" -lt 3 ] && sleep 10
    done
    return 1
}

# exists <loader> <line> <versions-file> -> prints true|false; any status counts, because
# a version number that exists in any state cannot be created again.
exists() {
    jq -r --arg v "$VERSION+$1-$2" 'if type == "array" then (any(.[]; .version_number == $v) | tostring) else "error" end' "$3"
}

case "$MODE" in
    --check)
        need MODRINTH_PROJECT_ID VERSION
        tmp="$(mktemp)"
        if ! list_versions "$tmp"; then
            echo "::error::cannot read the Modrinth versions of project $MODRINTH_PROJECT_ID - stopping before any upload rather than risking a second copy"
            exit 1
        fi
        targets="$(bash "$HERE/release-targets.sh" .)" || { echo "::error::cannot read the release targets"; exit 1; }
        while IFS=$'\t' read -r line loader _jar _type; do
            [ -n "$line" ] || continue
            answer="$(exists "$loader" "$line" "$tmp")"
            case "$answer" in
                true | false) emit "${loader}_${line//./_}_exists" "$answer" ;;
                *) echo "::error::the Modrinth answer is not a version list"; exit 1 ;;
            esac
        done <<< "$targets"
        rm -f "$tmp"
        ;;

    fabric | neoforge)
        LOADER="$MODE"
        MC="${2:-}"
        [[ "$MC" =~ ^[0-9]+(\.[0-9]+)+$ ]] || { echo "usage: publish-modrinth.sh <fabric|neoforge> <line>" >&2; exit 2; }
        need MODRINTH_TOKEN MODRINTH_PROJECT_ID VERSION MOD_ID MOD_NAME MODRINTH_ENVIRONMENT JAR_DIR NOTES
        JAR="$JAR_DIR/$MOD_ID-$LOADER-$MC-$VERSION.jar"
        [ -f "$JAR" ] || { echo "::error::no such jar: $JAR"; exit 2; }
        [ -s "$NOTES" ] || { echo "::error::the notes file $NOTES is missing or empty"; exit 2; }
        VERSION_TYPE="$(bash "$HERE/release-targets.sh" --type "$MC" "$LOADER" .)" \
            || { echo "::error::cannot tell the release type of $LOADER $MC"; exit 2; }

        LABEL="$(label_of "$LOADER")"
        if [ "$LOADER" = fabric ]; then
            # Fabric API. Add further required projects here, per loader.
            DEPENDENCIES='[{"project_id": "P7dR8mSH", "dependency_type": "required"}]'
        else
            DEPENDENCIES='[]'
        fi

        # --rawfile keeps the notes byte for byte. Passing multi-line text through
        # a workflow output folds it into one line, which is how a changelog once
        # arrived as a single paragraph of escaped Markdown.
        DATA="$(jq -n \
            --arg name "$MOD_NAME $VERSION [$LABEL $MC]" \
            --arg version_number "$VERSION+$LOADER-$MC" \
            --rawfile changelog "$NOTES" \
            --argjson dependencies "$DEPENDENCIES" \
            --arg game_version "$MC" \
            --arg version_type "$VERSION_TYPE" \
            --arg loader "$LOADER" \
            --arg project_id "$MODRINTH_PROJECT_ID" \
            --arg environment "$MODRINTH_ENVIRONMENT" \
            '{
              name: $name,
              version_number: $version_number,
              changelog: $changelog,
              dependencies: $dependencies,
              game_versions: [$game_version],
              version_type: $version_type,
              loaders: [$loader],
              featured: false,
              status: "listed",
              project_id: $project_id,
              file_parts: ["file"],
              primary_file: "file",
              environment: $environment
            }')" || { echo "::error::could not build the Modrinth request"; exit 2; }

        RESP="$(mktemp)"
        LIST="$(mktemp)"
        trap 'rm -f "$RESP" "$LIST"' EXIT
        for attempt in 1 2 3 4; do
            code="$(curl -sS --connect-timeout 20 --max-time 300 -o "$RESP" -w '%{http_code}' \
                -X POST "$API/version" \
                -H "Authorization: $MODRINTH_TOKEN" \
                -H "User-Agent: $UA" \
                --form-string "data=$DATA" \
                -F "file=@$JAR")" || code=000

            if [ "$code" -ge 200 ] 2> /dev/null && [ "$code" -lt 300 ]; then
                echo "Modrinth $LABEL $MC upload OK (HTTP $code)"
                jq -r '"version id: \(.id)"' "$RESP" 2> /dev/null || true
                exit 0
            fi

            echo "::warning::Modrinth $LABEL $MC attempt $attempt: HTTP $code; the response was:"
            cat "$RESP" 2> /dev/null || true
            echo

            # Did an earlier attempt create it after all? Then this is done, and
            # another POST would only be refused or, worse, accepted twice.
            if list_versions "$LIST" && [ "$(exists "$LOADER" "$MC" "$LIST")" = true ]; then
                echo "Modrinth $VERSION+$LOADER-$MC exists now - an earlier attempt created it."
                exit 0
            fi

            # A 4xx other than rate limiting is an answer about the request itself;
            # sending the same request again cannot change it.
            case "$code" in
                4[0-9][0-9]) [ "$code" = 429 ] || { echo "::error::Modrinth refused the $LABEL $MC upload (HTTP $code)."; exit 1; } ;;
            esac
            [ "$attempt" -lt 4 ] && sleep 20
        done
        echo "::error::Modrinth $LABEL $MC upload failed after 4 attempts (last HTTP $code)."
        exit 1
        ;;

    *)
        echo "usage: publish-modrinth.sh --check | <fabric|neoforge> <line>" >&2
        exit 2
        ;;
esac
