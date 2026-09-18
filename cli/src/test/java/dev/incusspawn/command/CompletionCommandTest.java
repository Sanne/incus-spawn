package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the Linux completion output drops the macOS-only {@code vm} command while leaving
 * every other command — and the unrelated {@code --type vm} value — intact.
 */
class CompletionCommandTest {

    private static String linux(CompletionCommand.Shell shell) {
        return CompletionCommand.stripVmCommand(CompletionCommand.rawScript(shell), shell);
    }

    // --- bash ---

    @Test
    void bashDropsVmFromCommandList() {
        String mac = CompletionCommand.rawScript(CompletionCommand.Shell.bash);
        assertTrue(mac.contains("tools vm doctor"), "precondition: mac lists vm");

        String linux = linux(CompletionCommand.Shell.bash);
        assertFalse(linux.contains("tools vm doctor"));
        assertFalse(linux.contains("tools|vm|doctor"));
        // The now-unreachable vm) case block is removed too — no dead code left behind.
        assertFalse(linux.contains("vm_subcmds"), "dead vm) case block must be stripped");
        // Neighbours survive.
        assertTrue(linux.contains("tools doctor"));
        assertTrue(linux.contains("tools|doctor"));
        assertTrue(linux.contains("doctor)"), "neighbouring case blocks must survive");
    }

    @Test
    void bashKeepsTypeVmValue() {
        // `build --type` still offers container/vm/kvm — that vm token must not be stripped.
        assertTrue(linux(CompletionCommand.Shell.bash).contains("container vm kvm"));
    }

    // --- zsh ---

    @Test
    void zshDropsVmCommandAndDispatch() {
        String linux = linux(CompletionCommand.Shell.zsh);
        assertFalse(linux.contains("'vm:manage the incus-spawn VM appliance'"));
        assertFalse(linux.contains("_isx_vm ;;"));
        // The now-unreachable _isx_vm() function is removed too — no dead code left behind.
        assertFalse(linux.contains("_isx_vm()"), "dead _isx_vm function must be stripped");
        // Other commands remain dispatchable, and the --type vm value survives.
        assertTrue(linux.contains("_isx_doctor ;;"));
        assertTrue(linux.contains("_isx_doctor()"), "neighbouring functions must survive");
        assertTrue(linux.contains("container vm kvm"));
    }

    // --- fish ---

    @Test
    void fishDropsVmSuggestionAndSubcommandRules() {
        String linux = linux(CompletionCommand.Shell.fish);
        assertFalse(linux.contains("-a vm "), "top-level vm suggestion removed");
        assertFalse(linux.contains("__isx_using_subcommand vm"), "vm subcommand rules removed");
        assertFalse(linux.contains("tools|vm|doctor"));
        // Unrelated commands and the --type vm value survive.
        assertTrue(linux.contains("-a doctor"));
        assertTrue(linux.contains("container vm kvm"));
    }

    // --- internal commands hidden from all shells ---

    @Test
    void hiddenCommandsNotOfferedAsTopLevelCompletions() {
        for (var shell : CompletionCommand.Shell.values()) {
            String script = CompletionCommand.rawScript(shell);
            switch (shell) {
                case zsh -> {
                    assertFalse(script.contains("'completion:"), "zsh must not suggest completion");
                    assertFalse(script.contains("'instances:"), "zsh must not suggest instances");
                    assertFalse(script.contains("'git-remote-helper:"), "zsh must not suggest git-remote-helper");
                    assertFalse(script.contains("'ssh-proxy:"), "zsh must not suggest ssh-proxy");
                }
                case bash -> {
                    String commands = script.lines()
                            .filter(l -> l.stripLeading().startsWith("local commands="))
                            .findFirst().orElseThrow();
                    assertFalse(commands.contains("completion"), "bash must not list completion");
                    assertFalse(commands.contains("instances"), "bash must not list instances");
                    assertFalse(commands.contains("git-remote-helper"), "bash must not list git-remote-helper");
                    assertFalse(commands.contains("ssh-proxy"), "bash must not list ssh-proxy");
                }
                case fish -> {
                    assertFalse(script.contains("-a completion"), "fish must not suggest completion");
                    assertFalse(script.contains("-a instances"), "fish must not suggest instances");
                    assertFalse(script.contains("-a git-remote-helper"), "fish must not suggest git-remote-helper");
                    assertFalse(script.contains("-a ssh-proxy"), "fish must not suggest ssh-proxy");
                }
            }
        }
    }

    // --- macOS scripts keep vm (and the new resize subcommand) ---

    @Test
    void macScriptsRetainVmAndResize() {
        assertTrue(CompletionCommand.rawScript(CompletionCommand.Shell.zsh)
                .contains("'resize:grow the VM data disk that backs the storage pool'"));
        assertTrue(CompletionCommand.rawScript(CompletionCommand.Shell.bash)
                .contains("start stop restart status resize console"));
        assertTrue(CompletionCommand.rawScript(CompletionCommand.Shell.fish)
                .contains("-a resize"));
    }
}
