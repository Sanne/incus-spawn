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

    @Test
    void everyLineFitsTheBox() {
        for (var services : InitCommand.Services.values()) {
            for (var line : lines(services)) assertTrue(line.length() <= InitCommand.BOX_WIDTH, line);
        }
    }
}
