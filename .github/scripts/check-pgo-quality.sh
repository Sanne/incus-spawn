#!/bin/bash
# .github/scripts/check-pgo-quality.sh — Fail a release build whose binaries did not take their PGO profile
#
# A build given --pgo with -H:+PGOPrintProfileQuality prints, per binary,
#   Info: [PGO]: Profile applicability is A%. Profile relevance is R%.
# Relevance is the share of the profile that matched this image, applicability the share of the
# image the profile covers. Both binaries must print it, and each must reach the floors: a
# profile that fits this platform's image badly would make the code PGO picked as hot slow.
# The floors sit below what the release platforms measured (docs/dev/PERFORMANCE-NOTES.md).
#
# Usage: check-pgo-quality.sh <build log> [binaries, default 2]
set -euo pipefail

LOG=$1
EXPECTED=${2:-2}
MIN_APPLICABILITY=${PGO_MIN_APPLICABILITY:-12}
MIN_RELEVANCE=${PGO_MIN_RELEVANCE:-90}

lines=$(grep -E 'Info: \[PGO\]: Profile applicability is [0-9.]+%\. Profile relevance is [0-9.]+%\.' "$LOG" || true)
count=$(printf '%s' "$lines" | grep -c . || true)
echo "$lines"
if [ "$count" -ne "$EXPECTED" ]; then
    echo "::error::$count of $EXPECTED binaries report a PGO profile in $LOG: a build ran without one"
    exit 1
fi
status=0
while read -r line; do
    applicability=$(sed -E 's/.*applicability is ([0-9.]+)%.*/\1/' <<<"$line")
    relevance=$(sed -E 's/.*relevance is ([0-9.]+)%.*/\1/' <<<"$line")
    if awk -v a="$applicability" -v r="$relevance" -v ma="$MIN_APPLICABILITY" -v mr="$MIN_RELEVANCE" \
        'BEGIN { exit !(a >= ma && r >= mr) }'; then
        echo "ok: applicability $applicability% (floor $MIN_APPLICABILITY%), relevance $relevance% (floor $MIN_RELEVANCE%)"
    else
        echo "::error::PGO profile below its floor: applicability $applicability% (floor $MIN_APPLICABILITY%), relevance $relevance% (floor $MIN_RELEVANCE%)"
        status=1
    fi
done <<<"$lines"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    { echo "### PGO profile quality"; echo '```'; echo "$lines"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi
exit $status
