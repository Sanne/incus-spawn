package dev.incusspawn.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        var process = new ProcessBuilder("bash", "scripts/agent-install/validate.sh", "--print-prompt")
                .directory(ROOT.toFile()).redirectErrorStream(true).start();
        var prompt = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the harness did not exit");
        assertEquals(0, process.exitValue(), prompt);

        assertTrue(prompt.startsWith("Install isx (https://isx.run)"), prompt);
        assertTrue(prompt.contains("https://isx.run/llms.txt"), prompt);
        for (var handover : HANDOVERS) assertTrue(prompt.contains(handover), handover + " is not in the prompt:\n" + prompt);
        assertFalse(prompt.contains("```"), "the prompt runs past its fenced block:\n" + prompt);
        assertTrue(page().contains(prompt.strip()), "the harness's prompt is not the page's");
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

    private static String page() throws IOException {
        return Files.readString(ROOT.resolve("docs/AGENT-INSTALL.md"));
    }
}
