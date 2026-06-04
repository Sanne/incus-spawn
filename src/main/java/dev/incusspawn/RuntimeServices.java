package dev.incusspawn;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.tool.PiSetup;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tui.BackgroundTaskManager;
import dev.incusspawn.tui.FlockInstanceLockManager;
import dev.incusspawn.tui.InstanceLockManager;

import java.util.List;

/**
 * Singleton service registry. All fields are initialized eagerly at class-load
 * time, which means this class MUST be listed in the native-image
 * {@code --initialize-at-run-time} flag (see application.properties). Without
 * that, GraalVM would capture these instances into the image heap at build
 * time, baking in stale sockets, file handles, and host-specific paths.
 */
public final class RuntimeServices {

    private static final IncusClient INCUS = new IncusClient();
    private static final BackgroundTaskManager BG_TASKS = new BackgroundTaskManager();
    private static final FlockInstanceLockManager LOCK_MGR = new FlockInstanceLockManager();
    private static final ToolDefLoader TOOL_DEF_LOADER = new ToolDefLoader();
    private static final List<ToolSetup> TOOL_SETUPS = List.of(new ClaudeSetup(), new GhSetup(), new PiSetup());

    private RuntimeServices() {}

    public static IncusClient incus() { return INCUS; }
    public static BackgroundTaskManager backgroundTasks() { return BG_TASKS; }
    public static InstanceLockManager lockManager() { return LOCK_MGR; }
    public static ToolDefLoader toolDefLoader() { return TOOL_DEF_LOADER; }
    public static List<ToolSetup> toolSetups() { return TOOL_SETUPS; }
}
