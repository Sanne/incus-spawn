package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SshProxyCommandTest {

    @TempDir
    Path dir;

    /**
     * The guest command run with stand-ins for what it calls: {@code grep} finds sshd listening from
     * its {@code readyAfter}th probe on, {@code systemctl} reports {@code systemState}, {@code date} ticks a second per call, {@code sleep} only counts, and {@code nc} is refused until
     * then and otherwise relays stdin, as sshd's port would. Returns the stand-ins' log.
     */
    private List<String> runGuestCommand(int readyAfter, String stdin) throws Exception {
        return runGuestCommand(readyAfter, "starting", stdin);
    }

    private List<String> runGuestCommand(int readyAfter, String systemState, String stdin) throws Exception {
        return runGuestCommand(readyAfter, systemState, stdin, true);
    }

    private List<String> runGuestCommand(int readyAfter, String systemState, String stdin, boolean withSystemctl) throws Exception {
        var bin = Files.createDirectories(dir.resolve("bin"));
        stub(bin, "grep", """
                n=$(($(cat "$STATE/probes" 2>/dev/null || echo 0) + 1)); echo $n > "$STATE/probes"
                echo "grep $*" >> "$STATE/log"
                [ $n -ge %d ] && touch "$STATE/ready"
                [ -e "$STATE/ready" ]
                """.formatted(readyAfter));
        if (withSystemctl) stub(bin, "systemctl", "echo " + systemState);
        stub(bin, "date", """
                t=$(($(cat "$STATE/clock" 2>/dev/null || echo 0) + 1)); echo $t > "$STATE/clock"
                echo $t
                """);
        stub(bin, "sleep", "echo \"sleep $*\" >> \"$STATE/log\"");
        stub(bin, "nc", """
                if [ ! -e "$STATE/ready" ]; then echo "nc refused" >> "$STATE/log"; exit 1; fi
                echo "nc $* got $(cat)" >> "$STATE/log"
                """);
        var cmd = new ArrayList<>(List.of("/bin/sh", "-c", "exec \"$@\"", "sh"));
        cmd.addAll(List.of(SshProxyCommand.guestCommand()));
        var input = Files.writeString(dir.resolve("stdin"), stdin);
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(input.toFile());
        pb.environment().put("PATH", bin + ":/usr/bin:/bin");
        pb.environment().put("STATE", dir.toString());
        var p = pb.start();
        // The stubs write little, so waiting first cannot fill the pipe, and a loop that never ends fails here.
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        p.getInputStream().readAllBytes();
        var log = dir.resolve("log");
        return Files.exists(log) ? Files.readAllLines(log) : List.of();
    }

    private static void stub(Path bin, String name, String body) throws Exception {
        var f = bin.resolve(name);
        Files.writeString(f, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void waitsForSshdBeforeConnecting() throws Exception {
        // Right after a start, sshd is not listening yet for about a second (#1699).
        var log = runGuestCommand(3, "SSH-2.0-OpenSSH_9.9");

        assertFalse(log.contains("nc refused"), log::toString);
        assertEquals("nc localhost 22 got SSH-2.0-OpenSSH_9.9", log.getLast(), log::toString);
        assertEquals(3, log.stream().filter(l -> l.startsWith("grep ")).count(), log::toString);
        assertEquals(2, log.stream().filter(l -> l.equals("sleep 0.1")).count(), log::toString);
    }

    @Test
    void connectsAtOnceWhenSshdListens() throws Exception {
        var log = runGuestCommand(1, "hello");

        assertEquals(List.of("grep -qs " + SshProxyCommand.SSHD_LISTENING
                        + " /proc/net/tcp6 /proc/net/tcp", "nc localhost 22 got hello"), log);
    }

    @Test
    void givesUpWaitingAndConnectsAnyway() throws Exception {
        // Nothing listens while the guest never finishes starting: the wait ends at a wall-clock deadline.
        var log = runGuestCommand(Integer.MAX_VALUE, "");

        assertEquals("nc refused", log.getLast(), log::toString);
        // The first miss reads the clock at 1 and sets the deadline at 1 + 20; each pass reads it once more.
        assertEquals(SshProxyCommand.SSHD_WAIT_SECONDS,
                log.stream().filter(l -> l.equals("sleep 0.1")).count(), log::toString);
    }

    @Test
    void doesNotWaitOnceTheGuestHasBooted() throws Exception {
        // sshd stopped or failed after boot will not come: refused at once, as before the wait.
        for (var state : List.of("running", "degraded")) {
            Files.deleteIfExists(dir.resolve("log"));
            Files.deleteIfExists(dir.resolve("probes"));
            var log = runGuestCommand(Integer.MAX_VALUE, state, "");

            assertEquals(List.of("grep -qs " + SshProxyCommand.SSHD_LISTENING
                    + " /proc/net/tcp6 /proc/net/tcp", "nc refused"), log, state);
        }
    }

    /**
     * Right after a start {@code systemctl} cannot reach the system bus yet and prints nothing: the
     * guest is still starting, so the connection waits for sshd rather than being refused. Seen with
     * {@code isx ssh-proxy --start}, whose connection meets every instance it starts at that moment.
     */
    @Test
    void waitsWhileSystemctlCannotReachTheBusYet() throws Exception {
        var log = runGuestCommand(3, "", "SSH-2.0-OpenSSH_9.9");

        assertFalse(log.contains("nc refused"), log::toString);
        assertEquals("nc localhost 22 got SSH-2.0-OpenSSH_9.9", log.getLast(), log::toString);
    }

    /** A guest without systemctl has nothing to wait for: refused at once, as before the wait. */
    @Test
    void doesNotWaitForAGuestWithoutSystemctl() throws Exception {
        var log = runGuestCommand(Integer.MAX_VALUE, "", "", false);

        assertEquals(List.of("grep -qs " + SshProxyCommand.SSHD_LISTENING
                + " /proc/net/tcp6 /proc/net/tcp", "nc refused"), log);
    }

    @Test
    void theProbeMatchesOnlyAListeningPort22() throws Exception {
        var tcp = dir.resolve("tcp");
        var listening = List.of(
                "   1: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1 1",
                "   0: 00000000000000000000000000000000:0016 00000000000000000000000000000000:0000 0A "
                        + "00000000:00000000 00:00000000 00000000     0        0 2 1");
        var notListening = List.of(
                // An established ssh connection, and something else listening.
                "   2: 0100007F:0016 0100007F:A1B2 01 00000000:00000000 00:00000000 00000000     0        0 3 1",
                "   3: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 4 1",
                "   4: 0100007F:A1B2 0100007F:0016 01 00000000:00000000 00:00000000 00000000     0        0 5 1");
        for (var line : listening) {
            Files.writeString(tcp, line + "\n");
            assertEquals(0, grep(tcp), line);
        }
        for (var line : notListening) {
            Files.writeString(tcp, line + "\n");
            assertEquals(1, grep(tcp), line);
        }
    }

    private static int grep(Path file) throws Exception {
        var p = new ProcessBuilder("grep", "-qs", SshProxyCommand.SSHD_LISTENING, file.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    /**
     * A stopped instance is one error, with how to start it, which a recorder sees; stdout, the
     * connection's stream, gets nothing (#1703).
     */
    @Test
    void aStoppedInstanceIsAnErrorWithItsRemedy() {
        var incus = mock(IncusClient.class);
        when(incus.instanceMetadataOrThrow("box")).thenReturn(io.hyperfoil.tools.jjq.value.JqObject.builder(1)
                .put("status", "Stopped").build());
        var command = new SshProxyCommand();
        command.instance = "box";
        var recorded = RecordingOutput.during(() -> assertFalse(command.checkInstanceRunning(incus)));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.ERROR,
                        "instance 'box' is not running (status: Stopped).", "Start it first: isx start box")),
                recorded.events());
    }

    /**
     * ssh shows a ProxyCommand's stderr on the terminal untouched, and the guest decides it: it is
     * shown as text, while the connection's bytes pass through as they are.
     */
    @Test
    void theGuestsStderrIsShownAsText() {
        var incus = mock(IncusClient.class);
        when(incus.execBidirectional(eq("box"), eq(0), eq(0), eq("/"), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    OutputStream stdout = call.getArgument(6);
                    OutputStream stderr = call.getArgument(7);
                    stdout.write("SSH-2.0-OpenSSH\033]0;raw\007".getBytes(StandardCharsets.UTF_8));
                    stderr.write("nc: refused\033]0;pwned\007\033[2J\r\nlast".getBytes(StandardCharsets.UTF_8));
                    return 1;
                });
        var command = new SshProxyCommand();
        command.instance = "box";
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();

        assertEquals(1, command.relay(incus, InputStream.nullInputStream(), out,
                new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertEquals("SSH-2.0-OpenSSH\033]0;raw\007", out.toString(StandardCharsets.UTF_8));
        assertEquals("nc: refused\nlast\n", err.toString(StandardCharsets.UTF_8));
    }

    /** {@code --start} prepares a stopped instance under its lock, as {@code isx start} does (#1720). */
    @Test
    void withStartAStoppedInstanceIsPreparedFirst() throws Exception {
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon().container("box", java.util.Map.of());
        var locks = new StartStopCommandTest.Locks();
        var prepared = new ArrayList<String>();
        var command = new SshProxyCommand();
        command.instance = "box";
        command.start = true;
        command.preparer = (incus, name, out) -> prepared.add(name);

        var recorded = RecordingOutput.during(() -> assertTrue(
                assertDoesNotThrow(() -> command.ensureRunning(daemon.client(), locks))));

        assertEquals(List.of("box"), prepared);
        assertEquals(List.of("box:starting"), locks.taken);
        assertEquals(List.of(), recorded.events(RecordingOutput.Kind.ERROR));
    }

    /**
     * A running instance no isx process holds is used as it is: every connection of an orchestrator,
     * git and scp included, runs this ProxyCommand, and must not pay a whole preparation each time.
     * One held (a start delivering its proofs) is waited for and prepared.
     */
    @Test
    void withStartARunningInstanceIsPreparedOnlyWhileAnotherIsxHoldsIt() throws Exception {
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon().instance("box", "container", "Running", java.util.Map.of());
        var prepared = new ArrayList<String>();
        var command = new SshProxyCommand();
        command.instance = "box";
        command.start = true;
        command.preparer = (incus, name, out) -> prepared.add(name);
        var locks = new StartStopCommandTest.Locks();

        daemon.clearRequests();
        RecordingOutput.during(() -> assertTrue(assertDoesNotThrow(() -> command.ensureRunning(daemon.client(), locks))));
        assertEquals(List.of(), prepared);
        assertEquals(List.of("GET /1.0/instances/box"), daemon.requests(), "one read per connection");

        locks.held.add("box");
        var release = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            synchronized (locks) { locks.held.remove("box"); }
        });
        release.start();
        RecordingOutput.during(() -> assertTrue(assertDoesNotThrow(() -> command.ensureRunning(daemon.client(), locks))));
        release.join();
        assertEquals(List.of("box"), prepared);
    }

    /**
     * After a host reboot Incus restores an instance running, with an empty /run/isx: its boot is
     * not the one its secret was stamped for, so the connection prepares it rather than reaching an
     * agent with no secret or credential proofs (#1720, review of #1815).
     */
    @Test
    void withStartARunningInstanceWhoseBootHasNoSecretIsPrepared() throws Exception {
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon().instance("box", "container", "Running", java.util.Map.of(
                dev.incusspawn.incus.Metadata.INSTANCE_SECRET_BOOT, "2026-10-27T09:00:00Z"))
                .lastUsedAt("box", "2026-10-28T08:00:00Z");
        var prepared = new ArrayList<String>();
        var command = new SshProxyCommand();
        command.instance = "box";
        command.start = true;
        command.preparer = (incus, name, out) -> prepared.add(name);

        RecordingOutput.during(() -> assertTrue(assertDoesNotThrow(
                () -> command.ensureRunning(daemon.client(), new StartStopCommandTest.Locks()))));

        assertEquals(List.of("box"), prepared);
    }

    /**
     * A plain {@code ssh} into a box Incus restored running after a host reboot: its /run/isx is
     * empty, so without its secret and proofs the agent in that shell got a 401. The connection
     * gives it them, once per boot, without starting anything (#1975).
     */
    @Test
    @org.junit.jupiter.api.extension.ExtendWith(IsolatedHome.class)
    void withoutStartARunningBoxWhoseBootHasNoSecretIsGivenItOnce() {
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon().instance("box", "container", "Running", java.util.Map.of(
                dev.incusspawn.incus.Metadata.INSTANCE_SECRET_BOOT, "2026-11-02T19:04:00Z proofs-v4:"))
                .lastUsedAt("box", "2026-11-03T16:33:00Z");
        var delivered = new ArrayList<String>();
        var incus = org.mockito.Mockito.spy(daemon.client());
        org.mockito.Mockito.doAnswer(call -> {
            java.util.Map<String, String> env = call.getArgument(1);
            if (env.containsKey(dev.incusspawn.proxy.InstanceSecret.DELIVERY_ENV)) delivered.add(env.get(dev.incusspawn.proxy.InstanceSecret.DELIVERY_ENV));
            return new IncusClient.ExecResult(0, "", "");
        }).when(incus).shellExec(eq("box"), org.mockito.ArgumentMatchers.anyMap(), any(String[].class));
        var command = new SshProxyCommand();
        command.instance = "box";

        assertTrue(command.checkInstanceRunning(incus));
        assertEquals(1, delivered.size(), daemon.requests()::toString);
        org.mockito.Mockito.verify(incus, org.mockito.Mockito.never()).start(org.mockito.ArgumentMatchers.anyString());

        daemon.clearRequests();
        assertTrue(command.checkInstanceRunning(incus));
        assertEquals(1, delivered.size(), "once per boot, not once per connection");
        assertEquals(List.of("GET /1.0/instances/box"), daemon.requests(), "one read per connection, as before");
    }

    /** Without {@code --start}, the ProxyCommand a plain {@code ssh} runs, nothing is started. */
    @Test
    void withoutStartAStoppedInstanceIsNeverPrepared() throws Exception {
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon().container("box", java.util.Map.of());
        var command = new SshProxyCommand();
        command.instance = "box";
        command.preparer = (incus, name, out) -> fail("a plain ssh never starts an instance");

        RecordingOutput.during(() -> assertFalse(
                assertDoesNotThrow(() -> command.ensureRunning(daemon.client(), new StartStopCommandTest.Locks()))));
    }

    @Test
    void withStartAMissingInstanceIsAnError() {
        var command = new SshProxyCommand();
        command.instance = "gone";
        command.start = true;
        command.preparer = (incus, name, out) -> fail("nothing to prepare");

        var recorded = RecordingOutput.during(() -> assertFalse(assertDoesNotThrow(() ->
                command.ensureRunning(new dev.incusspawn.incus.FakeIncusDaemon().client(), new StartStopCommandTest.Locks()))));

        assertEquals(List.of("instance 'gone' not found or not accessible."),
                recorded.events(RecordingOutput.Kind.ERROR).stream().map(RecordingOutput.Event::msg).toList());
    }

    /**
     * A connection that waited out a stop must not undo it: an instance up when the connection came
     * and down once it may act is refused, never started again (#1720).
     */
    @Test
    void withStartAConnectionThatWaitedOutAStopDoesNotUndoIt() throws Exception {
        var daemon = new dev.incusspawn.incus.FakeIncusDaemon().instance("box", "container", "Running", java.util.Map.of());
        var locks = new StartStopCommandTest.Locks();
        locks.held.add("box");
        var command = new SshProxyCommand();
        command.instance = "box";
        command.start = true;
        command.preparer = (incus, name, out) -> fail("a stopped instance is not started again");
        var stopper = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { return; }
            daemon.client().stop("box");
            synchronized (locks) { locks.held.remove("box"); }
        });
        stopper.start();

        var recorded = RecordingOutput.during(() -> assertFalse(
                assertDoesNotThrow(() -> command.ensureRunning(daemon.client(), locks))));
        stopper.join();

        assertEquals("Stopped", daemon.instance("box").path("status").asText());
        assertEquals(List.of("'box' was stopped while this connection waited for it."),
                recorded.events(RecordingOutput.Kind.ERROR).stream().map(RecordingOutput.Event::msg).toList());
    }
}
