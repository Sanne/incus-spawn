package dev.incusspawn.command;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.proxy.ChatGptSessionStore;
import dev.incusspawn.proxy.ChatGptSignIn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static dev.incusspawn.command.IsolatedHome.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx init}'s OpenAI step, end to end (#1332): the ChatGPT sign-in and what lands in
 * config.yaml and the host's session store. The device-code flow is the overridable seam, as
 * every network call on {@link CredentialSetup} is; the browser is too.
 */
@ExtendWith(IsolatedHome.class)
class ChatGptAuthFlowTest {

    private static final String API_KEY = "sk-typed-by-hand";

    /** A sign-in that succeeds at once, recording what the step told the person. */
    static class FakeSignIn extends ChatGptSignIn {
        final List<String> shown = new ArrayList<>();
        int signIns;
        int browserSignIns;

        FakeSignIn() {
            super("http://127.0.0.1:1");
        }

        @Override
        public DeviceCode requestDeviceCode() {
            return new DeviceCode("dev-1", "ABCD-EFGH", 1, "https://auth.openai.com/codex/device");
        }

        @Override
        public ChatGptSessionStore.Tokens awaitAuthorization(DeviceCode device, java.util.function.Consumer<String> note) {
            signIns++;
            return tokens();
        }

        @Override
        public ChatGptSessionStore.Tokens signInWithBrowser(java.util.function.Consumer<String> openUrl) {
            browserSignIns++;
            openUrl.accept("https://auth.openai.com/oauth/authorize?response_type=code");
            return tokens();
        }

        private static ChatGptSessionStore.Tokens tokens() {
            return new ChatGptSessionStore.Tokens("id-token", "access-token", "rt-real", "acct-ent");
        }
    }

    static class FakeInit extends CredentialSetup {
        final FakeSignIn signIn = new FakeSignIn();

        @Override
        dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
            return signIn;
        }
    }

    /** The answers a first-time browser sign-in gives: the menu alone, for the flow opens the browser itself. */
    private static ScriptedPrompts chooseChatGpt() {
        return new ScriptedPrompts().line("1");
    }

    /** The one-time-code path: the menu alone, for the sign-in prints its address and code itself. */
    private static ScriptedPrompts chooseDeviceCode() {
        return new ScriptedPrompts().line("2");
    }

    private static void run(FakeInit init, SpawnConfig config, ScriptedPrompts prompts) {
        init.setupCodexAuth(config, prompts);
        prompts.assertFullyConsumed();
    }

    @Test
    void aChatGptSignInRecordsTheAccountAndKeepsTheSessionOnTheHost() {
        var init = new FakeInit();
        var prompts = chooseChatGpt();
        run(init, new SpawnConfig(), prompts);

        assertEquals("chatgpt", saved("openai.accounts.default.type"));
        var session = new ChatGptSessionStore().read("default");
        assertNotNull(session, "the session is the host's, in the session store");
        assertEquals("rt-real", session.tokens().refreshToken());
        assertEquals("acct-ent", session.tokens().accountId());
        assertEquals(1, init.signIn.browserSignIns);
        var conversation = String.join("\n", prompts.conversation());
        assertTrue(conversation.contains("https://auth.openai.com/oauth/authorize?response_type=code"),
                "the address is printed for the person to open, never opened for them: " + conversation);
    }

    @Test
    void theOneTimeCodePathStillSignsIn() {
        var init = new FakeInit();
        run(init, new SpawnConfig(), chooseDeviceCode());

        assertEquals("chatgpt", saved("openai.accounts.default.type"));
        assertEquals(1, init.signIn.signIns);
        assertEquals(0, init.signIn.browserSignIns);
    }

    @Test
    void thePersonSeesTheVerificationUrlAndTheOneTimeCode() {
        var prompts = chooseDeviceCode();
        run(new FakeInit(), new SpawnConfig(), prompts);

        var conversation = String.join("\n", prompts.conversation());
        assertTrue(conversation.contains("https://auth.openai.com/codex/device"), conversation);
        assertTrue(conversation.contains("ABCD-EFGH"), "the code is the one thing to show: " + conversation);
        assertFalse(conversation.contains("rt-real"), "the tokens are never shown: " + conversation);
    }

    @Test
    void signingInAgainReplacesTheSession() throws Exception {
        var init = new FakeInit();
        var config = seed("""
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                  default: sso
                """);
        var first = new ChatGptSessionStore().create("sso",
                new ChatGptSessionStore.Tokens("old-id", "old-access", "rt-old", "acct-old"));

        // The account exists and is a ChatGPT one: its menu offers replacing it, then a sign-in
        // again in the browser, which the fake opens itself
        run(init, config, new ScriptedPrompts().line("r", "s"));

        var replaced = new ChatGptSessionStore().read("sso");
        assertNotEquals(first.sessionId(), replaced.sessionId(),
                "a sign-in again invalidates every proof made for the previous session");
        assertEquals("rt-real", replaced.tokens().refreshToken());
        assertEquals(1, init.signIn.browserSignIns);
    }

    @Test
    void anApiKeyIsStillThePlatformCredential() {
        run(new FakeInit(), new SpawnConfig(), new ScriptedPrompts().line("3").secret(API_KEY));

        assertEquals(API_KEY, saved("openai.accounts.default.apiKey"));
        assertNull(new ChatGptSessionStore().read("default"), "no session was signed in");
    }

    @Test
    void switchingToAnApiKeyEndsTheHostSessionAndTheChatGptType() throws Exception {
        var config = seed("""
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                  default: sso
                """);
        new ChatGptSessionStore().create("sso",
                new ChatGptSessionStore.Tokens("id", "access", "rt-real", "acct"));

        run(new FakeInit(), config, new ScriptedPrompts().line("r", "k").secret(API_KEY));

        assertEquals(API_KEY, saved("openai.accounts.sso.apiKey"));
        assertEquals("", saved("openai.accounts.sso.type"),
                "the account is no longer a ChatGPT one, or the pasted key would be ignored");
        assertNull(new ChatGptSessionStore().read("sso"),
                "the session ended with the ChatGPT it left: no refresh token stays behind");
    }

    @Test
    void replacingOneAccountsCredentialEndsThatSessionsToo() throws Exception {
        var config = seed("""
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                    keyed:
                      apiKey: "sk-other"
                  default: sso
                """);
        new ChatGptSessionStore().create("sso",
                new ChatGptSessionStore.Tokens("id", "access", "rt-real", "acct"));

        run(new FakeInit(), config, new ScriptedPrompts().line("e", "sso", "k").secret(API_KEY));

        assertEquals(API_KEY, saved("openai.accounts.sso.apiKey"));
        assertEquals("", saved("openai.accounts.sso.type"));
        assertNull(new ChatGptSessionStore().read("sso"));
        assertEquals("sk-other", saved("openai.accounts.keyed.apiKey"), "the other account keeps its key");
    }

    @Test
    void replacingEveryAccountEndsEachChatGptSessions() throws Exception {
        var config = seed("""
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                    keyed:
                      apiKey: "sk-other"
                  default: sso
                """);
        new ChatGptSessionStore().create("sso",
                new ChatGptSessionStore.Tokens("id", "access", "rt-real", "acct"));

        run(new FakeInit(), config, new ScriptedPrompts().line("r", "3").secret(API_KEY));

        assertNull(new ChatGptSessionStore().read("sso"),
                "the replaced account's session ended with it, nothing points at it anymore");
        assertEquals(API_KEY, saved("openai.accounts.default.apiKey"));
        assertEquals("", saved("openai.accounts.sso.apiKey"), "the replaced account is gone");
    }

    @Test
    void skippingWritesNothing() {
        run(new FakeInit(), new SpawnConfig(), new ScriptedPrompts().line(""));

        assertFalse(Files.exists(Path.of(System.getProperty("user.home"), ".config/incus-spawn/openai")));
        assertEquals("", saved("openai.accounts.default.type"));
    }

    @Test
    void signingInAgainOverEveryAccountEndsTheReplacedAccountsSessions() throws Exception {
        var config = seed("""
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                    keyed:
                      apiKey: "sk-other"
                  default: sso
                """);
        new ChatGptSessionStore().create("sso",
                new ChatGptSessionStore.Tokens("id", "access", "rt-old", "acct-old"));

        run(new FakeInit(), config, new ScriptedPrompts().line("r", "1"));

        assertNull(new ChatGptSessionStore().read("sso"),
                "the replaced account's session ended with it: no refresh token stays behind");
        var replaced = new ChatGptSessionStore().read("default");
        assertNotNull(replaced, "the new account holds the signed-in session");
        assertEquals("rt-real", replaced.tokens().refreshToken());
        assertEquals("", saved("openai.accounts.sso.type"), "the replaced account is gone");
    }

    @Test
    void aFailedSignInLeavesTheConfigAlone() {
        var init = new FakeInit() {
            @Override
            dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
                return new ChatGptSignIn("http://127.0.0.1:1") {
                    @Override
                    public DeviceCode requestDeviceCode() {
                        return new DeviceCode("dev-1", "ABCD-EFGH", 1, "https://auth.openai.com/codex/device");
                    }

                    @Override
                    public ChatGptSessionStore.Tokens awaitAuthorization(DeviceCode device,
                            java.util.function.Consumer<String> note) throws java.io.IOException {
                        throw new java.io.IOException("the device code expired");
                    }

                    @Override
                    public ChatGptSessionStore.Tokens signInWithBrowser(java.util.function.Consumer<String> openUrl)
                            throws java.io.IOException {
                        throw new java.io.IOException("the sign-in was refused");
                    }
                };
            }
        };

        run(init, new SpawnConfig(), chooseChatGpt());

        assertEquals("", saved("openai.accounts.default.type"), "nothing was written");
    }
}
