package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The steps a person runs by name, and the menu a re-run shows of them (#2407). */
class InitDeferredStepsTest {

    /** Init whose deferred steps and Credential Setup only record that they ran, answering from {@code prompts}. */
    static class DeferredInit extends InitCommand {
        final List<String> ran = new ArrayList<>();
        Prompts prompts;

        @Override
        Prompts prompts() {
            return prompts;
        }

        @Override
        List<InitStep> credentialSteps() {
            ran.add("credentials");
            startStep("Credential Setup");
            return List.of();
        }

        @Override
        int addCredential(String name) {
            ran.add("credentials");
            return 0;
        }

        @Override
        void installGitRemoteShim() {
        }

        @Override
        void offerAgentGitIdentity() {
            ran.add("git-identity");
        }

        @Override
        InitStep deferredStep(String id) {
            return step(id);
        }

        InitStep step(String id) {
            return new InitStep(id, "Step " + id, new String[0], () -> ran.add(id), InitPlan.Assessment::done);
        }
    }

    @Test
    void aRerunAsksNoneOfTheDeferredQuestions() throws Exception {
        var init = new DeferredInit();
        init.rerun = true;
        var finish = List.of(init.step("search-paths"), init.step("code-directories"), init.step("mcp"), init.step("proxy-service"));
        var frames = String.join("\n", RecordingOutput.during(() -> init.runSteps(List.of(), finish)).frameLines());
        assertEquals(List.of("proxy-service"), init.ran);
        assertTrue(frames.contains("[1/1]"), frames);
    }

    @Test
    void aFirstRunStillAsksThemInOrder() throws Exception {
        var init = new DeferredInit();
        var finish = List.of(init.step("search-paths"), init.step("code-directories"), init.step("mcp"), init.step("proxy-service"));
        RecordingOutput.during(() -> init.runSteps(List.of(), finish));
        assertEquals(List.of("credentials", "git-identity", "search-paths", "code-directories", "mcp", "proxy-service"), init.ran);
    }

    @Test
    void theMenuRunsWhatIsChosenUntilEnter() throws Exception {
        var init = new DeferredInit();
        init.rerun = true;
        init.prompts = ScriptedPrompts.lines("2", "mcp", "9", "");
        var recorded = RecordingOutput.during(init::offerMenu);
        assertEquals(List.of("search-paths", "mcp"), init.ran);
        var said = recorded.events().stream().filter(e -> e.msg() != null).map(RecordingOutput.Event::msg).toList();
        assertTrue(said.stream().anyMatch(l -> l.startsWith("1  credentials") && l.contains("done")), said.toString());
        assertTrue(said.contains("No step '9'."), said.toString());
    }

    @Test
    void noMenuWithoutATerminalOrForAnotherCommand() throws Exception {
        var init = new DeferredInit();
        init.rerun = true;
        RecordingOutput.during(init::offerMenu);
        init.prompts = ScriptedPrompts.lines("1", "");
        init.automatic = true;
        RecordingOutput.during(init::offerMenu);
        assertEquals(List.of(), init.ran);
    }

    @Test
    void oneStepRunsAloneByName() throws Exception {
        var init = new DeferredInit();
        init.prompts = ScriptedPrompts.lines();
        RecordingOutput.during(() -> assertEquals(0, init.runOne("code-directories")));
        RecordingOutput.during(() -> assertEquals(0, init.runOne("credentials")));
        // Credential Setup is followed by the Git identity question there too (#2410)
        assertEquals(List.of("code-directories", "credentials", "git-identity"), init.ran);
    }

    @Test
    void credentialsAloneNeedATerminal() throws Exception {
        var init = new DeferredInit();
        var recorded = RecordingOutput.during(() -> assertEquals(1, init.runOne("credentials")));
        assertEquals(List.of(), init.ran);
        assertTrue(recorded.events().stream().anyMatch(e -> e.kind() == RecordingOutput.Kind.ERROR), recorded.events().toString());
    }

    @Test
    void aStepThatDoesNotRunAloneIsRefused() throws Exception {
        var init = new DeferredInit();
        var recorded = RecordingOutput.during(() -> assertEquals(1, init.runOne("firewall")));
        assertEquals(List.of(), init.ran);
        assertTrue(recorded.events().stream().anyMatch(e -> e.kind() == RecordingOutput.Kind.ERROR), recorded.events().toString());
    }

    @Test
    void theMenuTakesANumberOrAName() {
        assertEquals("credentials", InitCommand.menuChoice("1"));
        assertEquals("mcp", InitCommand.menuChoice("4"));
        assertEquals("search-paths", InitCommand.menuChoice("search-paths"));
        assertEquals("mcp", InitCommand.menuChoice("MCP"));
        assertNull(InitCommand.menuChoice("0"));
        assertNull(InitCommand.menuChoice("dns"));
    }

    /** A step by name refuses an answer flag it does not read, rather than dropping it unsaid. */
    @Test
    void aStepRefusesAnotherStepsAnswer() {
        var init = new InitCommand();
        init.codeDirAnswers = List.of("/tmp");
        assertEquals(List.of("--code-dir"), init.flagsNotFor("mcp"));
        assertEquals(List.of(), init.flagsNotFor("code-directories"));
        init.gitIdentityFromHost = true;
        init.forkTemplates = true;
        assertEquals(List.of("--fork-templates", "--git-identity-from-host"), init.flagsNotFor("code-directories"));
        assertEquals(List.of("--fork-templates", "--code-dir"), init.flagsNotFor("credentials"));
    }
}
