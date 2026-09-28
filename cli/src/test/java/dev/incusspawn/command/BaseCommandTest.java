package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseCommandTest {

    @Test
    void destructiveConfirmationRefusesWithoutATerminal() {
        // Piped stdin, cron, a wrapper script: never a silent yes for something that wipes data.
        var e = assertThrows(BaseCommand.NoTerminalException.class,
                () -> BaseCommand.confirmDestructive("Wipe?", false, "--yes", null));
        assertTrue(e.getMessage().contains("--yes"), e.getMessage());
        assertTrue(BaseCommand.confirmDestructive("Wipe?", true, "--yes", null), "the skip flag still skips the question");
    }

    @Test
    void parseConfirmationAcceptsYesAndNoCaseInsensitively() {
        assertEquals(true, BaseCommand.parseConfirmation(" y ", false));
        assertEquals(true, BaseCommand.parseConfirmation("Y", false));
        assertEquals(false, BaseCommand.parseConfirmation(" n ", true));
        assertEquals(false, BaseCommand.parseConfirmation("N", true));
    }

    @Test
    void parseConfirmationUsesDefaultForBlankResponses() {
        assertEquals(true, BaseCommand.parseConfirmation("", true));
        assertEquals(false, BaseCommand.parseConfirmation("   ", false));
        assertNull(BaseCommand.parseConfirmation(null, true));
        assertNull(BaseCommand.parseConfirmation(null, false));
    }

    @Test
    void parseConfirmationRejectsInvalidResponses() {
        assertNull(BaseCommand.parseConfirmation("maybe", true));
    }
}
