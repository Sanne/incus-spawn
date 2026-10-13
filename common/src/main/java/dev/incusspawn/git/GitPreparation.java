package dev.incusspawn.git;

import dev.incusspawn.Environment;
import dev.incusspawn.config.GitCacheConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static dev.incusspawn.git.PreparationGit.*;
import static dev.incusspawn.incus.Container.shellQuote;

/** Standalone full clones selected by the remote, retained as bounded immutable snapshots. */
public final class GitPreparation {
    private static final String POLICY = "isx-clean-full-snapshot-v1";
    static final int MAX_INCREMENTAL_PACKS = 32;
    private final PreparationCache cache;

    public GitPreparation(GitCacheConfig policy) throws IOException {
        this(Environment.cacheDir().resolve("git"), policy);
    }

    public GitPreparation(Path cacheRoot, GitCacheConfig policy) throws IOException {
        cache = new PreparationCache(cacheRoot, policy);
    }

    /** Exact URL (including transport), optional branch/tag, and an optional discovered checkout. */
    public record Request(String url, String branch, Path checkout) {}

    public static final class Prepared implements AutoCloseable {
        private final PreparationCache.Lease lease;
        private final Path repository;
        private final String strategy;
        private final Map<String, String> checksums;
        private final Map<String, Long> timings;

        private Prepared(PreparationCache.Lease lease, Path repository, String strategy,
                         Map<String, String> checksums, Map<String, Long> timings) {
            this.lease = lease;
            this.repository = repository;
            this.strategy = strategy;
            this.checksums = Map.copyOf(checksums);
            this.timings = new LinkedHashMap<>(timings);
        }

        public Path repository() { return repository; }
        public String strategy() { return strategy; }
        /** Relative to the bare repository; checked again on the guest's independent copy. */
        public Map<String, String> checksums() { return checksums; }
        public Map<String, Long> timingsMillis() { return Map.copyOf(timings); }
        public String timingSummary() {
            return timings.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue() + "ms")
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        @Override public void close() throws IOException { lease.close(); }
    }

    public Prepared prepare(Request request, Consumer<String> phase) throws IOException {
        if (request.url() == null || request.url().isBlank() || request.url().contains("\n")) {
            throw new IOException("Invalid Git remote");
        }
        var timings = new LinkedHashMap<String, Long>();
        var key = hash(POLICY + "\n" + request.url());
        var lease = cache.lease();
        boolean success = false;
        try (var ignored = cache.writer(key)) {
            long start = System.nanoTime();
            phase.accept("Reading remote refs");
            // Always authenticate/contact the remote, even on a hit. Never serve a stale offline snapshot.
            var advertised = advertisement(lease.path, request.url());
            elapsed(timings, "remote", start);

            start = System.nanoTime();
            phase.accept("Checking Git snapshot");
            var previous = lease.path.resolve("previous");
            Map<String, String> manifest = null;
            try {
                if (cache.snapshot(key, previous)) manifest = readSnapshot(previous, key);
            } catch (IOException invalid) {
                // Damaged cache bytes or metadata are rebuildable; never use them as a reference.
            }
            var previousRepo = previous.resolve("repo.git");
            if (manifest != null && advertised != null && advertised.equals(selection(previousRepo))) {
                validatePin(previousRepo, request.branch());
                elapsed(timings, "check", start);
                success = true;
                return new Prepared(lease, previousRepo, "cached clean snapshot", objectChecksums(manifest), timings);
            }
            elapsed(timings, "check", start);

            start = System.nanoTime();
            phase.accept("Selecting Git reference");
            Reference reference = manifest == null ? null : new Reference(previousRepo, null);
            var tips = lease.path.resolve("reference-tips");
            boolean retried = false;
            if (reference == null) {
                delete(previous);
                if (request.checkout() != null) {
                    try {
                        reference = hostReference(request.checkout(), request.url(), tips);
                    } catch (IOException unavailable) {
                        if (Thread.currentThread().isInterrupted()) throw unavailable;
                        phase.accept("Host reference unavailable; cloning remote");
                    }
                }
            }
            elapsed(timings, "reference", start);

            var staged = Files.createDirectory(lease.path.resolve("snapshot"));
            var result = staged.resolve("repo.git");
            boolean incremental = false;
            if (manifest != null && advertised != null) {
                try {
                    incremental = refreshSnapshot(request.url(), previousRepo, manifest, advertised,
                            lease.path, result, phase, timings);
                } catch (IOException failedRefresh) {
                    if (Thread.currentThread().isInterrupted()) throw failedRefresh;
                    phase.accept("Incremental Git refresh failed; rebuilding snapshot");
                }
            }
            if (!incremental) {
                // Failed fetches can leave unchecked objects behind. Never borrow from this staging tree.
                delete(result);
                try {
                    cloneSnapshot(request.url(), lease.path, result, reference, phase, timings);
                } catch (IOException failedReference) {
                    if (reference == null || Thread.currentThread().isInterrupted()) throw failedReference;
                    // A live checkout can be repacked, removed or damaged while we borrow from it.
                    // Discard every byte of the failed attempt and retry once with only the real remote.
                    delete(result);
                    reference = null;
                    retried = true;
                    phase.accept("Retrying Git snapshot without reference");
                    cloneSnapshot(request.url(), lease.path, result, null, phase, timings);
                }
            }
            validatePin(result, request.branch());

            start = System.nanoTime();
            // Recreate only the selected refs and pack/index pairs in a minimal bare repository.
            // Discard clone config (including credentials), reflogs and generated object sidecars.
            var clean = lease.path.resolve("prepared.git");
            sanitize(result, clean);
            delete(result);
            Files.move(clean, result);
            manifest = manifest(result);
            Files.writeString(staged.resolve("seal"), seal(key, manifest));
            elapsed(timings, "verify", start);

            start = System.nanoTime();
            phase.accept("Publishing Git snapshot");
            // The lease pins immutable owned files before publication can move/evict the staging tree.
            PreparationCache.copyTree(result, clean, true);
            cache.publishSnapshot(key, staged);
            delete(staged);
            delete(previous);
            Files.deleteIfExists(tips);
            elapsed(timings, "publish", start);
            success = true;
            return new Prepared(lease, clean, incremental ? "incremental clean snapshot"
                    : reference != null ? "reference-seeded clean snapshot"
                    : retried ? "new clean snapshot (reference retry)" : "new clean snapshot",
                    objectChecksums(manifest), timings);
        } finally {
            if (!success) lease.close();
        }
    }

    private static void elapsed(Map<String, Long> timings, String name, long start) {
        timings.merge(name, (System.nanoTime() - start) / 1_000_000, Long::sum);
    }

    private static void init(Path repo, String format) throws IOException {
        Files.createDirectories(repo);
        git(repo, "init", "--bare", "--template=", "--object-format=" + format, ".");
    }

    private record Reference(Path repository, Path tips) {}

    /** Read objects in place; only the matching remote's tracking tips become negotiation hints. */
    private static Reference hostReference(Path checkout, String url, Path tips) throws IOException {
        if (!git(checkout, "rev-parse", "--is-shallow-repository").equals("false")) return null;
        var objects = Path.of(git(checkout, "rev-parse", "--path-format=absolute", "--git-path", "objects"));
        if (Files.exists(objects.resolve("info/alternates"))) return null;
        if (children(objects.resolve("pack")).stream().anyMatch(p -> p.toString().endsWith(".promisor"))) return null;
        var remote = GitRemoteUtils.matchingRemoteName(checkout, url);
        if (remote == null) return null;
        var refs = git(checkout, "for-each-ref", "--format=%(objectname)", "refs/remotes/" + remote + "/");
        Files.writeString(tips, refs.isEmpty() ? "" : refs + "\n");
        return new Reference(checkout.toAbsolutePath(), tips);
    }

    private static void cloneSnapshot(String url, Path work, Path result, Reference reference,
                                      Consumer<String> phase, Map<String, Long> timings) throws IOException {
        long start = System.nanoTime();
        phase.accept("Creating clean Git snapshot");
        try {
            var clone = new ArrayList<String>();
            if (reference != null && reference.tips() != null) {
                // Git appends the alternate's repository path. Ignore it: emitting only captured
                // public tracking tips also overrides a global alternateRefsCommand/prefix policy.
                clone.addAll(List.of("-c", "core.alternateRefsCommand=sh -c 'cat \"$1\"' - "
                        + shellQuote(reference.tips().toString())));
            }
            // A bare clone avoids the commit-graph/checkout bug in older Git's --dissociate.
            // The real remote selects refs; Git's non-thin repack excludes private reference objects.
            clone.addAll(List.of("clone", "--bare", "--no-local", "--template="));
            if (reference != null) clone.addAll(List.of("--reference", reference.repository().toString(), "--dissociate"));
            clone.addAll(List.of("--", url, result.toString()));
            run(work, null, null, clone);
            if (Files.exists(result.resolve("shallow")) || Files.exists(result.resolve("objects/info/alternates"))) {
                throw new IOException("Git snapshot is not a standalone full repository");
            }
            // Small network clones may unpack received objects. Normalize those once; a dissociated
            // reference clone already has its selected pack and must not pay for a second repack.
            if (hasLooseObjects(result)) {
                git(result, "repack", "-a", "-d");
                git(result, "prune-packed");
            }
        } finally {
            elapsed(timings, "clone", start);
        }
        start = System.nanoTime();
        phase.accept("Verifying Git snapshot");
        try {
            git(result, "fsck", "--full", "--no-dangling");
        } finally {
            elapsed(timings, "verify", start);
        }
    }

    /** Extend a verified generation without rewriting or decoding its old packs. */
    private static boolean refreshSnapshot(String url, Path previous, Map<String, String> previousManifest,
                                           String advertised, Path work, Path result,
                                           Consumer<String> phase, Map<String, Long> timings) throws IOException {
        long start = System.nanoTime();
        phase.accept("Fetching incremental Git snapshot");
        try {
            // Only owned immutable packs are linked. Config, HEAD and refs are recreated independently.
            sanitize(previous, result);
            git(result, "-c", "fetch.fsckObjects=true", "-c", "fetch.unpackLimit=1",
                    "fetch", "--atomic", "--prune", "--no-tags", "--no-write-fetch-head",
                    "--no-auto-maintenance", "--no-write-commit-graph", "--no-recurse-submodules",
                    "--", url, "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*", "+HEAD:refs/isx/remote-head");
        } finally {
            elapsed(timings, "fetch", start);
        }
        start = System.nanoTime();
        phase.accept("Verifying incremental Git snapshot");
        try {
            var advertisedParts = advertised.split("\n", 4);
            // A detached remote HEAD need not be reachable from any branch or tag. Fetch it too,
            // recording its actual fetched ID so a concurrent HEAD update cannot pass selection checks.
            var fetchedHead = git(result, "rev-parse", "--verify", "refs/isx/remote-head");
            git(result, "update-ref", "-d", "refs/isx/remote-head");
            Files.writeString(result.resolve("HEAD"), advertisedParts[1] + "\n");
            if (!fetchedHead.equals(advertisedParts[2]) || !advertised.equals(selection(result))) {
                phase.accept("Rebuilding Git snapshot: remote refs changed during fetch");
                return false;
            }
            if (Files.exists(result.resolve("shallow")) || Files.exists(result.resolve("objects/info/alternates"))
                    || hasLooseObjects(result)) throw new IOException("Incremental Git snapshot is not standalone and packed");
            var packs = children(result.resolve("objects/pack")).stream()
                    .filter(p -> p.toString().endsWith(".pack")).toList();
            if (packs.size() > MAX_INCREMENTAL_PACKS) {
                phase.accept("Compacting Git snapshot");
                return false;
            }
            // Reuse the old object validation only for exactly the sealed bytes. Check new packs and
            // indexes independently, including object structure and links into the verified old store.
            for (var entry : objectChecksums(previousManifest).entrySet()) {
                if (!entry.getValue().equals(hash(result.resolve(entry.getKey())))) {
                    throw new IOException("Retained Git snapshot bytes changed");
                }
            }
            for (var pack : packs) {
                if (!previousManifest.containsKey("objects/pack/" + pack.getFileName())) {
                    git(result, "index-pack", "--verify", "--strict", pack.toString());
                }
            }
            if (!retainsHistory(previous, result, work)) {
                phase.accept("Rebuilding Git snapshot: selected history was removed");
                return false;
            }
            return true;
        } finally {
            elapsed(timings, "verify", start);
        }
    }

    /** Every formerly selected root must remain reachable, including annotated tag objects and HEAD. */
    private static boolean retainsHistory(Path previous, Path result, Path work) throws IOException {
        var oldRefs = refMap(previous);
        var newRefs = refMap(result);
        var retained = new HashSet<>(newRefs.values());
        for (var entry : oldRefs.entrySet()) {
            var old = entry.getValue();
            var next = newRefs.get(entry.getKey());
            if (!retained.contains(old) && next != null && entry.getKey().startsWith("refs/heads/")
                    && git(result, "merge-base", old, next).equals(old)) retained.add(old);
        }
        var remaining = new HashSet<>(oldRefs.values());
        var oldSelection = selection(previous);
        if (oldSelection != null) remaining.add(oldSelection.split("\n", 4)[2]);
        remaining.removeAll(retained);
        if (remaining.isEmpty()) return true;
        // Deleting a merged branch is safe; deleting an unmerged one or an annotated tag may not be.
        // Stream a potentially large difference to disk instead of capturing it in the JVM heap.
        var input = work.resolve("retained-tips");
        var output = work.resolve("removed-objects");
        try {
            Files.write(input, remaining);
            run(result, input, output, List.of("rev-list", "--objects", "--no-object-names", "--stdin", "--not", "--all"));
            return Files.size(output) == 0;
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    /** Canonical heads, tags, HEAD target and inferred object format. Unborn/ambiguous HEADs cannot hit. */
    private static String advertisement(Path work, String url) throws IOException {
        var refs = new TreeMap<String, String>();
        String head = null;
        var output = git(work, "ls-remote", "--symref", "--", url, "HEAD", "refs/heads/*", "refs/tags/*");
        for (var line : output.lines().toList()) {
            var parts = line.split("\t", 2);
            if (parts.length != 2) throw new IOException("Invalid Git advertisement");
            if (parts[0].startsWith("ref: ")) {
                if (parts[1].equals("HEAD")) head = parts[0];
            } else if (!parts[1].endsWith("^{}")) {
                if (!parts[0].matches("[0-9a-f]{40}(?:[0-9a-f]{24})?")) throw new IOException("Invalid Git object ID");
                refs.put(parts[1], parts[0]);
            }
        }
        var oid = refs.remove("HEAD");
        if (oid == null) return null; // ls-remote cannot describe an unborn remote's HEAD/format reliably.
        return (oid.length() == 64 ? "sha256" : "sha1") + "\n" + (head == null ? oid : head)
                + "\n" + oid + "\n" + refText(refs);
    }

    private static String selection(Path repo) throws IOException {
        var head = Files.readString(repo.resolve("HEAD")).strip();
        var refs = refs(repo);
        String oid = head;
        if (head.startsWith("ref: ")) {
            var prefix = head.substring(5) + " ";
            oid = refs.lines().filter(s -> s.startsWith(prefix)).map(s -> s.substring(prefix.length())).findFirst().orElse(null);
        }
        // An empty snapshot can seed a newly populated remote, but cannot match its advertised HEAD.
        if (oid == null) return null;
        return git(repo, "rev-parse", "--show-object-format") + "\n" + head + "\n" + oid + "\n" + refs;
    }

    private static String refs(Path repo) throws IOException {
        // Use the advertisement's Java ordering too: Git's UTF-8 order differs for supplementary characters.
        return refText(refMap(repo));
    }

    private static Map<String, String> refMap(Path repo) throws IOException {
        var refs = new TreeMap<String, String>();
        for (var line : git(repo, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads/", "refs/tags/")
                .lines().toList()) {
            int space = line.indexOf(' ');
            refs.put(line.substring(0, space), line.substring(space + 1));
        }
        return refs;
    }

    private static String refText(Map<String, String> refs) {
        return refs.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static void validatePin(Path repo, String branch) throws IOException {
        var refs = refs(repo);
        if (branch != null && !branch.isBlank()) {
            // Match clone's branch-before-tag resolution, including an annotated tag's commit.
            var names = refs.lines().map(s -> s.substring(0, s.indexOf(' '))).collect(java.util.stream.Collectors.toSet());
            var name = names.contains("refs/heads/" + branch) ? "refs/heads/" + branch : "refs/tags/" + branch;
            if (!names.contains(name)) throw new IOException("Remote has no requested branch or tag");
            git(repo, "rev-parse", "--verify", name + "^{commit}");
        } else if (!refs.isEmpty()) git(repo, "rev-parse", "--verify", "HEAD^{commit}");
    }

    private static void sanitize(Path source, Path result) throws IOException {
        init(result, git(source, "rev-parse", "--show-object-format"));
        var packs = Files.createDirectories(result.resolve("objects/pack"));
        for (var file : children(source.resolve("objects/pack"))) {
            if (packFile(file)) Files.createLink(packs.resolve(file.getFileName()), file);
        }
        var input = result.resolve("refs.in");
        Files.write(input, refs(source).lines().filter(s -> !s.isEmpty()).map(s -> {
            int space = s.indexOf(' ');
            return "update " + s.substring(0, space) + " " + s.substring(space + 1);
        }).toList());
        run(result, input, null, List.of("update-ref", "--stdin"));
        Files.delete(input);
        Files.writeString(result.resolve("HEAD"), Files.readString(source.resolve("HEAD")));
        git(result, "pack-refs", "--all");
    }

    private static boolean hasLooseObjects(Path repo) throws IOException {
        return children(repo.resolve("objects")).stream().anyMatch(p -> p.getFileName().toString().matches("[0-9a-f]{2}"));
    }

    private static boolean packFile(Path p) {
        return p.getFileName().toString().matches("pack-[0-9a-f]{40}(?:[0-9a-f]{24})?\\.(pack|idx)");
    }

    /** Bind the complete repository, including config, refs, HEAD and all object bytes, to one seal. */
    private static Map<String, String> manifest(Path repo) throws IOException {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(repo)) {
            for (var file : paths.toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("Symbolic link in Git snapshot");
                if (Files.isDirectory(file)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Non-regular Git snapshot file");
                result.put(repo.relativize(file).toString(), hash(file));
            }
        }
        return result;
    }

    private static String seal(String key, Map<String, String> manifest) {
        return hash(key + "\n" + refText(manifest)) + "\n";
    }

    private static Map<String, String> readSnapshot(Path snapshot, String key) throws IOException {
        var seal = snapshot.resolve("seal");
        if (!Files.isRegularFile(seal) || Files.size(seal) != 65) return null;
        var manifest = manifest(snapshot.resolve("repo.git"));
        return Files.readString(seal).equals(seal(key, manifest)) ? manifest : null;
    }

    private static Map<String, String> objectChecksums(Map<String, String> manifest) {
        var objects = new TreeMap<String, String>();
        manifest.forEach((path, sum) -> { if (path.startsWith("objects/")) objects.put(path, sum); });
        return objects;
    }
}
