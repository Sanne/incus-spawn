package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Parsing of {@code --account <namespace>=<account>}. */
class AccountSelectionParseTest {

    @Test
    void parsesRepeatedFlags() {
        assertEquals(Map.of("claude", "work", "github", "bot"),
                AccountSelection.parse(List.of("claude=work", "github=bot")));
    }

    @Test
    void parsesCommaSeparatedValues() {
        assertEquals(Map.of("claude", "work", "github", "bot"),
                AccountSelection.parse(List.of("claude=work,github=bot")));
    }

    @Test
    void toleratesSurroundingWhitespace() {
        assertEquals(Map.of("claude", "work"),
                AccountSelection.parse(List.of("  claude = work  ")));
    }

    @Test
    void nullAndEmptyAreEmptySelections() {
        assertTrue(AccountSelection.parse(null).isEmpty());
        assertTrue(AccountSelection.parse(List.of()).isEmpty());
        assertTrue(AccountSelection.parse(List.of("", "  ")).isEmpty());
    }

    /**
     * A bare account name is rejected rather than being spread over every namespace that
     * happens to have one: adding a credential namespace later would silently widen the
     * meaning of a command someone already wrote down.
     */
    @Test
    void bareNameIsRejected() {
        var e = assertThrows(AccountSelection.InvalidSelectionException.class,
                () -> AccountSelection.parse(List.of("acme")));
        assertTrue(e.getMessage().contains("<namespace>=<account>"), e.getMessage());
    }

    @Test
    void missingSideIsRejected() {
        assertThrows(AccountSelection.InvalidSelectionException.class,
                () -> AccountSelection.parse(List.of("=work")));
        assertThrows(AccountSelection.InvalidSelectionException.class,
                () -> AccountSelection.parse(List.of("claude=")));
    }

    /**
     * Whitespace-only sides must be rejected too. Validating raw offsets instead of the
     * stripped halves let these through, whereupon they stripped to empty and quietly resolved
     * to the configured default -- the user's selection discarded with no message.
     */
    @Test
    void whitespaceOnlySideIsRejected() {
        assertThrows(AccountSelection.InvalidSelectionException.class,
                () -> AccountSelection.parse(List.of("claude=   ")));
        assertThrows(AccountSelection.InvalidSelectionException.class,
                () -> AccountSelection.parse(List.of("   =work")));
    }

    @Test
    void repeatingANamespaceWithTheSameValueIsFine() {
        assertEquals(Map.of("claude", "work"),
                AccountSelection.parse(List.of("claude=work", "claude=work")));
    }

    @Test
    void contradictingYourselfIsAnError() {
        var e = assertThrows(AccountSelection.InvalidSelectionException.class,
                () -> AccountSelection.parse(List.of("claude=work", "claude=personal")));
        assertTrue(e.getMessage().contains("claude"), e.getMessage());
    }

    @Test
    void describeRendersForHumans() {
        assertEquals("(defaults)", AccountSelection.describe(Map.of()));
        assertEquals("claude=work", AccountSelection.describe(Map.of("claude", "work")));
    }
}
