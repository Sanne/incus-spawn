package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.Environment;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.ssh.HostKeys;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.ssh.TestHostKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * An SSH-capable instance started by {@code isx shell} or {@code isx run} gets the isx key and its
 * {@code Host} entry, as a branch that starts at once does (#1640): a {@code --no-start} branch is
 * first started there, and was left unreachable over SSH.
 */
@ExtendWith(TempHome.class)
class SshOnStartTest {

    private static final String NAME = "t-ssh";
    private static final String KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIManagedKeyForTest incus-spawn managed key";

    static void givenManagedKey() throws Exception {
        Files.createDirectories(Environment.sshDir());
        Files.writeString(Environment.sshKeyFile(), "private\n");
        Files.writeString(Environment.sshPubKeyFile(), KEY + "\n");
    }

    /** Start {@code config}'s instance for use, and return the script its readiness probe ran. */
    private static String startScript(Map<String, String> config) {
        return startScript(config, "");
    }

    /** {@link #startScript(Map)}, with the guest's script printing {@code answer} before its {@code ready}. */
    private static String startScript(Map<String, String> config, String answer) {
        var daemon = new FakeIncusDaemon().container(NAME, config).ipFiltering(NAME, "true");
        var incus = spy(daemon.client());
        var script = ArgumentCaptor.forClass(String.class);
        doReturn(answer).when(incus).waitForReady(eq(NAME), eq(MachineType.CONTAINER), script.capture(), anyMap());
        InstanceLifecycle.startForUse(incus, NAME, MachineType.CONTAINER, RecordingOutput.QUIET);
        return script.getValue();
    }

    private static final HostKeys.HostKey HOST_KEY = TestHostKeys.key("ssh-ed25519", 7);

    @Test
    void aStartPinsTheHostKeyItsProbeReported() throws Exception {
        // A client that forces strict checking, such as herdr, finds it by the name (#1863)
        givenManagedKey();

        var script = startScript(sshCapable(), TestHostKeys.printed(HOST_KEY));

        assertTrue(script.contains(HostKeys.GUEST_SCRIPT), script);
        assertEquals(List.of(NAME + ",10.166.11.42 ssh-ed25519 " + HOST_KEY.blob() + " isx:" + NAME),
                Files.readAllLines(Environment.sshKnownHostsFile()));
        assertTrue(Files.readString(Environment.sshConfigFile()).contains("HostKeyAlias " + NAME + "\n"));
    }

    @Test
    void aStartWhoseProbeReportedNoKeyStillWritesTheBlock() throws Exception {
        givenManagedKey();

        startScript(sshCapable(), "");

        assertTrue(Files.readString(Environment.sshConfigFile()).contains("Host " + NAME + "\n"));
        assertFalse(Files.exists(Environment.sshKnownHostsFile()));
    }

    @Test
    void aRestartFromTheTuiPinsTheHostKeyToo() throws Exception {
        givenManagedKey();
        var daemon = new FakeIncusDaemon().instance(NAME, "container", "Running", sshCapable());
        var incus = spy(daemon.client());
        doReturn(TestHostKeys.printed(HOST_KEY)).when(incus)
                .waitForReady(eq(NAME), eq(MachineType.CONTAINER), anyString(), anyMap());

        InstanceLifecycle.restartForUse(incus, NAME, MachineType.CONTAINER, false,
                daemon.instance(NAME), RecordingOutput.QUIET);

        assertTrue(SshKeyManager.hasHostKeys(NAME));
    }

    private static Map<String, String> sshCapable() {
        var config = new HashMap<String, String>();
        config.put(Metadata.SSH_SETUP, "true");
        config.put(Metadata.STATIC_IP, "10.166.11.42");
        return config;
    }

    @Test
    void aStartedSshInstanceGetsTheKeyAndAHostEntry() throws Exception {
        givenManagedKey();

        var script = startScript(sshCapable());

        assertTrue(script.contains(KEY), script);
        var hosts = Files.readString(Environment.sshConfigFile());
        assertTrue(hosts.contains("Host " + NAME + "\n"), hosts);
        assertTrue(hosts.contains("Hostname 10.166.11.42"), hosts);
    }

    @Test
    void aRestartFromTheTuiGivesTheKeysToo() throws Exception {
        givenManagedKey();
        var daemon = new FakeIncusDaemon().instance(NAME, "container", "Running", sshCapable());
        var incus = spy(daemon.client());
        var script = ArgumentCaptor.forClass(String.class);
        doReturn("").when(incus).waitForReady(eq(NAME), eq(MachineType.CONTAINER), script.capture(), anyMap());

        InstanceLifecycle.restartForUse(incus, NAME, MachineType.CONTAINER, false,
                daemon.instance(NAME), RecordingOutput.QUIET);

        assertTrue(script.getValue().contains(KEY), script.getValue());
    }

    @Test
    void anInstanceWithoutSshGetsNeither() throws Exception {
        givenManagedKey();

        var script = startScript(Map.of(Metadata.STATIC_IP, "10.166.11.42"));

        assertFalse(script.contains(KEY), script);
        assertFalse(Files.exists(Environment.sshConfigFile()));
    }

    @Test
    void aStartRefreshesAHostEntryForAnOldAddress() throws Exception {
        givenManagedKey();
        dev.incusspawn.ssh.SshKeyManager.addHostEntry(NAME, "10.166.10.42");

        startScript(sshCapable());

        var hosts = Files.readString(Environment.sshConfigFile());
        assertTrue(hosts.contains("Hostname 10.166.11.42"), hosts);
        assertFalse(hosts.contains("10.166.10.42"), hosts);
    }

    @Test
    void theKeysAreWrittenAsAgentuserNeverAsRoot() {
        // agentuser owns ~/.ssh: a symlink there must not steer a root write
        var script = RuntimeSetup.authorizeKeysScript(List.of(KEY));
        assertTrue(script.contains("runuser -u agentuser -- env -i HOME=/home/agentuser PATH=/usr/bin:/bin sh -c '"
                + RuntimeSetup.APPEND_KEYS + "'"), script);
        assertFalse(RuntimeSetup.APPEND_KEYS.contains("'"), "it is quoted whole");
    }

    @Test
    void aStartKeepsKeysTheGuestAlreadyAccepts(@TempDir Path home) throws Exception {
        // Unlike a branch, which writes the file whole, a start only adds what is missing: a key
        // the user added inside the box survives its next start
        var file = home.resolve(".ssh/authorized_keys");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "ssh-ed25519 AAAAuserKey user@box"); // no final newline

        for (int run = 0; run < 2; run++) {
            var sh = new ProcessBuilder("sh", "-c", RuntimeSetup.APPEND_KEYS, "isx-keys", home.toString())
                    .redirectErrorStream(true).start();
            try (var in = sh.getOutputStream()) {
                in.write((KEY + "\nssh-ed25519 AAAAuserKey user@box\n").getBytes());
            }
            assertTrue(sh.waitFor(10, TimeUnit.SECONDS));
            assertEquals("", new String(sh.getInputStream().readAllBytes()));
        }

        assertEquals(List.of("ssh-ed25519 AAAAuserKey user@box", KEY), Files.readAllLines(file));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(file)));
    }

    @Test
    void aKeyTheUserRestrictedIsNotAddedAgainUnrestricted(@TempDir Path home) throws Exception {
        // The same key with options or another comment is that key: re-adding it whole would undo
        // a from="..." the user set on it
        var file = home.resolve(".ssh/authorized_keys");
        Files.createDirectories(file.getParent());
        var restricted = "from=\"10.0.0.1\" " + KEY.replace("incus-spawn managed key", "mine");
        Files.writeString(file, restricted + "\n");

        var sh = new ProcessBuilder("sh", "-c", RuntimeSetup.APPEND_KEYS, "isx-keys", home.toString()).start();
        try (var in = sh.getOutputStream()) {
            in.write((KEY + "\n").getBytes());
        }
        assertTrue(sh.waitFor(10, TimeUnit.SECONDS));

        assertEquals(List.of(restricted), Files.readAllLines(file));
    }

    @Test
    void aStartWithEveryKeyInPlaceNeverSwitchesUser(@TempDir Path home) throws Exception {
        assumeGuestTimeout();
        // The usual start: root's read finds every key, so runuser (a PAM session) never runs
        var file = home.resolve(".ssh/authorized_keys");
        Files.createDirectories(file.getParent());
        Files.writeString(file, KEY + "\n");
        var script = RuntimeSetup.authorizeKeysScript(List.of(KEY))
                .replace("/home/agentuser", home.toString())
                .replace("timeout 10 runuser -u agentuser --", "echo switched >> " + home.resolve("switched") + "; true ||");

        var sh = new ProcessBuilder("sh", "-c", script).start();
        assertTrue(sh.waitFor(10, TimeUnit.SECONDS));
        assertFalse(Files.exists(home.resolve("switched")));

        Files.writeString(file, "");
        sh = new ProcessBuilder("sh", "-c", script).start();
        assertTrue(sh.waitFor(10, TimeUnit.SECONDS));
        assertTrue(Files.exists(home.resolve("switched")), "a missing key does switch");
    }

    /**
     * The guest's script runs in Fedora, whose coreutils has {@code timeout}; a host running these
     * tests without it (stock macOS) would only see the check fail with 127, proving nothing.
     */
    private static void assumeGuestTimeout() throws Exception {
        var probe = new ProcessBuilder("sh", "-c", "command -v timeout").start();
        org.junit.jupiter.api.Assumptions.assumeTrue(probe.waitFor() == 0,
                "no coreutils timeout on this host, as the guest has");
    }

    /** Run {@code script} under sh, failing the test if it has not returned within {@code seconds}. */
    private static int runBounded(String script, String stdin, int seconds) throws Exception {
        var sh = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
        try (var in = sh.getOutputStream()) {
            in.write(stdin.getBytes());
        }
        if (!sh.waitFor(seconds, TimeUnit.SECONDS)) {
            sh.destroyForcibly();
            fail("still running after " + seconds + "s: a guest-controlled file hung root's read");
        }
        return sh.exitValue();
    }

    @Test
    void rootsReadOfAFileThatNeverAnswersIsBoundedAndCountsAsMissing(@TempDir Path home) throws Exception {
        assumeGuestTimeout();
        // agentuser may swap a FIFO in after the -f test, or write a huge line: root's read must
        // end, and say "missing" so the agentuser path decides under its own bound
        var fifo = home.resolve("authorized_keys");
        assertEquals(0, new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor());

        var status = runBounded(RuntimeSetup.keysPresentCheck(fifo.toString()), KEY + "\n", 8);

        assertEquals(124, status, "timeout's own status: the bound ended the read");
    }

    @Test
    void aFifoOrASymlinkedSshDirectoryIsLeftToAgentuser(@TempDir Path home) throws Exception {
        assumeGuestTimeout();
        var switched = home.resolve("switched");
        var script = RuntimeSetup.authorizeKeysScript(List.of(KEY))
                .replace("/home/agentuser", home.toString())
                .replace("timeout 10 runuser -u agentuser --", "echo switched >> " + switched + "; true ||");

        // ~/.ssh pointing at a directory that holds every key: root must not read through it
        var elsewhere = Files.createDirectories(home.resolve("elsewhere"));
        Files.writeString(elsewhere.resolve("authorized_keys"), KEY + "\n");
        Files.createSymbolicLink(home.resolve(".ssh"), elsewhere);
        runBounded(script, "", 8);
        assertTrue(Files.exists(switched), "a symlinked ~/.ssh goes to agentuser");

        Files.delete(switched);
        Files.delete(home.resolve(".ssh"));
        Files.createDirectories(home.resolve(".ssh"));
        assertEquals(0, new ProcessBuilder("mkfifo", home.resolve(".ssh/authorized_keys").toString()).start().waitFor());
        runBounded(script, "", 8);
        assertTrue(Files.exists(switched), "a FIFO goes to agentuser");
    }

    @Test
    void aCommentedOutKeyIsNoKey(@TempDir Path home) throws Exception {
        assumeGuestTimeout();
        // A line the user commented out disables that key: it neither counts as present for root's
        // check nor stops the key being added
        var file = home.resolve(".ssh/authorized_keys");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# " + KEY + "\n");

        assertNotEquals(0, runBounded(RuntimeSetup.keysPresentCheck(file.toString()), KEY + "\n", 8));
        runBounded("sh -c '" + RuntimeSetup.APPEND_KEYS + "' isx-keys " + home, KEY + "\n", 8);

        assertEquals(List.of("# " + KEY, KEY), Files.readAllLines(file));
        assertEquals(0, runBounded(RuntimeSetup.keysPresentCheck(file.toString()), KEY + "\n", 8));
    }
}
