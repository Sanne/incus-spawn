package dev.incusspawn.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.tool.DownloadCache;

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
        if (path != null && !path.isBlank()) return path;
        if (source.startsWith("http://") || source.startsWith("https://")) {
            throw new IllegalArgumentException("'path' is required for URL sources: " + source);
        }
        if (source.startsWith("~/")) return "/home/agentuser/" + source.substring(2);
        if (source.equals("~")) return "/home/agentuser";
        return source;
    }

    public static String expandHostTilde(String source) {
        if (source.startsWith("~/")) return System.getProperty("user.home") + source.substring(1);
        if (source.equals("~")) return System.getProperty("user.home");
        return source;
    }

    static String deviceName(String containerPath) {
        var stripped = containerPath.startsWith("/") ? containerPath.substring(1) : containerPath;
        return "hr-" + stripped.replaceAll("[^a-zA-Z0-9]", "-");
    }

    private static String overlayDir(String containerPath) {
        return OVERLAY_BASE + containerPath;
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
                result.put(containerPath, hr);
            }
        }
        return new ArrayList<>(result.values());
    }

    public static void applyForBuild(IncusClient incus, Container container, List<ImageDef.HostResource> resources) {
        var overlayEntries = new ArrayList<ImageDef.HostResource>();
        for (var hr : resources) {
            switch (hr.getMode()) {
                case "copy" -> applyCopy(container, hr);
                case "readonly" -> applyReadonly(incus, container.name(), hr);
                case "overlay" -> {
                    applyOverlay(incus, container, hr);
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

    public static void applyForInstance(IncusClient incus, String container, List<ImageDef.HostResource> resources) {
        for (var hr : resources) {
            switch (hr.getMode()) {
                case "readonly" -> applyReadonly(incus, container, hr);
                case "overlay" -> applyOverlayDevice(incus, container, hr);
                case "copy" -> {} // already baked into the template
            }
        }
    }

    public static void removeBuildDevices(IncusClient incus, String container, List<ImageDef.HostResource> resources) {
        for (var hr : resources) {
            if ("copy".equals(hr.getMode())) continue;
            var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
            try {
                if ("overlay".equals(hr.getMode())) {
                    incus.shellExec(container, "umount", containerPath);
                    var lowerDir = overlayDir(containerPath) + "/lower";
                    incus.deviceRemove(container, deviceName(lowerDir));
                } else {
                    incus.deviceRemove(container, deviceName(containerPath));
                }
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

    // --- Private helpers ---

    private static void applyCopy(Container container, ImageDef.HostResource hr) {
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        var parentDir = containerPath.contains("/")
                ? containerPath.substring(0, containerPath.lastIndexOf('/'))
                : "/";

        if (hr.getSource().startsWith("http://") || hr.getSource().startsWith("https://")) {
            try {
                var cache = new DownloadCache();
                var downloaded = cache.download(hr.getSource(), null);
                container.exec("mkdir", "-p", parentDir);
                container.filePush(downloaded.toString(), containerPath);
                container.chown(containerPath, "agentuser:agentuser");
                System.out.println("  Copied " + hr.getSource() + " -> " + containerPath);
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
            System.out.println("  Copied " + hr.getSource() + " -> " + containerPath);
        }
    }

    private static void applyReadonly(IncusClient incus, String container, ImageDef.HostResource hr) {
        var expandedSource = expandHostTilde(hr.getSource());
        if (!Files.exists(Path.of(expandedSource))) {
            System.err.println("Warning: host-resource source not found: " + hr.getSource() + " (skipping)");
            return;
        }
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        var devName = deviceName(containerPath);
        incus.deviceAdd(container, devName, "disk",
                "source=" + expandedSource,
                "path=" + containerPath,
                "readonly=true",
                "shift=true");
        System.out.println("  Mounted " + hr.getSource() + " -> " + containerPath + " (readonly)");
    }

    private static void applyOverlay(IncusClient incus, Container container, ImageDef.HostResource hr) {
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

        incus.deviceAdd(container.name(), deviceName(lowerDir), "disk",
                "source=" + expandedSource,
                "path=" + lowerDir,
                "readonly=true",
                "shift=true");

        container.exec("mkdir", "-p", upperDir, workDir, containerPath);
        container.exec("mount", "-t", "overlay", "overlay",
                "-o", "lowerdir=" + lowerDir + ",upperdir=" + upperDir + ",workdir=" + workDir,
                containerPath);

        System.out.println("  Mounted " + hr.getSource() + " -> " + containerPath + " (overlay)");
    }

    private static void applyOverlayDevice(IncusClient incus, String container, ImageDef.HostResource hr) {
        var containerPath = resolveContainerPath(hr.getSource(), hr.getPath());
        var lowerDir = overlayDir(containerPath) + "/lower";

        var expandedSource = expandHostTilde(hr.getSource());
        if (!Files.exists(Path.of(expandedSource))) {
            System.err.println("Warning: host-resource source not found: " + hr.getSource() + " (skipping)");
            return;
        }

        incus.deviceAdd(container, deviceName(lowerDir), "disk",
                "source=" + expandedSource,
                "path=" + lowerDir,
                "readonly=true",
                "shift=true");
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
                "    mount -t overlay overlay -o \"lowerdir=$lower,upperdir=$upper,workdir=$work\" \"$target\"\n" +
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
