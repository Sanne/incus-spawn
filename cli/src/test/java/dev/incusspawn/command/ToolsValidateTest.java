package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.tool.ToolDefLoader;
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
 * {@code isx tools validate} reports a tool definition's own errors, which the loader does not,
 * before a build fails on them (#2291): a repository of tools checks each file in CI with it.
 */
@ExtendWith(IsolatedHome.class)
class ToolsValidateTest {

    @TempDir Path dir;

    record Run(int exit, RecordingOutput out) {
        JqValue json() throws Exception {
            return JqValues.parseStrict(String.join("\n", out.resultLines()));
        }

        List<String> findings() throws Exception {
            return json().path("findings").asList().stream()
                    .map(f -> f.pathText("severity", "") + " " + f.pathText("message", "")).toList();
        }
    }

    private static Run validate(String target, String format) {
        var cmd = new ToolsCommand.Validate();
        cmd.target = target;
        cmd.format = format;
        var exit = new int[1];
        var out = RecordingOutput.during(() -> exit[0] = cmd.doExecute().getResultValue());
        return new Run(exit[0], out);
    }

    private Path file(String yaml) throws Exception {
        return Files.writeString(dir.resolve("mine.yaml"), yaml);
    }

    @Test
    void aDownloadWithNoUrlIsAnError() throws Exception {
        var run = validate(file("name: mine\ndownloads:\n  - extract: /opt\n").toString(), "json");
        assertEquals(1, run.exit());
        assertEquals("mine", run.json().pathText("name", null));
        assertEquals(List.of("error download entry in 'mine' is missing a 'url'"), run.findings());
    }

    @Test
    void aFileThatDoesNotParseIsAnError() throws Exception {
        var run = validate(file("name: mine\ndownloads: [\n").toString(), "json");
        assertEquals(1, run.exit());
        assertTrue(run.json().path("name").isNull(), run.out().resultLines().toString());
        assertTrue(run.findings().getFirst().startsWith("error mine.yaml"), run.findings().toString());
    }

    @Test
    void anUnknownRequiredToolIsAnError() throws Exception {
        var run = validate(file("name: mine\nrequires:\n  - sshd\n  - nonesuch\n").toString(), "plain");
        assertEquals(1, run.exit());
        assertEquals(List.of("error\t'mine' requires 'nonesuch', which is not a tool isx knows, so isx build skips it;"
                + " 'isx tools list' lists the tools"), run.out().resultLines());
    }

    @Test
    void aValidFileIsValid() throws Exception {
        var run = validate(file("name: mine\nrequires:\n  - claude\nrun:\n  - true\n").toString(), null);
        assertEquals(0, run.exit());
        assertEquals("Tool is valid", run.out().events(RecordingOutput.Kind.OK).getFirst().msg());
    }

    @Test
    void aNameChecksTheFileThatDefinesIt() throws Exception {
        var tools = Files.createDirectories(Environment.configDir().resolve("tools"));
        Files.writeString(tools.resolve("mine.yaml"), "name: mine\ndownloads:\n  - extract: /opt\n");
        var run = validate("mine", "json");
        assertEquals(1, run.exit());
        assertEquals(tools.resolve("mine.yaml").toAbsolutePath().normalize().toString(), run.json().pathText("path", null));

        // One the loader skipped is found by its file name
        Files.writeString(tools.resolve("broken.yaml"), "name: broken\ndownloads: [\n");
        assertEquals(1, validate("broken", null).exit());

        assertEquals(1, validate("nonesuch", null).exit());
    }

    @Test
    void everyBuiltInToolIsValid() {
        for (var name : new ToolDefLoader(List.of()).allToolSetups().keySet()) {
            var run = validate(name, null);
            assertEquals(0, run.exit(), name + ": " + run.out().events());
        }
    }
}
