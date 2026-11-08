package dev.incusspawn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@code appliance/build.sh}'s in-container step up to unpacking the Alpine minirootfs, with
 * stub {@code curl} and {@code tar} that record their calls; the stub {@code tar} fails, so the
 * script never builds.
 *
 * <p>Pins #2045: the minirootfs is unpacked only once it matches the sha256 pinned for its arch,
 * and both build chroots take the branch's fixes with {@code apk upgrade} before installing.
 */
@EnabledOnOs(value = OS.LINUX, architectures = {"amd64", "x86_64", "aarch64"})
class ApplianceRootfsVerificationTest {

    private static final Path SCRIPT = Path.of("../appliance/build.sh").toAbsolutePath();
    private static final Path INCUSD_SCRIPT = Path.of("../appliance/incus/build-incusd.sh").toAbsolutePath();

    /** Appends the stub's name and arguments to the call log. */
    private static final String RECORD = "echo \"$(basename \"$0\") $*\" >> \"$CALLS\"\n";

    private static final Pattern VERSION = Pattern.compile("(?m)^ALPINE_VERSION=\"([0-9.]+)\"");
    private static final Pattern ARCH_SUM = Pattern.compile(
            "(?m)^\\s*(x86_64|aarch64)\\)\\s+ALPINE_SHA256=\"([0-9a-f]{64})\" ;;");

    @TempDir
    Path dir;

    private Path bin;
    private Path calls;

    @BeforeEach
    void setUp() throws IOException {
        bin = Files.createDirectory(dir.resolve("bin"));
        calls = Files.createFile(dir.resolve("calls.log"));
        stub("tar", RECORD + "exit 1\n");
        stub("umount", "exit 0\n");
    }

    @Test
    void bothArchesPinAChecksumForTheRelease() throws IOException {
        var source = Files.readString(SCRIPT);
        assertTrue(VERSION.matcher(source).find(), "ALPINE_VERSION is not pinned in " + SCRIPT);
        var arches = ARCH_SUM.matcher(source).results().map(m -> m.group(1)).sorted().toList();
        assertEquals(java.util.List.of("aarch64", "x86_64"), arches,
                "every appliance arch needs its minirootfs sha256 pinned");
    }

    @Test
    void downloadThatDoesNotMatchIsRejectedBeforeUnpacking() throws Exception {
        // Records the call, then writes a tampered body to the -o file.
        stub("curl", RECORD + """
                while [ $# -gt 0 ]; do [ "$1" = -o ] && { echo tampered > "$2"; exit 0; }; shift; done
                exit 1
                """);

        var out = run("x86_64");

        assertTrue(out.contains("curl ") && out.contains("https://dl-cdn.alpinelinux.org/"), out);
        assertFalse(out.contains("tar "), "an unverified minirootfs was unpacked:\n" + out);
        assertTrue(out.contains("checksum mismatch"), out);
    }

    @Test
    void archWithoutAPinnedChecksumIsRefused() throws Exception {
        stub("curl", RECORD + "exit 1\n");

        var out = run("riscv64");

        assertFalse(out.contains("curl "), "an arch with no pinned checksum must not download:\n" + out);
        assertTrue(out.contains("no Alpine") && out.contains("checksum pinned for riscv64"), out);
    }

    @Test
    void matchingDownloadIsUnpacked() throws Exception {
        stub("curl", RECORD + """
                while [ $# -gt 0 ]; do [ "$1" = -o ] && { echo minirootfs > "$2"; exit 0; }; shift; done
                exit 1
                """);
        // The real hash of the real tarball cannot be produced here: the stub records what it was
        // asked to check and reports a match, so this pins that the arch's pinned sum is the one checked.
        stub("sha256sum", RECORD + "cat >> \"$CALLS\"\nexit 0\n");
        var pinned = ARCH_SUM.matcher(Files.readString(SCRIPT)).results()
                .filter(m -> m.group(1).equals("aarch64")).map(m -> m.group(2)).findFirst().orElseThrow();

        var out = run("aarch64");

        assertTrue(out.contains(pinned + "  ") && out.contains(".minirootfs.tar.gz.part"),
                "the download must be checked against aarch64's pinned sum:\n" + out);
        assertFalse(out.contains("checksum mismatch"), out);
        assertTrue(out.contains("tar xzf ") && out.contains(".minirootfs.tar.gz -C "), out);
    }

    @Test
    void bothChrootsUpgradeBeforeInstalling() throws IOException {
        for (var script : new Path[] {SCRIPT, INCUSD_SCRIPT}) {
            // Commands only: a comment naming apk upgrade must not satisfy this.
            var source = Files.readAllLines(script).stream()
                    .filter(l -> !l.strip().startsWith("#")).collect(java.util.stream.Collectors.joining("\n"));
            var upgrade = source.indexOf("apk upgrade --no-cache");
            var add = source.indexOf("apk add --no-cache");
            assertTrue(upgrade >= 0 && upgrade < add,
                    script + " must run apk upgrade before apk add, or the minirootfs's packages keep "
                            + "the point release's versions");
        }
    }

    /** @return the recorded calls, then the script's output; the script must have failed */
    private String run(String arch) throws IOException, InterruptedException {
        var output = dir.resolve("output.log");
        var tmp = Files.createDirectories(dir.resolve("tmp"));
        var pb = new ProcessBuilder("/bin/bash", SCRIPT.toString(), dir.resolve("out").toString())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        pb.environment().putAll(Map.of(
                "PATH", bin + ":" + System.getenv("PATH"),
                "CALLS", calls.toString(),
                "TMPDIR", tmp.toString(),
                "ISX_INSIDE_CONTAINER", "1",
                "ISX_VERSION", "test",
                "ISX_ARCH", arch));
        var process = pb.start();
        var finished = process.waitFor(30, TimeUnit.SECONDS);
        process.destroyForcibly();
        var log = Files.readString(calls) + "--- script output ---\n" + Files.readString(output);
        assertTrue(finished, "the script hung:\n" + log);
        assertNotEquals(0, process.exitValue(), "the stub tar fails, so the script cannot succeed:\n" + log);
        return log;
    }

    private void stub(String name, String body) throws IOException {
        var file = bin.resolve(name);
        Files.writeString(file, "#!/bin/bash\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
