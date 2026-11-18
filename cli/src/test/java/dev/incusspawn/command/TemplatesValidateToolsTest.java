package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code isx templates validate} resolves a template's tools as {@code isx build} does (#2291):
 * a template whose tool isx does not know, or whose tool file did not load or has errors of its
 * own, is not valid, since the build would set it up without the tool or refuse it.
 */
@ExtendWith(IsolatedHome.class)
class TemplatesValidateToolsTest {

    @TempDir Path dir;

    /** What a run printed and how it ended, with its JSON findings as {@code severity field message}. */
    record Run(int exit, List<String> findings) {}

    private Run validate(String yaml) throws Exception {
        var file = Files.writeString(dir.resolve("x.yaml"), yaml);
        return validateTarget(file.toString());
    }

    private static Run validateTarget(String target) throws Exception {
        var cmd = new TemplatesCommand.Validate();
        cmd.target = target;
        cmd.format = "json";
        var exit = new int[1];
        var out = RecordingOutput.during(() -> exit[0] = cmd.doExecute().getResultValue());
        JqValue json = JqValues.parseStrict(String.join("\n", out.resultLines()));
        var findings = json.path("findings").asList().stream()
                .map(f -> f.pathText("severity", "") + " " + f.pathText("field", "") + " " + f.pathText("message", ""))
                .toList();
        return new Run(exit[0], findings);
    }

    private static void tool(String file, String yaml) throws Exception {
        var tools = Files.createDirectories(Environment.configDir().resolve("tools"));
        Files.writeString(tools.resolve(file), yaml);
    }

    private static String template(String... tools) {
        return "name: tpl-x\nparent: tpl-minimal\ntools:\n" + String.join("", List.of(tools).stream()
                .map(t -> "  - " + t + "\n").toList());
    }

    @Test
    void anUnknownToolIsAWarningOnItsEntry() throws Exception {
        // Not an error: a template may be saved before its tools are defined; the build refuses it
        var run = validate(template("sshd", "gradlex"));
        assertEquals(0, run.exit());
        assertEquals(List.of("warning tools[1] tool 'gradlex' is not a tool isx knows, so isx build refuses this template"
                + " until it is defined; 'isx tools list' lists the tools"), run.findings());
    }

    @Test
    void anUnknownToolARequiresEntryNamesIsAWarningNamingTheToolThatRequiresIt() throws Exception {
        tool("mine.yaml", "name: mine\nrequires:\n  - nonesuch\n");
        var run = validate(template("mine"));
        assertEquals(0, run.exit());
        assertEquals(List.of("warning tools[0] tool 'nonesuch', which 'mine' requires, is not a tool isx knows,"
                + " so isx build refuses this template until it is defined; 'isx tools list' lists the tools"), run.findings());
    }

    @Test
    void aToolFileThatDoesNotParseIsAnErrorOnTheEntryNamingIt() throws Exception {
        tool("gradlex.yaml", "name: gradlex\ndownloads: [\n");
        var run = validate(template("gradlex"));
        assertEquals(1, run.exit());
        // The file's error alone: the tool is unknown only because it did not load
        assertEquals(1, run.findings().size(), run.findings().toString());
        assertTrue(run.findings().getFirst().startsWith("error tools[0] gradlex.yaml"), run.findings().toString());
        assertTrue(run.findings().getFirst().endsWith("isx build refuses until it is fixed"), run.findings().toString());
    }

    @Test
    void aBrokenToolFileThatShadowsABuiltInIsAnError() throws Exception {
        // The loader skips it, so the template resolves the built-in, and the build refuses
        tool("sshd.yaml", "name: sshd\ndownloads: [\n");
        var run = validate(template("sshd"));
        assertEquals(1, run.exit());
        assertEquals(1, run.findings().size(), run.findings().toString());
        assertTrue(run.findings().getFirst().startsWith("error tools[0] sshd.yaml"), run.findings().toString());
    }

    @Test
    void aBrokenToolFileTheTemplateDoesNotUseIsAWarning() throws Exception {
        tool("other.yaml", "name: other\ndownloads: [\n");
        var run = validate(template("sshd"));
        assertEquals(0, run.exit());
        assertEquals(1, run.findings().size(), run.findings().toString());
        assertTrue(run.findings().getFirst().startsWith("warning tools other.yaml"), run.findings().toString());
    }

    @Test
    void aToolFilesOwnErrorsAreErrorsOnTheEntry() throws Exception {
        tool("mine.yaml", "name: mine\ndownloads:\n  - extract: /opt\n");
        var run = validate(template("mine"));
        assertEquals(1, run.exit());
        assertEquals(List.of("error tools[0] mine.yaml: download entry in 'mine' is missing a 'url'"), run.findings());
    }

    @Test
    void aToolBehindAFeatureNotEnabledIsAWarning() throws Exception {
        tool("gated.yaml", "name: gated\nfeature: experiments\n");
        var run = validate(template("gated"));
        assertEquals(0, run.exit());
        assertEquals(1, run.findings().size(), run.findings().toString());
        assertTrue(run.findings().getFirst().startsWith("warning tools[0] Tool 'gated' in template 'tpl-x' requires feature 'experiments'"),
                run.findings().toString());
    }

    @Test
    void aToolWithNoDistributionForAnArchitectureIsAWarningWhateverTheHost() throws Exception {
        // isx build refuses it only on the other architecture (#2357), so the check says so on any host (#2368)
        tool("corestory.yaml", "name: corestory\narchitectures: [x86_64]\n");
        var run = validate(template("corestory"));
        assertEquals(0, run.exit());
        assertEquals(List.of("warning tools[0] tool 'corestory' supports only x86_64,"
                + " so isx build refuses this template on aarch64"), run.findings());
    }

    @Test
    void aRequiredToolWhoseDownloadsAreAllForOneArchitectureIsAWarningNamingTheEntryThatNeedsIt() throws Exception {
        tool("corestory.yaml", "name: corestory\ndownloads:\n  - url: https://example.com/c.tar.gz\n"
                + "    arch: aarch64\n    extract: /opt\n");
        tool("mine.yaml", "name: mine\nrequires:\n  - corestory\n");
        var run = validate(template("mine"));
        assertEquals(0, run.exit());
        assertEquals(List.of("warning tools[0] tool 'corestory', which 'mine' needs, has downloads only for aarch64,"
                + " so isx build refuses this template on x86_64"), run.findings());
    }

    @Test
    void parametersTheToolRefusesAreAnError() throws Exception {
        var run = validate(template("sshd:\n      nonesuch: 1"));
        assertEquals(1, run.exit());
        assertTrue(run.findings().getFirst().startsWith("error tools[0] Tool 'sshd' does not accept parameters"),
                run.findings().toString());
    }

    @Test
    void everyBuiltInTemplateValidatesWithNoError() throws Exception {
        for (var def : ImageDef.loadAll().values()) {
            if (!def.isBuiltIn()) continue;
            var run = validateTarget(def.getName());
            assertEquals(0, run.exit(), def.getName() + ": " + run.findings());
            assertTrue(run.findings().stream().noneMatch(f -> f.startsWith("error")), def.getName() + ": " + run.findings());
        }
    }
}
