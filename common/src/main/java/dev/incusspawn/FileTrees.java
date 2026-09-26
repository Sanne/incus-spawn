package dev.incusspawn;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Recursive file-tree operations. */
public final class FileTrees {

    private FileTrees() {}

    /**
     * Delete a directory tree. Symlinks are removed, never followed, so nothing
     * outside the tree is touched. A missing tree is not an error. An entry that
     * cannot be read or deleted does not stop the rest from being deleted; the
     * first such failure is thrown at the end.
     */
    public static void delete(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            // The link goes, whatever it points to stays
            Files.deleteIfExists(root);
            return;
        }
        if (!Files.isDirectory(root)) return;
        var failures = new ArrayList<IOException>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                deleteOrRecord(file, failures);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                failures.add(exc);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) failures.add(exc);
                deleteOrRecord(dir, failures);
                return FileVisitResult.CONTINUE;
            }
        });
        if (!failures.isEmpty()) throw failures.getFirst();
    }

    private static void deleteOrRecord(Path path, List<IOException> failures) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            failures.add(e);
        }
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
