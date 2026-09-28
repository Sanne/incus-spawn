package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CleanCommandTest {

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
