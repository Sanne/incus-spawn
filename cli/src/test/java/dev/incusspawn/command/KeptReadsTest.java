package dev.incusspawn.command;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The last reading of each guest, kept on the host so a stopped instance shows what it held
 * without being started (#2752).
 */
class KeptReadsTest {

    private static final String WORK = WorkState.HEADER + "\nrepo 2 0 1 app\nend\n";
    private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");

    @TempDir
    Path dir;

    private KeptReads kept() {
        return new KeptReads(dir.resolve("reads"));
    }

    @Test
    void aKeptAnswerIsParsedAgainWithTheTimeItWasMade() {
        kept().put("dev", "2026-11-01T00:00:00Z", new GuestRead.Answer(0, WORK, false, AT));

        var read = kept().get("dev", "2026-11-01T00:00:00Z");
        assertEquals(WorkState.State.UNSAVED, read.work().state());
        assertEquals(2, read.work().uncommitted());
        assertEquals(1, read.work().stashes());
        assertEquals(AT, read.at());
    }

    @Test
    void anotherIncarnationOfTheNameDoesNotReadIt() {
        kept().put("dev", "2026-11-01T00:00:00Z", new GuestRead.Answer(0, WORK, false, AT));

        assertNull(kept().get("dev", "2026-11-02T00:00:00Z"), "a new instance of the same name never shows the older one's");
        assertNull(kept().get("other", "2026-11-01T00:00:00Z"));
        assertNull(kept().get("dev", ""), "an instance with no creation time is never read back");
    }

    @Test
    void anAnswerToAnotherScriptOrAnUnreadableFileIsNotReadBack() throws Exception {
        kept().put("dev", "c", new GuestRead.Answer(0, WORK, false, AT));
        var file = dir.resolve("reads").resolve(KeptReads.key("dev", "c") + ".json");
        var json = Files.readString(file);
        assertTrue(json.contains(KeptReads.SCRIPT_SHA));

        Files.writeString(file, json.replace(KeptReads.SCRIPT_SHA, "0".repeat(64)));
        assertNull(kept().get("dev", "c"), "a changed script never parses an older one's answer");
        Files.writeString(file, "{not json");
        assertNull(kept().get("dev", "c"));
    }

    @Test
    void aRenamedInstanceKeepsItsReadingUnderItsNewName() {
        kept().put("dev", "c", new GuestRead.Answer(0, WORK, false, AT));

        kept().renamed("dev", "dev2", "c");

        assertNull(kept().get("dev", "c"));
        assertEquals(AT, kept().get("dev2", "c").at());
        assertEquals(2, kept().get("dev2", "c").work().uncommitted());
    }

    @Test
    void aStaleTemporaryFileIsPrunedAndOneBeingWrittenIsNot() throws Exception {
        var reads = dir.resolve("reads");
        Files.createDirectories(reads);
        var stale = Files.writeString(reads.resolve(".read123.tmp"), "guest text");
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(Instant.now().minus(java.time.Duration.ofDays(1))));
        var fresh = Files.writeString(reads.resolve(".read456.tmp"), "guest text");

        kept().prune(java.util.List.of());

        assertFalse(Files.exists(stale), "a write a crash interrupted");
        assertTrue(Files.exists(fresh), "a write under way");
    }

    @Test
    void aFailedAnswerIsNotKept() {
        kept().put("dev", "c", new GuestRead.Answer(0, WORK, false, AT));
        kept().put("dev", "c", new GuestRead.Answer(137, WorkState.HEADER + "\n", false, AT.plusSeconds(60)));

        assertEquals(AT, kept().get("dev", "c").at(), "the earlier reading stays");
    }

    @Test
    void aCutAnswerStaysUnknown() {
        kept().put("dev", "c", new GuestRead.Answer(0, WorkState.HEADER + "\nrepo 0 0 0 app\n", true, AT));

        assertEquals(WorkState.State.UNKNOWN, kept().get("dev", "c").work().state(), "fails closed, as the live read does");
    }

    @Test
    void theReadBeforeAStopIsKeptAndAFailedOneKeepsTheEarlier() {
        var daemon = new FakeIncusDaemon().instance("dev", "container", "Running", Map.of())
                .execOutput(WorkState.HEADER, WORK);
        kept().beforeStop(daemon.client(), "dev", "c");
        assertEquals(WorkState.State.UNSAVED, kept().get("dev", "c").work().state());

        var silent = new FakeIncusDaemon().instance("dev", "container", "Running", Map.of());
        assertDoesNotThrow(() -> kept().beforeStop(silent.client(), "dev", "c"), "a guest that does not answer is stopped all the same");
        assertEquals(WorkState.State.UNSAVED, kept().get("dev", "c").work().state(), "the earlier reading stays");

        // A read killed at the stop's limit exits nonzero: it never replaces the earlier reading
        var killed = new FakeIncusDaemon().instance("dev", "container", "Running", Map.of())
                .execOutput(WorkState.HEADER, WorkState.HEADER + "\n").execExit(WorkState.HEADER, 137);
        kept().beforeStop(killed.client(), "dev", "c");
        assertEquals(WorkState.State.UNSAVED, kept().get("dev", "c").work().state(), "the earlier reading stays");
    }

    @Test
    void aReadingOlderThanTheLastStartIsSaidToBe() {
        var daemon = new FakeIncusDaemon()
                .instance("dev", "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE))
                .lastUsedAt("dev", "2026-11-21T00:00:00Z");
        var instance = InstanceListing.collectEntries(daemon.client().listJsonConfig()).getFirst();
        var read = new GuestRead.Answer(0, WORK, false, AT).parsed();

        assertTrue(KeptReads.beforeLastStart(read, instance), "it ran after the reading, so the reading may be out of date");
        var after = new GuestRead.Answer(0, WORK, false, Instant.parse("2026-11-22T00:00:00Z")).parsed();
        assertFalse(KeptReads.beforeLastStart(after, instance));
    }
}
