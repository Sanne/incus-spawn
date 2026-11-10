package dev.incusspawn.lifecycle;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqObject;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ShellSession;
import dev.incusspawn.Environment;
import dev.incusspawn.FileTrees;
import dev.incusspawn.OwnDirectory;
import dev.incusspawn.Platform;
import dev.incusspawn.util.Output;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Shares the container's zmx socket directory with the host via an Incus
 * disk device, so host-side {@code zmx list}, {@code zmx attach}, etc.
 * work natively against container sessions.
 *
 * <p>Each instance's directory, {@code ~/.local/state/incus-spawn/zmx/<name>},
 * is bind-mounted at the guest's {@code ~/.zmx}: its sockets and the logs zmx
 * keeps beside them appear on the host immediately, and the tree is the
 * user's alone.  The host zmx dir, which can be a {@code /tmp/zmx-<uid>}
 * another user made first, holds only a link named {@code isx-<container>}
 * to the container's {@code isx} session socket, so it shows up in
 * {@code zmx list} (#1821), and one named {@code isx-<container>.<session>}
 * to each other session the container has (#2059).</p>
 */
public final class ZmxSocketForward {

    private ZmxSocketForward() {}

    private static final String DEVICE_NAME = "zmx-sockets";
    private static final String CONTAINER_ZMX_DIR = "/home/agentuser/.zmx";
    private static final String SYMLINK_PREFIX = "isx-";
    /** Between the instance and a session in a link's name: no instance name has a dot (#2059). */
    private static final String SESSION_SEPARATOR = ".";
    /** The session every shell enters by default, linked as {@code isx-<name>}. */
    private static final String DEFAULT_SESSION = "isx";
    /** The most sessions of one instance linked: the guest makes them, so it must not fill the host dir. */
    static final int MAX_SESSION_LINKS = 64;
    private static final int S_IFMT = 0170000;
    private static final int S_IFSOCK = 0140000;
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");

    /**
     * Where the instances' zmx directories live ({@code instances}), the host
     * zmx dir their links go in ({@code zmx}), and the uid both must belong to.
     */
    record HostDirs(Path instances, Path zmx, Callable<Integer> processUid) {
        static HostDirs of(Path zmx) {
            return new HostDirs(Environment.vmStateDir().resolve("zmx"), zmx, OwnDirectory::processUid);
        }

        /** The instance's own directory: the device's source. */
        Path instanceDir(String name) {
            return instances.toAbsolutePath().resolve(name);
        }
    }

    /**
     * Resolve the host zmx socket directory using the same priority as zmx
     * itself: {@code ZMX_DIR} > {@code XDG_RUNTIME_DIR/zmx} >
     * {@code TMPDIR/zmx-<uid>} > {@code /tmp/zmx-<uid>}.
     */
    static Path hostZmxDir() {
        return hostZmxDir(System::getenv, InstanceLifecycle::getUid);
    }

    static Path hostZmxDir(UnaryOperator<String> env, Supplier<String> uid) {
        var zmxDir = env.apply("ZMX_DIR");
        if (zmxDir != null && !zmxDir.isBlank()) return Path.of(zmxDir);
        var xdgRuntime = env.apply("XDG_RUNTIME_DIR");
        if (xdgRuntime != null && !xdgRuntime.isBlank()) return Path.of(xdgRuntime, "zmx");
        var tmpdir = env.apply("TMPDIR");
        if (tmpdir != null && !tmpdir.isBlank()) return Path.of(tmpdir, "zmx-" + uid.get());
        return Path.of("/tmp", "zmx-" + uid.get());
    }

    /**
     * The host zmx dir where isx shares zmx sockets, or null on macOS, which
     * shares none.  The platform is checked first because resolving the dir
     * on macOS forks {@code id -u} on the {@code TMPDIR} and {@code /tmp}
     * fallbacks (Linux reads {@code /proc/self/status}), and macOS sets
     * {@code TMPDIR} without {@code XDG_RUNTIME_DIR}: every shell would pay
     * for a process it has no use for.
     */
    private static Path sharedHostZmxDir() {
        return sharedHostZmxDir(Platform.isMacOS(), ZmxSocketForward::hostZmxDir);
    }

    static Path sharedHostZmxDir(boolean macOS, Supplier<Path> hostZmxDir) {
        return macOS ? null : hostZmxDir.get();
    }

    public static boolean isZmxInstalled(String buildSourceJson) {
        return BuildSource.installs(buildSourceJson, "zmx");
    }

    /**
     * Configure a disk device that shares the instance's own host directory
     * at the container's zmx socket path, and link it from the host zmx dir
     * so the default {@code isx} session is visible to host zmx.
     *
     * <p>When that directory is not the user's alone, the instance gets no
     * device, is stamped {@link Metadata#ZMX_WITHHELD} so a later start tries
     * again, and {@code out} is told why.</p>
     */
    public static void configure(IncusClient incus, String name, Output out) {
        var zmxDir = sharedHostZmxDir();
        if (zmxDir != null) configure(incus, name, HostDirs.of(zmxDir), out);
    }

    /** Whether the device was added. */
    static boolean configure(IncusClient incus, String name, HostDirs dirs, Output out) {
        return configure(incus, name, dirs, false, out);
    }

    /** {@code withheld}: the instance already has no device and the stamp, so a refusal writes nothing. */
    private static boolean configure(IncusClient incus, String name, HostDirs dirs, boolean withheld, Output out) {
        int uid;
        Path instanceDir;
        try {
            uid = dirs.processUid().call();
            instanceDir = ownInstanceDir(dirs, name, uid);
        } catch (Exception e) {
            // Said first, so a failed write below still leaves why
            var why = e instanceof OwnDirectory.NotOwn ? "its directory " + e.getMessage()
                    : "could not make its directory: " + e;
            out.warn("'" + name + "' shares no zmx sessions with the host: " + why,
                    "isx keeps them only in a directory nobody else can change.",
                    "Its next start through isx shares them, once that holds.");
            // Fails closed: a guest's sessions go nowhere others can reach
            if (!withheld) {
                incus.updateFresh(name, new InstanceUpdate().removeDevice(DEVICE_NAME)
                        .config(Metadata.ZMX_WITHHELD, "true"));
            }
            return false;
        }

        // One write: the device whole, and the stamp of an earlier refusal gone
        incus.updateFresh(name, new InstanceUpdate().unset(Metadata.ZMX_WITHHELD).replaceDevice(DEVICE_NAME, Map.of(
                "type", "disk",
                "source", instanceDir.toString(),
                "path", CONTAINER_ZMX_DIR,
                // isx recreates a missing source before its own starts, and a
                // start that bypasses isx (plain `incus start`, boot.autostart)
                // must not fail on one either (#1044)
                "required", "false",
                // Linux only, where every container mount is shifted
                "shift", "true")));

        // Quiet: the shell that follows warns of a link it cannot make (ensureSymlink)
        try {
            link(dirs.zmx(), name, instanceDir, uid);
        } catch (IOException ignored) {
        }
        return true;
    }

    /**
     * The instance's directory, made {@code 0700} where missing, and checked
     * to be the user's alone, as is the directory that holds it.
     */
    private static Path ownInstanceDir(HostDirs dirs, String name, int uid) throws IOException {
        var dir = dirs.instanceDir(name);
        Files.createDirectories(dir.getParent().getParent());
        createPrivate(dir.getParent());
        createPrivate(dir);
        secureInstanceDir(dir, uid);
        return dir;
    }

    /**
     * That the instance's directory and the one holding it are the user's
     * alone, making the one holding it {@code 0700} again if it was opened
     * up, wherever this runs: adding the device, every start, every shell.  The guest owns its own directory through the shifted
     * mount and can open that to anyone, so {@code zmx/} is what keeps
     * other users out of its sessions and logs.
     */
    private static void secureInstanceDir(Path dir, int uid) throws IOException {
        var instances = dir.getParent();
        // A user-private group (gid == uid) may write, as umask 002 lets it;
        // for zmx/ that only spares a refusal, as it is closed right after
        OwnDirectory.require(instances, uid, uid, "isx");
        closeToOthers(instances);
        OwnDirectory.require(dir, uid, uid, "isx");
    }

    /** {@code dir}, just found to be a real directory of the user's, made {@code 0700} if group or others have any access. */
    private static void closeToOthers(Path dir) throws IOException {
        var view = Files.getFileAttributeView(dir, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) return;
        if (!view.readAttributes().permissions().equals(OWNER_ONLY)) view.setPermissions(OWNER_ONLY);
    }

    /** {@code dir} as a new directory only its owner can enter; an existing path is left to the check that follows. */
    private static void createPrivate(Path dir) throws IOException {
        try {
            Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (FileAlreadyExistsException checkedNext) {
        }
    }

    /**
     * Restore the host side of the {@code zmx-sockets} device before start.
     *
     * <p>A rename, an {@code isx clean state}, or a device from before
     * #1821, which pointed into the host zmx dir, leaves the device pointing
     * at a directory that is gone or not the instance's own.  Incus would
     * then refuse to start it with {@code Missing source path}, or mount
     * whatever is there.  A device withheld by an earlier start is added
     * once the directory is the user's alone again.</p>
     *
     * <p>Costs a few stats while the directory is right, and no Incus
     * request; the repair reuses {@link #configure}.</p>
     *
     * @param instanceMetadata the instance as fetched by
     *        {@link IncusClient#instanceMetadata} — shared with the other
     *        pre-start repairs so a start pays for only one round-trip
     */
    public static void ensureHostDirForStart(IncusClient incus, String name,
                                             JqValue instanceMetadata, Output out) {
        var zmxDir = sharedHostZmxDir();
        if (zmxDir != null) ensureHostDirForStart(incus, name, HostDirs.of(zmxDir), instanceMetadata, out);
    }

    static void ensureHostDirForStart(IncusClient incus, String name, HostDirs dirs,
                                      JqValue instanceMetadata, Output out) {
        var device = instanceMetadata.path("devices").path(DEVICE_NAME);
        var source = device.path("source").asText("");
        if (source.isEmpty()) {
            if (instanceMetadata.path("config").path(Metadata.ZMX_WITHHELD).asText("").isEmpty()) return;
            configure(incus, name, dirs, true, out);
            return;
        }

        // Healthy only if the device points at this name's directory and that
        // directory is there and still the user's alone: a source left over
        // from a rename can still exist (it may belong to another instance by
        // now), and the right directory can exist while the device points
        // elsewhere.  A device added before it was optional (#1044) is
        // upgraded too.  Anything else is handed to configure(), which is
        // idempotent and withholds the device from a directory others can change.
        var instanceDir = dirs.instanceDir(name);
        var optional = "false".equals(device.path("required").asText(""));
        if (optional && source.equals(instanceDir.toString())) {
            try {
                secureInstanceDir(instanceDir, dirs.processUid().call());
                return;
            } catch (Exception missingOrNotOwn) {
                // configure() makes it, or checks again and says why
            }
        }

        configure(incus, name, dirs, out);
    }

    /**
     * (Re-)create the link from the host zmx dir to the container's
     * {@code isx} session socket, for an instance that shares its sessions.
     * Cheap — no API calls.  Warns when the host zmx dir is not the user's
     * alone: the link is not made there, and host zmx cannot see the instance.
     */
    public static void ensureSymlink(String name, Output out) {
        var zmxDir = sharedHostZmxDir();
        if (zmxDir != null) ensureSymlink(name, HostDirs.of(zmxDir), out);
    }

    /**
     * {@link #ensureSymlink} again once a shell returns, so a session it made
     * (a new one the guest named, #2059) shows up in host zmx.  Quiet: the
     * shell's own call already said whatever is wrong.
     */
    public static void relinkAfterShell(String name) {
        ensureSymlink(name, Output.diagnosticsTo(said -> { }));
    }

    static void ensureSymlink(String name, HostDirs dirs, Output out) {
        var instanceDir = dirs.instanceDir(name);
        // One stat for an instance that shares no sessions
        if (!Files.isDirectory(instanceDir, LinkOption.NOFOLLOW_LINKS)) return;
        int uid;
        try {
            uid = dirs.processUid().call();
            // Not into a directory others can change: its start withheld the device and said so
            secureInstanceDir(instanceDir, uid);
        } catch (Exception withheld) {
            return;
        }
        try {
            link(dirs.zmx(), name, instanceDir, uid);
        } catch (OwnDirectory.NotOwn e) {
            out.warn("host zmx cannot see the sessions of '" + name + "': the host zmx dir " + e.getMessage(),
                    "isx links them only from a directory nobody else can change.",
                    "Set ZMX_DIR to a directory of your own, or remove " + dirs.zmx() + " if it is not yours.");
        } catch (Exception ignored) {
        }
    }

    /**
     * Link {@code isx-<name>} in the host zmx dir to the instance's session,
     * by absolute path, making the dir {@code 0700} where missing, and every
     * other session the instance has as {@code isx-<name>.<session>} (#2059).
     * Only in a dir that is the user's alone: anyone else could swap the link.
     */
    private static void link(Path zmxDir, String name, Path instanceDir, int uid) throws IOException {
        var dir = ownDir(zmxDir, uid);
        if (dir == null) {
            if (zmxDir.getParent() != null) Files.createDirectories(zmxDir.getParent());
            createPrivate(zmxDir);
            dir = ownDir(zmxDir, uid);
            if (dir == null) return;
        }
        linkTo(dir.resolve(SYMLINK_PREFIX + name), instanceDir.resolve(DEFAULT_SESSION));
        linkSessions(dir, name, instanceDir);
    }

    /** {@code link} pointing at {@code target}, unless something that is not a link is in its way. */
    private static void linkTo(Path link, Path target) throws IOException {
        if (Files.isSymbolicLink(link)) {
            if (target.equals(Files.readSymbolicLink(link))) return;
            Files.delete(link);
        } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.createSymbolicLink(link, target);
    }

    /**
     * Link each session socket in the instance's directory but its default
     * one, and drop the links of sessions that are gone.  The guest names
     * these, so only a socket (never a link it made) with a name a shell
     * could ask for ({@link ShellSession#NAME}, no dot) is linked, and at
     * most {@link #MAX_SESSION_LINKS} of them.  An instance name has no dot,
     * so {@code isx-<name>.} is this instance's alone: {@code web}'s links
     * are never {@code web-2}'s.
     */
    private static void linkSessions(Path dir, String name, Path instanceDir) throws IOException {
        var prefix = sessionLinkPrefix(name);
        // Sorted, so the same sessions are linked every time when there are more than the bound
        var live = new TreeSet<String>();
        try (var entries = Files.newDirectoryStream(instanceDir)) {
            for (var entry : entries) {
                var session = entry.getFileName().toString();
                if (DEFAULT_SESSION.equals(session) || !ShellSession.isValidName(session) || !isSocket(entry)) continue;
                live.add(session);
            }
        }
        while (live.size() > MAX_SESSION_LINKS) live.pollLast();
        for (var session : live) {
            try {
                linkTo(dir.resolve(prefix + session), instanceDir.resolve(session));
            } catch (IOException oneLink) {
                // The others are still linked, and stale ones still dropped
            }
        }
        try (var links = Files.newDirectoryStream(dir, entry -> entry.getFileName().toString().startsWith(prefix))) {
            for (var link : links) {
                var session = link.getFileName().toString().substring(prefix.length());
                if (!live.contains(session) && Files.isSymbolicLink(link)) deleteQuietly(link);
            }
        }
    }

    private static String sessionLinkPrefix(String name) {
        return SYMLINK_PREFIX + name + SESSION_SEPARATOR;
    }

    /** Whether {@code path} itself, not what a link there points to, is a Unix socket. */
    private static boolean isSocket(Path path) {
        try {
            var mode = (Integer) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
            return (mode & S_IFMT) == S_IFSOCK;
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Every {@code isx-<name>.<session>} link in {@code dir}: links only, never what they point to. */
    private static void deleteSessionLinks(Path dir, String name) throws IOException {
        var prefix = sessionLinkPrefix(name);
        try (var links = Files.newDirectoryStream(dir, entry -> entry.getFileName().toString().startsWith(prefix))) {
            for (var link : links) {
                if (Files.isSymbolicLink(link)) deleteQuietly(link);
            }
        }
    }

    /**
     * Remove host-side artifacts after an instance is destroyed: the
     * instance's directory, with the logs zmx keeps there, and the link.
     * The disk device itself is auto-removed by Incus on instance deletion.
     */
    public static void cleanup(String name, Output out) {
        var zmxDir = sharedHostZmxDir();
        if (zmxDir != null) cleanup(name, HostDirs.of(zmxDir), out);
    }

    static void cleanup(String name, HostDirs dirs, Output out) {
        var zmxDir = dirs.zmx();
        var link = SYMLINK_PREFIX + name;
        var instanceDir = dirs.instanceDir(name);
        // Only a stat each: nothing of this instance's, nothing to check or say
        if (!Files.exists(zmxDir.resolve(link), LinkOption.NOFOLLOW_LINKS)
                && !Files.exists(instanceDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // The recursive delete below goes only through directories nobody
        // else can change, so none can be swapped under it
        int uid;
        try {
            uid = dirs.processUid().call();
        } catch (Exception e) {
            // Fails closed: no uid, no walk (id -u would fall back to a guess)
            out.warn("zmx sessions of '" + name + "' left on the host: isx cannot tell which uid it runs as: "
                    + e.getMessage());
            return;
        }
        // On the /tmp fallback another user can have made the host zmx dir,
        // or planted links in it.  deleteIfExists removes a link, never what
        // it points to, and in a real dir of the user's nobody else can put
        // a link in its way.
        try {
            if (Files.isDirectory(zmxDir, LinkOption.NOFOLLOW_LINKS) && uid == OwnDirectory.uidOf(zmxDir)) {
                deleteQuietly(zmxDir.resolve(link));
                deleteSessionLinks(zmxDir, name);
            } else {
                var dir = ownDir(zmxDir, uid);
                if (dir != null) {
                    deleteQuietly(dir.resolve(link));
                    deleteSessionLinks(dir, name);
                }
            }
        } catch (IOException | RuntimeException notOurs) {
            // Not the user's to tidy: the link was never made there
        }
        try {
            if (!Files.exists(instanceDir, LinkOption.NOFOLLOW_LINKS)) return;
            OwnDirectory.require(instanceDir.getParent(), uid, uid, "isx");
        } catch (NoSuchFileException gone) {
            return;
        } catch (OwnDirectory.NotOwn e) {
            // zmx/ gone since the stat above, as by a second cleanup: nothing left to say
            if (!Files.exists(instanceDir.getParent(), LinkOption.NOFOLLOW_LINKS)) return;
            out.warn("zmx sessions of '" + name + "' left on the host: their directory " + e.getMessage(),
                    "isx deletes them only through directories nobody else can change.");
            return;
        } catch (IOException | RuntimeException e) {
            out.warn("zmx sessions of '" + name + "' left on the host: could not check their directory: " + e);
            return;
        }
        // zmx keeps its logs/ in the shared dir, so the tree goes, and no
        // symlink a guest left in it is followed (#1772)
        try {
            FileTrees.delete(instanceDir);
        } catch (IOException e) {
            out.warn("could not remove zmx directory " + instanceDir + ": " + e.getMessage(),
                    "A guest whose user is not yours on the host can leave files you cannot delete.",
                    "Remove what is left with: sudo rm -r " + instanceDir);
        }
    }

    private static void deleteQuietly(Path link) {
        try {
            Files.deleteIfExists(link);
        } catch (IOException ignored) {
        }
    }

    /**
     * The zmx dir as a real directory that is the user's alone, or null when
     * there is none.  A user's {@code ZMX_DIR} may be a link of their own, to
     * such a directory; a link of anyone else's is refused.
     */
    private static Path ownDir(Path zmxDir, int uid) throws IOException {
        if (!Files.exists(zmxDir, LinkOption.NOFOLLOW_LINKS)) return null;
        var dir = zmxDir;
        if (Files.isSymbolicLink(zmxDir) && uid == OwnDirectory.uidOf(zmxDir)) {
            if (!Files.exists(zmxDir)) return null;
            dir = zmxDir.toRealPath();
        }
        OwnDirectory.require(dir, uid, uid, "isx");
        return dir;
    }
}
