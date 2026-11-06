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
}
