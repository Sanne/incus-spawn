package dev.incusspawn.tool;

import dev.incusspawn.Warnings;
import dev.incusspawn.config.SpawnConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * What the shell status bar's F12 menu offers in one {@code isx shell} session, and the context
 * its actions run against. Callers resolve it only when the {@value #FEATURE} feature is on and
 * pass {@link #NONE} otherwise, so a shell without the bar costs no extra Incus round trips.
 */
public record ShellMenu(List<ToolAction> actions, ActionContext context) {

    public static final String FEATURE = "shell-status-bar";
    public static final ShellMenu NONE = new ShellMenu(List.of(), null);

    private static final String DEFAULT_WORKDIR = "/home/agentuser";

    public static boolean enabled() {
        return SpawnConfig.load().isFeatureEnabled(FEATURE);
    }

    /**
     * The menu-eligible actions among {@code actions}: {@code url} actions with
     * {@code shell_menu: true}. Only those, since the menu runs them while the shell owns the
     * terminal, and a {@code command} action's process would share the raw tty with the session.
     * An {@code expand: repos} action is offered once, for the repo the session starts in
     * ({@code workdir}, or the home directory), falling back to its first repo. Each entry needs a
     * key of its own: one without a single printable ASCII shortcut could not be run, and of two
     * with the same key (in either case) only the first ever would, so those are left out.
     */
    public static ShellMenu of(List<ToolAction> actions, String workdir, ActionContext context) {
        var effectiveWorkdir = workdir == null || workdir.isBlank() ? DEFAULT_WORKDIR : workdir;
        var chosen = new LinkedHashMap<Object, ToolAction>();
        for (var action : actions) {
            if (!action.isShellMenu() || !action.type().map(YamlToolAction.TYPE_URL::equals).orElse(false)) {
                continue;
            }
            if (action.repoPath().isEmpty()) {
                chosen.put(action, action);
                continue;
            }
            // One entry per expanded action, whichever of its repos the session starts in.
            var key = action.expandedFrom();
            var repo = action.repoPath().get();
            var inWorkdir = effectiveWorkdir.equals(repo) || effectiveWorkdir.startsWith(repo + "/");
            if (!chosen.containsKey(key) || inWorkdir) {
                chosen.put(key, action);
            }
        }
        var byKey = new LinkedHashMap<Character, ToolAction>();
        for (var action : chosen.values()) {
            var key = action.shortcut().filter(s -> s.length() == 1 && s.charAt(0) > ' ' && s.charAt(0) < 0x7F);
            if (key.isEmpty()) {
                Warnings.warn("Action '" + action.label() + "' of " + action.toolName()
                        + " is left out of the shell menu: its shortcut must be one printable ASCII key");
                continue;
            }
            var previous = byKey.putIfAbsent(Character.toLowerCase(key.get().charAt(0)), action);
            if (previous != null) {
                Warnings.warn("Action '" + action.label() + "' of " + action.toolName()
                        + " is left out of the shell menu: '" + previous.label() + "' already uses the key "
                        + key.get());
            }
        }
        return new ShellMenu(new ArrayList<>(byKey.values()), context);
    }
}
