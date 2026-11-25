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
        // As the list shows them with no agent found on this computer
        var agents = InitCommand.agentsInListOrder(init.codingAgentTools(), java.util.Set.of());
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
     * Pi and opencode are asked for their provider first (#2536): Enter keeps Claude's, and the
     * account step then says whose account it is, with no word on choosing another provider.
     */
    @Test
    void anAgentWithNoAccountOfItsOwnIsAskedItsProviderFirst() {
        for (var agent : List.of("pi", "opencode")) {
            IsolatedHome.configFile().toFile().delete();
            var init = new Init();
            var lines = ask(agent, ScriptedPrompts.lines("", "4"), init);

            var provider = at(lines, "should " + (agent.equals("pi") ? "Pi" : "opencode") + " use?");
            assertTrue(provider < at(lines, "uses a Claude account."), String.join("\n", lines));
            assertTrue(at(lines, "uses a Claude account.") < at(lines, "Which kind of Claude account do you have?"), String.join("\n", lines));
            assertEquals(0, count(lines, "works with several providers"), String.join("\n", lines));
            assertEquals(0, count(lines, "{provider:"), String.join("\n", lines));
            assertEquals(java.util.Map.of(), init.agentParams);
        }
    }

    /** Choosing OpenAI sets up an OpenAI account, never a Claude one, and the template names the provider (#2536). */
    @Test
    void aProviderChosenDecidesTheAccountSetUp() throws Exception {
        for (var agent : List.of("pi", "opencode")) {
            IsolatedHome.configFile().toFile().delete();
            var init = new Init();
            // OpenAI, then end of input at its account step
            var lines = ask(agent, ScriptedPrompts.lines("2"), init);

            assertEquals(1, count(lines, "Which kind of OpenAI account do you have?"), String.join("\n", lines));
            assertEquals(0, count(lines, "Which kind of Claude account"), String.join("\n", lines));
            // A ChatGPT session would give Pi and opencode no key (#2525): only the key is offered
            assertEquals(0, count(lines, "ChatGPT"), String.join("\n", lines));
            assertEquals(java.util.Map.of("provider", "openai"), init.agentParams);
            init.prepareFirstTemplate();
            var tools = dev.incusspawn.config.ImageDef.parseYaml(java.nio.file.Files.readString(ExampleTemplate.file())).getTools();
            assertEquals(java.util.Map.of("provider", "openai"), tools.getFirst().getParams(), agent);
            java.nio.file.Files.delete(ExampleTemplate.file());
        }
    }

    /** A provider isx does not manage is typed, checked, and needs no account step (#2536). */
    @Test
    void aProviderIsxDoesNotManageNeedsNoAccount() {
        var init = new Init();
        var lines = ask("pi", ScriptedPrompts.lines("4", "Not A Name", "openrouter"), init);

        assertEquals(1, count(lines, "Use lowercase letters, digits"), String.join("\n", lines));
        assertEquals(1, count(lines, "Pi needs no account from isx for that provider."), String.join("\n", lines));
        assertEquals(0, count(lines, "Pi Account"), String.join("\n", lines));
        assertEquals(0, count(lines, "Which kind of"), String.join("\n", lines));
        assertEquals(java.util.Map.of("provider", "openrouter"), init.agentParams);
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

        var question = at(lines, "Which kind of Gemini account do you have?");
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
        assertEquals(1, count(lines, "Your answer to \"Accept IBM license terms\" is saved."), String.join("\n", lines));
        assertEquals(1, count(lines, "Add one later with 'isx account add bob'."), String.join("\n", lines));
    }
}
