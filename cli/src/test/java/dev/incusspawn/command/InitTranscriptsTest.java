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
    /** A box is {@link InitCommand#BOX_WIDTH} wide inside its two borders. */
    static final int BOX = InitCommand.BOX_WIDTH + 2;
    /** A line under a step header, its indent included (.claude/rules/interface-text.md "A line fits its frame"). */
    static final int WIDTH = InitCommand.BOX_WIDTH;

    private static final Pattern OSC = Pattern.compile("\u001B\\]8;;[^\u001B\u0007]*(?:\u001B\\\\|\u0007)");
    /**
     * The first run's "Never" words (.claude/rules/interface-text.md "One word for one thing"):
     * "machine" alone, not after "isx", and the words isx uses elsewhere.
     */
    private static final Pattern NEVER = Pattern.compile("(?i)\\b(instances?|containers?|host|hosts|box)\\b|(?<!isx )\\bmachines?\\b");
    private static final Pattern CSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");

    static final HostAgentProbe.HostAgent CLAUDE_SUBSCRIPTION = new HostAgentProbe.HostAgent("claude", true,
            HostAgentProbe.Kind.SUBSCRIPTION, "anthropic", "opus", null, null, "~/.claude.json");
    static final HostAgentProbe.HostAgent CLAUDE_API_KEY = new HostAgentProbe.HostAgent("claude", true,
            HostAgentProbe.Kind.API_KEY, "anthropic", null, null, null, "~/.claude.json");
    static final HostAgentProbe.HostAgent CODEX_FOUND = new HostAgentProbe.HostAgent("codex", true,
            HostAgentProbe.Kind.SUBSCRIPTION, "openai", null, null, null, "~/.codex/auth.json");
    static final HostAgentProbe.HostAgent ANTIGRAVITY_FOUND = new HostAgentProbe.HostAgent("antigravity", true,
            HostAgentProbe.Kind.SUBSCRIPTION, "google", null, null, null, "~/.gemini/antigravity-cli/antigravity-oauth-token");
    static final HostAgentProbe.HostAgent COPILOT_FOUND = new HostAgentProbe.HostAgent("copilot", true,
            HostAgentProbe.Kind.SUBSCRIPTION, "github", null, null, null, "~/.copilot/config.json");

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
        /** What GitHub answers a token: its account, or null for a rejected token. */
        CredentialSetup.GitHubUser gitHub = new CredentialSetup.GitHubUser("jdoe-ai", 81234567, "jdoe-ai@example.org");
        boolean chatGptSignsIn = true;

        @Override String env(String name) { return env.getOrDefault(name, ""); }
        @Override boolean hostHasCommand(String command) { return commands.contains(command); }
        @Override boolean openUrl(String url) { return false; }
        @Override boolean ghCliLoggedIn() { return ghLoggedIn; }
        @Override String readGhAuthToken() { return ghToken; }
        // No transcript pastes a token of gh's own account, and the real one would run gh
        @Override String ghCliLogin() { return null; }

        // Only the network call: the checks around it, a token's shape warning included, are the real ones
        @Override
        AuthResult verifyAnthropicCredential(String headerName, String headerValue, String label) {
            return anthropicAccepts ? new AuthResult(true, label + " verified.")
                    : new AuthResult(false, "Anthropic rejected it (HTTP 401): it may be expired or mistyped.");
        }

        // A tool's declared key check (#2566): accepted, never sent to the real service
        @Override
        AuthResult checkSecret(dev.incusspawn.tool.SecretCheck.Request check) {
            return new AuthResult(true, "Verified.");
        }

        @Override
        AuthResult verifyVertexConfig(String region, String projectId) {
            // As the real check answers with no gcloud on this computer: it could not run
            return vertexAccepts ? new AuthResult(true, "Vertex AI configuration verified.")
                    : AuthResult.unchecked("gcloud is not installed on this computer. Install it from"
                    + " https://cloud.google.com/sdk/docs/install, then run 'gcloud auth login'.");
        }

        @Override
        CredentialSetup.GitHubUser fetchGitHubUser(String token) throws Exception {
            if (gitHub == null) throw new GitHubRejected("GitHub rejected it (HTTP 401): it may be expired or mistyped.");
            return gitHub;
        }

        /** What isx reads from 'claude setup-token''s screen: none unless a branch sets it. */
        List<String> screenTokens = List.of();

        @Override
        List<String> runClaudeSetupToken() {
            BuildOutput.step("⟪'claude setup-token' runs here: it opens the browser and prints a token⟫");
            return screenTokens;
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
        /** The whole run, its setup steps faked: {@link #runFirstRun} on Linux with {@code --no-sudo}. */
        boolean wholeRun;
        /** In a whole run, the setup step that throws, or null. */
        String failingStep;
        /** The continuation under {@code sg}, which ends the run its parent started. */
        boolean continuation;
        /** A project's {@code .incus-spawn/} in the working directory overrides a tool of the first template. */
        boolean inAProject;
        final List<String> ran = new ArrayList<>();

        @Override Prompts prompts() { return prompts; }
        @Override Set<String> projectToolsHere() { return inAProject ? Set.of("claude") : Set.of(); }
        @Override InitStep gitRemoteHelper() { return faked("git-remote-helper"); }
        @Override InitStep dnsStep() { return faked("dns"); }
        @Override List<InitStep> linuxSetup() { return List.of(faked("subids"), faked("storage-network"), faked("mitm-proxy")); }

        @Override
        InitStep proxyServiceStep(AtomicBoolean installed) {
            return new InitStep("proxy-service", "Proxy Service", new String[0], () -> {
                BuildOutput.step("⟪the proxy service is installed here⟫");
                installed.set(true);
            }, null);
        }

        /** A setup step that prints a marker, or throws when it is {@link #failingStep}. */
        InitStep faked(String id) {
            return new InitStep(id, "Step " + id, new String[]{"What " + id + " does."}, () -> {
                if (id.equals(failingStep)) throw new IllegalStateException(id + " failed for the test");
                BuildOutput.step("⟪setup step " + id + " runs here⟫");
            }, InitPlan.Assessment::done);
        }

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

        /** The number the agent list shows for {@code agent} when no agent is found on this computer. */
        int number(String agent) {
            return number(agent, java.util.Set.of());
        }

        /** The number the list shows for {@code agent} when the agents named {@code found} are found: those come first. */
        int number(String agent, java.util.Set<String> found) {
            var agents = InitCommand.agentsInListOrder(codingAgentTools(), found);
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
            // A typed answer ends in Enter too, so TranscriptGate knows the line holds the person's answer
            out.println(line == null ? "⟪end of input⟫" : line + "⏎");
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
        var sub = List.of(CLAUDE_SUBSCRIPTION);
        Consumer<Host> claudeHere = h -> h.commands.add("claude");
        var gho = "gho_" + "d".repeat(36);
        var openaiKey = "sk-proj-" + "e".repeat(40);
        var geminiKey = "AIza" + "f".repeat(35);

        // The path Sanne ran: Claude Code with a subscription, a GitHub account of the agent's own
        b.add(new Branch("01-claude-subscription-agent-github", "Claude Code signed in with a subscription; the agent's own GitHub account",
                i -> { i.found = sub; i.login = "jdoe"; }, h -> { h.commands.add("claude"); h.screenTokens = List.of(OAUTH_TOKEN); },
                new ScriptedPrompts().line("", "", "1").secret(GITHUB_TOKEN).line("")));
        b.add(new Branch("02-claude-subscription-own-github", "Claude Code with a subscription; the token not read from the screen, so pasted; your own GitHub account, from gh",
                i -> { i.found = sub; i.login = "jdoe"; },
                h -> { h.commands.add("claude"); h.ghLoggedIn = true; h.ghToken = gho;
                    h.gitHub = new CredentialSetup.GitHubUser("jdoe", 1234567, "jane@example.org"); },
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("2", "2")));
        b.add(new Branch("03-claude-subscription-skipped", "Claude Code with a subscription; its account skipped, GitHub skipped, no Git identity",
                i -> { i.found = sub; i.login = "jdoe"; }, claudeHere,
                new ScriptedPrompts().line("", "s", "3", "")));
        b.add(new Branch("04-claude-api-key-found", "Claude Code signed in with an API key; a key pasted; no gh login",
                i -> i.found = List.of(CLAUDE_API_KEY), claudeHere,
                new ScriptedPrompts().line("", "").secret(API_KEY).line("2", "")));
        b.add(new Branch("05-no-agent-found-claude-not-installed", "No agent found; Claude Code chosen; not installed here; a subscription token",
                i -> { }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("claude")), "2").secret(OAUTH_TOKEN).line("2", "")));
        b.add(new Branch("06-claude-vertex-fails-saved-anyway", "Claude Code chosen; Vertex AI, whose check fails, saved anyway",
                i -> { }, h -> h.vertexAccepts = false,
                new ScriptedPrompts().line(String.valueOf(probe.number("claude")), "3", "my-project", "us-east5", "u", "2", "")));
        b.add(new Branch("07-claude-token-fails-skipped", "Claude Code with a subscription; run by hand; the pasted token fails, then skipped",
                i -> i.found = sub, h -> { h.commands.add("claude"); h.anthropicAccepts = false; },
                new ScriptedPrompts().line("", "n").secret(OAUTH_TOKEN).line("s", "2", "")));
        b.add(new Branch("08-claude-env-oauth-token", "CLAUDE_CODE_OAUTH_TOKEN in the environment",
                i -> i.found = sub, h -> { h.commands.add("claude"); h.env.put("CLAUDE_CODE_OAUTH_TOKEN", OAUTH_TOKEN); },
                new ScriptedPrompts().line("", "", "2", "")));
        b.add(new Branch("09-claude-env-api-key", "ANTHROPIC_API_KEY in the environment",
                i -> i.found = List.of(CLAUDE_API_KEY), h -> { h.commands.add("claude"); h.env.put("ANTHROPIC_API_KEY", API_KEY); },
                new ScriptedPrompts().line("", "", "2", "")));
        b.add(new Branch("10-claude-env-vertex-incomplete", "Vertex AI's variables in the environment, the project missing; None for now",
                i -> { }, h -> { h.env.put("CLAUDE_CODE_USE_VERTEX", "1"); h.env.put("CLOUD_ML_REGION", "us-east5"); },
                new ScriptedPrompts().line(String.valueOf(probe.number("claude")), "4", "2", "")));
        b.add(new Branch("11-several-agents-found-another-chosen", "Claude Code and Codex found; Codex chosen from the list; an OpenAI API key",
                i -> i.found = List.of(CLAUDE_SUBSCRIPTION, CODEX_FOUND), claudeHere,
                new ScriptedPrompts().line("o", String.valueOf(probe.number("codex", java.util.Set.of("claude", "codex"))), "o", "3").secret(openaiKey).line("2", "")));
        b.add(new Branch("12-codex-chatgpt-browser", "Codex chosen; ChatGPT in the browser",
                i -> { }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("codex")), "1", "2", "")));
        b.add(new Branch("12b-codex-found-chatgpt", "Codex CLI signed in with ChatGPT on this computer; Enter signs in for isx",
                i -> i.found = List.of(CODEX_FOUND), h -> { },
                new ScriptedPrompts().line("", "", "2", "")));
        b.add(new Branch("13-codex-chatgpt-code-fails", "Codex chosen; ChatGPT with a one-time code, which expires; tried again, then skipped",
                i -> { }, h -> h.chatGptSignsIn = false,
                new ScriptedPrompts().line(String.valueOf(probe.number("codex")), "2", "", "s", "2", "")));
        b.add(new Branch("14-codex-none-for-now", "Codex chosen; None for now",
                i -> { }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("codex")), "4", "2", "")));
        b.add(new Branch("15-pi", "Pi chosen; Enter re-asks, then Claude as its provider; None for now",
                i -> { }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("pi")), "", "1", "4", "2", "")));
        b.add(new Branch("15-pi-openai", "Pi chosen; OpenAI as its provider; an API key",
                i -> { }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("pi")), "2", "1").secret(openaiKey).line("2", "")));
        b.add(new Branch("15-pi-other-provider", "Pi chosen; a local or custom-proxied provider",
                i -> { }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("pi")), "4", "Open Router", "ollama", "2", "")));
        b.add(new Branch("16-opencode", "opencode chosen; Claude as its provider; a Claude API key",
                i -> { }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("opencode")), "1", "1").secret(API_KEY).line("2", "")));
        b.add(new Branch("17-copilot", "GitHub Copilot CLI chosen; its account is GitHub's; the agent's own",
                i -> i.login = "jdoe", h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("copilot")), "1").secret(GITHUB_TOKEN).line("")));
        b.add(new Branch("17b-copilot-found-classic-gh", "GitHub Copilot CLI signed in; own gh login holds a classic token, warning wrapped",
                i -> { i.found = List.of(COPILOT_FOUND); i.login = "jdoe"; },
                h -> { h.ghLoggedIn = true; h.ghToken = "ghp_" + "d".repeat(36);
                    h.gitHub = new CredentialSetup.GitHubUser("jdoe", 1234567, "jane@example.org"); },
                new ScriptedPrompts().line("", "2", "2", "")));
        b.add(new Branch("18-gemini-api-key", "Gemini CLI chosen; an API key",
                i -> { }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("gemini-cli")), "1").secret(geminiKey).line("2", "")));
        b.add(new Branch("19-antigravity-google", "Antigravity CLI chosen; a Google sign-in",
                i -> { }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("antigravity")), "2", "2", "")));
        b.add(new Branch("19b-antigravity-found-google", "Antigravity CLI signed in on this computer; Enter chooses Google sign-in",
                i -> i.found = List.of(ANTIGRAVITY_FOUND), h -> { },
                new ScriptedPrompts().line("", "", "2", "")));
        b.add(new Branch("20-bob", "Bob Shell chosen; its key skipped",
                i -> { }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.number("bob"))).secret("").line("2", "")));
        b.add(new Branch("21-none-for-now-identity-yes", "No agent; GitHub None for now; the Git identity of this computer taken",
                i -> i.login = "jdoe", h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.none()), "3", "y")));
        b.add(new Branch("22-github-token-fails", "Claude Code; the agent's GitHub token fails, then skipped",
                i -> i.found = sub, h -> { h.commands.add("claude"); h.gitHub = null; },
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("1").secret(GITHUB_TOKEN).line("s", "")));
        b.add(new Branch("23-github-no-email", "Claude Code; the agent's GitHub token shows no email",
                i -> i.found = sub, h -> { h.commands.add("claude"); h.gitHub = new CredentialSetup.GitHubUser("jdoe-ai", 81234567, null); },
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("1").secret(GITHUB_TOKEN)));
        b.add(new Branch("24-github-own-account-fails", "Claude Code; your own GitHub account, whose gh token fails; then None for now",
                i -> { i.found = sub; i.login = "jdoe"; },
                h -> { h.commands.add("claude"); h.ghLoggedIn = true; h.ghToken = gho; h.gitHub = null; },
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("2", "3", "")));
        b.add(new Branch("25-build-fails", "Claude Code; everything given; the build fails",
                i -> { i.found = sub; i.buildFails = true; }, claudeHere,
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("1").secret(GITHUB_TOKEN).line("")));
        b.add(new Branch("26-proxy-not-running", "Claude Code; everything given; the services not installed",
                i -> i.found = sub, claudeHere,
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("1").secret(GITHUB_TOKEN).line(""),
                true, false));
        b.add(new Branch("27-linux-ready", "Linux; Claude Code; everything given",
                i -> i.found = sub, claudeHere,
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("1").secret(GITHUB_TOKEN).line(""),
                false, true));
        b.add(new Branch("28-go-back", "Claude Code; Enter at the paste goes back; another kind of account; an API key",
                i -> i.found = sub, claudeHere,
                new ScriptedPrompts().line("", "n").secret("").line("o", "1").secret(API_KEY).line("2", "")));
        b.add(new Branch("29-github-address-and-back", "The agent's own GitHub account: the full address asked for, back, then the token; the primary email",
                i -> i.found = sub, claudeHere,
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("1").secret("u").secret("").line("1").secret(GITHUB_TOKEN).line("2")));
        b.add(new Branch("31-preferred-agent-in-config", "A preferred agent in config.yaml, before init ever completed",
                i -> seed("preferred-agent: codex\n"), h -> { },
                new ScriptedPrompts().line("", "4", "2", "")));
        b.add(new Branch("32-accounts-already-set-up", "Claude Code preferred, its account and a GitHub account in config.yaml already",
                i -> { i.found = sub; seed("""
                        preferred-agent: claude
                        claude:
                          accounts:
                            default:
                              oauthToken: %s
                          default: default
                        github:
                          accounts:
                            default:
                              token: %s
                              email: jdoe-ai@example.org
                          default: default
                        """.formatted(OAUTH_TOKEN, GITHUB_TOKEN)); },
                claudeHere, new ScriptedPrompts().line("")));
        b.add(new Branch("33-whole-run-setup-fails", "The whole run on Linux with --no-sudo; no agent; a setup step fails",
                i -> { i.wholeRun = true; i.failingStep = "storage-network"; }, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.none()), "2", ""), false, true));
        b.add(new Branch("34-whole-run-linux-no-sudo", "The whole run on Linux with --no-sudo; Claude Code; everything given",
                i -> { i.wholeRun = true; i.found = sub; }, claudeHere,
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("1").secret(GITHUB_TOKEN).line("", ""),
                false, true));
        b.add(new Branch("35-continuation-under-sg", "Linux; the end of the run, in the continuation under sg",
                i -> { i.found = sub; i.continuation = true; }, claudeHere,
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("1").secret(GITHUB_TOKEN).line(""),
                false, true));
        b.add(new Branch("36-in-a-project-directory", "Claude Code; run in a directory whose .incus-spawn/ overrides the claude tool",
                i -> { i.found = sub; i.inAProject = true; }, claudeHere,
                new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("1").secret(GITHUB_TOKEN).line("")));
        // Seen on a real run (#2528): a token from the environment was never pasted, so no note about a paste
        b.add(new Branch("37-claude-env-short-token", "CLAUDE_CODE_OAUTH_TOKEN in the environment, shorter than one from setup-token",
                i -> i.found = sub, h -> { h.commands.add("claude"); h.env.put("CLAUDE_CODE_OAUTH_TOKEN", OAUTH_TOKEN.substring(0, 49)); },
                new ScriptedPrompts().line("", "", "2", "")));
        // Seen on a real run (#2528): a tpl-agent an earlier run wrote lists gh, and no GitHub account is saved
        b.add(new Branch("38-earlier-tpl-agent-lists-gh", "A tpl-agent.yaml from an earlier run that lists gh; a subscription token; GitHub None for now",
                i -> { i.found = sub; seedTemplate("""
                        name: tpl-agent
                        description: Claude Code on a minimal Fedora machine
                        parent: tpl-minimal
                        tools:
                          - claude
                          - gh
                        default-action: claude
                        """); },
                claudeHere, new ScriptedPrompts().line("", "").secret(OAUTH_TOKEN).line("2", "")));
        b.add(new Branch("30-linux-no-sudo", "Linux with --no-sudo; no agent; GitHub None for now",
                i -> i.noSudo = true, h -> { },
                new ScriptedPrompts().line(String.valueOf(probe.none()), "2", ""),
                false, true));
        return b;
    }

    @Test
    void everyBranchIsWrittenAndHeldToTheRulesOfInterfaceText() throws Exception {
        // Only this run's branches: one renamed or gone leaves no stale file behind
        if (Files.isDirectory(OUT)) try (var old = Files.list(OUT)) { for (var f : old.toList()) Files.delete(f); }
        Files.createDirectories(OUT);
        var faults = new ArrayList<String>();
        for (var branch : branches()) {
            IsolatedHome.configFile().toFile().delete();
            Files.deleteIfExists(ExampleTemplate.file());
            var text = transcript(branch);
            Files.writeString(OUT.resolve(branch.name() + ".txt"), text);
            // The closing message's bridging line is the one place "instance" is said
            faults.addAll(TranscriptGate.faults(branch.name(), text, NEVER, "isx commands and docs call"));
        }
        Files.writeString(OUT.resolve("faults.txt"), String.join("\n", faults) + "\n");
        assertEquals(List.of(), faults, "against .claude/rules/interface-text.md");
    }

    /** The branch named {@code name}, run alone from a fresh home. */
    static String transcriptOf(String name) throws Exception {
        IsolatedHome.configFile().toFile().delete();
        Files.deleteIfExists(ExampleTemplate.file());
        return transcript(branches().stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow());
    }

    @Test
    void aTokenFromTheEnvironmentIsNeverSaidToBeAPasteCutShort() throws Exception {
        var text = transcriptOf("37-claude-env-short-token");
        assertFalse(text.contains("paste"), text);
        assertTrue(text.contains("Token verified and saved"), text);
    }

    /**
     * Each header after the first recaps what was answered (#2528): a tick and the answer, or a
     * hyphen for a question left for later, above the question the header opens.
     */
    @Test
    void aHeaderRecapsTheAnswersSoFar() throws Exception {
        var text = transcriptOf("01-claude-subscription-agent-github");
        var first = text.indexOf("Your Coding Agent");
        var second = text.indexOf("Claude Code Account");
        var third = text.indexOf("GitHub Account  ");
        assertTrue(first >= 0 && first < second && second < third, text);
        assertFalse(text.substring(0, first).contains("Coding agent   "), "the first header has nothing to recap:\n" + text);
        var agent = "│  \u2713 Coding agent   Claude Code";
        assertTrue(text.substring(first, second).contains(agent), text);
        var beforeThird = text.substring(second, third);
        assertTrue(beforeThird.contains(agent), text);
        assertTrue(beforeThird.contains("│  \u2713 Its account    Claude subscription"), text);
        // The banner that closes the questions shows every answer, the last one too, before the setup
        var done = text.indexOf("─ Questions done ─");
        var setup = text.indexOf("⟪the host's setup runs here⟫");
        assertTrue(third < done && done < setup, text);
        var banner = text.substring(done, setup);
        assertTrue(banner.contains(agent), banner);
        assertTrue(banner.contains("│  \u2713 Its account    Claude subscription"), banner);
        assertTrue(banner.contains("│  \u2713 GitHub         acts as jdoe-ai"), banner);
        assertTrue(banner.contains("Next, isx installs what it needs on this Mac, and will"), banner);
        assertTrue(banner.contains("then build an example template."), banner);
        assertFalse(text.contains("sets up this"), "say what isx does to the computer:\n" + text);
        // The questions' own: the closing box and what follows show none
        assertFalse(text.substring(text.indexOf("Setup complete")).contains("Coding agent   "), text);

        var later = transcriptOf("15-pi");
        assertTrue(later.contains("│  \u2713 Coding agent   Pi (Claude)"), later);
        assertTrue(later.contains("│  - Its account    left for later"), later);

        var chatGpt = transcriptOf("12b-codex-found-chatgpt");
        assertTrue(chatGpt.contains("│  \u2713 Its account    ChatGPT"), chatGpt);

        var google = transcriptOf("19b-antigravity-found-google");
        assertTrue(google.contains("│  \u2713 Its account    Google account"), google);

        var none = transcriptOf("21-none-for-now-identity-yes");
        assertTrue(none.contains("│  - Coding agent   none for now"), none);
    }

    @Test
    void aGitHubAccountAnEarlierTemplateListsIsAskedInItsOwnStep() throws Exception {
        var text = transcriptOf("38-earlier-tpl-agent-lists-gh");
        assertTrue(text.contains("1. A few questions about your preferred coding agent,"), text);
        assertTrue(text.contains("its account and GitHub."), text);
        assertTrue(text.contains("Claude Code Account                                   [2/3]"), text);
        assertTrue(text.contains("GitHub Account                                        [3/3]"), text);
        // GitHub's account is not the agent's: the closing box does not ask for it as Claude Code's
        assertFalse(text.contains("Add Claude Code's account"), text);
        // Named on its own, since the build inside init does not warn of it, and the agent is not offered before it
        assertTrue(text.contains("Add a GitHub account:"), text);
        assertTrue(text.contains("isx account add github"), text);
        assertFalse(text.contains("Setup complete"), text);
        assertFalse(text.contains("now? (Y/n)"), text);
    }

    /** {@code yaml} as the person's own tpl-agent.yaml, as an earlier run writes it. */
    static void seedTemplate(String yaml) {
        try {
            var dir = dev.incusspawn.config.ImageDef.userImagesDir();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("tpl-agent.yaml"), yaml);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static void seed(String yaml) {
        try {
            IsolatedHome.seed(yaml);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
            boolean atWelcome;
            @Override public String readLine() {
                // Every branch presses Enter at the welcome's pause: no script lists it
                if (atWelcome) {
                    atWelcome = false;
                    out.println("⏎");
                    return "";
                }
                return echoing.readLine();
            }
            @Override public char[] readPassword() { return echoing.readPassword(); }
            @Override public void show(String question) {
                atWelcome = question.equals(InitCommand.BEGIN_PROMPT);
                branch.answers().show(question);
            }
            @Override public void say(String line) { branch.answers().say(line); }
        };
        try (var ansi = ForcedAnsi.on(); var bound = BuildOutput.bind(TerminalOutput.to(out, out))) {
            out.println("⟪" + branch.about() + (branch.macOs() ? ", on a Mac" : ", on Linux") + "⟫");
            if (init.wholeRun) {
                init.noSudo = true;
                init.runFirstRun(false);
            } else {
                init.printFirstRunIntro(branch.macOs());
                init.waitToBegin();
                init.askFirstRunQuestions();
                init.printQuestionsDone(branch.macOs());
                out.println();
                out.println("⟪the host's setup runs here⟫");
                init.finishFirstRun(branch.macOs(), branch.servicesInstalled(), init.continuation);
            }
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
