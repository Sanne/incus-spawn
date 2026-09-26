package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static InstanceLifecycle.RuntimeConfig prefetched(String terminfo) {
        return new InstanceLifecycle.RuntimeConfig(null, true, null, null, null, terminfo);
    }

    private static int syntaxCheck(String script) throws Exception {
        var process = new ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    @Test
    void carriesKeysAndTerminfoInline() throws Exception {
        var keys = List.of("ssh-ed25519 AAAAC3Nza managed@isx", "ssh-rsa AAAAB3Nza it's me");
        var script = InstanceLifecycle.buildSetupScript(prefetched(TERMINFO), null, NetworkMode.FULL, keys);

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
        var script = InstanceLifecycle.buildSetupScript(prefetched(null), null, NetworkMode.FULL, List.of());

        assertEquals(0, syntaxCheck(script), script);
        assertFalse(script.contains("authorized_keys"));
        assertFalse(script.contains("tic "));
        assertTrue(script.startsWith("chown agentuser:agentuser /home/agentuser && {"));
    }

    @Test
    void pollsForTheNetworkEvery50ms() {
        var script = InstanceLifecycle.buildSetupScript(prefetched(null), null, NetworkMode.FULL, List.of());
        // 300 x 50 ms keeps the old 15 s ceiling; a 0.5 s interval was paid in full by every branch.
        assertTrue(script.contains("seq 1 300") && script.contains("sleep 0.05"), script);
    }

    @Test
    void airgapSkipsTheNetworkWait() {
        var script = InstanceLifecycle.buildSetupScript(prefetched(null), null, NetworkMode.AIRGAP, List.of());
        assertFalse(script.contains("eth0"));
    }
}
