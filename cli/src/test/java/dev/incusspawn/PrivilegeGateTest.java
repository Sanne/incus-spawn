package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every file that starts a command through sudo asks {@code Privilege} first, so
 * {@code isx init --no-sudo} cannot elevate by a path nobody remembered (#2074). A file that names
 * {@code "sudo"} either calls {@code Privilege} itself or is listed here with the runner that does.
 */
class PrivilegeGateTest {

    /** Files that name sudo but start nothing themselves: each hands its commands to a guarded runner. */
    private static final Map<String, String> THROUGH_A_GUARDED_RUNNER = Map.of(
            "HostSetup.java", "InitCommand's runHost, runHostQuiet, ran and captureOutput, and CowPoolBacking.runCapturingStderr",
            "DoctorCommand.java", "CowPoolBacking.runCapturingStderr",
            "Privilege.java", "the guard itself, which names the commands that elevate");

    @Test
    void everyFileThatRunsSudoAsksPrivilege() throws IOException {
        var offenders = new TreeSet<String>();
        var listed = new TreeSet<String>();
        for (var root : new String[] {"src/main/java", "../common/src/main/java", "../proxy/src/main/java"}) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    var text = Files.readString(file);
                    if (!text.contains("\"sudo\"")) continue;
                    var name = file.getFileName().toString();
                    if (THROUGH_A_GUARDED_RUNNER.containsKey(name)) {
                        listed.add(name);
                        continue;
                    }
                    if (!text.contains("Privilege.check(") && !text.contains("Privilege.forbidden()")) offenders.add(file.toString());
                }
            }
        }
        assertEquals(new TreeSet<String>(), offenders,
                "start a sudo command only after dev.incusspawn.util.Privilege.check, or list the file with the guarded runner it uses");
        for (var name : THROUGH_A_GUARDED_RUNNER.keySet()) {
            if (!listed.contains(name)) fail(name + " no longer names sudo: drop it from the list");
        }
    }
}
