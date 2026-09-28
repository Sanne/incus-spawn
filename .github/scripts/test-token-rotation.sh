#!/bin/bash
# A GitHub token replaced in place is a change of git identity (#281).
#
# Replacing an account's token with another user's keeps the account name, so a
# build that only recorded the name never noticed: a child template inherited
# its parent's .gitconfig, gh's setup skipped the identity already present, and
# the child went on committing as the previous user while pushing as the new one;
# existing instances did the same.
#
# The identity is only ever derived from the account a *build container* is
# served, which is the default, so the default is moved to 'agent' for the
# duration and restored on exit. No real credential is involved: the echo server
# answers GitHub's /user as whichever login a ghp-acct-<login> token names.
#
# Runs on the host; expects tpl-minimal to be built and the proxy running.

set -u

CFG="$HOME/.config/incus-spawn/config.yaml"
IMAGES="$HOME/.config/incus-spawn/images"
BACKUP="$(mktemp)"
cp "$CFG" "$BACKUP"

reload_proxy() {
    local pid="${ISX_PROXY_PID:-$(pgrep -nf 'isx-proxy|incus-spawn-proxy' || true)}"
    [ -n "$pid" ] && kill -HUP "$pid" 2>/dev/null
    sleep 3
}

restore() {
    cp "$BACKUP" "$CFG"
    reload_proxy
}
trap restore EXIT

# Make 'agent' the default GitHub account, holding the given token.
github_default_agent() {
    python3 - "$CFG" "$1" <<'EOF'
import sys, yaml
path, token = sys.argv[1], sys.argv[2]
with open(path) as f: cfg = yaml.safe_load(f)
cfg['github']['accounts']['agent']['token'] = token
cfg['github']['default'] = 'agent'
with open(path, 'w') as f: yaml.dump(cfg, f, default_flow_style=False)
EOF
    reload_proxy
}

assert_identity() {
    incus exec "$1" -- bash /tmp/test-git-identity.sh "$2"
}

# Branch without isx starting it, so no branch-time reconcile runs: the identity
# asserted is the one the template itself carries.
check_identity() {
    local instance="$1" template="$2" login="$3"
    isx branch "$instance" --from "$template" --no-start
    incus start "$instance"
    incus exec "$instance" -- bash -c '
        systemctl start systemd-networkd 2>/dev/null
        for n in $(seq 1 30); do
            ip -4 -o addr show eth0 | grep -q "inet " && break
            sleep 0.5
        done'
    incus file push .github/scripts/test-git-identity.sh "$instance/tmp/"
    assert_identity "$instance" "$login"
}

cat > "$IMAGES/test-rotation-base.yaml" << 'EOF'
name: tpl-test-rotation-base
parent: tpl-minimal
tools:
  - gh
EOF
cat > "$IMAGES/test-rotation-leaf.yaml" << 'EOF'
name: tpl-test-rotation-leaf
parent: tpl-test-rotation-base
EOF
cat > "$IMAGES/test-rotation-add-gh.yaml" << 'EOF'
name: tpl-test-rotation-add-gh
parent: tpl-minimal
tools:
  - gh
EOF

rc=0

echo "[1] The base template bakes the identity of the token 'agent' holds"
github_default_agent ghp-acct-agent
isx build tpl-test-rotation-base --yes || exit 1
check_identity rotation-base tpl-test-rotation-base agent || rc=1

echo "[2] 'agent' is given another user's token; a child built now commits as that user"
github_default_agent ghp-acct-rotated
isx build tpl-test-rotation-leaf --yes || exit 1
check_identity rotation-leaf tpl-test-rotation-leaf rotated || rc=1

echo "[3] ...and so does an instance built before the swap, on its next use"
# Still the old identity: the swap alone changes nothing inside, which is what
# makes the step below prove something.
assert_identity rotation-base agent || rc=1
# 'isx run' with an action that does not exist runs the same preparation as
# 'isx shell' -- where the identity is reconciled -- then fails on the action,
# so the next use can be driven without a terminal.
isx run rotation-base --action=no-such-action || true   # the unknown action fails, by design
assert_identity rotation-base rotated || rc=1

echo "[4] A child adding gh to a parent without it still gets gh's git defaults"
# tpl-minimal was built under the CI default and carries a GitHub stamp for it,
# gh or not, so under 'agent' its child's inherited identity is out of date and
# refreshed. Refreshing before gh's setup would create the .gitconfig whose
# absence is how gh knows to write its defaults.
isx build tpl-test-rotation-add-gh --yes || exit 1
check_identity rotation-add-gh tpl-test-rotation-add-gh rotated || rc=1
push_default=$(incus exec rotation-add-gh -- su -l agentuser -c "git config --global --get push.default")
if [ "$push_default" = "current" ]; then
    printf '  \033[32mPASS\033[0m  %s\n' "gh's git defaults were written"
else
    printf '  \033[31mFAIL\033[0m  %s  (push.default: %s)\n' "gh's git defaults were written" "$push_default"
    rc=1
fi

exit $rc
