#!/usr/bin/env bash
# =============================================================================
# CurseForge: checking the pinned version ids, finding files this release has
# already put there, and uploading one.
#
# One release is one CurseForge file per loader and Minecraft line, displayed as
# "<Name> X.Y.Z [<Loader> <line>]". The upload step of each carries the same
# words in its name ("Publish Fabric 26.2 to CurseForge"), because the second
# witness below reads the steps of earlier runs by name.
#
# CurseForge's upload API can create a file but cannot list or edit one, so
# "is it there already" has two witnesses, asked in this order:
#
#   1. the history of this workflow - an upload step for this tag that
#      succeeded in any earlier run or attempt. CurseForge accepted that file:
#      the step only succeeds on a 2xx answer. This is what keeps a re-run from
#      uploading a second copy, and it does not lag.
#   2. the public cfwidget index, which also sees files uploaded by hand. It
#      lags from minutes to hours, so it can only ever add a "yes"; when it
#      cannot be read, the run history above still stands.
#
# Usage:
#   publish-curseforge.sh --resolve-ids
#       checks every pinned id against the name CurseForge reports for it and
#       prints <loader>_<line>_ids=[...] for every target (a dot in the line
#       becomes an underscore: fabric_26_2_ids). Needs CURSEFORGE_TOKEN and
#       CURSEFORGE_ENVIRONMENTS ("client server" or "client"); reads the lines
#       from the working tree and refuses a line without a row in
#       curseforge-versions.txt, and a row without a line.
#   publish-curseforge.sh --check
#       prints <loader>_<line>_exists=true|false for every target. Needs
#       CURSEFORGE_PROJECT_ID, VERSION, MOD_ID, MOD_NAME, GH_TOKEN,
#       GITHUB_REPOSITORY, RELEASE_TAG_SHA, GITHUB_RUN_ID and GITHUB_RUN_ATTEMPT.
#   publish-curseforge.sh <fabric|neoforge> <line>
#       uploads that jar. Needs CURSEFORGE_TOKEN, CURSEFORGE_PROJECT_ID,
#       VERSION, MOD_ID, MOD_NAME, JAR_DIR, NOTES and GAME_VERSION_IDS.
# Exit: 0 done, 1 the platform refused or cannot be read, 2 bad input.
# =============================================================================

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_FILE="$HERE/../curseforge-versions.txt"
# Overridable only to point a rehearsal at a local stand-in; the workflow never sets them.
CF="${CURSEFORGE_API:-https://minecraft.curseforge.com/api}"
WIDGET="${CFWIDGET_API:-https://api.cfwidget.com}"
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

# pinned <key> -> "<id> <name>" from the data file
pinned() {
    awk -v key="$1" '!/^[[:space:]]*(#|$)/ && $1 == key { $1 = ""; sub(/^ +/, ""); print; exit }' "$DATA_FILE"
}

# targets -> "<line><TAB><loader><TAB><jar><TAB><type>" lines of the working tree
targets() {
    bash "$HERE/release-targets.sh" . || { echo "::error::cannot read the release targets"; exit 1; }
}

case "$MODE" in
    --resolve-ids)
        need CURSEFORGE_TOKEN CURSEFORGE_ENVIRONMENTS
        [ -f "$DATA_FILE" ] || { echo "::error::no id data file at $DATA_FILE"; exit 2; }

        all_targets="$(targets)" || exit 1
        lines="$(printf '%s\n' "$all_targets" | cut -f1 | sort -uV | tr '\n' ' ' | sed 's/ *$//')"
        [ -n "$lines" ] || { echo "::error::the tree has no Minecraft line"; exit 1; }

        # Both directions: a line without a row would be published with nothing to tag it by, and a row
        # without a line is a stale id that nothing would ever check again.
        rows="$(awk '!/^[[:space:]]*(#|$)/ && $1 ~ /^minecraft\./ { sub(/^minecraft\./, "", $1); print $1 }' "$DATA_FILE" | sort -uV | tr '\n' ' ' | sed 's/ *$//')"
        if [ "$rows" != "$lines" ]; then
            echo "::error::curseforge-versions.txt has Minecraft rows for '$rows', the tree is released for '$lines'. One row per line, no more and no fewer. Nothing has been uploaded."
            exit 1
        fi

        keys="fabric neoforge $CURSEFORGE_ENVIRONMENTS"
        for line in $lines; do keys="$keys minecraft.$line"; done
        declare -A ID NAME
        for key in $keys; do
            row="$(pinned "$key")"
            id="${row%% *}"
            name="${row#* }"
            [[ "$id" =~ ^[0-9]+$ ]] || { echo "::error::curseforge-versions.txt has no numeric id for '$key'"; exit 2; }
            ID[$key]="$id"
            NAME[$key]="$name"
        done

        for line in $lines; do
            if [ "${NAME[minecraft.$line]}" != "$line" ]; then
                echo "::error::curseforge-versions.txt names the row minecraft.$line '${NAME[minecraft.$line]}', not '$line'. Look up the id of '$line' in the list below and fix the row."
                exit 1
            fi
        done

        versions="$(mktemp)"
        code=000
        for attempt in 1 2 3; do
            code="$(curl -sS --connect-timeout 20 --max-time 120 -o "$versions" -w '%{http_code}' \
                -H "X-Api-Token: $CURSEFORGE_TOKEN" "$CF/game/versions")" || code=000
            [ "$code" = 200 ] && break
            echo "::warning::CurseForge game versions: HTTP $code (attempt $attempt)"
            case "$code" in 401 | 403) break ;; esac
            [ "$attempt" -lt 3 ] && sleep 10
        done
        if [ "$code" != 200 ]; then
            echo "::error::cannot read the CurseForge game versions (HTTP $code) - is CURSEFORGE_TOKEN a valid upload token?"
            exit 1
        fi

        fail=0
        for key in $keys; do
            actual="$(jq -r --argjson id "${ID[$key]}" '[.[] | select(.id == $id) | .name][0] // ""' "$versions")"
            if [ "$actual" = "${NAME[$key]}" ]; then
                echo "  id ${ID[$key]} = $actual"
            else
                echo "::error::CurseForge id ${ID[$key]} is named '$actual', curseforge-versions.txt expects '${NAME[$key]}' ($key). Nothing has been uploaded; fix the data file."
                fail=1
            fi
        done
        if [ "$fail" -ne 0 ]; then
            echo "Entries CurseForge reports under the expected names, for reference:"
            for key in $keys; do
                jq -r --arg n "${NAME[$key]}" '.[] | select(.name == $n) | "  \(.id)  \(.name)  (type \(.gameVersionTypeID))"' "$versions"
            done
            exit 1
        fi

        envs=""
        for key in $CURSEFORGE_ENVIRONMENTS; do envs="$envs, ${ID[$key]}"; done
        while IFS=$'\t' read -r line loader _jar _type; do
            [ -n "$line" ] || continue
            emit "${loader}_${line//./_}_ids" "[${ID[minecraft.$line]}, ${ID[$loader]}$envs]"
        done <<< "$all_targets"
        rm -f "$versions"
        ;;

    --check)
        need CURSEFORGE_PROJECT_ID VERSION MOD_ID MOD_NAME GH_TOKEN GITHUB_REPOSITORY RELEASE_TAG_SHA GITHUB_RUN_ID GITHUB_RUN_ATTEMPT
        workflow="${GITHUB_WORKFLOW_REF:-release.yml}"
        workflow="${workflow%@*}"
        workflow="${workflow##*/}"

        all_targets="$(targets)" || exit 1

        # Every run of this workflow built from the tagged commit, with the number
        # of attempts each has had.
        if ! runs="$(gh api -X GET "repos/$GITHUB_REPOSITORY/actions/workflows/$workflow/runs" \
            -f head_sha="$RELEASE_TAG_SHA" -f per_page=100 \
            --jq '.workflow_runs[] | "\(.id) \(.run_attempt)"')"; then
            echo "::error::cannot read the run history of $workflow - stopping before any upload rather than risking a second copy"
            exit 1
        fi

        declare -A FOUND
        while IFS=' ' read -r run attempts; do
            [ -n "$run" ] || continue
            for ((n = 1; n <= attempts; n++)); do
                if [ "$run" = "$GITHUB_RUN_ID" ] && [ "$n" -ge "$GITHUB_RUN_ATTEMPT" ]; then
                    continue
                fi
                if ! steps="$(gh api "repos/$GITHUB_REPOSITORY/actions/runs/$run/attempts/$n/jobs" \
                    --jq '.jobs[].steps[]? | select(.conclusion == "success") | .name')"; then
                    echo "::error::cannot read the steps of run $run attempt $n - stopping before any upload"
                    exit 1
                fi
                while IFS=$'\t' read -r line loader _jar _type; do
                    [ -n "$line" ] || continue
                    if printf '%s\n' "$steps" | grep -qxF -e "Publish $(label_of "$loader") $line to CurseForge"; then
                        FOUND["$loader/$line"]="run $run attempt $n"
                    fi
                done <<< "$all_targets"
            done
        done <<< "$runs"

        widget="$(mktemp)"
        widget_ok=0
        for attempt in 1 2 3; do
            code="$(curl -sS --connect-timeout 20 --max-time 120 -o "$widget" -w '%{http_code}' \
                -H "User-Agent: $UA" "$WIDGET/minecraft/mc-mods/$CURSEFORGE_PROJECT_ID")" || code=000
            if [ "$code" = 200 ] && jq -e '.files | type == "array"' "$widget" > /dev/null 2>&1; then
                widget_ok=1
                break
            fi
            # 202: cfwidget has queued the project and answers on a later request.
            [ "$attempt" -lt 3 ] && sleep 20
        done
        if [ "$widget_ok" -eq 0 ]; then
            echo "::warning::cfwidget did not answer (last HTTP $code). Files this workflow uploaded are still recognised from the run history; a file uploaded by hand would not be."
        fi

        while IFS=$'\t' read -r line loader jar _type; do
            [ -n "$line" ] || continue
            label="$(label_of "$loader")"
            display="$MOD_NAME $VERSION [$label $line]"
            out="${loader}_${line//./_}_exists"
            if [ -n "${FOUND["$loader/$line"]:-}" ]; then
                echo "CurseForge $label $line: already uploaded by ${FOUND["$loader/$line"]} - not uploading again."
                emit "$out" true
            elif [ "$widget_ok" -eq 1 ] && [ "$(jq -r --arg n "$jar" --arg d "$display" \
                    'any(.files[]; (.name // "") == $n or (.display // "") == $d) | tostring' "$widget")" = true ]; then
                echo "CurseForge $label $line: listed by cfwidget as '$display' - not uploading again."
                emit "$out" true
            else
                emit "$out" false
            fi
        done <<< "$all_targets"
        rm -f "$widget"
        ;;

    fabric | neoforge)
        LOADER="$MODE"
        MC="${2:-}"
        [[ "$MC" =~ ^[0-9]+(\.[0-9]+)+$ ]] || { echo "usage: publish-curseforge.sh <fabric|neoforge> <line>" >&2; exit 2; }
        LABEL="$(label_of "$LOADER")"
        need CURSEFORGE_TOKEN CURSEFORGE_PROJECT_ID VERSION MOD_ID MOD_NAME JAR_DIR NOTES GAME_VERSION_IDS
        JAR="$JAR_DIR/$MOD_ID-$LOADER-$MC-$VERSION.jar"
        [ -f "$JAR" ] || { echo "::error::no such jar: $JAR"; exit 2; }
        [ -s "$NOTES" ] || { echo "::error::the notes file $NOTES is missing or empty"; exit 2; }
        printf '%s' "$GAME_VERSION_IDS" | jq -e 'type == "array" and length >= 3 and all(.[]; type == "number")' > /dev/null 2>&1 \
            || { echo "::error::GAME_VERSION_IDS is not a list of numeric ids: $GAME_VERSION_IDS"; exit 2; }
        RELEASE_TYPE="$(bash "$HERE/release-targets.sh" --type "$MC" "$LOADER" .)" \
            || { echo "::error::cannot tell the release type of $LOADER $MC"; exit 2; }

        # Markdown, read from the file: a changelog sent without a type renders
        # as plain text, and one passed through a workflow output loses its
        # line breaks.
        METADATA="$(jq -n \
            --rawfile changelog "$NOTES" \
            --arg displayName "$MOD_NAME $VERSION [$LABEL $MC]" \
            --arg releaseType "$RELEASE_TYPE" \
            --argjson gameVersions "$GAME_VERSION_IDS" \
            '{
              changelog: $changelog,
              changelogType: "markdown",
              displayName: $displayName,
              gameVersions: $gameVersions,
              releaseType: $releaseType
            }')" || { echo "::error::could not build the CurseForge request"; exit 2; }

        RESP="$(mktemp)"
        trap 'rm -f "$RESP"' EXIT
        for attempt in 1 2 3 4; do
            # -o and -w, never -f: the body of a refusal is the only place that says
            # why (errorCode 1009, 1021, ...), and -f throws it away.
            code="$(curl -sS --connect-timeout 20 --max-time 300 -o "$RESP" -w '%{http_code}' \
                -X POST "$CF/projects/$CURSEFORGE_PROJECT_ID/upload-file" \
                -H "X-Api-Token: $CURSEFORGE_TOKEN" \
                --form-string "metadata=$METADATA" \
                -F "file=@$JAR")" || code=000

            if [ "$code" -ge 200 ] 2> /dev/null && [ "$code" -lt 300 ]; then
                echo "CurseForge $LABEL $MC upload OK (HTTP $code)"
                cat "$RESP" 2> /dev/null || true
                echo
                exit 0
            fi

            echo "::warning::CurseForge $LABEL $MC attempt $attempt: HTTP $code; the response was:"
            cat "$RESP" 2> /dev/null || true
            echo
            case "$code" in
                4[0-9][0-9]) [ "$code" = 429 ] || { echo "::error::CurseForge refused the $LABEL $MC upload (HTTP $code) - read the errorCode above."; exit 1; } ;;
            esac
            [ "$attempt" -lt 4 ] && sleep 20
        done
        echo "::error::CurseForge $LABEL $MC upload failed after 4 attempts (last HTTP $code)."
        exit 1
        ;;

    *)
        echo "usage: publish-curseforge.sh --resolve-ids | --check | <fabric|neoforge> <line>" >&2
        exit 2
        ;;
esac
