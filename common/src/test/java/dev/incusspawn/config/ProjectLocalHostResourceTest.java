package dev.incusspawn.config;

import dev.incusspawn.config.HostResourceSetup.HostPathOutsideProjectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Project-local templates may only reference host paths inside their project (#765).
 */
class ProjectLocalHostResourceTest {

    @TempDir
    Path tmp;

    private Path project;
    private Path outside;

    @BeforeEach
    void setUp() throws IOException {
        project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        outside = Files.createDirectories(tmp.resolve("outside")).toRealPath();
        Files.writeString(outside.resolve("id_rsa"), "secret");
    }

    @Test
    void rejectsTildePath() {
        assertRejected("~/.ssh", "copy");
    }

    @Test
    void rejectsAbsolutePathOutsideProject() {
        assertRejected(outside.toString(), "readonly");
    }

    @Test
    void rejectsDotDotEscape() {
        assertRejected("../outside/id_rsa", "copy");
    }

    @Test
    void rejectsDotDotEscapeFromNestedPath() throws IOException {
        Files.createDirectories(project.resolve("a"));
        assertRejected("a/../../outside", "readonly");
    }

    @Test
    void rejectsSymlinkEscapingProject() throws IOException {
        Files.createSymbolicLink(project.resolve("keys"), outside);
        assertRejected("keys", "readonly");
        assertRejected("keys/id_rsa", "copy");
    }

    @Test
    void rejectsDanglingSymlinkToOutside() throws IOException {
        Files.createSymbolicLink(project.resolve("later"), outside.resolve("not-yet"));
        assertRejected("later", "readonly");
    }

    @Test
    void rejectsDeepPathUnderDanglingEscapingSymlink() throws IOException {
        // More missing components than any recursion limit: the link must still be resolved.
        Files.createSymbolicLink(project.resolve("later"), outside.resolve("not-yet"));
        assertRejected("later/" + "d/".repeat(60) + "file", "readonly");
    }

    @Test
    void rejectsSymlinkLoop() throws IOException {
        Files.createSymbolicLink(project.resolve("a"), project.resolve("b"));
        Files.createSymbolicLink(project.resolve("b"), project.resolve("a"));
        assertRejected("a/x", "readonly");
    }

    @Test
    void acceptsDeepMissingPathInsideProject() {
        var deep = "cache/" + "d/".repeat(60) + "file";
        assertEquals(project.resolve(deep).toString(), collect(deep, "overlay").getSource());
    }

    @Test
    void rejectsCopiedDirectoryContainingEscapingSymlink() throws IOException {
        var data = Files.createDirectories(project.resolve("data/nested"));
        Files.createSymbolicLink(data.resolve("key"), outside.resolve("id_rsa"));
        assertRejected("data", "copy");
    }

    @Test
    void mountedDirectoryMayContainEscapingSymlink() throws IOException {
        // A mount leaves symlinks to be resolved inside the container, where they reach nothing
        // of the host's: only a host-side copy follows them.
        var data = Files.createDirectories(project.resolve("data"));
        Files.createSymbolicLink(data.resolve("key"), outside.resolve("id_rsa"));
        var result = collect("data", "readonly");
        assertEquals(data.toString(), result.getSource());
    }

    @Test
    void acceptsPathsInsideProject() throws IOException {
        var data = Files.createDirectories(project.resolve("data"));
        Files.createSymbolicLink(data.resolve("internal"), project.resolve("README"));
        Files.writeString(project.resolve("README"), "hi");

        var copied = collect("data", "copy");
        assertEquals(data.toString(), copied.getSource());
        assertEquals("/home/agentuser/data", copied.getPath(),
                "the container path must not change when the source is made absolute");
        assertEquals(project.toString(), copied.getConfinedTo());

        assertEquals(project.resolve("README").toString(), collect("./README", "readonly").getSource());
        assertEquals(project.toString(), collect(".", "readonly").getSource());
        assertEquals(data.toString(), collect(project.resolve("data").toString(), "overlay").getSource());
    }

    @Test
    void acceptsNonExistentPathInsideProject() {
        var result = collect("cache/m2", "overlay");
        assertEquals(project.resolve("cache/m2").toString(), result.getSource());
    }

    @Test
    void urlSourcesAreNotHostPaths() {
        var def = projectDef(new ImageDef.HostResource("https://example.com/f", "/etc/f", "copy"));
        var result = HostResourceSetup.collectEffective(def, Map.of("tpl-project", def)).getFirst();
        assertEquals("https://example.com/f", result.getSource());
        assertNull(result.getConfinedTo());
    }

    @Test
    void trustedParentOfProjectLocalChildKeepsItsResources() {
        var parent = new ImageDef();
        parent.setName("tpl-user");
        parent.setHostResources(List.of(new ImageDef.HostResource("~/.gitconfig", null, "readonly")));
        var child = projectDef(new ImageDef.HostResource("data", null, "readonly"));
        child.setParent("tpl-user");

        var result = HostResourceSetup.collectEffective(child, Map.of("tpl-user", parent, "tpl-project", child));
        assertEquals("~/.gitconfig", result.get(0).getSource());
        assertNull(result.get(0).getConfinedTo());
        assertEquals(project.resolve("data").toString(), result.get(1).getSource());
    }

    @Test
    void confinementCannotBeDeclaredInYaml() throws IOException {
        var def = ImageDef.parseYaml("""
                name: tpl-user
                host-resources:
                  - source: ~/.gitconfig
                    confined-to: /
                """);
        var result = HostResourceSetup.collectEffective(def, Map.of("tpl-user", def)).getFirst();
        assertNull(result.getConfinedTo());
    }

    @Test
    void verifyConfinedCatchesSymlinkSwappedInAfterBuild() throws IOException {
        var data = Files.createDirectories(project.resolve("data"));
        var result = collect("data", "readonly");
        HostResourceSetup.verifyConfined(result);

        Files.delete(data);
        Files.createSymbolicLink(data, outside);
        assertThrows(HostPathOutsideProjectException.class, () -> HostResourceSetup.verifyConfined(result));
    }

    @Test
    void confinementSurvivesMetadataRoundTrip() throws IOException {
        Files.createDirectories(project.resolve("data"));
        var restored = HostResourceSetup.deserialize(
                HostResourceSetup.serialize(List.of(collect("data", "readonly")))).getFirst();
        assertEquals(project.toString(), restored.getConfinedTo());
    }

    @Test
    void trustedResourcesSerializeWithoutConfinement() {
        var json = HostResourceSetup.serialize(List.of(new ImageDef.HostResource("~/.gitconfig", null, "readonly")));
        assertFalse(json.contains("confined-to"), json);
    }

    private void assertRejected(String source, String mode) {
        var def = projectDef(new ImageDef.HostResource(source, null, mode));
        var e = assertThrows(HostPathOutsideProjectException.class,
                () -> HostResourceSetup.collectEffective(def, Map.of("tpl-project", def)),
                source + " must be rejected");
        assertTrue(e.getMessage().contains("tpl-project"), e.getMessage());
        assertTrue(e.getMessage().contains(ImageDef.userImagesDir().toString()), e.getMessage());
    }

    private ImageDef.HostResource collect(String source, String mode) {
        var def = projectDef(new ImageDef.HostResource(source, null, mode));
        var result = HostResourceSetup.collectEffective(def, Map.of("tpl-project", def));
        assertEquals(1, result.size());
        return result.getFirst();
    }

    private ImageDef projectDef(ImageDef.HostResource hr) {
        var def = new ImageDef();
        def.setName("tpl-project");
        def.setSource(project.resolve(".incus-spawn/images/project.yaml").toString());
        def.setProjectRoot(project);
        def.setHostResources(List.of(hr));
        return def;
    }
}
