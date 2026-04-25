package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ToolDefTest {

    @Test
    void parseCompleteTool() throws Exception {
        var yaml = """
                name: test-tool
                description: A test tool
                packages:
                  - pkg-one
                  - pkg-two
                run:
                  - echo hello
                  - echo world
                run_as_user:
                  - whoami
                files:
                  - path: /etc/test.conf
                    content: |
                      key=value
                    owner: testuser:testuser
                env:
                  - export FOO=bar
                verify: test-tool --version
                """;
        var def = ToolDef.loadFromStream(toStream(yaml));

        assertEquals("test-tool", def.getName());
        assertEquals("A test tool", def.getDescription());
        assertEquals(2, def.getPackages().size());
        assertEquals("pkg-one", def.getPackages().get(0));
        assertEquals("pkg-two", def.getPackages().get(1));
        assertEquals(2, def.getRun().size());
        assertEquals("echo hello", def.getRun().get(0));
        assertEquals(1, def.getRunAsUser().size());
        assertEquals("whoami", def.getRunAsUser().get(0));
        assertEquals(1, def.getFiles().size());
        assertEquals("/etc/test.conf", def.getFiles().get(0).getPath());
        assertEquals("key=value\n", def.getFiles().get(0).getContent());
        assertEquals("testuser:testuser", def.getFiles().get(0).getOwner());
        assertEquals(1, def.getEnv().size());
        assertEquals("export FOO=bar", def.getEnv().get(0));
        assertEquals("test-tool --version", def.getVerify());
    }

    @Test
    void parseToolWithDownloads() throws Exception {
        var yaml = """
                name: maven-3
                downloads:
                  - url: https://example.com/maven-3.9.14-bin.tar.gz
                    sha256: abc123def456
                    extract: /opt
                    links:
                      /opt/maven-3.9.14/bin/mvn: /usr/local/bin/mvn
                      /opt/maven-3.9.14/bin/mvnDebug: /usr/local/bin/mvnDebug
                """;
        var def = ToolDef.loadFromStream(toStream(yaml));

        assertEquals(1, def.getDownloads().size());
        var dl = def.getDownloads().get(0);
        assertEquals("https://example.com/maven-3.9.14-bin.tar.gz", dl.getUrl());
        assertEquals("abc123def456", dl.getSha256());
        assertEquals("/opt", dl.getExtract());
        assertEquals(2, dl.getLinks().size());
        assertEquals("/usr/local/bin/mvn", dl.getLinks().get("/opt/maven-3.9.14/bin/mvn"));
        assertEquals("/usr/local/bin/mvnDebug", dl.getLinks().get("/opt/maven-3.9.14/bin/mvnDebug"));
    }

    @Test
    void parseToolWithDownloadsNoSha256() throws Exception {
        var yaml = """
                name: tool-no-sha
                downloads:
                  - url: https://example.com/tool.tar.gz
                    extract: /opt
                """;
        var def = ToolDef.loadFromStream(toStream(yaml));

        assertEquals(1, def.getDownloads().size());
        var dl = def.getDownloads().get(0);
        assertEquals("https://example.com/tool.tar.gz", dl.getUrl());
        assertNull(dl.getSha256());
        assertEquals("/opt", dl.getExtract());
        assertTrue(dl.getLinks().isEmpty());
    }

    @Test
    void parseMinimalTool() throws Exception {
        var yaml = "name: minimal\n";
        var def = ToolDef.loadFromStream(toStream(yaml));

        assertEquals("minimal", def.getName());
        assertEquals("", def.getDescription());
        assertTrue(def.getDownloads().isEmpty());
        assertTrue(def.getPackages().isEmpty());
        assertTrue(def.getRun().isEmpty());
        assertTrue(def.getRunAsUser().isEmpty());
        assertTrue(def.getFiles().isEmpty());
        assertTrue(def.getEnv().isEmpty());
        assertTrue(def.getRequires().isEmpty());
        assertNull(def.getVerify());
    }

    @Test
    void parseRequiresField() throws Exception {
        var yaml = """
                name: idea-backend
                requires:
                  - sshd
                  - other-tool
                run:
                  - echo hello
                """;
        var def = ToolDef.loadFromStream(toStream(yaml));
        assertEquals(2, def.getRequires().size());
        assertEquals("sshd", def.getRequires().get(0));
        assertEquals("other-tool", def.getRequires().get(1));
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        var yaml = """
                name: flexible
                future_field: should be ignored
                """;
        var def = ToolDef.loadFromStream(toStream(yaml));
        assertEquals("flexible", def.getName());
    }

    @Test
    void fileEntryWithoutOwner() throws Exception {
        var yaml = """
                name: files-test
                files:
                  - path: /tmp/test
                    content: hello
                """;
        var def = ToolDef.loadFromStream(toStream(yaml));
        assertEquals(1, def.getFiles().size());
        assertEquals("/tmp/test", def.getFiles().get(0).getPath());
        assertEquals("hello", def.getFiles().get(0).getContent());
        assertNull(def.getFiles().get(0).getOwner());
    }

    // --- contentFingerprint tests ---

    @Test
    void fingerprintStableForSameInput() throws Exception {
        var yaml = """
                name: test
                packages:
                  - alpha
                  - beta
                env:
                  - FOO=1
                  - BAR=2
                """;
        var def1 = ToolDef.loadFromStream(toStream(yaml));
        var def2 = ToolDef.loadFromStream(toStream(yaml));
        assertEquals(def1.contentFingerprint(), def2.contentFingerprint());
    }

    @Test
    void fingerprintIgnoresPackageOrder() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                packages:
                  - alpha
                  - beta
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                packages:
                  - beta
                  - alpha
                """));
        assertEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintIgnoresEnvOrder() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                env:
                  - FOO=1
                  - BAR=2
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                env:
                  - BAR=2
                  - FOO=1
                """));
        assertEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintIgnoresNameAndDescription() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: tool-a
                description: First tool
                packages:
                  - pkg
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: tool-b
                description: Second tool
                packages:
                  - pkg
                """));
        assertEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenPackageAdded() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                packages:
                  - alpha
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                packages:
                  - alpha
                  - beta
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenRunCommandChanges() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                run:
                  - echo hello
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                run:
                  - echo world
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenVerifyChanges() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                verify: cmd --version
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                verify: cmd --help
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenDownloadUrlChanges() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                downloads:
                  - url: https://example.com/v1.tar.gz
                    sha256: abc123
                    extract: /opt
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                downloads:
                  - url: https://example.com/v2.tar.gz
                    sha256: abc123
                    extract: /opt
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintStableForLinkMapOrder() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                downloads:
                  - url: https://example.com/tool.tar.gz
                    sha256: abc
                    extract: /opt
                    links:
                      /opt/bin/a: /usr/local/bin/a
                      /opt/bin/b: /usr/local/bin/b
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                downloads:
                  - url: https://example.com/tool.tar.gz
                    sha256: abc
                    extract: /opt
                    links:
                      /opt/bin/b: /usr/local/bin/b
                      /opt/bin/a: /usr/local/bin/a
                """));
        assertEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenRunAsUserChanges() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                run_as_user:
                  - echo hello
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                run_as_user:
                  - echo world
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintSensitiveToRunOrder() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                run:
                  - step-one
                  - step-two
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                run:
                  - step-two
                  - step-one
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintSensitiveToRunAsUserOrder() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                run_as_user:
                  - step-one
                  - step-two
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                run_as_user:
                  - step-two
                  - step-one
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenFileChanges() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                files:
                  - path: /etc/test.conf
                    content: old
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                files:
                  - path: /etc/test.conf
                    content: new
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenFilePathChanges() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                files:
                  - path: /etc/a.conf
                    content: same
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                files:
                  - path: /etc/b.conf
                    content: same
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenFileOwnerChanges() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                files:
                  - path: /etc/test.conf
                    content: data
                    owner: root:root
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                files:
                  - path: /etc/test.conf
                    content: data
                    owner: user:user
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenRequiresChanges() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                requires:
                  - sshd
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                requires:
                  - sshd
                  - other-tool
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintIgnoresRequiresOrder() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                requires:
                  - alpha
                  - beta
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                requires:
                  - beta
                  - alpha
                """));
        assertEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenDownloadSha256Changes() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                downloads:
                  - url: https://example.com/tool.tar.gz
                    sha256: abc123
                    extract: /opt
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                downloads:
                  - url: https://example.com/tool.tar.gz
                    sha256: def456
                    extract: /opt
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenDownloadExtractChanges() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                downloads:
                  - url: https://example.com/tool.tar.gz
                    sha256: abc123
                    extract: /opt
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                downloads:
                  - url: https://example.com/tool.tar.gz
                    sha256: abc123
                    extract: /usr/local
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    @Test
    void fingerprintChangesWhenDownloadLinkAdded() throws Exception {
        var a = ToolDef.loadFromStream(toStream("""
                name: test
                downloads:
                  - url: https://example.com/tool.tar.gz
                    sha256: abc
                    extract: /opt
                """));
        var b = ToolDef.loadFromStream(toStream("""
                name: test
                downloads:
                  - url: https://example.com/tool.tar.gz
                    sha256: abc
                    extract: /opt
                    links:
                      /opt/bin/tool: /usr/local/bin/tool
                """));
        assertNotEquals(a.contentFingerprint(), b.contentFingerprint());
    }

    // --- compositeFingerprints tests ---

    @Test
    void compositeNoDepsMatchesRaw() {
        var raw = new TreeMap<>(Map.of("toolA", "fp-a", "toolB", "fp-b"));
        var deps = new TreeMap<String, List<String>>();
        var result = ToolDef.compositeFingerprints(raw, deps);
        assertEquals("fp-a", result.get("toolA"));
        assertEquals("fp-b", result.get("toolB"));
    }

    @Test
    void compositeIncludesTransitiveDep() {
        var raw = new TreeMap<>(Map.of("parent", "fp-parent", "child", "fp-child"));
        var deps = new TreeMap<>(Map.of("parent", List.of("child")));
        var result = ToolDef.compositeFingerprints(raw, deps);
        assertEquals("fp-child", result.get("child"), "Leaf tool should keep raw fp");
        assertNotEquals("fp-parent", result.get("parent"), "Tool with deps should get composite fp");
    }

    @Test
    void compositeChangesWhenDepChanges() {
        var deps = new TreeMap<>(Map.of("parent", List.of("child")));
        var r1 = ToolDef.compositeFingerprints(new TreeMap<>(Map.of("parent", "fp-p", "child", "v1")), deps);
        var r2 = ToolDef.compositeFingerprints(new TreeMap<>(Map.of("parent", "fp-p", "child", "v2")), deps);
        assertNotEquals(r1.get("parent"), r2.get("parent"), "Dep change should propagate to parent");
    }

    @Test
    void compositeHandlesCycle() {
        var raw = new TreeMap<>(Map.of("a", "fp-a", "b", "fp-b"));
        var deps = new TreeMap<>(Map.of("a", List.of("b"), "b", List.of("a")));
        assertDoesNotThrow(() -> ToolDef.compositeFingerprints(raw, deps));
    }

    @Test
    void compositeStableForDepOrder() {
        var raw = new TreeMap<>(Map.of("parent", "fp-p", "depA", "fp-a", "depB", "fp-b"));
        var deps1 = new TreeMap<>(Map.of("parent", List.of("depA", "depB")));
        var deps2 = new TreeMap<>(Map.of("parent", List.of("depB", "depA")));
        var r1 = ToolDef.compositeFingerprints(raw, deps1);
        var r2 = ToolDef.compositeFingerprints(raw, deps2);
        assertEquals(r1.get("parent"), r2.get("parent"), "Dep order should not affect composite fp");
    }

    private static ByteArrayInputStream toStream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
