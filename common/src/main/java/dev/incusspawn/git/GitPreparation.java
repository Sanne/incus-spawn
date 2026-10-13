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
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

import static dev.incusspawn.git.PreparationGit.*;

/**
 * Prepares a standalone, remote-authoritative full clone without mutating a user's checkout.
 * Only a certified complete pack or a non-thin pack of the selected closure may reach the guest.
 * Shallow clones deliberately use RepoCloner's existing transport/fixup path instead.
 */
public final class GitPreparation {
    private static final String POLICY = "isx-full-branches-and-tags-v1";
    private final PreparationCache cache;

    public GitPreparation(GitCacheConfig policy) throws IOException {
        this(Environment.cacheDir().resolve("git"), policy);
    }

    public GitPreparation(Path cacheRoot, GitCacheConfig policy) throws IOException {
        cache = new PreparationCache(cacheRoot, policy);
    }

    /** Exact URL (including transport), optional branch/tag, and an optional discovered checkout. */
    public record Request(String url, String branch, Path checkout) {}

    /** The complete selection, including the remote's HEAD and object format, is part of every proof. */
    record Selection(String url, String branch, String objectFormat, String head, String refs) {
        String identity() {
            return POLICY + "\n" + url + "\n" + (branch == null ? "default" : "pin:" + branch)
                    + "\n" + objectFormat + "\n" + head + "\n" + refs;
        }
    }

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
            phase.accept("Pinning Git objects");
            var pinned = lease.path.resolve("source.git");
            boolean host = request.checkout() != null;
            boolean reference;
            if (host) reference = pinCheckout(request.checkout(), request.url(), pinned);
            else reference = cache.snapshot(key, pinned);
            if (host && !reference) {
                host = false;
                reference = cache.snapshot(key, pinned);
            }
            elapsed(timings, "pin", start);

            start = System.nanoTime();
            phase.accept("Reading remote refs");
            var selected = lease.path.resolve("remote.git");
            var clone = new ArrayList<>(List.of("clone", "--bare", "--no-local", "--template="));
            if (reference) clone.addAll(List.of("--reference", pinned.toString()));
            clone.addAll(List.of("--", request.url(), selected.toString()));
            run(lease.path, null, null, clone);
            if (Files.exists(selected.resolve("shallow"))) throw new IOException("Full preparation received a shallow repository");
            var selection = selection(request, selected);
            elapsed(timings, "remote", start);

            // Collect the received and pinned packs in an owned object directory. Never import user
            // loose objects, config, hooks, reflogs, alternates, bitmaps, commit graphs or private refs.
            var packs = selected.resolve("objects/pack");
            Files.createDirectories(packs);
            if (reference) {
                for (var file : children(pinned.resolve("objects/pack"))) {
                    if (!packFile(file)) continue;
                    var target = packs.resolve(file.getFileName());
                    if (Files.exists(target)) {
                        if (!hash(file).equals(hash(target))) throw new IOException("Conflicting Git pack files");
                    } else Files.createLink(target, file);
                }
            }
            Files.deleteIfExists(selected.resolve("objects/info/alternates"));
            if (hasLooseObjects(selected)) {
                git(selected, "pack-objects", "--all", "--incremental", "--non-empty", "--delta-base-offset",
                        packs.resolve("pack").toString());
            }

            start = System.nanoTime();
            phase.accept("Certifying Git packs");
            var manifest = manifest(packs);
            var proofKey = hash(selection.identity() + "\n" + manifestText(manifest));
            var certificate = lease.path.resolve("certificate");
            Proof proof = null;
            if (cache.metadata("proof-" + proofKey, certificate)) proof = readProof(certificate, proofKey, manifest);
            boolean hit = proof != null;
            if (proof == null) {
                delete(certificate);
                proof = certify(selected, packs, manifest, lease.path);
                Files.createDirectory(certificate);
                writeProof(certificate, proofKey, proof);
                cache.publishMetadata("proof-" + proofKey, certificate);
            }
            elapsed(timings, "certify", start);

            start = System.nanoTime();
            var result = lease.path.resolve("prepared.git");
            init(result, selection.objectFormat());
            var outputPacks = result.resolve("objects/pack");
            Files.createDirectories(outputPacks);
            String strategy;
            if (proof.complete()) {
                phase.accept("Reusing certified Git packs");
                for (var name : proof.packs()) {
                    Files.createLink(outputPacks.resolve(name + ".pack"), packs.resolve(name + ".pack"));
                    Files.createLink(outputPacks.resolve(name + ".idx"), packs.resolve(name + ".idx"));
                }
                strategy = hit ? "certified packs (cached proof)" : "certified packs";
            } else {
                phase.accept("Packing selected Git objects");
                ownedBitmaps(selected, proofKey, lease.path);
                git(selected, "pack-objects", "--all", "--revs", "--delta-base-offset", "--use-bitmap-index",
                        "--non-empty", "--quiet", outputPacks.resolve("pack").toString());
                strategy = "selected objects";
            }
            elapsed(timings, "pack", start);

            start = System.nanoTime();
            installRefs(result, selection);
            for (var file : children(outputPacks)) if (!packFile(file)) delete(file);
            // pack-objects writes a complete pack and its index itself. Do not receive/re-index it
            // a second time; copied input packs were verified before certification.
            var outputManifest = manifest(outputPacks);
            if (proof.complete()) {
                for (var entry : outputManifest.entrySet()) {
                    if (!entry.getValue().equals(manifest.get(entry.getKey()))) {
                        throw new IOException("Certified Git pack changed during preparation");
                    }
                }
            }
            // No mutable mirror: retain one clean snapshot only when a usable checkout wasn't found.
            if (!host) {
                // The selected pack writer already established exact closure membership. Bind that
                // result to its bytes so a refreshed snapshot needn't repeat the cold graph proof.
                var snapshotProofKey = hash(selection.identity() + "\n" + manifestText(outputManifest));
                var snapshotProof = Files.createDirectory(lease.path.resolve("snapshot-proof"));
                writeProof(snapshotProof, snapshotProofKey, new Proof(true, outputManifest.keySet().stream()
                        .filter(n -> n.endsWith(".pack")).map(n -> n.substring(0, n.length() - 5)).toList()));
                cache.publishMetadata("proof-" + snapshotProofKey, snapshotProof);
                var snapshot = lease.path.resolve("snapshot");
                PreparationCache.copyTree(result, snapshot, true);
                cache.publishSnapshot(key, snapshot);
            }
            elapsed(timings, "publish", start);
            var checksums = new TreeMap<String, String>();
            outputManifest.forEach((name, sum) -> checksums.put("objects/pack/" + name, sum));
            // Remove all unselected input before returning a shareable result. Only prepared.git remains.
            delete(selected);
            delete(pinned);
            success = true;
            return new Prepared(lease, result, strategy, checksums, timings);
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

    private static Selection selection(Request request, Path selected) throws IOException {
        var refs = git(selected, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads", "refs/tags");
        if (request.branch() != null && !request.branch().isBlank()) {
            // Match clone's branch-before-tag resolution, including an annotated tag's commit.
            var names = refs.lines().map(s -> s.substring(0, s.indexOf(' '))).collect(java.util.stream.Collectors.toSet());
            var name = names.contains("refs/heads/" + request.branch()) ? "refs/heads/" + request.branch()
                    : "refs/tags/" + request.branch();
            if (!names.contains(name)) throw new IOException("Remote has no requested branch or tag");
            git(selected, "rev-parse", "--verify", name + "^{commit}");
        } else if (!refs.isEmpty()) git(selected, "rev-parse", "--verify", "HEAD^{commit}");
        return new Selection(request.url(), request.branch(), git(selected, "rev-parse", "--show-object-format"),
                Files.readString(selected.resolve("HEAD")).strip(), refs);
    }

    private static boolean hasLooseObjects(Path repo) throws IOException {
        return children(repo.resolve("objects")).stream().anyMatch(p -> p.getFileName().toString().matches("[0-9a-f]{2}"));
    }

    private static boolean packFile(Path p) {
        return p.getFileName().toString().matches("pack-[0-9a-f]{40}(?:[0-9a-f]{24})?\\.(pack|idx)");
    }

    private static Map<String, String> manifest(Path packs) throws IOException {
        var result = new TreeMap<String, String>();
        for (var p : children(packs)) if (packFile(p)) result.put(p.getFileName().toString(), hash(p));
        for (var name : result.keySet()) {
            var base = name.substring(0, name.lastIndexOf('.'));
            if (!result.containsKey(base + ".pack") || !result.containsKey(base + ".idx")) {
                throw new IOException("Missing Git pack/index pair");
            }
        }
        return result;
    }

    private static String manifestText(Map<String, String> manifest) {
        var text = new StringBuilder();
        manifest.forEach((name, sum) -> text.append(sum).append(' ').append(name).append('\n'));
        return text.toString();
    }

    private record Proof(boolean complete, List<String> packs) {}

    private Proof certify(Path selected, Path packs, Map<String, String> manifest, Path scratch) throws IOException {
        verifyPacks(selected, packs, manifest, scratch);
        var closure = scratch.resolve("closure");
        run(selected, null, closure, List.of("rev-list", "--objects", "--all", "--no-object-names"));
        var allowed = new HashSet<String>();
        try (var lines = Files.lines(closure)) { lines.forEach(allowed::add); }
        var missing = new HashSet<>(allowed);
        var accepted = new ArrayList<String>();
        var inventory = scratch.resolve("inventory");
        for (var name : manifest.keySet()) {
            if (!name.endsWith(".idx")) continue;
            run(selected, packs.resolve(name), inventory, List.of("show-index"));
            var ids = new ArrayList<String>();
            boolean safe = true;
            try (var lines = Files.lines(inventory)) {
                for (var it = lines.iterator(); it.hasNext();) {
                    var oid = it.next().split(" ")[1];
                    ids.add(oid);
                    if (!allowed.contains(oid)) safe = false;
                }
            }
            if (safe) {
                accepted.add(name.substring(0, name.length() - 4));
                // AbstractSet.removeAll chooses List.contains when sizes are equal: quadratic on a clean pack.
                for (var id : ids) missing.remove(id);
            }
        }
        Files.delete(closure);
        Files.deleteIfExists(inventory);
        return new Proof(missing.isEmpty(), missing.isEmpty() ? accepted : List.of());
    }

    /** Integrity depends on bytes and object format, independently of the closure eligibility proof. */
    private void verifyPacks(Path repo, Path packs, Map<String, String> manifest, Path scratch) throws IOException {
        var format = git(repo, "rev-parse", "--show-object-format");
        for (var pack : children(packs)) {
            var name = pack.getFileName().toString();
            if (!name.endsWith(".pack")) continue;
            var idx = name.substring(0, name.length() - 5) + ".idx";
            var key = hash("integrity-v1\n" + format + "\n" + manifest.get(name) + "\n" + manifest.get(idx));
            var check = scratch.resolve("integrity-" + key);
            if (cache.metadata("integrity-" + key, check)
                    && Files.isRegularFile(check.resolve("verified"))
                    && Files.readString(check.resolve("verified")).equals(key)) continue;
            git(repo, "index-pack", "--verify", pack.toString());
            delete(check);
            Files.createDirectory(check);
            Files.writeString(check.resolve("verified"), key);
            cache.publishMetadata("integrity-" + key, check);
        }
    }

    private static void writeProof(Path directory, String key, Proof proof) throws IOException {
        var body = "proof-v1\n" + key + "\n" + (proof.complete() ? "copy" : "pack") + "\n"
                + String.join("\n", proof.packs()) + "\n";
        Files.writeString(directory.resolve("proof"), hash(body) + "\n" + body);
    }

    private static Proof readProof(Path directory, String key, Map<String, String> manifest) throws IOException {
        var file = directory.resolve("proof");
        if (!Files.isRegularFile(file) || Files.size(file) > 1024 * 1024) return null;
        var text = Files.readString(file);
        int newline = text.indexOf('\n');
        if (newline < 0 || !hash(text.substring(newline + 1)).equals(text.substring(0, newline))) return null;
        var lines = text.substring(newline + 1).lines().toList();
        if (lines.size() < 3 || !lines.get(0).equals("proof-v1") || !lines.get(1).equals(key)) return null;
        if (!Set.of("copy", "pack").contains(lines.get(2))) return null;
        var packs = lines.stream().skip(3).filter(s -> !s.isEmpty()).toList();
        for (var name : packs) {
            if (!manifest.containsKey(name + ".pack") || !manifest.containsKey(name + ".idx")) return null;
        }
        return new Proof(lines.get(2).equals("copy"), packs);
    }

    private void ownedBitmaps(Path selected, String key, Path scratch) throws IOException {
        var directory = scratch.resolve("bitmaps");
        var packs = selected.resolve("objects/pack");
        // A downgrade must not keep selecting metadata written in a newer Git's format.
        var metadataKey = "bitmap-" + hash(key + "\n" + git(selected, "--version"));
        if (cache.metadata(metadataKey, directory)) {
            if (validBitmaps(directory)) {
                for (var file : children(directory)) {
                    if (!file.getFileName().toString().equals("checksums")) Files.copy(file, packs.resolve(file.getFileName()));
                }
                return;
            }
            delete(directory);
        }
        try {
            git(selected, "multi-pack-index", "write", "--bitmap");
        } catch (IOException unsupported) {
            // Older Git, too few commits or an unsupported object format: selection still works without a bitmap.
            for (var file : children(packs)) if (file.getFileName().toString().startsWith("multi-pack-index")) Files.delete(file);
            return;
        }
        Files.createDirectories(directory);
        var sums = new TreeMap<String, String>();
        for (var file : children(packs)) {
            if (!file.getFileName().toString().startsWith("multi-pack-index")) continue;
            Files.copy(file, directory.resolve(file.getFileName()));
            sums.put(file.getFileName().toString(), hash(file));
        }
        Files.writeString(directory.resolve("checksums"), manifestText(sums));
        cache.publishMetadata(metadataKey, directory);
    }

    private static boolean validBitmaps(Path directory) throws IOException {
        var sums = directory.resolve("checksums");
        if (!Files.isRegularFile(sums)) return false;
        int count = 0;
        for (var line : Files.readAllLines(sums)) {
            var parts = line.split(" ", 2);
            if (parts.length != 2 || !parts[0].matches("[0-9a-f]{64}")
                    || !parts[1].matches("multi-pack-index(?:-[0-9a-f]+\\.bitmap)?")) return false;
            var file = directory.resolve(parts[1]);
            if (!Files.isRegularFile(file) || !parts[0].equals(hash(file))) return false;
            count++;
        }
        return count >= 2 && children(directory).size() == count + 1;
    }

    private static void installRefs(Path result, Selection selection) throws IOException {
        var input = result.resolve("refs.in");
        Files.write(input, selection.refs().lines().filter(s -> !s.isEmpty()).map(s -> {
            int space = s.indexOf(' ');
            return "update " + s.substring(0, space) + " " + s.substring(space + 1);
        }).toList());
        run(result, input, null, List.of("update-ref", "--stdin"));
        Files.delete(input);
        Files.writeString(result.resolve("HEAD"), selection.head() + "\n");
        git(result, "pack-refs", "--all");
    }
}
