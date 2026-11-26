package dev.incusspawn.command;

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
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A transcript of each path of {@code isx account add} (#2542), as a terminal shows it, written
 * to {@code target/account-add-transcripts/<path>.txt} for a person to read, from the same fake
 * host as {@link InitTranscriptsTest}. Every line is held to the frame of init's steps, whose
 * headers this command shows, and to the words of .claude/rules/interface-text.md: "instance"
 * and "this computer" here, never "container", and none of the mechanism a person has not met.
 */
@ExtendWith(IsolatedHome.class)
class AccountAddTranscriptsTest {

    static final Path OUT = Path.of("target", "account-add-transcripts");
    /** What these screens never say: another word for an instance, or a mechanism never introduced. */
    private static final Pattern NEVER = Pattern.compile("(?i)\\b(containers?|box|host|MITM|placeholders?|PAT)\\b");

    record Branch(String name, String about, String credential, String config,
                  Consumer<InitTranscriptsTest.Host> host, ScriptedPrompts answers) {}

    static List<Branch> branches() {
        var b = new ArrayList<Branch>();
        var T = InitTranscriptsTest.class;
        b.add(new Branch("01-menu-nothing-chosen", "No credential named; Enter at the menu", null, null, h -> { }, ScriptedPrompts.lines("")));
        b.add(new Branch("02-claude-api-key", "claude, none configured; an API key", "claude", null, h -> { },
                new ScriptedPrompts().line("1").secret(InitTranscriptsTest.API_KEY)));
        b.add(new Branch("03-claude-oauth", "claude, none configured; a subscription token", "claude", null, h -> h.commands.add("claude"),
                new ScriptedPrompts().line("2", "").secret(InitTranscriptsTest.OAUTH_TOKEN)));
        b.add(new Branch("04-claude-existing", "claude, an account configured; add another", "claude",
                "claude:\n  accounts:\n    default:\n      apiKey: " + InitTranscriptsTest.API_KEY + "\n  default: default\n", h -> { },
                new ScriptedPrompts().line("a", "work", "1").secret(InitTranscriptsTest.API_KEY)));
        b.add(new Branch("05-github", "github, none configured; a token", "github", null, h -> { },
                new ScriptedPrompts().secret(InitTranscriptsTest.GITHUB_TOKEN).line("")));
        b.add(new Branch("06-github-existing", "github, an account configured", "gh",
                "github:\n  accounts:\n    default:\n      token: " + InitTranscriptsTest.GITHUB_TOKEN + "\n  default: default\n", h -> { },
                ScriptedPrompts.lines("")));
        b.add(new Branch("07-openai-key", "openai, an API key", "openai", null, h -> { },
                new ScriptedPrompts().line("1").secret("sk-proj-" + "e".repeat(40))));
        b.add(new Branch("08-gemini", "gemini, an API key", "gemini", null, h -> { },
                new ScriptedPrompts().line("1").secret("AIza" + "f".repeat(35))));
        b.add(new Branch("09-unknown", "a name no step takes", "nope", null, h -> { }, new ScriptedPrompts()));
        b.add(new Branch("11-borrowed", "a tool that spends another tool's credential", "copilot", null, h -> { }, new ScriptedPrompts()));
        b.add(new Branch("12-menu-two-chosen", "No credential named; Claude and GitHub chosen, both skipped", null, null, h -> { },
                new ScriptedPrompts().line("1, 2", "4").secret("")));
        b.add(new Branch("10-atlassian", "atlassian", "atlassian-mcp", null, h -> { }, ScriptedPrompts.lines("")));
        var claudeKey = "claude:\n  accounts:\n    default:\n      apiKey: " + InitTranscriptsTest.API_KEY + "\n  default: default\n";
        var gitHubToken = "github:\n  accounts:\n    default:\n      token: " + InitTranscriptsTest.GITHUB_TOKEN + "\n  default: default\n";
        b.add(new Branch("13-claude-vertex", "claude, Vertex AI, verified", "claude", null, h -> { },
                ScriptedPrompts.lines("3", "my-project", "us-east5")));
        b.add(new Branch("14-claude-vertex-unchecked", "claude, Vertex AI, gcloud missing", "claude", null, h -> h.vertexAccepts = false,
                ScriptedPrompts.lines("3", "my-project", "us-east5", "y")));
        b.add(new Branch("15-claude-key-rejected", "claude, an API key Anthropic rejects, then skipped", "claude", null,
                h -> h.anthropicAccepts = false, new ScriptedPrompts().line("1").secret(InitTranscriptsTest.API_KEY).line("s")));
        b.add(new Branch("16-claude-key-in-env", "claude, ANTHROPIC_API_KEY set on this computer", "claude", null,
                h -> h.env.put("ANTHROPIC_API_KEY", InitTranscriptsTest.API_KEY), ScriptedPrompts.lines("")));
        b.add(new Branch("17-claude-replace", "claude, an account configured; replace all", "claude", claudeKey, h -> { },
                new ScriptedPrompts().line("r", "1").secret(InitTranscriptsTest.API_KEY)));
        b.add(new Branch("18-claude-rename", "claude, an account configured; rename it", "claude", claudeKey, h -> { },
                ScriptedPrompts.lines("n", "default", "work", "")));
        b.add(new Branch("19-github-rejected", "github, a token GitHub rejects, then skipped", "github", null,
                h -> h.gitHub = null, new ScriptedPrompts().secret(InitTranscriptsTest.GITHUB_TOKEN).line("s")));
        b.add(new Branch("20-github-replace", "github, an account configured; replace its credential", "github", gitHubToken, h -> { },
                new ScriptedPrompts().line("r").secret(InitTranscriptsTest.GITHUB_TOKEN).line("")));
        b.add(new Branch("21-github-second", "github, an account configured; add a second", "github", gitHubToken, h -> { },
                new ScriptedPrompts().line("a", "work").secret(InitTranscriptsTest.GITHUB_TOKEN).line("", "")));
        b.add(new Branch("22-github-gh-signed-in", "github, gh signed in on this computer", "github", null, h -> {
                    h.commands.add("gh");
                    h.ghLoggedIn = true;
                    h.ghToken = "gho_" + "d".repeat(36);
                }, new ScriptedPrompts().secret(InitTranscriptsTest.GITHUB_TOKEN).line("", "")));
        b.add(new Branch("23-openai-chatgpt-browser", "openai, ChatGPT in a browser here", "openai", null, h -> { },
                ScriptedPrompts.lines("2", "y", "")));
        b.add(new Branch("24-openai-chatgpt-device", "openai, ChatGPT with a code on another device", "openai", null, h -> { },
                ScriptedPrompts.lines("3", "y", "")));
        b.add(new Branch("25-openai-chatgpt-fails", "openai, a ChatGPT sign-in that is cancelled", "openai", null,
                h -> h.chatGptSignsIn = false, ScriptedPrompts.lines("2", "y", "", "")));
        b.add(new Branch("26-gemini-google", "gemini, a Google account", "gemini", null, h -> { }, ScriptedPrompts.lines("2", "")));
        b.add(new Branch("27-bob", "bob, an API key", "bob", null, h -> { },
                new ScriptedPrompts().secret("bob-" + "g".repeat(40)).line("")));
        b.add(new Branch("29-claude-subscription-only", "claude, a subscription account only; Enter keeps it", "claude",
                "claude:\n  accounts:\n    default:\n      oauthToken: " + InitTranscriptsTest.OAUTH_TOKEN + "\n  default: default\n",
                h -> { }, ScriptedPrompts.lines("")));
        b.add(new Branch("28-claude-oauth-read-from-screen", "claude, a subscription token read from claude setup-token's screen", "claude", null,
                h -> {
                    h.commands.add("claude");
                    h.screenTokens = List.of(InitTranscriptsTest.OAUTH_TOKEN);
                }, ScriptedPrompts.lines("2", "", "")));
        return b;
    }

    @Test
    void everyPathIsWrittenAndHeldToTheFrameAndTheWords() throws Exception {
        if (Files.isDirectory(OUT)) try (var old = Files.list(OUT)) { for (var f : old.toList()) Files.delete(f); }
        Files.createDirectories(OUT);
        var faults = new ArrayList<String>();
        // The Mac's pass answers from a script of its own: the first pass reads each to its end
        var onAMac = branches();
        for (var branch : branches()) {
            var macBranch = onAMac.removeFirst();
            IsolatedHome.configFile().toFile().delete();
            if (branch.config() != null) IsolatedHome.seed(branch.config());
            // Linux's words, even when the test runs on a Mac (#2588)
            var text = dev.incusspawn.Platform.wordedAs(false, () -> transcript(branch));
            Files.writeString(OUT.resolve(branch.name() + ".txt"), text);
            assertFalse(text.contains("⟪the run threw"), branch.name() + ":\n" + text);
            faults.addAll(TranscriptGate.faults(branch.name(), text, NEVER, null));
            // The same path on a Mac, in its words whatever OS runs the test (#2588)
            IsolatedHome.configFile().toFile().delete();
            if (branch.config() != null) IsolatedHome.seed(branch.config());
            var mac = dev.incusspawn.Platform.wordedAs(true, () -> transcript(macBranch));
            Files.writeString(OUT.resolve(branch.name() + ".mac.txt"), mac);
            faults.addAll(TranscriptGate.faults(branch.name() + ".mac", mac, NEVER, null));
            if (mac.substring(mac.indexOf('\n') + 1).contains("this computer")) faults.add(branch.name() + ".mac: says \"this computer\"");
            if (text.contains("this Mac")) faults.add(branch.name() + ": says \"this Mac\"");
        }
        Files.writeString(OUT.resolve("faults.txt"), String.join("\n", faults) + "\n");
        assertEquals(List.of(), faults, "against .claude/rules/interface-text.md");
    }

    @Test
    void aMenuWithNothingChosenSaysHowToComeBack() throws Exception {
        var text = transcript(branches().getFirst());
        assertTrue(text.contains("Which credentials should isx set up? Choose one or more:"), text);
        assertTrue(text.contains("Skipped. 'isx account add' adds one later."), text);
    }

    /**
     * The list names each credential as the step it starts is titled, with the tools that spend
     * it: it once listed "Codex CLI" and then started "OpenAI Account", and said "Gemini CLI" of
     * the account Antigravity CLI, Pi and opencode spend too.
     */
    @Test
    void theListNamesEachAccountAsItsStepDoesWithWhatSpendsIt() throws Exception {
        var text = transcript(branches().getFirst());
        assertTrue(text.contains("1. Claude Account\n         For Claude Code, Pi and opencode\n"), text);
        assertTrue(text.contains("2. GitHub Account\n         For Git, gh and GitHub Copilot CLI\n"), text);
        assertTrue(text.contains("4. OpenAI Account\n         For Codex CLI, Pi and opencode\n"), text);
        assertTrue(text.contains("6. Gemini Account\n         For Gemini CLI, Antigravity CLI, Pi and opencode\n"), text);
        // One tool alone: its own description, as before
        assertTrue(text.contains("3. Bob Shell\n         IBM AI coding assistant\n"), text);
        var gemini = transcript(branches().stream().filter(b -> b.name().equals("08-gemini")).findFirst().orElseThrow());
        assertTrue(gemini.contains("│  Gemini Account "), gemini);
        assertTrue(gemini.contains("The credential Gemini CLI, Antigravity CLI, Pi and"), gemini);
    }

    /**
     * A tool behind a feature gate is named under a credential by the config the list was given,
     * as the list itself decides by it: a tool the config in hand enables is named while
     * config.yaml on disk does not enable it, and one it does not enable is not.
     */
    @Test
    void theToolsNamedUnderACredentialFollowTheConfigInHand() {
        var tools = new java.util.LinkedHashMap<>(new dev.incusspawn.tool.ToolDefLoader().allToolSetups());
        tools.put("gated", new dev.incusspawn.tool.ToolSetup() {
            @Override public String name() { return "gated"; }
            @Override public String description() { return "Gated Agent — behind a feature"; }
            @Override public String feature() { return "gated-agent"; }
            @Override public java.util.Set<String> credentialNamespaces() { return java.util.Set.of("gemini"); }
            @Override public void install(dev.incusspawn.incus.Container container, java.util.Map<String, String> params) {}
        });
        var gemini = tools.get("gemini-cli");
        var off = new dev.incusspawn.config.SpawnConfig();
        var on = new dev.incusspawn.config.SpawnConfig();
        on.setFeatures(List.of("gated-agent"));
        assertFalse(InitCommand.spending("gemini-cli", gemini, tools, off).contains("Gated Agent"));
        assertTrue(InitCommand.spending("gemini-cli", gemini, tools, on).contains("Gated Agent"));
        assertTrue(CredentialSetup.optionAbout("gemini-cli", gemini, tools, on).contains("Gated Agent"),
                CredentialSetup.optionAbout("gemini-cli", gemini, tools, on));
    }

    /**
     * A Claude account's line that does not fit the frame keeps its tag whole, two spaces before
     * it, on the last line when it has room and on a line of its own when it has not; no line is
     * wider than the frame.
     */
    @Test
    void aWrappedClaudeAccountLineKeepsItsTagWhole() {
        var region = "europe-west1";
        for (var project = "p"; project.length() < 2 * Lines.WIDTH; project += "x y") {
            var account = dev.incusspawn.config.SpawnConfig.ClaudeAccount.ofVertex(region, project);
            var lines = CredentialSetup.describeClaudeAccount("work", account, true);
            assertTrue(lines.stream().allMatch(l -> l.length() <= Lines.WIDTH), String.join("\n", lines));
            var shown = String.join("\n", lines);
            assertTrue(lines.getLast().endsWith("  (default)"), shown);
            assertEquals(shown.indexOf("(default)"), shown.lastIndexOf("(default)"), shown);
        }
    }

    static String transcript(Branch branch) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        var init = new InitTranscriptsTest.Init();
        var host = new InitTranscriptsTest.Host();
        branch.host().accept(host);
        init.credentials = host;
        var echoing = new InitTranscriptsTest.Echoing(branch.answers(), out);
        init.prompts = new Prompts() {
            @Override public String readLine() { return echoing.readLine(); }
            @Override public char[] readPassword() { return echoing.readPassword(); }
            @Override public void show(String question) { branch.answers().show(question); }
            @Override public void say(String line) { branch.answers().say(line); }
        };
        try (var ansi = ForcedAnsi.on(); var bound = BuildOutput.bind(TerminalOutput.to(out, out))) {
            out.println("⟪isx account add " + (branch.credential() == null ? "" : branch.credential()) + ": " + branch.about() + "⟫");
            var code = init.addCredential(branch.credential());
            out.println("⟪exit " + code + "⟫");
        } catch (Exception | AssertionError e) {
            out.println("⟪the run threw: " + e + "⟫");
        }
        out.flush();
        return InitTranscriptsTest.drawn(bytes.toString(StandardCharsets.UTF_8));
    }
}
