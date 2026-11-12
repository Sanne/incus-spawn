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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.incusspawn.git.PreparationGit.*;
import static dev.incusspawn.incus.Container.shellQuote;

/**
 * Standalone full-history clones of the one ref a template requests, selected by the remote and retained
 * as bounded immutable snapshots (#1990): the guest fetches any other branch it needs itself, later.
 */
public final class GitPreparation {
    private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern SCP_LIKE = Pattern.compile("(?:[^/@:]+@)?(?:\\[[0-9a-fA-F:]+\\]|[^/@:]+):[^:].*");
    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}(?:[0-9a-f]{24})?");
    /**
     * What may follow {@code refs/heads/} or {@code refs/tags/} in a name a reading gives, which the host then
     * fetches by name: no refspec or glob character, no control character or space, none of the sequences
     * {@code check-ref-format} refuses.
     */
    private static final String FETCHABLE_NAME =
            "(?!\\.)(?!.*(?:\\.\\.|@\\{|//|/\\.|\\.lock(?:/|$)))[^\\x00-\\x20\\x7f:?*\\[\\\\^~]+(?<![./])";
    /** A branch a reading may name as HEAD's target. */
    private static final Pattern FETCHABLE_BRANCH = Pattern.compile("refs/heads/" + FETCHABLE_NAME);
    /** A tag a reading may name for {@link #withTags}. */
    private static final Pattern FETCHABLE_TAG = Pattern.compile("refs/tags/" + FETCHABLE_NAME);
    private static final Pattern LOOSE_OBJECT_DIR = Pattern.compile("[0-9a-f]{2}");
    private static final Pattern PACK_FILE = Pattern.compile("pack-[0-9a-f]{40}(?:[0-9a-f]{24})?\\.(pack|idx)");
    private static final String POLICY = "isx-clean-branch-snapshot-v1";
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
        private final String branch;
        /** The tags the host's own reading showed, by name, at their IDs: {@link #withTags} adds no other. */
        private final Map<String, String> hostTags;

        private Prepared(PreparationCache.Lease lease, Path repository, String strategy,
                         Map<String, String> checksums, Map<String, Long> timings, String notRetained, String branch,
                         Map<String, String> hostTags) {
            this.branch = branch;
            this.hostTags = Map.copyOf(hostTags);
            this.lease = lease;
            this.repository = repository;
            this.strategy = strategy;
            this.checksums = Map.copyOf(checksums);
            this.timings = new LinkedHashMap<>(timings);
            this.notRetained = notRetained;
        }

        public Path repository() { return repository; }
        public String strategy() { return strategy; }
        /**
         * The guest's own reading must resolve the requested ref to exactly this snapshot's, at the same ID,
         * before any prepared bytes are mounted. The snapshot holds only what that ref reaches.
         */
        public boolean matchesAdvertisement(String advertisement) throws IOException {
            var visible = parseAdvertisement(advertisement, branch);
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
        private final String branch;
        private final Reading advertised;
        private final long millis;
        private boolean taken;

        private RemoteRefs(PreparationCache.Lease lease, String url, String branch, Reading advertised, long millis) {
            this.lease = lease;
            this.url = url;
            this.branch = branch;
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
    public RemoteRefs readRemote(String url, String branch, Phases phase) throws IOException {
        if (!isRemoteUrl(url)) throw new IOException("Git preparation requires an HTTPS, SSH or Git remote");
        var lease = cache.lease();
        boolean success = false;
        try {
            long start = System.nanoTime();
            var refs = new RemoteRefs(lease, url, branch, advertisement(lease.path, url, branch, phase),
                    (System.nanoTime() - start) / 1_000_000);
            success = true;
            return refs;
        } finally {
            if (!success) lease.close();
        }
    }

    public Prepared prepare(Request request, Phases phase) throws IOException {
        return prepare(request, readRemote(request.url(), request.branch(), phase), phase);
    }

    /** Continues from {@code remote}, which it takes over whatever the outcome; it must have read {@code request}'s URL. */
    public Prepared prepare(Request request, RemoteRefs remote, Phases phase) throws IOException {
        var lease = remote.take();
        boolean success = false;
        try {
            if (!remote.url.equals(request.url())) throw new IOException("Remote refs were read for another URL");
            if (!pin(remote.branch).equals(pin(request.branch()))) throw new IOException("Remote refs were read for another branch");
            var prepared = prepare(request, lease, remote.advertised, remote.millis, phase);
            success = true;
            return prepared;
        } finally {
            if (!success) lease.close();
        }
    }

    private Prepared prepare(Request request, PreparationCache.Lease lease, Reading reading, long remoteMillis,
                             Phases phase) throws IOException {
        var advertised = reading.selection();
        var hostTags = reading.tags();
        var timings = new LinkedHashMap<String, Long>();
        timings.put("remote", remoteMillis);
        var key = key(request);
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
                // The early read may predate the access barrier, the limiter wait and this writer lock, and a
                // concurrent build may have published exactly what the remote shows now: read again first.
                start = System.nanoTime();
                reading = advertisement(lease.path, request.url(), request.branch(), phase);
                advertised = reading.selection();
                hostTags = reading.tags();
                elapsed(timings, "remote", start);
                hit = advertised != null && advertised.equals(selection(previousRepo));
            }
            if (hit) {
                validatePin(previousRepo, request.branch());
                return new Prepared(lease, previousRepo, "cached clean snapshot", objectChecksums(manifest), timings, null,
                        request.branch(), hostTags);
            }

            if (advertised == null) {
                throw new IOException(request.branch() == null || request.branch().isBlank()
                        ? "Remote HEAD names no branch to prepare" : "Remote has no requested branch or tag");
            }
            start = System.nanoTime();
            phase.accept("Selecting Git reference");
            Reference reference = manifest == null ? null : new Reference(previousRepo, null);
            var tips = lease.path.resolve("reference-tips");
            var sibling = lease.path.resolve("sibling");
            boolean retried = false, seededBySibling = false;
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
                if (reference == null) {
                    reference = siblingReference(key, family(request), sibling, tips);
                    seededBySibling = reference != null;
                }
            }
            elapsed(timings, "reference", start);

            var staged = Files.createDirectory(lease.path.resolve("snapshot"));
            var result = staged.resolve("repo.git");
            boolean incremental = false;
            if (manifest != null) {
                try {
                    incremental = refreshSnapshot(request.url(), previousRepo, manifest, advertised, false,
                            lease.path, result, phase, timings) == Refreshed.YES;
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
                    cloneSnapshot(request.url(), advertised, lease.path, result, reference, phase, timings);
                } catch (IOException failedReference) {
                    if (reference == null || Thread.currentThread().isInterrupted()
                            || failedReference instanceof TransferStopped) throw failedReference;
                    // A live checkout can be repacked, removed or damaged while we borrow from it.
                    // Discard every byte of the failed attempt and retry once with only the real remote.
                    delete(result);
                    reference = null;
                    seededBySibling = false;
                    retried = true;
                    phase.accept("Retrying Git snapshot without reference");
                    cloneSnapshot(request.url(), advertised, lease.path, result, null, phase, timings);
                }
            }
            validatePin(result, request.branch());
            delete(previous);
            delete(sibling);
            Files.deleteIfExists(tips);
            return publish(key, request, lease, staged, incremental ? "incremental clean snapshot"
                    : seededBySibling ? "sibling-seeded clean snapshot"
                    : reference != null ? "reference-seeded clean snapshot"
                    : retried ? "new clean snapshot (reference retry)" : "new clean snapshot",
                    timings, hostTags, phase);
        }
    }

    /**
     * Bring {@code prior} up to {@code guestAdvertisement}, the guest's latest {@code ls-remote}, with an
     * incremental fetch from its own leased bytes, whether or not the cache kept it (#1980, #1990).
     * The host fetches only {@code prior}'s own ref, by name, and keeps the result only when that fetch brought
     * it to the guest's ID: so the result matches that reading, and a guest's reading can name an ID but never
     * make the host hand over what the host's fetch did not confirm. The fetched name is the template's pin or
     * the one the host's own reading chose, never one only the guest's reading names.
     * Returns {@code prior} when the reading cannot be followed yet: the guest sees exactly {@code prior}'s
     * selection, or the ref moved again before the host fetched it. Returns null when no later reading would
     * settle it in this build: the reading names no such ref, or another ref, such as a default branch the
     * remote has just switched, which a later preparation rebuilds from the host's own reading; or only a full
     * rebuild could follow the remote, such as after a force-push, which is the cost a refresh exists to avoid.
     * {@code prior} stays open; the caller closes it once it holds the result.
     */
    public Prepared refresh(Request request, Prepared prior, String guestAdvertisement, Phases phase) throws IOException {
        if (!isRemoteUrl(request.url())) throw new IOException("Git preparation requires an HTTPS, SSH or Git remote");
        var guest = parseAdvertisement(guestAdvertisement, request.branch());
        if (guest == null) return null;
        var own = selection(prior.repository());
        if (guest.equals(own)) return prior;
        if (own == null || !refName(guest).equals(refName(own))) return null;
        var key = key(request);
        // The ready line and --verbose timings describe the whole preparation, the refreshes included.
        var timings = new LinkedHashMap<>(prior.timings);
        var strategy = prior.strategy().endsWith(REFRESHED) ? prior.strategy() : prior.strategy() + REFRESHED;
        var lease = cache.lease();
        boolean success = false;
        try (var ignored = cache.writer(key)) {
            var staged = Files.createDirectory(lease.path.resolve("snapshot"));
            var result = staged.resolve("repo.git");
            var refreshed = refreshSnapshot(request.url(), prior.repository(), prior.checksums(), guest, true,
                    lease.path, result, phase, timings);
            if (refreshed == Refreshed.MOVED) return prior;
            if (refreshed == Refreshed.NO) return null;
            validatePin(result, request.branch());
            var published = publish(key, request, lease, staged, strategy, timings, prior.hostTags, phase);
            success = true;
            return published;
        } finally {
            if (!success) lease.close();
        }
    }

    /**
     * {@code prior}, a snapshot {@code guestAdvertisement} matches, with the tags into its ref's history that
     * the guest's reading and the host's both show at one ID (#2051), so a build reading tags, such as
     * {@code git describe}, sees what a network clone gives it. The guest receives nothing its own account
     * could not fetch: every tag is one its reading shows at that ID, and the only objects added are those tags'
     * own tag objects. The host fetches only names its own reading shows, never an ID, and keeps a fetched tag
     * only at the guest's ID; a tag outside the snapshot's history is left out, and so is one that moved. The
     * copy is the build's own, in {@code prior}'s lease: the published snapshot never holds a tag beyond its
     * ref. Returns {@code prior} when no tag is added; otherwise the result takes over {@code prior}'s lease.
     */
    public Prepared withTags(Request request, Prepared prior, String guestAdvertisement, Phases phase) throws IOException {
        if (!isRemoteUrl(request.url())) throw new IOException("Git preparation requires an HTTPS, SSH or Git remote");
        var repo = prior.repository();
        var own = refMap(repo);
        var guest = new TreeMap<>(tagReadings(guestAdvertisement));
        guest.keySet().removeIf(name -> !FETCHABLE_TAG.matcher(name).matches() || own.containsKey(name));
        if (guest.isEmpty()) return prior;
        // The timings are the whole preparation's, which this copy supersedes.
        var timings = prior.timings;
        long start = System.nanoTime();
        var tagged = prior.lease.path.resolve("tagged.git");
        var work = Files.createDirectory(prior.lease.path.resolve("tags"));
        var checksums = new TreeMap<>(prior.checksums);
        var refs = new TreeMap<String, String>();
        var hostTags = prior.hostTags;
        try {
            // The snapshot holds exactly its ref's closure, so an object it holds is one that ref reaches. A tag
            // outside it is left out first, so it neither costs a reading nor keeps another tag out.
            var held = present(repo, guest.values().stream()
                    .flatMap(tag -> java.util.stream.Stream.of(tag.id(), tag.peeled())).distinct().toList(), work);
            guest.values().removeIf(tag -> !held.contains(tag.peeled()));
            if (guest.isEmpty()) return prior;
            if (guest.entrySet().stream().anyMatch(e -> !e.getValue().id().equals(prior.hostTags.get(e.getKey())))) {
                // The guest read later than the host, say after a release pushed a commit and its tag: read again,
                // so the names fetched are still only ones the host's own reading shows.
                elapsed(timings, "tags", start);
                start = System.nanoTime();
                hostTags = advertisement(prior.lease.path, request.url(), request.branch(), phase).tags();
                elapsed(timings, "remote", start);
                start = System.nanoTime();
            }
            var shown = hostTags;
            guest.entrySet().removeIf(e -> !e.getValue().id().equals(shown.get(e.getKey())));
            // Loose refs of names that differ only in case, or of a name and another's directory so, would collide
            // in a case-insensitive cache (macOS): leave every such name out, on every host alike.
            var taken = new ArrayList<>(own.keySet());
            taken.addAll(guest.keySet());
            guest.keySet().removeAll(foldCollisions(taken));
            if (guest.isEmpty()) return prior;
            phase.accept("Adding Git tags");
            var fetch = new TreeMap<String, String>();
            guest.forEach((name, tag) -> (held.contains(tag.id()) ? refs : fetch).put(name, tag.id()));
            // Hardlinks of the immutable packs; Git adds files and replaces refs, never rewriting one in place.
            PreparationCache.copyTree(repo, tagged, true);
            if (!fetch.isEmpty()) addTagObjects(request.url(), tagged, work, fetch, refs, phase);
            if (refs.isEmpty()) {
                delete(tagged);
                return prior;
            }
            // The guest counts every file under objects/ against these.
            try (var files = Files.walk(tagged.resolve("objects"))) {
                for (var file : files.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    var path = tagged.relativize(file).toString();
                    if (!checksums.containsKey(path)) checksums.put(path, hash(file));
                }
            }
            var input = work.resolve("refs.in");
            Files.write(input, refs.entrySet().stream().map(e -> "create " + e.getKey() + " " + e.getValue()).toList());
            run(tagged, input, null, List.of("update-ref", "--stdin"));
            git(tagged, "pack-refs", "--all");
        } finally {
            delete(work);
            elapsed(timings, "tags", start);
        }
        var expected = new TreeMap<>(own);
        expected.putAll(refs);
        if (!expected.equals(refMap(tagged))) throw new IOException("Tagged Git snapshot has other refs");
        return new Prepared(prior.lease, tagged, prior.strategy, checksums, timings, prior.notRetained, prior.branch,
                hostTags);
    }

    /**
     * The names among {@code names}, each listed once, whose loose ref shares a path with another's once case is
     * folded: the same file, or one's file where the other needs a directory.
     */
    static java.util.Set<String> foldCollisions(java.util.Collection<String> names) {
        var files = new java.util.HashMap<String, Integer>();
        var dirs = new java.util.HashSet<String>();
        for (var name : names) {
            var folded = fold(name);
            files.merge(folded, 1, Integer::sum);
            for (int slash = folded.indexOf('/'); slash >= 0; slash = folded.indexOf('/', slash + 1)) {
                dirs.add(folded.substring(0, slash));
            }
        }
        var colliding = new java.util.HashSet<String>();
        for (var name : names) {
            var folded = fold(name);
            boolean collides = files.get(folded) > 1 || dirs.contains(folded);
            for (int slash = folded.indexOf('/'); !collides && slash >= 0; slash = folded.indexOf('/', slash + 1)) {
                collides = files.containsKey(folded.substring(0, slash));
            }
            if (collides) colliding.add(name);
        }
        return colliding;
    }

    /** Case folded the way a case-insensitive file system compares names, closer than lower case alone (final sigma). */
    private static String fold(String name) {
        return name.toUpperCase(java.util.Locale.ROOT).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Fetch {@code fetch}'s annotated tags by name into a scratch repository borrowing {@code tagged}'s objects,
     * and pack into {@code tagged} only the tag objects of those fetched at the guest's ID, adding them to
     * {@code refs}. A strict index without the alternate proves each one's target is already in the snapshot.
     */
    private void addTagObjects(String url, Path tagged, Path work, Map<String, String> fetch, Map<String, String> refs,
                               Phases phase) throws IOException {
        var scratch = work.resolve("fetch.git");
        init(scratch, git(tagged, "rev-parse", "--show-object-format"));
        Files.writeString(scratch.resolve("objects/info/alternates"),
                tagged.resolve("objects").toAbsolutePath() + "\n");
        var refspecs = work.resolve("refspecs");
        Files.write(refspecs, fetch.keySet().stream()
                .map(name -> "+" + name + ":refs/isx/" + name.substring("refs/".length())).toList());
        // By name and with --no-tags, so no include-tag; the snapshot's tip, through the alternate, is a have.
        run(scratch, refspecs, null, List.of("-c", "fetch.fsckObjects=true", "-c", "fetch.unpackLimit=1",
                "fetch", "--progress", "--stdin", "--no-tags", "--no-write-fetch-head", "--no-auto-maintenance",
                "--no-write-commit-graph", "--no-recurse-submodules", "--", url),
                transfer, live(phase, "Fetching Git tags"));
        var fetched = new TreeMap<String, String>();
        for (var line : git(scratch, "for-each-ref", "--format=%(refname) %(objectname)", "refs/isx/tags/").lines().toList()) {
            int space = line.indexOf(' ');
            fetched.put("refs/" + line.substring("refs/isx/".length(), space), line.substring(space + 1));
        }
        var objects = new java.util.LinkedHashSet<String>();
        fetch.forEach((name, id) -> {
            if (id.equals(fetched.get(name))) {
                objects.add(id);
                refs.put(name, id);
            }
        });
        if (objects.isEmpty()) return;
        // A tag of a tag: the inner tag objects the snapshot lacks come too. Each is reached from a kept tag.
        for (var frontier = List.copyOf(objects); !frontier.isEmpty(); ) {
            var inner = new ArrayList<String>();
            for (var target : innerTags(scratch, frontier, work)) if (!objects.contains(target)) inner.add(target);
            var missing = new ArrayList<>(inner);
            missing.removeAll(present(tagged, inner, work));
            objects.addAll(missing);
            frontier = missing;
        }
        var list = work.resolve("objects");
        Files.write(list, objects);
        var pack = work.resolve("tags.pack");
        run(scratch, list, pack, List.of("pack-objects", "--stdout", "--no-use-bitmap-index"));
        run(tagged, pack, null, List.of("index-pack", "--stdin", "--strict"));
    }

    /** Of {@code tags}, tag objects all, the targets that are tags themselves. */
    private static List<String> innerTags(Path repo, List<String> tags, Path work) throws IOException {
        var input = work.resolve("inner.in");
        var output = work.resolve("inner.out");
        Files.write(input, tags);
        try {
            run(repo, input, output, List.of("cat-file", "--batch"));
            var bytes = Files.readAllBytes(output);
            var targets = new ArrayList<String>();
            int at = 0;
            while (at < bytes.length) {
                int eol = indexOf(bytes, (byte) '\n', at);
                var header = new String(bytes, at, eol - at, StandardCharsets.US_ASCII).split(" ");
                if (header.length != 3 || !header[1].equals("tag")) throw new IOException("Fetched Git tag is not a tag object");
                int size = Integer.parseInt(header[2]);
                var body = new String(bytes, eol + 1, size, StandardCharsets.ISO_8859_1);
                var lines = body.split("\n", 3);
                if (lines.length < 2 || !lines[0].startsWith("object ") || !lines[1].startsWith("type ")) {
                    throw new IOException("Fetched Git tag is malformed");
                }
                if (lines[1].equals("type tag")) targets.add(lines[0].substring("object ".length()));
                at = eol + 1 + size + 1;
            }
            return targets;
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    private static int indexOf(byte[] bytes, byte value, int from) throws IOException {
        for (int i = from; i < bytes.length; i++) if (bytes[i] == value) return i;
        throw new IOException("Truncated Git object output");
    }

    /** Which of {@code ids} {@code repo} holds. */
    private static java.util.Set<String> present(Path repo, List<String> ids, Path work) throws IOException {
        var input = work.resolve("present.in");
        var output = work.resolve("present.out");
        Files.write(input, ids);
        try {
            run(repo, input, output, List.of("cat-file", "--batch-check=%(objectname)"));
            var held = new java.util.HashSet<String>();
            for (var line : Files.readAllLines(output, StandardCharsets.US_ASCII)) {
                if (!line.endsWith(" missing")) held.add(line);
            }
            return held;
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    /** The object format a {@link #selection} names on its first line. */
    private static String objectFormat(String selection) {
        return selection.substring(0, selection.indexOf('\n'));
    }

    /** The ref a {@link #selection} names. */
    private static String refName(String selection) {
        var ref = selection.split("\n", 2)[1];
        return ref.substring(0, ref.indexOf(' '));
    }

    /** One cache entry per URL and requested branch or tag; none requested is the remote's default branch. */
    private static String key(Request request) {
        return hash(POLICY + "\n" + request.url() + "\n" + pin(request.branch()));
    }

    /** What every pin of one URL shares: a new pin borrows objects from another's snapshot (#2050). */
    private static String family(Request request) {
        return hash(POLICY + "\n" + request.url());
    }

    /** A requested branch or tag, with none and a blank one both the empty string: the remote's default branch. */
    private static String pin(String branch) {
        return branch == null || branch.isBlank() ? "" : branch;
    }

    /** Seal and publish {@code staged}'s repository; the result reads a pinned copy in {@code lease}. */
    private Prepared publish(String key, Request request, PreparationCache.Lease lease, Path staged, String strategy,
                             Map<String, Long> timings, Map<String, String> hostTags, Phases phase) throws IOException {
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
        var notRetained = cache.publishSnapshot(key, family(request), staged);
        delete(staged);
        elapsed(timings, "publish", start);
        return new Prepared(lease, clean, strategy, objectChecksums(manifest), timings, notRetained, request.branch(),
                hostTags);
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
            return new Prepared(lease, result, "host source", checksums, timings, null, branch, Map.of());
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

    /**
     * The sealed snapshot of another pin of the same URL, pinned into {@code destination}, or null. Its one ref
     * is the fetch's negotiation tip, so the remote sends only what the new ref adds, and the non-thin export
     * after the fetch copies only the new ref's closure: the sibling's own history never widens the result.
     */
    private Reference siblingReference(String key, String family, Path destination, Path tips) throws IOException {
        Map<String, String> manifest = null;
        try {
            var siblingKey = cache.sibling(family, key, destination);
            if (siblingKey == null) return null;
            manifest = readSnapshot(destination, siblingKey);
        } catch (IOException invalid) {
            // A sibling is only an optimization: a pin that fails or damaged cache bytes, which their own pin
            // rebuilds, leave this preparation to clone the remote, never borrowing from them.
            if (Thread.currentThread().isInterrupted()) throw invalid;
        }
        if (manifest != null) {
            // Its one tip, as for a host checkout: a global alternateRefsCommand or prefix policy cannot hide it.
            var repo = destination.resolve("repo.git");
            Files.writeString(tips, git(repo, "for-each-ref", "--format=%(objectname)") + "\n");
            return new Reference(repo, tips);
        }
        delete(destination);
        return null;
    }

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

    /** Create a snapshot of {@code target}'s ref alone, a {@link #selection}, with all of its history. */
    private void cloneSnapshot(String url, String target, Path work, Path result, Reference reference,
                               Phases phase, Map<String, Long> timings) throws IOException {
        long start = System.nanoTime();
        var progress = live(phase, "Creating clean Git snapshot");
        if (reference != null) {
            Borrowed borrowed;
            try {
                borrowed = borrow(url, target, work, reference, progress);
            } finally {
                elapsed(timings, "clone", start);
            }
            start = System.nanoTime();
            try {
                indexBorrowed(target, borrowed, result, phase);
            } finally {
                Files.deleteIfExists(borrowed.export());
                elapsed(timings, "verify", start);
            }
            return;
        }
        var trace = work.resolve("clone-trace");
        boolean transferChecked, loose;
        Received received;
        try {
            try {
                received = clone(url, target, result, trace, phase, progress);
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
            // Small network clones may unpack received objects. Normalize those once.
            if (loose) {
                git(result, "repack", "-a", "-d");
                git(result, "prune-packed");
            }
        } finally {
            elapsed(timings, "clone", start);
        }
        // A strict receive has hashed every object and checked its structure and links.
        if (transferChecked) return;
        start = System.nanoTime();
        var checking = live(phase, "Verifying Git snapshot");
        try {
            run(result, null, null, List.of("fsck", "--full", "--no-dangling", "--progress"), LOCAL, checking);
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

    /** Fetch {@code target} from {@code url} with a strict receive traced to {@code trace}. */
    private Received clone(String url, String target, Path result, Path trace, Phases phase,
                          Consumer<GitProcess.Progress> progress) throws IOException {
        GitCommandException rejected;
        try {
            // Git's trace shows how the objects arrived. It names the URL, so it stays in the private lease.
            fetchSnapshot(url, target, result, null, true, progress, Map.of("GIT_TRACE", trace.toString()));
            return new Received(true, null);
        } catch (GitCommandException e) {
            if (e instanceof TransferStopped || !fsckRejected(e.details())) throw e;
            rejected = e;
        }
        // --strict also makes fsck's warnings fatal, such as zero-padded modes or bad dates in old history.
        // Fetch again without it and let the full fsck decide, as it alone did before the receive was strict.
        delete(result);
        Files.deleteIfExists(trace);
        try {
            fetchSnapshot(url, target, result, null, false, live(phase, "Recloning Git snapshot for a full check"), Map.of());
        } catch (IOException failed) {
            failed.addSuppressed(rejected);
            throw failed;
        }
        return new Received(false, rejected);
    }

    /** A reference clone's objects as one pack, {@code export}, of the ref fetched at {@code id}. */
    private record Borrowed(Path export, String id) {}

    /**
     * Fetch {@code target} borrowing {@code reference}'s objects, then export what the ref reaches as one pack, as
     * a host-source export does: walked from the ref alone and, without {@code --thin}, holding every base it needs,
     * so the reference's private objects, and those only other refs reach, stay behind.
     */
    private Borrowed borrow(String url, String target, Path work, Reference reference,
                            Consumer<GitProcess.Progress> progress) throws IOException {
        var borrowing = work.resolve("borrowing.git");
        var roots = work.resolve("roots");
        var export = work.resolve("export.pack");
        try {
            fetchSnapshot(url, target, borrowing, reference, false, progress, Map.of());
            var id = git(borrowing, "rev-parse", "--verify", refName(target));
            Files.writeString(roots, id + "\n");
            // Bitmaps stay off: they are the reference's own claim about reachability, not our walk.
            // A large repository's export takes minutes: keep its elapsed time and progress showing.
            run(borrowing, roots, export, List.of("-c", "protocol.https.allow=never", "-c", "protocol.ssh.allow=never",
                    "-c", "protocol.git.allow=never", "pack-objects", "--stdout", "--revs", "--delta-base-offset",
                    "--no-use-bitmap-index", "--progress"), LOCAL, progress);
            return new Borrowed(export, id);
        } catch (IOException failed) {
            Files.deleteIfExists(export);
            throw failed;
        } finally {
            Files.deleteIfExists(roots);
            delete(borrowing);
        }
    }

    /**
     * Index a reference clone's export into the empty {@code result}, with nothing attached to borrow from (#1991).
     * {@code index-pack --strict} then hashes every object, checks its structure and resolves every link inside
     * the pack, so a borrowed object whose bytes do not match its name, or one missing, fails here, as it does for a
     * host-source export. It also makes fsck's warnings fatal: on that rejection alone the pack is indexed without
     * it, and the full fsck decides, as it alone did before. The export is indexed where it lies, inside the
     * repository but with no index yet, so it is not an object source until then and is never copied.
     */
    private static void indexBorrowed(String target, Borrowed borrowed, Path result, Phases phase) throws IOException {
        // A large repository's index takes minutes: show its elapsed time and progress, as for the fetch.
        var progress = live(phase, "Verifying Git snapshot");
        init(result, objectFormat(target));
        var packs = Files.createDirectories(result.resolve("objects/pack"));
        var pack = Files.move(borrowed.export(), packs.resolve("export.pack"));
        GitCommandException rejected = null;
        String hash;
        try {
            hash = run(result, null, null, List.of("index-pack", "--strict", "-v", pack.toString()), LOCAL, progress);
        } catch (GitCommandException e) {
            if (!fsckRejected(e.details())) throw e;
            rejected = e;
            hash = run(result, null, null, List.of("index-pack", "-v", pack.toString()), LOCAL, progress);
        }
        Files.move(packs.resolve("export.idx"), packs.resolve("pack-" + hash.strip() + ".idx"));
        Files.move(pack, packs.resolve("pack-" + hash.strip() + ".pack"));
        var name = refName(target);
        git(result, "update-ref", name, borrowed.id());
        writeHead(result, name);
        if (rejected == null) return;
        try {
            run(result, null, null, List.of("fsck", "--full", "--no-dangling", "--progress"), LOCAL, progress);
        } catch (IOException failed) {
            failed.addSuppressed(rejected);
            throw failed;
        }
    }

    /**
     * A fresh bare repository holding only {@code target}'s ref, fetched by name. Not a clone: clone asks the
     * remote to include every annotated tag pointing into what it sends, even with {@code --no-tags}, and those
     * tag objects would sit in the snapshot with no ref of it reaching them. With a reference, its objects are
     * borrowed through an alternate, which stays: {@link #borrow} exports what the ref reaches from there.
     */
    private void fetchSnapshot(String url, String target, Path result, Reference reference, boolean strict,
                               Consumer<GitProcess.Progress> progress, Map<String, String> environment) throws IOException {
        var name = refName(target);
        init(result, objectFormat(target));
        var alternates = result.resolve("objects/info/alternates");
        var fetch = new ArrayList<String>();
        if (reference != null) {
            Files.createDirectories(alternates.getParent());
            Files.writeString(alternates, git(reference.repository(), "rev-parse", "--path-format=absolute",
                    "--git-path", "objects") + "\n");
            if (reference.tips() != null) {
                // Git appends the alternate's repository path. Ignore it: emitting only captured
                // public tracking tips also overrides a global alternateRefsCommand/prefix policy.
                fetch.addAll(List.of("-c", "core.alternateRefsCommand=sh -c 'cat \"$1\"' - "
                        + shellQuote(reference.tips().toString())));
            }
        }
        // fetch.fsckObjects, not transfer.fsckObjects, which a user's fetch.fsckObjects=false would override.
        if (strict) fetch.addAll(List.of("-c", "fetch.fsckObjects=true"));
        // A pack is kept as received, so index-pack sees every object and the trace can count the receives.
        fetch.addAll(List.of("-c", "fetch.unpackLimit=1", "fetch", "--progress", "--no-tags", "--no-write-fetch-head",
                "--no-auto-maintenance", "--no-write-commit-graph", "--no-recurse-submodules",
                "--", url, "+" + name + ":" + name));
        run(result, null, null, fetch, transfer, progress, environment);
        writeHead(result, name);
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

    /** Whether a refresh holds its target: yes, not until a later reading ({@code MOVED}), or only by a rebuild. */
    enum Refreshed { YES, MOVED, NO }

    /**
     * Extend a verified generation towards {@code target}, a {@link #selection}, without rewriting or decoding
     * its old packs. Only the target's ref is fetched, by name. With {@code exact}, a fetch that found the ref
     * at another ID is {@link Refreshed#MOVED}; without, the snapshot records what was fetched, as a clone does.
     * {@code previousManifest} needs only its {@code objects/} entries.
     */
    private Refreshed refreshSnapshot(String url, Path previous, Map<String, String> previousManifest,
                                      String target, boolean exact, Path work, Path result,
                                      Phases phase, Map<String, Long> timings) throws IOException {
        var ref = target.split("\n", 2)[1];
        var name = refName(target);
        long start = System.nanoTime();
        var progress = live(phase, "Fetching incremental Git snapshot");
        try {
            // Only owned immutable packs are linked. Config, HEAD and refs are recreated independently.
            sanitize(previous, result);
            run(result, null, null, List.of("-c", "fetch.fsckObjects=true", "-c", "fetch.unpackLimit=1",
                    "fetch", "--progress", "--no-tags", "--no-write-fetch-head",
                    "--no-auto-maintenance", "--no-write-commit-graph", "--no-recurse-submodules",
                    "--", url, "+" + name + ":refs/isx/fetched"),
                    transfer, progress);
        } finally {
            elapsed(timings, "fetch", start);
        }
        start = System.nanoTime();
        phase.accept("Verifying incremental Git snapshot");
        try {
            // The fetch lands in a temporary ref, so its actual ID is what is checked. The batch below replaces the
            // previous ref, which on the host's own refresh may be another one when the default branch changed;
            // retainsHistory reads the old ID from previous, so it still walks it.
            var fetched = git(result, "rev-parse", "--verify", "refs/isx/fetched");
            if (!ref.equals(name + " " + fetched)) {
                if (exact) {
                    phase.accept("Remote ref moved during the incremental Git fetch");
                    return Refreshed.MOVED;
                }
                target = target.substring(0, target.indexOf('\n') + 1) + name + " " + fetched;
            }
            var input = work.resolve("refs.in");
            var lines = new ArrayList<String>();
            refMap(result).keySet().forEach(old -> { if (!old.equals(name)) lines.add("delete " + old); });
            lines.add("update " + name + " " + fetched);
            lines.add("delete refs/isx/fetched");
            Files.write(input, lines);
            run(result, input, null, List.of("update-ref", "--stdin"));
            Files.delete(input);
            writeHead(result, name);
            if (!target.equals(selection(result))) throw new IOException("Incremental Git snapshot has another selection");
            if (Files.exists(result.resolve("shallow")) || Files.exists(result.resolve("objects/info/alternates"))
                    || hasLooseObjects(result)) throw new IOException("Incremental Git snapshot is not standalone and packed");
            var packs = children(result.resolve("objects/pack")).stream()
                    .filter(p -> p.toString().endsWith(".pack")).toList();
            if (packs.size() > MAX_INCREMENTAL_PACKS) {
                phase.accept("Compacting Git snapshot");
                return Refreshed.NO;
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
                return Refreshed.NO;
            }
            return Refreshed.YES;
        } finally {
            elapsed(timings, "verify", start);
        }
    }

    /** A branch is HEAD's symbolic target; a tag leaves HEAD detached at its commit, as clone does. */
    private static void writeHead(Path repo, String name) throws IOException {
        Files.writeString(repo.resolve("HEAD"), (name.startsWith("refs/heads/") ? "ref: " + name
                : git(repo, "rev-parse", "--verify", name + "^{commit}")) + "\n");
    }

    /** The formerly selected ref's object, annotated tag included, must remain reachable from the new one. */
    private static boolean retainsHistory(Path previous, Path result, Path work) throws IOException {
        var old = refMap(previous).values();
        var next = refMap(result).values();
        if (next.containsAll(old)) return true;
        // A fast-forward keeps everything; a force-push or a moved annotated tag may not.
        // Stream a potentially large difference to disk instead of capturing it in the JVM heap.
        var input = work.resolve("retained-tips");
        var output = work.resolve("removed-objects");
        try {
            Files.write(input, old);
            run(result, input, output, List.of("rev-list", "--objects", "--no-object-names", "--stdin", "--not", "--all"));
            return Files.size(output) == 0;
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    /** A host reading: its {@link #selection}, or null, and the tags it shows, by name, at their IDs. */
    private record Reading(String selection, Map<String, String> tags) {}

    /**
     * The remote's view of the requested ref: the branch, else the tag, named {@code branch}, or with none
     * the branch the remote's HEAD names; and its tags. Only those refs are read, as the guest reads them.
     */
    private Reading advertisement(Path work, String url, String branch, Phases phase) throws IOException {
        var command = new ArrayList<>(List.of("ls-remote", "--symref", "--", url));
        command.addAll(lsRemotePatterns(branch));
        var output = run(work, null, null, command, transfer, live(phase, "Reading remote refs"));
        var tags = new TreeMap<String, String>();
        tagReadings(output).forEach((name, tag) -> tags.put(name, tag.id()));
        return new Reading(parseAdvertisement(output, branch), tags);
    }

    /**
     * What {@code ls-remote} is asked for a request of {@code branch}; the guest asks for the same. Every tag
     * is read too, for {@link #withTags}: a pattern sends the server no ref prefix, so the server advertises
     * every ref whatever the patterns, and they only choose what Git prints.
     */
    public static List<String> lsRemotePatterns(String branch) {
        return branch == null || branch.isBlank() ? List.of("HEAD", "refs/tags/*")
                : List.of("refs/heads/" + branch, "refs/tags/*");
    }

    /** A tag in a reading: its own ID, and what it peels to, which is the same ID for a lightweight tag. */
    private record TagRead(String id, String peeled) {}

    /** The tags an {@code ls-remote} reading shows, by full name; one naming something other than an object ID fails it. */
    private static Map<String, TagRead> tagReadings(String output) throws IOException {
        var ids = new TreeMap<String, String>();
        var peeled = new TreeMap<String, String>();
        for (var line : output.lines().toList()) {
            var parts = line.split("\t", 2);
            if (parts.length != 2 || !parts[1].startsWith("refs/tags/") || parts[0].startsWith("ref: ")) continue;
            if (!OBJECT_ID.matcher(parts[0]).matches()) throw new IOException("Invalid Git object ID");
            if (parts[1].endsWith("^{}")) peeled.put(parts[1].substring(0, parts[1].length() - 3), parts[0]);
            else ids.put(parts[1], parts[0]);
        }
        var tags = new TreeMap<String, TagRead>();
        ids.forEach((name, id) -> tags.put(name, new TagRead(id, peeled.getOrDefault(name, id))));
        return tags;
    }

    /**
     * The {@link #selection} an {@code ls-remote --symref} reading gives a request of {@code branch}: a branch
     * before a tag, as clone resolves it, or the branch HEAD names. Null when it names no such ref, including
     * a remote whose HEAD is unborn or detached.
     */
    private static String parseAdvertisement(String output, String branch) throws IOException {
        var refs = new TreeMap<String, String>();
        String head = null;
        for (var line : output.lines().toList()) {
            var parts = line.split("\t", 2);
            if (parts.length != 2) throw new IOException("Invalid Git advertisement");
            if (parts[0].startsWith("ref: ")) {
                if (parts[1].equals("HEAD")) head = parts[0].substring(5);
            } else if (!parts[1].endsWith("^{}") && (parts[1].equals("HEAD")
                    || parts[1].startsWith("refs/heads/") || parts[1].startsWith("refs/tags/"))) {
                // A pattern also matches the tail of other refs, such as a mirror's refs/remotes/origin/HEAD.
                if (!OBJECT_ID.matcher(parts[0]).matches()) throw new IOException("Invalid Git object ID");
                refs.put(parts[1], parts[0]);
            }
        }
        String name;
        if (branch == null || branch.isBlank()) {
            if (head == null || !FETCHABLE_BRANCH.matcher(head).matches() || !refs.containsKey("HEAD")) return null;
            name = head;
            refs.putIfAbsent(name, refs.get("HEAD"));
        } else {
            name = refs.containsKey("refs/heads/" + branch) ? "refs/heads/" + branch : "refs/tags/" + branch;
        }
        var oid = refs.get(name);
        if (oid == null) return null;
        return (oid.length() == 64 ? "sha256" : "sha1") + "\n" + name + " " + oid;
    }

    /** A snapshot's object format and its one ref; null unless it holds exactly one branch or tag. */
    private static String selection(Path repo) throws IOException {
        var refs = refMap(repo);
        if (refs.size() != 1) return null;
        var ref = refs.entrySet().iterator().next();
        return git(repo, "rev-parse", "--show-object-format") + "\n" + ref.getKey() + " " + ref.getValue();
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
