package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

/** The host's copy of a Google sign-in (#2443): what a session holds, on the shared store's mechanics. */
class GoogleSessionStoreTest {

    @TempDir
    Path root;

    private GoogleSessionStore store() {
        return new GoogleSessionStore(() -> root);
    }

    private static GoogleSessionStore.Tokens tokens(String tag, long expiry) {
        return new GoogleSessionStore.Tokens("ya29.real-" + tag, "1//real-" + tag, expiry, tag + "@example.invalid");
    }

    @Test
    void aSessionIsReadBackWithItsExpiryAndEmailAndKeptFromOtherUsers() throws Exception {
        var store = store();
        var created = store.create("home", tokens("a", 1_800_000_000L));

        assertEquals(created, store.read("home"));
        assertEquals(1_800_000_000L, store.read("home").tokens().expiry());
        assertEquals("a@example.invalid", store.read("home").tokens().email());
        var file = store.file("home");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        assertNull(store.read("other"));
    }

    @Test
    void aRefreshKeepsTheRefreshTokenAndEmailGoogleDidNotRepeat() {
        var current = tokens("a", 100);
        var refreshed = current.refreshed("ya29.real-b", "", 200, "");
        assertEquals(new GoogleSessionStore.Tokens("ya29.real-b", "1//real-a", 200, "a@example.invalid"), refreshed);
        var rotated = current.refreshed("ya29.real-c", "1//real-c", 300, "c@example.invalid");
        assertEquals(new GoogleSessionStore.Tokens("ya29.real-c", "1//real-c", 300, "c@example.invalid"), rotated);
    }

    @Test
    void aRefreshCommitsOnlyToTheSessionAndGenerationItStartedFrom() {
        var store = store();
        var session = store.create("home", tokens("a", 100));

        assertTrue(store.beginRefresh(session));
        assertEquals(SessionStore.Commit.COMMITTED, store.commit(session, tokens("b", 200)));
        assertEquals(session.generation() + 1, store.read("home").generation());
        assertEquals(SessionStore.Commit.SUPERSEDED, store.commit(session, tokens("c", 300)));
        assertEquals(tokens("b", 200), store.read("home").tokens());
    }

    @Test
    void aSignInAgainIsANewSessionAndTokensNeverPrint() {
        var store = store();
        var old = store.create("home", tokens("a", 100));
        var renewed = store.create("home", tokens("b", 200));
        assertNotEquals(old.sessionId(), renewed.sessionId());
        assertFalse(tokens("a", 100).toString().contains("real-a"));
    }
}
