package dev.incusspawn.command;

import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * What {@code isx reset}, {@code isx clean config} and {@code isx clean all} leave in the config
 * directory, and where they archive the rest (#2666).
 *
 * <p>{@code isx init} offers to clone the templates repository into the config directory, so a
 * person's own, possibly uncommitted, templates can live there. A Git checkout and a search path
 * of {@code config.yaml} inside it are what a person wrote, never what isx made, so they are kept.
 * Everything else goes, and is first written to an archive ({@link ConfigBackup}).
 */
final class ConfigSweep {

    /** A path inside the config directory that is not deleted, and why, in the person's words. */
    record Kept(Path path, String why) {}

    static final String CHECKOUT = "a Git checkout";
    static final String SEARCH_PATH = "a search path in config.yaml";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;
    private final List<Kept> kept;
    private final boolean configUnreadable;

    private ConfigSweep(Path dir, List<Kept> kept, boolean configUnreadable) {
        this.dir = dir;
        this.kept = List.copyOf(kept);
        this.configUnreadable = configUnreadable;
    }

    /** The sweep of the config directory when {@code dirs} deletes it, else null. */
    static ConfigSweep forDirs(List<Path> dirs) throws IOException {
        var dir = dev.incusspawn.Environment.configDir();
        return dirs.contains(dir) && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) ? of(dir) : null;
    }

    /** Where the archive goes unless {@code --no-backup}: the directory the command is run from. */
    static Path backupDir(boolean noBackup) {
        return noBackup ? null : Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    static final String NO_BACKUP_HELP = "Do not archive what is deleted from the configuration directory first";

    /** The sweep of {@code dir}, keeping the search paths the current {@code config.yaml} lists. */
    static ConfigSweep of(Path dir) throws IOException {
        var config = SpawnConfig.load();
        return of(dir, config.getSearchPaths(), config.unreadableReason() != null);
    }

    /**
     * The sweep of {@code dir}: its Git checkouts and the {@code searchPaths} inside it, each
     * strictly inside it and never under another kept path. A checkout is a directory holding a
     * {@code .git} entry, a worktree's file included; symlinks are not followed, so a link to a
     * checkout elsewhere is only a link, which deleting it does not touch.
     */
    static ConfigSweep of(Path dir, List<String> searchPaths, boolean configUnreadable) throws IOException {
        var found = new LinkedHashMap<Path, String>();
        for (var path : searchPaths) {
            var inside = inside(dir, path);
            if (inside != null) found.putIfAbsent(inside, SEARCH_PATH);
        }
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (d.equals(dir)) return FileVisitResult.CONTINUE;
                    if (found.containsKey(d)) return FileVisitResult.SKIP_SUBTREE;
                    if (Files.exists(d.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                        found.put(d, CHECKOUT);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // The archive fails on it, so nothing is deleted
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        // A path under another kept path is kept with it, and named once
        var kept = new ArrayList<Kept>();
        found.forEach((path, why) -> {
            if (found.keySet().stream().noneMatch(other -> !other.equals(path) && path.startsWith(other))) {
                kept.add(new Kept(path, why));
            }
        });
        kept.sort((a, b) -> a.path().compareTo(b.path()));
        return new ConfigSweep(dir, kept, configUnreadable);
    }

    /**
     * {@code searchPath} as a path under {@code dir} when it lies strictly inside it, read as
     * written and on real paths, so a symlinked config directory is matched too; else null.
     */
    private static Path inside(Path dir, String searchPath) {
        Path path;
        try {
            path = Path.of(HostResourceSetup.expandHostTilde(searchPath)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        var base = dir.toAbsolutePath().normalize();
        // As written only when it is there and no link inside dir leads to it, which the sweep deletes
        if (path.startsWith(base) && !path.equals(base) && noLinkBetween(base, path)) {
            return dir.resolve(base.relativize(path));
        }
        try {
            var realBase = base.toRealPath();
            var real = path.toRealPath();
            if (real.startsWith(realBase) && !real.equals(realBase)) return dir.resolve(realBase.relativize(real));
        } catch (IOException e) {
            // Missing: nothing there to keep
        }
        return null;
    }

    /** Whether {@code path}, under {@code base}, exists and is reached from it through no symlink. */
    private static boolean noLinkBetween(Path base, Path path) {
        var at = base;
        for (var part : base.relativize(path)) {
            at = at.resolve(part);
            if (!Files.exists(at, LinkOption.NOFOLLOW_LINKS)) return false;
            if (!at.equals(path) && Files.isSymbolicLink(at)) return false;
        }
        return true;
    }

    Path dir() {
        return dir;
    }

    List<Kept> kept() {
        return kept;
    }

    Set<Path> keep() {
        var paths = new java.util.HashSet<Path>();
        kept.forEach(k -> paths.add(k.path()));
        return paths;
    }

    /** Names each kept path, and says when only checkouts could be kept. */
    void showKept() {
        for (var k : kept) {
            BuildOutput.step("Kept " + k.path() + ": " + k.why());
        }
        if (configUnreadable) {
            BuildOutput.note("config.yaml could not be read, so its search paths are not known."
                    + " Only Git checkouts are kept.");
        }
    }

    /**
     * Says in a plan where the archive goes, or that none is written ({@code archive} null), and
     * that it holds credentials.
     */
    void showArchive(Path archive, boolean dryRun) {
        if (archive == null) {
            BuildOutput.note("What is deleted from " + dir + " is not archived (--no-backup).");
            return;
        }
        BuildOutput.step((dryRun ? "Would archive" : "Before deleting, archive") + " what is deleted from "
                + dir + " to " + archive);
        BuildOutput.warn("The archive holds credentials: API keys, the CA key and SSH keys.",
                "Only you can read it. It is yours to delete once you no longer need it.");
        var tree = workingTree(archive.getParent());
        if (tree != null) {
            BuildOutput.warn("The archive goes into the Git working tree at " + tree + ".",
                    "Do not commit it. Move it out of the tree, or run the command from another directory.");
        }
    }

    /** The Git working tree {@code dir} is in, the nearest directory holding a {@code .git} entry, or null. */
    static Path workingTree(Path dir) {
        for (var at = dir.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
            if (Files.exists(at.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return at;
        }
        return null;
    }

    /**
     * Refuses an {@code archive} that would be written inside one of the {@code deleted}
     * directories and outside every path in {@code keep}, since the sweep would delete it.
     */
    static void requireArchiveKept(Path archive, List<Path> deleted, Set<Path> keep) throws IOException {
        var at = real(archive.getParent());
        if (keep.stream().anyMatch(k -> at.startsWith(real(k)))) return;
        for (var d : deleted) {
            if (at.startsWith(real(d))) {
                throw new IOException("the archive would be written in " + archive.getParent() + ", which this deletes;"
                        + " run it from another directory, or pass --no-backup");
            }
        }
    }

    private static Path real(Path path) {
        var absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException e) {
            return absolute;
        }
    }

    /** Writes the archive of what the sweep deletes, read back whole, before anything is deleted. */
    void archive(Path archive) throws IOException {
        BuildOutput.stepStart("Archiving what is deleted from " + dir);
        // A failure goes up as the command's error, which ends the step
        ConfigBackup.write(dir, keep(), archive);
        BuildOutput.stepDone();
        BuildOutput.stepNote("Written to " + archive);
    }

    /** The archive a sweep writes in {@code backupDir} at {@code now}. */
    static Path archivePath(Path backupDir, LocalDateTime now) {
        return backupDir.resolve("isx-config-backup-" + STAMP.format(now) + ".tar.gz");
    }
}
