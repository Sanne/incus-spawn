package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.FileTrees;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The archive of what a reset or a clean deletes from the config directory, written to the
 * directory the command runs in before anything is deleted (#2666).
 *
 * <p>It holds exactly what will be deleted, so not what {@link KeptPaths} keeps, and it is read
 * back before the caller deletes: a disk that filled up passes a check that the directory is
 * writeable. It holds every credential of the config directory, so it is created for its owner
 * alone, never replaces a file, and the plan says whose it is to delete. {@code tar} writes it,
 * since the JDK's zip keeps neither a key's mode nor a symlink.
 */
final class ConfigBackup {

    /** Where the archive goes instead of the working directory, for tests; null outside them. */
    static volatile Path directory;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path configDir;
    private final List<Path> members;
    private final Path file;

    private ConfigBackup(Path configDir, List<Path> members, Path file) {
        this.configDir = configDir;
        this.members = members;
        this.file = file;
    }

    /**
     * The archive a command removing {@code removed} and keeping {@code kept} writes first, or
     * null when it deletes nothing from the config directory.
     *
     * @throws IOException when the working directory cannot take it, before anything is asked
     */
    static ConfigBackup plan(Collection<Path> removed, Collection<Path> kept) throws IOException {
        var config = Environment.configDir();
        // A config directory that is a symlink loses the link alone; what it points to stays
        if (!removed.contains(config) || !Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS)) return null;
        var members = members(config, kept);
        if (members.isEmpty()) return null;

        var into = directory != null ? directory : Path.of("").toAbsolutePath();
        var real = into.toRealPath();
        for (var dir : removed) {
            if (Files.exists(dir) && real.startsWith(dir.toRealPath())) {
                throw new IOException("the backup of " + config + " would go to " + into
                        + ", which this command deletes. Run it from another directory, or pass --no-backup.");
            }
        }
        if (!Files.isWritable(into)) {
            throw new IOException("the backup of " + config + " cannot be written to " + into
                    + ". Run this from a directory you can write to, or pass --no-backup.");
        }
        var file = into.resolve("isx-config-backup-" + STAMP.format(LocalDateTime.now()) + ".tar.gz");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(file + " already exists, and a backup never replaces a file."
                    + " Run this again, or pass --no-backup.");
        }
        return new ConfigBackup(config, members, file);
    }

    Path file() {
        return file;
    }

    /** The plan's lines: where the archive goes and what it holds. */
    void announce() {
        BuildOutput.step("Will first write " + file + ", a backup of what is deleted from " + configDir + ".");
        BuildOutput.stepNote("It holds your credentials, and it is yours to delete. Pass --no-backup to write none.");
        var checkout = gitCheckoutHolding(file);
        if (checkout != null) {
            BuildOutput.warn("The backup goes into the Git checkout " + checkout + ", and it holds your credentials.",
                    "Do not commit " + file.getFileName() + ".");
        }
    }

    /**
     * Writes the archive and reads it back.
     *
     * @throws IOException when it could not be written whole; the file is removed and the caller deletes nothing
     */
    void write() throws IOException {
        BuildOutput.stepStart("Writing the backup " + file);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        var list = Files.createTempFile("isx-backup-members", ".list");
        try {
            var parent = configDir.getParent();
            var names = new StringBuilder();
            for (var member : members) names.append(parent.relativize(member)).append('\0');
            Files.writeString(list, names, StandardCharsets.UTF_8);
            // To stdout, so the file created above for its owner alone is the one written
            run(new ProcessBuilder("tar", "-czf", "-", "-C", parent.toString(), "--null", "-T", list.toString())
                    .redirectOutput(file.toFile()));
            run(new ProcessBuilder("tar", "-tzf", file.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD));
            if (Files.size(file) == 0) throw new IOException("tar wrote an empty archive");
        } catch (IOException e) {
            Files.deleteIfExists(file);
            BuildOutput.stepBreak();
            throw new IOException("the backup " + file + " could not be written (" + e.getMessage()
                    + "), so nothing was deleted. Pass --no-backup to go on without one.", e);
        } finally {
            Files.deleteIfExists(list);
        }
        BuildOutput.stepDone();
    }

    private static void run(ProcessBuilder command) throws IOException {
        var process = command.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile())).start();
        var errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (exit != 0) throw new IOException(errors.isEmpty() ? "tar exited with " + exit : errors);
    }

    /**
     * The outermost entries of {@code dir} that are deleted whole, so that an archive of them
     * leaves out {@code kept} and holds everything else.
     */
    private static List<Path> members(Path dir, Collection<Path> kept) throws IOException {
        var result = new ArrayList<Path>();
        try (var children = Files.list(dir)) {
            for (var child : children.sorted().toList()) {
                if (kept.contains(child)) continue;
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && FileTrees.leadsToAny(child, kept)) {
                    result.addAll(members(child, kept));
                } else {
                    result.add(child);
                }
            }
        }
        return result;
    }

    private static Path gitCheckoutHolding(Path file) {
        for (var dir = file.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return dir;
        }
        return null;
    }
}
