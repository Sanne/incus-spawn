package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A {@code files:} entry with {@code merge: json} adds to the JSON file there instead of replacing it. */
class JsonFileMergeTest {

    private static final String CONTAINER = "test-container";
    private static final String PATH = "/home/agentuser/.pi/agent/models.json";
    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");

    @Test
    void objectsMergeKeyByKeyAndKeysTheEntryDoesNotNameAreKept() {
        var existing = """
                {"providers": {"theirs": {"url": "https://a"}}, "theme": "dark"}
                """;
        var entry = """
                {"providers": {"mine": {"url": "https://b"}}}
                """;
        assertEquals("""
                {
                  "providers": {
                    "theirs": {
                      "url": "https://a"
                    },
                    "mine": {
                      "url": "https://b"
                    }
                  },
                  "theme": "dark"
                }
                """, JsonFileMerge.merged(existing, "pi-provider", PATH, entry));
    }

    @Test
    void theEntrysScalarsWinAndItsArraysReplaceTheFilesArrays() {
        var merged = JsonFileMerge.merged("""
                {"a": {"n": 1, "keep": true}, "list": [1, 2, 3], "s": "old"}
                """, "t", PATH, """
                {"a": {"n": 2}, "list": [9], "s": {"now": "an object"}}
                """);
        assertEquals("""
                {
                  "a": {
                    "n": 2,
                    "keep": true
                  },
                  "list": [
                    9
                  ],
                  "s": {
                    "now": "an object"
                  }
                }
                """, merged);
    }

    @Test
    void noFileOrAnEmptyOneIsWrittenAsTheEntryStands() {
        assertEquals("{\n  \"a\": 1\n}\n", JsonFileMerge.merged(null, "t", PATH, "{\"a\": 1}"));
        assertEquals("{\n  \"a\": 1\n}\n", JsonFileMerge.merged(" \n", "t", PATH, "{\"a\": 1}"));
    }

    @Test
    void anExistingFileThatIsNotAJsonObjectIsAnErrorNamingItsPath() {
        var notJson = assertThrows(IllegalArgumentException.class, () -> JsonFileMerge.merged("{oops", "t", PATH, "{}"));
        assertTrue(notJson.getMessage().contains("tool 't': " + PATH + ", which it merges into, is not valid JSON"), notJson.getMessage());
        var array = assertThrows(IllegalArgumentException.class, () -> JsonFileMerge.merged("[1]", "t", PATH, "{}"));
        assertTrue(array.getMessage().endsWith(PATH + ", which it merges into, is not a JSON object"), array.getMessage());
    }

    @Test
    void anEntryBrokenBySubstitutionIsAnErrorNamingTheEntry() {
        // A parameter value holding a quote breaks the JSON only once substituted, at build time
        var e = assertThrows(IllegalArgumentException.class,
                () -> JsonFileMerge.merged("{}", "t", PATH, "{\"name\": \"a\"b\"}"));
        assertTrue(e.getMessage().startsWith("tool 't': the content it merges into " + PATH + " is not valid JSON"), e.getMessage());
    }

    @Test
    void theBuildMergesIntoTheFileThereAndKeepsItsOwner() {
        var incus = mockIncus();
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), startsWith("[ -e ")))
                .thenReturn(new IncusClient.ExecResult(0, "{\"providers\": {\"theirs\": {}}}", ""));

        new YamlToolSetup(tool()).install(new Container(incus, CONTAINER), Map.of("id", "mine"));

        verify(incus).shellExec(CONTAINER, "sh", "-c", "[ -e '" + PATH + "' ] || exit 3; cat '" + PATH + "'");
        verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat((String script) -> script.startsWith("mkdir -p")
                && script.contains("\"theirs\": {}") && script.contains("\"mine\": {\n      \"url\": \"https://b\"")));
        verify(incus).shellExec(CONTAINER, "chown", "-R", "agentuser:agentuser", PATH);
    }

    @Test
    void theBuildWritesTheEntryWhenThereIsNoFile() {
        var incus = mockIncus();
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), startsWith("[ -e ")))
                .thenReturn(new IncusClient.ExecResult(3, "", ""));

        new YamlToolSetup(tool()).install(new Container(incus, CONTAINER), Map.of("id", "mine"));

        verify(incus).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), argThat((String script) -> script.startsWith("mkdir -p")
                && !script.contains("theirs") && script.contains("\"mine\"")));
    }

    @Test
    void theBuildFailsOnAFileThatIsNotJsonAndWritesNothing() {
        var incus = mockIncus();
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), startsWith("[ -e ")))
                .thenReturn(new IncusClient.ExecResult(0, "providers = 1\n", ""));

        var e = assertThrows(IncusException.class,
                () -> new YamlToolSetup(tool()).install(new Container(incus, CONTAINER), Map.of("id", "mine")));

        assertTrue(e.getMessage().contains(PATH + ", which it merges into, is not valid JSON"), e.getMessage());
        verify(incus, never()).shellExec(eq(CONTAINER), eq("sh"), eq("-c"), startsWith("mkdir -p"));
    }

    @Test
    void aMergeThatIsNotJsonOrContentThatIsNotAnObjectIsRefusedWhereDefinitionsLoad() throws IOException {
        var unknown = ToolDefValidator.refusal(def("""
                name: t
                files:
                  - path: /etc/x.toml
                    merge: toml
                    content: "a = 1"
                """));
        assertEquals("files entry '/etc/x.toml' in 't' has merge 'toml'; the only value is 'json'", unknown);

        var notJson = ToolDefValidator.refusal(def("""
                name: t
                files:
                  - path: /etc/x.json
                    merge: json
                    content: "{a: 1}"
                """));
        assertNotNull(notJson);
        assertTrue(notJson.startsWith("files entry '/etc/x.json' in 't' merges JSON, but its content is not valid JSON"), notJson);

        var array = ToolDefValidator.refusal(def("""
                name: t
                files:
                  - path: /etc/x.json
                    merge: json
                    content: "[1]"
                """));
        assertEquals("files entry '/etc/x.json' in 't' merges JSON, but its content is not a JSON object", array);
    }

    @Test
    void aParameterOutsideAStringIsAcceptedWhereDefinitionsLoad() throws IOException {
        var def = def("""
                name: t
                parameters:
                  port: { type: integer, default: 8080 }
                files:
                  - path: /etc/x.json
                    merge: json
                    content: '{"port": ${param_port}, "home": "${HOME}"}'
                """);
        assertNull(ToolDefValidator.refusal(def));
        var errors = new java.util.ArrayList<String>();
        var warnings = new java.util.ArrayList<String>();
        ToolDefValidator.validateDef(def, errors, warnings);
        assertEquals(List.of(), errors);
        assertEquals(List.of(), warnings, "merge is a known key");
    }

    @Test
    void mergeIsPartOfTheFingerprintOnlyWhenSet() {
        var merging = tool();
        var replacing = tool();
        replacing.getFiles().getFirst().setMerge(null);
        assertNotEquals(merging.contentFingerprint(), replacing.contentFingerprint());

        // A tool without merge: keeps the fingerprint it had before the key existed
        var file = new ToolDef.FileEntry();
        file.setPath("/etc/a");
        file.setContent("b");
        var def = new ToolDef();
        def.setName("t");
        def.setFiles(List.of(file));
        assertEquals(ToolDef.sha256hex("file=/etc/a,b,null\n"), def.contentFingerprint());
    }

    private static ToolDef tool() {
        var file = new ToolDef.FileEntry();
        file.setPath(PATH);
        file.setContent("{\"providers\": {\"${param_id}\": {\"url\": \"https://b\"}}}");
        file.setOwner("agentuser:agentuser");
        file.setMerge("json");
        var def = new ToolDef();
        def.setName("pi-provider");
        def.setFiles(List.of(file));
        return def;
    }

    private static ToolDef def(String yaml) throws IOException {
        return ToolDef.loadFromStream(new ByteArrayInputStream(yaml.getBytes()));
    }

    private static IncusClient mockIncus() {
        var incus = mock(IncusClient.class);
        when(incus.getInstanceArchitecture(CONTAINER)).thenReturn("x86_64");
        when(incus.machineType(CONTAINER)).thenReturn(dev.incusspawn.incus.MachineType.CONTAINER);
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        return incus;
    }
}
