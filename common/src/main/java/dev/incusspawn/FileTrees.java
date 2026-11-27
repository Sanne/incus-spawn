package dev.incusspawn;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
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
        delete(root, List.of());
    }

    /**
     * {@link #delete(Path)}, leaving each of {@code keep} as it is, with everything under it and
     * the directories that lead to it. The paths are compared as given, so they are written the
     * way {@code root} is: absolute and normalized.
     */
    public static void delete(Path root, Collection<Path> keep) throws IOException {
        if (keep.contains(root)) return;
        if (Files.isSymbolicLink(root)) {
            // The link goes, whatever it points to stays
            Files.deleteIfExists(root);
            return;
        }
        if (!Files.isDirectory(root)) return;
        var failures = new ArrayList<IOException>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return keep.contains(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!keep.contains(file)) deleteOrRecord(file, failures);
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
                if (!leadsToAny(dir, keep)) deleteOrRecord(dir, failures);
                return FileVisitResult.CONTINUE;
            }
        });
        if (!failures.isEmpty()) throw failures.getFirst();
    }

    /** Whether one of {@code paths} is {@code dir} or lies under it. */
    public static boolean leadsToAny(Path dir, Collection<Path> paths) {
        for (var path : paths) {
            if (path.startsWith(dir)) return true;
        }
        return false;
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
