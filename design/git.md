# Git: clones, remotes and the remote helper

How repositories reach an instance, and how an instance and its host exchange commits.
The clone acceleration itself is specified in docs/dev/GIT-PREPARATION.md.

## Repo Cloning and Reference Optimization

Repos declared in an image definition are cloned into the container during build as `agentuser`.
Full network clones select all advertised branches and tags, with the remote's default HEAD or the requested branch/tag checked out.
This is the former local path's scope, now also used by the network fallback; the old network path initially fetched only one branch.
The normal broad fetch refspec is retained, while shallow clones keep their existing narrow selection and refspec.
The selection and cache contracts are specified in [Git preparation](../docs/dev/GIT-PREPARATION.md).
Its [clean-snapshot decision table](../docs/dev/GIT-PREPARATION.md#when-and-why-we-use-a-clean-snapshot) explains the build's ready-line labels and when snapshots are reused, updated incrementally, rebuilt or bypassed.

**Explicit host repositories** (`repos[].host-source`, #756 / #1408) export local branches and tags, including unpushed history, through `GitPreparation.prepareHost`.
The source is an absolute or tilde-expanded host path in a trusted template; project-local templates remain unable to prepare host repositories.
`branch` selects the initial checkout without narrowing the export, and `depth` is refused.
Only objects reachable from the captured branch/tag tips enter a newly packed independent repository, excluding stash-only, reflog-only, remote-tracking-only and unreachable objects, uncommitted files, hooks and configuration.
No build fetches into or modifies the source, contacts its corporate remote, or mounts the original checkout.
The prepared export uses the same lease, read-only transfer, independent guest copy and checksum verification on Linux and macOS, with no overlay dependency.
A failure aborts this repository step without a network fallback.
The guest preserves all exported branches locally and removes the temporary origin.
Canonical source associations are stamped by destination as `host-repositories` and inherited with the guest repository.
Automatic `isx://` remotes use those paths without URL matching or global `repo-paths` entries.
A host-owned `git-remotes/` record in the data directory makes cleanup independent of the current template definitions and the deleted instance.
Host sources are excluded from host refresh, and existing guest `file://` URLs retain their meaning.

**One clone per destination** (#1394): a repo's identity is its destination (`~/x` and `/home/agentuser/x` are one, and the default path comes from the URL), resolved across the whole template chain by `ImageDef.chainRepos`.
A layer clones only the destinations it declares first; one an ancestor declared is already in the parent copy (or was cloned by that ancestor's layer in a build from scratch), so the build prints `✓ Inherited <repo> (from <template>)` and neither clones nor primes it again, and a repeat within a template is the same clone.
Repeating a declaration is only allowed when it is identical (URL compared via `GitRemoteUtils.urlsMatch`, host source, branch, depth, prime): `ImageDef.repoConflict` refuses the build before any work starts, naming the destination, both templates and the differing fields, because changing an inherited checkout's branch, depth or prime has no defined meaning yet.
Trust, the agent context and the TUI's chain view list the same resolved repos.

**Parallel cloning**: when a template declares multiple repos they are cloned concurrently, bounded to the host's high-performance ("P") core count (`CpuInfo.highPerfCores()` — `sysctl hw.perflevel0.logicalcpu` on macOS, on Linux the top tier of the sysfs scan described in docs/dev/PERFORMANCE-NOTES.md "VM boot: where the wait goes" (the hybrid PMU's `cpu_core` list, else `cpu_capacity`, else `cpufreq`), falling back to all logical processors).
`CpuInfo` is the shared home for CPU-topology detection.
It also backs the appliance VM's vCPU default (`VmManager.detectCpus()` → `performanceCores()`) and the native-image-safe host processor count (`ResourceLimits.hostProcessorCount()` → `logicalCores()`, which reads `/proc/cpuinfo`/`sysctl` rather than `Runtime.availableProcessors()` since the CLI native image pins the latter via `-R:ActiveProcessorCount`).
Cloning runs entirely with captured (non-streamed) exec so the many parallel clones don't garble the terminal.
Progress is rendered by the shared `TerminalProgress` helper as one animated braille-spinner line per repo (green ✓ / red ✗ on completion), falling back to one report per repo on a non-ANSI terminal.
Off an ANSI terminal `RepoCloner`'s parallel block reports each repository as it ends, from its state (`TerminalProgress.run` with `plainDone`): a step when ready, a warning when it failed.
On any terminal the start of a failed repository's command and the end of its log are one warning (#1703).
Any clone failure is surfaced after the batch and aborts the build.
Which repos have a host reference is decided on the host before the parallel section; each worker attaches its own reference just before its clone and detaches it as soon as the clone is done (#826).
Attaches and detaches share a lock, because a device removal is a read-modify-write of the whole device map, and an attach that lands in between makes Incus refuse it: the retries below are bounded, and a burst of parallel attaches could exhaust them.
The removal itself is safe without the lock: its PUT carries the read's `ETag` as `If-Match`, so Incus refuses it with 412 when anything changed since, and `IncusClient.devicesRemoveAll` reads and writes again, up to `CONFLICT_ATTEMPTS` (#1234).
Every other write that removes a device is conditional the same way, since `IncusClient.update` takes the read it was built from (`readInstance`, metadata and `ETag`): a branch dropping inherited KVM devices and a template giving back its build address re-read and rebuild their whole update on a 412 (`retryWhileChanged`, #1276).
That covers what no in-process lock can: another isx process, such as the TUI, editing the same instance's devices.
Attaching lazily is what keeps a VM within its 8 PCI hotplug slots (see design/host-resources.md "Devices are attached before start"): mounting every reference up front lost the 9th one to the network, silently.
The slots are a budget (`HotplugSlots`) that starts at `min(references, 8)` on a VM and is unbounded for a container.
It is a hint, not a count.
An attach Incus refuses for want of a slot retires one permit for good and waits for another, so a device nobody counted shrinks the budget instead of reintroducing the bug.
Only when the budget would reach zero does a repo clone from the network, with a note saying why on its line.
On a VM, an attach is not a mount: Incus returns once the device is added, and incus-agent mounts the share in the guest shortly afterwards (about half a second measured), so a clone started at once found an empty directory and fell back to the network, silently (#1233).
So the clone from a reference first polls `mountpoint -q` for at most `MOUNT_WAIT_SECONDS` (30 s, bounded by `timeout`, so a guest clock step cannot lengthen it), in the clone's own exec: the wait costs no request, and runs outside the device lock.
A container's disk is mounted by the add itself, so there the loop ends at its first check.
A guest without `mountpoint` is not waited on: the clone runs at once, as it did before.
A reference that does not appear in time fails that exec with a status of its own, so its line says it was not mounted rather than quoting git.
Two references never appeared at all, whatever the wait: Incus shares a VM disk as the virtiofs tag `incus_<device name>`, hashes a tag longer than its 36-byte limit, and on a hot-plug still asks incus-agent to mount the unhashed tag (#1513, Incus 6.23).
So every device hot-plugged into a running VM has a name of at most 30 characters (`DeviceNames`): a reference keeps `ref-`, the start of the repo name and the URL hash that keeps it distinct.
Every way a repo ends up on the network clone instead of its reference says why on its line: no slot, no mount, or the git error of a failed clone from the reference.
Before the fallback the failed attempt's destination is removed, but only one it created: the clone from the reference first checks that its destination does not exist or is an empty directory (`freshDestination`, exiting 3), and a destination already there is left alone, so the network clone then fails on it rather than a checkout another layer made being deleted (#1394).
The latch the primes wait on counts *planned raw shallow* references, each counted down once, in a `finally`, after its attach is resolved and anything attached is detached.
So a failed or re-queued attach can neither hang the primes nor land after them.
Each repo's declared `prime` commands (also captured, no PTY; a list runs in turn and stops at the first failure) run in the same worker once that repo's clone is done **and every raw shallow reference has been detached**, so priming pipelines with other clean preparations and network clones.
Full-clone mounts contain only objects authorized by the declared source and sanitized repository metadata; the unchanged shallow path can still mount the host checkout's whole working tree.
A prime command is arbitrary code with network access, and the old "remove all references after everything" order let any repo's prime read every mounted checkout (#765).
A failed detach fails the build rather than prime next to a mount.
The concurrency bound is a semaphore in `prepareOne` taken around the clone and the prime separately, not `TerminalProgress`'s per-task slot.
A worker waiting for the detaches must not hold a slot, or with more referenced repos than slots the clones that would detach them could never start.
A single progress line per repo advances Cloning → Priming → ✓/✗.
Failures are aggregated and abort the build; as a best-effort fail-fast, once any repo fails a clone that completes afterward skips launching its (potentially expensive) prime — primes already in flight run to completion.

**Host preparation** (#1395): `GitPreparation` retains bounded clean snapshots for all full clones, optionally seeding the first by reading a discovered checkout's objects directly.
Discovery still uses `host-paths` and `repo-paths`, matching every configured remote, including an upstream beside a fork.
The host checkout is never fetched into or maintained, and its packs are not copied before borrowing.
A small list of the matching remote's tracking tips supplies negotiation hints through an invocation-scoped `core.alternateRefsCommand`, excluding private refs and other remotes' tips.
If a reference clone, dissociation or full fsck fails, its partial output is discarded and preparation retries once from the remote without any reference.
Source removal, corruption or concurrent pruning therefore cannot publish an incomplete snapshot.
A warm reuse requires a fresh authoritative advertisement with identical heads, tags, default HEAD and object format, plus a verified SHA-256 seal covering all cached repository files.
Branch/tag pins share the full selection and are checked on every request.
No graph walk, user-checkout access or repack runs on a hit.
Network-source preparation allows only HTTPS, SSH and Git network URLs and enforces the transport allowlist in Git, including after URL rewrites.
Paths, file transports and arbitrary helpers cannot turn a template URL into a host filesystem read.
SSH defaults to batch mode unless an SSH command or executable was explicitly supplied.
The guest first checks the remote with its template's accounts, and its full advertised selection must match the prepared snapshot before any bytes are mounted.
A busy remote moves during a long preparation, so a mismatch reads the guest's refs again and refreshes the snapshot incrementally from its own leased bytes, up to three times, before giving up (#1980).
The refresh fetches only when the host's advertisement equals the guest's latest reading, so a guest whose accounts see a different ref scope never costs a fetch.
The refresh never clones in full, and only a selection the guest has just seen is mounted, so the guarantee is unchanged.
When following the remote would need a rebuild, as when an unmerged branch was deleted, the refresh gives up and the guest clones from the network.
Denied access, different credential-visible refs or a selection still racing after the refreshes uses the ordinary guest clone path instead.
The host's `ls-remote` runs beside the guest's check, not after it: neither needs the other's result until the selections are compared, and it fetches nothing a denied guest could gain (#1934).
A read nobody uses is cut, and the build's repos step returns only once every read has ended, so none still touches the cache's work directories afterwards (#2000).
All guest access checks finish before any prime runs; host credentials alone never authorize snapshot access, including warm-cache copies.
The re-reads after a mismatch are the exception: a slow preparation does not hold back other repos' primes, so a re-read may follow one (#1992).
That is accepted because a prime can change only what the guest reads, never the snapshot it must match.
A match needs the snapshot's exact selection, including SHAs of refs the guest's accounts cannot see, so a prime that rewrites the guest's Git (`insteadOf`, a wrapper) can turn a match into a network clone but cannot forge one.
The guest trusts only the mounted snapshot's own path, on the clone's command line, for Git's ownership check, since an unshifted macOS mount shows it owned by an unmapped uid (#1590).
Changed refs first fetch incrementally into staging, with separate metadata and hardlinks only to the previous verified generation's owned immutable packs.
Fetching checks incoming objects, forces packed storage and disables automatic maintenance; new packs also pass `index-pack --verify --strict`, while old pack/index hashes must still match their seal.
Old refs and HEAD must remain reachable from the new selection, including annotated tag objects; unchanged roots and fast-forward ancestry avoid an object walk, with a reachability difference for the remaining roots.
This permits ordinary merges, new branches/tags and deletion of merged branches without rewriting or fully decoding old packs.
An advertisement/fetch mismatch, removed history, failed incremental validation or growth beyond 32 packs discards staging and uses a fresh `clone --bare --no-local --reference … --dissociate` instead.
The rebuild prefers the previous snapshot as its reference, never the failed staging tree.
Git's dissociation repack selects the current closure, excluding private reference objects and objects belonging only to deleted refs; bare cloning avoids the historical commit-graph/checkout failure in older Git's dissociation path.
Reference-seeded snapshots pass full fsck; one cloned without a reference relies instead on `fetch.fsckObjects` checking every object it receives, when the clone's `GIT_TRACE` shows that only strict receives wrote objects (dumb HTTP still gets the fsck, #1557); incremental snapshots reuse the object validation of exactly matching old bytes and validate new objects and their links.
Only selected pack/index pairs and freshly written refs enter the minimal bare repository mounted read-only into the guest.
The guest copies them with `clone --no-hardlinks`, checks their SHA-256 digests, and sets the real remote URL; no full-history guest repack remains.
A failed preparation, mount or verification falls back to a fresh remote clone before priming.
Shallow clones bypass this cache and still reconcile with the remote and repack their selected objects for each build.
Their ready line identifies the depth, and guest/attach/detach timings cover shallow and network clones as well as prepared snapshots.
Only raw shallow references hold up other repositories' primes; each clean clone detaches its own snapshot before priming while other clean preparations may continue.
`PreparationCache` atomically publishes immutable generations, coordinates writers through stable per-URL locks with a two-hour wait and pins readers in leased work directories across replacement and eviction.
One size/age budget bounds retained payload and metadata together, including for repositories with a host checkout.
Per-repo progress reports preparation phases and records their milliseconds alongside guest copy time.
Project-local definitions still get no host preparation or host mounts.

**Shallow repos** (#1344): a repo entry's `depth` keeps only that many commits, for repositories that publish binaries as commits (`isx-apt-releases` replaces its `.deb` files on every release, and every old one stays in its history).
Full history stays the default, since development repositories need it.
From the network the clone is `--single-branch --depth N`, and the refspec is *not* widened: a later `git fetch` of every branch would bring their history back.
From a host reference the local fast path cannot be used: a local clone ignores `--depth` and would copy the reference's whole history, and the reference may itself be shallow.
So the clone goes through the transport (`--no-local --depth N`), which reads only the last commits from the mounted checkout.
Then origin is pointed at the real remote, and its refspec narrowed to the configured branch (else the remote's default, from `ls-remote --symref`), which is fetched at the same depth and checked out.
A configured name `ls-remote` finds no branch for is a tag (exit code 2; any other failure is the remote's, and falls back to the network).
Its refspec becomes the tag's (`+refs/tags/<tag>:refs/tags/<tag>`, what `clone --single-branch --branch <tag>` sets), HEAD is detached at it, and no branch is kept.
Commits the reference already has do not travel again.
The clone through the transport also takes over the host checkout's own branch (which may hold unpushed work) and its origin/HEAD, so those refs are deleted, and origin/HEAD set to the branch only when it is the remote's default: the refs a shallow network clone has.
The objects only they reached are then stripped (`stripUnreachableObjects`, cheap in a shallow repo), so a host branch's unpushed work does not ship in the image.
Its tags go the same way, before the depth fetch, which follows the remote's own tags as a network clone does.
Guest updates do not pass `--depth`, so a shallow repo in a long-lived instance keeps the history fetched after its build; the old history stays out.
The other way round, a repo without a depth never inherits a shallow host checkout (one another template's depth made, or the user's own).
Full preparation refuses shallow object sources and uses a remote-derived snapshot or the remote instead.

**TUI visibility**: The F3 template detail view shows the host repo link status for each declared repo — the resolved host path when matched, or "No matching host checkout found" when not.
This lets users verify their `host-paths`/`repo-paths` configuration without running a build.
The view is full screen and shows the template's effective settings resolved across the inheritance chain (`TemplateDetails`), using the build's own resolvers so what it shows is what a build produces -- including the type a template was actually built as (its `instance-mode` stamp), flagged when the definition has since changed it.
Under the template's source it names the definition that one overrides from an earlier search layer, and the file the image was built from when that is another one -- the "the built image doesn't match my file" confusion of #593, answered where people look rather than only in `isx doctor` (#1099).
Behind "definition changed since last build" it lists what changed, as built versus now (packages, tools and their parameters, repos), with the command that applies it, and under Tools it names the tools a parent provided when the image was built and no longer does -- `claude` after #559 dropped it from `tpl-dev`, which `isx build` warns about only during a rebuild (#1201).
Both come from data the TUI already holds: the layered load and the listing's `build-source` stamp, so the view adds no Incus request.
A definitions directory reached twice (listed again, or through a symlink) is the same file, not an override.
`LayeredDefinitions.put()` still records the later layer's path and definition, exactly as for any layer, because project-local confinement is read from them -- a tool's from its recorded path (`projectLocalToolNames()`), an image's from its definition's `projectRoot` -- and a project's `.incus-spawn/` that is also a search path, under whatever spelling, must stay confined.
What changes is only the override list: no self-override is recorded, and the override the earlier spelling made is re-spelled to the later path, so the real override below it still shows.

## Clone selection and future fetches

Full clones now use the same initial selection on the prepared and network paths: all advertised branches and tags, with a broad future-fetch refspec.
The old network-only single-branch optimization made initial refs and tag reachability depend on whether a host checkout happened to exist.
A configured branch wins over a same-named tag; a tag checks out detached.
Shallow clones remain single-branch, with the existing depth and tag-following behavior and narrow future-fetch refspec.
`depth` remains opt-in and per repository.

## Git Remote Helper

Containers cloned via `isx branch` are isolated development environments, but developers need a way to get their changes back to the host.
Rather than inventing a custom sync mechanism, incus-spawn integrates with git's native remote helper protocol so standard `git fetch`/`git push`/`git pull` work between host repos and container repos.

**Architecture: bash shim + Java command**

The git remote helper is split into two processes:

1. **`git-remote-isx`** (bash script): installed alongside `isx` in `$PATH`.
   Git discovers it automatically when a remote URL uses the `isx://` scheme.
   The script handles the text-based git remote helper protocol (advertising the `connect` capability), then `exec`s `isx git-remote-helper` to handle the actual transport.

2. **`isx git-remote-helper`** (Java/picocli command): validates the instance is running, validates the requested service against an allowlist, and uses `IncusClient.execBidirectional` to run `<service> '<path>'` inside the container with stdin/stdout forwarded over WebSocket.
   The git pack protocol flows directly between the host git process and the container git process.

The bash `exec` replaces the shell process with the Java process before any data flows through stdin.
This is critical: Java's `BufferedInputStream` would consume bytes from the stdin pipe that are meant for the git pack protocol, corrupting the stream.
By having bash handle only the text protocol exchange (a few short lines) and then `exec`-replacing itself, the Java process inherits the raw file descriptors with no buffered-ahead data.

Stderr is captured in a virtual thread so the command can detect that the path is not a repository ("does not appear to be a git repository", from upload-pack and receive-pack) and print hints listing known repos from the image definition chain.
Whatever runs in the instance decides that stderr, and git passes a helper's stderr to the terminal untouched, so `GuestStderr` relays it a line at a time through `BuildOutput.guestText`, decoded as UTF-8 per line.
The hints quote each repository's path and URL, and the requested path, through `OutputFormat.oneLine` (#1624): a template, project-local included, decides them (#1133).

**URL scheme**

`isx://<instance-name>/<path-to-repo>` where a double slash marks an absolute path and tilde expands to `/home/agentuser`.
For example, `isx://fix-auth//home/agentuser/quarkus` or `isx://fix-auth/~/quarkus`.
Old-style URLs with a single slash before an absolute path (`isx://fix-auth/home/agentuser/quarkus`) are still accepted by the shim and the helper, and matched by cleanup.
The shim strips the double slash, and `isx git-remote-helper` strips one still there (`GitRemoteUtils.containerPath`, #1709).
`isx init` never replaces an installed shim, so a shim from before the double slash forwards `//~/repo`, whose tilde would otherwise not expand.
`GitRemoteShimTest` runs the shim with a stub `isx` for the `//`, `/` and `~/` forms.

**Auto-remote management**

When the user configures `host-paths` (and optionally `repo-paths`) in `config.yaml`, incus-spawn automatically adds and removes git remotes in host repositories:

- **On `isx branch`**: for each repo declared in the image definition chain, resolve the corresponding host repo via `repo-paths` (exact match) or `host-paths` (base directories scanned recursively up to 4 levels deep).
  Direct-child matches take priority; recursive scanning only fires when no direct match exists.
  When the same repo name appears in more than one location, the operation fails with an error instructing the user to add an explicit `repo-paths` entry.
  Verify that any of the host repo's remotes (not just `origin`) match the container repo's URL (protocol-lenient comparison).
  If a match is found, add a remote named after the instance.
- **On `isx destroy`**: scan candidate host repos for any remote with a URL matching `isx://<instance-name>/` and remove it.

`AutoRemoteService` reports its warnings (an existing remote of that name, a failed add) to the `Output` its caller passes, since the TUI renames instances while it draws, and quotes the repository's path or URL through `OutputFormat.oneLine` (#1624).

The removal is stateless — rather than tracking which remotes were added, we scan for `isx://` URLs matching the instance name.
This avoids a class of bugs where state gets out of sync (e.g., the user manually removes a remote, or the add failed silently).

**Protocol-lenient URL matching**

Host repos may use SSH URLs (`git@github.com:org/repo.git`) while container repos use HTTPS (`https://github.com/org/repo.git`).
The URL matcher normalizes both formats by stripping the scheme, `user@` prefix, SSH `:` separator, trailing `.git`, `www.` prefix, and lowercasing.
The result is a canonical form like `github.com/org/repo` that matches regardless of protocol.

## Git remote helper: bash + Java split
The git remote helper is split into a bash shim and a Java command rather than implementing the full protocol in Java.
The reason is stdin buffering: Java's `BufferedInputStream` (used by `System.in` and `ProcessBuilder`) reads ahead into an internal buffer.
In the git remote helper protocol, the initial text exchange ("capabilities", "connect git-upload-pack") is followed by a binary pack protocol stream on the same stdin pipe.
If Java reads even one byte too many during the text phase, the binary stream is corrupted.
The bash shim handles only the text protocol (a few short lines via `read`), then `exec`-replaces itself with the Java process.
The Java process inherits raw file descriptors with no buffered-ahead data and can safely use `execBidirectional` (WebSocket-based stdin/stdout forwarding) to pipe the git pack protocol to the container.

The alternative — implementing the full protocol in Java with careful single-byte reads — is fragile and would need to be re-validated with every JDK update that touches `System.in` buffering behaviour.

## Auto-remote: stateless cleanup vs state tracking
When an instance is destroyed, its git remotes need to be removed from host repos.
Two approaches: (1) track which remotes were added in metadata and remove exactly those, or (2) scan host repos for `isx://` URLs matching the instance name.
We chose stateless scanning because it's simpler and eliminates a class of state-sync bugs (user manually removes a remote, add failed silently, metadata gets corrupted).
The cost is scanning a few git repos on every destroy, which takes milliseconds.

## Auto-remote: opt-in via configuration
Auto-remote management requires explicit `host-paths` or `repo-paths` configuration — we don't scan `~` or `/` to auto-discover repos.
Within configured `host-paths`, subdirectories are scanned recursively up to 4 levels deep, skipping known non-project directories (`.git`, `node_modules`, `target`, `build`, `vendor`, etc.).
This handles the common case where repos are organized in category subfolders (e.g. `~/Code/java/`, `~/Code/go/`).
Direct-child matches take priority over nested matches.
If the same repo name appears in multiple locations, the operation fails with an error instructing the user to add an explicit `repo-paths` entry to disambiguate.
