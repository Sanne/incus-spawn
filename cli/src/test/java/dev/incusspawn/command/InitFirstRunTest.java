package dev.incusspawn.command;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.GitIdentity;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The first run of {@code isx init} (#2412): its questions before any work, the answers a
 * continuation under {@code sg} is handed, and how the run ends.
 */
@ExtendWith(IsolatedHome.class)
class InitFirstRunTest {

    private static final HostAgentProbe.HostAgent CLAUDE_SUBSCRIPTION = new HostAgentProbe.HostAgent("claude", true,
            HostAgentProbe.Kind.SUBSCRIPTION, "anthropic", null, null, null, "~/.claude.json");

    private static final CredentialCheck.Missing CLAUDE_GAP =
            new CredentialCheck.Missing("Claude API credential", "isx account add claude");

    /** A first run whose host, credential steps, plumbing, build and branch are the test's own. */
    static class FirstRunInit extends InitCommand {
        Prompts prompts;
        List<HostAgentProbe.HostAgent> found = List.of();
        String login;
        /** Whether the agent's credential step saves an account, else the person skips it. */
        boolean credentialGiven;
        boolean credentialAsked;
        /** What the GitHub question answers: whether an account was saved. */
        boolean gitHubGiven;
        boolean gitHubAsked;
        GitIdentity host = new GitIdentity("Jane Doe", "jane@example.org");
        FirstRunAnswers handedOver;
        boolean continued;
        final List<String> ran = new ArrayList<>();
        final List<String> built = new ArrayList<>();
        final List<String> opened = new ArrayList<>();
        final List<String> downloads = new ArrayList<>();

        @Override Prompts prompts() { return prompts; }
        @Override List<HostAgentProbe.HostAgent> probeAgents() { return found; }
        @Override String ghLogin() { return login; }
        @Override GitIdentity hostGitIdentity() { return host; }
        @Override boolean continuedInGroup() { return continued; }
        @Override FirstRunAnswers continuationAnswers() { return handedOver; }
        @Override void startDownloadsAhead(ToolSetup agent) { downloads.add(agent == null ? "base" : agent.name()); }
        @Override boolean templateBuilt(String name) { return false; }
        @Override int buildTemplate(String template) { built.add(template); return 0; }
        @Override int openAgent(String template, String instance) { opened.add(template + " as " + instance); return 0; }
        @Override String firstInstanceName() { return "my-first"; }

        @Override
        Map<CredentialCheck.Missing, List<String>> agentGaps() {
            return credentialGiven && credentialAsked ? Map.of() : Map.of(CLAUDE_GAP, List.of("tpl-agent"));
        }

        @Override
        void setUpAgentCredential() {
            credentialAsked = true;
            ran.add("agent-credential");
        }

        @Override
        boolean offerGitHub() {
            gitHubAsked = true;
            ran.add("github");
            return gitHubGiven;
        }

        @Override
        List<InitStep> credentialSteps() {
            ran.add("credentials");
            return List.of();
        }

        @Override InitStep searchPathsStep() { return recorded("search-paths"); }
        @Override InitStep codeDirectoriesStep() { return recorded("code-directories"); }
        @Override InitStep mcpStep() { return recorded("mcp"); }
        @Override InitStep gitRemoteHelper() { return recorded("git-remote-helper"); }
        @Override InitStep dnsStep() { return recorded("dns"); }

        @Override
        InitStep proxyServiceStep(AtomicBoolean installed) {
            return new InitStep("proxy-service", "Proxy Service", new String[0], () -> {
                ran.add("proxy-service");
                installed.set(true);
            }, null);
        }

        @Override
        List<InitStep> linuxSetup() {
            return List.of(recorded("incus"), recorded("subids"), recorded("storage-network"));
        }

        InitStep recorded(String id) {
            return new InitStep(id, "Step " + id, new String[0], () -> ran.add(id), InitPlan.Assessment::done);
        }

        ToolSetup agent(String name) {
            return codingAgentTools().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
        }
    }

    private static String said(ScriptedPrompts prompts) {
        return String.join("\n", prompts.conversation());
    }

    @Test
    void anAgentFoundOnTheHostIsChosenByEnter() throws Exception {
        var init = new FirstRunInit();
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.gitHubGiven = true;
        var prompts = ScriptedPrompts.lines("");
        init.prompts = prompts;

        RecordingOutput.during(init::askFirstRunQuestions);

        prompts.assertFullyConsumed();
        assertEquals("claude", init.chosenAgent.name());
        assertEquals("claude", SpawnConfig.load().getPreferredAgent());
        assertEquals(List.of("Set up isx for Claude Code, so templates that include it can run it? (Y/n/other): "),
                prompts.questions());
        assertTrue(said(prompts).contains("Claude Code is on this host, signed in with a subscription."), said(prompts));
        assertEquals(List.of("agent-credential", "github"), init.ran);
        assertEquals(new InitCommand.FirstRunAnswers(false, false, false, false), init.answers);
        // The agent's own downloads start once it is chosen; the run started the base image's before asking
        assertEquals(List.of("claude"), init.downloads);
    }

    @Test
    void withNoAgentFoundEnterChoosesNothing() throws Exception {
        var init = new FirstRunInit();
        init.credentialGiven = true;
        init.gitHubGiven = true;
        var agents = init.codingAgentTools();
        var codex = agents.indexOf(init.agent("codex")) + 1;
        var prompts = ScriptedPrompts.lines("", String.valueOf(codex));
        init.prompts = prompts;

        RecordingOutput.during(init::askFirstRunQuestions);

        prompts.assertFullyConsumed();
        assertEquals("codex", init.chosenAgent.name());
        assertEquals(List.of("Choice (Enter to see the list again): ", "Choice (Enter to see the list again): "), prompts.questions());
        assertTrue(said(prompts).contains("Which agent should isx set up, so templates that include it can run it? Choose one:"), said(prompts));
        assertTrue(said(prompts).contains("None for now"), said(prompts));
    }

    @Test
    void noneForNowStoresNothingAndAsksForNoAgentAccount() throws Exception {
        var init = new FirstRunInit();
        init.gitHubGiven = true;
        var none = init.codingAgentTools().size() + 1;
        var prompts = ScriptedPrompts.lines(String.valueOf(none));
        init.prompts = prompts;

        RecordingOutput.during(init::askFirstRunQuestions);

        prompts.assertFullyConsumed();
        assertNull(init.chosenAgent);
        assertNull(SpawnConfig.load().getPreferredAgent());
        assertEquals(List.of("github"), init.ran);
        assertTrue(init.answers.noAgent());
        assertEquals(List.of(), init.downloads);
        assertEquals("tpl-minimal", init.prepareFirstTemplate());
        assertFalse(Files.exists(ExampleTemplate.file()));
    }

    @Test
    void theFoundAgentCanBeDeclined() throws Exception {
        var init = new FirstRunInit();
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.gitHubGiven = true;
        init.prompts = ScriptedPrompts.lines("n");

        RecordingOutput.during(init::askFirstRunQuestions);

        assertNull(init.chosenAgent);
        assertTrue(init.answers.noAgent());
    }

    /** Skipping the credential: the build asks for it no more, the message says so, and no agent is offered to open. */
    @Test
    void aSkippedCredentialIsNotAskedAgainAndNoAgentIsOffered() throws Exception {
        var init = new FirstRunInit();
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.gitHubGiven = true;
        var prompts = ScriptedPrompts.lines("");
        init.prompts = prompts;
        RecordingOutput.during(init::askFirstRunQuestions);
        assertTrue(init.answers.noCredential());

        var recorded = RecordingOutput.during(() -> assertEquals(0, init.finishFirstRun(false, true, false).getResultValue()));

        prompts.assertFullyConsumed();
        assertEquals(List.of("tpl-agent"), init.built);
        assertEquals(List.of(), init.opened);
        var box = String.join("\n", recorded.frameLines());
        // What is left, in the order it has to be done: the account, then the agent
        assertFalse(box.contains("Setup complete"), box);
        assertTrue(box.contains("needs these 2 steps"), box);
        assertTrue(box.indexOf("1. Add Claude Code's account:") < box.indexOf("isx account add claude"), box);
        assertTrue(box.indexOf("isx account add claude") < box.indexOf("2. Open your first agent:"), box);
        assertTrue(Files.readString(ExampleTemplate.file()).contains("  - claude"));
    }

    @Test
    void aSkippedGitHubAccountAsksAboutTheIdentityAndEnterIsNo() throws Exception {
        var init = new FirstRunInit();
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        var prompts = ScriptedPrompts.lines("", "");
        init.prompts = prompts;

        RecordingOutput.during(init::askFirstRunQuestions);

        prompts.assertFullyConsumed();
        assertEquals("Let it commit as Jane Doe <jane@example.org>, the identity of this host? (y/N): ",
                prompts.questions().getLast());
        assertNull(SpawnConfig.load().gitIdentity());
        assertEquals(new InitCommand.FirstRunAnswers(false, false, true, true), init.answers);

        var recorded = RecordingOutput.during(() -> init.finishFirstRun(false, true, false));
        assertTrue(String.join("\n", recorded.frameLines()).contains("no name to commit under"));
    }

    @Test
    void aSkippedGitHubAccountWithTheIdentityAnsweredYes() throws Exception {
        var init = new FirstRunInit();
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.prompts = ScriptedPrompts.lines("", "y");

        RecordingOutput.during(init::askFirstRunQuestions);

        assertEquals("Jane Doe", SpawnConfig.load().gitIdentity().name());
        assertEquals(new InitCommand.FirstRunAnswers(false, false, true, false), init.answers);
    }

    /** With the credential given, the run builds the template and opens the agent on Enter. */
    @Test
    void theRunEndsWithTheOfferToOpenTheAgent() throws Exception {
        var init = new FirstRunInit();
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.gitHubGiven = true;
        var prompts = ScriptedPrompts.lines("", "");
        init.prompts = prompts;
        RecordingOutput.during(init::askFirstRunQuestions);

        RecordingOutput.during(() -> init.finishFirstRun(false, true, false));

        prompts.assertFullyConsumed();
        assertEquals("Open your first agent now? (Y/n): ", prompts.questions().getLast());
        assertEquals(List.of("tpl-agent"), init.built);
        assertEquals(List.of("tpl-agent as my-first"), init.opened);
    }

    private static InitCommand.Closing closing(InitCommand.Services services, boolean built, boolean buildFailed,
                                               String agent, String accountCommand, List<String> firstStart) {
        return new InitCommand.Closing(services, agent == null ? "tpl-minimal" : "tpl-agent", built, buildFailed, agent,
                accountCommand, firstStart, true, "my-first-12", true);
    }

    private static List<String> plain(String[] lines) {
        return java.util.Arrays.stream(lines).map(l -> l.replaceAll("\u001B\\[[0-9;]*m", "")).toList();
    }

    /** Ready: one thing to do, said as it is, then how to come back and change the template. */
    @Test
    void aReadyRunEndsWithTheOneCommandAndWhatComesAfter() {
        var box = String.join("\n", plain(InitCommand.firstRunNextSteps(
                closing(InitCommand.Services.INSTALLED, true, false, "Claude Code", null, List.of()))));

        assertTrue(box.contains("Setup complete!"), box);
        assertTrue(box.contains("tpl-agent is built, with Claude Code."), box);
        assertTrue(box.contains("   Open your first agent:\n     isx branch my-first-12 --from tpl-agent"), box);
        assertTrue(box.contains("Return to the machine:   isx shell my-first-12"), box);
        assertTrue(box.contains("Change the template:     isx templates edit tpl-agent"), box);
        assertTrue(box.contains("https://isx.run/docs/templates#adding-your-own"), box);
    }

    /** Everything left at once: numbered in the order that works, and never called complete. */
    @Test
    void whatIsLeftIsNumberedInTheOrderItIsDone() {
        var box = String.join("\n", plain(InitCommand.firstRunNextSteps(closing(InitCommand.Services.START_PROXY, false, true,
                "Antigravity CLI", "isx account add gemini", List.of("agy then prints a sign-in link.")))));

        assertFalse(box.contains("Setup complete"), box);
        assertTrue(box.contains("tpl-agent did not build."), box);
        assertTrue(box.contains("needs these 4 steps"), box);
        var order = List.of("1. Start the proxy:", "isx proxy start", "it keeps its terminal",
                "2. Fix the error above, then build tpl-agent:", "isx build tpl-agent",
                "3. Add Antigravity CLI's account:", "isx account add gemini",
                "4. Open your first agent:", "isx branch my-first-12 --from tpl-agent", "agy then prints a sign-in link.");
        var at = -1;
        for (var part : order) {
            var next = box.indexOf(part, at + 1);
            assertTrue(next > at, part + " out of order in:\n" + box);
            at = next;
        }
    }

    /** No line is wider than the box, on any path: a wider one breaks its right border. */
    @Test
    void noLineOfTheClosingMessageIsWiderThanItsBox() {
        var google = new dev.incusspawn.tool.AntigravitySetup().firstStartNote(seedGoogle());
        assertFalse(google.isEmpty());
        for (var services : InitCommand.Services.values()) {
            for (var agent : new String[]{null, "GitHub Copilot CLI"}) {
                for (var built : new boolean[]{true, false}) {
                    var lines = plain(InitCommand.firstRunNextSteps(closing(services, built, !built, agent,
                            agent == null ? null : "isx account add github", google)));
                    for (var line : lines) assertTrue(line.length() <= InitCommand.BOX_WIDTH, "too wide: '" + line + "'");
                }
            }
        }
    }

    private static SpawnConfig seedGoogle() {
        try {
            return IsolatedHome.seed("""
                    gemini:
                      accounts:
                        work:
                          type: google
                      default: work
                    """);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** An API key account starts agy with nothing more to do. */
    @Test
    void onlyAGoogleAccountHasAFirstStartNote() throws Exception {
        var keyed = IsolatedHome.seed("""
                gemini:
                  accounts:
                    work:
                      apiKey: "k"
                  default: work
                """);
        assertEquals(List.of(), new dev.incusspawn.tool.AntigravitySetup().firstStartNote(keyed));
        assertEquals(List.of(), new dev.incusspawn.tool.AntigravitySetup().firstStartNote(new SpawnConfig()));
    }

    /** A step of the plumbing that fails: the run says the answers are kept and what to run once it is fixed. */
    @Test
    void aFailedStepSaysWhatToRunOnceItIsFixed() throws Exception {
        var init = new FirstRunInit() {
            @Override InitStep dnsStep() {
                return new InitStep("dns", "DNS Configuration", new String[0], () -> { throw new IllegalStateException("no resolver"); }, null);
            }
        };
        init.continued = true;
        init.handedOver = new InitCommand.FirstRunAnswers(true, false, false, false);
        init.prompts = ScriptedPrompts.lines();

        var recorded = RecordingOutput.during(() ->
                assertEquals("no resolver", assertThrows(IllegalStateException.class, () -> init.runFirstRun(false)).getMessage()));

        var said = String.join("\n", recorded.lines());
        assertTrue(said.contains("The agent and accounts you set up are saved"), said);
        assertTrue(said.contains("run 'isx init' again"), said);
        assertEquals(List.of(), init.built);
    }

    /** A build that failed is named, the run exits 1, and the agent is not offered. */
    @Test
    void aFailedBuildIsNamedAndNothingIsOpened() throws Exception {
        var init = new FirstRunInit() {
            @Override int buildTemplate(String template) { return 1; }
        };
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.gitHubGiven = true;
        init.prompts = ScriptedPrompts.lines("");
        RecordingOutput.during(init::askFirstRunQuestions);

        var recorded = RecordingOutput.during(() -> assertEquals(1, init.finishFirstRun(false, true, false).getResultValue()));

        var box = String.join("\n", recorded.frameLines());
        assertTrue(box.contains("tpl-agent did not build."), box);
        assertTrue(box.contains("Fix the error above, then build tpl-agent:"), box);
        assertFalse(box.contains("Setup complete"), box);
        assertEquals(List.of(), init.opened);
    }

    /** Another command's init writes the template, and builds and opens nothing: that command carries on. */
    @Test
    void anotherCommandsInitBuildsNothing() throws Exception {
        var init = new FirstRunInit();
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.gitHubGiven = true;
        init.automatic = true;
        var prompts = ScriptedPrompts.lines("");
        init.prompts = prompts;
        RecordingOutput.during(init::askFirstRunQuestions);

        RecordingOutput.during(() -> init.finishFirstRun(false, true, false));

        prompts.assertFullyConsumed();
        assertEquals(List.of(), init.built);
        assertEquals(List.of(), init.opened);
        assertTrue(Files.exists(ExampleTemplate.file()));
    }

    /** A GitHub account given in the same run reaches the first template: it lists gh (#2535). */
    @Test
    void aGivenGitHubAccountPutsGhInTheFirstTemplate() throws Exception {
        var init = new FirstRunInit() {
            @Override
            boolean offerGitHub() {
                var saved = super.offerGitHub();
                var config = SpawnConfig.load();
                dev.incusspawn.config.NamespaceAccounts.put(config, "github", "default", "token", "ghp_test");
                config.save();
                return saved;
            }
        };
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.gitHubGiven = true;
        init.prompts = ScriptedPrompts.lines("");
        RecordingOutput.during(init::askFirstRunQuestions);

        RecordingOutput.during(() -> assertEquals("tpl-agent", init.prepareFirstTemplate()));

        var text = Files.readString(ExampleTemplate.file());
        assertTrue(text.contains("  - claude\n  - gh\n"), text);
    }

    /** A skipped GitHub account leaves gh out, so the template needs no account the person declined (#2535). */
    @Test
    void aSkippedGitHubAccountLeavesGhOutOfTheFirstTemplate() throws Exception {
        var init = new FirstRunInit();
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.prompts = ScriptedPrompts.lines("");
        RecordingOutput.during(init::askFirstRunQuestions);

        RecordingOutput.during(() -> assertEquals("tpl-agent", init.prepareFirstTemplate()));

        var text = Files.readString(ExampleTemplate.file());
        assertTrue(text.contains("  - claude\n"), text);
        assertFalse(text.contains("  - gh"), text);
    }

    /** An agent whose own account is GitHub's (Copilot) is not asked for GitHub a second time. */
    @Test
    void anAgentSpendingGitHubIsNotAskedForItAgain() throws Exception {
        var init = new FirstRunInit() {
            @Override
            void setUpAgentCredential() {
                super.setUpAgentCredential();
                var config = SpawnConfig.load();
                dev.incusspawn.config.NamespaceAccounts.put(config, "github", "default", "token", "ghp_test");
                config.save();
            }
        };
        init.credentialGiven = true;
        init.found = List.of(new HostAgentProbe.HostAgent("copilot", true, HostAgentProbe.Kind.SUBSCRIPTION,
                null, null, null, null, "~/.copilot"));
        init.prompts = ScriptedPrompts.lines("");

        RecordingOutput.during(init::askFirstRunQuestions);

        assertEquals(List.of("agent-credential"), init.ran);
        assertFalse(init.answers.noGitHub());
    }

    /**
     * A config.yaml that does not parse stops the first run before anything saves it, "None for
     * now" included: read leniently it is empty, and the GitHub step's save would drop its accounts.
     */
    @Test
    void aBrokenConfigIsLeftAsItIs() throws Exception {
        var broken = "claude:\n  accounts:\n    work: {apiKey: sk-ant-x\n";
        Files.createDirectories(IsolatedHome.configFile().getParent());
        Files.writeString(IsolatedHome.configFile(), broken);
        var init = new InitCommand() {
            @Override Prompts prompts() { return ScriptedPrompts.lines(String.valueOf(codingAgentTools().size() + 1), "1"); }
        };

        var thrown = assertThrows(IllegalStateException.class, () -> RecordingOutput.during(init::askFirstRunQuestions));

        assertTrue(thrown.getMessage().contains("config.yaml"), thrown.getMessage());
        assertEquals(broken, Files.readString(IsolatedHome.configFile()));
        // Each step that saves reads it strictly itself, whatever ran before it
        assertThrows(IllegalStateException.class, () -> RecordingOutput.during(init::offerGitHub));
        assertEquals(broken, Files.readString(IsolatedHome.configFile()));
    }

    /**
     * A tpl-agent in the working directory's {@code .incus-spawn/} is a cloned repository's: init
     * neither keeps it as the person's, nor builds nor opens it, and names the directory.
     */
    @Test
    void aProjectsTemplateIsNeitherBuiltNorOpened() throws Exception {
        var clone = java.nio.file.Path.of("/tmp/a-clone");
        var init = new FirstRunInit() {
            @Override
            Map<String, dev.incusspawn.config.ImageDef> templatesHere() {
                var here = new java.util.LinkedHashMap<>(super.templatesHere());
                try {
                    var project = dev.incusspawn.config.ImageDef.parseYaml("name: tpl-agent\nparent: tpl-minimal\ntools:\n  - codex\n");
                    project.setProjectRoot(clone);
                    here.put("tpl-agent", project);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return here;
            }
        };
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.gitHubGiven = true;
        var prompts = ScriptedPrompts.lines("");
        init.prompts = prompts;
        RecordingOutput.during(init::askFirstRunQuestions);

        var recorded = RecordingOutput.during(() -> init.finishFirstRun(false, true, false));

        prompts.assertFullyConsumed();
        assertEquals(List.of(), init.built);
        assertEquals(List.of(), init.opened);
        // The person's own template is written all the same, for Claude Code, and not the project's
        assertTrue(Files.readString(ExampleTemplate.file()).contains("  - claude"));
        assertEquals("claude", init.templateAgent().name());
        var warning = recorded.events().stream().filter(e -> e.kind() == RecordingOutput.Kind.WARN).map(RecordingOutput.Event::text)
                .filter(w -> w.contains("Did not build tpl-agent")).findFirst().orElseThrow(() -> new AssertionError(recorded.events().toString()));
        assertTrue(warning.contains("/tmp/a-clone"), warning);
        assertTrue(warning.contains("from a directory with no .incus-spawn/ of its own"), warning);
        assertTrue(String.join("\n", recorded.frameLines()).contains("isx build tpl-agent"));
    }

    /** A tool of the chain that the working directory's {@code .incus-spawn/tools/} overrides holds the build back too. */
    @Test
    void aProjectsToolOfTheChainHoldsTheBuildBack() throws Exception {
        var init = new FirstRunInit() {
            @Override
            Set<String> projectToolsHere() {
                return Set.of("claude");
            }
        };
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.gitHubGiven = true;
        var prompts = ScriptedPrompts.lines("");
        init.prompts = prompts;
        RecordingOutput.during(init::askFirstRunQuestions);

        var recorded = RecordingOutput.during(() -> init.finishFirstRun(false, true, false));

        prompts.assertFullyConsumed();
        assertEquals(List.of(), init.built);
        assertEquals(List.of(), init.opened);
        assertTrue(recorded.events().stream().anyMatch(e -> e.kind() == RecordingOutput.Kind.WARN
                && e.text().contains("Did not build tpl-agent")), recorded.events().toString());
    }

    /**
     * A project tool overriding one the agent requires holds the build back too: Antigravity CLI
     * requires agent-cli-tools, which a build here would take from the project.
     */
    @Test
    void aProjectsRequiredToolHoldsTheBuildBack() throws Exception {
        var init = new FirstRunInit() {
            @Override
            Set<String> projectToolsHere() {
                return Set.of("agent-cli-tools");
            }
        };
        init.found = List.of(new HostAgentProbe.HostAgent("antigravity", true, HostAgentProbe.Kind.API_KEY,
                "google", null, null, null, "$GEMINI_API_KEY"));
        init.credentialGiven = true;
        init.gitHubGiven = true;
        init.prompts = ScriptedPrompts.lines("");
        RecordingOutput.during(init::askFirstRunQuestions);
        assertEquals("antigravity", init.chosenAgent.name());

        var recorded = RecordingOutput.during(() -> init.finishFirstRun(false, true, false));

        assertEquals(List.of(), init.built);
        assertEquals(List.of(), init.opened);
        assertTrue(recorded.events().stream().anyMatch(e -> e.kind() == RecordingOutput.Kind.WARN
                && e.text().contains("Did not build tpl-agent")), recorded.events().toString());
    }

    /** A held-back template built before is said to be built, not to be built again, and is not offered. */
    @Test
    void aHeldBackTemplateBuiltBeforeIsSaidToBeBuilt() throws Exception {
        var init = new FirstRunInit() {
            @Override Set<String> projectToolsHere() { return Set.of("claude"); }
            @Override boolean templateBuilt(String name) { return true; }
        };
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.gitHubGiven = true;
        init.prompts = ScriptedPrompts.lines("");
        RecordingOutput.during(init::askFirstRunQuestions);

        var recorded = RecordingOutput.during(() -> init.finishFirstRun(false, true, false));

        var box = String.join("\n", recorded.frameLines());
        assertTrue(box.contains("tpl-agent is built"), box);
        assertFalse(box.contains("Build it:"), box);
        assertTrue(box.contains("(from a directory with no .incus-spawn/ of its own)"), box);
        assertEquals(List.of(), init.opened);
    }

    /** A kept tpl-agent written for another agent is said to install that agent, not the one chosen. */
    @Test
    void aKeptTemplateForAnotherAgentSaysWhichItInstalls() throws Exception {
        var init = new FirstRunInit();
        init.chosenAgent = init.agent("claude");
        Files.createDirectories(ExampleTemplate.file().getParent());
        Files.writeString(ExampleTemplate.file(), ExampleTemplate.text(init.agent("codex")));

        var recorded = RecordingOutput.during(() -> assertEquals("tpl-agent", init.prepareFirstTemplate()));

        assertEquals("codex", init.templateAgent().name());
        assertTrue(recorded.events().stream().anyMatch(e -> e.text().contains("it installs Codex CLI, not Claude Code")),
                recorded.events().toString());
    }

    /** Init never writes over a template of the person's own. */
    @Test
    void anExistingTemplateIsBuiltAsItIs() throws Exception {
        var init = new FirstRunInit();
        init.chosenAgent = init.agent("codex");
        Files.createDirectories(ExampleTemplate.file().getParent());
        Files.writeString(ExampleTemplate.file(), "name: tpl-agent\nparent: tpl-minimal\n# mine\n");

        RecordingOutput.during(() -> assertEquals("tpl-agent", init.prepareFirstTemplate()));

        assertEquals("name: tpl-agent\nparent: tpl-minimal\n# mine\n", Files.readString(ExampleTemplate.file()));
    }

    /**
     * The continuation under {@code sg} asks nothing: it reads the settings from config.yaml, and
     * keeps each declined answer it was handed.
     */
    @Test
    void theContinuationAsksNothingAndKeepsEachDeclinedAnswer() throws Exception {
        IsolatedHome.seed("preferred-agent: claude\n");
        var init = new FirstRunInit();
        init.continued = true;
        init.handedOver = InitCommand.FirstRunAnswers.decode(new InitCommand.FirstRunAnswers(false, true, true, true).encode());
        var prompts = ScriptedPrompts.lines();
        init.prompts = prompts;

        var recorded = RecordingOutput.during(() -> {
            assertTrue(init.isFirstRun());
            assertEquals(0, init.runFirstRun(false).getResultValue());
        });

        prompts.assertFullyConsumed();
        assertEquals(List.of(), prompts.questions());
        assertFalse(init.credentialAsked);
        assertFalse(init.gitHubAsked);
        assertEquals(List.of(), init.downloads, "the parent fetches them");
        // Incus Installation ran in the parent
        assertEquals(List.of("subids", "storage-network", "git-remote-helper", "dns", "proxy-service"), init.ran);
        assertEquals("claude", init.chosenAgent.name());
        assertEquals(List.of("tpl-agent"), init.built);
        assertEquals(List.of(), init.opened);
        var box = String.join("\n", recorded.frameLines());
        assertTrue(box.contains("Add Claude Code's account:"), box);
        assertTrue(box.contains("no name to commit under"), box);
        assertTrue(box.contains("newgrp incus-admin"), box);
    }

    @Test
    void theAnswersSurviveTheirEncoding() {
        for (var bits = 0; bits < 16; bits++) {
            var answers = new InitCommand.FirstRunAnswers((bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0);
            assertEquals(answers, InitCommand.FirstRunAnswers.decode(answers.encode()));
        }
        assertNull(InitCommand.FirstRunAnswers.decode(null));
        assertNull(InitCommand.FirstRunAnswers.decode("no-agent"));
    }

    /** {@code --all} asks the steps a first run leaves for later too, before the work. */
    @Test
    void allAsksEveryStepInTurn() throws Exception {
        var init = new FirstRunInit();
        init.all = true;
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.prompts = ScriptedPrompts.lines("", "");

        RecordingOutput.during(init::askFirstRunQuestions);

        assertEquals(List.of("agent-credential", "github", "credentials", "search-paths", "code-directories", "mcp"), init.ran);
    }

    /** A step's answer flag runs that step in a first run, with the answer and no question. */
    @Test
    void anAnswerFlagRunsItsStep() throws Exception {
        var init = new FirstRunInit();
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.gitHubGiven = true;
        init.codeDirAnswers = List.of("/tmp");
        init.prompts = ScriptedPrompts.lines("");

        RecordingOutput.during(init::askFirstRunQuestions);

        assertEquals(List.of("agent-credential", "github", "code-directories"), init.ran);
    }

    /** A host where init completed once gets the menu, never the first run again; one with no terminal keeps its walk. */
    @Test
    void onlyAFreshHostAtATerminalGetsTheFirstRun() throws Exception {
        var init = new FirstRunInit();
        init.prompts = ScriptedPrompts.lines();
        assertTrue(init.isFirstRun());
        init.format = "json";
        assertFalse(init.isFirstRun());
        init.format = null;
        init.prompts = null;
        assertFalse(init.isFirstRun());
        init.prompts = ScriptedPrompts.lines();
        Files.createDirectories(dev.incusspawn.Environment.configDir());
        Files.writeString(dev.incusspawn.Environment.initCompleteMarker(), "1");
        assertFalse(init.isFirstRun());
        init.all = true;
        assertTrue(init.isFirstRun());
    }

    /**
     * Every step today's init runs is in the first run or in the menu, so none is dropped from
     * both: the steps {@code isx init --plan} reads, on Linux and on macOS.
     */
    @Test
    void everyStepIsInTheFirstRunOrInTheMenu() {
        var init = new InitCommand();
        for (var macOs : List.of(false, true)) {
            var firstRun = new HashSet<String>();
            init.firstRunSteps(macOs).forEach(s -> firstRun.add(s.id()));
            for (var step : init.planSteps(macOs)) {
                assertTrue(firstRun.contains(step.id()) || InitCommand.DEFERRED.contains(step.id()),
                        step.id() + (macOs ? " (macOS)" : " (Linux)") + " is in neither the first run nor the menu");
            }
        }
    }

    /** The gh login is a choice the person types: Enter skips GitHub and never takes it. */
    @Test
    void theGhLoginIsNamedAndEnterNeverTakesIt() throws Exception {
        var read = new AtomicBoolean();
        var setup = new CredentialSetup() {
            @Override
            String readGhAuthToken() {
                read.set(true);
                return null;
            }
        };
        var prompts = ScriptedPrompts.lines("");

        RecordingOutput.during(() -> assertFalse(setup.offerGitHubForAgent(SpawnConfig.load(), prompts, "jdoe")));

        prompts.assertFullyConsumed();
        assertFalse(read.get());
        var said = said(prompts);
        assertTrue(said.contains("Your gh login, jdoe"), said);
        assertTrue(said.contains("the agent would act with jdoe's own access to GitHub"), said);
        assertEquals(List.of("Choice (Enter to skip GitHub for now): "), prompts.questions());
        IsolatedHome.assertNothingSaved();
    }

    /** Every coding agent's first template has an account the first run can name. */
    @Test
    void everyAgentsFirstTemplateIsKnownToTheCredentialCheck() {
        var init = new InitCommand();
        for (var agent : AgentCommand.agents(RuntimeConstants.CDI_TOOLS)) {
            init.chosenAgent = agent;
            for (var gap : init.agentGaps().keySet()) assertFalse(gap.command().isBlank(), agent.name());
        }
    }
}
