#!/bin/bash
# Real project create/update must authenticate, then leave no temporary grant behind.
set -euo pipefail
parent=tpl-project-proof-source
project=test-project-proofs
scratch=$(mktemp -d)
cleanup() {
    for name in "$project-rebuilding" "$project" "$parent"; do
        incus delete -f "$name" >/dev/null 2>&1 || true
    done
    rm -rf "$scratch"
}
trap cleanup EXIT

incus copy tpl-test-accounts "$parent"
incus config set "$parent" user.incus-spawn.account.testAccountTool beta
# Export a placeholder before isx's login profile replaces it with the delivered proof.
echo 'export TEST_ACCOUNT_TOKEN=tacct_placeholder' > "$scratch/profile.sh"
incus file push "$scratch/profile.sh" "$parent/etc/profile.d/aaa-project-test.sh"
incus file pull "$parent/home/agentuser/.gitconfig" "$scratch/gitconfig"
cat >> "$scratch/gitconfig" <<'EOF'
[credential "https://echo-accounts.incus-spawn.test"]
    helper = "!f() { printf '%s\\n' username=isx \"password=$TEST_ACCOUNT_TOKEN\"; }; f"
EOF
incus file push --uid 1000 --gid 1000 "$scratch/gitconfig" "$parent/home/agentuser/.gitconfig"

cat > "$scratch/project.yaml" <<EOF
name: $project
parent: $parent
repos:
  - https://echo-accounts.incus-spawn.test/project-private.git
preBuild: |
  set -eu
  test -d ~/project-private/.git
  reply=\$(curl --max-time 30 -sf -H "Authorization: Bearer \$TEST_ACCOUNT_TOKEN" https://echo-accounts.incus-spawn.test/)
  case "\$reply" in *'Bearer acct-token-beta'*) ;; *) echo 'Project credential was not injected' >&2; exit 1;; esac
  echo authenticated >> ~/project-proof-checks
EOF

assert_clean() {
    test "$(incus query "/1.0/instances/$project" | jq -r .status)" = Stopped
    test "$(incus config get "$project" user.incus-spawn.untrusted-lineage)" = project
    test "$(incus config get "$project" user.incus-spawn.account.testAccountTool)" = beta
    for key in instance-secret-sha256 instance-proof-id instance-proof-sha256 instance-secret-boot static-ip; do
        test -z "$(incus config get "$project" "user.incus-spawn.$key")"
    done
}
isx project create "$project" --config "$scratch/project.yaml"
assert_clean
isx project update "$project" --config "$scratch/project.yaml"
assert_clean
test "$(incus file pull "$project/home/agentuser/project-proof-checks" - | wc -l)" -eq 2
echo 'Private project clone and update authenticated with the selected account; both operations revoked their grants'

cat > "$scratch/failed.yaml" <<EOF
name: $project
preBuild: exit 17
EOF
if isx project update "$project" --config "$scratch/failed.yaml"; then
    echo 'Failing project update incorrectly succeeded' >&2
    exit 1
fi
assert_clean
echo 'Failed project update also revoked proofs, stopped the project and released its address'
