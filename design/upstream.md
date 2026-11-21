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

**The engine** (`upstream/engine/upstream.sh`, with `lib.sh`, `pr.sh`, `app-token.sh` and one `source-<type>.sh` per source type) is a deterministic script that runs from `main`'s checkout only, or for a sibling repository from the incus-spawn commit that sibling pins ("Sibling repositories" below).
`current`, `releases`, `check` and `apply` read and rewrite one item; `plan` decides every item (a prepared commit, an issue, or nothing) with no app credential; `issues` opens what `plan` wrote; `publish` pushes the prepared commits and opens or refreshes their PRs as the app; `verify-pr` and `verify-trees` are the check; `track` keeps the one `[upstream] watch failing` issue.
Everything an upstream sends (a version, a tag, a URL, a sum) is data: it is matched against a shape before use and never reaches a shell line unquoted or a jq filter unbound.
The three source types reuse what already verified a pin by hand: `kernel-org` is `appliance/kernel/update-kernel-pin.sh` (which gained `releases [<major>]` and `sum <version>`), `github-release` is `.github/scripts/base-image-sums.sh` and `verify-base-image-sums.sh`, and `jetbrains` reads the products API with a URL prefix allowlist and the `.sha256` beside each tarball.
`github-asset`, `signed-file`, `github-action`, `maven-central` and `git-main-paths` (#2210) are described under their own headings below, and `model-deprecations` (#2227) under "The model-deprecations source type".

**The watch** (`.github/workflows/upstream-watch.yml`) runs every six hours and on `workflow_dispatch` (an optional `item`, checked against the keys of the manifest of `repo`, by default this repository's), in `Sanne/incus-spawn` from `main` only, as two jobs, each once per watched repository.
It has no PR, issue or comment trigger, `persist-credentials: false`, `contents: read` and `issues: write`, no third-party action, and no `${{ }}` inside any `run:`.
The `plan` job runs in no environment: with the run's own read-only token it does every download and signature check, prepares each bump as a commit, opens the issues, and hands the plan on as an artifact (the decisions, the issue texts and each prepared commit as a git bundle under `refs/upstream-prepared/<item>`).
The `publish` job is the only one in the `upstream-watch` environment, whose deployment branch policy admits `main` alone, so the only one that can read the app's key; it runs on another runner, does not fetch anything from an upstream, and its one credentialed step mints a one-hour installation token of the `isx-upstream[bot]` GitHub App from the environment's `UPSTREAM_APP_ID` and `UPSTREAM_APP_PRIVATE_KEY` (`app-token.sh`: an RS256 JWT signed by `openssl` on a file descriptor, then an installation token limited to the leg's repository and to `contents` and `pull_requests` write), masks it and hands it on through `$GITHUB_ENV`.
That step holds the two secrets alone and runs `main`'s `app-token.sh` and nothing else; the next step runs `publish` with the token alone.
They are two steps because a sibling's engine is code another repository's pin chose, and an `unset` key stays readable in the parent shell's `/proc/<pid>/environ`, so the engine must never run in a process tree that held the key (#2210).
Two jobs rather than two steps because steps of one job share a runner and a user, so a process the planning step left behind could read a later step's environment; ordering steps is not isolation.
The app's commits carry its login and noreply address, resolved from the public user id, since the ruleset asks an extra approval for unattributed changes.
Issues are opened with the run's own token: one per judgement item and version, one when a `within: series` item's publisher lists a newer series, one when a pinned version's values no longer match what the publisher gives for it (a republished or tampered release, never a silent re-pin; a publisher that cannot be read while the pin is checked fails the run instead, #2261), the kernel's `[upstream] kernel: series <s> is EOL`, and `[upstream] watch failing` from a final `track` job that judges both jobs' results once, after both, so a plan that passed never closes the issue a failed publish is about to open; the next run where both pass closes it.
The kernel source reads the next major's signed sums file as well once kernel.org publishes it (a missing file is no release yet; one that does not verify fails the run), and an EOL issue of a series no longer pinned or no longer ended is closed by the next run, the retired job's `[kernel] <s> is EOL` title included.
The `github-asset`, `signed-file`, `maven-central`, `jetbrains`, `antigravity` and `model-deprecations` source types download through `fetch` in `lib.sh`, and so does the kernel source's `releases.json` (#2461).
`fetch` tries a failed download six more times, waiting a second doubling each time, or the `Retry-After` the server names.
Its waits stay within two minutes in all, and the time a transfer takes does not count, so a large download that drops after minutes is tried again.
Three retries waited seven seconds in all, and Maven Central's rate limit answered all four attempts at a listing with HTTP 429.
A `Retry-After` past what is left of the two minutes fails the download at once, since curl's own retry would wait out an hour or more.
A download that still fails fails the run.
A 4xx answer fails at once, unless it is a 403, 408 or 429 or names a `Retry-After`, since asking again cannot change it.
Maven Central has refused runners with a 403 that cleared, and throttled with a 404 that names a `Retry-After`.
The kernel's sums files and its next-major probe go through `update-kernel-pin.sh`'s own `fetch`, which keeps curl's three retries.
`github-release` and `github-action` read through `gh api`, and `git-main-paths` through git, so `fetch` does not retry them.
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
| `antigravity` | `common/src/main/java/dev/incusspawn/tool/AntigravitySetup.java` (`VERSION`, `BUILD`, both sha256) | Google's stable Linux manifests (the installer's), and the `manifest.json` beside each build in the bucket, a sha512 per tarball | `publisher-sums` | review | `series` (the major) | 24h | a new major (`go:build`) |
| `maven-3` | `common/src/main/resources/tools/maven-3.yaml` (description, URL, sum, link, `MAVEN_HOME`) and `.mvn/wrapper/maven-wrapper.properties` (the zip's URL and `distributionSha256Sum`) | Apache's `maven-3/` listing, the `.asc` of each file, signed by the release manager | `signed-file`, `upstream/keys/apache-maven.asc` | mechanical | `series` (3.9) | 24h | a new series (`go:build`) |
| `mvnd` | `common/src/main/resources/tools/mvnd.yaml` (both URLs, sums and links) | Apache's `mvnd/` listing, the `.asc` of each zip | `signed-file`, `upstream/keys/apache-mvnd.asc` | mechanical | `series` (1.x) | 24h | a new major (`go:build`) |
| `incus` | `appliance/build.sh` (`INCUS_VERSION`, `INCUS_SHA512`) | linuxcontainers.org's `downloads/incus/` listing of LTS releases (`X.0.Y`), the `.asc` of the tarball | `signed-file`, `upstream/keys/incus-stgraber.asc` | mechanical | `series` (7.0) | 24h | a new LTS (`go:design`); gate `kernel-mac-verified` |
| `alpine-minirootfs` | `appliance/build.sh` (`ALPINE_VERSION`, both `ALPINE_SHA256`) | Alpine's `v3.24/releases/<arch>/` listing, the `.asc` of each tarball | `signed-file`, `upstream/keys/alpine-ncopa.asc` | mechanical | `series` (3.24) | 24h | a new minor, seen in the latest-stable feed (`go:design`); gate `kernel-mac-verified` |
| `arch-cloud-image` | `scripts/distro-images.env` (`ARCH_IMAGE_URL`, `ARCH_IMAGE_SHA256`) | the Arch mirror's `images/` listing, the signed `.SHA256` of the image | `signed-file`, `upstream/keys/arch-linux.asc` | mechanical | `any` | 24h | none |
| `ubuntu-cloud-image` | `scripts/distro-images.env` (`UBUNTU_IMAGE_URL`, `UBUNTU_IMAGE_SHA256`) | Canonical's `releases/noble/` listing, the `SHA256SUMS` signed by `SHA256SUMS.gpg` | `signed-file`, `upstream/keys/ubuntu-cloud-images.asc` | mechanical | `any` | 24h | none |
| `models` | `common/src/main/resources/models.yaml` (every model ID isx ships) | each vendor's keyless markdown deprecations page (Anthropic, OpenAI, Google) | none: a findings source pins nothing | judgement | none | none | a deprecated or retired ID, or a successor (`go:build`) |
| `maven-plugins` | the four poms (`compiler-plugin.version`, `surefire-plugin.version`, which failsafe shares, and the exec, jandex and assertj versions) | Maven Central's listing of each artifact | `central-exists` | review | `series` (each member's major) | 72h | a member's new major (`go:build`) |
| `aesh` | `pom.xml` (`aesh.version`, for aesh and its processor) | Maven Central's listings | `central-exists` | review | `series` (the major) | 72h | a new major (`go:build`) |
| `jjq` | `pom.xml` (`jjq.version`, for jjq-yaml, jjq-mapper and its processor) | Maven Central's listings | `central-exists` | review | `series` (the major) | 72h | a new major (`go:build`) |
| `json-schema-validator` | `cli/pom.xml` (the test dependency's version) | Maven Central's listing | `central-exists` | review | `series` (the major) | 72h | a new major (`go:build`) |
| `github-actions` | every file under `.github/` with a `uses:` of a repository's action (each `owner/repo@<sha> # <tag>`) | each member's GitHub releases and tag refs, an annotated tag followed to its commit | `tag-commit` | judgement | none (a new major is listed apart) | 72h | every member with a newer cooled release, in one issue (`go:build`) |

The `base-image` item takes over what `update-base-image.yml` did (#1678, #1862): every checksum comes from the release's own sums files for the asset each URL downloads, a release of another Fedora line is an issue rather than a PR, and `vm_image_url` moves to `-vm-direct` in the first release that has it for both arches and never back to the stock image (design/templates.md "Which base image an instance runs").
Its series is a tag's whole line, its channel included (`src_series`, #1994), and it lists the stable channel's releases alone, so a channel is never proposed and never opens an issue.
Its versions are ordered as isx orders release tags (`src_order`): by line, then by date and that day's number, with `fedora-45-v1`, the one typed tag, as the stable line built on 2026-11-04.
`sort -V` ranked `fedora-45-v1` above every dated Fedora 45 tag, so a built-in on it never moved.
A source type may define `src_order` (versions on stdin, oldest first) wherever `sort -V` is wrong for it; `newest` uses it, and `publish` loads each item's source type so `pr.sh` compares versions the same way.
The images repo's `repository_dispatch` to this one, and its `INCUS_SPAWN_DISPATCH_TOKEN`, retire in favour of the six-hourly poll: the payload was only ever cross-checked against the release.
`publisher-sums` proves consistency and transfer, not provenance, since the sums come from the same origin as the artifact; the manifest says so in the verifier's name rather than pretending otherwise.
The siblings' items, `ISX_REF` and the engine pin of `incus-spawn-images`, are in that repository's own manifest ("Sibling repositories" below).
Items the design inventories for later phases (Gradle in `incus-spawn-templates`) and the judgement-only ones (Quarkus, GraalVM, herdr, zmx) are listed on the issue; each lands as a manifest item with a source type and its fixture tests, never as a special case.

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

## The antigravity source type

Antigravity CLI (`agy`) is pinned in `AntigravitySetup.java`, not a tool YAML, and moves through an `antigravity` item (#2366).
Google publishes no signature and no sha256, and lists no releases with dates.
What it does publish, read by its installer and by `agy`'s self-updater, is one manifest per platform on its updater service (`manifests/linux_amd64.json`, `linux_arm64.json`).
Each names the one stable version, the tarball's URL in Google's `antigravity-public` bucket, and its sha512.
The same JSON also sits beside each build's tarball as `manifest.json`, and stays there when the stable version moves on.
That it stays was observed, not promised: on 2026-11-20 both Linux `manifest.json` files were there for each of the last 25 builds, back to 1.4.2, and the bucket refuses anonymous listing.
If Google removed the pinned build's `manifest.json`, `check` would return 2, as for any publisher that cannot be read (#2261).
The plan step then fails before its issue and hand-off steps, so that run opens no PR and no issue for any item of this repository, and `track` opens `[upstream] watch failing`.
The watch does not recover by itself: every run fails the same way until a person moves the pin, with `upstream.sh apply antigravity <stable version>`, or the file returns.

**Chosen:** the candidate is the stable version alone, and only while both Linux manifests name the same build under the bucket.
While they name two builds, as when a rollout reached one platform first, the item proposes nothing and the rest of the watch goes on.
Each build's `manifest.json` must name that version and URL and give the stable manifest's sha512.
The engine downloads both tarballs, checks them against the sha512 and pins their sha256, which is what `DownloadCache` checks.
A version the stable manifests no longer name is read only at the build the tree itself pins, from its `manifest.json`, so the six-hourly check of the pin keeps passing after Google moves on.
That check runs with `UPSTREAM_REHASH=0`, so it does not download the tarballs and does not notice one replaced under its name; only a check with a re-hash, as `upstream-verify` runs for a person's PR, fails on it.
When both stable manifests name a version older than the pin, Google rolled a release back, and the check opens a pin-mismatch issue (`src_confirms`, which reads each manifest on its own, so a split rollout hides no rollback), since the pulled build's `manifest.json` still reads as valid.
The release date is the bucket's `timeCreated` of the later tarball.

**Alternatives:** the updater also answers `/releases`, every version with its build number, undocumented and without sums or dates.
Taking candidates from it could propose a build Google never made stable, or one it pulled.
Pinning the sha512 beside the sha256 would let the check skip the download, but `DownloadCache` takes a sha256, so the second sum would be read by nothing in isx.

**Why:** the stable manifests are what every `agy` install trusts, so the pin follows exactly what Google gives its own users.
The sum comes from Google over HTTPS, as JetBrains' does, so it proves consistency and transfer, not provenance.
The item is `class: review` anyway: isx's delegation parses what `agy` prints and the conversations it writes (`antigravity-runner.cjs`, `AgentSessions`), and no CI job runs `agy` against a model.

What this gives up: Google releases about once a day, and the 24h floor counts from the tarball's upload, not from the moment the manifests named it stable.
A build uploaded a day or more before Google made it stable is proposed at once, with no time left for a yank.
One made stable at its upload is proposed only once it has stood a day, which about half of November 2026's releases did.
An app PR passes `upstream-verify` only while its version is still the stable one; when Google moves on, the next run proposes the new version.
Every new sum costs a download of both tarballs, about 200 MB.
With `UPSTREAM_REHASH=0`, as in `plan`'s check of the pin, the pin keeps its own sum and nothing is downloaded.
That spares the pin alone: while the item's PR is open, each six-hourly run prepares its commit again and downloads both tarballs of the new version, since their sha256 is written nowhere the engine reads.

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

## The github-action source type

Every GitHub Action the workflows use is pinned to a commit, with the release tag it was read from beside it: `actions/checkout@<40 hex> # v6.1.0` (#2210).
A tag can be moved to other code, and a commit cannot.
That matters most where an action holds a credential or builds what users install: `wrangler-action` gets the Cloudflare token, and `setup-graalvm` runs in the release build.
The first pins are the commits the floating major tags (`v6`, `v5`) pointed at on 2026-11-15, each named by the exact release tag at that commit, so CI runs the same code as before.

The Actions are one item, a family: `source.members` maps a short key to each action's repository, and the item moves all of them as one version.
The version names each member's tag in the members' order, as `<key>.<tag>` joined by `_`: `checkout.v6.1.0_cache.v5.1.0_...`.
So one issue covers every member, and `src_order` compares two versions member by member.
`src_current` reads every external `uses:` line of the item's files, a local `./` action and a `docker://` image apart.
It fails on a tag in place of a commit, on a missing or malformed tag comment, on an action that is not a member, on one member at two commits or tags, and on a member no file uses.
Since `src_fields` reads the same lines, a PR that adds any of those fails `upstream-verify`, behind `WorkflowActionsPinnedTest`.

The verifier is `tag-commit`: each commit must be the one its tag points at on GitHub when it is read, an annotated tag followed to its commit.
It proves where the tag pointed, not what the action's code does.
A tag the publisher moved fails `check`, so `plan` opens the pin-mismatch issue rather than following it.
A release counts once 72 hours have passed since GitHub published it, and a draft, a prerelease and a tag that is not `v<numbers>` never count.
The proposed version is each member's newest such release, never older than its pin.

The family is `class: judgement`, and `validate` refuses any other class for this source type.
Sanne's answer on #2210, once the conflict below was put on the issue:

> go with option 2

The design had made the family a `mechanical` item, which the app would push and the bot merge.
The upstream app has no `workflows` permission, so it could never push a change to a workflow file, and granting one would let a stolen key rewrite any workflow, `upstream-verify.yml` included.
So `plan` opens one issue, `[upstream] github-actions: newer releases available`, which lists each member that moves and marks a new major.
It gives the `upstream.sh apply github-actions <version>` command for the newest release within each pinned major, and for the newest outright.
A box writes the PR with that command, `upstream-verify` re-reads every tag on it, and Sanne merges it.
The title names no version, so a member's next release does not open a second issue beside the open one (`src_issue_title`).

What this gives up: an Action bump waits for a box and for Sanne, so a pin lags as long as the issue stays open.
The issue's table is the one the run that opened it saw; `upstream.sh releases github-actions` gives the current target.
The six-hourly run plans it like every item until the weekly run of build-side families exists.
incus-spawn-images pins its own Actions the same way, by hand, until its watch (#2210, the siblings) reads them.

## The maven-central source type

A version the poms pin, of an artifact on Maven Central, is watched by a `maven-central` item (#2210): the build plugins and assertj, aesh, jjq and json-schema-validator.
An item has `source.members`, and each member is a set of artifacts released together under one version.
`artifacts` names every one the version drives, so aesh's version moves only to a release of both aesh and `aesh-processor`.
`pins` are literal texts with `{version}`, as for `signed-file` (`pins.pl`): a property such as `<aesh.version>{version}</aesh.version>`, or an `artifactId` line with the `version` line below it.
Every occurrence is read and must agree, and every one is rewritten, so assertj moves in all three poms at once.
The poms are read with `yq` but never written with it, since it would not write the file back byte for byte.

One member's version is the version (`3.19.1`).
A family's names each member's version in the members' order, as `<key>.<version>` joined by `_`: `compiler.3.16.0_surefire.3.6.1_...`, compared member by member.
Only a dotted number is a release, so `3.6.0-M1`, `4.0.0-beta-5` and `4.0.0.CR1` never count.
Central's listing of an artifact dates each version by its directory, and a member's version is dated by the latest of its artifacts.
A version that one of a member's artifacts lacks is no release of it: failsafe without the surefire of its number holds the member back.

A family proposes each member's newest release past cooling within its major, never older than its pin.
A member's new major opens the item's new-series issue beside that PR, so it never holds the other members back.
`src_releases` gives two versions for that: every member in its major, and every member at its newest.
A bump never moves a member back, whatever version it is given.

`validate` also requires each property a pin defines to version only the member's artifacts, in every file of the item, and to appear in no other pom of the repository.
A dependency added on `${jjq.version}` is then either declared, and checked on Central with the others, or fails `UpstreamManifestTest`.

The verifier is `central-exists`: Central's listing names every artifact at its version, and the pom there hashes to the `.sha1` Central lists beside it.
That is Central's record of a release, the check Maven itself makes when it downloads, and not the publisher's signature.
It says nothing of what the code does, so `validate` refuses it on a mechanical item, and all four items are `class: review`.

The design on #2210 had the plugin family mechanical, on the ground that a build plugin ships in no binary.
It does reach one: the compiler plugin compiles what ships, and the jandex plugin writes the index without which the native image binds every Claude account `type:` to null (CLAUDE.md, "Module Structure").
So the family is a review item until Sanne decides otherwise, and promoting it takes a verifier that proves the publisher.
Every artifact on Central carries the publisher's OpenPGP signature (`.asc`), so a mechanical family could check it against committed keys, as `signed-file` does.
That costs a key per publisher and fails closed when a release manager changes.

What this gives up: a Maven bump waits for Sanne, so a plugin's pin lags as long as its PR stays open.
Quarkus and Tamboui are not items: a new Quarkus moves the proxy's whole network stack and Tamboui's 0.x releases change its events, so each needs a person's judgement (the issue-only feeds of #2210).
Plugins without a version of their own (`maven-jar-plugin`, `maven-resources-plugin`) take Quarkus's or Maven's, and are not watched.

## The model-deprecations source type

isx names AI models by ID, and a vendor retires or supersedes one without isx noticing (#2227).
Every ID isx sends or suggests is declared in `common/src/main/resources/models.yaml`, one `key: id` per line, read through `ModelIds`.
`ModelIdsGateTest` fails a model ID literal in any other source file, so a new one is declared the day it is added and the watch sees it.
The `models` item reads each vendor's deprecations page, which needs no key (Anthropic's and Google's pages and OpenAI's `.md` are markdown).
This is the first findings source: it defines `src_findings` and none of the pin functions, so it pins nothing, rewrites nothing and has no verifier, cooling or PR.
The item is `class: judgement`, because a model is a choice (cost, quality, a Vertex region), and `validate` refuses any other class.
`plan` opens one `go:build` issue per finding, titled `[upstream] models: <id> is deprecated`, `is retired` or `has a successor <id>`.
A successor is the newest active ID of the same Claude family on Anthropic's page, or the replacement Google names for an ID it is not shutting down.
For OpenAI, a deprecated ID is the finding and its replacement is in the body.
An alias matches the vendor's dated snapshot (`gpt-4o-mini` and `gpt-4o-mini-2024-07-18`).
An issue a person closed is not opened again, and a title is unique per finding, so the watch files each once.
A retirement date inside a window is not flagged apart: the title is the signal (Sanne, #2227).
A page is third-party text.
Only tokens of an ID's shape and a date's shape are taken from it into an issue, and the page's other text never is.
A page that yields no row, a state the parser does not know on Anthropic's page, and a page that cannot be fetched each fail this item alone: the run goes on, so no other item's bump is held back, and `plan` opens one `[upstream] models: the watch cannot read its source` issue, so a changed page is never read as "all clear".
A declared ID no vendor's `id-prefix` covers fails `validate`, so a new vendor is added to the item before an ID of it is declared.
What this gives up: Google's and OpenAI's pages are read for deprecations and replacements only, so a newer OpenAI or Gemini model that replaces nothing is not reported, and a model an account cannot reach is still only found by `ModelCheck` when it is used (#1012).
A person's PR that edits `models.yaml` is an ordinary change; `upstream-verify` refuses an app PR that touches it.

## The git-main-paths source type

`source-git-main-paths.sh` (#2210) pins a commit of a first-party repository's `main`, the way `incus-spawn-images` pins the incus-spawn commit its VM kernel is built from (`ISX_REF`).
It moves the pin to the newest commit on `main` that changed one of the item's `source.paths`, the files the pinning repository reads from it.
A commit that changed none of them is never proposed, so merging docs into incus-spawn opens no PR in a sibling, and a pin already past the newest such commit stays.
The engine reads `main` from a blob-less bare clone over HTTPS, a few megabytes, once per run; "newer" is the later place on `main` in topological order, ancestors before descendants whatever the commit dates say, never `sort -V`.
The pin is a literal text with `{version}` where the 40-hex commit is (`source.pin`), found exactly once, and the version is never shortened.

Its verifier is `main-commit`: the commit is on the repository's `main` as fetched when the check runs.
That proves the owner merged it, not what it does.
`validate` accepts a `git-main-paths` item, `review` or `mechanical`, only when the repository's owner is the manifest's and GitHub says it is not a fork: a fork's `main` holds commits its upstream merged.
So the step that validates needs a token to ask GitHub, and `UpstreamWorkflowTest` requires the run's on every such step; without it the answer reads as "not first party" and the leg fails.
A move goes only to a commit that changed a watched path, so `upstream-verify` fails an app PR to any other commit on `main`, which `plan` never proposes; a person may still pin one.
The design made these items auto for that reason (rows 45 and 48 of #2210): what moves is a commit Sanne already merged, and the PR's own CI builds and boots the kernel it names.
A pin on a branch never merged is a pin-mismatch issue, and a person's PR pinning one fails `upstream-verify`.
`check` asks the type's `src_confirms` for that before it regenerates anything, so the pin reads as a mismatch.
A clone of `main` that fails is a failed read instead: `check` returns 2, and `plan` prints why and fails the run (#2261).
Cooling is 0, as for every first-party source, and counts from the committer date.

## Sibling repositories

**Chosen:** one watch, one engine and one app for every repository (#2210).
A sibling holds its own `upstream/manifest.yaml`, beside the files it pins, and `upstream/engine.ref`, the incus-spawn commit whose engine plans and verifies it.
`upstream-watch.yml` here runs its `plan` and `publish` jobs once per repository its `TARGETS` and matrices name, today `Sanne/incus-spawn` and `Sanne/incus-spawn-images`.
A sibling's leg checks out the sibling and incus-spawn at that pin, and runs that engine with `UPSTREAM_REPO_ROOT` naming the sibling's checkout.
`REPO_ROOT` is then the watched repository (its manifest, its keys, its files, the history plan and verify make worktrees of), and `ENGINE_ROOT` the engine's own.
`publish` mints a one-hour token limited to the leg's repository, with `main`'s `app-token.sh`, and refuses a manifest of another repository than the one the token names.
A leg on a repository the app is not installed on fails at that mint, saying so and naming the repository (#2330).
It fails rather than skips, so `[upstream] watch failing` stays open until the installation is done, and a sibling never goes unwatched without anyone seeing.
The sibling's own `upstream-verify.yml` checks out its default branch, reads the pin there, never the PR's, and runs that same engine on the PR.
So a regeneration is planned and checked by the same code, and a PR cannot choose its own verifier.

The pin is a plain file, not a workflow's `ref:`: the app has no `workflows` permission, so it could never move a pin written there.
The engine pin is a `git-main-paths` item of the sibling (`engine`, paths `upstream/engine`), so it follows incus-spawn's `main` by the same mechanical PR as any other pin.
A bump of it is verified by the engine it replaces, which only checks that the new commit is on `main`.
Both jobs also refuse the leg unless the pin is an ancestor of incus-spawn's `main` in their own full checkout, before checking the engine out: a checkout by commit also reaches a fork's commits, and the pin may have been set some way `upstream-verify` did not see.
`publish` runs the engine the sibling's default branch pins, never one the plan's artifact names, since the plan job handled publisher data.
The plan records its engine commit only for `publish` to compare, so a merge into the sibling between the two jobs fails the leg rather than hand the plan to another engine.
An engine without sibling support fails the leg rather than plan incus-spawn's manifest there.
`publish` compares a `git-main-paths` item's versions by their place on incus-spawn's `main`, so its publishing step reads that public history from GitHub, the host it pushes to; it fetches nothing from a third-party upstream.

A sibling's issues are opened in incus-spawn, where the run's token reaches and where judgement work is dispatched.
Their titles name the repository (`[upstream] incus-spawn-images/isx-ref: ...`), their bodies start with it, and a sibling's PR names the watch's issue as `Sanne/incus-spawn#2036`.
Each `publish` leg runs whatever another repository's `plan` did, so a sibling whose plan fails holds back no bump of incus-spawn's; its own leg fails at its download, before a token is minted.

| Repository | Item | File | Source | Verifier | Class | `cooling` |
|---|---|---|---|---|---|---|
| `incus-spawn-images` | `isx-ref` | `fedora/build-vm-kernel.sh` (`ISX_REF`) | incus-spawn's `main`, the commits that changed `appliance/kernel/build-kernel.sh`, `appliance/kernel/*.config` or `appliance/test-vm-kernel.sh` | `main-commit` | mechanical | 0 |
| `incus-spawn-images` | `engine` | `upstream/engine.ref` | incus-spawn's `main`, the commits that changed `upstream/engine` | `main-commit` | mechanical | 0 |

`isx-ref` replaces the daily job the draft incus-spawn-images#26 proposed, with the same rule: move only when what the image build reads changed.
Its PR runs the images repository's `build-vm-scratch.yml`, which builds that kernel, checks it against `KERNEL_SHA256`, and boots it.

**Alternatives:** a workflow and an engine copy in each sibling, which "Why not one engine copy per repo" below rejects.
Planning a sibling with `main`'s engine and verifying with its pinned one, which fails every regeneration while the two differ.
Opening a sibling's issues in the sibling, which needs `issues` write for the app on every repository.

**What this gives up:** an engine change reaches a sibling one merged engine-pin PR later.
An engine pin that names a commit without sibling support breaks that sibling's plan and verify, so the first pin is the merge of the PR that added it.
The coordinator's merge script takes the repository as an argument, but its listing of upstream PRs (isx-workflow's `bin/pr-state.sh`) looks at incus-spawn alone until it is taught the siblings.

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
| Compromised upstream or release feed | Where the publisher signs (kernel.org, Apache, Incus, Alpine, Arch, Ubuntu), the signature against a committed key is required and the feed only tells the engine *which* version to verify. Where it does not (JetBrains, Google's agy manifests, the images releases), the URL allowlist and `within` bound what a feed can point at, `cooling` leaves time for an upstream yank, and the pin remains a public PR and a reviewed record. A publisher whose own signing key is compromised is beyond any watcher. |
| Poisoned checksum source | Signed sums, or a signature of the file itself: closed by the signature, from a key whose primary fingerprint the manifest pins. Same-origin sums: `upstream-verify` re-downloads artifact and sums from the publisher, never from the PR, so a sum not derived from the publisher fails; and a pinned version whose values the publisher no longer confirms becomes an issue, not a re-pin. |
| Credential exfiltration | The app key is an environment secret only `main`'s `upstream-watch.yml` can reference; it is read in one step of a job that does not download anything, on another runner than the one that fetched publisher data, and the minted token is per run, per repository, one hour. The app has no ruleset bypass, so the worst a thief can do is open branches and PRs, and the bot merges only a PR that byte-matches the engine's regeneration. Sanne's own account's token (`BOT_TOKEN`) leaves Actions. |
| A malicious PR or issue reaching the job | The watch has no PR, issue or comment trigger, reads no PR, issue or comment, and takes input only from the manifest on `main` and the publisher endpoints it names; `workflow_dispatch` input is checked against the manifest's keys; no `${{ }}` reaches a `run:` line. `upstream-verify` runs `main`'s engine, has no secret and never executes PR content. |
| The bot approving outside the class | `bin/upstream-merge.sh` re-checks every gate from GitHub alone and refuses on the first failure; `upstream-verify` has already refused a PR that touches a second item, a file outside the item's, a judgement item or anything the regeneration did not write. The script itself refuses every class but `mechanical`, a `review` item's included. |
| The engine or manifest changed by a PR | They are ordinary files: a PR touching them is never the app's regeneration of an item, so the gate refuses it, and the check runs `main`'s copy, so a PR cannot verify itself. |
| A stolen app key reaching the Mac | `appliance/test-kernel-on-mac.sh` trusts the app login, since a kernel bump needs a Mac boot; but for an app PR the script itself refuses unless the `upstream-verify` check run at the very head it boots, by GitHub Actions, concluded `success` (and isx-workflow's `bin/mac-pick.sh` lists it only then), so only a kernel whose build is `main`'s engine's regeneration is ever booted with the verifier's credentials. |
| A moved Action tag | Every Action is pinned to a commit, so moving a tag changes nothing a workflow runs. The watch re-reads each pinned tag on every run and opens the pin-mismatch issue when one points elsewhere; `upstream-verify` re-reads each tag a PR names. A tag the publisher moved before the pin was taken is beyond this check, which proves where a tag points, not what the code does. |
| A Maven Central release | Central keeps a release for good and never lets it be replaced, and `central-exists` checks the pom against Central's sha1 again on every person's PR. It proves Central published the version, not what it does, so a person merges every such bump and `validate` refuses the verifier on a mechanical item. |
| A sibling's PR choosing its verifier | The sibling's `upstream-verify` reads `upstream/engine.ref` from its default branch, never the PR's, and checks out that incus-spawn commit; an app PR that moves the pin is verified by the engine it replaces, and the new pin must be a commit on incus-spawn's `main`. The token a sibling's leg mints is limited to that repository, and `publish` refuses a manifest of another. |
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
The sibling repositories pin the engine's commit as an item of their own manifest and check it out from incus-spawn at that pin for their verify job ("Sibling repositories" above).
The same central watch mints a one-hour token per target repository, so no sibling keeps a long-lived secret or a bump job of its own.

## What is never done

No manifest key skips a check: an unknown key fails `validate`, and there is no field that disables a verifier, the regeneration or a gate.
`class` defaults to `judgement`, so an item that forgets to say what it is gets an issue, not a PR.
The bot never approves or merges a `review` item's PR.
`cooling` has a floor of 24 hours for a third-party source and no off switch; only a first-party source (`Sanne/*`) may set 0.
The watch never moves a `within: series` item to another series, never re-pins a version the publisher stopped confirming, and never proposes a version whose PR was closed unmerged.
Nothing else becomes auto-mergeable: not a bot PR, not a lead's, not an upstream PR anyone else pushed to.
