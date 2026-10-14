# Why full clones use bare dissociation

Historical findings from [#1396](https://github.com/Sanne/incus-spawn/issues/1396), retained after [#1401](https://github.com/Sanne/incus-spawn/pull/1401) selected incremental clean snapshots.
The current behavior, decision table and production measurements live in [Git preparation](GIT-PREPARATION.md).

## Why the project moved away from dissociation

[PR #67](https://github.com/Sanne/incus-spawn/pull/67) delayed dissociation until after checkout to work around an `unable to parse commit` failure.
[Commit d7cba982](https://github.com/Sanne/incus-spawn/commit/d7cba9825efb1fe32b869d5c7649f85a2c0f2659) later switched to copying the host object store to avoid repeated repacking.
Whole-store copying also carried private host objects into guests, requiring a selected-object repack in [#1368](https://github.com/Sanne/incus-spawn/pull/1368).
That restored the packing cost after first copying unwanted objects.

## The checkout failure and its reproducer

Git's dissociation could close the reference object database while a commit still depended on its commit-graph data for access to its tree.
The subsequent checkout then failed to parse that commit.
[Upstream fix 3d8e4004](https://github.com/git/git/commit/3d8e4004c604b206d70240a087816907cce70a08) reloads the tree from the commit object when graph data is unavailable.
It shipped in [Git 2.55.0](https://github.com/git/git/blob/v2.55.0/Documentation/RelNotes/2.55.0.adoc).
The symptom and workaround match #67, although the original failing repository and logs are unavailable to prove that attribution.

The [reproducer](../bench/git-dissociate-regression.py) creates disposable repositories with a real commit graph.
Its [recorded results](../bench/data/git-dissociate-commit-graph-results-2026-10-13.json) isolate the failure and working alternatives:

| Operation | Git 2.54.0 | Git 2.56.0 |
| --- | --- | --- |
| Reference clone with `--dissociate` and immediate checkout | Exit 128: unable to parse commit | Pass |
| Same, with `core.commitGraph=false` | Pass | Pass |
| Same, with `--no-checkout`, then checkout in another process | Pass | Pass |
| Bare reference clone with `--dissociate` | Pass | Pass |
| Reference clone, then separate repack and alternates removal | Pass | Pass |

Every successful case passed full fsck with the reference moved out of reach; non-bare cases also checked out the expected file.
Bare preparation avoids this failure even on Git 2.54.0.
The guest's later checkout runs in a separate process against an independent repository.

## What the merged design keeps

The remote selects the history; the host checkout supplies objects through `--reference` without being copied or modified.
Bare `--dissociate` preparation writes an independent store containing the selected objects before anything is mounted in the guest.
The transport policy and guest-account checks are specified in the current design.

Dissociation still traverses selected objects and writes a pack and index on each fresh preparation, even when Git reuses compressed objects and deltas.
Retaining a clean snapshot avoids repeating that work on unchanged builds, at the cost of bounded additional disk space.
The merged implementation also refreshes snapshots incrementally when old history remains selected, superseding the research proposal to rebuild on every ref change.
See the [decision table](GIT-PREPARATION.md#when-and-why-we-use-a-clean-snapshot) for rebuilds, shallow clones and fallbacks.

## Reproduction

Run from the repository root with Python 3 and the Git versions to compare:

```shell
python3 bench/git-dissociate-regression.py /path/to/git-2.54.0/git /usr/bin/git > results.json
```

Omit the arguments to use `git` from `PATH`.
A Git source build needs its `git-upload-pack` executable beside `git`.
The script needs no network or Incus and modifies only temporary repositories, which it removes afterward.
It records clone failures in JSON so both affected and fixed Git versions can be compared; inspect the rows rather than treating its exit status as a regression-test verdict.
