package dev.incusspawn;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Whether a directory is one user's alone to change: theirs, a real directory and not a link,
 * and writable by nobody else. Only then can nothing in it be swapped, for a link or anything
 * else, between a check and what follows it.
 */
public final class OwnDirectory {

    private OwnDirectory() {}

    /** Why a directory is not one's own, as a phrase about what is in it ("is in ..., which ..."). */
    public static final class NotOwn extends IOException {
        NotOwn(String reason) {
            super(reason);
        }
    }

    /**
     * That {@code path} is a directory of {@code uid}'s that nobody else can write to. Group write
     * is accepted only when the directory's group is {@code privateGroup}, by number; null accepts
     * none. {@code who} names the process that checks, in what it is told to do.
     */
    public static void require(Path path, Integer uid, Integer privateGroup, String who) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new NotOwn("is under " + isALink(path, who));
        }
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new NotOwn("is not in a directory: " + path + " is something else");
        }
        if (uid != null && uidOf(path) != uid) {
            throw new NotOwn("is in " + path + ", which belongs to uid " + uidOf(path) + ", not to uid " + uid
                    + ", whom " + who + " runs as (chown it)");
        }
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            var permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            var groupWrite = permissions.contains(PosixFilePermission.GROUP_WRITE)
                    && (privateGroup == null || !privateGroup.equals(gidOf(path)));
            if (groupWrite || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new NotOwn("is in " + path + ", which others can write to (chmod go-w it)");
            }
        }
    }

    /**
     * Makes {@code dir}, with its parents, when it is missing, writable by its owner alone whatever
     * the umask: a umask of 002, which Ubuntu and Fedora give a user with a private group, would
     * otherwise make it group-writable, which {@link #require} refuses unless its gid is the uid
     * (#2136). Each missing parent is made the same way, and the umask still narrows them further.
     * An existing directory, or a link to one, is left as it is, to be checked rather than
     * repaired; anything else there fails, as {@link Files#createDirectories} does.
     */
    public static Path create(Path dir) throws IOException {
        if (Files.isDirectory(dir)) return dir;
        var parent = dir.toAbsolutePath().getParent();
        if (parent != null) create(parent);
        try {
            if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
            } else {
                Files.createDirectory(dir);
            }
        } catch (FileAlreadyExistsException raced) {
            // another isx made it: left as it is, like any existing one
            if (!Files.isDirectory(dir)) throw raced;
        }
        return dir;
    }

    /** That {@code link} is one, and what to do. Never toRealPath: a dangling link gets this too. */
    public static String isALink(Path link, String who) {
        String target;
        try {
            target = String.valueOf(Files.readSymbolicLink(link));
        } catch (IOException e) {
            target = "an unreadable target";
        }
        return link + ", which is a link (to " + target + "): " + who + " checks the real directory only, so make "
                + link + " that directory";
    }

    /**
     * The uid this process runs as, from the owner of {@code /proc/self}: no
     * fork, and an error rather than a guess where it cannot be read (Linux only).
     */
    public static int processUid() throws IOException {
        return (Integer) Files.getAttribute(Path.of("/proc/self"), "unix:uid");
    }

    public static int uidOf(Path path) throws IOException {
        return (Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    }

    private static int gidOf(Path path) throws IOException {
        return (Integer) Files.getAttribute(path, "unix:gid", LinkOption.NOFOLLOW_LINKS);
    }
}
