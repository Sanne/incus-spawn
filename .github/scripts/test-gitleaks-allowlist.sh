#!/bin/bash
# Checks that both gitleaks configs allowlist test fixtures by value, not by file (#1484): on a
# copy of the tracked tree with real-shaped Anthropic API and OAuth keys, GitHub ghp_/gho_ tokens,
# an RSA private key and a Basic auth header added to every tracked test file, a scan reports each
# of them in every file and nothing else. Also checks that no path but the vendored ruleset file is
# allowlisted, that nothing suppresses by stopword, commit, inline allow comment or ignore file,
# retargets the regexes, or disables or replaces a base rule, and holds every allowlist regex to
# the ^...$ anchors .claude/rules/ci.md requires, with no unbounded repetition, that the root
# config's global allowlist holds every regex the leaktk one does, and that
# gitleaks.yml sets GITLEAKS_VERSION once, at the top, to the gitleaks this runs with.
# Needs bash, git, python3 (3.11+, for tomllib) and gitleaks, given as $1 or found on PATH.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
GITLEAKS="${1:-gitleaks}"
[[ "$GITLEAKS" != */* ]] || GITLEAKS="$(realpath "$GITLEAKS")"  # the scans run from a copy of the tree
WORK="$(mktemp -d)"
mkdir "$WORK/tree"  # the scan root; reports and lists stay outside it
trap 'rm -rf "$WORK"' EXIT
CONFIGS=(.gitleaks.toml .gitleaks-leaktk.toml)
fail=0

# The tracked files as staged, configs and fixtures alike, so one run sees one version of the repo
# and a local build's target/ or a stray untracked file changes nothing.
git -C "$REPO" checkout-index -a --prefix="$WORK/tree/"
cd "$WORK/tree"

# Both suppress by line or by file, beside the configs. The marker is split so this file has none.
allow="gitleaks"":allow"
if git -C "$REPO" grep --cached -l -F "$allow"; then
    echo "FAIL: the files above carry an inline $allow comment; allowlist the exact value instead"
    fail=1
fi
if git -C "$REPO" ls-files --cached | grep -E '(^|/)\.gitleaksignore$'; then
    echo "FAIL: a .gitleaksignore suppresses findings by fingerprint; allowlist the exact value instead"
    fail=1
fi

# Versions flag different values (8.24.3 reports a fixture 8.27.0 does not, #1495), so this test
# only vouches for the scans if gitleaks.yml sets the version it runs with once, at the top, and
# names no other.
tested="$("$GITLEAKS" version)"  # an assignment, so a missing binary stops the script here
python3 -I - .github/workflows/gitleaks.yml "$tested" <<'EOF' || fail=1
import re, sys
# Comment lines dropped, and the tag comment of a SHA-pinned action (@<sha> # v6.0.0), which is not
# a version. Other trailing " #" stay: in a run: script they can be shell text, a version after them included.
lines = [re.sub(r"^(\s*(?:- )?uses: *\S+)\s+#.*", r"\1", l)
         for l in open(sys.argv[1]).read().splitlines() if not re.match(r"\s*#", l)]
tested, ok = sys.argv[2].strip().removeprefix("v"), True
# The pin: one line in the top-level env block, which every job and step inherits.
start = next((i + 1 for i, l in enumerate(lines) if re.fullmatch(r"env: *(?:#.*)?", l)), len(lines))
end = next((i for i in range(start, len(lines)) if lines[i] and not lines[i].startswith(" ")), len(lines))
# A bare x.y.z: the install URL and gitleaks-action both add their own "v".
pin = r"""\s+GITLEAKS_VERSION: *(['"]?)([0-9.]+)\1 *(?:#.*)?"""
pins = {i: m[2] for i in range(start, end) if (m := re.fullmatch(pin, lines[i]))}
if len(pins) != 1:
    print("FAIL: gitleaks.yml must set GITLEAKS_VERSION once in its top-level env, as x.y.z with no leading v")
    ok = False
elif list(pins.values()) != [tested]:
    print(f"FAIL: gitleaks.yml pins gitleaks {list(pins.values())[0]}, but this test runs {tested}")
    ok = False
# Anywhere else it may only be read; a job-level env, a $GITHUB_ENV line or a hardcoded release
# number would run some job on a version this test never ran.
for i, l in enumerate(lines):
    if i in pins:
        continue
    if "GITLEAKS_VERSION" in re.sub(r"env\.GITLEAKS_VERSION|\$\{?GITLEAKS_VERSION\b\}?", "", l):
        print(f"FAIL: gitleaks.yml sets GITLEAKS_VERSION outside its top-level env: {l.strip()}")
        ok = False
    if re.search(r"(?<![0-9.])[0-9]+\.[0-9]+\.[0-9]+(?![0-9.])", l):
        print(f"FAIL: gitleaks.yml names a version outside its top-level GITLEAKS_VERSION: {l.strip()}")
        ok = False
sys.exit(0 if ok else 1)
EOF

for config in "${CONFIGS[@]}"; do
    python3 -I - "$config" "$WORK/upstream-$config.json" <<'EOF' || fail=1
import json, re, sys, tomllib
config, ok = sys.argv[1], True
def load(path):  # gitleaks reads its config through viper, which ignores the case of keys
    lower = lambda v: ({k.lower(): lower(x) for k, x in v.items()} if isinstance(v, dict)
                       else [lower(x) for x in v] if isinstance(v, list) else v)
    with open(path, "rb") as f:
        return lower(tomllib.load(f))
toml = load(config)
# The base ruleset: gitleaks' defaults, or the vendored leaktk file used as is. Anything else in
# [extend] (disabledRules, a wrapper file with its own allowlists) would hide findings unseen.
extend = toml.get("extend", {})
vendored = re.fullmatch(r"\.gitleaks/leaktk-gitleaks-[0-9.]+\.toml", str(extend.get("path", "")))
if not (extend == {"usedefault": True} or (set(extend) == {"path"} and vendored and "extend" not in load(extend["path"]))):
    print(f"FAIL: {config}: [extend] must be useDefault = true or the vendored .gitleaks/ ruleset alone: {extend}")
    ok = False
# The vendored ruleset's own path allowlists, globally (key "") and per rule id, for the scan below.
upstream = {}
if vendored:
    base = load(extend["path"])
    paths = lambda t: [p for a in [t.get("allowlist", {}), *t.get("allowlists", [])] for p in a.get("paths", [])]
    upstream = {"": paths(base), **{rule["id"]: paths(rule) for rule in base.get("rules", [])}}
with open(sys.argv[2], "w") as f:
    json.dump(upstream, f)
# Own rules add detection; one reusing a base rule's id would replace that rule, weakened or not.
for rule in toml.get("rules", []):
    if rule.get("id") not in {"anthropic-api-key", "anthropic-oauth-token"}:
        print(f"FAIL: {config}: [[rules]] id {rule.get('id')} would add to or replace a base rule; review it here first")
        ok = False
# Every allowlist form gitleaks reads: the global table, the [[allowlists]] array, and per rule.
tables = [toml.get("allowlist", {}), *toml.get("allowlists", [])]
for rule in toml.get("rules", []):
    tables += [rule.get("allowlist", {}), *rule.get("allowlists", [])]
if [p for t in tables for p in t.get("paths", [])] != [r"^\.gitleaks/leaktk-gitleaks-[0-9.]+\.toml$"]:
    print(f"FAIL: {config}: a path allowlist other than the vendored ruleset file hides every secret in its files")
    ok = False
# stopwords and commits suppress outside the exact-value list; regexTarget makes the anchors
# apply to the match or line instead of the secret.
for key in sorted({k for t in tables for k in t} & {"stopwords", "commits", "regextarget"}):
    print(f"FAIL: {config}: allowlist sets {key}; list exact values under regexes instead")
    ok = False
def top_level_alternation(regex):
    depth, i, in_class = 0, 0, False
    while i < len(regex):
        c = regex[i]
        if c == "\\":
            i += 1
        elif in_class:
            in_class = c != "]"
        elif c == "[":
            in_class = True
            i += regex.startswith("^", i + 1)
            i += regex.startswith("]", i + 1)  # a ] first in a class is a literal
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "|" and depth == 0:
            return True
        i += 1
    return False
def escaped_end(regex):  # \$ is a literal dollar: an odd run of backslashes before the last $
    return (len(regex) - 1 - len(regex[:-1].rstrip("\\"))) % 2 == 1
def multiline_flag(regex):  # (?m) makes ^ and $ match at any line, (?s) lets . span them
    return re.search(r"\(\?[a-zA-Z-]*[ms][a-zA-Z-]*[:)]", regex) is not None
def unbounded(regex):  # *, + or {n,} outside a class: a net for real lengths, whatever the anchors
    i, in_class = 0, False
    while i < len(regex):
        c = regex[i]
        if c == "\\":
            i += 1
        elif in_class:
            in_class = c != "]"
        elif c == "[":
            in_class = True
            i += regex.startswith("^", i + 1)
            i += regex.startswith("]", i + 1)
        elif c in "*+" or re.match(r"\{[0-9]*,\}", regex[i:]):
            return True
        i += 1
    return False
for regex in (r for t in tables for r in t.get("regexes", [])):
    if (not (regex.startswith("^") and regex.endswith("$")) or escaped_end(regex)
            or top_level_alternation(regex) or multiline_flag(regex)):
        print(f"FAIL: {config}: allowlist regex not anchored ^...$ as a whole: {regex}")
        ok = False
    if unbounded(regex):
        print(f"FAIL: {config}: allowlist regex has an unbounded *, + or {{n,}}; list exact values: {regex}")
        ok = False
sys.exit(0 if ok else 1)
EOF
done

# rh-gitleaks runs leaktk-style rules but reads only the default-named .gitleaks.toml, so the root
# config's global allowlist holds every value the leaktk one does, beside the stock rules' own.
python3 -I - "${CONFIGS[@]}" <<'EOF' || fail=1
import sys, tomllib
def regexes(path):  # viper ignores the case of keys
    with open(path, "rb") as f:
        table = {k.lower(): v for k, v in tomllib.load(f).items()}.get("allowlist", {})
    return {k.lower(): v for k, v in table.items()}.get("regexes", [])
root, leaktk = sys.argv[1:3]
missing = [r for r in regexes(leaktk) if r not in regexes(root)]
for regex in missing:
    print(f"FAIL: {leaktk} allowlists {regex}, which {root} does not: rh-gitleaks reads only {root}")
sys.exit(1 if missing else 0)
EOF

# Text files only (gitleaks skips binaries, so a key appended to one would never be reported), and
# no symlinks, which an append would follow out of the copy.
git -C "$REPO" grep --cached -I --name-only -e '' -- '*/src/test/*' | while IFS= read -r file; do
    [ -L "$file" ] || echo "$file"
done > "$WORK/planted.txt"

# One real-shaped key, at real length, per credential shape the allowlists name exact values
# under, so a value entry widened into a net for that shape is caught: Anthropic keys run 90+
# characters past the prefix, GitHub tokens 36, and an RSA key block is 64-character base64 lines.
# Mixed-case bodies, as real ones have, from a fixed seed so every run is the same.
python3 -I - "$WORK/planted.txt" "$WORK/keys.json" <<'EOF'
import base64, json, random, string, sys
rng, alnum = random.Random(1484), string.ascii_letters + string.digits
body = lambda length, alphabet: "".join(rng.choice(alphabet) for _ in range(length))
keys = {prefix: {"secret": prefix + body(length, alphabet)} for prefix, length, alphabet in [
    ("sk-ant-api03-", 95, alnum + "_-"), ("sk-ant-oat01-", 95, alnum + "_-"),
    ("ghp_", 36, alnum), ("gho_", 36, alnum)]}
pem = "RSA PRIVATE" + " KEY-----"  # split, or the scanners read this file as holding a key
keys["RSA private key"] = {"secret": "\n".join(["-----BEGIN " + pem, *(body(64, alnum + "+/") for _ in range(24)),
                                                body(44, alnum + "+/") + "==", "-----END " + pem])}
# A git credential in a Basic auth header, as SecretRedactorTest's fixture has it. The rule that
# captures the header's value is leaktk's; gitleaks' defaults have none, so only leaktk must report it.
# Newer gitleaks (8.30.1) also decodes the base64 and reports the token inside, planted too.
token = f"ghp_{body(36, alnum)}"
basic = base64.b64encode(f"x-access-token:{token}".encode()).decode()
keys["Basic auth header"] = {"line": f"Authorization: Basic {basic}", "secret": basic, "inner": token,
                             "configs": [".gitleaks-leaktk.toml"]}
json.dump(keys, open(sys.argv[2], "w"))
# Prepended: leaktk's Private Key rule matches lazily from the first BEGIN line, so a key block
# after a fixture's fake one would be captured together with it, not on its own.
for file in open(sys.argv[1]).read().splitlines():
    with open(file) as f:
        text = f.read()
    with open(file, "w") as f:
        f.write("".join(f"planted {key.get('line', key['secret'])}\n" for key in keys.values()) + text)
EOF

for config in "${CONFIGS[@]}"; do
    # Leaks are expected, so gitleaks' exit code is not the verdict.
    "$GITLEAKS" dir -c "$config" --no-banner --log-level error --exit-code 0 -r "$WORK/report.json" . >/dev/null
    python3 -I - "$WORK/report.json" "$WORK/planted.txt" "$WORK/keys.json" "$config" "$WORK/upstream-$config.json" <<'EOF' || fail=1
import json, re, sys
leaks = json.load(open(sys.argv[1]))
planted, config = open(sys.argv[2]).read().splitlines(), sys.argv[4]
planted_keys = json.load(open(sys.argv[3]))
keys = {label: key["secret"] for label, key in planted_keys.items() if config in key.get("configs", [config])}
# The vendored ruleset skips some paths, globally or in the rule that reports a key (leaktk's
# Private Key rule skips tests?/.*\.txt). That is upstream data, not this repo's allowlist, so a
# miss there is counted, not failed.
upstream = {rule: [re.compile(p) for p in paths] for rule, paths in json.load(open(sys.argv[5])).items()}
secrets = {key.get(part) for key in planted_keys.values() for part in ("secret", "inner")}
others = [leak for leak in leaks if leak["Secret"] not in secrets]
for leak in others:
    print(f"FAIL: {config}: the tracked tree reports {leak['RuleID']} in {leak['File']}")
found = {(leak["Secret"], leak["File"]) for leak in leaks}
missed = 0
for prefix, key in keys.items():
    unreported = [f for f in planted if (key, f) not in found]
    rules = {""} | {leak["RuleID"] for leak in leaks if leak["Secret"] == key}
    skips = [p for rule in rules for p in upstream.get(rule, [])]
    # A key reported nowhere gets no allowance: something hides it, whatever the paths say.
    files = [f for f in unreported if len(unreported) == len(planted) or not any(p.search(f) for p in skips)]
    for f in files:
        print(f"FAIL: {config}: a real-shaped {prefix} in {f} is not reported")
    skipped = f", {len(unreported) - len(files)} skipped by the vendored ruleset's paths" if unreported != files else ""
    print(f"{config}: planted {prefix} reported in {len(planted) - len(unreported)} of {len(planted)} test files{skipped}")
    missed += len(files)
sys.exit(1 if missed or others else 0)
EOF
done

exit "$fail"
