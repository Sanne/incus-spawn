package dev.incusspawn.proxy;

import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiActivityTest {

    @Test
    void onlyMessageCreatesAreModelCalls() {
        assertTrue(ApiActivity.isModelCall("/v1/messages"));
        assertTrue(ApiActivity.isModelCall("/v1/projects/p/locations/global/publishers/anthropic/models/claude-opus-5-5:streamRawPredict"));
        assertTrue(ApiActivity.isModelCall("/v1/projects/p/locations/global/publishers/anthropic/models/claude-opus-5-5:rawPredict"));
        assertFalse(ApiActivity.isModelCall("/v1/messages/count_tokens"));
        assertFalse(ApiActivity.isModelCall("/v1/messages/batches"));
        assertFalse(ApiActivity.isModelCall("/v1/projects/p/locations/global/publishers/anthropic/models/count-tokens:rawPredict"));
        assertFalse(ApiActivity.isModelCall("/api/event_logging/batch"));
        assertFalse(ApiActivity.isModelCall(null));
    }

    @Test
    void aCallIsInFlightUntilItEndsOnceHoweverItEnds() {
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);
        var first = activity.begin("w");
        now.set(2_000);
        activity.begin("w");

        var w = activity.snapshot(Set.of("w")).of("w");
        assertEquals(2, w.requests());
        assertEquals(2, w.inFlight());
        assertEquals(Instant.ofEpochMilli(2_000), w.lastRequestAt());
        assertNull(w.lastResponseAt(), "nothing answered yet");

        now.set(3_000);
        first.end();
        first.end(); // the response's end and its connection's close both report it
        w = activity.snapshot(Set.of("w")).of("w");
        assertEquals(1, w.inFlight());
        assertEquals(Instant.ofEpochMilli(3_000), w.lastResponseAt());
        assertEquals(Instant.ofEpochMilli(1_000), w.countingSince(), "from the first call");
    }

    @Test
    void anInstanceNoLongerKnownIsForgottenOnceIdle() {
        var activity = new ApiActivity();
        activity.begin("gone").end();
        activity.begin("kept").end();
        activity.begin("busy");

        var instances = activity.snapshot(Set.of("kept")).instances();

        assertTrue(instances.containsKey("kept"));
        assertFalse(instances.containsKey("gone"));
        assertEquals(1, instances.get("busy").inFlight(), "a call in flight keeps its counters until it ends");
    }

    @Test
    void aKnownInstanceHasCountersFromTheFirstReadSoNoReadLacksAStart() {
        // A first read without counting_since must never be subtracted as zero: counters made
        // after it could be dropped and made again before the second, undercounting unseen
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);

        var first = activity.snapshot(Set.of("w")).of("w");
        now.set(2_000);
        activity.begin("w").end();
        var second = activity.snapshot(Set.of("w")).of("w");

        assertEquals(Instant.ofEpochMilli(1_000), first.countingSince());
        assertEquals(0, first.requests());
        assertEquals(first.countingSince(), second.countingSince(), "the same start: subtractable");
        assertEquals(1, second.requests() - first.requests());
        assertNull(activity.snapshot(Set.of("w")).of("unknown").countingSince(), "nothing for one it does not know");
    }

    @Test
    void countsDroppedBetweenTwoReadsAreNeverSubtractedAsIfTheyWereNot() {
        // The registry can miss a live instance for a moment (an address two instances claim, a
        // listing that failed to parse): its counters are dropped, and that must show (#1052)
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);
        var call = activity.begin("w");
        call.respond(200, "application/json", null);
        call.accept(bytes("{\"usage\":{\"output_tokens\":500}}"));
        call.end();
        var before = activity.snapshot(Set.of("w")).of("w");

        now.set(2_000);
        assertFalse(activity.snapshot(Set.of()).instances().containsKey("w"), "dropped while unlisted");
        now.set(3_000);
        var again = activity.begin("w");
        again.respond(200, "application/json", null);
        again.accept(bytes("{\"usage\":{\"output_tokens\":20}}"));
        again.end();
        var after = activity.snapshot(Set.of("w")).of("w");

        assertEquals(500, before.outputTokens());
        assertEquals(20, after.outputTokens(), "a subtraction would say -480");
        assertNotEquals(before.countingSince(), after.countingSince(),
                "so the client sees the counts started afresh and does not subtract");
        assertEquals(Instant.ofEpochMilli(3_000), after.countingSince());
    }

    @Test
    void anOversizedEventIsSkippedAndTheNextOneStillRead() {
        var tap = new ApiActivity.UsageTap(true);
        tap.accept(bytes("data: {\"usage\":{\"input_tokens\":" + "9".repeat(ApiActivity.UsageTap.MAX_LINE)));
        tap.accept(bytes("}}\ndata: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":5}}\n"));

        var usage = tap.finish();

        assertEquals(0, usage.input);
        assertEquals(5, usage.output);
    }

    @Test
    void aResponseWithoutUsageReportsNone() {
        var tap = new ApiActivity.UsageTap(true);
        tap.accept(bytes("event: ping\ndata: {\"type\":\"ping\"}\n\ndata: not json, \"usage\"\n"));
        assertNull(tap.finish());

        var json = new ApiActivity.UsageTap(false);
        json.accept(bytes("{\"usage\":{\"input_tokens\":"));
        json.accept(bytes("4,\"output_tokens\":2}}"));
        assertEquals(4, json.finish().input);
    }

    @Test
    void anOversizedJsonBodyIsNotRead() {
        var json = new ApiActivity.UsageTap(false);
        json.accept(bytes("{\"usage\":{\"input_tokens\":4},\"pad\":\""));
        json.accept(Buffer.buffer(new byte[ApiActivity.UsageTap.MAX_BODY]));
        json.accept(bytes("\"}"));
        assertNull(json.finish());
    }

    @Test
    void whatTheProxyServesIsWhatTheCliReads() {
        var now = new AtomicLong(5_000);
        var activity = new ApiActivity(now::get);
        var call = activity.begin("w");
        call.respond(200, "application/json", null);
        call.accept(bytes("{\"usage\":{\"input_tokens\":1,\"output_tokens\":2,"
                + "\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":4}}"));
        now.set(6_000);
        call.end();
        activity.begin("w");

        var read = ProxyActivity.parse(activity.snapshot(Set.of("w")).toJson());

        assertEquals(new ProxyActivity.Instance(Instant.ofEpochMilli(5_000), 2, 1, Instant.ofEpochMilli(6_000),
                Instant.ofEpochMilli(6_000), 1, 2, 3, 4), read.of("w"));
        assertEquals(ProxyActivity.Instance.NONE, read.of("never-called"));
    }

    private static Buffer bytes(String s) {
        return Buffer.buffer(s);
    }
}
