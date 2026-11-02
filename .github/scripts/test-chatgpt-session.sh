#!/bin/bash
# M1 of #1332: a host-owned ChatGPT session used by the real Codex in an instance, through the
# real isx-proxy, against a local stub of OpenAI's token endpoint and the Codex backend.
#
# Nothing here may reach OpenAI or ChatGPT, and that is enforced, not assumed:
#   - host side: the stub, the proxy and every probe run with the group isx-chatgpt-test, and an
#     nftables table drops every packet that group sends anywhere but the stub's two loopback
#     ports and the bridge. A negative control proves the drop works before anything else runs.
#   - guest side: its own --proxy-only rules can be removed by guest root (#1137), so a host
#     table drops everything the instance would route off the host. The control flushes the
#     guest's own rules as root first, then proves it still cannot get out.
# Every token is minted by the stub, random per run, and searched for afterwards anywhere the
# guest can read: a hit is a leak.
#
# The instance's guest files and proofs are written by this fixture (M1 has no sign-in or
# delivery yet): the secret is the instance's own per-start one, the proofs are derived from it as
# ChatGptProof does (checked against its pinned vector first), and only their SHA-256 verifiers go
# into instance metadata.
#
# Its work directory, which holds a copy of the user's CA key, is removed on every exit unless KEEP=1.
# None of the user's own isx state is touched: the test's proxy runs in a mount namespace that puts
# a throwaway config and state directory under the work directory over the user's (a copy of
# config.yaml and the CA, plus the ChatGPT account and its session),
# the user's config is checked unchanged at the end, and a proxy that was running before is
# started again on exit, failures included.
#
# Usage (as a user in incus-admin, with passwordless sudo, isx-proxy on PATH and the instance
# running with codex installed):
#   test-chatgpt-session.sh <instance>
# Environment:
#   PLAIN_CONTAINER=1   the instance is no isx branch: give it a secret and a static-ip stamp
#   KEEP=1              leave the stub, the proxy and the firewall up afterwards

set -uo pipefail

INSTANCE=${1:?usage: test-chatgpt-session.sh <instance>}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d /tmp/isx-chatgpt-test.XXXXXX)
STUB_PORT=18943
CONTROL_PORT=18944
ACCOUNT=sso
TEST_GROUP=isx-chatgpt-test
REAL_CONFIG_DIR="$HOME/.config/incus-spawn"
# The test's own home: everything the test proxy reads and writes is under it
TEST_HOME="$WORK/home"
CONFIG_DIR="$TEST_HOME/.config/incus-spawn"
CODEX_CLIENT_ID=app_EMoamEEZ73f0CkXaXp7hrann

PASS=0
FAIL=0
ERRORS=""

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL + 1)); ERRORS="${ERRORS}  - $1\n"; }
check() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi; }
check_not() { local desc="$1"; shift; if "$@" >/dev/null 2>&1; then fail "$desc"; else pass "$desc"; fi; }
die() { echo "FATAL: $*" >&2; cleanup; exit 1; }

gx() { incus exec "$INSTANCE" -- "$@"; }
gu() { incus exec "$INSTANCE" -- su - agentuser -c "$1"; }
# Runs a command with the test group, the only identity the host-side egress table lets out
as_test() { sudo setpriv --reuid="$(id -u)" --regid="$TEST_GROUP" --init-groups env HOME="$HOME" PATH="$PATH" "$@"; }
control() { curl -sf -m 10 "$@"; }
# Packets an nftables named counter has seen: <table> <counter>
counter() { sudo nft -j list counter inet "$1" "$2" | python3 -c "import json,sys; print([o['counter']['packets'] for o in json.load(sys.stdin)['nftables'] if 'counter' in o][0])"; }
stub_requests() { control "http://127.0.0.1:$CONTROL_PORT/log" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))"; }
# Whether a log has a line matching <pattern> from line <from> on: <from> <file> <pattern>
logged_since() { tail -n "+$1" "$2" | grep -q "$3"; }
# Whether the stub has seen a request: <method> <path>
stub_saw() { control "http://127.0.0.1:$CONTROL_PORT/log" | python3 -c "import json,sys; sys.exit(0 if any(e['method'] == sys.argv[1] and e['path'] == sys.argv[2] for e in json.load(sys.stdin)) else 1)" "$1" "$2"; }
# Waits up to 30 s for a proxy to answer on the bridge
wait_healthy() { for _ in $(seq 1 60); do curl -sf -m 2 "http://$GATEWAY:18080/health" >/dev/null && return 0; sleep 0.5; done; return 1; }
health_pid() { curl -sf -m 2 "http://$GATEWAY:18080/health" | python3 -c "import json,sys; print(json.load(sys.stdin).get('pid',''))"; }
rotations() { control "http://127.0.0.1:$CONTROL_PORT/state" | python3 -c "import json,sys; print(json.load(sys.stdin)['rotations'])"; }

REAL_PROXY_PID=
USER_PROXY_WAS_UP=0
CLEANED=0
cleanup() {
    [ "$CLEANED" = 1 ] && return
    CLEANED=1
    if [ "${KEEP:-0}" = 1 ]; then
        [ "$USER_PROXY_WAS_UP" = 1 ] && echo "KEEP=1: your own proxy stays stopped; run 'isx proxy start' when done" >&2
        echo "KEEP=1: $WORK holds a copy of your isx CA key; remove it when done" >&2
        return
    fi
    # The test proxy by the pid it reports, and its wrapper and the stub by the work directory
    # in their arguments
    # Read again: a pidfile written after the main path stopped waiting still names the proxy
    [ -n "$REAL_PROXY_PID" ] || REAL_PROXY_PID=$(cat "$WORK/proxy.pid" 2>/dev/null)
    if [ -n "$REAL_PROXY_PID" ]; then
        sudo kill "$REAL_PROXY_PID" 2>/dev/null
        # Gone, not just quiet: the user's proxy needs its ports
        for _ in $(seq 1 60); do sudo kill -0 "$REAL_PROXY_PID" 2>/dev/null || break; sleep 0.25; done
        sudo kill -9 "$REAL_PROXY_PID" 2>/dev/null
    fi
    for p in $(pgrep -f -- "$WORK"); do sudo kill "$p" 2>/dev/null; done
    sudo nft delete table inet isx_chatgpt_host 2>/dev/null
    sudo nft delete table inet isx_chatgpt_guest 2>/dev/null
    if [ "$USER_PROXY_WAS_UP" = 1 ]; then
        # The user's own proxy, with the user's own config, as it was before the test stopped it
        # Its output outside $WORK, which goes below, and shown if it did not come back
        local out
        out=$(mktemp /tmp/isx-proxy-restart.XXXXXX)
        setsid nohup isx proxy start > "$out" 2>&1 < /dev/null &
        if wait_healthy && [ "$(health_pid)" != "$REAL_PROXY_PID" ]; then
            echo "Restarted the proxy that was running before"
            rm -f "$out"
        else
            echo "WARNING: could not restart the proxy that was running before; run 'isx proxy start'. Its output: $out" >&2
            tail -20 "$out" >&2
        fi
    fi
    # The test home holds a copy of the user's CA key, which every instance trusts: never left behind
    rm -rf "$WORK"
    return 0
}
trap cleanup EXIT
# An interrupted run restores too: the EXIT trap runs only on an exit
trap 'exit 130' INT TERM HUP

echo "========================================"
echo " ChatGPT session (#1332, M1) end to end"
echo "========================================"

GATEWAY=$(incus network get incusbr0 ipv4.address | cut -d/ -f1)
BRIDGE_NET=$(python3 -c "import ipaddress,sys; print(ipaddress.ip_interface(sys.argv[1]).network)" "$(incus network get incusbr0 ipv4.address)")
GUEST_IP=$(incus list "$INSTANCE" -c 4 -f csv | grep -oE '^[0-9.]+' | head -1)
[ -n "$GUEST_IP" ] || die "$INSTANCE has no IPv4 address"
echo "gateway $GATEWAY, bridge $BRIDGE_NET, $INSTANCE at $GUEST_IP"

# --- 1. Host-side egress: only the stub and the bridge ---
echo ""
echo "[1] Host-side egress is blocked for the test's processes"
getent group "$TEST_GROUP" >/dev/null || sudo groupadd "$TEST_GROUP"
sudo nft -f - <<EOF || die "could not install the host egress table"
table inet isx_chatgpt_host {
  chain out {
    type filter hook output priority 0; policy accept;
    meta skgid != "$TEST_GROUP" accept
    oifname "lo" ip daddr 127.0.0.1 tcp dport { $STUB_PORT, $CONTROL_PORT } accept
    oifname "lo" ip saddr 127.0.0.1 tcp sport { $STUB_PORT, $CONTROL_PORT } accept
    ip daddr $BRIDGE_NET accept
    counter name dropped drop
  }
  counter dropped {}
}
EOF
BEFORE=$(counter isx_chatgpt_host dropped)
check_not "a public address cannot be reached (TCP 1.1.1.1:443)" as_test curl -sS -m 5 -o /dev/null https://1.1.1.1
check_not "chatgpt.com cannot be reached by name" as_test curl -sS -m 5 -o /dev/null https://chatgpt.com
check_not "auth.openai.com cannot be reached by name" as_test curl -sS -m 5 -o /dev/null https://auth.openai.com
check_not "a public resolver cannot be reached (TCP 8.8.8.8:53)" as_test curl -sS -m 5 -o /dev/null telnet://8.8.8.8:53
AFTER=$(counter isx_chatgpt_host dropped)
check "the table dropped them (counter $BEFORE -> $AFTER)" test "$AFTER" -gt "$BEFORE"
check "without the test group the same host still gets out (so the control tests the table)" \
    curl -sS -m 10 -o /dev/null https://1.1.1.1

# --- 2. The stub ---
echo ""
echo "[2] The stub"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$WORK/ca.key" -out "$WORK/ca.pem" -days 1 \
    -subj "/CN=isx chatgpt stub CA" -addext "basicConstraints=critical,CA:true" 2>/dev/null
openssl req -new -newkey rsa:2048 -nodes -keyout "$WORK/stub.key" -out "$WORK/stub.csr" -subj "/CN=chatgpt.com" 2>/dev/null
# 127.0.0.1 too: a WebSocket to an overridden host is dialled by address, without SNI
printf 'subjectAltName=DNS:chatgpt.com,DNS:auth.openai.com,IP:127.0.0.1\n' > "$WORK/san.ext"
openssl x509 -req -in "$WORK/stub.csr" -CA "$WORK/ca.pem" -CAkey "$WORK/ca.key" -CAcreateserial \
    -out "$WORK/stub.pem" -days 1 -extfile "$WORK/san.ext" 2>/dev/null
chmod 644 "$WORK"/*.pem
as_test python3 "$HERE/chatgpt-stub.py" "$WORK/stub.pem" "$WORK/stub.key" \
    127.0.0.1 "$STUB_PORT" "$CONTROL_PORT" 2>"$WORK/stub.log" &
for _ in $(seq 1 50); do control "http://127.0.0.1:$CONTROL_PORT/state" >/dev/null && break; sleep 0.1; done
check "the stub answers on loopback, from inside the test group too (positive control)" \
    as_test curl -sf -m 5 -o /dev/null "http://127.0.0.1:$CONTROL_PORT/state"

# --- 3. The host session and the proxy ---
echo ""
echo "[3] The host's session, and the proxy pointed at the stub"
# Never the user's own: a copy of what the proxy needs (config and CA), in the test's home
[ "$(realpath -m "$CONFIG_DIR")" != "$(realpath -m "$REAL_CONFIG_DIR")" ] || die "refusing to run against the real config"
mkdir -p -m 700 "$CONFIG_DIR"
for f in config.yaml ca.crt ca.key .init-complete; do
    [ -e "$REAL_CONFIG_DIR/$f" ] && cp -p "$REAL_CONFIG_DIR/$f" "$CONFIG_DIR/$f"
done
[ -e "$CONFIG_DIR/ca.key" ] || die "no isx CA in $REAL_CONFIG_DIR: run 'isx init' first"
# Everything under the user's config and state but what other isx processes rewrite as they run:
# the lock file 'isx proxy' takes, and the CLI's own log and API debug captures (a TUI or 'isx mcp'
# left running writes them). The proxy's logs stay in: a test proxy that escaped its mount
# namespace would write them.
real_state() { find "$REAL_CONFIG_DIR" "$HOME/.local/state/incus-spawn" -name api-debug -prune \
    -o -type f ! -name proxy.lock ! -name client.log -print0 \
    | sort -z | xargs -0 sha256sum 2>/dev/null | sha256sum | cut -d' ' -f1; }
python3 - "$CONFIG_DIR/config.yaml" "$ACCOUNT" <<'PY' || die "could not add the chatgpt account to the test config"
import sys, yaml
path, account = sys.argv[1:3]
try:
    cfg = yaml.safe_load(open(path)) or {}
except FileNotFoundError:
    cfg = {}
openai = cfg.setdefault("openai", {})
openai.setdefault("accounts", {})[account] = {"type": "chatgpt"}
yaml.safe_dump(cfg, open(path, "w"), default_flow_style=False)
PY
SESSION_ID=$(python3 -c "import uuid; print(uuid.uuid4())")
control -X POST "http://127.0.0.1:$CONTROL_PORT/mint" > "$WORK/minted.json" || die "the stub minted nothing"
mkdir -p -m 700 "$CONFIG_DIR/openai/$ACCOUNT"
python3 - "$WORK/minted.json" "$SESSION_ID" "$CONFIG_DIR/openai/$ACCOUNT/session.json" <<'PY'
import json, os, sys
tokens = json.load(open(sys.argv[1]))
session = {"session_id": sys.argv[2], "generation": 0, "tokens": tokens}
fd = os.open(sys.argv[3], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.write(fd, json.dumps(session).encode()); os.close(fd)
PY
ACCOUNT_ID=$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['account_id'])" "$WORK/minted.json")

# Only one proxy can serve the bridge: the user's stops for the test, and starts again on exit
curl -sf -m 2 "http://$GATEWAY:18080/health" >/dev/null && USER_PROXY_WAS_UP=1
isx proxy stop >/dev/null 2>&1 || true
sleep 1
# After the user's proxy stopped: whatever it wrote on the way out is not the test's doing
REAL_STATE_BEFORE=$(real_state)
# The test proxy sees the test's directories where the user's are: in a mount namespace of its
# own, they are bind-mounted over ~/.config/incus-spawn and ~/.local/state/incus-spawn. Java takes
# its home from the password database, and a native image ignores -Duser.home, so neither $HOME
# nor a property can move it; a mount does, for any build, and only for this one process.
REAL_STATE_DIR="$HOME/.local/state/incus-spawn"
mkdir -p -m 700 "$TEST_HOME/.local/state/incus-spawn" "$REAL_STATE_DIR"
# Once both mounts are in place, the shell records its pid and execs, and every step after it execs
# too: that pid is the proxy's, for cleanup to stop even a proxy that never answered /health
sudo unshare --mount --propagation private sh -c '
    mount --bind "$1" "$2" && mount --bind "$3" "$4" || exit 1
    echo $$ > "$5"
    shift 5
    exec "$@"' sh "$CONFIG_DIR" "$REAL_CONFIG_DIR" "$TEST_HOME/.local/state/incus-spawn" "$REAL_STATE_DIR" "$WORK/proxy.pid" \
    setpriv --reuid="$(id -u)" --regid="$TEST_GROUP" --init-groups env HOME="$HOME" PATH="$PATH" \
    ISX_BENCH_UPSTREAM="chatgpt.com=127.0.0.1:$STUB_PORT,auth.openai.com=127.0.0.1:$STUB_PORT" \
    ISX_BENCH_UPSTREAM_CERT="$WORK/ca.pem" isx-proxy --gateway-ip "$GATEWAY" > "$WORK/proxy.log" 2>&1 &
for _ in $(seq 1 20); do [ -s "$WORK/proxy.pid" ] && break; sleep 0.1; done
REAL_PROXY_PID=$(cat "$WORK/proxy.pid" 2>/dev/null)
check "the proxy is healthy under the egress table" wait_healthy
check "it intercepts chatgpt.com" grep -q "chatgpt.com" "$WORK/proxy.log"
check "it works in the test's home, not the user's (it minted auth.openai.com's certificate there)" \
    test -e "$CONFIG_DIR/certs/auth.openai.com.crt"
check "the proxy answering /health is the test's ($REAL_PROXY_PID)" test -n "$REAL_PROXY_PID" -a "$(health_pid)" = "$REAL_PROXY_PID"

# --- 4. The instance: its secret, its proofs, its verifiers ---
echo ""
echo "[4] Binding $INSTANCE to the session"
derive() { # <secret> <account> <session> -> "refresh access"
    python3 - "$@" <<'PY'
import hmac, hashlib, sys
secret, account, session = sys.argv[1:4]
r = hmac.new(bytes.fromhex(secret), f"isx-chatgpt:{account}:{session}:refresh".encode(), hashlib.sha256).hexdigest()
print(r, hmac.new(bytes.fromhex(r), b"isx-chatgpt-access", hashlib.sha256).hexdigest())
PY
}
VECTOR=$(derive "$(printf '11%.0s' $(seq 32))" sso session-1)
check "the fixture derives ChatGptProofTest's pinned vector" test "$VECTOR" = \
    "ad11f9e55facd04df98d86f8b541d815b6a70c02e3f86eccc2207180b5271082 1f9f4bb27ccf73b6b3b1043fa1381ca38631be1068353fe4b30e89e7929b0eb1"
if [ "${PLAIN_CONTAINER:-0}" = 1 ]; then
    SECRET=$(openssl rand -hex 32)
    gx sh -c "install -d -m 755 /run/isx && umask 077 && printf '%s\n' $SECRET > /run/isx/instance-secret && chgrp 1000 /run/isx/instance-secret && chmod 440 /run/isx/instance-secret"
    incus config set "$INSTANCE" user.incus-spawn.static-ip="$GUEST_IP" \
        user.incus-spawn.instance-secret-sha256="$(printf '%s' "$SECRET" | sha256sum | cut -d' ' -f1)"
fi
SECRET=$(gx cat /run/isx/instance-secret | tr -d '[:space:]')
[ ${#SECRET} = 64 ] || die "$INSTANCE holds no instance secret"
read -r REFRESH_PROOF ACCESS_PROOF <<< "$(derive "$SECRET" "$ACCOUNT" "$SESSION_ID")"
sha() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
incus config set "$INSTANCE" user.incus-spawn.account.openai="$ACCOUNT" \
    user.incus-spawn.chatgpt-session="$ACCOUNT:$SESSION_ID" \
    user.incus-spawn.chatgpt-access-proof-sha256="$(sha "$ACCESS_PROOF")" \
    user.incus-spawn.chatgpt-refresh-proof-sha256="$(sha "$REFRESH_PROOF")" \
    user.incus-spawn.chatgpt-proof-secret-sha256="$(sha "$SECRET")"
# Until the proxy says it re-read the registry: it answers /health before it handles signals, and a
# lost signal would leave it serving the verifiers it had before this binding
for _ in $(seq 1 40); do
    kill -USR1 "$REAL_PROXY_PID"
    sleep 0.25
    grep -q "Received SIGUSR1" "$WORK/proxy.log" && break
done
sleep 0.5
check_not "no proof is in the instance's metadata" sh -c "incus config show '$INSTANCE' | grep -qE '$REFRESH_PROOF|$ACCESS_PROOF|$SECRET'"

# --- 5. Guest-side egress: host-enforced, whatever guest root does ---
echo ""
echo "[5] The guest cannot get out, even as root with its own rules flushed (#1137)"
sudo nft -f - <<EOF || die "could not install the guest egress table"
table inet isx_chatgpt_guest {
  chain from_guests {
    type filter hook forward priority -10; policy accept;
    # The control counts the instance under test; the second rule drops everything else the
    # bridge would route off the host, whatever address guest root gives itself
    iifname "incusbr0" ip saddr $GUEST_IP counter name guest_dropped drop
    iifname "incusbr0" drop
  }
  counter guest_dropped {}
}
EOF
gx sh -c 'iptables -F 2>/dev/null; iptables -P OUTPUT ACCEPT 2>/dev/null; nft flush ruleset 2>/dev/null; true'
GUEST_BEFORE=$(counter isx_chatgpt_guest guest_dropped)
check_not "guest root: a public address cannot be reached (TCP 1.1.1.1:443)" gx curl -sS -m 5 -o /dev/null https://1.1.1.1
check_not "guest root: a public resolver cannot be reached (TCP 8.8.8.8:53)" gx curl -sS -m 5 -o /dev/null telnet://8.8.8.8:53
check_not "guest root: chatgpt.com by a public address cannot be reached" \
    gx curl -sS -m 5 -o /dev/null --resolve chatgpt.com:443:104.18.32.47 https://chatgpt.com
GUEST_AFTER=$(counter isx_chatgpt_guest guest_dropped)
check "the host table dropped them (counter $GUEST_BEFORE -> $GUEST_AFTER)" test "$GUEST_AFTER" -gt "$GUEST_BEFORE"
check "the guest still reaches the proxy (positive control)" gx curl -sf -m 5 -o /dev/null "http://$GATEWAY:18080/health"
check "chatgpt.com resolves to the proxy in the guest" sh -c "incus exec '$INSTANCE' -- getent ahostsv4 chatgpt.com | grep -q '$GATEWAY'"

# --- 6. Guest files: bootstrapped through the proxy's own refresh answer ---
echo ""
echo "[6] The guest's auth.json, from the proxy"
REFRESH_TOKEN="rt.isx_$REFRESH_PROOF"
gu "curl -sS -m 20 -o /tmp/bootstrap.json -w '%{http_code}' -H 'Content-Type: application/json' \
    -d '{\"client_id\":\"$CODEX_CLIENT_ID\",\"grant_type\":\"refresh_token\",\"refresh_token\":\"$REFRESH_TOKEN\"}' \
    https://auth.openai.com/oauth/token" > "$WORK/bootstrap.status"
check "the proxy answers a refresh with the current proof (HTTP $(cat "$WORK/bootstrap.status"))" grep -qx 200 "$WORK/bootstrap.status"
gu "python3 - <<'PY'
import datetime, json, os
t = json.load(open('/tmp/bootstrap.json'))
auth = {'auth_mode': 'chatgpt', 'OPENAI_API_KEY': None,
        'tokens': {'id_token': t['id_token'], 'access_token': t['access_token'],
                   'refresh_token': t['refresh_token'], 'account_id': '$ACCOUNT_ID'},
        'last_refresh': datetime.datetime.now(datetime.timezone.utc).isoformat()}
os.makedirs(os.path.expanduser('~/.codex'), exist_ok=True)
json.dump(auth, open(os.path.expanduser('~/.codex/auth.json'), 'w'))
open(os.path.expanduser('~/.codex/config.toml'), 'w').write(
    'forced_login_method = \"chatgpt\"\ncheck_for_update_on_startup = false\napproval_policy = \"never\"\n')
os.remove('/tmp/bootstrap.json')
PY"
check "a fresh session's refresh is answered by the proxy, not upstream" test "$(rotations)" = 0
check "the guest holds the refresh placeholder, not a real token" sh -c "incus exec '$INSTANCE' -- grep -q '$REFRESH_TOKEN' /home/agentuser/.codex/auth.json"
check "real Codex reads it as a ChatGPT login" sh -c "incus exec '$INSTANCE' -- su - agentuser -c 'codex login status' 2>&1 | grep -q 'Logged in using ChatGPT'"
ACCESS_TOKEN=$(gx python3 -c "import json; print(json.load(open('/home/agentuser/.codex/auth.json'))['tokens']['access_token'])")

# --- 7. Real Codex ---
echo ""
echo "[7] Real Codex through the proxy"
# Time passes: the host's real token is now 4 minutes from expiry (as if refreshed elsewhere, same
# session), and so is the guest's copy. Codex refreshes before it does anything, and since the real
# token needs it, the proxy has to relay that refresh upstream.
control -X POST "http://127.0.0.1:$CONTROL_PORT/mint?ttl=240" > "$WORK/near-expiry.json"
python3 - "$WORK/near-expiry.json" "$SESSION_ID" "$CONFIG_DIR/openai/$ACCOUNT/session.json" <<'PY'
import json, os, sys
tokens = json.load(open(sys.argv[1]))
session = json.load(open(sys.argv[3]))
assert session["session_id"] == sys.argv[2]
session["tokens"], session["generation"] = tokens, session["generation"] + 1
tmp = sys.argv[3] + ".tmp"
fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.write(fd, json.dumps(session).encode()); os.close(fd); os.replace(tmp, sys.argv[3])
PY
gu "python3 - <<'PY'
import base64, json, os, time
path = os.path.expanduser('~/.codex/auth.json')
auth = json.load(open(path))
head, payload, sig = auth['tokens']['access_token'].split('.')
claims = json.loads(base64.urlsafe_b64decode(payload + '=' * (-len(payload) % 4)))
claims['exp'] = int(time.time()) + 240
auth['tokens']['access_token'] = '.'.join([head, base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip('='), sig])
json.dump(auth, open(path, 'w'))
PY"
# Where the proxy's and the stub's logs stand before Codex's turns, so what they caused can be told apart
TURNS_LOG_FROM=$(( $(wc -l < "$WORK/proxy.log") + 1 ))
TURNS_STUB_FROM=$(stub_requests)
gu "cd /tmp && timeout 120 codex exec --skip-git-repo-check 'say hi' </dev/null" > "$WORK/codex-ws.out" 2>&1
echo "    codex exec exited $? ($(grep -c . "$WORK/codex-ws.out") lines of output)"
REFRESHES=$(rotations)
check "Codex's own refresh went upstream through the relay, exactly once (stub rotations: $REFRESHES)" \
    test "$REFRESHES" = 1
check "the host session took the rotated tokens" python3 - "$CONFIG_DIR/openai/$ACCOUNT/session.json" "$WORK/near-expiry.json" "$SESSION_ID" <<'PY'
import json, sys
session, before = json.load(open(sys.argv[1])), json.load(open(sys.argv[2]))
sys.exit(0 if session["session_id"] == sys.argv[3] and session["tokens"]["refresh_token"] != before["refresh_token"]
         and session["generation"] == 2 and "refreshing" not in session else 1)
PY
check "a turn completed over WebSocket" grep -q "stub turn over websocket" "$WORK/codex-ws.out"
check "workspace discovery reached the stub" stub_saw GET /backend-api/wham/accounts/check
# The stub refuses WebSocket upgrades, so Codex falls back to HTTP, as it does when a network blocks them
control -X POST "http://127.0.0.1:$CONTROL_PORT/fault?name=ws-unavailable&count=100" >/dev/null
gu "cd /tmp && timeout 180 codex exec --skip-git-repo-check 'say hi again' </dev/null" > "$WORK/codex-http.out" 2>&1
echo "    codex exec exited $? ($(grep -c . "$WORK/codex-http.out") lines of output)"
check "a turn completed over HTTP" grep -q "stub turn over http" "$WORK/codex-http.out"
control -X POST "http://127.0.0.1:$CONTROL_PORT/fault?name=ws-unavailable&count=0" >/dev/null
control "http://127.0.0.1:$CONTROL_PORT/log" > "$WORK/stub-log.json"
check "the proxy refused some of Codex's other calls during its turns" \
    logged_since "$TURNS_LOG_FROM" "$WORK/proxy.log" "not an operation ChatGPT sessions allow"
# ChatGptRoute.Operation, kept apart on purpose so widening it fails here: a GET of codex/responses
# only as the upgrade, client_version 1-64 (POST /oauth/revoke is answered by the proxy, never relayed)
check "everything Codex's two turns sent the stub is on the allowlist" python3 - "$WORK/stub-log.json" "$WORK/off-allowlist.txt" "$TURNS_STUB_FROM" <<'PY'
import json, re, sys
allowed = [("POST", "auth.openai.com", r"/oauth/token", False), ("POST", "chatgpt.com", r"/backend-api/codex/responses", False),
           ("GET", "chatgpt.com", r"/backend-api/codex/responses", True), ("WS", "chatgpt.com", r"/backend-api/codex/responses", False),
           ("GET", "chatgpt.com", r"/backend-api/codex/models(\?client_version=[0-9A-Za-z.+-]{1,64})?", False),
           ("GET", "chatgpt.com", r"/backend-api/wham/accounts/check", False)]
def on_list(e):
    upgrade = e.get("upgrade", "").lower() == "websocket"
    return any(m == e["method"] and h == e["host"] and re.fullmatch(p, e["path"]) and u == upgrade
               for m, h, p, u in allowed)
off = ["%s %s %s" % (e["method"], e["host"], e["path"]) for e in json.load(open(sys.argv[1]))[int(sys.argv[3]):] if not on_list(e)]
open(sys.argv[2], "w").write("".join(line + "\n" for line in off))
sys.exit(1 if off else 0)
PY
[ -s "$WORK/off-allowlist.txt" ] && sed 's/^/      off the allowlist: /' "$WORK/off-allowlist.txt"
check "the stub saw Codex's model list request with a stub-minted bearer" python3 - "$WORK/stub-log.json" <<'PY'
import json, sys
log = json.load(open(sys.argv[1]))
hits = [e for e in log if e["path"].startswith("/backend-api/codex/models")]
sys.exit(0 if hits and all(len(e["authorization"]) == 1 for e in hits) else 1)
PY
check_not "no isx placeholder ever reached the stub" grep -q "isx_" "$WORK/stub-log.json"
check "every backend request the stub saw carried one bearer and the session's account" python3 - "$WORK/stub-log.json" "$ACCOUNT_ID" <<'PY'
import json, sys
log, account = json.load(open(sys.argv[1])), sys.argv[2]
backend = [e for e in log if e["host"] == "chatgpt.com" and e["method"] != "WS"]
sys.exit(0 if all(len(e["authorization"]) == 1 and e["account_id"] == [account] for e in backend) else 1)
PY

# --- 8. Both transports, directly ---
echo ""
echo "[8] HTTP and WebSocket injection, and what is refused"
status() { gu "curl -sS -m 20 -o /dev/null -w '%{http_code}' $1"; }
check "POST /backend-api/codex/responses with the current proof: 200" test "$(status "-X POST -H 'Authorization: Bearer $ACCESS_TOKEN' -d '{}' https://chatgpt.com/backend-api/codex/responses")" = 200
WS_STATUS=$(gu "curl -sS -m 5 --http1.1 -o /dev/null -w '%{http_code}' -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
    -H 'Authorization: Bearer $ACCESS_TOKEN' https://chatgpt.com/backend-api/codex/responses" 2>/dev/null)
check "a WebSocket upgrade with the current proof: 101 (got $WS_STATUS)" test "$WS_STATUS" = 101
control "http://127.0.0.1:$CONTROL_PORT/log" > "$WORK/stub-log.json"
check "the stub saw the WebSocket upgrade with a stub-minted bearer" python3 - "$WORK/stub-log.json" <<'PY'
import json, sys
log = json.load(open(sys.argv[1]))
ups = [e for e in log if e["upgrade"].lower() == "websocket"]
sys.exit(0 if ups and all(len(e["authorization"]) == 1 and "isx_" not in e["authorization"][0] for e in ups) else 1)
PY
read -r OTHER_REFRESH OTHER_ACCESS <<< "$(derive "$(openssl rand -hex 32)" "$ACCOUNT" "$SESSION_ID")"
read -r OLD_REFRESH OLD_ACCESS <<< "$(derive "$SECRET" "$ACCOUNT" "previous-session")"
read -r HASH_REFRESH HASH_ACCESS <<< "$(derive "$(printf '%s' "$SECRET" | sha256sum | cut -d' ' -f1)" "$ACCOUNT" "$SESSION_ID")"
HEAD_PART=${ACCESS_TOKEN%.*}
LOG_BEFORE=$(stub_requests)
for case in "another instance's:$OTHER_ACCESS" "a previous session's:$OLD_ACCESS" "one keyed with sha256(secret):$HASH_ACCESS" \
            "the refresh proof as access:$REFRESH_PROOF" "the static placeholder:placeholder"; do
    name=${case%%:*}; proof=${case#*:}
    check "$name access proof: 401" test "$(status "-X POST -H 'Authorization: Bearer $HEAD_PART.isx_$proof' -d '{}' https://chatgpt.com/backend-api/codex/responses")" = 401
done
for case in "another instance's:$OTHER_REFRESH" "a previous session's:$OLD_REFRESH" "one keyed with sha256(secret):$HASH_REFRESH" \
            "the access proof as refresh:$ACCESS_PROOF"; do
    name=${case%%:*}; proof=${case#*:}
    check "$name refresh proof: 401" test "$(status "-H 'Content-Type: application/json' -d '{\"client_id\":\"$CODEX_CLIENT_ID\",\"grant_type\":\"refresh_token\",\"refresh_token\":\"rt.isx_$proof\"}' https://auth.openai.com/oauth/token")" = 401
done
check "an operation off the allowlist with the current proof: 403" test "$(status "-H 'Authorization: Bearer $ACCESS_TOKEN' https://chatgpt.com/backend-api/conversations")" = 403
check "an encoded path with the current proof: 400" test "$(status "-X POST -H 'Authorization: Bearer $ACCESS_TOKEN' -d '{}' --path-as-is https://chatgpt.com/backend-api/codex/responses%2f..%2f..%2fconversations")" = 400
check "the guest's logout is answered here: 200" test "$(status "-H 'Content-Type: application/json' -d '{\"token\":\"$REFRESH_TOKEN\"}' https://auth.openai.com/oauth/revoke")" = 200
control "http://127.0.0.1:$CONTROL_PORT/log" > "$WORK/stub-log.json"
check "none of the refused requests reached the stub" test "$(stub_requests)" = "$LOG_BEFORE"
check "the host session survived the guest's logout" test "$(control "http://127.0.0.1:$CONTROL_PORT/state" | python3 -c "import json,sys; print(json.load(sys.stdin)['revokes'])")" = 0

# --- 9. Leaks ---
echo ""
echo "[9] No stub-minted token anywhere the guest can read"
control "http://127.0.0.1:$CONTROL_PORT/minted" | python3 -c "import json,sys; print('\n'.join(json.load(sys.stdin)))" > "$WORK/minted.txt"
incus file push "$WORK/minted.txt" "$INSTANCE/root/isx-minted.txt" >/dev/null
check "$(wc -l < "$WORK/minted.txt") tokens minted, none in the guest's files" sh -c "! incus exec '$INSTANCE' -- grep -rlFf /root/isx-minted.txt /home /etc /run /tmp /var/tmp /opt --exclude=isx-minted.txt 2>/dev/null | grep -q ."
check "none in the guest's journal" sh -c "! incus exec '$INSTANCE' -- sh -c 'journalctl --no-pager 2>/dev/null | grep -Ff /root/isx-minted.txt' | grep -q ."
check "none in agentuser's environment" sh -c "! incus exec '$INSTANCE' -- su - agentuser -c env | grep -Ff '$WORK/minted.txt' | grep -q ."
check "none in the proxy's log" sh -c "! grep -qFf '$WORK/minted.txt' '$WORK/proxy.log'"
gx rm -f /root/isx-minted.txt

# --- 10. The user's own state ---
echo ""
echo "[10] The user's own isx config is untouched"
check "nothing in $REAL_CONFIG_DIR changed" test "$(real_state)" = "$REAL_STATE_BEFORE"
check_not "no ChatGPT session was written there" test -e "$REAL_CONFIG_DIR/openai/$ACCOUNT/session.json"

# --- 11. Nothing left the machine ---
echo ""
echo "[11] Nothing tried to leave"
check_not "the proxy logged no upstream connect or DNS failure" grep -qiE "Upstream connect error|UnknownHost|resolve" "$WORK/proxy.log"

echo ""
echo "========================================"
echo " Results: $PASS passed, $FAIL failed"
echo "========================================"
if [ "$FAIL" -gt 0 ]; then
    echo -e "Failures:\n$ERRORS"
    echo "--- proxy log ---"; tail -50 "$WORK/proxy.log"
    echo "--- codex ---"; tail -30 "$WORK/codex-ws.out"
    exit 1
fi
