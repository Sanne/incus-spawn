package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGptSessionStoreTest {

    @TempDir
    Path root;

    private ChatGptSessionStore store() {
        return new ChatGptSessionStore(() -> root);
    }

    private static ChatGptSessionStore.Tokens tokens(String tag) {
        return new ChatGptSessionStore.Tokens("id-" + tag, "access-" + tag, "rt-" + tag, "acct");
    }

    @Test
    void aSessionIsReadBackAndKeptFromOtherUsers() throws Exception {
        var store = store();
        var created = store.create("sso", tokens("a"));

        assertEquals(created, store.read("sso"));
        var file = store.file("sso");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        assertNull(store.read("other"));
    }

    @Test
    void aRefreshCommitsOnlyToTheSessionAndGenerationItStartedFrom() {
        var store = store();
        var session = store.create("sso", tokens("a"));

        assertTrue(store.beginRefresh(session));
        assertNotNull(store.read("sso").refreshing(), "the attempt is on disk before it is sent");
        assertEquals(ChatGptSessionStore.Commit.COMMITTED, store.commit(session, tokens("b")));
        var after = store.read("sso");
        assertEquals(session.generation() + 1, after.generation());
        assertEquals(tokens("b"), after.tokens());
        assertNull(after.refreshing());

        // The same starting point again: another refresh landed first
        assertEquals(ChatGptSessionStore.Commit.SUPERSEDED, store.commit(session, tokens("c")));
        assertEquals(tokens("b"), store.read("sso").tokens());
    }

    @Test
    void aSignInAgainSupersedesARefreshInFlight() {
        var store = store();
        var old = store.create("sso", tokens("a"));
        var renewed = store.create("sso", tokens("new"));

        assertNotEquals(old.sessionId(), renewed.sessionId());
        assertEquals(ChatGptSessionStore.Commit.SUPERSEDED, store.commit(old, tokens("late")));
        assertEquals(renewed, store.read("sso"));
    }

    @Test
    void aRemovedSessionIsNeverRecreatedByALateRefresh() {
        var store = store();
        var session = store.create("sso", tokens("a"));
        store.remove("sso");

        assertEquals(ChatGptSessionStore.Commit.GONE, store.commit(session, tokens("late")));
        assertNull(store.read("sso"));
        assertTrue(!store.beginRefresh(session));
    }

    @Test
    void anAbandonedRefreshClearsItsRecord() {
        var store = store();
        var session = store.create("sso", tokens("a"));
        store.beginRefresh(session);
        store.abandonRefresh(session);

        assertNull(store.read("sso").refreshing());
        assertEquals(session.generation(), store.read("sso").generation());
    }

    @Test
    void anInterruptedRefreshIsVisibleAfterARestart() {
        var session = store().create("sso", tokens("a"));
        store().beginRefresh(session);

        assertNotNull(store().read("sso").refreshing(), "a new store, as after a proxy restart, still sees it");
    }

    @Test
    void tokensNeverPrint() {
        assertTrue(!tokens("a").toString().contains("rt-a"));
    }
}
