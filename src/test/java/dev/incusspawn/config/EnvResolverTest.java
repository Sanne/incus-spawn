package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnvResolverTest {

    @Test
    void setProducesExportLine() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.set("FOO", "bar"), "test");
        var script = resolver.resolve();
        assertTrue(script.contains("export FOO=\"bar\""));
    }

    @Test
    void setIfUnsetProducesConditionalExport() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.setIfUnset("FOO", "default"), "test");
        var script = resolver.resolve();
        assertTrue(script.contains("export FOO=\"${FOO:-default}\""));
    }

    @Test
    void prependProducesCorrectShell() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.prepend("PATH", "/opt/bin", ":"), "test");
        var script = resolver.resolve();
        assertTrue(script.contains("export PATH=\"/opt/bin${PATH:+:$PATH}\""));
    }

    @Test
    void appendProducesCorrectShell() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.append("PATH", "/opt/bin", ":"), "test");
        var script = resolver.resolve();
        assertTrue(script.contains("export PATH=\"${PATH:+$PATH:}/opt/bin\""));
    }

    @Test
    void idempotentSetIsAllowed() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.set("FOO", "same"), "source-a");
        resolver.add(EnvEntry.set("FOO", "same"), "source-b");
        var script = resolver.resolve();
        assertTrue(script.contains("export FOO=\"same\""));
    }

    @Test
    void conflictingSetThrows() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.set("FOO", "value-a"), "source-a");
        resolver.add(EnvEntry.set("FOO", "value-b"), "source-b");
        var ex = assertThrows(EnvResolver.EnvConflictException.class, resolver::resolve);
        assertTrue(ex.getMessage().contains("FOO"));
        assertTrue(ex.getMessage().contains("value-a"));
        assertTrue(ex.getMessage().contains("value-b"));
        assertTrue(ex.getMessage().contains("source-a"));
        assertTrue(ex.getMessage().contains("source-b"));
    }

    @Test
    void setWinsOverSetIfUnset() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.setIfUnset("FOO", "fallback"), "source-a");
        resolver.add(EnvEntry.set("FOO", "winner"), "source-b");
        var script = resolver.resolve();
        assertTrue(script.contains("export FOO=\"winner\""));
        assertFalse(script.contains("fallback"));
    }

    @Test
    void firstSetIfUnsetWins() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.setIfUnset("FOO", "first"), "source-a");
        resolver.add(EnvEntry.setIfUnset("FOO", "second"), "source-b");
        var script = resolver.resolve();
        assertTrue(script.contains("export FOO=\"${FOO:-first}\""));
        assertFalse(script.contains("second"));
    }

    @Test
    void setWithPrependProducesBaseAndModify() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.set("JAVA_OPTS", "-Xmx512m"), "template");
        resolver.add(EnvEntry.prepend("JAVA_OPTS", "-Dfoo=true", " "), "tool");
        var script = resolver.resolve();
        assertTrue(script.contains("export JAVA_OPTS=\"-Xmx512m\""));
        assertTrue(script.contains("export JAVA_OPTS=\"-Dfoo=true${JAVA_OPTS:+ $JAVA_OPTS}\""));
        int baseIdx = script.indexOf("export JAVA_OPTS=\"-Xmx512m\"");
        int prependIdx = script.indexOf("export JAVA_OPTS=\"-Dfoo=true");
        assertTrue(baseIdx < prependIdx, "Base should come before prepend");
    }

    @Test
    void multiplePrependsAccumulate() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.prepend("PATH", "/a", ":"), "tool-a");
        resolver.add(EnvEntry.prepend("PATH", "/b", ":"), "tool-b");
        var script = resolver.resolve();
        assertTrue(script.contains("/a"));
        assertTrue(script.contains("/b"));
    }

    @Test
    void rawEntriesAppendedVerbatim() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.raw("export CUSTOM=value"), "legacy");
        var script = resolver.resolve();
        assertTrue(script.contains("export CUSTOM=value"));
    }

    @Test
    void rawEntriesAppearAfterStructured() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.raw("export RAW=1"), "legacy");
        resolver.add(EnvEntry.set("STRUCTURED", "2"), "modern");
        var script = resolver.resolve();
        int structuredIdx = script.indexOf("export STRUCTURED=");
        int rawIdx = script.indexOf("export RAW=1");
        assertTrue(structuredIdx < rawIdx, "Structured should come before raw");
    }

    @Test
    void headerPresent() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.set("X", "1"), "test");
        var script = resolver.resolve();
        assertTrue(script.startsWith("# Generated by incus-spawn"));
    }

    @Test
    void emptyResolverProducesHeaderOnly() {
        var resolver = new EnvResolver();
        var script = resolver.resolve();
        assertEquals("# Generated by incus-spawn — do not edit\n", script);
    }

    @Test
    void addAllCollectsMultipleEntries() {
        var resolver = new EnvResolver();
        resolver.addAll(List.of(
                EnvEntry.set("A", "1"),
                EnvEntry.set("B", "2")
        ), "bulk");
        var script = resolver.resolve();
        assertTrue(script.contains("export A=\"1\""));
        assertTrue(script.contains("export B=\"2\""));
    }

    @Test
    void shellEscapesSpecialCharacters() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.set("ESCAPED", "has\"quotes\\and$dollars`backticks"), "test");
        var script = resolver.resolve();
        assertTrue(script.contains("has\\\"quotes\\\\and\\$dollars\\`backticks"));
    }

    @Test
    void prependOnlyWithoutBase() {
        var resolver = new EnvResolver();
        resolver.add(EnvEntry.prepend("PATH", "/opt/bin", ":"), "tool");
        var script = resolver.resolve();
        assertTrue(script.contains("export PATH=\"/opt/bin${PATH:+:$PATH}\""));
    }
}
