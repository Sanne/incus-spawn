# Git preparation for template builds

This implements the production scope agreed in [#1395](https://github.com/Sanne/incus-spawn/issues/1395#issuecomment-6151612756), following the strategy research in [#1398](https://github.com/Sanne/incus-spawn/pull/1398).
Full clones are prepared on the host before a read-only mount exposes their selected object store to the guest.
Builds neither fetch into nor create user working checkouts for acceleration.
Explicit `update-all` and checkout linking for instance push/pull retain their existing behavior.

## Selection contract

| Property | Full prepared clone and network fallback | Shallow clone |
| --- | --- | --- |
| Initial branches | All advertised remote branches | Requested branch, or remote default |
| Initial tags | Remote tags | Existing depth-limited Git tag-following behavior |
| Checkout | Remote default; requested branch wins over a same-named tag; tags detach HEAD | Existing branch/tag fixup |
| Future fetch | `+refs/heads/*:refs/remotes/origin/*` | Existing narrow branch or tag refspec |
| History | Complete selected closure | Configured depth |

This deliberately makes full-clone scope consistent: previously the local path fetched all branches/tags while the network path initially fetched one branch and widened only its future refspec.
The broader network fallback can transfer more data on its first clone.
Project-local templates continue to clone inside the guest, without host preparation, discovery, or cache access.
Shallow clones keep the existing transport and strip logic; a naive shallow clone with an alternate can follow old remote tags and recover history outside an ordinary depth-limited clone.

## Object preparation

`GitPreparation` starts from the exact remote URL, optional branch/tag, and an optional discovered checkout.
A fresh bare transport clone supplies the authoritative refs and HEAD.
A checkout supplies only independently copied pack/index pairs and temporary negotiation hints; its config, working tree, loose objects, reflogs, hooks, alternates, commit graphs and bitmaps are not imported into the result.
Negotiation hints come from the matching remote's tracking refs, never local branches or private tags; a loose public tip can use a packed ancestor within its first 64 first-parent commits.
Shallow sources, alternate-backed sources and promisor packs are ineligible and use a snapshot or the remote instead.
Missing files, failed commands and failed validation abandon preparation and trigger the guest's normal remote clone.

The copy into owned staging pins the bytes before proof, independently of the user's inodes.
Concurrent GC can make an input disappear, in which case preparation fails closed, but cannot remove a successfully copied input.
An in-place write to the source after copying cannot change the pinned bytes.
Inconsistent pack/index copies fail integrity verification.
No maintenance command runs in the user's checkout.

Cold certification verifies every candidate pack/index pair with `index-pack --verify`, enumerates the selected closure, and compares each pack's object inventory against it.
A separate integrity record binds each validated pack/index pair and object format, so changed remote refs require a new closure proof without decoding unchanged packs again.
A complete pack is reusable only if every object it holds is selected; the accepted packs must also cover the whole closure.
The certificate binds the selection policy, exact URL, requested branch/tag, object format, remote HEAD, full ref vector, and SHA-256 digests of all candidate pack/index contents.
Warm preparation hashes the pinned bytes again before accepting that certificate.
Corrupt certificate contents cannot authorize copying.
Remote advances, rewinds, deleted branches and moved tags change the proof key.

When whole packs cannot cover the selected closure safely, `pack-objects --all --revs --delta-base-offset --use-bitmap-index` writes a new non-thin pack and index directly.
Git must reencode a selected delta whose base is excluded.
MIDX/bitmap acceleration is generated only in owned staging, cached under the selection, pack manifest and Git version, and checked before reuse.
Unavailable bitmap support falls back to selected packing without a bitmap.
The generated pack is not streamed through another receiving `index-pack` pass.

The final bare repository gets newly written refs and config, with no alternate or dependency on the checkout or cache generation.
Only its selected pack/index pairs are shared.
The guest uses `clone --no-hardlinks`, restores the real origin URL, checks the copied pack/index SHA-256 digests and object-directory inventory, and detaches the mount before priming.
Any copy or verification failure discards the partial checkout and clones from the remote.
The shallow path still requires the existing barrier that prevents any prime from running while any reference remains mounted.

## Storage and concurrency

The cache lives under `~/.cache/incus-spawn/git/`.
Its directories and work leases are private to the host user; the final bare directory alone is mounted read-only into the guest.
User checkout files are never hardlinked into the cache.
Owned immutable files may be hardlinked between a cache generation and a reader's private work directory.
Those links pin the reader's inodes even when retention deletes the published generation.

Writers use a bounded set of 256 stable host lock files selected by the URL key.
Colliding keys serialize; lock files are never unlinked while another process could hold an open descriptor.
A short catalogue lock coordinates atomic publication, pinning and retention across processes.
Work directories carry kernel-backed reader leases, including across processes.
The lease remains held through guest copying and device detachment; unsuccessful detachment retains it for the final removal attempt.
Dead-process work is reclaimed on the next preparation, while live leases are skipped.

Without an eligible checkout, preparation can retain one standalone remote-derived snapshot per URL.
There is no additional permanent mutable mirror.
Refresh builds a new selected closure in staging and atomically publishes a complete immutable generation.
Old generations can then be retired, because live readers already pinned independent directory entries.
Deleting refs therefore removes their exclusively reachable objects from the next snapshot; fetch/prune alone would not do this.
With an eligible checkout, only metadata is retained persistently.

```yaml
git-cache:
  metadata-max-mib: 256
  snapshot-max-mib: 1024
  max-age-days: 7
```

These are the defaults; both budgets apply across all repositories.
Entries are evicted by last-use time until the size and age bounds are met.
Retention is checked when preparing or publishing, rather than by a background daemon.
Budgets count logical file bytes; additional ceilings of 1,024 metadata entries and 128 snapshots bound tiny-entry overhead.
An entry larger than its category's entire budget is not retained.
Zero disables retention for that category; zero age disables both categories.
Active work is temporary space in addition to the persistent budgets, retained until its lease ends.
Malformed values use the defaults without preventing the rest of the user's configuration from loading.
`--skip-git-refresh` remains accepted as a compatibility no-op, and builds no longer use `auto-clone-repos`.

## Diagnostics and validation

Build progress identifies pinning, remote selection, certification and selected packing.
Per-repo diagnostics record the phase durations and guest copy/verification duration in milliseconds.
Failures report a network fallback; remote command lines and credential-bearing URLs are not copied into preparation diagnostics.

`GitPreparationTest` executes real Git fixtures for complete and mixed packs, private commits/stashes/staged blobs, a forced private delta base, changed refs, corrupt indexes and certificates, source writes after pinning, standalone snapshots, retention, concurrent writers and readers, and dead-process lease reclamation.
The physical object inventory is compared against the selected remote closure, not merely its visible refs.
`RepoClonerPreparedTest` runs the production preparation and guest shell commands, including detached tag checkout, later fetches after source removal, and corruption fallback before priming.
Checksum checks use `-c -`, supported by GNU and BSD `sha256sum` and the `shasum` fallback; the explicit input file `-` is required by the macOS tool to read the piped manifest.
Tests require explicit standard input and require valid copies to use the prepared path.
The existing shallow, hotplug, mount-timeout, detach-failure and project-local tests remain regression coverage.

The [2026-10-13 measurements](../bench/data/git-preparation-2026-10-13.json) use a 1,097,673-object Quarkus fixture, Git 2.56.0 and a JVM build on Linux.
The recorded source fingerprints identify the measured implementation before the rebase onto #1394 and the checksum-option portability fix.

| Operation | Measured time |
| --- | --- |
| Clean host packs, first integrity/closure proof | 10.06 s |
| Same packs and selection, cached proof | 0.48 s |
| Remote advances one commit, unchanged source packs | 2.90 s |
| Mixed public/private pack, first preparation | 13.99 s |
| Mixed pack with cached proof and owned bitmaps | 2.04–2.11 s |
| Real Incus base, including an identical full-fsck prime | 20.99–22.06 s |
| Real Incus implementation, first proof plus that prime | 24.90 s |
| Real Incus implementation, cached proof plus that prime | 15.14–15.21 s |

Cold integrity validation is a deliberate cost; the improvement is strongest when packs or metadata can be reused.
The nested host cannot provide the requested idmapped mount, so both real-Incus comparison paths used the same test-only omission of `shift=` and an explicit UID mapping.
The production mount failure also exercised the normal network fallback successfully.
Production mount settings were not changed for that measurement.
The preparation and guest-copy tests also passed against Git 2.39.5, including SHA-256 repositories and the private delta-base fixture.
macOS, VM hotplug and native-image execution were not available for local validation; their CI checks remain necessary before merging.
