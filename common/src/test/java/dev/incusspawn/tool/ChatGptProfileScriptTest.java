package dev.incusspawn.tool;

import dev.incusspawn.proxy.InstanceSecret;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The login profile a start writes for a ChatGPT session (#1332), run in a real shell: it
 * replaces only what isx put in Codex's auth.json, and it stops the build's config from
 * forcing API-key login, which would log the session out and delete the file.
 */
class ChatGptProfileScriptTest {

    private static final String PLACEHOLDER_AUTH = """
            {"auth_mode": "apikey", "OPENAI_API_KEY": "sk-placeholder"}
            """;
    private static final String SESSION_AUTH = """
            {"auth_mode": "chatgpt", "OPENAI_API_KEY": null,
             "tokens": {"id_token": "i.isx_x", "access_token": "a.isx_y", "refresh_token": "rt.isx_z"},
             "last_refresh": "2026-11-18T00:00:00Z"}
            """;
    private static final String ISX_CONFIG = """
            approval_policy = "never"
            forced_login_method = "api"
            check_for_update_on_startup = false
            default_permissions = "Running unconstrained in isx dev [container]"
            """;

    @TempDir
    Path home;

    private Path codex() throws Exception {
        var dir = Files.createDirectories(home.resolve(".codex"));
        Files.writeString(dir.resolve("auth.json"), PLACEHOLDER_AUTH);
        Files.writeString(dir.resolve("config.toml"), ISX_CONFIG);
        return dir;
    }

    private Path sessionFile() throws Exception {
        var file = Files.createTempFile(home, "chatgpt-auth", "");
        Files.writeString(file, SESSION_AUTH);
        return file;
    }

    private void login(Path source, String then) throws Exception {
        var env = source == null ? Map.<String, String>of()
                : Map.of("ISX_CHATGPT_AUTH_FILE", source.toString());
        assertEquals("", GuestShell.login(home, InstanceSecret.CHATGPT_PROFILE_SCRIPT, env, then),
                "a login script says nothing");
    }

    @Test
    void aLoginReplacesThePlaceholdersAndStopsTheForcedApiKeyLogin() throws Exception {
        codex();

        login(sessionFile(), "true");

        var auth = JqValues.parseStrict(Files.readAllBytes(home.resolve(".codex/auth.json")));
        assertEquals("chatgpt", auth.pathText("auth_mode", ""));
        assertTrue(auth.path("OPENAI_API_KEY").isNull());
        assertEquals("rt.isx_z", auth.path("tokens").pathText("refresh_token", ""));

        var config = Files.readString(home.resolve(".codex/config.toml"));
        assertFalse(config.contains("forced_login_method"), config);
        assertTrue(config.contains("approval_policy = \"never\""), "only the forcing line goes: " + config);
        assertTrue(config.contains("Running unconstrained in isx"), config);
    }

    @Test
    void aUsersOwnLoginIsNeverReplacedAndTheirConfigNeverPatched() throws Exception {
        var dir = codex();
        // The user's own codex login: real tokens, and their own config without isx's footer
        Files.writeString(dir.resolve("auth.json"),
                "{\"auth_mode\": \"chatgpt\", \"tokens\": {\"refresh_token\": \"rt-real\"}}");
        Files.writeString(dir.resolve("config.toml"), "forced_login_method = \"api\"\nmodel = \"gpt-x\"\n");
        var before = Files.readString(dir.resolve("auth.json"));

        login(sessionFile(), "true");

        assertEquals(before, Files.readString(dir.resolve("auth.json")), "the user's login stays");
        assertEquals("forced_login_method = \"api\"\nmodel = \"gpt-x\"\n",
                Files.readString(dir.resolve("config.toml")), "their config stays");
    }

    @Test
    void aSessionTheStartDidNotDeliverTouchesNothing() throws Exception {
        var dir = codex();
        var before = Files.readString(dir.resolve("auth.json"));

        // No source file: an account switched away from ChatGPT, or the file not yet delivered
        login(null, "true");

        assertEquals(before, Files.readString(dir.resolve("auth.json")));
        assertTrue(Files.readString(dir.resolve("config.toml")).contains("forced_login_method"));
    }

    @Test
    void aSessionCodexRefreshedSinceTheDeliveryIsLeftAlone() throws Exception {
        var dir = codex();
        // Codex refreshed through the proxy: a new access token, the same refresh placeholder
        var refreshed = """
                {
                  "auth_mode": "chatgpt",
                  "OPENAI_API_KEY": null,
                  "tokens": {"id_token": "i.isx_x", "access_token": "a.isx_new", "refresh_token": "rt.isx_z"},
                  "last_refresh": "2026-11-19T00:00:00Z"
                }
                """;
        Files.writeString(dir.resolve("auth.json"), refreshed);

        login(sessionFile(), "true");

        assertEquals(refreshed, Files.readString(dir.resolve("auth.json")),
                "a later login does not hand Codex back the start's access token");
    }

    @Test
    void theProfileLeavesNoVariablesBehind() throws Exception {
        codex();
        login(sessionFile(), "[ -z \"${isx_auth:-}\" ] && [ -z \"${isx_src:-}\" ] && [ -z \"${isx_rt:-}\" ]");
    }
}
