package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.BuildOutput;

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
 * <p>Two rules: a configured search path anywhere in those directories, and a Git checkout in the
 * config directory. A checkout is found on disk, so a second reset keeps it too; a search path
 * that is not one is named only by the config.yaml the first reset deleted, so a later reset
 * deletes it, into its archive unless {@code --no-backup} (design/init.md).
 *
 * <p>The Git rule stops at the config directory because isx makes Git repositories of its own in
 * the cache, which a clean is there to delete. The config directory itself is never kept: a reset
 * removes the configuration.
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
        for (var searchPath : searchPaths()) {
            var path = Path.of(HostResourceSetup.expandHostTilde(searchPath)).toAbsolutePath().normalize();
            var inside = spelledIn(path, dirs);
            if (inside != null) found.add(new Kept(inside, SEARCH_PATH));
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

    /**
     * Whether config.yaml could be read for its search paths; when not, only the Git rule holds,
     * which the caller says before it asks.
     */
    static boolean searchPathsKnown() {
        try {
            SpawnConfig.loadStrict();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The warning for a plan that removes the config directory with its search paths not known. */
    static void warnIfSearchPathsUnknown(Collection<Path> dirs) {
        if (!dirs.contains(Environment.configDir()) || searchPathsKnown()) return;
        BuildOutput.warn("config.yaml could not be read, so its search paths are not known.",
                "Only Git checkouts are kept; a search path that is not one is deleted.");
    }

    private static List<String> searchPaths() {
        try {
            return SpawnConfig.loadStrict().getSearchPaths();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * {@code path} as it is spelled under the one of {@code dirs} it lies strictly inside, or null.
     * Real paths are compared, so a search path written through a symlink (/home for /var/home)
     * is still found, and the answer is spelled the way the delete walks the directory. A search
     * path that is such a directory does not keep it.
     */
    private static Path spelledIn(Path path, Collection<Path> dirs) {
        try {
            var real = path.toRealPath();
            for (var dir : dirs) {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
                var realDir = dir.toRealPath();
                if (real.startsWith(realDir) && !real.equals(realDir)) return dir.resolve(realDir.relativize(real));
            }
        } catch (IOException e) {
            // A search path that is not there, or cannot be resolved, is not kept
        }
        return null;
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
