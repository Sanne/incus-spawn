package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * No message tells the user to run an {@code incus storage|profile|move|image} command (#986):
 * isx runs the operation itself, through {@code IncusClient}, so a remediation works the same
 * over the Unix socket and over the macOS vsock tunnel, where the host has no {@code incus} CLI
 * at all (#939). The only exceptions are hints printed on Linux before isx manages anything,
 * listed here literal by literal: a new one should have to add a line to appear.
 */
class NoIncusCliHintTest {

    private static final List<Path> SOURCES = List.of(
            Path.of("src/main/java"), Path.of("../common/src/main/java"));

    private static final Pattern HINT =
            Pattern.compile("\"[^\"\\n]*\\bincus (storage|profile|move|image)\\b[^\"\\n]*\"");

    /** Linux-only hints, by file. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            // isx init's Linux setup, before any pool or profile is isx's to manage
            "InitCommand.java", Set.of(
                    "\"  Warning: Incus initialization may have failed. Check 'incus storage list'.\"",
                    "\"    incus profile device add default root disk path=/ pool=\"",
                    "\"    incus profile device add default eth0 nic network=incusbr0\"",
                    "\"  Resize with: sudo incus storage set cow size=200GiB\"",
                    "\"sudo incus storage create cow btrfs size=100GiB\""),
            // noCowPoolMsg's Linux arm
            "IncusClient.java", Set.of(
                    "\" Create one with: sudo incus storage create cow btrfs size=100GiB\""));

    @Test
    void noMessageNamesAnIncusCommandForTheUserToRun() throws IOException {
        var violations = new ArrayList<String>();
        for (var root : SOURCES) {
            try (Stream<Path> files = Files.walk(root)) {
                for (var source : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var file = source.getFileName().toString();
                    var matcher = HINT.matcher(Files.readString(source));
                    while (matcher.find()) {
                        if (!ALLOWED.getOrDefault(file, Set.of()).contains(matcher.group())) {
                            violations.add(file + ": " + matcher.group());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }
}
