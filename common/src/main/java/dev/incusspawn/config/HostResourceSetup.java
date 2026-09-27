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

    /**
     * Attach the disk devices of a build's {@code readonly} and {@code overlay} resources. Call it
     * while the build instance is still stopped (#828): a device present at start gets its own PCIe
     * root port on a VM, while one hot-plugged into a running VM takes one of only 8 spare hotplug
     * slots, which repo references need. A VM also mounts boot-time devices before incus-agent
     * serves its first exec, so nothing has to wait for them to appear.
     *
     * <p>Quiet, because it runs inside the launch step's progress line. {@link #applyForBuild}
     * reports each resource once the instance is up. A missing source is skipped rather than
     * attached, since Incus refuses to start an instance with a missing {@code source} path.
     */
    public static void attachBuildDevices(IncusClient incus, String container,
                                          List<ImageDef.HostResource> resources, boolean isVm) {
        for (var hr : resources) {
            verifyConfined(hr);
            if (!sourceExists(hr)) continue;
            switch (effectiveMode(hr, isVm)) {
                case "readonly" -> addReadonlyDevice(incus, container, hr, isVm);
                case "overlay" -> {
                    requireOverlaySupported(hr);
                    addOverlayDevice(incus, container, hr, isVm);
                }
                default -> {}
            }
        }
    }

    /**
     * The in-guest half of a build's host resources, after {@link #attachBuildDevices} and start:
     * {@code copy} pushes the files, {@code overlay} mounts the overlay over its already-attached
     * lower directory, and each resource is reported.
     */
    public static void applyForBuild(IncusClient incus, Container container, List<ImageDef.HostResource> resources,
                                      boolean isVm) {
        var overlayEntries = new ArrayList<ImageDef.HostResource>();
        for (var hr : resources) {
            verifyConfined(hr);
            switch (effectiveMode(hr, isVm)) {
                case "copy" -> {
                    noteVmCopyFallback(hr);
                    applyCopy(container, hr);
                }
                case "readonly" -> {
                    if (warnIfMissing(hr)) continue;
                    BuildOutput.note("Mounted " + hr.getSource() + " -> "
                            + resolveContainerPath(hr.getSource(), hr.getPath()) + " (readonly)");
                }
                case "overlay" -> {
                    requireOverlaySupported(hr);
                    applyOverlay(container, hr);
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
                    if (warnIfMissing(hr)) continue;
                    addReadonlyDevice(incus, container, hr, isVm);
                    BuildOutput.note("Mounted " + hr.getSource() + " -> "
                            + resolveContainerPath(hr.getSource(), hr.getPath()) + " (readonly)");
                }
                case "overlay" -> {
                    requireOverlaySupported(hr);
                    removeExistingDevice(incus, container, deviceNameForMode(hr));
                    if (warnIfMissing(hr)) continue;
                    addOverlayDevice(incus, container, hr, isVm);
                }
                case "copy" -> noteVmCopyFallback(hr); // already baked into the template
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
            return "copy";
        }
        return hr.getMode();
    }

    private static void noteVmCopyFallback(ImageDef.HostResource hr) {
        if (!"copy".equals(hr.getMode())) {
            BuildOutput.note("VM: falling back to copy mode for file " + hr.getSource());
        }
    }

    private static boolean sourceExists(ImageDef.HostResource hr) {
        return Files.exists(Path.of(expandHostTilde(hr.getSource())));
    }

    /** Warns and returns true when the resource's host source is gone, so the caller skips it. */
    private static boolean warnIfMissing(ImageDef.HostResource hr) {
        if (sourceExists(hr)) return false;
        System.err.println("Warning: host-resource source not found: " + hr.getSource() + " (skipping)");
        return true;
    }

    private static void requireOverlaySupported(ImageDef.HostResource hr) {
        if (Platform.isMacOS()) {
            throw new IllegalStateException(
                    "Host-resource '" + hr.getSource() + "' uses overlay mode, which is not yet supported on macOS.\n"
                    + "  Change the mode to 'readonly' in your image definition to mount it read-only,\n"
                    + "  or remove the host-resource entry to skip it entirely.\n"
                    + "  Tracking: https://github.com/Sanne/incus-spawn/issues/157");
        }
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

    private static void addReadonlyDevice(IncusClient incus, String container, ImageDef.HostResource hr,
                                          boolean isVm) {
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        var args = new java.util.ArrayList<>(java.util.List.of(
                "source=" + translateForVm(expandHostTilde(hr.getSource())),
                "path=" + containerPath,
                "readonly=true"));
        addShiftIfSupported(args, isVm);
        incus.deviceAdd(container, deviceName(containerPath), "disk", args.toArray(String[]::new));
    }

    /** The overlay's read-only lower layer: pure device config, so it is attached before start. */
    private static void addOverlayDevice(IncusClient incus, String container, ImageDef.HostResource hr,
                                         boolean isVm) {
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        var devArgs = new java.util.ArrayList<>(java.util.List.of(
                "source=" + translateForVm(expandHostTilde(hr.getSource())),
                "path=" + overlayDir(containerPath) + "/lower",
                "readonly=true"));
        addShiftIfSupported(devArgs, isVm);
        incus.deviceAdd(container, overlayDeviceName(containerPath), "disk",
                devArgs.toArray(String[]::new));
    }

    private static void applyOverlay(Container container, ImageDef.HostResource hr) {
        if (warnIfMissing(hr)) return;
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        var oDir = overlayDir(containerPath);
        var lowerDir = oDir + "/lower";
        var upperDir = oDir + "/upper";
        var workDir = oDir + "/work";

        container.exec("mkdir", "-p", upperDir, workDir, containerPath);
        container.exec("chown", "agentuser:agentuser", upperDir);
        chownHomeParents(container, containerPath);

        // The lower device was attached before start, so it is mounted by now: containers get it
        // at start, and a VM's incus-agent mounts boot-time shares before serving any exec. But the
        // agent only logs a share it fails to mount, and an overlay over the empty mount point would
        // silently hide the host content, so check (without waiting) in the same exec. A template
        // derived from one with overlays boots with the parent's overlay service, which has already
        // mounted this overlay; stacking a second one would leave one behind at removal.
        var mountResult = container.exec("sh", "-c",
                "mountpoint -q \"$1\" || { echo \"$1 is not mounted\" >&2; exit 1; }; "
                + "[ \"$(findmnt -n -o FSTYPE --mountpoint \"$4\")\" = overlay ] && exit 0; "
                + "mount -t overlay overlay -o \"lowerdir=$1,upperdir=$2,workdir=$3,metacopy=off\" \"$4\"",
                "sh", lowerDir, upperDir, workDir, containerPath);
        if (!mountResult.success()) {
            System.err.println("  Warning: overlay mount failed for " + hr.getSource()
                    + ": " + mountResult.stderr());
            return;
        }

        BuildOutput.note("Mounted " + hr.getSource() + " -> " + containerPath + " (overlay)");
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
                // In a VM the lower layers are virtiofs shares that incus-agent mounts before it
                // signals ready (Type=notify); without this the overlay can win the race and cover
                // an empty lower dir. Containers have no such unit, so the ordering is a no-op there.
                "After=local-fs.target incus-agent.service\n" +
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
