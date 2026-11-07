#!/usr/bin/env bash
# =============================================================================
# The facts a release needs, read from gradle.properties - the one place they
# are declared. Nothing in CI keeps its own copy of the version, the mod id or
# where the mod runs.
#
# Prints key=value lines. When GITHUB_OUTPUT is set they are also appended
# there, so a workflow step can simply run this script.
#
#   version                  X.Y.Z
#   mod_id                   the mod id
#   mod_name                 the display name
#   minecraft_version        the Minecraft version of the default line (`mc` in gradle.properties)
#   minecraft_lines          every line the release is built for, oldest first, space-separated
#   java_version             the Java release to build with
#   mod_environment          '*' (client and server) or 'client'
#   license                  SPDX identifier
#   modrinth_environment     the Modrinth version environment derived from it
#   curseforge_environments  the CurseForge environment tags derived from it
#
# minecraft_version is the one fact that may live elsewhere: a mod with Minecraft lines
# keeps `mc=<line>` in gradle.properties and the versions of the line in
# gradle/mc/<line>.properties next to it. It is read from gradle.properties when it is
# declared there, otherwise from the file of the line `mc` names.
#
# minecraft_lines is one word per file in gradle/mc/: adding a line is adding a file, and nothing in
# CI names a line. Every file must be named after the version it declares.
#
# Usage: mod-facts.sh [gradle.properties]
# Exit:  0 printed, 1 a fact is missing or invalid, 2 the file cannot be read.
# =============================================================================

set -uo pipefail

FILE="${1:-gradle.properties}"

[ -r "$FILE" ] || { echo "facts: cannot read $FILE" >&2; exit 2; }

# Gradle reads the file as ISO-8859-1, so a UTF-8 character reaches the jar
# double-encoded and the first place anybody sees it is a store page.
ascii_only() {
    if LC_ALL=C grep -nP '[^\x00-\x7F]' "$1" > /dev/null 2>&1; then
        echo "facts: $1 contains non-ASCII characters; every value there must be ASCII:" >&2
        LC_ALL=C grep -nP '[^\x00-\x7F]' "$1" | sed 's/^/    line /' >&2
        exit 1
    fi
}
ascii_only "$FILE"

# prop_in <file> <key> - the first value of a key in a properties file.
prop_in() {
    sed -n -E "s/^[[:space:]]*$2[[:space:]]*[=:][[:space:]]*//p" "$1" | head -n 1 \
        | tr -d '\r' | sed 's/[[:space:]]*$//'
}
prop() { prop_in "$FILE" "$1"; }

bad=0
fail() { echo "facts: $1" >&2; bad=1; }

version="$(prop version)"
mod_id="$(prop mod_id)"
mod_name="$(prop mod_name)"
minecraft_version="$(prop minecraft_version)"
if [ -z "$minecraft_version" ]; then
    # Not declared here: the line file named by `mc` holds it.
    mc_line="$(prop mc)"
    if [ -z "$mc_line" ]; then
        echo "facts: $FILE declares neither minecraft_version nor mc" >&2
        exit 1
    fi
    if ! [[ "$mc_line" =~ ^[0-9]+(\.[0-9]+)+$ ]]; then
        echo "facts: mc '$mc_line' in $FILE is not a version" >&2
        exit 1
    fi
    mc_file="$(dirname "$FILE")/gradle/mc/$mc_line.properties"
    if [ ! -r "$mc_file" ]; then
        echo "facts: mc=$mc_line, but $mc_file cannot be read" >&2
        exit 2
    fi
    ascii_only "$mc_file"
    minecraft_version="$(prop_in "$mc_file" minecraft_version)"
    if [ "$minecraft_version" != "$mc_line" ]; then
        echo "facts: $mc_file declares minecraft_version '$minecraft_version', not '$mc_line'" >&2
        exit 1
    fi
fi
# Every Minecraft line the mod is released for. A mod without gradle/mc/ has the one line it declares.
minecraft_lines=""
mc_dir="$(dirname "$FILE")/gradle/mc"
if [ -d "$mc_dir" ]; then
    while IFS= read -r mc_name; do
        [ -n "$mc_name" ] || continue
        mc_name="${mc_name%.properties}"
        if ! [[ "$mc_name" =~ ^[0-9]+(\.[0-9]+)+$ ]]; then
            echo "facts: gradle/mc/$mc_name.properties is not named after a Minecraft version" >&2
            exit 1
        fi
        ascii_only "$mc_dir/$mc_name.properties"
        declared="$(prop_in "$mc_dir/$mc_name.properties" minecraft_version)"
        if [ "$declared" != "$mc_name" ]; then
            echo "facts: gradle/mc/$mc_name.properties declares minecraft_version '$declared', not '$mc_name'" >&2
            exit 1
        fi
        minecraft_lines="${minecraft_lines:+$minecraft_lines }$mc_name"
    done < <(cd "$mc_dir" && ls -- *.properties 2> /dev/null | sort -V)
fi
[ -n "$minecraft_lines" ] || minecraft_lines="$minecraft_version"
case " $minecraft_lines " in
    *" $minecraft_version "*) ;;
    *) echo "facts: the default line $minecraft_version is not among the lines: $minecraft_lines" >&2; exit 1 ;;
esac

java_version="$(prop java_version)"
mod_environment="$(prop mod_environment)"
license="$(prop license)"

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version must be X.Y.Z, got '$version'"
[[ "$mod_id" =~ ^[a-z][a-z0-9_]{1,63}$ ]] || fail "mod_id '$mod_id' is not a valid mod id"
[ -n "$mod_name" ] || fail "mod_name is empty"
case "$mod_name" in *\\*) fail "mod_name contains a backslash; escapes are not decoded here" ;; esac
[[ "$minecraft_version" =~ ^[0-9]+(\.[0-9]+)+$ ]] || fail "minecraft_version '$minecraft_version' is not a version"
[[ "$java_version" =~ ^[0-9]+$ ]] || fail "java_version '$java_version' is not a Java release number"
[ -n "$license" ] || fail "license is empty"

# One property decides where the mod runs; the platforms' vocabularies are
# derived from it here and nowhere else. Anything new is refused rather than
# guessed: a wrong environment puts the mod in front of the wrong players.
case "$mod_environment" in
    '*')
        modrinth_environment=client_and_server
        curseforge_environments="client server"
        ;;
    client)
        modrinth_environment=client_only
        curseforge_environments="client"
        ;;
    *)
        fail "mod_environment must be '*' or 'client', got '$mod_environment' - extend the mapping in mod-facts.sh deliberately"
        modrinth_environment=""
        curseforge_environments=""
        ;;
esac

[ "$bad" -eq 0 ] || exit 1

emit() {
    printf '%s=%s\n' "$1" "$2"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"
    fi
}

emit version "$version"
emit mod_id "$mod_id"
emit mod_name "$mod_name"
emit minecraft_version "$minecraft_version"
emit minecraft_lines "$minecraft_lines"
emit java_version "$java_version"
emit mod_environment "$mod_environment"
emit license "$license"
emit modrinth_environment "$modrinth_environment"
emit curseforge_environments "$curseforge_environments"
