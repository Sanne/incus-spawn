# Source type git-main-paths (#2210): a pin of a commit of a first-party repository's main, moved to
# the newest commit on main that changed one of the paths the pinning repository reads from it. It
# pins incus-spawn-images' ISX_REF (the incus-spawn commit its VM kernel is built from) and a
# sibling's engine pin (the incus-spawn commit whose upstream/engine verifies its PRs).
#   source.repo    owner/name of the repository whose main is followed; the same owner as the
#                  manifest's repository, since the proof is that the owner merged the commit
#   source.paths   git pathspecs (a * is a wildcard) of what the pinning repository reads: a commit
#                  that changes none of them is never proposed, so an unrelated merge moves nothing
#   source.pin     file and text: a literal text of that file with {version} where the 40-hex
#                  commit is; it must occur exactly once
# The proof is main-commit: the commit is on the repository's main as fetched over HTTPS when the
# check runs, and newer means later on main. It proves the owner merged it, not what it does, so
# validate allows it on any item only for a first-party repository. A version is a commit
# and is never shortened. Cooling counts from the commit's committer date.

GMP_SHA_RE='^[0-9a-f]{40}$'
GMP_REPO_RE='^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$'
# A plain relative path, optionally with * wildcards: no pathspec magic (:), no option (-), no ..
GMP_PATH_RE='^[A-Za-z0-9_][A-Za-z0-9._/*-]*$'

_src() { yq -r ".items[\"$ITEM\"].source$1 // \"\"" "$MANIFEST"; }
_repo() { _src .repo; }
_paths() { yq -r ".items[\"$ITEM\"].source.paths // [] | .[]" "$MANIFEST"; }
_pin_file() { _src .pin.file; }
_pin_text() { _src .pin.text; }

# _clone: a blob-less bare clone of the repository's main, once per run; its trees answer which
# commits changed the paths, and it holds no checkout.
_clone() {
    local repo d
    repo="$(_repo)"
    [[ "$repo" =~ $GMP_REPO_RE ]] || die "$ITEM: source.repo is not owner/name: '$repo'"
    d="$UPSTREAM_CACHE/gmp-$(printf '%s' "$repo" | sha256sum | cut -c1-24).git"
    if [[ ! -d "$d" ]]; then
        git clone -q --bare --filter=blob:none --single-branch --branch main "https://github.com/$repo.git" "$d" 2> /dev/null \
            || { rm -rf "$d"; die "$ITEM: cannot fetch main of $repo"; }
    fi
    printf '%s' "$d"
}

# _on_main <sha>: the commit is main or one of its ancestors.
_on_main() {
    local d; d="$(_clone)"
    [[ "$1" =~ $GMP_SHA_RE ]] && git -C "$d" merge-base --is-ancestor "$1" main 2> /dev/null
}

# _read: the one 40-hex commit the pin's text holds in the pin's file; zero or two occurrences fail.
_read() {
    local f out
    f="$TREE/$(_pin_file)"
    [[ -f "$f" ]] || die "$ITEM: no such file $(_pin_file)"
    out="$(TEXT="$(_pin_text)" perl -0ne '
        my ($pre, $post) = split(/\{version\}/, $ENV{TEXT}, 2);
        # The commit ends where a name character would go on: $(...) drops a text'"'"'s final newline,
        # so without it ISX_REF=<40 hex>abcd would read as a pin and keep its tail on a rewrite.
        my $re = quotemeta($pre) . "([0-9a-f]{40})(?![0-9A-Za-z._-])" . quotemeta($post);
        my @m = /$re/g;
        print scalar(@m), "\n", join("\n", @m), "\n";' "$f")" || die "$ITEM: cannot read the pin in $(_pin_file)"
    [[ "$(head -1 <<<"$out")" == 1 ]] || die "$(_pin_file) holds the pin of $ITEM $(head -1 <<<"$out") times, not once"
    sed -n 2p <<<"$out"
}

src_validate() {
    local p text
    [[ "$(_repo)" =~ $GMP_REPO_RE ]] || die "manifest: item $ITEM: source.repo is not owner/name: '$(_repo)'"
    [[ -n "$(_paths)" ]] || die "manifest: item $ITEM: source.paths lists nothing"
    while read -r p; do
        [[ "$p" =~ $GMP_PATH_RE && "$p" != *..* ]] || die "manifest: item $ITEM: path '$p' is not a plain relative path"
    done < <(_paths)
    grep -qxF -- "$(_pin_file)" <<<"$(item_list "$ITEM" files)" || die "manifest: item $ITEM: source.pin.file is not one of its files"
    text="$(_pin_text)"
    [[ "$text" == *"{version}"* && "${text/\{version\}/}" != *"{version}"* ]] || die "manifest: item $ITEM: source.pin.text must hold {version} once"
    src_first_party || die "manifest: item $ITEM: every git-main-paths item, review or mechanical, follows a repository of the manifest's owner that GitHub says is not a fork; $(_repo) is not one, or GitHub could not be asked: main-commit proves only that the owner merged it"
    src_current > /dev/null
}

src_current() {
    local v; v="$(_read)"
    [[ "$v" =~ $GMP_SHA_RE ]] || die "cannot read $ITEM's commit from its pin"
    printf '%s' "$v"
}
src_series() { echo main; }
src_verifier() { echo main-commit; }
# The manifest owner's own repository, and not a fork: a fork's main holds commits its upstream
# merged, not the owner. GitHub's answer is read once per run; an unanswered read is not first party.
src_first_party() {
    local f
    [[ "$(_repo | cut -d/ -f1)" == "$(manifest_repo | cut -d/ -f1)" ]] || return 1
    f="$UPSTREAM_CACHE/gmp-fork-$(_repo | tr / _)"
    [[ -s "$f" ]] || { gh api "repos/$(_repo)" --jq .fork > "$f" 2> /dev/null || rm -f "$f"; }
    [[ "$(cat "$f" 2> /dev/null)" == false ]]
}

# Every commit on main that changed a path, oldest first.
src_releases() {
    local d paths=()
    d="$(_clone)"
    mapfile -t paths < <(_paths)
    git -C "$d" log --topo-order --reverse --format=%H main -- "${paths[@]}"
}

# Oldest first, by place on main, ancestors before descendants whatever the commit dates say; a
# commit that is not on main fails. main's order is listed once per run, beside the clone: newest
# and is_newer ask often.
src_order() {
    local d v n
    local -A at=()
    d="$(_clone)"
    [[ -s "$d.order" ]] || git -C "$d" rev-list --topo-order --reverse main | awk '{ printf "%d\t%s\n", NR, $0 }' > "$d.order"
    while IFS=$'\t' read -r n v; do at[$v]="$n"; done < "$d.order"
    while read -r v; do
        [[ -n "$v" ]] || continue
        [[ -n "${at[$v]:-}" ]] || die "$ITEM: $v is not a commit on main of $(_repo)"
        printf '%s\t%s\n' "${at[$v]}" "$v"
    done | sort -n | cut -f2
}

# The pinned commit is still on main: 0 when it is, 1 when it is not, a mismatch; 2 when main could
# not be fetched, a read that failed, which check reports and plan fails the run on (#2261).
src_confirms() {
    # In a subshell: _clone dies, which would end the engine before check could report it.
    ( _clone > /dev/null ) 2> /dev/null || { log "ERROR: $ITEM: cannot fetch main of $(_repo)"; return 2; }
    # Any failure past the clone is a mismatch: merge-base exits 128, not 1, for a commit GitHub does
    # not have (a typo, or history rewritten away), which must not read as a failed fetch.
    _on_main "$1" || return 1
}

src_released_at() {
    _on_main "$1" || die "$ITEM: $1 is not a commit on main of $(_repo)"
    git -C "$(_clone)" log -1 --format=%ct "$1"
}

# A move goes only to a commit that changed a watched path, the commits plan proposes, so verify
# passes no app PR plan would not have opened; re-applying the pinned commit (check) is not a move.
src_apply() {
    local version="$1" f paths=()
    [[ "$version" =~ $GMP_SHA_RE ]] || die "$ITEM: not a commit: '$version'"
    _on_main "$version" || die "$ITEM: $version is not a commit on main of $(_repo)"
    f="$TREE/$(_pin_file)"
    if [[ "$(src_current)" != "$version" ]]; then
        mapfile -t paths < <(_paths)
        [[ "$(git -C "$(_clone)" log -1 --format=%H "$version" -- "${paths[@]}")" == "$version" ]] \
            || die "$ITEM: $version changed none of $(_paths | paste -sd' ' -), so the watch never moves to it"
    fi
    TEXT="$(_pin_text)" VERSION="$version" perl -0pi -e '
        my ($pre, $post) = split(/\{version\}/, $ENV{TEXT}, 2);
        s/\Q$pre\E[0-9a-f]{40}(?![0-9A-Za-z._-])\Q$post\E/$pre$ENV{VERSION}$post/;' "$f"
    [[ "$(src_current)" == "$version" ]] || die "failed to rewrite $ITEM to $version"
}

src_fields() {
    local v; v="$(src_current)"
    printf 'commit=%s\n' "$v"
}

# The PR's text: the commits that changed the paths since the pin, each subject in a code span so
# its #123 and @name do not link from another repository. Called on the bumped tree, so the commit
# it moves from is read from the watched repository's checkout, the tree plan bumped.
src_describe() {
    local d cur paths=() sha subject rows=""
    d="$(_clone)"; cur="$(TREE="$REPO_ROOT" src_current)"
    mapfile -t paths < <(_paths)
    while IFS=$'\t' read -r sha subject; do
        subject="$(tr -d '`' <<<"$subject" | cut -c1-120)"
        rows+="- [\`${sha:0:8}\`](https://github.com/$(_repo)/commit/$sha) \`$subject\`"$'\n'
    done < <(git -C "$d" log --format='%H%x09%s' "$cur..$1" -- "${paths[@]}" 2> /dev/null || true)
    cat <<MD
Moves \`$ITEM\` from \`$(_repo)\` commit \`${cur:0:8}\` to \`${1:0:8}\` (\`$1\`), the newest commit on its \`main\` that changed what this repository reads from it ($(_paths | sed 's/.*/`&`/' | paste -sd, - | sed 's/,/, /g')):

${rows%$'\n'}

Compare: https://github.com/$(_repo)/compare/$cur...$1
MD
}
