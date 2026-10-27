package dev.incusspawn.command;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the generated zsh completion in a real interactive zsh and checks the buffer TAB leaves
 * behind. zsh only completes inside zle, so the driver (a test resource) uses zpty to type a
 * line, press TAB and capture the buffer.
 *
 * <p>The fake {@code isx} answers every source with one candidate, which TAB inserts
 * unambiguously: the captured buffer is then exactly what the completion offered. This is the
 * regression test for the dispatcher shifting {@code words} after {@code _arguments -C} had
 * already done so, which made {@code isx destroy <TAB>} (and every other positional value) list
 * nothing.
 */
class ZshCompletionTest {

    @TempDir
    Path dir;

    private static Boolean zshUsable;

    private static synchronized boolean zshUsable() {
        if (zshUsable == null) {
            try {
                var p = new ProcessBuilder("zsh", "-f", "-c", "zmodload zsh/zpty")
                        .redirectErrorStream(true)
                        .start();
                zshUsable = p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (Exception e) {
                zshUsable = false;
            }
        }
        return zshUsable;
    }

    /** One candidate per source: box1 for instances, base for templates, claude/claude=work for accounts. */
    private static final String FAKE_ISX = """
            #!/bin/bash
            case "$1 $2 $3" in
              "list -q ") echo box1 ;;
              "templates  ") echo base ;;
              "tools list ") echo claude ;;
              "account list --completion") printf 'claude\\nclaude=work\\n' ;;
              *) exit 0 ;;
            esac
            """;

    /** Completes {@code line} with the cursor at its end and returns the resulting buffer, stripped. */
    private String complete(String line) throws Exception {
        Assumptions.assumeTrue(zshUsable(), "zsh with the zpty module is not installed");

        var bin = Files.createDirectories(dir.resolve("bin"));
        var isx = bin.resolve("isx");
        Files.writeString(isx, FAKE_ISX);
        isx.toFile().setExecutable(true);
        var script = dir.resolve("_isx");
        Files.writeString(script, CompletionCommand.rawScript(CompletionCommand.Shell.zsh));
        var driver = dir.resolve("zsh-completion-driver.zsh");
        try (InputStream in = getClass().getResourceAsStream("/zsh-completion-driver.zsh")) {
            assertNotNull(in, "driver resource is on the test classpath");
            Files.write(driver, in.readAllBytes());
        }
        var out = dir.resolve("buffer.txt");
        var log = dir.resolve("driver.log");
        var pb = new ProcessBuilder("zsh", "-f", driver.toString(),
                bin.toString(), script.toString(), line, out.toString());
        pb.redirectErrorStream(true).redirectOutput(log.toFile());
        var p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail("zsh completion did not finish in 30s");
        }
        assertEquals(0, p.exitValue(), () -> "driver failed: " + readQuietly(log));
        return Files.readString(out).strip();
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return "(no driver log)";
        }
    }

    @Test
    void destroyCompletesInstanceNames() throws Exception {
        assertEquals("isx destroy box1", complete("isx destroy "));
        assertEquals("isx destroy box1", complete("isx destroy bo"));
    }

    @Test
    void destroyStillCompletesItsOptions() throws Exception {
        assertEquals("isx destroy --all-instances", complete("isx destroy --all-i"));
    }

    @Test
    void otherCommandsCompleteTheirPositionalValues() throws Exception {
        assertEquals("isx shell box1", complete("isx shell "));
        assertEquals("isx branch --from box1", complete("isx branch --from "));
        assertEquals("isx build base", complete("isx build "));
        assertEquals("isx templates edit base", complete("isx templates edit "));
        assertEquals("isx tools show claude", complete("isx tools show "));
        assertEquals("isx project update box1", complete("isx project update "));
    }

    @Test
    void accountSubcommandsCompleteTheirOwnArguments() throws Exception {
        assertEquals("isx account show box1", complete("isx account show "));
        assertEquals("isx account set box1", complete("isx account set "));
        assertEquals("isx account set box1 claude=work", complete("isx account set box1 "));
        assertEquals("isx account unset box1 claude", complete("isx account unset box1 "));
        assertEquals("isx account add claude", complete("isx account add "));
    }
}
