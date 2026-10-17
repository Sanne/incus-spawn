package dev.incusspawn.incus;

import dev.incusspawn.incus.FirewallDetector.DetectionResult;
import dev.incusspawn.incus.FirewallDetector.DetectionResult.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class FirewallDetectorTest {

    @Test
    void firewalldActiveSelected() {
        var result = FirewallDetector.decide(true, true, false);
        assertInstanceOf(UseFirewalld.class, result);
        assertFalse(((UseFirewalld) result).needsStart());
    }

    @Test
    void ufwActiveSelected() {
        var result = FirewallDetector.decide(false, false, true);
        assertInstanceOf(UseUfw.class, result);
    }

    @Test
    void firewalldTakesPriorityOverUfw() {
        var result = FirewallDetector.decide(true, true, true);
        assertInstanceOf(UseFirewalld.class, result);
    }

    @Test
    void ufwActiveEvenWhenFirewalldInstalled() {
        var result = FirewallDetector.decide(true, false, true);
        assertInstanceOf(UseUfw.class, result);
    }

    @Test
    void onlyFirewalldInstalledNeedsStart() {
        var result = FirewallDetector.decide(true, false, false);
        assertInstanceOf(UseFirewalld.class, result);
        assertTrue(((UseFirewalld) result).needsStart());
    }

    @Test
    void ufwInstalledButDisabledFallsToNeitherInstalled() {
        // UFW installed-but-disabled (Ubuntu default) should NOT auto-enable it
        var result = FirewallDetector.decide(false, false, false);
        assertInstanceOf(NeitherInstalled.class, result);
    }

    @Test
    void bothInstalledNeitherActivePrefersFirewalld() {
        var result = FirewallDetector.decide(true, false, false);
        assertInstanceOf(UseFirewalld.class, result);
        assertTrue(((UseFirewalld) result).needsStart());
    }

    @Test
    void neitherInstalledReturnsNeitherInstalled() {
        var result = FirewallDetector.decide(false, false, false);
        assertInstanceOf(NeitherInstalled.class, result);
    }

    /** Answers as configured, counting each probe; {@code failure} makes the first probe throw. */
    private static final class FakeProbes implements FirewallDetector.Probes {
        boolean firewalldActive, ufwActive, firewalldInstalled, ufwInstalled;
        Exception failure;
        /** When set, firewalld is asked through the real probe, running this command. */
        List<String> systemctl;
        final AtomicInteger probes = new AtomicInteger();

        @Override public boolean firewalldActive() throws IOException, InterruptedException {
            probes.incrementAndGet();
            if (failure instanceof IOException e) throw e;
            if (failure instanceof InterruptedException e) throw e;
            if (systemctl != null) return FirewalldCheck.probeActive(systemctl);
            return firewalldActive;
        }
        @Override public boolean ufwActive() { probes.incrementAndGet(); return ufwActive; }
        @Override public boolean firewalldInstalled() { probes.incrementAndGet(); return firewalldInstalled; }
        @Override public boolean ufwInstalled() { probes.incrementAndGet(); return ufwInstalled; }
    }

    /** A check whose banners are collected by title. */
    private record Collected(FirewallDetector.Check check, List<String> banners) {
        boolean warnIfNotRunning() {
            return check.warnIfNotRunning((title, diagnostic) -> banners.add(title));
        }
    }

    private static Collected check(FakeProbes probes, List<String> banners) {
        return new Collected(new FirewallDetector.Check(probes), banners);
    }

    @Test
    void aCheckWithNothingToWarnAboutRunsOncePerProcess() {
        var probes = new FakeProbes();
        probes.firewalldActive = true;
        var banners = new ArrayList<String>();
        var check = check(probes, banners);
        for (int i = 0; i < 3; i++) assertFalse(check.warnIfNotRunning());
        assertEquals(1, probes.probes.get(), "a check with nothing to warn about must not run again");
        assertEquals(List.of(), banners);
    }

    @Test
    void aCheckThatWarnedRunsAgainUntilTheFirewallIsStarted() {
        var probes = new FakeProbes();
        probes.firewalldInstalled = true;
        var banners = new ArrayList<String>();
        var check = check(probes, banners);
        assertTrue(check.warnIfNotRunning());
        assertTrue(check.warnIfNotRunning());
        assertEquals(List.of("firewalld is not running:", "firewalld is not running:"), banners);
        // each probe asked once per check: is firewalld active, is UFW, is firewalld installed
        assertEquals(6, probes.probes.get());

        probes.firewalldActive = true; // the user started it
        assertFalse(check.warnIfNotRunning());
        assertFalse(check.warnIfNotRunning());
        assertEquals(7, probes.probes.get());
        assertEquals(2, banners.size());
    }

    @Test
    void aProbeThatFailedIsNotRememberedAsNothingToWarnAbout() {
        var probes = new FakeProbes();
        probes.firewalldInstalled = true;
        probes.failure = new IOException("cannot run systemctl");
        var banners = new ArrayList<String>();
        var check = check(probes, banners);
        assertFalse(check.warnIfNotRunning());

        probes.failure = null;
        assertTrue(check.warnIfNotRunning(), "a failed probe must not silence the next check");
        assertEquals(List.of("firewalld is not running:"), banners);
    }

    @Test
    void anInterruptedProbeIsNotRememberedAndKeepsTheInterrupt() {
        var probes = new FakeProbes();
        probes.ufwInstalled = true;
        probes.failure = new InterruptedException();
        var banners = new ArrayList<String>();
        var check = check(probes, banners);
        try {
            assertFalse(check.warnIfNotRunning());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        probes.failure = null;
        assertTrue(check.warnIfNotRunning(), "an interrupted probe must not silence the next check");
        assertEquals(List.of("UFW is not active:"), banners);
    }

    @Test
    void findStoppedPrefersARunningFirewallAndThenFirewalld() throws Exception {
        var probes = new FakeProbes();
        assertNull(FirewallDetector.findStopped(probes));
        probes.ufwInstalled = true;
        assertSame(UfwCheck.STOPPED, FirewallDetector.findStopped(probes));
        probes.firewalldInstalled = true;
        assertSame(FirewalldCheck.STOPPED, FirewallDetector.findStopped(probes));
        probes.ufwActive = true;
        assertNull(FirewallDetector.findStopped(probes));
    }

    private static final List<String> MISSING_SYSTEMCTL = List.of("isx-test-no-such-systemctl-870", "is-active", "firewalld");

    @Test
    void aHostWithoutSystemctlHasNoFirewalldRunningRatherThanAFailedProbe() throws Exception {
        assertFalse(FirewalldCheck.probeActive(MISSING_SYSTEMCTL));
    }

    @Test
    void aHostWithoutSystemctlStillReachesUfwAndIsRemembered() {
        var probes = new FakeProbes();
        probes.systemctl = MISSING_SYSTEMCTL;
        probes.ufwInstalled = true;
        var banners = new ArrayList<String>();
        assertTrue(check(probes, banners).warnIfNotRunning(), "a stopped UFW must be reported without systemd");
        assertEquals(List.of("UFW is not active:"), banners);

        var quietProbes = new FakeProbes();
        quietProbes.systemctl = MISSING_SYSTEMCTL;
        var quiet = check(quietProbes, banners);
        assertFalse(quiet.warnIfNotRunning());
        assertFalse(quiet.warnIfNotRunning());
        assertEquals(4, quietProbes.probes.get(), "a host without systemd and without a firewall is remembered");
    }

    @Test
    void aMissingOrUnreadableUfwConfIsInactiveRatherThanAFailedProbe(@TempDir Path dir) throws Exception {
        assertFalse(UfwCheck.probeActive(dir.resolve("ufw.conf")));
        var unreadable = Files.writeString(dir.resolve("unreadable.conf"), "ENABLED=yes\n");
        try {
            Files.setPosixFilePermissions(unreadable, Set.of());
        } catch (UnsupportedOperationException e) {
            return; // no POSIX permissions to take away
        }
        assumeFalse(Files.isReadable(unreadable), "running as root, which reads it anyway");
        assertFalse(UfwCheck.probeActive(unreadable));
    }
}
