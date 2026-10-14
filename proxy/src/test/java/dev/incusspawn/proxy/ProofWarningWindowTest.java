package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProofWarningWindowTest {
    @Test void aBurstIsBoundedButCannotPermanentlySilenceDiagnostics() {
        var warnings = new MitmProxy.ProofWarningWindow();
        long start = 123;
        assertTrue(warnings.shouldLog("first:github", start));
        assertFalse(warnings.shouldLog("first:github", start + 1));
        for (int i = 1; i < MitmProxy.ProofWarningWindow.LIMIT; i++) {
            assertTrue(warnings.shouldLog("caller" + i + ":github", start + 1));
        }
        var nextWindow = start + MitmProxy.ProofWarningWindow.WINDOW_NANOS;
        assertFalse(warnings.shouldLog("later:github", nextWindow - 1));
        assertFalse(warnings.shouldLog("first:github", nextWindow - 1));
        assertTrue(warnings.shouldLog("later:github", nextWindow));
        assertTrue(warnings.shouldLog("first:github", nextWindow));
        assertFalse(warnings.shouldLog("first:github", nextWindow + 1));
    }
}
