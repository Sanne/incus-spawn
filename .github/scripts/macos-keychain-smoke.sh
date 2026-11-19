#!/usr/bin/env bash
# A native isx on macOS trusts a CA an administrator trusts for SSL in the System keychain, as
# a TLS-inspection agent installs it, and counts one an administrator denied (#1467): `isx
# doctor` reports both.
# Given the native isx-proxy too, it also checks that both binaries refuse a handshake with a
# chain through a denied root's cross-signed copy, the shape api.github.com sends (#1552, #1631).
# /etc/hosts points api.github.com at a local server for that, and a made-up lease stands in for
# the VM the proxy looks for.
# Changes the machine's trust settings, /etc/hosts and /var/db/dhcpd_leases: run it only on a
# disposable CI runner.
# Usage: macos-keychain-smoke.sh <isx binary> [<isx-proxy binary>]
set -euo pipefail

isx=$1
proxy=${2:-}
keychain=/Library/Keychains/System.keychain
work=$(mktemp -d)

# macOS has no timeout(1): a hang fails the step with what it was doing, not at the job's limit
limit() { local s=$1; shift; perl -e 'alarm shift; exec @ARGV' "$s" "$@"; }

cat > "$work/ca.cnf" <<'CNF'
[req]
distinguished_name = dn
prompt = no
[dn]
CN = placeholder
[v3_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
CNF
# One CA per case, never re-used: changing or removing a certificate's admin trust settings on a
# runner can wait for an authorization nobody gives (#1515), and nothing here needs to.
ca_subject() { echo "/CN=isx keychain smoke $1 ${GITHUB_RUN_ID:-local}-$$"; }
new_ca() {
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 -keyout "$work/$1.key" -out "$work/$1.pem" \
    -subj "$(ca_subject "$1")" -config "$work/ca.cnf" -extensions v3_ca 2>/dev/null
}
fingerprint() { openssl x509 -in "$work/$1.pem" -noout -fingerprint "-$2" | cut -d= -f2 | tr -d :; }
new_ca trusted
new_ca denied
trusted=$(fingerprint trusted sha256 | tr 'A-F' 'a-f')

pids=()
hosts_added=
lease_written=
marker_written=
ca_made=
cleanup() {
  for pid in ${pids[@]+"${pids[@]}"}; do kill "$pid" 2>/dev/null || true; done
  [ ! -f "$work/server.pid" ] || sudo -n kill "$(cat "$work/server.pid")" 2>/dev/null || true
  if [ -n "$hosts_added" ]; then
    sudo -n sed -i '' '/ # isx-keychain-smoke$/d' /etc/hosts || true
    flush_dns
  fi
  [ -z "$lease_written" ] || sudo -n rm -f "$leases" || true
  [ -z "$marker_written" ] || rm -f "$config/.init-complete" "$config/config.yaml"
  [ -z "$ca_made" ] || rm -f "$config/ca.crt" "$config/ca.key"
  for ca in trusted denied older crossed; do
    [ -f "$work/$ca.pem" ] || continue
    limit 30 sudo -n security delete-certificate -Z "$(fingerprint "$ca" sha1)" "$keychain" >/dev/null 2>&1 || true
  done
  rm -rf "$work"
}
flush_dns() { sudo -n dscacheutil -flushcache || true; sudo -n killall -HUP mDNSResponder 2>/dev/null || true; }
leases=/var/db/dhcpd_leases
trap cleanup EXIT

# doctor exits non-zero here (no isx VM on a runner); only its findings matter
doctor() {
  local out rc=0
  out=$(limit 120 "$isx" doctor --format=json 2>"$work/doctor.err") || rc=$?
  # 142 is SIGALRM: the time limit, not one of doctor's own exit codes
  if [ "$rc" = 142 ]; then echo "::error::isx doctor did not finish in 120s" >&2; cat "$work/doctor.err" >&2; exit 1; fi
  echo "$out"
}

echo "isx doctor before either CA is in the keychain"
before=$(doctor)
if echo "$before" | grep -q "$trusted"; then
  echo "::error::isx trusted the smoke CA before it was added to the keychain"
  exit 1
fi

echo "Trusting one CA and denying the other in the System keychain"
limit 60 sudo -n security add-trusted-cert -d -r trustRoot -k "$keychain" "$work/trusted.pem"
limit 60 sudo -n security add-trusted-cert -d -r deny -k "$keychain" "$work/denied.pem"

start=$(date +%s)
after=$(doctor)
echo "doctor took $(( $(date +%s) - start ))s"
echo "$after" | grep -A2 '"Keychain CAs trusted for TLS"' || true
if ! echo "$after" | grep -q "$trusted"; then
  echo "::error::isx doctor does not list the CA an administrator trusts in the System keychain"
  echo "$after"
  exit 1
fi
if ! echo "$after" | grep -qF "1 denied (security dump-trust-settings"; then
  echo "::error::isx doctor does not count the CA an administrator denied"
  echo "$after"
  exit 1
fi
echo "Keychain doctor checks passed"
[ -n "$proxy" ] || { echo "Keychain trust smoke passed"; exit 0; }

# The handshake: the shape of api.github.com's chain, where Sectigo's Root E46 comes cross-signed
# by USERTrust ECC (#1552). A leaf for api.github.com, an intermediate the crossed root signed, and
# the crossed root's copy signed by the older root. The older root is trusted and the self-signed
# crossed root is denied, so only a match on subject and key refuses the chain, as in
# KeychainTrustMacTest's cross-signed case.
grep -q '[[:space:]]api\.github\.com\([[:space:]]\|$\)' /etc/hosts \
  && { echo "::error::/etc/hosts already names api.github.com"; exit 1; }
[ ! -e "$leases" ] || { echo "::error::$leases exists; this runner is not one to fake a VM's lease on"; exit 1; }

new_ca older
new_ca crossed
openssl req -new -key "$work/crossed.key" -subj "$(ca_subject crossed)" -out "$work/crossed.csr" 2>/dev/null
openssl x509 -req -in "$work/crossed.csr" -CA "$work/older.pem" -CAkey "$work/older.key" -CAcreateserial -days 1 \
  -extfile "$work/ca.cnf" -extensions v3_ca -out "$work/crossed-by-older.pem" 2>/dev/null
openssl req -new -newkey rsa:2048 -nodes -keyout "$work/intermediate.key" -subj "/CN=isx keychain smoke intermediate $$" \
  -out "$work/intermediate.csr" 2>/dev/null
openssl x509 -req -in "$work/intermediate.csr" -CA "$work/crossed.pem" -CAkey "$work/crossed.key" -CAcreateserial -days 1 \
  -extfile "$work/ca.cnf" -extensions v3_ca -out "$work/intermediate.pem" 2>/dev/null
openssl req -new -newkey rsa:2048 -nodes -keyout "$work/leaf.key" -subj "/CN=api.github.com" -out "$work/leaf.csr" 2>/dev/null
openssl x509 -req -in "$work/leaf.csr" -CA "$work/intermediate.pem" -CAkey "$work/intermediate.key" -CAcreateserial -days 1 \
  -extfile <(printf 'subjectAltName=DNS:api.github.com\nextendedKeyUsage=serverAuth\n') -out "$work/leaf.pem" 2>/dev/null
cat "$work/leaf.pem" "$work/intermediate.pem" "$work/crossed-by-older.pem" > "$work/chain.pem"

# Stands in for GitHub's release list, with one release whose tag nothing else has. Port 443 on
# loopback alone takes root on macOS; only the wildcard address does not.
tag="fedora-44-smoke$$"
sudo -n python3 -I - "$work/chain.pem" "$work/leaf.key" "$tag" "$work/server.pid" > "$work/server.log" 2>&1 <<'EOF' &
import http.server, json, os, ssl, sys
body = json.dumps([{"tag_name": sys.argv[3], "published_at": "2026-01-01T00:00:00Z", "assets": []}]).encode()
class Releases(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(sys.argv[1], sys.argv[2])
class Server(http.server.ThreadingHTTPServer):
    # One line per handshake a client aborted, which the checks count
    def get_request(self):
        sock, address = self.socket.accept()
        try:
            return context.wrap_socket(sock, server_side=True), address
        except ssl.SSLError as e:
            print("handshake refused by the client: %s" % e.reason, flush=True)
            sock.close()
            raise
server = Server(("127.0.0.1", 443), Releases)
with open(sys.argv[4], "w") as pid:
    pid.write(str(os.getpid()))
server.serve_forever()
EOF
# Out of the job table, so bash does not print the heredoc when cleanup ends it
disown
echo "127.0.0.1 api.github.com # isx-keychain-smoke" | sudo -n tee -a /etc/hosts >/dev/null
hosts_added=1
flush_dns
for _ in $(seq 1 20); do [ -s "$work/server.pid" ] && break; sleep 0.5; done
[ -s "$work/server.pid" ] || { echo "::error::the stand-in for api.github.com did not start"; cat "$work/server.log"; exit 1; }

# GITHUB_TOKEN would be sent to the stand-in
releases() { limit 60 env -u GITHUB_TOKEN "$isx" update-base --list --format=json 2>"$work/releases.err"; }

# The proxy looks for its VM's address in the leases, then for the host's on the same /24: a lease
# on loopback's network makes it listen on 127.0.0.1. It reads the runner's own isx home, since a
# native image takes user.home from the account, and starts only where isx init has run.
config="$HOME/.config/incus-spawn"
[ ! -e "$config/.init-complete" ] && [ ! -e "$config/config.yaml" ] \
  || { echo "::error::isx init has run on this machine; this runner is not a disposable one"; exit 1; }
mkdir -p "$config"
# The proxy makes its CA on first start; one it made here goes with the rest
[ -e "$config/ca.crt" ] || [ -e "$config/ca.key" ] || ca_made=1
# Any version at least isx's own: what isx init leaves once it has run
echo 2147483647 > "$config/.init-complete"
marker_written=1
# The proxy intercepts api.github.com only for a GitHub account; the host's own request is never
# given its token, and the stand-in would be the only one to see it
printf 'github:\n  accounts:\n    smoke: {token: ghp-isx-keychain-smoke}\n  default: smoke\n' > "$config/config.yaml"
printf '{\n\tname=isx\n\tip_address=127.0.0.2\n\thw_address=1,4a:53:58:0:0:1\n\tlease=0x7fffffff\n}\n' \
  | sudo -n tee "$leases" >/dev/null
lease_written=1
proxy_pid=
start_proxy() {
  "$proxy" < /dev/null > "$work/proxy-$1.log" 2>&1 &
  proxy_pid=$!
  pids+=("$proxy_pid")
  for _ in $(seq 1 60); do curl -sf --max-time 2 http://127.0.0.1:18080/health >/dev/null && return 0; sleep 0.5; done
  echo "::error::isx-proxy did not answer its health endpoint"
  cat "$work/proxy-$1.log"
  exit 1
}
stop_proxy() { kill "$proxy_pid" 2>/dev/null || true; wait "$proxy_pid" 2>/dev/null || true; }
# The host's own request, which the proxy relays upstream without a credential
through_proxy() {
  curl -s --max-time 60 -o "$work/proxied.out" -w '%{http_code}' --cacert "$config/ca.crt" \
    --resolve api.github.com:18443:127.0.0.1 https://api.github.com:18443/repos/Sanne/incus-spawn-images/releases || true
}

echo "Trusting the older root; the chain through the crossed root's copy must be accepted"
limit 60 sudo -n security add-trusted-cert -d -r trustRoot -k "$keychain" "$work/older.pem"
if ! out=$(releases) || ! echo "$out" | grep -q "$tag"; then
  echo "::error::native isx did not read the stand-in's release list through a trusted chain"
  cat "$work/releases.err" "$work/server.log"
  exit 1
fi
start_proxy trusted
status=$(through_proxy)
stop_proxy
if [ "$status" != 200 ] || ! grep -q "$tag" "$work/proxied.out"; then
  echo "::error::native isx-proxy did not relay the stand-in's answer through a trusted chain (HTTP $status)"
  cat "$work/proxied.out" "$work/proxy-trusted.log"
  exit 1
fi

# Each refusal below is a handshake that reached the stand-in and that the client then aborted.
# Java's ends in an EOF rather than an alert the stand-in can name.
refusals() { grep -c "handshake refused by the client" "$work/server.log" || true; }
[ "$(refusals)" = 0 ] || { echo "::error::a client refused the trusted chain"; cat "$work/server.log"; exit 1; }

echo "Denying the self-signed crossed root; the same chain must now be refused"
limit 60 sudo -n security add-trusted-cert -d -r deny -k "$keychain" "$work/crossed.pem"
if releases >/dev/null; then
  echo "::error::native isx read the release list through a chain whose root an administrator denied"
  exit 1
fi
if ! grep -q "denied in the Keychain" "$work/releases.err"; then
  echo "::error::native isx failed, but not on the Keychain's deny"
  cat "$work/releases.err"
  exit 1
fi
grep "denied in the Keychain" "$work/releases.err"
cli_refusals=$(refusals)
[ "$cli_refusals" -ge 1 ] || { echo "::error::the stand-in saw no handshake native isx refused"; cat "$work/server.log"; exit 1; }
start_proxy denied
status=$(through_proxy)
stop_proxy
# The proxy's line does not say why the handshake failed; the stand-in's does
if [ "$status" != 502 ] || [ "$(refusals)" -le "$cli_refusals" ]; then
  echo "::error::native isx-proxy did not refuse a chain whose root an administrator denied (HTTP $status)"
  cat "$work/proxied.out" "$work/proxy-denied.log" "$work/server.log" 2>/dev/null || true
  exit 1
fi
grep "Relay connect error (api.github.com)" "$work/proxy-denied.log"
echo "Keychain trust smoke passed"
