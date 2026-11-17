#!/usr/bin/env bash
# app-token.sh against a fake GitHub (#2036): the JWT it signs verifies with the key's public half and
# carries the app id and a short expiry; the installation is looked up for the one repository; the
# token request is limited to that repository and to contents and pull_requests write; a missing id
# or key fails before any request; a missing installation, a refused key and a refused token each
# fail saying why on stderr, with nothing on stdout (#2330). Needs openssl, curl faked below, jq.
set -euo pipefail
ENGINE="$(cd "$(dirname "$0")/../engine" && pwd)"
# CI runs this under GitHub Actions; each case sets GITHUB_ACTIONS itself.
unset GITHUB_ACTIONS
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/key.pem" 2>/dev/null
openssl pkey -in "$WORK/key.pem" -pubout -out "$WORK/pub.pem" 2>/dev/null

# A curl that records each request and answers the two endpoints.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/curl" <<'FAKE'
#!/bin/bash
url=""; data=""; auth=""; fmt=""
while [ $# -gt 0 ]; do
  case "$1" in
    -d) data="$2"; shift ;;
    -H) [[ "$2" == Authorization:* ]] && auth="${2#Authorization: Bearer }"; shift ;;
    -X) shift ;;
    -w) fmt="$2"; shift ;;
    -*) ;;
    *) url="$1" ;;
  esac; shift
done
printf '%s\t%s\t%s\n' "$url" "$auth" "$data" >> "$CALLS"
# answer <status> <body>: as GitHub does, with -w's status line after the body. Without -w it is
# curl -f, as app-token.sh used before #2330, so reverting the fix fails on the messages below.
answer() {
  if [ -n "$fmt" ]; then printf '%s\n%s' "$2" "$1"; exit 0; fi
  [ "$1" -lt 400 ] || exit 22
  echo "$2"
}
case "$url" in
  */repos/Sanne/incus-spawn/installation)
    case "$INSTALLATION" in
      missing) answer 404 '{"message":"Not Found"}' ;;
      badkey) answer 401 '{"message":"A JSON web token could not be decoded"}' ;;
      *) answer 200 '{"id": 4242}' ;;
    esac ;;
  */app/installations/4242/access_tokens)
    [ "$INSTALLATION" = noperms ] && answer 422 '{"message":"The permissions requested are not granted to this installation."}'
    answer 201 '{"token": "ghs_fixture"}' ;;
  *) echo "unexpected $url" >&2; exit 6 ;;
esac
FAKE
chmod +x "$WORK/bin/curl"

run() { PATH="$WORK/bin:$PATH" CALLS="$WORK/calls" INSTALLATION="${INSTALLATION:-}" GITHUB_API=https://api.test \
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
# Each failure says why on stderr, and prints nothing on stdout (#2330).
refused() { # refused <installation> <expected message>
  local out
  out="$(INSTALLATION="$1" run Sanne/incus-spawn 2> "$WORK/err")" && fail "$1: got a token"
  [ -z "$out" ] || fail "$1: printed '$out' on stdout"
  grep -qF -- "$2" "$WORK/err" || fail "$1: stderr does not say '$2': $(cat "$WORK/err")"
}
refused missing "the isx-upstream app is not installed on Sanne/incus-spawn (404)"
[ "$(wc -l < "$WORK/calls")" = 1 ] || fail "a missing installation still requested a token"
grep -q '^::error::' "$WORK/err" && fail "an ::error:: line outside GitHub Actions"
out="$(GITHUB_ACTIONS=true INSTALLATION=missing run Sanne/incus-spawn 2>&1)" && fail "missing: got a token"
[[ "$out" == "::error::the isx-upstream app is not installed on Sanne/incus-spawn"* ]] || fail "no ::error:: annotation in Actions: $out"
refused badkey "GitHub refused the app's id or key"
refused noperms "GitHub answered 422 to mint a token for Sanne/incus-spawn: The permissions requested are not granted"
: > "$WORK/calls"
APP_ID="" run Sanne/incus-spawn > /dev/null 2>&1 && fail "no app id passed"
run "Sanne/incus-spawn;rm" > /dev/null 2>&1 && fail "a malformed repository passed"
[ ! -s "$WORK/calls" ] || fail "a refused input still sent a request"
echo "test-app-token: ok"
