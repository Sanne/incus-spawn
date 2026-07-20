#!/bin/bash
# Functional integration tests for incus-spawn instances.
# Exercises end-to-end behavior: proxy interception, git, sudo, systemd.
#
# Usage: incus file push test-instance.sh <instance>/tmp/
#        incus exec <instance> -- bash /tmp/test-instance.sh

TESTS=0
PASS=0
FAIL=0
ERRORS=""

assert() {
    local desc="$1"; shift
    local output
    TESTS=$((TESTS + 1))
    if output=$("$@" 2>&1); then
        printf '  \033[32mPASS\033[0m  %s\n' "$desc"
        PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s\n' "$desc"
        if [ -n "$output" ]; then
            printf '         %s\n' "$output" | head -5
        fi
        FAIL=$((FAIL + 1))
        ERRORS="${ERRORS}  - ${desc}\n"
    fi
}

assert_eq() {
    local desc="$1" expected="$2"; shift 2
    local actual
    actual=$("$@" 2>/dev/null)
    TESTS=$((TESTS + 1))
    if [ "$actual" = "$expected" ]; then
        printf '  \033[32mPASS\033[0m  %s\n' "$desc"
        PASS=$((PASS + 1))
    else
        printf '  \033[31mFAIL\033[0m  %s  (expected: %s, got: %s)\n' "$desc" "$expected" "$actual"
        FAIL=$((FAIL + 1))
        ERRORS="${ERRORS}  - ${desc} (expected '${expected}', got '${actual}')\n"
    fi
}

echo "========================================"
echo " incus-spawn functional integration tests"
echo "========================================"
echo ""

# --- 1. Maven artifact download (MITM proxy interception) ---
# Verifies the full MITM chain: DNS override resolves repo1.maven.org
# to the bridge gateway, iptables redirects :443 to the proxy on :18443,
# proxy terminates TLS with its CA, re-encrypts to upstream Maven Central.
echo "[1] Maven Artifact Download (Proxy Interception)"
assert "download Maven POM through proxy" \
    bash -c "curl -sf https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom \
        | grep -q '<artifactId>junit</artifactId>'"
assert "download Maven JAR checksum through proxy" \
    bash -c "curl -sf https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar.sha1 \
        | grep -qE '^[0-9a-f]{40}$'"
echo ""

# --- 2. Git clone over HTTPS (proxy + git) ---
# Tests GitHub domain interception. The proxy relays without injecting
# auth for public repos (no GitHub token configured on CI).
echo "[2] Git Clone over HTTPS (Proxy)"
assert "clone public GitHub repo through proxy" \
    bash -c "rm -rf /tmp/test-clone && \
        git clone --depth 1 -q https://github.com/octocat/Hello-World.git /tmp/test-clone && \
        test -d /tmp/test-clone/.git"
assert "cloned repo has commits" \
    bash -c "cd /tmp/test-clone && git log --oneline | head -1 | grep -q ."
rm -rf /tmp/test-clone
echo ""

# --- 3. Passwordless sudo ---
# isx creates agentuser with a sudoers rule during template build.
echo "[3] Passwordless Sudo"
assert "agentuser can sudo without password" \
    su -l agentuser -c "sudo -n true"
assert "agentuser can run commands as root via sudo" \
    su -l agentuser -c "sudo cat /etc/shadow"
echo ""

# --- 4. Systemd service lifecycle ---
# Full system containers run systemd as init — services, timers, and
# nested containers all work, unlike Docker-style app containers.
echo "[4] Systemd Service Lifecycle"
cat > /etc/systemd/system/isx-test.service << 'UNIT'
[Service]
Type=oneshot
ExecStart=/bin/touch /tmp/isx-service-ran
UNIT
systemctl daemon-reload
rm -f /tmp/isx-service-ran
assert "start a oneshot systemd service" systemctl start isx-test
assert "service produced its side effect" test -f /tmp/isx-service-ran
assert "systemctl reports service as inactive (finished)" \
    bash -c "systemctl is-active isx-test 2>&1 | grep -q inactive"
rm -f /tmp/isx-service-ran /etc/systemd/system/isx-test.service
echo ""

# --- 5. DNS interception ---
# Intercepted domains (Maven, GitHub, Docker) resolve to the bridge
# gateway where the proxy listens, not their real IPs. This is how isx
# routes traffic through the proxy without modifying any tools.
echo "[5] DNS Interception"
gateway=$(grep nameserver /etc/resolv.conf | head -1 | cut -d' ' -f2)
assert "repo1.maven.org resolves to proxy gateway" \
    bash -c "getent ahostsv4 repo1.maven.org | grep -q '$gateway'"
assert "github.com resolves to proxy gateway" \
    bash -c "getent ahostsv4 github.com | grep -q '$gateway'"
echo ""

# --- 6. Login shell environment ---
# isx sets ISX_TEMPLATE and ISX_CONTAINER in agentuser's .bashrc so
# tools can detect they're running inside an isx container.
echo "[6] Login Shell Environment"
assert "ISX_TEMPLATE is set" \
    su -l agentuser -c 'bash -c "source ~/.bashrc 2>/dev/null; test -n \"\$ISX_TEMPLATE\""'
assert "ISX_CONTAINER is set (matches hostname)" \
    su -l agentuser -c 'bash -c "source ~/.bashrc 2>/dev/null; test -n \"\$ISX_CONTAINER\""'
echo ""

# --- 7. TLS certificate quality (AKI/SKI extensions) ---
# Python 3.14+ (OpenSSL 3.5+) rejects MITM leaf certs missing Authority
# Key Identifier or Subject Key Identifier extensions.
echo "[7] TLS Certificate Quality (AKI/SKI)"
dnf install -y -q python3 openssl 2>/dev/null
assert "Python accepts MITM proxy cert chain" \
    python3 -c "
import urllib.request
urllib.request.urlopen(
    'https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom',
    timeout=30)
"
assert "leaf cert has Authority Key Identifier" \
    bash -c "echo | openssl s_client -connect repo1.maven.org:443 2>/dev/null \
        | openssl x509 -noout -text | grep -q 'Authority Key Identifier'"
assert "leaf cert has Subject Key Identifier" \
    bash -c "echo | openssl s_client -connect repo1.maven.org:443 2>/dev/null \
        | openssl x509 -noout -text | grep -q 'Subject Key Identifier'"
echo ""

echo "========================================"
printf " Results: \033[1m%d/%d passed\033[0m" "$PASS" "$TESTS"
if [ "$FAIL" -gt 0 ]; then
    printf ", \033[31m%d failed\033[0m" "$FAIL"
fi
echo ""
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
    echo ""
    echo "Failed tests:"
    printf "$ERRORS"
    exit 1
fi
