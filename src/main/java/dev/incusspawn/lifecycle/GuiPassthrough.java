package dev.incusspawn.lifecycle;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static helpers for Wayland GUI passthrough on Incus containers.
 * <p>
 * These were previously private statics on {@code BranchCommand}; they are
 * shared by {@code BranchCommand}, {@code ListCommand}, and {@code ShellCommand}
 * so they belong in the lifecycle layer.
 */
public final class GuiPassthrough {

    private GuiPassthrough() {}

    // Env vars needed for Wayland GUI passthrough (toolkit backends + quirk suppressors).
    private static final Map<String, String> WAYLAND_ENV = Map.of(
            "GDK_BACKEND", "wayland",
            "QT_QPA_PLATFORM", "wayland",
            "SDL_VIDEODRIVER", "wayland",
            "MOZ_ENABLE_WAYLAND", "1",
            "ELECTRON_OZONE_PLATFORM_HINT", "wayland",
            "NO_AT_BRIDGE", "1");

    private static final Pattern WAYLAND_DISPLAY_PATTERN =
            Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * Configure GUI passthrough on a (stopped) container: GPU device, Wayland
     * socket mount, environment variables, and tmpfiles.d for XDG_RUNTIME_DIR.
     */
    public static boolean configureGui(IncusClient incus, String name) {
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        if (xdgRuntimeDir == null || waylandDisplay == null) {
            System.err.println("Error: GUI passthrough requires WAYLAND_DISPLAY and XDG_RUNTIME_DIR.");
            System.err.println("Make sure you are running isx from a Wayland graphical session.");
            return false;
        }
        if (!WAYLAND_DISPLAY_PATTERN.matcher(waylandDisplay).matches()) {
            System.err.println("Error: WAYLAND_DISPLAY contains invalid characters: " + waylandDisplay);
            return false;
        }
        var hostSocket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(hostSocket))) {
            System.err.println("Error: Wayland socket not found at " + hostSocket);
            System.err.println("Make sure you are running isx from a Wayland graphical session.");
            return false;
        }

        System.out.println("Enabling GUI passthrough...");
        // Remove first in case the source already had these devices (incus copy carries them over).
        incus.deviceRemove(name, "gpu");
        incus.deviceRemove(name, "xdg-runtime");
        incus.deviceAdd(name, "gpu", "gpu");
        // Mount to /mnt/host-xdg instead of /run/user/<uid> — systemd-logind
        // mounts its own tmpfs at /run/user/<uid> which would hide this device.
        incus.deviceAdd(name, "xdg-runtime", "disk",
                "source=" + xdgRuntimeDir,
                "path=/mnt/host-xdg");
        // Set env vars via container config (visible to init and direct exec)
        // AND via profile.d script (visible to login shells, since su - resets env).
        var uid = InstanceLifecycle.getUid();
        var waylandSocketPath = "/mnt/host-xdg/" + waylandDisplay;
        incus.configSet(name, "environment.WAYLAND_DISPLAY", waylandSocketPath);
        incus.configSet(name, "environment.XDG_RUNTIME_DIR", "/run/user/" + uid);
        WAYLAND_ENV.forEach((k, v) -> incus.configSet(name, "environment." + k, v));
        if (!pushWaylandFiles(incus, name, waylandSocketPath, uid)) {
            System.err.println("Warning: GUI devices configured but profile.d scripts failed to install.");
            System.err.println("GUI may not work in login shells.");
            return false;
        }
        return true;
    }

    /**
     * Remove GUI passthrough from a container that inherited it via incus copy.
     * Clears devices, environment keys, metadata, and profile.d/tmpfiles.d scripts.
     */
    public static void removeGui(IncusClient incus, String name) {
        incus.deviceRemove(name, "gpu");
        incus.deviceRemove(name, "xdg-runtime");
        incus.configUnset(name, Metadata.GUI_ENABLED);
        incus.configUnset(name, "environment.WAYLAND_DISPLAY");
        incus.configUnset(name, "environment.XDG_RUNTIME_DIR");
        for (var key : WAYLAND_ENV.keySet()) {
            incus.configUnset(name, "environment." + key);
        }
        // Remove profile.d and tmpfiles.d scripts that re-export Wayland env in login shells
        try {
            pushTempFile(incus, name, "", "/etc/profile.d/wayland.sh");
            pushTempFile(incus, name, "", "/etc/tmpfiles.d/wayland-runtime.conf");
        } catch (IOException | RuntimeException e) {
            // Best-effort: files may not exist if GUI was never fully configured
        }
    }

    /**
     * Warn at shell entry if a GUI-enabled container can't reach the host
     * Wayland compositor.
     */
    public static void checkGuiHealth(IncusClient incus, String name) {
        var guiEnabled = incus.configGet(name, Metadata.GUI_ENABLED);
        if (!"true".equals(guiEnabled)) return;
        var waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        var xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR");
        if (waylandDisplay == null || xdgRuntimeDir == null) {
            System.err.println("\033[33mWarning: GUI passthrough is enabled but no Wayland session detected.\033[0m");
            System.err.println("GUI applications will not work in this session.");
            return;
        }
        var socket = xdgRuntimeDir + "/" + waylandDisplay;
        if (!java.nio.file.Files.exists(java.nio.file.Path.of(socket))) {
            System.err.println("\033[33mWarning: GUI passthrough is enabled but Wayland socket not found.\033[0m");
            System.err.println("GUI applications may not work. Try re-branching with --gui.");
        }
    }

    private static boolean pushWaylandFiles(IncusClient incus, String container,
                                             String waylandSocketPath, String uid) {
        try {
            var profile = new StringBuilder();
            profile.append("export WAYLAND_DISPLAY=").append(waylandSocketPath).append('\n');
            profile.append("export XDG_RUNTIME_DIR=/run/user/").append(uid).append('\n');
            WAYLAND_ENV.forEach((k, v) -> profile.append("export ").append(k).append('=').append(v).append('\n'));
            pushTempFile(incus, container, profile.toString(), "/etc/profile.d/wayland.sh");

            // systemd-logind may not create /run/user/<uid> in containers;
            // ensure it exists at boot so XDG_RUNTIME_DIR is usable.
            var tmpfiles = "d /run/user/" + uid + " 0700 " + uid + " " + uid + " -\n";
            pushTempFile(incus, container, tmpfiles, "/etc/tmpfiles.d/wayland-runtime.conf");
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("Warning: failed to push wayland config: " + e.getMessage());
            return false;
        }
    }

    private static void pushTempFile(IncusClient incus, String container, String content, String destPath)
            throws IOException {
        var tmp = Files.createTempFile("isx-", ".tmp");
        try {
            Files.writeString(tmp, content);
            var perms = Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ);
            Files.setPosixFilePermissions(tmp, perms);
            incus.filePush(tmp.toString(), container, destPath);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
