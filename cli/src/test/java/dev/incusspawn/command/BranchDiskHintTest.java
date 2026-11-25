package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #2563: {@code isx branch} names {@code isx overview} as a note once the pool reaches the hint
 * level. With {@code --no-start} it uses the pool read its space check already made; after a
 * shell it reads the pool again. The listing it counts is read only when the hint shows.
 */
@ExtendWith(IsolatedHome.class)
class BranchDiskHintTest {

    private static final long GIB = 1024L * 1024 * 1024;
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");

    static final class Command extends BranchCommand {
        Command() {}
        boolean terminal = true;
        @Override boolean interactive() { return terminal; }
        @Override Instant now() { return NOW; }
    }

    private static FakeIncusDaemon daemon(long usedBytes) {
        return new FakeIncusDaemon()
                .container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE))
                .container("proj-tpl", Map.of(Metadata.TYPE, Metadata.TYPE_PROJECT))
                .instance("busy", "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE))
                .container("idle", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE))
                // A kept failed build is not an instance a person branched
                .container("tpl-dev-failed-build", Map.of(Metadata.TYPE, Metadata.TYPE_FAILED_BUILD))
                .poolSpace("default", usedBytes, 60 * GIB);
    }

    private static BranchCommand.Created preflight(FakeIncusDaemon daemon) {
        var def = new ImageDef();
        def.setName("tpl-dev");
        var request = new BranchFlow.Request("tpl-dev", "dev-1", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());
        return new BranchCommand.Created("tpl-dev", BranchFlow.preflight(daemon.client(), request, Map.of("tpl-dev", def)), null, null);
    }

    private static long resourceReads(FakeIncusDaemon daemon) {
        return daemon.requests().stream().filter(r -> r.endsWith("/resources")).count();
    }

    @Test
    void withNoStartItNotesThePoolAsPreflightReadItOnceAndStampsIt() {
        // 87% of 60 GiB, 7.5 GiB free
        var daemon = daemon(52 * GIB + GIB / 2);
        var created = preflight(daemon);
        var command = new Command();
        var notes = RecordingOutput.during(() -> command.diskHint(created, daemon.client(), false)).events(RecordingOutput.Kind.NOTE);
        assertEquals(2, notes.size(), notes.toString());
        assertEquals("The storage pool is 87% full (7.5 GiB free), with 2 instances, 1 of them stopped.", notes.get(0).msg());
        assertEquals(1, resourceReads(daemon), "the pool read milliseconds earlier is not repeated");
        assertTrue(Files.exists(Environment.diskHintFile()));

        // Within the day it shows nothing, and reads no listing
        var before = daemon.requests().size();
        var again = RecordingOutput.during(() -> command.diskHint(created, daemon.client(), false)).events(RecordingOutput.Kind.NOTE);
        assertEquals(List.of(), again);
        assertEquals(before, daemon.requests().size(), () -> String.join("\n", daemon.requests()));
    }

    @Test
    void belowTheLevelOrWithoutATerminalItReadsNothingMore() {
        var low = daemon(48 * GIB);
        var created = preflight(low);
        var before = low.requests().size();
        assertEquals(List.of(), RecordingOutput.during(() -> new Command().diskHint(created, low.client(), false))
                .events(RecordingOutput.Kind.NOTE));
        assertEquals(before, low.requests().size());

        var short_ = daemon(52 * GIB);
        var atHint = preflight(short_);
        var command = new Command();
        command.terminal = false;
        before = short_.requests().size();
        assertEquals(List.of(), RecordingOutput.during(() -> command.diskHint(atHint, short_.client(), true))
                .events(RecordingOutput.Kind.NOTE));
        assertEquals(before, short_.requests().size());
        assertFalse(Files.exists(Environment.diskHintFile()));
    }

    @Test
    void afterTheShellItReadsThePoolAgainThoughPreflightFoundRoom() {
        // 80% when the branch was made: below the hint
        var daemon = daemon(48 * GIB);
        var created = preflight(daemon);
        // The shell ran for hours, and the pool filled meanwhile
        daemon.poolSpace("default", 56 * GIB, 60 * GIB);
        var notes = RecordingOutput.during(() -> new Command().diskHint(created, daemon.client(), true)).events(RecordingOutput.Kind.NOTE);
        assertEquals(2, notes.size(), notes.toString());
        assertTrue(notes.getFirst().msg().startsWith("The storage pool is 93% full (4.0 GiB free)"), notes.toString());
        assertEquals(2, resourceReads(daemon));
    }

    @Test
    void afterTheShellAFailedReadShowsNothingRatherThanPreflightsFigures() {
        var daemon = daemon(52 * GIB + GIB / 2);
        var created = preflight(daemon);
        daemon.noPoolSpace("default");
        assertEquals(List.of(), RecordingOutput.during(() -> new Command().diskHint(created, daemon.client(), true))
                .events(RecordingOutput.Kind.NOTE));
        assertFalse(Files.exists(Environment.diskHintFile()), "nothing shown, so the next day is not quiet");
    }

    @Test
    void afterTheShellItDoesNotReadThePoolWhileQuietAtTheTopLevel() {
        var daemon = daemon(48 * GIB);
        var created = preflight(daemon);
        dev.incusspawn.incus.DiskHint.shown(Environment.diskHintFile(),
                dev.incusspawn.incus.IncusClient.PoolUsage.Level.EXHAUSTED, NOW.minusSeconds(60));
        var before = daemon.requests().size();
        RecordingOutput.during(() -> new Command().diskHint(created, daemon.client(), true));
        assertEquals(before, daemon.requests().size());
    }

    @Test
    void diskHintFalseInConfigTurnsItOff() throws Exception {
        IsolatedHome.seed("disk-hint: false\n");
        var daemon = daemon(52 * GIB);
        var created = preflight(daemon);
        var before = daemon.requests().size();
        assertEquals(List.of(), RecordingOutput.during(() -> new Command().diskHint(created, daemon.client(), true))
                .events(RecordingOutput.Kind.NOTE));
        assertEquals(before, daemon.requests().size(), "turned off, it does not read the pool after the shell");
    }
}
