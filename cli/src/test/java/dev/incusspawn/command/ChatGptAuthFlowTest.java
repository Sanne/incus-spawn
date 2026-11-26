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

        /** What each check of a pasted API key answers, in turn; the last repeats. */
        java.util.List<AuthResult> checks = java.util.List.of(new AuthResult(true, "Verified."));
        final java.util.List<dev.incusspawn.tool.SecretCheck.Request> checked = new java.util.ArrayList<>();

        @Override
        dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
            return signIn;
        }

        @Override
        AuthResult checkSecret(dev.incusspawn.tool.SecretCheck.Request check) {
            checked.add(check);
            return checks.get(Math.min(checked.size(), checks.size()) - 1);
        }
    }

    /** The answers a first-time browser sign-in gives: the menu and the yes past its notice, for the flow opens the browser itself. */
    private static ScriptedPrompts chooseChatGpt() {
        return new ScriptedPrompts().line("2", "y");
    }

    /** The one-time-code path: the menu and the yes past its notice, for the sign-in prints its address and code itself. */
    private static ScriptedPrompts chooseDeviceCode() {
        return new ScriptedPrompts().line("3", "y");
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
        assertTrue(said.contains("in a browser on " + dev.incusspawn.Platform.thisComputer()),
                "the browser flow's redirect answers only a browser on this host: " + said);
        assertTrue(said.contains("isx waits here while you sign in."), said);
    }

    @Test
    void aFailedBrowserSignInOffersAnotherTryAndSkippingLeavesNothingSaved() {
        var failing = new FakeInit() {
            int attempts;
            @Override
            dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
                return new FakeSignIn() {
                    @Override
                    public ChatGptSessionStore.Tokens signInWithBrowser(java.util.function.Consumer<String> openUrl) {
                        attempts++;
                        openUrl.accept("https://auth.openai.com/oauth/authorize?response_type=code");
                        if (attempts == 1) throw new IllegalStateException("timed out");
                        return super.signInWithBrowser(openUrl);
                    }
                };
            }
        };
        run(failing, new SpawnConfig(), new ScriptedPrompts().line("2", "y", ""));
        assertEquals("chatgpt", saved("openai.accounts.default.type"));

        IsolatedHome.configFile().toFile().delete();
        var alwaysFailing = new FakeInit() {
            @Override
            dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
                return new FakeSignIn() {
                    @Override
                    public ChatGptSessionStore.Tokens signInWithBrowser(java.util.function.Consumer<String> openUrl) {
                        throw new IllegalStateException("cancelled");
                    }
                };
            }
        };
        run(alwaysFailing, new SpawnConfig(), new ScriptedPrompts().line("2", "y", "s"));
        assertNothingSaved();
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
        assertTrue(said.contains("On any device, open this address"),
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
    void theMenuAsksOneQuestionAndSaysWhatEnterLeaves() {
        // Enter chooses nothing, and the question is asked again (#2528)
        var prompts = new ScriptedPrompts().line("", "4");
        run(new FakeInit(), new SpawnConfig(), prompts);

        var conversation = prompts.conversation();
        var question = conversation.indexOf("Which kind of OpenAI account do you have?");
        assertTrue(question >= 0, "the question comes before its options, so they read as alternatives: " + conversation);
        for (var option : List.of("  1. An OpenAI API key",
                "  2. ChatGPT, signed in with a browser on " + dev.incusspawn.Platform.thisComputer(),
                "  3. ChatGPT, signed in with a code on another device", "  4. None for now")) {
            assertTrue(conversation.indexOf(option) > question, option + " follows the question: " + conversation);
        }
        assertEquals(List.of("Choice: ", "Choice: "), prompts.questions(),
                "None for now is an option, never what Enter does");
        assertNothingSaved();
        assertNull(new ChatGptSessionStore().read("default"), "Enter signs nothing in");
    }

    /**
     * A ChatGPT account is chosen past a notice of what it is, never by Enter or a missing
     * terminal (#2651): Enter at its question goes back to the kinds of account.
     */
    @Test
    void enterAtTheNoticeSignsNothingInAndGoesBackToTheKindsOfAccount() {
        var init = new FakeInit();
        var prompts = new ScriptedPrompts().line("2", "", "3", "", "4");
        var output = run(init, new SpawnConfig(), prompts);

        assertEquals(0, init.signIn.browserSignIns + init.signIn.signIns, "Enter declines the sign-in");
        assertNothingSaved();
        assertNull(new ChatGptSessionStore().read("default"));
        assertEquals(List.of("Choice: ", "Sign in with ChatGPT? (y/N): ", "Choice: ",
                "Sign in with ChatGPT? (y/N): ", "Choice: "), prompts.questions());
        var said = String.join(" ", output.lines());
        for (var point : List.of("experimental", "may stop working when Codex or OpenAI changes",
                "Codex's own client, which OpenAI has not endorsed", "never see them",
                "browser sign-in was tested", "code has not been tested")) {
            assertTrue(said.contains(point), "the notice says: " + point + "\n" + said);
        }
    }

    @Test
    void aMissingTerminalNeverChoosesChatGpt() {
        var init = new FakeInit();
        // The menu's answer, then end of input at the notice, as `isx init </dev/null` gives
        var prompts = new ScriptedPrompts().line("2");
        RecordingOutput.during(() -> init.setupCodexAuth(new SpawnConfig(), prompts));

        assertEquals(List.of("Choice: ", "Sign in with ChatGPT? (y/N): ", "Choice: "), prompts.questions(),
                "end of input declines the notice, then leaves the account for later");
        assertEquals(0, init.signIn.browserSignIns);
        assertNothingSaved();
    }

    @Test
    void codexSignedInHereShowsTheNoticeAndEnterDoesNotSignIn() {
        var init = new FakeInit();
        var prompts = new ScriptedPrompts().line("", "");
        var output = RecordingOutput.during(() -> init.setupCodexLocalChatGpt(new SpawnConfig(), prompts));

        assertEquals(0, init.signIn.browserSignIns + init.signIn.signIns,
                "Enter asks again, and end of input skips");
        assertNothingSaved();
        assertTrue(String.join(" ", output.lines()).contains("Signing in with ChatGPT is experimental."),
                String.join("\n", output.lines()));
        assertEquals(List.of("Choice: ", "Choice: ", "Choice: "), prompts.questions());
    }

    @Test
    void anApiKeyIsStillThePlatformCredential() {
        run(new FakeInit(), new SpawnConfig(), new ScriptedPrompts().line("1").secret(API_KEY));

        assertEquals(API_KEY, saved("openai.accounts.default.apiKey"));
        assertNull(new ChatGptSessionStore().read("default"), "no session was signed in");
    }

    /** A pasted key is checked with OpenAI before it is saved, a rejected one asked for again (#2566). */
    @Test
    void aRejectedApiKeyIsAskedForAgainAndOnlyTheVerifiedOneSaved() {
        var init = new FakeInit();
        init.checks = java.util.List.of(new CredentialSetup.AuthResult(false, "api.openai.com rejected it (HTTP 401)."),
                new CredentialSetup.AuthResult(true, "Verified."));
        var output = run(init, new SpawnConfig(), new ScriptedPrompts().line("1").secret("sk-mistyped")
                .line("").secret(API_KEY));

        assertEquals(API_KEY, saved("openai.accounts.default.apiKey"));
        assertEquals(2, init.checked.size());
        assertEquals("https://api.openai.com/v1/models", init.checked.getFirst().uri().toString());
        assertEquals("Bearer sk-mistyped", init.checked.getFirst().value());
        var said = String.join("\n", output.lines());
        assertTrue(said.contains("The key did not verify. api.openai.com rejected it"), said);
        assertTrue(said.contains("API key verified and saved on " + dev.incusspawn.Platform.thisComputer()), said);
    }

    /** A key the service rejected is never saved: try again or skip are the choices. */
    @Test
    void aRejectedApiKeyThatIsSkippedIsNotSaved() {
        var init = new FakeInit();
        init.checks = java.util.List.of(new CredentialSetup.AuthResult(false, "api.openai.com rejected it (HTTP 401)."));
        run(init, new SpawnConfig(), new ScriptedPrompts().line("1").secret("sk-mistyped").line("s"));

        assertEquals("", saved("openai.accounts.default.apiKey"));
    }

    /** A key isx could not check may be saved unverified, and says so. */
    @Test
    void aKeyThatCouldNotBeCheckedIsSavedUnverifiedOnRequest() {
        var init = new FakeInit();
        init.checks = java.util.List.of(CredentialSetup.AuthResult.unchecked("isx could not reach api.openai.com: timeout"));
        var output = run(init, new SpawnConfig(), new ScriptedPrompts().line("1").secret(API_KEY).line("u"));

        assertEquals(API_KEY, saved("openai.accounts.default.apiKey"));
        var said = String.join("\n", output.lines());
        assertTrue(said.contains("API key saved on " + dev.incusspawn.Platform.thisComputer() + ", unverified"), said);
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

        run(new FakeInit(), config, new ScriptedPrompts().line("e", "keyed", "2", "y"));

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

        run(new FakeInit(), config, new ScriptedPrompts().line("r", "1").secret(API_KEY));

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

        var output = run(new FakeInit(), config, new ScriptedPrompts().line("r", "1").secret(API_KEY));

        assertNull(store.read("other"), "the removal after the failed one still ran");
        assertTrue(Files.exists(stuck), "the failed removal left the session behind, as warned");
        var warned = String.join("\n", output.warnings());
        assertTrue(warned.contains("'sso'"), "the warning names the session left behind: " + warned);
        var said = String.join("\n", output.lines());
        assertTrue(said.contains("API key verified and saved on " + dev.incusspawn.Platform.thisComputer()),
                "the step finishes rather than aborting after the save: " + said);
    }

    @Test
    void skippingWritesNothing() {
        run(new FakeInit(), new SpawnConfig(), new ScriptedPrompts().line("4"));

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

        run(new FakeInit(), config, new ScriptedPrompts().line("r", "2", "y"));

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

        run(init, new SpawnConfig(), new ScriptedPrompts().line("2", "y", "s"));

        assertEquals("", saved("openai.accounts.default.type"), "nothing was written");
    }

    /** A one-time code expires by itself: the step offers another try, with a new code (#2528). */
    @Test
    void anExpiredCodeIsTriedAgainWithANewCode() {
        var codes = new java.util.concurrent.atomic.AtomicInteger();
        var init = new FakeInit() {
            @Override
            dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
                return new ChatGptSignIn("http://127.0.0.1:1") {
                    @Override
                    public DeviceCode requestDeviceCode() {
                        var n = codes.incrementAndGet();
                        return new DeviceCode("dev-" + n, "CODE-000" + n, 1, "https://auth.openai.com/codex/device");
                    }

                    @Override
                    public ChatGptSessionStore.Tokens awaitAuthorization(DeviceCode device,
                            java.util.function.Consumer<String> note) throws java.io.IOException {
                        if (device.userCode().endsWith("1")) throw new java.io.IOException("the code expired");
                        return new ChatGptSessionStore.Tokens("id-token", "access-token", "rt-real", "acct-ent");
                    }
                };
            }
        };

        var out = run(init, new SpawnConfig(), new ScriptedPrompts().line("3", "y", ""));

        assertEquals(2, codes.get(), "a second try asks for a new code");
        assertTrue(String.join("\n", out.lines()).contains("CODE-0002"), String.join("\n", out.lines()));
        assertEquals("chatgpt", saved("openai.accounts.default.type"), "the second try signed in");
    }

    @Test
    void anExpiredCodeCanBeSkipped() {
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
                        throw new java.io.IOException("the code expired");
                    }
                };
            }
        };

        var out = run(init, new SpawnConfig(), new ScriptedPrompts().line("3", "y", "s"));

        assertTrue(String.join("\n", out.lines()).contains("Skipped. 'isx account add openai' adds one later."), String.join("\n", out.lines()));
        assertEquals("", saved("openai.accounts.default.type"), "nothing was written");
    }

    /** A server that does not offer the one-time code is said so, and a new code is not offered (#2650). */
    @Test
    void aServerWithoutTheOneTimeCodeIsNotAskedAgain() {
        var asked = new java.util.concurrent.atomic.AtomicInteger();
        var init = new FakeInit() {
            @Override
            dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
                return new ChatGptSignIn("http://127.0.0.1:1") {
                    @Override
                    public DeviceCode requestDeviceCode() throws java.io.IOException {
                        asked.incrementAndGet();
                        throw new ChatGptSignIn.DeviceCodeUnavailable();
                    }
                };
            }
        };

        var out = run(init, new SpawnConfig(), chooseDeviceCode());

        // The warning wraps at the frame's width, so it is read as one line
        var said = String.join(" ", out.lines()).replaceAll("\\s+", " ");
        assertEquals(1, asked.get());
        assertTrue(said.contains("does not offer the one-time code"), said);
        assertFalse(said.contains("could not reach"), said);
        assertEquals("", saved("openai.accounts.default.type"), "nothing was written");
    }

    /**
     * What OpenAI's sign-in server answers when asked for a code, through the real sign-in: an
     * answer isx cannot use is the server's answer, never a server isx could not reach (#2650).
     */
    private static String askedForACode(int status, String answer, ScriptedPrompts prompts) throws Exception {
        var stub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/accounts/deviceauth/usercode", exchange -> {
            exchange.getRequestBody().readAllBytes();
            var bytes = answer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        stub.start();
        try {
            var issuer = "http://127.0.0.1:" + stub.getAddress().getPort();
            var init = new FakeInit() {
                @Override
                dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
                    return new ChatGptSignIn(issuer);
                }
            };
            // The warning wraps at the frame's width, so it is read as one line
            return String.join(" ", run(init, new SpawnConfig(), prompts).lines()).replaceAll("\\s+", " ");
        } finally {
            stub.stop(0);
        }
    }

    @Test
    void anUnreadableIntervalIsTheServersAnswer() throws Exception {
        var said = askedForACode(200, "{\"device_auth_id\":\"d\",\"user_code\":\"X\",\"interval\":\"5s\"}",
                new ScriptedPrompts().line("2", "s"));

        assertTrue(said.contains("OpenAI's sign-in server did not give a code isx can use:"
                + " its answer gives a poll interval that is not a number"), said);
        assertFalse(said.contains("could not reach"), said);
        assertEquals("", saved("openai.accounts.default.type"), "nothing was written");
    }

    @Test
    void aServerErrorIsTheServersAnswer() throws Exception {
        var said = askedForACode(503, "", new ScriptedPrompts().line("2", "s"));

        assertTrue(said.contains("did not give a code isx can use: it answered HTTP 503"), said);
        assertFalse(said.contains("could not reach"), said);
    }

    /** An interrupt while asking for a code ends the step: no new code, and the server is not blamed. */
    @Test
    void anInterruptWhileAskingForACodeEndsTheStep() {
        var init = new FakeInit() {
            @Override
            dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
                return new ChatGptSignIn("http://127.0.0.1:1") {
                    @Override
                    public DeviceCode requestDeviceCode() throws InterruptedException {
                        throw new InterruptedException();
                    }
                };
            }
        };
        try {
            var said = String.join(" ", run(init, new SpawnConfig(), chooseDeviceCode()).lines());

            assertTrue(said.contains("The ChatGPT sign-in was interrupted."), said);
            assertFalse(said.contains("could not reach"), said);
        } finally {
            Thread.interrupted();
        }
    }

    /** A sign-in whose one-time code always expires, or is interrupted. */
    private static FakeInit failingCodes(java.util.concurrent.atomic.AtomicInteger codes, boolean interrupted) {
        return new FakeInit() {
            @Override
            dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
                return new ChatGptSignIn("http://127.0.0.1:1") {
                    @Override
                    public DeviceCode requestDeviceCode() {
                        codes.incrementAndGet();
                        return new DeviceCode("dev-1", "ABCD-EFGH", 1, "https://auth.openai.com/codex/device");
                    }

                    @Override
                    public ChatGptSessionStore.Tokens awaitAuthorization(DeviceCode device,
                            java.util.function.Consumer<String> note) throws java.io.IOException, InterruptedException {
                        if (interrupted) throw new InterruptedException();
                        throw new java.io.IOException("the code expired");
                    }
                };
            }
        };
    }

    /** A person who has accounts is not told to add one when a code expires (#2528). */
    @Test
    void aSkippedCodeSaysTheAccountsThereStay() throws Exception {
        var config = seed("""
                openai:
                  accounts:
                    default:
                      apiKey: "sk-kept"
                """);
        var codes = new java.util.concurrent.atomic.AtomicInteger();

        var out = run(failingCodes(codes, false), config, new ScriptedPrompts().line("a", "work", "3", "y", "s"));

        var said = String.join("\n", out.lines());
        assertTrue(said.contains("Your OpenAI accounts stay as they were."), said);
        assertFalse(said.contains("adds one later"), said);
        assertEquals("sk-kept", saved("openai.accounts.default.apiKey"));
    }

    /** An interrupt ends the step: no new code is asked for, and no question is asked. */
    @Test
    void anInterruptIsNotAnsweredWithANewCode() {
        var codes = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var out = run(failingCodes(codes, true), new SpawnConfig(), new ScriptedPrompts().line("3", "y"));

            assertEquals(1, codes.get(), "one code, never a second");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept for the caller");
            assertTrue(String.join("\n", out.lines()).contains("Skipped."), String.join("\n", out.lines()));
        } finally {
            Thread.interrupted();
        }
    }

    /** A failure without a message is named by its kind: the warning once ended "did not finish: null". */
    @Test
    void aSignInFailureWithoutAMessageIsNamedByItsKind() {
        assertEquals("SocketTimeoutException", CredentialSetup.reason(new java.net.SocketTimeoutException()));
        assertEquals("the code expired", CredentialSetup.reason(new java.io.IOException("the code expired")));
    }
}
