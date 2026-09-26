package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileTreesTest {

    @TempDir
    Path dir;

    @Test
    void deletesTheWholeTree() throws Exception {
        var root = dir.resolve("tree");
        Files.createDirectories(root.resolve("a/b"));
        Files.writeString(root.resolve("a/b/c.jar"), "x");
        Files.writeString(root.resolve("top.txt"), "y");

        FileTrees.delete(root);
        assertFalse(Files.exists(root));
    }

    @Test
    void anUndeletableEntryDoesNotStopTheRest() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
                "root ignores directory permissions");
        var root = dir.resolve("tree");
        var locked = root.resolve("locked");
        Files.createDirectories(locked);
        Files.writeString(locked.resolve("inside.txt"), "x");
        Files.writeString(root.resolve("a.txt"), "a");
        Files.createDirectories(root.resolve("z"));
        Files.writeString(root.resolve("z/b.txt"), "b");
        locked.toFile().setWritable(false);
        try {
            assertThrows(java.io.IOException.class, () -> FileTrees.delete(root));
            assertFalse(Files.exists(root.resolve("a.txt")));
            assertFalse(Files.exists(root.resolve("z")), "everything deletable was deleted");
            assertTrue(Files.exists(locked.resolve("inside.txt")));
        } finally {
            locked.toFile().setWritable(true);
        }
    }

    @Test
    void missingTreeIsFine() throws Exception {
        FileTrees.delete(dir.resolve("absent"));
        FileTrees.deleteQuietly(dir.resolve("absent"));
    }

    @Test
    void symlinksAreRemovedNotFollowed() throws Exception {
        var outside = dir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "keep");
        var root = dir.resolve("tree");
        Files.createDirectories(root);
        Files.createSymbolicLink(root.resolve("link"), outside);

        FileTrees.delete(root);
        assertFalse(Files.exists(root));
        assertTrue(Files.exists(outside.resolve("keep.txt")), "nothing outside the tree is touched");
    }

    @Test
    void aSymlinkedRootIsNotFollowed() throws Exception {
        var outside = dir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "keep");
        var link = dir.resolve("link");
        Files.createSymbolicLink(link, outside);

        FileTrees.delete(link);
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(outside.resolve("keep.txt")));
    }
}
