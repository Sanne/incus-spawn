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
        assertEquals("bash -lc 'agy ${ISX_AGY_MODEL:+--model \"$ISX_AGY_MODEL\"} ${ISX_AGY_EFFORT:+--effort \"$ISX_AGY_EFFORT\"}'",
                tool.actions().getFirst().getCommand());
    }

    /** The template's model and effort travel as login variables, which the action and the runner read (#2474). */
    @Test
    void itExportsTheTemplatesModelAndEffortAsDeclaredParameters() {
        var tool = new AntigravitySetup();
        assertEquals(List.of("model", "effort"), List.copyOf(tool.parameters().keySet()));
        assertTrue(tool.parameters().values().stream().allMatch(p -> p.isOptional() && p.isReconfigurable()));
        var env = tool.envEntries(Map.of("model", "gemini-4-argon", "effort", "high"));
        assertEquals("gemini-4-argon", env.stream().filter(e -> e.getName().equals("ISX_AGY_MODEL")).findFirst().orElseThrow().getValue());
        assertEquals("high", env.stream().filter(e -> e.getName().equals("ISX_AGY_EFFORT")).findFirst().orElseThrow().getValue());
    }

    /** agy installs the CLIs it recommends itself, as a transitive tool (#2451). */
    @Test
    void itRequiresTheAgentCliToolsWhichInstallEveryCommandItNames() {
        var loader = new ToolDefLoader();
        assertEquals(List.of("agent-cli-tools"), loader.allToolSetups().get("antigravity").requires());
        var cli = loader.allToolSetups().get("agent-cli-tools");
        assertNotNull(cli);
        assertTrue(cli.packages().containsAll(List.of("ripgrep", "fd-find", "jq", "yq", "tree", "difftastic")));
        assertTrue(cli.verifyCommand(Map.of()).contains("ast-grep"));
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

    /** A Google account leaves agy to sign in: no {@code modelProvider}, whatever the user's own said (#2443). */
    @Test
    void aGoogleAccountDropsTheApiKeyModeFromTheSettings() {
        var google = (JqObject) JqValues.parseStrict(AntigravitySetup.settingsJson("", true));
        assertFalse(google.keys().contains("modelProvider"), google.toJsonString());
        assertEquals("always-proceed", google.getField("toolPermission").asText());
        var mine = "{\"colorScheme\": \"dark\", \"modelProvider\": \"gemini\"}";
        var merged = (JqObject) JqValues.parseStrict(AntigravitySetup.settingsJson(mine, true));
        assertFalse(merged.keys().contains("modelProvider"), "agy exits on modelProvider gemini with no key");
        assertEquals("dark", merged.getField("colorScheme").asText());
        assertEquals("gemini", JqValues.parseStrict(AntigravitySetup.settingsJson(AntigravitySetup.settingsJson("", true), false))
                .getField("modelProvider").asText(), "a move back to an API key account puts it back");
    }

    /** The build derives the mode from the account, and can re-derive it on a built instance. */
    @Test
    void theBakedIdentityIsTheModeNotTheAccount() throws Exception {
        var config = new dev.incusspawn.config.SpawnConfig();
        dev.incusspawn.config.NamespaceAccounts.put(config, "gemini", "home", "type", "google");
        dev.incusspawn.config.NamespaceAccounts.put(config, "gemini", "work", "apiKey", "gemini-k1");
        dev.incusspawn.config.NamespaceAccounts.put(config, "gemini", "other", "apiKey", "gemini-k2");
        var tool = new AntigravitySetup();
        assertEquals("google", tool.bakedAccountIdentity(config, "home"));
        assertEquals("api-key", tool.bakedAccountIdentity(config, "work"));
        assertEquals(tool.bakedAccountIdentity(config, "work"), tool.bakedAccountIdentity(config, "other"),
                "two key accounts leave the image the same");
        assertEquals("", tool.bakedAccountIdentity(config, "missing"));
        assertEquals("", tool.bakedAccountIdentity(config, ""));
        assertTrue(tool.canRebakeForAccount(), "settings and the login script are read when agy starts");
    }

    /**
     * The login script seeds agy's token file from the proof in {@code GEMINI_API_KEY}, brings an
     * isx look-alike of another digest to this login's, and leaves a real token alone.
     */
    @Test
    void theLoginScriptDerivesTheTokenFileFromTheProof(@TempDir Path home) throws Exception {
        var digest = "0123456789abcdef0123456789abcdef";
        var token = home.resolve(".gemini/antigravity-cli/antigravity-oauth-token");

        assertEquals(0, login(home, "gemini-isx_" + digest), "a home agy is not configured in gets no file");
        assertFalse(Files.exists(token), "root's login shell leaves no stray file");
        Files.createDirectories(token.getParent());
        assertEquals(0, login(home, "gemini-isx_" + digest), "seeded");
        var seeded = JqValues.parseStrict(Files.readString(token));
        assertEquals("ya29.isx_" + digest, seeded.getField("token").pathText("access_token", ""));
        assertEquals("1//isx_" + digest, seeded.getField("token").pathText("refresh_token", ""));
        assertEquals("1970-01-01T00:00:00Z", seeded.getField("token").pathText("expiry", ""), "expired: agy refreshes first");
        assertEquals("consumer", seeded.pathText("auth_method", ""));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(token)));

        var other = "fedcba9876543210fedcba9876543210";
        Files.writeString(token, "{\"token\":{\"access_token\":\"ya29.isx_" + other + "\",\"refresh_token\":\"1//isx_" + other
                + "\",\"expiry\":\"2030-01-01T00:00:00Z\"},\"id_token_email\":\"me@example.invalid\"}");
        assertEquals(0, login(home, "gemini-isx_" + digest), "patched");
        var patched = Files.readString(token);
        assertFalse(patched.contains(other), patched);
        assertTrue(patched.contains("ya29.isx_" + digest) && patched.contains("1//isx_" + digest), patched);
        assertTrue(patched.contains("2030-01-01T00:00:00Z") && patched.contains("me@example.invalid"), "the rest is kept: " + patched);
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(token)),
                "the copy the edit makes is owner-only too");
        assertFalse(Files.exists(home.resolve(".gemini/antigravity-cli/antigravity-oauth-token.isx-new")), "no temp file left");

        var real = "{\"token\":{\"access_token\":\"ya29.a-real-token\",\"refresh_token\":\"1//a-real-token\"}}";
        Files.writeString(token, real);
        assertEquals(0, login(home, "gemini-isx_" + digest), "left alone");
        assertEquals(real, Files.readString(token));

        Files.delete(token);
        assertEquals(0, login(home, "gemini-placeholder"), "a static placeholder seeds nothing");
        assertFalse(Files.exists(token));
        assertEquals(0, login(home, "gemini-isx_$(touch " + home.resolve("pwned") + ")"), "an unhex digest runs nothing");
        assertFalse(Files.exists(token));
        assertFalse(Files.exists(home.resolve("pwned")));
    }

    private static int login(Path home, String geminiApiKey) throws Exception {
        var process = new ProcessBuilder("sh", "-c", AntigravitySetup.LOGIN_SCRIPT).redirectErrorStream(true);
        process.environment().put("HOME", home.toString());
        process.environment().put("GEMINI_API_KEY", geminiApiKey);
        var started = process.start();
        var output = new String(started.getInputStream().readAllBytes());
        assertTrue(started.waitFor(30, TimeUnit.SECONDS));
        assertEquals("", output, output);
        return started.exitValue();
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

    /**
     * agy reads global skills only from {@code ~/.gemini/config/skills}, never {@code ~/.agents/skills},
     * so the skills isx installs reach it through a link (#2472).
     */
    @Test
    void itsSkillsDirectoryIsALinkToTheSharedOne(@TempDir Path home) throws Exception {
        var shared = home.resolve(".agents/skills");
        var link = home.resolve(".gemini/config/skills");
        Files.createDirectories(link.getParent());

        runLinkScript(shared, link);
        assertEquals(shared, Files.readSymbolicLink(link), "a fresh home gets the link");
        assertTrue(Files.isDirectory(shared), "and the directory it names, so agy can write a skill through it");

        Files.createDirectories(shared.resolve("demo"));
        Files.writeString(shared.resolve("demo/SKILL.md"), "isx's");
        runLinkScript(shared, link);
        assertEquals(shared, Files.readSymbolicLink(link), "a second run leaves the link as it is");
        assertEquals("isx's", Files.readString(link.resolve("demo/SKILL.md")));

        Files.delete(link);
        Files.createDirectories(link.resolve("mine"));
        Files.writeString(link.resolve("mine/SKILL.md"), "the user's");
        Files.createDirectories(link.resolve("demo"));
        Files.writeString(link.resolve("demo/SKILL.md"), "agy's");
        Files.writeString(link.resolve("demo/helper.sh"), "agy's");
        runLinkScript(shared, link);
        assertEquals(shared, Files.readSymbolicLink(link), "a real directory is replaced by the link");
        assertEquals("the user's", Files.readString(shared.resolve("mine/SKILL.md")), "and its skills are kept");
        assertEquals("isx's", Files.readString(shared.resolve("demo/SKILL.md")), "isx's skill of the same name wins");
        assertFalse(Files.exists(shared.resolve("demo/helper.sh")), "and keeps no file of the other");

        Files.delete(link);
        Files.createSymbolicLink(link, home.resolve("elsewhere"));
        runLinkScript(shared, link);
        assertEquals(shared, Files.readSymbolicLink(link), "a link elsewhere is replaced");
    }

    private static void runLinkScript(Path shared, Path link) throws Exception {
        var process = new ProcessBuilder("sh", "-c", AntigravitySetup.linkSkillsScript(shared.toString(), link.toString()))
                .redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
    }

    /** The build runs it as agentuser, in the exec that already creates agy's directories. */
    @Test
    void theDefaultLinkIsAgysGlobalSkillsDirectory() {
        assertEquals(AntigravitySetup.linkSkillsScript("/home/agentuser/.agents/skills", "/home/agentuser/.gemini/config/skills"),
                AntigravitySetup.linkSkillsScript());
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
