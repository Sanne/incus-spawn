#!/usr/bin/env bash
set -uo pipefail

# Run isx and isx-proxy in their macOS roles against the appliance, on Linux (#1800).
#
# Both binaries take their platform role from the OS they run on, and nothing at run time in a
# released one changes it (PlatformRoleFeature, scripts/check-platform-role.sh). So this runs the
# macOS-role test build instead (scripts/macos-role-build.sh): the release uber-jars with a Platform
# that says macOS. It is never released.
#
# Everything else is as on a Mac, with stand-ins for what only macOS has:
#  - vfkit: QEMU boots the appliance with isx's kernel command line (isx.proxy=remote) and the MAC
#    isx gives the VM. Its Incus and agent vsock ports are bridged by socat onto the sockets isx
#    looks for, and vm.pid names QEMU, which VmManager.isRunning() accepts.
#  - vfkit's NAT: a tap device holds the host's address, dnsmasq leases the VM an address and
#    serves it DNS, and the host masquerades the VM's traffic to the internet, which the builds need.
#    So every container reaches the proxy from the VM's address, as on a Mac.
#  - macOS's DHCP leases: /var/db/dhcpd_leases names the VM's lease, which is where
#    VmNetwork.discoverHostBridgeIp() looks; ifconfig (net-tools) then finds the host's address.
#  - the Keychain: /usr/bin/security answers the admin domain's trust settings with one CA trusted
#    for SSL, which is the only thing that makes the proxy trust the stand-in for api.github.com.
#  - GitHub's API: .github/scripts/echo-server.py answers /user as the account whose token reached
#    it, so the checks see which account each caller was served. /etc/hosts points
#    api.github.com at it, for the proxy on the host alone; the instances reach it through the proxy.
#
# The checks are the ones a #1582-style regression fails: a template build that runs gh is served
# the template's account, a branch pinned with --account is served its pin, the proxy refuses no
# known instance's proof, an --mcp-client branch reaches mcp.isx.internal, and what is not an
# instance (a wrong proof, the host's own traffic) gets no credential.
#
# Needs root (tap device, NAT, DHCP, port 443, the stand-in files), qemu-system-<arch>, socat,
# dnsmasq, iptables, ifconfig, openssl, python3, curl, the incus client, java 25, /dev/kvm and
# /dev/vhost-vsock usable by RUN_AS, and internet access. It refuses to run where /usr/bin/security
# or /var/db/dhcpd_leases already exist, or /etc/hosts names api.github.com. It runs isx as RUN_AS (default: SUDO_USER) under a home
# of its own, so it never reads or writes that user's isx configuration.
#
# Usage:  sudo ./test-macos-role.sh <build-dir> <macos-role-cli-jar> <macos-role-proxy-jar>
#
#   build-dir          holds vmlinuz and disk.img, booted with snapshot=on (left unchanged).
#   BOOT_DEADLINE      seconds to wait for ISX READY (default 120).
#   KEEP_LOGS          directory to copy the console, proxy and build logs into.
#   HOLD               when set, waits before cleaning up until the file it names is removed,
#                      so the instances, the VM and the proxy can be looked at.
#
# Prints one PASS/FAIL line per check and exits non-zero if any check failed.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=SCRIPTDIR/../.github/scripts/net-retry.sh
source "$ROOT/.github/scripts/net-retry.sh"
BUILD_DIR="${1:-}"
CLI_JAR="${2:-}"
PROXY_JAR="${3:-}"
BOOT_DEADLINE="${BOOT_DEADLINE:-120}"
RUN_AS="${RUN_AS:-${SUDO_USER:-}}"

# Not test-proxy-remote.sh's tap and network, so both can run on one host.
TAP=isxmr0
NET_PREFIX=192.168.178
HOST_IP=$NET_PREFIX.1
# VmNetwork.ISX_VM_MAC, and VmManager.kernelCmdline()'s gateway and MITM port.
VM_MAC=4a:53:58:00:00:01
GATEWAY=10.166.11.1
MITM_PORT=18443
LEASES=/var/db/dhcpd_leases
SECURITY=/usr/bin/security

die() { echo "ERROR: $*" >&2; exit 2; }
[ "$(id -u)" -eq 0 ] || die "needs root (run with sudo)"
[ -n "$BUILD_DIR" ] && [ -n "$CLI_JAR" ] && [ -n "$PROXY_JAR" ] \
    || die "usage: $0 <build-dir> <macos-role-cli-jar> <macos-role-proxy-jar>"
[ -n "$RUN_AS" ] && [ "$RUN_AS" != root ] || die "set RUN_AS to the unprivileged user to run isx as"
for f in "$BUILD_DIR/vmlinuz" "$BUILD_DIR/disk.img" "$CLI_JAR" "$PROXY_JAR"; do
    [ -f "$f" ] || die "$f not found"
done
for tool in socat dnsmasq iptables ifconfig openssl python3 incus java runuser curl sysctl; do
    command -v "$tool" >/dev/null || die "$tool not found"
done
# The stand-ins are files macOS has; this host must not have them already.
[ ! -e "$SECURITY" ] || die "$SECURITY exists; this host is not one to stand in for macOS on"
[ ! -e "$LEASES" ] || die "$LEASES exists; this host is not one to stand in for macOS on"
ip link show "$TAP" >/dev/null 2>&1 && die "$TAP exists, from another run: ip link del $TAP"
# The proxy must reach the stand-in for GitHub's API, which /etc/hosts names for this run.
grep -q '[[:space:]]api\.github\.com\([[:space:]]\|$\)' /etc/hosts \
    && die "/etc/hosts already names api.github.com; remove that line (a killed run leaves '# isx-test-macos-role')"

ARCH=$(uname -m)
case "$ARCH" in
    x86_64)  QEMU=qemu-system-x86_64;  MACHINE=pc;   CONSOLE=ttyS0 ;;
    aarch64) QEMU=qemu-system-aarch64; MACHINE=virt; CONSOLE=ttyAMA0 ;;
    *) die "unsupported architecture: $ARCH" ;;
esac
command -v "$QEMU" >/dev/null || die "$QEMU not found"
runuser -u "$RUN_AS" -- test -r /dev/kvm -a -w /dev/kvm || die "/dev/kvm is not usable by $RUN_AS"

WORK=$(mktemp -d)
chmod 755 "$WORK"
HOME_DIR=$WORK/home
STATE=$HOME_DIR/.local/state/incus-spawn
CONFIG=$HOME_DIR/.config/incus-spawn
CID=$(( 1000 + $$ % 50000 ))
PIDS=()
ADDED_HOSTS=""
# The host's net.ipv4.ip_forward before this run, put back on exit.
FORWARDING=""
MADE_VAR_DB=""
# The VM's way out, as vfkit's NAT gives it: table, then the rule.
NAT_RULES=(
    "nat POSTROUTING -s $NET_PREFIX.0/24 ! -d $NET_PREFIX.0/24 -j MASQUERADE"
    "filter FORWARD -i $TAP -j ACCEPT"
    "filter FORWARD -o $TAP -j ACCEPT"
)
# nat_rules -I adds them, nat_rules -D removes them.
nat_rules() {
    local rule op=$1
    for rule in "${NAT_RULES[@]}"; do
        # shellcheck disable=SC2086
        set -- $rule
        iptables -t "$1" "$op" "${@:2}"
    done
}

keep_logs() {
    [ -n "${KEEP_LOGS:-}" ] || return 0
    mkdir -p "$KEEP_LOGS"
    cp "$WORK"/*.log "$STATE"/proxy.log "$KEEP_LOGS"/ 2>/dev/null
    chmod -R a+r "$KEEP_LOGS" 2>/dev/null
}

cleanup() {
    keep_logs
    for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; done
    # QEMU and the proxy run as RUN_AS, under runuser: end what they started too.
    pkill -u "$RUN_AS" -f "$WORK/" 2>/dev/null
    for p in "${PIDS[@]}"; do wait "$p" 2>/dev/null; done
    nat_rules -D 2>/dev/null
    ip link del "$TAP" 2>/dev/null
    [ -n "$FORWARDING" ] && sysctl -qw net.ipv4.ip_forward="$FORWARDING"
    rm -f "$SECURITY" "$LEASES"
    [ -n "$MADE_VAR_DB" ] && rmdir /var/db 2>/dev/null
    [ -n "$ADDED_HOSTS" ] && sed -i "/^127\.0\.0\.1 api\.github\.com # isx-test-macos-role$/d" /etc/hosts
    rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

PASS=0
FAIL=0
pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }
summary() {
    if [ -n "${HOLD:-}" ]; then
        touch "$HOLD" && chown "$RUN_AS" "$HOLD"
        echo "Holding: $WORK (HOME=$HOME_DIR, INCUS_SOCKET=$STATE/vm.incus.sock); rm $HOLD to finish."
        while [ -e "$HOLD" ]; do sleep 1; done
    fi
    echo
    if [ "$FAIL" -eq 0 ]; then
        echo "All $PASS macOS role checks passed."
        exit 0
    fi
    echo "$FAIL of $((PASS + FAIL)) macOS role checks failed."
    echo "Last 30 proxy log lines:"
    tail -30 "$STATE/proxy.log" 2>/dev/null | sed 's/^/  /'
    exit 1
}
# $3, when given, is shown on failure. require stops at a check the rest depend on.
check() { if eval "$1"; then pass "$2"; else fail "$2${3:+ ($3)}"; fi; }
require() { check "$@" || true; [ "$FAIL" -eq 0 ] || summary; }

# As RUN_AS, in this run's home, with the macOS-role isx first on PATH: the proxy starts the
# host side of isx mcp as 'isx', and the checks' incus reaches the VM through the bridged socket.
as_user() {
    runuser -u "$RUN_AS" -- env HOME="$HOME_DIR" PATH="$WORK/bin:$PATH" \
        INCUS_SOCKET="$STATE/vm.incus.sock" INCUS_CONF="$WORK/incus-client" "$@"
}
# Every call is bounded, and none reads stdin: isx init would prompt, incus init would wait.
isx() { as_user timeout 600 "$WORK/bin/isx" "$@" < /dev/null; }
incus_() { as_user timeout 120 incus "$@" < /dev/null; }

echo "-- Stand-ins for vfkit's NAT, macOS's leases and Keychain, and GitHub --"
mkdir -p "$WORK/bin" "$WORK/echo" "$STATE" "$CONFIG"
chown -R "$RUN_AS" "$WORK"
# user.home is what isx reads, and Java takes it from the password database, not $HOME.
for pair in "isx:$CLI_JAR" "isx-proxy:$PROXY_JAR"; do
    printf '#!/bin/sh\nexec java -Duser.home=%s -jar %s "$@"\n' "$HOME_DIR" "${pair#*:}" > "$WORK/bin/${pair%%:*}"
    chmod 755 "$WORK/bin/${pair%%:*}"
done

ip tuntap add dev "$TAP" mode tap user "$RUN_AS" || die "could not create $TAP"
ip addr add "$HOST_IP/24" dev "$TAP"
ip link set "$TAP" up
# Put back on exit. Turning it off again also resets every interface's forwarding, as the kernel
# does whenever the global value changes: a host that forwards on one interface alone loses that.
FORWARDING=$(sysctl -n net.ipv4.ip_forward)
sysctl -qw net.ipv4.ip_forward=1 || die "could not turn on IPv4 forwarding, which the VM's way out needs"
nat_rules -I
dnsmasq --keep-in-foreground --interface="$TAP" --bind-interfaces --except-interface=lo \
    --dhcp-range="$NET_PREFIX.10,$NET_PREFIX.20,1h" --dhcp-option=option:router,"$HOST_IP" \
    --dhcp-option=option:dns-server,"$HOST_IP" --no-hosts \
    --dhcp-leasefile="$WORK/leases" --pid-file="$WORK/dnsmasq.pid" 2>"$WORK/dnsmasq.log" &
PIDS+=($!)

# The upstream's CA, which only the stand-in Keychain trusts: not isx's CA, not in any bundle.
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=isx-test-macos-role-upstream" \
    -keyout "$WORK/echo/ca.key" -out "$WORK/echo/ca.pem" 2>/dev/null
openssl req -new -newkey rsa:2048 -nodes -subj "/CN=api.github.com" \
    -keyout "$WORK/echo/leaf.key" -out "$WORK/echo/leaf.csr" 2>/dev/null
openssl x509 -req -in "$WORK/echo/leaf.csr" -CA "$WORK/echo/ca.pem" -CAkey "$WORK/echo/ca.key" \
    -CAcreateserial -out "$WORK/echo/leaf.crt" -days 1 \
    -extfile <(printf 'subjectAltName=DNS:api.github.com') 2>/dev/null
python3 "$ROOT/.github/scripts/echo-server.py" "$WORK/echo/leaf.crt" "$WORK/echo/leaf.key" \
    > "$WORK/echo.log" 2>&1 &
PIDS+=($!)
echo "127.0.0.1 api.github.com # isx-test-macos-role" >> /etc/hosts
ADDED_HOSTS=1

# The admin domain trusts the upstream's CA for SSL; the user domain has no settings.
fingerprint=$(openssl x509 -in "$WORK/echo/ca.pem" -noout -fingerprint -sha1 | sed 's/.*=//; s/://g')
cat > "$WORK/admin-trust.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>trustList</key><dict>
<key>$fingerprint</key><dict><key>trustSettings</key><array><dict>
<key>kSecTrustSettingsPolicy</key><data>KoZIhvdjZAED</data>
<key>kSecTrustSettingsPolicyName</key><string>sslServer</string>
</dict></array></dict>
</dict><key>trustVersion</key><integer>1</integer></dict></plist>
EOF
cat > "$SECURITY" <<EOF
#!/bin/sh
# Stand-in for macOS's security(1), from appliance/test-macos-role.sh: the answers isx asks for.
case "\$1 \$2" in
    "trust-settings-export -d") cp "$WORK/admin-trust.plist" "\$3" ;;
    trust-settings-export*) echo "SecTrustSettingsCreateExternalRepresentation: No Trust Settings were found."; exit 1 ;;
    "find-certificate -a")
        [ "\$4" = /Library/Keychains/System.keychain ] && cat "$WORK/echo/ca.pem"
        exit 0 ;;
    *) echo "security stand-in: \$*: not supported" >&2; exit 1 ;;
esac
EOF
chmod 755 "$SECURITY" "$WORK/admin-trust.plist"
[ -d /var/db ] || { mkdir /var/db; MADE_VAR_DB=1; }

echo
echo "-- Boot (as isx boots it on macOS) --"
truncate -s 512M "$WORK/swap.img"
truncate -s 20G "$WORK/data.img"
chown "$RUN_AS" "$WORK/swap.img" "$WORK/data.img"
# VmManager.kernelCmdline(), less the clock and the shared folder, on the console QEMU has.
as_user setsid "$QEMU" -machine "$MACHINE" -enable-kvm -cpu host \
    -m 4G -smp 4 -nographic -no-reboot -nodefaults -serial stdio \
    -kernel "$BUILD_DIR/vmlinuz" \
    -drive file="$BUILD_DIR/disk.img",format=raw,if=virtio,snapshot=on \
    -drive file="$WORK/swap.img",format=raw,if=virtio \
    -drive file="$WORK/data.img",format=raw,if=virtio \
    -netdev tap,id=net0,ifname="$TAP",script=no,downscript=no \
    -device virtio-net-pci,netdev=net0,mac="$VM_MAC" \
    -device vhost-vsock-pci,guest-cid="$CID" \
    -pidfile "$WORK/qemu.pid" \
    -append "root=/dev/vda rootfstype=btrfs rw rootflags=commit=300 console=$CONSOLE isx.gateway=$GATEWAY isx.mitm_port=$MITM_PORT isx.ga_vsock=1024 isx.vsock_incus=8443 isx.agent_vsock=1025 isx.proxy=remote" \
    < /dev/null > "$WORK/console.log" 2>&1 &
PIDS+=($!)

start=$(date +%s)
until grep -q 'ISX READY' "$WORK/console.log" 2>/dev/null; do
    if ! kill -0 "${PIDS[-1]}" 2>/dev/null || [ $(( $(date +%s) - start )) -ge "$BOOT_DEADLINE" ]; then
        fail "appliance reached ISX READY"
        tail -20 "$WORK/console.log" | sed 's/^/  /'
        summary
    fi
    sleep 1
done
pass "appliance reached ISX READY after $(( $(date +%s) - start ))s"
VM_IP=$(awk '{print $3}' "$WORK/leases" 2>/dev/null | head -1)
require '[ -n "$VM_IP" ]' "the VM leased an address on the host network (${VM_IP:-none})"
# macOS writes each octet without its leading zero, as VmNetwork.normalizeMac expects.
printf '{\n\tname=isx\n\tip_address=%s\n\thw_address=1,%s\n\tlease=0x7fffffff\n}\n' \
    "$VM_IP" "$(sed 's/\(^\|:\)0\([0-9a-f]\)/\1\2/g' <<< "$VM_MAC")" > "$LEASES"
cp "$WORK/qemu.pid" "$STATE/vm.pid" && chown "$RUN_AS" "$STATE/vm.pid"
for lane in incus:8443 agent:1025; do
    as_user socat UNIX-LISTEN:"$STATE/vm.${lane%%:*}.sock",fork,backlog=128 VSOCK-CONNECT:"$CID":"${lane#*:}" \
        > /dev/null 2>&1 &
    PIDS+=($!)
done
api_up() {
    local deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        incus_ info >/dev/null 2>&1 && return 0
        sleep 1
    done
    return 1
}
require 'api_up' "Incus API reachable over the vsock tunnel"

echo
echo "-- isx and isx-proxy in their macOS roles --"
require 'isx --help 2>&1 | sed "s/\x1b\[[0-9;]*m//g" | grep -qE "^ +vm +"' "isx takes macOS's role (it has the vm command)"
isx init > "$WORK/init.log" 2>&1
require '[ -f "$CONFIG/ca.crt" ] && grep -q "macOS" "$WORK/init.log"' "isx init ran its macOS setup"
# The accounts: the default, the template's and a pin. The echo server answers /user as the
# account a ghp-acct-<name> token names.
python3 - "$CONFIG/config.yaml" <<'EOF' || die "config.yaml has no 'github: {}' line to replace"
import sys
path = sys.argv[1]
text = open(path).read()
if "github: {}\n" not in text:
    sys.exit(1)
open(path, "w").write(text.replace("github: {}\n", """github:
  accounts:
    ci: {token: ghp-acct-ci}
    tplacct: {token: ghp-acct-tplacct}
    agent: {token: ghp-acct-agent}
  default: ci
"""))
EOF
mkdir -p "$CONFIG/images" "$CONFIG/tools"
cp "$ROOT/.github/test-fixtures/tools/test-claude-stub.yaml" "$CONFIG/tools/"
cat > "$CONFIG/images/test-macos-role.yaml" <<'EOF'
name: tpl-macos-role
parent: tpl-minimal
tools:
  - gh
  - test-claude-stub
accounts:
  github: tplacct
EOF
chown -R "$RUN_AS" "$CONFIG"

as_user setsid "$WORK/bin/isx-proxy" < /dev/null > "$WORK/proxy-stdout.log" 2>&1 &
PIDS+=($!)
health() { curl -sf --max-time 2 "http://127.0.0.1:18080/health" >/dev/null; }
# The MITM listener may log its address a moment after /health answers.
listening() { grep -q "Listening on $HOST_IP:$MITM_PORT" "$STATE/proxy.log" 2>/dev/null; }
for _ in $(seq 1 60); do health && listening && break; sleep 0.5; done
require 'health' "isx-proxy answers its health endpoint on loopback, as on macOS"
check 'grep -q "Gateway IP: *$HOST_IP$" "$WORK/proxy-stdout.log"' \
    "isx-proxy found the host's address on the VM network from the leases and ifconfig ($HOST_IP)"
check '! grep -q "^  Incus:" "$WORK/proxy-stdout.log"' "isx-proxy takes macOS's role (no Incus server line)"
check 'listening' "isx-proxy listens on the VM network's host address"

echo
echo "-- A template build that runs gh is served the template's account --"
# A github.com outage outlasting isx's own download retries runs it again (#2042)
require 'retry_download_outage isx build tpl-minimal --yes > "$WORK/build-minimal.log" 2>&1' "built tpl-minimal through the tunnel" "see build-minimal.log"
# Its status is kept: the failure message's grep runs before require does.
isx build tpl-macos-role --yes > "$WORK/build-gh.log" 2>&1
# shellcheck disable=SC2034
built=$?
require '[ $built -eq 0 ]' "built tpl-macos-role, whose gh derives its identity through the proxy" \
    "$(grep -m1 -i 'fail' "$WORK/build-gh.log")"
check 'grep -q "Credential accounts: github=tplacct" "$WORK/build-gh.log"' "the build used the template's account"

# The scripts the checks run in an instance.
push_scripts() { incus_ file push -q "$ROOT/.github/scripts/proof-curl.sh" "$ROOT/.github/scripts/test-git-identity.sh" "$1/tmp/"; }
require 'isx branch mr-tpl --from tpl-macos-role > "$WORK/branch-tpl.log" 2>&1' "branched mr-tpl from the template"
push_scripts mr-tpl
check 'incus_ exec mr-tpl -- bash /tmp/test-git-identity.sh tplacct > "$WORK/identity-tpl.log" 2>&1' \
    "the template baked the identity of the account it was served (tplacct)" "see identity-tpl.log"
login() { incus_ exec "$1" -- bash /tmp/proof-curl.sh https://api.github.com/user -s | sed -n 's/.*"login": "\([^"]*\)".*/\1/p'; }
check '[ "$(login mr-tpl)" = tplacct ]' "a branch on the template's account is served it from the VM's address"

echo
echo "-- A branch pinned with --account is served its pin --"
require 'isx branch mr-pin --from tpl-macos-role --account github=agent > "$WORK/branch-pin.log" 2>&1' \
    "branched mr-pin with --account github=agent"
push_scripts mr-pin
check '[ "$(login mr-pin)" = agent ]' "mr-pin is served its pinned account, not the template's or the default"
check 'incus_ exec mr-pin -- bash /tmp/test-git-identity.sh agent > "$WORK/identity-pin.log" 2>&1' \
    "mr-pin commits as its pinned account" "see identity-pin.log"

echo
echo "-- The proxy refuses no known instance's proof --"
check '! grep -q "Credential proof refused" "$STATE/proxy.log"' "no proof was refused" \
    "$(grep -m1 'Credential proof refused' "$STATE/proxy.log")"

echo
echo "-- An --mcp-client branch reaches mcp.isx.internal --"
isx branch mr-mcp --from tpl-macos-role --mcp-client > "$WORK/branch-mcp.log" 2>&1
check 'grep -qE "Checking isx mcp from mr-mcp.* done \(isx [^,]+, [1-9][0-9]* tools\)" "$WORK/branch-mcp.log"' \
    "isx branch --mcp-client reached isx mcp from the guest, right after its start" \
    "$(grep -m1 'isx mcp' "$WORK/branch-mcp.log")"
mcp_status() {
    incus_ exec "$1" -- sh -c 'curl -s -o /dev/null -w "%{http_code}" --max-time 10 \
        -H "X-Isx-Instance-Secret: $(cat /run/isx/instance-secret)" -H "Content-Type: application/json" \
        -H "Accept: application/json, text/event-stream" \
        --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}" \
        https://mcp.isx.internal/mcp'
}
check '[ "$(mcp_status mr-pin)" = 403 ]' "an instance not branched with --mcp-client is refused, by its own secret"

echo
echo "-- What is not an instance gets no credential --"
# The echo server answers any other path with the headers it was sent. A proof is filled in
# from the placeholder a tool's variable holds, as proof-curl.sh does.
authorization() { sed -n 's/.*"Authorization": "\([^"]*\)".*/\1/p'; }
proof=$(incus_ exec mr-pin -- sh -c 'GH_TOKEN=gho_placeholder; . /run/isx/proof-tokens && printf %s "$GH_TOKEN"')
require '[ -n "$proof" ] && [ "$proof" != gho_placeholder ]' "read mr-pin's GitHub proof"
# The same shape, one character changed: a proof, but nobody's.
forged="${proof%?}$([ "${proof: -1}" = a ] && echo b || echo a)"
wrong=$(incus_ exec mr-pin -- curl -s --max-time 30 -H "Authorization: Bearer $forged" https://api.github.com/headers | authorization)
check '[ "$wrong" = "Bearer $forged" ]' "a wrong proof from the VM's address is relayed unchanged, with no credential" "'$wrong'"
check 'grep -q "Credential proof refused for caller $VM_IP (github)" "$STATE/proxy.log"' \
    "and refused, as from the VM's address, which names no instance"
host=$(curl -s --max-time 30 --cacert "$CONFIG/ca.crt" --resolve "api.github.com:$MITM_PORT:$HOST_IP" \
    -H "Authorization: Bearer $proof" "https://api.github.com:$MITM_PORT/headers" | authorization)
check '[ "$host" = "Bearer $proof" ]' \
    "the host's own request, from the listener's address, is never identified by a proof it copied" "'$host'"

summary
