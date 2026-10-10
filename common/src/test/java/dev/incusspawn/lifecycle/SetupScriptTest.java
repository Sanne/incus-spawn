package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The post-start setup script now carries the SSH keys and terminfo that used to be pushed
 * into the stopped instance, so it must stay valid shell whatever that content holds.
 */
class SetupScriptTest {

    private static final String TERMINFO = """
            xterm-kitty|KovIdTTY,
            \tam, ccc, hs, km, mc5i, mir, msgr, npc, xenl,
            \tbel=^G, cr=\\r, sgr0=\\E(B\\E[m, setaf=\\E[38;5;%p1%dm,
            INCUS_EOF
            \tuse=$HOME,""";

    private static RuntimeSetup.RuntimeConfig prefetched(String terminfo) {
        return new RuntimeSetup.RuntimeConfig(null, true, null, null, null, terminfo, "10.0.0.2", null, null);
    }

    private static int syntaxCheck(String script) throws Exception {
        var process = new ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    @Test
    void carriesKeysAndTerminfoInline() throws Exception {
        var keys = List.of("ssh-ed25519 AAAAC3Nza managed@isx", "ssh-rsa AAAAB3Nza it's me");
        var script = RuntimeSetup.buildSetupScript(prefetched(TERMINFO), null, NetworkMode.FULL, keys);

        assertEquals(0, syntaxCheck(script), script);
        assertTrue(script.contains(keys.get(0) + "\n" + keys.get(1) + "\n"));
        assertTrue(script.contains(TERMINFO + "\n"), "terminfo must arrive byte-for-byte");
        assertTrue(script.contains("tic -x -"));
        // A terminfo line equal to the default delimiter must not end the heredoc early.
        assertTrue(script.contains("<< 'INCUS_EOF_'"));
        assertFalse(script.contains("/tmp/.isx-terminfo.src"), "nothing is pushed any more");
    }

    @Test
    void omitsWhatIsNotNeeded() throws Exception {
        var script = RuntimeSetup.buildSetupScript(prefetched(null), null, NetworkMode.FULL, List.of());

        assertEquals(0, syntaxCheck(script), script);
        assertFalse(script.contains("authorized_keys"));
        assertFalse(script.contains("tic "));
        assertTrue(script.startsWith("chown agentuser:agentuser /home/agentuser || true\n{"));
    }

    @Test
    void pollsForTheNetworkEvery50ms() {
        var script = RuntimeSetup.buildSetupScript(prefetched(null), null, NetworkMode.FULL, List.of());
        // 300 x 50 ms keeps the old 15 s ceiling; a 0.5 s interval was paid in full by every branch.
        assertTrue(script.contains("seq 1 300") && script.contains("sleep 0.05"), script);
    }

    @Test
    void onlyTheReadinessChecksDecideSuccess(@TempDir Path stubs) throws Exception {
        // pollUntilReady retries until the script succeeds, so a best-effort step that fails
        // (here the home chown) must not keep a ready instance waiting for the full timeout.
        stub(stubs, "chown", "exit 1");
        stub(stubs, "systemctl", "exit 0");
        stub(stubs, "ip", "echo '2: eth0    inet 10.0.0.2/24'");
        var script = RuntimeSetup.buildSetupScript(prefetched(null), null, NetworkMode.FULL, List.of());
        assertEquals(0, run(script, stubs), script);

        // ...while a readiness check that fails still fails it.
        stub(stubs, "ip", "exit 0");
        stub(stubs, "seq", "echo 1");
        assertEquals(1, run(script, stubs), script);
    }

    /**
     * A VM guest keeps its kernel's predictable NIC name, so there is no {@code eth0} to wait on
     * (#997): the wait must find the instance's address wherever it is, or every VM branch
     * spends both setup runs (~35 s) on it and then warns that setup may not be complete.
     */
    @Test
    void findsTheAddressOnAVmNicThatIsNotEth0(@TempDir Path stubs) throws Exception {
        var daemon = new FakeIncusDaemon().instance("vm", "virtual-machine", "Stopped",
                Map.of(Metadata.STATIC_IP, "10.166.11.7"));
        var prefetched = RuntimeSetup.prefetchRuntimeConfig(daemon.client(), "vm");
        var script = RuntimeSetup.buildSetupScript(prefetched, null, NetworkMode.FULL, List.of());
        stub(stubs, "chown", "exit 0");
        stub(stubs, "systemctl", "exit 0");
        stub(stubs, "seq", "echo 1");
        stub(stubs, "sleep", "exit 0");
        stub(stubs, "ip", """
                case "$*" in *eth0*) echo 'Device "eth0" does not exist.' >&2; exit 1;; esac
                echo '1: lo    inet 127.0.0.1/8 scope host lo'
                echo '2: enp5s0    inet 10.166.11.7/24 brd 10.166.11.255 scope global dynamic enp5s0'""");
        assertEquals(0, run(script, stubs), script);

        // Another interface's address is not the instance's: a docker0 up first is no answer.
        stub(stubs, "ip", "echo '3: docker0    inet 172.17.0.1/16 scope global docker0'");
        assertEquals(1, run(script, stubs), script);
    }

    @Test
    void onlyAnAddressReachesTheShell() {
        assertEquals("ip -4 -o addr show | grep -qF ' inet 10.166.11.7/'",
                RuntimeSetup.addressUpCheck("10.166.11.7"));
        var fallback = "ip -4 route show default | grep -q .";
        assertEquals(fallback, RuntimeSetup.addressUpCheck(""));
        assertEquals(fallback, RuntimeSetup.addressUpCheck(null));
        assertEquals(fallback, RuntimeSetup.addressUpCheck("10.0.0.2'; reboot; '"));
    }

    private static void stub(Path dir, String command, String body) throws Exception {
        var file = dir.resolve(command);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        assertTrue(file.toFile().setExecutable(true));
    }

    private static int run(String script, Path stubs) throws Exception {
        var builder = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        builder.environment().put("PATH", stubs + ":" + builder.environment().get("PATH"));
        var process = builder.start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    @Test
    void airgapSkipsTheNetworkWait() {
        var script = RuntimeSetup.buildSetupScript(prefetched(null), null, NetworkMode.AIRGAP, List.of());
        assertFalse(script.contains("ip -4"), script);
    }
}
