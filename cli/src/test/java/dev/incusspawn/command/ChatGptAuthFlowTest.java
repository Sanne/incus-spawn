package dev.incusspawn.command;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.proxy.ChatGptSessionStore;
import dev.incusspawn.proxy.ChatGptSignIn;
import dev.incusspawn.util.RecordingOutput;
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

    /** Runs the step and answers it, recording what it reported: the address reports go through BuildOutput, not the prompts' conversation (#1703). */
    private static RecordingOutput run(FakeInit init, SpawnConfig config, ScriptedPrompts prompts) {
        var output = RecordingOutput.during(() -> init.setupCodexAuth(config, prompts));
        prompts.assertFullyConsumed();
        return output;
    }

    @Test
    void aChatGptSignInRecordsTheAccountAndKeepsTheSessionOnTheHost() {
        var init = new FakeInit();
        var prompts = chooseChatGpt();
        var output = run(init, new SpawnConfig(), prompts);

        assertEquals("chatgpt", saved("openai.accounts.default.type"));
        var session = new ChatGptSessionStore().read("default");
        assertNotNull(session, "the session is the host's, in the session store");
        assertEquals("rt-real", session.tokens().refreshToken());
        assertEquals("acct-ent", session.tokens().accountId());
        assertEquals(1, init.signIn.browserSignIns);
        var said = String.join("\n", output.lines());
        assertTrue(said.contains("https://auth.openai.com/oauth/authorize?response_type=code"),
                "the address is printed for the person to open, never opened for them: " + said);
        assertTrue(said.contains("in a browser on this machine"),
                "the browser flow's redirect answers only a browser on this host: " + said);
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
        var output = run(new FakeInit(), new SpawnConfig(), chooseDeviceCode());

        var said = String.join("\n", output.lines());
        assertTrue(said.contains("Open this address on any device"),
                "the one-time code carries the sign-in from any device, not only a browser on"
                        + " this host: " + said);
        assertTrue(said.contains("https://auth.openai.com/codex/device"), said);
        assertTrue(said.contains("ABCD-EFGH"), "the code is the one thing to show: " + said);
        assertFalse(said.contains("rt-real"), "the tokens are never shown: " + said);
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
    void signingAnApiKeyAccountInWithChatGptDropsItsKey() throws Exception {
        var config = seed("""
                openai:
                  accounts:
                    keyed:
                      apiKey: "sk-old"
                    other:
                      apiKey: "sk-other"
                  default: keyed
                """);

        run(new FakeInit(), config, new ScriptedPrompts().line("e", "keyed", "1"));

        assertEquals("chatgpt", saved("openai.accounts.keyed.type"));
        assertEquals("", saved("openai.accounts.keyed.apiKey"),
                "the session replaces the key: it must not stay in config.yaml, nor be injected for the account");
        assertNotNull(new ChatGptSessionStore().read("keyed"));
        assertEquals("sk-other", saved("openai.accounts.other.apiKey"), "the other account keeps its key");
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
    void aSessionThatCannotBeRemovedDoesNotStopTheOthersEnding() throws Exception {
        var config = seed("""
                openai:
                  accounts:
                    sso:
                      type: chatgpt
                    other:
                      type: chatgpt
                  default: sso
                """);
        var store = new ChatGptSessionStore();
        store.create("sso", new ChatGptSessionStore.Tokens("id", "access", "rt-sso", "acct"));
        store.create("other", new ChatGptSessionStore.Tokens("id", "access", "rt-other", "acct"));
        // A session the store cannot remove: session.json as a directory deleteIfExists refuses
        // to empty, so the first removal of the step fails
        var stuck = Path.of(System.getProperty("user.home"), ".config/incus-spawn/openai/sso/session.json");
        Files.delete(stuck);
        Files.createDirectories(stuck.resolve("occupied"));

        var output = run(new FakeInit(), config, new ScriptedPrompts().line("r", "3").secret(API_KEY));

        assertNull(store.read("other"), "the removal after the failed one still ran");
        assertTrue(Files.exists(stuck), "the failed removal left the session behind, as warned");
        var warned = String.join("\n", output.warnings());
        assertTrue(warned.contains("'sso'"), "the warning names the session left behind: " + warned);
        var said = String.join("\n", output.lines());
        assertTrue(said.contains("OpenAI credentials saved"),
                "the step finishes rather than aborting after the save: " + said);
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
