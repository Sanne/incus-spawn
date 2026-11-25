package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The TUI's reader of the event log (#2625): a read parses only what the current file gained, and
 * reads the whole log again when a file it read before changed in any other way.
 */
class EventLogReaderIncrementalTest {

    @TempDir
    Path dir;

    private static final String INC = "2026-11-20T08:00:00Z";

    private static String model(String at, long input) {
        return "{\"t\":\"" + at + "\",\"kind\":\"model\",\"instance\":\"box\",\"incarnation\":\"" + INC
                + "\",\"input_tokens\":" + input + ",\"output_tokens\":1}\n";
    }

    private static String start(String at) {
        return "{\"t\":\"" + at + "\",\"kind\":\"task\",\"op\":\"start\",\"instance\":\"box\",\"incarnation\":\"\","
                + "\"task_id\":\"t1-ab\",\"run\":1,\"task_kind\":\"agent\",\"activity\":\"implement\"}\n";
    }

    private Path file() {
        return dir.resolve("events.jsonl");
    }

    private void append(String text) throws Exception {
        Files.writeString(file(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void assertSame(EventLogReader.Log expected, EventLogReader.Log actual) {
        assertEquals(expected.byIncarnation(), actual.byIncarnation());
        assertEquals(expected.byName(), actual.byName());
        assertEquals(expected.since(), actual.since());
        assertEquals(expected.droppedLines(), actual.droppedLines());
        assertEquals(expected.unreadableLines(), actual.unreadableLines());
        assertEquals(expected.runs("box", INC), actual.runs("box", INC));
    }

    @Test
    void aReadAfterTheLogGrewParsesOnlyTheNewLinesAndAgreesWithAWholeRead() throws Exception {
        append(start("2026-11-20T10:00:00Z") + model("2026-11-20T10:01:00Z", 100));
        var reader = new EventLogReader.Incremental(file());
        assertEquals(100, reader.read().of("box", INC).inputTokens());

        append(model("2026-11-20T10:02:00Z", 20));
        var again = reader.read();
        assertEquals(120, again.of("box", INC).inputTokens());
        assertSame(EventLogReader.read(file()), again);
        assertEquals(120, again.runs("box", INC).runs().getFirst().usage().inputTokens(),
                "a run's calls are booked once however often the log is read");

        // A line already read is not read again: one changed in place, at the same size, still counts as it was
        try (var raw = new RandomAccessFile(file().toFile(), "rw")) {
            var text = Files.readString(file());
            raw.seek(text.indexOf("\"input_tokens\":100") + "\"input_tokens\":".length());
            raw.write("999".getBytes(StandardCharsets.UTF_8));
        }
        append(model("2026-11-20T10:03:00Z", 1));
        assertEquals(121, reader.read().of("box", INC).inputTokens());
    }

    @Test
    void aRolloverReadsTheWholeLogAgain() throws Exception {
        append(model("2026-11-20T10:01:00Z", 100));
        var reader = new EventLogReader.Incremental(file());
        reader.read();
        Files.move(file(), dir.resolve("events.jsonl.1"));
        append(model("2026-11-20T10:02:00Z", 20));
        var after = reader.read();
        assertEquals(120, after.of("box", INC).inputTokens());
        assertSame(EventLogReader.read(file()), after);
    }

    @Test
    void aTruncatedOrReplacedFileIsReadWhole() throws Exception {
        append(model("2026-11-20T10:01:00Z", 100) + model("2026-11-20T10:02:00Z", 20));
        var reader = new EventLogReader.Incremental(file());
        reader.read();
        Files.writeString(file(), model("2026-11-20T10:03:00Z", 7));
        assertEquals(7, reader.read().of("box", INC).inputTokens(), "the same file, shorter");

        Files.delete(file());
        append(model("2026-11-20T10:04:00Z", 3) + model("2026-11-20T10:05:00Z", 4) + model("2026-11-20T10:06:00Z", 5));
        assertEquals(12, reader.read().of("box", INC).inputTokens(), "another file at the path, longer");

        // What a file made again on a reused inode looks like: the same file key, longer, other lines
        Files.writeString(file(), model("2026-11-20T10:07:00Z", 1) + model("2026-11-20T10:08:00Z", 2)
                + model("2026-11-20T10:09:00Z", 30) + model("2026-11-20T10:10:00Z", 40));
        assertEquals(73, reader.read().of("box", INC).inputTokens(), "the same file key, written anew");
    }

    @Test
    void anOlderFileThatChangedIsReadAgain() throws Exception {
        Files.writeString(dir.resolve("events.jsonl.1"), model("2026-11-20T09:00:00Z", 1000));
        append(model("2026-11-20T10:01:00Z", 100));
        var reader = new EventLogReader.Incremental(file());
        assertEquals(1100, reader.read().of("box", INC).inputTokens());
        Files.writeString(dir.resolve("events.jsonl.1"), model("2026-11-20T09:00:00Z", 2000));
        assertEquals(2100, reader.read().of("box", INC).inputTokens());
    }

    @Test
    void aLastLineNotYetEndedIsLeftForTheNextRead() throws Exception {
        var line = model("2026-11-20T10:02:00Z", 20);
        append(model("2026-11-20T10:01:00Z", 100) + line.substring(0, 30));
        var reader = new EventLogReader.Incremental(file());
        var torn = reader.read();
        assertEquals(100, torn.of("box", INC).inputTokens());
        assertEquals(0, torn.unreadableLines(), "the proxy may still be writing it");

        append(line.substring(30));
        var whole = reader.read();
        assertEquals(120, whole.of("box", INC).inputTokens());
        assertEquals(0, whole.unreadableLines());
    }
}
