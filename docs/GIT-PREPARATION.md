# Full Git clone preparation

Full template clones use a bounded cache of clean, independent bare repositories.
A fresh remote advertisement decides whether the cached snapshot can be reused.
When upstream changes preserve the selected history, a new generation reuses verified packs and fetches the additional objects.
Initial snapshots and updates that remove selected history use `clone --bare --no-local --reference … --dissociate`, borrowing objects from the previous clean snapshot or the user's existing checkout directly.
The guest copies the resulting repository with `--no-hardlinks` and verifies its object bytes before priming.

This is the clean-snapshot alternative to the certificate/bitmap implementation in [PR #1400](https://github.com/Sanne/incus-spawn/pull/1400), addressing [#1395](https://github.com/Sanne/incus-spawn/issues/1395).
The historical dissociation failure and comparative experiments are recorded in [research PR #1398](https://github.com/Sanne/incus-spawn/pull/1398).

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
SSH defaults to `ssh -o BatchMode=yes` unless the user supplied `GIT_SSH_COMMAND` or `GIT_SSH`.

Before preparing a full snapshot, the guest runs `ls-remote --symref` with the template's own accounts.
All such checks finish before any prime runs, so primes cannot interfere with another repository's authorization check.
Denied access skips host preparation and uses the ordinary guest clone path, which must obtain its own access.
The guest's complete advertised selection must match the prepared snapshot before any prepared bytes are mounted.
A different account-visible ref scope or a race that changes selection uses the guest's network clone instead; a successful host credential check alone never authorizes a guest copy.
This applies to warm cache hits as well as new generations.

A warm host-cache hit additionally requires a successful `ls-remote --symref` using the host's current remote credentials.
The exact URL and selection policy identify the cache entry.
All heads and tags, the default HEAD target and object ID, and the object format inferred from advertised IDs must match the verified snapshot.
Different branch/tag pins share the same complete snapshot; each pin is validated separately.
Unborn or ambiguous HEAD advertisements cannot produce a hit, since `ls-remote` does not reliably describe their target and object format.
A remote failure does not authorize an offline snapshot.
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
After successful dissociation, verification, publication and guest copying no longer access the user checkout.

The authoritative remote supplies refs to a new bare transport clone.
With a reference, Git's `--dissociate` repacks the selected objects into a standalone, non-thin store and removes the alternate.
This rewrites mixed public/private packs, including public objects whose stored delta base is private.
The fresh-clone approach also drops objects retained only by deleted branches or moved tags; fetching and pruning refs in an old snapshot would leave those objects behind.
Without a reference, a normal bare transport clone supplies the objects; small clones that unpack received objects are packed once.
Initial and rebuilt output must be full and independent and pass `git fsck --full`.
Only its pack/index pairs and freshly installed heads, tags and HEAD enter the final minimal bare repository, discarding clone configuration and generated sidecars.

Using `--bare` avoids the historical in-process checkout failure in older Git's `--dissociate` path.
[Upstream commit 3d8e4004](https://github.com/git/git/commit/3d8e4004c604b206d70240a087816907cce70a08) fixes the commit-graph lifetime bug; the research reproduces it on Git 2.54.0 and verifies bare dissociation there.
The guest's later local clone checks out in a separate Git process from a repository containing no borrowed graph metadata.

## Retention and readers

The cache lives under `~/.cache/incus-spawn/git/`, private to the host user.
It retains one immutable snapshot per exact URL, whether or not a host checkout exists.
That extra clean payload is the deliberate space tradeoff for eliminating per-pack certificates and owned bitmap caches.

```yaml
git-cache:
  snapshot-max-mib: 1024
  max-age-days: 7
```

These defaults bound retained logical file bytes across all repositories, including each snapshot's metadata.
Least-recently-used entries expire on preparation/publication until the size, age and 128-entry bounds hold.
There is no background eviction daemon.
A snapshot larger than the entire budget is usable for its current build but is not retained.
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
Checksum input uses `-c -`, including the explicit stdin filename required on macOS, with `shasum -a 256 -c -` as the fallback.
A preparation, mount or copy-verification failure uses the existing remote fallback before priming.
An already-existing destination is preserved.
No prime runs while any raw shallow checkout remains planned or mounted, retaining the host-private-object barrier.
Each prepared clone detaches its own clean snapshot before priming; other clean preparations and copies can continue concurrently.

## Validation and measurements

Real-Git tests compare the physical object inventory with the selected remote closure, including mixed private commits, stashes, staged blobs and a forced private delta base.
They cover ref rewrites/deletions, moved tags, default HEAD changes, branch/tag pins, SHA-256 repositories, empty remotes, source removal, corruption and pruning while borrowing, integrity-failure retry, snapshot corruption and an unavailable remote.
Incremental tests cover repeated SHA-1/SHA-256 updates, unchanged pack inodes, independent old readers and guest copies, new branches/tags, detached remote HEADs, merged branch deletion, removed history, advertisement races, new/retained pack corruption, failed fetches, cancellation and compaction.
Concurrency tests cover independent readers, replacement and eviction, cross-process leases and dead-reader reclamation.
Guest-command tests exercise independent copies, later fetches, corruption fallback and the macOS checksum-input requirement.
Existing shallow, project-local, hotplug, timeout and detach-failure tests remain regression coverage.

`bench/GitPreparationBench.java` runs the production preparation code with a fresh cache and reports cold/warm phase timings.
Measurements and their limits are recorded with the raw dataset linked from `bench/README.md`.
Build diagnostics report guest access checks, remote selection, snapshot validation, reference selection, incremental fetching, cloning, verification and publication durations alongside guest-copy time.
Credential-bearing command lines are not included in failure diagnostics.

The [incremental Quarkus measurements](../bench/data/git-incremental-snapshots-2026-10-13.json) use Git 2.56.0, four packing threads, loopback transport, tmpfs and a warm filesystem cache.
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

The [historical pre-incremental dataset](../bench/data/git-clean-snapshots-2026-10-13.json) recorded 20.80 seconds for a one-commit refresh, with about 13.3 seconds in full integrity verification.
That dataset also includes the former preliminary host-pack copy; source fingerprints identify both measured implementations.
The current approach still retains a full clean payload and hashes its bytes, but ordinary upstream advances no longer repeat full-history packing and object validation.

Before the review changes, local `mvn package` passed 4,511 tests with one skipped and no failures or errors on Git 2.56.0.
Focused preparation, cache, guest-copy and shallow-diagnostics tests pass with Git 2.54.0 as well as Git 2.56.0.
A real Incus 6.23 container passed first preparation, cached reuse and incremental refresh after an upstream update.
Guest checks verified independence after detach, full fsck and exact physical object inventory; the source remained unchanged and no devices were left attached.
The smoke test used an explicit UID mapping in this nested container.
Its cold/warm checks omitted `shift=` in the test harness, and the incremental copy passed with the unchanged production mount options.
Earlier validation also exercised the network fallback when the nested kernel refused a shifted mount.
macOS, native and VM integration checks passed for the preceding revision and run again in CI for this update.
