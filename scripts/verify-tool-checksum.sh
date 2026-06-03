#!/bin/bash
# Helper script to download a URL and compute its SHA256 checksum
# Usage: ./scripts/verify-tool-checksum.sh <url>

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Usage: $0 <url>" >&2
    exit 1
fi

URL="$1"
TMPFILE=$(mktemp)
trap "rm -f $TMPFILE" EXIT

echo "Downloading: $URL" >&2
curl -fsSL -o "$TMPFILE" "$URL"

echo "Computing SHA256..." >&2
SHA256=$(sha256sum "$TMPFILE" | cut -d' ' -f1)

echo
echo "URL:    $URL"
echo "SHA256: $SHA256"
