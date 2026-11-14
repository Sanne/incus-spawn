# The upstream watch

How pinned upstream artifacts (the appliance kernel, the base image, tool tarballs) move forward without a person reading a diff a script wrote, and why only a proven regeneration may merge by itself (#2036).

## The model: a manifest, an engine, two workflows and a merge gate

Every pin in isx follows one rule: take the checksum from the publisher, prove it again in CI, and change it only through a reviewed PR (CLAUDE.md, #1678, #1355).
The kernel and base-image bump jobs did the first two, and what was left was Sanne clicking merge on a diff a script had written.
Sanne's words, which set the invariant this page implements:

> Good ideas for the mechanical GithubActions job.
> Let's make sure that anything that is deterministic and can trigger say a PR via a non-agentic script could be sent using my own private token on an isolated actions job, so that you can approve and merge such PRs automatically if they pass the right gates.
> For anything needing judgement, it would be implemented by a box you coordinate and require my approval to merge as usual.
> Assign this brief to a planner box using the Fable model to ensure we maintain security and honour our project principles.

The bot never judges a diff.
It checks that a PR *is* the trusted engine's output for one item at one version, so even an attacker holding the credential that opens PRs can land only what the engine would have landed anyway.
Four parts carry that:

**The manifest** (`upstream/manifest.yaml`) declares every watched artifact, one item each, as a policy file.
An item names its `class` (`mechanical`, `review`, or `judgement` by default), what a bump may move `within` (`series` or `any`), its `cooling` period, which `go:` label a `judgement` issue gets (`design` by default, or `build`), its `source.type`, its `verify.type`, the `files` and `fields` the bump rewrites, and the `gates` its PR needs beyond CI.
`upstream.sh validate` and `UpstreamManifestTest` hold its rules: a mechanical or review item needs `within`, a source, a verifier and files; a mechanical item's verifier is `signed-sums` or `publisher-sums`; `verify.type` must be what the source type provides; a signed verifier's key must be a committed file and its fingerprint the one `update-kernel-pin.sh` pins; `fields` must be exactly what the source type reads; an unknown key fails, since there is no key that skips a check.

**The engine** (`upstream/engine/upstream.sh`, with `lib.sh`, `pr.sh`, `app-token.sh` and one `source-<type>.sh` per source type) is a deterministic script that runs from `main`'s checkout only.
`current`, `releases`, `check` and `apply` read and rewrite one item; `plan` decides every item (a prepared commit, an issue, or nothing) with no app credential; `issues` opens what `plan` wrote; `publish` pushes the prepared commits and opens or refreshes their PRs as the app; `verify-pr` and `verify-trees` are the check; `track` keeps the one `[upstream] watch failing` issue.
Everything an upstream sends (a version, a tag, a URL, a sum) is data: it is matched against a shape before use and never reaches a shell line unquoted or a jq filter unbound.
The three source types reuse what already verified a pin by hand: `kernel-org` is `appliance/kernel/update-kernel-pin.sh` (which gained `releases [<major>]` and `sum <version>`), `github-release` is `.github/scripts/base-image-sums.sh` and `verify-base-image-sums.sh`, and `jetbrains` reads the products API with a URL prefix allowlist and the `.sha256` beside each tarball.
`github-asset` and `signed-file` (#2210) are described under "The github-asset source type" and "The signed-file source type" below.

**The watch** (`.github/workflows/upstream-watch.yml`) runs every six hours and on `workflow_dispatch` (an optional `item`, checked against the manifest's keys), in `Sanne/incus-spawn` from `main` only, as two jobs.
It has no PR, issue or comment trigger, `persist-credentials: false`, `contents: read` and `issues: write`, no third-party action, and no `${{ }}` inside any `run:`.
The `plan` job runs in no environment: with the run's own read-only token it does every download and signature check, prepares each bump as a commit, opens the issues, and hands the plan on as an artifact (the decisions, the issue texts and each prepared commit as a git bundle under `refs/upstream-prepared/<item>`).
The `publish` job is the only one in the `upstream-watch` environment, whose deployment branch policy admits `main` alone, so the only one that can read the app's key; it runs on another runner, does not fetch anything from an upstream, and its one credentialed step mints a one-hour installation token of the `isx-upstream[bot]` GitHub App from the environment's `UPSTREAM_APP_ID` and `UPSTREAM_APP_PRIVATE_KEY` (`app-token.sh`: an RS256 JWT signed by `openssl` on a file descriptor, then an installation token limited to this repository and to `contents` and `pull_requests` write), masks it, unsets the key and runs `publish`.
Two jobs rather than two steps because steps of one job share a runner and a user, so a process the planning step left behind could read a later step's environment; ordering steps is not isolation.
The app's commits carry its login and noreply address, resolved from the public user id, since the ruleset asks an extra approval for unattributed changes.
Issues are opened with the run's own token: one per judgement item and version, one when a `within: series` item's publisher lists a newer series, one when a pinned version's values no longer match what the publisher gives for it (a republished or tampered release, never a silent re-pin), the kernel's `[upstream] kernel: series <s> is EOL`, and `[upstream] watch failing` from a final `track` job that judges both jobs' results once, after both, so a plan that passed never closes the issue a failed publish is about to open; the next run where both pass closes it.
The kernel source reads the next major's signed sums file as well once kernel.org publishes it (a missing file is no release yet; one that does not verify fails the run), and an EOL issue of a series no longer pinned or no longer ended is closed by the next run, the retired job's `[kernel] <s> is EOL` title included.
A bump's downloads are re-hashed once per version: when this repository's open PR already carries it, `plan` takes the sums from the publisher without the tarballs, since `publish` will not push anything and `upstream-verify` re-hashed that PR.

**The PR mechanics** (`pr.sh`) are `update-kernel.yml`'s, ported for every item (#1355): one PR per item on `upstream/<item>`, this repository's branch only and never a fork's of the same name; a refresh marks the PR pending before the push and names the version only after it, so a failure between the two leaves a PR that claims nothing its branch lacks; a version whose PR was closed unmerged is recognised by the marker in its body and not proposed again; a non-merge commit on the branch the app did not make stops any push or close; a PR `main` has caught up with is closed and its branch deleted, unless the branch carries a commit the app did not make, which stops the close as it stops a push (the run fails so a person sees it).
A newer release replaces the PR's commit rather than opening a second PR, and a `cooling` period that has not elapsed, or a release the publisher does not date yet, makes the item wait.

**The check** (`.github/workflows/upstream-verify.yml`, `upstream-verify`) runs on every PR under `pull_request_target`, checks out the default branch, holds no secret and `contents: read` and `pull-requests: read` only, and reads the PR's trees as data through git and the API.
It passes at once when no item's files changed, so it can be a required check.
For anyone else's bump it re-derives every declared field of each touched item from the publisher, re-hashing the downloads, and fails on a difference.
For a PR by the app from this repository it requires more: exactly one item, `class: mechanical` or `review`, only that item's files, a version newer than the base's and of the same series when `within: series`, a version the engine would propose itself (its `cooling` period over since the publisher dated it, and not one a closed unmerged `upstream/<item>` PR declined), every regenerated sum of a sum's shape, and a tree byte-identical to `main`'s engine applying that version to the merge base; anything else in the diff fails it.
The regeneration runs in a subshell with errexit on and `inherit_errexit` set, since a function on the left of `||` runs with errexit off and a `die` inside `$(...)` would otherwise leave an empty sum that the final diff passes (the review of #2047 reproduced exactly that); the fixture test keeps that case.
A PR touching `upstream/**` or `.github/**` is never the app's regeneration of any item, so the engine and its manifest change only through a PR a person merges, checked by `main`'s copy of themselves.

**The merge gate** lives outside Actions, in the coordinator box: isx-workflow's `bin/upstream-merge.sh` is the only thing that ever approves or merges as the bot (its documentation is in that repository, WORKFLOW.md "Upstream bumps").
It derives the item from the branch name, reads the manifest from `main` through the API and requires `class: mechanical`, the app as author, a same-repository head, base `main`, changed files within the item's `files`, `upstream-verify` concluded `success` at the head SHA, every other check `success` or `skipped` with none pending, the item's `gates` green, no changes requested, not draft, a clean merge state and no commit by anyone but the app; then `review --approve` and `merge --squash --match-head-commit <sha>`.
Any API error or missing field is a refusal, and a refused PR is left alone: the next watch run refreshes it, and nobody fixes it in place.
A `review` item's PR fails the script's first requirement, so the bot never approves or merges one ("The review class" below).
Opening lives in Actions as the app and approving lives in the coordinator as the bot; neither holds both credentials, so landing a bad change needs both compromised.

## The items

| Item | File | Source | Verifier | Class | `within` | `cooling` | Judgement issue |
|---|---|---|---|---|---|---|---|
| `kernel` | `appliance/kernel/build-kernel.sh` (`KERNEL_VERSION`, `KERNEL_SHA256`) | kernel.org's signed `sha256sums.asc`, through `update-kernel-pin.sh` | `signed-sums`, the committed autosigner key and pinned fingerprint | mechanical | `series` | 24h | a new series, the series' EOL (`go:design`); gate `kernel-mac-verified` |
| `base-image` | `common/src/main/resources/images/minimal.yaml` (tag, both URLs, four sums) | `Sanne/incus-spawn-images` releases, `SHA256SUMS` and `SHA256SUMS.v2` | `publisher-sums` | mechanical | `series` (one Fedora line) | 0 (first party) | a new Fedora line (`go:build`) |
| `idea-backend` | `common/src/main/resources/tools/idea-backend.yaml` (`description`, both URLs and sums) | JetBrains products API, `.sha256` beside each tarball, URLs under `https://download.jetbrains.com/idea/` only | `publisher-sums` | mechanical | `any` | 48h | none; the tool installs whatever `idea-IU-*` unpacks to |
| `starship` | `common/src/main/resources/tools/starship.yaml` (`description`, both URLs and sums) | `starship/starship` releases, the `.sha256` asset beside each tarball | `publisher-sums` | mechanical | `series` (the major) | 48h | a new major (`go:build`) |
| `idea-index-plugin` | `common/src/main/resources/tools/idea-backend.yaml` (the plugin's `downloads:` entry) | `hechtcarmel/jetbrains-index-mcp-plugin` releases, GitHub's digest of the signed zip | `asset-digest` | review | `series` (the major) | 48h | a new major (`go:build`) |
| `jasolo` | `common/src/main/resources/tools/jasolo.yaml` (`description`, the jar's URL and sum) | `mkouba/jasolo` releases, GitHub's digest of the runner jar | `asset-digest` | review | `series` (the major) | 48h | a new major (`go:build`) |
| `maven-3` | `common/src/main/resources/tools/maven-3.yaml` (description, URL, sum, link, `MAVEN_HOME`) and `.mvn/wrapper/maven-wrapper.properties` (the zip's URL and `distributionSha256Sum`) | Apache's `maven-3/` listing, the `.asc` of each file, signed by the release manager | `signed-file`, `upstream/keys/apache-maven.asc` | mechanical | `series` (3.9) | 24h | a new series (`go:build`) |
| `mvnd` | `common/src/main/resources/tools/mvnd.yaml` (both URLs, sums and links) | Apache's `mvnd/` listing, the `.asc` of each zip | `signed-file`, `upstream/keys/apache-mvnd.asc` | mechanical | `series` (1.x) | 24h | a new major (`go:build`) |
| `incus` | `appliance/build.sh` (`INCUS_VERSION`, `INCUS_SHA512`) | linuxcontainers.org's `downloads/incus/` listing of LTS releases (`X.0.Y`), the `.asc` of the tarball | `signed-file`, `upstream/keys/incus-stgraber.asc` | mechanical | `series` (7.0) | 24h | a new LTS (`go:design`); gate `kernel-mac-verified` |
| `alpine-minirootfs` | `appliance/build.sh` (`ALPINE_VERSION`, both `ALPINE_SHA256`) | Alpine's `v3.24/releases/<arch>/` listing, the `.asc` of each tarball | `signed-file`, `upstream/keys/alpine-ncopa.asc` | mechanical | `series` (3.24) | 24h | a new minor, seen in the latest-stable feed (`go:design`); gate `kernel-mac-verified` |
| `arch-cloud-image` | `scripts/distro-images.env` (`ARCH_IMAGE_URL`, `ARCH_IMAGE_SHA256`) | the Arch mirror's `images/` listing, the signed `.SHA256` of the image | `signed-file`, `upstream/keys/arch-linux.asc` | mechanical | `any` | 24h | none |
| `ubuntu-cloud-image` | `scripts/distro-images.env` (`UBUNTU_IMAGE_URL`, `UBUNTU_IMAGE_SHA256`) | Canonical's `releases/noble/` listing, the `SHA256SUMS` signed by `SHA256SUMS.gpg` | `signed-file`, `upstream/keys/ubuntu-cloud-images.asc` | mechanical | `any` | 24h | none |

The `base-image` item takes over what `update-base-image.yml` did (#1678, #1862): every checksum comes from the release's own sums files for the asset each URL downloads, a release of another Fedora line is an issue rather than a PR, and `vm_image_url` moves to `-vm-direct` in the first release that has it for both arches and never back to the stock image (design/templates.md "Which base image an instance runs").
Its series is a tag's whole line, its channel included (`src_series`, #1994), and it lists the stable channel's releases alone, so a channel is never proposed and never opens an issue.
Its versions are ordered as isx orders release tags (`src_order`): by line, then by date and that day's number, with `fedora-45-v1`, the one typed tag, as the stable line built on 2026-11-04.
`sort -V` ranked `fedora-45-v1` above every dated Fedora 45 tag, so a built-in on it never moved.
A source type may define `src_order` (versions on stdin, oldest first) wherever `sort -V` is wrong for it; `newest` uses it, and `publish` loads each item's source type so `pr.sh` compares versions the same way.
The images repo's `repository_dispatch` to this one, and its `INCUS_SPAWN_DISPATCH_TOKEN`, retire in favour of the six-hourly poll: the payload was only ever cross-checked against the release.
`publisher-sums` proves consistency and transfer, not provenance, since the sums come from the same origin as the artifact; the manifest says so in the verifier's name rather than pretending otherwise.
Items the design inventories for later phases (Actions pinned to commit SHAs, the siblings' `ISX_REF` and engine pins, the Maven plugins and libraries) and the judgement-only ones (Quarkus, GraalVM, herdr, zmx) are listed on the issue; each lands as a manifest item with a source type and its fixture tests, never as a special case.

## The github-asset source type

A tool YAML that downloads assets of one GitHub release is pinned by a `github-asset` item (#2210).
The manifest names the `repo`, the `tag-prefix`, one asset name per label (`x86_64`, `aarch64` or `any`, with `{version}` standing for the version) and where the sum comes from.
The version is read from the URLs in the tool YAML, so the YAML holds each pin once, and `versioned-description: true` also moves the version named in its `description`.
Its series is the major version, so a new major opens an issue.
A draft, a prerelease, a tag that is not numeric and a release that lacks one of the assets (or its `.sha256`) is never a candidate.
Every URL is built from the repository, the tag and the asset name, and must be the one GitHub lists for that asset, so a feed cannot send the engine elsewhere.
The engine downloads each asset again and checks that it hashes to the sum.
`sums: sha256-asset` takes the sum from the `<asset>.sha256` beside it, and the verifier is `publisher-sums` (starship).
`sums: github-digest` takes GitHub's digest of the uploaded asset, for a release that ships no sums, and the verifier is `asset-digest` (the IntelliJ index plugin, and jasolo).
That digest is GitHub's record, not a sum the publisher signed, and the re-hash proves only that the two agree.
So `validate` refuses it on a mechanical item and the bot never merges such a PR: the item is `class: review`.
jasolo is a review item for that reason alone, though Sanne asked for a mechanical one on #2206: it would become mechanical with `sums: sha256-asset` once its releases ship a `.sha256` beside the jar.
What this gives up: the plugin's pin lags until Sanne merges, and the download now happens on every `idea-backend` build, `index-mcp` or not, since a `downloads:` entry has no condition (the host caches it).

Two items may name one file, as `idea-index-plugin` and `idea-backend` do.
A bump then changes the file of both, so `upstream-verify` counts an item as changed only when a field it declares differs between the merge base and the head, and an app PR that changes the fields of two items fails.
The regeneration check still compares the whole tree, so a change to anything else in the file fails as before.

## The signed-file source type

A download whose publisher signs it with OpenPGP is pinned by a `signed-file` item (#2210): Maven and mvnd (Apache), Incus (linuxcontainers.org), Alpine's minirootfs, and the Arch and Ubuntu cloud images the distro tests boot.
The proof is a signature, so the verifier is `signed-file`, which `validate` accepts on a mechanical item beside `signed-sums` and `publisher-sums`.
The manifest says where the versions are listed (`source.index`: a page and a regex with one capture group, matched against each link or line), which files the item pins (`source.files`, one label each), and where each pin sits in the tree (`source.pins`).
The date on a version's line in the listing is its release date, so `cooling` counts from it.
`series-parts` says how many dotted parts of a version name its series (default 2), and `successors` is a second page whose versions only show that another series exists: Alpine's latest-stable feed, so 3.25 opens an issue although the pinned listing is `v3.24/`.
Incus lists LTS releases only (`X.0.Y`), so a feature release is never a candidate and a new LTS is the issue.

A pin is a literal text of a file with `{version}` and `{sum}` (or `{sum:<label>}`) where the values are (`pins.pl`).
Only the two placeholders are patterns, so nothing a publisher sends changes what a pin matches.
Every occurrence is read and must agree, `count` says how many there must be, and every one is rewritten, so a version that appears in a description, a URL and an environment variable moves as one.
`src_fields` reads the values back, so `upstream-verify` can tell which item a PR changes.

Two proofs, chosen per file:
- `artifact`: the `.asc` (or `sig-suffix`) is a signature of the file itself.
  The engine downloads both, verifies, and hashes the file with `algo` (`sha256` or `sha512`, whichever the pin holds).
  It never takes a `.sha256` or `.sha512` the publisher lists beside the file: those are unsigned.
- `sums`: the signature is of the publisher's sums file, which gives the file's hash.
  The file itself is never downloaded, since a cloud image is gigabytes.

`verify_signed` (`lib.sh`) accepts a signature only when `gpgv` says it is good and its `VALIDSIG` names a primary key that is one of the item's `verify.fingerprints`.
That check is the point: Apache's `KEYS` holds dozens of keys, so a good signature by any of them would prove nothing.
Each item commits its own minimal key file under `upstream/keys/`, exported from the publisher's page with the provenance in a manifest comment, and `validate` requires every pinned fingerprint to be a primary key in it.
A release manager that rotates, or an expired signing key (the Arch images' expires in 2027), fails closed with an issue until a person commits the new key, which is a PR and a reviewed record.
The kernel's `signed-sums` check keeps its own extra rule, that the fingerprint is the one `update-kernel-pin.sh` pins, in `source-kernel-org.sh`.

A signature is always checked, so `UPSTREAM_REHASH=0` has no effect here, and `plan` and `check` download each pinned `artifact` file every run: about 90 MB for the artifact items.
That is the price of never trusting an unsigned sum.

The Maven wrapper's `distributionSha256Sum` is new: the wrapper downloaded Central's zip with no check.
The `wrapper` file of `maven-3` is Apache's signed zip, byte-identical to Central's, so the wrapper's sum is the signed one.
The wrapper downloads from Central, which syncs some hours after Apache, so the item's `note` tells a reviewer in the PR to merge only once Central lists the version.

The Arch and Ubuntu pins moved out of `test-integration.yml` into `scripts/distro-images.env`.
The upstream app has no `workflows` permission, so a PR changing a workflow file could never be pushed by it, and granting one would let a stolen key rewrite any workflow.
The job reads the file in a step, and `HostDistroWorkflowTest` checks the pairing there.

`incus` and `alpine-minirootfs` pin different lines of `appliance/build.sh`, an input of the kernel's Mac boot, so their PRs carry the `kernel-mac-verified` gate and wait for a Mac boot like a kernel bump (isx-workflow's `bin/mac-pick.sh` picks a PR by that failing check, not by the item).

Not every PR that touches `build.sh` or `distro-images.env` re-proves its pins.
`upstream-verify` now counts an item as touched by a PR from a person only when a field it declares differs from the merge base, as it already did for an app PR.
Without that, every unrelated edit of `appliance/build.sh` would ask the Incus, Alpine, Arch and Ubuntu publishers again, and a mirror that deleted an old Arch image would fail PRs that never touched the pin.
A tree whose fields cannot be read counts as changed, so it fails closed.

What this gives up: Maven 4, a different Apache line, is not watched, so a release of it opens no issue (the listing is `maven-3/`).
Maven 3.10.0 is in the listing, so the first run opens an issue for the new series.
The 3.9 line is bumped by the watch, and a person decides on 3.10.

## The review class

Sanne's words on #2210, after asking what the class would mean:

> Good idea, go with (a) and add such a review class to cover more components.

**Chosen:** a third class, `review`, between the two.
The engine writes a review item's bump as a PR exactly as it does a mechanical one's, `upstream-verify` proves it by the same regeneration, and CI runs on it.
The bot never approves or merges it: Sanne merges it, or closes it, and a version he closed is not proposed again.
The PR's text says so, so nobody waits for the bot.

**Alternatives:** make such an item `judgement`, which costs a box and a review for a one-line change and delivers it later.
Make it `mechanical`, which lets code that ships in the binary change with no person having looked.

**Why:** some bumps a script can write and a publisher's sum cannot vouch for.
A library that ships inside isx (aesh, jjq) or a plugin that runs in an instance is one line to bump, and its checksum proves only that the file is the publisher's, not that it behaves.
The regeneration check still earns its place there: it proves the diff is that one line and nothing else, so what Sanne reads is the version number and the CI result.

What this gives up: a review PR waits for a person, so such a pin can lag as long as nobody merges it.

The class is held in three places.
`item_proposed` (`lib.sh`) is the one test for "the engine writes this item's PR", used by `validate`, `plan` and the check, so a review item needs everything a mechanical one does: `within`, a verifier its source provides, and a `cooling` at or above the floor.
`upstream-verify` passes the app's PR for a review item only as the regeneration, like a mechanical one's.
The merge script requires `class: mechanical` by name, so it refuses a review item without knowing the class exists; a class added later is refused the same way.

## Threat model

| Threat | How it is closed |
|---|---|
| Compromised upstream or release feed | Where the publisher signs (kernel.org, Apache, Incus, Alpine, Arch, Ubuntu), the signature against a committed key is required and the feed only tells the engine *which* version to verify. Where it does not (JetBrains, the images releases), the URL allowlist and `within` bound what a feed can point at, `cooling` leaves time for an upstream yank, and the pin remains a public PR and a reviewed record. A publisher whose own signing key is compromised is beyond any watcher. |
| Poisoned checksum source | Signed sums, or a signature of the file itself: closed by the signature, from a key whose primary fingerprint the manifest pins. Same-origin sums: `upstream-verify` re-downloads artifact and sums from the publisher, never from the PR, so a sum not derived from the publisher fails; and a pinned version whose values the publisher no longer confirms becomes an issue, not a re-pin. |
| Credential exfiltration | The app key is an environment secret only `main`'s `upstream-watch.yml` can reference; it is read in one step of a job that does not download anything, on another runner than the one that fetched publisher data, and the minted token is per run, per repository, one hour. The app has no ruleset bypass, so the worst a thief can do is open branches and PRs, and the bot merges only a PR that byte-matches the engine's regeneration. Sanne's own account's token (`BOT_TOKEN`) leaves Actions. |
| A malicious PR or issue reaching the job | The watch has no PR, issue or comment trigger, reads no PR, issue or comment, and takes input only from the manifest on `main` and the publisher endpoints it names; `workflow_dispatch` input is checked against the manifest's keys; no `${{ }}` reaches a `run:` line. `upstream-verify` runs `main`'s engine, has no secret and never executes PR content. |
| The bot approving outside the class | `bin/upstream-merge.sh` re-checks every gate from GitHub alone and refuses on the first failure; `upstream-verify` has already refused a PR that touches a second item, a file outside the item's, a judgement item or anything the regeneration did not write. The script itself refuses every class but `mechanical`, a `review` item's included. |
| The engine or manifest changed by a PR | They are ordinary files: a PR touching them is never the app's regeneration of an item, so the gate refuses it, and the check runs `main`'s copy, so a PR cannot verify itself. |
| A stolen app key reaching the Mac | `appliance/test-kernel-on-mac.sh` trusts the app login, since a kernel bump needs a Mac boot; but for an app PR the script itself refuses unless the `upstream-verify` check run at the very head it boots, by GitHub Actions, concluded `success` (and isx-workflow's `bin/mac-pick.sh` lists it only then), so only a kernel whose build is `main`'s engine's regeneration is ever booted with the verifier's credentials. |
| Replay or downgrade | Forward-only comparison in both the engine and the check, `within: series`, the declined marker; a sums file that lost entries never moves a pin back. |

## Why not Sanne's own token

Sanne asked for "my own private token"; the design recommends the GitHub App he owns instead, and the implementation uses it.
A fine-grained PAT acts as Sanne, and Sanne merges bump PRs today by ruleset bypass (#2028, #1986 and #1928 carry no approval, and #2028 merged with `kernel-mac-verified` failing).
A leaked PAT could push to `main` or merge any PR itself, and no gate of ours would see it.
The app is still his private credential: he created it and holds the key.
Its identity has write without bypass, its installation tokens live an hour and are minted per run for one repository and the permissions that run needs, and its PRs trigger CI as a PAT's would.
The status quo, `BOT_TOKEN` PRs that Sanne approves, was the manual step he wanted gone, and that token was his account's as a repository secret, readable by every workflow on `main` and by same-repository PR branches, which `update-base-image.yml` also persisted in `.git/config` with no repo or ref guard.

## Why not approving from Actions

A `workflow_run` job holding the bot's token could approve and merge on green.
That puts the approving credential beside untrusted PR context, and it removes the second party.
With the chosen split neither place holds both credentials.

## Why not GitHub auto-merge

`gh pr merge --auto` fires on GitHub's required-checks list alone.
The repositories had none when this was designed, and even with `upstream-verify` required, auto-merge would not check the item's `gates`, the author, the branch or a foreign commit.
The script merges with `--match-head-commit` and its own gate list, and the required checks make GitHub enforce what the script also checks.

## Why not Dependabot

Dependabot would cover Maven and Actions pins, and nothing else: not the kernel, not the images, not a tool tarball.
It is a second identity with a second configuration and no notion of classes, and its PR bodies paste upstream release notes, untrusted text, into the PR.
Maven items go into the same manifest in a later phase, with a body the engine fills from validated values and links to release notes rather than copying them.

## Why not one engine copy per repo

A copy per repository drifts.
The sibling repositories (incus-spawn-images, incus-spawn-templates) will pin the engine's commit as an item of their own manifest and check it out from incus-spawn at that pin for their verify job.
The same central watch mints a one-hour token per target repository, so no sibling keeps a long-lived secret or a bump job of its own.

## What is never done

No manifest key skips a check: an unknown key fails `validate`, and there is no field that disables a verifier, the regeneration or a gate.
`class` defaults to `judgement`, so an item that forgets to say what it is gets an issue, not a PR.
The bot never approves or merges a `review` item's PR.
`cooling` has a floor of 24 hours for a third-party source and no off switch; only a first-party source (`Sanne/*`) may set 0.
The watch never moves a `within: series` item to another series, never re-pins a version the publisher stopped confirming, and never proposes a version whose PR was closed unmerged.
Nothing else becomes auto-mergeable: not a bot PR, not a lead's, not an upstream PR anyone else pushed to.
