package dev.incusspawn.config;

import dev.incusspawn.git.HostRepoSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImageDefHostRepoRootTest {
    @TempDir Path tempDir;

    @Test
    void eachSearchPathSuppliesItsOwnRootAndYamlCannotOverrideIt() throws Exception {
        var first = Files.createDirectories(tempDir.resolve("first/images"));
        var second = Files.createDirectories(tempDir.resolve("second/images"));
        Files.writeString(first.resolve("parent.yaml"), """
                name: tpl-root-parent
                hostRepoRoot: /
                repos:
                  - url: ../repo
                    hostRepoRoot: /
                    templateDirectory: /
                """);
        Files.writeString(second.resolve("child.yaml"), """
                name: tpl-root-child
                parent: tpl-root-parent
                repos:
                  - url: ../repo
                """);
        var definitions = ImageDef.loadAll(List.of(first.getParent().toString(), second.getParent().toString()));
        var parent = definitions.get("tpl-root-parent");
        var child = definitions.get("tpl-root-child");
        assertRoot(parent, first.getParent(), first);
        assertRoot(child, second.getParent(), second);
        var stored = new BuildSource(Map.of(parent.getName(), parent, child.getName(), child), Map.of(), Map.of(),
                Map.of(parent.getName(), parent.getSource(), child.getName(), child.getSource()));
        var restored = BuildSource.fromJson(stored.toJson()).getDefinitions();
        assertRoot(restored.get(parent.getName()), first.getParent(), first);
        assertRoot(restored.get(child.getName()), second.getParent(), second);
    }

    @Test
    void projectHostReposAreConfinedToTheProjectDirectory() throws Exception {
        var images = ImageDef.projectImagesDir().toAbsolutePath();
        var project = images.getParent().getParent();
        boolean hadImages = Files.exists(images);
        boolean hadProjectConfig = Files.exists(images.getParent());
        Files.createDirectories(images);
        var file = Files.createTempFile(images, "host-root-test-", ".yaml");
        var source = Files.createTempDirectory(project, "host-root-repo-");
        try {
            var init = new ProcessBuilder("git", "init", "-b", "main", source.toString())
                    .redirectErrorStream(true).start();
            var output = new String(init.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, init.waitFor(), output);
            Files.writeString(file, "name: tpl-root-project-test\nrepos:\n  - url: ../../" + source.getFileName() + "\n");
            var def = ImageDef.loadAll(List.of()).get("tpl-root-project-test");
            assertEquals(project, def.getProjectRoot());
            var stored = new BuildSource(Map.of(def.getName(), def), Map.of(), Map.of(),
                    Map.of(def.getName(), def.getSource()));
            var restored = BuildSource.fromJson(stored.toJson()).getDefinitions().get(def.getName());
            var link = Files.createSymbolicLink(source.resolve("outside"), project.getParent());
            for (var definition : List.of(def, restored)) {
                assertRoot(definition, project, images);
                var repo = definition.getRepos().getFirst();
                for (var url : List.of("../../" + source.getFileName(), "file:../../" + source.getFileName(),
                        source.toString(), source.toUri().toString())) {
                    repo.setUrl(url);
                    assertEquals(source.resolve(".git").toRealPath(), HostRepoSource.gitDirectory(repo));
                }
                for (var url : List.of("../../..", "file:../../..", project.getParent().toString(),
                        project.getParent().toUri().toString())) {
                    repo.setUrl(url);
                    var failure = assertThrows(IllegalArgumentException.class, () -> HostRepoSource.gitDirectory(repo));
                    assertTrue(failure.getMessage().contains("normalized-path rule failed"), failure.getMessage());
                    assertTrue(failure.getMessage().contains("allowed root: " + project), failure.getMessage());
                }
                repo.setUrl(images.relativize(link).toString());
                var failure = assertThrows(IllegalArgumentException.class, () -> HostRepoSource.gitDirectory(repo));
                assertTrue(failure.getMessage().contains("canonical-path rule failed"), failure.getMessage());
                assertTrue(failure.getMessage().contains("allowed root: " + project), failure.getMessage());
            }
        } finally {
            Files.delete(file);
            try (var paths = Files.walk(source)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            if (!hadImages) Files.delete(images);
            if (!hadProjectConfig) Files.delete(images.getParent());
        }
    }

    @Test
    void userTemplatesAreConfinedToTheConfigDirectory() throws Exception {
        var originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            var images = Files.createDirectories(ImageDef.userImagesDir());
            Files.writeString(images.resolve("local.yaml"), "name: tpl-user-root\nrepos:\n  - url: ../repo\n");
            var def = ImageDef.loadAll(List.of()).get("tpl-user-root");
            assertRoot(def, SpawnConfig.configDir(), images);
            var stored = new BuildSource(Map.of(def.getName(), def), Map.of(), Map.of(),
                    Map.of(def.getName(), def.getSource()));
            assertRoot(BuildSource.fromJson(stored.toJson()).getDefinitions().get(def.getName()),
                    SpawnConfig.configDir(), images);
            def.getRepos().getFirst().setUrl(tempDir.resolve("private").toString());
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> HostRepoSource.capture(def.getRepos().getFirst()));
            assertTrue(failure.getMessage().contains("normalized-path rule failed"));
            assertTrue(failure.getMessage().contains(SpawnConfig.configDir().toString()));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void missingStoredRootAndUnloadedYamlCannotAuthorizeHostAccess() throws Exception {
        var def = ImageDef.parseYaml("""
                name: tpl-no-root
                hostRepoRoot: /
                repos:
                  - url: /some/repo
                    hostRepoRoot: /
                """);
        assertNull(def.getHostRepoRoot());
        assertNull(def.getRepos().getFirst().getHostRepoRoot());
        var stored = new BuildSource(Map.of(def.getName(), def), Map.of(), Map.of(),
                Map.of(def.getName(), tempDir.resolve("images/template.yaml").toString()));
        var restored = BuildSource.fromJson(stored.toJson()).getDefinitions().get(def.getName());
        assertNull(restored.getHostRepoRoot());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> HostRepoSource.capture(restored.getRepos().getFirst()));
        assertTrue(failure.getMessage().contains("no allowed root"));
    }

    private static void assertRoot(ImageDef def, Path root, Path templateDirectory) {
        assertEquals(root, def.getHostRepoRoot());
        assertEquals(root, def.getRepos().getFirst().getHostRepoRoot());
        assertEquals(templateDirectory, def.getRepos().getFirst().getTemplateDirectory());
    }
}
