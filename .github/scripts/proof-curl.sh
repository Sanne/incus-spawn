#!/bin/bash
# Requests to CI's echo upstreams must present a real per-instance namespace proof.
# Used even in minimal images without the corresponding tool installed.
set -euo pipefail
url=${1:?usage: proof-curl.sh URL [curl-options...]}
shift
export TEST_PROXY_TOKEN=tpt_placeholder TEST_ACCOUNT_TOKEN=tacct_placeholder
export GH_TOKEN=gho_placeholder OPENAI_API_KEY=sk-placeholder
export CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-placeholder
. /run/isx/proof-tokens
case "$url" in
    https://echo.incus-spawn.test/*) auth="Authorization: Bearer $TEST_PROXY_TOKEN" ;;
    https://echo-basic.incus-spawn.test/*)
        auth="Authorization: Basic $(printf 'test-user:%s' "$TEST_PROXY_TOKEN" | base64 -w0)" ;;
    https://echo-header.incus-spawn.test/*) auth="X-Custom-Auth: Key $TEST_PROXY_TOKEN" ;;
    https://echo-accounts.incus-spawn.test/*) auth="Authorization: Bearer $TEST_ACCOUNT_TOKEN" ;;
    https://api.github.com/*) auth="Authorization: Bearer $GH_TOKEN" ;;
    https://api.openai.com/*) auth="Authorization: Bearer $OPENAI_API_KEY" ;;
    https://api.anthropic.com/*) auth="x-api-key: $CLAUDE_CODE_OAUTH_TOKEN" ;;
    *) echo "Not a credential test endpoint: $url" >&2; exit 1 ;;
esac
exec curl --max-time 30 -H "$auth" "$@" "$url"
