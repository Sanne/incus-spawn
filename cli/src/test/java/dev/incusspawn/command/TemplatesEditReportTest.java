package dev.incusspawn.command;

import dev.incusspawn.util.Output;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.Environment;
import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(IsolatedHome.class)
class TemplatesEditReportTest {

    @Test
    void anIgnoredHostResourceKeyIsReportedOnceAfterAnEdit() throws Exception {
        // #1543: the edit flow loads every definition, then validates the edited file. The
        // load's warning about this file's ignored key is the report's, so it is not printed
        // twice, and the file is not called valid; a warning about another file still passes on.
        var images = Files.createDirectories(Environment.configDir().resolve("images"));
        var file = images.resolve("claude.yaml");
        Files.writeString(file, """
                name: tpl-claude-md
                image: images:fedora/44
                host-resources:
                  - source: ~/.claude/CLAUDE-isx.md
                    target: ~/.claude/CLAUDE.md
                """);
        Files.writeString(images.resolve("unnamed.yaml"), "image: images:fedora/44\n");
        var loadWarnings = new ArrayList<String>();
        var defs = ImageDef.loadAll(loadWarnings::add);
        var passedOn = new ArrayList<String>();
        var report = new RecordingOutput();

        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(passedOn::add))) {
            TemplatesCommand.validateAndReport(file, defs, loadWarnings, report);
        }

        assertEquals(List.of("unknown key 'host-resources[0].target', ignored; use 'path:' to set where it goes"
                + " in the instance"), report.warnings());
        // The load also reads the working directory's project-local templates, so only these two are asserted.
        assertTrue(passedOn.contains("unnamed.yaml: no 'name:'; it is skipped, and isx build refuses until it is fixed"),
                passedOn.toString());
        assertFalse(passedOn.stream().anyMatch(w -> w.startsWith("claude.yaml: ")), passedOn.toString());
    }

    @Test
    void aRepoTheLoaderRefusesIsTheChecksErrorAndSaidOnce() throws Exception {
        // #2076: the shared check refuses what the loader skips the file for, so the editor no
        // longer calls such a file valid; the load's warning about it is the same finding
        var images = Files.createDirectories(Environment.configDir().resolve("images"));
        var file = images.resolve("shallow.yaml");
        Files.writeString(file, """
                name: tpl-shallow
                image: images:fedora/44
                repos:
                  - url: https://example.com/a.git
                    depth: 0
                """);
        var loadWarnings = new ArrayList<String>();
        var defs = ImageDef.loadAll(loadWarnings::add);
        var passedOn = new ArrayList<String>();
        var report = new RecordingOutput();

        boolean valid;
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(passedOn::add))) {
            valid = TemplatesCommand.validateAndReport(file, defs, loadWarnings, report);
        }

        assertFalse(valid);
        assertEquals(List.of("repo https://example.com/a.git: 'depth' 0 is not a positive number of commits"),
                report.events(RecordingOutput.Kind.ERROR).stream().map(RecordingOutput.Event::msg).toList());
        assertFalse(passedOn.stream().anyMatch(w -> w.startsWith("shallow.yaml: ")), passedOn.toString());
    }

    @Test
    void aMissingCredentialIsAWarningAfterAnEditToo() throws Exception {
        // #2163: the editor reports what --from and validate report, so a template made in the
        // editor says before its build that isx branch will refuse it
        var images = Files.createDirectories(Environment.configDir().resolve("images"));
        var file = images.resolve("tpl-agent.yaml");
        Files.writeString(file, "name: tpl-agent\nparent: tpl-minimal\ntools:\n  - claude\n");
        var report = new RecordingOutput();

        assertTrue(TemplatesCommand.validateAndReport(file, ImageDef.loadAll(w -> {}), List.of(), report));

        assertEquals(1, report.warnings().size(), report.warnings().toString());
        assertTrue(report.warnings().getFirst().contains("'isx account add claude'"), report.warnings().toString());
    }
}
