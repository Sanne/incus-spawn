package dev.incusspawn.tool;

import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Antigravity CLI in its Gemini API key mode (#2366). */
class AntigravitySetupTest {

    @Test
    void itSpendsGeminiClisAccountThroughThePlaceholderGeminiCliDeclares() {
        var tool = new ToolDefLoader().allToolSetups().get("antigravity");
        assertInstanceOf(AntigravitySetup.class, tool);
        assertTrue(tool.codingAgent());
        assertEquals(Set.of("gemini"), tool.credentialNamespaces());
        assertNull(tool.proxy(), "no credential of its own");
        assertEquals(List.of(), tool.placeholders(), "GEMINI_API_KEY is Gemini CLI's declaration");
        assertTrue(new GeminiSetup().placeholders().stream().anyMatch(p -> p.env().equals("GEMINI_API_KEY")
                && p.namespace().equals("gemini")));

        var env = tool.envEntries(Map.of());
        assertEquals(List.of("GEMINI_API_KEY", "AGY_CLI_DISABLE_AUTO_UPDATE"), env.stream().map(e -> e.getName()).toList());
        assertEquals(GeminiSetup.PLACEHOLDER_API_KEY, env.get(0).getValue());
        assertEquals("true", env.get(1).getValue(), "the pin is what runs");
        assertEquals("bash -lc agy", tool.actions().getFirst().getCommand());
    }

    @Test
    void settingsSelectTheApiKeyModeAndKeepTheUsersOwn() {
        var fresh = JqValues.parseStrict(AntigravitySetup.settingsJson(""));
        assertEquals("gemini", fresh.getField("modelProvider").asText());
        assertEquals("always-proceed", fresh.getField("toolPermission").asText());
        assertEquals("always-proceed", fresh.getField("artifactReviewPolicy").asText());
        assertEquals(JqBoolean.TRUE, fresh.getField("allowNonWorkspaceAccess"));
        assertEquals(JqBoolean.FALSE, fresh.getField("enableTerminalSandbox"));
        assertEquals(JqBoolean.FALSE, fresh.getField("showTips"));
        assertEquals("[\"/home/agentuser\"]", fresh.getField("trustedWorkspaces").toJsonString());
        assertFalse(AntigravitySetup.settingsJson("").contains("placeholder"), "no credential in its settings");

        var mine = """
                {"colorScheme": "dark", "modelProvider": "", "trustedWorkspaces": ["/home/agentuser/app"]}
                """;
        var merged = (JqObject) JqValues.parseStrict(AntigravitySetup.settingsJson(mine));
        assertEquals("dark", merged.getField("colorScheme").asText());
        assertEquals("gemini", merged.getField("modelProvider").asText());
        assertEquals("[\"/home/agentuser/app\",\"/home/agentuser\"]", merged.getField("trustedWorkspaces").toJsonString());
        assertEquals(AntigravitySetup.settingsJson(mine),
                AntigravitySetup.settingsJson(AntigravitySetup.settingsJson(mine)), "a second build changes nothing");
        assertThrows(IllegalStateException.class, () -> AntigravitySetup.settingsJson("[]"));
    }

    @Test
    void eachArchitectureHasItsPinnedTarball() {
        for (var arch : List.of("x86_64", "amd64", "aarch64", "arm64")) {
            var download = AntigravitySetup.download(arch);
            assertTrue(download.url().startsWith("https://storage.googleapis.com/antigravity-public/antigravity-cli/"
                    + AntigravitySetup.VERSION + "-"), download.url());
            assertTrue(download.sha256().matches("[0-9a-f]{64}"), download.sha256());
        }
        assertNotEquals(AntigravitySetup.download("x86_64"), AntigravitySetup.download("aarch64"));
        assertThrows(IllegalStateException.class, () -> AntigravitySetup.download("riscv64"));
    }

    /** The script unpacks only the binary, from the tarball's single entry, and links it as agy. */
    @Test
    void theInstallScriptPutsAgyOnThePath(@TempDir Path dir) throws Exception {
        var staged = Files.createDirectories(dir.resolve("staged"));
        Files.writeString(staged.resolve("antigravity"), "#!/bin/sh\necho agy\n");
        var tarball = dir.resolve("cli.tar.gz");
        assertEquals(0, new ProcessBuilder("tar", "-czf", tarball.toString(), "-C", staged.toString(), "antigravity")
                .inheritIO().start().waitFor());
        var script = AntigravitySetup.installScript(dir.resolve("opt/1.0").toString(), tarball.toString())
                .replace(AntigravitySetup.COMMAND, dir.resolve("agy").toString());

        var process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);

        assertTrue(Files.isExecutable(dir.resolve("agy")));
        assertEquals(dir.resolve("opt/1.0/antigravity"), Files.readSymbolicLink(dir.resolve("agy")));
        assertFalse(Files.exists(tarball), "the tarball is removed");
    }

    /** Its rule directory loads a file only with front matter, before the heading isx recognises. */
    @Test
    void theInstructionsAreAnAlwaysOnRule() {
        var tool = new AntigravitySetup();
        assertEquals("/home/agentuser/.gemini/config/rules/isx.md", tool.environmentContextPath());
        assertEquals("---\ntrigger: always_on\n---\n# incus-spawn environment\nv1\n",
                tool.renderEnvironmentContext("# incus-spawn environment\nv1\n"));
    }
}
