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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins #1255's role argument to {@code appliance/kernel/build-kernel.sh}: {@code vm} builds the VM
 * base image's kernel from the appliance fragments plus {@code vm.config} and
 * {@code vm-<arch>.config}, in that order, and the appliance build reads neither.
 *
 * <p>The script runs only up to its banner: a stub {@code curl} fails, so nothing is downloaded or
 * built. The fragments themselves are checked for what the VM image relies on with no boot loader
 * and no initrd.
 */
@EnabledOnOs(value = OS.LINUX, architectures = {"amd64", "x86_64", "aarch64"})
class KernelRoleTest {

    private static final Path KERNEL_DIR = Path.of("../appliance/kernel").toAbsolutePath();
    private static final Path SCRIPT = KERNEL_DIR.resolve("build-kernel.sh");
    private static final List<String> ARCHES = List.of("x86_64", "aarch64");

    @TempDir
    Path dir;

    private Path bin;

    @BeforeEach
    void setUp() throws IOException {
        bin = Files.createDirectory(dir.resolve("bin"));
        // Fails the download, so the script stops right after printing its configuration.
        stub("curl", "exit 22\n");
    }

    @Test
    void vmRoleAddsTheVmFragmentsAfterTheAppliancePair() throws Exception {
        for (var arch : ARCHES) {
            var out = run("vm", dir.resolve("out").toString(), arch);
            assertTrue(out.contains("Building the vm kernel"), out);
            assertTrue(out.contains("Config: isx.config + isx-" + arch + ".config + vm.config + vm-" + arch + ".config\n"),
                    "the vm role must layer its fragments over the appliance pair, arch fragment last:\n" + out);
        }
    }

    @Test
    void applianceRoleReadsNoVmFragment() throws Exception {
        for (var arch : ARCHES) {
            var out = run("appliance", dir.resolve("out").toString(), arch);
            assertTrue(out.contains("Building the appliance kernel"), out);
            assertTrue(out.contains("Config: isx.config + isx-" + arch + ".config\n"), out);
        }
    }

    @Test
    void vmKernelNeverDefaultsToTheApplianceBuildDirectory() throws Exception {
        // build.sh reuses a vmlinuz found in appliance/build, so the vm kernel must not land there.
        // A copy of the script, so the default output directory is created in the temp dir.
        var kernel = Files.createDirectories(dir.resolve("appliance/kernel"));
        try (var files = Files.list(KERNEL_DIR)) {
            for (var f : files.toList()) {
                Files.copy(f, kernel.resolve(f.getFileName()));
            }
        }
        var script = kernel.resolve("build-kernel.sh");

        var applianceBuild = outputOf(run(script, "appliance"));
        assertEquals(dir.resolve("appliance/build").toRealPath().toString(), applianceBuild,
                "the appliance default is unchanged");
        var vm = outputOf(run(script, "vm"));
        assertNotEquals(applianceBuild, vm);
        assertTrue(vm.startsWith(applianceBuild + "/"), "the vm kernel gets a directory of its own: " + vm);
    }

    private static String outputOf(String log) {
        var m = Pattern.compile("(?m)^  Output: (.*)$").matcher(log);
        assertTrue(m.find(), log);
        return m.group(1);
    }

    @Test
    void theTwoArgumentFormStillBuildsTheAppliance() throws Exception {
        // build.sh and older scripts call build-kernel.sh <output-dir> [arch].
        var out = run(dir.resolve("out").toString(), "x86_64");
        assertTrue(out.contains("Building the appliance kernel"), out);
        assertTrue(out.contains("Config: isx.config + isx-x86_64.config\n"), out);
        assertTrue(out.contains("Output: " + dir.resolve("out")), out);
    }

    @Test
    void vmFragmentsBootWithNoLoaderNoInitrdAndNoModules() throws IOException {
        var shared = enabled(Files.readString(KERNEL_DIR.resolve("vm.config")));
        assertFalse(shared.contains("CONFIG_MODULES=y"), "the VM image ships no /lib/modules");
        for (var option : List.of("CONFIG_EXT4_FS=y", "CONFIG_EFI=y", "CONFIG_EFI_PARTITION=y", "CONFIG_9P_FS=y")) {
            assertTrue(shared.contains(option), "vm.config must set " + option);
        }
        for (var arch : ARCHES) {
            var fragment = Files.readString(KERNEL_DIR.resolve("vm-" + arch + ".config"));
            var m = Pattern.compile("(?m)^CONFIG_CMDLINE=\"([^\"]*)\"").matcher(fragment);
            assertTrue(m.find(), "vm-" + arch + ".config must build in the command line: nothing else passes one");
            var cmdline = m.group(1);
            // The image build names the root partition; the kernel resolves it with no udev.
            assertTrue(cmdline.contains("root=PARTLABEL=isx-root "), cmdline);
            assertTrue(cmdline.contains("TERM=dumb"), cmdline);
            assertTrue(enabled(fragment).contains(arch.equals("x86_64") ? "CONFIG_CMDLINE_OVERRIDE=y" : "CONFIG_CMDLINE_FORCE=y"),
                    "vm-" + arch + ".config must make the built-in command line the only one");
        }
    }

    @Test
    void bothKernelsRunRootfulPodmanAndDockerOnABridge() throws IOException {
        // Rootful container runtimes in an instance (#2003). netavark's FORWARD chain accepts by
        // "ct mark", and nft refuses the whole ruleset without NF_CONNTRACK_MARK. Docker drops
        // outside traffic to a container in the raw table, which legacy iptables (Fedora's incus
        // package installs it) has only with IP_NF_RAW. Either gap fails every start on a bridge.
        // isx.config is read by both roles: the appliance kernel runs macOS's containers.
        var shared = enabled(Files.readString(KERNEL_DIR.resolve("isx.config")));
        for (var option : List.of("CONFIG_NETFILTER_ADVANCED=y", "CONFIG_NF_CONNTRACK=y", "CONFIG_NFT_CT=y",
                "CONFIG_NF_CONNTRACK_MARK=y", "CONFIG_IP_NF_RAW=y", "CONFIG_IP6_NF_RAW=y")) {
            assertTrue(shared.contains(option), "isx.config must set " + option);
        }
    }

    private static List<String> enabled(String fragment) {
        return fragment.lines().filter(l -> l.startsWith("CONFIG_")).toList();
    }

    /** @return the script's output; the stub curl fails, so the script must have failed */
    private String run(String... args) throws IOException, InterruptedException {
        return run(SCRIPT, args);
    }

    private String run(Path script, String... args) throws IOException, InterruptedException {
        var output = dir.resolve("output.log");
        var command = new ArrayList<>(List.of("/bin/bash", script.toString()));
        command.addAll(List.of(args));
        var pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("KERNEL_CACHE_DIR", Files.createDirectories(dir.resolve("cache")).toString());
        // A non-host arch is cross-compiled; a CROSS_COMPILE set here skips the toolchain check.
        pb.environment().put("CROSS_COMPILE", "stub-");
        var process = pb.start();
        var finished = process.waitFor(30, TimeUnit.SECONDS);
        process.destroyForcibly();
        var log = Files.readString(output);
        assertTrue(finished, "the script hung:\n" + log);
        assertNotEquals(0, process.exitValue(), "the stub curl fails, so the script cannot succeed:\n" + log);
        return log;
    }

    private void stub(String name, String body) throws IOException {
        var file = bin.resolve(name);
        Files.writeString(file, "#!/bin/bash\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
