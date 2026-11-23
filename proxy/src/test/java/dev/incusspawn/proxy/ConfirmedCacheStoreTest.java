package dev.incusspawn.proxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The files of the confirmed cache (#2518): one immutable file per ETag, evicted least recently served first. */
class ConfirmedCacheStoreTest {

    @TempDir
    Path home;
    String origHome;

    @BeforeEach
    void inHome() {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", origHome);
    }

    private static ConfirmedCacheStore.Copy store(Path object, String etag, String bytes) throws Exception {
        var temp = ConfirmedCacheStore.newTemp(object);
        Files.writeString(temp, bytes);
        return ConfirmedCacheStore.commit(object, temp, etag, bytes.length(), List.of(Map.entry("Content-Type", "x/y")));
    }

    /**
     * A serve upstream confirmed for one ETag reads that ETag's own file: a refill landing between
     * the 304 and the read never hands it the new version's bytes, which would splice two blobs.
     */
    @Test
    void aRefillNeverChangesTheBytesOfACopyAlreadyConfirmed() throws Exception {
        var object = ConfirmedCacheStore.objectDir("h.example.com", "/blob", null);
        var first = store(object, "\"v1\"", "version one");

        var second = store(object, "\"v2\"", "version two!");

        assertEquals("version one", Files.readString(first.body()));
        assertEquals("version two!", Files.readString(ConfirmedCacheStore.current(object).body()));
        assertEquals("\"v2\"", ConfirmedCacheStore.current(object).etag());
        assertNotEquals(first.body(), second.body());
    }

    /**
     * A superseded copy is kept for its window from the moment it was superseded, whenever it was
     * last served, and removed by a later commit once that window has passed.
     */
    @Test
    void aSupersededCopyIsKeptForItsWindowFromWhenItWasSuperseded() throws Exception {
        var object = ConfirmedCacheStore.objectDir("h.example.com", "/blob", null);
        var first = store(object, "\"v1\"", "version one");
        // Last served an hour ago: that alone must not remove it
        Files.setLastModifiedTime(first.body(), FileTime.from(Instant.now().minusSeconds(3600)));

        store(object, "\"v2\"", "version two");
        assertTrue(Files.exists(first.body()), "removed at once, though a 304 for it may just have come");

        var marker = first.body().resolveSibling(first.body().getFileName() + ".superseded");
        Files.setLastModifiedTime(marker, FileTime.from(Instant.now().minusSeconds(3600)));
        store(object, "\"v3\"", "version three");

        assertFalse(Files.exists(first.body()));
        assertFalse(Files.exists(marker));
    }

    /**
     * An evicted object leaves no directory behind, its {@code current} and marks included, so the
     * directories stay as few as the copies and eviction does not walk one per key ever stored.
     */
    @Test
    void anEvictedObjectLeavesNoDirectoryBehind() throws Exception {
        var gone = ConfirmedCacheStore.objectDir("h.example.com", "/gone", null);
        var old = store(gone, "\"a\"", "x".repeat(100));
        store(gone, "\"b\"", "z".repeat(100));
        Files.setLastModifiedTime(ConfirmedCacheStore.current(gone).body(), FileTime.from(Instant.now().minusSeconds(60)));
        Files.setLastModifiedTime(old.body(), FileTime.from(Instant.now().minusSeconds(60)));
        var kept = ConfirmedCacheStore.objectDir("h.example.com", "/kept", null);
        store(kept, "\"c\"", "y".repeat(100));

        ConfirmedCacheStore.evict(150);

        assertFalse(Files.exists(gone), "the evicted object's directory is still there");
        assertTrue(Files.exists(kept));
        assertNotNull(ConfirmedCacheStore.current(kept));
    }

    /** A directory a fill is writing into is never removed, though it holds no copy yet. */
    @Test
    void anObjectBeingFilledKeepsItsDirectory() throws Exception {
        var object = ConfirmedCacheStore.objectDir("h.example.com", "/filling", null);
        var temp = ConfirmedCacheStore.newTemp(object);

        ConfirmedCacheStore.evict(0);

        assertTrue(Files.exists(temp));
    }

    @Test
    void theLeastRecentlyServedCopiesAreEvictedFirst() throws Exception {
        var old = store(ConfirmedCacheStore.objectDir("h.example.com", "/old", null), "\"a\"", "x".repeat(100));
        var used = store(ConfirmedCacheStore.objectDir("h.example.com", "/used", null), "\"b\"", "y".repeat(100));
        Files.setLastModifiedTime(old.body(), FileTime.from(Instant.now().minusSeconds(60)));
        Files.setLastModifiedTime(used.body(), FileTime.from(Instant.now().minusSeconds(120)));
        ConfirmedCacheStore.touch(used);

        ConfirmedCacheStore.evict(150);

        assertFalse(Files.exists(old.body()));
        assertTrue(Files.exists(used.body()));
        assertNull(ConfirmedCacheStore.current(ConfirmedCacheStore.objectDir("h.example.com", "/old", null)));
    }

    /**
     * A signed link's own parameters are not part of the key, so every link to a blob finds its copy;
     * any other parameter is, so {@code ?comp=metadata} on the same path is another object.
     */
    @Test
    void aKeyLeavesOutOnlyTheParametersThatSignALink() {
        var blob = ConfirmedCacheStore.objectDir("h.example.com", "/blob", null);
        assertEquals(blob, ConfirmedCacheStore.objectDir("h.example.com", "/blob", "sv=2025&se=x&sp=r&sig=abc"));
        assertEquals(blob, ConfirmedCacheStore.objectDir("h.example.com", "/blob", "X-Amz-Signature=1&X-Amz-Date=2"));
        assertNotEquals(blob, ConfirmedCacheStore.objectDir("h.example.com", "/blob", "comp=metadata&sig=abc"));
        assertEquals("a=1&b=2", ConfirmedCacheStore.unsigned("b=2&sig=x&a=1"));
    }

    @Test
    void aKeyIsTheHostAndPathWithoutTheQuery() {
        assertEquals(ConfirmedCacheStore.objectDir("h.example.com", "/blob", null), ConfirmedCacheStore.objectDir("h.example.com", "/blob", null));
        assertNotEquals(ConfirmedCacheStore.objectDir("h.example.com", "/blob", null), ConfirmedCacheStore.objectDir("g.example.com", "/blob", null));
    }
}
