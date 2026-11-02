# Full Git clone preparation

Full template clones use a bounded cache of clean, independent bare repositories.
A fresh remote advertisement decides whether the cached snapshot can be reused.
When upstream changes preserve the selected history, a new generation reuses verified packs and fetches the additional objects.
Initial snapshots and updates that remove selected history use `clone --bare --no-local --reference … --dissociate`, borrowing objects from the previous clean snapshot or the user's existing checkout directly.
The guest copies the resulting repository with `--no-hardlinks` and verifies its object bytes before priming.

This is the clean-snapshot alternative to the certificate/bitmap implementation in [PR #1400](https://github.com/Sanne/incus-spawn/pull/1400), addressing [#1395](https://github.com/Sanne/incus-spawn/issues/1395).
The [dissociation regression note](GIT-DISSOCIATE-REVIEW.md) preserves the historical checkout failure, its reproducer and the reason for bare preparation.

## Explicit host sources

A trusted template can declare `host-source: ~/work/repo` instead of `url`, with the same guest `path`, `branch` and `prime` fields.
This is a separate source policy: local branches and tags authorize their complete reachable object closure, including unpushed commits.
Neither remote-tracking refs, stash refs, reflogs, the index nor a detached HEAD add roots to that closure.
A detached HEAD outside it needs an explicit branch or tag selection.
Shallow sources and `depth` are refused, because their missing history cannot be supplied without upstream access.
Project-local templates cannot prepare host sources, including when rebuilding from stored definitions.
Guest-side `file://` URLs retain their existing meaning and never select this path.

`GitPreparation.prepareHost` reads source refs, then packs their captured object IDs through fresh owned metadata with a temporary alternate pointing at the source object directory.
The walk from those object IDs alone decides what enters the export, so no extra source objects enter it.
Stored objects and deltas are reused: without `--thin`, `pack-objects` reuses a delta only when its base is selected too, and otherwise writes the object whole or against a selected object, so a private object never enters as a delta base.
Bitmap selection stays disabled: a bitmap is the source's own record of reachability, and trusting it would let a stale or damaged one add objects that the walk would not select.
Reused bytes are not trusted either: `index-pack --strict` recomputes every object ID, checks its structure and follows every link.
On apache/camel (4.4 million objects) reuse cut the export from 263–300 s to 127–131 s; recomputing every delta had taken 175 s of it (#1548, [measurements](../../bench/data/git-large-repositories-2026-10-19.json)).
Indexing without the source and dropping the fsck (#1557) then brought it to 40–53 s.
The pack is written to a file and the alternate removed before `index-pack` reads it, so every link must resolve inside the export: that proves it self-contained, and no separate fsck runs.
Only the resulting pack/index pair, exported heads/tags and chosen HEAD remain.
Source hooks, configuration, worktree files and reflogs are never copied.
The source is never fetched into or maintained, and no remote advertisement or network fallback is involved.
These exports use the existing preparation lease and guest checksum verification but are ephemeral, with no retained network-cache entry.
Linux and macOS mount only the independent prepared repository, read-only, and detach it before priming.
The guest keeps every exported branch locally and removes the temporary clone remote.
Export, mount, copy or verification failures abort the repository step without contacting an upstream.

The build stamps canonical source paths by guest destination as `host-repositories`, preserving inherited associations when building a child.
Automatic remotes use this association rather than URL matching or the current template file.
A host-owned record under the data directory's `git-remotes/` remembers those paths before adding remotes, so cleanup still works after deleting the instance or its template.
Host-source entries are excluded from `HostRepoRefresh`, including explicit `update-all` refreshes.

## When and why we use a clean snapshot

A user's checkout can contain private branches, stashes and other objects that the template's remote does not expose.
A clean snapshot contains only objects reachable from the selected remote branches, tags and HEAD, with fresh repository metadata and no dependency on the user's checkout.
Keeping this independent bare repository lets later builds reuse the validated objects.

The table applies to repositories being cloned in the current template layer; inherited repositories need no new clone.
Full clones have no depth limit and use snapshots only for eligible network URLs after the guest's template-account access check succeeds.

| Situation | What we do / ready-line label | Why |
| --- | --- | --- |
| Full clone, no usable cached snapshot, eligible host checkout available | Create a snapshot by borrowing directly from the checkout with `--reference … --dissociate`: `via reference-seeded clean snapshot` | Reuse local objects while excluding private history and removing the dependency on the checkout. |
| Full clone, neither a usable cached snapshot nor an eligible reference | Create a snapshot from the remote: `via new clean snapshot` | Establish a validated, independent copy for this build and later reuse. |
| Full clone, cached bytes verify and all advertised refs, HEAD and object format are unchanged | Reuse it: `via cached clean snapshot` | Avoid another full-history clone, repack and fsck; still check current remote access, selection and cached bytes. |
| Full clone, upstream changes preserve all previously selected history | Fetch into a new generation, retaining verified old packs: `via incremental clean snapshot` | Ordinary new commits and merges need only the additional objects and their validation, plus checks of retained bytes and reachability. |
| Full clone, changes remove selected history, incremental validation fails, or the result would exceed 32 packs | Rebuild a clean snapshot, preferably borrowing from the previous verified generation: usually `via reference-seeded clean snapshot` | Drop objects no longer selected, recover from failed validation, or compact accumulated packs. |
| Reference cloning, dissociation or full validation fails | Discard the partial snapshot and retry from the remote: `via new clean snapshot (reference retry)` | A changing or damaged reference must not produce an incomplete guest repository. |
| Shallow clone (`depth: N`, including `depth: 1`) | Bypass this cache: `via host reference (shallow, depth N)` or `via remote (shallow, depth N)` | The separate shallow path reconciles with the remote and repacks on every build; limiting history still requires copying current large binaries. |
| Project-local template, ineligible URL, denied guest access, differing guest/host refs, or preparation/mount/copy-verification failure | Use the ordinary guest clone path | Host preparation must respect the template's trust and access scope; failures must not expose an unchecked snapshot. |

`Cached` means the freshly checked remote selection matches the snapshot; it never means using stale data while the remote is unavailable.
Guest access and selection checks apply to every snapshot, including cache hits.
Frequent upstream commits normally take the incremental row, followed by cached reuse on builds that see no further changes.
A corrupt cached snapshot is discarded and rebuilt without borrowing its damaged bytes.

Every snapshot path still makes and verifies an independent copy for the guest, then detaches the snapshot before priming.
The cache saves host preparation work; it does not eliminate copying the selected objects into each new guest.
Snapshots that exceed the retention budget, or builds with retention disabled, can still use a clean snapshot for the active clone without keeping it for the next build.

## Selection and independence

Every full clone includes all advertised remote branches and tags.
The guest checks out the remote's default HEAD unless a branch or tag was requested.
Branches take precedence over same-named tags; annotated tags are peeled to commits and tag checkouts detach HEAD.
Both prepared and network full clones use this selection and the normal broad future-fetch refspec.
Shallow clones retain their existing transport, depth, narrow refspec and fixup behavior.
They bypass this cache: a shallow host clone is followed by remote reconciliation and repacking the selected objects on every build.
Depth limits historical objects, but current binary contents still need copying; the ready line identifies the shallow depth, and shallow/network clones also report guest and mount timings.
Project-local definitions never prepare repositories on the host or mount host references.

Host preparation accepts HTTPS, SSH (including SCP spelling) and Git network URLs only.
Paths, `file:` URLs and arbitrary remote helpers are rejected before preparation; Git also runs with an explicit protocol allowlist and `protocol.file.allow=never`, covering URL rewrites.
SSH never waits on a prompt; "Creating a snapshot" says how.

Before preparing a full snapshot, the guest runs `ls-remote --symref` with the template's own accounts.
All such checks finish before any prime runs, so primes cannot interfere with another repository's authorization check.
Denied access skips host preparation and uses the ordinary guest clone path, which must obtain its own access.
The host's own `ls-remote` starts beside the guest's check rather than after it, since it transfers no objects (#1934).
A denied guest discards that advertisement, and nothing is fetched, cloned or published until the guest's check succeeded.
The guest's complete advertised selection must match the prepared snapshot before any prepared bytes are mounted.
A different account-visible ref scope or a race that changes selection uses the guest's network clone instead; a successful host credential check alone never authorizes a guest copy.
This applies to warm cache hits as well as new generations.

A warm host-cache hit additionally requires a successful `ls-remote --symref` using the host's current remote credentials.
The exact URL and selection policy identify the cache entry.
All heads and tags, the default HEAD target and object ID, and the object format inferred from advertised IDs must match the verified snapshot.
Different branch/tag pins share the same complete snapshot; each pin is validated separately.
Unborn or ambiguous HEAD advertisements cannot produce a hit, since `ls-remote` does not reliably describe their target and object format.
A remote failure does not authorize an offline snapshot.
The advertisement is read before the cache entry's writer lock, so a concurrent build may publish a newer snapshot meanwhile; that only makes this build's selection miss and refresh.
A cold clone may observe a newer remote state than the preceding advertisement; its snapshot records its actual repository bytes, never the earlier advertisement.

The snapshot carries a SHA-256 seal over its entire file inventory and contents, bound to its URL/policy key.
This covers refs, HEAD, config, pack/index pairs and any added object or alternate files.
Reuse rehashes the owned bytes and checks the seal before invoking Git in that repository.
Damaged snapshots are discarded as references and rebuilt.
The seal detects corruption of owned cache data; it is not an authentication boundary against someone who can rewrite the host user's cache and its seal.
A hit does not access the user checkout or perform object enumeration, repacking or full fsck.

## Incremental updates

A changed branch or tag does not invalidate the object validation of unchanged clean packs.
Preparation first creates separate staging metadata and hardlinks the previous verified generation's owned pack/index files.
It never fetches into a published snapshot or a user's checkout.
An atomic fetch selects all remote heads and tags plus HEAD, prunes obsolete refs and checks incoming objects with `fetch.fsckObjects=true`.
HEAD is fetched into a temporary ref to verify its actual fetched ID, including detached remote HEADs with no branch pointing at them.
Received objects stay packed; automatic maintenance, commit-graph writes and submodule recursion are disabled.
New pack/index pairs additionally pass `index-pack --verify --strict`, including checks of links to existing objects.
Retained pack/index bytes must still match their sealed SHA-256 hashes.

Reusing the old physical object store is safe only if every previously selected root remains reachable from the new selection.
Unchanged root IDs and fast-forward branch ancestry establish the common case without an object walk.
For remaining roots, a streamed reachability difference checks the old object closure against the new heads, tags and HEAD.
This includes annotated tag objects, not just their peeled commits.
A merged branch can be deleted without rebuilding, while an unmerged deletion, destructive force-push or removed annotated tag requires a clean rebuild when it removes objects.
Adding branches/tags and switching the default HEAD also support incremental publication.

Fetched refs and the advertised HEAD must reproduce the fresh advertisement exactly; a mismatch falls back to a fresh clone with its own authoritative selection.
More than 32 packs also triggers a rebuild to compact accumulated incremental packs.
Any failed fetch or incremental validation discards the entire staging repository before rebuilding from the previous snapshot or remote.
Failed staging is never used as a reference: fetch can leave unchecked objects behind when it fails.
Cancellation releases its lease and preserves the interruption instead of retrying.

An incremental generation avoids full-history dissociation and full fsck.
It still hashes retained bytes, checks new objects and reachability, and makes an independent complete copy for a new guest.
The first snapshot and occasional rebuilds retain full validation.

## Creating a snapshot

A valid previous snapshot is the preferred reference when remote refs change.
Otherwise discovery can supply an existing checkout through `host-paths` or `repo-paths`, including a matching upstream beside a fork.
Builds never fetch into, maintain, create or request permission to create user checkouts.
Explicit `update-all` and instance push/pull linking retain their existing behavior.

The user's repository supplies objects in place through `--reference`; no preliminary pack/index copy or hardlink is made.
Both packed and loose objects can be borrowed, including from a loose-only checkout.
Shallow, alternate-backed and promisor sources remain ineligible.
A small staging file records only the matching remote's tracking object IDs for fetch negotiation.
An invocation-scoped `core.alternateRefsCommand` reads that file, preventing local branches, private tags or other remotes' tracking tips from being advertised as haves.
User config, hooks, reflogs, refs and other metadata are never copied into the result.
No part of the user's repository is mounted in the guest for a full clone.

The source can change while it is being read.
Git may recover by downloading objects it can no longer borrow.
If the reference clone, dissociation or full integrity check fails, preparation deletes the entire partial repository and retries once from the real remote without any reference.
An unavailable source during reference selection also uses the remote directly.
Interruption is propagated, and a failed remote retry cannot publish a snapshot.

Contacting the remote (`ls-remote`, the clone, the incremental fetch) is bounded by `git-transfer:` in `config.yaml` (#1343).
A transfer is stopped when it stalls: no output, no I/O and next to no CPU in Git or its helpers for the stall budget, five minutes by default.
It is also stopped at the total limit, three hours by default.
Git and every helper it started are killed and reaped.
A stopped transfer is not retried, whether from a reference or as a fresh clone, since the same remote would stall again.
The build then clones inside the guest, as for any other preparation failure.
While a transfer runs, the build's repository line shows its elapsed time and Git's latest progress line after the repository's name.
Preparation never waits on an SSH prompt.
It adds `-o BatchMode=yes` to the command Git runs, in Git's order: the user's `GIT_SSH_COMMAND`, their global `core.sshCommand`, or plain `ssh` (a `GIT_SSH` program cannot take it); a repository's own does not apply, since preparation runs Git in isx's own repository.
For every SSH command, those the option cannot reach included (`GIT_SSH`, a wrapper), it also sets `SSH_ASKPASS_REQUIRE=force` with `SSH_ASKPASS=false`, so OpenSSH 8.4 and later fails instead of asking.
An empty `GIT_ASKPASS` keeps Git itself from running an askpass dialog for a credential.
When preparation fails, the build warns after its repository lines with isx's reason and explanation, including what it did to SSH when SSH refused, then Git's own output quoted line by line.
Local steps such as `fsck` and `repack` keep a fixed 15-minute limit and no stall budget, and reaching it still allows the retry without a reference.
After successful dissociation, verification, publication and guest copying no longer access the user checkout.

The authoritative remote supplies refs to a new bare transport clone.
With a reference, Git's `--dissociate` repacks the selected objects into a standalone, non-thin store and removes the alternate.
This rewrites mixed public/private packs, including public objects whose stored delta base is private.
The fresh-clone approach also drops objects retained only by deleted branches or moved tags; fetching and pruning refs in an old snapshot would leave those objects behind.
Without a reference, a normal bare transport clone supplies the objects; small clones that unpack received objects are packed once.
Initial and rebuilt output must be full and independent, and pass `git fsck --full` or, cloned without a reference, a strict check of every received object (see below).
Only its pack/index pairs and freshly installed heads, tags and HEAD enter the final minimal bare repository, discarding clone configuration and generated sidecars.

Using `--bare` avoids the historical in-process checkout failure in older Git's `--dissociate` path.
[Upstream commit 3d8e4004](https://github.com/git/git/commit/3d8e4004c604b206d70240a087816907cce70a08) fixes the commit-graph lifetime bug; the research reproduces it on Git 2.54.0 and verifies bare dissociation there.
The guest's later local clone checks out in a separate Git process from a repository containing no borrowed graph metadata.

## Which fresh snapshots get a separate full fsck

On apache/camel (4.4 million objects, #1548) the reference clone takes about 34 s and `fsck --full` another 62 s.
With retention working it runs only on a first build and on rebuilds, but those still matter for large repositories, so the cheaper checks were measured against what they catch ([measurements](../../bench/data/git-large-repositories-2026-10-19.json)).

A reference clone keeps the full fsck.
`--dissociate` repacks the borrowed objects with `pack-objects`, which copies them as stored and names them after the checkout's own index entries or loose file names without rehashing them; only packed entries get a CRC check against the checkout's index.
A checkout whose loose object holds other content under a public object's name therefore produces a clone that succeeds, with one standalone pack serving the wrong content under that name.
Clone's own connectivity check and `fsck --connectivity-only` (8 s on camel) accept that repository; `fsck --full` rejects it and the remote retry follows.
Checking objects on receive (`fetch.fsckObjects`) does not help here: it checks only objects received from the remote, and a reference clone borrows almost all of them.
The test `aCheckoutObjectWhoseBytesDoNotMatchItsNameIsCaughtAfterDissociation` keeps this case covered.
`index-pack --verify --strict` on the single pack would catch it as well, in 43 s against 62 s; it is not adopted (#1553, option (a)).

A clone without a reference, the first build's and the retry's, skips the separate fsck when the transfer has already done its work (#1557).
Every object then arrives over the transport, and with `fetch.fsckObjects=true` `index-pack --strict` computes each ID as it receives it, checks each object's structure and fails on a link that does not resolve; clone's connectivity check covers the refs.
On camel that receive cost nothing measurable (37 s either way), and the separate fsck took 59 s with nothing new to find.
A first snapshot without a reference now takes 33.6–33.7 s there instead of 89.6–90.3 s.
That holds only when every object really arrived that way, so the clone runs with `GIT_TRACE`, and the fsck is skipped only when each `index-pack` it ran had a bare `--strict`, one ran for every pack in the result, and no loose object was written.
Dumb HTTP does not qualify: it indexes the packs it downloads without `--strict` and writes loose objects directly, so a malformed tree passes `fetch.fsckObjects` there and only `fsck --full` rejects it.
Packfile URIs and bundle URIs are indexed with `--fsck-objects`, which does not check links, so they get the full fsck too.
Unpacked objects are loose, so a transfer that `unpack-objects` received gets it as well.
A user's `fetch.fsck.<msg-id>` severities or `fetch.fsck.skipList` turn `--strict` into `--strict=<list>`, which may accept what the fsck rejects, so they keep the fsck too.
`--strict` is also stricter than the fsck: it makes fsck's warnings fatal, such as zero-padded file modes or bad dates in old history.
When `index-pack` rejects an object or the pack under it (Git's own `fatal: fsck error in packed object` or `in pack objects` line), the clone runs once more without it, and the full fsck decides, as it alone did before; a network failure is not retried.
A `remote:` line cannot match, but a server can still trigger that one reclone, by serving history with an fsck warning or, over SSH, where its stderr reaches isx unprefixed, by printing the line; this costs a second download and is safe, because the full fsck decides.
The strict rejection is attached to a later failure as a suppressed exception, which build output does not show.
The trace names the URL, so it is written inside the private preparation lease and deleted right after the clone.

A host-source export follows the same reasoning.
Its pack is indexed with `index-pack --stdin --strict` after the alternate is removed, so a link that only the source could resolve fails there, and the export is self-contained without the fsck that used to prove it (61 s on camel).
Indexing also gets faster, 28 s instead of 50 s on camel: with the source attached every incoming object already exists, and index-pack reads each one back to compare it.

## Retention and readers

The cache lives under `~/.cache/incus-spawn/git/`, private to the host user.
It retains one immutable snapshot per exact URL, whether or not a host checkout exists.
That extra clean payload is the deliberate space tradeoff for eliminating per-pack certificates and owned bitmap caches.

```yaml
git-cache:
  snapshot-max-mib: 4096
  max-age-days: 7
```

These defaults bound retained logical file bytes across all repositories, including each snapshot's metadata.
Least-recently-used entries expire on preparation/publication until the size, age and 128-entry bounds hold.
There is no background eviction daemon.
A snapshot larger than the entire budget is usable for its current build but is not retained.
The default budget fits large repositories such as apache/camel, whose snapshot is about 1.5 GiB (#1548); with 1 GiB it was never kept, and every build took the full clone and fsck.
When a snapshot is not retained, the build warns after its repository lines. With retention disabled the warning names the setting that is `0`; over the budget it gives the size, the limit and the setting to raise, for example `monorepo: Git snapshot (5324.8 MiB) exceeds git-cache.snapshot-max-mib (4096 MiB); it was used for this build but not kept`.
The size is in the setting's unit, rounded up to a tenth of a MiB, so it always reads as larger than the limit.
Snapshots that each fit but not together evict each other: building the repositories in turn then clones and verifies each in full every time (#1554).
So when keeping a snapshot evicts others still within `max-age-days`, the build warns with their count, their size and the setting to raise, for example `docs: Git snapshot kept, but it evicted 1 other recently used snapshot (2500.1 MiB) to fit git-cache.snapshot-max-mib (4096 MiB); raise it, or repositories built in turn are cloned and verified in full every time`.
Evicting an expired snapshot, or replacing a URL's own older generation, is not reported.
Publishing an oversized replacement also retires the old generation for that URL, preventing repeated refreshes from a stale retained base.
Existing readers keep their pinned files.
Setting either limit to zero disables retention; malformed values use the defaults.
Active work occupies temporary space in addition to the retained-cache budget.

Writers serialize through one stable lock file per complete URL key, with a two-hour wait covering long Git operations, validation and retry.
Different URLs never share a writer lock.
Lock files are never unlinked while another process might have them open.
A short catalogue lock protects publication, reader pinning and eviction across processes.
Publication atomically moves a complete generation into place; neither an incremental fetch nor a clean rebuild mutates the previous generation.
Readers pin owned immutable files through hardlinks in their own leased work directories before eviction can remove a generation.
Kernel-backed leases protect active work across processes, and the next preparation reclaims work left by dead processes.
The lease lasts through guest copying and device detachment, including a retained lease when detachment fails.

Only the prepared bare repository is mounted read-only into the guest.
The guest uses `clone --no-hardlinks`, sets the real origin, rejects alternates, and checks the physical object-file count and every expected SHA-256 digest.
On macOS the mount is not shifted, so the guest sees the snapshot owned by the Mac user's unmapped uid.
Git refuses a local clone from a repository another user owns, since 2.45.1 and the maintenance releases back to 2.39.4 (CVE-2024-32004, #1590).
The clone therefore passes `-c safe.directory=<mount path>`.
It trusts exactly that read-only, isx-made snapshot, for that one command.
It never trusts `*` and writes nothing to the guest's config, so the finished template keeps no exception.
The checksums still verify every copied byte.
Shallow references clone with `--no-local` through `upload-pack`, which does not apply this check (checked by hand with Git 2.56).
Checksum input uses `-c -`, including the explicit stdin filename required on macOS, with `shasum -a 256 -c -` as the fallback.
A preparation, mount or copy-verification failure uses the existing remote fallback before priming.
An already-existing destination is preserved.
No prime runs while any raw shallow checkout remains planned or mounted, retaining the host-private-object barrier.
Each prepared clone detaches its own clean snapshot before priming; other clean preparations and copies can continue concurrently.

## Validation and measurements

Preparation tests serve disposable remotes through `git daemon`, exercising the production transport policy without enabling file transport for tests.
The daemon executable must be installed (`git-daemon` on Fedora).
Real-Git tests compare the physical object inventory with the selected remote closure, including mixed private commits, stashes, staged blobs and a forced private delta base, for both clean snapshots and host-source exports.
They cover ref rewrites/deletions, moved tags, default HEAD changes, branch/tag pins, SHA-256 repositories, empty remotes, source removal, corruption and pruning while borrowing, integrity-failure retry, snapshot corruption and an unavailable remote.
Incremental tests cover repeated SHA-1/SHA-256 updates, unchanged pack inodes, independent old readers and guest copies, new branches/tags, detached remote HEADs, merged branch deletion, removed history, advertisement races, new/retained pack corruption, failed fetches, cancellation and compaction.
Concurrency tests cover independent readers, replacement and eviction, cross-process leases and dead-reader reclamation.
Review regressions cover local paths, rewritten file transports, SSH defaults, denied or narrower guest access, writer waits beyond 30 seconds, unrelated URL locks, prime pipelining and oversized replacement with active readers.
Guest-command tests exercise independent copies, later fetches, corruption fallback, the macOS checksum-input requirement and a snapshot owned by another uid.
Existing shallow, project-local, hotplug, timeout and detach-failure tests remain regression coverage.

`bench/GitPreparationBench.java` runs the production preparation code with a fresh cache and reports cold/warm phase timings.
Measurements and their limits are recorded with the raw dataset linked from `bench/README.md`.
Build diagnostics report guest access checks, remote selection, snapshot validation, reference selection, incremental fetching, cloning, verification and publication durations alongside guest-copy time.
Credential-bearing command lines are not included in failure diagnostics.

The [incremental Quarkus measurements](../../bench/data/git-incremental-snapshots-2026-10-13.json) use Git 2.56.0, four packing threads, loopback transport, tmpfs and a warm filesystem cache.
The initial fixture contains 1,097,673 selected objects, with private objects mixed into the host's packs.
Each of the two upstream advances adds one commit without changing its tree; new file contents would add transfer and validation work.

| Preparation | Time |
| --- | --- |
| First generation from mixed host packs | 21.52 s |
| Two successive one-commit upstream advances | 0.80 s |
| Rewind removing the new history, requiring a rebuild | 21.40 s |
| Unchanged snapshot, across those cases | 0.22–0.27 s |

Incremental generations retain the existing pack bytes and add small packs; independent audits confirm the exact selected physical object inventory and full fsck outside the timed preparation.
The retained snapshot is about 310 MiB in this fixture.
These are small-sample JVM host measurements excluding fixture setup, Incus, guest access checks, guest copying and priming.
They are not a controlled direct comparison with #1400's earlier measurements on a different filesystem.

The [historical pre-incremental dataset](../../bench/data/git-clean-snapshots-2026-10-13.json) recorded 20.80 seconds for a one-commit refresh, with about 13.3 seconds in full integrity verification.
That dataset also includes the former preliminary host-pack copy; source fingerprints identify both measured implementations.
The current approach still retains a full clean payload and hashes its bytes, but ordinary upstream advances no longer repeat full-history packing and object validation.

Local `mvn package` passed 4,527 tests with one skipped and no failures or errors on Git 2.56.0.
Focused preparation, cache, guest-copy and shallow-diagnostics tests pass with Git 2.54.0 as well as Git 2.56.0.
A real Incus 6.23 container passed first preparation, cached reuse and incremental refresh after an upstream update.
Guest checks verified independence after detach, full fsck and exact physical object inventory; the source remained unchanged and no devices were left attached.
A guest-only denied-remote fixture also failed against a warm host cache without receiving a checkout or snapshot mount.
The smoke test used an explicit UID mapping in this nested container.
Its cold/warm checks omitted `shift=` in the test harness, and the incremental copy passed with the unchanged production mount options.
Earlier validation also exercised the network fallback when the nested kernel refused a shifted mount.
macOS, native and VM integration checks passed for the preceding revision and run again in CI for this update.
