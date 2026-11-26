#!/bin/bash
# Run as root in the CI instance before its runner starts (#2519): remove what would let the job
# use the server's accounts through the isx proxy.
#
# Every isx start hands an instance a proof for each credential namespace the host's proxy serves,
# whatever tools its template installed: /run/isx/proof-tokens (readable by agentuser), the
# credential secret they derive from, and the per-start instance secret that host routes and
# isx mcp check. The same delivery adds a system Git credential helper that presents the GitHub
# proof. A job holding any of them could have the proxy inject the server's default GitHub or
# Claude account. With all of them gone, the job's requests carry no proof, and the proxy passes
# them on with no credential added.
#
# Nothing delivers them again: the instance is never started again, and for a container a later
# isx exec or ssh reads the host's boot stamp, not these files, so it gives none (a VM would be asked
# and given them again: the template is a container). Exits 1, and the runner does not start, when
# anything is left.
#
#   drop-credentials.sh [ROOT]    ROOT is / in the instance, a scratch directory in the tests
set -u
root=${1:-/}
run="$root/run/isx"
gitconfig="$root/etc/gitconfig"

rm -f "$run/proof-tokens" "$run/credential-secret" "$run/instance-secret"
if [ -f "$gitconfig" ]; then
  git config --file "$gitconfig" --unset-all 'credential.https://github.com.helper' 2>/dev/null
  case $? in 0|5) ;; *) echo "could not remove the GitHub credential helper from $gitconfig" >&2; exit 1 ;; esac
fi

left=""
for f in proof-tokens credential-secret instance-secret; do
  [ ! -e "$run/$f" ] || left="$left $run/$f"
done
if [ -f "$gitconfig" ] && git config --file "$gitconfig" --get-regexp '^credential\.' >/dev/null 2>&1; then
  left="$left $gitconfig (a credential helper)"
fi
if [ -n "$left" ]; then
  echo "credentials are still in the instance:$left" >&2
  exit 1
fi
echo "the runner's job holds no credential proof"
