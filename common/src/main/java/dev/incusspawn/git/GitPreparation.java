package dev.incusspawn.git;

import dev.incusspawn.Environment;
import dev.incusspawn.config.GitCacheConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import static dev.incusspawn.git.PreparationGit.*;

/** Standalone full clones selected by the remote, retained as bounded immutable snapshots. */
public final class GitPreparation {
    private static final String POLICY = "isx-clean-full-snapshot-v1";
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
            var reference = previous.resolve("repo.git");
            if (manifest != null && advertised != null && advertised.equals(selection(reference))) {
                validatePin(reference, request.branch());
                elapsed(timings, "check", start);
                success = true;
                return new Prepared(lease, reference, "cached clean snapshot", objectChecksums(manifest), timings);
            }
            elapsed(timings, "check", start);

            start = System.nanoTime();
            phase.accept("Pinning Git objects");
            boolean borrowed = manifest != null;
            if (!borrowed) {
                delete(previous);
                reference = lease.path.resolve("source.git");
                if (request.checkout() != null) borrowed = pinCheckout(request.checkout(), request.url(), reference);
            }
            elapsed(timings, "pin", start);

            start = System.nanoTime();
            phase.accept("Creating clean Git snapshot");
            var staged = Files.createDirectory(lease.path.resolve("snapshot"));
            var result = staged.resolve("repo.git");
            // A bare clone avoids the commit-graph/checkout bug in older Git's --dissociate.
            // Transport selection and Git's non-thin repack drop every unselected reference object.
            var clone = new ArrayList<>(List.of("clone", "--bare", "--no-local", "--template="));
            if (borrowed) clone.addAll(List.of("--reference", reference.toString(), "--dissociate"));
            clone.addAll(List.of("--", request.url(), result.toString()));
            run(lease.path, null, null, clone);
            if (Files.exists(result.resolve("shallow")) || Files.exists(result.resolve("objects/info/alternates"))) {
                throw new IOException("Git snapshot is not a standalone full repository");
            }
            validatePin(result, request.branch());
            // Small network clones may unpack received objects. Normalize those once; a dissociated
            // reference clone already has its selected pack and must not pay for a second repack.
            if (hasLooseObjects(result)) {
                git(result, "repack", "-a", "-d");
                git(result, "prune-packed");
            }
            elapsed(timings, "clone", start);

            start = System.nanoTime();
            phase.accept("Verifying Git snapshot");
            git(result, "fsck", "--full", "--no-dangling");
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
            delete(lease.path.resolve("source.git"));
            elapsed(timings, "publish", start);
            success = true;
            return new Prepared(lease, clean, borrowed ? "reference-seeded clean snapshot" : "new clean snapshot",
                    objectChecksums(manifest), timings);
        } finally {
            if (!success) lease.close();
        }
    }

    private static void elapsed(Map<String, Long> timings, String name, long start) {
        timings.put(name, (System.nanoTime() - start) / 1_000_000);
    }

    private static void init(Path repo, String format) throws IOException {
        Files.createDirectories(repo);
        git(repo, "init", "--bare", "--template=", "--object-format=" + format, ".");
    }

    /** An independent copy pins bytes, not names or shared inodes, across source GC and in-place writes. */
    private static boolean pinCheckout(Path checkout, String url, Path pinned) throws IOException {
        if (!git(checkout, "rev-parse", "--is-shallow-repository").equals("false")) return false;
        var objects = Path.of(git(checkout, "rev-parse", "--path-format=absolute", "--git-path", "objects"));
        if (Files.exists(objects.resolve("info/alternates"))) return false;
        var sourcePacks = children(objects.resolve("pack"));
        if (sourcePacks.stream().anyMatch(p -> p.toString().endsWith(".promisor"))) return false;
        if (sourcePacks.stream().noneMatch(p -> p.toString().endsWith(".pack"))) return false;
        init(pinned, git(checkout, "rev-parse", "--show-object-format"));
        var target = Files.createDirectories(pinned.resolve("objects/pack"));
        for (var source : sourcePacks) {
            if (!packFile(source)) continue;
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Non-regular source pack");
            Files.copy(source, target.resolve(source.getFileName()));
        }
        // Never advertise local branches or private tags as fetch haves. Only this remote's tracking
        // refs supply negotiation hints; authority still comes from the fresh remote clone.
        var remote = GitRemoteUtils.matchingRemoteName(checkout, url);
        if (remote == null) return true;
        var refs = git(checkout, "for-each-ref", "--format=%(objectname) %(refname)", "refs/remotes/" + remote + "/");
        var input = pinned.resolve("tips.in");
        var output = pinned.resolve("tips.out");
        var lines = refs.isEmpty() ? List.<String>of() : refs.lines().toList();
        Files.write(input, lines.stream().map(s -> s.substring(0, s.indexOf(' '))).toList());
        run(pinned, input, output, List.of("cat-file", "--batch-check=%(objectname) %(objecttype)"));
        var checked = Files.readAllLines(output);
        var commands = new ArrayList<String>();
        for (int i = 0; i < checked.size(); i++) {
            var line = lines.get(i);
            var oid = line.substring(0, line.indexOf(' '));
            if (!checked.get(i).endsWith(" missing")) {
                commands.add("update " + line.substring(line.indexOf(' ') + 1) + " " + oid);
            } else {
                // A small fetch often leaves a public tip loose. Find a packed public ancestor
                // without importing any user loose objects or losing all negotiation on that branch.
                var ancestors = git(checkout, "rev-list", "--first-parent", "--max-count=64", oid, "--");
                Files.writeString(input, ancestors + "\n");
                run(pinned, input, output, List.of("cat-file", "--batch-check=%(objectname) %(objecttype)"));
                var ancestor = Files.readAllLines(output).stream().filter(s -> s.endsWith(" commit")).findFirst();
                if (ancestor.isPresent()) commands.add("update " + line.substring(line.indexOf(' ') + 1)
                        + " " + ancestor.get().split(" ")[0]);
            }
        }
        Files.write(input, commands);
        run(pinned, input, null, List.of("update-ref", "--stdin"));
        Files.delete(input);
        Files.delete(output);
        return true;
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
        return git(repo, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads/", "refs/tags/")
                .lines().sorted().collect(java.util.stream.Collectors.joining("\n"));
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
