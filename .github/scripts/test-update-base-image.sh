#!/bin/bash
# Runs update-base-image.yml's three steps, as the workflow_dispatch backstop and the
# repository_dispatch path run them, against a fake images release that carries -vm-direct and
# SHA256SUMS.v2 (#1255), with gh and the remote faked. Checks the switch to -vm-direct, with both arches' checksums, from a stock
# built-in; after a stock-image bump of the same release was merged; and while that bump's
# branch is still open; a routine bump of a built-in already on -vm-direct; the refusal to move
# such a built-in back to the stock image when a release lacks -vm-direct; and that the PR names
# a switch of VM image and a release published again under its tag. Both events take every checksum
# from the release's sums files, for the names the built-in's URLs download, and stop on a payload
# that disagrees, a sum the release lacks or a malformed one (#1678); the PR lists each sum beside its
# asset. A release of the next Fedora moves the built-in to its release line, by either event, and one of an
# older Fedora stops the run (#1862). Then runs verify-base-image-sums.sh, the pull-request check, against the same release. The built-in it starts from is
# written here, held to minimal.yaml's keys. Needs bash, git and mikefarah yq.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TAG=fedora-44-20991231
OLD_TAG=fedora-44-20000101
H() { printf '%064x' "$1"; }

WORKFLOW="$REPO/.github/workflows/update-base-image.yml"
for pair in "resolve:Resolve release + checksums" "update:Update minimal.yaml" "pr:Open pull request"; do
    STEP="${pair#*:}" yq -r '.jobs.bump.steps[] | select(.name == strenv(STEP)) | .run' "$WORKFLOW" > "$WORK/${pair%%:*}.sh"
    [ -s "$WORK/${pair%%:*}.sh" ] || { echo "FAIL: no step named '${pair#*:}' in $WORKFLOW" >&2; exit 1; }
done

mkdir -p "$WORK/bin" "$WORK/release"
{ echo "$(H 11)  fedora-44-aarch64-vm.tar.xz"; echo "$(H 12)  fedora-44-aarch64.tar.xz"
  echo "$(H 13)  fedora-44-x86_64-vm.tar.xz"; echo "$(H 14)  fedora-44-x86_64.tar.xz"; } > "$WORK/release/SHA256SUMS"
{ cat "$WORK/release/SHA256SUMS"
  echo "$(H 15)  fedora-44-x86_64-vm-direct.tar.xz"; echo "$(H 16)  fedora-44-aarch64-vm-direct.tar.xz"; } > "$WORK/release/SHA256SUMS.v2"
cat > "$WORK/bin/gh" <<EOF
#!/bin/bash
case "\$*" in
  *"/releases --jq"*) echo "\${FAKE_TAG:-$TAG}" ;;
  "release download \${FAKE_TAG:-$TAG} -R Sanne/incus-spawn-images -p SHA256SUMS -p SHA256SUMS.v2 -D "*)
      rel="\${RELEASE:-$WORK/release}"
      [ -n "\${NO_V1:-}" ] || cp "\$rel/SHA256SUMS" "\${@: -1}"
      [ -n "\${NO_V2:-}" ] || cp "\$rel/SHA256SUMS.v2" "\${@: -1}" ;;
  "release download "*) echo "release not found" >&2; exit 1 ;;
  "pr create"*) printf '%s\n' "\$@" >> "$WORK/prs" ;;
  *) echo "unexpected: gh \$*" >&2; exit 2 ;;
esac
EOF
chmod +x "$WORK/bin/"*

fail() { echo "FAIL: $*" >&2; exit 1; }

# run <minimal.yaml> [branch already on the remote]: prints the resulting minimal.yaml
run() {
    # mktemp, not $RANDOM: every $(run ...) subshell starts from the same RANDOM state, so two runs
    # would share a directory and the second would find the first's repository.
    local dir
    dir="$(mktemp -d "$WORK/run-XXXXXX")"
    mkdir -p "$dir/common/src/main/resources/images"
    cp "$1" "$dir/common/src/main/resources/images/minimal.yaml"
    mkdir -p "$dir/.github/scripts"
    cp "$REPO/.github/scripts/base-image-sums.sh" "$REPO/.github/scripts/verify-base-image-sums.sh" "$dir/.github/scripts/"
    (
        cd "$dir"
        git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -qm init
        git init -q --bare remote.git && git remote add origin remote.git && git push -q origin main
        [ -n "${2:-}" ] && git push -q origin "main:refs/heads/$2"
        export PATH="$WORK/bin:$PATH" GITHUB_OUTPUT="$dir/out" GH_TOKEN=x
        rm -f "$WORK/prs"
        EVENT_NAME="${EVENT_NAME:-workflow_dispatch}" PAYLOAD="${PAYLOAD:-null}" bash -eo pipefail "$WORK/resolve.sh" >/dev/null
        set -a; . "$dir/out"; set +a
        [ "$changed" = true ] || { echo "UNCHANGED"; exit 0; }
        TAG=$tag C_X86=$c_x86 C_ARM=$c_arm V_X86=$v_x86 V_ARM=$v_arm VM_KIND=$vm_kind VM_FILE=${vm_file:-} \
            IMAGE=$image IMAGE_URL=$image_url bash -eo pipefail "$WORK/update.sh" >/dev/null
        TAG=$tag CURRENT_TAG=$current VM_KIND=$vm_kind REPUBLISHED=${republished:-} VM_SWITCH=${vm_switch:-} LINE_MOVE=${line_move:-} \
            C_X86=$c_x86 C_ARM=$c_arm V_X86=$v_x86 V_ARM=$v_arm bash -eo pipefail "$WORK/pr.sh" >/dev/null 2>&1
        [ -f "$WORK/prs" ] || { echo "NO-PR"; exit 0; }
        cat common/src/main/resources/images/minimal.yaml
        sed -n '/^--title$/{n;s/^/title: /;p}' "$WORK/prs"
        sed -n '/^--body$/,/^--base$/{s/^/body: /;p}' "$WORK/prs"
        echo "branch: $(git branch --show-current)"
    )
}

# yaml_of <run output>: the resulting minimal.yaml alone.
yaml_of() { grep -v '^title: \|^branch: \|^body: ' <<<"$1"; }

expect_direct() {
    local out what="$2"
    out="$(yaml_of "$1")"
    grep -q 'vm_image_url: .*/{tag}/fedora-44-{arch}-vm-direct.tar.xz$' <<<"$out" || fail "$what: vm_image_url not -vm-direct: $out"
    grep -q "image_tag: $TAG" <<<"$out" || fail "$what: tag not $TAG"
    [ "$(yq -r '.vm_image_sha256.x86_64' <<<"$out")" = "$(H 15)" ] || fail "$what: x86_64 VM checksum is not -vm-direct's"
    [ "$(yq -r '.vm_image_sha256.aarch64' <<<"$out")" = "$(H 16)" ] || fail "$what: aarch64 VM checksum is not -vm-direct's"
    [ "$(yq -r '.image_sha256.x86_64' <<<"$out")" = "$(H 14)" ] || fail "$what: x86_64 container checksum"
    [ "$(yq -r '.image_sha256.aarch64' <<<"$out")" = "$(H 12)" ] || fail "$what: aarch64 container checksum"
    echo "ok: $what"
}

# A stock built-in of an older release, written here rather than taken from the repository's
# minimal.yaml: the bump this checks moves that file to -vm-direct, and a fixture derived from
# it would stop being the state the checks start from.
BUILTIN="$WORK/stock-builtin.yaml"
cat > "$BUILTIN" <<EOF
name: tpl-minimal
description: Base OS only
mask_services: [tmp.mount]
image: fedora-44-base
image_url: https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/fedora-44-{arch}.tar.xz
vm_image_url: https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/fedora-44-{arch}-vm.tar.xz
image_tag: $OLD_TAG
image_sha256:
  x86_64: $(H 1)
  aarch64: $(H 2)
vm_image_sha256:
  x86_64: $(H 3)
  aarch64: $(H 4)
EOF
# Written by hand, so it is held to the real file's shape, nested keys included: a key renamed
# there must be renamed here.
shape() { yq -o=json -I=0 '[.. | path | join(".")] | sort' "$1"; }
WANT_SHAPE="$(shape "$REPO/common/src/main/resources/images/minimal.yaml")"
[ "$(shape "$BUILTIN")" = "$WANT_SHAPE" ] \
    || fail "the fixture's keys differ from minimal.yaml's: fixture $(shape "$BUILTIN"), minimal.yaml $WANT_SHAPE"
out="$(run "$BUILTIN")"
expect_direct "$out" "stock built-in, older tag"
grep -qx "title: Update base image to $TAG, VM image built from scratch" <<<"$out" || fail "the switch is not in the title: $(grep '^title:' <<<"$out")"

yq ".image_tag = \"$TAG\" | .image_sha256.x86_64 = \"$(H 14)\" | .image_sha256.aarch64 = \"$(H 12)\"
    | .vm_image_sha256.x86_64 = \"$(H 13)\" | .vm_image_sha256.aarch64 = \"$(H 11)\"" "$BUILTIN" > "$WORK/stock-bump.yaml"
out="$(run "$WORK/stock-bump.yaml")"
expect_direct "$out" "stock-image bump of the same release already merged"
grep -qx "title: Update base image to $TAG, VM image built from scratch" <<<"$out" \
    || fail "a switch after a stock-image bump of the same release is not a re-publish: $(grep '^title:' <<<"$out")"
expect_direct "$(run "$BUILTIN" "bot/update-base-image-$TAG")" "stock-image bump of the same release still open"

yq ".vm_image_url |= sub(\"[^/]+\$\"; \"fedora-44-{arch}-vm-direct.tar.xz\")
    | .vm_image_sha256.x86_64 = \"$(H 15)\" | .vm_image_sha256.aarch64 = \"$(H 16)\"" \
    "$WORK/stock-bump.yaml" > "$WORK/direct.yaml"
[ "$(run "$WORK/direct.yaml")" = UNCHANGED ] || fail "a built-in already on this release's -vm-direct is bumped again"
echo "ok: already current"

# main once the first -vm-direct bump has merged: a built-in already on -vm-direct, of an older
# release. A routine bump must not be called a switch of image, nor a re-publish.
yq ".image_tag = \"$OLD_TAG\" | .image_sha256.x86_64 = \"$(H 5)\" | .image_sha256.aarch64 = \"$(H 6)\"
    | .vm_image_sha256.x86_64 = \"$(H 7)\" | .vm_image_sha256.aarch64 = \"$(H 8)\"" "$WORK/direct.yaml" > "$WORK/direct-older.yaml"
out="$(run "$WORK/direct-older.yaml")"
expect_direct "$out" "built-in already on -vm-direct, older tag"
grep -qx "title: Update base image to $TAG" <<<"$out" \
    || fail "a routine bump is titled as more than one: $(grep '^title:' <<<"$out")"
grep -qx "branch: bot/update-base-image-$TAG-vm-direct" <<<"$out" \
    || fail "a routine -vm-direct bump's branch: $(grep '^branch:' <<<"$out")"
echo "ok: routine bump of a built-in already on -vm-direct"

# The same built-in, and a release without -vm-direct: the bump stops rather than move every
# user's VMs back to the stock image.
NO_V2=1 run "$WORK/direct-older.yaml" >/dev/null 2>&1 && fail "a release without -vm-direct moved a -vm-direct built-in back to stock"
echo "ok: no move back to the stock image"

# The release published again under its tag: a file the built-in already names has another checksum.
yq ".image_sha256.aarch64 = \"$(H 99)\"" "$WORK/direct.yaml" > "$WORK/republished.yaml"
out="$(run "$WORK/republished.yaml")"
expect_direct "$out" "release published again under its tag"
grep -qx "title: Base image $TAG was published again: new checksums" <<<"$out" \
    || fail "a re-publish is not named in the title: $(grep '^title:' <<<"$out")"
NAMED="$(printf '%s %s %s %s' "$(H 14)" "$(H 12)" "$(H 15)" "$(H 16)" | sha256sum | cut -c1-12)"
grep -qx "branch: bot/update-base-image-$TAG-vm-direct-$NAMED" <<<"$out" \
    || fail "a re-publish's branch is not named by all four checksums: $(grep '^branch:' <<<"$out")"
echo "ok: re-publish named, branch by all four checksums"

# The PR lists each checksum beside the asset it was matched to, and how to repeat the check.
out="$(run "$BUILTIN")"
grep -qx "body: | \`vm_image_sha256.aarch64\` | \`fedora-44-aarch64-vm-direct.tar.xz\` | \`$(H 16)\` |" <<<"$out" \
    || fail "the PR does not pair each checksum with its asset: $(grep '^body:' <<<"$out")"
grep -q "^body: .*gh release download $TAG -R Sanne/incus-spawn-images -p SHA256SUMS.v2 -O -" <<<"$out" \
    || fail "the PR does not give the command to repeat the check"
echo "ok: PR body pairs each checksum with its asset"

# repository_dispatch: the payload's tag is used, its checksums only checked against the release (#1678).
payload() { # <container x86> <container arm> <vm x86> <vm arm> <direct x86> <direct arm>
    printf '{"tag":"%s","sha256":{"container":{"x86_64":"%s","aarch64":"%s"},"vm":{"x86_64":"%s","aarch64":"%s"},"vm_direct":{"x86_64":"%s","aarch64":"%s"}}}' \
        "$TAG" "$@"
}
expect_direct "$(EVENT_NAME=repository_dispatch PAYLOAD="$(payload "$(H 14)" "$(H 12)" "$(H 13)" "$(H 11)" "$(H 15)" "$(H 16)")" run "$BUILTIN")" \
    "dispatch whose payload agrees with the release"
expect_direct "$(EVENT_NAME=repository_dispatch PAYLOAD="{\"tag\":\"$TAG\"}" run "$BUILTIN")" "dispatch carrying only the tag"
# The images repo sends empty strings for a -vm-direct it did not build.
out="$(NO_V2=1 EVENT_NAME=repository_dispatch PAYLOAD="$(payload "$(H 14)" "$(H 12)" "$(H 13)" "$(H 11)" "" "")" run "$BUILTIN")"
[ "$(yq -r '.vm_image_sha256.x86_64' <<<"$(yaml_of "$out")")" = "$(H 13)" ] || fail "dispatch of a stock-only release: $out"
echo "ok: dispatch of a release without -vm-direct, empty direct sums in the payload"

EVENT_NAME=repository_dispatch PAYLOAD="$(payload "$(H 14)" "$(H 77)" "$(H 13)" "$(H 11)" "$(H 15)" "$(H 16)")" \
    run "$BUILTIN" > "$WORK/out" 2> "$WORK/err" && fail "a payload checksum that differs from the release's was accepted: $(cat "$WORK/out")"
grep -q "sha256.container.aarch64 ($(H 77)) is not the release's sum for fedora-44-aarch64.tar.xz ($(H 12))" "$WORK/err" \
    || fail "a payload mismatch does not print both checksums: $(cat "$WORK/err")"
echo "ok: payload that disagrees with the release stops the run"

EVENT_NAME=repository_dispatch PAYLOAD='{"tag":"fedora-44-20991230"}' run "$BUILTIN" >/dev/null 2>&1 \
    && fail "a dispatch for a tag with no release was accepted"
EVENT_NAME=repository_dispatch PAYLOAD='{"tag":"../../x"}' run "$BUILTIN" >/dev/null 2>&1 \
    && fail "a dispatch with a malformed tag was accepted"
echo "ok: dispatch for a missing or malformed tag"

# release_variant <name> <sed on SHA256SUMS> [<sed on SHA256SUMS.v2>, the same by default]: a copy
# of the release, edited.
release_variant() {
    mkdir -p "$WORK/$1"
    sed "$2" "$WORK/release/SHA256SUMS" > "$WORK/$1/SHA256SUMS"
    sed "${3:-$2}" "$WORK/release/SHA256SUMS.v2" > "$WORK/$1/SHA256SUMS.v2"
}
release_variant no-arm-rootfs '/ fedora-44-aarch64.tar.xz$/d'
RELEASE="$WORK/no-arm-rootfs" run "$BUILTIN" > "$WORK/out" 2> "$WORK/err" \
    && fail "a release without one container rootfs was accepted: $(cat "$WORK/out")"
grep -q "lists no fedora-44-aarch64.tar.xz" "$WORK/err" || fail "a missing sum does not name its asset: $(cat "$WORK/err")"
release_variant short-sum "s/^$(H 14)/$(H 14 | cut -c1-63)/"
RELEASE="$WORK/short-sum" run "$BUILTIN" > "$WORK/out" 2> "$WORK/err" \
    && fail "a malformed sum was accepted: $(cat "$WORK/out")"
grep -q "malformed sum for fedora-44-x86_64.tar.xz" "$WORK/err" || fail "a malformed sum does not name its asset: $(cat "$WORK/err")"
release_variant disagree '' "s/^$(H 12)/$(H 66)/"
RELEASE="$WORK/disagree" run "$BUILTIN" >/dev/null 2>&1 && fail "SHA256SUMS and SHA256SUMS.v2 disagreeing was accepted"
echo "ok: a missing, malformed or contradicted sum stops the run"

# The asset names are the built-in's URLs', not literals: a release of another Fedora.
release_variant fedora-45 's/fedora-44-/fedora-45-/'
sed 's/fedora-44-{arch}/fedora-45-{arch}/' "$BUILTIN" > "$WORK/fedora-45.yaml"
out="$(RELEASE="$WORK/fedora-45" run "$WORK/fedora-45.yaml")"
grep -q 'vm_image_url: .*/{tag}/fedora-45-{arch}-vm-direct.tar.xz$' <<<"$out" || fail "fedora-45 vm_image_url: $out"
[ "$(yq -r '.image_sha256.aarch64' <<<"$(yaml_of "$out")")" = "$(H 12)" ] \
    || fail "fedora-45 container checksum: $out"
echo "ok: asset names come from the URLs"

# A release of the next Fedora (#1862): the built-in's release line moves with it, the image alias
# and both URLs' asset names included, by either event; never back to an older line.
TAG45=fedora-45-20991231
expect_next_line() { # <run output> <what>
    local out
    out="$(yaml_of "$1")"
    [ "$(yq -r '.image' <<<"$out")" = fedora-45-base ] || fail "$2: image alias: $out"
    grep -q 'image_url: .*/{tag}/fedora-45-{arch}.tar.xz$' <<<"$out" || fail "$2: image_url: $out"
    grep -q 'vm_image_url: .*/{tag}/fedora-45-{arch}-vm-direct.tar.xz$' <<<"$out" || fail "$2: vm_image_url: $out"
    [ "$(yq -r '.image_tag' <<<"$out")" = "$TAG45" ] || fail "$2: tag: $out"
    [ "$(yq -r '.image_sha256.x86_64' <<<"$out")" = "$(H 14)" ] || fail "$2: x86_64 container checksum: $out"
    [ "$(yq -r '.vm_image_sha256.aarch64' <<<"$out")" = "$(H 16)" ] || fail "$2: aarch64 VM checksum: $out"
    grep -qx "title: Move base image to fedora-45 ($TAG45)" <<<"$1" || fail "$2: title: $(grep '^title:' <<<"$1")"
    grep -q '^body: .*Release line: `fedora-44` to `fedora-45`' <<<"$1" || fail "$2: the PR does not name the move: $(grep '^body:' <<<"$1")"
    echo "ok: $2"
}
expect_next_line "$(FAKE_TAG=$TAG45 RELEASE="$WORK/fedora-45" run "$WORK/direct-older.yaml")" "next Fedora, by hand"
# Fedora 45 releases publish SHA256SUMS.v2 alone, so isx releases that read only SHA256SUMS find none.
expect_next_line "$(NO_V1=1 FAKE_TAG=$TAG45 RELEASE="$WORK/fedora-45" run "$WORK/direct-older.yaml")" "next Fedora, SHA256SUMS.v2 alone"
expect_next_line "$(FAKE_TAG=$TAG45 RELEASE="$WORK/fedora-45" EVENT_NAME=repository_dispatch PAYLOAD="{\"tag\":\"$TAG45\"}" \
    run "$WORK/direct-older.yaml")" "next Fedora, by dispatch"
release_variant fedora-43 's/fedora-44-/fedora-43-/'
FAKE_TAG=fedora-43-20991231 RELEASE="$WORK/fedora-43" run "$WORK/direct-older.yaml" > "$WORK/out" 2> "$WORK/err" \
    && fail "a release of an older Fedora was taken: $(cat "$WORK/out")"
grep -q "not a newer release line" "$WORK/err" || fail "an older line's refusal: $(cat "$WORK/err")"
echo "ok: never back to an older Fedora"

# verify-base-image-sums.sh, the pull-request check, against the same fake release.
verify() { PATH="$WORK/bin:$PATH" bash "$REPO/.github/scripts/verify-base-image-sums.sh" "$1"; }
for y in direct stock-bump; do
    sed "s/^image_tag: .*/image_tag: $TAG/" "$WORK/$y.yaml" > "$WORK/verify-$y.yaml"
    verify "$WORK/verify-$y.yaml" >/dev/null || fail "verify rejects the release's own sums ($y)"
done
NO_V1=1 verify "$WORK/verify-direct.yaml" >/dev/null || fail "verify rejects a release with SHA256SUMS.v2 alone"
expect_rejected() { # <what> <yq edit of a correct -vm-direct minimal.yaml> <expected message>
    yq "$2" "$WORK/verify-direct.yaml" > "$WORK/verify-bad.yaml"
    verify "$WORK/verify-bad.yaml" >/dev/null 2> "$WORK/err" && fail "verify accepted $1"
    grep -q "$3" "$WORK/err" || fail "verify's message for $1: $(cat "$WORK/err")"
}
expect_rejected "the VM arches swapped" ".vm_image_sha256.x86_64 = \"$(H 16)\" | .vm_image_sha256.aarch64 = \"$(H 15)\"" \
    "vm_image_sha256.x86_64 is $(H 16), but release $TAG lists $(H 15) for fedora-44-x86_64-vm-direct.tar.xz"
expect_rejected "the stock VM sums under a -vm-direct URL" ".vm_image_sha256.x86_64 = \"$(H 13)\"" "vm_image_sha256.x86_64 is $(H 13)"
expect_rejected "a container sum paired with the VM disk" ".image_sha256.x86_64 = \"$(H 15)\"" "image_sha256.x86_64 is $(H 15)"
expect_rejected "a tag with no release" ".image_tag = \"fedora-44-20991230\"" "Cannot download the sums files"
expect_rejected "an asset the release lacks" '.image_url |= sub("fedora-44-"; "fedora-43-")' "lists no fedora-43-x86_64.tar.xz"
echo "ok: verify-base-image-sums.sh checks each sum against its asset"
