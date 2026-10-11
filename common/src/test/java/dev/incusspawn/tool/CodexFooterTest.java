package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodexFooterTest {
    @TempDir
    Path home;

    @Test
    void loginRefreshesTheProfileForEachCloneAndVersion() throws Exception {
        var original = CodexSetup.configToml(Map.of("model", "custom-model", "effort", "high"));
        var config = writeConfig(original);

        login(Map.of("ISX_VERSION", "0.9.0-dev.1+test", "ISX_CONTAINER", "tpl-codex"));
        assertEquals(original.replace("dev [container]", "0.9.0-dev.1+test [tpl-codex]"), Files.readString(config));

        login(Map.of("ISX_VERSION", "0.9.1", "ISX_CONTAINER", "my-project"));
        assertEquals(original.replace("dev [container]", "0.9.1 [my-project]"), Files.readString(config));
    }

    @Test
    void unchangedLoginDoesNotRewriteConfig() throws Exception {
        var config = writeConfig(CodexSetup.configToml(Map.of()));
        login(Map.of("ISX_VERSION", "1.0", "ISX_CONTAINER", "project"));
        var before = Files.readString(config);
        var modified = Files.getLastModifiedTime(config);
        var shims = Files.createDirectories(home.resolve("shims"));
        var sed = shims.resolve("sed");
        Files.writeString(sed, "#!/bin/sh\ntouch \"$HOME/sed-ran\"\n");
        assertTrue(sed.toFile().setExecutable(true));

        login(Map.of("ISX_VERSION", "1.0", "ISX_CONTAINER", "project",
                "PATH", shims + ":" + System.getenv("PATH")));

        assertEquals(before, Files.readString(config));
        assertEquals(modified, Files.getLastModifiedTime(config));
        assertFalse(Files.exists(home.resolve("sed-ran")));
    }

    @Test
    void loginRenamesStaleTablesEvenWhenTheSelectionIsCurrent() throws Exception {
        var current = "default_permissions = \"Running unconstrained in isx 1.0 [project]\"";
        var original = CodexSetup.configToml(Map.of())
                .replace("default_permissions = \"Running unconstrained in isx dev [container]\"", current);
        var config = writeConfig(original);

        login(Map.of("ISX_VERSION", "1.0", "ISX_CONTAINER", "project"));

        assertEquals(original.replace("dev [container]", "1.0 [project]"), Files.readString(config));
    }

    @Test
    void loginPreservesCustomFooterAndSelectedPermissions() throws Exception {
        var original = CodexSetup.configToml(Map.of())
                .replace("default_permissions = \"Running unconstrained in isx dev [container]\"",
                        "default_permissions = \":read-only\"")
                .replace("status_line = [\"model-with-reasoning\", \"current-dir\", \"permissions\"]", "status_line = [\"model\", \"permissions\", \"git-branch\"]");
        assertTrue(original.contains("default_permissions = \":read-only\"") && original.contains("\"git-branch\""),
                "the fixture no longer matches the generated config");
        var config = writeConfig(original);

        login(Map.of("ISX_VERSION", "1.0", "ISX_CONTAINER", "project"));

        assertEquals(original.replace("dev [container]", "1.0 [project]"), Files.readString(config));
    }

    @Test
    void missingIdentifiersUseTheFallbackWithoutLeakingShellVariables() throws Exception {
        var original = CodexSetup.configToml(Map.of()).replace("dev [container]", "1.0 [parent]");
        var config = writeConfig(original);

        assertEquals("", GuestShell.login(home, CodexSetup.LOGIN_FOOTER_SCRIPT, Map.of(),
                "set | sed -n '/^isx_codex_/p'"));

        assertEquals(original.replace("1.0 [parent]", "dev [container]"), Files.readString(config));
    }

    @Test
    void unsafeIdentifiersAndUserOwnedConfigurationsAreLeftAlone() throws Exception {
        var original = CodexSetup.configToml(Map.of());
        var config = writeConfig(original);
        var marker = home.resolve("marker");
        for (var value : new String[]{"bad\"name", "bad&name", "bad/name", "bad\nname", "$(touch " + marker + ")"}) {
            login(Map.of("ISX_VERSION", value, "ISX_CONTAINER", "project"));
            login(Map.of("ISX_VERSION", "1.0", "ISX_CONTAINER", value));
            assertEquals(original, Files.readString(config));
        }
        assertFalse(Files.exists(marker));

        Files.writeString(config, "sandbox_mode = \"read-only\"\n");
        login(Map.of("ISX_VERSION", "1.0", "ISX_CONTAINER", "project"));
        assertEquals("sandbox_mode = \"read-only\"\n", Files.readString(config));
        Files.delete(config);
        login(Map.of());
        assertFalse(Files.exists(config));
    }

    private Path writeConfig(String content) throws Exception {
        return Files.writeString(Files.createDirectories(home.resolve(".codex")).resolve("config.toml"), content);
    }

    private void login(Map<String, String> env) throws Exception {
        assertEquals("", GuestShell.login(home, CodexSetup.LOGIN_FOOTER_SCRIPT, env, "true"));
    }
}
