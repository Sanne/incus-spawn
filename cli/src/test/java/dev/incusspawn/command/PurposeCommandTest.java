package dev.incusspawn.command;

import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code isx purpose} (#2239): a person's note on one instance, set on a stopped instance, kept out of copies. */
class PurposeCommandTest {

    private static PurposeCommand command(String name, boolean clear, String... text) {
        var command = new PurposeCommand();
        command.name = name;
        command.clear = clear;
        command.text = text.length == 0 ? null : List.of(text);
        return command;
    }

    private static String purposeOf(FakeIncusDaemon daemon, String name) {
        return daemon.client().readInstance(name).metadata().path("config").pathText(Metadata.PURPOSE, "");
    }

    @Test
    void setsAndClearsThePurposeInItsOwnStampWithoutTouchingTheMcpOne() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of(Metadata.MCP_PURPOSE, "#870 implement"));
        RecordingOutput.during(() -> assertEquals(0,
                command("dev", false, "waiting", "on", "review").purpose(daemon.client()).getResultValue()));
        assertEquals("waiting on review", purposeOf(daemon, "dev"));
        assertEquals("#870 implement", daemon.client().readInstance("dev").metadata().path("config")
                .pathText(Metadata.MCP_PURPOSE, ""));
        RecordingOutput.during(() -> assertEquals(0, command("dev", true).purpose(daemon.client()).getResultValue()));
        assertEquals("", purposeOf(daemon, "dev"));
    }

    @Test
    void refusesControlCharactersBlankTextAndAMissingInstance() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of());
        RecordingOutput.during(() -> {
            assertEquals(1, command("dev", false, "a\u001b[2Jb").purpose(daemon.client()).getResultValue());
            assertEquals(1, command("dev", false, "x".repeat(Metadata.PURPOSE_MAX + 1)).purpose(daemon.client()).getResultValue());
            assertEquals(1, command("ghost", false, "x").purpose(daemon.client()).getResultValue());
        });
        assertEquals("", purposeOf(daemon, "dev"));
    }

    @Test
    void aTemplateTakesNoPurpose() {
        var daemon = new FakeIncusDaemon().container("tpl-x", Map.of(Metadata.TYPE, Metadata.TYPE_BASE));
        RecordingOutput.during(() -> assertEquals(1, command("tpl-x", false, "x").purpose(daemon.client()).getResultValue()));
        assertEquals("", purposeOf(daemon, "tpl-x"));
    }

    @Test
    void noCopyInheritsIt() {
        assertTrue(Metadata.isOwnershipKey(Metadata.PURPOSE));
        assertFalse(Metadata.isMcpKey(Metadata.PURPOSE), "a separate key from the one an isx mcp session writes");
    }

    @Test
    void listShowsItInTheTableAndAppendsItToPlainAndJson() {
        var daemon = new FakeIncusDaemon().container("dev", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PURPOSE, "waiting on\nreview"));
        var entry = InstanceListing.collectEntries(daemon.client().listJson()).getFirst();
        assertEquals("waiting on review", ListCommand.purposeCell(entry));
    }
}
