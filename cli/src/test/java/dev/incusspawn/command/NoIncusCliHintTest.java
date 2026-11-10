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
 * No message tells the user to run an
 * {@code incus storage|profile|move|image|network|remote|console|config|restart} command (#986, #1139):
 * isx runs the operation itself, through {@code IncusClient}, so a remediation works the same
 * over the Unix socket and over the macOS vsock tunnel, where the host has no {@code incus} CLI
 * at all (#939). The only exceptions are Linux-only hints, listed here literal by literal: a new
 * one should have to add a line to appear.
 */
class NoIncusCliHintTest {

    private static final List<Path> SOURCES = List.of(
            Path.of("src/main/java"), Path.of("../common/src/main/java"), Path.of("../proxy/src/main/java"));

    private static final Pattern HINT =
            Pattern.compile("\"[^\"\\n]*\\bincus (storage|profile|move|image|network|remote|console|config|restart)\\b[^\"\\n]*\"");

    /** Linux-only hints, by file. */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            // isx init's Linux setup, before any pool or profile is isx's to manage
            "HostSetup.java", Set.of(
                    "\"Check 'incus storage list'.\"",
                    "\"incus profile device add default root disk path=/ pool=\"",
                    "\"incus profile device add default eth0 nic network=incusbr0\"",
                    "\"Resize with: sudo incus storage set cow size=200GiB\"",
                    "\"incus network set incusbr0 ipv4.address 172.20.0.1/24\"",
                    // isx init --no-sudo's root script (#2074), which runs on the Linux host as root, beside the incus CLI
                    "\"incus network show \"",
                    "\" >/dev/null 2>&1 || incus network create \"",
                    "\"gw=$(incus network get \""),
            // the Linux arm of unknownRemoteMsg
            "IncusClient.java", Set.of(
                    "\"Add it with: incus remote add \""),
            // the Linux arm of restartAnywayHint
            "VmAgentRecovery.java", Set.of("\"To restart it anyway: incus restart --force \""),
            // gatewayUnavailableHint's Linux arm
            "ProxyConfig.java", Set.of("\"Is Incus running? Try 'incus network list'.\""),
            // --gateway-ip on Linux, where the proxy shares the host with the incus CLI
            "ProxyMain.java", Set.of(
                    "\"'incus network get incusbr0 ipv4.address' shows (10.166.11.1, not 10.166.11.1/24).\""));

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
