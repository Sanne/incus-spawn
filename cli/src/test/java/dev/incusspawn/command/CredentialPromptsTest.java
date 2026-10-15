package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.command.IsolatedHome.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The rest of {@code isx init}'s interactive surface: every YAML-declared tool's credential
 * prompts, the menu choosing which credentials to configure, and the clone-path prompt.
 */
@ExtendWith(IsolatedHome.class)
class CredentialPromptsTest {

    /**
     * A YAML tool with one secret, one plain value and one yes/no, in declaration order.
     *
     * <p>Written where a user's own tools live and loaded back through {@link ToolDefLoader}, as
     * production does: the account menu finds a namespace's {@code AccountShape} by discovering
     * its tool, so a tool built only in memory would have no accounts to offer.
     */
    private static ToolSetup acme() {
        try {
            var dir = Files.createDirectories(Environment.configDir().resolve("tools"));
            Files.writeString(dir.resolve("acme.yaml"), """
                    name: acme
                    description: acme credentials
                    proxy:
                      config-namespace: acme
                      configuration:
                        api-key:
                          config-path: "apiKey"
                          description: Acme API key
                          secret: true
                        region:
                          config-path: "region"
                          description: Acme region
                        telemetry:
                          config-path: "telemetry"
                          type: confirm
                          description: Send telemetry
                    """);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return new ToolDefLoader().find("acme");
    }

    private static ToolSetup tool(String name, Map<String, ToolDef.ConfigEntry> configuration) {
        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace(name);
        proxy.setConfiguration(configuration);
        var def = new ToolDef();
        def.setName(name);
        def.setDescription(name + " credentials");
        def.setProxy(proxy);
        return new YamlToolSetup(def);
    }

    private static ToolSetup tokenTool(String name) {
        var token = new ToolDef.ConfigEntry();
        token.setConfigPath("token");
        token.setSecret(true);
        return tool(name, Map.of("token", token));
    }

    /** acme configured by an older isx: one flat credential, as most re-runs start. */
    private static final String KEY_AND_REGION = """
            acme:
              apiKey: "acme-existing-secret"
              region: "us-east"
            """;

    /** The same, with the shared telemetry decision already made (so it is only offered for keeping). */
    private static final String KEY_AND_TELEMETRY = """
            acme:
              apiKey: "acme-existing-secret"
              telemetry: "true"
            """;

    private static final String KEY_REGION_AND_TELEMETRY = """
            acme:
              apiKey: "acme-existing-secret"
              region: "us-east"
              telemetry: "true"
            """;

    private static final String TWO_ACCOUNTS = """
            acme:
              accounts:
                default:
                  apiKey: "acme-default-secret"
                work:
                  apiKey: "acme-work-secret"
                  region: "us-east"
              default: "default"
              telemetry: "true"
            """;

    private static void configure(SpawnConfig config, ScriptedPrompts prompts) {
        new CredentialSetup().setupGenericToolCredentials("acme", acme(), config, prompts);
        prompts.assertFullyConsumed();
    }

    @Test
    void geminiUsesTheHostCredentialMenuAndPreservesNamedAccounts() {
        var setup = new CredentialSetup();
        var tool = new ToolDefLoader().allToolSetups().get("gemini-cli");
        var first = new ScriptedPrompts().secret("gemini-test-default");
        setup.setupGenericToolCredentials("gemini-cli", tool, new SpawnConfig(), first);
        first.assertFullyConsumed();
        var second = new ScriptedPrompts().line("a", "work").secret("gemini-test-work");
        setup.setupGenericToolCredentials("gemini-cli", tool, SpawnConfig.load(), second);
        second.assertFullyConsumed();

        assertEquals("gemini-test-default", saved("gemini.accounts.default.apiKey"));
        assertEquals("gemini-test-work", saved("gemini.accounts.work.apiKey"));
        assertEquals("default", saved("gemini.default"));
        assertEquals("", saved("gemini.apiKey"));
    }

    // ── YAML-declared tool credentials ───────────────────────────────────────────

    /**
     * What the user supplies belongs to one identity and lands in {@code accounts.default};
     * a {@code confirm} entry records a decision about the tool and is shared by every account
     * ({@code AccountShape.declaredBy}).
     */
    @Test
    void suppliedValuesArePerAccountAndConfirmationsShared() {
        configure(new SpawnConfig(), new ScriptedPrompts().secret("acme-secret").line("eu-west", "y"));

        assertEquals("acme-secret", saved("acme.accounts.default.apiKey"));
        assertEquals("eu-west", saved("acme.accounts.default.region"));
        assertEquals("", saved("acme.apiKey"));
        assertEquals("true", saved("acme.telemetry"));
    }

    @Test
    void skippedPromptsLeaveNoEmptyCredentialBehind() throws Exception {
        configure(new SpawnConfig(), new ScriptedPrompts().secret("").line("", ""));

        // Answering the confirm (No) is a value; skipping the others must not become "".
        assertEquals("false", saved("acme.telemetry"));
        var acme = SpawnConfig.load().tree().getField("acme");
        assertTrue(acme instanceof io.hyperfoil.tools.jjq.value.JqObject acmeObj
                && acmeObj.size() == 1 && acmeObj.keys().contains("telemetry"), acme.toString());
    }

    @Test
    void closedStdinSavesNoCredential() {
        new CredentialSetup().setupGenericToolCredentials("acme", acme(), new SpawnConfig(), new ScriptedPrompts());
        assertEquals("", saved("acme.accounts.default.apiKey"));
        assertEquals("", saved("acme.region"));
    }

    /** A configured tool opens on the account menu, whose Enter leaves everything as it is. */
    @Test
    void enterOnReRunLeavesEverythingAsItIs() throws Exception {
        configure(seed(KEY_AND_REGION), ScriptedPrompts.lines(""));
        assertUnchanged(KEY_AND_REGION);
    }

    /** EOF at "keep current?" keeps it: running out of input must never clear a credential. */
    @Test
    void closedStdinKeepsExistingValues() throws Exception {
        new CredentialSetup().setupGenericToolCredentials("acme", acme(), seed(KEY_AND_REGION), new ScriptedPrompts());
        assertUnchanged(KEY_AND_REGION);
    }

    /**
     * 'r' starts the account afresh: its own values are asked for again rather than offered for
     * keeping, while a shared confirmation still gets "keep current?", which Enter keeps.
     */
    @Test
    void replacingStartsTheAccountAfreshAndKeepsSharedConfirmations() throws Exception {
        configure(seed(KEY_REGION_AND_TELEMETRY), new ScriptedPrompts().line("r").secret("acme-new-secret").line("ap-south", ""));

        assertEquals("acme-new-secret", saved("acme.accounts.default.apiKey"));
        assertEquals("ap-south", saved("acme.accounts.default.region"));
        assertEquals("", saved("acme.apiKey"), "the replaced flat secret must not linger");
        assertEquals("", saved("acme.region"));
        assertEquals("true", saved("acme.telemetry"));
    }

    /** Choosing 'r' and then skipping every prompt must not clear what is there. */
    @Test
    void replacingWithNothingKeepsTheOldSecret() throws Exception {
        configure(seed(KEY_AND_TELEMETRY), new ScriptedPrompts().line("r").secret("").line("", ""));
        assertUnchanged(KEY_AND_TELEMETRY);
    }

    /**
     * Replacing, then skipping the secret but answering another prompt, must not replace a
     * working credential with an account that has none: "replace all" clears every account, so
     * it may only run once there is a credential to replace them with.
     */
    @Test
    void replacingWithoutANewSecretKeepsTheWorkingCredential() throws Exception {
        configure(seed(KEY_AND_TELEMETRY), new ScriptedPrompts().line("r").secret("").line("eu-west", ""));
        assertUnchanged(KEY_AND_TELEMETRY);
    }

    /** Likewise a new account: a name and a region, but no secret, is not an account. */
    @Test
    void addingAnAccountWithoutASecretCreatesNothing() throws Exception {
        configure(seed(KEY_AND_TELEMETRY), new ScriptedPrompts().line("a", "work").secret("").line("eu-west", ""));
        assertUnchanged(KEY_AND_TELEMETRY);
    }

    /** Editing an existing account may change its other values while keeping its secret. */
    @Test
    void anExistingAccountsOtherValuesCanBeChangedAlone() throws Exception {
        // 'e' work; decline keeping the secret, then skip it; replace the region; keep telemetry.
        configure(seed(TWO_ACCOUNTS), new ScriptedPrompts().line("e", "work", "n").secret("").line("n", "eu-west", ""));

        assertEquals("acme-work-secret", saved("acme.accounts.work.apiKey"));
        assertEquals("eu-west", saved("acme.accounts.work.region"));
    }

    /** YAML tools get named accounts through the same menu as GitHub, keeping the first. */
    @Test
    void aSecondAccountCanBeAddedForAYamlTool() throws Exception {
        configure(seed(KEY_AND_REGION), new ScriptedPrompts().line("a", "work").secret("acme-work-secret").line("", ""));

        assertEquals("acme-existing-secret", saved("acme.accounts.default.apiKey"));
        assertEquals("us-east", saved("acme.accounts.default.region"), "the first account keeps its region");
        assertEquals("acme-work-secret", saved("acme.accounts.work.apiKey"));
        assertEquals("", saved("acme.accounts.work.region"), "a new account starts empty");
        assertEquals("default", saved("acme.default"), "adding an account must not move the default");
    }

    @Test
    void abandoningASecondYamlToolAccountLeavesTheCredentialAlone() throws Exception {
        configure(seed(KEY_AND_TELEMETRY), new ScriptedPrompts().line("a", "work").secret("").line("", ""));
        assertUnchanged(KEY_AND_TELEMETRY);
    }

    // ── choosing which credentials to configure ──────────────────────────────────

    /** Listed as: 1. bob (known order first), 2. acme, 3. zeta. */
    private static List<String> select(String... input) {
        var tools = new LinkedHashMap<String, ToolSetup>();
        tools.put("zeta", tokenTool("zeta"));
        tools.put("acme", tokenTool("acme"));
        tools.put("bob", tokenTool("bob"));
        var prompts = ScriptedPrompts.lines(input);
        var selected = new CredentialSetup().selectCredentials(tools, new SpawnConfig(), prompts);
        prompts.assertFullyConsumed();
        return selected;
    }

    @Test
    void numbersSelectInListedOrder() {
        assertEquals(List.of("bob", "zeta"), select("1,3"));
    }

    @Test
    void allSelectsEverything() {
        assertEquals(List.of("bob", "acme", "zeta"), select("ALL"));
    }

    @Test
    void enterSelectsNothing() {
        assertEquals(List.of(), select(""));
    }

    @Test
    void duplicatesAndNonsenseAreIgnored() {
        assertEquals(List.of("acme"), select(" 2 , 2, 9, x, 0"));
    }

    @Test
    void closedStdinSelectsNothing() {
        var tools = Map.of("acme", tokenTool("acme"));
        assertEquals(List.of(), new CredentialSetup().selectCredentials(tools, new SpawnConfig(), new ScriptedPrompts()));
    }

    // ── text a tool definition supplies ──────────────────────────────────────────

    /**
     * Text a cloned repository can ship in {@code .incus-spawn/tools/}: an ESC sequence, an 8-bit
     * CSI, a line break and a bidi override, in every string init prints from a definition.
     */
    private static final String HOSTILE = "a\u001b]0;pwned\u0007\u009b2J\nfake: prompt\u202Egnp.exe";

    private static ToolSetup hostileTool() {
        var key = new ToolDef.ConfigEntry();
        key.setConfigPath("apiKey");
        key.setSecret(true);
        key.setDescription("key " + HOSTILE);
        key.setHelp(List.of("help " + HOSTILE));
        var region = new ToolDef.ConfigEntry();
        region.setConfigPath("region");
        region.setDescription("region " + HOSTILE);
        var consent = new ToolDef.ConfigEntry();
        consent.setConfigPath("consent");
        consent.setType("confirm");
        consent.setDescription("consent " + HOSTILE);
        var unlabelled = new ToolDef.ConfigEntry();
        unlabelled.setConfigPath("zone");
        unlabelled.setDescription("\u001b\n");
        var configuration = new LinkedHashMap<String, ToolDef.ConfigEntry>();
        configuration.put("key", key);
        configuration.put("region", region);
        configuration.put("consent", consent);
        configuration.put("zone " + HOSTILE, unlabelled);
        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace("evil");
        proxy.setConfiguration(configuration);
        var def = new ToolDef();
        def.setName("evil");
        def.setDescription("tool " + HOSTILE);
        def.setProxy(proxy);
        return new YamlToolSetup(def);
    }

    /**
     * Init prints a definition's description, labels and help one line each, with nothing a
     * terminal acts on: the step title, the credentials menu, every prompt and the closing line
     * (#1222). A description with nothing printable falls back to the entry's key.
     */
    @Test
    void definitionTextCannotDriveTheTerminal() {
        var tools = Map.of("evil", hostileTool());
        var out = captureStdout(() -> {
            var prompts = ScriptedPrompts.lines("1").secret("s3cret").line("").line("n").line("");
            var setup = new CredentialSetup();
            assertEquals(List.of("evil"), setup.selectCredentials(tools, new SpawnConfig(), prompts));
            setup.setupGenericToolCredentials("evil", tools.get("evil"), new SpawnConfig(), prompts);
            prompts.assertFullyConsumed();
        });

        for (var shown : List.of("tool ", "key ", "help ", "region ", "consent ", "zone ")) {
            assertTrue(out.contains(shown + "a ]0;pwned  2J fake: prompt gnp.exe"), shown + " in:\n" + out);
        }
        assertTrue(out.contains("tool a ]0;pwned  2J fake: prompt gnp.exe credentials saved."), out);
        out.codePoints().filter(c -> c != '\n').forEach(c -> assertFalse(
                Character.isISOControl(c) || c >= 0x2028 && c <= 0x202E || c >= 0x2066 && c <= 0x2069,
                () -> "U+%04X printed raw:%n%s".formatted(c, out)));
    }

    private static String captureStdout(Runnable body) {
        var original = System.out;
        var buffer = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── where to clone the templates repo ────────────────────────────────────────

    private static String clonePath(String... input) {
        var prompts = ScriptedPrompts.lines(input);
        var path = InitCommand.askClonePath(prompts, "/default");
        prompts.assertFullyConsumed();
        return path;
    }

    @Test
    void clonePathDefaultsOnEnterOrYes() {
        assertEquals("/default", clonePath(""));
        assertEquals("/default", clonePath("Y"));
    }

    @Test
    void clonePathNoDeclines() {
        assertNull(clonePath("n"));
    }

    @Test
    void clonePathAcceptsAPathDirectlyOrAfterAsking() {
        var home = System.getProperty("user.home");
        assertEquals("/srv/templates", clonePath("/srv/templates"));
        assertEquals(home + "/templates", clonePath("~/templates"));
        assertEquals(home + "/templates", clonePath("path", "~/templates"));
        assertEquals("/default", clonePath("path", ""));
    }
}
