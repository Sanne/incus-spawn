package dev.incusspawn.incus;

import dev.incusspawn.tool.GeminiSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveShellTest {

    @TempDir
    Path bin;

    @Test
    void geminiActionPreservesTheLegacyLoginGuardWhenZmxExecutesIt() throws Exception {
        var binary = bin.resolve("gemini");
        Files.writeString(binary, "#!/bin/sh\necho 'Gemini binary launched'\nexit 42\n");
        assertTrue(binary.toFile().setExecutable(true));

        var action = new GeminiSetup().actions().getFirst().getCommand();
        // An old guest's login profile exports this guard. Like zmx, exec launches
        // the supplied executable directly instead of resolving shell functions.
        var script = """
                export PATH="$1:$PATH"
                gemini() {
                    echo 'Gemini login through isx is not supported yet' >&2
                    return 1
                }
                export -f gemini
                exec %s
                """.formatted(InteractiveShell.zmxCommand(action));
        var builder = new ProcessBuilder("bash", "--noprofile", "--norc", "-c", script,
                "bash", bin.toString()).redirectErrorStream(true);
        var path = builder.environment().get("PATH");
        builder.environment().clear();
        builder.environment().put("PATH", path);
        var process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Gemini action did not exit");
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, process.exitValue(), output);
            assertTrue(output.contains("Gemini login through isx is not supported yet"), output);
            assertFalse(output.contains("Gemini binary launched"), output);
        } finally {
            process.destroyForcibly();
        }
    }
}
