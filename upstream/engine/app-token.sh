#!/usr/bin/env bash
# An installation token of the upstream-watch GitHub App for one repository (#2036): a JWT signed with
# the app's private key, then POST /app/installations/<id>/access_tokens limited to that repository and
# to contents and pull_requests write. The token lives an hour, is printed once, alone, on stdout
# (the caller captures it and masks it: a workflow command printed here would be captured too and
# never reach the runner), and is the only credential of Sanne's the watch ever holds; the key never
# leaves this process.
#
#   UPSTREAM_APP_ID=<id> UPSTREAM_APP_PRIVATE_KEY=<pem> app-token.sh <owner/repo>
#
# Needs openssl, curl, jq. GITHUB_API may point curl elsewhere (tests).
set -euo pipefail
repo="${1:?owner/repo}"
[[ "$repo" =~ ^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$ ]] || { echo "not owner/repo: '$repo'" >&2; exit 1; }
[[ "${UPSTREAM_APP_ID:-}" =~ ^[0-9]+$ ]] || { echo "UPSTREAM_APP_ID is not set to a number" >&2; exit 1; }
[[ -n "${UPSTREAM_APP_PRIVATE_KEY:-}" ]] || { echo "UPSTREAM_APP_PRIVATE_KEY is not set" >&2; exit 1; }
API="${GITHUB_API:-https://api.github.com}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now="$(date -u +%s)"
header="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)"
payload="$(printf '{"iat":%d,"exp":%d,"iss":%d}' "$((now - 60))" "$((now + 540))" "$UPSTREAM_APP_ID" | b64url)"
# The key is handed to openssl on a file descriptor, never a file or an argument.
sig="$(printf '%s.%s' "$header" "$payload" \
    | openssl dgst -sha256 -sign /dev/fd/3 3<<<"$UPSTREAM_APP_PRIVATE_KEY" | b64url)"
jwt="$header.$payload.$sig"

installation="$(curl -fsS -H "Authorization: Bearer $jwt" -H "Accept: application/vnd.github+json" \
    "$API/repos/$repo/installation" | jq -r '.id // empty')"
[[ "$installation" =~ ^[0-9]+$ ]] || { echo "the app is not installed on $repo" >&2; exit 1; }
token="$(curl -fsS -X POST -H "Authorization: Bearer $jwt" -H "Accept: application/vnd.github+json" \
    "$API/app/installations/$installation/access_tokens" \
    -d "$(jq -cn --arg r "${repo#*/}" '{repositories: [$r], permissions: {contents: "write", pull_requests: "write"}}')" \
    | jq -r '.token // empty')"
[[ -n "$token" ]] || { echo "no installation token for $repo" >&2; exit 1; }
printf '%s\n' "$token"
