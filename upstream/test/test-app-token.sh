#!/usr/bin/env bash
# app-token.sh against a fake GitHub (#2036): the JWT it signs verifies with the key's public half and
# carries the app id and a short expiry; the installation is looked up for the one repository; the
# token request is limited to that repository and to contents and pull_requests write; a missing
# installation, id or key fails before any request. Needs openssl, curl faked below, jq.
set -euo pipefail
ENGINE="$(cd "$(dirname "$0")/../engine" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/key.pem" 2>/dev/null
openssl pkey -in "$WORK/key.pem" -pubout -out "$WORK/pub.pem" 2>/dev/null

# A curl that records each request and answers the two endpoints.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/curl" <<'FAKE'
#!/bin/bash
url=""; data=""; auth=""
while [ $# -gt 0 ]; do
  case "$1" in
    -d) data="$2"; shift ;;
    -H) [[ "$2" == Authorization:* ]] && auth="${2#Authorization: Bearer }"; shift ;;
    -X) shift ;;
    -*) ;;
    *) url="$1" ;;
  esac; shift
done
printf '%s\t%s\t%s\n' "$url" "$auth" "$data" >> "$CALLS"
case "$url" in
  */repos/Sanne/incus-spawn/installation) [ -z "$NOT_INSTALLED" ] && echo '{"id": 4242}' || { echo '{"message":"Not Found"}' >&2; exit 22; } ;;
  */app/installations/4242/access_tokens) echo '{"token": "ghs_fixture"}' ;;
  *) echo "unexpected $url" >&2; exit 22 ;;
esac
FAKE
chmod +x "$WORK/bin/curl"

run() { PATH="$WORK/bin:$PATH" CALLS="$WORK/calls" NOT_INSTALLED="${NOT_INSTALLED:-}" GITHUB_API=https://api.test \
    UPSTREAM_APP_ID="${APP_ID-77}" UPSTREAM_APP_PRIVATE_KEY="$(cat "$WORK/key.pem")" "$ENGINE/app-token.sh" "$@"; }

: > "$WORK/calls"
# As the workflow runs it, GITHUB_ACTIONS set: stdout is the token alone, nothing for the runner.
token="$(GITHUB_ACTIONS=true run Sanne/incus-spawn)" || fail "app-token.sh failed"
[ "$token" = ghs_fixture ] || fail "printed '$token', not the installation token"
[ "$(wc -l < "$WORK/calls")" = 2 ] || fail "expected two requests: $(cat "$WORK/calls")"
jwt="$(cut -f2 "$WORK/calls" | head -1)"
IFS=. read -r h p s <<<"$jwt"
b64d() { local x="$1"; x="${x//-/+}"; x="${x//_//}"; while (( ${#x} % 4 )); do x="$x="; done; printf '%s' "$x" | openssl base64 -d -A; }
[ "$(b64d "$h" | jq -r .alg)" = RS256 ] || fail "JWT alg"
[ "$(b64d "$p" | jq -r .iss)" = 77 ] || fail "JWT iss is not the app id: $(b64d "$p")"
exp="$(b64d "$p" | jq -r .exp)"; now="$(date -u +%s)"
(( exp - now <= 600 && exp - now > 0 )) || fail "JWT expiry is not within ten minutes: $exp vs $now"
b64d "$s" > "$WORK/sig.bin"
printf '%s.%s' "$h" "$p" | openssl dgst -sha256 -verify "$WORK/pub.pem" -signature "$WORK/sig.bin" > /dev/null 2>&1 || fail "JWT signature does not verify with the key's public half"
req="$(cut -f3 "$WORK/calls" | tail -1)"
[ "$(jq -r '.repositories | join(",")' <<<"$req")" = incus-spawn ] || fail "token not limited to the repository: $req"
[ "$(jq -r '.permissions | to_entries | map("\(.key)=\(.value)") | sort | join(",")' <<<"$req")" = "contents=write,pull_requests=write" ] || fail "token permissions: $req"

: > "$WORK/calls"
NOT_INSTALLED=1 run Sanne/incus-spawn > /dev/null 2>&1 && fail "an uninstalled app got a token"
[ "$(wc -l < "$WORK/calls")" = 1 ] || fail "a missing installation still requested a token"
: > "$WORK/calls"
APP_ID="" run Sanne/incus-spawn > /dev/null 2>&1 && fail "no app id passed"
run "Sanne/incus-spawn;rm" > /dev/null 2>&1 && fail "a malformed repository passed"
[ ! -s "$WORK/calls" ] || fail "a refused input still sent a request"
echo "test-app-token: ok"
