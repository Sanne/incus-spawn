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
# Each failure is said in words, on stderr only: stdout is the token alone. In Actions it is an
# ::error:: annotation, its %, CR and LF escaped as workflow commands require, so a message GitHub
# sent cannot end the annotation and start a command of its own.
die() {
    local msg="$*"
    if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
        msg="${msg//%/%25}"; msg="${msg//$'\r'/%0D}"; msg="${msg//$'\n'/%0A}"
        echo "::error::$msg" >&2
    else
        echo "$msg" >&2
    fi
    exit 1
}
repo="${1:?owner/repo}"
[[ "$repo" =~ ^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$ ]] || die "not owner/repo: '$repo'"
[[ "${UPSTREAM_APP_ID:-}" =~ ^[0-9]+$ ]] || die "UPSTREAM_APP_ID is not set to a number"
[[ -n "${UPSTREAM_APP_PRIVATE_KEY:-}" ]] || die "UPSTREAM_APP_PRIVATE_KEY is not set"
API="${GITHUB_API:-https://api.github.com}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now="$(date -u +%s)"
header="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url)"
payload="$(printf '{"iat":%d,"exp":%d,"iss":%d}' "$((now - 60))" "$((now + 540))" "$UPSTREAM_APP_ID" | b64url)"
# The key is handed to openssl on a file descriptor, never a file or an argument.
sig="$(printf '%s.%s' "$header" "$payload" \
    | openssl dgst -sha256 -sign /dev/fd/3 3<<<"$UPSTREAM_APP_PRIVATE_KEY" | b64url)"
jwt="$header.$payload.$sig"

# With curl -f a 404 ended the script with no message, and a 404 here is the app not installed on
# that repository (#2330), so each request's answer is named.
# github <what it does> <what a 404 means> <curl arguments...>: the body of a 2xx answer on stdout,
# any other answer named.
github() {
    local what="$1" not_found="$2" response status; shift 2
    response="$(curl -sS -w '\n%{http_code}' -H "Authorization: Bearer $jwt" -H "Accept: application/vnd.github+json" "$@")" \
        || die "could not reach GitHub to $what"
    status="${response##*$'\n'}"
    case "$status" in
        2??) printf '%s\n' "${response%$'\n'*}" ;;
        401) die "GitHub refused the app's id or key to $what (401): check UPSTREAM_APP_ID and UPSTREAM_APP_PRIVATE_KEY" ;;
        404) die "$not_found" ;;
        *) die "GitHub answered $status to $what: $(jq -r '.message? // empty' <<<"${response%$'\n'*}" 2> /dev/null)" ;;
    esac
}

body="$(github "look up the app's installation on $repo" \
    "the isx-upstream app is not installed on $repo (404): install it there, docs/dev/UPSTREAM-SETUP.md step 1 and \"Sibling repositories\"" \
    "$API/repos/$repo/installation")"
installation="$(jq -r '.id // empty' <<<"$body")"
[[ "$installation" =~ ^[0-9]+$ ]] || die "GitHub named no installation of the app on $repo"
body="$(github "mint a token for $repo" "GitHub has no installation $installation of the app (404)" \
    -X POST "$API/app/installations/$installation/access_tokens" \
    -d "$(jq -cn --arg r "${repo#*/}" '{repositories: [$r], permissions: {contents: "write", pull_requests: "write"}}')")"
token="$(jq -r '.token // empty' <<<"$body")"
[[ -n "$token" ]] || die "no installation token for $repo"
printf '%s\n' "$token"
