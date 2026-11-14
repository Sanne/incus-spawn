#!/usr/bin/env bash
# Every fixture test of the upstream engine (#2036), as the upstream-engine-steps CI job runs them.
set -euo pipefail
cd "$(dirname "$0")"
for t in test-engine.sh test-github-asset.sh test-signed-file.sh test-pr.sh test-app-token.sh; do
    echo "== $t"
    bash "$t"
done
echo "upstream tests: ok"
