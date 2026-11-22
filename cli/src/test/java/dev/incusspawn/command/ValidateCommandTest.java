package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Isolated: the config check loads the tools of ~/.config/incus-spawn/tools
@ExtendWith(IsolatedHome.class)
class ValidateCommandTest {

    @TempDir Path dir;

    record Run(int exit, JqValue json) {
        List<String> findings() {
            return json.path("findings").asList().stream()
                    .map(f -> f.pathText("severity", "") + " " + f.pathText("message", "")).toList();
        }
    }

    private static Run run(String target, String kind, String stdin) throws Exception {
        var cmd = new ValidateCommand();
        cmd.target = target;
        cmd.kind = kind;
        cmd.format = "json";
        if (stdin != null) cmd.stdin = () -> new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        var exit = new int[1];
        var out = RecordingOutput.during(() -> exit[0] = cmd.doExecute().getResultValue());
        return new Run(exit[0], JqValues.parseStrict(String.join("\n", out.resultLines())));
    }

    private Path file(String name, String yaml) throws Exception {
        return Files.writeString(dir.resolve(name), yaml);
    }

    @Test
    void aBadMcpYamlIsAnErrorWithTheLoadersWords() throws Exception {
        var run = run(file("mcp.yaml", "templates: [a]\nbogus: 1\n").toString(), null, null);
        assertEquals(1, run.exit());
        assertEquals("mcp", run.json().pathText("kind", null));
        assertTrue(run.findings().getFirst().contains("unknown key bogus"), run.findings().toString());
    }

    @Test
    void aGoodMcpYamlIsValid() throws Exception {
        var run = run(file("mcp.yaml", "templates: [a]\n").toString(), null, null);
        assertEquals(0, run.exit());
        assertEquals(List.of(), run.findings());
    }

    @Test
    void theTextReadOnceIsWhatIsChecked() throws Exception {
        // A pipe (<(cat mcp.yaml)) or stdin holds nothing once read: the check must use the text already read
        var run = run("-", null, "# yaml-language-server: $schema=https://isx.run/schema/mcp.json\nbogus: 1\n");
        assertEquals(1, run.exit());
        assertTrue(run.findings().getFirst().contains("unknown key bogus"), run.findings().toString());
    }

    @Test
    void aToolFromStdinIsChecked() throws Exception {
        var run = run("-", "tool", "name: mine\ndownloads:\n  - extract: /opt\n");
        assertEquals(1, run.exit());
        assertEquals(List.of("error download entry in 'mine' is missing a 'url'"), run.findings());
    }

    @Test
    void aConfigYamlThatDoesNotParseIsAnError() throws Exception {
        var run = run(file("config.yaml", "host-routes: [\n").toString(), null, null);
        assertEquals(1, run.exit());
    }

    @Test
    void aConfigYamlWithAnIgnoredGitIdentityIsAWarning() throws Exception {
        var run = run(file("config.yaml", "git-identity:\n  name: x\n").toString(), null, null);
        assertEquals(0, run.exit());
        assertTrue(run.findings().getFirst().startsWith("warning"), run.findings().toString());
    }

    @Test
    void aRepositoryOnADomainTheProxyAlreadyInterceptsIsAWarning() throws Exception {
        var run = run(file("config.yaml", """
                maven-repositories:
                  - url: https://chatgpt.com/maven/
                    access: public
                    confirm: sidecar
                    replaces-releases: false
                """).toString(), null, null);
        assertEquals(0, run.exit());
        assertEquals(1, run.findings().size(), run.findings().toString());
        assertTrue(run.findings().getFirst().contains("chatgpt.com"), run.findings().toString());
    }

    @Test
    void aMissingFileIsAnError() throws Exception {
        var cmd = new ValidateCommand();
        cmd.target = dir.resolve("absent.yaml").toString();
        var exit = new int[1];
        var out = RecordingOutput.during(() -> exit[0] = cmd.doExecute().getResultValue());
        assertEquals(1, exit[0]);
        assertTrue(out.events(RecordingOutput.Kind.ERROR).getFirst().msg().startsWith("No file named"));
    }

    @Test
    void theKindComesFromTheSchemaLineThenTheName() {
        assertEquals(ValidateCommand.Kind.TOOL, ValidateCommand.detect(Path.of("x.yaml"),
                "# yaml-language-server: $schema=https://isx.run/schema/tool.json\nname: x\n"));
        assertEquals(ValidateCommand.Kind.MCP, ValidateCommand.detect(Path.of("x.yaml"),
                "# yaml-language-server: $schema=mcp.json\n"));
        assertEquals(ValidateCommand.Kind.CONFIG, ValidateCommand.detect(Path.of("/a/config.yaml"), "a: 1\n"));
        assertThrows(IllegalArgumentException.class, () -> ValidateCommand.detect(Path.of("x.yaml"), "a: 1\n"));
    }
}
