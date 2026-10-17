package dev.incusspawn.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What tests of {@link OwnSession} look at: a session leader is also the leader of its process
 * group, which is what {@code ps} can show on both Linux and macOS.
 */
public final class SessionProbe {

    /** A shell that prints its pid and its process group, the same number when it leads a session. */
    public static final List<String> SH = List.of("sh", "-c", "echo $$ $(ps -o pgid= -p $$)");

    public record Seen(String processGroup, String command) {}

    private SessionProbe() {}

    public static Seen look(long pid) throws Exception {
        var ps = new ProcessBuilder("ps", "-o", "pgid=,comm=", "-p", String.valueOf(pid))
                .redirectErrorStream(true).start();
        var fields = new String(ps.getInputStream().readAllBytes()).strip().split("\\s+", 2);
        ps.waitFor();
        return new Seen(fields[0], fields.length > 1 ? fields[1] : "");
    }

    /** Whether what {@link #SH} printed shows a process group leader. */
    public static boolean leadsItsGroup(String printed) {
        var fields = printed.strip().split("\\s+");
        return fields.length == 2 && fields[0].equals(fields[1]);
    }

    /** Without setsid or perl the command is left as it is. */
    public static void assumeSession() throws Exception {
        assumeTrue(OwnSession.available(), "no setsid or /usr/bin/perl");
        if (OwnSession.command(List.of("true")).getFirst().endsWith("perl")) assumePerl();
    }

    /** Some Linux images package perl's POSIX module apart. */
    public static void assumePerl() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/perl")), "no /usr/bin/perl");
        assumeTrue(new ProcessBuilder("/usr/bin/perl", "-MPOSIX=setsid", "-e", "1")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0,
                "perl has no POSIX module");
    }
}
