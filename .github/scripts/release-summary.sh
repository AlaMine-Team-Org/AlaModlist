#!/usr/bin/env bash
# =============================================================================
# One line per publication target, at the end of every release run - also when
# it failed half-way.
#
# A green run looks the same whether a platform received the files or was left
# out by a variable, so the run says which one happened, target by target. A
# target is a platform, a Minecraft line and a loader; the lines come from the
# working tree, so a new line shows up here without touching this file.
#
# Environment:
#   STEPS_JSON   the whole `steps` context of the publish job (toJSON(steps)).
#                An absent step, or an empty outcome, means the step never ran.
#                Read: gh_release (outcome, outputs.github_release), config
#                (outputs.modrinth / outputs.curseforge), mr_check and cf_check
#                (outputs.<loader>_<line>_exists) and the upload steps
#                mr_<loader>_<line> and cf_<loader>_<line> (outcome); in the
#                step ids a dot of the line is an underscore (cf_fabric_26_2).
# Always exits 0: it reports, it does not judge.
# =============================================================================

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STEPS="${STEPS_JSON:-}"
[ -n "$STEPS" ] || STEPS='{}'

# step <jq-path> -> the value, or empty
step() { printf '%s' "$STEPS" | jq -r "$1 // empty" 2> /dev/null; }

# status_of <decision> <exists> <outcome> <skip variable>
status_of() {
    local decision="$1" exists="$2" outcome="$3" skip_var="$4"
    if [ "$decision" = skip ]; then echo "skipped on purpose ($skip_var=true)"; return; fi
    if [ -z "$decision" ]; then echo "not reached - the configuration step did not finish"; return; fi
    if [ "$exists" = true ]; then echo "already there - not uploaded again"; return; fi
    case "$outcome" in
        success) echo "uploaded" ;;
        failure) echo "FAILED - read the step log" ;;
        cancelled) echo "cancelled" ;;
        *) echo "not reached" ;;
    esac
}

case "$(step '.gh_release.outcome')" in
    success)
        case "$(step '.gh_release.outputs.github_release')" in
            created) gh_status="created" ;;
            completed) gh_status="completed - missing files attached" ;;
            *) gh_status="already complete" ;;
        esac
        ;;
    failure) gh_status="FAILED - read the step log" ;;
    *) gh_status="not reached" ;;
esac

modrinth="$(step '.config.outputs.modrinth')"
curseforge="$(step '.config.outputs.curseforge')"

rows=("GitHub Release|$gh_status")
if targets="$(bash "$HERE/release-targets.sh" . 2> /dev/null)"; then
    while IFS=$'\t' read -r line loader _jar _type; do
        [ -n "$line" ] || continue
        key="${loader}_${line//./_}"
        [ "$loader" = fabric ] && label=Fabric || label=NeoForge
        rows+=("Modrinth $label $line|$(status_of "$modrinth" "$(step ".mr_check.outputs.${key}_exists")" "$(step ".mr_${key}.outcome")" SKIP_MODRINTH)")
    done <<< "$targets"
    while IFS=$'\t' read -r line loader _jar _type; do
        [ -n "$line" ] || continue
        key="${loader}_${line//./_}"
        [ "$loader" = fabric ] && label=Fabric || label=NeoForge
        rows+=("CurseForge $label $line|$(status_of "$curseforge" "$(step ".cf_check.outputs.${key}_exists")" "$(step ".cf_${key}.outcome")" SKIP_CURSEFORGE)")
    done <<< "$targets"
else
    rows+=("Modrinth, CurseForge|the release targets of the tree could not be read")
fi

echo "Publication targets:"
for row in "${rows[@]}"; do
    printf '  %-26s %s\n' "${row%%|*}" "${row#*|}"
done

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    {
        echo "### Publication targets"
        echo
        echo "| Target | Result |"
        echo "|---|---|"
        for row in "${rows[@]}"; do
            echo "| ${row%%|*} | ${row#*|} |"
        done
    } >> "$GITHUB_STEP_SUMMARY"
fi
exit 0
