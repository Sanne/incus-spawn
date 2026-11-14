# Source type model-deprecations (#2227): the AI model IDs isx ships (common/src/main/resources/models.yaml)
# against what each vendor publishes without a key. It is a findings source, not a pin: it has no
# version to bump and rewrites nothing, so an item of this type is class judgement and the watch opens
# one issue per finding for a box to act on (a model is a judgement: cost, quality, a Vertex region).
#   source.vendors.<name>  url (https) of the vendor's markdown deprecations page, and id-prefix, a
#                          regex that says which declared IDs it answers for; <name> is the page's
#                          format: anthropic (a table of id | state | deprecated | retired),
#                          openai (tables of shutdown date | models | status | replacement; a row of another status is not a model's) or
#                          google (a list of id, release, shutdown, replacement).
# The page is third-party text. Only tokens of an ID's shape and a date's shape are taken from it and
# put in an issue; every other character of it is dropped. A declared ID no vendor's prefix covers,
# and a page that no longer yields a single row, fail the run rather than read as "all clear".

_vendors() { yq -r ".items[\"$ITEM\"].source.vendors | keys | .[]" "$MANIFEST"; }
_vget() { yq -r ".items[\"$ITEM\"].source.vendors[\"$1\"][\"$2\"] // \"\"" "$MANIFEST"; }
_models_file() { printf '%s/%s' "$TREE" "$(item_list "$ITEM" files | head -1)"; }
MODEL_ID_RE='^[a-z][a-z0-9.]*(-[a-z0-9.]+)+$'
# A date as the vendors write it: June 9, 2026 or Jan 14, 2027.
MODEL_DATE_RE='^[A-Z][a-z]{2,8}\.? [0-9]{1,2}, [0-9]{4}$'

# Every declared "key<TAB>id", checked for shape.
_declared() {
    local out key id
    out="$(yq -r 'to_entries | .[] | "\(.key)\t\(.value)"' "$(_models_file)")"
    [[ -n "$out" ]] || die "$(_models_file) declares no model ID"
    while IFS=$'\t' read -r key id; do
        [[ "$id" =~ $MODEL_ID_RE ]] || die "$(_models_file): '$key' is not a model ID"
    done <<<"$out"
    printf '%s\n' "$out"
}

src_validate() {
    local v u
    [[ -n "$(_vendors)" ]] || die "manifest: item $ITEM has no source.vendors"
    [[ "$(item_class "$ITEM")" == judgement ]] || die "manifest: item $ITEM: a model-deprecations item is class judgement"
    for v in $(_vendors); do
        case "$v" in anthropic | openai | google) ;; *) die "manifest: item $ITEM: unknown vendor '$v'" ;; esac
        u="$(_vget "$v" url)"
        [[ "$u" =~ ^https://[A-Za-z0-9.-]+/[A-Za-z0-9._/~-]*$ ]] || die "manifest: item $ITEM: vendor $v has no https url"
        [[ -n "$(_vget "$v" id-prefix)" ]] || die "manifest: item $ITEM: vendor $v has no id-prefix"
    done
    _vendor_of_all > /dev/null
}

# _vendor_of <id>: the vendor whose id-prefix matches; none or two is an error.
_vendor_of() {
    local v found=""
    for v in $(_vendors); do
        if [[ "$1" =~ $(_vget "$v" id-prefix) ]]; then
            [[ -z "$found" ]] || die "$ITEM: $1 matches the id-prefix of $found and $v"
            found="$v"
        fi
    done
    [[ -n "$found" ]] || die "$ITEM: no vendor's id-prefix covers $1 (add the vendor to the item before declaring it)"
    printf '%s' "$found"
}
_vendor_of_all() { local k id; while IFS=$'\t' read -r k id; do _vendor_of "$id" > /dev/null; done < <(_declared); }

# ---- parsers: each prints "id<TAB>state<TAB>date<TAB>replacement" (state: active, deprecated, retired,
# superseded; date: the shutdown date when the page gives one; replacement: an ID or empty) ----------

_ids_in() { grep -oE '[a-z][a-z0-9.]*(-[a-z0-9.]+)+' <<<"$1" | head -3 | tr '\n' ' ' | sed 's/ $//' || true; }
_date_or_empty() { local d; d="$(sed 's/^ *//; s/ *$//; s/\*//g' <<<"$1")"; [[ "$d" =~ $MODEL_DATE_RE ]] && printf '%s' "$d" || true; }

_parse_anthropic() {
    local line id state dep ret
    while IFS='|' read -r _ id state dep ret _; do
        id="$(tr -d ' `' <<<"$id")"; state="$(tr -d ' ' <<<"$state" | tr 'A-Z' 'a-z')"
        [[ "$id" =~ ^claude-[a-z0-9-]+$ ]] || continue
        case "$state" in
            active | legacy) printf '%s\tactive\t\t\n' "$id" ;;
            deprecated | retired) printf '%s\t%s\t%s\t\n' "$id" "$state" "$(_date_or_empty "$ret")" ;;
            *) die "$ITEM: Anthropic's page gives $id the state '$(tr -cd 'A-Za-z ' <<<"$state" | cut -c1-20)', which the source does not know" ;;
        esac
    done < <(grep -E '^\| *claude-' "$1" || true)
}

_parse_openai() {
    local when models status repl cell id
    while IFS='|' read -r _ when models status repl _; do
        [[ "$when" == *'**'* ]] || continue
        status="$(tr -d ' ' <<<"$status" | tr 'A-Z' 'a-z')"
        case "$status" in
            deprecated) status=deprecated ;;
            shutdown) status=retired ;;
            *) continue ;;   # the page's other tables (features, endpoints) use other words
        esac
        while IFS= read -r cell; do
            id="$(tr -d '`' <<<"$cell")"
            [[ "$id" =~ $MODEL_ID_RE ]] && printf '%s\t%s\t%s\t%s\n' "$id" "$status" "$(_date_or_empty "$when")" "$(_ids_in "$repl" | cut -d' ' -f1)"
        done < <(grep -oE '`[^`]*`' <<<"$models" || true)
    done < <(grep -E '^\| *\*\*' "$1" || true)
}

_parse_google() {
    awk '
        /^  \* `[a-z][^`]*`$/ { flush(); id = $0; sub(/^  \* `/, "", id); sub(/`$/, "", id); n = 0; next }
        /^  \* / && id != "" { n++; sub(/^  \* ?/, ""); cell[n] = $0; next }
        /^  ---$|^\{% \/table %\}$/ { flush(); next }
        function flush() { if (id != "") printf "%s\t%s\t%s\n", id, cell[2], cell[3]; id = ""; n = 0; cell[2] = ""; cell[3] = "" }
        END { flush() }
    ' "$1" | while IFS=$'\t' read -r id shut repl; do
        [[ "$id" =~ $MODEL_ID_RE ]] || continue
        if [[ -n "$(_date_or_empty "$shut")" ]]; then
            printf '%s\tdeprecated\t%s\t%s\n' "$id" "$(_date_or_empty "$shut")" "$(_ids_in "$repl" | cut -d' ' -f1)"
        elif [[ -n "$(_ids_in "$repl")" ]]; then
            printf '%s\tsuperseded\t\t%s\n' "$id" "$(_ids_in "$repl" | cut -d' ' -f1)"
        else
            printf '%s\tactive\t\t\n' "$id"
        fi
    done
}

# ---- successors -----------------------------------------------------------------------------------

# _anthropic_key <id>: "<family> <zero-padded version>" of claude-<family>-<n>[-<m>][-<yyyymmdd>], or nothing.
_anthropic_key() {
    local rest fam v out="" part
    [[ "$1" =~ ^claude-(opus|sonnet|haiku)-([0-9]+(-[0-9]{1,2})?)(-[0-9]{8})?$ ]] || return 0
    fam="${BASH_REMATCH[1]}"; rest="${BASH_REMATCH[2]}"
    for part in ${rest//-/ }; do out+="$(printf '%03d' "$((10#$part))")"; done
    # Padded to two parts so 5 and 5-0 compare equal.
    while (( ${#out} < 6 )); do out+="000"; done
    printf '%s %s' "$fam" "$out"
}

# _newer_anthropic <id> <rows>: the newest active ID of the family, when newer than <id>.
_newer_anthropic() {
    local mine row id best="" bestk="" k
    mine="$(_anthropic_key "$1")"
    [[ -n "$mine" ]] || return 0
    while IFS=$'\t' read -r id state _ _; do
        [[ "$state" == active ]] || continue
        k="$(_anthropic_key "$id")"
        [[ "${k%% *}" == "${mine%% *}" && "${k#* }" > "${mine#* }" ]] || continue
        if [[ -z "$best" || "${k#* }" > "$bestk" ]]; then best="$id"; bestk="${k#* }"; fi
    done <<<"$2"
    printf '%s' "$best"
}

# _row_for <id> <rows>: the rows naming <id>: the ID itself, or it followed by a date (-20251001,
# -2024-07-18), which is how a vendor lists the snapshot an alias stands for. Worst state first.
_row_for() {
    local id="$1" rows="$2" r
    r="$(awk -F'\t' -v id="$id" '
        function rank(s) { return s == "retired" ? 0 : s == "deprecated" ? 1 : s == "superseded" ? 2 : 3 }
        $1 == id || index($1, id "-") == 1 && substr($1, length(id) + 2) ~ /^([0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]|[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9])$/ { print rank($2) "\t" $0 }
    ' <<<"$rows" | sort -n | head -1 | cut -f2-)"
    printf '%s' "$r"
}

# The findings: "key<TAB>title<TAB>body", the body's newlines written \n.
src_findings() {
    local v url f rows all="" declared key id vendor vrows row state date repl succ keys n
    declared="$(_declared)"
    for v in $(_vendors); do
        url="$(_vget "$v" url)"
        f="$(mktemp -p "$UPSTREAM_CACHE")"
        fetch "$url" "$f"
        # An empty field is written "-": read with a tab for IFS would collapse two tabs into one.
        rows="$("_parse_$v" "$f" | awk -F'\t' -v OFS='\t' '{ for (i = 3; i <= 4; i++) if ($i == "") $i = "-"; print }')"
        [[ -n "$rows" ]] || die "$ITEM: $url yields no model row; the page's format has changed"
        all+="$v"$'\t'"${rows//$'\n'/$'\n'$v$'\t'}"$'\n'
    done
    declare -A seen=()
    while IFS=$'\t' read -r key id; do
        [[ -z "${seen[$id]:-}" ]] || continue
        seen[$id]=1
        vendor="$(_vendor_of "$id")"
        vrows="$(awk -F'\t' -v v="$vendor" '$1 == v { sub(/^[^\t]*\t/, ""); print }' <<<"$all")"
        keys="$(awk -F'\t' -v i="$id" '$2 == i { printf "%s%s", s, $1; s = ", " }' <<<"$declared")"
        n="Declared in \`models.yaml\` as: $keys. Source: $(_vget "$vendor" url)"
        row="$(_row_for "$id" "$vrows")"
        if [[ -n "$row" ]]; then
            IFS=$'\t' read -r _ state date repl <<<"$row"
            [[ "$date" != - ]] || date=""
            [[ "$repl" != - ]] || repl=""
            case "$state" in
                retired)
                    printf '%s\t%s\t%s\n' "$id-retired" "[upstream] models: $id is retired" \
                        "The $vendor page lists \`$id\` as retired${date:+ ($date)}. A call that names it fails.\n\n$n\n\nA box changes the ID in \`models.yaml\` to the one the vendor recommends, after checking what that changes (cost, quality, a Vertex region). The title is the signal (#2227)." ;;
                deprecated)
                    printf '%s\t%s\t%s\n' "$id-deprecated" "[upstream] models: $id is deprecated" \
                        "The $vendor page lists \`$id\` as deprecated${date:+, shutting down $date}${repl:+, with \`$repl\` as its replacement}.\n\n$n\n\nA box changes the ID in \`models.yaml\` before the shutdown, after checking what the replacement changes (cost, quality, a Vertex region) (#2227)." ;;
                superseded)
                    printf '%s\t%s\t%s\n' "$id-successor-$repl" "[upstream] models: $id has a successor $repl" \
                        "The $vendor page names \`$repl\` as the replacement of \`$id\`, which is not shutting down.\n\n$n\n\nA person decides whether isx moves to it (#2227)." ;;
            esac
        fi
        if [[ "$vendor" == anthropic && ( -z "$row" || "$state" == active ) ]]; then
            succ="$(_newer_anthropic "$id" "$vrows")"
            [[ -z "$succ" ]] || printf '%s\t%s\t%s\n' "$id-successor-$succ" "[upstream] models: $id has a successor $succ" \
                "The $vendor page lists \`$succ\`, an active model of the same family and newer than \`$id\`.\n\n$n\n\nA person decides whether isx moves to it (cost, quality, a Vertex region) (#2227)."
        fi
    done <<<"$declared"
}
