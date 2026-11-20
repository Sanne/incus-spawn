package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.tool.AntigravitySetup;
import dev.incusspawn.tool.DownloadCache;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.YamlToolSetup;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** #2411: what a build will download, listed so it can be fetched ahead. */
class BuildDownloadsTest {

    private static final String SHA_X86 = "a".repeat(64);
    private static final String SHA_ARM = "b".repeat(64);
    private static final String VM_X86 = "c".repeat(64);

    @Test
    void aBuildFromScratchFetchesItsBaseImageThenEachLayersPinnedTools() {
        var loader = loader(Map.of(
                "maven", tool("maven", download("https://example.com/maven.tar.gz", "1".repeat(64), null)),
                "jdk", tool("jdk",
                        download("https://example.com/jdk-x86_64.tar.gz", "2".repeat(64), "x86_64"),
                        download("https://example.com/jdk-aarch64.tar.gz", "3".repeat(64), "aarch64"),
                        download("https://example.com/unpinned.tar.gz", null, null))));
        var root = base();
        root.setTools(List.of(new ToolDef.ToolRef("jdk")));
        var child = layer("tpl-java", "tpl-minimal", "maven");
        var defs = Map.of("tpl-minimal", root, "tpl-java", child);

        assertEquals(List.of(
                        new DownloadCache.Pinned("https://example.com/fedora-44-x86_64.tar.xz", SHA_X86),
                        new DownloadCache.Pinned("https://example.com/jdk-x86_64.tar.gz", "2".repeat(64)),
                        new DownloadCache.Pinned("https://example.com/maven.tar.gz", "1".repeat(64))),
                plan(child, defs, MachineType.CONTAINER, "x86_64", name -> false, loader),
                "the base image, then the farthest layer's tools first; another arch's and an unpinned download are not listed");
    }

    @Test
    void aVmBuildFetchesItsDiskImageToo() {
        var root = base();
        root.setVmImageUrl("https://example.com/fedora-{tag}-{arch}-vm.tar.xz");
        root.setVmImageSha256(Map.of("x86_64", VM_X86));

        assertEquals(List.of(
                        new DownloadCache.Pinned("https://example.com/fedora-44-x86_64.tar.xz", SHA_X86),
                        new DownloadCache.Pinned("https://example.com/fedora-44-x86_64-vm.tar.xz", VM_X86)),
                plan(root, Map.of("tpl-minimal", root), MachineType.VM, "x86_64", name -> false, loader(Map.of())));
    }

    @Test
    void aBuiltParentMeansNoBaseImageAndNoneOfItsTools() {
        var loader = loader(Map.of(
                "maven", tool("maven", download("https://example.com/maven.tar.gz", "1".repeat(64), null)),
                "jdk", tool("jdk", download("https://example.com/jdk.tar.gz", "2".repeat(64), null))));
        var root = base();
        root.setTools(List.of(new ToolDef.ToolRef("jdk")));
        var child = layer("tpl-java", "tpl-minimal", "maven");

        assertEquals(List.of(new DownloadCache.Pinned("https://example.com/maven.tar.gz", "1".repeat(64))),
                plan(child, Map.of("tpl-minimal", root, "tpl-java", child), MachineType.CONTAINER, "x86_64",
                        "tpl-minimal"::equals, loader));
    }

    @Test
    void aLocalBaseImageOrOneWithoutAChecksumIsNotFetchedAhead() {
        var local = base();
        local.setImageUrl("file:///home/someone/fedora.tar.xz");
        assertEquals(List.of(), plan(local, Map.of("tpl-minimal", local), MachineType.CONTAINER, "x86_64",
                name -> false, loader(Map.of())));

        var unpinned = base();
        unpinned.setImageSha256(Map.of("aarch64", SHA_ARM));
        assertEquals(List.of(), plan(unpinned, Map.of("tpl-minimal", unpinned), MachineType.CONTAINER, "x86_64",
                name -> false, loader(Map.of())));
    }

    @Test
    void aJavaToolsPinnedDownloadIsListedForItsArchitecture() {
        var agy = new AntigravitySetup();
        var root = base();
        root.setTools(List.of(new ToolDef.ToolRef("antigravity")));
        var planned = plan(root, Map.of("tpl-minimal", root), MachineType.CONTAINER, "aarch64", name -> false,
                loader(Map.of("antigravity", agy)));
        assertEquals(2, planned.size(), planned.toString());
        assertEquals(agy.pinnedDownloads("aarch64").getFirst(), planned.get(1));
        assertNotEquals(agy.pinnedDownloads("x86_64"), agy.pinnedDownloads("aarch64"));
    }

    @Test
    void planningSaysNothing() {
        var root = base();
        var events = RecordingOutput.during(() ->
                plan(root, Map.of("tpl-minimal", root), MachineType.CONTAINER, "x86_64", name -> false, loader(Map.of())))
                .events();
        assertEquals(List.of(), events);
    }

    private static List<DownloadCache.Pinned> plan(ImageDef template, Map<String, ImageDef> defs, MachineType type,
                                                   String arch, java.util.function.Predicate<String> built,
                                                   ToolDefLoader loader) {
        return BuildDownloads.plan(template, defs, type, arch, built, BuildCommand.BaseImage::of, loader, List.of());
    }

    private static ImageDef base() {
        var root = new ImageDef();
        root.setName("tpl-minimal");
        root.setImage("isx-fedora");
        root.setImageUrl("https://example.com/fedora-{tag}-{arch}.tar.xz");
        root.setImageTag("44");
        root.setImageSha256(Map.of("x86_64", SHA_X86, "aarch64", SHA_ARM));
        return root;
    }

    private static ImageDef layer(String name, String parent, String tool) {
        var def = new ImageDef();
        def.setName(name);
        def.setParent(parent);
        def.setTools(List.of(new ToolDef.ToolRef(tool)));
        return def;
    }

    private static ToolDefLoader loader(Map<String, ? extends dev.incusspawn.tool.ToolSetup> tools) {
        var loader = mock(ToolDefLoader.class);
        tools.forEach((name, setup) -> when(loader.find(name)).thenReturn(setup));
        return loader;
    }

    private static YamlToolSetup tool(String name, ToolDef.DownloadEntry... downloads) {
        var def = new ToolDef();
        def.setName(name);
        def.setDownloads(List.of(downloads));
        return new YamlToolSetup(def);
    }

    private static ToolDef.DownloadEntry download(String url, String sha256, String arch) {
        var dl = new ToolDef.DownloadEntry();
        dl.setUrl(url);
        dl.setSha256(sha256);
        dl.setArch(arch);
        dl.setExtract("/opt");
        return dl;
    }
}
