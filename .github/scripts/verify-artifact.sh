#!/usr/bin/env bash
# =============================================================================
# Looks inside a built jar before anyone downloads it.
#
# The file name, the store label and the metadata inside the jar are three
# separate claims about one file. This checks that they agree, and that the jar
# carries what a player's launcher reads first:
#
#   - the name is <mod_id>-<loader>-<minecraft>-<version>.jar (the modpack that
#     consumes our releases matches on exactly that shape);
#   - the loader's own metadata is inside and the other loader's is not;
#   - the metadata carries this mod id, this version, this license and, for
#     Fabric, the environment gradle.properties declares;
#   - the Minecraft range the jar declares is the one of the line it is
#     published for (gradle/mc/<line>.properties): a jar built for 26.2 that
#     went out under 26.3, or the other way round, is refused here;
#   - nothing in it is an unexpanded placeholder or double-encoded text;
#   - the icon it declares, and every language file the sources ship, are in it.
#
# Usage: verify-artifact.sh <fabric|neoforge> <line> [tree] [jar]
#        line is the Minecraft line (26.2, 26.3, ...) the jar is published for;
#        tree defaults to the current directory; the jar to the one the build
#        writes into <tree>/<loader>/build/libs/.
# Exit:  0 the jar is what it claims to be, 1 it is not, 2 cannot check.
# =============================================================================

set -uo pipefail

LOADER="${1:-}"
MC="${2:-}"
TREE="${3:-.}"
JAR="${4:-}"

case "$LOADER" in
    fabric | neoforge) ;;
    *) echo "usage: verify-artifact.sh <fabric|neoforge> <line> [tree] [jar]" >&2; exit 2 ;;
esac
[[ "$MC" =~ ^[0-9]+(\.[0-9]+)+$ ]] || { echo "usage: verify-artifact.sh <fabric|neoforge> <line> [tree] [jar]" >&2; exit 2; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export LC_ALL=C

for tool in unzip jq; do
    command -v "$tool" > /dev/null 2>&1 || { echo "artifact: $tool is not on PATH" >&2; exit 2; }
done

cd "$TREE" || { echo "artifact: cannot enter $TREE" >&2; exit 2; }

if ! FACTS="$(GITHUB_OUTPUT= bash "$HERE/mod-facts.sh" gradle.properties)"; then
    echo "artifact: the facts in gradle.properties are not usable" >&2
    exit 2
fi
fact() { printf '%s\n' "$FACTS" | sed -n "s/^$1=//p" | head -n 1; }

VERSION="$(fact version)"
MOD_ID="$(fact mod_id)"
LINES="$(fact minecraft_lines)"
LICENSE="$(fact license)"
ENVIRONMENT="$(fact mod_environment)"

case " $LINES " in
    *" $MC "*) ;;
    *) echo "artifact: $MC is not one of the lines of this mod: $LINES" >&2; exit 2 ;;
esac
LINE_FILE="gradle/mc/$MC.properties"
[ -r "$LINE_FILE" ] || { echo "artifact: cannot read $LINE_FILE" >&2; exit 2; }
# The line's own keys, read the way mod-facts.sh reads gradle.properties.
line_prop() { sed -n -E "s/^[[:space:]]*$1[[:space:]]*[=:][[:space:]]*//p" "$LINE_FILE" | head -n 1 | tr -d '\r' | sed 's/[[:space:]]*$//'; }

NAME="$MOD_ID-$LOADER-$MC-$VERSION.jar"
[ -n "$JAR" ] || JAR="$LOADER/build/libs/$NAME"

FAIL=0
bad() { echo "FAIL - $1"; FAIL=1; }
ok() { echo "  ok   $1"; }

if [ ! -f "$JAR" ]; then
    echo "FAIL - the jar is missing: $JAR"
    ls -la "$(dirname "$JAR")" 2> /dev/null | sed 's/^/       /'
    exit 1
fi

if [ "$(basename "$JAR")" = "$NAME" ]; then
    ok "named $NAME"
else
    bad "the jar is named $(basename "$JAR"), expected $NAME"
fi

SIZE="$(wc -c < "$JAR" | tr -d ' ')"
if [ "$SIZE" -lt 4096 ]; then
    bad "the jar is $SIZE bytes - that is not a built mod"
else
    ok "size $((SIZE / 1024)) KiB"
fi

if ! LIST="$(unzip -Z1 "$JAR" 2> /dev/null)"; then
    echo "FAIL - $JAR is not a readable zip archive"
    exit 1
fi
has() { printf '%s\n' "$LIST" | grep -qxF -e "$1"; }

if [ "$LOADER" = fabric ]; then
    META_NAME="fabric.mod.json"
    OTHER_NAME="META-INF/neoforge.mods.toml"
else
    META_NAME="META-INF/neoforge.mods.toml"
    OTHER_NAME="fabric.mod.json"
fi

has "$OTHER_NAME" && bad "the jar carries $OTHER_NAME - it is not a $LOADER artifact"

if ! has "$META_NAME"; then
    bad "$META_NAME is not in the jar"
    echo
    echo "The artifact is not publishable."
    exit 1
fi

META="$(unzip -p "$JAR" "$META_NAME")"

# A property that did not expand is still valid text, so the loader accepts it
# and the store page shows it.
if printf '%s' "$META" | grep -qE '\$\{[A-Za-z_]+\}|\{\{[A-Za-z_]+\}\}'; then
    bad "$META_NAME holds an unexpanded placeholder:"
    printf '%s\n' "$META" | grep -nE '\$\{[A-Za-z_]+\}|\{\{[A-Za-z_]+\}\}' | sed 's/^/       /'
fi

# UTF-8 read as Latin-1 and written back as UTF-8: a lead byte turns into
# C3 82/83 or C3 A2..AF followed by a C2 continuation. Correct UTF-8 text never
# has that shape, however many accented letters it holds.
if printf '%s' "$META" | grep -qP '\xc3[\x82\x83\xa2-\xaf]\xc2[\x80-\xbf]'; then
    bad "$META_NAME looks double-encoded - check gradle.properties for non-ASCII values"
else
    ok "$META_NAME is not double-encoded"
fi

if [ "$LOADER" = fabric ]; then
    if ! printf '%s' "$META" | jq -e . > /dev/null 2>&1; then
        bad "fabric.mod.json is not valid JSON"
    else
        field() { printf '%s' "$META" | jq -r "$1 // empty"; }
        [ "$(field .id)" = "$MOD_ID" ] && ok "id $MOD_ID" || bad "fabric.mod.json id is '$(field .id)', expected '$MOD_ID'"
        [ "$(field .version)" = "$VERSION" ] && ok "version $VERSION" || bad "fabric.mod.json version is '$(field .version)', expected '$VERSION'"
        [ "$(field .license)" = "$LICENSE" ] && ok "license $LICENSE" || bad "fabric.mod.json license is '$(field .license)', expected '$LICENSE'"
        [ "$(field .environment)" = "$ENVIRONMENT" ] && ok "environment '$ENVIRONMENT'" \
            || bad "fabric.mod.json environment is '$(field .environment)', gradle.properties says '$ENVIRONMENT'"
        # The jar declares the line it was compiled for; the name and the store label only claim it.
        [ "$(field .depends.minecraft)" = "~$MC" ] && ok "depends on Minecraft ~$MC" \
            || bad "fabric.mod.json depends on Minecraft '$(field .depends.minecraft)', a $MC jar must say '~$MC'"
        # tr: a native jq on Windows ends every line with CR, and a path with a CR is not in the jar.
        ICONS="$(printf '%s' "$META" | jq -r 'if (.icon | type) == "string" then .icon elif (.icon | type) == "object" then .icon[] else empty end' | tr -d '\r')"
        if [ -z "$ICONS" ]; then
            bad "fabric.mod.json declares no icon"
        else
            while IFS= read -r icon; do
                has "$icon" && ok "icon $icon" || bad "fabric.mod.json declares the icon $icon, which is not in the jar"
            done <<< "$ICONS"
        fi
    fi
else
    toml() { printf '%s\n' "$META" | sed -n -E "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\"([^\"]*)\".*/\\1/p"; }
    if toml modId | grep -qxF -e "$MOD_ID"; then ok "modId $MOD_ID"; else bad "neoforge.mods.toml declares no modId \"$MOD_ID\""; fi
    if toml version | grep -qxF -e "$VERSION"; then ok "version $VERSION"; else bad "neoforge.mods.toml does not carry version \"$VERSION\""; fi
    if toml license | grep -qxF -e "$LICENSE"; then ok "license $LICENSE"; else bad "neoforge.mods.toml license is not \"$LICENSE\""; fi
    case "$ENVIRONMENT" in
        '*') SIDE=BOTH ;;
        client) SIDE=CLIENT ;;
        *) SIDE="" ;;
    esac
    # The range of the minecraft dependency: the line this jar is published for.
    MC_RANGE="$(printf '%s\n' "$META" | awk '
        /^\[\[dependencies\./ { inblock = 1; mod = ""; range = ""; next }
        /^\[/ { inblock = 0 }
        inblock && /^[[:space:]]*modId[[:space:]]*=/ { mod = $0; sub(/^[^"]*"/, "", mod); sub(/".*$/, "", mod) }
        inblock && /^[[:space:]]*versionRange[[:space:]]*=/ { range = $0; sub(/^[^"]*"/, "", range); sub(/".*$/, "", range) }
        inblock && mod == "minecraft" && range != "" { print range; exit }
    ')"
    WANT_RANGE="$(line_prop minecraft_version_range)"
    if [ -z "$WANT_RANGE" ]; then
        bad "$LINE_FILE declares no minecraft_version_range"
    elif [ "$MC_RANGE" = "$WANT_RANGE" ]; then
        ok "depends on Minecraft $MC_RANGE"
    else
        bad "neoforge.mods.toml depends on Minecraft '$MC_RANGE', a $MC jar must say '$WANT_RANGE'"
    fi
    SIDES="$(toml side | sort -u)"
    if [ -z "$SIDES" ]; then
        echo "  note neoforge.mods.toml declares no dependency side - nothing to compare"
    elif [ "$SIDES" = "$SIDE" ]; then
        ok "dependency side $SIDE"
    else
        bad "neoforge.mods.toml dependency sides are '$(printf '%s' "$SIDES" | tr '\n' ' ')', expected only $SIDE for mod_environment '$ENVIRONMENT'"
    fi
    for key in logoFile iconFile; do
        for icon in $(toml "$key"); do
            if has "$icon" || has "assets/$MOD_ID/$icon"; then ok "$key $icon"; else bad "neoforge.mods.toml $key is $icon, which is not in the jar"; fi
        done
    done
fi

if printf '%s\n' "$LIST" | grep -qE "^assets/$MOD_ID/icon[^/]*\.png$"; then
    ok "an icon under assets/$MOD_ID/"
else
    bad "no icon*.png under assets/$MOD_ID/ in the jar"
fi

# Every language the sources ship must reach the jar. The list comes from the
# tree, not from this script: a hard-coded list goes stale the day a language is
# added, and then checks everything except the new one.
LANGS="$(find . -path '*/build' -prune -o -type f -path "*/src/main/resources/assets/$MOD_ID/lang/*.json" -print 2> /dev/null \
    | sed 's|.*/||' | sort -u)"
if [ -z "$LANGS" ]; then
    bad "no language files under */src/main/resources/assets/$MOD_ID/lang/ - nothing proves the jar is translated"
else
    count=0
    while IFS= read -r lang; do
        if has "assets/$MOD_ID/lang/$lang"; then
            count=$((count + 1))
        else
            bad "the language file $lang is missing from the jar"
        fi
    done <<< "$LANGS"
    ok "$count language file(s) present"
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "The $LOADER artifact is what it claims to be."
    exit 0
fi
echo "The $LOADER artifact is not publishable."
exit 1
