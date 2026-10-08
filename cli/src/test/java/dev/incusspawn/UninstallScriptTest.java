package dev.incusspawn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs {@code uninstall.sh} as on a stopped macOS install, in a scratch home, with no-op stubs for
 * the macOS tools it calls ({@code launchctl}, {@code tccutil}, {@code lsof}) and no Homebrew.
 *
 * <p>Pins #1155: the macOS VM's data disk is every instance, template and image, and the script
 * deleted it with the rest of the state directory, which is also the step a move from a source
 * build to Homebrew leads to. It now keeps the disk unless asked, and {@code --binaries-only}
 * removes only the binaries.
 */
class UninstallScriptTest {

    private static final Path SCRIPT = Path.of("../uninstall.sh").toAbsolutePath();

    @TempDir
    Path tmp;

    private Path home;
    private Path stubs;
    private Path state;
    private Path dataDisk;
    private Path bin;

    record Result(int exit, String output) {}

    @BeforeEach
    void stoppedMacOsInstall() throws IOException {
        // Glob characters in the home directory must not stop a path matching the data disk
        home = Files.createDirectories(tmp.resolve("user[1]"));
        stubs = Files.createDirectories(home.resolve("stubs"));
        stub("uname", "echo Darwin");
        for (var noop : List.of("launchctl", "tccutil", "lsof", "systemctl")) stub(noop, "exit 0");

        state = Files.createDirectories(home.resolve(".local/state/incus-spawn"));
        dataDisk = Files.writeString(state.resolve("data.img"), "the cow pool");
        Files.writeString(state.resolve("disk.img"), "root disk");
        Files.writeString(state.resolve("vm.log"), "boot log");
        Files.createDirectories(home.resolve(".local/share/incus-spawn/appliance"));
        Files.createDirectories(home.resolve(".config/incus-spawn"));
        bin = Files.createDirectories(home.resolve("bin"));
        for (var binary : List.of("isx", "isx-proxy", "git-remote-isx")) Files.writeString(bin.resolve(binary), "");
    }

    @Test
    void keepsTheDataDiskByDefault() throws Exception {
        var result = run("--yes");

        assertEquals(0, result.exit(), result.output());
        assertTrue(Files.exists(dataDisk), "the data disk holds every instance:\n" + result.output());
        assertFalse(Files.exists(state.resolve("disk.img")), result.output());
        assertFalse(Files.exists(state.resolve("vm.log")), result.output());
        assertFalse(Files.exists(home.resolve(".local/share/incus-spawn")), result.output());
        assertFalse(Files.exists(bin.resolve("isx")), result.output());
        assertTrue(result.output().contains("Kept:                  " + dataDisk), result.output());
        assertTrue(result.output().contains("the VM is not running"), result.output());
    }

    /** The isx being uninstalled: answers the listings, and runs {@code vmStop} for {@code vm stop}. */
    private void isx(String list, String vmStop) throws IOException {
        Files.writeString(bin.resolve("isx"), """
                #!/bin/sh
                case "$*" in
                  "vm stop") echo stopped > "$(dirname "$0")/vm-stop"; %s ;;
                  "list -q") %s ;;
                  "templates list --format=plain") printf 'tpl-a\\t-\\tbuiltin\\tA\\ttrue\\ntpl-b\\t-\\tbuiltin\\tB\\tfalse\\n' ;;
                  *) exit 1 ;;
                esac
                """.formatted(vmStop, list));
        Files.setPosixFilePermissions(bin.resolve("isx"), PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static final String THREE_INSTANCES = "printf 'dev-1\\ndev-2\\ndev-3\\n'";
    private static final String STOP_THE_VM = "kill \"$(cat \"$HOME/.local/state/incus-spawn/vm.pid\")\"";

    /** A process whose name says qemu stands in for the running VM. */
    private Process startVm() throws IOException {
        var qemu = stubs.resolve("qemu-system-aarch64");
        if (!Files.exists(qemu)) {
            // A link on macOS: newer releases kill a copy of a system binary on exec, and ps
            // names the process after the link all the same. A copy elsewhere, where sleep may
            // be one multi-call binary that goes by the name it is run under.
            if (System.getProperty("os.name").startsWith("Mac")) Files.createSymbolicLink(qemu, Path.of("/bin/sleep"));
            else Files.copy(Path.of("/bin/sleep"), qemu);
        }
        var vm = new ProcessBuilder(qemu.toString(), "60").start();
        Files.writeString(state.resolve("vm.pid"), Long.toString(vm.pid()));
        return vm;
    }

    @Test
    void aRunningVmSaysWhatTheDataDiskHoldsAndIsShutDownByIsx() throws Exception {
        // The count comes from the isx being uninstalled, before anything is stopped
        isx(THREE_INSTANCES, STOP_THE_VM);
        var vm = startVm();
        try {
            var result = run("--yes");

            assertEquals(0, result.exit(), result.output());
            assertTrue(result.output().contains("(3 instance(s) and 1 built template(s));"), result.output());
            assertTrue(Files.exists(dataDisk), result.output());
            // A disk that is kept is shut down by isx, never only signalled
            assertTrue(Files.exists(bin.resolve("vm-stop")), result.output());
            assertFalse(result.output().contains("did not shut down cleanly"), result.output());
        } finally {
            vm.destroyForcibly();
        }
    }

    @Test
    void aVmIsxCouldNotStopIsSignalledWithAWarning() throws Exception {
        // isx vm stop gives up on a guest that does not shut down: the disk may need recovery
        isx(THREE_INSTANCES, "exit 1");
        var vm = startVm();
        try {
            var result = run("--yes");

            assertEquals(0, result.exit(), result.output());
            assertFalse(vm.isAlive(), result.output());
            assertTrue(result.output().contains("Warning: the VM did not shut down cleanly"), result.output());
            assertTrue(result.output().contains("The VM was stopped with signals"), result.output());
            assertTrue(Files.exists(dataDisk), result.output());
        } finally {
            vm.destroyForcibly();
        }
    }

    @Test
    void theListingBoundDoesNotWaitForWhatIsxLeftRunning() throws Exception {
        // $(...) waits for every holder of its pipe: a child isx left behind outlasted the bound
        isx("(sleep 20) & exec sleep 20", STOP_THE_VM);
        var vm = startVm();
        try {
            long start = System.nanoTime();
            var result = run(Map.of("ISX_UNINSTALL_LIST_TIMEOUT", "1"), "--yes");
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

            assertEquals(0, result.exit(), result.output());
            assertTrue(seconds < 10, "took " + seconds + " s under a 1 s bound:\n" + result.output());
            assertTrue(result.output().contains("isx could not list them"), result.output());
        } finally {
            vm.destroyForcibly();
        }
    }

    @Test
    void deleteInstancesRemovesTheDataDisk() throws Exception {
        var result = run("--yes", "--delete-instances");

        assertEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(state), result.output());
        assertTrue(result.output().contains("EVERY instance, template and image"), result.output());
    }

    @Test
    void binariesOnlyKeepsEverythingElse() throws Exception {
        var result = run("--yes", "--binaries-only");

        assertEquals(0, result.exit(), result.output());
        for (var binary : List.of("isx", "isx-proxy", "git-remote-isx")) {
            assertFalse(Files.exists(bin.resolve(binary)), binary + "\n" + result.output());
        }
        assertTrue(Files.exists(dataDisk), result.output());
        assertTrue(Files.exists(state.resolve("vm.log")), result.output());
        assertTrue(Files.exists(home.resolve(".local/share/incus-spawn/appliance")), result.output());
        assertTrue(Files.exists(home.resolve(".config/incus-spawn")), result.output());
    }

    /** Launch agents as {@code isx proxy install} writes them, for the isx in {@code bin}. */
    private Path launchAgents(Path isxDir) throws IOException {
        var agents = Files.createDirectories(home.resolve("Library/LaunchAgents"));
        Files.writeString(agents.resolve("dev.incusspawn.proxy.plist"),
                "<array><string>" + isxDir.resolve("isx-proxy") + "</string></array>\n");
        Files.writeString(agents.resolve("dev.incusspawn.vm.plist"),
                "<array><string>" + isxDir.resolve("isx") + "</string><string>vm</string></array>\n");
        return agents;
    }

    /**
     * Another isx on PATH, as Homebrew's is. It records what it is asked and, as the real one,
     * writes both launch agents only when the service was stopped first; {@code install} runs
     * in place of that.
     */
    private Path anotherIsx(String install) throws IOException {
        stub("isx", """
                echo "$*" >> "$HOME/isx-asked"
                [ "$*" = "proxy install" ] || exit 0
                grep -qx "proxy stop" "$HOME/isx-asked" || exit 1
                %s
                """.formatted(install));
        return home.resolve("isx-asked");
    }

    private static final String WRITE_BOTH_AGENTS = """
            for agent in proxy vm; do
              echo "<string>$(dirname "$0")/isx</string>" > "$HOME/Library/LaunchAgents/dev.incusspawn.$agent.plist"
            done""";

    /**
     * Seen on a Mac: with the binaries gone both launch agents still named them. The proxy went
     * on running only as the old process, and nothing repaired the VM agent, so neither came
     * back after the next login. The isx that remains is asked to write them again.
     */
    @Test
    void binariesOnlyPointsTheServicesAtTheIsxThatRemains() throws Exception {
        var agents = launchAgents(bin);
        var asked = anotherIsx(WRITE_BOTH_AGENTS);

        var result = run("--yes", "--binaries-only");

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("isx now resolves to " + stubs.resolve("isx")), result.output());
        assertEquals(List.of("proxy stop", "proxy install"), Files.readAllLines(asked), result.output());
        for (var agent : List.of("proxy", "vm")) {
            var plist = Files.readString(agents.resolve("dev.incusspawn." + agent + ".plist"));
            assertFalse(plist.contains(bin.toString()), agent + " still names a removed binary:\n" + result.output());
        }
        assertFalse(result.output().contains("Warning"), result.output());
    }

    @Test
    void binariesOnlyWarnsWhenTheServicesCouldNotBeRepointed() throws Exception {
        launchAgents(bin);
        anotherIsx("exit 1");

        var result = run("--yes", "--binaries-only");

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains(
                "Warning: the services still start the removed binaries. Run: isx proxy stop && isx proxy install"),
                result.output());
    }

    @Test
    void binariesOnlyLeavesServicesOfAnotherInstallAlone() throws Exception {
        launchAgents(Path.of("/opt/homebrew/bin"));
        var asked = anotherIsx(WRITE_BOTH_AGENTS);

        var result = run("--yes", "--binaries-only");

        assertEquals(0, result.exit(), result.output());
        assertFalse(Files.exists(asked), result.output());
    }

    @Test
    void binariesOnlySaysWhatToRunWhenNoIsxRemains() throws Exception {
        launchAgents(bin);

        var result = run("--yes", "--binaries-only");

        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("Then run: isx proxy stop && isx proxy install"), result.output());
    }

    @Test
    void anUnknownOptionRemovesNothing() throws Exception {
        // A mistyped --binaries-only must not fall through to a full uninstall
        var result = run("--yes", "--binaries-onyl");

        assertEquals(2, result.exit(), result.output());
        assertTrue(Files.exists(bin.resolve("isx")), result.output());
        assertTrue(Files.exists(state.resolve("vm.log")), result.output());
    }

    @Test
    void binariesOnlyRefusesToDeleteData() throws Exception {
        var result = run("--yes", "--binaries-only", "--delete-instances");

        assertEquals(2, result.exit(), result.output());
        assertTrue(Files.exists(dataDisk), result.output());
        assertTrue(Files.exists(bin.resolve("isx")), result.output());
    }

    private void stub(String name, String body) throws IOException {
        var file = stubs.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Result run(String... args) throws IOException, InterruptedException {
        return run(Map.of(), args);
    }

    private Result run(Map<String, String> env, String... args) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("bash", SCRIPT.toString()));
        command.addAll(List.of(args));
        var pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("HOME", home.toString());
        pb.environment().put("INSTALL_DIR", bin.toString());
        pb.environment().put("PATH", stubs + ":/usr/bin:/bin");
        pb.environment().putAll(env);
        var process = pb.start();
        process.getOutputStream().close();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), output);
        return new Result(process.exitValue(), output);
    }
}
