package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ListCommandSearchTest {

    @Test
    void emptyQueryMatchesEverything() {
        assertTrue(ListCommand.matchesSearch("", "foo", "bar"));
        assertTrue(ListCommand.matchesSearch(null, "foo"));
        assertTrue(ListCommand.matchesSearch(""));
    }

    @Test
    void caseInsensitiveMatch() {
        assertTrue(ListCommand.matchesSearch("java", "tpl-java"));
        assertTrue(ListCommand.matchesSearch("JAVA", "tpl-java"));
        assertTrue(ListCommand.matchesSearch("Java", "tpl-java"));
        assertTrue(ListCommand.matchesSearch("java", "TPL-JAVA"));
    }

    @Test
    void substringMatch() {
        assertTrue(ListCommand.matchesSearch("dev", "tpl-dev"));
        assertTrue(ListCommand.matchesSearch("pl-d", "tpl-dev"));
        assertTrue(ListCommand.matchesSearch("tpl-dev", "tpl-dev"));
    }

    @Test
    void matchesAnyField() {
        assertTrue(ListCommand.matchesSearch("quarkus", "my-instance", "tpl-java", "Quarkus dev"));
        assertTrue(ListCommand.matchesSearch("192", "my-instance", "10.0.0.1", "192.168.1.1"));
    }

    @Test
    void noMatchReturnsFalse() {
        assertFalse(ListCommand.matchesSearch("python", "tpl-java", "Java development"));
        assertFalse(ListCommand.matchesSearch("xyz", "abc", "def"));
    }

    @Test
    void nullFieldsHandledGracefully() {
        assertTrue(ListCommand.matchesSearch("foo", null, "foobar", null));
        assertFalse(ListCommand.matchesSearch("foo", null, null));
        assertTrue(ListCommand.matchesSearch("", (String[]) null));
        assertFalse(ListCommand.matchesSearch("foo", (String[]) null));
    }

    @Test
    void noFieldsWithNonEmptyQuery() {
        assertFalse(ListCommand.matchesSearch("foo"));
    }
}
