# Templates, tools and builds

How templates stack, how tools install into them, and what a build does.

## Template Image Hierarchy

Images are defined in YAML and layered via copy-on-write.
Built-in definitions live in `src/main/resources/images/*.yaml`; user-defined images in `~/.config/incus-spawn/images/` can extend or override them:

```
tpl-minimal   (Base OS only — no tools)
  └── tpl-dev   (Podman, GitHub CLI, Starship, tmux)
        └── tpl-java  (JDK packages + Maven tool)
```

`tpl-dev` and `tpl-java` are deprecated and will be removed; their maintained copies, under the same names, live in `incus-spawn-templates`.
`isx project create`, whose default parent is still `tpl-java`, warns the same way.
A definition sets `deprecated: true` or `deprecated: <what to use instead>` (#1195): it still builds, so nobody's build breaks, but `BuildCommand` warns for each template it builds that is deprecated or names a deprecated `parent`, in one line naming both when both are, once per template per run (not for deprecated templates further up the chain, #1353: the template naming the deprecated one is where it gets replaced, and a warning per ancestor per descendant buried which one that was), and the TUI keeps that warning in its warning log (the build prints it on the released terminal, which the TUI redraws over, so the TUI copies every warning of a build it runs there with `Output.copying`) and shows it in the template's details before anyone builds.
`deprecated` is left out of the content fingerprint, so marking a template deprecated, or switching to a copy that is not, rebuilds nothing.
Removing a template outright was the alternative: every template derived from it would stop building on upgrade, with no warning beforehand.

**The `tpl-` prefix is a convention, never a rule.**
A template is named exactly as its `name:` says, and `isx templates new` and `edit` and the TUI use the name as given, never adding `tpl-` (`TemplatesCommand`).
A new template's file is named after it (`ImageDef.filenameForName`); a built-in's user override keeps the built-in's filename, `java.yaml` for `tpl-java` (`ImageDef.builtinFilenameFor`).
So a new name whose file is a built-in's, such as `dev`, is refused, and `edit` opens an existing override only while its `name:` is the built-in's (`TemplatesCommand.newTemplateNameProblem`).
A new name must also be short enough to rebuild under its temporary name (`BuildCommand.MAX_TEMPLATE_NAME_LENGTH`).
Code that must tell templates from instances reads the `base` type every build stamps (`isx list`, `isx doctor`), never the name.
The TUI's F4 once looked for `tpl-isx-presentation` when asked to edit `isx-presentation`, because the edit command added the prefix.

**A definition can arrive whole, from something other than an editor** (#2076).
`isx templates new <name> --from <file|->` checks it and stores it with no editor and no prompt, and `isx templates validate <file|-|name>` is the check alone.
Feedback says people do not expect to write templates by hand, so a form, an agent or something not yet chosen will; each ends with a complete definition that isx must check, explain and store, and `--from` is that step's first caller.
The editor, `--from` and `validate` share one check, `TemplateValidator.validate(yaml, location, known)`, which checks text as if it were the file at `location`, since where a definition lives decides whether it is project-local and so confined.
Its findings are structured (`TemplateFinding`: severity, the field at fault, a message), so a form can mark the field and an agent correct its own mistake; the edit loop still reports only their messages, as before.
The check refuses a repo the loader would skip the whole file for (`ImageDef.repoProblem`, one rule for both), so `--from` never stores a template that then does not exist; the edit loop, which used to call such a file valid beside the load's warning, now reports it once, as an error.
The check also lists what the definition's own fields reach beyond its instance (`TemplateReach`: a `file://` base image, a child's too since a change of machine type builds it from scratch, host resources, `host-source` repos, `gui`, account pins, coordinator control; for a project-local definition `gui` and `coordinator` are told as what they do there, no branch default and ignored), which is what a person reviews once something else writes the templates.
`--from` refuses a `name:` that disagrees with the name given rather than choosing one, writes nothing for a definition with an error, and creates its file exclusively; `--replace` swaps in a whole new file, and only the template's own (`TemplatesCommand.placeProblem`), never a built-in or the other layer's.
The skeleton the editor starts from is written by the same `TemplatesCommand.store`.
An earlier design added `--tool` and `--repo` flags that wrote the YAML; it was dropped, since a flag per field is a second way to write a template, and every later field would either be refused arbitrarily or make the flags a shadow of the YAML schema.
Changing part of an existing template stays out: rewriting a hand-written file loses its comments and layout.

**`/tmp` is on disk** (#1561): `tpl-minimal` masks `tmp.mount`; what that means for users is in `docs/FEDORA.md`.
It is in the root template, not in one template (yrodiere/incus-spawn-templates#68 did it in `tpl-dev-base`), because the restarts that empty a tmpfs come from isx itself and hit every template.
It is not in the base image either, which would put an isx choice in a Fedora image other tools could use.
`mask_services` is in the content fingerprint, as is `remove_packages`, each adding nothing when empty so other templates keep theirs; masking `tmp.mount` marks `tpl-minimal` and everything below it out of sync.
What a step leaves in `/tmp` is now saved with the template, so every path that saves one empties `/tmp` and `/var/tmp` first, hidden files included (`GuestUpdate.EMPTY_TMP_SCRIPT`): the builds' `cleanCaches`, `isx update-all`, and `isx project create` and `update`.
A base image pinned before #1563 replaced the whole built-in definition, so it keeps the tmpfs until `isx update-base` pins it again or unpins it (below).

**An update waits for the template's network** (#1734).
A template boots on DHCP, and exec answers before its lease is in place, so a dnf run straight after cannot resolve its mirrors.
`isx update-all` and `isx project update` wait first through `GuestNetwork`, the same waits a build makes: an address, then a Fedora mirror that answers.
Both run in the guest, in one exec, so a network already up costs one exec more than before.
A container's wait looks for an address on `eth0` and starts its `systemd-networkd`; a VM's NIC keeps its kernel's name (#997), so its wait looks for a default route.
A template whose network never comes up fails its update with the build's diagnostics, and no step that needs the network runs.

No coding agent is included in built-in templates by default — users add the ones they need to custom image definitions.
Available Java tools: `gemini-cli` (Gemini CLI), `claude` (Claude Code), `codex` (Codex CLI), `pi` (Pi coding agent: Anthropic, OpenAI, Gemini API or Gemini on Vertex provider), `bob` (Bob Shell), `copilot` (GitHub Copilot CLI).

Each image definition specifies:
- `name` — container name (required)
- `description` — human-readable description for the TUI
- `image` — base OS image, only for root images
- `image_url` — download URL for the base image tarball (supports `{arch}` and `{tag}` placeholders)
- `image_tag` — release tag identifying the base image version
- `image_sha256` — per-architecture checksums for integrity verification
- `type` — instance type: `container` (default), `vm`, or `kvm`.
  Inherits through the parent chain via `inheritTypes()` at load time
- `vm_image_url` — download URL for the VM base image (qcow2 tarball, supports `{arch}` and `{tag}` placeholders)
- `vm_image_sha256` — per-architecture checksums for the VM base image
- `parent` — parent image name (omit for root images)
- `packages` — dnf packages to install
- `tools` — tool names to run (resolved from YAML or Java)
- `agent_note` — always-true fact an agent must know before acting, rendered into the generated agent context file (see below)
- `deprecated` — `true` or a note: still builds, but warns on every build of it or of a template naming it as `parent` (one line, even when that template is deprecated too)

A key isx does not read, anywhere in a template or tool definition, is ignored with a warning when the file loads (#1546), and `isx templates edit` reports it in its check of the edited file.
The warning names where the key is (`host-resource` at the top level, `repos[0].branch-name`) and the key it probably meant: the same one in another case or with `_` for `-` (the format uses both), one or two letters off, or a word of it.
Without one, an entry's warning lists the keys it takes.
Every mapped class, entries included, extends `MappedDefinition`, whose `@JqAnySetter` collects the keys; `ImageDef.unknownKeyWarnings()` and `ToolDef.unknownKeyWarnings()` walk the parts, and the env and skills converters hand their unknown keys to the entry they build.
A misspelled key changes the build as surely as a wrong value, since the build goes ahead without what it asked for, so it is not dropped without a word.
It is a warning and not a refusal, so every definition that loaded before still loads; whether some keys (a misspelled top-level section) should refuse is a separate decision.
The keys are never written back, so a stored definition holds only what isx read.

A credential in `config.yaml` written under a key isx does not read (`openai.token` for `openai.apiKey`) is kept and never used, so the only symptom used to be the credential reported missing (#1733).
`UnreadConfigKeys` names such keys in every namespace a tool declares, from the same declarations the proxy and the account resolver use: the tools' `proxy.configuration` paths and `AccountShape` keys, plus `accounts:` and `default:`.
The credential step of `isx init` and `isx account add` warns before it asks, the refusal of a template that needs the credential adds the note to its remedy, and `isx doctor` reports it.
The key is the user's, perhaps a secret they mean to move, so isx never moves it to the key it reads; the warning says to rename or remove it.
`ClaudeConfig` and each Claude account keep the keys they do not declare, as every typed namespace does, so a stray `claude.token` reaches the check and survives a save (#1876).
The `oauthMode` key isx itself wrote up to v0.3.8 is declared and dropped on load instead, since it never meant anything.
A namespace no tool declares is not checked, since its tool may simply not be installed here.
What counts as read is decided only by the tools the proxy sees, never by a project's `.incus-spawn/tools/`: a cloned repository must not silence a warning about a key the proxy will never serve.

Building an image automatically builds missing parents recursively.
`isx build --all` rebuilds every defined image from scratch.
`isx build` takes several templates (#1130).
`--with-parents` and `--with-descendants` turn them into one batch, the union of their chains (or subtrees) with every template once and parents before children.
So leaves that share a customization layer rebuild it once and are both derived from that one build.
Running `--with-parents` once per leaf instead rebuilt the shared parents each time and left the first leaf copied from a build that no longer existed.
That is also why a template whose parent was rebuilt after it now counts as out of sync (below).
Plain `isx build a b` and `--missing` build their targets one after the other, and like a batch (`rebuildAll`) a failure does not stop the run.
A target inheriting from a template that failed in this run is skipped, since its build would copy the old image, the others are built, and the run exits 1 with a single summary naming every template it left unbuilt, a parent a target's chain failed on included (the chain's own summary is held back for it).
One named target behaves as it always did.
In the TUI, Space marks templates on the templates table (#1131), and F5 then offers to rebuild the marked ones with their parents, with their descendants, or alone, as one `isx build` of several names.
The menu describes each batch with the chain `isx build` computes (`BuildCommand.withParentsChain`, `withDescendantsChain`), so a shared parent shows once.
The marks are TUI state only, kept by name: a refresh keeps them, drops those of templates that are gone, and Esc or a build launched from them clears them.
Space marks instances the same way (#1482).
While the focused table has marks, the keys that change state act on the marked rows and leave the row under the cursor alone, as a file manager with this F-key layout does.
F8 destroys the marked templates or instances after one confirmation that names them: only the built templates, and anything derived from another marked row before it.
F7 stops and Shift+F7 restarts the marked running instances, after a confirmation that lists every one of them and names the marked ones left alone (Sanne, #1482).
That list, like F8's for marked rows, is never cut short, because the marks may be out of sight and a stop ends what runs there.
A single row's F7 still acts at once.
The keys that open one thing (F2, F3, F4, F6, F9, `a`, Enter) keep acting on the cursor's row, since for several rows each would be a shell or a dialog.
Shift+F8 still means all.
A batch is refused while any marked row has an operation under way, as F5's are.
A mark moves nothing on the screen: it is drawn in the cursor gutter's blank second cell, and counted at the end of the context line.

**Base image**: The root image (`tpl-minimal`) uses a custom Fedora base image from [`Sanne/incus-spawn-images`](https://github.com/Sanne/incus-spawn-images) instead of linuxcontainers.org.
This image is a pre-baked systemd rootfs with agentuser, systemd-networkd, a connectivity watchdog, container-specific service masking, and a tmpfiles override for device node permissions — all the static setup that `buildFromScratch` would otherwise perform on every build.
A separate VM base image (`vm_image_url`) is also available: the same root, built from scratch around isx's own kernel, which the firmware starts with no boot loader and no initrd (`-vm-direct.tar.xz`, see design/macos-and-vms.md "The VM image's boot chain").
Fedora 44 releases also carried an older one, the stock Incus Fedora VM image customized with the same base configuration (`-vm.tar.xz`), for isx releases that read only `SHA256SUMS`.
Fedora 45 releases drop it and publish `SHA256SUMS.v2` alone (#1862).
isx releases up to v0.3.11 read `SHA256SUMS` by arch alone, so they would take a Fedora 45 release's checksums for their own `fedora-44-*` downloads and fail.
With no `SHA256SUMS` to read they keep their built-in release, as later ones do on finding none of their exact names.
A base image tag and SHA256 checksums are baked into `src/main/resources/images/minimal.yaml`, but they are only an **offline fallback**.
When the base image is unpinned, `BuildCommand.resolveTrackedBaseImage()` fetches the newest release from the GitHub API at build time (via the shared `baseimage/BaseImageReleases`, whose owning repo is parsed from the definition's `image_url` — the YAML is the single source of truth, and a non-GitHub URL is simply not tracked) and downloads that release, with its per-arch container/VM checksums, in place of the built-in one.
The definition itself is left as loaded, so `definition-sha` and `build-source` fingerprint the same definition the staleness checks read, and `build-source` records the release used as `trackedImageTag`.
Swapping the release into the definition made every rebuild stamp a fingerprint the check could never match, so the root stayed "definition changed" and `--out-of-sync` rebuilt the whole chain on every run (#1244).
So a plain `isx build tpl-minimal` always installs the latest base image.
Any failure to reach or read the release list leaves the built-in tag in place and the build proceeds.
The upstream watch keeps that built-in fallback from drifting: its `base-image` item opens a PR to bump it when the images repo publishes a newer release of the same Fedora line, polled every six hours (design/upstream.md "The model", #2036).
It replaced the event-driven `update-base-image.yml`, which the images repo woke with a `repository_dispatch` carrying the tag and the checksums it had just computed.

**The bump takes only the tag on trust** (#1678).
The checksums in `minimal.yaml` are what every base-image download is verified against, and they were merged wrong more than once while the dispatch path copied them from the payload.
The watch reads every checksum from the release's own `SHA256SUMS` and `SHA256SUMS.v2` (`.github/scripts/base-image-sums.sh`, sourced by the engine's `github-release` source type).
The names looked up are those `image_url` and `vm_image_url` download, with the tag and arch filled in.
A release of a newer Fedora is an issue for a person, never a PR, since every template rebuilds on it; that bump moves the image alias and both URLs' file names from the built-in's release line, such as `fedora-44`, to the tag's (`release_line`, `move_line`, #1862).
A missing or malformed sum, or one the two files disagree on, stops the run naming the asset.
The PR lists each checksum beside its asset.
`upstream-verify` checks the four checksums against the release on every PR that changes `minimal.yaml`, a bump by hand included, so the pairing of rootfs, VM disk and arch is checked as well as the digits.
The watch's own PR must also be exactly the engine's regeneration of the item, or it fails the same check (design/upstream.md "The model").

`isx update-base` manages the pin: it fetches the release list from the GitHub API, retrieves per-architecture container **and VM** SHA256 checksums, and writes them to `~/.config/incus-spawn/base-image-pin.yaml` when pinning a specific version.
`--latest` (or menu option 1) simply removes that file, restoring build-time latest tracking — it does not itself download anything.

**A pin is data beside the definition, not a definition** (#1563).
The pin file holds only the release's base image: `image`, `image_url`, `vm_image_url`, `image_tag` and both checksum maps.
`ImageDef` applies it to `tpl-minimal` once the trusted layers are loaded, before the project layer, and marks the template `pinned`.
Every other field comes from whichever definition is in effect, so a built-in change such as #1561's `tmp.mount` mask reaches pinned users, and pinning keeps a user's own `minimal.yaml` edits.
Until #1563 a pin was a user `minimal.yaml` that replaced the built-in whole, copying back a hand-picked list of fields.
Every new built-in field then missed pinned users, and pinning over a hand-edited file dropped its edits.
A pin-only user `minimal.yaml` merged onto the built-in was rejected: it needs an exception to "a later same-name definition replaces", and a shape test that breaks again on the first hand edit.
`config.yaml` was rejected as the pin's home because `isx init` owns that file and support bundles redact it.
The pin carries the image's name and URLs, not only its tag, so a built-in moved to another Fedora's file names never pairs them with the pinned checksums.
It is applied only while the definition still downloads from the release repository it was taken from, with a warning otherwise, so its checksums never verify another publisher's files.
A pin file that cannot be read, lacks a field or lacks a checksum for an arch counts as a file that failed to parse, so `isx build` refuses rather than track the latest release in its place.
A project-local `tpl-minimal` replaces the pinned one whole, as it replaced a pinned user file before.
The pin is applied before fingerprinting, so pinning or unpinning marks `tpl-minimal` out of sync, as it did.
A user `minimal.yaml` with `pinned: true`, only the keys an earlier `isx update-base` wrote, and the built-in's `mask_services` and `remove_packages` where it lists them is that command's legacy pin: `isx update-base` removes it when it pins or unpins, and `isx build` notes it.
From #1561 that command copied those lists from the definition in effect, so other lists are a choice the user made.
Any other user `minimal.yaml` is the user's, and the command never removes it; before #1563 `--latest` deleted it whatever it held.
When such a file sets `pinned: true` itself, `--latest` says builds stay on its tag rather than report tracking the latest.
`BaseImageReleases` looks checksums up by the exact file names the definition's `image_url` and `vm_image_url` download, per arch, so a pin records the correct digest for each.
A Fedora 44 release carries two VM images from #1255 on, and only the old ones are in `SHA256SUMS`, byte for byte what earlier isx releases parse: `-vm.tar.xz`, built from the stock Incus image, and `-vm-direct.tar.xz`, built from scratch around isx's own kernel, listed with everything else in `SHA256SUMS.v2`.
isx reads `SHA256SUMS.v2` when a release has one.
Earlier releases told the VM disk from the container rootfs by `-vm` anywhere in the name, so one more line in `SHA256SUMS` would have overwritten a checksum they use; matching exact names keeps any new asset from standing in for another.
A manifest with a malformed line or a file listed twice is not read at all.
When the tracked release has no VM image the definition names (no release before #1255 has `-vm-direct`), the VM stays on the built-in release, tag and checksum together; it used to pair the built-in checksum with the newer tag, a download that could never verify.
A local base image is reused only when it is the file now expected, not only of the same tag: both VM images of a release share one, and the bump that moves the built-in definition from one to the other keeps it.
Incus fingerprints an image imported from one tarball by that tarball's sha256, so with a checksum to expect the fingerprint must be it, under whatever tag it was imported, which holds for imports by any isx; with none to expect, the import must have recorded the same URL (`incus-spawn.source`, written in one request with the tag and read back like `incus-spawn.project`).
isx releases before this import their VM image under `<image>-vm` and reuse it by tag alone, so a `-vm-direct` import of the same release there would pass for their stock image, which their VMs (Secure Boot on, root on virtio-scsi) cannot boot from.
So the alias follows the file (`BuildCommand.vmImageAlias`): the stock `-vm.tar.xz` under `<image>-vm`, shared with older isx, which find the same file there, and `-vm-direct.tar.xz` under `<image>-vm-direct`, which they never read; `isx clean` counts both as base images.
Incus keeps one image per fingerprint, so the expected file already imported under another alias, or under none, cannot be imported again: it is aliased as it is, its properties untouched, if the same project imported it.
Incus also resolves a fingerprint by prefix, so a checksum that is not a full SHA-256 would match any image starting with it: such a checksum fails the build before anything is looked up.
An unchecked import by an isx before this recorded no URL, and is imported once more.
If the request for `SHA256SUMS.v2` fails or returns an error status, `SHA256SUMS` is read instead; a v2 that downloads but does not parse is not read around.
The upstream watch's `base-image` item (`upstream/engine/source-github-release.sh`) points `vm_image_url` at `-vm-direct` in the bump of the first release that has it for both arches, says so in the PR, and refuses a later release without it rather than move the built-in back to the stock image.
`isx update-base` pins only what the release lists a checksum for on every arch, since a pin is used wherever it is copied: a release without the container image for every arch is not pinned, and one without the VM image the definition names for every arch is pinned without `vm_image_url`, with a warning, so its VM templates start from the Incus image server; the next pin is taken from the definition with no pin applied, so it names the VM image again.
See the [incus-spawn-images README](https://github.com/Sanne/incus-spawn-images#releasing-a-new-version) for the full release process.

**Resolution order** (later overrides earlier): built-in YAML (classpath) → user-defined YAML (`~/.config/incus-spawn/images/`) → search paths (`searchPaths` in config.yaml) → project-local (`.incus-spawn/images/`).
Definitions with the same name from a later source override earlier ones — this is the mechanism behind template customization, so it stays silent.
But two files declaring the same `name:` *within the same directory* is always a mistake (typically a copy that forgot to update `name:`, which silently masks the file you think you are editing).
`ImageDef.loadAllWithConflicts()` distinguishes the two: same-directory collisions become `NameConflict`s (listing every colliding file), cross-layer replacements become `LayerOverride`s.
`isx build` refuses to build while any conflict exists and names the offending files; the TUI degrades to a status warning but still renders; `isx doctor` surfaces both (conflicts as warnings, overrides as informational notes).
Both `ImageDef` and `ToolDefLoader` feed the same `LayeredDefinitions<T>` collector (`config/LayeredDefinitions.java`), which owns the per-directory collision/override bookkeeping and the `NameConflict`/`LayerOverride` record types — so the policy is defined once and applies identically to images and tools.
The collector also records files that failed to parse (`parseFailures()`), and image definitions refused at load for their name (below).
Loading skips them with a warning, but `isx build` refuses while any exist.
An unparsable file's `name:` is unknown.
So it may have been the target, a parent, or an override.
Building anyway would silently use whatever it was meant to replace — a lower layer's definition, or the stale build-source snapshot of an existing template.
Read-only commands keep warning and carry on.
A definition whose `name:` or `parent:` is not a name Incus accepts for an instance (`InstanceNames`, Incus's own rule: letters, digits and `-`, not starting or ending with `-`, not only digits, at most 63 characters) is refused at load, with one warning that shows the name through `oneLine` (#1274).
Such a template could never be built.
Refusing it where definitions load keeps a cloned repository's name from every place isx prints one, including places added later.
That name could carry escape sequences or line breaks.
The template drops out of `isx templates`, the TUI and `isx build` alike.
It is recorded with the parse failures, because it too may have meant to replace a lower layer's definition, so `isx build` refuses in the same way.
A definition with no `name:` at all is refused the same way, with a warning naming the file, rather than skipped silently.
`isx templates edit` reports the same as a validation error.
A tool definition whose credential namespace is not an identifier is refused at load the same way (#1266).
That is its `config-namespace`, or the namespace a `config-path` borrows.
An identifier is letters, digits, `.`, `-` and `_`, starting with a letter or digit, the rule proof tokens already used; camelCase and `.` stay allowed, because a previous release's `config.yaml` may hold such a namespace.
The namespace names a place in `config.yaml`, and `isx init` and `isx account` print it as is.
`ToolDefValidator.invalidNamespace` holds the rule, and `ToolDefValidator.validateDef` reports it as an error.
A tool whose `name:` is not an identifier, by the same rule, is refused at load the same way (`ToolDefValidator.invalidName`, #1778).
Every built-in tool already has such a name.
Bash completion feeds `isx tools list` to the completion script, and `compgen -W` expands its word list, so a cloned repository's tool named `$(...)` ran on TAB.
Refusing the name at load keeps it out of every listing, as for a template; a template's stored definitions are skipped under a refused name too.
The completion scripts are the second layer: none hands what isx prints to anything that expands it (see design/terminal.md "Shell completion asks the running binary").
The bash script quotes each candidate for the quote the word left open, because readline puts it on the command line as is, where a `$(...)` would run on Enter.
It uses `printf %q` only outside a quote: inside `"`, the `$'...'` form `%q` writes for a character such as U+2028 leaves a `$(...)` live.
An account's typed `<namespace>=` is dropped before quoting, because `%q` writes such a name as one `$'...'` word, where the prefix is no longer at the front.
A host with a user tool named outside the rule renames it (docs/dev/MIGRATIONS.md, "A user tool whose name is not an identifier").
A tool definition is refused the same way with a bare key under `parameters:` or `proxy.configuration:` (`flavour:` with nothing under it; `ToolDefValidator.bareKeys`, #1475): a bare key has no meaning of its own, and isx does not imply a default for it.

## Tool System

Tools define how software gets installed into template images.
Two formats:

**YAML tools** (primary format) — declarative, no Java needed:

```yaml
name: maven-3
description: Apache Maven (latest 3.x)
run:
  - |
    MAVEN_VERSION=$(curl -s https://dlcdn.apache.org/maven/maven-3/ ...)
    ...
verify: mvn --version
```

Schema fields (all optional except `name`):
- `packages` — dnf install
- `downloads` — artifacts to download and cache on the host, then extract into the container or expose as a file, or both (with optional SHA256 verification and symlink creation)
- `requires` — list of other tool names that must be installed first (resolved transitively); an entry without parameters is satisfied by an ancestor template's installation, whatever its parameters (#1906)
- `run` — shell commands as root
- `run_as_user` — shell commands as agentuser
- `files` — files to write (path, content, optional owner)
- `env` — environment variables written to `/etc/profile.d/isx-env.sh` (supports structured entries with merge strategies)
- `verify` — verification command (logged, non-fatal), run as `agentuser` in a login shell, so `isx-env.sh` is loaded
- `verify_as_root` — set `true` for a check that needs root (`sshd -t` reads the host keys); it then runs as root in root's own environment (`HOME=/root`), without `isx-env.sh`

Execution order: packages → downloads → run → run_as_user → files.
Environment variables are collected centrally after all tools run, and only then does `ToolVerifier` run each installed tool's `verify` (`ToolSetup.verifyCommand()`), for agentuser checks with `/etc/profile.d/isx-env.sh` loaded.
It runs **as `agentuser`** through a login shell (`Container.shAsUser`, the same path as `run_as_user`), unless the tool sets `verify_as_root`: that is what the image's user gets, and a root run can leave root-owned state in their home when the env points there (0.3.9: zmx's `ZMX_DIR`, then `error: AccessDenied` on every `zmx` run).
A `verify_as_root` check runs in root's own environment instead, `env -i HOME=/root PATH=<root's default> sh -c <verify>`: without `isx-env.sh`, and without whatever the exec itself carries (an instance's `environment.*` keys, such as the agentuser `XDG_RUNTIME_DIR` GUI passthrough sets).
So it cannot rely on another tool's env entries (`sshd -t`, the one there is, never needed them).
No ownership repair runs afterwards.
One did, until #931, and it walked the whole home, cloned repos included, on every build with a root check, while each hardening in review (busybox's `grep`, walk errors, newline paths) added shell for a case root's own environment prevents outright.
A verify often depends on another tool: Maven's `mvn --version` needs the `JAVA_HOME` a JDK tool declares, and verifying it right after its own install (before the JDK, without the env file) failed in an image where Maven works.
A failed verify's warning keeps every line of the reason, joined and capped, since messages such as Maven's wrap mid-sentence.

**logind is opt-in per tool** (#1520): the container base image ships no system bus (`install_weak_deps=False`), and since incus-spawn-images#31 its logind is condition-skipped until one is installed.
So a tool that relies on logind (lingering, a user unit under `~/.config/systemd/user`) lists `dbus-broker` and `systemd-pam` in `packages:`, as podman and headroom do: the bus lets logind start at boot and start the lingering user's manager, and `systemd-pam` ships the `systemd-user` PAM stack `user@.service` runs.
It enables lingering by writing `/var/lib/systemd/linger/<user>`, which is what `loginctl enable-linger` writes, because `loginctl` needs logind running during the build, which a box being built never has; `ToolDefValidator.lingersThroughLoginctl` warns on a `loginctl enable-linger` step, when tools load as well as in validation.
A tool declares its own bus even when another tool in the template brings one, so any combination of tools works; dnf installs a package once.
**Alternatives:** a bus in the base image (every box carries a broker that only a few tools use); a `requires: podman` for the bus (a container runtime, and its `DOCKER_HOST`, for a dependency it does not need); a validator warning for a lingering tool whose `packages:` lacks a bus (warns on valid templates where another tool brings it).

**Environment variable system** (`EnvEntry` + `EnvResolver`): Env entries from the full template parent chain and all tools are collected by `GuestProvisioning.writeEnvFile()` into a single `/etc/profile.d/isx-env.sh`.
Four strategies: `set` (unconditional), `set-if-unset` (conditional default), `prepend`/`append` (additive with separator).
Conflict detection: two `set` entries for the same variable with different values fail the build with both sources named.
Templates (`ImageDef`) can also declare env entries.
Java tools participate via `ToolSetup.envEntries()`.
Definitions accept only the structured form: a shell string (`- export FOO=bar`) is rejected at load, with the structured equivalent in the error, because it would bypass conflict detection.
There is no verbatim-line escape hatch, not even for built-in code: every entry has a name and a strategy, so every entry is conflict-checked.
Values are escaped so they are taken literally.
Built-in Java code that needs the shell to expand a value at login (`$HOME` in Claude's `PATH` prepend, `$HOSTNAME` for `ISX_CONTAINER`) marks the entry `expandingAtLogin()`, which leaves plain `$NAME`/`${NAME}` references live but still escapes every other `$` (no `$(...)`), quotes, backslashes and backticks.
YAML cannot set that flag, and an expanded `$HOME` and a literal `$HOME` count as different values.

**Transitive dependency resolution** (`requires`): Tools can declare dependencies on other tools.
During build, `resolveWithDeps()` performs a recursive depth-first traversal to build the full dependency graph.
Circular dependencies are detected and reported.
Auto-added dependencies are reported once each, naming every tool that requires them: "Adding the sshd tool automatically: the herdr and orca tools require it".
Resolution only returns them (`ToolResolution.autoAdded`), and the Tools group prints the note as its first line, so it sits in the section it describes (#1935).
Printed during resolution, it landed after whatever step ran last and before the Packages group.
A required tool that cannot be found is warned about, and one a parent template already installed is inherited; neither is announced as added.
A `requires` entry that sets parameters other than a parent's, all of them reconfigurable, sets the tool up again, so it is announced; one without parameters keeps the parent's installation.
Its note names only the dependents this build sets up, or all of them when it sets up none.
Packages the build installs anyway do not silence the note, since the tool is still added and its setup still runs.
Dependencies are installed before the tools that require them.

**Java tools** (fallback) — for tools needing programmatic logic beyond what YAML supports:
- Implement `ToolSetup` interface (`name()` + `install(Container, Map<String, String>)` + `envEntries(Map<String, String>)`)
- Discovered via CDI (`@Dependent`)
- Currently used by: `claude` (binary install + settings), `codex` (npm install + settings), `copilot` (npm install + settings), `gh` (dnf install), `pi` (npm install + settings), `bob` (npm install)
- npm-distributed CLIs whose real binary is an *optional* per-platform package (`codex`, `copilot`) install through `NpmGlobalInstall`.
  npm treats a failed optional dependency as skippable and still exits 0 -- at any log level below `http` it does not even mention it -- so a transient download failure leaves only the JS launcher and the template is stamped as built anyway (#808).
  After `npm install -g`, and after the `npm update -g` of `isx update-all` / `isx project update`, a node one-liner run as root resolves the platform package the way the launcher does.
  A missing one is reinstalled, then fails the step with the failure lines from npm's verbose log.
  A just-published release reaches the registry piecemeal (#1757): npm publishes the launcher minutes before its platform builds, skips a platform version it cannot resolve without a word, and the CDN can answer 404 for a tarball the metadata already names.
  So the first reinstall is immediate, and each later one waits out a bounded backoff (`REGISTRY_RETRY_DELAYS`, 6m45s in all); an `npm install` that fails with E404 or ETARGET retries from the same budget.
  The `npm update -g` of `NpmUpdate` retries the same way, from a budget of its own.
  Any other npm failure fails at once, and nothing pins an older version: the build still verifies the latest release, only later.
  The check deliberately does not run the CLI: `copilot --version` unpacks ~165 MB into agentuser's cache and `codex --version` leaves lock files, all of which would be baked into the template.

`GeminiSetup` installs `@google/gemini-cli` through `NpmGlobalInstall` and configures user settings in `~/.gemini/settings.json`.
It disables telemetry, usage statistics, automatic updates and notifications, startup tips, the banner and home-directory warnings, folder trust prompts, and inner sandboxing.
It writes a priority-zero allow-all user policy in `~/.gemini/policies/isx-auto-approve.toml` for default, auto-edit, and YOLO modes.
This makes direct and headless launches auto-approve tool calls while leaving plan mode unaffected and allowing higher-priority user rules to override it.
The policy allows shell redirection, but default-mode shell heuristics still apply to commands outside the workspace and parsing failures; full YOLO still requires its flag.
The optional, reconfigurable `model` parameter pins `model.name`; otherwise the CLI selects its default.
Authentication uses a Gemini API key held in host-side `gemini.accounts.<name>.apiKey`, with the ordinary default, template and instance account selection (#1409).
`GeminiSetup` declares the secret through the generic proxy configuration, so account prompts, persistence, readiness checks, DNS interception and per-instance resolution need no Gemini-specific branches.
The guest selects `security.auth.selectedType: gemini-api-key` and exports the static build placeholder `GEMINI_API_KEY=gemini-placeholder`.
Login replaces that placeholder with the stable per-instance proof for namespace `gemini`.
Gemini reads the environment at launch, so neither settings nor a credential cache holds a build proof.
The SDK sends it in `x-goog-api-key` to `generativelanguage.googleapis.com`; the existing header carrier verifies it before injecting the selected account's key, for HTTP and WebSockets alike.
This follows [Gemini CLI's API-key authentication](https://geminicli.com/docs/get-started/authentication/) and the Google SDK's `src/node/_node_auth.ts` header carrier.
Independent credentials and unproven requests retain their original authentication and destination semantics, including when an account pin is missing.
The route names the exact Gemini API host, avoiding unrelated Google APIs.
Google OAuth and Vertex remain separate work (#1114), rather than adding token acquisition and routing to a key-only account.
Install, reconfigure and inherited-tool refresh remove the old login-notice script from #1407; existing instances need a rebuilt template and a new branch.
The action retains `bash -lc gemini` so zmx launches in existing instances still reach that login guard until they are rebuilt.
Inherited-tool refresh sets the API-key auth selection while preserving the parent's model and other settings.
Interactive Gemini requires this selection even when the environment supplies a key; headless Gemini can infer it from the environment.

**Resolution order** (later overrides earlier): built-in YAML (`resources/tools/`) → user-defined YAML (`~/.config/incus-spawn/tools/`) → search paths → project-local (`.incus-spawn/tools/`).
A YAML tool with the same name replaces any earlier definition.
Two tool files declaring the same `name:` within one directory are a same-directory conflict, reported by `ToolDefLoader.conflicts()` and treated exactly like image conflicts (see the images section above).
Java CDI implementations (`@Dependent` beans) are used as fallback when no YAML tool matches.

**Feature flags**: Tools can declare a `feature()` that gates them behind an opt-in `features` list in `~/.config/incus-spawn/config.yaml`.
A gated tool is excluded from build resolution, proxy registration and action resolution until the feature is enabled.
The `isx init` credential menu is deliberately *not* gated: a gated tool's credential is usually what the user needs to configure first, so hiding it until the feature is on is a deadlock (Pi with `provider: openai` hit exactly that).
`openai` (gating `codex`) was the first and so far only use; codex graduated to always-available once the flow was validated end to end, so no built-in tool is currently gated and the mechanism waits for the next experimental capability.
Enablement is purely the `features` list: the earlier "a configured `openai.apiKey` implicitly enables `openai`" rule existed so that graduating the flag couldn't break setups that predated it, and went away with the gate.

## YAML tools vs a full plugin system (Packer, Ansible, etc.)
We evaluated Packer (null builder + shell provisioner) and Ansible but rejected both.
Packer's null builder is just indirection over what Java already does, and Ansible adds a Python dependency and playbook complexity for what amounts to "install some packages and run some scripts."
YAML tool definitions give 90% of the flexibility with zero dependencies.
Java `ToolSetup` implementations remain available as an escape hatch for tools that need programmatic logic (reading host config, conditional branching).

## Hardcoded built-in tool list vs classpath scanning
Built-in YAML tools are loaded from a hardcoded list of filenames rather than scanning the classpath.
This is a deliberate choice: Quarkus native image compilation makes classpath directory listing unreliable, and the list only changes when a developer adds a built-in tool (at which point they also update the loader).
User-defined tools in `.incus-spawn/tools/` are discovered via filesystem scanning.

## Host Repo Refresh

Builds never fetch into or create user working checkouts for acceleration (#1395).
`GitPreparation` reads matching checkouts as object sources and reconciles against the real remote in owned staging.
Explicit `isx update-all` still uses `HostRepoRefresh` to fetch linked checkouts; its existing managed-shallow depth policy remains intact.
Every host Git command that talks to a remote runs through `GitProcess` (#1343).
It is non-interactive: `GIT_TERMINAL_PROMPT=0` and stdin `/dev/null`.
It also leads a session of its own (`OwnSession`, see design/macos-and-vms.md "The macOS VM outlives whatever started it"), so a build's first Ctrl+C, which may be a copy, reaches isx alone (#1425); only `update-all`'s Git, which no second Ctrl+C guards, stays there, where the first one stops it.
It reads Git's `--progress` output on its own thread into a bounded tail: the last 20 lines of up to 200 characters, with terminal escapes, control characters and URL credentials removed, since a remote's text reaches the terminal.
A `\r` alone ends a progress update; `\r\n`, as ssh ends its messages, ends a whole line that the tail keeps.
A stopped Git reports the tail as it was when the stop was decided, never what the kill made its tree print, such as macOS sh's `Terminated: 15` for its killed job (#2114).
A line it left unended, such as a prompt it stalled on, is part of that tail.
SSH never prompts, in a build (decided on #1403) or in `update-all` (#1410).
isx inserts `-o BatchMode=yes` right after the `ssh` of the command Git would run, in Git's order (`GIT_SSH_COMMAND`, else `core.sshCommand`, else `GIT_SSH`), where a later `-o BatchMode=no` or a trailing comment cannot undo it, or uses plain `ssh -o BatchMode=yes`.
`update-all` fetches in the user's checkout, so that repository's own `core.sshCommand` applies; a build runs Git in isx's own repository, so only the user's global one does.
Where the option cannot go (`GIT_SSH`, a wrapper or plink, a command isx could not read), `SSH_ASKPASS_REQUIRE=force` with `SSH_ASKPASS=false` makes OpenSSH 8.4 and later ask a program that answers nothing instead of the terminal.
isx sets those two for every case.
OpenSSH before 8.4 ignores them, so there a wrapper or `GIT_SSH` can still prompt until the stall budget ends it.
isx also sets an empty `GIT_ASKPASS`, which ends Git's own askpass chain (`GIT_ASKPASS`, `core.askPass`, `SSH_ASKPASS`) before any dialog.
The failure note says which of these isx did, and to which command.
Local preparation steps use no SSH and skip reading `core.sshCommand`.
A failure keeps isx's words apart from Git's (`GitCommandException.details()`).
isx's reason comes first, then, for a failure a prompt would have answered, what isx did and what to set up.
For a credential Git could not get over HTTPS, that advice names the GitHub CLI where it can help, by `gh auth status --hostname` for the host in Git's message.
A logged-in `gh` gets `gh auth setup-git`, with `--hostname` for a host other than github.com.
For github.com, a `gh` that is not logged in gets `gh auth login` first.
Otherwise, including when `gh` is missing or takes over 10 seconds, the note just says to configure a credential helper.
`gh` runs only after such a failure, so it costs a successful fetch nothing.
isx does not use a template's GitHub account for these host fetches: host-side Git authenticates as the user, and a checkout is shared by every template naming its URL.
Then comes `Git's output:`, with each of Git's lines quoted as `| …` and URL credentials scrubbed, an HTTP(S) URL's whole userinfo included.
isx's own text, such as a reason it stopped Git or could not run it, never goes under that header.
The exception message names only the Git command, never its URL or output.
The calling thread polls instead of reading, so it can stop Git at any time.
It stops a transfer that exceeds the `git-transfer:` total limit, or that stalls for the stall budget: no output, no I/O (`/proc/<pid>/io`, sockets included, on Linux), and less than 5% of a CPU across Git and its live helpers between samples, a second or more apart.
Any change in CPU time is not enough: an idle `git-remote-http` polls curl and gains a clock tick a minute while its server says nothing.
Usage is kept per process, so a helper that exits neither looks like work nor takes the others' usage with it.
Silence alone is not a stall, since resolving deltas or writing a pack is quiet but busy, and a server that is still building a pack sends keepalives; where CPU times are unavailable only the total limit applies.
On macOS the JDK reports CPU time only for its own process, so the tree's CPU times come from `ps -o pid=,time=` instead.
There are no I/O counters there, so a server that builds a pack for longer than the stall budget, sending only keepalives and no progress, is taken for a stall.
On a stop or an interrupt it sends Git and every helper it started a SIGTERM, so Git can remove its temporary packs and locks, then a SIGKILL after two seconds; it waits (bounded) for them to exit, and stops waiting for a helper that still holds the pipe.
`update-all` shows each fetch's elapsed time and latest progress line live, or a "Still fetching" line every 30s off a terminal; a stopped fetch is the usual non-fatal warning and leaves the checkout as it was.
A clone it stops is deleted, since an incomplete clone is no checkout.
`--skip-git-refresh` remains accepted by builds as a compatibility no-op.
Checkout discovery and instance push/pull linking still use `host-paths` and `repo-paths`.
Discovery narrows same-named checkouts to those with a remote matching the URL (`GitRemoteUtils.resolveHostRepoPath`).
A checkout only lends objects, so a lookup that fails (still ambiguous, unreadable) drops the checkout and keeps the preparation from the remote; the build warns with the repo's name why its checkout was not used (#1933).

## Build Flow

**`buildFromScratch` (root image, no parent):**
1. Import and launch base image (pre-baked with agentuser, systemd-networkd, service masks)
2. Install trust anchors: MITM proxy CA and, when `host-ca: { propagate: true }`, host CA certs — each as `isx-<sha256>.crt`
3. Configure security (idmap, nesting, syscall interception, no capability dropping) — *skipped for VMs*
4. Prepare container for package install (tmpfiles overrides, temporary DHCP network config, man dirs) — *skipped for VMs*
5. Configure DNS (disable systemd-resolved, point at Incus bridge gateway)
6. Upgrade system packages
7. Install image-defined packages via dnf
8. Install image-defined tools (resolved from YAML/Java)
9. Clone declared repos (with reference optimization — see below)
10. Configure terminal title (`PROMPT_COMMAND` in `.bashrc` sets `isx:<hostname>`)
11. Pre-trust cloned repo directories in `.claude.json` and `.codex/config.toml` (when the corresponding tool is installed)
12. Write the agent instructions (`/etc/isx/agent-instructions.md`, linked into each agent's location) — see below
13. Clean caches (dnf, /tmp)
14. Tag metadata (version, SHA, definition fingerprint, CA fingerprint, build source), stop

Claude Code and Codex trust is restored for repositories from the whole template chain after all tool setup finishes, in both build paths.
Reconfiguring either tool in a child layer rewrites its config, so trusting only that layer's repositories would lose the parent's pre-approvals.

Codex's footer renders a custom permission profile's name verbatim (verified with stock Codex 0.162.1).
The built-in tool selects a profile named `Running unconstrained in isx [version] [container]`, granting root filesystem writes and network access with approvals disabled, and `web_search = "live"` restores the live search a custom profile would otherwise lose.
Its login script refreshes only that name after `isx-env.sh`, so clones display their own identity while preserving custom footer fields and user-selected permissions.

Pi's footer gets the same message from an extension, since Pi has no status-line command (`PiSetup.STATUS_EXTENSION`).
It sets an extension status, the footer's third line, from `ISX_VERSION` and `ISX_CONTAINER` read when Pi starts, so no login script is needed.
The file is root-owned at `/etc/isx/pi/isx-status.ts`, beside the other isx files, and the user settings' `extensions` entry loads it.
Dropping it into `~/.pi/agent/extensions/` was rejected: that directory is the user's, as `~/.pi/agent/AGENTS.md` is.

**VM-specific build behavior:**

When `type` is `vm` (set in the definition or via `--type`), `buildFromScratch` applies the entire ancestor tool/package chain from YAML definitions alone.
Parent Incus instances are not needed.
So container parent rebuilds are skipped when a type change is detected in `buildChain`.
Additional differences:

- **Base image**: uses `vm_image_url` (pre-baked VM qcow2) when available, falls back to a stock Incus VM image otherwise
- **Disk expansion**: runs `growpart` + `resize2fs`/`xfs_growfs` before package install (both for pre-baked images that ship at 10G and the final build which defaults to 100G).
  The partition is the root's mount source, its disk comes from `lsblk` and its number from its name, so it grows on virtio-scsi (`sda`), virtio-blk (`vda`) and NVMe alike.
- **Root disk on virtio-blk**: `prepareVmBuild` sets `io.bus=virtio-blk` on the root in the one write it makes, and branches copy the device (#1255).
  The base VM image's own kernel has no virtio-scsi, Incus's default; the stock Fedora kernel builds virtio-blk in, so an image built from it boots on either.
  Per request, virtio-blk measured about 18 µs less than virtio-scsi at queue depth 1 (#810).
- **Security config**: container-specific security settings (raw.idmap, nesting, setxattr interception) are skipped — VMs have their own kernel and don't need them
- **No restart**: VMs don't need the container restart that applies security config changes
- **Guest SELinux pinned to `disabled`** (#842): the VM base image ships no SELinux policy and a filesystem nothing ever labelled, but ordinary packages pull the policy in (`perl` -> `selinux-policy-targeted`), and its `%post` writes `SELINUX=enforcing`.
  The build still succeeds, because the policy only loads on the *next* boot, where it denies the incus-agent's vsock `listen` and the instance is unreachable.
  Relabelling does not help: the agent runs from tmpfs as `init_t`, which the targeted policy never allows to listen on a vsock socket.
  So both build paths call `disableGuestSelinux` before any package install, writing `SELINUX=disabled` to `/etc/selinux/config`, or rewriting an existing `SELINUX=` line.
  The rewrite cannot rescue a parent that already says `enforcing`: its copy boots enforcing and the agent is unreachable before the step runs.
  Such a parent was built by an older isx, so `isImageOutdated` makes `buildChain` rebuild it through the fixed path first.
  That `%post` only writes the file when it is missing or empty, so the seeded file survives every later install, in the template and in its branches.
  `assertGuestSelinuxNotEnforcing` then fails the build if anything set the file back to `enforcing`, so the failure cannot ship silently.
- **Waiting for the agent** (#844, #953): `IncusClient.waitForReady` is the one wait for "the instance answers exec", for builds and branches alike.
  A VM gets 120s rather than a container's 30s, since firmware, kernel, systemd and on a first boot cloud-init all come before its incus-agent.
  Every caller passes the known `MachineType`: builds pass `activeBuild.machineType()`, branches and VM-only code pass `MachineType.VM`, and callers on existing instances pass `incus.machineType(name)`.
  A fallback no-arg overload still learns the type from the status GET that follows a failed probe.
  But callers should not rely on it.
  Incus may return a non-exception failure (`exit 0` with an error on stdout, as observed for VMs whose agent is booting) that bypasses the detection and applies the 30s container timeout.
  Both timeouts are configurable via `ready-timeouts:` in config.yaml (see `ReadyTimeoutsConfig`), loaded eagerly at `IncusClient` construction:
  ```yaml
  ready-timeouts:
    container: 60s
    vm: 3m
  ```
  Durations use the same format as `artifact-cache:` (`30s`, `2m`, `5m`); unset values keep the built-in defaults.
  While a VM waits, its console log (`GET /1.0/instances/<n>/console`, what `incus console --show-log` prints) is checked every 2s for the agent failing (`VmAgentFailure`: `Failed to start incus-agent`, or an `avc: denied` for `comm="incus-agent"`).
  Once such a line appears the agent gets 20s to recover through systemd's restart, then the wait fails with those lines quoted instead of running out the full budget.
  Reading that endpoint is not destructive: Incus drains QEMU's ring buffer into a log file and returns the whole file, which it also serves once the VM has stopped.
  Incus rotates that file to `.old` on every start, so an earlier boot's agent failure cannot cut a later wait short.
  A failure names the VM and its agent, never "Container", and points at the whole console log, which it first saves on the host (`VmAgentFailure.save`, to `~/.local/state/incus-spawn/console-logs/<n>.log`, overwritten by the next failure).
  It used to name `incus console <n> --show-log`, which the macOS host has no CLI to run (#1139); isx already reads the log over the API, so saving it does the step for the user.
  A log that cannot be read or written is not named.
  The wait prints nothing itself: its callers already show a step line for it, and the TUI calls it too.
  A VM is probed with exec far less often while its state reports the agent disconnected (#954).
  Each iteration first reads `GET /1.0/instances/<n>/state`, whose `processes` Incus reports as -1 until the agent connects -- the moment exec starts working -- and the exec probe, a POST plus WebSockets that almost always fails until then (and leaves `broken pipe` lines in the guest journal), runs on every poll only once `processes >= 0`, and otherwise only at the safety interval below.
  The same read's `status` catches a VM that died.
  Exec stays the authority.
  `processes` says the daemon reached the agent, and every caller needs exec next.
  So the wait still ends only on a probe that ran -- exit 0 *and* `ready` on stdout, because exit 0 alone has been seen from a probe that never reached the agent.
  The gate opens once and stays open, so an agent that restarts later is handled exactly as before, and a state without `processes` opens it at once, degrading to plain exec probing rather than waiting out the budget.
  `processes` is not proof the other way either: Incus also reports -1 when its own state query to a connected agent fails, so while the gate is shut exec still gets one probe every 5s (`ReadyTimeouts.gatedProbeInterval`).
  A wrong -1 then costs at most that much, rather than a healthy VM timing out, and a boot still makes a handful of probes instead of one per poll.
  Containers are never gated: their first probe usually answers, and a state read would be a round trip added to the branch path.
  The budget, the console fail-fast and the messages are the same either way; the gate changes only what is polled inside them.
- **Recovering an unresponsive agent** (#843): when `isx shell`, `isx run` or the TUI find a running VM whose agent does not answer, `VmAgentRecovery.restartForAgent` decides whether to restart it.
  A restart recovers an agent that merely wedged.
  But it is a cold boot that kills the guest's work, and it cannot help an agent that fails to start on this image.
  That boot fails identically, and restarting unconditionally cycled such a VM on every attempt.
  So it restarts only when nothing says that is futile, and at most once per boot.
  The agent is probed through a caught exec, since Incus refuses the exec outright when the agent is down.
  If this boot's console log holds a `VmAgentFailure` line that systemd has not since followed with `Started incus-agent` (`VmAgentFailure.unrecoveredLines`, which `waitForReady` uses too, so a failure the agent recovered from never cuts a wait short), the agent gets `waitForReady`'s restart grace and then those lines are reported instead of restarting (the log is rotated at every start, so they belong to this boot; systemd prints unit status there only during boot, so the two kinds of line are ordered comparably).
  Otherwise it restarts and stamps the new boot's QEMU pid (`IncusClient.pid`, from `GET /state`, fresh at every start) as `user.incus-spawn.agent-restart-boot`, before the wait so a boot whose agent never comes up is recorded however the wait ends.
  An agent unresponsive on the stamped boot is reported, pointing at the saved console log and the restart-anyway hint (`incus restart --force` on Linux, the TUI's Shift+F7 on macOS, which has no incus CLI).
  Any other boot, one the user started or a guest reboot, gets its one restart, so no stamp ever needs clearing, not even on a copy.
  Both callers reach it through `InstanceLifecycle.ensureReady`, the one "start it, or recover its agent" step before a shell.
  The guest is asked to shut down first (ACPI, which needs no agent, 15s) and powered off only if it ignores that.
- **Tool downloads**: large file pushes over vsock are slow, so `YamlToolSetup` uses a mount-and-copy strategy for both extracted archive and downloaded files exposed via `destination_file`

None of this applies to `type: kvm`, which builds a **container**: `effectiveMachineType()` returns `MachineType.CONTAINER` for `kvm`, since `BuildCommand.InstanceType` maps both `container` and `kvm` to `MachineType.CONTAINER`.
The type is stamped as the template's `instance-mode`, and `isx branch` then passes the host's `/dev/kvm` (and `/dev/vhost-vsock`) in as `unix-char` devices (`KvmPassthrough`) so the branch can run VMs itself; `--no-kvm` opts a branch out.
A device node can only be handed to a container, which is why `kvm` is a container type.

**`buildFromParent` (derived image):**
1. Copy parent image, start, wait for network
2. Install image-defined packages via dnf (deduplicated — see below)
3. Install image-defined tools (with transitive `requires` resolution)
4. Install skills, clone declared repos, pre-trust repo directories
5. Write the agent instructions (`/etc/isx/agent-instructions.md`, linked into each agent's location) — see below
6. Clean caches
7. Tag metadata, stop

**A running build takes two Ctrl+C** (#1415).
A terminal can send Ctrl+C for Ctrl+Shift+C, its copy shortcut: ptyxis does when no text is selected, and on a non-Latin keyboard layout.
isx then gets the same SIGINT and cannot tell a copy from an interrupt, so copying a line of the output ended a long build.
While a build instance exists and isx is at a terminal, `BuildInterrupt` takes the first SIGINT as a warning to press it again, and a second within five seconds goes to the handler that was there before.
So an interrupt still ends the process with the shutdown hook's "Build interrupted for" report, and outside a build, at a prompt before it starts and off a terminal, Ctrl+C acts at once.
Git preparing a repository runs in a session of its own (`GitProcess`, #1425), out of the terminal's process group, so the first Ctrl+C does not reach it either.
So do the `tar`, `unzip` and `chmod` that tool setup runs on the host (`YamlToolSetup`).
Out of that group, the Ctrl+C that does end isx no longer stops them, so `OwnSession.start` has a shutdown hook stop them and their helpers, SIGTERM then SIGKILL, as `GitProcess` does after a stall, in one bounded pass (a process even SIGKILL cannot end, on a hung mount, must not hold the exit), and stops at once one that a still-running build thread starts meanwhile; a SIGKILL of isx leaves them to finish alone.
Turning off the terminal's own SIGINT (`stty -isig`) and reading Ctrl+C from stdin was rejected: the bytes are just as ambiguous, and a crash would leave the terminal without Ctrl+C.

## Agent Context File

Every build regenerates a short always-loaded primer for agents running inside the box.
Generation happens once per build, in both `buildFromScratch` and `buildFromParent`, and in each path after all layer work is finished.
So it sees the fully resolved image (every ancestor's tools and repos) rather than one layer at a time, and the repos it lists have actually been cloned by the time it is written.
Do not move the call earlier to sit beside `writeEnvFile`: in `buildFromParent` that is before `cloneRepos`.
Content comes from `AgentContextGenerator` (module `common`), a pure function that `GuestProvisioning.writeAgentContext()` feeds and writes — the same split as `EnvResolver`/`writeEnvFile`, and the reason the whole format is unit-testable without Incus.

**Stored once, linked into each agent's location** (#1788).
`AgentInstructions` writes the primer to one file, `/etc/isx/agent-instructions.md`, root-owned and 0644.
Each installed tool declares where its agent reads it with `ToolSetup.environmentContextPath`, so no tool is matched by name.
A location outside `/home/agentuser` is a hardlink to that file, so every agent reads the same inode.
`ClaudeSetup` declares `/etc/claude-code/CLAUDE.md`, so a template without the `claude` tool has no managed policy.
`PiSetup` declares `/AGENTS.md`.
One script does all of it in one exec, and records the locations in `/etc/isx/agent-instructions.paths`.

What a hardlink needs, and how each is met:
- **One filesystem.**
  A mount can put a location on another filesystem, where `ln` fails; that location gets a copy instead.
- **One owner and mode.**
  All links share them, so only root-owned system locations are links.
  A location in the home is the user's own copy, owned by `agentuser`, since a root-owned file there would be one the user cannot edit.
- **Nothing writes through a link.**
  A tool that wrote its file in place would change every agent's copy, Claude's managed policy included.
  So would a person editing one with `sudo`; the user docs say so, and the next build restores them.
  Claude Code never writes its managed policy and Pi never writes `AGENTS.md`.
  Gemini's memory tool writes to its global file (`getGlobalMemoryFilePath`), which is one reason Gemini's location is a copy.
- **Every build relinks.**
  The new content is a new inode, renamed over the store, and every location is linked to it again.
  A location a tool replaced with its own file is relinked rather than left stale.
- **Stale locations go.**
  Before relinking, a recorded location no tool declares any more is removed if isx wrote it: a link to the store, or a file starting with `AgentContextGenerator.HEADING`.
  Builds before the record wrote `/etc/claude-code/CLAUDE.md` and `/AGENTS.md` unconditionally, so those two are always checked the same way, as is Gemini's old `~/.gemini/GEMINI.md`.
  A file a template put there itself, under any other first line, is kept.
- **Images keep their links.**
  A copy-on-write copy keeps the inode, and `incus publish` keeps the link count, both checked on a real Incus.
  A VM build runs the same script in the guest, on its own disk.

For images containing `gemini-cli`, the same content is also written to `GeminiSetup.CONTEXT_PATH` (`/home/agentuser/.gemini/extensions/isx/GEMINI.md`) and owned by `agentuser`.
Gemini CLI has no system context layer: it reads the user's `~/.gemini/GEMINI.md`, the workspace's ancestors and every active extension's context file (#1784).
So the instructions go to an extension of isx's own, whose manifest `GeminiSetup` writes, and the user's global file stays theirs.
Rejected: `GEMINI_SYSTEM_MD` replaces Gemini's whole system prompt, and a system `context.includeDirectories` widens the workspace.
Gemini ranks extension context above the global file on a conflict, as Claude Code ranks its managed policy.
Unlike the global file, which goes into the system instruction, extension context arrives in the session's first user message, so a compressed long session may summarize it.
Both build paths regenerate it after resolving the whole template chain, including when Gemini is inherited without reconfiguration.
Project context files are untouched and `context.fileName` stays unset.
Images containing `pi` get the same content in `PiSetup.CONTEXT_PATH` (`/AGENTS.md`), owned by root.
Pi has no system-wide layer like Claude Code's managed policy.
It loads `AGENTS.md` from its agent directory (`~/.pi/agent`) and from every directory between its working directory and the root.
So the root's file reaches every Pi session, while the user's `~/.pi/agent/AGENTS.md` stays theirs and loads before it.
The agent directory was rejected for the same reason Claude Code's user layer was: isx would overwrite a file the user owns.
Pi links to the store, not to `/etc/claude-code/CLAUDE.md`, so it depends neither on the `claude` tool nor on where Claude Code keeps its managed policy.

Images containing `codex` get the instructions in `CodexSetup.REQUIREMENTS_PATH` (`/etc/codex/requirements.toml`), root-owned and 0644 (#1783).
Codex reads no Markdown outside the user's `~/.codex/AGENTS.md`, which isx must not write.
Its system requirements layer is the analogue of Claude Code's managed policy: `additional_developer_instructions` there is sent as a developer message of its own, beside the user's instructions, in the TUI and `codex exec` alike.
The rejected alternative, `developer_instructions` in `/etc/codex/config.toml`, is the lowest config layer, so a user's own value would replace it.
TOML cannot be a link to the store, so `ToolSetup.renderEnvironmentContext` lets a tool render its own file, which `AgentInstructions` writes in place.
It starts with `AgentContextGenerator.HEADING`, a TOML comment, so stale cleanup treats it like a link.
The value is a multi-line basic string with every quote, backslash and control character escaped, so an agent note cannot end it early.
A requirements file can also hold a template's own policy, such as allowed sandbox modes, so a non-empty one under another first line is kept and the build warns.
Codex refuses to load any config whose managed instructions pass 10,000 estimated tokens, so instructions above `MAX_INSTRUCTIONS_BYTES` fail the build rather than leave Codex unable to start.
The MCP Codex runner still sends its primer from the store, since an older Codex inherited from a parent image may not read the requirement.

**Why the managed-policy layer for Claude Code.**
That path is Claude Code's *managed policy* memory location on Linux: it loads ahead of the user layer (`~/.claude/CLAUDE.md`) and the project layer (`./CLAUDE.md`), all layers concatenate rather than override, and it cannot be suppressed via `claudeMdExcludes`.
Writing there means isx never merges with, prepends to, or overwrites a file a user or a template owns — no marker blocks and no read-modify-write logic, which the obvious alternative (generating into the user layer) would have required.
It also sits beside the `managed-settings.json` and `statusline.sh` that `ClaudeSetup` already owns in that directory, and root ownership is correct, so no `chown` is needed.

**What goes in it.**
One test governs every line: *would omitting it cause a wrong action?*
That admits four framework-level facts (passwordless sudo, so a missing tool gets installed rather than worked around; credentials for proxied services are held by the proxy rather than stored in the box, so they are not worth hunting for (stated in exactly those terms — `host-resources` can mount real credentials in, so a blanket "there are no credentials here" would be false for some templates); autonomy is for investigation, not for scope or outward-facing action such as opening a PR unbidden; and the "already installed / already cloned" lists, which stop an agent re-running `dnf install` or `git clone` for things the template already provides).
It excludes anything the agent would treat identically whether or not it was told — template descriptions, env vars already exported into every login shell via `/etc/profile.d/isx-env.sh`, and host-resource mount semantics.

**Names and paths, never descriptions.**
`ImageDef.contentFingerprint()` covers tool names and repo url/path, so those cannot drift away from the built image.
`description` is deliberately *not* fingerprinted (editing one must not trigger rebuilds), so rendering it could assert something that stopped being true.
`agent_note` *is* fingerprinted on both `ImageDef` and `ToolDef`, because a stale warning baked into an image is worse than an extra rebuild.

**`agent_note` vs `skills`.**
Notes are for always-true constraints and traps that must be known *before* the first relevant action; procedures belong in `skills`, which load on demand when the model recognizes a matching task.
Both are declarable on a tool as well as an image, so they travel with the tool into every template that installs it.
`mvnd` is the worked example.
The note is what gets it reached for at all (the name alone says nothing), and a skill would carry the procedure without spending context in sessions that never build anything.
Tool skills are installed by `installSkills` alongside the image's, deduplicated against them, and bare names resolve against the *tool's* `skills.repo`, since the tool reaches templates that have never heard of its catalog.
Most tools declare neither.

**Package deduplication**: Before installing packages, the build walks the parent chain and collects all packages from ancestor images and their tools.
These are subtracted from the current image's package list so derived images only install what's new.
The build logs both the count being installed and the count already present in ancestors.

**DNF cache sharing**: During builds, a persistent cache is mounted into the build instance at `/var/cache/libdnf5` so downloaded RPMs and metadata are reused across builds.
On every platform the cache is a custom Incus storage volume (`dnf-cache`) on the CoW pool, which works for both instance types: containers get a bind mount, VMs get it over virtiofs.
VMs are included because each VM build otherwise re-downloads the full repo metadata on its first dnf run (~20s).
The volume is attached before the build instance starts (`attachDnfCache`; see design/host-resources.md "Devices are attached before start"), which keeps it out of a VM's 8 hotplug slots.
It also means incus-agent has mounted it before the first exec, so no dnf run can race an asynchronous hot-plug mount and fill the image's own cache dir.
The package **install/upgrade** paths (and the VM rootfs dependency install, which runs only when `growpart`/`resize2fs`/`xfs_growfs` are missing: the prebaked VM image ships them, and a no-op dnf install still costs seconds of metadata loading) use `--setopt=keepcache=true` so downloaded RPMs persist, `--setopt=metadata_expire=3600` (1 hour) so repeated builds within that window skip metadata downloads, and `--setopt=max_parallel_downloads` (scaled to `CpuInfo.logicalCores()`, capped at dnf's practical max of 20) to parallelize the download phase.
The rpm transaction itself is serial.
These shared flags are centralized in `BuildProgress.DNF_BASE_OPTS` and spliced on by `dnfCommand(...)`.
Repo-management calls that download nothing (`dnf copr enable`, `dnf clean`) run plain `dnf` — the cache/download flags don't apply to them.
`dnf copr` is a dnf5 plugin isx's base images do not ship (stock Fedora does), so a layer that enables a COPR repo first installs `dnf5-command(copr)` through `dnfCommand(...)` (#1736).
An `rpm -q --whatprovides` check skips that install when the plugin is present, since a no-op dnf install still costs seconds of metadata loading.
It asks for the plugin of the dnf that runs (`dnf5-command(copr)` when `dnf5` is on the path, dnf4's `dnf-command(copr)` otherwise): a package can pull `dnf-plugins-core` into a dnf5 guest, whose `dnf copr` still fails.
The cache device is unmounted before the final cleanup step so the image stays small.
For VMs, `unmountDnfCache` unmounts inside the guest before removing the device (the agent would otherwise tear the mount down asynchronously).
`cleanCaches` skips `dnf clean`/`rm -rf` of the cache dir while it is still a mount point, since cleaning through a live mount would wipe the shared volume for every later build.
`isx clean cache` wipes the cache by deleting the storage volume (via `IncusClient.deleteStorageVolume`).
It lists the volume with the host cache directories, and deletes it only on the same confirmation, as `isx clean all` does (#1627).
`isx clean dnf` deletes the volume alone, and `isx clean pool` asks about it as one of its categories.
The volume is automatically recreated by the next build.

**DNF failure recovery**: All DNF install/upgrade commands are wrapped in `runDnf()`, which retries once on any failure.
It runs `dnf clean metadata` to clear potentially stale repo data, then retries with `--refresh` to force fresh metadata from a (potentially different) mirror.
This handles transient mirror issues like packages appearing in metadata before their signatures are available, without requiring manual intervention.

**DNF output**: dnf steps are one live step (`stepStart`, a `stepProgress` detail read on every frame, `stepDone`), drawn like every other step, rather than streaming dnf's verbose per-package output to the terminal, keeping isx's own warnings and caveats visible instead of scrolling off in a flood.
Install/upgrade (`runDnf`) stream dnf's output through a parser instead of echoing it: dnf5's non-TTY output emits one `[N/M] <action> <package>` line per completed step in both the download and transaction phases.
So the spinner shows live "N/M — current package" feedback parsed from dnf's own progress lines.
(dnf has no dedicated single-line progress mode; `--quiet` would suppress exactly those lines, so parsing the native output is the only way to get live feedback.) dnf's non-TTY column truncates each NEVRA's version/arch tail and the width can't be raised (`COLUMNS`/`terminal_width` are ignored without a TTY; a PTY widens it but replaces the tidy per-line output with concurrent ANSI progress-bar redraws).
So `shortenNevra` reduces each `name-epoch:ver-rel.arch` to its bare package name for the live label.
The streaming-without-echo path is `Container.execLines` → `IncusClient.shellExecStreaming` → `util/LineOutputStream`.
COPR-enable and VM rootfs-expansion use `runWithSpinner` with captured exec (single/short operations that don't warrant a parser).
On failure, the step ends in its ✗ and the end of the output is one warning under it (so it doesn't interleave with the live display); the `--refresh` retry is surfaced as a live "retrying with --refresh" detail.

## Which base image an instance runs

A fix in a base image reaches only instances created after it (#1657).
Base images before `fedora-44-20261009` restarted systemd-networkd every 30 seconds, and three long-lived instances on one host did that for weeks, found only by measuring the host's disk writes (#1656).
So `isx list` (`base_image`), the TUI's instance details and `isx doctor` say which release an instance runs, and whether a rebuild would move it to a newer one (`BaseImageAge`).

Nothing is stamped for it.
When Incus creates an instance from an image, it copies the image's properties into the instance's config as `image.*`, and a copy keeps them.
Every base image isx imports carries `incus-spawn.tag`, so `image.incus-spawn.tag` is already on templates built before this change and on every branch of them, which are the ones that matter.
A stamp written at build would have left exactly those unknown.
`BUILD_SOURCE` was not used either: a child template built on its parent records no tracked tag, so it can name an older release than the one the root actually started from.

The comparison is with the instance's root definition as loaded, its base-image pin applied: what a rebuild would use offline.
The image's fingerprint (`volatile.base_image`, a base image's tarball sha256) is compared before the tag.
A release can re-publish an unchanged asset, and the import then reuses the image under the tag it was first imported with, which says nothing about its age.
Otherwise the tags are compared (`<distribution>-<release>-<yyyymmdd>`, release first): a newer one, such as a tracked build of a later release, is fine, and one that does not parse is unknown, a `NOTE`.
A later release the same day carries `-2`, `-3` and so on (incus-spawn-images' `release-tag.sh`), which orders after that day's bare tag and before the next day.
Doctor's remediation is a rebuild the user starts: an instance cannot be rebuilt, only branched again, and that is theirs to decide.
An older base image is a `NOTE`, so publishing a routine release does not turn every host's `isx doctor` into a list of issues to address (Sanne's decision on #1664).
It is a `WARN` only when the instance's release predates a known fix that a rebuild would bring: `BaseImageAge.KNOWN_FIXES`, which names the fix, such as the network watchdog of `fedora-44-20261009`.
That list is written by hand, one entry per base-image fix worth a rebuild for everyone still on an older release.
A release lacks a fix only on the fix's own release line (`fedora-44`), since a date on another release says nothing about the scripts it was built from.
Each release is one finding, so a rebuild is planned once, and it is a `WARN` as soon as one member lacks a known fix, which the detail names with those members.
A note's remediation is printed under it, since only an issue's is offered after the list.
A project template is a copy of its parent, so it moves by `isx project create` again, which replaces it.
An instance is branched again from its template only when the listing shows that template current; a deleted one, or one whose release was not recorded, is built first.
Building a template builds every layer under it that is not known to be current as well, parents first.
`isx build` builds its targets in the order given and copies a child from its parent as built, so a child named first would copy the old image again.
The expected release is never fetched, so doctor and the list work offline; the next build reports tracking a newer published one.

## Fedora-specific
The base image and package management are Fedora-specific (`dnf`, `images:fedora/44`).
This is intentional — supporting multiple distros adds complexity for a tool primarily targeting developer workstations where Fedora is a common choice.
The YAML tool system is distro-agnostic in principle (tools can use any shell commands), but the built-in base image setup assumes Fedora.
