package dev.incusspawn.git;

import dev.incusspawn.Environment;
import dev.incusspawn.config.GitCacheConfig;
import dev.incusspawn.config.GitTransferConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.incusspawn.git.PreparationGit.*;
import static dev.incusspawn.incus.Container.shellQuote;

/** Standalone full clones selected by the remote, retained as bounded immutable snapshots. */
public final class GitPreparation {
    private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern SCP_LIKE = Pattern.compile("(?:[^/@:]+@)?(?:\\[[0-9a-fA-F:]+\\]|[^/@:]+):[^:].*");
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}(?:[0-9a-f]{24})?");
    private static final Pattern LOOSE_OBJECT_DIR = Pattern.compile("[0-9a-f]{2}");
    private static final Pattern PACK_FILE = Pattern.compile("pack-[0-9a-f]{40}(?:[0-9a-f]{24})?\\.(pack|idx)");
    private static final String POLICY = "isx-clean-full-snapshot-v1";
    static final int MAX_INCREMENTAL_PACKS = 32;
    private static final String REFRESHED = " (refreshed)";
    private final PreparationCache cache;
    private final GitTransferConfig transfer;

    public GitPreparation(GitCacheConfig policy, GitTransferConfig transfer) throws IOException {
        this(Environment.cacheDir().resolve("git"), policy, transfer);
    }

    /** {@code transfer} bounds the network commands (ls-remote, clone, fetch); local ones keep {@link PreparationGit#LOCAL}. */
    public GitPreparation(Path cacheRoot, GitCacheConfig policy, GitTransferConfig transfer) throws IOException {
        cache = new PreparationCache(cacheRoot, policy);
        this.transfer = transfer;
    }

    /** Where preparation reports its phases, and the progress of a transfer running under one. */
    public interface Phases extends Consumer<String> {
        /** {@code detail} is the transfer's elapsed time and Git's latest progress line. */
        default void progress(String phase, String detail) {}
    }

    /** Exact URL (including transport), optional branch/tag, and an optional discovered checkout. */
    public record Request(String url, String branch, Path checkout) {}

    public static final class Prepared implements AutoCloseable {
        private final PreparationCache.Lease lease;
        private final Path repository;
        private final String strategy;
        private final Map<String, String> checksums;
        private final Map<String, Long> timings;
        private final String notRetained;

        private Prepared(PreparationCache.Lease lease, Path repository, String strategy,
                         Map<String, String> checksums, Map<String, Long> timings, String notRetained) {
            this.lease = lease;
            this.repository = repository;
            this.strategy = strategy;
            this.checksums = Map.copyOf(checksums);
            this.timings = new LinkedHashMap<>(timings);
            this.notRetained = notRetained;
        }

        public Path repository() { return repository; }
        public String strategy() { return strategy; }
        /** The guest must independently see exactly this selection before any prepared bytes are mounted. */
        public boolean matchesAdvertisement(String advertisement) throws IOException {
            var visible = parseAdvertisement(advertisement);
            return visible != null && visible.equals(selection(repository));
        }
        /** Relative to the bare repository; checked again on the guest's independent copy. */
        public Map<String, String> checksums() { return checksums; }
        public Map<String, Long> timingsMillis() { return Map.copyOf(timings); }
        /** Whether this snapshot or others it evicted were not kept for later builds, why, and what to change; null when none was lost. */
        public String notRetained() { return notRetained; }
        public String timingSummary() {
            return timings.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue() + "ms")
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        @Override public void close() throws IOException { lease.close(); }
    }

    /** Host preparation accepts network transports only, never paths or arbitrary remote helpers. */
    public static boolean isRemoteUrl(String url) {
        if (url == null || url.isBlank() || url.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) return false;
        if (url.contains("://")) {
            try {
                var uri = java.net.URI.create(url);
                return java.util.Set.of("https", "ssh", "git").contains(uri.getScheme())
                        && uri.getHost() != null && !uri.getHost().isBlank();
            } catch (IllegalArgumentException e) { return false; }
        }
        // SCP-style SSH: a slash before the colon denotes a path, and drive letters are paths too.
        return !url.startsWith("file:") && !DRIVE_LETTER.matcher(url).matches() && !url.startsWith("-")
                && SCP_LIKE.matcher(url).matches();
    }

    /**
     * The remote's refs as the host's credentials see them, and the lease preparation continues in.
     * Reading them transfers no objects, so it may start before the guest's access check ends.
     */
    public static final class RemoteRefs implements AutoCloseable {
        private final PreparationCache.Lease lease;
        private final String url;
        private final String advertised;
        private final long millis;
        private boolean taken;

        private RemoteRefs(PreparationCache.Lease lease, String url, String advertised, long millis) {
            this.lease = lease;
            this.url = url;
            this.advertised = advertised;
            this.millis = millis;
        }

        private synchronized PreparationCache.Lease take() throws IOException {
            if (taken) throw new IOException("Remote refs were already used");
            taken = true;
            return lease;
        }

        /** Discards refs never handed to {@link #prepare(Request, RemoteRefs, Phases)}. */
        @Override public synchronized void close() throws IOException {
            if (!taken) {
                taken = true;
                lease.close();
            }
        }
    }

    /** Always authenticate/contact the remote, even on a hit: never serve a stale offline snapshot. */
    public RemoteRefs readRemote(String url, Phases phase) throws IOException {
        if (!isRemoteUrl(url)) throw new IOException("Git preparation requires an HTTPS, SSH or Git remote");
        var lease = cache.lease();
        boolean success = false;
        try {
            long start = System.nanoTime();
            var refs = new RemoteRefs(lease, url, advertisement(lease.path, url, phase), (System.nanoTime() - start) / 1_000_000);
            success = true;
            return refs;
        } finally {
            if (!success) lease.close();
        }
    }

    public Prepared prepare(Request request, Phases phase) throws IOException {
        return prepare(request, readRemote(request.url(), phase), phase);
    }

    /** Continues from {@code remote}, which it takes over whatever the outcome; it must have read {@code request}'s URL. */
    public Prepared prepare(Request request, RemoteRefs remote, Phases phase) throws IOException {
        var lease = remote.take();
        boolean success = false;
        try {
            if (!remote.url.equals(request.url())) throw new IOException("Remote refs were read for another URL");
            var prepared = prepare(request, lease, remote.advertised, remote.millis, phase);
            success = true;
            return prepared;
        } finally {
            if (!success) lease.close();
        }
    }

    private Prepared prepare(Request request, PreparationCache.Lease lease, String advertised, long remoteMillis,
                             Phases phase) throws IOException {
        var timings = new LinkedHashMap<String, Long>();
        timings.put("remote", remoteMillis);
        var key = hash(POLICY + "\n" + request.url());
        try (var ignored = cache.writer(key)) {
            long start = System.nanoTime();
            phase.accept("Checking Git snapshot");
            var previous = lease.path.resolve("previous");
            Map<String, String> manifest = null;
            try {
                if (cache.snapshot(key, previous)) manifest = readSnapshot(previous, key);
            } catch (IOException invalid) {
                // Damaged cache bytes or metadata are rebuildable; never use them as a reference.
            }
            var previousRepo = previous.resolve("repo.git");
            boolean hit = manifest != null && advertised != null && advertised.equals(selection(previousRepo));
            elapsed(timings, "check", start);
            if (manifest != null && !hit) {
                // The early read may predate the access barrier, the limiter wait and this writer lock. A push
                // since then would fail the refresh's selection check and rebuild in full, so read again first.
                // A concurrent build may also have published exactly what it reads now.
                start = System.nanoTime();
                advertised = advertisement(lease.path, request.url(), phase);
                elapsed(timings, "remote", start);
                hit = advertised != null && advertised.equals(selection(previousRepo));
            }
            if (hit) {
                validatePin(previousRepo, request.branch());
                return new Prepared(lease, previousRepo, "cached clean snapshot", objectChecksums(manifest), timings, null);
            }

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
                    // A stalled remote stalls a full clone too: fail now and let the build clone in the guest.
                    if (Thread.currentThread().isInterrupted() || failedRefresh instanceof TransferStopped) throw failedRefresh;
                    phase.accept("Incremental Git refresh failed; rebuilding snapshot");
                }
            }
            if (!incremental) {
                // Failed fetches can leave unchecked objects behind. Never borrow from this staging tree.
                delete(result);
                try {
                    cloneSnapshot(request.url(), lease.path, result, reference, phase, timings);
                } catch (IOException failedReference) {
                    if (reference == null || Thread.currentThread().isInterrupted()
                            || failedReference instanceof TransferStopped) throw failedReference;
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
            delete(previous);
            Files.deleteIfExists(tips);
            return publish(key, lease, staged, incremental ? "incremental clean snapshot"
                    : reference != null ? "reference-seeded clean snapshot"
                    : retried ? "new clean snapshot (reference retry)" : "new clean snapshot",
                    timings, phase);
        }
    }

    /**
     * Bring {@code prior} up to {@code guestAdvertisement}, the guest's latest {@code ls-remote}, with an
     * incremental fetch from its own leased bytes, whether or not the cache kept it (#1980).
     * The host fetches only when its own advertisement equals the guest's, so a result matches that reading
     * by construction, and a guest whose accounts see a different ref scope never costs a fetch.
     * Returns {@code prior} itself when the host sees a different selection than the guest, which a later
     * reading may settle, or already sees exactly {@code prior}'s, and null when only a full rebuild could
     * follow the remote, such as when selected history was removed: that is the cost a refresh exists to avoid.
     * {@code prior} stays open; the caller closes it once it holds the result.
     */
    public Prepared refresh(Request request, Prepared prior, String guestAdvertisement, Phases phase) throws IOException {
        if (!isRemoteUrl(request.url())) throw new IOException("Git preparation requires an HTTPS, SSH or Git remote");
        var key = hash(POLICY + "\n" + request.url());
        // The ready line and --verbose timings describe the whole preparation, the refreshes included.
        var timings = new LinkedHashMap<>(prior.timings);
        var strategy = prior.strategy().endsWith(REFRESHED) ? prior.strategy() : prior.strategy() + REFRESHED;
        var lease = cache.lease();
        boolean success = false;
        try (var ignored = cache.writer(key)) {
            long start = System.nanoTime();
            var advertised = advertisement(lease.path, request.url(), phase);
            elapsed(timings, "remote", start);
            if (advertised == null || !advertised.equals(parseAdvertisement(guestAdvertisement))
                    || advertised.equals(selection(prior.repository()))) return prior;
            var staged = Files.createDirectory(lease.path.resolve("snapshot"));
            var result = staged.resolve("repo.git");
            if (!refreshSnapshot(request.url(), prior.repository(), prior.checksums(), advertised,
                    lease.path, result, phase, timings)) return null;
            validatePin(result, request.branch());
            var refreshed = publish(key, lease, staged, strategy, timings, phase);
            success = true;
            return refreshed;
        } finally {
            if (!success) lease.close();
        }
    }

    /** Seal and publish {@code staged}'s repository; the result reads a pinned copy in {@code lease}. */
    private Prepared publish(String key, PreparationCache.Lease lease, Path staged, String strategy,
                             Map<String, Long> timings, Phases phase) throws IOException {
        var result = staged.resolve("repo.git");
        long start = System.nanoTime();
        // Recreate only the selected refs and pack/index pairs in a minimal bare repository.
        // Discard clone config (including credentials), reflogs and generated object sidecars.
        var clean = lease.path.resolve("prepared.git");
        sanitize(result, clean);
        delete(result);
        Files.move(clean, result);
        var manifest = manifest(result);
        Files.writeString(staged.resolve("seal"), seal(key, manifest));
        elapsed(timings, "verify", start);

        start = System.nanoTime();
        phase.accept("Publishing Git snapshot");
        // The lease pins immutable owned files before publication can move/evict the staging tree.
        PreparationCache.copyTree(result, clean, true);
        var notRetained = cache.publishSnapshot(key, staged);
        delete(staged);
        elapsed(timings, "publish", start);
        return new Prepared(lease, clean, strategy, objectChecksums(manifest), timings, notRetained);
    }

    /**
     * Export an explicit host source. Only local heads/tags select objects; neither source HEAD,
     * remote-tracking refs, reflogs nor the index enlarge that selection. No transport is used.
     * Unlike network snapshots, these exports are ephemeral and never fall back to a remote.
     */
    public Prepared prepareHost(Path source, String branch, Phases phase) throws IOException {
        var lease = cache.lease();
        boolean success = false;
        long start = System.nanoTime();
        try {
            phase.accept("Exporting host repository");
            source = source.toRealPath();
            if (git(source, "rev-parse", "--is-shallow-repository").equals("true")) {
                throw new IOException("host-source requires a complete repository, not a shallow checkout");
            }
            var selected = refMap(source);
            if (branch != null && !branch.isBlank() && !selected.containsKey("refs/heads/" + branch)
                    && !selected.containsKey("refs/tags/" + branch)) {
                throw new IOException("Host source has no requested branch or tag: " + branch);
            }
            var result = lease.path.resolve("host.git");
            init(result, git(source, "rev-parse", "--show-object-format"));
            // Read objects through fresh metadata, so source config (including promisor remotes,
            // hooks and replace refs) cannot fetch or change what the selected IDs mean.
            var objects = Path.of(git(source, "rev-parse", "--path-format=absolute", "--git-path", "objects"));
            if (!selected.isEmpty()) {
                var alternate = result.resolve("objects/info/alternates");
                Files.writeString(alternate, objects + "\n");
                var roots = lease.path.resolve("roots");
                Files.write(roots, selected.values());
                var pack = result.resolve("objects/pack/export.pack");
                // The walk from the roots alone decides which objects enter the pack. Without --thin, a
                // stored delta is reused only when its base is selected too; otherwise the object is
                // written whole or against a selected base, so a private object is never a base here.
                // Bitmaps stay off: they are the source's own claim about reachability, not our walk.
                run(result, roots, pack, List.of("-c", "protocol.https.allow=never", "-c", "protocol.ssh.allow=never",
                        "-c", "protocol.git.allow=never", "pack-objects", "--stdout", "--revs", "--no-use-bitmap-index"));
                // Without the alternate, index-pack --strict must resolve every link inside the export, which
                // proves it self-contained. --stdin installs the standard pack-<hash> pair in our owned store.
                Files.delete(alternate);
                run(result, pack, null, List.of("index-pack", "--stdin", "--strict"));
                Files.delete(pack);
                Files.delete(roots);
            }
            var refsInput = lease.path.resolve("refs");
            Files.write(refsInput, selected.entrySet().stream()
                    .map(e -> "update " + e.getKey() + " " + e.getValue()).toList());
            run(result, refsInput, null, List.of("update-ref", "--stdin"));
            Files.delete(refsInput);
            String head;
            if (branch != null && !branch.isBlank()) {
                // A requested checkout also works when the host is detached at an unexported commit.
                head = selected.containsKey("refs/heads/" + branch) ? "ref: refs/heads/" + branch
                        : git(result, "rev-parse", "--verify", "refs/tags/" + branch + "^{commit}");
            } else {
                // Git resolves HEAD independently of the source's ref storage (files or reftable).
                try {
                    head = "ref: " + git(source, "symbolic-ref", "--quiet", "HEAD");
                } catch (GitCommandException detached) {
                    head = git(source, "rev-parse", "--verify", "HEAD");
                }
            }
            Files.writeString(result.resolve("HEAD"), head + "\n");
            try {
                validatePin(result, branch);
            } catch (GitCommandException e) {
                if (branch == null || branch.isBlank()) {
                    throw new IOException("Host source HEAD is outside exported branches and tags; set branch to select a checkout", e);
                }
                throw e;
            }
            git(result, "pack-refs", "--all");
            var timings = new LinkedHashMap<String, Long>();
            elapsed(timings, "export", start);
            var checksums = objectChecksums(manifest(result));
            success = true;
            return new Prepared(lease, result, "host source", checksums, timings, null);
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

    /** Shows {@code label}, then the transfer's elapsed time and Git's progress under it. */
    private static Consumer<GitProcess.Progress> live(Phases phase, String label) {
        phase.accept(label);
        return progress -> phase.progress(label, progress.summary());
    }

    private void cloneSnapshot(String url, Path work, Path result, Reference reference,
                               Phases phase, Map<String, Long> timings) throws IOException {
        long start = System.nanoTime();
        var progress = live(phase, "Creating clean Git snapshot");
        var trace = work.resolve("clone-trace");
        boolean transferChecked, loose;
        Received received;
        try {
            try {
                received = clone(url, work, result, reference, trace, phase, progress);
                if (Files.exists(result.resolve("shallow")) || Files.exists(result.resolve("objects/info/alternates"))) {
                    throw new IOException("Git snapshot is not a standalone full repository");
                }
                // Loose objects did not come through index-pack: dumb HTTP writes them directly, and an
                // unpacked transfer gets the full fsck too. Each pack must come from its own strict receive.
                loose = hasLooseObjects(result);
                transferChecked = received.strict() && !loose && Files.exists(trace)
                        && strictReceives(Files.readString(trace, StandardCharsets.ISO_8859_1)) == children(result.resolve("objects/pack"))
                                .stream().filter(p -> p.getFileName().toString().endsWith(".pack")).count();
            } finally {
                Files.deleteIfExists(trace);
            }
            // Small network clones may unpack received objects. Normalize those once; a dissociated
            // reference clone already has its selected pack and must not pay for a second repack.
            if (loose) {
                git(result, "repack", "-a", "-d");
                git(result, "prune-packed");
            }
        } finally {
            elapsed(timings, "clone", start);
        }
        // Without a reference, a strict receive has hashed every object and checked its structure and
        // links; a reference clone's borrowed objects are copied without rehashing and need the full fsck.
        if (transferChecked) return;
        start = System.nanoTime();
        phase.accept("Verifying Git snapshot");
        try {
            git(result, "fsck", "--full", "--no-dangling");
        } catch (IOException failed) {
            // Keep why the snapshot was cloned twice next to why it was then rejected.
            if (received.rejected() != null) failed.addSuppressed(received.rejected());
            throw failed;
        } finally {
            elapsed(timings, "verify", start);
        }
    }

    /**
     * index-pack's own rejection of an object or a pack under {@code --strict}, as a quoted line of Git's
     * output. Matching the whole line keeps a {@code remote:} sideband line out, but a server can still trigger
     * one reclone: by serving history with an fsck warning, or over SSH, where its stderr reaches us unprefixed.
     * That is safe, because the full fsck then decides.
     */
    private static final Pattern FSCK_REJECTED = Pattern.compile(
            Pattern.quote(GitCommandException.QUOTE) + "fatal: fsck error in pack(?:ed object| objects)");

    static boolean fsckRejected(List<String> details) {
        return details.stream().anyMatch(line -> FSCK_REJECTED.matcher(line).matches());
    }

    /** {@code strict}: the strict receive was kept. {@code rejected}: why it was not, when it was cloned again. */
    private record Received(boolean strict, GitCommandException rejected) {}

    /** Clone {@code url}; without a reference, with a strict receive traced to {@code trace}. */
    private Received clone(String url, Path work, Path result, Reference reference, Path trace, Phases phase,
                          Consumer<GitProcess.Progress> progress) throws IOException {
        if (reference != null) {
            run(work, null, null, cloneCommand(url, result, reference, false), transfer, progress);
            return new Received(false, null);
        }
        GitCommandException rejected;
        try {
            // Git's trace shows how the objects arrived. It names the URL, so it stays in the private lease.
            run(work, null, null, cloneCommand(url, result, null, true), transfer, progress, Map.of("GIT_TRACE", trace.toString()));
            return new Received(true, null);
        } catch (GitCommandException e) {
            if (e instanceof TransferStopped || !fsckRejected(e.details())) throw e;
            rejected = e;
        }
        // --strict also makes fsck's warnings fatal, such as zero-padded modes or bad dates in old history.
        // Clone again without it and let the full fsck decide, as it alone did before the receive was strict.
        delete(result);
        Files.deleteIfExists(trace);
        try {
            run(work, null, null, cloneCommand(url, result, null, false), transfer,
                    live(phase, "Recloning Git snapshot for a full check"));
        } catch (IOException failed) {
            failed.addSuppressed(rejected);
            throw failed;
        }
        return new Received(false, rejected);
    }

    private static List<String> cloneCommand(String url, Path result, Reference reference, boolean strict) {
        var clone = new ArrayList<String>();
        if (reference != null && reference.tips() != null) {
            // Git appends the alternate's repository path. Ignore it: emitting only captured
            // public tracking tips also overrides a global alternateRefsCommand/prefix policy.
            clone.addAll(List.of("-c", "core.alternateRefsCommand=sh -c 'cat \"$1\"' - "
                    + shellQuote(reference.tips().toString())));
        }
        // fetch.fsckObjects, not transfer.fsckObjects, which a user's fetch.fsckObjects=false would override.
        if (strict) clone.addAll(List.of("-c", "fetch.fsckObjects=true"));
        // A bare clone avoids the commit-graph/checkout bug in older Git's --dissociate.
        // The real remote selects refs; Git's non-thin repack excludes private reference objects.
        clone.addAll(List.of("clone", "--progress", "--bare", "--no-local", "--template="));
        if (reference != null) clone.addAll(List.of("--reference", reference.repository().toString(), "--dissociate"));
        clone.addAll(List.of("--", url, result.toString()));
        return clone;
    }

    private static final Pattern RECEIVE = Pattern.compile("trace: built-in: git index-pack(?: (.*))?$");

    /**
     * How many index-pack runs a clone's {@code GIT_TRACE} shows, or 0 unless each had a bare {@code --strict}.
     * Dumb HTTP indexes its packs without it and writes loose objects with no index-pack at all; packfile URIs
     * and bundles use {@code --fsck-objects}, which skips links. {@code --strict=<list>} carries the user's
     * {@code fetch.fsck.*} severities or skip list, which may let through what {@code fsck --full} rejects.
     */
    static long strictReceives(String trace) {
        var receives = trace.lines().map(RECEIVE::matcher).filter(Matcher::find).toList();
        return receives.stream().allMatch(receive -> receive.group(1) != null
                && Arrays.asList(receive.group(1).replace("'", "").split(" ")).contains("--strict")) ? receives.size() : 0;
    }

    /**
     * Extend a verified generation without rewriting or decoding its old packs.
     * {@code previousManifest} needs only its {@code objects/} entries.
     */
    private boolean refreshSnapshot(String url, Path previous, Map<String, String> previousManifest,
                                           String advertised, Path work, Path result,
                                           Phases phase, Map<String, Long> timings) throws IOException {
        long start = System.nanoTime();
        var progress = live(phase, "Fetching incremental Git snapshot");
        try {
            // Only owned immutable packs are linked. Config, HEAD and refs are recreated independently.
            sanitize(previous, result);
            run(result, null, null, List.of("-c", "fetch.fsckObjects=true", "-c", "fetch.unpackLimit=1",
                    "fetch", "--progress", "--atomic", "--prune", "--no-tags", "--no-write-fetch-head",
                    "--no-auto-maintenance", "--no-write-commit-graph", "--no-recurse-submodules",
                    "--", url, "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*", "+HEAD:refs/isx/remote-head"),
                    transfer, progress);
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
    private String advertisement(Path work, String url, Phases phase) throws IOException {
        return parseAdvertisement(run(work, null, null,
                List.of("ls-remote", "--symref", "--", url, "HEAD", "refs/heads/*", "refs/tags/*"),
                transfer, live(phase, "Reading remote refs")));
    }

    private static String parseAdvertisement(String output) throws IOException {
        var refs = new TreeMap<String, String>();
        String head = null;
        for (var line : output.lines().toList()) {
            var parts = line.split("\t", 2);
            if (parts.length != 2) throw new IOException("Invalid Git advertisement");
            if (parts[0].startsWith("ref: ")) {
                if (parts[1].equals("HEAD")) head = parts[0];
            } else if (!parts[1].endsWith("^{}") && (parts[1].equals("HEAD")
                    || parts[1].startsWith("refs/heads/") || parts[1].startsWith("refs/tags/"))) {
                // The HEAD pattern also matches the tail of other refs, such as a mirror's refs/remotes/origin/HEAD.
                if (!OBJECT_ID.matcher(parts[0]).matches()) throw new IOException("Invalid Git object ID");
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
        return children(repo.resolve("objects")).stream().anyMatch(p -> LOOSE_OBJECT_DIR.matcher(p.getFileName().toString()).matches());
    }

    private static boolean packFile(Path p) {
        return PACK_FILE.matcher(p.getFileName().toString()).matches();
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
