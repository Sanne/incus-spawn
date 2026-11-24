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
                new ScriptedPrompts().line("3").secret("sk-proj-" + "e".repeat(40))));
        b.add(new Branch("08-gemini", "gemini, an API key", "gemini", null, h -> { },
                new ScriptedPrompts().line("1").secret("AIza" + "f".repeat(35))));
        b.add(new Branch("09-unknown", "a name no step takes", "nope", null, h -> { }, new ScriptedPrompts()));
        b.add(new Branch("11-borrowed", "a tool that spends another tool's credential", "copilot", null, h -> { }, new ScriptedPrompts()));
        b.add(new Branch("12-menu-two-chosen", "No credential named; Claude and GitHub chosen, both skipped", null, null, h -> { },
                new ScriptedPrompts().line("1, 2", "4").secret("")));
        b.add(new Branch("10-atlassian", "atlassian", "atlassian-mcp", null, h -> { }, ScriptedPrompts.lines("")));
        return b;
    }

    @Test
    void everyPathIsWrittenAndHeldToTheFrameAndTheWords() throws Exception {
        if (Files.isDirectory(OUT)) try (var old = Files.list(OUT)) { for (var f : old.toList()) Files.delete(f); }
        Files.createDirectories(OUT);
        var faults = new ArrayList<String>();
        for (var branch : branches()) {
            IsolatedHome.configFile().toFile().delete();
            if (branch.config() != null) IsolatedHome.seed(branch.config());
            var text = transcript(branch);
            Files.writeString(OUT.resolve(branch.name() + ".txt"), text);
            assertFalse(text.contains("⟪the run threw"), branch.name() + ":\n" + text);
            var lineNo = 0;
            for (var line : text.split("\n", -1)) {
                lineNo++;
                var shown = line.replaceAll("(⏎|••••|⟪end of input⟫)$", "");
                if (shown.strip().startsWith("⟪") || shown.strip().startsWith("https://")) continue;
                var box = shown.startsWith("│") || shown.startsWith("╭") || shown.startsWith("╰");
                if (shown.codePointCount(0, shown.length()) > (box ? InitTranscriptsTest.BOX : InitTranscriptsTest.WIDTH))
                    faults.add(branch.name() + ":" + lineNo + " wider than the frame: " + line);
                if (NEVER.matcher(shown).find()) faults.add(branch.name() + ":" + lineNo + " a word it never uses: " + line);
            }
        }
        assertEquals(List.of(), faults);
    }

    @Test
    void aMenuWithNothingChosenSaysHowToComeBack() throws Exception {
        var text = transcript(branches().getFirst());
        assertTrue(text.contains("Which credentials should isx set up? Choose one or more:"), text);
        assertTrue(text.contains("Skipped. 'isx account add' adds one later."), text);
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
