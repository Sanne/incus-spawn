package dev.incusspawn.tool;

import java.util.List;
import java.util.Set;

/**
 * Runtime context passed to a ToolAction when it executes.
 * Contains everything an action needs to know about the target instance.
 */
public record ActionContext(
        String instanceName,
        String ipv4,
        String status,
        String parent,
        Set<String> installedTools,
        String networkMode,
        List<RepoInfo> repos
) {
    public boolean isRunning() {
        return "RUNNING".equalsIgnoreCase(status);
    }

    /**
     * Repository information from the ImageDef inheritance chain.
     */
    public record RepoInfo(String name, String path, String url) {}
}
