package dev.incusspawn.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class GitTestUtils {

    private GitTestUtils() {}

    static String runGit(Path dir, String... args) throws IOException, InterruptedException {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process process = null;
        try {
            process = new ProcessBuilder(cmd).directory(dir.toFile())
                    .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            assertEquals(0, exit, "git " + String.join(" ", args) + " failed in " + dir + ": " + output);
            return output;
        } catch (InterruptedException e) {
            try {
                process.destroyForcibly();
            }
            catch (RuntimeException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
    }
}
