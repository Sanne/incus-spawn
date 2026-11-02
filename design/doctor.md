# Doctor, support bundles and secret scanning

How isx diagnoses a host, what it collects for a report, and how CI keeps secrets out of the repository.

## Doctor: a finding that is neither healthy nor broken

`isx doctor` reports four statuses, not three.
`OK`/`WARN`/`FAIL` cover "fine", "you should look at this" and "this is broken"; `NOTE` (`·`) states something about the setup that nothing is waiting on.
Only `WARN` and `FAIL` answer `Status.isProblem()`, which is what the exit code, the "N issue(s) can be addressed" list and the closing summary all consult.
So a note can never turn a clean run into a reported problem, and never dilutes the exit status a script checks.

It exists because of unconfigured credentials.
Every tool's credential is offered unconditionally (`ToolProxyResolver.findUnresolved`), so "OpenAI key not set" says nothing about health on its own.
It is missing for the user who has never touched codex exactly as it is for the user whose template installs it.
Before `NOTE` those were the same yellow line.
The graduation of `codex` out of the `openai` feature flag would have added one to every existing user's `doctor` output — the classic way a diagnostic becomes something people learn to ignore.

Doctor distinguishes the two by asking whether a template *the user wrote* declares the tool.
Built-in definitions are excluded deliberately: they ship with isx and declare tools for everyone (`tpl-dev` has `gh`), so counting them would warn about every credential again and put us back where we started.
Templates are only the triage signal, not the gate — `isx branch` already refuses to run without the credentials a template actually needs (`CredentialCheck`), so a note here cannot let a broken build through.
Anthropic credentials stay a `WARN` regardless: isx itself uses them for `isx ask`, so there is no template to check against.
If the definitions can't be read at all, every tool counts as in use — the conservative direction is to keep reporting.

## Doctor and build remediations act through isx, never name an `incus` command

A remediation says *what* is done, and isx does it: through `IncusClient` over REST where it can, so it works the same over the Unix socket and over the macOS vsock tunnel (#986).
A hint telling the user to run `incus storage|profile|move|image ...` fails on a Mac, whose host has no `incus` CLI (#939).
On Linux it ties the advice to Incus's CLI syntax for an operation isx already knows how to perform.
So the default profile's root disk is repointed with `updateProfileRootDiskPool`, and stopped instances off the CoW pool are moved with `moveToPool` (`instance_pool_move`).
That move copies to a temporary name, deletes the original and renames the copy back, so it gets `rename()`'s post-check (#717); a full copy can outlast one `/wait` poll, which the shared operation wait (#1089) already sees through.
A running or frozen instance is listed, never stopped for the move; one in any other state (stopped, or `Error` after a failed start) is moved.
A build whose base image's `/sbin/init` fails with "Exec format error" deletes the failed instance and the image its `volatile.base_image` names without asking when isx can get it back, since the cache is known corrupt.
A remote image is launched once more, and a local one isx imported from the template's `image_url` is re-imported by the re-run it asks for.
A local image with no `image_url` behind it (imported by hand) is the only copy, so it is kept, and the error says to replace it.
The check sits around the start (`BuildCommand.launchBuildInstance`), because that is where the fault shows.
Creating the instance runs nothing in it, so Incus fails the *start* (forkstart exits 1) and LXC writes `<errno> - Failed to exec "/sbin/init"` to the instance's `lxc.log`.
Until #986 the hint was checked after the create and so could never fire.
A base image imported by another project stays a trust gate: at a terminal the build offers to delete it (`--yes` does not answer for it) and stops either way.
Without a terminal it says to re-run in one.
A new `isx image delete` or `isx move` command, or "run it in `isx vm shell`", were the alternatives, rejected because each exposes an operation the user should not need to know about.
The hints `isx init` prints during Linux setup, before isx manages a pool or profile, are the exception.
`noCowPoolMsg`'s Linux arm was one until #1656, when its `size=100GiB` command would have made the nested loop file on a btrfs host; it names `isx doctor`, whose remediation picks the backing.
#1139 extended the rule to `incus network` and `incus remote`: the bridge-subnet conflict diagnostic now says only to run `isx doctor`, which offers to move the bridge to a free subnet (#1203), and is shown on Linux only, since on macOS nothing reconfigures the bridge and the Mac's routes are not the VM's (#1202); an image naming an unknown remote says on macOS which remotes isx knows and which file it reads others from, and the `incus remote add` hint stays on Linux.
`gatewayUnavailableHint` and the proxy's `--gateway-ip` check already named `incus network` only on Linux.
`NoIncusCliHintTest` allowlists those literals one by one, scanning `cli`, `common` and `proxy`, and fails on any other.
#1139 then covered `incus console` and `incus config`: a VM agent failure saves the console log on the host and names that file, and an inbox removed for a missing directory is mounted again by the start that finds it back.
The macOS remediation for "no CoW pool" is still open (#1085): doctor offers none, and `noCowPoolMsg` names `isx vm delete`, which does not exist.

On Linux the bridge-subnet conflict diagnostic now points at `isx doctor` instead (#1203), whose "Bridge subnet conflict" finding applies init's fix without the rest of init (`InitCommand.moveBridgeOff`).
Reading the conflict (`BridgeSubnetCheck.detect`) changes nothing.
The remedy reads it again when the user agrees, since the routes may have changed meanwhile, and acts on that one read.
It asks for sudo before anything moves, then moves the bridge and migrates the instances.
It refuses before anything moves when no firewall is running, chosen as init chooses it (`FirewallDetector`), since the redirect could not follow the bridge.
Then it redoes only what names the old address (`HostSetup.followBridgeMove`): the cached gateway and the PREROUTING redirect, on that firewall.
On UFW that is the isx NAT block, which also holds the masquerade naming the subnet.
Installing or enabling a firewall stays init's.
It does not restart the proxy, which follows the move by itself (#966).
A password refused at the first prompt leaves the bridge where it was.
A migration that outlasts sudo's timestamp makes the firewall step ask again, after the bridge has moved; a refusal there fails the remedy and points at `isx init`.
A redirect not written, or an instance left on the old subnet, fails the remedy instead of reporting it done.
macOS gets no such finding: the bridge lives in the VM, whose routes the Mac's are not, and its wording is #1202's.

## Support bundles: redaction by construction

`isx doctor --bundle` exists so that diagnosing a remote failure is an attachment rather than an hour of screen-sharing.
That only works if a user can send the archive without auditing it first, which makes "no credential ever enters the bundle" a property the code must guarantee rather than a rule contributors are asked to remember.

**Secret locations come from the tool declarations, never from a list.**
Every tool that injects credentials already describes them: a `ToolDef.ConfigEntry` with `secret: true` and a `config-path`, resolved against the tool's `config-namespace` by `ProxyDef.fullConfigPath()`.
`SecretRegistry` (in `common/config/`) collects exactly those declarations and answers one question — where do secrets live in `config.yaml`.
Knowing what a credential *looks like* is a separate job, and lives in `SecretRedactor` alongside the token-shape patterns, so the registry stays provably declaration-derived.
The first implementation instead hardcoded two keys, and it went stale the way a hardcoded list always does: `bob.apiKey`, `openai.apiKey` and every YAML-declared tool secret (`typesafe.apiKey`) were written into support archives in the clear.
A registry fed by the declarations cannot drift, because the same declaration that makes the proxy inject a credential is what makes the redactor remove it.
A future pluggable secrets backend — keychain, 1Password, environment — reads the same locations; that is the seam it plugs into.

**A declaration holds wherever the key appears.**
A declared leaf is redacted at its exact path *and* anywhere else under its tool's namespace.
That is not breadth for its own sake.
When Claude credentials became a named map, `claude.accounts.<name>.apiKey` appeared in every real config, and an exact-path-only redactor wrote each account's token into the bundle while reporting that it had found no credentials to redact.
The tool declared that `apiKey` under `claude` is a credential; honouring that at any depth keeps the declaration true as the config grows a dimension, with no second list to update.
It stays narrow because only a declared namespace/leaf pair earns the deep walk — a map whose keys are user data, like `repo-paths`, is neither.
The whole-word name backstop covers what no tool declared: the catch-all extras, the undeclared keys a typed namespace keeps, and every key of each of its accounts.

**Structural redaction is the mechanism; scrubbing is a net.**
The config is serialized to a tree and the values at the registry's locations are replaced before anything is written, so nothing depends on a secret looking the way we expected it to look.
Logs and `instances.json` have no model to scrub, so they get the secondary treatment: exact replacement of the values structural redaction just removed, then well-known credential shapes (`sk-ant-`, `gh[pousr]_`, `github_pat_`, `Authorization:` headers, `user:pass@host` URLs, PEM private-key blocks).
The distinction is deliberate and stated in the bundle's own `REDACTIONS.txt`: a net can miss, which is why the config never relies on one.

**A redacted value is marked, not blanked.**
Secrets become `<isx:redacted:github.token>` rather than `""`, because empty is itself a valid value — without the marker, a credential that was removed and one that was never configured look identical, and those describe opposite bugs.
Unset keys are left empty for the same reason.

**One funnel.**
`SupportBundle.add()` is the only path into the archive: it scrubs, tallies, then writes.
A collector added later cannot leak by forgetting to redact, because there is no other way in.
The tally becomes `REDACTIONS.txt`, so the recipient can see what was withheld and the sender can check before sending.

**`--bundle` implies `--deep`.**
The in-container DNS and TLS probes are the definitive end-to-end evidence; a bundle without them is one we would have to ask the user to generate again.

The undeclared-key backstop (`SecretRedactor.looksSecret`) matches whole words, not substrings, so `apiKey` and `AUTH_TOKEN` are withheld while `monkey` and `keyboardLayout` survive — substring matching would quietly blank out the diagnostics the bundle exists to carry.
How far a matched name's authority reaches follows the same instinct: **the nearest key name governs**.
A string is redacted; a *list* inherits the name, its elements having none of their own (`tokens: [...]` is a list of tokens); an *object* does not, because its fields carry their own names and those are better evidence than the parent's.
Blanket-redacting an object cost more than it protected — an ordinary `auth: {enabled, endpoint, timeoutMs}` block lost every field, and the endpoint, now counted as a secret value, was scrubbed out of every log in the archive.
A declaration outranks all of this: a declared path is taken at its word and redacted whole, whatever shape it holds, so a tool that stores a structured credential should declare it rather than rely on the name.

## Secret Scanning in CI

`.github/workflows/gitleaks.yml` guards against a real leaked credential landing in the repo, as distinct from the credential-isolation work above, which guards containers against ever holding one.
It runs `gitleaks/gitleaks-action@v3` on every push and PR targeting `main` (on a push, root jobs are gated with `if: github.event_name != 'push' || github.ref_name == 'main' || github.event.repository.fork`, so feature-branch pushes only run on forks).
It scans every commit in that push or PR, not just the final diff, since a secret introduced and later reverted in the same range is still live in history; it does not re-scan the repository's earlier history.

The scan runs twice, against two separate rulesets, because gitleaks' `[extend]` table only supports one base ruleset per config file (`path` and `useDefault` are mutually exclusive — see `gitleaks/config/config.go`) and `extend.url` is an unimplemented stub in gitleaks itself.
So a ruleset can't be pulled in live at scan time either.
One pass extends gitleaks' own built-in rules (`.gitleaks.toml`).
The other extends a vendored copy of [leaktk/patterns](https://github.com/leaktk/patterns)' generated gitleaks config (`.gitleaks-leaktk.toml` → `.gitleaks/leaktk-gitleaks-8.27.0.toml`).
leaktk/patterns is not incidental.
It's the closest public equivalent to the internal Red Hat "Pattern Server" that `rh-gitleaks` (an internal tool wrapping gitleaks) draws from, and its own README says as much — a false positive it produced during setup was on a rule literally named "Authorization Header," matching a finding category `rh-gitleaks` had independently reported against this repo.
`rh-gitleaks` itself can't be wired into this workflow: it needs to reach that internal Pattern Server, which a public-repo GitHub Actions runner has no path to.

Both rulesets miss one thing neither maintains: an Anthropic-key-specific rule.
`.gitleaks.toml` and `.gitleaks-leaktk.toml` each add `anthropic-api-key`/`anthropic-oauth-token` rules for `sk-ant-api03-`/`sk-ant-oat01-`, gated on a 40+ character suffix rather than the prefix alone.
Real keys run 90+ chars past the prefix, while this repo's own test fixtures (which legitimately need key-shaped strings to exercise `SpawnConfig`/`SecretRedactor` parsing) top out at 28, so the length gate catches a real leak without re-flagging a fake one.
Each config's `[allowlist]` names test fixtures by their exact values, never by file (#1484): a path entry hid every secret in that file from every rule, so a real key pasted into a fixture by mistake went unreported.
The only path left is the vendored pattern database file, `.gitleaks/leaktk-gitleaks-<version>.toml`.
`.gitleaks.toml` also carries the leaktk config's fixture values, because `rh-gitleaks` reads only that default-named config while running leaktk-style rules.
The allowlist test below fails when one of them is missing there.
The regexes are anchored at both ends and list exact values, or a shape no service issues; a prefix net (`sk-ant-api03-` plus a few characters) is refused because it would hide a short real secret in any file.
`.github/scripts/test-gitleaks-allowlist.sh` checks that no fixture path comes back and that a real-shaped key in any test file is reported; how gitleaks matches the regexes is in `.claude/rules/ci.md`.
