#!/bin/bash
# Real CLI build/branch regression for local repository paths. Requires isx init
# and a running proxy; tpl-minimal is built on demand. No source remote is reachable.
set -euo pipefail

fixture=$(mktemp -d "$HOME/.isx-host-repos.XXXXXX")
template="tpl-host-repos-${fixture##*.}"
instance="host-repos-${fixture##*.}"
cleanup() {
    isx destroy "$instance" 2>/dev/null || true
    incus delete -f "$template" 2>/dev/null || true
    incus delete -f "$template-rebuilding" 2>/dev/null || true
    incus delete -f "$template-failed-build" 2>/dev/null || true
    cd /
    rm -rf "$fixture"
}
trap cleanup EXIT
cd "$fixture"
images="$fixture/.incus-spawn/images"
mkdir -p "$images/dir"
definition="$images/local.yaml"
printf 'name: %s\nparent: tpl-minimal\nrepos:\n' "$template" > "$definition"

forms=(
    'file://@ABS@' 'file:@ABS@' '@ABS@'
    'file://~/@HOME@' 'file:~/@HOME@' '~/@HOME@'
    'file://@NAME@' 'file:@NAME@' 'file://./@NAME@' 'file:./@NAME@'
    '@NAME@' './@NAME@'
    'file:dir/@NAME@' 'file://./dir/@NAME@' 'file:./dir/@NAME@'
    'dir/@NAME@' './dir/@NAME@'
)
sources=()
for form in "${forms[@]}"; do
    for suffix in '' '/.git'; do
        # file://host/path is deliberately rejected; use file://./repo/.git.
        if [[ "$form" == 'file://@NAME@' && -n "$suffix" ]]; then continue; fi
        name="repo-${#sources[@]}"
        source="$images/$name"
        if [[ "$form" == *'dir/'* ]]; then source="$images/dir/$name"; fi
        mkdir -p "$source"
        git -C "$source" init -q -b local-work
        git -C "$source" config user.name Test
        git -C "$source" config user.email test@example.com
        printf 'committed\n' > "$source/tracked"
        git -C "$source" add tracked
        git -C "$source" commit -qm 'Local commit'
        git -C "$source" remote add origin https://invalid.example/unreachable.git
        git -C "$source" config branch.local-work.remote origin
        git -C "$source" config branch.local-work.merge refs/heads/main
        printf 'dirty\n' > "$source/tracked"
        git -C "$source" add tracked
        printf 'private\n' > "$source/untracked"
        url=${form//@ABS@/$source}
        url=${url//@HOME@/${source#"$HOME/"}}
        url=${url//@NAME@/$name}
        printf "  - url: '%s%s'\n    prime: test \"\$(cat tracked)\" = committed\n" "$url" "$suffix" >> "$definition"
        sources+=("$source")
    done
done

# The current directory differs from the directory defining the repositories.
isx build "$template" --yes
isx branch "$instance" --from "$template" --no-start
incus start "$instance"
for source in "${sources[@]}"; do
    destination="/home/agentuser/${source##*/}"
    head=$(git -C "$source" rev-parse HEAD)
    incus exec "$instance" -- su - agentuser -c "
        set -eu
        cd '$destination'
        test \"\$(git rev-parse HEAD)\" = '$head'
        test \"\$(git branch --show-current)\" = local-work
        test \"\$(cat tracked)\" = committed
        test ! -e untracked
        test ! -e .git/FETCH_HEAD
        test \"\$(git config --bool isx.hostOnly)\" = true
        test \"\$(git remote get-url origin)\" = https://invalid.example/unreachable.git
        test \"\$(git config branch.local-work.merge)\" = refs/heads/main
        echo container-only > tracked
        git fsck --full
    "
    test "$(cat "$source/tracked")" = dirty
    test ! -e "$source/.git/FETCH_HEAD"
    test "$(git -C "$source" remote get-url "$instance")" = "isx://$instance/home/agentuser/${source##*/}"
done
if incus config device show "$instance" | grep -q '^ref-'; then
    echo 'Repository reference mounts leaked into the branch' >&2
    exit 1
fi
isx destroy "$instance"
for source in "${sources[@]}"; do
    if git -C "$source" remote get-url "$instance" 2>/dev/null; then
        echo "Host remote was not removed from $source" >&2
        exit 1
    fi
done
echo "All ${#sources[@]} local repository forms passed build, branch and cleanup."
