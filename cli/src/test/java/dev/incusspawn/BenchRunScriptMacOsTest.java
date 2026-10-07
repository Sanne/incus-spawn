package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@code bench/run.sh} against stub commands on a host whose {@code uname} answers Darwin, up
 * to the point where it would need a running VM. The stub {@code ifconfig} shows no interface, so
 * the script stops there on any host, a real Mac with its VM up included.
 *
 * <p>Pins #1140: the script was Linux-only. On macOS it asked for Podman, whose containers run in a
 * VM there, so the load would have crossed Podman's user-mode network, and it asked the {@code incus}
 * client, which a Mac does not have, for the bridge address.
 */
class BenchRunScriptMacOsTest {

    private static final Path SCRIPT = Path.of("../bench/run.sh").toAbsolutePath();

    /** Appends the stub's name and arguments to the call log. */
    private static final String RECORD = "echo \"$(basename \"$0\") $*\" >> \"$CALLS\"\n";

    @TempDir
    Path bin;

    @TempDir
    Path work;

    @Test
    void refusesToPutTheLoadGeneratorInPodmansVm() throws Exception {
        var result = run();
        assertTrue(result.contains("On macOS pass --hyperfoil=DIR"), result);
        assertFalse(result.contains("podman run"), "no container may be started on macOS:\n" + result);
    }

    @Test
    void withHyperfoilOnTheHostItNeedsNeitherPodmanNorTheIncusClient() throws Exception {
        var hyperfoil = Files.createDirectories(work.resolve("hyperfoil/bin")).getParent();
        executable(hyperfoil.resolve("bin/standalone.sh"), "exit 0\n");

        var result = run("--hyperfoil=" + hyperfoil);

        // Stopping anywhere earlier would make the absence assertions pass without testing anything.
        assertTrue(result.contains("Could not determine the VM bridge address"),
                "the script should get as far as looking for the VM-facing bridge:\n" + result);
        var calls = result.substring(0, result.indexOf("--- script output ---"));
        assertFalse(calls.contains("podman"), "Podman must not be used with --hyperfoil:\n" + result);
        assertFalse(calls.contains("incus"), "macOS has no incus client to ask for the bridge:\n" + result);
    }

    /** @return the recorded commands, one per line, then the script's output */
    private String run(String... arguments) throws IOException, InterruptedException {
        var log = bin.resolve("calls.log");
        Files.createFile(log);
        stub("uname", "case \"$1\" in -sm) echo 'Darwin arm64';; *) echo Darwin;; esac\n");
        stub("native-image", "echo 'native-image 25.0.4 Oracle GraalVM'\n");
        stub("java", "exit 0\n");
        stub("ifconfig", "exit 0\n");
        stub("curl", "exit 7\n");
        stub("podman", RECORD);
        stub("incus", RECORD);
        // Only the stubs and these real tools are on PATH, so a host's own podman or incus never answers.
        for (var tool : List.of("awk", "grep", "sed", "head", "tr", "wc", "cat", "dirname", "basename",
                "rm", "mktemp", "ls", "git", "python3", "pkill")) {
            var real = List.of("/usr/bin/" + tool, "/bin/" + tool).stream()
                    .map(Path::of).filter(Files::isExecutable).findFirst().orElseThrow();
            Files.createSymbolicLink(bin.resolve(tool), real);
        }
        var config = Files.createDirectories(work.resolve("config/incus-spawn"));
        Files.writeString(config.resolve("config.yaml"), "");
        Files.writeString(config.resolve("ca.key"), "");

        var command = new ArrayList<>(List.of("/bin/bash", SCRIPT.toString(), "--skip-build"));
        command.addAll(List.of(arguments));
        var output = bin.resolve("output.log");
        var pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        pb.environment().clear();
        pb.environment().putAll(Map.of("PATH", bin.toString(), "CALLS", log.toString(),
                "HOME", work.toString(), "XDG_CONFIG_HOME", work.resolve("config").toString()));
        var process = pb.start();
        var finished = process.waitFor(30, TimeUnit.SECONDS);
        process.destroyForcibly();
        var result = Files.readString(log) + "--- script output ---\n" + Files.readString(output);
        assertTrue(finished, "the script hung:\n" + result);
        assertNotEquals(0, process.exitValue(), "the script should have stopped with an error:\n" + result);
        return result;
    }

    private void stub(String name, String body) throws IOException {
        executable(bin.resolve(name), body);
    }

    private static void executable(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/bash\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
