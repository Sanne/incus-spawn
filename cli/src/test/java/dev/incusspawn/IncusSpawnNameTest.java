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
}
