package dev.incusspawn.lifecycle;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The branch's credential check sees what the branch will be stamped with -- the template's
 * pins, the source's own and any {@code --account} -- and the template a branch of a branch
 * really comes from (#793).
 */
@ExtendWith(TempHome.class)
class BranchFlowCredentialsTest {

    @BeforeEach
    void setUp() throws Exception {
        var configDir = SpawnConfig.configDir();
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.yaml"), """
                openai:
                  accounts:
                    personal: { apiKey: "sk-personal" }
                    spare: {}
                  default: personal
                github:
                  accounts:
                    work: { token: "ghp_work" }
                  default: work
                """);
    }

    private static Map<String, ImageDef> defs(String yaml) throws Exception {
        return Map.of("tpl-dev", ImageDef.parseYaml(yaml));
    }

    private static String problem(FakeIncusDaemon daemon, String source, List<String> overrides,
                                  Map<String, ImageDef> defs) {
        var inherited = BranchFlow.inheritedAccounts(daemon.client(), source, defs);
        return BranchFlow.credentialProblem(inherited, overrides, defs, new ToolDefLoader());
    }

    private static FakeIncusDaemon template() {
        return new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));
    }

    @Test
    void anAccountOverrideIsWhatGetsChecked() throws Exception {
        var defs = defs("name: tpl-dev\ntools:\n  - pi: {provider: openai}\n");
        assertEquals("", problem(template(), "tpl-dev", List.of(), defs));
        assertFalse(problem(template(), "tpl-dev", List.of("openai=spare"), defs).isEmpty(),
                "the account the branch will use has no key");
    }

    /** Each of {@code providers} needs its account, as the default provider does (#2674). */
    @Test
    void everyProviderATemplateGivesAnAgentNeedsItsAccount() throws Exception {
        for (var agent : List.of("pi", "opencode")) {
            var defs = defs("name: tpl-dev\ntools:\n  - " + agent + ": {provider: openai, providers: anthropic google}\n");
            var result = problem(template(), "tpl-dev", List.of(), defs);
            assertTrue(result.contains("Anthropic API key"), agent + ": " + result);
            assertTrue(result.contains("Gemini"), agent + ": " + result);
            assertFalse(result.contains("OpenAI"), agent + " has the default provider's key: " + result);
            assertTrue(result.contains("To do without it, remove 'anthropic' from providers"), agent + ": " + result);

            // A comma works where YAML leaves it alone: on a line of its own, here with a space after it
            var commas = defs("name: tpl-dev\ntools:\n  - " + agent + ":\n      provider: openai\n      providers: anthropic, google\n");
            assertEquals(result, problem(template(), "tpl-dev", List.of(), commas), agent);

            var onlyTheOneItHas = defs("name: tpl-dev\ntools:\n  - " + agent + ": {provider: google, providers: openai}\n");
            assertFalse(problem(template(), "tpl-dev", List.of("openai=spare"), onlyTheOneItHas).contains("Anthropic"), agent);
            assertTrue(problem(template(), "tpl-dev", List.of("openai=spare"), onlyTheOneItHas).contains("OpenAI"), agent);
        }
    }

    /**
     * A listed provider's ChatGPT or Google sign-in is refused as the default provider's is (#2525):
     * it gives the agent no key, though Claude's account for the default provider is complete.
     */
    @Test
    void aListedProvidersSignInIsRefusedAsTheDefaultOnesIs() throws Exception {
        Files.writeString(SpawnConfig.configDir().resolve("config.yaml"), """
                claude:
                  accounts:
                    personal: { type: api-key, apiKey: "sk-ant-x" }
                  default: personal
                openai:
                  accounts:
                    sub: { type: chatgpt }
                    key: { apiKey: "sk-key" }
                  default: sub
                gemini:
                  accounts:
                    g: { type: google }
                    key: { apiKey: "gemini-key" }
                  default: g
                """);
        for (var agent : List.of("pi", "opencode")) {
            var openai = defs("name: tpl-dev\ntools:\n  - " + agent + ": {provider: anthropic, providers: openai}\n");
            var result = problem(template(), "tpl-dev", List.of(), openai);
            assertTrue(result.contains("ChatGPT sign-in"), agent + ": " + result);
            assertTrue(result.contains("--account openai=key"), agent + ": " + result);
            assertEquals("", problem(template(), "tpl-dev", List.of("openai=key"), openai), agent);

            var google = defs("name: tpl-dev\ntools:\n  - " + agent + ": {provider: anthropic, providers: google}\n");
            assertTrue(problem(template(), "tpl-dev", List.of(), google).contains("Google sign-in"), agent);
            assertEquals("", problem(template(), "tpl-dev", List.of("gemini=key"), google), agent);

            // Both listed: the first sign-in is reported, then the other once it is fixed
            var both = defs("name: tpl-dev\ntools:\n  - " + agent + ": {provider: anthropic, providers: openai google}\n");
            assertTrue(problem(template(), "tpl-dev", List.of(), both).contains("ChatGPT sign-in"), agent);
            assertTrue(problem(template(), "tpl-dev", List.of("openai=key"), both).contains("Google sign-in"), agent);
        }
    }

    @Test
    void anOverrideRepairsABrokenTemplatePin() throws Exception {
        var defs = defs("name: tpl-dev\ntools: [gh]\naccounts:\n  github: deleted\n");
        assertTrue(problem(template(), "tpl-dev", List.of(), defs).contains("deleted"));
        assertEquals("", problem(template(), "tpl-dev", List.of("github=work"), defs));
    }

    @Test
    void aBranchOfABranchIsCheckedAgainstItsTemplate() throws Exception {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of(Metadata.PROFILE, "tpl-dev"));
        var result = problem(daemon, "dev-1", List.of(), defs("name: tpl-dev\ntools: [claude]\n"));
        assertTrue(result.contains("Anthropic API key"), result);
    }

    @Test
    void theToolsTheSourceWasBuiltWithAreWhatIsChecked() throws Exception {
        // tpl-dev's YAML gained claude after dev-1 was built without it: branching dev-1 needs
        // what dev-1 has, recorded in its build source, not what the YAML says now.
        var built = new dev.incusspawn.config.BuildSource(
                Map.of("tpl-dev", ImageDef.parseYaml("name: tpl-dev\ntools: [gh]\n")), null, null, null);
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of(
                Metadata.PROFILE, "tpl-dev", Metadata.BUILD_SOURCE, built.toJson()));
        var edited = defs("name: tpl-dev\ntools: [gh, claude]\n");
        assertEquals("", problem(daemon, "dev-1", List.of(), edited));

        var unrecorded = new FakeIncusDaemon().container("dev-1", Map.of(Metadata.PROFILE, "tpl-dev"));
        assertTrue(problem(unrecorded, "dev-1", List.of(), edited).contains("Anthropic API key"),
                "without a build record, the YAML is all there is to go by");
    }

    @Test
    void preflightRefusesABranchWhoseAccountHasNoKey() throws Exception {
        // The CLI's path: the selection preflight stamps, --account included, is what is checked.
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = incus -> true;
        try {
            var defs = defs("name: tpl-dev\ntools:\n  - pi: {provider: openai}\n");
            var request = new BranchFlow.Request("tpl-dev", "dev-2", false, false,
                    dev.incusspawn.config.NetworkMode.FULL, null, null, null, null,
                    List.of("openai=spare"), false, Map.of());
            var daemon = template();
            var e = assertThrows(BranchFlow.BranchException.class,
                    () -> BranchFlow.preflight(daemon.client(), request, defs));
            assertTrue(e.getMessage().contains("OpenAI API key"), e.getMessage());

            var airgapped = new BranchFlow.Request("tpl-dev", "dev-2", false, false,
                    dev.incusspawn.config.NetworkMode.AIRGAP, null, null, null, null,
                    List.of("openai=spare"), false, Map.of());
            BranchFlow.preflight(daemon.client(), airgapped, defs); // no proxy, no credentials needed
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }
    }

    /** {@code isx branch}'s preflight, at full network, asking {@code ask} for what is missing. */
    private static BranchFlow.Preflight preflight(FakeIncusDaemon daemon, Map<String, ImageDef> defs,
                                                  List<String> overrides, BranchFlow.MissingAccounts ask) {
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = incus -> true;
        try {
            var request = new BranchFlow.Request("tpl-dev", "dev-2", false, false,
                    dev.incusspawn.config.NetworkMode.FULL, null, null, null, null, overrides, false, Map.of());
            return BranchFlow.preflight(daemon.client(), request, defs, ask);
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }
    }

    /**
     * At a terminal the branch asks for what it lacks before refusing it (#2405), and goes ahead
     * once it is configured: the check is made again on what config.yaml now holds.
     */
    @Test
    void aMissingAccountGivenWhenAskedLetsTheBranchGoAhead() throws Exception {
        var defs = defs("name: tpl-dev\ntools: [claude]\n");
        var asked = new java.util.ArrayList<String>();
        var preflight = preflight(template(), defs, List.of(), (template, missing) -> {
            asked.add(template + ": " + missing.stream().map(m -> m.credential() + " (" + m.command() + ")").toList());
            try {
                Files.writeString(SpawnConfig.configDir().resolve("config.yaml"), "claude:\n  apiKey: \"sk-ant-x\"\n",
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return true;
        });

        assertEquals(List.of("tpl-dev: [Anthropic API key, OAuth token, or Vertex AI (isx account add claude)]"), asked);
        assertEquals("tpl-dev", preflight.request().source());
    }

    /** A skipped account leaves today's refusal, naming the command. */
    @Test
    void aSkippedAccountIsRefusedAsBefore() throws Exception {
        var defs = defs("name: tpl-dev\ntools: [claude]\n");
        var e = assertThrows(BranchFlow.BranchException.class,
                () -> preflight(template(), defs, List.of(), (template, missing) -> false));
        assertTrue(e.getMessage().contains("Anthropic API key"), e.getMessage());
        assertTrue(e.getMessage().contains("isx account add claude"), e.getMessage());
    }

    /**
     * The question is asked of the account the branch will use: a step that adds another account
     * does not satisfy an {@code --account} pin to one without a key, so the branch is still refused.
     */
    @Test
    void anAccountSetUpThatIsNotTheOneSelectedStillRefuses() throws Exception {
        var defs = defs("name: tpl-dev\ntools:\n  - pi: {provider: openai}\n");
        var calls = new int[1];
        var e = assertThrows(BranchFlow.BranchException.class, () -> preflight(template(), defs,
                List.of("openai=spare"), (template, missing) -> {
                    calls[0]++;
                    return true; // as if a step ran, adding nothing to 'spare'
                }));
        assertEquals(1, calls[0], "asked once, then checked again");
        assertTrue(e.getMessage().contains("OpenAI API key"), e.getMessage());
    }

    /**
     * A sign-in in use for Pi is refused whatever else is set up (#2525), so the branch asks for
     * nothing, not even Claude's missing key beside it: a secret typed there would not let it go ahead.
     */
    @Test
    void aGapNoStepFixesIsRefusedWithoutAskingForAnything() throws Exception {
        Files.writeString(SpawnConfig.configDir().resolve("config.yaml"), """
                openai:
                  accounts:
                    sub: { type: chatgpt }
                  default: sub
                """);
        var defs = defs("name: tpl-dev\ntools:\n  - claude\n  - pi: {provider: openai}\n");
        var e = assertThrows(BranchFlow.BranchException.class, () -> preflight(template(), defs, List.of(),
                (template, missing) -> {
                    throw new AssertionError("asked for " + missing);
                }));
        assertTrue(e.getMessage().contains("ChatGPT sign-in"), e.getMessage());
        assertTrue(e.getMessage().contains("Anthropic API key"), e.getMessage());
    }

    /** Nothing missing, nothing asked. */
    @Test
    void aBranchWithEveryAccountIsNotAsked() throws Exception {
        var defs = defs("name: tpl-dev\ntools: [gh]\n");
        preflight(template(), defs, List.of(), (template, missing) -> {
            throw new AssertionError("asked for " + missing);
        });
    }
}
