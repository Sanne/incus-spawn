package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EventuallyTest {

    @Test
    void returnsAtOnceForAConditionThatAlreadyHolds() throws Exception {
        var start = System.nanoTime();
        Eventually.await("nothing", Duration.ofSeconds(30), () -> true);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000);
    }

    @Test
    void samplesOftenEnoughToSeeAConditionThatTurnsTrueSoon() throws Exception {
        var samples = new AtomicInteger();
        Eventually.await("the fifth sample", Duration.ofSeconds(30), () -> samples.incrementAndGet() >= 5);
        assertEquals(5, samples.get());
    }

    @Test
    void failureNamesWhatWasAwaitedAndTheState() {
        var e = assertThrows(AssertionError.class, () ->
                Eventually.await("the moon", Duration.ofMillis(30), () -> false, () -> "still earth"));
        assertTrue(e.getMessage().contains("the moon"), e.getMessage());
        assertTrue(e.getMessage().contains("still earth"), e.getMessage());
    }

    @Test
    void pollGivesTheFirstValue() throws Exception {
        var n = new AtomicInteger();
        assertEquals("three", Eventually.poll("three", Duration.ofSeconds(30),
                () -> n.incrementAndGet() >= 3 ? "three" : null));
    }

    @Test
    void holdsWithinSaysFalseOnTimeout() throws Exception {
        assertFalse(Eventually.holdsWithin(Duration.ofMillis(20), () -> false));
    }
}
