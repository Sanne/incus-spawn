package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What a build of a template will download into the host's download cache, so it can be fetched
 * ahead of the build (#2411, {@link dev.incusspawn.tool.BackgroundDownloads}): the base image, for
 * a VM its disk image too, then each layer's tools' pinned downloads, in the order the build
 * fetches them.
 *
 * <p>Only what the build would reuse is listed: a file with a checksum, at an {@code http(s)} URL.
 * A {@code file://} base image is a local copy that gains nothing from being fetched ahead.
 * Planning prints nothing, since a question may be on screen while it runs.
 */
final class BuildDownloads {

    private BuildDownloads() {}

    /**
     * @param built         whether a template of this name is already built, so a build of the
     *                      layers below it starts from it, and downloads nothing of its own
     * @param baseImage     the base image release a build from scratch would use
     */
    static List<DownloadCache.Pinned> plan(ImageDef template, Map<String, ImageDef> defs, MachineType machineType,
                                           String arch, Predicate<String> built,
                                           Function<ImageDef, BuildCommand.BaseImage> baseImage,
                                           ToolDefLoader toolDefLoader, Iterable<ToolSetup> cdiTools) {
        // The layers a build would make, the template's own first, down to the first built one
        var layers = new ArrayList<ImageDef>();
        var fromScratch = true;
        var chain = new ArrayList<ImageDef>();
        chain.add(template);
        chain.addAll(ImageDef.ancestors(template, defs));
        for (var layer : chain) {
            if (layer != template && built.test(layer.getName())) {
                fromScratch = false;
                break;
            }
            layers.add(layer);
        }

        var planned = new LinkedHashMap<String, DownloadCache.Pinned>();
        if (fromScratch) {
            var root = layers.getLast();
            var base = baseImage.apply(root);
            add(planned, root.getImageUrl(), base.tag(), base.sha256(), arch);
            if (machineType == MachineType.VM) {
                add(planned, root.getVmImageUrl(), base.vmTag(), base.vmSha256(), arch);
            }
        }
        // Farthest layer first, as the build sets them up
        for (var layer : layers.reversed()) {
            for (var tool : BuildTools.resolveTools(layer, toolDefLoader, cdiTools, true)) {
                for (var download : tool.setup().pinnedDownloads(arch)) {
                    if (fetchable(download.url()) && download.sha256() != null) {
                        planned.putIfAbsent(download.url(), download);
                    }
                }
            }
        }
        return List.copyOf(planned.values());
    }

    /** The plan for a build on this host: its architecture, its tools and its base-image releases, said nothing of. */
    static List<DownloadCache.Pinned> plan(ImageDef template, Map<String, ImageDef> defs, MachineType machineType,
                                           Predicate<String> built) {
        return plan(template, defs, machineType, ImageDef.hostArch(), built, BuildCommand::trackedBaseImageQuietly,
                RuntimeServices.toolDefLoader(), RuntimeServices.toolSetups());
    }

    private static void add(Map<String, DownloadCache.Pinned> planned, String imageUrl, String tag,
                            Map<String, String> sha256, String arch) {
        if (imageUrl == null || imageUrl.isBlank() || sha256 == null || sha256.get(arch) == null) return;
        // Resolved for the plan's arch, the arch its checksum is for, never the host's
        var url = ImageDef.resolveImageUrl(imageUrl, tag, arch);
        if (fetchable(url)) planned.putIfAbsent(url, new DownloadCache.Pinned(url, sha256.get(arch)));
    }

    private static boolean fetchable(String url) {
        var lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }
}
