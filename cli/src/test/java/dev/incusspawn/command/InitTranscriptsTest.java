package dev.incusspawn.command;

import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.GitIdentity;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.ForcedAnsi;
import dev.incusspawn.util.TerminalOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A transcript of each branch of the first run of {@code isx init} (#2528), as a terminal shows
 * it: the real questions and credential steps, drawn by the terminal's own output with escapes
 * on, then reduced to what the terminal draws (a link is its label). The answers typed are shown
 * after each question, a secret as {@code ••••}. Only the host is the test's: what is found on
 * it, what a verification answers, and the plumbing, build and branch, which print a marker.
 *
 * <p>Each transcript is written to {@code target/init-transcripts/<branch>.txt}, for a person to
 * read, and every line is held to the frame's width (rule 8 of the review on #2528).
 */
@ExtendWith(IsolatedHome.class)
class InitTranscriptsTest {

    static final Path OUT = Path.of("target", "init-transcripts");
    /** The frame is {@link InitCommand#BOX_WIDTH} wide inside its two borders. */
    static final int WIDTH = InitCommand.BOX_WIDTH + 2;

    private static final Pattern OSC = Pattern.compile("\u001B\\]8;;[^\u001B\u0007]*(?:\u001B\\\\|\u0007)");
    private static final Pattern CSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");

    static final HostAgentProbe.HostAgent CLAUDE_SUBSCRIPTION = new HostAgentProbe.HostAgent("claude", true,
            HostAgentProbe.Kind.SUBSCRIPTION, "anthropic", "opus", null, null, "~/.claude.json");
    static final HostAgentProbe.HostAgent CLAUDE_API_KEY = new HostAgentProbe.HostAgent("claude", true,
            HostAgentProbe.Kind.API_KEY, "anthropic", null, null, null, "~/.claude.json");
    static final HostAgentProbe.HostAgent CODEX_FOUND = new HostAgentProbe.HostAgent("codex", true,
            HostAgentProbe.Kind.SUBSCRIPTION, "openai", null, null, null, "~/.codex/auth.json");

    static final String OAUTH_TOKEN = "sk-ant-oat01-" + "a".repeat(95);
    static final String API_KEY = "sk-ant-api03-" + "b".repeat(80);
    static final String GITHUB_TOKEN = "github_pat_" + "c".repeat(82);

    /** The host as a scenario has it: what is installed, in the environment, and what checks answer. */
    static class Host extends CredentialSetup {
        final Map<String, String> env = new HashMap<>();
        final Set<String> commands = new HashSet<>();
        boolean ghLoggedIn;
        String ghToken;
        /** What Anthropic answers a credential: verified or not. */
        boolean anthropicAccepts = true;
        boolean vertexAccepts = true;
        /** What GitHub answers a token: null for a rejected token. */
        GitHubVerifyResult gitHub = new CredentialSetup.GitHubVerifyResult("jdoe-ai", "jdoe-ai@example.org");
        boolean chatGptSignsIn = true;

        @Override String env(String name) { return env.getOrDefault(name, ""); }
        @Override boolean hostHasCommand(String command) { return commands.contains(command); }
        @Override boolean openUrl(String url) { return false; }
        @Override boolean ghCliLoggedIn() { return ghLoggedIn; }
        @Override String readGhAuthToken() { return ghToken; }

        @Override
        AuthResult verifyAnthropicApiKey(String key) {
            return anthropicAccepts ? new AuthResult(true, "API key verified.")
                    : new AuthResult(false, "API key rejected (HTTP 401). It may be expired or invalid.");
        }

        @Override
        AuthResult verifyOauthToken(String token) {
            return anthropicAccepts ? new AuthResult(true, "OAuth token verified.")
                    : new AuthResult(false, "OAuth token rejected (HTTP 401). It may be expired or invalid.");
        }

        @Override
        AuthResult verifyVertexConfig(String region, String projectId) {
            return vertexAccepts ? new AuthResult(true, "Vertex AI configuration verified.")
                    : new AuthResult(false, "gcloud is not installed. Install the Google Cloud SDK.");
        }

        @Override
        CredentialSetup.GitHubVerifyResult verifyGitHubToken(String token, Prompts prompts) {
            BuildOutput.stepStart("Testing GitHub token");
            if (gitHub == null) {
                BuildOutput.stepFail("Authentication failed (HTTP 401).");
                return null;
            }
            BuildOutput.stepDone();
            return gitHub;
        }

        @Override
        void runClaudeSetupToken() {
            BuildOutput.step("⟪'claude setup-token' takes the terminal here: it opens the browser and prints a token⟫");
        }

        @Override
        dev.incusspawn.proxy.ChatGptSignIn chatGptSignIn() {
            return new dev.incusspawn.proxy.ChatGptSignIn() {
                @Override
                public dev.incusspawn.proxy.ChatGptSessionStore.Tokens signInWithBrowser(Consumer<String> openUrl)
                        throws java.io.IOException {
                    openUrl.accept("https://auth.openai.com/oauth/authorize?client_id=app&state=x");
                    if (!chatGptSignsIn) throw new java.io.IOException("the browser sign-in was cancelled");
                    return new dev.incusspawn.proxy.ChatGptSessionStore.Tokens("id", "access", "refresh", "acct");
                }

                @Override
                public DeviceCode requestDeviceCode() {
                    return new DeviceCode("device", "ABCD-1234", 5, "https://auth.openai.com/codex/device");
                }

                @Override
                public dev.incusspawn.proxy.ChatGptSessionStore.Tokens awaitAuthorization(DeviceCode code, Consumer<String> note)
                        throws java.io.IOException {
                    if (!chatGptSignsIn) throw new java.io.IOException("the code expired");
                    return new dev.incusspawn.proxy.ChatGptSessionStore.Tokens("id", "access", "refresh", "acct");
                }
            };
        }
    }

    /** A first run on this host: its probe, its gh login and Git identity, and markers for what runs elsewhere. */
    static class Init extends InitCommand {
        Prompts prompts;
        List<HostAgentProbe.HostAgent> found = List.of();
        String login;
        GitIdentity identity = new GitIdentity("Jane Doe", "jane@example.org");
        boolean buildFails;
        final List<String> ran = new ArrayList<>();

        @Override Prompts prompts() { return prompts; }
        @Override List<HostAgentProbe.HostAgent> probeAgents() { return found; }
        @Override String ghLogin() { return login; }
        @Override GitIdentity hostGitIdentity() { return identity; }
        @Override boolean continuedInGroup() { return false; }
        @Override void startDownloadsAhead(ToolSetup agent) { }
        @Override boolean templateBuilt(String name) { return false; }
        @Override String firstInstanceName() { return "my-first"; }

        @Override
        int buildTemplate(String template) {
            BuildOutput.step("⟪isx build " + template + " runs here" + (buildFails ? ", and fails" : "") + "⟫");
            return buildFails ? 1 : 0;
        }

        @Override
        int openAgent(String template, String instance) {
            BuildOutput.step("⟪isx branch " + instance + " --from " + template + " runs here⟫");
            return 0;
        }

        int number(String agent) {
            var agents = codingAgentTools();
            for (int i = 0; i < agents.size(); i++) if (agents.get(i).name().equals(agent)) return i + 1;
            throw new AssertionError(agent);
        }

        int none() {
            return codingAgentTools().size() + 1;
        }
    }

    /** One branch: how the host is, and the answers given, in order. */
    record Branch(String name, String about, Consumer<Init> host, Consumer<Host> credentials, ScriptedPrompts answers,
                  boolean macOs, boolean servicesInstalled) {
        Branch(String name, String about, Consumer<Init> host, Consumer<Host> credentials, ScriptedPrompts answers) {
            this(name, about, host, credentials, answers, true, true);
        }
    }

    /** Answers echoed where the terminal shows them: a line after its question, a secret as dots. */
    static final class Echoing implements Prompts {
        final ScriptedPrompts answers;
        final PrintStream out;

        Echoing(ScriptedPrompts answers, PrintStream out) {
            this.answers = answers;
            this.out = out;
        }

        @Override
        public String readLine() {
            var line = answers.readLine();
            out.println(line == null ? "⟪end of input⟫" : line.isEmpty() ? "⏎" : line);
            return line;
        }

        @Override
        public char[] readPassword() {
            var secret = answers.readPassword();
            out.println(secret == null ? "⟪end of input⟫" : secret.length == 0 ? "⏎" : "••••");
            return secret;
        }
    }

    static List<Branch> branches() {
        var b = new ArrayList<Branch>();
        Init probe = new Init();

        // The path Sanne ran: Claude Code with a subscription, a GitHub account of the agent's own
        b.add(new Branch("01-claude-subscription-agent-github", "Claude Code signed in with a subscription; the agent's own GitHub account",
                i -> { i.found = List.of(CLAUDE_SUBSCRIPTION); i.login = "jdoe"; },
                h -> h.commands.add("claude"),
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("", "1", "n").secret(GITHUB_TOKEN)));
        b.add(new Branch("02-claude-subscription-own-github", "Claude Code with a subscription; your own GitHub account, from gh",
                i -> { i.found = List.of(CLAUDE_SUBSCRIPTION); i.login = "jdoe"; },
                h -> { h.commands.add("claude"); h.ghLoggedIn = true; h.ghToken = "gho_" + "d".repeat(36);
                    h.gitHub = new CredentialSetup.GitHubVerifyResult("jdoe", "jane@example.org"); },
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("", "2")));
        b.add(new Branch("03-claude-subscription-skipped", "Claude Code with a subscription; the token skipped, GitHub skipped, no Git identity",
                i -> { i.found = List.of(CLAUDE_SUBSCRIPTION); i.login = "jdoe"; },
                h -> h.commands.add("claude"),
                new ScriptedPrompts().line("", "n").secret("").line("", "")));
        b.add(new Branch("04-claude-api-key-found", "Claude Code signed in with an API key; the key pasted",
                i -> i.found = List.of(CLAUDE_API_KEY),
                h -> h.commands.add("claude"),
                new ScriptedPrompts().line("", "1").secret(API_KEY).line("")));
        b.add(new Branch("05-no-agent-found-claude-chosen-subscription", "No agent found; Claude Code chosen from the list; Claude Code not installed; a subscription token",
                i -> { },
                h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("claude")), "2").secret(OAUTH_TOKEN).line("", "")));
        b.add(new Branch("06-claude-vertex-fails-saved-anyway", "Claude Code chosen; Vertex AI, whose check fails, saved anyway",
                i -> { },
                h -> h.vertexAccepts = false,
                new ScriptedPrompts().line(String.valueOf(probe.number("claude")), "3", "us-east5", "my-project", "s", "")));
        b.add(new Branch("07-claude-token-fails-skipped", "Claude Code with a subscription; the pasted token fails, then skipped",
                i -> i.found = List.of(CLAUDE_SUBSCRIPTION),
                h -> { h.commands.add("claude"); h.anthropicAccepts = false; },
                new ScriptedPrompts().line("", "n").secret(OAUTH_TOKEN).line("n", "")));
        b.add(new Branch("08-claude-env-oauth-token", "CLAUDE_CODE_OAUTH_TOKEN in the environment",
                i -> i.found = List.of(CLAUDE_SUBSCRIPTION),
                h -> { h.commands.add("claude"); h.env.put("CLAUDE_CODE_OAUTH_TOKEN", OAUTH_TOKEN); },
                new ScriptedPrompts().line("", "", "", "")));
        b.add(new Branch("09-claude-env-api-key", "ANTHROPIC_API_KEY in the environment",
                i -> i.found = List.of(CLAUDE_API_KEY),
                h -> { h.commands.add("claude"); h.env.put("ANTHROPIC_API_KEY", API_KEY); },
                new ScriptedPrompts().line("", "", "")));
        b.add(new Branch("10-claude-env-vertex-incomplete", "Vertex AI's variables in the environment, the project missing",
                i -> { },
                h -> { h.env.put("CLAUDE_CODE_USE_VERTEX", "1"); h.env.put("CLOUD_ML_REGION", "us-east5"); },
                new ScriptedPrompts().line(String.valueOf(probe.number("claude")), "", "")));
        b.add(new Branch("11-several-agents-found", "Claude Code and Codex found; another agent chosen",
                i -> i.found = List.of(CLAUDE_SUBSCRIPTION, CODEX_FOUND),
                h -> h.commands.add("claude"),
                new ScriptedPrompts().line("o", String.valueOf(probe.number("codex")), "3").secret("sk-proj-" + "e".repeat(40)).line("")));
        b.add(new Branch("12-codex-chatgpt-browser", "Codex chosen; ChatGPT in the browser",
                i -> { },
                h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("codex")), "1", "")));
        b.add(new Branch("13-codex-chatgpt-code-fails", "Codex chosen; ChatGPT with a one-time code, which expires",
                i -> { },
                h -> h.chatGptSignsIn = false,
                new ScriptedPrompts().line(String.valueOf(probe.number("codex")), "2", "")));
        b.add(new Branch("14-codex-skipped", "Codex chosen; its account skipped",
                i -> { },
                h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("codex")), "", "")));
        b.add(new Branch("15-pi", "Pi chosen; it uses a Claude account, skipped",
                i -> { },
                h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("pi")), "", "")));
        b.add(new Branch("16-opencode", "opencode chosen; a Claude API key",
                i -> { },
                h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("opencode")), "1").secret(API_KEY).line("", "")));
        b.add(new Branch("17-copilot", "GitHub Copilot CLI chosen; its account is GitHub's",
                i -> i.login = "jdoe",
                h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("copilot")), "1", "n").secret(GITHUB_TOKEN)));
        b.add(new Branch("18-gemini-api-key", "Gemini CLI chosen; an API key",
                i -> { },
                h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("gemini-cli"))).line("").secret("AIza" + "f".repeat(35)).line("", "")));
        b.add(new Branch("19-antigravity-google", "Antigravity CLI chosen; a Google sign-in",
                i -> { },
                h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("antigravity")), "2", "", "")));
        b.add(new Branch("20-bob", "Bob Shell chosen; its key skipped",
                i -> { },
                h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("bob"))).secret("").line("", "", "")));
        b.add(new Branch("21-none-for-now-identity-yes", "None for now; GitHub skipped; the Git identity of this computer taken",
                i -> i.login = "jdoe",
                h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.none()), "", "y")));
        b.add(new Branch("22-github-token-fails", "Claude Code; the agent's GitHub token fails, then skipped",
                i -> i.found = List.of(CLAUDE_SUBSCRIPTION),
                h -> { h.commands.add("claude"); h.gitHub = null; },
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("", "1", "n").secret(GITHUB_TOKEN).line("n", "")));
        b.add(new Branch("23-github-no-email", "Claude Code; the agent's GitHub token gives no email",
                i -> i.found = List.of(CLAUDE_SUBSCRIPTION),
                h -> { h.commands.add("claude"); h.gitHub = new CredentialSetup.GitHubVerifyResult("jdoe-ai", null); },
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("", "1", "n").secret(GITHUB_TOKEN).secret("")));
        b.add(new Branch("24-github-own-account-fails", "Claude Code; your own GitHub account, whose gh token fails",
                i -> { i.found = List.of(CLAUDE_SUBSCRIPTION); i.login = "jdoe"; },
                h -> { h.commands.add("claude"); h.ghLoggedIn = true; h.ghToken = "gho_" + "d".repeat(36); h.gitHub = null; },
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("", "2", "n").secret("").line("", "")));
        b.add(new Branch("25-build-fails", "Claude Code; everything given; the build fails",
                i -> { i.found = List.of(CLAUDE_SUBSCRIPTION); i.buildFails = true; },
                h -> h.commands.add("claude"),
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("", "1", "n").secret(GITHUB_TOKEN)));
        b.add(new Branch("26-proxy-not-running", "Claude Code; everything given; the services declined",
                i -> i.found = List.of(CLAUDE_SUBSCRIPTION),
                h -> h.commands.add("claude"),
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("", "1", "n").secret(GITHUB_TOKEN),
                true, false));
        b.add(new Branch("27-linux-ready", "Linux; Claude Code; everything given",
                i -> i.found = List.of(CLAUDE_SUBSCRIPTION),
                h -> h.commands.add("claude"),
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("", "1", "n").secret(GITHUB_TOKEN),
                false, true));
        return b;
    }

    @Test
    void everyBranchIsWrittenAndNoLineIsWiderThanTheFrame() throws Exception {
        Files.createDirectories(OUT);
        var tooWide = new ArrayList<String>();
        for (var branch : branches()) {
            IsolatedHome.configFile().toFile().delete();
            Files.deleteIfExists(ExampleTemplate.file());
            var text = transcript(branch);
            Files.writeString(OUT.resolve(branch.name() + ".txt"), text);
            var lineNo = 0;
            for (var line : text.split("\n", -1)) {
                lineNo++;
                if (line.codePointCount(0, line.length()) > WIDTH && !line.startsWith("⟪"))
                    tooWide.add(branch.name() + ":" + lineNo + ": " + line);
            }
        }
        Files.writeString(OUT.resolve("too-wide.txt"), String.join("\n", tooWide) + "\n");
        if (Boolean.getBoolean("transcripts.strict")) assertEquals(List.of(), tooWide);
    }

    /** {@code branch} run from its first question to the end of the run, as the terminal shows it. */
    static String transcript(Branch branch) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        var init = new Init();
        var host = new Host();
        branch.host().accept(init);
        branch.credentials().accept(host);
        init.credentials = host;
        var echoing = new Echoing(branch.answers(), out);
        init.prompts = new Prompts() {
            @Override public String readLine() { return echoing.readLine(); }
            @Override public char[] readPassword() { return echoing.readPassword(); }
            @Override public void show(String question) { branch.answers().show(question); }
            @Override public void say(String line) { branch.answers().say(line); }
        };
        try (var ansi = ForcedAnsi.on(); var bound = BuildOutput.bind(TerminalOutput.to(out, out))) {
            out.println("⟪" + branch.about() + (branch.macOs() ? ", on a Mac" : ", on Linux") + "⟫");
            init.printFirstRunIntro(branch.macOs());
            init.askFirstRunQuestions();
            out.println();
            out.println("⟪the host's setup runs here⟫");
            init.finishFirstRun(branch.macOs(), branch.servicesInstalled(), false);
        } catch (Exception | AssertionError e) {
            out.println("⟪the run threw: " + e + "⟫");
        }
        out.flush();
        return drawn(bytes.toString(StandardCharsets.UTF_8));
    }

    /** What a terminal draws of {@code text}: no colour, an OSC 8 link as its label, a carriage return's last write. */
    static String drawn(String text) {
        var lines = new ArrayList<String>();
        for (var line : OSC.matcher(text).replaceAll("").split("\n", -1)) {
            var cr = line.lastIndexOf('\r');
            var shown = cr >= 0 && cr < line.length() - 1 ? line.substring(cr + 1) : line.replace("\r", "");
            lines.add(CSI.matcher(shown).replaceAll("").stripTrailing());
        }
        return String.join("\n", lines);
    }
}
