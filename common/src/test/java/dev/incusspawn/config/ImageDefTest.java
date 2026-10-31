package dev.incusspawn.config;

import dev.incusspawn.util.Output;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.mapper.JqName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.ParameterizedType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
        assertEquals("fedora-44-base", minimal.getImage());
        assertNotNull(minimal.getImageUrl());
        assertNotNull(minimal.getImageSha256());
        assertTrue(minimal.getTools().isEmpty());
    }

    @Test
    void minimalKeepsTmpOnDisk() {
        // Fedora's tmpfs /tmp would lose an agent's clones on every restart (#1561)
        var minimal = ImageDef.loadAll().get("tpl-minimal");
        assertTrue(minimal.getMaskServices().contains("tmp.mount"),
                "tpl-minimal should mask tmp.mount, but masks: " + minimal.getMaskServices());
    }

    @Test
    void devExtendsMinimal() {
        var defs = ImageDef.loadAll();
        var dev = defs.get("tpl-dev");
        assertFalse(dev.isRoot());
        assertEquals("tpl-minimal", dev.getParent());
        assertTrue(dev.getTools().stream().anyMatch(t -> "podman".equals(t.getName())));
        assertTrue(dev.getTools().stream().anyMatch(t -> "gh".equals(t.getName())));
        var agentTools = java.util.Set.of("claude", "codex", "pi", "bob");
        var found = dev.getTools().stream().map(t -> t.getName()).filter(agentTools::contains).toList();
        assertTrue(found.isEmpty(),
                "tpl-dev should not include any coding agent by default, but found: " + found);
    }

    @Test
    void javaExtendsDev() {
        var defs = ImageDef.loadAll();
        var java = defs.get("tpl-java");
        assertFalse(java.isRoot());
        assertEquals("tpl-dev", java.getParent());
        assertTrue(java.getPackages().contains("java-25-openjdk-devel"));
        assertTrue(java.getTools().stream().anyMatch(t -> "maven-3".equals(t.getName())));
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
        assertTrue(quarkus.getTools().stream().anyMatch(t -> "quarkus-src".equals(t.getName())));
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
        assertEquals(List.of("mvn -B dependency:go-offline"), repo1.getPrime());

        var repo2 = quarkus.getRepos().get(1);
        assertEquals("https://github.com/hibernate/hibernate-reactive.git", repo2.getUrl());
        assertEquals("~/hibernate-reactive", repo2.getPath());
        assertNull(repo2.getBranch());
        assertEquals(List.of(), repo2.getPrime());
    }

    @Test
    void primeTakesAListOfCommandsOrASingleOne() throws Exception {
        var def = ImageDef.parseYaml("""
                name: tpl-primed
                repos:
                  - url: https://github.com/o/listed.git
                    prime:
                      - mise install
                      - ./gradlew dependencies
                  - url: https://github.com/o/single.git
                    prime: mvn -B dependency:go-offline
                """);

        assertEquals(List.of("mise install", "./gradlew dependencies"), def.getRepos().get(0).getPrime());
        assertEquals(List.of("mvn -B dependency:go-offline"), def.getRepos().get(1).getPrime());
    }

    @Test
    void aSingleCommandPrimeKeepsItsFingerprintAsAOneItemList() throws Exception {
        var single = ImageDef.parseYaml("name: t\nrepos:\n  - url: https://e.com/a.git\n    prime: make\n");
        var listed = ImageDef.parseYaml("name: t\nrepos:\n  - url: https://e.com/a.git\n    prime: [make]\n");
        var more = ImageDef.parseYaml("name: t\nrepos:\n  - url: https://e.com/a.git\n    prime: [make, make test]\n");

        assertEquals(single.contentFingerprint(Map.of()), listed.contentFingerprint(Map.of()));
        assertNotEquals(single.contentFingerprint(Map.of()), more.contentFingerprint(Map.of()));
        var split = ImageDef.parseYaml("name: t\nrepos:\n  - url: https://e.com/a.git\n    prime: ['a, b', c]\n");
        var resplit = ImageDef.parseYaml("name: t\nrepos:\n  - url: https://e.com/a.git\n    prime: [a, 'b, c']\n");
        assertNotEquals(split.contentFingerprint(Map.of()), resplit.contentFingerprint(Map.of()));
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

    /** An empty {@code list:} must not swallow the keys that follow the skills block. */
    @Test
    void emptySkillsListKeepsTheRestOfTheDefinition() throws Exception {
        var def = ImageDef.parseYaml("""
                name: tpl-agent
                skills:
                  repo: myorg/claude-skills
                  list:
                packages: [git]
                """);

        assertEquals("myorg/claude-skills", def.getSkills().getRepo());
        assertEquals(List.of(), def.getSkills().getList());
        assertEquals(List.of("git"), def.getPackages());
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
    void sameDirectoryDuplicateNameIsConflict(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        // Two files in ONE directory both claim tpl-quarkus — a copy-paste mistake.
        Files.writeString(imagesDir.resolve("quarkus.yaml"), """
                name: tpl-quarkus
                description: Current
                parent: tpl-java
                """);
        Files.writeString(imagesDir.resolve("quarkus-old.yaml"), """
                name: tpl-quarkus
                description: Stale copy
                parent: tpl-java
                """);

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), msg -> {});

        assertEquals(1, result.conflicts().size(), "same-directory duplicate should be one conflict");
        var conflict = result.conflicts().get(0);
        assertEquals("tpl-quarkus", conflict.name());
        assertEquals(2, conflict.files().size());
        assertTrue(conflict.files().stream().anyMatch(p -> p.getFileName().toString().equals("quarkus.yaml")));
        assertTrue(conflict.files().stream().anyMatch(p -> p.getFileName().toString().equals("quarkus-old.yaml")));
        // A same-directory collision is not a cross-layer override.
        assertTrue(result.overrides().stream().noneMatch(o -> o.name().equals("tpl-quarkus")));
    }

    @Test
    void threeFilesSameNameReportedTogether(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        for (var suffix : List.of("a", "b", "c")) {
            Files.writeString(imagesDir.resolve("dup-" + suffix + ".yaml"), """
                    name: tpl-dup
                    description: copy %s
                    parent: tpl-java
                    """.formatted(suffix));
        }

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), msg -> {});

        assertEquals(1, result.conflicts().size(), "one conflict listing all three files");
        assertEquals(3, result.conflicts().get(0).files().size());
    }

    @Test
    void crossLayerOverrideIsNotConflict(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("java.yaml"), """
                name: tpl-java
                description: Custom Java override
                parent: tpl-dev
                packages:
                  - java-25-openjdk-devel
                """);

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), msg -> {});

        // Override wins, and it is reported as an override, not a conflict.
        assertEquals("Custom Java override", result.defs().get("tpl-java").getDescription());
        assertTrue(result.conflicts().isEmpty(), "cross-layer override must not be a conflict");
        assertTrue(result.overrides().stream().anyMatch(o -> o.name().equals("tpl-java")),
                "cross-layer override should be recorded");
    }

    @Test
    void overriddenSourceNamesTheLayerJustBelowTheKeptDefinition(@TempDir Path tempDir) throws Exception {
        var lower = tempDir.resolve("lower/images");
        var upper = tempDir.resolve("upper/images");
        for (var dir : List.of(lower, upper)) {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("java.yaml"), """
                    name: tpl-java
                    parent: tpl-dev
                    """);
        }

        var result = ImageDef.loadAllWithConflicts(
                List.of(tempDir.resolve("lower").toString(), tempDir.resolve("upper").toString()), msg -> {});

        assertEquals(upper.resolve("java.yaml").toString(), result.getSource("tpl-java"));
        assertEquals(lower.resolve("java.yaml").toString(), result.overriddenSource("tpl-java"));
        assertNull(result.overriddenSource("tpl-dev"));
    }

    @Test
    void aDirectoryScannedTwiceOverridesNothing(@TempDir Path tempDir) throws Exception {
        var images = tempDir.resolve("images");
        Files.createDirectories(images);
        Files.writeString(images.resolve("app.yaml"), """
                name: tpl-app
                parent: tpl-dev
                """);

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString(), tempDir.toString()), msg -> {});

        assertNull(result.overriddenSource("tpl-app"));
        assertTrue(result.overrides().stream().noneMatch(o -> o.name().equals("tpl-app")));
    }

    @Test
    void sameDirectoryDuplicateOfBuiltinIsOnlyConflict(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        // Two files in ONE directory both re-declare a built-in name (tpl-java).
        // The first would shadow the built-in (an override), but because a second
        // file in the same directory collides, this is a mistake, not an override:
        // it must be reported as a conflict only, never as both.
        Files.writeString(imagesDir.resolve("java.yaml"), """
                name: tpl-java
                description: Custom
                parent: tpl-dev
                """);
        Files.writeString(imagesDir.resolve("java-old.yaml"), """
                name: tpl-java
                description: Stale copy
                parent: tpl-dev
                """);

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), msg -> {});

        assertTrue(result.conflicts().stream().anyMatch(c -> c.name().equals("tpl-java")),
                "same-directory duplicate should be a conflict");
        assertTrue(result.overrides().stream().noneMatch(o -> o.name().equals("tpl-java")),
                "a same-directory collision must not also be recorded as an override");
    }

    @Test
    void unparsableFileIsRecordedAsParseFailure(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        // Meant to override the built-in tpl-java, but 'type' is written as a list.
        var broken = imagesDir.resolve("java.yaml");
        Files.writeString(broken, """
                name: tpl-java
                type:
                  - vm
                """);
        var warnings = new java.util.ArrayList<String>();

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), warnings::add);

        assertEquals(List.of(broken.toAbsolutePath().normalize()), result.parseFailures());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'type'")), warnings.toString());
        // The skipped file leaves the built-in in its place, which is why build must refuse.
        assertEquals("built-in", result.getSource("tpl-java"));
    }

    @Test
    void aRepoDepthIsParsed(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("apt.yaml"), """
                name: tpl-apt
                parent: tpl-minimal
                repos:
                  - url: https://github.com/Sanne/isx-apt-releases.git
                    depth: 1
                """);

        var defs = ImageDef.loadAll(List.of(tempDir.toString()));

        assertEquals(1, defs.get("tpl-apt").getRepos().get(0).getDepth());
    }

    @Test
    void anEmptyRepoItemDoesNotBreakLoadingTheOtherTemplates(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("empty.yaml"), """
                name: tpl-empty
                parent: tpl-minimal
                repos:
                  -
                """);

        var defs = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), w -> {}).defs();

        assertNotNull(defs.get("tpl-java"));
        assertNotNull(defs.get("tpl-empty"));
        assertTrue(defs.get("tpl-empty").getRepos().isEmpty());
        defs.get("tpl-empty").contentFingerprint(Map.of()); // would throw on a null item
    }

    @Test
    void emptyListItemsAreDroppedFromEveryList() throws Exception {
        // A bare '-' (or '~') arrives as null; no reader of these lists expects one
        var def = Jjq.imageDef("""
                name: tpl-blanks
                packages: [git, ~]
                remove_packages: [~]
                repos: [~]
                package_repos: [~]
                host-resources: [~]
                mask_services: [~, sshd]
                skills: [~, security-review]
                """);
        assertEquals(List.of("git"), def.getPackages());
        assertEquals(List.of(), def.getRemovePackages());
        assertEquals(List.of(), def.getRepos());
        assertEquals(List.of(), def.getPackageRepos());
        assertEquals(List.of(), def.getHostResources());
        assertEquals(List.of("sshd"), def.getMaskServices());
        assertEquals(List.of("security-review"), def.getSkills().getList());
        def.contentFingerprint(Map.of()); // would throw on a null item
    }

    @Test
    void aRepoDepthThatIsNotAWholeNumberFailsTheFile(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("apt.yaml"), """
                name: tpl-apt
                parent: tpl-minimal
                repos:
                  - url: https://github.com/Sanne/isx-apt-releases.git
                    depth: .inf
                """);
        var warnings = new java.util.ArrayList<String>();

        var defs = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), warnings::add).defs();

        assertNull(defs.get("tpl-apt"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("depth") && w.contains("whole number")), warnings.toString());
    }

    @Test
    void aRepoDepthThatIsNotPositiveIsRefusedAtLoad(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("apt.yaml"), """
                name: tpl-apt
                parent: tpl-minimal
                repos:
                  - url: https://github.com/Sanne/isx-apt-releases.git
                    depth: 0
                """);
        var warnings = new java.util.ArrayList<String>();

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), warnings::add);

        assertNull(result.defs().get("tpl-apt"));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("apt.yaml") && warnings.get(0).contains("'depth' 0"), warnings.get(0));
    }

    @Test
    void aNameThatCannotBeAnInstanceIsRefusedAtLoad(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        // A cloned repository's template, whose name would reach the terminal raw wherever isx shows it.
        Files.writeString(imagesDir.resolve("evil.yaml"), """
                name: "tpl-\\e]0;pwned\\a\\nfake"
                parent: tpl-minimal
                """);
        var warnings = new java.util.ArrayList<String>();

        var defs = ImageDef.loadAll(List.of(tempDir.toString()), warnings::add);

        assertTrue(defs.keySet().stream().noneMatch(name -> name.contains("pwned")), defs.keySet().toString());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("evil.yaml"), warnings.get(0));
        assertTrue(warnings.get(0).contains("tpl- ]0;pwned  fake"), warnings.get(0));
        assertTrue(warnings.get(0).codePoints().noneMatch(Character::isISOControl), warnings.get(0));
    }

    @Test
    void anUnknownHostResourceKeyIsWarnedAboutAtLoad(@TempDir Path tempDir) throws Exception {
        // #1543: 'target:' is not a key, so the file landed at the source's name without a word.
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("claude.yaml"), """
                name: tpl-claude-md
                parent: tpl-minimal
                host-resources:
                  - source: ~/.claude/CLAUDE-isx.md
                    target: ~/.claude/CLAUDE.md
                  - source: ~/.gitconfig
                    Mount_Point: ~/.gitconfig-isx
                  - source: ~/.m2
                    path: ~/.m2
                    dest: ~/.m2-isx
                    confined-to: /
                  - target: ~/.ssh
                """);
        var warnings = new java.util.ArrayList<String>();

        var defs = ImageDef.loadAll(List.of(tempDir.toString()), warnings::add);

        assertTrue(defs.containsKey("tpl-claude-md"), "a warning, not a refusal: " + defs.keySet());
        var usePath = "', ignored; use 'path:' to set where it goes in the instance";
        var takes = "', ignored (host-resources[%d] takes source, path and mode)";
        assertEquals(List.of(
                        "claude.yaml: unknown key 'host-resources[0].target" + usePath,
                        "claude.yaml: unknown key 'host-resources[1].Mount_Point" + usePath,
                        // path: is set, so it is not what the author is missing
                        "claude.yaml: unknown key 'host-resources[2].dest" + takes.formatted(2),
                        // without a source, 'target:' may as well have meant source: as path:
                        "claude.yaml: unknown key 'host-resources[3].target" + takes.formatted(3),
                        "claude.yaml: 'host-resources[2].confined-to' is set by isx, a file's value is ignored (#765)"),
                warnings);
    }

    @Test
    void everyKeyIsxIgnoresIsWarnedAboutAtLoadWithWhereItIs(@TempDir Path tempDir) throws Exception {
        // #1546: a misspelled key anywhere in a template was dropped without a word, so the build
        // went ahead without the host resource and the repo.
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("x.yaml"), """
                name: tpl-x
                parent: tpl-minimal
                host-resource:
                  - source: ~/.gitconfig
                repo:
                  - url: https://github.com/a/b
                repos:
                  - url: https://github.com/a/c
                    branch-name: main
                package-repos:
                  - type: copr
                    name: a/b
                    nmae: a/c
                env:
                  - name: A
                    value: b
                    strategey: append
                skills:
                  repo: a/skills
                  lsit: [x]
                Gui: true
                deprecatd: true
                frobnicate: 1
                """);
        var warnings = new java.util.ArrayList<String>();

        var defs = ImageDef.loadAll(List.of(tempDir.toString()), warnings::add);

        assertTrue(defs.containsKey("tpl-x"), "a warning, not a refusal: " + defs.keySet());
        assertEquals(List.of(
                        "x.yaml: unknown key 'host-resource', ignored; did you mean 'host-resources'?",
                        "x.yaml: unknown key 'repo', ignored; did you mean 'repos'?",
                        "x.yaml: unknown key 'package-repos', ignored; did you mean 'package_repos'?",
                        "x.yaml: unknown key 'Gui', ignored; did you mean 'gui'?",
                        "x.yaml: unknown key 'deprecatd', ignored; did you mean 'deprecated'?",
                        "x.yaml: unknown key 'frobnicate', ignored",
                        "x.yaml: unknown key 'repos[0].branch-name', ignored; did you mean 'branch'?",
                        "x.yaml: unknown key 'env[0].strategey', ignored; did you mean 'strategy'?",
                        "x.yaml: unknown key 'skills.lsit', ignored; did you mean 'list'?"),
                warnings);
    }

    @Test
    void aMisspelledKeyBehindARefusalIsNamed(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("x.yaml"), "nmae: tpl-x\nparent: tpl-minimal\n");
        var warnings = new java.util.ArrayList<String>();

        ImageDef.loadAll(List.of(tempDir.toString()), warnings::add);

        assertEquals(List.of("x.yaml: unknown key 'nmae', ignored; did you mean 'name'?",
                "x.yaml: no 'name:'; it is skipped, and isx build refuses until it is fixed"), warnings);
    }

    @Test
    void anotherSpellingOfConfinedToIsIsxsOwnKeyToo() throws Exception {
        var def = ImageDef.parseYaml("""
                name: tpl-x
                host-resources:
                  - source: ~/.m2
                    confined_to: /
                """);

        assertEquals(List.of("unknown key 'host-resources[0].confined_to', ignored;"
                + " 'confined-to' is set by isx, a file's value is ignored (#765)"), def.unknownKeyWarnings());
    }

    /** Where a definition came from is set by isx; a key of that name is reported, never bound (#1889). */
    @Test
    void aKeyNamedAfterWhereADefinitionCameFromIsReportedNotBound() throws Exception {
        var def = ImageDef.parseYaml("name: tpl-x\nsource: built-in\nprojectRoot: /\n");

        assertNull(def.getProjectRoot());
        assertNotEquals("built-in", def.getSource());
        assertEquals(List.of("unknown key 'source', ignored; 'source' is set by isx, a file's value is ignored (#765)",
                        "unknown key 'projectRoot', ignored; 'projectRoot' is set by isx, a file's value is ignored (#765)"),
                def.unknownKeyWarnings());
    }

    @Test
    void theOldVmKeySaysWhatBuildsAVirtualMachine() throws Exception {
        // No mapping reads 'vm:', so such a template builds a container.
        var def = ImageDef.parseYaml("name: tpl-x\nvm: true\n");

        assertFalse(def.isVm());
        assertEquals(List.of("unknown key 'vm', ignored; a virtual machine is 'type: vm';"
                + " without it this builds a container"), def.unknownKeyWarnings());
        // With type: set, the key is only a leftover, and what gets built is what type: says;
        // a child may inherit type: vm from its parent, so neither is told it builds a container.
        assertEquals(List.of("unknown key 'vm', ignored; a virtual machine is 'type: vm'"),
                ImageDef.parseYaml("name: tpl-x\ntype: vm\nvm: true\n").unknownKeyWarnings());
        assertEquals(List.of("unknown key 'vm', ignored; a virtual machine is 'type: vm'"),
                ImageDef.parseYaml("name: tpl-x\nparent: tpl-vm-base\nvm: true\n").unknownKeyWarnings());
    }

    @Test
    void aKeyWithNothingCloseNamesTheKeysItsEntryTakes() throws Exception {
        var def = ImageDef.parseYaml("""
                name: tpl-x
                package_repos:
                  - type: copr
                    name: a/b
                    channel: stable
                """);

        assertEquals(List.of("unknown key 'package_repos[0].channel', ignored (package_repos[0] takes type and name)"),
                def.unknownKeyWarnings());
    }

    @Test
    void theBuiltInTemplatesHoldNoKeyIsxIgnores() {
        for (var file : ImageDef.BUILTIN_FILES) {
            var def = ImageDef.loadBuiltinByName(builtinName(file));
            assertEquals(List.of(), def.unknownKeyWarnings(), file);
        }
    }

    private static String builtinName(String file) {
        return "tpl-" + file.substring(0, file.length() - ".yaml".length());
    }

    @Test
    void aDefinitionWithoutANameIsRefusedAtLoad(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        // Meant to override the built-in tpl-java, but the name: line is missing.
        var refused = imagesDir.resolve("java.yaml");
        Files.writeString(refused, """
                parent: tpl-minimal
                """);
        var warnings = new java.util.ArrayList<String>();

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), warnings::add);

        assertEquals("built-in", result.getSource("tpl-java"));
        // So isx build refuses, as for an unparsable file, instead of building the built-in in its place.
        assertEquals(List.of(refused.toAbsolutePath().normalize()), result.parseFailures());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("java.yaml") && warnings.get(0).contains("name"), warnings.get(0));
    }

    @Test
    void aNullDocumentIsRefusedLikeADefinitionWithoutAName(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        var refused = imagesDir.resolve("java.yaml");
        Files.writeString(refused, "~\n");
        var warnings = new java.util.ArrayList<String>();

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), warnings::add);

        assertEquals(List.of(refused.toAbsolutePath().normalize()), result.parseFailures());
        assertEquals(1, warnings.size(), warnings.toString());
    }

    @Test
    void aParentThatCannotBeAnInstanceIsRefusedAtLoad(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        // Meant to override the built-in tpl-java.
        var refused = imagesDir.resolve("java.yaml");
        Files.writeString(refused, """
                name: tpl-java
                parent: "tpl-\\u009b2Jminimal"
                """);
        var warnings = new java.util.ArrayList<String>();

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), warnings::add);

        assertEquals("built-in", result.getSource("tpl-java"));
        // So isx build refuses, as for an unparsable file, instead of building the built-in in its place.
        assertEquals(List.of(refused.toAbsolutePath().normalize()), result.parseFailures());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("parent 'tpl- 2Jminimal'"), warnings.get(0));
        assertTrue(warnings.get(0).codePoints().noneMatch(Character::isISOControl), warnings.get(0));
    }

    @Test
    void aParentCycleIsRefusedAtLoad(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        var cx = imagesDir.resolve("cx.yaml");
        var cy = imagesDir.resolve("cy.yaml");
        Files.writeString(cx, """
                name: tpl-cx
                type: container
                parent: tpl-cy
                """);
        Files.writeString(cy, """
                name: tpl-cy
                type: container
                parent: tpl-cx
                """);
        Files.writeString(imagesDir.resolve("child.yaml"), """
                name: tpl-child
                parent: tpl-cx
                """);
        var warnings = new java.util.ArrayList<String>();

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), warnings::add);

        // Walking the chain of any of them would never end (#1443)
        assertFalse(result.defs().containsKey("tpl-cx"));
        assertFalse(result.defs().containsKey("tpl-cy"));
        // So isx build refuses, as for any definition refused at load.
        assertEquals(java.util.Set.of(cx.toAbsolutePath().normalize(), cy.toAbsolutePath().normalize()),
                java.util.Set.copyOf(result.parseFailures()));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("tpl-cx → tpl-cy → tpl-cx"), warnings.get(0));
        assertTrue(warnings.get(0).contains("cx.yaml") && warnings.get(0).contains("cy.yaml"), warnings.get(0));
        // A template below the cycle still loads, its chain ending at the missing parent
        var child = result.defs().get("tpl-child");
        assertEquals(List.of(), ImageDef.ancestors(child, result.defs()));
    }

    @Test
    void aParentCycleClosedByALaterLayerIsRefused(@TempDir Path tempDir) throws Exception {
        var lower = tempDir.resolve("lower");
        var upper = tempDir.resolve("upper");
        Files.createDirectories(lower.resolve("images"));
        Files.createDirectories(upper.resolve("images"));
        var cx = lower.resolve("images/cx.yaml");
        Files.writeString(cx, """
                name: tpl-cx
                parent: tpl-cy
                """);
        Files.writeString(lower.resolve("images/cy.yaml"), """
                name: tpl-cy
                parent: tpl-minimal
                """);
        // Each directory alone has no cycle; the override closes one
        var cy = upper.resolve("images/cy.yaml");
        Files.writeString(cy, """
                name: tpl-cy
                parent: tpl-cx
                """);
        var warnings = new java.util.ArrayList<String>();

        var result = ImageDef.loadAllWithConflicts(List.of(lower.toString(), upper.toString()), warnings::add);

        assertFalse(result.defs().containsKey("tpl-cx"));
        assertFalse(result.defs().containsKey("tpl-cy"));
        assertEquals(java.util.Set.of(cx.toAbsolutePath().normalize(), cy.toAbsolutePath().normalize()),
                java.util.Set.copyOf(result.parseFailures()));
        assertEquals(List.of(), result.overrides());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains(cy.toAbsolutePath().normalize().toString()), warnings.get(0));
    }

    @Test
    void aTemplateThatIsItsOwnParentIsRefusedAtLoad(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        // Without a type: inheriting one walks the chain while loading
        var self = imagesDir.resolve("self.yaml");
        Files.writeString(self, """
                name: tpl-self
                parent: tpl-self
                """);
        var warnings = new java.util.ArrayList<String>();

        var result = ImageDef.loadAllWithConflicts(List.of(tempDir.toString()), warnings::add);

        assertFalse(result.defs().containsKey("tpl-self"));
        assertEquals(List.of(self.toAbsolutePath().normalize()), result.parseFailures());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("tpl-self → tpl-self"), warnings.get(0));
    }

    @Test
    void aCycleClosedAfterLoadStopsTheChainWalk() {
        // A build-source snapshot merged in after load (isx build, the TUI) can bring back tpl-a,
        // whose definition file is gone, while the loaded tpl-b now names it as parent (#1443)
        var a = new ImageDef();
        a.setName("tpl-a");
        a.setParent("tpl-b");
        var b = new ImageDef();
        b.setName("tpl-b");
        b.setParent("tpl-a");
        var defs = Map.of("tpl-a", a, "tpl-b", b);
        var warnings = new java.util.ArrayList<String>();

        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(warnings::add))) {
            assertEquals(List.of(b), ImageDef.ancestors(a, defs));
            assertEquals(List.of(b, a), ImageDef.chain(a, defs));
            assertEquals(List.of(a, b), ImageDef.chain(b, defs));
        }

        assertEquals(1, warnings.size(), warnings.toString());
        // Once per cycle, however the walks entered it
        assertTrue(warnings.get(0).contains("tpl-a → tpl-b → tpl-a"), warnings.get(0));
    }

    @Test
    void namesIncusAcceptsStillLoad(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("ok.yaml"), """
                name: Tpl-9-ok
                parent: tpl-minimal
                """);
        var warnings = new java.util.ArrayList<String>();

        var defs = ImageDef.loadAll(List.of(tempDir.toString()), warnings::add);

        assertNotNull(defs.get("Tpl-9-ok"));
        assertEquals(List.of(), warnings);
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

    @Test
    void searchPathExpandsTilde() {
        var originalHome = System.getProperty("user.home");
        try {
            var testResources = Path.of("src/test/resources").toAbsolutePath();
            System.setProperty("user.home", testResources.toString());

            var defs = ImageDef.loadAll(List.of("~/searchpaths-test"));

            assertNotNull(defs.get("tpl-tilde-test"), "Should load image from ~/searchpaths-test");
            assertEquals("Test tilde expansion", defs.get("tpl-tilde-test").getDescription());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void parseImageWithImageUrl() throws Exception {
        var yaml = """
                name: tpl-test
                image: fedora-44-base
                image_url: https://example.com/fedora-44-{arch}.tar.xz
                image_sha256:
                  x86_64: abc123
                  aarch64: def456
                """;
        var def = ImageDef.parseYaml(yaml);
        assertEquals("fedora-44-base", def.getImage());
        assertEquals("https://example.com/fedora-44-{arch}.tar.xz", def.getImageUrl());
        assertEquals("abc123", def.getImageSha256().get("x86_64"));
        assertEquals("def456", def.getImageSha256().get("aarch64"));
    }

    @Test
    void imageWithoutImageUrlDefaultsToNull() {
        var def = new ImageDef();
        assertNull(def.getImageUrl());
        assertNull(def.getImageSha256());
    }

    @Test
    void fingerprintChangesWhenImageUrlChanges() {
        var a = makeDef("fedora-44-base", null, List.of(), List.of());
        var b = makeDef("fedora-44-base", null, List.of(), List.of());
        a.setImageUrl("https://example.com/v1/fedora-44-{arch}.tar.xz");
        b.setImageUrl("https://example.com/v2/fedora-44-{arch}.tar.xz");
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenImageSha256Changes() {
        var a = makeDef("fedora-44-base", null, List.of(), List.of());
        var b = makeDef("fedora-44-base", null, List.of(), List.of());
        a.setImageUrl("https://example.com/fedora-44-{arch}.tar.xz");
        b.setImageUrl("https://example.com/fedora-44-{arch}.tar.xz");
        a.setImageSha256(Map.of("x86_64", "aaa"));
        b.setImageSha256(Map.of("x86_64", "bbb"));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenARepoDepthChanges() {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/Sanne/isx-apt-releases.git");
        var def = makeDef("images:fedora/44", null, List.of(), List.of());
        def.setRepos(List.of(repo));
        var full = def.contentFingerprint(Map.of());
        repo.setDepth(1);
        assertNotEquals(full, def.contentFingerprint(Map.of()));
    }

    // --- contentFingerprint tests ---

    @Test
    void fingerprintStableForSameInput() {
        var def = makeDef("images:fedora/44", null, List.of("pkg-a", "pkg-b"), List.of("tool-x"));
        var fp1 = def.contentFingerprint(Map.of("tool-x", "abc"));
        var fp2 = def.contentFingerprint(Map.of("tool-x", "abc"));
        assertEquals(fp1, fp2);
    }

    @Test
    void fingerprintIgnoresPackageOrder() {
        var a = makeDef("images:fedora/44", null, List.of("alpha", "beta"), List.of());
        var b = makeDef("images:fedora/44", null, List.of("beta", "alpha"), List.of());
        assertEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintIgnoresToolOrder() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of("maven", "podman"));
        var b = makeDef("images:fedora/44", null, List.of(), List.of("podman", "maven"));
        var toolFps = Map.of("maven", "fp1", "podman", "fp2");
        assertEquals(a.contentFingerprint(toolFps), b.contentFingerprint(toolFps));
    }

    @Test
    void fingerprintChangesWhenPackageAdded() {
        var a = makeDef("images:fedora/44", null, List.of("alpha"), List.of());
        var b = makeDef("images:fedora/44", null, List.of("alpha", "beta"), List.of());
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenAMaskedServiceChanges() {
        // A mask is baked into the image: changing one must mark the template stale (#1561)
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        b.setMaskServices(List.of("tmp.mount"));
        var c = makeDef("images:fedora/44", null, List.of(), List.of());
        c.setMaskServices(List.of("tmp.mount", "sshd.service"));
        var d = makeDef("images:fedora/44", null, List.of(), List.of());
        d.setMaskServices(List.of("sshd.service", "tmp.mount"));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
        assertNotEquals(b.contentFingerprint(Map.of()), c.contentFingerprint(Map.of()));
        assertEquals(c.contentFingerprint(Map.of()), d.contentFingerprint(Map.of()), "order does not matter");
        d.setMaskServices(List.of("sshd.service", "tmp.mount", "tmp.mount"));
        assertEquals(c.contentFingerprint(Map.of()), d.contentFingerprint(Map.of()), "nor does a repeat");
    }

    @Test
    void fingerprintChangesWhenAPackageToRemoveChanges() {
        // Removed before installing, so the image differs: changing the list must mark it stale
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        b.setRemovePackages(List.of("nano"));
        var c = makeDef("images:fedora/44", null, List.of(), List.of());
        c.setRemovePackages(List.of("vim-minimal", "nano"));
        var d = makeDef("images:fedora/44", null, List.of(), List.of());
        d.setRemovePackages(List.of("nano", "vim-minimal", "nano"));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
        assertNotEquals(b.contentFingerprint(Map.of()), c.contentFingerprint(Map.of()));
        assertEquals(c.contentFingerprint(Map.of()), d.contentFingerprint(Map.of()), "order and repeats do not matter");
    }

    @Test
    void aTemplateMaskingAndRemovingNothingKeepsTheFingerprintItWasBuiltWith() {
        // Pinned to the value before mask_services and remove_packages were fingerprinted: an empty
        // list must add nothing, or every template ever built would rebuild
        var def = makeDef("images:fedora/44", null, List.of("alpha"), List.of());
        assertEquals("afe047add72648ed36bd18bd3aa817fa10828a41137c46724c865d3aac74c2a5", def.contentFingerprint(Map.of()));
    }

    /**
     * Keys of a definition that are deliberately not in {@link ImageDef#contentFingerprint}, each
     * with why: changing one must not rebuild anything. Every other key is in {@link #FINGERPRINTED}.
     */
    private static final Map<String, String> NOT_FINGERPRINTED = Map.of(
            "name", "the template's identity, not its content: a rename keeps the image",
            "description", "shown in listings, never reaches the image",
            "pinned", "protects the instance from removal, never reaches the image",
            "default-action", "what the TUI does on Enter, never reaches the image",
            "deprecated", "a warning at build time: marking a template deprecated must not rebuild it",
            "coordinator", "what a branch may do over MCP, never baked into the image: editing it must not rebuild",
            "coordinator.isx-control-capability", "a branch default, stamped per branch and never baked: editing it must not rebuild",
            "coordinator.control-over", "read live by isx mcp and a branch default, never baked: editing it must not rebuild");

    /** A minimal definition holding one entry of each nested part, so each nested key can vary. */
    private static final Map<String, String> GUARD_BASE = Map.of(
            "name", "name: tpl-guard\n",
            "repos", "repos:\n  - url: https://example.com/a.git\n",
            "host-resources", "host-resources:\n  - source: /tmp/a\n    path: /a\n",
            "env", "env:\n  - name: A\n    value: one\n    strategy: append\n",
            "package_repos", "package_repos:\n  - type: copr\n    name: x/y\n",
            "tools", "tools: [podman]\n",
            "accounts", "accounts:\n  github: acme-bot\n",
            "image_sha256", "image_sha256:\n  x86_64: aaa\n",
            "vm_image_sha256", "vm_image_sha256:\n  x86_64: aaa\n");

    /**
     * For each fingerprinted key ({@code part.key} for a nested one), its top-level section set to
     * something other than in {@link #GUARD_BASE}, replacing that section there or adding it.
     */
    private static final Map<String, String> FINGERPRINTED = Map.ofEntries(
            Map.entry("image", "image: images:fedora/43\n"),
            Map.entry("image_url", "image_url: https://example.com/f.tar.xz\n"),
            Map.entry("image_tag", "image_tag: t1\n"),
            Map.entry("image_sha256", "image_sha256:\n  x86_64: bbb\n"),
            Map.entry("vm_image_url", "vm_image_url: https://example.com/v.tar.xz\n"),
            Map.entry("vm_image_sha256", "vm_image_sha256:\n  x86_64: bbb\n"),
            Map.entry("parent", "parent: tpl-minimal\n"),
            Map.entry("packages", "packages: [nano]\n"),
            Map.entry("remove_packages", "remove_packages: [nano]\n"),
            Map.entry("tools", "tools:\n  - podman:\n      compose: \"true\"\n"),
            Map.entry("skills", "skills: [security-review]\n"),
            Map.entry("skills.repo", "skills:\n  repo: myorg/catalog\n"),
            Map.entry("skills.list", "skills:\n  list: [security-review]\n"),
            Map.entry("mask_services", "mask_services: [tmp.mount]\n"),
            Map.entry("gui", "gui: true\n"),
            Map.entry("type", "type: vm\n"),
            Map.entry("workdir", "workdir: ~/a\n"),
            Map.entry("shell-command", "shell-command: zsh\n"),
            Map.entry("agent_note", "agent_note: careful\n"),
            Map.entry("accounts", "accounts:\n  github: other-bot\n"),
            Map.entry("repos", "repos: []\n"),
            Map.entry("repos.url", "repos:\n  - url: https://example.com/b.git\n"),
            Map.entry("repos.host-source", "repos:\n  - url: https://example.com/a.git\n    host-source: ~/a\n"),
            Map.entry("repos.path", "repos:\n  - url: https://example.com/a.git\n    path: ~/b\n"),
            Map.entry("repos.branch", "repos:\n  - url: https://example.com/a.git\n    branch: dev\n"),
            Map.entry("repos.depth", "repos:\n  - url: https://example.com/a.git\n    depth: 1\n"),
            Map.entry("repos.prime", "repos:\n  - url: https://example.com/a.git\n    prime: make\n"),
            Map.entry("host-resources", "host-resources: []\n"),
            Map.entry("host-resources.source", "host-resources:\n  - source: /tmp/b\n    path: /a\n"),
            Map.entry("host-resources.path", "host-resources:\n  - source: /tmp/a\n    path: /b\n"),
            Map.entry("host-resources.mode",
                    "host-resources:\n  - source: /tmp/a\n    path: /a\n    mode: overlay\n"),
            Map.entry("env", "env: []\n"),
            Map.entry("env.name", "env:\n  - name: B\n    value: one\n    strategy: append\n"),
            Map.entry("env.value", "env:\n  - name: A\n    value: two\n    strategy: append\n"),
            Map.entry("env.strategy", "env:\n  - name: A\n    value: one\n    strategy: prepend\n"),
            Map.entry("env.separator",
                    "env:\n  - name: A\n    value: one\n    strategy: append\n    separator: ':'\n"),
            Map.entry("package_repos", "package_repos: []\n"),
            Map.entry("package_repos.type", "package_repos:\n  - type: dnf\n    name: x/y\n"),
            Map.entry("package_repos.name", "package_repos:\n  - type: copr\n    name: x/z\n"));

    private static String guardYaml(Map<String, String> sections) {
        return String.join("", new TreeMap<>(sections).values());
    }

    @Test
    void everyDefinitionKeyIsFingerprintedOrDeliberatelyLeftOut() throws Exception {
        // A key affecting the image but left out of the fingerprint never marks a template stale (#1571):
        // a new key goes in FINGERPRINTED, with a variation, or in NOT_FINGERPRINTED, with a reason
        var parts = Map.of(
                "skills", new ImageDef.SkillsDef(null, List.of()),
                "repos", new ImageDef.RepoEntry(),
                "host-resources", new ImageDef.HostResource(),
                "env", new EnvEntry(),
                "package_repos", new ImageDef.PackageRepo(),
                "coordinator", new ImageDef.Coordinator());
        assertEquals(nestedParts(), parts.keySet(), "the parts of a definition whose keys are checked too");
        var keys = new TreeSet<String>(new ImageDef().knownKeys());
        parts.forEach((part, entry) -> entry.knownKeys().forEach(k -> keys.add(part + "." + k)));
        var unlisted = new TreeSet<>(keys);
        unlisted.removeAll(FINGERPRINTED.keySet());
        unlisted.removeAll(NOT_FINGERPRINTED.keySet());
        assertEquals(Set.of(), unlisted, "fingerprint these, or say why not");
        var stale = new TreeSet<>(FINGERPRINTED.keySet());
        stale.addAll(NOT_FINGERPRINTED.keySet());
        stale.removeAll(keys);
        assertEquals(Set.of(), stale, "no longer keys of a definition");
        assertTrue(NOT_FINGERPRINTED.keySet().stream().noneMatch(FINGERPRINTED::containsKey), "listed as both");

        var base = ImageDef.parseYaml(guardYaml(GUARD_BASE)).contentFingerprint(Map.of());
        // Pinned: a new key adds to the fingerprint only when set, or every template would rebuild
        assertEquals("5692efd0f8eff120fba6885f5655aa9d6d066b3114aeecdc7f1fec2dd57c094f", base);
        for (var e : new TreeMap<>(FINGERPRINTED).entrySet()) {
            var sections = new HashMap<>(GUARD_BASE);
            sections.put(e.getKey().split("\\.")[0], e.getValue());
            var variant = ImageDef.parseYaml(guardYaml(sections));
            assertEquals(List.of(), variant.unknownKeyWarnings(), e.getKey() + " must be set by a key isx reads");
            assertNotEquals(base, variant.contentFingerprint(Map.of()), "changing " + e.getKey() + " must change the fingerprint");
        }
    }

    /** The keys of a definition holding a part with keys of its own, itself or as a list's element. */
    private static Set<String> nestedParts() {
        var parts = new HashSet<String>();
        for (var field : ImageDef.class.getDeclaredFields()) {
            var type = field.getGenericType() instanceof ParameterizedType p
                    ? p.getActualTypeArguments()[p.getActualTypeArguments().length - 1] : field.getType();
            if (type instanceof Class<?> c && MappedDefinition.class.isAssignableFrom(c)) {
                var name = field.getAnnotation(JqName.class);
                parts.add(name != null ? name.value() : field.getName());
            }
        }
        return parts;
    }

    @Test
    void fingerprintChangesWhenImageChanges() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/43", null, List.of(), List.of());
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenParentChanges() {
        var a = makeDef("images:fedora/44", "tpl-dev", List.of(), List.of());
        var b = makeDef("images:fedora/44", "tpl-minimal", List.of(), List.of());
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintIncludesToolFingerprints() {
        var def = makeDef("images:fedora/44", null, List.of(), List.of("maven"));
        var fp1 = def.contentFingerprint(Map.of("maven", "version-1"));
        var fp2 = def.contentFingerprint(Map.of("maven", "version-2"));
        assertNotEquals(fp1, fp2);
    }

    @Test
    void fingerprintIgnoresUnrelatedToolsInMap() {
        var def = makeDef("images:fedora/44", null, List.of(), List.of("maven"));
        var fp1 = def.contentFingerprint(Map.of("maven", "v1"));
        var fp2 = def.contentFingerprint(Map.of("maven", "v1", "unrelated-tool", "xyz"));
        assertEquals(fp1, fp2, "Tools not in the image's explicit list should not affect fingerprint");
    }

    @Test
    void fingerprintNotEmpty() {
        var def = makeDef("images:fedora/44", null, List.of(), List.of());
        var fp = def.contentFingerprint(Map.of());
        assertNotNull(fp);
        assertFalse(fp.isEmpty());
        assertEquals(64, fp.length(), "SHA-256 hex should be 64 characters");
    }

    @Test
    void fingerprintIgnoresNameAndDescription() {
        var a = makeDef("images:fedora/44", null, List.of("pkg"), List.of());
        a.setName("tpl-alpha");
        a.setDescription("First template");
        var b = makeDef("images:fedora/44", null, List.of("pkg"), List.of());
        b.setName("tpl-beta");
        b.setDescription("Second template");
        assertEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenRepoAdded() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/example/repo.git");
        repo.setPath("~/repo");
        b.setRepos(List.of(repo));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenRepoUrlChanges() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        var r1 = new ImageDef.RepoEntry();
        r1.setUrl("https://github.com/example/repo-a.git");
        r1.setPath("~/repo");
        a.setRepos(List.of(r1));
        var r2 = new ImageDef.RepoEntry();
        r2.setUrl("https://github.com/example/repo-b.git");
        r2.setPath("~/repo");
        b.setRepos(List.of(r2));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintIgnoresRepoOrder() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        var r1 = new ImageDef.RepoEntry();
        r1.setUrl("https://github.com/alpha.git");
        r1.setPath("~/alpha");
        var r2 = new ImageDef.RepoEntry();
        r2.setUrl("https://github.com/beta.git");
        r2.setPath("~/beta");
        a.setRepos(List.of(r1, r2));
        b.setRepos(List.of(r2, r1));
        assertEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenSkillAdded() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        b.setSkills(new ImageDef.SkillsDef(null, List.of("security-review")));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintIgnoresSkillOrder() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setSkills(new ImageDef.SkillsDef(null, List.of("alpha", "beta")));
        b.setSkills(new ImageDef.SkillsDef(null, List.of("beta", "alpha")));
        assertEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenAgentNoteChanges() {
        // Unlike description, agent_note is fingerprinted: it is baked into the
        // generated /etc/claude-code/CLAUDE.md, so a stale warning must force a rebuild.
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setAgentNote("boot JDK must be 26");
        b.setAgentNote("boot JDK must be 27");
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenAgentNoteAdded() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        b.setAgentNote("a note");
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintIgnoresDescriptionChanges() {
        // Documents why the generated file renders no descriptions: editing one does
        // not rebuild the image, so a rendered description could go stale.
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setDescription("one thing");
        b.setDescription("a completely different thing");
        assertEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenSkillsRepoChanges() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setSkills(new ImageDef.SkillsDef("org/catalog-a", List.of()));
        b.setSkills(new ImageDef.SkillsDef("org/catalog-b", List.of()));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenHostResourceAdded() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        b.setHostResources(List.of(new ImageDef.HostResource("~/.m2", "/home/agentuser/.m2", "readonly")));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenHostResourceFileContentChanges(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve(".gitconfig");
        Files.writeString(file, "[user]\n  name = Alice\n");

        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setHostResources(List.of(new ImageDef.HostResource(file.toString(), "~/.gitconfig", "copy")));
        var fp1 = a.contentFingerprint(Map.of());

        Files.writeString(file, "[user]\n  name = Bob\n");
        var fp2 = a.contentFingerprint(Map.of());

        assertNotEquals(fp1, fp2, "Fingerprint should change when host-resource file content changes");
    }

    @Test
    void fingerprintSkipsReadonlyFileContent(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve(".zshrc");
        Files.writeString(file, "export FOO=bar\n");

        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setHostResources(List.of(new ImageDef.HostResource(file.toString(), "~/.zshrc", "readonly")));
        var fp1 = a.contentFingerprint(Map.of());

        Files.writeString(file, "export FOO=baz\n");
        var fp2 = a.contentFingerprint(Map.of());

        assertEquals(fp1, fp2, "Fingerprint should not include content for readonly (live-mounted) host-resources");
    }

    @Test
    void fingerprintSkipsOverlayFileContent(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("storage.conf");
        Files.writeString(file, "driver = overlay\n");

        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setHostResources(List.of(new ImageDef.HostResource(file.toString(), "/etc/containers/storage.conf", "overlay")));
        var fp1 = a.contentFingerprint(Map.of());

        Files.writeString(file, "driver = vfs\n");
        var fp2 = a.contentFingerprint(Map.of());

        assertEquals(fp1, fp2, "Fingerprint should not include content for overlay mode host-resources");
    }

    @Test
    void fingerprintSkipsDirectoryContent(@TempDir Path tempDir) throws Exception {
        var dir = tempDir.resolve("m2-repo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("marker.txt"), "v1");

        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setHostResources(List.of(new ImageDef.HostResource(dir.toString(), "/home/agentuser/.m2", "readonly")));
        var fp1 = a.contentFingerprint(Map.of());

        Files.writeString(dir.resolve("marker.txt"), "v2");
        var fp2 = a.contentFingerprint(Map.of());

        assertEquals(fp1, fp2, "Fingerprint should not change when directory content changes");
    }

    @Test
    void fingerprintSkipsUrlSourceContent() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setHostResources(List.of(new ImageDef.HostResource(
                "https://example.com/gitconfig", "/home/agentuser/.gitconfig", "copy")));
        var fp = a.contentFingerprint(Map.of());
        assertNotNull(fp);
        assertFalse(fp.isEmpty());
    }

    @Test
    void fingerprintSkipsMissingFile() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setHostResources(List.of(new ImageDef.HostResource(
                "/nonexistent/path/.gitconfig", "~/.gitconfig", "copy")));
        var fp = a.contentFingerprint(Map.of());
        assertNotNull(fp);
        assertFalse(fp.isEmpty());
    }

    @Test
    void fingerprintChangesWhenHostResourceModeChanges() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setHostResources(List.of(new ImageDef.HostResource("~/.m2", "/home/agentuser/.m2", "readonly")));
        b.setHostResources(List.of(new ImageDef.HostResource("~/.m2", "/home/agentuser/.m2", "overlay")));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenToolAdded() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of("maven"));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void parseImageWithPackageRepos(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("mise.yaml"), """
                name: tpl-mise
                parent: tpl-dev
                package_repos:
                  - type: copr
                    name: jdxcode/mise
                packages:
                  - mise
                """);

        var defs = ImageDef.loadAll(List.of(tempDir.toString()));
        var mise = defs.get("tpl-mise");
        assertNotNull(mise);
        assertEquals(1, mise.getPackageRepos().size());
        assertEquals("copr", mise.getPackageRepos().get(0).getType());
        assertEquals("jdxcode/mise", mise.getPackageRepos().get(0).getName());
        assertEquals(1, mise.getPackages().size());
        assertEquals("mise", mise.getPackages().get(0));
    }

    @Test
    void imageWithoutPackageReposDefaultsToEmpty() {
        var defs = ImageDef.loadAll();
        var minimal = defs.get("tpl-minimal");
        assertNotNull(minimal);
        assertTrue(minimal.getPackageRepos().isEmpty());
    }

    @Test
    void fingerprintChangesWhenPackageRepoAdded() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        b.setPackageRepos(List.of(new ImageDef.PackageRepo("copr", "jdxcode/mise")));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenPackageRepoNameChanges() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setPackageRepos(List.of(new ImageDef.PackageRepo("copr", "user/repo-a")));
        b.setPackageRepos(List.of(new ImageDef.PackageRepo("copr", "user/repo-b")));
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintIgnoresPackageRepoOrder() {
        var a = makeDef("images:fedora/44", null, List.of(), List.of());
        var b = makeDef("images:fedora/44", null, List.of(), List.of());
        a.setPackageRepos(List.of(
                new ImageDef.PackageRepo("copr", "alpha/repo"),
                new ImageDef.PackageRepo("copr", "beta/repo")));
        b.setPackageRepos(List.of(
                new ImageDef.PackageRepo("copr", "beta/repo"),
                new ImageDef.PackageRepo("copr", "alpha/repo")));
        assertEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void parseImageWithWorkdirAndShellCommand(@TempDir Path tempDir) throws Exception {
        var imagesDir = tempDir.resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve("custom.yaml"), """
                name: tpl-custom
                parent: tpl-dev
                workdir: ~/my-project
                shell-command: claude
                """);

        var defs = ImageDef.loadAll(List.of(tempDir.toString()));
        var custom = defs.get("tpl-custom");
        assertNotNull(custom);
        assertEquals("~/my-project", custom.getWorkdir());
        assertEquals("claude", custom.getShellCommand());
    }

    @Test
    void imageWithoutWorkdirDefaultsToNull() {
        var defs = ImageDef.loadAll();
        var minimal = defs.get("tpl-minimal");
        assertNotNull(minimal);
        assertNull(minimal.getWorkdir());
        assertNull(minimal.getShellCommand());
    }

    @Test
    void fingerprintChangesWhenWorkdirSet() {
        var a = makeDef("images:fedora/43", null, List.of(), List.of());
        var b = makeDef("images:fedora/43", null, List.of(), List.of());
        b.setWorkdir("~/project");
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    @Test
    void fingerprintChangesWhenShellCommandSet() {
        var a = makeDef("images:fedora/43", null, List.of(), List.of());
        var b = makeDef("images:fedora/43", null, List.of(), List.of());
        b.setShellCommand("claude");
        assertNotEquals(a.contentFingerprint(Map.of()), b.contentFingerprint(Map.of()));
    }

    private static ImageDef makeDef(String image, String parent, List<String> packages, List<String> tools) {
        var def = new ImageDef();
        def.setImage(image);
        def.setParent(parent);
        def.setPackages(packages);
        def.setTools(tools.stream().map(ToolDef.ToolRef::new).toList());
        return def;
    }

    private static ImageDef.RepoEntry repoEntry(String url, String path, String branch, Integer depth, String prime) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url);
        repo.setPath(path);
        repo.setBranch(branch);
        repo.setDepth(depth);
        repo.setPrime(prime == null ? List.of() : List.of(prime));
        return repo;
    }

    private static Map<String, ImageDef> parentAndChild(List<ImageDef.RepoEntry> parentRepos,
                                                        List<ImageDef.RepoEntry> childRepos) {
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setRepos(parentRepos);
        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setRepos(childRepos);
        return Map.of("tpl-parent", parent, "tpl-child", child);
    }

    @Test
    void identicalRedeclarationsResolveToTheFirstDeclaration() {
        var defs = parentAndChild(
                List.of(repoEntry("https://github.com/o/quarkus.git", null, "main", null, "mvn -q")),
                List.of(repoEntry("git@github.com:o/quarkus", "/home/agentuser/quarkus/", "main", null, "mvn -q"),
                        repoEntry("https://github.com/o/quarkus.git", "~/quarkus-2", null, null, null),
                        repoEntry("https://github.com/o/quarkus.git", "~/quarkus-2", null, null, null)));
        var child = defs.get("tpl-child");

        assertNull(ImageDef.repoConflict(child, defs));
        var repos = ImageDef.chainRepos(child, defs);
        assertEquals(List.of("tpl-parent", "tpl-child"), repos.stream().map(ImageDef.ChainRepo::template).toList());
        assertEquals(List.of("~/quarkus", "~/quarkus-2"), repos.stream().map(r -> r.repo().getPath()).toList());
    }

    @Test
    void conflictingRedeclarationNamesBothTemplatesAndTheField() {
        var defs = parentAndChild(
                List.of(repoEntry("https://github.com/o/quarkus.git", "~/quarkus", "main", null, null)),
                List.of(repoEntry("https://github.com/o/quarkus.git", "~/quarkus", "dev", 1, null)));

        assertEquals("repo ~/quarkus: template 'tpl-child' redeclares it from 'tpl-parent' with a different"
                        + " branch (main vs dev), depth (none vs 1); remove one declaration or make them identical"
                        + " (an inherited repo cannot be overridden)",
                ImageDef.repoConflict(defs.get("tpl-child"), defs));
        assertNull(ImageDef.repoConflict(defs.get("tpl-parent"), defs));
    }

    @Test
    void conflictingUrlOrPrimeAtOneDestinationWithinATemplate() {
        var defs = parentAndChild(List.of(), List.of(
                repoEntry("https://github.com/o/a.git", "~/x", null, null, "make"),
                repoEntry("https://github.com/p/b.git", "/home/agentuser/x", null, null, "make all")));

        assertEquals("repo /home/agentuser/x: template 'tpl-child' declares it twice with a different"
                        + " url (https://github.com/o/a.git vs https://github.com/p/b.git), prime (make vs make all);"
                        + " remove one declaration or make them identical (an inherited repo cannot be overridden)",
                ImageDef.repoConflict(defs.get("tpl-child"), defs));
    }
    @Test void hostSourceRoundTripsAndChangesFingerprint() throws Exception {
        var def = Jjq.imageDef("""
                name: tpl-private
                parent: tpl-minimal
                repos:
                  - host-source: ~/work/private
                    branch: feature
                """);
        var repo = def.getRepos().getFirst();
        assertNull(repo.getUrl());
        assertEquals("~/private", repo.getPath());
        assertNull(repo.sourceProblem());
        assertEquals("~/work/private", Jjq.imageDef(Jjq.mapper().toJson(def)).getRepos().getFirst().getHostSource());
        var fingerprint = def.contentFingerprint(Map.of());
        repo.setHostSource("~/other/private");
        assertNotEquals(fingerprint, def.contentFingerprint(Map.of()));
        var named = new ImageDef.RepoEntry();
        named.setHostSource("/work/private # repo.git");
        assertEquals("~/private # repo", named.getPath());
        var parent = Jjq.imageDef("""
                name: tpl-parent
                parent: tpl-minimal
                repos:
                  - host-source: ~/work/private
                    branch: feature
                """);
        def.setParent("tpl-parent");
        assertTrue(ImageDef.repoConflict(def, Map.of("tpl-parent", parent)).contains("host-source"));
    }

    @Test void invalidHostSourcesAreRefusedAtLoad(@TempDir Path dir) throws Exception {
        var images = Files.createDirectories(dir.resolve("images"));
        for (var fields : List.of("host-source: relative/path", "host-source: ''",
                "host-source: /private\n    url: https://example.com/repo",
                "host-source: /private\n    depth: 1")) {
            Files.writeString(images.resolve("private.yaml"), "name: tpl-private\nparent: tpl-minimal\nrepos:\n  - " + fields + "\n");
            var warnings = new java.util.ArrayList<String>();
            var defs = ImageDef.loadAllWithConflicts(List.of(dir.toString()), warnings::add);
            assertFalse(defs.defs().containsKey("tpl-private"), fields);
            assertFalse(warnings.isEmpty(), fields);
        }
    }

    @Test void guestFileUrlRetainsItsMeaning() throws Exception {
        var repo = Jjq.imageDef("name: tpl-local\nrepos:\n  - url: file:///guest/staging/repo\n").getRepos().getFirst();
        assertFalse(repo.isHostSource());
        assertNull(repo.sourceProblem());
        assertEquals("file:///guest/staging/repo", repo.getUrl());
    }

}
