package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The box {@code isx init} closes with leads to a first agent, as README's Quick Start does (#1435). */
class InitNextStepsTest {

    private static List<String> lines(InitCommand.Services services) {
        return Arrays.stream(InitCommand.nextSteps(services)).map(BuildOutput::stripAnsi).toList();
    }

    @Test
    @Tag("docs")
    void stepsEndInABranchWithAnAgent() throws Exception {
        var text = String.join("\n", lines(InitCommand.Services.INSTALLED));
        var readme = Files.readString(Path.of("..", "README.md"));
        var start = readme.indexOf("\n## Quick Start\n");
        assertTrue(start >= 0, "the link's anchor is README's heading");
        var end = readme.indexOf("\n## ", start + 1);
        var quickStart = readme.substring(start, end < 0 ? readme.length() : end);
        for (var command : List.of("isx build tpl-agent", "isx branch my-first --from tpl-agent")) {
            assertTrue(text.contains(command), text);
            assertTrue(quickStart.contains(command), "README's Quick Start runs " + command);
        }
        assertTrue(text.contains("https://isx.run/docs#quick-start"), text);
        assertFalse(text.contains("tpl-minimal"), text);
        assertFalse(text.contains("isx proxy start"), text);
    }

    @Test
    void theProxyComesFirstWhenNothingStartsIt() {
        var lines = lines(InitCommand.Services.START_PROXY);
        assertTrue(lines.contains("   1. Start the proxy:   isx proxy start"), String.join("\n", lines));
        assertTrue(lines.contains("   4. Branch it:         isx branch my-first --from tpl-agent"),
                String.join("\n", lines));
    }

    @Test
    void anInstalledServiceIsSaid() {
        var lines = lines(InitCommand.Services.INSTALLED);
        assertTrue(lines.contains("   isx's services start on their own."), String.join("\n", lines));
        assertTrue(lines.contains("   1. Define tpl-agent:  https://isx.run/docs#quick-start"),
                String.join("\n", lines));
    }

    @Test
    void onMacOsWithoutServicesTheVmAndProxyComeFirst() {
        var lines = lines(InitCommand.Services.START_VM_AND_PROXY);
        assertTrue(lines.contains("   1. Start VM, proxy:   isx vm start && isx proxy start"), String.join("\n", lines));
        assertFalse(lines.contains("   isx's services start on their own."), String.join("\n", lines));
    }

    /** A service whose install failed is said in the header, never ticked as a complete setup (#2597). */
    @Test
    void aFailedServiceIsNotCalledComplete() {
        var lines = Arrays.stream(InitCommand.nextSteps(InitCommand.Services.START_PROXY, "the proxy service", false))
                .map(BuildOutput::stripAnsi).toList();
        var text = String.join("\n", lines);
        assertFalse(text.contains("Setup complete"), text);
        assertEquals("   ! Setup finished without the proxy service.", lines.getFirst());
        assertTrue(lines.contains("   1. Start the proxy:   isx proxy start"), text);
    }

    /** A host with a template built is not told to define and build one again (#2598). */
    @Test
    void aHostWithATemplateIsNotLedToAFirstOne() {
        var lines = Arrays.stream(InitCommand.nextSteps(InitCommand.Services.START_PROXY, null, true))
                .map(BuildOutput::stripAnsi).toList();
        var text = String.join("\n", lines);
        assertFalse(text.contains("first agent"), text);
        assertFalse(text.contains("tpl-agent"), text);
        assertTrue(lines.contains("   1. Start the proxy:   isx proxy start"), text);
        assertTrue(lines.contains("   2. Branch a template: isx branch <name> --from <template>"), text);
        assertTrue(lines.contains("   Or launch the TUI:    isx"), text);
    }

    /**
     * A re-run that found everything set up is not told a setup just completed, and its one next
     * step is not a numbered list of one (#2542, seen on a Mac).
     */
    @Test
    void aReRunWithNothingLeftSaysHowTheComputerStands() {
        var lines = dev.incusspawn.Platform.wordedAs(true, () -> Arrays.stream(InitCommand.nextSteps(InitCommand.Services.INSTALLED, null, true))
                .map(BuildOutput::stripAnsi).toList());
        assertEquals(List.of("   ✓ Setup complete", "", "   isx's services start on their own.", "",
                "   Next step:",
                "   Branch a template: isx branch <name> --from <template>",
                "   Or launch the TUI: isx"), lines);
    }

    /** A service step still left is not called a set-up computer: the header says a step is left (#2673 review). */
    @Test
    void aServiceStepLeftIsNotCalledSetUp() {
        for (var services : InitCommand.Services.values()) {
            if (services == InitCommand.Services.INSTALLED) continue;
            var lines = dev.incusspawn.Platform.wordedAs(false, () -> Arrays.stream(InitCommand.nextSteps(services, null, true))
                    .map(BuildOutput::stripAnsi).toList());
            assertEquals("   ! One step left: " + (services.installs() ? "install" : "start") + " isx's services",
                    lines.getFirst(), services.name());
        }
    }

    /** Services the person declined are said plainly, never as a warning against their answer (#2673 review). */
    @Test
    void declinedServicesAreNotAWarning() {
        var lines = dev.incusspawn.Platform.wordedAs(true, () -> Arrays.stream(InitCommand.nextSteps(InitCommand.Services.START_VM_AND_PROXY, null, true, true))
                .map(BuildOutput::stripAnsi).toList());
        assertEquals(List.of(
                "   ✓ isx is ready. Start its services when you need them:",
                "",
                "   Start VM, proxy:   isx vm start && isx proxy start",
                "",
                "   " + "Next step:",
                "   Branch a template: isx branch <name> --from <template>",
                "   Or launch the TUI: isx"), lines.subList(0, 7));
    }

    @Test
    void declinedServicesKeepTheTemplateStepsApart() {
        var lines = Arrays.stream(InitCommand.nextSteps(InitCommand.Services.START_PROXY, null, false, true))
                .map(BuildOutput::stripAnsi).toList();
        // Building a template is not a way to start the services, so it does not follow their colon
        assertEquals("   Start the proxy:   isx proxy start", lines.get(2));
        assertEquals("   Next steps, to a first agent:", lines.get(4));
        assertEquals("   1. Define tpl-agent:  https://isx.run/docs#quick-start", lines.get(5));
    }

    @Test
    void aServiceInstallLeftSaysInstall() {
        var header = BuildOutput.stripAnsi(InitCommand.nextSteps(InitCommand.Services.INSTALL_PROXY, null, true, false)[0]);
        assertEquals("   ! One step left: install isx's services", header);
    }

    @Test
    void everyLineFitsTheBox() {
        for (var services : InitCommand.Services.values()) {
            for (var failed : Arrays.asList(null, "the proxy service", "the VM and proxy services")) {
                for (var built : List.of(false, true)) {
                    for (var declined : List.of(false, true)) {
                        for (var line : InitCommand.nextSteps(services, failed, built, declined)) {
                            line = BuildOutput.stripAnsi(line);
                            assertTrue(line.length() <= InitCommand.BOX_WIDTH, line);
                        }
                    }
                }
            }
        }
    }
}
