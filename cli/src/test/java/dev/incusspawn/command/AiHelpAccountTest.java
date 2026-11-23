package dev.incusspawn.command;

import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;

import static dev.incusspawn.command.IsolatedHome.seed;
import static org.junit.jupiter.api.Assertions.*;

/**
 * AI Help asks for an account where it is first used (#2541): {@code isx ask}, {@code ?} in the
 * TUI and {@code isx doctor --explain} with no account that can answer. Every assertion reads
 * config.yaml back from disk.
 */
@ExtendWith(IsolatedHome.class)
class AiHelpAccountTest {

    private static final String API_KEY = "sk-ant-api03-good";
    private static final String SUBSCRIPTION = """
            claude:
              accounts:
                default:
                  type: oauth
                  oauthToken: "sk-ant-oat01-fixture"
              default: default
            """;

    private static SpawnConfig ask(SpawnConfig config, ScriptedPrompts prompts) {
        var answered = AiHelpAccount.ask(config, prompts,
                new ClaudeAuthFlowTest.FakeInit().acceptKey(API_KEY)).orElse(null);
        prompts.assertFullyConsumed();
        return answered;
    }

    /**
     * A subscription user asking a question is offered the kinds that can answer, the API credits
     * of their plan named; the key goes beside the subscription, which stays the default, so the
     * instances keep spending it while the key answers.
     */
    @Test
    void aSubscriptionUserAddsAnApiKeyBesideIt() throws Exception {
        var config = seed(SUBSCRIPTION);
        var prompts = new ScriptedPrompts().line("1", "api").secret(API_KEY);
        var out = RecordingOutput.during(() -> assertNotNull(ask(config, prompts)));

        var saved = SpawnConfig.load();
        assertEquals(Set.of("default", "api"), saved.getClaude().effectiveAccounts().keySet());
        assertEquals("default", saved.getClaude().accountName(), "the subscription keeps serving instances");
        assertEquals(List.of(new AiHelpClient.Target(AiHelpClient.Provider.ANTHROPIC, "api", AiHelpClient.ANTHROPIC_MODEL)),
                AiHelpClient.targets(saved));
        var said = out.events().stream().map(RecordingOutput.Event::text).filter(java.util.Objects::nonNull).toList();
        assertTrue(String.join(" ", said).contains(
                "Your Claude subscription cannot answer this. Its token works only in Claude Code."), said::toString);
        assertTrue(said.stream().anyMatch(line -> line.contains(AiHelpClient.API_CREDITS_URL)), said::toString);
        var conversation = String.join("\n", prompts.conversation());
        for (var kind : List.of("An Anthropic API key", "Claude on Google Cloud Vertex AI", "An OpenAI API key",
                "A Gemini API key", "None for now")) {
            assertTrue(conversation.contains(kind), conversation);
        }
        assertTrue(conversation.contains("Max and Team plans include API credits for one."), conversation);
    }

    /** With nothing configured, the key is the namespace's first account, and so its default. */
    @Test
    void aGeminiKeyIsTheFirstGeminiAccount() throws Exception {
        var config = seed("");
        var answered = ask(config, new ScriptedPrompts().line("4").secret("AIza-good"));

        assertNotNull(answered);
        assertEquals("AIza-good", AccountResolver.value(SpawnConfig.load(), "gemini", "default", "apiKey"));
        assertEquals(AiHelpClient.Provider.GEMINI, AiHelpClient.targets(answered).getFirst().provider());
    }

    /** An OpenAI key goes beside a ChatGPT sign-in, whose session is not ended. */
    @Test
    void anOpenAiKeyGoesBesideAChatGptSignIn() throws Exception {
        var config = seed("""
                openai:
                  accounts:
                    chat:
                      type: chatgpt
                  default: chat
                """);
        var answered = ask(config, new ScriptedPrompts().line("3", "api").secret("sk-proj-good"));

        assertNotNull(answered);
        var saved = SpawnConfig.load();
        assertEquals("chatgpt", AccountResolver.value(saved, "openai", "chat", "type"));
        assertEquals("chat", AccountResolver.defaultName(saved.tree(), "openai"));
        assertEquals("sk-proj-good", AccountResolver.value(saved, "openai", "api", "apiKey"));
    }

    /** Going back from the key returns to the kinds, and "None for now" ends it with nothing saved. */
    @Test
    void goingBackAndThenNoneSavesNothing() throws Exception {
        var config = seed(SUBSCRIPTION);
        var out = RecordingOutput.during(() -> assertNull(ask(config, new ScriptedPrompts().line("1", "api").secret("").line("5"))));
        assertEquals(Set.of("default"), SpawnConfig.load().getClaude().effectiveAccounts().keySet());
        // One error, as doctor --explain expects once it returns false, and no note beside it
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR, AiHelpAccount.NOT_SENT)),
                out.events(RecordingOutput.Kind.ERROR));
        assertEquals(List.of(), out.events(RecordingOutput.Kind.NOTE));
    }

    /** Off a terminal nothing is asked: the error names every kind and the commands, never 'isx init'. */
    @Test
    void offATerminalTheCommandsAreNamed() throws Exception {
        var config = seed(SUBSCRIPTION);
        var out = RecordingOutput.during(() -> assertTrue(AiHelpAccount.ask(config, null, new CredentialSetup()).isEmpty()));

        var errors = out.events(RecordingOutput.Kind.ERROR);
        assertEquals(1, errors.size(), out.events().toString());
        var all = out.events().stream().map(RecordingOutput.Event::text).filter(java.util.Objects::nonNull)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains(AiHelpClient.ADD_ONE), all);
        assertTrue(all.contains(AiHelpClient.KINDS), all);
        assertFalse(all.contains("isx init"), all);
    }
}
