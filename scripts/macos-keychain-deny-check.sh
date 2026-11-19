#!/usr/bin/env bash
# A guided check, for a person at a Mac, that isx refuses a root the user marked "Never Trust" in
# Keychain Access, and trusts it again once the mark is removed (#1552, #1631).
# It covers what no CI runner can: a deny in the user's own trust domain, which needs the person
# to authenticate in Keychain Access, the proxy behind an instance's curl, and the reset.
# The root is Sectigo Public Server Authentication Root E46, which api.github.com's chain sends
# cross-signed by USERTrust ECC: the shape a deny matched by hash alone used to miss.
#
# The script changes no trust setting and runs nothing as root: the person makes both changes in
# Keychain Access when asked. It restarts the proxy (isx proxy restart), which drops connections
# that running instances have open through it.
#
# Usage: macos-keychain-deny-check.sh <instance>
# The instance must be running, and its template must install the github tool, which makes the
# proxy intercept api.github.com. With sshd in the template, the script runs curl there itself;
# without it, it asks you to run curl in `isx shell <instance>` and paste what it prints.
# Run it with the isx you want to test first on PATH, or with ISX=/path/to/isx.
# It exits 0 when every check passed, 1 when one failed, and 2 when it could not run a check.

set -euo pipefail

E46="Sectigo Public Server Authentication Root E46"
CROSS_ISSUER="USERTrust ECC Certification Authority"
MITM_CA="incus-spawn MITM CA"
# The chain the instance and the CLI ask for; any path answers, an unauthenticated GET included
URL=https://api.github.com/
isx=${ISX:-isx}
instance=${1:-}

say() { printf '%s\n' "$*"; }
# What a check found, collected for the summary at the end
results=()
failed=0
pass() { say "  PASS: $*"; results+=("PASS: $*"); }
fail() { say "  FAIL: $*"; results+=("FAIL: $*"); failed=1; }
# The check could not be made, so nothing can be concluded from the rest
stop() { say "STOP: $*" >&2; exit 2; }
# macOS has no timeout(1)
limit() { local s=$1; shift; perl -e 'alarm shift; exec @ARGV' "$s" "$@"; }
pause() {
  printf '%s ' "$1"
  IFS= read -r _ || stop "no answer on standard input"
}

[ "$(uname -s)" = Darwin ] || stop "this check is for macOS; Linux reads no Keychain trust settings"
[ -n "$instance" ] || stop "usage: $0 <instance> (a running instance whose template installs the github tool)"
command -v "$isx" >/dev/null || stop "$isx is not on PATH; set ISX=/path/to/isx"
command -v openssl >/dev/null || stop "openssl is not installed"
work=$(mktemp -d)

# Set while E46 may still be marked "Never Trust", so an early exit says how to undo it
deny_set=
cleanup() {
  rm -rf "$work"
  if [ -n "$deny_set" ]; then
    say "" >&2
    say "E46 may still be marked \"Never Trust\", and HTTPS to GitHub fails on this Mac until it is reset:" >&2
    say "in Keychain Access, open \"$E46\" under System Roots and set \"When using this certificate\" to \"Use System Defaults\"." >&2
  fi
}
trap cleanup EXIT

say "isx under test: $(command -v "$isx")"
say "  $(file -b "$(command -v "$isx")" 2>/dev/null || echo "file type unknown")"
say "  $("$isx" --version 2>/dev/null | head -n 1 || echo "version unknown")"

# ---- The premise: api.github.com still sends E46 cross-signed by USERTrust ECC ----
# A rotation, or an inspecting proxy in front of this Mac, would make every check below pass or
# fail for a reason that has nothing to do with the deny.
say ""
say "Reading the chain api.github.com sends"
chain=$(limit 30 openssl s_client -connect api.github.com:443 -servername api.github.com -showcerts </dev/null 2>/dev/null || true)
# LibreSSL prints "s:/C=GB/.../CN=<name>", OpenSSL 3 "s:C=GB, ..., CN=<name>"; the issuer is the next line
if ! printf '%s\n' "$chain" | awk -v s="$E46" -v i="$CROSS_ISSUER" '
    /^ *[0-9]+ s:/ { subject = index($0, s) > 0; next }
    /^ *i:/ && subject && index($0, i) > 0 { found = 1 }
    { subject = 0 }
    END { exit !found }'; then
  printf '%s\n' "$chain" | grep -E '^ *([0-9]+ s:|i:)' >&2 || true
  stop "api.github.com does not send \"$E46\" issued by \"$CROSS_ISSUER\": its chain changed, or something on this network inspects TLS"
fi
say "  it sends $E46, cross-signed by $CROSS_ISSUER"

# ---- What isx shows; each function prints one value ----

# Whether the user's own trust domain denies E46: what Keychain Access writes, and the check that
# the person's change landed where this test needs it
user_denies_e46() {
  local dump
  dump=$(limit 60 security dump-trust-settings 2>/dev/null || true)
  printf '%s\n' "$dump" | awk -v s="$E46" '
    /^Cert [0-9]+: / { cert = (substr($0, index($0, ": ") + 2) == s); next }
    cert && /Result Type/ && /kSecTrustSettingsResultDeny/ { found = 1 }
    END { exit !found }'
}

# The number of CAs isx doctor counts as denied in the Keychain
doctor_denied() {
  local out line
  out=$(limit 180 "$isx" doctor --format=plain 2>"$work/doctor.err" || true)
  line=$(printf '%s\n' "$out" | grep -F "Keychain CAs trusted for TLS" | head -n 1 || true)
  [ -n "$line" ] || { cat "$work/doctor.err" >&2; stop "isx doctor did not report \"Keychain CAs trusted for TLS\""; }
  printf '%s\n' "$line" | sed -nE 's/.*[^0-9]([0-9]+) denied \(security dump-trust-settings.*/\1/p' | grep . || echo 0
}

# "ok" when isx update-base --list read GitHub's release list, "denied" when it failed on the
# Keychain's deny, and "other" when it failed for another reason
cli_reads_releases() {
  if limit 120 "$isx" update-base --list --format=plain >/dev/null 2>"$work/releases.err"; then
    echo ok
  elif grep -q "denied in the Keychain" "$work/releases.err"; then
    echo denied
  else
    echo other
  fi
}

# The guest's curl, as "<issuer> <status> <body>"; a missing value is "-". The body is kept apart
# from curl's trace, and named only when it is the proxy's answer to a failed upstream connection
# (Upstream.java, sent only when the connection upstream failed): any other 502, such as a
# credential failure or a timeout, is not the deny.
UPSTREAM_FAILED="Upstream connection failed"
guest_curl_cmd="f=\$(mktemp); curl -sv -o \"\$f\" -w 'isx-status=%{http_code}\n' --max-time 60 $URL 2>&1 | grep -E 'issuer:|^isx-status='; grep -qx '$UPSTREAM_FAILED' \"\$f\" && echo isx-body=upstream-failed; rm -f \"\$f\""
guest_curl() {
  local out
  if [ -n "$via_ssh" ]; then
    out=$(limit 120 ssh -o BatchMode=yes -o ConnectTimeout=15 "$instance" "$guest_curl_cmd" </dev/null 2>/dev/null || true)
  else
    say "  In another terminal, run \`isx shell $instance\`, and in it:" >&2
    say "    $guest_curl_cmd" >&2
    say "  Paste the lines it printed here, then an empty line:" >&2
    out=
    local line
    while IFS= read -r line && [ -n "$line" ]; do out="$out$line"$'\n'; done
  fi
  local issuer status body
  if printf '%s\n' "$out" | grep -q "issuer:.*$MITM_CA"; then issuer=proxy; else issuer=-; fi
  status=$(printf '%s\n' "$out" | sed -n 's/^isx-status=\([0-9]*\).*/\1/p' | head -n 1)
  if printf '%s\n' "$out" | grep -qx 'isx-body=upstream-failed'; then body=upstream-failed; else body=-; fi
  echo "$issuer ${status:--} $body"
}

restart_proxy() {
  say "  Restarting the proxy, which reads the Keychain only when it starts"
  limit 120 "$isx" proxy restart >"$work/proxy.log" 2>&1 || { cat "$work/proxy.log" >&2; stop "isx proxy restart failed"; }
  # The restart can return before the proxy answers; its status exits 0 only once it runs
  local _
  for _ in $(seq 1 30); do
    limit 30 "$isx" proxy status --format=plain >/dev/null 2>&1 && return 0
    sleep 2
  done
  stop "the proxy is not running 60 seconds after isx proxy restart"
}

via_ssh=
if limit 30 ssh -o BatchMode=yes -o ConnectTimeout=15 "$instance" true </dev/null 2>/dev/null; then
  via_ssh=1
  say "Running curl in $instance over ssh"
else
  say "ssh $instance does not answer (its template may have no sshd): you will run curl in it by hand"
fi

# ---- 1. Before the deny: everything works, and the guest's request goes through the proxy ----
say ""
say "1. Before the deny"
user_denies_e46 && stop "the user domain already denies E46: set it to \"Use System Defaults\" in Keychain Access first"
[ "$(cli_reads_releases)" = ok ] || { cat "$work/releases.err" >&2; stop "isx update-base --list fails before any deny"; }
baseline_denied=$(doctor_denied)
say "  isx doctor counts $baseline_denied denied CA(s)"
restart_proxy
read -r issuer status body <<<"$(guest_curl)"
[ "$issuer" = proxy ] \
  || stop "the guest's curl was not served by isx's proxy (issuer not \"$MITM_CA\"): give an instance whose template installs the github tool"
[ "$status" = 200 ] || stop "the guest's curl got HTTP $status before any deny"
say "  isx update-base --list reads the release list, and the guest's curl gets 200 through the proxy"

# ---- 2. The deny ----
say ""
say "2. Mark E46 \"Never Trust\" in your login trust settings:"
say "   open Keychain Access, choose System Roots, and double-click \"$E46\"."
say "   Expand Trust, set \"When using this certificate\" to \"Never Trust\", close the window and authenticate."
deny_set=1
pause "Press Enter once it is set."
say ""
user_denies_e46 || stop "security dump-trust-settings shows no user-domain deny of E46: the change did not land where this check needs it"
say "  security dump-trust-settings shows the deny"

case $(cli_reads_releases) in
  denied) pass "isx update-base --list is refused on the Keychain's deny" ;;
  ok) fail "isx update-base --list read GitHub's release list through a denied root" ;;
  *) fail "isx update-base --list failed, but not on the Keychain's deny: $(head -n 3 "$work/releases.err")" ;;
esac
denied=$(doctor_denied)
if [ "$denied" -gt "$baseline_denied" ]; then
  pass "isx doctor counts the deny ($baseline_denied, now $denied)"
else
  fail "isx doctor does not count the deny ($baseline_denied before, $denied now)"
fi
restart_proxy
read -r issuer status body <<<"$(guest_curl)"
if [ "$issuer" = proxy ] && [ "$status" = 502 ] && [ "$body" = upstream-failed ]; then
  # The CLI reached api.github.com just before and was refused on the deny, so the network is up
  pass "the guest's curl gets the proxy's 502 \"$UPSTREAM_FAILED\": it could not complete its upstream handshake"
else
  fail "the guest's curl was not refused by the proxy (issuer: $issuer, HTTP $status, body: $body; expected the proxy, 502 and \"$UPSTREAM_FAILED\")"
fi

# ---- 3. The reset ----
say ""
say "3. Set E46 back: in Keychain Access, open \"$E46\" again"
say "   and set \"When using this certificate\" to \"Use System Defaults\", then close the window and authenticate."
pause "Press Enter once it is set."
say ""
user_denies_e46 && stop "security dump-trust-settings still shows the user-domain deny of E46"
deny_set=

if [ "$(cli_reads_releases)" = ok ]; then
  pass "isx update-base --list reads the release list again"
else
  fail "isx update-base --list still fails after the reset: $(head -n 3 "$work/releases.err")"
fi
denied=$(doctor_denied)
if [ "$denied" = "$baseline_denied" ]; then
  pass "isx doctor no longer counts the deny ($denied)"
else
  fail "isx doctor counts $denied denied CA(s) after the reset, $baseline_denied before the deny"
fi
restart_proxy
read -r issuer status body <<<"$(guest_curl)"
if [ "$issuer" = proxy ] && [ "$status" = 200 ]; then
  pass "the guest's curl gets 200 through the proxy again"
else
  fail "the guest's curl did not recover (issuer: $issuer, HTTP $status; expected the proxy and 200)"
fi

say ""
say "Summary:"
for r in ${results[@]+"${results[@]}"}; do say "  $r"; done
if [ "$failed" = 0 ]; then say "Every check passed."; else say "A check failed."; fi
exit "$failed"
