package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@code scripts/local-incus.sh} against stub commands that record what it would do to the
 * host, up to its first Incus call (the stub {@code sg} fails, and {@code set -e} stops the script
 * there). Only the package step is exercised: everything after it needs a real Incus.
 *
 * <p>Pins #853: without {@code virtiofsd} Incus falls back to 9p for every VM disk device, which
 * cannot be hot-plugged, so VM builds with host resources or the DNF cache fail on a host the
 * script set up. The daemon only looks for it at startup, hence the restart.
 */
@EnabledOnOs(OS.LINUX)
class LocalIncusScriptTest {

    private static final Path SCRIPT = Path.of("../scripts/local-incus.sh").toAbsolutePath();

    /** Appends the stub's name and arguments to the call log. */
    private static final String RECORD = "echo \"$(basename \"$0\") $*\" >> \"$CALLS\"\n";
    /** {@code rpm -q P} / {@code dpkg -s P}: succeeds when P is listed in $INSTALLED. */
    private static final String IS_INSTALLED = "case \" $INSTALLED \" in *\" $2 \"*) exit 0;; *) exit 1;; esac\n";

    @TempDir
    Path bin;

    @Test
    void fedoraInstallsVirtiofsdAndRestartsIncus() throws Exception {
        var log = runOnFedora("incus btrfs-progs jq");
        assertTrue(log.contains("dnf install -y -q virtiofsd"), log);
        assertTrue(log.indexOf("systemctl try-restart incus.service") > log.indexOf("dnf install"),
                "Incus must be restarted after virtiofsd is installed, or it keeps using 9p:\n" + log);
    }

    @Test
    void noRestartWhenVirtiofsdIsAlreadyInstalled() throws Exception {
        var log = runOnFedora("incus btrfs-progs jq virtiofsd");
        assertFalse(log.contains("dnf install"), log);
        assertFalse(log.contains("restart"), log);
    }

    @Test
    void debianInstallsTheVirtiofsdPackageWhereItExists() throws Exception {
        var log = runOnDebian("incus btrfs-progs jq", true);
        assertTrue(log.contains("apt-get install -y -qq virtiofsd"), log);
        assertTrue(log.contains("systemctl try-restart incus.service"), log);
    }

    @Test
    void olderDebianGetsVirtiofsdFromQemu() throws Exception {
        var log = runOnDebian("incus btrfs-progs jq", false);
        assertTrue(log.contains("apt-get install -y -qq qemu-system-common"), log);
        assertTrue(log.contains("systemctl try-restart incus.service"), log);
    }

    @Test
    void olderDebianWithQemuAlreadyInstalledInstallsNothing() throws Exception {
        var log = runOnDebian("incus btrfs-progs jq qemu-system-common", false);
        assertFalse(log.contains("apt-get install"), log);
        assertFalse(log.contains("restart"), log);
    }

    private String runOnFedora(String installed) throws IOException, InterruptedException {
        stub("rpm", IS_INSTALLED);
        stub("dnf", RECORD);
        return run(installed);
    }

    /** @param aptHasPackage whether the stub {@code apt-cache} knows a {@code virtiofsd} package */
    private String runOnDebian(String installed, boolean aptHasPackage) throws IOException, InterruptedException {
        stub("dpkg", IS_INSTALLED);
        stub("apt-get", RECORD);
        // On a fresh host apt knows no package until its lists are updated.
        stub("apt-cache", aptHasPackage ? "grep -q '^apt-get update' \"$CALLS\"\n" : "exit 100\n");
        return run(installed);
    }

    /**
     * @param installed the packages the stub package database reports as installed
     * @return the recorded commands, one per line, then the script's output
     */
    private String run(String installed) throws IOException, InterruptedException {
        var log = bin.resolve("calls.log");
        Files.createFile(log);
        stub("uname", "echo Linux\n");
        stub("sudo", "[ \"$1\" = -n ] && shift\nexec \"$@\"\n");
        stub("isx", "exit 0\n");
        stub("isx-proxy", "exit 0\n");
        stub("getent", "echo \"incus-admin:x:999:$USER\"\n");
        stub("ip", "exit 0\n");
        stub("usermod", RECORD);
        stub("systemctl", RECORD);
        stub("sg", RECORD + "exit 1\n");
        // Only the stubs and these real tools are on PATH, so a host's own rpm or dnf never answers.
        for (var tool : List.of("awk", "grep", "seq", "basename", "true")) {
            var real = List.of("/usr/bin/" + tool, "/bin/" + tool).stream()
                    .map(Path::of).filter(Files::isExecutable).findFirst().orElseThrow();
            Files.createSymbolicLink(bin.resolve(tool), real);
        }

        var output = bin.resolve("output.log");
        var pb = new ProcessBuilder("/bin/bash", SCRIPT.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        pb.environment().putAll(Map.of(
                "PATH", bin.toString(), "CALLS", log.toString(), "INSTALLED", installed, "USER", "tester"));
        var process = pb.start();
        var finished = process.waitFor(30, TimeUnit.SECONDS);
        process.destroyForcibly();
        var calls = Files.readString(log) + "--- script output ---\n" + Files.readString(output);
        assertTrue(finished, "the script hung:\n" + calls);
        // Stopping anywhere earlier would make the absence assertions pass without testing anything.
        assertTrue(calls.contains("sg incus-admin"), "the script stopped before its first Incus call:\n" + calls);
        return calls;
    }

    private void stub(String name, String body) throws IOException {
        var file = bin.resolve(name);
        Files.writeString(file, "#!/bin/bash\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
