package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.SpawnConfig;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What a person wrote inside the directories a reset or a clean removes, which they keep (#2666):
 * {@code isx init} offers the config directory as the place for the templates checkout, so
 * deleting that directory whole deleted work nobody had committed.
 *
 * <p>Two rules, both read from what is there now, so a second reset keeps the same as the first:
 * a configured search path anywhere in those directories, and a Git checkout in the config
 * directory. The Git rule stops at the config directory because isx makes Git repositories of its
 * own in the cache, which a clean is there to delete. The config directory itself is never kept:
 * a reset removes the configuration.
 */
final class KeptPaths {

    private KeptPaths() {}

    /** A path that stays, and what it is, worded to follow "Kept <path>: ". */
    record Kept(Path path, String what) {}

    static final String SEARCH_PATH = "a search path of config.yaml";
    static final String GIT_CHECKOUT = "a Git checkout";

    /** What stays when {@code dirs} are removed, outermost paths only, in the order found. */
    static List<Kept> in(Collection<Path> dirs) {
        var found = new ArrayList<Kept>();
        for (var searchPath : SpawnConfig.load().getSearchPaths()) {
            var path = Path.of(HostResourceSetup.expandHostTilde(searchPath)).toAbsolutePath().normalize();
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && liesIn(path, dirs)) {
                found.add(new Kept(path, SEARCH_PATH));
            }
        }
        var config = Environment.configDir();
        if (dirs.contains(config) && Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS)) {
            gitCheckouts(config).forEach(path -> found.add(new Kept(path, GIT_CHECKOUT)));
        }
        return outermost(found);
    }

    static List<Path> paths(List<Kept> kept) {
        return kept.stream().map(Kept::path).toList();
    }

    /** Strictly inside one of {@code dirs}: a search path that is such a directory does not keep it. */
    private static boolean liesIn(Path path, Collection<Path> dirs) {
        return dirs.stream().anyMatch(dir -> path.startsWith(dir) && !path.equals(dir));
    }

    /** The directories under {@code root} that hold a {@code .git}, a worktree's file included. */
    private static List<Path> gitCheckouts(Path root) {
        var checkouts = new ArrayList<Path>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root) || !Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                        return FileVisitResult.CONTINUE;
                    }
                    checkouts.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // What could not be read is not kept; the backup holds it, or fails before any delete
        }
        return checkouts;
    }

    /** {@code found} without repeats and without a path that lies in another one. */
    private static List<Kept> outermost(List<Kept> found) {
        var result = new ArrayList<Kept>();
        for (var kept : found) {
            var covered = found.stream().anyMatch(other -> !other.path().equals(kept.path())
                    && kept.path().startsWith(other.path()));
            var repeated = result.stream().anyMatch(earlier -> earlier.path().equals(kept.path()));
            if (!covered && !repeated) result.add(kept);
        }
        return result;
    }
}
