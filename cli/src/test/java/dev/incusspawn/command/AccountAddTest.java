package dev.incusspawn.command;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import static dev.incusspawn.command.IsolatedHome.*;
import static dev.incusspawn.util.BuildOutput.stripAnsi;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx account add} configures one credential with the step {@code isx init} runs for it,
 * and runs nothing else: no package, firewall or service step, so no sudo (#1469).
 */
@ExtendWith(IsolatedHome.class)
class AccountAddTest {

    private static final Pattern COUNTER = Pattern.compile("\\[(\\d+)/(\\d+)]");

    /** Init whose credential steps read {@code prompts}; every other step would fail the test. */
    static final class ScriptedInit extends InitCommand {
        Prompts prompts;

        @Override
        Prompts prompts() {
            return prompts;
        }

        @Override
        int runHostQuiet(String... command) {
            throw new AssertionError("account add ran a host command: " + String.join(" ", command));
        }
    }

    private record Run(int exit, String out, String err) {}

    /** The exit code, and what the flow reported, split as a terminal splits it. */
    private static Run add(String credential, Prompts prompts) {
        var init = new ScriptedInit();
        init.prompts = prompts;
        var exit = new int[1];
        var reported = InitStepOutputTest.capture(() -> exit[0] = init.addCredential(credential));
        return new Run(exit[0], stripAnsi(reported.out()), stripAnsi(reported.err()));
    }

    /** A user's own YAML tool, owning the namespace {@code acmeCloud}, as init would find it. */
    private static void acme() throws Exception {
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
                """);
    }

    @Test
    void theNamespaceRunsThatCredentialsStepAlone() throws Exception {
        acme();
        var prompts = new ScriptedPrompts().secret("acme-secret");

        var run = add("acmeCloud", prompts);

        assertEquals(0, run.exit(), run.err());
        prompts.assertFullyConsumed();
        assertEquals("acme-secret", saved("acmeCloud.accounts.default.apiKey"));
        var counter = COUNTER.matcher(run.out());
        assertTrue(counter.find(), run.out());
        assertEquals("[1/1]", counter.group(), run.out());
        assertFalse(counter.find(), "only one step: " + run.out());
        assertFalse(run.out().contains("Credential Setup"), run.out());
    }

    @Test
    void theToolNameWorksToo() throws Exception {
        acme();
        var prompts = new ScriptedPrompts().secret("acme-secret");

        assertEquals(0, add("acme", prompts).exit());
        assertEquals("acme-secret", saved("acmeCloud.accounts.default.apiKey"));
    }

    /** A configured credential opens on init's account menu, so named accounts are added the same way. */
    @Test
    void aSecondAccountIsAddedThroughTheSameMenu() throws Exception {
        acme();
        seed("acmeCloud:\n  apiKey: \"acme-first\"\n");
        var prompts = new ScriptedPrompts().line("a", "work").secret("acme-work");

        assertEquals(0, add("acmeCloud", prompts).exit());
        prompts.assertFullyConsumed();
        assertEquals("acme-first", saved("acmeCloud.accounts.default.apiKey"));
        assertEquals("acme-work", saved("acmeCloud.accounts.work.apiKey"));
    }

    /** Without a name it is init's credential menu, then the steps picked there. */
    @Test
    void withoutANameItOffersInitsCredentialMenu() throws Exception {
        acme();
        var run = add(null, ScriptedPrompts.lines(""));

        assertEquals(0, run.exit(), run.err());
        assertTrue(run.out().contains("Credential Setup"), run.out());
        assertTrue(run.out().contains("Acme Cloud CLI"), run.out());
        assertNothingSaved();
    }

    @Test
    void anUnknownNameIsRefusedWithTheNamesThereAre() throws Exception {
        acme();
        var run = add("nope", new ScriptedPrompts());

        assertEquals(1, run.exit());
        assertTrue(run.err().contains("'isx account add' does not know 'nope'"), run.err());
        assertTrue(run.err().contains("acmeCloud (acme)"), run.err());
        assertTrue(run.err().contains("github (gh)"), run.err());
        assertTrue(run.err().contains("claude"), run.err());
        assertNothingSaved();
    }

    /** A tool with a step in a namespace another step owns is reached, and listed, by its own name. */
    @Test
    void aToolInAnOwnedNamespaceIsListedByItsName() throws Exception {
        var dir = Files.createDirectories(Environment.configDir().resolve("tools"));
        Files.writeString(dir.resolve("aider.yaml"), """
                name: aider
                proxy:
                  config-namespace: openai
                  configuration:
                    org: { config-path: org, description: OpenAI organization, secret: true }
                """);
        var run = add("nope", new ScriptedPrompts());

        assertTrue(run.err().contains(" aider,"), run.err());
        assertFalse(run.err().contains("openai (aider)"), run.err());
        assertTrue(run.err().contains("openai (codex)"), run.err());
    }

    /** A tool that borrows another's credential has no step: its owner's is the one to run. */
    @Test
    void aToolThatBorrowsItsCredentialIsNotAName() throws Exception {
        var run = add("copilot", new ScriptedPrompts());

        assertEquals(1, run.exit());
        assertTrue(run.err().contains("copilot has no credential of its own."), run.err());
        assertTrue(run.err().contains("It uses github's: run 'isx account add github'."), run.err());
    }

    /** A tool that spends several others' credentials names every one, not only the first. */
    @Test
    void aToolThatBorrowsSeveralCredentialsNamesThemAll() throws Exception {
        var run = add("pi", new ScriptedPrompts());

        assertEquals(1, run.exit());
        var err = run.err().replaceAll("\\s+", " ");
        assertTrue(err.contains("pi has no credential of its own."), err);
        assertTrue(err.contains("It uses the credentials of claude, openai and gemini"), err);
    }

    @Test
    void withoutATerminalItSaysSoAndChangesNothing() throws Exception {
        acme();
        var run = add("acmeCloud", null);

        assertEquals(1, run.exit());
        assertTrue(run.err().contains("needs a terminal"), run.err());
        assertNothingSaved();
    }
}
