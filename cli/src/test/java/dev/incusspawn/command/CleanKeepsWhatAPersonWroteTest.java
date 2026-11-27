package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * A clean of the config directory keeps what a person wrote there and archives the rest before
 * it deletes anything (#2666): {@code isx init} offers that directory for the templates checkout,
 * and a reset deleted one with work that was not committed. {@code isx reset} removes the host
 * directories through the same {@link KeptPaths}, {@link ConfigBackup} and {@code FileTrees.delete}.
 */
@ExtendWith(IsolatedHome.class)
class CleanKeepsWhatAPersonWroteTest {

    private static Path config() {
        return Environment.configDir();
    }

    /** The config directory as init leaves it, with the templates checkout at its default path. */
    private static Path seedWithCheckout() throws IOException {
        Files.createDirectories(config().resolve("certs"));
        Files.writeString(config().resolve("config.yaml"), "claude:\n  apiKey: sk-test\n");
        Files.writeString(config().resolve("ca.key"), "key");
        Files.writeString(config().resolve("certs/leaf.crt"), "leaf");
        var checkout = config().resolve("incus-spawn-templates");
        Files.createDirectories(checkout.resolve(".git"));
        Files.writeString(checkout.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        Files.writeString(checkout.resolve("mine.yaml"), "name: mine\n");
        return checkout;
    }

    private static String cleanConfig(boolean noBackup, boolean dryRun) throws Exception {
        var command = new CleanCommand.Config();
        command.skipConfirmation = true;
        command.noBackup = noBackup;
        command.dryRun = dryRun;
        return String.join("\n", RecordingOutput.during(command::doExecute).lines());
    }

    /** The names in {@code archive}, as {@code tar} lists them. */
    private static List<String> entries(Path archive) throws Exception {
        var process = new ProcessBuilder("tar", "-tzf", archive.toString()).redirectErrorStream(true).start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), out);
        return out.lines().toList();
    }

    @Test
    void aGitCheckoutIsKeptAndTheRestIsArchivedThenDeleted() throws Exception {
        var checkout = seedWithCheckout();

        var out = cleanConfig(false, false);

        assertEquals("name: mine\n", Files.readString(checkout.resolve("mine.yaml")), out);
        assertTrue(Files.exists(checkout.resolve(".git/HEAD")), out);
        assertFalse(Files.exists(config().resolve("config.yaml")), out);
        assertFalse(Files.exists(config().resolve("certs")), out);
        assertTrue(out.contains("Kept " + checkout + ": a Git checkout."), out);

        var archives = IsolatedHome.backups();
        assertEquals(1, archives.size(), out);
        var archive = archives.getFirst();
        assertTrue(archive.getFileName().toString().matches("isx-config-backup-\\d{8}-\\d{6}\\.tar\\.gz"), archive.toString());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(archive)));
        assertTrue(out.contains(archive.toString()), out);
        assertTrue(out.contains("It holds your credentials"), out);
        var entries = entries(archive);
        assertTrue(entries.contains("incus-spawn/config.yaml"), entries.toString());
        assertTrue(entries.contains("incus-spawn/ca.key"), entries.toString());
        assertTrue(entries.contains("incus-spawn/certs/leaf.crt"), entries.toString());
        assertTrue(entries.stream().noneMatch(e -> e.contains("incus-spawn-templates")), entries.toString());
    }

    @Test
    void aSecondCleanStillKeepsTheCheckoutWithNoConfigLeftToNameIt() throws Exception {
        var checkout = seedWithCheckout();
        cleanConfig(false, false);
        Files.writeString(config().resolve("config.yaml"), "x: 1\n");

        var out = cleanConfig(true, false);

        assertTrue(Files.exists(checkout.resolve("mine.yaml")), out);
        assertFalse(Files.exists(config().resolve("config.yaml")), out);
    }

    @Test
    void aConfiguredSearchPathIsKeptThoughItIsNotACheckout() throws Exception {
        var templates = config().resolve("my-templates");
        Files.createDirectories(templates);
        Files.writeString(templates.resolve("mine.yaml"), "name: mine\n");
        IsolatedHome.seed("searchPaths:\n  - ~/.config/incus-spawn/my-templates\n");

        var out = cleanConfig(false, false);

        assertTrue(Files.exists(templates.resolve("mine.yaml")), out);
        assertFalse(Files.exists(config().resolve("config.yaml")), out);
        assertTrue(out.contains("Kept " + templates + ": a search path of config.yaml."), out);
        assertTrue(entries(IsolatedHome.backups().getFirst()).stream().noneMatch(e -> e.contains("my-templates")));
    }

    @Test
    void aSearchPathThatIsTheConfigDirectoryDoesNotKeepIt() throws Exception {
        IsolatedHome.seed("searchPaths:\n  - ~/.config/incus-spawn\n");

        var out = cleanConfig(false, false);

        assertFalse(Files.exists(config()), out);
    }

    @Test
    void noBackupWritesNoArchiveAndStillKeepsTheCheckout() throws Exception {
        var checkout = seedWithCheckout();

        var out = cleanConfig(true, false);

        assertEquals(List.of(), IsolatedHome.backups(), out);
        assertFalse(out.contains("backup"), out);
        assertTrue(Files.exists(checkout.resolve("mine.yaml")), out);
        assertFalse(Files.exists(config().resolve("config.yaml")), out);
    }

    @Test
    void aDryRunNamesTheKeptCheckoutAndTheArchiveAndWritesNone() throws Exception {
        var checkout = seedWithCheckout();

        var out = cleanConfig(false, true);

        assertTrue(out.contains("Kept " + checkout), out);
        assertTrue(out.contains("isx-config-backup-"), out);
        assertEquals(List.of(), IsolatedHome.backups(), out);
        assertTrue(Files.exists(config().resolve("config.yaml")), out);
    }

    @Test
    void anAnswerOfNoWritesNoArchive() throws Exception {
        seedWithCheckout();

        RecordingOutput.during(() -> CleanCommand.All.clean(null, false, false, question -> false, true));

        assertEquals(List.of(), IsolatedHome.backups());
        assertTrue(Files.exists(config().resolve("config.yaml")));
    }

    @Test
    void aBackupDirectoryThatTheCleanDeletesStopsItBeforeItAsks() throws Exception {
        seedWithCheckout();
        var inside = config().resolve("certs");
        var previous = ConfigBackup.directory;
        ConfigBackup.directory = inside;
        try {
            var asked = new boolean[1];
            // The recorder hands on a checked exception as the cause of an AssertionError
            var e = assertThrows(AssertionError.class, () -> RecordingOutput.during(() ->
                    CleanCommand.All.clean(null, false, false, question -> asked[0] = true, true))).getCause();

            assertTrue(e.getMessage().contains("--no-backup"), e.getMessage());
            assertFalse(asked[0]);
            assertTrue(Files.exists(config().resolve("config.yaml")));
        } finally {
            ConfigBackup.directory = previous;
        }
    }

    @Test
    void anArchiveThatCouldNotBeWrittenWholeDeletesNothing() throws Exception {
        seedWithCheckout();
        var unreadable = config().resolve("ca.key");
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(unreadable), "root reads every file");

        var e = assertThrows(AssertionError.class, () -> cleanConfig(false, false)).getCause();

        assertTrue(e.getMessage().contains("nothing was deleted"), e.getMessage());
        assertTrue(Files.exists(config().resolve("config.yaml")));
        assertTrue(Files.exists(config().resolve("certs/leaf.crt")));
        assertEquals(List.of(), IsolatedHome.backups());
    }

    @Test
    void aGitRepositoryOfIsxInTheCacheIsDeleted() throws Exception {
        // isx prepares Git repositories of its own there, which a clean is for
        var prepared = Environment.cacheDir().resolve("git/abc");
        Files.createDirectories(prepared.resolve(".git"));

        RecordingOutput.during(() -> CleanCommand.All.clean(null, false, false, question -> true, true));

        assertFalse(Files.exists(Environment.cacheDir()));
        assertEquals(List.of(), IsolatedHome.backups());
    }

    @Test
    void aConfigDirectoryHoldingOnlyTheCheckoutIsNothingToClean() throws Exception {
        var checkout = config().resolve("incus-spawn-templates");
        Files.createDirectories(checkout.resolve(".git"));

        var out = cleanConfig(false, false);

        assertTrue(out.contains("Nothing to clean"), out);
        assertTrue(Files.exists(checkout.resolve(".git")));
        assertEquals(List.of(), IsolatedHome.backups(), out);
    }

    @Test
    void aBackupInAGitCheckoutWarnsNotToCommitIt() throws Exception {
        seedWithCheckout();
        Files.createDirectories(ConfigBackup.directory.resolve(".git"));

        var out = cleanConfig(false, true);

        assertTrue(out.contains("Do not commit isx-config-backup-"), out);
    }
}
