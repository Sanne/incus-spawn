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
     * The menu-eligible actions among {@code actions}: those with {@code shell_menu: true} that
     * run on the host ({@code url} and {@code command}). An {@code expand: repos} action is offered
     * once, for the repo the session starts in ({@code workdir}, or the home directory), falling
     * back to its first repo.
     */
    public static ShellMenu of(List<ToolAction> actions, String workdir, ActionContext context) {
        var effectiveWorkdir = workdir == null || workdir.isBlank() ? DEFAULT_WORKDIR : workdir;
        var chosen = new LinkedHashMap<String, ToolAction>();
        for (var action : actions) {
            var type = action.type().orElse("");
            if (!action.isShellMenu()
                    || !(YamlToolAction.TYPE_URL.equals(type) || YamlToolAction.TYPE_COMMAND.equals(type))) {
                continue;
            }
            var key = action.toolName() + ":" + action.baseId().orElse("");
            var inWorkdir = action.repoPath().map(effectiveWorkdir::equals).orElse(false);
            if (!chosen.containsKey(key) || inWorkdir) {
                chosen.put(key, action);
            }
        }
        return new ShellMenu(new ArrayList<>(chosen.values()), context);
    }
}
