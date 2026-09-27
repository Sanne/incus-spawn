package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HostResourceSetupTest {

    @Test
    void resolveContainerPathUsesExplicitPath() {
        assertEquals("/custom/path", HostResourceSetup.resolveContainerPath("~/.m2/repository", "/custom/path"));
    }

    @Test
    void resolveContainerPathExpandsTildeInExplicitPath() {
        assertEquals("/home/agentuser/.gitconfig",
                HostResourceSetup.resolveContainerPath("~/sources/templates/.gitconfig", "~/.gitconfig"));
    }

    @Test
    void resolveContainerPathDefaultsFromTilde() {
        assertEquals("/home/agentuser/.m2/repository",
                HostResourceSetup.resolveContainerPath("~/.m2/repository", null));
        assertEquals("/home/agentuser/.gitconfig",
                HostResourceSetup.resolveContainerPath("~/.gitconfig", null));
        assertEquals("/home/agentuser/some/dir",
                HostResourceSetup.resolveContainerPath("~/some/dir", null));
    }

    @Test
    void resolveContainerPathKeepsAbsolutePaths() {
        assertEquals("/opt/cache", HostResourceSetup.resolveContainerPath("/opt/cache", null));
    }

    @Test
    void resolveContainerPathRequiresPathForUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> HostResourceSetup.resolveContainerPath("https://example.com/file", null));
        assertThrows(IllegalArgumentException.class,
                () -> HostResourceSetup.resolveContainerPath("http://example.com/file", null));
    }

    @Test
    void resolveContainerPathAcceptsUrlWithExplicitPath() {
        assertEquals("/home/agentuser/.gitconfig",
                HostResourceSetup.resolveContainerPath("https://example.com/gitconfig", "/home/agentuser/.gitconfig"));
    }

    @Test
    void expandHostTildeExpandsHome() {
        var home = System.getProperty("user.home");
        assertEquals(home + "/.m2/repository", HostResourceSetup.expandHostTilde("~/.m2/repository"));
        assertEquals(home + "/.gitconfig", HostResourceSetup.expandHostTilde("~/.gitconfig"));
    }

    @Test
    void expandHostTildePassesThroughAbsolutePaths() {
        assertEquals("/opt/cache", HostResourceSetup.expandHostTilde("/opt/cache"));
    }

    @Test
    void expandHostTildeHandlesBareHome() {
        var home = System.getProperty("user.home");
        assertEquals(home, HostResourceSetup.expandHostTilde("~"));
    }

    @Test
    void deviceNameIsDeterministic() {
        var name1 = HostResourceSetup.deviceName("/home/agentuser/.m2/repository");
        var name2 = HostResourceSetup.deviceName("/home/agentuser/.m2/repository");
        assertEquals(name1, name2);
        assertTrue(name1.startsWith("hr-"));
    }

    @Test
    void deviceNameIsReadable() {
        assertEquals("hr-home-agentuser--m2-repository",
                HostResourceSetup.deviceName("/home/agentuser/.m2/repository"));
        assertEquals("hr-home-agentuser--gitconfig",
                HostResourceSetup.deviceName("/home/agentuser/.gitconfig"));
    }

    @Test
    void deviceNameTruncatesLongPaths() {
        var longPath = "/home/agentuser/.local/share/containers/storage/overlay-images/very-long-path";
        var name = HostResourceSetup.deviceName(longPath);
        assertTrue(name.length() <= 64, "device name must fit 64-char limit, got: " + name.length() + " (" + name + ")");
        assertTrue(name.startsWith("hr-"));
    }

    @Test
    void overlayDeviceNameFitsIncusLimit() {
        var name = HostResourceSetup.overlayDeviceName("/home/agentuser/.m2/repository");
        assertTrue(name.length() <= 64, "overlay device name must fit Incus 64-char limit, got: " + name.length());
        assertEquals("hr-home-agentuser--m2-repository-lo", name);
    }

    @Test
    void deviceNameDiffersForDifferentPaths() {
        var name1 = HostResourceSetup.deviceName("/home/agentuser/.m2/repository");
        var name2 = HostResourceSetup.deviceName("/home/agentuser/.gitconfig");
        assertNotEquals(name1, name2);
    }

    @Test
    void collectEffectiveSingleImage() {
        var defs = new LinkedHashMap<String, ImageDef>();
        var imageDef = makeImageDef("tpl-test", null, List.of(
                new ImageDef.HostResource("~/.gitconfig", null, "readonly")));
        defs.put("tpl-test", imageDef);

        var result = HostResourceSetup.collectEffective(imageDef, defs);
        assertEquals(1, result.size());
        assertEquals("~/.gitconfig", result.get(0).getSource());
    }

    @Test
    void collectEffectiveInheritsFromParent() {
        var defs = new LinkedHashMap<String, ImageDef>();
        var parent = makeImageDef("tpl-parent", null, List.of(
                new ImageDef.HostResource("~/.gitconfig", null, "readonly")));
        var child = makeImageDef("tpl-child", "tpl-parent", List.of(
                new ImageDef.HostResource("~/.m2/repository", null, "overlay")));
        defs.put("tpl-parent", parent);
        defs.put("tpl-child", child);

        var result = HostResourceSetup.collectEffective(child, defs);
        assertEquals(2, result.size());
    }

    @Test
    void collectEffectiveChildOverridesParentByPath() {
        var defs = new LinkedHashMap<String, ImageDef>();
        var parent = makeImageDef("tpl-parent", null, List.of(
                new ImageDef.HostResource("~/.gitconfig", null, "readonly")));
        var child = makeImageDef("tpl-child", "tpl-parent", List.of(
                new ImageDef.HostResource("~/.gitconfig", null, "copy")));
        defs.put("tpl-parent", parent);
        defs.put("tpl-child", child);

        var result = HostResourceSetup.collectEffective(child, defs);
        assertEquals(1, result.size());
        assertEquals("copy", result.get(0).getMode());
    }

    @Test
    void collectEffectiveDeepChain() {
        var defs = new LinkedHashMap<String, ImageDef>();
        var root = makeImageDef("tpl-root", null, List.of(
                new ImageDef.HostResource("~/.gitconfig", null, "readonly")));
        var mid = makeImageDef("tpl-mid", "tpl-root", List.of(
                new ImageDef.HostResource("~/.m2/repository", null, "overlay")));
        var leaf = makeImageDef("tpl-leaf", "tpl-mid", List.of(
                new ImageDef.HostResource("~/.ssh/config", null, "readonly")));
        defs.put("tpl-root", root);
        defs.put("tpl-mid", mid);
        defs.put("tpl-leaf", leaf);

        var result = HostResourceSetup.collectEffective(leaf, defs);
        assertEquals(3, result.size());
    }

    @Test
    void collectEffectiveEmptyResources() {
        var defs = new LinkedHashMap<String, ImageDef>();
        var imageDef = makeImageDef("tpl-test", null, List.of());
        defs.put("tpl-test", imageDef);

        var result = HostResourceSetup.collectEffective(imageDef, defs);
        assertTrue(result.isEmpty());
    }

    @Test
    void serializeDeserializeRoundTrip() {
        var resources = List.of(
                new ImageDef.HostResource("~/.m2/repository", "/home/agentuser/.m2/repository", "overlay"),
                new ImageDef.HostResource("~/.gitconfig", null, "readonly"));

        var json = HostResourceSetup.serialize(resources);
        var deserialized = HostResourceSetup.deserialize(json);

        assertEquals(2, deserialized.size());
        assertEquals("~/.m2/repository", deserialized.get(0).getSource());
        assertEquals("overlay", deserialized.get(0).getMode());
        assertEquals("~/.gitconfig", deserialized.get(1).getSource());
        assertEquals("readonly", deserialized.get(1).getMode());
    }

    @Test
    void deserializeEmptyStringReturnsEmptyList() {
        assertTrue(HostResourceSetup.deserialize("").isEmpty());
        assertTrue(HostResourceSetup.deserialize(null).isEmpty());
    }

    @Test
    void deserializeMalformedJsonReturnsEmptyList() {
        assertTrue(HostResourceSetup.deserialize("not json").isEmpty());
    }

    // --- Mount targets: devices attach before first boot, so none may shadow a system directory ---

    @Test
    void mountIntoSystemDirectoriesIsRejected() {
        for (var target : List.of("/etc/containers", "/usr/local/share", "/var/lib/rpm", "/var/lib/foo",
                "/run/x", "/tmp/x", "/boot", "/lib64/x", "/proc/x", "/", "/opt/../etc/x")) {
            for (var mode : List.of("readonly", "overlay")) {
                var hr = new ImageDef.HostResource("~/src", target, mode);
                assertThrows(HostResourceSetup.ForbiddenMountTargetException.class,
                        () -> HostResourceSetup.requireAllowedMountTarget(hr), target + " " + mode);
            }
        }
    }

    @Test
    void mountOutsideSystemDirectoriesAndCopyAnywhereAreAllowed() {
        for (var target : List.of("~/.m2", "/home/agentuser/x", "/opt/cache", "/srv/data",
                "/mnt/podman-host-storage", "/etcetera", "/variable")) {
            assertDoesNotThrow(() -> HostResourceSetup.requireAllowedMountTarget(
                    new ImageDef.HostResource("~/src", target, "readonly")), target);
        }
        // copy writes files rather than mounting: placing a config file under /etc is fine.
        assertDoesNotThrow(() -> HostResourceSetup.requireAllowedMountTarget(
                new ImageDef.HostResource("~/storage.conf", "/etc/containers/storage.conf", "copy")));
    }

    @Test
    void collectEffectiveRejectsASystemMountUnlessAChildOverridesItWithCopy() {
        var defs = new LinkedHashMap<String, ImageDef>();
        var parent = makeImageDef("tpl-parent", null, List.of(
                new ImageDef.HostResource("~/storage.conf", "/etc/containers/storage.conf", "readonly")));
        var child = makeImageDef("tpl-child", "tpl-parent", List.of(
                new ImageDef.HostResource("~/storage.conf", "/etc/containers/storage.conf", "copy")));
        defs.put("tpl-parent", parent);
        defs.put("tpl-child", child);

        assertThrows(HostResourceSetup.ForbiddenMountTargetException.class,
                () -> HostResourceSetup.collectEffective(parent, defs));
        assertEquals("copy", HostResourceSetup.collectEffective(child, defs).get(0).getMode());
    }

    @Test
    void branchingFromATemplateBuiltWithASystemMountFailsBeforeCopying() {
        var incus = org.mockito.Mockito.mock(dev.incusspawn.incus.IncusClient.class);
        org.mockito.Mockito.when(incus.configGet("tpl-old", dev.incusspawn.incus.Metadata.HOST_RESOURCES))
                .thenReturn(HostResourceSetup.serialize(List.of(
                        new ImageDef.HostResource("~/src", "/var/lib/foo", "readonly"))));
        org.mockito.Mockito.when(incus.configGet("tpl-ok", dev.incusspawn.incus.Metadata.HOST_RESOURCES))
                .thenReturn(HostResourceSetup.serialize(List.of(
                        new ImageDef.HostResource("~/src", "/opt/foo", "readonly"))));

        var e = assertThrows(HostResourceSetup.ForbiddenMountTargetException.class,
                () -> HostResourceSetup.requireBranchableTemplate(incus, "tpl-old"));
        assertTrue(e.getMessage().contains("isx build tpl-old"), e.getMessage());
        assertDoesNotThrow(() -> HostResourceSetup.requireBranchableTemplate(incus, "tpl-ok"));
    }

    @Test
    void applyForInstanceRefusesASystemMountBeforeAttachingAnything() {
        var incus = org.mockito.Mockito.mock(dev.incusspawn.incus.IncusClient.class);
        assertThrows(HostResourceSetup.ForbiddenMountTargetException.class,
                () -> HostResourceSetup.applyForInstance(incus, "b", List.of(
                        new ImageDef.HostResource(System.getProperty("java.io.tmpdir"), "/opt/ok", "readonly"),
                        new ImageDef.HostResource("~/src", "/etc/x", "overlay")), false));
        org.mockito.Mockito.verifyNoInteractions(incus);
    }

    @Test
    void hostPathOfDeviceSourceUndoesTheMacOsHomeTranslation() {
        assertEquals(Path.of("/home/me/inbox"),
                HostResourceSetup.hostPathOfDeviceSource("/home/me/inbox", false, "/home/me"));
        assertEquals(Path.of("/Users/me/inbox"),
                HostResourceSetup.hostPathOfDeviceSource("/host/inbox", true, "/Users/me"));
        assertEquals(Path.of("/Users/me"), HostResourceSetup.hostPathOfDeviceSource("/host", true, "/Users/me"));
        // Outside the shared home the path lives in the appliance VM: not checkable from the host.
        assertNull(HostResourceSetup.hostPathOfDeviceSource("/srv/data", true, "/Users/me"));
        assertNull(HostResourceSetup.hostPathOfDeviceSource("/hostile", true, "/Users/me"));
    }

    private static ImageDef makeImageDef(String name, String parent, List<ImageDef.HostResource> hostResources) {
        var def = new ImageDef();
        def.setName(name);
        def.setParent(parent);
        def.setHostResources(hostResources);
        return def;
    }
}
