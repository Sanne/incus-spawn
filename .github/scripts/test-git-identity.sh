#!/bin/bash
# Git identity follows the GitHub account an instance is pinned to.
#
# GitHub is the one namespace where the account changes what the build baked:
# user.name and user.email are derived from whoever the token belongs to, so two
# instances on different accounts must commit as different people -- not merely
# push with different tokens.
#
# No real credential is involved. The CI echo server answers GitHub's /user and
# /user/emails as whichever account the injected token names, which is how two
# identities are exercised when only one real token exists.
#
# Usage: incus exec <instance> -- bash /tmp/test-git-identity.sh <expected-login>
#
# The login is what the echo server answers /user as -- the account name for an
# account-scoped token, and "ci-user" for the real CI token, which is the default.

EXPECTED="${1:?usage: test-git-identity.sh <expected-login>}"

TESTS=0
PASS=0
FAIL=0
ERRORS=""

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

git_identity() { su -l agentuser -c "git config --global --get $1"; }

echo "========================================"
echo " git identity for login: $EXPECTED"
echo "========================================"
echo ""

# Mirrors Python's str.title() in echo-server.py: every hyphen-separated part is
# capitalised, so "ci-user" is "Ci-User" and not "Ci-user".
titled=$(echo "$EXPECTED" | sed -e 's/^./\U&/' -e 's/-./\U&/g')

# The echo server derives both from the account name, so the assertion says
# exactly what is claimed: this instance commits as the account it is pinned to.
assert_eq "user.name is the pinned account's" \
    "$titled Bot" git_identity user.name
assert_eq "user.email is the pinned account's" \
    "$EXPECTED@accounts.test" git_identity user.email

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
