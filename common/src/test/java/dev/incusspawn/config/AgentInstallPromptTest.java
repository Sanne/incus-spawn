package dev.incusspawn.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The agent install page (docs/AGENT-INSTALL.md) publishes the prompt a person gives their agent,
 * and the clean-host harness (scripts/agent-install/validate.sh) gives an agent that same prompt,
 * read from the page (#2081). Each one names the three handovers, and llms.txt, which the prompt
 * sends the agent to first, says how to reach them.
 */
@Tag("docs")
class AgentInstallPromptTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    /** What the prompt and llms.txt both name: the root script, a credential, the approvals. */
    private static final List<String> HANDOVERS = List.of("isx init --no-sudo", "isx account add", "isx mcp setup");

    @Test
    void theHarnessGivesTheAgentThePagesPrompt() throws Exception {
        var prompt = harnessPrompt();

        assertTrue(prompt.startsWith("Install isx (https://isx.run)"), prompt);
        assertTrue(prompt.contains("https://isx.run/llms.txt"), prompt);
        for (var handover : HANDOVERS) assertTrue(prompt.contains(handover), handover + " is not in the prompt:\n" + prompt);
        assertFalse(prompt.contains("```"), "the prompt runs past its fenced block:\n" + prompt);
        assertTrue(page().contains(prompt.strip()), "the harness's prompt is not the page's");
    }

    /** The agents isx has a tool for, as their users call them or as their tool and namespace are named. */
    private static final Pattern AGENT = Pattern.compile("\\b(Claude|Codex|Pi|opencode|Gemini|Bob|Copilot)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * One prompt for every agent (#2177): naming one makes it the default, which a user of another
     * agent has to edit away. The agent puts itself in the template, from llms.txt's table.
     */
    @Test
    void thePromptNamesNoAgent() throws Exception {
        var prompt = harnessPrompt();
        var named = AGENT.matcher(prompt);
        if (named.find()) fail("the prompt names " + named.group()
                + ", so it is that agent's prompt and every other agent's user edits it:\n" + prompt);
    }

    /** The agents isx has a tool for, as the install page and the validation notes name them. */
    private static final List<String> AGENT_NAMES =
            List.of("Claude Code", "Codex CLI", "Pi", "opencode", "Gemini CLI", "Bob Shell", "GitHub Copilot CLI");

    /**
     * The page promises only what a clean-VM run showed (#2178): the agents its "tested" sentence
     * names are exactly those docs/dev/AGENT-INSTALL-VALIDATION.md records as passing.
     */
    @Test
    void thePageNamesExactlyTheAgentsThatPassedTheHarness() throws IOException {
        var tested = page().lines().filter(l -> l.startsWith("It has been tested on a clean host with these agents: "))
                .findFirst().orElseThrow(() -> new AssertionError("the page has no \"tested on a clean host\" sentence"));
        var named = AGENT_NAMES.stream().filter(a -> tested.matches(".*[ :,]" + Pattern.quote(a) + "[,.].*")).sorted().toList();
        var notes = Files.readString(ROOT.resolve("docs/dev/AGENT-INSTALL-VALIDATION.md"));
        var section = notes.substring(notes.indexOf("## Agents that passed"));
        var next = section.indexOf("\n## ", 1);
        if (next >= 0) section = section.substring(0, next);
        var passed = section.lines().filter(l -> l.startsWith("| ") && !l.startsWith("| Agent "))
                .map(l -> l.substring(2, l.indexOf(" |"))).sorted().toList();
        assertFalse(passed.isEmpty(), "the validation notes record no agent that passed");
        assertTrue(AGENT_NAMES.containsAll(passed), "the validation notes name an agent this test does not know: " + passed);
        assertEquals(passed, named, "the page's tested agents are not the ones the validation notes record as passing");
    }

    /** The page's prompt is the block a reader copies, so it carries the homepage's Copy button (#2177). */
    @Test
    void thePromptHasACopyButton() throws IOException {
        assertTrue(page().contains("<!-- copy -->\n```text\nInstall isx (https://isx.run)"),
                "the prompt's block is not marked <!-- copy --> right before its fence, so site/render.js gives it no Copy button");
    }

    @Test
    void llmsTxtLeadsAnAgentToThePageAndTheHandovers() throws IOException {
        var llms = Files.readString(ROOT.resolve("site/llms.txt"));
        assertTrue(llms.contains("https://isx.run/docs/agent-install"), "llms.txt does not link the install page");
        for (var handover : HANDOVERS) assertTrue(llms.contains(handover), handover + " is not in llms.txt");
    }

    /**
     * On Linux an agent's session predates the incus-admin group the root script adds, so an MCP
     * server it registers without {@code sg incus-admin} is refused by the Incus socket (#2178:
     * Claude Code took the plain form twice while llms.txt offered sg only as a condition).
     * llms.txt gives one command per agent, {@code isx mcp register}, which chooses the sg form
     * itself (#2189), and the prompt sends the agent to it.
     */
    @Test
    void theRegistrationIsOneCommandThatChoosesSg() throws Exception {
        assertTrue(harnessPrompt().contains("`isx mcp register`"), "the prompt does not name isx mcp register");
        var llms = Files.readString(ROOT.resolve("site/llms.txt"));
        for (var command : List.of("`<isx> mcp register claude`", "`<isx> mcp register codex`", "`<isx> mcp register opencode`", "`<isx> mcp config`"))
            assertTrue(llms.contains(command), "llms.txt does not give the registration " + command);
        assertFalse(llms.contains("claude mcp add"), "llms.txt still has an agent write the registration itself");
    }

    /** The prompt the clean-host harness gives its agent, read from the page. */
    private static String harnessPrompt() throws Exception {
        var process = new ProcessBuilder("bash", "scripts/agent-install/validate.sh", "--print-prompt")
                .directory(ROOT.toFile()).redirectErrorStream(true).start();
        var prompt = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the harness did not exit");
        assertEquals(0, process.exitValue(), prompt);
        return prompt;
    }

    private static String page() throws IOException {
        return Files.readString(ROOT.resolve("docs/AGENT-INSTALL.md"));
    }
}
