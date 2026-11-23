package dev.incusspawn.command;

import dev.incusspawn.config.GitIdentity;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The first run's account step for the agents that are not Claude Code (#2528), run with the real
 * credential steps: what a Pi, Copilot, Antigravity CLI or Bob Shell user is told and asked.
 */
@ExtendWith(IsolatedHome.class)
class InitFirstRunAccountsTest {

    /** No credential in the environment, no browser, no gh login. */
    static class Host extends CredentialSetup {
        @Override String env(String name) { return ""; }
        @Override boolean hostHasCommand(String command) { return false; }
        @Override boolean openUrl(String url) { return false; }
        @Override boolean ghCliLoggedIn() { return false; }
        @Override String readGhAuthToken() { return null; }
    }

    static class Init extends InitCommand {
        Prompts prompts;
        @Override Prompts prompts() { return prompts; }
        @Override List<HostAgentProbe.HostAgent> probeAgents() { return List.of(); }
        @Override String ghLogin() { return null; }
        @Override GitIdentity hostGitIdentity() { return null; }
        @Override boolean continuedInGroup() { return false; }
        @Override void startDownloadsAhead(ToolSetup agent) { }
    }

    /** The questions for {@code agent}, chosen from the list, then answered with {@code answers} and end of input. */
    private static List<String> ask(String agent, ScriptedPrompts answers, Init init) {
        init.credentials = new Host();
        var agents = init.codingAgentTools();
        var number = agents.indexOf(agents.stream().filter(a -> a.name().equals(agent)).findFirst().orElseThrow()) + 1;
        var prompts = ScriptedPrompts.lines(String.valueOf(number));
        init.prompts = new Prompts() {
            boolean chosen;
            @Override public String readLine() {
                if (chosen) return answers.readLine();
                chosen = true;
                return prompts.readLine();
            }
            @Override public char[] readPassword() { return answers.readPassword(); }
        };
        return RecordingOutput.during(init::askFirstRunQuestions).lines();
    }

    private static int count(List<String> lines, String text) {
        return (int) lines.stream().filter(l -> l.contains(text)).count();
    }

    private static int at(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(text)) return i;
        return fail("'" + text + "' is not in:\n" + String.join("\n", lines));
    }

    /**
     * Pi is asked about Claude: the step says why first, and where another provider is set up,
     * with no template syntax before a template was explained (#2528).
     */
    @Test
    void anAgentWithNoAccountOfItsOwnIsToldWhoseItUses() {
        for (var agent : List.of("pi", "opencode")) {
            IsolatedHome.configFile().toFile().delete();
            var lines = ask(agent, ScriptedPrompts.lines("4"), new Init());

            var whose = at(lines, "works with several providers, and isx sets it up");
            assertTrue(at(lines, "with a Claude account first.") == whose + 1, String.join("\n", lines));
            assertTrue(whose < at(lines, "Which kind of Claude account do you have?"), String.join("\n", lines));
            assertTrue(at(lines, "https://isx.run/docs/agents#") > whose, String.join("\n", lines));
            assertEquals(0, count(lines, "{provider:"), String.join("\n", lines));
        }
    }

    /** Copilot spends GitHub's account: skipped at its step, the GitHub question is not asked a second time. */
    @Test
    void anAgentSpendingGitHubIsAskedForItOnceWhenSkipped() {
        var init = new Init();
        // With no gh login, None for now is the second option
        var lines = ask("copilot", ScriptedPrompts.lines("2"), init);

        assertEquals(1, count(lines, "As which GitHub account should the agent act?"), String.join("\n", lines));
        // Said here, since the GitHub step's own header, which says it, is never shown on this path
        assertTrue(at(lines, "GitHub Copilot CLI signs in with a GitHub account, and the") < at(lines, "As which GitHub account"),
                String.join("\n", lines));
        assertEquals(0, count(lines, "GitHub Account"), String.join("\n", lines));
        // The counter matches the run: the agent, then its account, which is the GitHub question (#2528)
        assertEquals(1, count(lines, "[2/2]"), String.join("\n", lines));
        assertEquals(0, count(lines, "[3/"), String.join("\n", lines));
        assertEquals(0, count(lines, "set up already"), String.join("\n", lines));
        assertTrue(init.answers.noCredential());
        assertTrue(init.answers.noGitHub());
        assertEquals(List.of(), dev.incusspawn.config.AccountResolver.usableAccountNames(
                dev.incusspawn.config.SpawnConfig.load(), dev.incusspawn.tool.GhSetup.NAMESPACE));
    }

    /** The kind of account is a list like every other, naming the credential rather than "a credential". */
    @Test
    void theKindOfAccountIsChosenFromANamedList() {
        // The key, then Enter at it, which goes back to the kinds (#2528); end of input ends there
        var lines = ask("antigravity", new ScriptedPrompts().line("1").secret(""), new Init());

        var question = at(lines, "Which kind of Gemini account do you have? Choose one:");
        assertTrue(at(lines, "Antigravity CLI uses a Gemini account.") < question, String.join("\n", lines));
        assertTrue(at(lines, "1. A Gemini API key") > question, String.join("\n", lines));
        assertTrue(at(lines, "2. A Google account") > question, String.join("\n", lines));
        assertTrue(at(lines, "3. None for now") > question, String.join("\n", lines));
        assertEquals(2, count(lines, "Which kind of Gemini account do you have?"), String.join("\n", lines));
        assertEquals(0, count(lines, "A credential entered here"), String.join("\n", lines));
    }

    /** A key left blank is not a credential: the consent saved after it is not reported as one. */
    @Test
    void aSkippedKeyIsNotSaidToBeSaved() {
        var lines = ask("bob", new ScriptedPrompts().secret("").line(""), new Init());

        assertEquals(0, count(lines, "Saved on"), String.join("\n", lines));
        assertEquals(1, count(lines, "Saved your answer to \"Accept IBM license terms\""), String.join("\n", lines));
        assertEquals(1, count(lines, "Add one later with 'isx account add bob'."), String.join("\n", lines));
    }
}
