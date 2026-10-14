package dev.incusspawn.proxy;

import dev.incusspawn.Environment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The secret a host route's controller checks before it trusts any identity header (#936): the
 * proxy sends it as {@link #HEADER} on every request it forwards on the route, and nothing else
 * can, since it lives only in an owner-only file on the host,
 * {@code ~/.config/incus-spawn/host-routes/<name>.secret}, which the controller reads too.
 *
 * <p>Never inside an instance: no guest is given it, the proxy strips every client
 * {@code X-Isx-*} header (this one included) on the way in and this one on the way back, and it is
 * never logged. It is one per route, so a controller for one route cannot pass as another's.
 *
 * <p>Created by the proxy the first time it serves the route, and kept: the controller and the
 * proxy read the same file, and deleting or rewriting it takes effect at once (the proxy's
 * watcher reloads on it; on a {@code chmod} only on Linux). Refused, and every request to the
 * route with it (503), fail closed: a secret that is a link or anything but a regular file, is
 * not owned by the proxy's own user or is readable by anyone else, sits in a directory (or a
 * config directory) not owned by that user or writable by others, or does not hold a usable
 * secret. Never a route without a secret.
 */
public final class HostRouteSecret {

    /** The header the proxy sends it in. Under {@code X-Isx-}, so a client copy is always stripped. */
    public static final String HEADER = "X-Isx-Route-Secret";
    public static final String DIR = "host-routes";
    public static final String SUFFIX = ".secret";
    private static final String TEMPORARY = ".secret-";
    private static final int MAX_BYTES = 4096;
    /** At least 32 characters (a generated one is 64 hex), and only what a header value carries unchanged. */
    private static final Pattern USABLE = Pattern.compile("[A-Za-z0-9._~+/=-]{32,256}");
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private HostRouteSecret() {}

    /** Where the secret of route {@code name} lives. */
    public static Path file(String name) {
        return file(Environment.configDir(), name);
    }

    static Path file(Path configDir, String name) {
        return configDir.resolve(DIR).resolve(name + SUFFIX);
    }

    /**
     * The secret of route {@code name}, created if there is none yet; null when it cannot be used,
     * said through {@code warn} without the value. <strong>Blocks</strong> on the file system.
     */
    public static String loadOrCreate(String name, Consumer<String> warn) {
        return loadOrCreate(Environment.configDir(), name, warn);
    }

    static String loadOrCreate(Path configDir, String name, Consumer<String> warn) {
        // Before the uid probe, which would make its file through the link
        if (Files.isSymbolicLink(configDir)) {
            warn.accept(refusing(name) + "its config directory " + isALink(configDir));
            return null;
        }
        Integer uid;
        try {
            uid = ownUid(configDir);
        } catch (IOException | RuntimeException e) {
            warn.accept(refusing(name) + "the proxy cannot tell which uid it runs as: " + e.getMessage());
            return null;
        }
        return loadOrCreate(configDir, name, warn, uid, uid);
    }

    /**
     * As above, with the files required to belong to {@code uid}, and the config directory allowed
     * group write only by {@code privateGroup} (the proxy's uid, as a user-private group's gid is):
     * tests name others.
     */
    static String loadOrCreate(Path configDir, String name, Consumer<String> warn, Integer uid, Integer privateGroup) {
        var file = file(configDir, name);
        try {
            var dir = ownerOnlyDirectory(configDir, file.getParent(), uid, privateGroup);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) create(dir, file);
            return read(file, uid);
        } catch (Unusable e) {
            warn.accept(refusing(name) + "its secret " + file + " " + e.getMessage());
            return null;
        } catch (IOException | RuntimeException e) {
            warn.accept(refusing(name) + "its secret " + file + " cannot be used: " + e.getMessage());
            return null;
        }
    }

    /** The route is kept and answers 503, never relayed: what the log says must say so too. */
    private static String refusing(String name) {
        return "Refusing every request to host route '" + name + "' (503): ";
    }

    /** Why a secret file is refused, in words that never include its content. */
    static final class Unusable extends IOException {
        Unusable(String reason) {
            super(reason);
        }
    }

    private static volatile Integer ownUid;

    /**
     * The uid this process creates files as: of a file it has just made, so neither its user name
     * (which may not resolve, or name someone else) nor a directory service is asked. Once per
     * process, so no reload pays for it again. Null where the file system has no uids.
     */
    static Integer ownUid(Path configDir) throws IOException {
        if (!hasUids(configDir)) return null;
        var uid = ownUid;
        if (uid == null) {
            Files.createDirectories(configDir);
            var probe = Files.createTempFile(configDir, ".uid-", ".tmp");
            try {
                uid = uidOf(probe);
            } finally {
                Files.deleteIfExists(probe);
            }
            ownUid = uid;
        }
        return uid;
    }

    private static boolean hasUids(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("unix");
    }

    private static int uidOf(Path path) throws IOException {
        return (Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * That {@code path} is a directory of {@code uid}'s that nobody else can write to. Group write
     * is accepted only when the directory's group is {@code privateGroup}, by number; null accepts
     * none.
     */
    private static void requireOwnDirectory(Path path, Integer uid, Integer privateGroup) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new Unusable("is under " + isALink(path));
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new Unusable("is not in a directory: " + path + " is something else");
        }
        if (uid != null && uidOf(path) != uid) {
            throw new Unusable("is in " + path + ", which belongs to uid " + uidOf(path) + ", not to uid " + uid
                    + ", whom the proxy runs as (chown it)");
        }
        if (isPosix(path)) {
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            var groupWrite = permissions.contains(PosixFilePermission.GROUP_WRITE)
                    && (privateGroup == null || !privateGroup.equals(gidOf(path)));
            if (groupWrite || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new Unusable("is in " + path + ", which others can write to (chmod go-w it)");
            }
        }
    }

    /** That {@code link} is one, and what to do. Never toRealPath: a dangling link gets this too. */
    private static String isALink(Path link) {
        String target;
        try {
            target = String.valueOf(Files.readSymbolicLink(link));
        } catch (IOException e) {
            target = "an unreadable target";
        }
        return link + ", which is a link (to " + target + "): the proxy checks the real directory only, so make "
                + link + " that directory";
    }

    private static int gidOf(Path path) throws IOException {
        return (Integer) Files.getAttribute(path, "unix:gid", LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * The directory, made owner-only when it is made here. It and the config directory around it
     * must be the proxy's user's alone to write, or anyone could swap a secret, or the directory,
     * for a link, a FIFO or their own between the checks and the open.
     */
    static Path ownerOnlyDirectory(Path configDir, Path dir, Integer uid, Integer privateGroup) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(dir.getParent());
            try {
                if (isPosix(dir.getParent())) {
                    Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectory(dir);
                }
            } catch (FileAlreadyExistsException raced) {
                // another proxy made it: checked below like any existing one
            }
        }
        // The config directory may be writable by the user's private group (gid == uid, as useradd
        // makes it), which a umask of 002, Fedora's default, gives every directory the user makes.
        // Never a shared group, and never on host-routes/, which the proxy makes 0700 itself
        requireOwnDirectory(configDir, uid, privateGroup);
        requireOwnDirectory(dir, uid, null);
        return dir;
    }

    static void create(Path dir, Path file) throws IOException {
        removeStaleTemporaries(dir);
        // Written whole under another name, owner-only from the start, then linked into place,
        // which never replaces: a controller never reads a half-written file, and two proxies
        // creating it at once agree on the one that landed first (a rename would replace it)
        var tmp = isPosix(dir)
                ? Files.createTempFile(dir, TEMPORARY, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                : Files.createTempFile(dir, TEMPORARY, ".tmp");
        try {
            Files.writeString(tmp, InstanceSecret.generate() + "\n", StandardCharsets.US_ASCII);
            try {
                Files.createLink(file, tmp);
            } catch (FileAlreadyExistsException raced) {
                // the other one is the secret now
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Temporary files a proxy killed mid-create left behind: after the link, one is a second name
     * for a live secret, which deleting {@code <name>.secret} to rotate it would leave on disk. An
     * hour old at least, so a create under way in another proxy is never cut short.
     */
    private static void removeStaleTemporaries(Path dir) throws IOException {
        var cutoff = System.currentTimeMillis() - 3_600_000L;
        try (var entries = Files.newDirectoryStream(dir, TEMPORARY + "*.tmp")) {
            for (var entry : entries) {
                if (Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toMillis() < cutoff) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    /**
     * The secret in {@code file}, judged entirely before it is opened: opening a FIFO blocks until
     * something writes to it, and this runs at startup and inside {@code reload()}, which would then
     * never return. Java cannot open with O_NONBLOCK or judge the open descriptor, so the checks are
     * on the path; the directories are the proxy's user's alone, so nobody else can swap the file
     * in between. The open does not follow a link either way.
     */
    static String read(Path file, Integer uid) throws IOException {
        if (Files.isSymbolicLink(file)) throw new Unusable("is a link, not a regular file");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new Unusable("is not a regular file");
        if (uid != null && uidOf(file) != uid) {
            throw new Unusable("belongs to uid " + uidOf(file) + ", not to uid " + uid + ", whom the proxy runs as (chown it)");
        }
        if (isPosix(file)) {
            var permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            if (!permissions.contains(PosixFilePermission.OWNER_READ)
                    || permissions.stream().anyMatch(p -> !OWNER_ONLY.contains(p))) {
                throw new Unusable("must be readable by its owner only (chmod 600 it)");
            }
        }
        String secret;
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (channel.size() > MAX_BYTES) throw new Unusable("is too large to be a secret");
            var bytes = java.nio.ByteBuffer.allocate((int) channel.size());
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
                // read it all
            }
            secret = new String(bytes.array(), 0, bytes.position(), StandardCharsets.US_ASCII).strip();
        }
        if (!USABLE.matcher(secret).matches()) {
            throw new Unusable("must hold 32 to 256 characters of [A-Za-z0-9._~+/=-] (delete it for a new one)");
        }
        return secret;
    }

    /** The uid this process runs as, for tests that name another. */
    static Integer ownUidForTest(Path configDir) throws IOException {
        return ownUid(configDir);
    }

    private static boolean isPosix(Path file) {
        return file.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

}
