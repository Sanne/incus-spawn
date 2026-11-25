package dev.incusspawn;

import org.aesh.command.impl.LazyHelp;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The command is {@code isx}, so its help and its errors say so, never the old incus-spawn (#1441). */
class IncusSpawnNameTest {

    private static final Pattern ANSI = Pattern.compile("\033\\[[^m]*m");
    private static final Pattern OLD_NAME = Pattern.compile("incus-spawn(?![-./])");

    @Test
    void helpAndSubcommandUsageNameIsx() {
        for (var top : List.of(IncusSpawn.IncusSpawnCommand.class, IncusSpawn.IncusSpawnLinuxCommand.class)) {
            for (var args : List.of(new String[] {"--help"}, new String[] {"branch", "--help"})) {
                var rendered = LazyHelp.render(top, args);
                assertNotNull(rendered, String.join(" ", args));
                var help = ANSI.matcher(rendered).replaceAll("");
                assertTrue(help.contains("Usage: isx"), help);
                // Paths such as ~/.config/incus-spawn/ keep their name; the command is isx
                assertFalse(OLD_NAME.matcher(help).find(), top.getSimpleName() + " " + String.join(" ", args) + ":\n" + help);
            }
        }
    }

    /**
     * The top-level help opens with the logo for a person at a terminal, its description beside
     * the middle row, and is the help as it always was anywhere else: a script reads the same.
     */
    @Test
    void theTopLevelHelpShowsTheLogoOnlyOnATerminal() {
        var help = IncusSpawn.filterHiddenCommands(LazyHelp.render(IncusSpawn.IncusSpawnLinuxCommand.class, new String[] {"--help"}));
        assertSame(help, IncusSpawn.withLogo(help, name -> null), "off a terminal the help is untouched");

        try (var ansi = dev.incusspawn.util.ForcedAnsi.on()) {
            var drawn = dev.incusspawn.util.BuildOutput.stripAnsi(IncusSpawn.withLogo(help, name -> null)).lines().toList();
            var plain = dev.incusspawn.util.BuildOutput.stripAnsi(help).lines().toList();
            assertEquals(dev.incusspawn.tui.Logo.ROWS[0], drawn.get(0));
            assertEquals(dev.incusspawn.tui.Logo.ROWS[1] + "    " + plain.get(0), drawn.get(1));
            assertEquals(dev.incusspawn.tui.Logo.ROWS[2], drawn.get(2));
            assertEquals("", drawn.get(3));
            // Nothing of the help is lost: the usage line and everything after it follow
            assertEquals(plain.subList(1, plain.size()), drawn.subList(4, drawn.size()));

            // A help that does not open with a description above its usage line is left alone
            var other = "Usage: isx branch\nOptions:";
            assertSame(other, IncusSpawn.withLogo(other, name -> null));
        }
    }

    /**
     * {@code isx --version --format=plain|json} is the version for a script: a record whose
     * fields keep their names and order, never the logo, on a terminal or off one.
     */
    @Test
    void theVersionRecordIsAContract() {
        var info = new dev.incusspawn.BuildInfo("1.2.3-SNAPSHOT", "abc1234", "JVM", "1.2.3-dev.1+5");
        var record = IncusSpawn.versionRecord(info);
        assertEquals(List.of("display_version", "version", "commit", "incus_client", "incus_server", "runtime"),
                List.copyOf(record.keySet()));
        assertEquals("1.2.3-SNAPSHOT", record.get("version"));
        assertEquals("1.2.3-dev.1+5", record.get("display_version"));
        assertEquals("abc1234", record.get("commit"));
        assertEquals("JVM", record.get("runtime"));

        try (var ansi = dev.incusspawn.util.ForcedAnsi.on()) {
            var bytes = new java.io.ByteArrayOutputStream();
            dev.incusspawn.util.OutputFormat.PLAIN.printOne(new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8), record);
            var plain = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            // The version people read comes first, for 'cut -f1'
            assertTrue(plain.startsWith("1.2.3-dev.1+5\t1.2.3-SNAPSHOT\tabc1234\t"), plain);
            assertEquals(1, plain.lines().count(), plain);
            assertFalse(plain.contains("\u001b") || plain.contains(dev.incusspawn.tui.Logo.ROWS[0]), plain);
        }
    }

    /** The lines people read keep their text; only a terminal adds the logo beside them. */
    @Test
    void theVersionLinesArePlainOffATerminal() {
        var info = new dev.incusspawn.BuildInfo("1.2.3", "abc1234", "JVM", "1.2.3");
        var lines = IncusSpawn.versionLines(info, name -> null);
        assertEquals(3, lines.size());
        assertEquals("isx 1.2.3 (abc1234)", lines.get(0));
        assertEquals("JVM", lines.get(2));

        try (var ansi = dev.incusspawn.util.ForcedAnsi.on()) {
            var drawn = IncusSpawn.versionLines(info, name -> null);
            assertEquals(dev.incusspawn.tui.Logo.ROWS[0] + "    isx 1.2.3 (abc1234)", dev.incusspawn.util.BuildOutput.stripAnsi(drawn.get(0)));
        }
    }

    /** {@code --format} is for {@code --version}: alone it would start the TUI with an answer nobody reads. */
    @Test
    void formatWithoutVersionIsRefusedAndAnUnknownFormatToo() {
        var alone = assertThrows(IllegalArgumentException.class, () -> IncusSpawn.runTop(false, "json"));
        assertTrue(alone.getMessage().contains("--format goes with --version"), alone.getMessage());
        assertThrows(IllegalArgumentException.class, () -> IncusSpawn.runTop(true, "yaml"));
    }
}
