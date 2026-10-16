package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * macOS's perl one-liner, checked on Linux too, where {@link OwnSession} picks setsid; and the
 * processes it starts for isx to wait on, stopped when isx exits (#1425). The chosen wrapper is
 * checked by {@code VmOwnSessionTest}.
 */
class OwnSessionTest {

    @Test
    void perlStartsTheCommandAsTheLeaderOfItsOwnSession() throws Exception {
        SessionProbe.assumePerl();
        var printed = run(OwnSession.viaPerl(SessionProbe.SH));
        assertTrue(SessionProbe.leadsItsGroup(printed), "still in the caller's process group: " + printed);
    }

    /** Arguments carry spaces, commas, equals signs, dashes and quotes: neither wrapper may reinterpret one. */
    @Test
    void everyArgumentIsPassedAsItWas() throws Exception {
        var args = List.of("--kernel-cmdline", "console=hvc0 root=/dev/vda", "--device", "x,y=$z", "-e", "a'b\"c", "");
        var cmd = new ArrayList<>(List.of("printf", "%s\\n"));
        cmd.addAll(args);

        SessionProbe.assumeSession();
        assertEquals(String.join("\n", args) + "\n", run(OwnSession.command(cmd)));
        SessionProbe.assumePerl();
        assertEquals(String.join("\n", args) + "\n", run(OwnSession.viaPerl(cmd)));
    }

    private static String run(List<String> cmd) throws Exception {
        var process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        var printed = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), printed);
        return printed;
    }

    /** Out of the terminal's process group, the Ctrl+C that ends isx no longer stops it: isx does. */
    @Test
    void whatItStartsIsStoppedWhenIsxExits() throws Exception {
        SessionProbe.assumeSession();
        var process = OwnSession.start(new ProcessBuilder("sh", "-c", "sleep 600 & wait"));
        try {
            ProcessHandle helper = null;
            for (int i = 0; i < 100 && helper == null; i++) {
                helper = process.descendants().findFirst().orElse(null);
                if (helper == null) Thread.sleep(50);
            }
            assertNotNull(helper, "the helper started");

            OwnSession.stopRunning();

            assertFalse(process.isAlive(), "the process survived");
            assertFalse(helper.isAlive(), "the process it started survived");
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
