package dev.incusspawn.incus;

import java.util.ArrayList;
import java.util.Map;

/**
 * Host and system diagnostics read through Incus: memory, swap, CPU, uptime, inotify limits and
 * the kernel log, for {@code isx vm status}, {@code isx doctor} and a failed build's report.
 * Split out of {@link IncusClient}.
 */
public final class IncusDiagnostics {

    private final IncusClient incus;

    public IncusDiagnostics(IncusClient incus) {
        this.incus = incus;
    }

    /**
     * Read memory stats from /proc/meminfo via container exec.
     * Returns [MemTotal, MemAvailable, SwapTotal, SwapFree] in kB, or null on failure.
     */
    private long[] getMemoryInfo() {
        try {
            var instances = incus.list();
            var running = instances.stream()
                    .filter(i -> "Running".equals(i.get("status")))
                    .map(i -> i.get("name"))
                    .findFirst().orElse(null);
            if (running == null) return null;
            var result = incus.shellExec(running, "sh", "-c",
                    "awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/ {print $1, $2}' /dev/.lxc/proc/meminfo 2>/dev/null || awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/ {print $1, $2}' /proc/meminfo");
            if (!result.success()) return null;
            long memTotal = 0, memAvail = 0, swapTotal = 0, swapFree = 0;
            for (var line : result.stdout().strip().split("\n")) {
                var parts = line.split("\\s+");
                if (parts.length < 2) continue;
                long kB = Long.parseLong(parts[1]);
                switch (parts[0]) {
                    case "MemTotal:" -> memTotal = kB;
                    case "MemAvailable:" -> memAvail = kB;
                    case "SwapTotal:" -> swapTotal = kB;
                    case "SwapFree:" -> swapFree = kB;
                }
            }
            if (memTotal == 0) return null;
            return new long[]{memTotal, memAvail, swapTotal, swapFree};
        } catch (Exception e) {
            return null;
        }
    }

    public String getServerMemoryUsage() {
        var info = getMemoryInfo();
        if (info == null) return "";
        long memUsed = info[0] - info[1];
        var sb = new StringBuilder();
        sb.append("Server memory: %dMiB used / %dMiB total (%d%% used)".formatted(
                memUsed / 1024, info[0] / 1024, memUsed * 100 / info[0]));
        if (info[2] > 0) {
            long swapUsed = info[2] - info[3];
            sb.append(", swap: %dMiB used / %dMiB total".formatted(
                    swapUsed / 1024, info[2] / 1024));
        }
        return sb.toString();
    }

    /**
     * Query the kernel ring buffer (dmesg) for OOM or cgroup events
     * mentioning a specific container. Requires a running container
     * to exec into (any will do — dmesg shows host-level events).
     * Returns matching lines, or empty string if nothing found or
     * no running container is available.
     */
    public String queryDmesgForContainer(String containerName) {
        try {
            var instances = incus.list();
            var running = instances.stream()
                    .filter(i -> "Running".equals(i.get("status")))
                    .map(i -> i.get("name"))
                    .findFirst().orElse(null);
            if (running == null) return "";
            var result = incus.shellExec(running, "sh", "-c",
                    "dmesg | grep 'lxc.payload." + containerName + "' | tail -10");
            return result.success() ? result.stdout().strip() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Get the last N lines of the kernel ring buffer (dmesg).
     * Requires a running container to exec into (any will do — dmesg shows host-level events).
     */
    public String getDmesgTail(int lines) {
        try {
            var instances = incus.list();
            var running = instances.stream()
                    .filter(i -> "Running".equals(i.get("status")))
                    .map(i -> i.get("name"))
                    .findFirst().orElse(null);
            if (running == null) return "(no running container to query dmesg)";
            var result = incus.shellExec(running, "sh", "-c", "dmesg | tail -" + lines);
            return result.success() ? result.stdout().strip() : "(dmesg query failed)";
        } catch (Exception e) {
            return "(error querying dmesg: " + e.getMessage() + ")";
        }
    }

    /**
     * Read fs.inotify.max_user_instances from the kernel. On both Linux and macOS
     * this execs into a running container (the procfs value reflects the host kernel).
     * Returns -1 if no running container is available or the read fails.
     */
    public int getInotifyMaxInstances() {
        try {
            var instances = incus.list();
            var running = instances.stream()
                    .filter(i -> "Running".equals(i.get("status")))
                    .map(i -> i.get("name"))
                    .findFirst().orElse(null);
            if (running == null) return -1;
            var result = incus.shellExec(running, "cat", "/proc/sys/fs/inotify/max_user_instances");
            if (result.success()) {
                return Integer.parseInt(result.stdout().strip());
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Get system uptime from /proc/uptime via a running container.
     */
    public String getSystemUptime() {
        try {
            var instances = incus.list();
            var running = instances.stream()
                    .filter(i -> "Running".equals(i.get("status")))
                    .map(i -> i.get("name"))
                    .findFirst().orElse(null);
            if (running == null) return "(no running container)";
            var result = incus.shellExec(running, "cat", "/proc/uptime");
            if (!result.success()) return "(could not read uptime)";
            var parts = result.stdout().strip().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) {
                return "(malformed uptime data)";
            }
            var uptimeSeconds = (long) Double.parseDouble(parts[0]);
            long days = uptimeSeconds / 86400;
            long hours = (uptimeSeconds % 86400) / 3600;
            long minutes = (uptimeSeconds % 3600) / 60;
            if (days > 0) {
                return "%d days, %d hours, %d minutes".formatted(days, hours, minutes);
            } else if (hours > 0) {
                return "%d hours, %d minutes".formatted(hours, minutes);
            } else {
                return "%d minutes".formatted(minutes);
            }
        } catch (Exception e) {
            return "(error: " + e.getMessage() + ")";
        }
    }

    /**
     * Get CPU information from /1.0/resources.
     */
    public String getCpuInfo() {
        var resp = incus.http().get("/1.0/resources");
        if (!resp.isSuccess()) return "(could not query resources)";
        var cpu = resp.body().path("metadata").path("cpu");
        int total = cpu.path("total").asInt(0);
        if (total == 0) return "(no CPU info)";
        // CPU usage is not directly available from /1.0/resources, only architecture and count
        var arch = cpu.path("architecture").asText("unknown");
        return "%d CPU cores (%s)".formatted(total, arch);
    }

    /**
     * Get swap usage from /1.0/resources.
     */
    public String getSwapUsage() {
        var resp = incus.http().get("/1.0/resources");
        if (!resp.isSuccess()) return "(could not query resources)";
        var mem = resp.body().path("metadata").path("memory");
        long swapTotal = mem.path("swap_total").asLong(0);
        long swapUsed = mem.path("swap_used").asLong(0);
        if (swapTotal == 0) return "no swap configured";
        return "%dMiB used / %dMiB total (%d%% used)".formatted(
                swapUsed / (1024 * 1024), swapTotal / (1024 * 1024),
                swapUsed * 100 / swapTotal);
    }

    /**
     * Get comprehensive system diagnostics suitable for 'isx vm status'.
     * Returns a formatted multi-line status report including CPU, memory, disk,
     * containers, kernel log, and uptime.
     */
    public String getSystemDiagnostics(String poolName) {
        var sb = new StringBuilder();

        var serverResp = incus.http().get("/1.0");
        if (serverResp.isSuccess()) {
            var env = serverResp.body().path("metadata").path("environment");
            var kernel = env.path("kernel").asText("");
            var kernelVer = env.path("kernel_version").asText("");
            if (!kernel.isEmpty() && !kernelVer.isEmpty()) {
                sb.append("Kernel: ").append(kernel).append(" ").append(kernelVer).append("\n");
            }
            var osName = env.path("os_name").asText("");
            var osVer = env.path("os_version").asText("");
            if (!osName.isEmpty()) {
                sb.append("OS: ").append(osName);
                if (!osVer.isEmpty()) sb.append(" ").append(osVer);
                sb.append("\n");
            }
            var serverVer = env.path("server_version").asText("");
            if (!serverVer.isEmpty()) {
                sb.append("Incus: ").append(serverVer).append("\n");
            }
        }

        // CPU from /1.0/resources (reliable)
        var resourcesResp = incus.http().get("/1.0/resources");
        if (resourcesResp.isSuccess()) {
            var cpu = resourcesResp.body().path("metadata").path("cpu");
            int cpuTotal = cpu.path("total").asInt(0);
            if (cpuTotal > 0) {
                var arch = cpu.path("architecture").asText("unknown");
                sb.append("CPU: ").append("%d cores (%s)".formatted(cpuTotal, arch)).append("\n");
            }
        }

        // Memory and swap from /proc/meminfo via container exec (resources API doesn't report swap)
        var memInfo = getMemoryInfo();
        if (memInfo != null) {
            long memUsed = memInfo[0] - memInfo[1];
            sb.append("Memory: %dMiB used / %dMiB total (%d%% used)".formatted(
                    memUsed / 1024, memInfo[0] / 1024,
                    memInfo[0] > 0 ? memUsed * 100 / memInfo[0] : 0)).append("\n");
            if (memInfo[2] > 0) {
                long swapUsed = memInfo[2] - memInfo[3];
                sb.append("Swap: %dMiB used / %dMiB total (%d%% used)".formatted(
                        swapUsed / 1024, memInfo[2] / 1024,
                        swapUsed * 100 / memInfo[2])).append("\n");
            } else {
                sb.append("Swap: no swap configured\n");
            }
        } else if (resourcesResp.isSuccess()) {
            var mem = resourcesResp.body().path("metadata").path("memory");
            long total = mem.path("total").asLong(0);
            long used = mem.path("used").asLong(0);
            if (total > 0) {
                long totalMiB = total / (1024 * 1024);
                long usedMiB = used / (1024 * 1024);
                sb.append("Memory: %dMiB used / %dMiB total (%d%% used)".formatted(
                        usedMiB, totalMiB, used * 100 / total)).append("\n");
            }
        }

        // Disk
        var diskUsage = incus.getStoragePoolUsage(poolName);
        if (!diskUsage.isEmpty()) {
            sb.append("Disk: ").append(diskUsage.replace(poolName + " pool: ", "")).append("\n");
        }

        // Containers - use recursion=2 to get memory usage in one API call
        try {
            var resp = incus.http().get("/1.0/instances?recursion=2");
            if (!resp.isSuccess()) {
                sb.append("Containers: (error listing: ").append(resp.body().path("error").asText()).append(")\n");
            } else {
                var instancesJson = resp.body().path("metadata");
                int totalCount = 0;
                var runningInstances = new ArrayList<Map<String, Object>>();

                for (var instance : instancesJson) {
                    totalCount++;
                    if ("Running".equals(instance.path("status").asText())) {
                        var name = instance.path("name").asText("");
                        var memUsage = instance.path("state").path("memory").path("usage").asLong(0);
                        runningInstances.add(Map.of("name", name, "memory", memUsage));
                    }
                }

                sb.append("\nContainers: ").append(runningInstances.size()).append(" running / ")
                  .append(totalCount).append(" total\n");

                if (!runningInstances.isEmpty()) {
                    sb.append("\nRunning containers:\n");
                    for (var container : runningInstances) {
                        sb.append("  ").append(container.get("name")).append(": ")
                          .append((long)container.get("memory") / (1024 * 1024)).append(" MiB\n");
                    }
                }
            }
        } catch (Exception e) {
            sb.append("Containers: (error listing: ").append(e.getMessage()).append(")\n");
        }

        // Uptime
        sb.append("\nUptime: ").append(getSystemUptime()).append("\n");

        // Kernel log (last 20 lines)
        sb.append("\nKernel log (last 20 lines):\n");
        var dmesg = getDmesgTail(20);
        if (dmesg.isEmpty()) {
            sb.append("  (no output)\n");
        } else {
            for (var line : dmesg.split("\n")) {
                sb.append("  ").append(line).append("\n");
            }
        }

        return sb.toString();
    }
}
