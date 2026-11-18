package dev.incusspawn.config;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.tool.ToolSetup;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** A credential written under a key isx does not read is named, never silently kept (#1733). */
class UnreadConfigKeysTest {

    /** Built-in tools only, so no tool YAML on the developer's machine can leak in. */
    private static Map<String, ToolSetup> tools() {
        var map = new LinkedHashMap<String, ToolSetup>();
        RuntimeConstants.CDI_TOOLS.forEach(t -> map.put(t.name(), t));
        return map;
    }

    private static Map<String, List<String>> unread(String yaml) throws Exception {
        return UnreadConfigKeys.all(Jjq.spawnConfig(yaml).tree(), tools());
    }

    @Test
    void aGuessedKeyNameIsReportedWithTheKeyIsxReads() throws Exception {
        var tree = Jjq.spawnConfig("openai:\n   token: sk-handwritten\n").tree();
        assertEquals(List.of("openai.token"), UnreadConfigKeys.in(tree, "openai", tools()));
        assertEquals("config.yaml has openai.token, which isx does not read;"
                        + " it reads openai.apiKey or the same under openai.accounts.<name>.",
                UnreadConfigKeys.note(tree, "openai", tools()));
    }

    @Test
    void anUnreadKeyInsideAnAccountIsNamedWithItsAccount() throws Exception {
        assertEquals(Map.of("github", List.of("github.accounts.work.pat")), unread("""
                github:
                  accounts:
                    personal: { token: "ghp_x", email: "me@example.com" }
                    work: { pat: "ghp_y" }
                  default: personal
                """));
    }

    @Test
    void everythingIsxWritesIsRead() throws Exception {
        assertEquals(Map.of(), unread("""
                github:
                  token: "ghp_x"
                  email: "me@example.com"
                bob:
                  apiKey: "bob-key"
                  licenseConsent: true
                openai:
                  accounts:
                    default: { apiKey: "sk-x" }
                    sso: { type: chatgpt }
                  default: default
                gemini:
                  apiKey: "g-key"
                """));
    }

    /**
     * Claude's typed config once dropped these at bind, so they never reached the tree (#1876).
     * A typed namespace writes its undeclared keys after its declared ones.
     */
    @Test
    void claudeKeysIsxDoesNotReadAreNamed() throws Exception {
        assertEquals(Map.of("claude", List.of("claude.accounts.w.extra", "claude.token")), unread("""
                claude:
                  token: sk-ant-x
                  useVertex: true
                  cloudMlRegion: us-east5
                  vertexProjectId: p
                  accounts:
                    w: { type: api-key, apiKey: k, extra: y }
                    o: { oauthToken: sk-ant-oat01-x }
                  default: w
                """));
    }

    @Test
    void theNoteNamesOnlyTheCredentialKeys() throws Exception {
        var tree = Jjq.spawnConfig("github:\n  pat: ghp_x\n").tree();
        assertEquals("config.yaml has github.pat, which isx does not read;"
                        + " it reads github.token or the same under github.accounts.<name>.",
                UnreadConfigKeys.note(tree, "github", tools()));
    }

    @Test
    void anAccountThatIsNotAMappingIsNamedWithoutATrailingDot() throws Exception {
        assertEquals(Map.of("github", List.of("github.accounts.work")), unread("""
                github:
                  accounts:
                    personal: { token: "ghp_x" }
                    work: "ghp_y"
                """));
    }

    /** An empty account is listed and reported as unusable elsewhere, never as a key to remove. */
    @Test
    void anEmptyOrNullAccountIsNotUnread() throws Exception {
        assertEquals(Map.of(), unread("""
                github:
                  accounts:
                    personal: { token: "ghp_x" }
                    work: {}
                    spare:
                    blank: ""
                  default: personal
                """));
    }

    /** A token typed as a key name is a path printed in refusals, so it is redacted. */
    @Test
    void aKeyNameShapedLikeATokenIsRedacted() throws Exception {
        var token = "sk-proj-abcdefghijklmnopqrstuvwxyz0123";
        var unread = unread("openai:\n  " + token + ":\n").get("openai");
        assertEquals(1, unread.size(), String.valueOf(unread));
        assertFalse(unread.getFirst().contains(token), unread.getFirst());
        assertTrue(unread.getFirst().startsWith("openai." + SecretRedactor.MARKER_PREFIX), unread.getFirst());
    }

    /** Two token-shaped key names redact to the same path, which is named once. */
    @Test
    void twoTokenKeyNamesAreNamedOnce() throws Exception {
        var unread = unread("openai:\n  sk-proj-abcdefghijklmnopqrstuvwxyz0123:\n  sk-proj-zyxwvutsrqponmlkjihgfedcba9876:\n")
                .get("openai");
        assertEquals(1, unread.size(), String.valueOf(unread));
    }

    /** A key pasted straight under the namespace is read by nothing, and named redacted like any path. */
    @Test
    void aNamespaceHoldingAScalarIsNamed() throws Exception {
        assertEquals(Map.of("gemini", List.of("gemini")), unread("gemini: plain-value\n"));
        assertEquals(Map.of("gemini", List.of("gemini")), unread("gemini: [plain-value]\n"));
        assertEquals(Map.of(), unread("gemini:\n"));
    }

    /**
     * A cloned repository's {@code .incus-spawn/tools/} decides nothing reported: its tool may
     * declare {@code openai.token}, which the proxy never serves, and must not hide the warning.
     * The directory is the real one a loader reads, relative to the working directory.
     */
    @Test
    void aProjectLocalToolDoesNotDecideWhatIsRead() throws Exception {
        var project = Path.of(".incus-spawn");
        assumeFalse(Files.exists(project), "a .incus-spawn directory is already in " + project.toAbsolutePath());
        try {
            Files.createDirectories(project.resolve("tools"));
            Files.writeString(project.resolve("tools/sneaky.yaml"), """
                    name: sneaky
                    proxy:
                      config-namespace: openai
                      configuration:
                        token: { config-path: token, secret: true }
                    """);
            var tree = Jjq.spawnConfig("openai:\n  token: sk-handwritten\n").tree();
            // The control: an ordinary loader reads it, and it would hide the key
            assertEquals(List.of(), UnreadConfigKeys.in(tree, "openai",
                    new dev.incusspawn.tool.ToolDefLoader(List.of()).allToolSetups()));
            assertEquals(List.of("openai.token"),
                    UnreadConfigKeys.in(tree, "openai", UnreadConfigKeys.trustedTools(new SpawnConfig())));
        } finally {
            Files.deleteIfExists(project.resolve("tools/sneaky.yaml"));
            Files.deleteIfExists(project.resolve("tools"));
            Files.deleteIfExists(project);
        }
    }

    @Test
    void aNamespaceNoToolDeclaresIsLeftAlone() throws Exception {
        assertEquals(Map.of(), unread("someone-elses-tool:\n  token: x\n"));
    }
}
