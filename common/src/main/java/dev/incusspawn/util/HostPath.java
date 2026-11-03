package dev.incusspawn.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** The host's {@code PATH}, searched in-process rather than by spawning {@code which}. */
public final class HostPath {

    private static final String LINUX_FALLBACK =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    private HostPath() {}

    /** {@code path}, or the usual Linux directories when it is unset or blank (as under some service managers). */
    public static String effective(String path) {
        return (path != null && !path.isBlank()) ? path : LINUX_FALLBACK;
    }

    public static boolean isOnPath(String name) {
        return find(name) != null;
    }

    /**
     * The absolute path of the first executable {@code name} on the {@code PATH}, or null. Absolute
     * even for a relative entry, since callers write it into service units, ssh config and sudoers.
     * Never {@code which} itself: Arch's base system does not ship it.
     */
    public static String find(String name) {
        return find(name, System.getenv("PATH"));
    }

    static String find(String name, String path) {
        for (var dir : effective(path).split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            var candidate = Path.of(dir).resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath().normalize().toString();
        }
        return null;
    }
}
