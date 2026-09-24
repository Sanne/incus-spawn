package dev.incusspawn.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.Environment;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HostResourceSetup {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OVERLAY_BASE = "/var/lib/incus-spawn/overlays";
    private static final String OVERLAY_CONF = "/etc/incus-spawn/overlay-mounts.conf";
    private static final String OVERLAY_SCRIPT = "/usr/local/sbin/incus-spawn-apply-overlays";
    private static final String OVERLAY_SERVICE = "/etc/systemd/system/incus-spawn-overlays.service";

    private HostResourceSetup() {}

    public static String resolveContainerPath(String source, String path) {
        var resolved = (path != null && !path.isBlank()) ? path : source;
        if (resolved.startsWith("~/")) return "/home/agentuser/" + resolved.substring(2);
        if (resolved.equals("~")) return "/home/agentuser";
        if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
            throw new IllegalArgumentException("'path' is required for URL sources: " + source);
        }
        if (!resolved.startsWith("/")) return "/home/agentuser/" + resolved;
        return resolved;
    }

    public static String expandHostTilde(String source) {
        if (source.startsWith("~/")) return System.getProperty("user.home") + source.substring(1);
        if (source.equals("~")) return System.getProperty("user.home");
        return source;
    }

    public static void addShiftIfSupported(java.util.List<String> args, boolean isVm) {
        if (!isVm && !Platform.isMacOS()) args.add("shift=true");
    }

    public static String translateForVm(String hostPath) {
        if (!Platform.isMacOS()) return hostPath;
        var home = System.getProperty("user.home");
        if (hostPath.startsWith(home + "/")) {
            return "/host" + hostPath.substring(home.length());
        }
        if (hostPath.equals(home)) {
            return "/host";
        }
        return hostPath;
    }

    static String deviceName(String containerPath) {
        var stripped = containerPath.startsWith("/") ? containerPath.substring(1) : containerPath;
        var name = "hr-" + stripped.replaceAll("[^a-zA-Z0-9]", "-");
        if (name.length() > 64) {
            var hash = Integer.toHexString(containerPath.hashCode() & 0x7fffffff);
            name = name.substring(0, 55) + "-" + hash;
        }
        return name;
    }

    static String overlayDeviceName(String containerPath) {
        var base = deviceName(containerPath);
        var name = base + "-lo";
        if (name.length() > 64) {
            var hash = Integer.toHexString(containerPath.hashCode() & 0x7fffffff);
            name = base.substring(0, 55) + "-" + hash + "-lo";
        }
        return name;
    }

    private static String overlayDir(String containerPath) {
        return OVERLAY_BASE + containerPath;
    }

    private static String deviceNameForMode(ImageDef.HostResource hr) {
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        return "overlay".equals(hr.getMode())
                ? overlayDeviceName(containerPath)
                : deviceName(containerPath);
    }

    public static List<ImageDef.HostResource> collectEffective(ImageDef imageDef, Map<String, ImageDef> defs) {
        var result = new LinkedHashMap<String, ImageDef.HostResource>();
        var chain = new ArrayList<ImageDef>();
        var current = imageDef;
        while (current != null) {
            chain.add(0, current);
            if (current.isRoot()) break;
            current = defs.get(current.getParent());
        }
        for (var def : chain) {
            for (var hr : def.getHostResources()) {
                var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
                result.put(containerPath, def.getProjectRoot() == null
                        ? new ImageDef.HostResource(hr.getSource(), hr.getPath(), hr.getMode())
                        : confineToProject(def, hr, containerPath));
            }
        }
        return new ArrayList<>(result.values());
    }

    /**
     * A project-local template tried to reach a host path outside its project directory (#765).
     */
    public static final class HostPathOutsideProjectException extends IllegalStateException {
        HostPathOutsideProjectException(String message) {
            super(message);
        }
    }

    /**
     * Project-local templates arrive with whatever repository was cloned, so they may only
     * reference host paths inside that project: otherwise building in a cloned directory would
     * be enough to copy {@code ~/.ssh} into a container whose prime commands the same repository
     * controls. The check runs on real paths, so neither {@code ..} nor a symlink can escape,
     * and the source is rewritten to that absolute real path: a relative source would otherwise
     * be re-resolved against whatever directory a later branch is created from.
     */
    private static ImageDef.HostResource confineToProject(ImageDef def, ImageDef.HostResource hr,
                                                          String containerPath) {
        if (isUrl(hr.getSource())) {
            return new ImageDef.HostResource(hr.getSource(), hr.getPath(), hr.getMode());
        }
        var root = realPath(def.getProjectRoot());
        var resolved = root.resolve(expandHostTilde(hr.getSource()));
        var escape = findEscape(resolved, root, hr.getMode());
        if (escape != null) throw outsideProject(def, "host-resource '" + hr.getSource() + "'", escape);
        var real = realPathOfExistingPrefix(resolved, new int[1]);
        if (real == null) throw outsideProject(def, "host-resource '" + hr.getSource() + "'", UNRESOLVABLE);
        return new ImageDef.HostResource(real.toString(), containerPath,
                hr.getMode(), root.toString());
    }

    /**
     * Why {@code path} leaves {@code projectRoot}, or null if it stays inside: the check
     * {@link #collectEffective} applies to host-resources, for other host paths a project-local
     * template names (a {@code file://} base image).
     */
    public static String projectEscape(Path path, Path projectRoot) {
        return findEscape(path, realPath(projectRoot), null);
    }

    public static HostPathOutsideProjectException outsideProject(ImageDef def, String what, String escape) {
        return new HostPathOutsideProjectException("Template '" + def.getName() + "' is project-local ("
                + def.getSource() + "),\n"
                + "  so the host paths it uses must stay inside the project directory "
                + realPath(def.getProjectRoot()) + ",\n"
                + "  but " + what + " " + escape + ".\n"
                + "  A cloned repository must not be able to copy or mount your files into a container.\n"
                + "  If you trust this template, move it to " + ImageDef.userImagesDir()
                + " or a configured search path.");
    }

    /**
     * Re-checks a resource confined by {@link #confineToProject} right before it is used: the
     * project is a working tree, and a later {@code git pull} can turn a checked directory into
     * a symlink pointing anywhere.
     */
    static void verifyConfined(ImageDef.HostResource hr) {
        if (hr.getConfinedTo() == null) return;
        var escape = findEscape(Path.of(hr.getSource()), Path.of(hr.getConfinedTo()), hr.getMode());
        if (escape != null) {
            throw new HostPathOutsideProjectException("Host-resource '" + hr.getSource()
                    + "' comes from a project-local template and must stay inside " + hr.getConfinedTo()
                    + ", but it " + escape + ".");
        }
    }

    /** Why {@code path} is not confined to {@code root} (already a real path), or null if it is. */
    private static String findEscape(Path path, Path root, String mode) {
        var real = realPathOfExistingPrefix(path, new int[1]);
        if (real == null) return UNRESOLVABLE;
        if (!real.startsWith(root)) return "resolves to " + real;
        // Mounts are safe from symlinks further down: those resolve inside the container. A copy
        // is made by the host, which follows a file symlink and pushes the file it points to.
        if ("copy".equals(mode) && Files.isDirectory(real)) {
            try (var stream = Files.walk(real)) {
                for (var p : (Iterable<Path>) stream::iterator) {
                    if (!Files.isSymbolicLink(p)) continue;
                    var target = realPathOfExistingPrefix(p, new int[1]);
                    if (target == null) return "contains " + real.relativize(p) + ", which " + UNRESOLVABLE;
                    if (!target.startsWith(root)) {
                        return "contains " + real.relativize(p) + ", a symlink to " + target;
                    }
                }
            } catch (IOException | java.io.UncheckedIOException e) {
                return "could not be checked for symlinks (" + e.getMessage() + ")";
            }
        }
        return null;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static final String UNRESOLVABLE = "could not be resolved (a symlink loop, or too many symlinks)";

    /** The kernel's own limit on symlinks followed while resolving one path (ELOOP). */
    private static final int MAX_SYMLINK_HOPS = 40;

    /**
     * The real path {@code path} would have: the longest existing prefix resolved through its
     * symlinks, with the rest appended. Dangling symlinks are followed to where they point, so a
     * link to a host path that does not exist yet cannot pass as a path inside the project.
     * Returns null when that takes more than {@link #MAX_SYMLINK_HOPS} symlinks: an unresolved
     * path must never be mistaken for its lexical, inside-the-project-looking self. {@code hops}
     * counts symlinks only (shared across the recursion); walking up missing parents is bounded
     * by the path's length.
     */
    private static Path realPathOfExistingPrefix(Path path, int[] hops) {
        path = path.toAbsolutePath();
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            // Does not exist (yet), or is a dangling symlink: resolve what does exist.
        }
        if (Files.isSymbolicLink(path)) {
            if (++hops[0] > MAX_SYMLINK_HOPS) return null;
            try {
                return realPathOfExistingPrefix(path.resolveSibling(Files.readSymbolicLink(path)), hops);
            } catch (IOException e) {
                return null;
            }
        }
        var parent = path.getParent();
        if (parent == null) return path;
        var realParent = realPathOfExistingPrefix(parent, hops);
        if (realParent == null) return null;
        var name = path.getFileName().toString();
        if (name.equals("..")) return realParent.getParent() != null ? realParent.getParent() : realParent;
        if (name.equals(".")) return realParent;
        return realParent.resolve(name);
    }

    private static boolean isUrl(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }

    public static void applyForBuild(IncusClient incus, Container container, List<ImageDef.HostResource> resources,
                                      boolean isVm) {
        var overlayEntries = new ArrayList<ImageDef.HostResource>();
        for (var hr : resources) {
            verifyConfined(hr);
            switch (effectiveMode(hr, isVm)) {
                case "copy" -> applyCopy(container, hr);
                case "readonly" -> applyReadonly(incus, container.name(), hr, isVm);
                case "overlay" -> {
                    if (Platform.isMacOS()) {
                        throw new IllegalStateException(
                                "Host-resource '" + hr.getSource() + "' uses overlay mode, which is not yet supported on macOS.\n"
                                + "  Change the mode to 'readonly' in your image definition to mount it read-only,\n"
                                + "  or remove the host-resource entry to skip it entirely.\n"
                                + "  Tracking: https://github.com/Sanne/incus-spawn/issues/157");
                    }
                    applyOverlay(incus, container, hr, isVm);
                    overlayEntries.add(hr);
                }
                default -> System.err.println("Warning: unknown host-resource mode '" + hr.getMode()
                        + "' for " + hr.getSource() + ", skipping.");
            }
        }
        if (!overlayEntries.isEmpty()) {
            installOverlayService(container, overlayEntries);
        }
    }

    /**
     * Removes disk devices whose host source path no longer exists.
     */
    public static void removeStaleDevices(IncusClient incus, String container) {
        removeStaleDevices(incus, container, incus.instanceMetadata(container));
    }

    /** As above, reading the host-resource list from an already-fetched instance. */
    public static void removeStaleDevices(IncusClient incus, String container,
                                          JsonNode instanceMetadata) {
        var hrJson = instanceMetadata.path("config").path(Metadata.HOST_RESOURCES).asText("");
        var resources = deserialize(hrJson);
        for (var hr : resources) {
            if ("copy".equals(hr.getMode())) continue;
            try {
                verifyConfined(hr);
            } catch (HostPathOutsideProjectException e) {
                removeExistingDevice(incus, container, deviceNameForMode(hr));
                System.err.println("Warning: " + e.getMessage() + " (device removed)");
                continue;
            }
            var expandedSource = expandHostTilde(hr.getSource());
            if (!Files.exists(Path.of(expandedSource))) {
                removeExistingDevice(incus, container, deviceNameForMode(hr));
                System.err.println("Warning: host-resource source not found: "
                        + hr.getSource() + " (device removed)");
            }
        }
    }

    public static void applyForInstance(IncusClient incus, String container, List<ImageDef.HostResource> resources,
                                        boolean isVm) {
        for (var hr : resources) {
            if (!"copy".equals(hr.getMode())) {
                try {
                    verifyConfined(hr);
                } catch (HostPathOutsideProjectException e) {
                    removeExistingDevice(incus, container, deviceNameForMode(hr));
                    System.err.println("Warning: " + e.getMessage() + " (skipping)");
                    continue;
                }
            }
            switch (effectiveMode(hr, isVm)) {
                case "readonly" -> {
                    removeExistingDevice(incus, container, deviceNameForMode(hr));
                    applyReadonly(incus, container, hr, isVm);
                }
                case "overlay" -> {
                    if (Platform.isMacOS()) {
                        throw new IllegalStateException(
                                "Host-resource '" + hr.getSource() + "' uses overlay mode, which is not yet supported on macOS.\n"
                                + "  Change the mode to 'readonly' in your image definition to mount it read-only,\n"
                                + "  or remove the host-resource entry to skip it entirely.\n"
                                + "  Tracking: https://github.com/Sanne/incus-spawn/issues/157");
                    }
                    removeExistingDevice(incus, container, deviceNameForMode(hr));
                    applyOverlayDevice(incus, container, hr, isVm);
                }
                case "copy" -> {} // already baked into the template
            }
        }
    }

    private static void removeExistingDevice(IncusClient incus, String container, String devName) {
        incus.deviceRemove(container, devName);
    }

    public static void removeBuildDevices(IncusClient incus, String container, List<ImageDef.HostResource> resources) {
        for (var hr : resources) {
            if ("copy".equals(hr.getMode())) continue;
            try {
                if ("overlay".equals(hr.getMode())) {
                    var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
                    incus.shellExec(container, "umount", containerPath);
                }
                incus.deviceRemove(container, deviceNameForMode(hr));
            } catch (Exception e) {
                System.err.println("Warning: failed to remove build device: " + e.getMessage());
            }
        }
    }

    public static String serialize(List<ImageDef.HostResource> resources) {
        try {
            return JSON.writeValueAsString(resources);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize host-resources", e);
        }
    }

    public static List<ImageDef.HostResource> deserialize(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            System.err.println("Warning: could not parse host-resources metadata: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * VMs cannot mount individual files as disk devices (only directories).
     * Fall back to copy mode for file-level readonly/overlay host resources.
     */
    private static String effectiveMode(ImageDef.HostResource hr, boolean isVm) {
        if (!isVm || "copy".equals(hr.getMode())) return hr.getMode();
        var expandedSource = expandHostTilde(hr.getSource());
        if (Files.exists(Path.of(expandedSource)) && !Files.isDirectory(Path.of(expandedSource))) {
            BuildOutput.note("VM: falling back to copy mode for file " + hr.getSource());
            return "copy";
        }
        return hr.getMode();
    }

    // --- Private helpers ---

    private static void applyCopy(Container container, ImageDef.HostResource hr) {
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        var parentDir = containerPath.contains("/")
                ? containerPath.substring(0, containerPath.lastIndexOf('/'))
                : "/";

        if (isUrl(hr.getSource())) {
            try {
                var cache = new DownloadCache();
                var downloaded = cache.download(hr.getSource(), null);
                container.exec("mkdir", "-p", parentDir);
                container.filePush(downloaded.toString(), containerPath);
                container.chown(containerPath, "agentuser:agentuser");
                chownHomeParents(container, containerPath);
                BuildOutput.note("Copied " + hr.getSource() + " -> " + containerPath);
            } catch (IOException e) {
                System.err.println("Warning: failed to download " + hr.getSource() + ": " + e.getMessage());
            }
        } else {
            var expandedSource = expandHostTilde(hr.getSource());
            var sourcePath = Path.of(expandedSource);
            if (!Files.exists(sourcePath)) {
                System.err.println("Warning: host-resource source not found: " + hr.getSource() + " (skipping)");
                return;
            }
            container.exec("mkdir", "-p", parentDir);
            if (Files.isDirectory(sourcePath)) {
                container.filePushRecursive(expandedSource, parentDir);
            } else {
                container.filePush(expandedSource, containerPath);
            }
            container.chown(containerPath, "agentuser:agentuser");
            chownHomeParents(container, containerPath);
            BuildOutput.note("Copied " + hr.getSource() + " -> " + containerPath);
        }
    }

    private static void applyReadonly(IncusClient incus, String container, ImageDef.HostResource hr, boolean isVm) {
        var expandedSource = expandHostTilde(hr.getSource());
        if (!Files.exists(Path.of(expandedSource))) {
            System.err.println("Warning: host-resource source not found: " + hr.getSource() + " (skipping)");
            return;
        }
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        var devName = deviceName(containerPath);
        var args = new java.util.ArrayList<>(java.util.List.of(
                "source=" + translateForVm(expandedSource),
                "path=" + containerPath,
                "readonly=true"));
        addShiftIfSupported(args, isVm);
        incus.deviceAdd(container, devName, "disk", args.toArray(String[]::new));
        BuildOutput.note("Mounted " + hr.getSource() + " -> " + containerPath + " (readonly)");
    }

    private static void applyOverlay(IncusClient incus, Container container, ImageDef.HostResource hr, boolean isVm) {
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        var oDir = overlayDir(containerPath);
        var lowerDir = oDir + "/lower";
        var upperDir = oDir + "/upper";
        var workDir = oDir + "/work";

        var expandedSource = expandHostTilde(hr.getSource());
        if (!Files.exists(Path.of(expandedSource))) {
            System.err.println("Warning: host-resource source not found: " + hr.getSource() + " (skipping)");
            return;
        }

        var overlayArgs = new java.util.ArrayList<>(java.util.List.of(
                "source=" + translateForVm(expandedSource),
                "path=" + lowerDir,
                "readonly=true"));
        addShiftIfSupported(overlayArgs, isVm);
        incus.deviceAdd(container.name(), overlayDeviceName(containerPath), "disk",
                overlayArgs.toArray(String[]::new));

        container.exec("mkdir", "-p", upperDir, workDir, containerPath);
        container.exec("chown", "agentuser:agentuser", upperDir);
        chownHomeParents(container, containerPath);

        if (isVm) {
            // VM disk devices are mounted asynchronously by incus-agent;
            // wait for the lower directory to be populated before overlaying.
            if (!incus.pollUntilReady(container.name(), 15,
                    "sh", "-c", "mountpoint -q " + lowerDir)) {
                System.err.println("  Warning: VM device for " + hr.getSource()
                        + " did not mount in time (skipping overlay)");
                return;
            }
        }

        var mountResult = container.exec("mount", "-t", "overlay", "overlay",
                "-o", "lowerdir=" + lowerDir + ",upperdir=" + upperDir + ",workdir=" + workDir + ",metacopy=off",
                containerPath);
        if (!mountResult.success()) {
            System.err.println("  Warning: overlay mount failed for " + hr.getSource()
                    + ": " + mountResult.stderr());
            return;
        }

        BuildOutput.note("Mounted " + hr.getSource() + " -> " + containerPath + " (overlay)");
    }

    private static void applyOverlayDevice(IncusClient incus, String container, ImageDef.HostResource hr,
                                            boolean isVm) {
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        var lowerDir = overlayDir(containerPath) + "/lower";

        var expandedSource = expandHostTilde(hr.getSource());
        if (!Files.exists(Path.of(expandedSource))) {
            System.err.println("Warning: host-resource source not found: " + hr.getSource() + " (skipping)");
            return;
        }

        var devArgs = new java.util.ArrayList<>(java.util.List.of(
                "source=" + translateForVm(expandedSource),
                "path=" + lowerDir,
                "readonly=true"));
        addShiftIfSupported(devArgs, isVm);
        incus.deviceAdd(container, overlayDeviceName(containerPath), "disk",
                devArgs.toArray(String[]::new));
    }

    private static void chownHomeParents(Container container, String containerPath) {
        var homePath = Path.of("/home/agentuser");
        var normalized = Path.of(containerPath).normalize();
        if (!normalized.startsWith(homePath) || normalized.equals(homePath)) return;
        var path = normalized.getParent();
        while (path != null && path.startsWith(homePath) && !path.equals(homePath)) {
            container.exec("chown", "agentuser:agentuser", path.toString());
            path = path.getParent();
        }
    }

    private static void installOverlayService(Container container, List<ImageDef.HostResource> overlayResources) {
        var confLines = new StringBuilder();
        for (var hr : overlayResources) {
            var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
            var oDir = overlayDir(containerPath);
            confLines.append(oDir).append("/lower|")
                    .append(oDir).append("/upper|")
                    .append(oDir).append("/work|")
                    .append(containerPath).append("\n");
        }

        container.exec("mkdir", "-p", "/etc/incus-spawn");
        container.writeFile(OVERLAY_CONF, confLines.toString());

        container.writeFile(OVERLAY_SCRIPT,
                "#!/bin/bash\n" +
                "while IFS='|' read -r lower upper work target; do\n" +
                "    [ -d \"$lower\" ] || continue\n" +
                "    mkdir -p \"$upper\" \"$work\" \"$target\"\n" +
                "    chown agentuser:agentuser \"$upper\"\n" +
                "    mount -t overlay overlay -o \"lowerdir=$lower,upperdir=$upper,workdir=$work,metacopy=off\" \"$target\"\n" +
                "done < " + OVERLAY_CONF);
        container.exec("chmod", "+x", OVERLAY_SCRIPT);

        container.writeFile(OVERLAY_SERVICE,
                "[Unit]\n" +
                "Description=incus-spawn overlay mounts\n" +
                "DefaultDependencies=no\n" +
                "After=local-fs.target\n" +
                "Before=multi-user.target\n" +
                "\n" +
                "[Service]\n" +
                "Type=oneshot\n" +
                "ExecStart=" + OVERLAY_SCRIPT + "\n" +
                "RemainAfterExit=yes\n" +
                "\n" +
                "[Install]\n" +
                "WantedBy=multi-user.target");

        container.exec("systemctl", "enable", "incus-spawn-overlays.service");
    }
}
