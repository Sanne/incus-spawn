package dev.incusspawn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImageDefTest {

    @Test
    void loadAllReturnsBuiltinImages() {
        var defs = ImageDef.loadAll();
        assertTrue(defs.size() >= 3, "Should load at least 3 built-in images");
        assertTrue(defs.containsKey("tpl-minimal"));
        assertTrue(defs.containsKey("tpl-dev"));
        assertTrue(defs.containsKey("tpl-java"));
    }

    @Test
    void minimalIsRoot() {
        var defs = ImageDef.loadAll();
        var minimal = defs.get("tpl-minimal");
        assertTrue(minimal.isRoot());
        assertNotNull(minimal.getImage());
        assertEquals("images:fedora/43", minimal.getImage());
        assertTrue(minimal.getPackages().isEmpty());
        assertTrue(minimal.getTools().isEmpty());
    }

    @Test
    void devExtendsMinimal() {
        var defs = ImageDef.loadAll();
        var dev = defs.get("tpl-dev");
        assertFalse(dev.isRoot());
        assertEquals("tpl-minimal", dev.getParent());
        assertTrue(dev.getTools().contains("podman"));
        assertTrue(dev.getTools().contains("gh"));
        assertTrue(dev.getTools().contains("claude"));
    }

    @Test
    void javaExtendsDev() {
        var defs = ImageDef.loadAll();
        var java = defs.get("tpl-java");
        assertFalse(java.isRoot());
        assertEquals("tpl-dev", java.getParent());
        assertTrue(java.getPackages().contains("java-25-openjdk-devel"));
        assertTrue(java.getTools().contains("maven-3"));
    }

    @Test
    void parentChainIsComplete() {
        var defs = ImageDef.loadAll();
        // java -> dev -> minimal
        var java = defs.get("tpl-java");
        var dev = defs.get(java.getParent());
        assertNotNull(dev);
        var minimal = defs.get(dev.getParent());
        assertNotNull(minimal);
        assertTrue(minimal.isRoot());
    }

    @Test
    void descriptionsAreSet() {
        var defs = ImageDef.loadAll();
        assertFalse(defs.get("tpl-minimal").getDescription().isEmpty());
        assertFalse(defs.get("tpl-dev").getDescription().isEmpty());
        assertFalse(defs.get("tpl-java").getDescription().isEmpty());
    }

    @Test
    void findByNameWorks() {
        var defs = ImageDef.loadAll();
        assertNotNull(ImageDef.findByName("tpl-java", defs));
        assertNull(ImageDef.findByName("nonexistent", defs));
    }

    @Test
    void searchPathLoadsImages(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("quarkus.yaml"), """
                name: tpl-quarkus
                description: Quarkus development
                parent: tpl-java
                tools:
                  - podman
                  - gradle
                  - quarkus-src
                """);

        var defs = ImageDef.loadAll(List.of(tempDir.toString()));

        var quarkus = defs.get("tpl-quarkus");
        assertNotNull(quarkus, "tpl-quarkus should be loaded from search path");
        assertEquals("tpl-java", quarkus.getParent());
        assertTrue(quarkus.getTools().contains("quarkus-src"));
        // builtins should still be present
        assertNotNull(defs.get("tpl-minimal"));
        assertNotNull(defs.get("tpl-java"));
    }

    @Test
    void searchPathOverridesBuiltin(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("java.yaml"), """
                name: tpl-java
                description: Custom Java override
                parent: tpl-dev
                packages:
                  - java-25-openjdk-devel
                """);

        var defs = ImageDef.loadAll(List.of(tempDir.toString()));

        var java = defs.get("tpl-java");
        assertEquals("Custom Java override", java.getDescription());
    }

    @Test
    void multipleSearchPaths(@TempDir Path tempDir) throws Exception {
        var dir1 = tempDir.resolve("repo1");
        var dir2 = tempDir.resolve("repo2");
        Files.createDirectories(dir1.resolve("images"));
        Files.createDirectories(dir2.resolve("images"));

        Files.writeString(dir1.resolve("images/alpha.yaml"), """
                name: tpl-alpha
                description: From repo1
                parent: tpl-dev
                """);
        Files.writeString(dir2.resolve("images/beta.yaml"), """
                name: tpl-beta
                description: From repo2
                parent: tpl-java
                """);

        var defs = ImageDef.loadAll(List.of(dir1.toString(), dir2.toString()));

        assertNotNull(defs.get("tpl-alpha"), "tpl-alpha should be loaded from first search path");
        assertNotNull(defs.get("tpl-beta"), "tpl-beta should be loaded from second search path");
    }

    @Test
    void parseImageWithRepos(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("quarkus.yaml"), """
                name: tpl-quarkus
                parent: tpl-java
                tools:
                  - podman
                repos:
                  - url: https://github.com/quarkusio/quarkus.git
                    path: ~/quarkus
                    branch: main
                    prime: mvn -B dependency:go-offline
                  - url: https://github.com/hibernate/hibernate-reactive.git
                    path: ~/hibernate-reactive
                """);

        var defs = ImageDef.loadAll(List.of(tempDir.toString()));
        var quarkus = defs.get("tpl-quarkus");
        assertNotNull(quarkus);
        assertEquals(2, quarkus.getRepos().size());

        var repo1 = quarkus.getRepos().get(0);
        assertEquals("https://github.com/quarkusio/quarkus.git", repo1.getUrl());
        assertEquals("~/quarkus", repo1.getPath());
        assertEquals("main", repo1.getBranch());
        assertEquals("mvn -B dependency:go-offline", repo1.getPrime());

        var repo2 = quarkus.getRepos().get(1);
        assertEquals("https://github.com/hibernate/hibernate-reactive.git", repo2.getUrl());
        assertEquals("~/hibernate-reactive", repo2.getPath());
        assertNull(repo2.getBranch());
        assertNull(repo2.getPrime());
    }

    @Test
    void imageWithoutReposDefaultsToEmptyList() {
        var defs = ImageDef.loadAll();
        var minimal = defs.get("tpl-minimal");
        assertNotNull(minimal);
        assertTrue(minimal.getRepos().isEmpty());
    }

    @Test
    void imageWithoutSkillsDefaultsToEmpty() {
        var defs = ImageDef.loadAll();
        var minimal = defs.get("tpl-minimal");
        assertNotNull(minimal);
        assertTrue(minimal.getSkills().getList().isEmpty());
        assertNull(minimal.getSkills().getRepo());
    }

    @Test
    void parseImageWithSkillsObjectForm(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("agent.yaml"), """
                name: tpl-agent
                description: Agent with skills
                parent: tpl-dev
                skills:
                  repo: myorg/claude-skills
                  list:
                    - security-review
                    - myorg/other-catalog@special-skill
                    - https://github.com/owner/repo
                """);

        var defs = ImageDef.loadAll(List.of(tempDir.toString()));
        var agent = defs.get("tpl-agent");
        assertNotNull(agent);
        assertEquals("myorg/claude-skills", agent.getSkills().getRepo());
        assertEquals(3, agent.getSkills().getList().size());
        assertEquals("security-review", agent.getSkills().getList().get(0));
        assertEquals("myorg/other-catalog@special-skill", agent.getSkills().getList().get(1));
        assertEquals("https://github.com/owner/repo", agent.getSkills().getList().get(2));
    }

    @Test
    void parseImageWithSkillsListShorthand(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("agent.yaml"), """
                name: tpl-agent
                description: Agent with skills
                parent: tpl-dev
                skills:
                  - xixu-me/skills@xget
                  - myorg/catalog
                """);

        var defs = ImageDef.loadAll(List.of(tempDir.toString()));
        var agent = defs.get("tpl-agent");
        assertNotNull(agent);
        assertNull(agent.getSkills().getRepo());
        assertEquals(2, agent.getSkills().getList().size());
        assertEquals("xixu-me/skills@xget", agent.getSkills().getList().get(0));
        assertEquals("myorg/catalog", agent.getSkills().getList().get(1));
    }

    @Test
    void parseImageWithHostResources(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("custom.yaml"), """
                name: tpl-custom
                parent: tpl-dev
                host-resources:
                  - source: ~/.m2/repository
                    path: /home/agentuser/.m2/repository
                    mode: overlay
                  - source: ~/.gitconfig
                  - source: https://example.com/gitconfig
                    path: /home/agentuser/.gitconfig-remote
                    mode: copy
                """);

        var defs = ImageDef.loadAll(List.of(tempDir.toString()));
        var custom = defs.get("tpl-custom");
        assertNotNull(custom);
        assertEquals(3, custom.getHostResources().size());

        var hr0 = custom.getHostResources().get(0);
        assertEquals("~/.m2/repository", hr0.getSource());
        assertEquals("/home/agentuser/.m2/repository", hr0.getPath());
        assertEquals("overlay", hr0.getMode());

        var hr1 = custom.getHostResources().get(1);
        assertEquals("~/.gitconfig", hr1.getSource());
        assertNull(hr1.getPath());
        assertEquals("readonly", hr1.getMode());

        var hr2 = custom.getHostResources().get(2);
        assertEquals("https://example.com/gitconfig", hr2.getSource());
        assertEquals("/home/agentuser/.gitconfig-remote", hr2.getPath());
        assertEquals("copy", hr2.getMode());
    }

    @Test
    void imageWithoutHostResourcesDefaultsToEmpty() {
        var defs = ImageDef.loadAll();
        var minimal = defs.get("tpl-minimal");
        assertNotNull(minimal);
        assertTrue(minimal.getHostResources().isEmpty());
    }

    @Test
    void emptySearchPathsWorks() {
        var defs = ImageDef.loadAll(List.of());
        // Should still load builtins
        assertTrue(defs.size() >= 3);
        assertNotNull(defs.get("tpl-minimal"));
    }

    @Test
    void nonexistentSearchPathIsIgnored() {
        var defs = ImageDef.loadAll(List.of("/nonexistent/path/that/does/not/exist"));
        // Should still load builtins without error
        assertTrue(defs.size() >= 3);
        assertNotNull(defs.get("tpl-minimal"));
    }

    // --- contentFingerprint tests ---

    @Test
    void fingerprintStableForSameInput() {
        var def = makeDef("images:fedora/43", null, List.of("pkg-a", "pkg-b"), List.of("tool-x"));
        var fp1 = def.contentFingerprint(Map.of("tool-x", "abc"));
        var fp2 = def.contentFingerprint(Map.of("tool-x", "abc"));
        assertEquals(fp1, fp2);
    }

    @Test
    void fingerprintIgnoresPackageOrder() {
        var a = makeDef("images:fedora/43", null, List.of("alpha", "beta"), List.of());
        var b = makeDef("images:fedora/43", null, List.of("beta", "alpha"), List.of());
        assertEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintIgnoresToolOrder() {
        var a = makeDef("images:fedora/43", null, List.of(), List.of("maven", "podman"));
        var b = makeDef("images:fedora/43", null, List.of(), List.of("podman", "maven"));
        var toolFps = Map.of("maven", "fp1", "podman", "fp2");
        assertEquals(a.contentFingerprint(toolFps), b.contentFingerprint(toolFps));
    }

    @Test
    void fingerprintChangesWhenPackageAdded() {
        var a = makeDef("images:fedora/43", null, List.of("alpha"), List.of());
        var b = makeDef("images:fedora/43", null, List.of("alpha", "beta"), List.of());
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenImageChanges() {
        var a = makeDef("images:fedora/43", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenParentChanges() {
        var a = makeDef("images:fedora/43", "tpl-dev", List.of(), List.of());
        var b = makeDef("images:fedora/43", "tpl-minimal", List.of(), List.of());
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintIncludesToolFingerprints() {
        var def = makeDef("images:fedora/43", null, List.of(), List.of("maven"));
        var fp1 = def.contentFingerprint(Map.of("maven", "version-1"));
        var fp2 = def.contentFingerprint(Map.of("maven", "version-2"));
        assertNotEquals(fp1, fp2);
    }

    @Test
    void fingerprintNotEmpty() {
        var def = makeDef("images:fedora/43", null, List.of(), List.of());
        var fp = def.contentFingerprint(Map.of());
        assertNotNull(fp);
        assertFalse(fp.isEmpty());
        assertEquals(64, fp.length(), "SHA-256 hex should be 64 characters");
    }

    private static ImageDef makeDef(String image, String parent, List<String> packages, List<String> tools) {
        var def = new ImageDef();
        def.setImage(image);
        def.setParent(parent);
        def.setPackages(packages);
        def.setTools(tools);
        return def;
    }
}
