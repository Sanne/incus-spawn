package dev.incusspawn.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.incusspawn.Environment;
import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.ai.TestApiErrors;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.util.RecordingOutput.Kind;
import org.aesh.command.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A failing AI Help account is not a dead end (#2605): the error names it, and on a terminal isx
 * asks before another account is billed for the question, or offers to add one.
 */
@ExtendWith(IsolatedHome.class)
class AskRecoveryTest {

    private static final String TWO = """
            claude:
              accounts:
                work:
                  apiKey: sk-ant-api03-work
            gemini:
              accounts:
                free:
                  apiKey: AIza-free
            """;

    private static final String ONE = """
            gemini:
              accounts:
                free:
                  apiKey: AIza-free
            """;

    private static final String QUOTA = "API error (429): You exceeded your current quota";

    /** {@link AskCommand} whose accounts answer, or fail, as each test says, at the prompts it is given. */
    static class Asking extends AskCommand {
        final List<String> asked = new ArrayList<>();
        final Map<String, AiHelpClient.ApiError> failures = new HashMap<>();
        ScriptedPrompts prompts;

        Asking() {
            this.questionWords = List.of("how?");
        }

        Asking(ScriptedPrompts prompts) {
            this();
            this.prompts = prompts;
        }

        Asking failing(String selection, int status) {
            failures.put(selection, TestApiErrors.of(status, status == 429 ? QUOTA : "API error (" + status + ")"));
            return this;
        }

        @Override
        AiHelpClient.StreamEnd send(String question, List<String> systemBlocks, SpawnConfig config,
                                    AiHelpClient.Target target, Consumer<String> chunks) throws AiHelpClient.ApiError {
            asked.add(target.selection());
            var failure = failures.get(target.selection());
            if (failure != null) throw failure;
            chunks.accept("Like this.");
            return new AiHelpClient.StreamEnd(AiHelpClient.Usage.NONE, null);
        }

        @Override
        Prompts prompts() {
            return prompts;
        }

        @Override
        CredentialSetup credentials() {
            return new ClaudeAuthFlowTest.FakeInit().acceptKey("AIza-paid");
        }

        RecordingOutput run(CommandResult expected) {
            return RecordingOutput.during(() -> assertEquals(expected, doExecute()));
        }
    }

    private static String recorded() throws Exception {
        var file = Environment.aiHelpAccountFile();
        return Files.exists(file) ? Files.readString(file).strip() : "";
    }

    /** At a terminal a yes sends the same question to the other account, which then answers first next time. */
    @Test
    void aYesAsksTheOtherAccountWhichIsAskedFirstNextTime() throws Exception {
        IsolatedHome.seed(TWO);
        var prompts = ScriptedPrompts.lines("y");
        var ask = new Asking(prompts).failing("claude=work", 429);
        var out = ask.run(CommandResult.SUCCESS);

        assertEquals(List.of("claude=work", "gemini=free"), ask.asked);
        prompts.assertFullyConsumed();
        assertTrue(String.join("\n", prompts.conversation()).contains("Ask free (Gemini API key) instead? (y/N)"),
                prompts.conversation()::toString);
        assertEquals("The claude account 'work' did not answer: " + QUOTA, out.events(Kind.WARN).getFirst().msg());
        assertEquals("gemini=free", recorded());
    }

    /** Enter declines: nothing else is sent, and the error names the option that asks the other account. */
    @Test
    void enterSendsNothingMore() throws Exception {
        IsolatedHome.seed(TWO);
        var ask = new Asking(ScriptedPrompts.lines("")).failing("claude=work", 403);
        var out = ask.run(CommandResult.valueOf(1));

        assertEquals(List.of("claude=work"), ask.asked);
        var error = out.events(Kind.ERROR).getFirst();
        assertEquals(AiHelpRecovery.NOT_ASKED, error.msg());
        assertEquals(List.of("Ask another account with --account gemini=free."), error.details());
        assertEquals("", recorded());
    }

    /** Each account is offered once: when the second fails too, nothing is left to ask, so isx offers to add one. */
    @Test
    void anAccountIsNeverOfferedTwice() throws Exception {
        IsolatedHome.seed(TWO);
        var prompts = ScriptedPrompts.lines("y", "n");
        var ask = new Asking(prompts).failing("claude=work", 429).failing("gemini=free", 401);
        ask.run(CommandResult.valueOf(1));

        assertEquals(List.of("claude=work", "gemini=free"), ask.asked);
        prompts.assertFullyConsumed();
        assertTrue(prompts.conversation().getLast().contains("Add another account to answer? (y/N)"),
                prompts.conversation()::toString);
    }

    /** With no other account, the menu of the first use adds one, and the question goes to it. */
    @Test
    void theSoleAccountFailingOffersToAddOneAndAsksIt() throws Exception {
        IsolatedHome.seed(ONE);
        var prompts = new ScriptedPrompts().line("y", "4", "paid").secret("AIza-paid");
        var ask = new Asking(prompts).failing("gemini=free", 429);
        ask.run(CommandResult.SUCCESS);

        assertEquals(List.of("gemini=free", "gemini=paid"), ask.asked);
        prompts.assertFullyConsumed();
        assertEquals("gemini=paid", recorded());
    }

    /** Off a terminal nothing is asked: the error names the account and the commands that add another. */
    @Test
    void offATerminalTheErrorSaysWhatToDo() throws Exception {
        IsolatedHome.seed(ONE);
        var ask = new Asking(null).failing("gemini=free", 429);
        var out = ask.run(CommandResult.valueOf(1));

        assertEquals(List.of("gemini=free"), ask.asked);
        var error = out.events(Kind.ERROR).getFirst();
        assertEquals("the gemini account 'free' did not answer: " + QUOTA, error.msg());
        assertEquals(List.of(AiHelpClient.ADD_ONE), error.details());
    }

    /** A named account is never replaced, and an error that is not the account's offers nothing. */
    @Test
    void aNamedAccountOrAServerErrorOffersNothing() throws Exception {
        IsolatedHome.seed(TWO);
        var pinned = new Asking(new ScriptedPrompts()).failing("claude=work", 429);
        pinned.account = "claude=work";
        var out = pinned.run(CommandResult.valueOf(1));
        assertEquals(List.of("claude=work"), pinned.asked);
        assertEquals(List.of("Ask another account with --account gemini=free."),
                out.events(Kind.ERROR).getFirst().details());

        var server = new Asking(new ScriptedPrompts()).failing("claude=work", 500);
        server.run(CommandResult.valueOf(1));
        assertEquals(List.of("claude=work"), server.asked);
    }

    /** {@code isx doctor --explain} recovers the same way: a yes sends the findings to the other account. */
    @Test
    void doctorExplainAsksTheOtherAccountOnAYes() throws Exception {
        IsolatedHome.seed(TWO);
        var bundle = new SupportBundle(Map.of());
        bundle.add("findings.txt", "✓ all good\n");
        var asked = new ArrayList<String>();
        var prompts = ScriptedPrompts.lines("y");
        RecordingOutput.during(() -> assertTrue(DoctorExplain.run(bundle, SpawnConfig.load(), null, () -> prompts,
                (question, blocks, config, target, chunks) -> {
                    asked.add(target.selection());
                    if (target.selection().equals("claude=work")) throw TestApiErrors.of(429, QUOTA);
                    chunks.accept("Nothing is wrong.");
                    return new AiHelpClient.StreamEnd(AiHelpClient.Usage.NONE, null);
                }, CredentialSetup::new)));

        assertEquals(List.of("claude=work", "gemini=free"), asked);
        prompts.assertFullyConsumed();
        assertEquals("gemini=free", recorded());
    }
}
