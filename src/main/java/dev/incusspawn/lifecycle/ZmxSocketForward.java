package dev.incusspawn.lifecycle;

import dev.incusspawn.Environment;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.incus.IncusClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shares the container's zmx socket directory with the host via an Incus
 * disk device, so host-side {@code zmx list}, {@code zmx attach}, etc.
 * work natively against container sessions.
 *
 * <p>A per-container directory under the host zmx dir is bind-mounted at
 * {@code /tmp/zmx} inside the container.  Sockets created by zmx appear
 * on the host filesystem immediately — no proxy device required.  A
 * symlink named {@code isx-<container>} in the host zmx dir points to
 * the container's {@code isx} session socket so it shows up in
 * {@code zmx list}.</p>
 */
public final class ZmxSocketForward {

    private ZmxSocketForward() {}

    private static final String DEVICE_NAME = "zmx-sockets";
    private static final String CONTAINER_ZMX_DIR = "/home/agentuser/.zmx";
    private static final String CONTAINERS_SUBDIR = "containers";
    private static final String SYMLINK_PREFIX = "isx-";

    /**
     * Resolve the host zmx socket directory using the same priority as zmx
     * itself: {@code ZMX_DIR} > {@code XDG_RUNTIME_DIR/zmx} >
     * {@code TMPDIR/zmx} > {@code /tmp/zmx-<uid>}.
     */
    static Path hostZmxDir() {
        var zmxDir = System.getenv("ZMX_DIR");
        if (zmxDir != null && !zmxDir.isBlank()) return Path.of(zmxDir);
        var xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
        if (xdgRuntime != null && !xdgRuntime.isBlank()) return Path.of(xdgRuntime, "zmx");
        var tmpdir = System.getenv("TMPDIR");
        if (tmpdir != null && !tmpdir.isBlank()) return Path.of(tmpdir, "zmx");
        return Path.of("/tmp", "zmx-" + InstanceLifecycle.getUid());
    }

    public static boolean isZmxInstalled(String buildSourceJson) {
        var bs = BuildSource.fromJson(buildSourceJson);
        return bs != null && bs.getTools().containsKey("zmx");
    }

    /**
     * Configure a disk device that shares a per-container host directory at
     * the container's zmx socket path.  Also creates a symlink in the host
     * zmx dir so the default {@code isx} session is visible to host zmx.
     *
     * <p>Idempotent — safe to call on every shell-in to repair a symlink
     * that zmx may have cleaned up.</p>
     */
    public static void configure(IncusClient incus, String name) {
        if (Environment.isMacOS()) return;

        var zmxDir = hostZmxDir();
        var containerDir = zmxDir.resolve(CONTAINERS_SUBDIR).resolve(name);
        try {
            Files.createDirectories(containerDir);
        } catch (IOException e) {
            System.err.println("Warning: could not create zmx directory: " + e.getMessage());
            return;
        }

        incus.devicesRemoveAll(name, java.util.List.of(DEVICE_NAME));
        var args = new java.util.ArrayList<>(java.util.List.of(
                "source=" + containerDir.toAbsolutePath(),
                "path=" + CONTAINER_ZMX_DIR));
        HostResourceSetup.addShiftIfSupported(args);
        incus.deviceAdd(name, DEVICE_NAME, "disk", args.toArray(String[]::new));

        ensureSymlink(zmxDir, name);
    }

    /**
     * (Re-)create the symlink from the host zmx dir to the container's
     * {@code isx} session socket.  Cheap — no API calls.
     */
    public static void ensureSymlink(String name) {
        ensureSymlink(hostZmxDir(), name);
    }

    private static void ensureSymlink(Path zmxDir, String name) {
        var link = zmxDir.resolve(SYMLINK_PREFIX + name);
        var target = Path.of(CONTAINERS_SUBDIR, name, "isx");
        try {
            if (Files.isSymbolicLink(link)) {
                if (target.equals(Files.readSymbolicLink(link))) return;
                Files.delete(link);
            } else if (Files.exists(link)) {
                return;
            }
            Files.createSymbolicLink(link, target);
        } catch (IOException ignored) {
        }
    }

    /**
     * Remove host-side artifacts after an instance is destroyed: the
     * per-container directory and the symlink.  The disk device itself
     * is auto-removed by Incus on instance deletion.
     */
    public static void cleanup(String name) {
        var zmxDir = hostZmxDir();
        try {
            Files.deleteIfExists(zmxDir.resolve(SYMLINK_PREFIX + name));
        } catch (IOException ignored) {
        }
        var containerDir = zmxDir.resolve(CONTAINERS_SUBDIR).resolve(name);
        try {
            if (Files.isDirectory(containerDir)) {
                try (var stream = Files.list(containerDir)) {
                    stream.forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
                Files.deleteIfExists(containerDir);
            }
        } catch (IOException ignored) {
        }
    }
}
