package dev.incusspawn.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The host commands a host integration runs are bounded, a daemon left holding the output included. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class HostCommandsTest {

    @Test
    void outputAndExitStatusAreReturned() {
        var result = HostCommands.SYSTEM.run(Duration.ofSeconds(10), List.of("sh", "-c", "echo out; echo err >&2; exit 3"));
        assertEquals(3, result.exit());
        assertTrue(result.output().contains("out") && result.output().contains("err"), result.output());
    }

    /** So no ssh a host integration's command runs starts an instance: herdr's add runs from a start (#2038). */
    @Test
    void aCommandRunsWithSshProxyStartsTurnedOff() {
        var result = HostCommands.SYSTEM.run(Duration.ofSeconds(10),
                List.of("sh", "-c", "echo \"$" + dev.incusspawn.ssh.SshKeyManager.NO_START_ENV + "\""));
        assertEquals("1", result.output().strip());
    }

    @Test
    void aCommandPastItsTimeIsKilled() {
        var start = System.nanoTime();
        var result = HostCommands.SYSTEM.run(Duration.ofSeconds(1), List.of("sh", "-c", "echo connecting to ws; sleep 30"));
        assertEquals(-1, result.exit());
        assertTrue(result.output().startsWith("connecting to ws\ntimed out after 1 seconds"), "says what it said: " + result.output());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    }

    /** It exits, but a child it started in the background still holds the output pipe open. */
    @Test
    void aDaemonLeftHoldingTheOutputDoesNotHoldTheCaller() {
        var start = System.nanoTime();
        var result = HostCommands.SYSTEM.run(Duration.ofSeconds(2), List.of("sh", "-c", "sleep 30 & echo started"));
        assertEquals(0, result.exit());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10, "bounded by its timeout");
        assertTrue(result.output().contains("started"), "what it printed before is kept: " + result.output());
    }

    @Test
    void aCommandIsLookedUpOnThePath() {
        assertTrue(HostCommands.SYSTEM.onPath("sh"));
        assertFalse(HostCommands.SYSTEM.onPath("no-such-command-isx-1720"));
    }
}
