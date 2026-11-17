package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThrottlesTest {

    @Test
    void refusalsAreCountedAndAnAnswerEndsRefusing() {
        var throttles = new Throttles(() -> 1000);
        throttles.answered("repo.example", 429);
        throttles.answered("repo.example", 429);
        throttles.servedUnconfirmed("repo.example", 429);
        throttles.answered("repo.example", 503);
        assertTrue(throttles.snapshot().get("repo.example").refusing(), "a 5xx says nothing of the limit");
        throttles.answered("repo.example", 200);
        var t = throttles.snapshot().get("repo.example");
        assertEquals(2, t.refusals());
        assertEquals(1, t.servedUnconfirmed());
        assertFalse(t.refusing());
    }

    @Test
    void aCopyServedWithoutAskingCountsOnlyWhileTheDomainRefuses() {
        var throttles = new Throttles(() -> 1);
        throttles.answered("repo.example", 429);
        throttles.servedUnconfirmed("repo.example", 0);
        throttles.answered("repo.example", 200);
        throttles.servedUnconfirmed("repo.example", 0);
        assertEquals(1, throttles.snapshot().get("repo.example").servedUnconfirmed(), "an outage after the limit lifted is not its");
    }

    @Test
    void aDomainThatNeverRefusedIsNotListed() {
        var throttles = new Throttles(() -> 1);
        throttles.answered("fine.example", 200);
        throttles.servedUnconfirmed("fine.example", 429);
        assertTrue(throttles.snapshot().isEmpty());
    }

    @Test
    void domainsAreBounded() {
        var throttles = new Throttles(() -> 1);
        for (var i = 0; i < Throttles.MAX_DOMAINS + 50; i++) throttles.answered("h" + i + ".example", 429);
        assertEquals(Throttles.MAX_DOMAINS, throttles.snapshot().size());
    }
}
