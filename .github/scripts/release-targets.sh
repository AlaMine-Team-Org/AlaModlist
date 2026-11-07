#!/usr/bin/env bash
# =============================================================================
# The release targets of a tree: one per Minecraft line and loader. The one
# place that says which jars a release consists of, so that the build, the
# GitHub Release, both platforms and the post-release check can never disagree
# about it.
#
# A release is every line in gradle/mc/ times {fabric, neoforge}: one version
# number, one set of notes, a jar per line and loader. Adding a line is adding
# a file there - and its rows in curseforge-versions.txt and the steps of
# release.yml, which check-release-targets.sh (private repository) insists on.
#
# Usage:
#   release-targets.sh [tree]
#       prints "<line><TAB><loader><TAB><jar name><TAB><release|beta>" for every
#       target, oldest line first. Needs gradle.properties in the tree.
#   release-targets.sh --type <line> <loader> [tree]
#       prints release or beta for one target. A NeoForge file whose NeoForge
#       is only an alpha or beta is published as a beta; everything else as a
#       release.
# Exit: 0 printed, 1 the facts or a line file are unusable, 2 bad usage.
# =============================================================================

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

TYPE_ONLY=0
LINE=""
LOADER=""
TREE="."
if [ "${1:-}" = "--type" ]; then
    TYPE_ONLY=1
    LINE="${2:-}"
    LOADER="${3:-}"
    TREE="${4:-.}"
    [[ "$LINE" =~ ^[0-9]+(\.[0-9]+)+$ ]] && { [ "$LOADER" = fabric ] || [ "$LOADER" = neoforge ]; } \
        || { echo "usage: release-targets.sh --type <line> <fabric|neoforge> [tree]" >&2; exit 2; }
else
    TREE="${1:-.}"
fi

cd "$TREE" || { echo "targets: cannot enter $TREE" >&2; exit 2; }
if ! FACTS="$(GITHUB_OUTPUT= bash "$HERE/mod-facts.sh" gradle.properties)"; then
    echo "targets: the facts in gradle.properties are not usable" >&2
    exit 1
fi
fact() { printf '%s\n' "$FACTS" | sed -n "s/^$1=//p" | head -n 1; }

MOD_ID="$(fact mod_id)"
VERSION="$(fact version)"
LINES="$(fact minecraft_lines)"

# type_of <line> <loader>
type_of() {
    local line="$1" loader="$2" neoforge
    if [ "$loader" = neoforge ]; then
        neoforge="$(sed -n -E 's/^[[:space:]]*neoforge_version[[:space:]]*[=:][[:space:]]*//p' "gradle/mc/$line.properties" 2> /dev/null \
            | head -n 1 | tr -d '\r' | sed 's/[[:space:]]*$//')"
        case "$neoforge" in
            *-beta* | *-alpha*) echo beta; return ;;
        esac
    fi
    echo release
}

if [ "$TYPE_ONLY" -eq 1 ]; then
    case " $LINES " in
        *" $LINE "*) type_of "$LINE" "$LOADER" ;;
        *) echo "targets: $LINE is not one of the lines: $LINES" >&2; exit 1 ;;
    esac
    exit 0
fi

for line in $LINES; do
    for loader in fabric neoforge; do
        printf '%s\t%s\t%s\t%s\n' "$line" "$loader" "$MOD_ID-$loader-$line-$VERSION.jar" "$(type_of "$line" "$loader")"
    done
done
