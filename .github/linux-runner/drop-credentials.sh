#!/bin/bash
# Run as root in the CI instance before its runner starts (#2519): remove what would let the job
# use the server's accounts through the isx proxy.
#
# Every isx start hands an instance a proof for each credential namespace the host's proxy serves,
# whatever tools its template installed: /run/isx/proof-tokens (readable by agentuser), the
# credential secret they derive from, and the per-start instance secret that host routes and
# isx mcp check. The same delivery adds a system Git credential helper that presents the GitHub
# proof. When the server's openai account is a ChatGPT sign-in, every branch also gets that
# session's look-alikes in /run/isx/chatgpt-auth, which a login profile copies to Codex's
# ~/.codex/auth.json; the proxy checks them against the host's stamps, so removing the instance
# secret does not revoke them. A job holding any of these could have the proxy serve it the
# server's GitHub, Claude or ChatGPT account. With all of them gone, the job's requests carry no
# proof, and the proxy passes them on with no credential added.
#
# /run/isx is checked as an allowlist: anything in it but the public proof generation and version
# fails the drop, so a file a later isx delivers there stops the runner rather than reaching it.
# Exits 1, and the runner does not start, when anything is left.
#
# This holds until something delivers again. A container's later isx exec or ssh reads the host's
# boot stamp, not these files, so it delivers nothing. `isx account reset-proofs` on the running
# instance does: it writes the proofs and the Git helper back while the job runs. Destroy a CI
# instance instead of resetting it (docs/dev/LINUX-CI-SETUP.md).
#
#   drop-credentials.sh [ROOT]    ROOT is / in the instance, a scratch directory in the tests
set -u
root=${1:-/}
run="$root/run/isx"
gitconfig="$root/etc/gitconfig"

# What a start delivers (InstanceSecret, CredentialProof), and Codex's copy of the ChatGPT session
rm -f "$run/proof-tokens" "$run/credential-secret" "$run/instance-secret" "$run/chatgpt-auth" \
  "$root/etc/profile.d/isx-zz-codex-chatgpt.sh" "$root/home/agentuser/.codex/auth.json"
if [ -f "$gitconfig" ]; then
  git config --file "$gitconfig" --unset-all 'credential.https://github.com.helper' 2>/dev/null
  case $? in 0|5) ;; *) echo "could not remove the GitHub credential helper from $gitconfig" >&2; exit 1 ;; esac
fi

# Only these may stay in /run/isx: the proof generation and version, which prove nothing
left=""
if [ -d "$run" ]; then
  for f in "$run"/* "$run"/.[!.]*; do
    [ -e "$f" ] || [ -L "$f" ] || continue
    case "${f##*/}" in proof-generation|proof-version) ;; *) left="$left $f" ;; esac
  done
fi
[ ! -e "$root/etc/profile.d/isx-zz-codex-chatgpt.sh" ] || left="$left $root/etc/profile.d/isx-zz-codex-chatgpt.sh"
[ ! -e "$root/home/agentuser/.codex/auth.json" ] || left="$left $root/home/agentuser/.codex/auth.json"
if [ -f "$gitconfig" ] && git config --file "$gitconfig" --get-regexp '^credential\..*helper$' >/dev/null 2>&1; then
  left="$left $gitconfig (a credential helper)"
fi
if [ -n "$left" ]; then
  echo "credentials are still in the instance:$left" >&2
  exit 1
fi
echo "the runner's job holds no credential proof"
