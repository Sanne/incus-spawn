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
    void zshLeavesTheWordsArgumentsAdjustedAlone() {
        // `_arguments -C` already removes the command from `words` and decrements `CURRENT`.
        // Shifting again left the empty word being completed as the command name, so no helper
        // offered a positional value and `isx destroy <TAB>` listed nothing (see ZshCompletionTest).
        String zsh = CompletionCommand.rawScript(CompletionCommand.Shell.zsh);
        assertFalse(zsh.contains("shift words"), "the dispatcher must not shift the words _arguments adjusted");
        assertFalse(zsh.contains("CURRENT--"), "the dispatcher must not decrement CURRENT again");
        assertTrue(zsh.contains("local cmd=$line[1]"), "the subcommand still comes from $line[1]");
    }

    @Test
    void zshAccountDispatchOwnsItsSubcommandParsing() {
        // _isx_account used the shifted $words[1], so it needs its own _arguments -C like the
        // other subcommand helpers now that the dispatcher leaves words alone.
        String account = CompletionCommand.rawScript(CompletionCommand.Shell.zsh)
                .lines()
                .dropWhile(l -> !l.startsWith("_isx_account()"))
                .takeWhile(l -> !l.equals("}"))
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(account.contains("_arguments -C"), "account must parse its subcommand with _arguments -C");
        assertTrue(account.contains("case $line[1] in"), "account must dispatch on $line[1]");
        assertFalse(account.contains("case $words[1] in"), "account must not dispatch on the shifted words");
        assertTrue(account.contains("set) _arguments '1:instance:_isx_instances' '*:account:_isx_accounts'"));
    }

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

    // --- macOS scripts keep vm (and its resize and reset subcommands) ---

    @Test
    void macScriptsRetainVmResizeAndReset() {
        var zsh = CompletionCommand.rawScript(CompletionCommand.Shell.zsh);
        assertTrue(zsh.contains("'resize:grow the VM data disk that backs the storage pool'"));
        assertTrue(zsh.contains("'reset:wipe the VM data disk"));
        assertTrue(CompletionCommand.rawScript(CompletionCommand.Shell.bash)
                .contains("start stop restart status resize reset console"));
        var fish = CompletionCommand.rawScript(CompletionCommand.Shell.fish);
        assertTrue(fish.contains("-a resize"));
        assertTrue(fish.contains("-a reset"));
    }

    // --- instance names come from isx list -q (#1036) ---

    @Test
    void instanceNamesComeFromListQuietNotTheDeprecatedInstancesCommand() {
        for (var shell : CompletionCommand.Shell.values()) {
            String script = CompletionCommand.rawScript(shell);
            assertFalse(script.contains("isx instances"), shell + " must not call the deprecated isx instances");
            assertTrue(script.contains("isx list -q 2>/dev/null"), shell + " must list instance names with isx list -q");
            assertTrue(script.contains("table plain json"), shell + " must offer the --format values");
            assertFalse(script.contains("no-op"), shell + " must not describe --plain as a no-op");
        }
    }

    // --- account values ---

    @Test
    void everyShellCompletesAccountValuesFromTheNamesOnlyHelper() {
        for (var shell : CompletionCommand.Shell.values()) {
            String script = linux(shell);
            assertTrue(script.contains("isx account list --completion"), shell + " must call the helper");
            assertFalse(script.contains("--format=json"), shell + " must not parse the full listing");
        }
        assertTrue(linux(CompletionCommand.Shell.zsh).contains("*--account=[Credential account as <namespace>=<account>]:selection:_isx_accounts"));
        assertTrue(linux(CompletionCommand.Shell.bash).contains("--account)"));
        assertTrue(linux(CompletionCommand.Shell.fish).contains("-l account"));
        assertTrue(linux(CompletionCommand.Shell.fish).contains("'(__isx_accounts)'"));
    }
}
