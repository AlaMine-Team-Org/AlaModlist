#!/usr/bin/env bash
# =============================================================================
# Decides, for each platform, whether a release publishes there, leaves it out
# on purpose, or must stop because a setting is missing.
#
# The same decision is taken in two places: by the release workflow right
# before anything is uploaded, and by a check run on a developer machine before
# a tag is cut. Both run this file, so the two answers cannot drift - and the
# table in selftest proves the answer before either of them relies on it.
#
# Rules:
#   - SKIP_<PLATFORM> set to the exact word "true" leaves the platform out on
#     purpose. That is the one quiet way past a platform, so it is loud: the
#     workflow warns, and warns harder when a project id or a token is set too
#     (a skip left behind after the project went live).
#   - Otherwise the token and a well-formed project id are both required. A
#     missing one stops the release before anything is published; it is never
#     read as "skip".
#   - Any other SKIP value ("TRUE", "yes", "1") does not skip, and says so.
#
# Usage:
#   release-config.sh --selftest
#   release-config.sh --decide <skip-value> <token:y|n> <id-value> <modrinth|curseforge>
#   release-config.sh --workflow
#       Reads SKIP_MODRINTH, SKIP_CURSEFORGE, MODRINTH_PROJECT_ID,
#       CURSEFORGE_PROJECT_ID, HAS_MODRINTH_TOKEN and HAS_CURSEFORGE_TOKEN
#       ("true"/"false") from the environment. Prints modrinth=publish|skip and
#       curseforge=publish|skip (also into GITHUB_OUTPUT) and exits 1 when a
#       platform is neither configured nor skipped.
# Exit: 0 decided, 1 configuration incomplete, 2 unusable input or failed self-test.
# =============================================================================

set -uo pipefail

# id_state <platform> <value> -> y (usable) | n (absent) | bad (malformed)
id_state() {
    local platform="$1" value="$2"
    [ -n "$value" ] || { echo n; return; }
    case "$platform" in
        modrinth)   [[ "$value" =~ ^[A-Za-z0-9]{6,16}$ ]] && echo y || echo bad ;;
        curseforge) [[ "$value" =~ ^[0-9]{3,10}$ ]] && echo y || echo bad ;;
        *) echo bad ;;
    esac
}

# decide <skip-value> <token:y|n> <id-state:y|n|bad>
# Pure: no I/O, no globals. Verdicts:
#   skip | skip configured | ok | note ok | fix <items> | note fix <items>
# where <items> is a space-separated subset of: token id badid.
decide() {
    local skip="$1" token="$2" id="$3" note="" items=""
    if [ "$skip" = true ]; then
        if [ "$token" = y ] || [ "$id" != n ]; then echo "skip configured"; else echo "skip"; fi
        return
    fi
    case "$skip" in '' | false) ;; *) note="note " ;; esac
    [ "$token" = y ] || items="token"
    case "$id" in
        n) items="${items:+$items }id" ;;
        bad) items="${items:+$items }badid" ;;
    esac
    if [ -z "$items" ]; then echo "${note}ok"; else echo "${note}fix $items"; fi
}

# action <verdict> -> publish | skip | stop
action() {
    case "$1" in
        skip*) echo skip ;;
        *fix*) echo stop ;;
        ok | "note ok") echo publish ;;
        *) echo stop ;;
    esac
}

selftest() {
    local bad=0
    expect() {
        [ "$2" = "$3" ] && return 0
        echo "config: self-test failed: $1 gave '$3', expected '$2'" >&2
        bad=1
    }

    expect "id modrinth 8 chars"      y   "$(id_state modrinth P7dR8mSH)"
    expect "id modrinth slug"         bad "$(id_state modrinth my-mod)"
    expect "id modrinth empty"        n   "$(id_state modrinth '')"
    expect "id curseforge numeric"    y   "$(id_state curseforge 1234567)"
    expect "id curseforge slug"       bad "$(id_state curseforge my-mod)"
    expect "id curseforge empty"      n   "$(id_state curseforge '')"

    expect "decide true n n"          "skip"                  "$(decide true n n)"
    expect "decide true y y"          "skip configured"       "$(decide true y y)"
    expect "decide true n y"          "skip configured"       "$(decide true n y)"
    expect "decide true y n"          "skip configured"       "$(decide true y n)"
    expect "decide true n bad"        "skip configured"       "$(decide true n bad)"
    expect "decide '' y y"            "ok"                    "$(decide '' y y)"
    expect "decide false y y"         "ok"                    "$(decide false y y)"
    expect "decide '' n y"            "fix token"             "$(decide '' n y)"
    expect "decide '' y n"            "fix id"                "$(decide '' y n)"
    expect "decide '' y bad"          "fix badid"             "$(decide '' y bad)"
    expect "decide '' n n"            "fix token id"          "$(decide '' n n)"
    expect "decide '' n bad"          "fix token badid"       "$(decide '' n bad)"
    expect "decide TRUE y y"          "note ok"               "$(decide TRUE y y)"
    expect "decide 1 n n"             "note fix token id"     "$(decide 1 n n)"
    expect "decide yes y bad"         "note fix badid"        "$(decide yes y bad)"

    expect "action skip"              skip    "$(action 'skip')"
    expect "action skip configured"   skip    "$(action 'skip configured')"
    expect "action ok"                publish "$(action 'ok')"
    expect "action note ok"           publish "$(action 'note ok')"
    expect "action fix token"         stop    "$(action 'fix token')"
    expect "action note fix id"       stop    "$(action 'note fix id')"
    expect "action garbage"           stop    "$(action 'something else')"

    unset -f expect
    if [ "$bad" -ne 0 ]; then
        echo "config: refusing to decide anything with a decision that fails its own table." >&2
        return 1
    fi
    return 0
}

emit() {
    printf '%s=%s\n' "$1" "$2"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"
    fi
}

# explain <Label> <platform> <verdict> <skip-value> -> prints annotations
explain() {
    local label="$1" platform="$2" verdict="$3" skip="$4" upper
    upper="$(printf '%s' "$platform" | tr '[:lower:]' '[:upper:]')"
    case "$verdict" in
        "skip")
            echo "::warning::SKIP_${upper}=true - this release will not reach $label."
            ;;
        "skip configured")
            echo "::warning::SKIP_${upper}=true - this release will not reach $label, although a ${label} project id or token is configured. Delete the SKIP_${upper} variable once the project is ready, or the next release goes past it as well."
            ;;
    esac
    case "$verdict" in
        note*) echo "::notice::SKIP_${upper} is '$skip'; only the exact word 'true' leaves $label out." ;;
    esac
    case " $verdict " in *" token "*)
        echo "::error::the ${upper}_TOKEN secret is not available to this job. Add it to the 'release' environment, or set the variable SKIP_${upper}=true to publish without $label on purpose." ;;
    esac
    case " $verdict " in *" id "*)
        echo "::error::the variable ${upper}_PROJECT_ID is not set." ;;
    esac
    case " $verdict " in *" badid "*)
        echo "::error::the variable ${upper}_PROJECT_ID does not look like a $label project id." ;;
    esac
}

MODE="${1:-}"

case "$MODE" in
    --selftest)
        selftest || exit 2
        echo "config: decision table holds."
        ;;
    --decide)
        [ "$#" -eq 5 ] || { echo "usage: release-config.sh --decide <skip> <token:y|n> <id> <platform>" >&2; exit 2; }
        selftest || exit 2
        case "$3" in y | n) ;; *) echo "config: token must be y or n" >&2; exit 2 ;; esac
        decide "$2" "$3" "$(id_state "$5" "$4")"
        ;;
    --workflow)
        selftest || exit 2
        stop=0
        for platform in modrinth curseforge; do
            case "$platform" in
                modrinth)
                    label=Modrinth
                    skip="${SKIP_MODRINTH:-}"
                    has="${HAS_MODRINTH_TOKEN:-false}"
                    id="${MODRINTH_PROJECT_ID:-}"
                    ;;
                curseforge)
                    label=CurseForge
                    skip="${SKIP_CURSEFORGE:-}"
                    has="${HAS_CURSEFORGE_TOKEN:-false}"
                    id="${CURSEFORGE_PROJECT_ID:-}"
                    ;;
            esac
            token=n
            [ "$has" = true ] && token=y
            verdict="$(decide "$skip" "$token" "$(id_state "$platform" "$id")")"
            explain "$label" "$platform" "$verdict" "$skip"
            decision="$(action "$verdict")"
            echo "$label: $verdict -> $decision"
            if [ "$decision" = stop ]; then
                stop=1
            else
                emit "$platform" "$decision"
            fi
        done
        if [ "$stop" -ne 0 ]; then
            echo "::error::the release stops here, before anything is published."
            exit 1
        fi
        ;;
    *)
        echo "usage: release-config.sh --selftest | --decide <skip> <token:y|n> <id> <platform> | --workflow" >&2
        exit 2
        ;;
esac
