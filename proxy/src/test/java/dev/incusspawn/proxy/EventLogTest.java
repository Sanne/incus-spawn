package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class EventLogTest {

    @TempDir
    Path dir;

    @Test
    void anEndedModelCallIsOneLineWithItsTokensAndNoContent() throws Exception {
        var file = dir.resolve("events/events.jsonl");
        var now = new AtomicLong(1_000);
        var activity = new ApiActivity(now::get);
        try (var log = new EventLog(file)) {
            activity.recordTo(log);
            var call = activity.begin("w", "2026-01-01T00:00:00Z", 0).to("api.anthropic.com");
            call.respond(200, "text/event-stream", null);
            call.accept(Buffer.buffer("data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-x\","
                    + "\"content\":[{\"text\":\"SECRET\"}],\"usage\":{\"input_tokens\":7,\"output_tokens\":1}}}\n"
                    + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":9}}\n"));
            now.set(1_250);
            call.end();
            call.end();
            log.flush();
        }
        var lines = Files.readAllLines(file);
        assertEquals(1, lines.size(), "ended once, logged once");
        var line = Jjq.parseForeign(lines.get(0).getBytes(), "line");
        assertEquals("model", line.path("kind").asText());
        assertEquals("w", line.path("instance").asText());
        assertEquals("2026-01-01T00:00:00Z", line.path("incarnation").asText());
        assertEquals("api.anthropic.com", line.path("domain").asText());
        assertEquals("claude-x", line.path("model").asText());
        assertEquals(200, line.path("status").asLong(0));
        assertEquals(250, line.path("ms").asLong(0));
        assertEquals(7, line.path("input_tokens").asLong(0));
        assertEquals(9, line.path("output_tokens").asLong(0));
        assertFalse(lines.get(0).contains("SECRET"), "no response content");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void aFailedCallIsLoggedWithItsStatusAndNoModel() throws Exception {
        var file = dir.resolve("events.jsonl");
        var activity = new ApiActivity();
        try (var log = new EventLog(file)) {
            activity.recordTo(log);
            var call = activity.begin("w", "", 0).to("api.anthropic.com");
            call.respond(529, "application/json", null);
            call.end();
            log.flush();
        }
        var line = Jjq.parseForeign(Files.readAllLines(file).get(0).getBytes(), "line");
        assertEquals(529, line.path("status").asLong(0));
        assertEquals("", line.path("model").asText());
    }

    @Test
    void theFileRollsOverAndKeepsABoundedNumberOfOlderOnes() throws Exception {
        var file = dir.resolve("events.jsonl");
        try (var log = new EventLog(file, 4096)) {
            // Each line is about 250 bytes: enough of them to roll the file more than KEPT times
            for (int i = 0; i < 16 * (EventLog.KEPT + 2); i++) {
                log.modelCall(new EventLog.ModelCall(Instant.EPOCH, "w", "", "d", "m", 200, 1, 1, 1, 1, 1));
                if (i % 50 == 0) log.flush();
            }
            log.flush();
        }
        for (int n = 1; n <= EventLog.KEPT; n++) assertTrue(Files.exists(dir.resolve("events.jsonl." + n)), "kept " + n);
        assertFalse(Files.exists(dir.resolve("events.jsonl." + (EventLog.KEPT + 1))), "older ones are deleted");
        assertTrue(Files.size(file) <= 4096);
    }

    @Test
    void anUnusableDirectoryNeverThrowsAtTheCaller() throws Exception {
        var blocked = dir.resolve("file");
        Files.writeString(blocked, "x");
        try (var log = new EventLog(blocked.resolve("sub/events.jsonl"))) {
            log.modelCall(new EventLog.ModelCall(Instant.EPOCH, "w", "", "d", "m", 200, 1, 1, 1, 1, 1));
            log.flush();
        }
    }

    @Test
    void aLogThatCouldNotWriteWritesAgainOnceItCan() throws Exception {
        var blocked = dir.resolve("blocked");
        Files.writeString(blocked, "x");
        var file = blocked.resolve("events.jsonl");
        try (var log = new EventLog(file)) {
            log.modelCall(new EventLog.ModelCall(Instant.EPOCH, "w", "", "d", "m", 200, 1, 1, 1, 1, 1));
            log.flush();
            Files.delete(blocked);
            log.modelCall(new EventLog.ModelCall(Instant.EPOCH, "w", "", "d", "m", 200, 1, 1, 1, 1, 1));
            log.flush();
        }
        var lines = Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
        assertEquals(2, lines.size(), "the lost line is reported, then the new one is written");
        assertTrue(lines.get(0).contains("\"dropped\"") && lines.get(0).contains("\"lines\":1"));
        assertTrue(lines.get(1).contains("\"model\""));
    }

    @Test
    void nothingIsLoggedUnlessALogIsGiven() {
        var activity = new ApiActivity();
        activity.begin("w", "", 0).end();
    }
}
