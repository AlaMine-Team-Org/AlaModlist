#!/usr/bin/env bash
# =============================================================================
# Finds and checks the notes of one release: release-notes/<tag>.md, a file in
# the tree of the tagged commit.
#
# The notes are a file on purpose. A tag message does not survive the checkout
# a CI job performs - the tag ref is rewritten to point straight at the commit,
# and what reads back as "the notes" is the commit message. Releases have
# already gone out to every platform with nothing but "Release vX.Y.Z" as their
# notes because of that. A file in the tagged tree cannot be swapped like that,
# and this check refuses the one text it must never be.
#
# The full style rules run in the private repository before the tag; this
# repeats only what the release cannot go without.
#
# Usage: check-release-notes.sh <tag> [tree]   - prints the path of the notes
# Exit:  0 usable, 1 not usable, 2 bad input.
# =============================================================================

set -uo pipefail

TAG="${1:-}"
TREE="${2:-.}"

[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "notes: '$TAG' is not a release tag (vX.Y.Z)" >&2; exit 2; }
VERSION="${TAG#v}"
cd "$TREE" || { echo "notes: cannot enter $TREE" >&2; exit 2; }

NOTES="release-notes/$TAG.md"
FAIL=0
bad() { echo "::error file=$NOTES::$1"; FAIL=1; }

if [ ! -f "$NOTES" ]; then
    echo "::error::$NOTES is not in the tagged commit. The notes are written in the private repository and published with the release commit."
    exit 1
fi

TEXT="$(tr -d '\r' < "$NOTES")"

if [ -z "$(printf '%s' "$TEXT" | tr -d '[:space:]')" ]; then
    bad "the notes are empty - a release with an empty body reaches players as a blank page"
    exit 1
fi

FIRST="$(printf '%s\n' "$TEXT" | head -n 1)"
[ "$FIRST" = "## $VERSION" ] || bad "the first line must be exactly '## $VERSION', got '$FIRST'"

if printf '%s\n' "$TEXT" | tail -n +2 | grep -qE '^## '; then
    bad "the notes carry another '## ' heading - one file describes one version"
fi

if ! printf '%s\n' "$TEXT" | grep -qE '^[[:space:]]*[-*][[:space:]]+[^[:space:]]'; then
    bad "the notes have no bullet point - say what changed"
fi

if printf '%s\n' "$TEXT" | grep -q '<!--'; then
    bad "the notes still carry an HTML comment from the template"
fi

SUBJECT="$(git log -1 --format=%s 2> /dev/null || true)"
# The text with blank lines and trailing spaces removed: what is left if the
# file says nothing more than a commit subject.
TRIMMED="$(printf '%s\n' "$TEXT" | sed 's/[[:space:]]*$//' | grep -v '^$')"
for forbidden in "Release $TAG" "Release $VERSION" "$SUBJECT"; do
    [ -n "$forbidden" ] || continue
    if [ "$TRIMMED" = "$forbidden" ]; then
        bad "the notes are just '$forbidden' - that is the commit message, not release notes"
    fi
done

[ "$FAIL" -eq 0 ] || exit 1
echo "$NOTES"
