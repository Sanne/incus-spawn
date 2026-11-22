package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidateCommandTest {

    @TempDir Path dir;

    private int[] exit = new int[1];

    private String run(Path file, String kind) throws Exception {
        var cmd = new ValidateCommand();
        cmd.target = file.toString();
        cmd.kind = kind;
        cmd.format = "json";
        var out = RecordingOutput.during(() -> exit[0] = cmd.doExecute().getResultValue());
        return String.join("\n", out.resultLines());
    }

    @Test
    void aBadMcpYamlIsAnErrorWithTheLoadersWords() throws Exception {
        var file = Files.writeString(dir.resolve("mcp.yaml"), "templates: [a]\nbogus: 1\n");
        var json = run(file, null);
        assertEquals(1, exit[0], json);
        assertTrue(json.contains("\"kind\" : \"mcp\""), json);
        assertTrue(json.contains("unknown key bogus"), json);
    }

    @Test
    void aGoodMcpYamlIsValid() throws Exception {
        var file = Files.writeString(dir.resolve("mcp.yaml"), "templates: [a]\n");
        run(file, null);
        assertEquals(0, exit[0]);
    }

    @Test
    void aConfigYamlThatDoesNotParseIsAnError() throws Exception {
        var file = Files.writeString(dir.resolve("config.yaml"), "host-routes: [\n");
        run(file, null);
        assertEquals(1, exit[0]);
    }

    @Test
    void aConfigYamlWithAnIgnoredGitIdentityIsAWarning() throws Exception {
        var file = Files.writeString(dir.resolve("config.yaml"), "git-identity:\n  name: x\n");
        var json = run(file, null);
        assertEquals(0, exit[0], json);
        assertTrue(json.contains("warning"), json);
    }

    @Test
    void theKindComesFromTheSchemaLineThenTheName() {
        assertEquals(ValidateCommand.Kind.TOOL, ValidateCommand.detect(Path.of("x.yaml"),
                "# yaml-language-server: $schema=https://isx.run/schema/tool.json\nname: x\n"));
        assertEquals(ValidateCommand.Kind.CONFIG, ValidateCommand.detect(Path.of("/a/config.yaml"), "a: 1\n"));
        assertThrows(IllegalArgumentException.class, () -> ValidateCommand.detect(Path.of("x.yaml"), "a: 1\n"));
    }
}
