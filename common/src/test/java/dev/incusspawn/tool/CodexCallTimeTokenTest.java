package dev.incusspawn.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Codex sends the key in {@code ~/.codex/auth.json} and ignores {@code OPENAI_API_KEY}, so the
 * file a build wrote would present the build's placeholder after every start (#1108). These run
 * the login script {@link CodexSetup} writes in a real shell.
 */
class CodexCallTimeTokenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROOF = "sk-isx_0123456789abcdef0123456789abcdef";

    @TempDir
    Path home;

    @Test
    void loginPutsTheKeyOfThisStartInAuthJson() throws Exception {
        var auth = writeAuth("apikey", "sk-placeholder");

        login(Map.of("OPENAI_API_KEY", PROOF));

        var root = JSON.readTree(auth.toFile());
        assertEquals("apikey", root.path("auth_mode").asText());
        assertEquals(PROOF, root.path("OPENAI_API_KEY").asText());

        // and the next start's in its turn
        login(Map.of("OPENAI_API_KEY", "sk-isx_fedcba9876543210fedcba9876543210"));
        assertEquals("sk-isx_fedcba9876543210fedcba9876543210",
                JSON.readTree(auth.toFile()).path("OPENAI_API_KEY").asText());
    }

    @Test
    void loginLeavesAnUnchangedKeyAlone() throws Exception {
        var auth = writeAuth("apikey", "sk-placeholder");
        var before = Files.readString(auth);
        var mtime = Files.getLastModifiedTime(auth);

        // Every login runs this, so an unchanged file costs a grep: a sed that runs leaves a mark
        var shims = Files.createDirectories(home.resolve("shims"));
        var sed = shims.resolve("sed");
        Files.writeString(sed, "#!/bin/sh\ntouch \"$HOME/sed-ran\"\n");
        assertTrue(sed.toFile().setExecutable(true));
        login(Map.of("OPENAI_API_KEY", "sk-placeholder", "PATH", shims + ":" + System.getenv("PATH")));

        assertEquals(before, Files.readString(auth));
        assertEquals(mtime, Files.getLastModifiedTime(auth));
        assertFalse(Files.exists(home.resolve("sed-ran")), "no sed when nothing changed");
    }

    @Test
    void theKeyElsewhereInTheFileIsNotTakenForTheCurrentOne() throws Exception {
        var auth = writeAuth("apikey", "sk-placeholder");
        Files.writeString(auth, Files.readString(auth).replace("{", "{\n  \"last_key\": \"" + PROOF + "\","));

        login(Map.of("OPENAI_API_KEY", PROOF));

        assertEquals(PROOF, JSON.readTree(auth.toFile()).path("OPENAI_API_KEY").asText());
    }

    @Test
    void loginNeverReplacesACredentialIsxDidNotWrite() throws Exception {
        var ownKey = writeAuth("apikey", "sk-proj-somebodysOwnKey");
        login(Map.of("OPENAI_API_KEY", PROOF));
        assertEquals("sk-proj-somebodysOwnKey", JSON.readTree(ownKey.toFile()).path("OPENAI_API_KEY").asText());

        var chatgpt = writeAuth("chatgpt", "sk-placeholder");
        login(Map.of("OPENAI_API_KEY", PROOF));
        assertEquals("chatgpt", JSON.readTree(chatgpt.toFile()).path("auth_mode").asText());
        assertEquals("sk-placeholder", JSON.readTree(chatgpt.toFile()).path("OPENAI_API_KEY").asText());
    }

    /** What {@link CodexSetup} writes at build time. */
    private Path writeAuth(String mode, String key) throws Exception {
        var dir = Files.createDirectories(home.resolve(".codex"));
        var auth = dir.resolve("auth.json");
        Files.writeString(auth, """
                {
                  "auth_mode": "%s",
                  "OPENAI_API_KEY": "%s"
                }
                """.formatted(mode, key));
        return auth;
    }

    private void login(Map<String, String> env) throws Exception {
        assertEquals("", GuestShell.login(home, CodexSetup.LOGIN_AUTH_SCRIPT, env, "true"),
                "a login script says nothing");
    }
}
