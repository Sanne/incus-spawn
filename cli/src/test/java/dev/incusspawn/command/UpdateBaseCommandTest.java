package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.baseimage.BaseImageReleases;
import dev.incusspawn.baseimage.BaseImageReleases.Checksums;
import dev.incusspawn.config.BaseImagePin;
import dev.incusspawn.config.ImageDef;
import org.aesh.command.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UpdateBaseCommandTest {

    private static String pin(Path home, Checksums sums) {
        return pin(home, ImageDef.loadBuiltinByName("tpl-minimal"), sums);
    }

    private static String pin(Path home, ImageDef def, Checksums sums) {
        var out = new String[1];
        InitCommandTest.withHome(home, () -> {
            try {
                new UpdateBaseCommand().writePin(new BaseImageReleases.Names(
                        def.getImage(), def.getImageUrl(), def.getVmImageUrl()), "fedora-44-20261020", sums);
                out[0] = Files.readString(BaseImagePin.file());
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
        return out[0];
    }

    @Test
    void aPinCarriesTheVmImageWithItsChecksums(@TempDir Path home) {
        var yaml = pin(home, new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of("x86_64", "v1", "aarch64", "v2")));
        assertTrue(yaml.contains("vm_image_url: "), yaml);
        assertTrue(yaml.contains("vm_image_sha256:\n  aarch64: v2\n  x86_64: v1\n"), yaml);
    }

    /** One arch's VM checksum is not a pin: the other arch would download the image unverified. */
    @Test
    void aPinWithTheVmImageForOneArchLeavesTheVmImageOut(@TempDir Path home) {
        var yaml = pin(home, new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of("x86_64", "v1")));
        assertFalse(yaml.contains("vm_image_url"), yaml);
        assertFalse(yaml.contains("vm_image_sha256"), yaml);
    }

    /** The same for the container image, which every template starts from: the pin is refused. */
    @Test
    void aReleaseWithTheContainerImageForOneArchIsNotPinned(@TempDir Path home) throws Exception {
        var release = new BaseImageReleases.Release("fedora-44-20261020", "2026-10-20", "sums", null);
        var releases = mock(BaseImageReleases.class);
        when(releases.fetchChecksums(org.mockito.ArgumentMatchers.eq(release),
                        org.mockito.ArgumentMatchers.anyString(), any()))
                .thenReturn(new Checksums(Map.of("x86_64", "c1"), Map.of("x86_64", "v1", "aarch64", "v2")));
        InitCommandTest.withHome(home, () -> {
            try {
                var cmd = new UpdateBaseCommand();
                cmd.releases = releases;
                var result = cmd.pinToTag("fedora-44-20261020", java.util.List.of(release),
                        ImageDef.loadBuiltinByName("tpl-minimal"));
                assertNotEquals(CommandResult.SUCCESS, result);
                assertFalse(Files.exists(BaseImagePin.file()));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** A pin is taken from the definition with no pin applied, so an earlier pin's lost VM image does not stick. */
    @Test
    void aPinAfterOneWithoutAVmImageNamesTheBuiltInVmImageAgain(@TempDir Path home) {
        pin(home, new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of()));
        InitCommandTest.withHome(home, () -> {
            assertNull(ImageDef.loadAll().get("tpl-minimal").getVmImageUrl());
            assertEquals(ImageDef.loadBuiltinByName("tpl-minimal").getVmImageUrl(),
                    UpdateBaseCommand.unpinnedMinimal().getVmImageUrl());
        });
    }

    /** A minimal.yaml of the user's own, on another repository, keeps its choice of no VM image. */
    @Test
    void aPinOfTheUsersOwnDefinitionGetsNoBuiltInVmImage(@TempDir Path home) throws Exception {
        var release = new BaseImageReleases.Release("fedora-44-20261020", "2026-10-20", "sums", null);
        var releases = mock(BaseImageReleases.class);
        when(releases.fetchChecksums(org.mockito.ArgumentMatchers.eq(release),
                        org.mockito.ArgumentMatchers.anyString(), any()))
                .thenReturn(new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of("x86_64", "v1", "aarch64", "v2")));
        InitCommandTest.withHome(home, () -> {
            try {
                var own = ImageDef.loadBuiltinByName("tpl-minimal");
                own.setImageUrl("https://github.com/someone/images/releases/download/{tag}/fedora-44-{arch}.tar.xz");
                own.setVmImageUrl(null);
                var cmd = new UpdateBaseCommand();
                cmd.releases = releases;
                assertEquals(CommandResult.SUCCESS,
                        cmd.pinToTag("fedora-44-20261020", java.util.List.of(release), own));
                var yaml = Files.readString(BaseImagePin.file());
                assertFalse(yaml.contains("vm_image_url"), yaml);
                org.mockito.Mockito.verify(releases).fetchChecksums(release, own.getImageUrl(), null);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** The built-in moved to {@code line}'s release, as a bump to another Fedora moves it (#1943). */
    private static ImageDef builtinOn(String line, String tag) {
        var def = ImageDef.loadBuiltinByName("tpl-minimal");
        var tag0 = def.getImageTag();
        var from = tag0.substring(0, tag0.indexOf('-', tag0.indexOf('-') + 1));
        def.setImage(def.getImage().replace(from + "-", line + "-"));
        def.setImageUrl(def.getImageUrl().replace("/" + from + "-", "/" + line + "-"));
        def.setVmImageUrl(def.getVmImageUrl().replace("/" + from + "-", "/" + line + "-"));
        def.setImageTag(tag);
        return def;
    }

    private static final String RELEASES = "https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/";

    /**
     * A release of another Fedora line is pinned under its own line's names (#1967): its assets are
     * named for it, so the sums are looked up, and the pin written, with the alias and both URLs
     * moved to it. Either way: back to the last Fedora 44 from 45, and ahead to 45 from 44.
     */
    @Test
    void aReleaseOfAnotherLineIsPinnedUnderItsOwnNames(@TempDir Path home) throws Exception {
        pinAcrossLines(home, builtinOn("fedora-45", "fedora-45-20261201"), "fedora-44-20261101", "fedora-44");
        pinAcrossLines(home, builtinOn("fedora-44", "fedora-44-20261101"), "fedora-45-20261201", "fedora-45");
    }

    private static void pinAcrossLines(Path home, ImageDef current, String tag, String line) throws Exception {
        var release = new BaseImageReleases.Release(tag, "2026-11-01", null, "sums");
        var url = RELEASES + line + "-{arch}.tar.xz";
        var vmUrl = RELEASES + line + "-{arch}-vm-direct.tar.xz";
        // The release lists only its own line's names: any other lookup finds nothing in it
        var releases = mock(BaseImageReleases.class);
        when(releases.fetchChecksums(any(),
                        any(), any()))
                .thenReturn(new Checksums(Map.of(), Map.of()));
        when(releases.fetchChecksums(release, url, vmUrl))
                .thenReturn(new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of("x86_64", "v1", "aarch64", "v2")));
        inHome(home, () -> {
            var cmd = new UpdateBaseCommand();
            cmd.releases = releases;
            assertEquals(CommandResult.SUCCESS, cmd.pinToTag(tag, List.of(release), current));
            var pin = BaseImagePin.read(BaseImagePin.file());
            assertEquals(line + "-base", pin.getImage());
            assertEquals(url, pin.getImageUrl());
            assertEquals(vmUrl, pin.getVmImageUrl());
            assertEquals(tag, pin.getImageTag());
        });
    }

    /** A definition whose names do not carry its line cannot be moved to another: refused, saying why. */
    @Test
    void aReleaseOfAnotherLineIsRefusedWhenTheNamesDoNotCarryTheLine(@TempDir Path home) throws Exception {
        var release = new BaseImageReleases.Release("fedora-45-20261201", "2026-12-01", null, "sums");
        var releases = mock(BaseImageReleases.class);
        var own = ImageDef.loadBuiltinByName("tpl-minimal");
        own.setImageUrl("https://github.com/someone/images/releases/download/{tag}/base-{arch}.tar.xz");
        own.setVmImageUrl(null);
        inHome(home, () -> {
            var cmd = new UpdateBaseCommand();
            cmd.releases = releases;
            var recorded = RecordingOutput.during(() -> assertNotEquals(CommandResult.SUCCESS,
                    cmd.pinToTag(release.tag(), List.of(release), own)));
            assertFalse(Files.exists(BaseImagePin.file()));
            var errors = recorded.events(RecordingOutput.Kind.ERROR);
            assertEquals(1, errors.size(), recorded.events().toString());
            assertTrue(errors.get(0).text().contains("fedora-45"), errors.get(0).text());
        });
        verifyNoInteractions(releases);
    }

    /** A release without the VM image the built-in names: pinning its URL with no checksum would download it unverified. */
    @Test
    void aPinWithoutVmChecksumsLeavesTheVmImageOut(@TempDir Path home) {
        var yaml = pin(home, new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of()));
        assertFalse(yaml.contains("vm_image_url"), yaml);
        assertFalse(yaml.contains("vm_image_sha256"), yaml);
        assertTrue(yaml.contains("image_sha256:\n  aarch64: c2\n  x86_64: c1\n"), yaml);
    }

    private static final BaseImageReleases.Release RELEASE =
            new BaseImageReleases.Release("fedora-44-20261020", "2026-10-20", "sums", null);

    /** Pins {@code RELEASE} as {@code isx update-base <tag>} does. */
    private static void pinRelease() throws java.io.IOException {
        var releases = mock(BaseImageReleases.class);
        when(releases.fetchChecksums(org.mockito.ArgumentMatchers.eq(RELEASE),
                        org.mockito.ArgumentMatchers.anyString(), any()))
                .thenReturn(new Checksums(Map.of("x86_64", "c1", "aarch64", "c2"), Map.of("x86_64", "v1", "aarch64", "v2")));
        var cmd = new UpdateBaseCommand();
        cmd.releases = releases;
        assertEquals(CommandResult.SUCCESS,
                cmd.pinToTag(RELEASE.tag(), List.of(RELEASE), UpdateBaseCommand.unpinnedMinimal()));
    }

    interface IoBody { void run() throws java.io.IOException; }

    private static void inHome(Path home, IoBody body) {
        InitCommandTest.withHome(home, () -> {
            try {
                body.run();
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    private static void writeUserMinimal(String yaml) throws java.io.IOException {
        Files.createDirectories(ImageDef.userImagesDir());
        Files.writeString(BaseImagePin.legacyFile(), yaml);
    }

    private static String userMinimal(String extra) {
        var builtin = ImageDef.loadBuiltinByName("tpl-minimal");
        return "name: tpl-minimal\nimage: " + builtin.getImage() + "\nimage_url: " + builtin.getImageUrl()
                + "\nimage_tag: " + builtin.getImageTag() + "\n" + extra;
    }

    /** A pin changes only the base image: a hand-edited minimal.yaml keeps its edits (#1563). */
    @Test
    void aPinKeepsTheUsersOwnEditsToTheDefinition(@TempDir Path home) {
        inHome(home, () -> {
            writeUserMinimal(userMinimal("packages: [htop]\n"));
            pinRelease();
            var minimal = ImageDef.loadAll().get("tpl-minimal");
            assertEquals(List.of("htop"), minimal.getPackages());
            assertTrue(minimal.isPinned());
            assertEquals(RELEASE.tag(), minimal.getImageTag());
            assertEquals(Map.of("x86_64", "c1", "aarch64", "c2"), minimal.getImageSha256());
        });
    }

    /** What the definition gains after the pin reaches the pinned template, as a built-in change would (#1563). */
    @Test
    void aDefinitionChangedAfterThePinIsStillPinned(@TempDir Path home) {
        inHome(home, () -> {
            writeUserMinimal(userMinimal(""));
            pinRelease();
            writeUserMinimal(userMinimal("mask_services: [tmp.mount, other.service]\n"));
            var minimal = ImageDef.loadAll().get("tpl-minimal");
            assertEquals(List.of("tmp.mount", "other.service"), minimal.getMaskServices());
            assertTrue(minimal.isPinned());
            assertEquals(RELEASE.tag(), minimal.getImageTag());
        });
    }

    /** With no definition of the user's own, the pinned template is the built-in, its masks included. */
    @Test
    void aPinOfTheBuiltInKeepsEverythingButTheBaseImage(@TempDir Path home) {
        inHome(home, () -> {
            pinRelease();
            assertFalse(Files.exists(BaseImagePin.legacyFile()));
            var minimal = ImageDef.loadAll().get("tpl-minimal");
            var builtin = ImageDef.loadBuiltinByName("tpl-minimal");
            assertEquals(builtin.getMaskServices(), minimal.getMaskServices());
            assertEquals(builtin.getDescription(), minimal.getDescription());
            assertEquals(builtin.getVmImageUrl(), minimal.getVmImageUrl());
            assertEquals(Map.of("x86_64", "v1", "aarch64", "v2"), minimal.getVmImageSha256());
            assertTrue(minimal.isPinned());
        });
    }

    /** --latest removes the pin, never a definition the user wrote. */
    @Test
    void trackingTheLatestKeepsTheUsersOwnDefinition(@TempDir Path home) {
        inHome(home, () -> {
            writeUserMinimal(userMinimal("packages: [htop]\n"));
            pinRelease();
            new UpdateBaseCommand().trackLatest(RELEASE.tag());
            var minimal = ImageDef.loadAll().get("tpl-minimal");
            assertEquals(List.of("htop"), minimal.getPackages());
            assertFalse(minimal.isPinned());
        });
    }

    /** A minimal.yaml an earlier update-base wrote replaced the built-in whole; pinning again removes it. */
    @Test
    void pinningAgainRemovesAPinAnEarlierIsxWrote(@TempDir Path home) {
        inHome(home, () -> {
            var builtin = ImageDef.loadBuiltinByName("tpl-minimal");
            writeUserMinimal("name: tpl-minimal\ndescription: Base OS only\nimage: " + builtin.getImage()
                    + "\nimage_url: " + builtin.getImageUrl() + "\nimage_tag: fedora-44-20260101\npinned: true\n"
                    + "image_sha256:\n  aarch64: o2\n  x86_64: o1\n");
            assertTrue(ImageDef.loadAll().get("tpl-minimal").getMaskServices().isEmpty());
            pinRelease();
            assertFalse(Files.exists(BaseImagePin.legacyFile()));
            var minimal = ImageDef.loadAll().get("tpl-minimal");
            assertEquals(builtin.getMaskServices(), minimal.getMaskServices());
            assertEquals(builtin.getVmImageUrl(), minimal.getVmImageUrl());
            assertEquals(RELEASE.tag(), minimal.getImageTag());
        });
    }

    /** --latest removes an earlier isx's pin too, which is the command's, not the user's. */
    @Test
    void trackingTheLatestRemovesAPinAnEarlierIsxWrote(@TempDir Path home) {
        inHome(home, () -> {
            var builtin = ImageDef.loadBuiltinByName("tpl-minimal");
            writeUserMinimal("name: tpl-minimal\nimage: " + builtin.getImage() + "\nimage_url: " + builtin.getImageUrl()
                    + "\nimage_tag: fedora-44-20260101\npinned: true\nimage_sha256: {x86_64: o1, aarch64: o2}\n");
            new UpdateBaseCommand().trackLatest(RELEASE.tag());
            assertFalse(Files.exists(BaseImagePin.legacyFile()));
            assertFalse(ImageDef.loadAll().get("tpl-minimal").isPinned());
        });
    }

    /** A pin's checksums are never paired with another publisher's files. */
    @Test
    void aPinOfAnotherRepositoryIsIgnored(@TempDir Path home) {
        inHome(home, () -> {
            pinRelease();
            writeUserMinimal("name: tpl-minimal\nimage: own\nimage_url: https://github.com/someone/images/releases/download/{tag}/f-{arch}.tar.xz\nimage_tag: t1\n");
            var warnings = new java.util.ArrayList<String>();
            var minimal = ImageDef.loadAll(warnings::add).get("tpl-minimal");
            assertFalse(minimal.isPinned());
            assertEquals("t1", minimal.getImageTag());
            assertTrue(warnings.stream().anyMatch(w -> w.contains("the pin is ignored")), warnings.toString());
        });
    }

    /** A pin that cannot be used makes the build refuse, rather than track the latest release instead. */
    @Test
    void anUnusablePinIsAParseFailure(@TempDir Path home) {
        inHome(home, () -> {
            Files.createDirectories(BaseImagePin.file().getParent());
            Files.writeString(BaseImagePin.file(), "image_tag: fedora-44-20261020\nimage_tagg: x\n");
            var warnings = new java.util.ArrayList<String>();
            var layers = ImageDef.loadLayers(warnings::add);
            assertFalse(layers.defs().get("tpl-minimal").isPinned());
            assertEquals(List.of(BaseImagePin.file().toAbsolutePath().normalize()), layers.parseFailures());
            assertTrue(warnings.stream().anyMatch(w -> w.contains("unknown key 'image_tagg'")), warnings.toString());
        });
    }

    /** A pin file without a checksum for every arch would download unverified on the arch without one. */
    @Test
    void aPinFileMissingAnArchIsAParseFailure(@TempDir Path home) {
        inHome(home, () -> {
            var builtin = ImageDef.loadBuiltinByName("tpl-minimal");
            new BaseImagePin(builtin.getImage(), builtin.getImageUrl(), null, "t1", Map.of("x86_64", "c1"), null)
                    .write(BaseImagePin.file());
            var warnings = new java.util.ArrayList<String>();
            var layers = ImageDef.loadLayers(warnings::add);
            assertFalse(layers.defs().get("tpl-minimal").isPinned());
            assertEquals(List.of(BaseImagePin.file().toAbsolutePath().normalize()), layers.parseFailures());
            assertTrue(warnings.stream().anyMatch(w -> w.contains("'image_sha256' does not list")), warnings.toString());
        });
    }

    /** --latest leaves a definition that pins itself to the user, and says it still pins. */
    @Test
    void trackingTheLatestOverTheUsersOwnPinSaysItStillPins(@TempDir Path home) {
        inHome(home, () -> {
            writeUserMinimal(userMinimal("pinned: true\nparent: ~\npackages: [htop]\n"));
            var out = RecordingOutput.during(() -> new UpdateBaseCommand().trackLatest(RELEASE.tag()));
            assertTrue(Files.exists(BaseImagePin.legacyFile()));
            assertTrue(ImageDef.loadAll().get("tpl-minimal").isPinned());
            assertTrue(out.warnings().stream().anyMatch(w -> w.contains("sets 'pinned: true' itself")), out.warnings()::toString);
        });
    }

    /** An earlier pin copied the masks of the definition in effect: other masks than the built-in's are the user's choice. */
    @Test
    void anEarlierPinWithMasksOfItsOwnIsKept(@TempDir Path home) {
        inHome(home, () -> {
            var builtin = ImageDef.loadBuiltinByName("tpl-minimal");
            writeUserMinimal("name: tpl-minimal\nmask_services: [other.service]\nimage: " + builtin.getImage()
                    + "\nimage_url: " + builtin.getImageUrl() + "\nimage_tag: fedora-44-20260101\npinned: true\n"
                    + "image_sha256: {x86_64: o1, aarch64: o2}\n");
            pinRelease();
            assertTrue(Files.exists(BaseImagePin.legacyFile()));
            var minimal = ImageDef.loadAll().get("tpl-minimal");
            assertEquals(List.of("other.service"), minimal.getMaskServices());
            assertEquals(RELEASE.tag(), minimal.getImageTag());
        });
    }

    /** The track-or-pin question is asked through Prompts, so a test sees and answers it (#1703). */
    @Test
    void theTrackOrPinQuestionIsAskedThroughPrompts() throws Exception {
        var prompts = ScriptedPrompts.lines("3");
        var recorded = RecordingOutput.during(() -> assertEquals(0,
                new UpdateBaseCommand().askWhatToDo(prompts, RELEASE, List.of(RELEASE), null).getResultValue()));
        assertEquals(List.of(
                "What would you like to do?",
                "1) Track latest (fedora-44-20261020) — always installs the newest release on build",
                "2) Pin a specific version",
                "3) Cancel",
                "? Choice [1-3]: "), prompts.conversation());
        assertTrue(recorded.events(RecordingOutput.Kind.STEP).stream().anyMatch(e -> e.msg().equals("Aborted.")));
    }

    /** Pinning asks for the tag through the same prompts, and an empty one pins nothing. */
    @Test
    void anEmptyTagToPinAborts(@TempDir Path home) throws Exception {
        var prompts = ScriptedPrompts.lines("2", "");
        InitCommandTest.withHome(home, () -> {
            var recorded = RecordingOutput.during(() -> assertEquals(0,
                    new UpdateBaseCommand().askWhatToDo(prompts, RELEASE, List.of(RELEASE), null).getResultValue()));
            assertTrue(recorded.events(RecordingOutput.Kind.STEP).stream().anyMatch(e -> e.msg().equals("Aborted.")));
            assertFalse(Files.exists(BaseImagePin.file()));
        });
        assertEquals("? Enter version tag: ", prompts.conversation().getLast());
    }

    /** An unknown tag is an error, then the releases there are, which the TUI's log and a recorder keep (#1703). */
    @Test
    void anUnknownTagIsAnErrorAndTheReleasesFollow(@TempDir Path home) {
        InitCommandTest.withHome(home, () -> {
            var recorded = RecordingOutput.during(() -> assertEquals(1, new UpdateBaseCommand()
                    .pinToTag("fedora-44-nope\u001b[2J", List.of(RELEASE), UpdateBaseCommand.unpinnedMinimal()).getResultValue()));
            assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR, "release 'fedora-44-nope [2J' not found.")),
                    recorded.events(RecordingOutput.Kind.ERROR));
            assertEquals(List.of("Available base images"), recorded.events(RecordingOutput.Kind.GROUP).stream()
                    .map(RecordingOutput.Event::msg).toList());
        });
    }
}
