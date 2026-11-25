package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * A hint is reworded for people, but {@code --plan}'s {@code purpose} is what a script reads,
     * so it stays as the hint once read.
     */
    @Test
    void aRewordedHintKeepsThePlansPurpose() {
        var init = new NoTerminal();
        var search = init.searchPathsStep();
        assertEquals("Local directories where isx looks for custom image and tool definitions. Definitions found"
                + " here can extend or override the built-in templates. Each directory should contain images/"
                + " and/or tools/ subdirectories with YAML files.", search.purpose());
        assertNotEquals(search.purpose(), String.join(" ", search.hint()));
        var firewall = init.linuxSetup().stream().filter(s -> s.id().equals("firewall")).findFirst().orElseThrow();
        assertEquals(HostSetup.FIREWALL_PURPOSE, firewall.purpose());
        assertTrue(firewall.purpose().startsWith("Configures the host firewall so containers can reach the internet"));
    }

    /**
     * Every step's {@code --plan} purpose, on both platforms, on a first run and outside it, as
     * {@code init-plan-purposes.txt} holds it, which is what main had before the hints were
     * reworded (#2542): what a script reads never changes when a hint is. The words for the
     * computer are the running platform's, so they are read as Linux's.
     */
    @Test
    void everyPlanPurposeStaysAsItWas() throws Exception {
        String expected;
        try (var in = InitAgainTextTest.class.getResourceAsStream("init-plan-purposes.txt")) {
            expected = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertEquals(expected, planPurposes());
    }

    /** The purposes, a line each: platform, run and step id, then the purpose. */
    static String planPurposes() {
        var out = new StringBuilder();
        for (var macOs : List.of(false, true)) {
            for (var first : List.of(false, true)) {
                var init = new NoTerminal();
                init.firstRun = first;
                for (var step : init.planSteps(macOs)) {
                    out.append(macOs ? "macos" : "linux").append(' ').append(first ? "first" : "again").append(' ')
                            .append(step.id()).append('\t')
                            .append(step.purpose().replace(dev.incusspawn.Platform.thisComputer(), "this computer")).append('\n');
                }
            }
        }
        return out.toString();
    }

    /** Every hint fits under its header: a line wider than the frame wraps and breaks the block. */
    @Test
    void everyHintFitsTheFrame() {
        var init = new NoTerminal();
        var steps = new ArrayList<InitCommand.InitStep>(init.linuxSetup());
        steps.addAll(init.finalSteps(init.proxyServiceStep(new AtomicBoolean())));
        steps.addAll(init.macOsSetup());
        steps.add(init.macOsServicesStep(new AtomicBoolean()));
        steps.add(init.gitRemoteHelper());
        steps.add(init.incusVm());
        steps.add(init.dependencies());
        for (var step : steps) {
            for (var line : step.hint()) {
                assertTrue(line.length() <= Lines.WIDTH, step.id() + ": " + line.length() + " columns: " + line);
            }
        }
    }
}
