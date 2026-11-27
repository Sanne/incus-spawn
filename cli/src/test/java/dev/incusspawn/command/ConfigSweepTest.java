package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code isx clean config}, {@code isx clean all} and {@code isx reset} keep the Git checkouts and
 * search paths in the config directory and archive the rest first (#2666): {@code isx init}
 * offers to clone the templates repository there, so a person's uncommitted templates can be in it.
 */
@ExtendWith(IsolatedHome.class)
class ConfigSweepTest {

    @TempDir
    Path backups;

    private static Path config() {
        return Environment.configDir();
    }

    /** A config directory as init leaves it, with the templates cloned into it and one uncommitted template. */
    private static Path seedWithTemplatesCheckout() throws IOException {
        Files.createDirectories(config().resolve("ssh"));
        Files.writeString(config().resolve("config.yaml"), "claude:\n  apiKey: test-key-1\n");
        Files.writeString(config().resolve("ssh/id_ed25519"), "PRIVATE KEY");
        var checkout = config().resolve("incus-spawn-templates");
        Files.createDirectories(checkout.resolve(".git"));
        Files.writeString(checkout.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        Files.createDirectories(checkout.resolve("images"));
        Files.writeString(checkout.resolve("images/mine.yaml"), "name: tpl-mine\n");
        return checkout;
    }

    private static List<Path> archives(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith("isx-config-backup-")).toList();
        }
    }

    @Test
    void cleanConfigKeepsTheTemplatesCheckoutAndSaysSo() throws Exception {
        var checkout = seedWithTemplatesCheckout();

        var recorded = RecordingOutput.during(() -> CleanCommand.Config.clean(false, q -> true, backups));

        assertEquals("name: tpl-mine\n", Files.readString(checkout.resolve("images/mine.yaml")));
        assertTrue(Files.exists(checkout.resolve(".git/HEAD")));
        assertFalse(Files.exists(config().resolve("config.yaml")));
        assertFalse(Files.exists(config().resolve("ssh")));
        assertTrue(recorded.lines().stream().anyMatch(l -> l.contains("Kept " + checkout + ": a Git checkout")),
                recorded.lines().toString());
    }

    @Test
    void cleanAllKeepsTheTemplatesCheckoutToo() throws Exception {
        var checkout = seedWithTemplatesCheckout();

        RecordingOutput.during(() -> CleanCommand.All.clean(null, false, false, q -> true, backups));

        assertTrue(Files.exists(checkout.resolve("images/mine.yaml")));
        assertFalse(Files.exists(config().resolve("config.yaml")));
    }

    @Test
    void aSearchPathInsideTheConfigDirectoryIsKept() throws Exception {
        Files.createDirectories(config().resolve("my-templates/images"));
        Files.writeString(config().resolve("my-templates/images/a.yaml"), "name: tpl-a\n");
        Files.writeString(config().resolve("config.yaml"), "searchPaths:\n  - ~/.config/incus-spawn/my-templates\n");

        var recorded = RecordingOutput.during(() -> CleanCommand.Config.clean(false, q -> true, backups));

        assertTrue(Files.exists(config().resolve("my-templates/images/a.yaml")));
        assertFalse(Files.exists(config().resolve("config.yaml")));
        assertTrue(recorded.lines().stream().anyMatch(l -> l.contains("a search path in config.yaml")),
                recorded.lines().toString());
    }

    @Test
    void whatIsDeletedIsArchivedFirstReadableByItsOwnerAlone() throws Exception {
        seedWithTemplatesCheckout();

        RecordingOutput.during(() -> CleanCommand.Config.clean(false, q -> true, backups));

        var archive = archives(backups);
        assertEquals(1, archive.size(), archive.toString());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(archive.getFirst())));
        var names = ConfigBackup.read(archive.getFirst()).stream().map(ConfigBackup.Entry::name).toList();
        assertTrue(names.contains("incus-spawn/config.yaml"), names.toString());
        assertTrue(names.contains("incus-spawn/ssh/id_ed25519"), names.toString());
        // What is kept is not deleted, so it is not in the archive either
        assertTrue(names.stream().noneMatch(n -> n.contains("incus-spawn-templates")), names.toString());
    }

    @Test
    void theArchiveRestoresWithTar() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/tar")) || Files.isExecutable(Path.of("/bin/tar")));
        seedWithTemplatesCheckout();
        var deep = config().resolve("a-rather-long-directory-name-for-the-test/" + "x".repeat(90) + "/é-config.yaml");
        Files.createDirectories(deep.getParent());
        Files.writeString(deep, "deep\n");
        Files.createSymbolicLink(config().resolve("link"), Path.of("ssh/id_ed25519"));
        RecordingOutput.during(() -> CleanCommand.Config.clean(false, q -> true, backups));
        var restored = Files.createDirectories(backups.resolve("restored"));

        var tar = new ProcessBuilder("tar", "xzf", archives(backups).getFirst().toString(), "-C", restored.toString())
                .redirectErrorStream(true).start();
        var output = new String(tar.getInputStream().readAllBytes());

        assertEquals(0, tar.waitFor(), output);
        var root = restored.resolve("incus-spawn");
        assertEquals("claude:\n  apiKey: test-key-1\n", Files.readString(root.resolve("config.yaml")));
        assertEquals("deep\n", Files.readString(root.resolve(config().relativize(deep))));
        assertEquals(Path.of("ssh/id_ed25519"), Files.readSymbolicLink(root.resolve("link")));
    }

    @Test
    void anArchiveThatCannotBeWrittenDeletesNothing() throws Exception {
        seedWithTemplatesCheckout();
        var missing = backups.resolve("not-there");

        var e = assertThrows(AssertionError.class,
                () -> RecordingOutput.during(() -> CleanCommand.Config.clean(false, q -> true, missing)));

        assertTrue(e.getCause().getMessage().contains("nothing was deleted"), e.getCause().getMessage());

        assertTrue(Files.exists(config().resolve("config.yaml")));
        assertTrue(Files.exists(config().resolve("ssh/id_ed25519")));
    }

    @Test
    void anExistingFileIsNeverOverwritten() throws Exception {
        seedWithTemplatesCheckout();
        var taken = Files.writeString(backups.resolve("taken.tar.gz"), "someone else's");

        var e = assertThrows(IOException.class, () -> ConfigBackup.write(config(), Set.of(), taken));

        assertTrue(e.getMessage().contains("already exists"), e.getMessage());
        assertEquals("someone else's", Files.readString(taken));
    }

    @Test
    void anArchiveInsideWhatIsDeletedIsRefusedBeforeAsking() throws Exception {
        seedWithTemplatesCheckout();
        var inside = Files.createDirectories(config().resolve("vm"));

        var e = assertThrows(AssertionError.class, () -> RecordingOutput.during(() ->
                CleanCommand.Config.clean(false, q -> { throw new IllegalStateException("asked"); }, inside)));

        assertTrue(e.getCause().getMessage().contains("--no-backup"), e.getCause().getMessage());
        assertTrue(Files.exists(config().resolve("config.yaml")));
    }

    @Test
    void noBackupWritesNoArchive() throws Exception {
        var checkout = seedWithTemplatesCheckout();

        var recorded = RecordingOutput.during(() -> CleanCommand.Config.clean(false, q -> true, null));

        assertFalse(Files.exists(config().resolve("config.yaml")));
        assertTrue(Files.exists(checkout.resolve("images/mine.yaml")));
        assertTrue(recorded.lines().stream().anyMatch(l -> l.contains("--no-backup")), recorded.lines().toString());
    }

    @Test
    void aDryRunNamesTheArchiveAndWritesNothing() throws Exception {
        seedWithTemplatesCheckout();

        var recorded = RecordingOutput.during(() -> CleanCommand.Config.clean(true, q -> true, backups));

        assertTrue(recorded.lines().stream().anyMatch(l -> l.contains("Would archive")), recorded.lines().toString());
        assertTrue(recorded.events(RecordingOutput.Kind.WARN).stream()
                .anyMatch(w -> w.msg().contains("credentials")), recorded.lines().toString());
        assertEquals(List.of(), archives(backups));
        assertTrue(Files.exists(config().resolve("config.yaml")));
    }

    @Test
    void anArchiveGoingIntoAGitWorkingTreeIsWarnedAbout() throws Exception {
        // Run from a project's checkout, the credentials land untracked beside its sources
        seedWithTemplatesCheckout();
        var project = Files.createDirectories(backups.resolve("project"));
        Files.createDirectories(project.resolve(".git"));
        var sub = Files.createDirectories(project.resolve("src"));

        var recorded = RecordingOutput.during(() -> CleanCommand.Config.clean(true, q -> true, sub));

        assertTrue(recorded.events(RecordingOutput.Kind.WARN).stream()
                .anyMatch(w -> w.msg().contains("Git working tree at " + project)), recorded.lines().toString());
    }

    @Test
    void aSymlinkToACheckoutElsewhereIsOnlyALink() throws Exception {
        var elsewhere = Files.createDirectories(backups.resolve("templates"));
        Files.createDirectories(elsewhere.resolve(".git"));
        Files.writeString(elsewhere.resolve("t.yaml"), "x");
        Files.createDirectories(config());
        Files.writeString(config().resolve("config.yaml"), "x: 1\n");
        Files.createSymbolicLink(config().resolve("templates"), elsewhere);

        RecordingOutput.during(() -> CleanCommand.Config.clean(false, q -> true, null));

        assertFalse(Files.exists(config().resolve("templates"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.exists(elsewhere.resolve("t.yaml")));
    }
}
