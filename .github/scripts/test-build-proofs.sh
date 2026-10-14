#!/bin/bash
# Published templates must not carry a build's credential grant or cached proof.
set -euo pipefail
for template in tpl-minimal tpl-test-accounts tpl-test-codex; do
    for key in instance-secret-sha256 instance-proof-sha256 instance-proof-id instance-secret-boot static-ip; do
        test -z "$(incus config get "$template" "user.incus-spawn.$key")"
    done
done
auth=$(incus file pull tpl-test-codex/home/agentuser/.codex/auth.json -)
if [[ "$auth" == *isx_* ]]; then
    echo 'Published Codex template retained a build proof' >&2
    exit 1
fi
echo 'Published templates have no build grant; Codex cached auth is scrubbed'
