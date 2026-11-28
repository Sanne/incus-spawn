#!/usr/bin/env bash
# Installs the pinned Incus from Zabbly's stable repository on an Ubuntu 24.04 runner (#2733).
#
#   sudo .github/scripts/install-incus.sh [<other package>...]
#
# The version is scripts/zabbly-incus.env's, which the upstream watch moves, so every run installs
# a known Incus and a new release reaches CI as a bump PR rather than as a red release run.
# The repository's signing key is the committed upstream/keys/zabbly.asc, the one the watch checks
# the repository's Release file against, never one downloaded at run time.
# The packages, the pinned incus among them, go through apt-install.sh (ci.md), once this script
# has written Zabbly's source.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=/dev/null
. "$ROOT/scripts/zabbly-incus.env"
[[ -n "${ZABBLY_INCUS_VERSION:-}" ]] || { echo "::error::install-incus: scripts/zabbly-incus.env sets no ZABBLY_INCUS_VERSION" >&2; exit 1; }

codename=$(. /etc/os-release && echo "$VERSION_CODENAME")
if [[ "$codename" != noble ]]; then
    echo "::error::install-incus: the pinned Incus is noble's build, and this runner is $codename" >&2
    exit 1
fi
arch=$(dpkg --print-architecture)
if [[ "$arch" != amd64 ]]; then
    echo "::error::install-incus: the pinned Incus is the amd64 build, and this runner is $arch" >&2
    exit 1
fi

install -d -m 755 /etc/apt/keyrings
gpg --batch --dearmor < "$ROOT/upstream/keys/zabbly.asc" > /etc/apt/keyrings/zabbly.gpg
echo "deb [signed-by=/etc/apt/keyrings/zabbly.gpg] https://pkgs.zabbly.com/incus/stable $codename main" \
    > /etc/apt/sources.list.d/zabbly-incus-stable.list
echo "install-incus: incus $ZABBLY_INCUS_VERSION"

# Zabbly keeps only its newest two builds, and a pin it dropped reads to apt-install.sh as a mirror
# failure: it drops Ubuntu mirrors and retries, never saying the pin is gone. So Zabbly's index is
# read alone first, bounded like apt-install.sh's phases, and a pinned package it lists only at other
# versions fails here, naming the pin. An index that cannot be read is left to apt-install.sh's
# retries: noble's apt exits 0 when a fetch fails unless Error-Mode is any, and a package the index
# lists at no version at all means it was not read, never that the pin is gone.
# PINNED is upstream/manifest.yaml's zabbly-incus source.package and source.with (WorkflowIncusPinTest).
PINNED=(incus incus-base incus-client)
if timeout 120 apt-get -qq -o APT::Update::Error-Mode=any \
        -o Dir::Etc::sourcelist=sources.list.d/zabbly-incus-stable.list \
        -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0 update; then
    for p in "${PINNED[@]}"; do
        listed=$(apt-cache madison "$p" | awk -F' *[|] *' '$3 ~ /pkgs\.zabbly\.com/ { print $2 }')
        if [[ -z "$listed" ]]; then
            echo "::warning::install-incus: Zabbly's index lists no $p at all, so it was not read, and the pin is not checked before apt-install.sh"
            break
        fi
        if ! grep -qxF -- "$ZABBLY_INCUS_VERSION" <<<"$listed"; then
            echo "::error::install-incus: Zabbly's repository no longer lists $p $ZABBLY_INCUS_VERSION, the build scripts/zabbly-incus.env pins. Merge the upstream watch's zabbly-incus PR, or pin a listed build with: upstream/engine/upstream.sh apply zabbly-incus <version>" >&2
            exit 1
        fi
    done
else
    echo "::warning::install-incus: Zabbly's index could not be read alone, so the pin is not checked before apt-install.sh"
fi

exec "$ROOT/.github/scripts/apt-install.sh" "${PINNED[@]/%/=$ZABBLY_INCUS_VERSION}" "$@"
