package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The hosted runners' resolver has failed every ubuntu.com name for hours at a time (#2146), and
 * host-distro's Ubuntu guest resolves through the runner (QEMU's user-mode network), so its apt
 * failed every index and {@code isx init} found no incus package (#2148).
 * {@code scripts/distro-vm-apt-fallback.sh}, which {@code distro-vm.sh boot} runs in the guest,
 * moves each ubuntu.com host that does not resolve to a mirror outside ubuntu.com.
 * The fix is the CI harness's alone: isx init's own apt is left as a user's host has it.
 */
class DistroVmAptFallbackTest {

    private static final Path SCRIPT = Path.of("../scripts/distro-vm-apt-fallback.sh").toAbsolutePath();
    private static final Path DISTRO_VM = Path.of("../scripts/distro-vm.sh");

    /** What noble's cloud image has once cloud-init wrote its sources. */
    private static final String NOBLE_SOURCES = """
            Types: deb
            URIs: http://archive.ubuntu.com/ubuntu/
            Suites: noble noble-updates noble-backports
            Components: main universe restricted multiverse
            Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

            Types: deb
            URIs: http://security.ubuntu.com/ubuntu/
            Suites: noble-security
            Components: main universe restricted multiverse
            Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
            """;

    @TempDir
    Path dir;

    @Test
    void unresolvedUbuntuHostsMoveToTheFallbackMirror() throws Exception {
        var sources = sources(NOBLE_SOURCES);
        var result = run("mirrors.edge.kernel.org");
        assertEquals(0, result.exitCode, result.output);
        var written = Files.readString(sources);
        assertEquals(NOBLE_SOURCES
                .replace("http://archive.ubuntu.com/ubuntu/", "https://mirrors.edge.kernel.org/ubuntu/")
                .replace("http://security.ubuntu.com/ubuntu/", "https://mirrors.edge.kernel.org/ubuntu/"), written);
        assertTrue(result.output.contains("archive.ubuntu.com"), result.output);
        assertTrue(result.output.contains("security.ubuntu.com"), result.output);
    }

    @Test
    void onlyTheHostThatDoesNotResolveMoves() throws Exception {
        var sources = sources(NOBLE_SOURCES);
        var result = run("mirrors.edge.kernel.org security.ubuntu.com");
        assertEquals(0, result.exitCode, result.output);
        var written = Files.readString(sources);
        assertTrue(written.contains("URIs: https://mirrors.edge.kernel.org/ubuntu/\nSuites: noble noble-updates"), written);
        assertTrue(written.contains("URIs: http://security.ubuntu.com/ubuntu/\n"), written);
    }

    @Test
    void resolvingHostsAreLeftAlone() throws Exception {
        var sources = sources(NOBLE_SOURCES);
        var result = run("mirrors.edge.kernel.org archive.ubuntu.com security.ubuntu.com");
        assertEquals(0, result.exitCode, result.output);
        assertEquals(NOBLE_SOURCES, Files.readString(sources));
        assertEquals("", result.output.strip());
    }

    @Test
    void aFallbackThatDoesNotResolveEitherChangesNothing() throws Exception {
        var sources = sources(NOBLE_SOURCES);
        var result = run("");
        assertEquals(0, result.exitCode, result.output);
        assertEquals(NOBLE_SOURCES, Files.readString(sources));
        assertTrue(result.output.contains("nor for mirrors.edge.kernel.org"), result.output);
    }

    @Test
    void anEmptyFallbackTurnsItOff() throws Exception {
        var sources = sources(NOBLE_SOURCES);
        var result = run("mirrors.edge.kernel.org", "");
        assertEquals(0, result.exitCode, result.output);
        assertEquals(NOBLE_SOURCES, Files.readString(sources));
    }

    @Test
    void portsAndOtherArchivesAreNeverMoved() throws Exception {
        // The fallback mirrors amd64's archive only, and a third-party source is not Ubuntu's
        var other = """
                Types: deb
                URIs: http://ports.ubuntu.com/ubuntu-ports/
                Suites: noble
                Components: main

                Types: deb
                URIs: https://pkgs.zabbly.com/incus/stable
                Suites: noble
                Components: main
                """;
        var sources = sources(other);
        var result = run("mirrors.edge.kernel.org");
        assertEquals(0, result.exitCode, result.output);
        assertEquals(other, Files.readString(sources));
    }

    @Test
    void aOneLineSourcesListIsMovedToo() throws Exception {
        Files.createDirectories(dir.resolve("apt/sources.list.d"));
        var list = dir.resolve("apt/sources.list");
        Files.writeString(list, "deb http://archive.ubuntu.com/ubuntu noble main universe\n"
                + "# deb http://archive.ubuntu.com/ubuntu-ports noble main\n");
        var result = run("mirrors.edge.kernel.org");
        assertEquals(0, result.exitCode, result.output);
        assertEquals("deb https://mirrors.edge.kernel.org/ubuntu noble main universe\n"
                + "# deb http://archive.ubuntu.com/ubuntu-ports noble main\n", Files.readString(list));
    }

    @Test
    void bootRunsItInTheGuestOnceItsTrustIsInPlace() throws IOException {
        var script = Files.readString(DISTRO_VM);
        var boot = script.substring(script.indexOf("\nboot() {"), script.indexOf("\ntrust_ca() {"));
        int trust = boot.indexOf("trust_ca \"$dir\"");
        int fallback = boot.indexOf("apt_fallback \"$dir\"");
        assertTrue(trust > 0 && fallback > trust,
                "distro-vm.sh boot must run the apt fallback after trusting DISTRO_VM_TRUST:\n" + boot);
        assertTrue(script.contains("distro-vm-apt-fallback.sh"), script);
    }

    private Path sources(String content) throws IOException {
        var d = Files.createDirectories(dir.resolve("apt/sources.list.d"));
        var file = d.resolve("ubuntu.sources");
        Files.writeString(file, content);
        return file;
    }

    /** Runs the script with a getent that resolves only the given hosts. */
    private Result run(String resolving) throws Exception {
        return run(resolving, null);
    }

    private Result run(String resolving, String fallback) throws Exception {
        // macOS has no timeout(1) unless coreutils is installed
        for (var tool : java.util.List.of("bash", "timeout", "paste", "mktemp"))
            assumeTrue(onPath(tool), tool + " is not installed");
        var bin = Files.createDirectories(dir.resolve("bin"));
        var getent = bin.resolve("getent");
        Files.writeString(getent, """
                #!/bin/bash
                for h in %s; do [ "$2" = "$h" ] && exit 0; done
                exit 2
                """.formatted(resolving));
        Files.setPosixFilePermissions(getent, PosixFilePermissions.fromString("rwxr-xr-x"));
        var builder = new ProcessBuilder("bash", "--norc", "--noprofile", SCRIPT.toString()).redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("APT_DIR", dir.resolve("apt").toString());
        builder.environment().remove("APT_FALLBACK_MIRROR");
        if (fallback != null) builder.environment().put("APT_FALLBACK_MIRROR", fallback);
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the script hung:\n" + output);
        return new Result(process.exitValue(), output);
    }

    private static boolean onPath(String tool) {
        for (var d : System.getenv("PATH").split(java.io.File.pathSeparator))
            if (Files.isExecutable(Path.of(d, tool))) return true;
        return false;
    }

    private record Result(int exitCode, String output) {}
}
