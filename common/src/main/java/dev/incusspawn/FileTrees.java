package dev.incusspawn;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Recursive file-tree operations. */
public final class FileTrees {

    private FileTrees() {}

    /**
     * Delete a directory tree. Symlinks are removed, never followed, so nothing
     * outside the tree is touched. A missing tree is not an error.
     */
    public static void delete(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            // The link goes, whatever it points to stays
            Files.deleteIfExists(root);
            return;
        }
        if (!Files.isDirectory(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** {@link #delete}, for disposable directories where a leftover is harmless. */
    public static void deleteQuietly(Path root) {
        try {
            delete(root);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
