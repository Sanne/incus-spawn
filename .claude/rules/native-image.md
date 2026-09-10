---
paths:
  - "common/src/main/java/dev/incusspawn/graal/**"
  - "common/src/main/java/dev/incusspawn/Environment.java"
  - "common/src/main/java/dev/incusspawn/RuntimeConstants.java"
  - "common/src/main/java/dev/incusspawn/Platform.java"
  - "cli/src/main/java/dev/incusspawn/RuntimeServices.java"
  - "cli/src/main/resources-filtered/application.properties"
  - "proxy/src/main/resources-filtered/application.properties"
  - "install.sh"
---

# Native Image: Build-Time Initialization

Quarkus initializes application classes **at image-build time** unless they are named in
`--initialize-at-run-time`. Everything reachable from a static initializer is therefore constructed
by the *builder* and snapshotted into the image heap, fields and all. On Linux the builder is **root
inside the GraalVM builder container** (`install.sh` runs native-image through docker/podman, where
`$HOME=/root`), so a field holding an `Environment` path freezes `/root/...` into the binary and
every user's run then fails on a directory they cannot even stat.

**Two classes may hold eagerly resolved host state, and both are on the flag:**

- `RuntimeConstants` (`common`) — `DOWNLOAD_CACHE_DIR`, `SKILLS_CACHE_DIR`, and `CDI_TOOLS` (the
  Java tool setups, which hold a `DownloadCache`).
- `RuntimeServices` (`cli`) — Incus client, background tasks, lock manager, tool-def loader.

`Environment` is on the list too and stays **method-based** on purpose: that is what lets tests
point `user.home` at a temp dir. `Platform` is deliberately *not* on it — its `os.name` lookup is
meant to be constant-folded so platform branches die at build time.

Anywhere else: call the `Environment` method, never store its result.

**Deferring the class that resolves the path is not enough.** GraalVM does not reject a build-time
initializer that touches a run-time-initialized class — it initializes it early and folds the value,
silently (this is what `Environment`'s header comment warns about, and it was measured here: listing
`RuntimeConstants` while leaving the tool-setup list in `ToolDefLoader`'s static initializer still
baked `/root/.cache/incus-spawn/downloads`). The *holder* has to be deferred too, which is why
`CDI_TOOLS` lives in `RuntimeConstants` rather than in `ToolDefLoader`. That regression was commit
08a8ea0 (2026-08-26), which moved the tool-setup list out of the already-deferred `RuntimeServices`
and into `ToolDefLoader`'s static initializer.

The flag is declared in **three** places — each module's `resources-filtered/application.properties`
plus the duplicate list in `cli/pom.xml`'s `macos-native` profile. `NativeImageInitializationTest`
(in `cli`) parses all three and asserts each defers the right classes *and* registers both guards, so
drift fails `mvn test` rather than shipping — a Linux build never exercises the macOS copy.

## Build-time guards (`graal/`)

Registered via `--features=` in both modules' `application.properties`, comma-escaped as `\\,` so
Quarkus does not split the argument:

- **`BakedHostPathFeature`** — registers an object replacer and aborts the build if any `String`,
  `Path` or `File` in the image heap is, or lives under, one of the *builder's* own directories
  (`user.home`, `user.dir`). Precision lives in its `ALLOWED` list (empty; add an entry only with a
  reason), not in a guess about which paths matter — the first version matched only builder paths
  containing `incus-spawn` and would have missed a baked `~/.m2/repository`, `~/.config/incus/`,
  `~/.local/bin/isx` or bare `$HOME`. `user.name` is deliberately not checked: its value is usually
  `root`, too common a substring to match safely. Verified by re-introducing the bug — it reports
  both the `String` and the `UnixPath` (`sun.nio.fs.UnixPath` stores bytes and materializes its
  `String` lazily, so a `String`-only check would have missed it).
- **`SyscallReachabilityFeature`** — meant to keep reachable GraalVM lazy system-property resolvers
  (`user.dir`/`user.home`/`os.name`) off the startup path of short-lived commands. **It cannot
  currently fail**: it resolves `userHomeValue`/`userDirValue`/… on the abstract
  `com.oracle.svm.core.jdk.SystemPropertiesSupport`, while the analysis reaches the concrete
  `PosixSystemPropertiesSupport`/`LinuxSystemPropertiesSupport` overrides, so `isReachable` answers
  `false` for every target regardless. It now prints `??  INCONCLUSIVE` per target rather than a
  reassuring `ok`. Fixing it means targeting the concrete subclasses *and* adding the allowed list
  its javadoc promises — the CLI legitimately reads `user.home` and `user.name` at runtime, so a
  working version fails the build on correct code until those are allowed with a rationale.

Both print a `[isx-…-guard]` report to stderr during the build; check it when touching either.
