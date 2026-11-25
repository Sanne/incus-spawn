package dev.incusspawn.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.incusspawn.Environment;
import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.util.RecordingOutput.Kind;
import org.aesh.command.CommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** {@code isx ask --account}, and the account that last answered being asked first (#2607). */
@ExtendWith(IsolatedHome.class)
class AskAccountTest {

    private static final String CONFIG = """
            claude:
              default: work
              accounts:
                work:
                  apiKey: sk-ant-api03-work
                personal:
                  oauthToken: a-subscription
                gcp:
                  type: vertex
                  cloudMlRegion: europe-west1
            gemini:
              accounts:
                free:
                  apiKey: AIza-free
                me:
                  type: google
            openai:
              accounts:
                typo:
                  type: apikey
                  apiKey: sk-typo
            """;

    @BeforeEach
    void seed() throws Exception {
        IsolatedHome.seed(CONFIG);
    }

    /** {@link AskCommand} whose questions reach a recorder, or a provider's refusal, rather than the network. */
    static class Asking extends AskCommand {
        final List<String> asked = new ArrayList<>();
        AiHelpClient.ApiError failure;

        Asking() {
            this.questionWords = List.of("how?");
        }

        Asking(String account) {
            this();
            this.account = account;
        }

        @Override
        AiHelpClient.StreamEnd send(String question, List<String> systemBlocks, SpawnConfig config,
                                    AiHelpClient.Target target, Consumer<String> chunks) throws AiHelpClient.ApiError {
            asked.add(target.selection());
            if (failure != null) throw failure;
            chunks.accept("Like this.");
            return new AiHelpClient.StreamEnd(AiHelpClient.Usage.NONE, null);
        }

        @Override
        Prompts prompts() {
            // An account that can answer is configured, so none is asked for: no answer is scripted
            return new ScriptedPrompts();
        }

        CommandResult run() {
            var result = new CommandResult[1];
            RecordingOutput.during(() -> result[0] = doExecute());
            return result[0];
        }
    }

    private static String recorded() throws Exception {
        var file = Environment.aiHelpAccountFile();
        return Files.exists(file) ? Files.readString(file).strip() : "";
    }

    @Test
    void theNamedAccountAnswersAndIsAskedFirstAfterwards() throws Exception {
        var pinned = new Asking("gemini=free");
        assertEquals(CommandResult.SUCCESS, pinned.run());
        assertEquals(List.of("gemini=free"), pinned.asked);
        assertEquals("gemini=free", recorded());

        // Without --account, the account that last answered, not the claude default
        var next = new Asking(null);
        assertEquals(CommandResult.SUCCESS, next.run());
        assertEquals(List.of("gemini=free"), next.asked);
    }

    @Test
    void withNoRecordTheFixedOrderApplies() {
        var ask = new Asking(null);
        assertEquals(CommandResult.SUCCESS, ask.run());
        assertEquals(List.of("claude=work"), ask.asked);
    }

    @Test
    void aRecordedAccountThatIsGoneIsIgnored() throws Exception {
        Files.createDirectories(Environment.aiHelpAccountFile().getParent());
        Files.writeString(Environment.aiHelpAccountFile(), "gemini=removed\n");
        var ask = new Asking(null);
        assertEquals(CommandResult.SUCCESS, ask.run());
        assertEquals(List.of("claude=work"), ask.asked);
        assertEquals("claude=work", recorded());
    }

    @Test
    void anEmptyAnswerIsNotRecorded() throws Exception {
        var ask = new Asking("gemini=free") {
            @Override
            AiHelpClient.StreamEnd send(String question, List<String> systemBlocks, SpawnConfig config,
                                        AiHelpClient.Target target, Consumer<String> chunks) {
                chunks.accept("");
                return new AiHelpClient.StreamEnd(AiHelpClient.Usage.NONE, null);
            }
        };
        assertEquals(CommandResult.SUCCESS, ask.run());
        assertEquals("", recorded());
    }

    @Test
    void anAccountThatCannotAnswerIsRefusedNeverReplaced() throws Exception {
        for (var selection : List.of("claude=personal", "claude=nobody", "free", "github=me")) {
            var ask = new Asking(selection);
            var recordedOutput = RecordingOutput.during(() -> assertEquals(CommandResult.valueOf(1), ask.doExecute()));
            assertEquals(List.of(), ask.asked, selection + " must not fall back to another account");
            var error = recordedOutput.events(Kind.ERROR).getFirst();
            assertTrue(String.join("\n", error.details()).contains("Accounts that can answer: claude=work and gemini=free."),
                    selection + ": " + error);
        }
        assertEquals("", recorded());
    }

    @Test
    void theRefusalsSayWhy() {
        assertEquals("the claude account 'personal' cannot answer: it is a Claude subscription,"
                + " whose token works only in Claude Code", refusal("claude=personal"));
        assertEquals("the claude account 'gcp' cannot answer: its Vertex AI region or project is not set",
                refusal("claude=gcp"));
        assertEquals("the gemini account 'me' cannot answer: it is a Google sign-in, which serves only its own agent",
                refusal("gemini=me"));
        assertEquals("the claude account 'inherit' cannot answer: it takes its credential from the instance this isx runs in",
                refusal("claude=inherit"));
        assertEquals("the openai account 'typo' cannot answer: its type is 'apikey', and only an api-key account answers",
                refusal("openai=typo"));
        assertEquals("there is no claude account 'nobody'", refusal("claude=nobody"));
        assertEquals("--account takes <namespace>=<account>, such as claude=work, not 'free'", refusal("free"));
        assertEquals("'github' accounts cannot answer: AI Help asks a claude, openai or gemini account", refusal("github=me"));
    }

    private static String refusal(String selection) {
        try {
            dev.incusspawn.ai.AiHelpChoice.pinned(SpawnConfig.load(), selection);
        } catch (dev.incusspawn.ai.AiHelpChoice.Refused e) {
            return e.getMessage();
        }
        throw new AssertionError(selection + " was not refused");
    }

    /**
     * An unreadable config.yaml reads as empty, so every account would look missing: the pin is
     * refused with why the file cannot be read, and doctor leaves that to its checks.
     */
    @Test
    void anUnreadableConfigIsNamedNotTakenForAMissingAccount() throws Exception {
        IsolatedHome.seed("claude:\n  accounts: [work\n");
        var config = SpawnConfig.load();
        var reason = config.unreadableReason();
        assertTrue(reason != null && reason.contains("config.yaml"), String.valueOf(reason));

        var ask = new Asking("claude=work");
        var output = RecordingOutput.during(() -> assertEquals(CommandResult.valueOf(1), ask.doExecute()));
        assertEquals(List.of(), ask.asked);
        assertEquals(List.of(new RecordingOutput.Event(Kind.ERROR, reason, "Fix config.yaml, then run the command again.")),
                output.events(Kind.ERROR));

        // Doctor runs its checks, whose config finding names the file first, and refuses the pin after them
        assertEquals(null, DoctorCommand.refusedBeforeChecks(config, "claude=work"));
        var explained = RecordingOutput.during(() -> assertFalse(DoctorExplain.run(
                new SupportBundle(java.util.Map.of()), config, "claude=work",
                () -> { throw new AssertionError("nothing is asked"); })));
        assertEquals(reason, explained.events(Kind.ERROR).getFirst().msg());
    }

    @Test
    void doctorRefusesAMissingAccountBeforeItsChecks() {
        var refused = DoctorCommand.refusedBeforeChecks(SpawnConfig.load(), "claude=nobody");
        assertEquals("there is no claude account 'nobody'", refused.getMessage());
        assertEquals(null, DoctorCommand.refusedBeforeChecks(SpawnConfig.load(), "claude=work"));
    }

    @Test
    void aFailedAccountNamesTheOthersThatCanAnswer() throws Exception {
        var ask = new Asking(null);
        ask.failure = testError(429, "You exceeded your current quota");
        var output = RecordingOutput.during(() -> assertEquals(CommandResult.valueOf(1), ask.doExecute()));
        var error = output.events(Kind.ERROR).getFirst();
        assertEquals(List.of("Ask another account with --account gemini=free."), error.details());
        assertFalse(Files.exists(Environment.aiHelpAccountFile()), "an account that failed is not recorded");
    }

    private static AiHelpClient.ApiError testError(int status, String message) {
        return dev.incusspawn.ai.TestApiErrors.of(status, message);
    }
}
