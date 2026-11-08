package dev.incusspawn.lifecycle;

import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    private static final String KEY = "ssh-ed25519 AAAAC3Nza managed@isx";

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
    void aBranchWritesTheKeysAsAgentuserNeverAsRoot() {
        // agentuser owns ~/.ssh, and a branch of an instance copies whatever is there: a symlink
        // in it must not steer a root write in the copy (#1698)
        var script = RuntimeSetup.buildSetupScript(prefetched(null), null, NetworkMode.FULL, List.of(KEY), true);
        var asAgentuser = "runuser -u agentuser -- env -i HOME=/home/agentuser PATH=/usr/bin:/bin sh -c '"
                + RuntimeSetup.WRITE_KEYS + "'";
        assertTrue(script.contains(asAgentuser), script);
        assertFalse(RuntimeSetup.WRITE_KEYS.contains("'"), "it is quoted whole");
        assertFalse(script.replace(asAgentuser, "").contains(".ssh"), "root never touches ~/.ssh: " + script);
        assertTrue(script.indexOf("chown agentuser:agentuser /home/agentuser") < script.indexOf(asAgentuser),
                "the home is agentuser's before agentuser writes in it");
    }

    @Test
    void theKeysStepStaysValidShellBeforeEveryNextStep() throws Exception {
        // An airgap branch's next step is a tool's readiness wait, which starts with "; "
        var withReadyCheck = "{\"tools\":{\"sshd\":{\"ready\":\"systemctl is-active sshd\"}}}";
        for (var mode : NetworkMode.values()) {
            for (var buildSource : new String[] {null, withReadyCheck}) {
                var script = RuntimeSetup.buildSetupScript(prefetched(null), buildSource, mode, List.of(KEY), true);
                assertEquals(0, syntaxCheck(script), script);
                if (buildSource != null) assertTrue(script.contains("systemctl is-active sshd"), script);
            }
        }
    }

    @Test
    void aBranchsKeysReplaceWhatTheSourceHeld(@TempDir Path tmp) throws Exception {
        // A branch's file holds exactly its keys, as the root write did: mode 0600 in a 0700 ~/.ssh
        var home = tmp.resolve("a home");
        var file = home.resolve(".ssh/authorized_keys");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ssh-ed25519 AAAAsourceKey source@box\n");

        var sh = new ProcessBuilder("sh", "-c", RuntimeSetup.WRITE_KEYS, "isx-keys", home.toString())
                .redirectErrorStream(true).start();
        try (var in = sh.getOutputStream()) {
            in.write((KEY + "\n").getBytes());
        }
        assertTrue(sh.waitFor(10, TimeUnit.SECONDS));
        assertEquals("", new String(sh.getInputStream().readAllBytes()));

        assertEquals(List.of(KEY), Files.readAllLines(file));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
    }

    @Test
    void omitsWhatIsNotNeeded() throws Exception {
        var script = RuntimeSetup.buildSetupScript(prefetched(null), null, NetworkMode.FULL, List.of());

        assertEquals(0, syntaxCheck(script), script);
        assertFalse(script.contains("authorized_keys"));
        assertFalse(script.contains("tic "));
        assertTrue(script.startsWith(InstanceNetwork.APPLY_STATIC_ADDRESS
                + "\nchown agentuser:agentuser /home/agentuser || true\n{"), script);
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

    /**
     * The static address is the pushed {@code .network} file's, applied at once rather than waited
     * for (#801): networkd is ordered after udev and comes up ~200 ms into boot, while exec answers
     * at ~80 ms, and every branch paid the difference. The stub's address appears only once the
     * script itself has set it, with the flags networkd adopts in place.
     */
    @Test
    void appliesTheStaticNetworkFileBeforeWaiting(@TempDir Path stubs) throws Exception {
        var daemon = new FakeIncusDaemon().container("c", Map.of(Metadata.STATIC_IP, "10.166.11.7"));
        var prefetched = RuntimeSetup.prefetchRuntimeConfig(daemon.client(), "c");
        var file = networkFile(stubs, "Name=eth0", "Address=10.166.11.7/24");
        var script = RuntimeSetup.buildSetupScript(prefetched, null, NetworkMode.FULL, List.of())
                .replace(InstanceNetwork.NETWORK_FILE, file.toString());
        stub(stubs, "chown", "exit 0");
        stub(stubs, "seq", "echo 1");
        stub(stubs, "sleep", "exit 0");
        stubIp(stubs);

        assertEquals(0, run(script, stubs), script);
        assertEquals(List.of("addr replace 10.166.11.7/24 brd + dev eth0", "link set eth0 up",
                "route replace default via 10.166.11.1 dev eth0 proto static"), ipCalls(stubs));
    }

    @Test
    void appliesOnlyAFileForEth0WithOneAddress(@TempDir Path stubs) throws Exception {
        stubIp(stubs);
        var apply = InstanceNetwork.APPLY_STATIC_ADDRESS;
        // A VM's file matches its NIC by MAC (#997): its address comes from the agent's push
        var vm = networkFile(stubs, "PermanentMACAddress=10:66:6f:00:00:01", "Address=10.166.11.7/24");
        assertEquals(0, run(apply.replace(InstanceNetwork.NETWORK_FILE, vm.toString()), stubs));
        // A hand-edited file networkd may read otherwise is left to networkd
        var twice = networkFile(stubs, "Name=eth0", "Address=10.166.11.7/24\nAddress=10.166.11.8/24");
        assertEquals(0, run(apply.replace(InstanceNetwork.NETWORK_FILE, twice.toString()), stubs));
        assertEquals(0, run(apply.replace(InstanceNetwork.NETWORK_FILE, stubs.resolve("none").toString()), stubs));
        assertEquals(List.of(), ipCalls(stubs));

        // No eth0, as in an airgapped instance whose NIC is masked: still not a failure
        stub(stubs, "ip", "exit 1");
        var eth0 = networkFile(stubs, "Name=eth0", "Address=10.166.11.7/24");
        assertEquals(0, run(apply.replace(InstanceNetwork.NETWORK_FILE, eth0.toString()), stubs));
    }

    /** A {@code .network} file as {@code InstanceNetwork} pushes it, with {@code match} and {@code address}. */
    private static Path networkFile(Path dir, String match, String address) throws Exception {
        var file = Files.createTempFile(dir, "eth0", ".network");
        Files.writeString(file, "[Match]\n" + match + "\n\n[Network]\n" + address
                + "\nGateway=10.166.11.1\nDNS=10.166.11.1\n");
        return file;
    }

    /** An {@code ip} that logs each change and shows the address only once it was set. */
    private static void stubIp(Path stubs) throws Exception {
        stub(stubs, "ip", """
                case "$1" in
                  -4) [ -f "$0.addr" ] && echo '2: eth0    inet 10.166.11.7/24 brd 10.166.11.255 scope global eth0'; exit 0;;
                esac
                echo "$*" >> "$0.calls"
                case "$*" in "addr replace "*) touch "$0.addr";; esac""");
    }

    private static List<String> ipCalls(Path stubs) throws Exception {
        var calls = stubs.resolve("ip.calls");
        return Files.exists(calls) ? Files.readAllLines(calls) : List.of();
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
