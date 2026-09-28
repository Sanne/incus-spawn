package dev.incusspawn.tool;

import dev.incusspawn.incus.MachineType;

import java.util.List;
import java.util.Set;

/**
 * Context for an action target — an instance or a template.
 */
public record ActionContext(
        String name,
        MachineType machineType,
        Set<String> installedTools,
        List<RepoInfo> repos,
        InstanceState instance
) {
    public ActionContext(String name, MachineType machineType) {
        this(name, machineType, Set.of(), List.of(), null);
    }

    public String instanceName() { return name; }
    public String ipv4() { return instance != null ? instance.ipv4 : ""; }
    public String status() { return instance != null ? instance.status : ""; }
    public String parent() { return instance != null ? instance.parent : ""; }
    public String networkMode() { return instance != null ? instance.networkMode : ""; }

    public boolean isRunning() {
        return instance != null && "RUNNING".equalsIgnoreCase(instance.status);
    }

    public record InstanceState(String ipv4, String status, String parent, String networkMode) {}

    public record RepoInfo(String name, String path, String url, String hostPath) {
        public RepoInfo(String name, String path, String url) {
            this(name, path, url, null);
        }
    }
}
