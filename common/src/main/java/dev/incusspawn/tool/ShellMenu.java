package dev.incusspawn.tool;

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
     * ({@code workdir}, or the home directory), falling back to its first repo.
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
            var key = action.toolName() + ":" + action.baseId().orElse("");
            var repo = action.repoPath().get();
            var inWorkdir = effectiveWorkdir.equals(repo) || effectiveWorkdir.startsWith(repo + "/");
            if (!chosen.containsKey(key) || inWorkdir) {
                chosen.put(key, action);
            }
        }
        return new ShellMenu(new ArrayList<>(chosen.values()), context);
    }
}
