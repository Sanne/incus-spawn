#!/bin/bash
# scripts/agent-install/run-in-vm.sh -- run validate.sh on a fresh, clean VM nested in this host (#2081)
#
# validate.sh needs a host that has never seen Incus, and one run per host. This launches a
# fresh images:fedora/45 VM in the Incus this host already has, prepares it the way
# docs/dev/AGENT-INSTALL-VALIDATION.md "Inside an isx instance" describes (a bridge without isx's
# redirect, a grown root filesystem, this instance's CA and credential proofs, the agent's CLI),
# runs validate.sh in it with the options given after "--", copies the results to --out and
# deletes the VM.
#
# Meant for an isx instance with Incus and nested KVM: the VM's traffic leaves through this
# instance, so the outer proxy serves the agent's credential.
#
# Usage:
#   scripts/agent-install/run-in-vm.sh --agent claude|codex --out DIR [--name VM] [--keep] -- [validate.sh options]
#   e.g.  scripts/agent-install/run-in-vm.sh --agent codex --out ~/runs/codex -- --docs "$PWD/candidate-docs"
# A --docs or --isx-from directory given after "--" is copied into the VM at the same path.
set -euo pipefail

die() { echo "Error: $*" >&2; exit 1; }
say() { echo "==> $*" >&2; }

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
AGENT=
OUT=
VM=
KEEP=
NET=isxvm0
while [ $# -gt 0 ]; do
    case "$1" in
        --agent) AGENT=$2; shift 2 ;;
        --out) OUT=$2; shift 2 ;;
        --name) VM=$2; shift 2 ;;
        --keep) KEEP=1; shift ;;
        --) shift; break ;;
        -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
done
case "$AGENT" in claude|codex) ;; *) die "--agent takes claude or codex" ;; esac
[ -n "$OUT" ] || die "--out is required"
VM=${VM:-agent-install-$AGENT-$(date +%H%M%S)}
command -v incus >/dev/null || die "this host has no Incus to nest a VM in"
[ -r /dev/kvm ] || die "no /dev/kvm: nested virtualization is needed"
INC=(sudo incus)

# A bridge of its own, without isx's redirect, trusted by firewalld.
if ! "${INC[@]}" network show "$NET" >/dev/null 2>&1; then
    say "Creating the bridge $NET"
    "${INC[@]}" network create "$NET" ipv4.address=10.77.0.1/24 ipv4.nat=true ipv6.address=none >/dev/null
    if command -v firewall-cmd >/dev/null; then
        sudo firewall-cmd -q --zone=trusted --add-interface="$NET"
        sudo firewall-cmd -q --permanent --zone=trusted --add-interface="$NET"
    fi
fi
POOL=$("${INC[@]}" storage list -f csv | awk -F, '$2 == "dir" {print $1; exit}')
if [ -z "$POOL" ]; then
    "${INC[@]}" storage create agent-install dir >/dev/null
    POOL=agent-install
fi

cleanup() { [ -n "$KEEP" ] || "${INC[@]}" delete -f "$VM" >/dev/null 2>&1 || true; }
trap cleanup EXIT

say "Launching $VM"
"${INC[@]}" launch images:fedora/45 "$VM" --vm -s "$POOL" -n "$NET" \
    -c limits.cpu=4 -c limits.memory=8GiB -d root,size=40GiB >/dev/null
ready=
for _ in $(seq 1 100); do "${INC[@]}" exec "$VM" -- true 2>/dev/null && { ready=1; break; }; sleep 3; done
[ -n "$ready" ] || die "$VM did not answer within 5 minutes"
in_vm() { "${INC[@]}" exec "$VM" -- "$@"; }

say "Growing the root filesystem and installing what the harness needs"
in_vm sh -c 'dnf install -y -q cloud-utils-growpart e2fsprogs jq tar util-linux >/dev/null 2>&1
    growpart /dev/sda 2 >/dev/null || true
    resize2fs /dev/sda2 >/dev/null 2>&1 || xfs_growfs / >/dev/null 2>&1 || true'

say "Giving it this instance's CA and credential proofs"
for f in /etc/pki/ca-trust/source/anchors/isx-*.crt; do "${INC[@]}" file push -q "$f" "$VM/etc/pki/ca-trust/source/anchors/"; done
in_vm update-ca-trust
GROUP=$(sudo stat -c %G /run/isx/credential-secret)
in_vm sh -c "getent group $GROUP >/dev/null || groupadd $GROUP; mkdir -p /run/isx"
for f in /run/isx/*; do "${INC[@]}" file push -q "$f" "$VM/run/isx/"; done
in_vm sh -c "chgrp $GROUP /run/isx/*; chmod 640 /run/isx/*; chmod 644 /run/isx/proof-generation /run/isx/proof-version 2>/dev/null || true"
"${INC[@]}" file push -q /etc/profile.d/isx-instance-secret.sh "$VM/etc/profile.d/"

say "Installing $AGENT"
case "$AGENT" in
    claude) "${INC[@]}" file push -q "$(readlink -f "$(command -v claude)")" "$VM/usr/local/bin/claude"
        in_vm chmod 755 /usr/local/bin/claude ;;
    # npm reaches its registry through this instance's proxy, so it comes after the CA above.
    codex) in_vm sh -c 'dnf install -y -q nodejs npm >/dev/null 2>&1
        npm install -g @openai/codex >/tmp/npm.log 2>&1 || { tail -n 20 /tmp/npm.log >&2; exit 1; }' ;;
esac
in_vm sh -c "command -v $AGENT" >/dev/null || die "could not install $AGENT in $VM"

say "Copying the harness and the candidate"
# The published pages too, which the report names when the agent never opened one.
(cd "$ROOT" && tar -cf - scripts/agent-install README.md docs/*.md) | in_vm sh -c 'mkdir -p /root/isx && tar -C /root/isx -xf - 2>/dev/null'
args=("$@")
for i in "${!args[@]}"; do
    case "${args[$i]}" in
        --docs|--isx-from) d=${args[$((i + 1))]:-}; [ -n "$d" ] || die "${args[$i]} needs a directory"
            d=$(cd "$d" && pwd); args[$((i + 1))]=$d
            sudo tar -C "$(dirname "$d")" -cf - "$(basename "$d")" | in_vm sh -c "mkdir -p '$(dirname "$d")' && tar -C '$(dirname "$d")' -xf - 2>/dev/null && chmod -R a+rX '$d'" ;;
    esac
done

say "Running validate.sh in $VM"
rc=0
in_vm /root/isx/scripts/agent-install/validate.sh --agent "$AGENT" --out /root/run "${args[@]}" || rc=$?
mkdir -p "$OUT"
in_vm tar -C /root/run -cf - . | tar -C "$OUT" -xf - || say "No results to copy from $VM"
say "Results in $OUT (validate.sh exit $rc)"
exit "$rc"
