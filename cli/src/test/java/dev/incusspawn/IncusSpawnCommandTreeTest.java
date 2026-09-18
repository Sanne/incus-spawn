package dev.incusspawn;

import dev.incusspawn.command.VmCommand;
import org.aesh.command.CommandDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the platform-specific command tree in {@link IncusSpawn}. Because aesh bakes
 * {@code groupCommands} into the annotation at compile time, the macOS and Linux top commands
 * duplicate the same command list — the Linux one only omits {@link VmCommand}. This test asserts
 * the two lists stay in step so adding a command to one but forgetting the other fails loudly
 * rather than silently dropping it from {@code isx --help} on Linux.
 */
class IncusSpawnCommandTreeTest {

    private static List<Class<?>> groupCommands(Class<?> topCommand) {
        return Arrays.asList(topCommand.getAnnotation(CommandDefinition.class).groupCommands());
    }

    @Test
    void linuxTreeEqualsMacTreeMinusVm() {
        var mac = groupCommands(IncusSpawn.IncusSpawnCommand.class);
        var linux = groupCommands(IncusSpawn.IncusSpawnLinuxCommand.class);

        assertTrue(mac.contains(VmCommand.class), "macOS tree must include the vm appliance command");
        assertFalse(linux.contains(VmCommand.class), "Linux tree must not include the vm appliance command");

        var expectedLinux = mac.stream().filter(c -> c != VmCommand.class).toList();
        assertEquals(expectedLinux, linux,
                "Linux command tree must equal the macOS tree minus VmCommand — a command was added to one but not the other");
    }

    @Test
    void filterHiddenCommandsStripsInternalEntries() {
        String help = """
                incus-spawn commands:
                    \033[34minit\033[0m         One-time host setup
                    \033[34mcompletion\033[0m   Print shell completion script
                    \033[34mbuild\033[0m        Build or rebuild a template image
                    \033[34minstances\033[0m    List connectable instance names
                    \033[34mgit-remote-helper\033[0m  Git remote helper
                    \033[34mssh-proxy\033[0m    SSH ProxyCommand
                    \033[34mdoctor\033[0m       Run health checks and completion status
                """;
        String filtered = IncusSpawn.filterHiddenCommands(help);
        assertTrue(filtered.contains("init"), "visible commands survive");
        assertTrue(filtered.contains("build"), "visible commands survive");
        assertTrue(filtered.contains("doctor"), "visible commands survive");
        assertTrue(filtered.contains("completion status"),
                "description text containing a hidden command name must survive");
        for (String hidden : IncusSpawn.HIDDEN_COMMANDS) {
            boolean hasOwnLine = filtered.lines().anyMatch(line -> {
                var stripped = line.replaceAll("\033\\[[^m]*m", "").stripLeading();
                return stripped.startsWith(hidden + " ") || stripped.equals(hidden);
            });
            assertFalse(hasOwnLine, hidden + " must not have its own line in help");
        }
    }

    @Test
    void hiddenCommandsAreStillRegistered() {
        var mac = groupCommands(IncusSpawn.IncusSpawnCommand.class);
        for (String hidden : IncusSpawn.HIDDEN_COMMANDS) {
            assertTrue(mac.stream().anyMatch(c -> {
                var def = c.getAnnotation(CommandDefinition.class);
                return def != null && def.name().equals(hidden);
            }), hidden + " must remain in groupCommands for execution");
        }
    }
}
