# Full Git clone preparation

Full template clones use a bounded cache of clean, independent bare repositories.
A fresh remote advertisement decides whether the cached snapshot can be reused.
When it cannot, Git creates a new snapshot with `clone --bare --no-local --reference … --dissociate`, borrowing objects from the previous clean snapshot or independently pinned host packs.
The guest copies the resulting repository with `--no-hardlinks` and verifies its object bytes before priming.

This is the clean-snapshot alternative to the certificate/bitmap implementation in [PR #1400](https://github.com/Sanne/incus-spawn/pull/1400), addressing [#1395](https://github.com/Sanne/incus-spawn/issues/1395).
The historical dissociation failure and comparative experiments are recorded in [research PR #1398](https://github.com/Sanne/incus-spawn/pull/1398).

## Selection and independence

Every full clone includes all advertised remote branches and tags.
The guest checks out the remote's default HEAD unless a branch or tag was requested.
Branches take precedence over same-named tags; annotated tags are peeled to commits and tag checkouts detach HEAD.
Both prepared and network full clones use this selection and the normal broad future-fetch refspec.
Shallow clones retain their existing transport, depth, narrow refspec and fixup behavior.
Project-local definitions never prepare repositories on the host or mount host references.

A warm hit requires a successful `ls-remote --symref` using the current remote credentials.
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
A hit does no source pinning, object enumeration, repacking or full fsck.

## Creating a snapshot

A valid previous snapshot is the preferred reference when remote refs change.
Otherwise discovery can supply an existing checkout through `host-paths` or `repo-paths`, including a matching upstream beside a fork.
Builds never fetch into, maintain, create or request permission to create user checkouts.
Explicit `update-all` and instance push/pull linking retain their existing behavior.

Only regular pack/index files are copied from a host checkout into private staging.
These are independent copies, never hardlinks to user-owned inodes, so source deletion, GC or in-place writes after pinning cannot change them.
Shallow, alternate-backed and promisor sources are ineligible.
Source refs used for negotiation are limited to the matching remote's tracking refs; local branches and private tags are not advertised as haves.
A loose public tip can use its nearest packed first-parent ancestor, bounded to 64 candidates, without importing user loose objects.
User config, hooks, reflogs, loose objects, alternates, bitmaps and commit graphs are not copied.

The authoritative remote supplies refs to a new bare transport clone.
With a reference, Git's `--dissociate` repacks the selected objects into a standalone, non-thin store and removes the alternate.
This rewrites mixed public/private packs, including public objects whose stored delta base is private.
The fresh-clone approach also drops objects retained only by deleted branches or moved tags; fetching and pruning refs in an old snapshot would leave those objects behind.
Without a reference, a normal bare transport clone supplies the objects; small clones that unpack received objects are packed once.
Fresh output must be full and independent and pass `git fsck --full`.
Only its pack/index pairs and freshly installed heads, tags and HEAD enter the final minimal bare repository, discarding clone configuration and generated sidecars.

Using `--bare` avoids the historical in-process checkout failure in older Git's `--dissociate` path.
[Upstream commit 3d8e4004](https://github.com/git/git/commit/3d8e4004c604b206d70240a087816907cce70a08) fixes the commit-graph lifetime bug; the research reproduces it on Git 2.54.0 and verifies bare dissociation there.
The guest's later local clone checks out in a separate Git process from a repository containing no borrowed graph metadata.

## Retention and readers

The cache lives under `~/.cache/incus-spawn/git/`, private to the host user.
It retains one immutable snapshot per exact URL, whether or not a host checkout exists.
That extra clean payload is the deliberate space tradeoff for eliminating pack certificates, closure proofs and owned bitmap caches.

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

Writers serialize through 256 stable URL-keyed lock files, which are never unlinked while another process might have them open.
A short catalogue lock protects publication, reader pinning and eviction across processes.
Publication atomically moves a complete generation into place; a new clone never mutates the previous generation.
Readers pin owned immutable files through hardlinks in their own leased work directories before eviction can remove a generation.
Kernel-backed leases protect active work across processes, and the next preparation reclaims work left by dead processes.
The lease lasts through guest copying and device detachment, including a retained lease when detachment fails.

Only the prepared bare repository is mounted read-only into the guest.
The guest uses `clone --no-hardlinks`, sets the real origin, rejects alternates, and checks the physical object-file count and every expected SHA-256 digest.
Checksum input uses `-c -`, including the explicit stdin filename required on macOS, with `shasum -a 256 -c -` as the fallback.
A preparation, mount or copy-verification failure uses the existing remote fallback before priming.
An already-existing destination is preserved.
No prime runs while any reference remains mounted, retaining the barrier required by shallow host references.

## Validation and measurements

Real-Git tests compare the physical object inventory with the selected remote closure, including mixed private commits, stashes, staged blobs and a forced private delta base.
They cover ref rewrites/deletions, moved tags, default HEAD changes, branch/tag pins, SHA-256 repositories, empty remotes, source writes after pinning, snapshot corruption and an unavailable remote.
Concurrency tests cover independent readers, replacement and eviction, cross-process leases and dead-reader reclamation.
Guest-command tests exercise independent copies, later fetches, corruption fallback and the macOS checksum-input requirement.
Existing shallow, project-local, hotplug, timeout and detach-failure tests remain regression coverage.

`bench/GitPreparationBench.java` runs the production preparation code with a fresh cache and reports cold/warm phase timings.
Measurements and their limits are recorded with the raw dataset linked from `bench/README.md`.
Build diagnostics report remote selection, snapshot validation, pinning, cloning, verification and publication durations alongside guest-copy time.
Credential-bearing command lines are not included in failure diagnostics.

The [production Quarkus measurements](../bench/data/git-clean-snapshots-2026-10-13.json) use Git 2.56.0, four packing threads, loopback transport, tmpfs and a warm filesystem cache.
The fixture contains 1,097,673 selected objects, with private objects both loose and mixed into the host's packs.

| Preparation | Time |
| --- | --- |
| First generation from clean host packs | 20.84 s |
| First generation from mixed host packs | 20.86 s |
| Refresh after one upstream commit | 20.80 s |
| Unchanged snapshot, across those cases | 0.22–0.25 s |

About 13.3 seconds of each new generation is full integrity verification, while a warm request only reads the remote advertisement and verifies owned bytes.
The retained snapshot is about 310 MiB in this fixture.
These are small-sample JVM host measurements excluding Incus, guest copying and priming; #1400's earlier figures used different filesystem conditions and are not a controlled direct comparison.
The choice retains a full clean payload and pays for generation verification in exchange for simpler state and cheap unchanged rebuilds.

Local validation passed 4,479 tests with one skipped, with focused preparation and guest-copy checks passing on Git 2.56.0 and 2.54.0.
A real Incus 6.23 container passed first preparation, cached reuse, independence after detach, full fsck and exact physical object inventory checks.
The nested kernel lacks idmapped mounts, so successful mount checks used a test-only omission of `shift=` with an explicit UID mapping; the unchanged production mount settings separately exercised the network fallback.
macOS, native and VM integration checks remain CI responsibilities for this draft.
