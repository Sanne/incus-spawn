package dev.incusspawn.incus;

import dev.incusspawn.incus.FirewallDetector.DetectionResult;
import dev.incusspawn.incus.FirewallDetector.DetectionResult.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
}
