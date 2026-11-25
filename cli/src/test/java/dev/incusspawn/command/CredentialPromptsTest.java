package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
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

    /** Every declared check of a pasted key answers that it verified, with no network. */
    static class Checked extends CredentialSetup {
        @Override
        AuthResult checkSecret(dev.incusspawn.tool.SecretCheck.Request check) {
            return new AuthResult(true, "Verified.");
        }
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
        new Checked().setupGenericToolCredentials("acme", acme(), config, prompts);
        prompts.assertFullyConsumed();
    }

    @Test
    void geminiUsesTheHostCredentialMenuAndPreservesNamedAccounts() {
        var setup = new Checked();
        var tool = new ToolDefLoader().allToolSetups().get("gemini-cli");
        // Gemini first asks which kind of account this is (#2443), with no default (#2528)
        var first = new ScriptedPrompts().line("1").secret("gemini-test-default");
        setup.setupGenericToolCredentials("gemini-cli", tool, new SpawnConfig(), first);
        first.assertFullyConsumed();
        var second = new ScriptedPrompts().line("a", "work", "1").secret("gemini-test-work");
        setup.setupGenericToolCredentials("gemini-cli", tool, SpawnConfig.load(), second);
        second.assertFullyConsumed();

        assertEquals("gemini-test-default", saved("gemini.accounts.default.apiKey"));
        assertEquals("gemini-test-work", saved("gemini.accounts.work.apiKey"));
        assertEquals("default", saved("gemini.default"));
        assertEquals("", saved("gemini.apiKey"));
    }

    /**
     * A tool's declared check runs on the pasted key before it is saved: Gemini's, which is
     * rejected once and asked for again, then saved as verified (#2566).
     */
    @Test
    void aToolsDeclaredCheckAsksAgainForARejectedKey() {
        var results = new java.util.ArrayDeque<>(List.of(
                new CredentialSetup.AuthResult(false, "generativelanguage.googleapis.com rejected it (HTTP 400)."),
                new CredentialSetup.AuthResult(true, "Verified.")));
        var checked = new java.util.ArrayList<dev.incusspawn.tool.SecretCheck.Request>();
        var setup = new CredentialSetup() {
            @Override
            AuthResult checkSecret(dev.incusspawn.tool.SecretCheck.Request check) {
                checked.add(check);
                return results.removeFirst();
            }
        };
        var tool = new ToolDefLoader().allToolSetups().get("gemini-cli");
        var prompts = new ScriptedPrompts().line("1").secret("gemini-mistyped").line("").secret("gemini-good");
        var printed = reported(() -> setup.setupGenericToolCredentials("gemini-cli", tool, new SpawnConfig(), prompts));
        prompts.assertFullyConsumed();

        assertEquals("gemini-good", saved("gemini.accounts.default.apiKey"));
        assertEquals(List.of("gemini-mistyped", "gemini-good"), checked.stream().map(c -> c.value()).toList());
        assertTrue(printed.contains("Gemini API key verified and saved on"), printed);
        assertFalse(printed.contains("not checked"), printed);
    }

    /** A key isx could not check is saved unverified only on the person's word, and said to be. */
    @Test
    void aToolsKeyThatCouldNotBeCheckedIsSavedUnverified() {
        var setup = new CredentialSetup() {
            @Override
            AuthResult checkSecret(dev.incusspawn.tool.SecretCheck.Request check) {
                return AuthResult.unchecked("isx could not reach generativelanguage.googleapis.com: timeout");
            }
        };
        var tool = new ToolDefLoader().allToolSetups().get("gemini-cli");
        var prompts = new ScriptedPrompts().line("1").secret("gemini-key").line("u");
        var printed = reported(() -> setup.setupGenericToolCredentials("gemini-cli", tool, new SpawnConfig(), prompts));
        prompts.assertFullyConsumed();

        assertEquals("gemini-key", saved("gemini.accounts.default.apiKey"));
        assertTrue(printed.contains("Gemini API key saved on " + dev.incusspawn.Platform.thisComputer() + ", unverified"), printed);
    }

    // ── YAML-declared tool credentials ───────────────────────────────────────────

    /**
     * What the user supplies belongs to one identity and lands in {@code accounts.default};
     * a {@code confirm} entry records a decision about the tool and is shared by every account
     * ({@code AccountShape.declaredBy}).
     */
    /** A Google account holds no credential here: the choice alone saves it, typed, and says how it signs in (#2443). */
    @Test
    void geminiOffersAGoogleAccountThatHoldsNoKey() {
        var setup = new Checked();
        var tool = new ToolDefLoader().allToolSetups().get("gemini-cli");
        var prompts = new ScriptedPrompts().line("2");
        var printed = reported(() -> setup.setupGenericToolCredentials("gemini-cli", tool, new SpawnConfig(), prompts));
        prompts.assertFullyConsumed();

        assertEquals("google", saved("gemini.accounts.default.type"));
        assertEquals("", saved("gemini.accounts.default.apiKey"));
        assertEquals("default", saved("gemini.default"));
        assertTrue(prompts.conversation().stream().anyMatch(l -> l.contains("Google account")), prompts.conversation().toString());
        assertTrue(printed.contains("The first time Antigravity CLI starts, it prints a"), printed);
        assertTrue(dev.incusspawn.config.AccountResolver.usableAccountNames(SpawnConfig.load(), "gemini").contains("default"));

        // A second, keyed account beside it, and the Google one keeps its type
        var second = new ScriptedPrompts().line("a", "work", "1").secret("gemini-test-work");
        setup.setupGenericToolCredentials("gemini-cli", tool, SpawnConfig.load(), second);
        second.assertFullyConsumed();
        assertEquals("gemini-test-work", saved("gemini.accounts.work.apiKey"));
        assertEquals("google", saved("gemini.accounts.default.type"));

        // Giving the Google account a key makes it a key account: the type that would ignore the key goes
        var keyed = new ScriptedPrompts().line("e", "default", "1").secret("gemini-test-default");
        setup.setupGenericToolCredentials("gemini-cli", tool, SpawnConfig.load(), keyed);
        keyed.assertFullyConsumed();
        assertEquals("gemini-test-default", saved("gemini.accounts.default.apiKey"));
        assertEquals("", saved("gemini.accounts.default.type"));

        // And back: a key account made a Google one keeps no key it no longer spends
        var hosted = new ScriptedPrompts().line("e", "default", "2");
        setup.setupGenericToolCredentials("gemini-cli", tool, SpawnConfig.load(), hosted);
        hosted.assertFullyConsumed();
        assertEquals("google", saved("gemini.accounts.default.type"));
        assertEquals("", saved("gemini.accounts.default.apiKey"));
    }

    /** A flat, pre-accounts key moved to a Google account leaves no key behind, flat or in the account (#2443). */
    @Test
    void aFlatGeminiKeyMovedToAGoogleAccountLeavesNoKey() throws Exception {
        var config = seed("gemini:\n  apiKey: gemini-flat-key\n");
        var tool = new ToolDefLoader().allToolSetups().get("gemini-cli");
        var prompts = new ScriptedPrompts().line("r", "2");
        new Checked().setupGenericToolCredentials("gemini-cli", tool, config, prompts);
        prompts.assertFullyConsumed();

        var saved = SpawnConfig.load();
        assertEquals("google", saved("gemini.accounts.default.type"));
        assertEquals("", saved("gemini.accounts.default.apiKey"));
        assertEquals("", saved("gemini.apiKey"));
        assertFalse(saved.tree().toJsonString().contains("gemini-flat-key"), "no key anywhere: " + saved.tree().toJsonString());
    }

    @Test
    void suppliedValuesArePerAccountAndConfirmationsShared() {
        configure(new SpawnConfig(), new ScriptedPrompts().secret("acme-secret").line("eu-west", "y"));

        assertEquals("acme-secret", saved("acme.accounts.default.apiKey"));
        assertEquals("eu-west", saved("acme.accounts.default.region"));
        assertEquals("", saved("acme.apiKey"));
        assertEquals("true", saved("acme.telemetry"));
    }

    @Test
    void skippedSecretAsksNoConsentAndLeavesNothingBehind() {
        // A skipped key skips the consent after it, never a plain setting, in 'isx account add' as in init
        var prompts = new ScriptedPrompts().secret("").line("");
        configure(new SpawnConfig(), prompts);
        prompts.assertFullyConsumed();
        assertTrue(prompts.questions().stream().anyMatch(q -> q.contains("Acme region")), prompts.questions().toString());
        assertTrue(prompts.questions().stream().noneMatch(q -> q.contains("telemetry")), prompts.questions().toString());
        assertNothingSaved();
    }

    @Test
    void closedStdinSavesNoCredential() {
        new Checked().setupGenericToolCredentials("acme", acme(), new SpawnConfig(), new ScriptedPrompts());
        assertEquals("", saved("acme.accounts.default.apiKey"));
        assertEquals("", saved("acme.region"));
    }

    /** A skipped step names itself to come back to, not the whole of init (#1469). */
    @Test
    void aSkippedStepNamesTheCommandThatRunsItAlone() throws Exception {
        var dir = Files.createDirectories(Environment.configDir().resolve("tools"));
        Files.writeString(dir.resolve("solo.yaml"), """
                name: solo
                proxy:
                  config-namespace: soloCloud
                  configuration:
                    key: { config-path: key, description: Solo key, secret: true }
                """);
        var printed = reported(() -> new Checked().setupGenericToolCredentials("solo",
                new ToolDefLoader().find("solo"), new SpawnConfig(), new ScriptedPrompts().secret("")));
        assertTrue(printed.contains("Add one later with 'isx account add soloCloud'."), printed);
    }

    /**
     * A key written by hand under a name isx does not read is named before the step asks, and
     * left as it is (#1733): otherwise the saved key looks like the step ignored the file.
     */
    @Test
    void aHandWrittenKeyIsxDoesNotReadIsNamedAndKept() throws Exception {
        var config = seed("openai:\n   token: sk-handwritten\n");
        var tool = new ToolDefLoader().allToolSetups().get("codex");
        var prompts = new ScriptedPrompts().secret("sk-pasted-1234567890abcdefghij");
        var recorded = RecordingOutput.during(() ->
                new Checked().setupGenericToolCredentials("codex", tool, config, prompts));
        prompts.assertFullyConsumed();

        var warnings = recorded.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warnings.size(), recorded.lines().toString());
        assertEquals("config.yaml has openai.token, which isx does not read;"
                + " it reads openai.apiKey or the same under openai.accounts.<name>.", warnings.getFirst().msg());
        assertEquals("sk-pasted-1234567890abcdefghij", saved("openai.accounts.default.apiKey"));
        assertEquals("sk-handwritten", saved("openai.token"));
    }

    /** A configured tool opens on the account menu, whose Enter leaves everything as it is. */
    @Test
    void enterOnReRunLeavesEverythingAsItIs() throws Exception {
        configure(seed(KEY_AND_REGION), ScriptedPrompts.lines(""));
        assertUnchanged(KEY_AND_REGION);
    }

    /** A key kept while another answer changes is not newly saved, so never said to be unchecked (#2528). */
    @Test
    void aKeptKeyIsNotSaidToBeSavedOrUnchecked() throws Exception {
        var config = seed(TWO_ACCOUNTS);
        var prompts = new ScriptedPrompts().line("e", "work", "", "n", "eu-west", "");
        var printed = reported(() -> new Checked().setupGenericToolCredentials("acme", acme(), config, prompts));
        prompts.assertFullyConsumed();

        assertEquals("acme-work-secret", saved("acme.accounts.work.apiKey"));
        assertEquals("eu-west", saved("acme.accounts.work.region"));
        assertFalse(printed.contains("not checked"), printed);
        assertTrue(printed.contains("Your credential stays as it was."), printed);
    }

    /** EOF at "keep current?" keeps it: running out of input must never clear a credential. */
    @Test
    void closedStdinKeepsExistingValues() throws Exception {
        new Checked().setupGenericToolCredentials("acme", acme(), seed(KEY_AND_REGION), new ScriptedPrompts());
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

    /** Choosing 'r' and then skipping the secret stops at once and keeps what is there. */
    @Test
    void replacingWithNothingKeepsTheOldSecret() throws Exception {
        configure(seed(KEY_AND_TELEMETRY), new ScriptedPrompts().line("r").secret("").line(""));
        assertUnchanged(KEY_AND_TELEMETRY);
    }

    /**
     * Replacing, then answering a plain prompt before skipping the secret (as Atlassian asks for
     * email before its token), must not replace a working credential with an account that has none.
     */
    @Test
    void replacingWithoutANewSecretKeepsTheWorkingCredential() throws Exception {
        var atlassian = new ToolDefLoader().allToolSetups().get("atlassian-mcp");
        var seeded = seed("atlassian:\n  email: \"old@example.com\"\n  apiToken: \"old-token\"\n");
        var prompts = new ScriptedPrompts().line("r", "new@example.com").secret("");
        new Checked().setupGenericToolCredentials("atlassian-mcp", atlassian, seeded, prompts);
        prompts.assertFullyConsumed();
        assertUnchanged("atlassian:\n  email: \"old@example.com\"\n  apiToken: \"old-token\"\n");
    }

    /** Likewise a new account: a name and an email, but no secret, is not an account. */
    @Test
    void addingAnAccountWithoutASecretCreatesNothing() throws Exception {
        var atlassian = new ToolDefLoader().allToolSetups().get("atlassian-mcp");
        var seeded = seed("atlassian:\n  email: \"old@example.com\"\n  apiToken: \"old-token\"\n");
        var prompts = new ScriptedPrompts().line("a", "work", "work@example.com").secret("");
        new Checked().setupGenericToolCredentials("atlassian-mcp", atlassian, seeded, prompts);
        prompts.assertFullyConsumed();
        assertUnchanged("atlassian:\n  email: \"old@example.com\"\n  apiToken: \"old-token\"\n");
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
        configure(seed(KEY_AND_TELEMETRY), new ScriptedPrompts().line("a", "work").secret("").line(""));
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
        var selected = new Checked().selectCredentials(tools, new SpawnConfig(), prompts);
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

    /** A feature-gated tool is offered only once its feature is enabled, as the proxy uses it only then (#1441). */
    @Test
    void aFeatureGatedToolIsOfferedOnlyWithItsFeature() {
        var tools = new LinkedHashMap<String, ToolSetup>();
        tools.put("acme", tokenTool("acme"));
        var gated = (YamlToolSetup) tokenTool("gated");
        gated.toolDef().setFeature("gated");
        tools.put("gated", gated);

        var prompts = ScriptedPrompts.lines("all");
        assertEquals(List.of("acme"), new Checked().selectCredentials(tools, new SpawnConfig(), prompts));
        assertFalse(String.join("\n", prompts.conversation()).contains("gated credentials"), prompts.conversation().toString());

        var enabled = new SpawnConfig();
        enabled.setFeatures(List.of("gated"));
        assertEquals(List.of("acme", "gated"),
                new Checked().selectCredentials(tools, enabled, ScriptedPrompts.lines("all")));
    }

    @Test
    void closedStdinSelectsNothing() {
        var tools = Map.of("acme", tokenTool("acme"));
        assertEquals(List.of(), new Checked().selectCredentials(tools, new SpawnConfig(), new ScriptedPrompts()));
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
        var out = reported(() -> {
            var prompts = ScriptedPrompts.lines("1").secret("s3cret").line("").line("n").line("");
            var setup = new Checked();
            assertEquals(List.of("evil"), setup.selectCredentials(tools, new SpawnConfig(), prompts));
            setup.setupGenericToolCredentials("evil", tools.get("evil"), new SpawnConfig(), prompts);
            prompts.assertFullyConsumed();
        });

        for (var shown : List.of("tool ", "key ", "help ", "region ", "consent ", "zone ")) {
            assertTrue(out.contains(shown + "a ]0;pwned  2J fake: prompt gnp.exe"), shown + " in:\n" + out);
        }
        assertTrue(out.contains("saved on " + dev.incusspawn.Platform.thisComputer()), out);
        out.codePoints().filter(c -> c != '\n').forEach(c -> assertFalse(
                Character.isISOControl(c) || c >= 0x2028 && c <= 0x202E || c >= 0x2066 && c <= 0x2069,
                () -> "U+%04X printed raw:%n%s".formatted(c, out)));
    }

    /** What the flow said and asked, in order, a line each: steps, warnings and questions alike. */
    private static String reported(Runnable body) {
        return String.join("\n", RecordingOutput.during(body::run).lines());
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

    private static dev.incusspawn.tool.ToolSetup servers() throws Exception {
        return new dev.incusspawn.tool.YamlToolSetup(dev.incusspawn.tool.ToolDef.loadFromStream(
                new java.io.ByteArrayInputStream(SERVERS.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    private static final String SERVERS = """
                name: develocity
                proxy:
                  config-namespace: develocity
                  configuration:
                    servers:
                      config-path: servers
                      description: "Develocity servers"
                      for-each:
                        host: { description: "Server host" }
                        access-key: { config-path: accessKey, description: "Access key", secret: true }
                  auth:
                    - for-each: servers
                      domains: ["${servers.host}"]
                      type: bearer
                      token: "${servers.access-key}"
                """;

    @Test
    void eachServerIsAskedForInTurnAndSavedInTheAccount() throws Exception {
        var prompts = new ScriptedPrompts()
                .line("quarkus", "ge.quarkus.io").secret("key-quarkus")
                .line("y", "Not A Label", "commonhaus", "develocity.commonhaus.dev").secret("key-commonhaus")
                .line("n");
        new Checked().setupGenericToolCredentials("develocity", servers(), new SpawnConfig(), prompts);
        prompts.assertFullyConsumed();

        assertEquals("ge.quarkus.io", saved("develocity.accounts.default.servers.quarkus.host"));
        assertEquals("key-quarkus", saved("develocity.accounts.default.servers.quarkus.accessKey"));
        assertEquals("develocity.commonhaus.dev", saved("develocity.accounts.default.servers.commonhaus.host"));
        assertEquals("key-commonhaus", saved("develocity.accounts.default.servers.commonhaus.accessKey"));
        assertEquals("", saved("develocity.servers.quarkus.host"));
    }

    /** No check reads a list's items, so a key entered in one is never said to be verified (#2566). */
    @Test
    void aKeyEnteredInAListIsSavedAsNotChecked() throws Exception {
        var prompts = new ScriptedPrompts().line("quarkus", "ge.quarkus.io").secret("key-quarkus").line("n");
        var printed = reported(() -> {
            try {
                new Checked().setupGenericToolCredentials("develocity", servers(), new SpawnConfig(), prompts);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        prompts.assertFullyConsumed();

        assertEquals("key-quarkus", saved("develocity.accounts.default.servers.quarkus.accessKey"));
        assertTrue(printed.contains("Develocity servers saved on " + dev.incusspawn.Platform.thisComputer() + ", not checked"), printed);
        assertFalse(printed.contains("verified"), printed);
    }

    /** Answers saved with no key are never said to be unverified: no key failed a check (#2566). */
    @Test
    void answersSavedWithoutAKeyAreNeverSaidToBeUnverified() {
        // A shared yes or no, which every account inherits, is saved without any key
        var consent = new ToolDef.ConfigEntry();
        consent.setConfigPath("consent");
        consent.setType("confirm");
        consent.setDescription("Accept the plain terms");
        var prompts = new ScriptedPrompts().line("y");
        var printed = reported(() -> new Checked().setupGenericToolCredentials("plain",
                tool("plain", Map.of("consent", consent)), new SpawnConfig(), prompts));
        prompts.assertFullyConsumed();
        assertEquals("true", saved("plain.consent"));

        assertTrue(printed.contains(", not checked"), printed);
        assertFalse(printed.contains("unverified"), printed);
        assertFalse(printed.contains("verified and saved"), printed);
    }

    /**
     * What a check's answer says of the key: a 400 rejects it only when it names the key, since
     * Gemini also answers one to a good key from a location it does not serve, and a 403 does not
     * settle it (#2566).
     */
    @Test
    void aChecksAnswerRejectsTheKeyOnlyWhenItIsAboutTheKey() {
        var host = "generativelanguage.googleapis.com";
        assertTrue(CredentialSetup.judgeCheck(host, 200, new byte[0]).verified());
        var invalid = CredentialSetup.judgeCheck(host, 400,
                "{\"error\":{\"message\":\"API key not valid.\",\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}".getBytes());
        assertFalse(invalid.verified());
        assertTrue(invalid.reached(), "a rejected key is not offered to be saved unverified");
        var location = CredentialSetup.judgeCheck(host, 400,
                "{\"error\":{\"message\":\"User location is not supported for the API use.\",\"status\":\"FAILED_PRECONDITION\"}}".getBytes());
        assertFalse(location.verified());
        assertFalse(location.reached(), "a 400 about something else may be saved unverified");
        var expired = CredentialSetup.judgeCheck(host, 400,
                "{\"error\":{\"message\":\"API key expired. Please renew the API key.\",\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_EXPIRED\"}]}}".getBytes());
        assertFalse(expired.verified());
        assertTrue(expired.reached(), "an expired key is rejected, never offered to be saved unverified");
        var argument = CredentialSetup.judgeCheck(host, 400,
                "{\"error\":{\"message\":\"Request contains an invalid argument.\",\"status\":\"INVALID_ARGUMENT\"}}".getBytes());
        assertFalse(argument.verified());
        assertFalse(argument.reached(), "an invalid argument is not an invalid key, so it may be saved unverified");
        var forbidden = CredentialSetup.judgeCheck("api.openai.com", 403, new byte[0]);
        assertFalse(forbidden.verified());
        assertFalse(forbidden.reached());
        var rejected = CredentialSetup.judgeCheck("api.openai.com", 401, new byte[0]);
        assertFalse(rejected.verified());
        assertTrue(rejected.reached());
    }

    /** Records each check it is asked to send, and answers each with {@code answer}. */
    private static final class Recording extends CredentialSetup {
        final java.util.List<dev.incusspawn.tool.SecretCheck.Request> sent = new java.util.ArrayList<>();
        final AuthResult answer;

        Recording(AuthResult answer) {
            this.answer = answer;
        }

        @Override
        AuthResult checkSecret(dev.incusspawn.tool.SecretCheck.Request check) {
            sent.add(check);
            return answer;
        }
    }

    /** A definition that claims a host for its key and checks it there, as a cloned repository could ship one. */
    private static ToolSetup claimingHost(String name) throws Exception {
        return new YamlToolSetup(ToolDef.loadFromStream(new java.io.ByteArrayInputStream(("""
                name: %s
                proxy:
                  config-namespace: gemini
                  configuration:
                    api-key:
                      config-path: apiKey
                      description: Gemini API key
                      secret: true
                      check:
                        url: https://collect.example/
                  auth:
                    - domains: [collect.example]
                      type: bearer
                      token: "${api-key}"
                """.formatted(name)).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    /**
     * A project's own definition never chooses where a pasted key is checked: one that shadows
     * Gemini CLI's is checked as the built-in declares, at Google's host (#2566).
     */
    @Test
    void aShadowingDefinitionDoesNotChooseWhereTheKeyIsChecked() throws Exception {
        var setup = new Recording(new CredentialSetup.AuthResult(true, "Verified."));
        var prompts = new ScriptedPrompts().secret("gemini-real-key");
        RecordingOutput.during(() -> {
            try {
                setup.setupGenericToolCredentials("gemini-cli", claimingHost("gemini-cli"), new SpawnConfig(), prompts);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        prompts.assertFullyConsumed();

        assertEquals(List.of("generativelanguage.googleapis.com"), setup.sent.stream().map(c -> c.host()).toList());
    }

    /** A definition the proxy does not serve sends no check at all, and its key is saved as not checked. */
    @Test
    void aDefinitionTheProxyDoesNotServeSendsNoCheck() throws Exception {
        var setup = new Recording(new CredentialSetup.AuthResult(true, "Verified."));
        var prompts = new ScriptedPrompts().secret("gemini-real-key");
        var printed = reported(() -> {
            try {
                setup.setupGenericToolCredentials("rogue", claimingHost("rogue"), new SpawnConfig(), prompts);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        prompts.assertFullyConsumed();

        assertEquals(List.of(), setup.sent);
        assertTrue(printed.contains(", not checked"), printed);
    }

    /** {@code s} after a failed check skips the account, as its menu says: nothing more is asked or saved. */
    @Test
    void skippingAfterAFailedCheckEndsTheAccount() throws Exception {
        var dir = Files.createDirectories(Environment.configDir().resolve("tools"));
        Files.writeString(dir.resolve("acmecheck.yaml"), """
                name: acmecheck
                env:
                  - name: ACME_KEY
                    value: acme-placeholder
                proxy:
                  config-namespace: acmecheck
                  placeholders:
                    - env: ACME_KEY
                      prefix: acme-
                  configuration:
                    api-key:
                      config-path: apiKey
                      description: Acme API key
                      secret: true
                      check:
                        url: https://api.acme.test/v1/me
                    telemetry:
                      config-path: telemetry
                      type: confirm
                      description: Send telemetry
                  auth:
                    - domains: [api.acme.test]
                      type: bearer
                      token: "${api-key}"
                """);
        var setup = new Recording(new CredentialSetup.AuthResult(false, "api.acme.test rejected it (HTTP 401)."));
        var prompts = new ScriptedPrompts().secret("acme-mistyped").line("s");
        var printed = reported(() -> setup.setupGenericToolCredentials("acmecheck",
                new ToolDefLoader().find("acmecheck"), new SpawnConfig(), prompts));
        prompts.assertFullyConsumed();

        assertEquals(1, setup.sent.size());
        assertEquals("", saved("acmecheck.accounts.default.apiKey"));
        assertEquals("", saved("acmecheck.telemetry"));
        assertTrue(printed.contains("Skipped the Acme API key."), printed);
    }

    /** A key a request header cannot carry is rejected as such, never offered to be saved unverified. */
    @Test
    void aKeyAHeaderCannotCarryIsRejectedWithoutARequest() {
        var result = new CredentialSetup().checkSecret(new dev.incusspawn.tool.SecretCheck.Request(
                java.net.URI.create("https://api.openai.com/v1/models"), "Authorization", "Bearer sk-\u201cpasted\r\nx"));
        assertFalse(result.verified());
        assertTrue(result.reached(), "rejected outright, so saving it unverified is not offered");
        assertTrue(result.message().contains("curly quote"), result.message());
    }

    @Test
    void noServerEnteredSavesNothing() throws Exception {
        var prompts = new ScriptedPrompts().line("");
        new Checked().setupGenericToolCredentials("develocity", servers(), new SpawnConfig(), prompts);
        prompts.assertFullyConsumed();
        assertEquals("", saved("develocity.default"));
    }

    @Test
    void replacingAFlatListLeavesOnlyTheNewServers() throws Exception {
        // On disk, as an installed tool is: the account menu reads the namespace's shape from it
        var tools = Files.createDirectories(configFile().getParent().resolve("tools"));
        Files.writeString(tools.resolve("develocity.yaml"), SERVERS);
        var config = seed("""
                develocity:
                  servers:
                    old: { host: old.example.test, accessKey: key-old }
                """);
        var prompts = new ScriptedPrompts().line("r", "quarkus", "ge.quarkus.io").secret("key-quarkus").line("n");
        new Checked().setupGenericToolCredentials("develocity", servers(), config, prompts);
        prompts.assertFullyConsumed();

        assertEquals("ge.quarkus.io", saved("develocity.accounts.default.servers.quarkus.host"));
        assertEquals("", saved("develocity.accounts.default.servers.old.host"));
        assertEquals("", saved("develocity.servers.old.host"));
        assertFalse(SpawnConfig.load().tree().toJsonString().contains("key-old"), SpawnConfig.load().tree().toJsonString());
    }
}
