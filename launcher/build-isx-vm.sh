#!/usr/bin/env bash
# Builds isx-vm and signs it for this Mac to run (#2031). Prints the binary's path, alone, on
# stdout; everything else goes to stderr.
#
#   launcher/build-isx-vm.sh
#
# Virtualization.framework starts a machine only for a program that carries the entitlement
# com.apple.security.virtualization, and an entitlement is part of a signature. The signature
# is ad hoc: no certificate, no key and no Apple account, which is how vfkit is signed too. The
# entitlement is the only one. vfkit's file also lists the two network entitlements of the App
# Sandbox, which mean nothing to a program outside that sandbox.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
[ "$(uname -s)" = Darwin ] || { echo "isx-vm runs on macOS alone, and is built there" >&2; exit 1; }

swift build --package-path "$here" -c release --product isx-vm >&2
bin="$(swift build --package-path "$here" -c release --show-bin-path)/isx-vm"

codesign --force --sign - --entitlements "$here/isx-vm.entitlements" "$bin" >&2
codesign --verify --strict "$bin" >&2

# What the signature carries, not what the file beside this script says.
signed=$(codesign -d --entitlements - --xml "$bin" 2>/dev/null | plutil -convert json -o - -- -)
if [ "$signed" != '{"com.apple.security.virtualization":true}' ]; then
    echo "isx-vm is signed with other entitlements than the one it needs: $signed" >&2
    exit 1
fi
echo "isx-vm: $(wc -c < "$bin" | tr -d ' ') bytes, signed ad hoc with com.apple.security.virtualization" >&2
echo "$bin"
