package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CleanCommandTest {

    private final AtomicInteger refreshes = new AtomicInteger();
    private final Runnable realRefresh = CleanCommand.proxyRefresh;

    {
        CleanCommand.proxyRefresh = refreshes::incrementAndGet;
    }

    @AfterEach
    void restoreRefresh() {
        CleanCommand.proxyRefresh = realRefresh;
    }

    private static IncusClient withInstances(String... names) {
        var incus = mock(IncusClient.class);
        var list = new ArrayList<Map<String, String>>();
        for (var name : names) list.add(Map.of("name", name));
        when(incus.list()).thenReturn(list);
        return incus;
    }

    @Test
    void deletingFailedBuildsTellsTheProxyOnce() {
        // Each held a static IP and its template's pins (#903): the proxy must stop serving them
        var incus = withInstances("tpl-a-failed-build", "dev-1", "tpl-b-failed-build");
        var warnings = new ArrayList<String>();

        assertEquals(2, CleanCommand.deleteFailedBuilds(incus, warnings));

        verify(incus).delete("tpl-a-failed-build", true);
        verify(incus).delete("tpl-b-failed-build", true);
        verify(incus, never()).delete(eq("dev-1"), anyBoolean());
        assertEquals(1, refreshes.get());
        assertEquals(List.of(), warnings);
    }

    @Test
    void nothingDeletedSignalsNothing() {
        var incus = withInstances("tpl-a-failed-build");
        doThrow(new RuntimeException("busy")).when(incus).delete("tpl-a-failed-build", true);
        var warnings = new ArrayList<String>();

        assertEquals(0, CleanCommand.deleteFailedBuilds(incus, warnings));

        assertEquals(0, refreshes.get());
        assertEquals(1, warnings.size());
    }

    @Test
    void doctorsSingleDeleteTellsTheProxy() {
        var incus = withInstances();
        CleanCommand.deleteFailedBuild(incus, "tpl-a-failed-build");
        verify(incus).delete("tpl-a-failed-build", true);
        assertEquals(1, refreshes.get());
    }

    private static IncusClient.ImageInfo image(String fingerprint, String... aliases) {
        return new IncusClient.ImageInfo(fingerprint, 100, List.of(aliases));
    }

    private static ImageDef def(String image, String imageUrl, String vmImageUrl) {
        var def = new ImageDef();
        def.setName("tpl-" + image);
        def.setImage(image);
        def.setImageUrl(imageUrl);
        def.setVmImageUrl(vmImageUrl);
        return def;
    }

    private static List<String> fingerprints(List<IncusClient.ImageInfo> images) {
        return images.stream().map(IncusClient.ImageInfo::fingerprint).toList();
    }

    @Test
    void downloadedBaseImagesAreTheirOwnCategory() {
        var defs = List.of(
                def("fedora-44-base", "https://example.com/fedora.tar.xz", "https://example.com/fedora-vm.tar.xz"),
                def("hand-made", null, null),
                def("images:debian/13", null, null));
        var scan = CleanCommand.classifyImages(List.of(
                image("container", "fedora-44-base"),
                // The VM image lives under a derived alias, which once read as unused, so a
                // clean deleted it and the next VM build downloaded gigabytes again.
                image("vm", "fedora-44-base-vm"),
                image("manual", "hand-made"),
                image("orphan", "something-else"),
                image("unaliased")), defs);

        assertEquals(List.of("orphan", "unaliased"), fingerprints(scan.unused()));
        assertEquals(List.of("container", "vm"), fingerprints(scan.base()));
    }

    @Test
    void vmAliasWithoutAVmImageUrlIsUnused() {
        var scan = CleanCommand.classifyImages(List.of(image("vm", "fedora-44-base-vm")),
                List.of(def("fedora-44-base", "https://example.com/fedora.tar.xz", null)));

        assertEquals(List.of("vm"), fingerprints(scan.unused()));
        assertEquals(List.of(), scan.base());
    }
}
