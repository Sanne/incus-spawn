package dev.incusspawn.proxy;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where the proxy keeps the downloads of {@code confirmed-cache:} domains (#2518), and nothing
 * else: files only, every call blocking. The requests are {@link ConfirmedCacheHandler}'s.
 * <p>
 * One directory per object, named by a hash of its host, its path and its query less the
 * parameters that sign the link, since a signed link changes on every request while the object
 * it names does not ({@link #unsigned}). In it, one immutable
 * file per {@code ETag} it was stored at, named by a hash of that tag, with a {@code .head} file
 * beside it, and {@code current}, which names the newest. A copy is never rewritten in place: a
 * serve that upstream confirmed for one tag reads that tag's file, so a refill that lands meanwhile
 * can never splice two versions into one download.
 */
final class ConfirmedCacheStore {

    private ConfirmedCacheStore() {}

    /** The file naming the newest copy of an object. */
    static final String CURRENT = "current";
    private static final String HEAD = ".head";
    // Beside a copy a newer one replaced: its time is when that happened
    private static final String SUPERSEDED = ".superseded";
    private static final String TEMP_PREFIX = "fill-";
    /** How long a superseded copy stays, for a serve upstream confirmed before the new one landed. */
    static final long SUPERSEDED_KEEP_MILLIS = 10 * 60 * 1000;

    // What a copy's file is named: the SHA-256 of its ETag
    private static final java.util.regex.Pattern COPY_NAME = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    // A .head file is a handful of headers: anything longer is not one
    private static final int MAX_HEAD_BYTES = 64 * 1024;

    /**
     * The response headers that describe an object rather than one exchange, kept with its copy and
     * served with it: everything upstream sent but the hop-by-hop ones, those the proxy writes
     * itself for each serve, and those that name one request.
     */
    private static final Set<String> NOT_STORED = Set.of("connection", "keep-alive", "proxy-connection",
            "transfer-encoding", "te", "trailer", "upgrade", "content-length", "content-range", "accept-ranges",
            "etag", "date", "age", "set-cookie", "x-ms-request-id", "x-ms-client-request-id");

    static Path dir() {
        return Environment.confirmedCacheDir();
    }

    /**
     * The query parameters that sign or time-limit a link and so change from one link to the next
     * while the object stays the same: an Azure shared access signature's, and the {@code X-Amz-}
     * and {@code X-Goog-} ones of S3's and Cloud Storage's signed URLs. Every other parameter is
     * part of the key, so a request for something else at the same path (Azure's
     * {@code ?comp=metadata}, which answers with the blob's own {@code ETag}) never shares a copy.
     */
    private static final Set<String> SIGNING = Set.of("sv", "ss", "srt", "sp", "se", "st", "spr", "sig", "sr",
            "si", "sip", "skoid", "sktid", "skt", "ske", "sks", "skv", "saoid", "suoid", "scid", "sdd", "ses");

    /** The directory of the object at {@code path} on {@code host}, with {@code query} (raw, or null) less its signing parameters. */
    static Path objectDir(String host, String path, String query) {
        return dir().resolve(hash(host + "\n" + path + "\n" + unsigned(query)));
    }

    /** {@code query}'s parameters that do not sign the link, sorted, joined by {@code &}; empty for none. */
    static String unsigned(String query) {
        if (query == null || query.isEmpty()) return "";
        var kept = new ArrayList<String>();
        for (var parameter : query.split("&", -1)) {
            if (parameter.isEmpty()) continue;
            var equals = parameter.indexOf('=');
            var name = (equals < 0 ? parameter : parameter.substring(0, equals)).toLowerCase(Locale.ROOT);
            if (!SIGNING.contains(name) && !name.startsWith("x-amz-") && !name.startsWith("x-goog-")) kept.add(parameter);
        }
        kept.sort(null);
        return String.join("&", kept);
    }

    /** One stored copy: its bytes, the {@code ETag} upstream gave them, their length and headers. */
    record Copy(Path body, String etag, long length, List<Map.Entry<String, String>> headers) {}

    /** Whether upstream's header {@code name} is kept with a copy. */
    static boolean stored(String name) {
        return !NOT_STORED.contains(name.toLowerCase(Locale.ROOT));
    }

    /** The newest whole copy of the object in {@code objectDir}, or null. */
    static Copy current(Path objectDir) {
        try {
            var current = objectDir.resolve(CURRENT);
            if (!Files.isRegularFile(current) || Files.size(current) > 128) return null;
            var name = Files.readString(current, StandardCharsets.US_ASCII).strip();
            if (!COPY_NAME.matcher(name).matches()) return null;
            return read(objectDir.resolve(name));
        } catch (IOException e) {
            return null;
        }
    }

    private static Copy read(Path body) throws IOException {
        var head = body.resolveSibling(body.getFileName() + HEAD);
        if (!Files.isRegularFile(body) || !Files.isRegularFile(head) || Files.size(head) > MAX_HEAD_BYTES) return null;
        var lines = Files.readAllLines(head, StandardCharsets.UTF_8);
        if (lines.size() < 2) return null;
        long length;
        try {
            length = Long.parseLong(lines.get(1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (Files.size(body) != length) return null;
        var headers = new ArrayList<Map.Entry<String, String>>();
        for (var line : lines.subList(2, lines.size())) {
            var colon = line.indexOf(':');
            if (colon > 0) headers.add(Map.entry(line.substring(0, colon), line.substring(colon + 1).strip()));
        }
        return new Copy(body, lines.get(0), length, List.copyOf(headers));
    }

    /** A new temporary file in {@code objectDir} for a fill to write to. */
    static Path newTemp(Path objectDir) throws IOException {
        synchronized (lockFor(objectDir)) {
            Files.createDirectories(objectDir);
            return Files.createTempFile(objectDir, TEMP_PREFIX, ".tmp");
        }
    }

    // Commits, evictions and new fills of one object run one at a time, so two commits never mark
    // each other superseded and eviction never removes a directory a fill is about to write into
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private static Object lockFor(Path objectDir) {
        return LOCKS[Math.floorMod(objectDir.getFileName().toString().hashCode(), LOCKS.length)];
    }

    /**
     * Makes {@code temp}, {@code length} bytes upstream sent with {@code etag} and {@code headers},
     * the object's newest copy, and removes copies it superseded more than
     * {@link #SUPERSEDED_KEEP_MILLIS} ago. Returns the copy.
     */
    static Copy commit(Path objectDir, Path temp, String etag, long length,
                       List<Map.Entry<String, String>> headers) throws IOException {
        synchronized (lockFor(objectDir)) {
            return commitHeld(objectDir, temp, etag, length, headers);
        }
    }

    private static Copy commitHeld(Path objectDir, Path temp, String etag, long length,
                                   List<Map.Entry<String, String>> headers) throws IOException {
        var name = hash(etag);
        var body = objectDir.resolve(name);
        var head = new StringBuilder(etag).append('\n').append(length).append('\n');
        for (var header : headers) head.append(header.getKey()).append(": ").append(header.getValue()).append('\n');
        writeAtomically(objectDir, body.getFileName() + HEAD, head.toString());
        // The same tag twice is the same bytes: the copy there may be serving, so it stays
        if (Files.isRegularFile(body) && Files.size(body) == length) {
            Files.deleteIfExists(temp);
        } else {
            Files.move(temp, body, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        writeAtomically(objectDir, CURRENT, name);
        removeSuperseded(objectDir, name);
        return new Copy(body, etag, length, List.copyOf(headers));
    }

    private static void writeAtomically(Path dir, String name, String text) throws IOException {
        var temp = Files.createTempFile(dir, "write-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Marks each copy {@code keep} superseded, the first time it is, and removes those superseded
     * {@link #SUPERSEDED_KEEP_MILLIS} ago or more: the time is the marker's, never the copy's last
     * serve, so a copy a {@code 304} just confirmed keeps its window.
     */
    private static void removeSuperseded(Path objectDir, String keep) throws IOException {
        var before = System.currentTimeMillis() - SUPERSEDED_KEEP_MILLIS;
        try (var files = Files.list(objectDir)) {
            for (var file : files.toList()) {
                var name = file.getFileName().toString();
                if (!COPY_NAME.matcher(name).matches() || name.equals(keep)) continue;
                var marker = file.resolveSibling(name + SUPERSEDED);
                if (!Files.exists(marker)) {
                    try {
                        Files.createFile(marker);
                    } catch (java.nio.file.FileAlreadyExistsException markedMeanwhile) {
                        // Another commit of this object marked it first: its time stands
                    }
                } else if (modified(marker) < before) {
                    remove(file);
                }
            }
        }
        // The kept copy may have been superseded before: it is current again
        Files.deleteIfExists(objectDir.resolve(keep + SUPERSEDED));
    }

    /** Records a serve of {@code copy}, so the least recently served copies are the ones evicted. */
    static void touch(Copy copy) {
        try {
            Files.setLastModifiedTime(copy.body(), FileTime.from(Instant.now()));
        } catch (IOException e) {
            // Gone meanwhile: nothing to keep
        }
    }

    /**
     * Removes the least recently served copies until those left take at most {@code maxBytes}, and
     * temporary files of fills that ended without a commit an hour ago or more. An object left with
     * no copy and no fill under way loses its directory too, {@code current} and marks with it, so
     * the directories stay as few as the copies.
     */
    static void evict(long maxBytes) throws IOException {
        var root = dir();
        if (!Files.isDirectory(root)) return;
        record Stored(Path body, long size, long modified) {}
        var copies = new ArrayList<Stored>();
        var staleTemp = System.currentTimeMillis() - 60 * 60 * 1000;
        var withoutCopies = new ArrayList<Path>();
        try (Stream<Path> objects = Files.list(root)) {
            for (var object : objects.toList()) {
                if (!Files.isDirectory(object)) continue;
                try (var files = Files.list(object)) {
                    var hasCopy = false;
                    for (var file : files.toList()) {
                        var name = file.getFileName().toString();
                        if (COPY_NAME.matcher(name).matches()) {
                            hasCopy = true;
                            copies.add(new Stored(file, size(file), modified(file)));
                        } else if (name.startsWith(TEMP_PREFIX) && modified(file) < staleTemp) {
                            Files.deleteIfExists(file);
                        } else if (name.endsWith(SUPERSEDED)
                                && !Files.exists(file.resolveSibling(name.substring(0, name.length() - SUPERSEDED.length())))) {
                            // A mark a concurrent commit left beside a copy it removed
                            Files.deleteIfExists(file);
                        }
                    }
                    // One an earlier pass or isx left empty: gone with this pass
                    if (!hasCopy) withoutCopies.add(object);
                } catch (NoSuchFileException gone) {
                    // Removed meanwhile (isx clean cache): the other objects are still counted
                }
            }
        }
        var total = copies.stream().mapToLong(Stored::size).sum();
        copies.sort(Comparator.comparingLong(Stored::modified));
        var emptied = new java.util.LinkedHashSet<Path>();
        for (var copy : copies) {
            if (total <= maxBytes) break;
            var object = copy.body().getParent();
            synchronized (lockFor(object)) {
                remove(copy.body());
            }
            emptied.add(object);
            total -= copy.size();
        }
        for (var object : emptied) removeIfEmpty(object);
        for (var object : withoutCopies) removeIfEmpty(object);
    }

    /** Removes {@code object}'s directory, with what is left in it, when it holds no copy and no fill. */
    static void removeIfEmpty(Path object) throws IOException {
        synchronized (lockFor(object)) {
            List<Path> left;
            try (var files = Files.list(object)) {
                left = files.toList();
            } catch (NoSuchFileException gone) {
                return;
            }
            for (var file : left) {
                var name = file.getFileName().toString();
                if (COPY_NAME.matcher(name).matches() || name.startsWith(TEMP_PREFIX)) return;
            }
            for (var file : left) Files.deleteIfExists(file);
            try {
                Files.deleteIfExists(object);
            } catch (java.nio.file.DirectoryNotEmptyException written) {
                // A file came meanwhile, outside the lock: the directory is in use again
            }
        }
    }

    private static void remove(Path body) throws IOException {
        Files.deleteIfExists(body.resolveSibling(body.getFileName() + SUPERSEDED));
        Files.deleteIfExists(body.resolveSibling(body.getFileName() + HEAD));
        Files.deleteIfExists(body);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (NoSuchFileException e) {
            return 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
