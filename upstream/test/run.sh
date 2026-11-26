#!/usr/bin/env bash
# Every fixture test of the upstream engine (#2036), as the upstream-engine-steps CI job runs them.
set -euo pipefail
cd "$(dirname "$0")"
# A failed download in a fixture is tried again at once, not after a second doubling (#2461).
export UPSTREAM_FETCH_PAUSE=0
for t in test-fetch.sh test-engine.sh test-github-asset.sh test-antigravity.sh test-signed-file.sh test-rpm-repo.sh test-github-action.sh test-git-main-paths.sh test-maven-central.sh test-model-deprecations.sh test-pr.sh test-app-token.sh test-slow-pipe.sh; do
    echo "== $t"
    bash "$t"
done
echo "upstream tests: ok"
