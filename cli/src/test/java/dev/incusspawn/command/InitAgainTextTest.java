package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@code isx init} shows outside the first run (#2542): on a re-run, without a terminal and
 * for one step by name.
 */
@ExtendWith(IsolatedHome.class)
class InitAgainTextTest {

    /** Init with no terminal, whose steps are those the test hands it. */
    static class NoTerminal extends InitCommand {
        @Override
        Prompts prompts() {
            return null;
        }
    }

    private static InitCommand.InitStep plumbing(String id) {
        return new InitCommand.InitStep(id, id, new String[0], () -> {}, InitPlan.Assessment::done);
    }

    /**
     * The DNS step runs after the questions and prints only when it retries or fails. It opened a
     * second "Installing what isx needs on this computer" over nothing, which read as a setup that did not happen.
     */
    @Test
    void aPlumbingStepAfterTheQuestionsOpensNoSecondHeader() throws Exception {
        var init = new NoTerminal();
        var recorded = RecordingOutput.during(() -> {
            init.begin(plumbing("incus"));
            init.begin(new InitCommand.InitStep("mcp", "Agent Access over MCP", new String[0], () -> {}, InitPlan.Assessment::done));
            init.begin(plumbing("dns"));
        });
        var headers = recorded.events(RecordingOutput.Kind.GROUP).stream().map(RecordingOutput.Event::msg)
                .filter(title -> !title.isEmpty()).toList();
        assertEquals(List.of("Installing what isx needs on " + dev.incusspawn.Platform.thisComputer()), headers);
    }

    /**
     * A plumbing step that asks shows its own header and hint, which breaks the block: the
     * plumbing after it opens "Installing what isx needs on this computer" again rather than printing under the
     * asking step's frame.
     */
    @Test
    void aPlumbingStepThatAsksBreaksTheBlockAndTheNextOneOpensItAgain() throws Exception {
        var init = new InitCommand() {
            @Override
            Prompts prompts() {
                return announcing(ScriptedPrompts.lines("y"));
            }
        };
        var recorded = RecordingOutput.during(() -> {
            init.begin(plumbing("incus"));
            init.begin(new InitCommand.InitStep("subids", "User Namespace Mappings", new String[]{"hint"},
                    () -> init.prompts().ask("Replace it? "), InitPlan.Assessment::done));
            init.begin(plumbing("storage-network"));
        });
        var headers = recorded.events(RecordingOutput.Kind.GROUP).stream().map(RecordingOutput.Event::msg)
                .filter(title -> !title.isEmpty()).toList();
        var header = "Installing what isx needs on " + dev.incusspawn.Platform.thisComputer();
        assertEquals(List.of(header, header), headers);
    }

    /**
     * A continuation under sg carries on its parent's block without a header, until a step's
     * question breaks it: the plumbing after that opens the block again under its header.
     */
    @Test
    void aContinuationReopensTheBlockAfterAStepThatAsks() throws Exception {
        var init = new InitCommand() {
            @Override
            Prompts prompts() {
                return announcing(ScriptedPrompts.lines("y"));
            }

            @Override
            boolean continuedInGroup() {
                return true;
            }
        };
        var recorded = RecordingOutput.during(() -> init.runPlumbing(List.of(
                plumbing("incus"),
                new InitCommand.InitStep("subids", "User Namespace Mappings", new String[]{"hint"},
                        () -> init.prompts().ask("Replace it? "), InitPlan.Assessment::done),
                plumbing("storage-network")), 0));
        var headers = recorded.events(RecordingOutput.Kind.GROUP).stream().map(RecordingOutput.Event::msg)
                .filter(title -> !title.isEmpty()).toList();
        assertEquals(List.of("Installing what isx needs on " + dev.incusspawn.Platform.thisComputer()), headers);
    }

    /** Without a terminal Credential Setup said nothing under its header, as if it had nothing to ask. */
    @Test
    void credentialSetupWithoutATerminalSaysItSkipped() throws Exception {
        var init = new NoTerminal();
        var recorded = RecordingOutput.during(() -> init.credentialSetup().run().run());
        var notes = recorded.events(RecordingOutput.Kind.NOTE).stream().map(RecordingOutput.Event::msg).toList();
        assertEquals(List.of("Skipped: this run has no terminal to ask in.",
                "'isx account add' sets one up at a terminal."), notes);
        assertTrue(init.skipped.contains("credentials"));
    }
}
