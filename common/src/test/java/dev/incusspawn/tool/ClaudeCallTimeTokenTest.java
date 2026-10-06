package dev.incusspawn.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Claude Code's credentials change on every start once they are per-start proofs (#1106), so
 * nothing in the guest may hold one fixed at build time (#1108). These run the guest scripts
 * {@link ClaudeSetup} writes in a real shell, against the values a restarted instance would
 * export.
 */
class ClaudeCallTimeTokenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROOF = "sk-ant-isx_0123456789abcdef0123456789abcdef";
    private static final String NEXT_PROOF = "sk-ant-isx_fedcba9876543210fedcba9876543210";

    @TempDir
    Path home;

    @Test
    void gcloudStubPrintsTheVertexTokenOfTheCallingEnvironment() throws Exception {
        var stub = home.resolve("gcloud");
        Files.writeString(stub, ClaudeSetup.GCLOUD_STUB_SCRIPT);

        var env = Map.of(ClaudeSetup.VERTEX_TOKEN_ENV, "ya29.isx_0123abcd");
        assertEquals("ya29.isx_0123abcd",
                run(env, "sh", stub.toString(), "auth", "print-access-token").strip(),
                "the stub must print the token exported at login, which changes on every start");
        assertEquals(ClaudeSetup.VERTEX_PLACEHOLDER_TOKEN,
                run(Map.of(), "sh", stub.toString(), "auth", "print-access-token").strip(),
                "a build, or an instance never started, has only the placeholder");
    }

    @Test
    void gcloudStubStaysRecognisableForRemoval() {
        // syncGcloudStub identifies its own stub by this marker, also in stubs written before #1108
        assertTrue(ClaudeSetup.GCLOUD_STUB_SCRIPT.contains("placeholder-for-proxy"));
    }

    @Test
    void vertexLoginSendsTheTokenAsTheAuthorizationHeader() throws Exception {
        // Under CLAUDE_CODE_SKIP_VERTEX_AUTH Claude Code sends no Authorization header of its
        // own: the only one it sends is from ANTHROPIC_CUSTOM_HEADERS
        var out = login(Map.of("CLAUDE_CODE_USE_VERTEX", "1",
                ClaudeSetup.VERTEX_TOKEN_ENV, "ya29.isx_0123abcd"), "printf %s \"$ANTHROPIC_CUSTOM_HEADERS\"");
        assertEquals("Authorization: Bearer ya29.isx_0123abcd", out);

        assertEquals("", login(Map.of(ClaudeSetup.VERTEX_TOKEN_ENV, "ya29.isx_0123abcd"),
                "printf %s \"$ANTHROPIC_CUSTOM_HEADERS\""), "only in Vertex mode");
    }

    @Test
    void vertexLoginKeepsTheHeadersSetBeforeIt() throws Exception {
        var env = Map.of("CLAUDE_CODE_USE_VERTEX", "1", ClaudeSetup.VERTEX_TOKEN_ENV, "ya29.isx_0123abcd",
                "ANTHROPIC_CUSTOM_HEADERS", "X-Team: platform");
        assertEquals("X-Team: platform\nAuthorization: Bearer ya29.isx_0123abcd",
                login(env, "printf %s \"$ANTHROPIC_CUSTOM_HEADERS\""));
        // A nested login shell sources the script again with the headers it exported
        assertEquals("X-Team: platform\nAuthorization: Bearer ya29.isx_0123abcd",
                login(env, ". \"$1\"; printf %s \"$ANTHROPIC_CUSTOM_HEADERS\""), "added once");
    }

    @Test
    void vertexLoginReplacesAStaleAuthorizationLine() throws Exception {
        // Carried over from an earlier start, e.g. through 'sudo -E'; this one is a prefix of it
        var env = Map.of("CLAUDE_CODE_USE_VERTEX", "1", ClaudeSetup.VERTEX_TOKEN_ENV, "ya29.isx_0123abcd",
                "ANTHROPIC_CUSTOM_HEADERS", "authorization: Bearer ya29.isx_0123abcdef\nX-Team: platform");
        assertEquals("X-Team: platform\nAuthorization: Bearer ya29.isx_0123abcd",
                login(env, "printf %s \"$ANTHROPIC_CUSTOM_HEADERS\""));
    }

    @Test
    void loginApprovesTheApiKeyOfThisStart() throws Exception {
        var claudeJson = writeClaudeJson("sk-ant-placeholder");

        login(Map.of("ANTHROPIC_API_KEY", PROOF), "true");

        var root = JSON.readTree(claudeJson.toFile());
        assertEquals(List.of(PROOF.substring(PROOF.length() - 20)),
                JSON.convertValue(root.at("/customApiKeyResponses/approved"), List.class),
                "Claude Code approves a key by its last 20 characters, and asks again for any other");
        assertTrue(root.at("/projects/~1home~1agentuser/hasTrustDialogAccepted").asBoolean(),
                "the rest of the file is kept");

        // The next start brings a new proof, approved in its turn
        login(Map.of("ANTHROPIC_API_KEY", NEXT_PROOF), "true");
        assertEquals(List.of(NEXT_PROOF.substring(NEXT_PROOF.length() - 20)),
                JSON.convertValue(JSON.readTree(claudeJson.toFile()).at("/customApiKeyResponses/approved"), List.class));
    }

    @Test
    void aKeyShorterThanTwentyCharactersIsApprovedWhole() throws Exception {
        var claudeJson = writeClaudeJson("sk-ant-placeholder");

        login(Map.of("ANTHROPIC_API_KEY", "sk-ant-short"), "true");

        assertEquals(List.of("sk-ant-short"), JSON.convertValue(
                JSON.readTree(claudeJson.toFile()).at("/customApiKeyResponses/approved"), List.class));
    }

    @Test
    void loginLeavesAnAlreadyApprovedKeyAlone() throws Exception {
        var claudeJson = writeClaudeJson("sk-ant-placeholder");
        var before = Files.readString(claudeJson);

        login(Map.of("ANTHROPIC_API_KEY", "sk-ant-placeholder"), "true");

        assertEquals(before, Files.readString(claudeJson), "no write when nothing changed");
    }

    @Test
    void loginNeverEditsAFileItDoesNotRecognise() throws Exception {
        var claudeJson = home.resolve(".claude.json");
        Files.writeString(claudeJson, "{\"compact\": true}\n");

        login(Map.of("ANTHROPIC_API_KEY", PROOF), "true");

        assertEquals("{\"compact\": true}\n", Files.readString(claudeJson),
                "an approval prompt is better than a corrupted config");
    }

    @Test
    void loginWithoutAnApiKeyLeavesTheFileAlone() throws Exception {
        var claudeJson = writeClaudeJson("sk-ant-placeholder");
        var before = Files.readString(claudeJson);

        login(Map.of(), "true");

        assertEquals(before, Files.readString(claudeJson));
    }

    /** What Claude Code itself writes: {@code JSON.stringify(config, null, 2)}. */
    private Path writeClaudeJson(String approved) throws IOException {
        var config = JSON.createObjectNode();
        config.put("hasCompletedOnboarding", true);
        var responses = config.putObject("customApiKeyResponses");
        responses.putArray("approved").add(approved);
        responses.putArray("rejected");
        config.putObject("projects").putObject("/home/agentuser").put("hasTrustDialogAccepted", true);
        var path = home.resolve(".claude.json");
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(config));
        return path;
    }

    private String login(Map<String, String> env, String then) throws Exception {
        return GuestShell.login(home, ClaudeSetup.LOGIN_AUTH_SCRIPT, env, then);
    }

    private String run(Map<String, String> env, String... command) throws Exception {
        return GuestShell.run(home, env, command);
    }
}
