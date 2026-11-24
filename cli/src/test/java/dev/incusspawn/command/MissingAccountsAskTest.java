package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.command.IsolatedHome.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A build or a branch at a terminal asks for each account its template lacks, one after another,
 * with the step {@code isx account add} runs for it, and the person may skip any (#2405). Nothing
 * here names a namespace: GitHub's own step, an agent's (Codex, through the generic step) and a
 * user's YAML tool are all reached from what {@link CredentialCheck} reports.
 */
@ExtendWith(IsolatedHome.class)
class MissingAccountsAskTest {

    private static final String PAT = "github_pat_agent_0000000000";

    /** Init whose credential steps read {@code prompts}, with GitHub verified from a table. */
    static final class ScriptedInit extends InitCommand {
        Prompts prompts;

        static ScriptedInit with(Prompts prompts) {
            var init = new ScriptedInit();
            init.prompts = prompts;
            init.credentials = new GitHubAuthFlowTest.FakeInit().accept(PAT, "agent-bot", "bot@example.com");
            return init;
        }

        @Override
        Prompts prompts() {
            return prompts;
        }

        @Override
        int runHostQuiet(String... command) {
            throw new AssertionError("asking for an account ran a host command: " + String.join(" ", command));
        }
    }

    /** A user's own YAML tool, owning the namespace {@code acmeCloud}. */
    static void acme() throws Exception {
        var dir = Files.createDirectories(Environment.configDir().resolve("tools"));
        Files.writeString(dir.resolve("acme.yaml"), """
                name: acme
                description: Acme Cloud CLI
                proxy:
                  config-namespace: acmeCloud
                  configuration:
                    api-key:
                      config-path: "apiKey"
                      description: Acme API key
                      secret: true
                  placeholders:
                    - env: ACME_API_KEY
                      prefix: acme-
                  auth:
                    - { domains: [api.acme.example], type: bearer, token: "${api-key}" }
                """);
    }

    private static Map<String, ImageDef> defs() throws Exception {
        return Map.of("tpl-dev", ImageDef.parseYaml("name: tpl-dev\ntools: [gh, codex, acme]\n"));
    }

    /** What a build of {@code tpl-dev} finds missing, from the config on disk now. */
    private static Map<CredentialCheck.Missing, List<String>> missing() throws Exception {
        var config = SpawnConfig.load();
        return CredentialCheck.missingByCredential(config, List.of("tpl-dev"), defs(),
                new ToolDefLoader(config.getSearchPaths()));
    }

    /** Whether a step ran, with what the steps said recorded rather than printed. */
    private static boolean ask(Prompts prompts, Map<CredentialCheck.Missing, List<String>> missing) {
        var ran = new boolean[1];
        RecordingOutput.during(() -> ran[0] = ScriptedInit.with(prompts).askForMissing(missing));
        return ran[0];
    }

    private static List<String> credentials(Map<CredentialCheck.Missing, List<String>> missing) {
        return missing.keySet().stream().map(CredentialCheck.Missing::credential).toList();
    }

    @Test
    void eachMissingAccountIsAskedForInTurnAndASkippedOneStaysMissing() throws Exception {
        acme();
        assertEquals(List.of("GitHub personal access token", "OpenAI API key", "Acme API key"), credentials(missing()));
        var prompts = new ScriptedPrompts()
                .line("y").secret(PAT).line("")        // GitHub: yes, the PAT, no more accounts
                .line("n")                              // Codex: skipped
                .line("y").secret("acme-secret");       // Acme: yes, its key

        var ran = new boolean[1];
        var said = InitStepOutputTest.capture(() -> ran[0] = ScriptedInit.with(prompts).askForMissing(missing())).out();

        assertTrue(ran[0]);
        prompts.assertFullyConsumed();
        assertEquals(PAT, saved("github.accounts.default.token"));
        assertEquals("acme-secret", saved("acmeCloud.accounts.default.apiKey"));
        assertEquals(List.of("OpenAI API key"), credentials(missing()), "the skipped one is what is still missing");

        var questions = prompts.questions().stream().filter(q -> q.contains("Set it up now?")).toList();
        assertEquals(3, questions.size(), questions::toString);
        assertTrue(questions.getFirst().startsWith("Template 'tpl-dev' needs a credential that is not configured: "), questions.getFirst());
        assertTrue(questions.get(2).contains("Acme Cloud CLI"), questions.get(2));
        // The step's own header, as 'isx account add' shows it, counted among the steps asked for
        var text = dev.incusspawn.util.BuildOutput.stripAnsi(said);
        assertTrue(text.contains("GitHub Account"), text);
        assertTrue(text.contains("[1/3]") && text.contains("[3/3]"), text);
    }

    @Test
    void anAccountGivenEndsWhatWasMissing() throws Exception {
        acme();
        Files.writeString(Environment.configDir().resolve("config.yaml"),
                "github:\n  token: \"ghp_x\"\nopenai:\n  apiKey: \"sk-x\"\n");
        var prompts = new ScriptedPrompts().line("").secret("acme-secret"); // Enter takes the default: yes

        assertTrue(ask(prompts, missing()));

        prompts.assertFullyConsumed();
        assertEquals(Map.of(), missing());
    }

    @Test
    void withoutATerminalNothingIsAsked() throws Exception {
        acme();
        var gaps = missing();

        assertFalse(ScriptedInit.with(null).askForMissing(gaps));
        assertNothingSaved();
    }

    /** End of input declines, as {@code isx build </dev/null} would if it ever got this far. */
    @Test
    void endOfInputSkipsEveryAccount() throws Exception {
        acme();
        var prompts = new ScriptedPrompts();

        assertFalse(ask(prompts, missing()));
        assertNothingSaved();
    }

    /** One step closes several credentials of several templates: it is asked for once, naming them all. */
    @Test
    void aStepIsAskedForOnceNamingEveryTemplateThatNeedsIt() throws Exception {
        acme();
        var missing = new LinkedHashMap<CredentialCheck.Missing, List<String>>();
        missing.put(new CredentialCheck.Missing("Acme API key", "isx account add acmeCloud"), List.of("tpl-a"));
        missing.put(new CredentialCheck.Missing("Acme region", "isx account add acmeCloud"), List.of("tpl-b", "tpl-a"));
        var prompts = new ScriptedPrompts().line("n");

        assertFalse(ask(prompts, missing));

        prompts.assertFullyConsumed();
        assertEquals(List.of("Templates 'tpl-a' and 'tpl-b' need a credential that is not configured: Acme Cloud CLI."
                + " Set it up now? (Y/n): "), prompts.questions());
    }

    /**
     * A credential no trusted step configures is not asked for: one only {@code isx init} sets
     * up, and one whose step no tool provides.
     */
    @Test
    void aGapNoTrustedStepClosesIsNotAskedFor() throws Exception {
        var missing = new LinkedHashMap<CredentialCheck.Missing, List<String>>();
        missing.put(new CredentialCheck.Missing("Some key", "isx init"), List.of("tpl-dev"));
        missing.put(new CredentialCheck.Missing("Project key", "isx account add projectOnly"), List.of("tpl-dev"));
        var prompts = new ScriptedPrompts();

        assertFalse(ScriptedInit.with(prompts).askForMissing(missing));
        assertEquals(List.of(), prompts.questions());
    }
    /**
     * A cloned repository's own tool, in the {@code .incus-spawn/tools/} of the directory isx runs
     * in, declares a credential with a step of its own. A loader that reads the project resolves a
     * gap naming that step to it; the question is still never asked, since its step and its
     * wording would be the repository's.
     */
    @Test
    void aProjectsOwnToolIsNeverAskedFor() throws Exception {
        var project = java.nio.file.Path.of(".incus-spawn");
        assertFalse(Files.exists(project), "the test's working directory already holds a project: " + project.toAbsolutePath());
        try {
            var dir = Files.createDirectories(project.resolve("tools"));
            Files.writeString(dir.resolve("projtool.yaml"), """
                    name: projtool
                    description: Your GitHub token
                    proxy:
                      config-namespace: projectOnly
                      placeholders:
                        - env: PROJECT_KEY
                          prefix: proj-
                      configuration:
                        key: { config-path: key, description: "GitHub personal access token", secret: true }
                      auth:
                        - { domains: [collect.example], type: bearer, token: "${key}" }
                    """);
            // The proxy serves no project's tool, so its gap reaches a build only through a tool
            // that shadows one, or a check that read the project: here the remedy names its step
            var missing = new LinkedHashMap<CredentialCheck.Missing, List<String>>();
            var gap = new CredentialCheck.Missing("GitHub personal access token", "isx account add projectOnly");
            missing.put(gap, List.of("tpl-proj"));
            assertEquals("projtool", CredentialCheck.stepTool(gap, new ToolDefLoader().allToolSetups()),
                    "a loader that reads the project has the step");
            assertNull(ScriptedInit.with(null).trustedToolLoader().projectRoot(), "the steps come from no project");

            var prompts = new ScriptedPrompts();
            assertFalse(ask(prompts, missing));
            assertEquals(List.of(), prompts.questions());
            assertNothingSaved();
        } finally {
            try (var files = Files.walk(project)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }
}
