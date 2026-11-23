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
        assertEquals(List.of("Choice [Y]: "),
                prompts.questions());
        assertTrue(said(prompts).contains("Found Claude Code on " + dev.incusspawn.Platform.thisComputer() + "."), said(prompts));
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
        assertTrue(said(prompts).contains("isx can set up these agents. Choose one:"), said(prompts));
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
        assertTrue(box.contains("2 steps left"), box);
        assertTrue(box.indexOf("1. Add Claude Code's account:") < box.indexOf("isx account add claude"), box);
        assertTrue(box.indexOf("isx account add claude") < box.indexOf("2. Open Claude Code in a new isx machine:"), box);
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
        assertEquals("Choice [N]: ",
                prompts.questions().getLast());
        assertNull(SpawnConfig.load().gitIdentity());
        assertEquals(new InitCommand.FirstRunAnswers(false, false, true, true), init.answers);

        var recorded = RecordingOutput.during(() -> init.finishFirstRun(false, true, false));
        assertTrue(String.join("\n", recorded.frameLines()).contains("refuses to commit until it has a"));
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
        assertEquals("Run 'isx branch my-first --from tpl-agent' now? (Y/n): ", prompts.questions().getLast());
        assertEquals(List.of("tpl-agent"), init.built);
        assertEquals(List.of("tpl-agent as my-first"), init.opened);
    }

    private static InitCommand.Closing closing(InitCommand.Services services, boolean built, boolean buildFailed,
                                               String agent, String accountCommand, List<String> firstStart) {
        return new InitCommand.Closing(services, agent == null ? "tpl-minimal" : "tpl-agent", built, buildFailed, agent,
                accountCommand, firstStart, true, "my-first-12", true);
    }

    private static List<String> plain(List<String> lines) {
        return lines.stream().map(InitFirstRunTest::drawn).toList();
    }

    /** Ready: the result and the one next command in the box, then what the person may want later. */
    @Test
    void aReadyRunEndsWithTheOneCommandAndWhatComesAfter() {
        var message = InitCommand.firstRunNextSteps(closing(InitCommand.Services.INSTALLED, true, false, "Claude Code", null, List.of()));
        var box = String.join("\n", plain(message.box()));
        var later = String.join("\n", plain(message.later()));

        assertEquals("Setup complete", message.title());
        assertTrue(box.contains("tpl-agent is built: a template that makes isx machines\n   with Claude Code."), box);
        assertTrue(box.contains("   Open Claude Code in a new isx machine:\n     isx branch my-first-12 --from tpl-agent"), box);
        assertTrue(later.lines().anyMatch(l -> l.strip().startsWith("Return to it ") && l.endsWith(" isx shell my-first-12")), later);
        assertTrue(later.lines().anyMatch(l -> l.strip().startsWith("Change tpl-agent ") && l.endsWith(" isx templates edit tpl-agent")), later);
        assertTrue(later.contains("https://isx.run/docs/templates"), later);
        assertTrue(later.lines().anyMatch(l -> l.strip().startsWith("Set up more ") && l.endsWith(" isx init")), later);
        // The one line that bridges the first run's word to the rest of isx's
        assertTrue(later.endsWith("isx commands and docs call an isx machine an \"instance\"."), later);
    }

    /** Everything left at once: numbered in the order that works, and never called complete. */
    @Test
    void whatIsLeftIsNumberedInTheOrderItIsDone() {
        var message = InitCommand.firstRunNextSteps(closing(InitCommand.Services.START_PROXY, false, true,
                "Antigravity CLI", "isx account add gemini", List.of("agy then prints a sign-in link.")));
        var box = String.join("\n", plain(message.box()));

        assertNotEquals("Setup complete", message.title());
        assertTrue(box.contains("tpl-agent did not build."), box);
        assertEquals("4 steps left", message.title());
        var order = List.of("1. Start the proxy:", "isx proxy start", "the proxy keeps this terminal",
                "2. Fix the error above, then build tpl-agent:", "isx build tpl-agent",
                "3. Add Antigravity CLI's account:", "isx account add gemini",
                "4. Open Antigravity CLI in a new isx machine:", "isx branch my-first-12 --from tpl-agent",
                "agy then prints a sign-in link.");
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
                    var message = InitCommand.firstRunNextSteps(closing(services, built, !built, agent,
                            agent == null ? null : "isx account add github", google));
                    for (var line : plain(message.box())) assertTrue(line.length() <= InitCommand.BOX_WIDTH, "too wide: '" + line + "'");
                    // Under the box, at the step indent
                    for (var line : plain(message.later())) assertTrue(line.length() + 4 <= InitCommand.BOX_WIDTH + 2, "too wide: '" + line + "'");
                }
            }
        }
    }

    /**
     * A framed line that holds a link, as a terminal draws it (#2528): with escapes on, a link is
     * an OSC 8 string whose URL the terminal does not draw, so the line must still be padded to
     * the border. Before the fix the padding counted the escape's bytes, and the line Sanne saw
     * ended short of its border.
     */
    @Test
    void aFramedLineHoldingALinkEndsAtTheBorderAsTheTerminalDrawsIt() {
        String line;
        try (var ansi = dev.incusspawn.util.ForcedAnsi.on()) {
            line = InitCommand.boxLine("     " + dev.incusspawn.util.TerminalLink.link("https://isx.run/docs/templates#adding-your-own"));
        }
        assertTrue(line.contains("\u001B]8;;"), "the line holds no link: " + line);
        var drawn = drawn(line);
        assertEquals(InitCommand.BOX_WIDTH + 2, drawn.length(), "not at the border: '" + drawn + "'");
        assertTrue(drawn.endsWith("│"), "not at the border: '" + drawn + "'");
    }

    /** Every framed line of the closing box, drawn with escapes on, ends at the border. */
    @Test
    void everyLineOfTheClosingBoxEndsAtItsBorderAsTheTerminalDrawsIt() throws Exception {
        var init = new FirstRunInit();
        init.found = List.of(CLAUDE_SUBSCRIPTION);
        init.credentialGiven = true;
        init.gitHubGiven = true;
        init.prompts = ScriptedPrompts.lines("", "n");
        RecordingOutput.during(init::askFirstRunQuestions);

        List<String> frame;
        try (var ansi = dev.incusspawn.util.ForcedAnsi.on()) {
            frame = RecordingOutput.during(() -> init.finishFirstRun(false, true, false)).frameLines();
        }
        var framed = frame.stream().map(InitFirstRunTest::drawn).filter(l -> l.startsWith("│")).toList();
        assertFalse(framed.isEmpty());
        for (var line : framed) {
            assertEquals(InitCommand.BOX_WIDTH + 2, line.length(), "not at the border: '" + line + "'");
            assertTrue(line.endsWith("│"), "not at the border: '" + line + "'");
        }
    }

    /** What a terminal draws of {@code line}: no colour, and of an OSC 8 link only its label. */
    static String drawn(String line) {
        return line.replaceAll("\u001B\\]8;;[^\u001B\u0007]*(\u001B\\\\|\u0007)", "")
                .replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
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
        // No agent was chosen in this run: the line says what was kept, never "your agent" (#2528)
        assertTrue(said.contains("Your accounts are saved."), said);
        assertTrue(said.contains("run\n'isx init' again"), said);
        assertEquals(List.of(), init.built);
    }

    /** A run that kept nothing (no agent, GitHub None for now) says only how to start again (#2528). */
    @Test
    void aFailedStepAfterNothingWasKeptSaysOnlyHowToStartAgain() throws Exception {
        var init = new FirstRunInit() {
            @Override InitStep dnsStep() {
                return new InitStep("dns", "DNS Configuration", new String[0], () -> { throw new IllegalStateException("no resolver"); }, null);
            }
        };
        init.continued = true;
        init.handedOver = new InitCommand.FirstRunAnswers(true, false, true, true);
        init.prompts = ScriptedPrompts.lines();

        var recorded = RecordingOutput.during(() -> assertThrows(IllegalStateException.class, () -> init.runFirstRun(false)));

        var said = String.join("\n", recorded.lines());
        assertFalse(said.contains("saved"), said);
        assertTrue(said.contains("Fix the error below, then run 'isx init' again. It asks\nits questions again."), said);
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
                .filter(w -> w.contains("isx did not build tpl-agent")).findFirst().orElseThrow(() -> new AssertionError(recorded.events().toString()));
        assertTrue(warning.contains("/tmp/a-clone"), warning);
        assertTrue(warning.contains(".incus-spawn/ of its own."), warning);
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
                && e.text().contains("isx did not build tpl-agent")), recorded.events().toString());
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
                && e.text().contains("isx did not build tpl-agent")), recorded.events().toString());
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
        assertTrue(box.contains("refuses to commit until it has a"), box);
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

    /** The gh login is a choice the person types: Enter is asked again and never takes it, and None for now is an option (#2528). */
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
        var prompts = ScriptedPrompts.lines("", "3");

        RecordingOutput.during(() -> assertFalse(setup.offerGitHubForAgent(SpawnConfig.load(), prompts, "jdoe")));

        prompts.assertFullyConsumed();
        assertFalse(read.get());
        var said = said(prompts);
        assertTrue(said.contains("2. Your own account, jdoe"), said);
        assertTrue(said.contains("appears as yours, with all of your access."), said);
        assertTrue(said.contains("3. None for now"), said);
        assertEquals(List.of("Choice: ", "Choice: "), prompts.questions());
        IsolatedHome.assertNothingSaved();
    }

    /** For Copilot CLI, the gh-login option says which of gh's tokens it takes (#2537). */
    @Test
    void forCopilotTheGhLoginSaysWhichTokenCopilotTakes() throws Exception {
        var setup = new CredentialSetup();
        setup.forCopilot = true;
        var prompts = ScriptedPrompts.lines("3");

        RecordingOutput.during(() -> assertFalse(setup.offerGitHubForAgent(SpawnConfig.load(), prompts, "jdoe")));

        var said = said(prompts);
        assertTrue(said.contains("Copilot CLI does not take a classic token from gh."), said);
        IsolatedHome.assertNothingSaved();
    }

    /** For Copilot CLI, the token guide says the token page does not add Copilot Requests (#2537). */
    @Test
    void forCopilotTheTokenGuideNamesCopilotRequests() throws Exception {
        var setup = new CredentialSetup();
        setup.forCopilot = true;
        // The agent's own token, Enter goes back to the options, then None for now
        var prompts = ScriptedPrompts.lines("1").secret("").line("2");

        RecordingOutput.during(() -> assertFalse(setup.offerGitHubForAgent(SpawnConfig.load(), prompts, null)));

        var said = said(prompts);
        assertTrue(said.contains("Copilot CLI also needs \"Copilot Requests\""), said);
        IsolatedHome.assertNothingSaved();
    }

    /** Another agent's token guide does not mention Copilot. */
    @Test
    void theTokenGuideNamesNoCopilotPermissionForAnotherAgent() throws Exception {
        var setup = new CredentialSetup();
        var prompts = ScriptedPrompts.lines("1").secret("").line("2");

        var output = RecordingOutput.during(() -> assertFalse(setup.offerGitHubForAgent(SpawnConfig.load(), prompts, null)));

        var said = said(prompts);
        assertFalse(said.contains("Copilot"), said);
        assertTrue(output.events().stream().noneMatch(e -> e.text().contains("Copilot")), output.events()::toString);
        IsolatedHome.assertNothingSaved();
    }

    /** The gh login's token is read by its prefix: a classic one is named as not taken by Copilot CLI. */
    @Test
    void forCopilotAClassicGhTokenIsSavedWithAWarning() throws Exception {
        var output = choosingGhLoginFor("ghp_" + "a".repeat(36));

        var warns = output.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warns.size(), warns::toString);
        assertTrue(warns.get(0).msg().contains("it is a classic token"), warns::toString);
    }

    @Test
    void forCopilotAFineGrainedGhTokenNamesCopilotRequests() throws Exception {
        var output = choosingGhLoginFor("github_pat_" + "a".repeat(30));

        var warns = output.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warns.size(), warns::toString);
        assertTrue(warns.get(0).msg().contains("'Copilot Requests' permission"), warns::toString);
    }

    @Test
    void forCopilotABrowserSignInsTokenIsSavedWithoutAWarning() throws Exception {
        var output = choosingGhLoginFor("gho_" + "a".repeat(36));

        assertEquals(List.of(), output.events(RecordingOutput.Kind.WARN));
    }

    /** A pasted classic token is saved, and named as not taken by Copilot CLI. */
    @Test
    void forCopilotAPastedClassicTokenIsSavedWithAWarning() throws Exception {
        var setup = new CredentialSetup() {
            @Override
            GitHubVerifyResult verifyGitHubToken(String t, Prompts p) {
                return new GitHubVerifyResult("jdoe-bot", "bot@example.com");
            }
        };
        setup.forCopilot = true;
        var prompts = ScriptedPrompts.lines("1").secret("ghp_" + "a".repeat(36));

        var output = RecordingOutput.during(() -> assertTrue(setup.offerGitHubForAgent(SpawnConfig.load(), prompts, null)));

        var warns = output.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warns.size(), warns::toString);
        assertTrue(warns.get(0).msg().contains("it is a classic token"), warns::toString);
        assertEquals("ghp_" + "a".repeat(36), savedGitHubToken());
    }

    /**
     * The agent's GitHub account step follows the agent the build installs: a kept tpl-agent
     * listing Copilot CLI gets its guidance though another agent was chosen, and the reverse not.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"copilot, claude, true", "claude, copilot, false"})
    void theAgentsGitHubStepFollowsTheTemplatesAgent(String kept, String chosen, boolean copilot) throws Exception {
        var asked = new ArrayList<Boolean>();
        var init = new InitCommand() {
            @Override Prompts prompts() { return ScriptedPrompts.lines(); }
            @Override String ghLogin() { return null; }

            @Override
            Map<CredentialCheck.Missing, List<String>> agentGaps() {
                return Map.of(new CredentialCheck.Missing("GitHub token", "isx account add github"), List.of("tpl-agent"));
            }
        };
        init.credentials = new CredentialSetup() {
            @Override
            boolean offerGitHubForAgent(SpawnConfig config, Prompts prompts, String login) {
                asked.add(forCopilot);
                return false;
            }
        };
        var agents = init.codingAgentTools();
        init.chosenAgent = agents.stream().filter(a -> a.name().equals(chosen)).findFirst().orElseThrow();
        Files.createDirectories(ExampleTemplate.file().getParent());
        Files.writeString(ExampleTemplate.file(),
                ExampleTemplate.text(agents.stream().filter(a -> a.name().equals(kept)).findFirst().orElseThrow()));

        RecordingOutput.during(init::setUpAgentCredential);

        assertEquals(List.of(copilot), asked);
    }

    /** Chooses the gh login, holding {@code token}, for Copilot CLI; the token is saved. */
    private static RecordingOutput choosingGhLoginFor(String token) throws Exception {
        var setup = new CredentialSetup() {
            @Override
            String readGhAuthToken() {
                return token;
            }

            @Override
            GitHubVerifyResult verifyGitHubToken(String t, Prompts p) {
                return new GitHubVerifyResult("jdoe", "jdoe@example.com");
            }
        };
        setup.forCopilot = true;
        var prompts = ScriptedPrompts.lines("2");
        var output = RecordingOutput.during(() -> assertTrue(setup.offerGitHubForAgent(SpawnConfig.load(), prompts, "jdoe")));
        prompts.assertFullyConsumed();
        assertEquals(token, savedGitHubToken());
        return output;
    }

    /** The token config.yaml holds for the GitHub account the first run saved. */
    private static String savedGitHubToken() {
        return dev.incusspawn.config.AccountResolver.navigate(SpawnConfig.load().tree(), "github.accounts.default.token");
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
