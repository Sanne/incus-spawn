package dev.incusspawn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraalVM's JIT sometimes computes a wrong AES-256 key schedule in AESCrypt.makeSessionKey while
 * warming up (#940, #1018, <a href="https://github.com/oracle/graal/issues/14599">oracle/graal#14599</a>),
 * and the TLS connection using it fails with bad_record_mac: an occasional 502 from a JVM-mode
 * isx-proxy. The root pom keeps the method out of the test JVM's JIT; the JVM launchers need the
 * same options, and nothing else ties the three places together.
 */
class LauncherJitWorkaroundTest {

    private static final Path ROOT_POM = Path.of("../pom.xml");
    private static final Path INSTALL_SCRIPT = Path.of("../install.sh");
    private static final Path CATALOG = Path.of("../jbang-catalog.json");

    private static final Pattern ARG_LINE = Pattern.compile("<argLine>([^<]+)</argLine>");
    private static final Pattern SHELL_FUNCTION = Pattern.compile("^\\w+\\(\\) \\{\\n.*?^\\}\\n",
            Pattern.MULTILINE | Pattern.DOTALL);

    private static final String GRAAL_JIT_FLAG =
            "     bool UseJVMCICompiler                         = true                                {JVMCI product} {default}";

    /** What the launcher runs after its JVM options: the jar, then its own arguments, unsplit. */
    private static final List<String> LAUNCHER_ARGS = List.of("-jar", "/opt/app.jar", "status", "two words");

    @TempDir
    Path dir;

    @Test
    void jbangAliasesCarryTheWorkaround() throws IOException {
        // A catalog cannot depend on the JDK it ends up on, so the options are unconditional:
        // HotSpot accepts them with or without JVMCI (checked on Temurin, OpenJDK and GraalVM 25).
        var aliases = new ObjectMapper().readTree(Files.readString(CATALOG)).get("aliases");
        var expected = workaroundOptions();
        aliases.fieldNames().forEachRemaining(alias -> {
            var options = new ArrayList<String>();
            aliases.get(alias).path("java-options").forEach(o -> options.add(o.asText()));
            assertEquals(expected, options, "alias '" + alias + "' must run with the JIT workaround");
        });
    }

    @Test
    void installedLauncherOnTheGraalJitCarriesTheWorkaround() throws Exception {
        var expected = new ArrayList<>(workaroundOptions());
        expected.addAll(LAUNCHER_ARGS);
        assertEquals(expected, runInstalledLauncher(GRAAL_JIT_FLAG));
    }

    @Test
    void installedLauncherOnC2LeavesTheJitAlone() throws Exception {
        // A stock JDK has no JVMCI flags at all; GraalVM can also be told to run C2
        assertEquals(LAUNCHER_ARGS, runInstalledLauncher(""));
        assertEquals(LAUNCHER_ARGS, runInstalledLauncher(GRAAL_JIT_FLAG.replace("= true ", "= false")));
    }

    private static List<String> workaroundOptions() throws IOException {
        var matcher = ARG_LINE.matcher(Files.readString(ROOT_POM));
        assertTrue(matcher.find(), "the root pom must keep the JIT workaround in its argLine");
        // Only the workaround: the argLine may also carry flags that are for tests alone
        var options = Stream.of(matcher.group(1).trim().split("\\s+"))
                .filter(o -> o.startsWith("-XX:CompileCommand="))
                .toList();
        assertTrue(options.contains("-XX:CompileCommand=exclude,com.sun.crypto.provider.AESCrypt::makeSessionKey"),
                "the root pom's argLine must exclude AESCrypt.makeSessionKey, it has " + options);
        return options;
    }

    /**
     * Generates the launcher with install.sh's own functions, against a fake java that prints the
     * given PrintFlagsFinal line and otherwise echoes its arguments, one per line, then runs it.
     */
    private List<String> runInstalledLauncher(String printFlagsFinal) throws Exception {
        var java = dir.resolve("java");
        Files.writeString(java, """
                #!/bin/bash
                if [ "$1" = -XX:+PrintFlagsFinal ]; then
                    echo '%s'
                    echo 'openjdk version "25.0.4.1"' >&2
                    exit 0
                fi
                printf '%%s\\n' "$@"
                """.formatted(printFlagsFinal));
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));

        var functions = new StringBuilder();
        var matcher = SHELL_FUNCTION.matcher(Files.readString(INSTALL_SCRIPT));
        while (matcher.find()) {
            functions.append(matcher.group());
        }
        var launcher = dir.resolve("isx");
        var script = functions + "set -e\nJAVA_BIN=\"$1\"\ninstall_wrapper \"$2\" " + LAUNCHER_ARGS.get(1) + "\n";
        assertEquals(0, run("bash", "-c", script, "install", java.toString(), launcher.toString()).exitCode);

        var result = run(Stream.concat(Stream.of(launcher.toString()), LAUNCHER_ARGS.stream().skip(2)).toArray(String[]::new));
        assertEquals(0, result.exitCode);
        return result.output.lines().toList();
    }

    private record Result(int exitCode, String output) {}

    private static Result run(String... command) throws Exception {
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(process.waitFor(), output);
    }
}
