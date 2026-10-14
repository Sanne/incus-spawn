#!/bin/bash
# Run as agentuser, after test-podman.sh pulls Alpine. CI's GitHub upstream echoes
# headers. Disable TLS verification explicitly, as in the original #1100 report.
set -euo pipefail
request() {
    podman run --rm docker.io/library/alpine wget -T 30 --no-check-certificate -qO- "$@" https://api.github.com/
}
plain=$(request)
[[ "$plain" == *'"headers"'* && "$plain" != *'"Authorization"'* ]]
own=$(request --header='Authorization: Bearer ghp-acct-inner')
[[ "$own" == *'Bearer ghp-acct-inner'* ]]

# A minimal Podman image does not install gh, so opt into its declared proof.
export GH_TOKEN=gho_placeholder
. /run/isx/proof-tokens
[[ "$GH_TOKEN" == gho_isx_* ]]
shared=$(podman run --rm -e GH_TOKEN docker.io/library/alpine sh -c \
    'wget -T 30 --no-check-certificate -qO- --header="Authorization: Bearer $GH_TOKEN" https://api.github.com/')
[[ "$shared" == *'Bearer '* && "$shared" != *isx_* && "$shared" != *placeholder* ]]
echo 'Nested child: anonymous without proof, independent credential preserved, explicit sharing authorized'
