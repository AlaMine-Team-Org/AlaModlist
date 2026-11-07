#!/usr/bin/env bash
# =============================================================================
# The build every CI run and every local pre-release check performs, written
# down once: for each Minecraft line the mod is released for (one file per line
# in gradle/mc/), compile both loaders and run the unit tests and the Fabric
# game tests (all part of `build`), then the NeoForge game tests, which `build`
# does not run.
#
# A client-only mod (mod_environment=client) has no world game tests and no
# `runGameTests` task at all, so that step is left out for it - asking Gradle
# for a task that does not exist would fail every release.
#
# One build per line, never one for all: the jars of two lines are not
# interchangeable even from identical source (the shared code is compiled
# against each line's own Minecraft), so a line that was not built was not
# tested either.
#
# Usage: ci-build.sh [--release] [--mc LINE] [tree]
#   --release  serve nothing from the build cache. A test restored from the
#              cache is a test that did not run against the tree being shipped.
#   --mc LINE  build one line only (the default is every line of gradle/mc/).
# Exit: 0 green, 1 the build or a test failed, 2 cannot run.
# =============================================================================

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE=0
ONLY=""
TREE="."
while [ "$#" -gt 0 ]; do
    case "$1" in
        --release) RELEASE=1 ;;
        --mc)
            ONLY="${2:-}"
            [[ "$ONLY" =~ ^[0-9]+(\.[0-9]+)+$ ]] || { echo "build: --mc needs a Minecraft version" >&2; exit 2; }
            shift
            ;;
        -*) echo "build: unknown option $1" >&2; exit 2 ;;
        *) TREE="$1" ;;
    esac
    shift
done

cd "$TREE" || { echo "build: cannot enter $TREE" >&2; exit 2; }
[ -f gradlew ] || { echo "build: no gradlew in $TREE" >&2; exit 2; }

if ! FACTS="$(GITHUB_OUTPUT= bash "$HERE/mod-facts.sh" gradle.properties)"; then
    echo "build: the facts in gradle.properties are not usable" >&2
    exit 2
fi
fact() { printf '%s\n' "$FACTS" | sed -n "s/^$1=//p" | head -n 1; }

LINES="$(fact minecraft_lines)"
ENVIRONMENT="$(fact mod_environment)"
if [ -n "$ONLY" ]; then
    case " $LINES " in
        *" $ONLY "*) LINES="$ONLY" ;;
        *) echo "build: $ONLY is not one of the lines: $LINES" >&2; exit 2 ;;
    esac
fi
[ -n "$LINES" ] || { echo "build: no Minecraft line to build" >&2; exit 2; }

FLAGS=(--no-daemon --stacktrace)
[ "$RELEASE" -eq 1 ] && FLAGS+=(--no-build-cache)

# Through sh rather than ./gradlew: the build must not depend on the executable
# bit of a checkout. Whether the repository stores that bit is checked on its
# own, where it can be fixed.
for mc in $LINES; do
    echo "== Minecraft $mc: ./gradlew build -Pmc=$mc ${FLAGS[*]}"
    sh ./gradlew build "-Pmc=$mc" "${FLAGS[@]}" || exit 1

    if [ "$ENVIRONMENT" = client ]; then
        echo "== Minecraft $mc: no NeoForge game tests (client-only mod)"
    else
        echo "== Minecraft $mc: ./gradlew :neoforge:runGameTests -Pmc=$mc ${FLAGS[*]}"
        sh ./gradlew :neoforge:runGameTests "-Pmc=$mc" "${FLAGS[@]}" || exit 1
    fi
done
