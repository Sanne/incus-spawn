package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.McpScope;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/MCP.md "From a fresh install" is the short way from the Quick Start's {@code tpl-agent} to a
 * coordinator in an instance (#2079): a ready definition handed to {@code isx templates new --from -},
 * a build, the person's approval and a branch. This runs the page's own definition through the
 * command and checks the coordinator it makes may branch {@code tpl-agent} once approved, and
 * nothing before: the page adds a coordinator, never a way around the approval.
 */
@Tag("docs")
@ExtendWith(IsolatedHome.class)
class CoordinatorFromFreshInstallDocsTest {

    private static final String HEREDOC = "isx templates new tpl-coordinator --from - <<'EOF'\n";

    private static final String QUICK_START_BLOCK = "```yaml\nname: tpl-agent\n";

    private static String page() throws IOException {
        return Files.readString(Path.of("..", "docs", "MCP.md"));
    }

    /** The README Quick Start's own {@code tpl-agent}, which the page builds on, so a change there reaches this. */
    private static String quickStart() throws IOException {
        var readme = Files.readString(Path.of("..", "README.md"));
        var start = readme.indexOf(QUICK_START_BLOCK);
        assertTrue(start >= 0, "README.md no longer defines tpl-agent in a yaml block");
        var body = readme.substring(start + "```yaml\n".length());
        var end = body.indexOf("\n```");
        assertTrue(end >= 0, "README.md's tpl-agent block is not closed");
        return body.substring(0, end + 1);
    }

    /** The definition between the page's heredoc and its {@code EOF}, as the shell hands it to isx. */
    private static String definition(String page) {
        var start = page.indexOf(HEREDOC);
        assertTrue(start >= 0, "docs/MCP.md no longer shows: " + HEREDOC);
        var body = page.substring(start + HEREDOC.length());
        var end = body.indexOf("\nEOF\n");
        assertTrue(end >= 0, "docs/MCP.md's definition no longer ends at EOF");
        return body.substring(0, end + 1);
    }

    /** Stores {@code yaml} as {@code name} the way the page does, and returns what isx warned of. */
    private static List<String> newFromStdin(String name, String yaml) {
        var cmd = new TemplatesCommand.New();
        cmd.name = name;
        cmd.from = "-";
        cmd.stdin = () -> new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        var exit = new int[1];
        var out = RecordingOutput.during(() -> exit[0] = cmd.doExecute().getResultValue());
        assertEquals(0, exit[0], out.events().toString());
        return out.warnings();
    }

    @Test
    void thePagesDefinitionMakesACoordinatorOfTheQuickStartsTemplate() throws IOException {
        var page = page();
        // The Quick Start sets up the Claude credential in isx init, before tpl-agent
        IsolatedHome.seed("claude:\n  apiKey: \"sk-ant-api-x\"\n");
        assertEquals(List.of(), newFromStdin("tpl-agent", quickStart()));
        // What it reaches is shown to the person, which is the review the page asks of them
        var reach = newFromStdin("tpl-coordinator", definition(page));
        assertEquals(1, reach.size(), reach.toString());
        assertTrue(reach.getFirst().contains("may create instances of tpl-agent (as far as mcp.yaml allows)"), reach.toString());

        var defs = ImageDef.loadTrusted();
        var coordinator = defs.get("tpl-coordinator");
        assertTrue(ImageDef.resolveCoordinatorDefault(coordinator, defs), "every branch of it is a coordinator");
        assertTrue(ImageDef.chain(coordinator, defs).stream().anyMatch(d -> d.getTools().stream().anyMatch(t -> t.getName().equals("claude"))),
                "it runs Claude Code, which every start registers the server with");

        // Approved, it controls the worker; unapproved, nothing: the person's approval still gates it
        assertEquals(List.of("tpl-agent"), McpScope.coordinator("tpl-coordinator", null, defs, List.of("tpl-agent")).templates());
        assertEquals(List.of(), McpScope.coordinator("tpl-coordinator", null, defs, List.of()).templates());
    }

    @Test
    void theSequenceBuildsApprovesAndBranchesWhatItDefined() throws IOException {
        var page = page();
        var start = page.indexOf(HEREDOC);
        assertTrue(start >= 0, "docs/MCP.md no longer shows: " + HEREDOC);
        var afterDefinition = page.substring(start + HEREDOC.length() + definition(page).length() + "EOF\n".length());
        var fence = afterDefinition.indexOf("```");
        assertTrue(fence >= 0, "docs/MCP.md's command block is not closed");
        afterDefinition = afterDefinition.substring(0, fence);
        assertEquals(List.of("isx build tpl-coordinator", McpScope.SETUP_COMMAND, "isx branch coord --from tpl-coordinator"),
                afterDefinition.lines().toList());
    }
}
