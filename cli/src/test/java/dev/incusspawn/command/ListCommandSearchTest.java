package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ListCommandSearchTest {

    @Test
    void emptyQueryMatchesEverything() {
        assertTrue(Tui.matchesSearch("", "foo", "bar"));
        assertTrue(Tui.matchesSearch(null, "foo"));
        assertTrue(Tui.matchesSearch(""));
    }

    @Test
    void caseInsensitiveMatch() {
        assertTrue(Tui.matchesSearch("java", "tpl-java"));
        assertTrue(Tui.matchesSearch("JAVA", "tpl-java"));
        assertTrue(Tui.matchesSearch("Java", "tpl-java"));
        assertTrue(Tui.matchesSearch("java", "TPL-JAVA"));
    }

    @Test
    void substringMatch() {
        assertTrue(Tui.matchesSearch("dev", "tpl-dev"));
        assertTrue(Tui.matchesSearch("pl-d", "tpl-dev"));
        assertTrue(Tui.matchesSearch("tpl-dev", "tpl-dev"));
    }

    @Test
    void matchesAnyField() {
        assertTrue(Tui.matchesSearch("quarkus", "my-instance", "tpl-java", "Quarkus dev"));
        assertTrue(Tui.matchesSearch("192", "my-instance", "10.0.0.1", "192.168.1.1"));
    }

    @Test
    void noMatchReturnsFalse() {
        assertFalse(Tui.matchesSearch("python", "tpl-java", "Java development"));
        assertFalse(Tui.matchesSearch("xyz", "abc", "def"));
    }

    @Test
    void nullFieldsHandledGracefully() {
        assertTrue(Tui.matchesSearch("foo", null, "foobar", null));
        assertFalse(Tui.matchesSearch("foo", null, null));
        assertTrue(Tui.matchesSearch("", (String[]) null));
        assertFalse(Tui.matchesSearch("foo", (String[]) null));
    }

    @Test
    void noFieldsWithNonEmptyQuery() {
        assertFalse(Tui.matchesSearch("foo"));
    }
}
