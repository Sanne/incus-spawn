package dev.incusspawn.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnvEntryTest {

    @Test
    void setFactoryMethod() {
        var entry = EnvEntry.set("FOO", "bar");
        assertEquals("FOO", entry.getName());
        assertEquals("bar", entry.getValue());
        assertEquals(EnvEntry.Strategy.SET, entry.getStrategy());
        assertTrue(entry.isStructured());
        assertFalse(entry.isRaw());
    }

    @Test
    void setIfUnsetFactoryMethod() {
        var entry = EnvEntry.setIfUnset("FOO", "default");
        assertEquals(EnvEntry.Strategy.SET_IF_UNSET, entry.getStrategy());
    }

    @Test
    void prependFactoryMethod() {
        var entry = EnvEntry.prepend("PATH", "/usr/local/bin", ":");
        assertEquals(EnvEntry.Strategy.PREPEND, entry.getStrategy());
        assertEquals(":", entry.getSeparator());
    }

    @Test
    void appendFactoryMethod() {
        var entry = EnvEntry.append("PATH", "/opt/bin", ":");
        assertEquals(EnvEntry.Strategy.APPEND, entry.getStrategy());
        assertEquals(":", entry.getSeparator());
    }

    @Test
    void rawFactoryMethod() {
        var entry = EnvEntry.raw("export FOO=bar");
        assertTrue(entry.isRaw());
        assertFalse(entry.isStructured());
        assertEquals("export FOO=bar", entry.getRaw());
        assertNull(entry.getName());
        assertNull(entry.getValue());
    }

    @Test
    void withSubstitutionOnStructuredEntry() {
        var entry = EnvEntry.set("HOME", "${user_home}");
        var substituted = entry.withSubstitution(s -> s.replace("${user_home}", "/home/agentuser"));
        assertEquals("HOME", substituted.getName());
        assertEquals("/home/agentuser", substituted.getValue());
        assertTrue(substituted.isStructured());
    }

    @Test
    void withSubstitutionOnRawEntry() {
        var entry = EnvEntry.raw("export HOME=${user_home}");
        var substituted = entry.withSubstitution(s -> s.replace("${user_home}", "/home/agentuser"));
        assertTrue(substituted.isRaw());
        assertEquals("export HOME=/home/agentuser", substituted.getRaw());
    }

    @Test
    void fingerprintStringStructured() {
        var entry = EnvEntry.set("FOO", "bar");
        assertEquals("env=FOO,bar,SET, ", entry.fingerprintString());
    }

    @Test
    void fingerprintStringRaw() {
        var entry = EnvEntry.raw("export FOO=bar");
        assertEquals("raw=export FOO=bar", entry.fingerprintString());
    }

    @Test
    void deserializeRawStringFromYaml() throws Exception {
        var yaml = """
                env:
                  - export FOO=bar
                  - export BAZ=qux
                """;
        var parsed = parseEnvList(yaml);
        assertEquals(2, parsed.size());
        assertTrue(parsed.get(0).isRaw());
        assertEquals("export FOO=bar", parsed.get(0).getRaw());
        assertTrue(parsed.get(1).isRaw());
        assertEquals("export BAZ=qux", parsed.get(1).getRaw());
    }

    @Test
    void deserializeStructuredEntryFromYaml() throws Exception {
        var yaml = """
                env:
                  - name: MAVEN_HOME
                    value: /opt/maven
                """;
        var parsed = parseEnvList(yaml);
        assertEquals(1, parsed.size());
        assertTrue(parsed.get(0).isStructured());
        assertEquals("MAVEN_HOME", parsed.get(0).getName());
        assertEquals("/opt/maven", parsed.get(0).getValue());
        assertEquals(EnvEntry.Strategy.SET, parsed.get(0).getStrategy());
    }

    @Test
    void deserializeStructuredEntryWithStrategy() throws Exception {
        var yaml = """
                env:
                  - name: JAVA_TOOL_OPTIONS
                    value: "-Dtest=true"
                    strategy: prepend
                    separator: " "
                """;
        var parsed = parseEnvList(yaml);
        assertEquals(1, parsed.size());
        assertEquals(EnvEntry.Strategy.PREPEND, parsed.get(0).getStrategy());
        assertEquals(" ", parsed.get(0).getSeparator());
    }

    @Test
    void deserializeMixedList() throws Exception {
        var yaml = """
                env:
                  - export OLD_STYLE=true
                  - name: NEW_STYLE
                    value: "true"
                """;
        var parsed = parseEnvList(yaml);
        assertEquals(2, parsed.size());
        assertTrue(parsed.get(0).isRaw());
        assertTrue(parsed.get(1).isStructured());
    }

    @Test
    void deserializeRejectsUnknownStrategy() {
        var yaml = """
                env:
                  - name: FOO
                    value: bar
                    strategy: unknown
                """;
        assertThrows(Exception.class, () -> parseEnvList(yaml));
    }

    @Test
    void deserializeRejectsMissingName() {
        var yaml = """
                env:
                  - value: bar
                """;
        assertThrows(Exception.class, () -> parseEnvList(yaml));
    }

    @Test
    void deserializeRejectsMissingValue() {
        var yaml = """
                env:
                  - name: FOO
                """;
        assertThrows(Exception.class, () -> parseEnvList(yaml));
    }

    private record Wrapper(
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = EnvEntry.ListDeserializer.class)
            List<EnvEntry> env
    ) {}

    private static List<EnvEntry> parseEnvList(String yaml) throws Exception {
        var mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(yaml, Wrapper.class).env();
    }
}
