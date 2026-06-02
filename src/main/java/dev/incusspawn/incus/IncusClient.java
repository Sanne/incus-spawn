package dev.incusspawn.incus;

import dev.incusspawn.config.BuildSource;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages Incus container/VM lifecycle operations.
 * Uses the Incus REST API over Unix socket when available; falls back to the CLI otherwise.
 */
@ApplicationScoped
public class IncusClient {

    private volatile Boolean needsSg;
    private volatile boolean apiInitialized;
    private volatile IncusHttp api;

    private boolean needsSg() {
        if (needsSg == null) {
            synchronized (this) {
                if (needsSg == null) {
                    needsSg = detectSgRequirement();
                }
            }
        }
        return needsSg;
    }

    private IncusHttp api() {
        if (!apiInitialized) {
            synchronized (this) {
                if (!apiInitialized) {
                    api = IncusHttp.tryConnect();
                    apiInitialized = true;
                }
            }
        }
        return api;
    }

    private static boolean detectSgRequirement() {
        // Test with 'incus list' since 'incus version' succeeds even without daemon access.
        try {
            var pb = new ProcessBuilder("incus", "list", "--format=csv", "--columns=n");
            pb.redirectErrorStream(true);
            var p = pb.start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0) {
                return false;
            }
        } catch (Exception e) {
            // incus not installed or not accessible
        }

        // Direct access failed — check if sg would help
        try {
            var pb = new ProcessBuilder("sg", "incus-admin", "-c", "incus version");
            pb.redirectErrorStream(true);
            var p = pb.start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() == 0) {
                return true;
            }
        } catch (Exception e) {
            // sg not available or group doesn't exist
        }
        return false;
    }

    public record ExecResult(int exitCode, String stdout, String stderr) {
        public boolean success() {
            return exitCode == 0;
        }

        public ExecResult assertSuccess(String context) {
            if (!success()) {
                throw new IncusException(context + ": " + stderr.strip());
            }
            return this;
        }
    }

    private static boolean needsShellQuoting(String arg) {
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (Character.isLetterOrDigit(c) || "-_./=:,+@".indexOf(c) >= 0) {
                continue;
            }
            return true;
        }
        return false;
    }

    private List<String> buildCommand(List<String> args) {
        var command = new ArrayList<String>();
        if (needsSg()) {
            command.add("sg");
            command.add("incus-admin");
            command.add("-c");
            var sb = new StringBuilder("incus");
            for (var arg : args) {
                sb.append(' ');
                if (needsShellQuoting(arg)) {
                    sb.append("'").append(arg.replace("'", "'\\''")).append("'");
                } else {
                    sb.append(arg);
                }
            }
            command.add(sb.toString());
        } else {
            command.add("incus");
            command.addAll(args);
        }
        return command;
    }

    public ExecResult exec(String... args) {
        return exec(List.of(args));
    }

    public ExecResult exec(List<String> args) {
        var command = buildCommand(args);
        try {
            var pb = new ProcessBuilder(command);
            pb.environment().putAll(System.getenv());
            var process = pb.start();
            var stdout = readStream(process.getInputStream());
            var stderr = readStream(process.getErrorStream());
            int exitCode = process.waitFor();
            return new ExecResult(exitCode, stdout, stderr);
        } catch (IOException | InterruptedException e) {
            throw new IncusException("Failed to execute: incus " + String.join(" ", args), e);
        }
    }

    /**
     * Execute an incus command with inherited IO, so progress output is visible.
     * Use this for long-running operations like launch, image downloads, package installs.
     */
    public int execInteractive(String... args) {
        return execInteractive(List.of(args));
    }

    public int execInteractive(List<String> args) {
        var command = buildCommand(args);
        try {
            var pb = new ProcessBuilder(command);
            pb.inheritIO();
            return pb.start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new IncusException("Failed to execute: incus " + String.join(" ", args), e);
        }
    }

    /**
     * Build the full command list for running a shell command inside a container as a given user,
     * without executing it. Useful when the caller needs to manage the process directly
     * (e.g., for stdin/stdout piping in the git remote helper).
     */
    public List<String> buildExecCommand(String instance, String user, String shellCommand) {
        var args = new ArrayList<String>();
        args.add("exec");
        args.add(instance);
        args.add("--");
        args.add("su");
        args.add("-l");
        args.add(user);
        args.add("-c");
        args.add(shellCommand);
        return buildCommand(args);
    }

    /**
     * Build a command list for running a program directly inside a container, without a login
     * shell. Uses incus exec --user/--env flags to set the user and home directory.
     * This avoids shell init scripts that may produce stdout output, which is critical for
     * binary protocols like the git pack protocol.
     */
    public List<String> buildDirectExecCommand(String instance, int uid, String home,
                                                String... command) {
        var args = new ArrayList<String>();
        args.add("exec");
        args.add(instance);
        args.add("--user");
        args.add(String.valueOf(uid));
        args.add("--env");
        args.add("HOME=" + home);
        args.add("--");
        args.addAll(List.of(command));
        return buildCommand(args);
    }

    /**
     * Execute a command inside a container as a given user.
     */
    public ExecResult execInContainer(String container, String user, String... command) {
        var args = new ArrayList<String>();
        args.add("exec");
        args.add(container);
        args.add("--");
        args.add("su");
        args.add("-");
        args.add(user);
        if (command.length > 0) {
            args.add("-c");
            args.add(String.join(" ", command));
        }
        return exec(args);
    }

    /**
     * Execute a command inside a container as root, with inherited IO for progress output.
     */
    public int shellExecInteractive(String container, String... command) {
        var args = new ArrayList<String>();
        args.add("exec");
        args.add(container);
        args.add("--");
        args.addAll(List.of(command));
        return execInteractive(args);
    }

    /**
     * Execute a command inside a container as root.
     */
    public ExecResult shellExec(String container, String... command) {
        var args = new ArrayList<String>();
        args.add("exec");
        args.add(container);
        args.add("--");
        args.addAll(List.of(command));
        return exec(args);
    }

    /**
     * Poll a command inside a container until it succeeds or retries are exhausted.
     */
    public boolean pollUntilReady(String name, int maxAttempts, String... command) {
        for (int i = 0; i < maxAttempts; i++) {
            if (shellExec(name, command).success()) return true;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    public void waitForReady(String name) {
        pollUntilReady(name, 30, "true");
    }

    /**
     * Open an interactive shell in a container, inheriting stdio.
     */
    public int interactiveShell(String container, String user) {
        var workdir = configGet(container, Metadata.WORKDIR);
        var shellCmd = configGet(container, Metadata.SHELL_COMMAND);
        return interactiveShell(container, user,
                workdir.isBlank() ? null : workdir,
                shellCmd.isBlank() ? null : shellCmd);
    }

    private int interactiveShell(String container, String user, String workdir, String shellCommand) {
        System.out.print("\033]0;isx:" + container + "\007");
        System.out.flush();

        String savedWindowName = null;
        String savedStatusRight = null;
        boolean inTmux = System.getenv("TMUX") != null;
        if (inTmux) {
            savedWindowName = hostExecCapture("tmux", "display-message", "-p", "#W");
            hostExecQuiet("tmux", "rename-window", "isx:" + container);
            savedStatusRight = setTmuxSubnetWarning();
        }

        propagateTerminfo(container);

        try {
            List<String> args;
            var cdPrefix = workdir != null
                    ? "cd " + Container.shellQuote(workdir) + " 2>/dev/null; "
                    : "";

            if (shellCommand != null) {
                args = List.of("exec", container, "--force-interactive", "--", "su", "-", user, "-c",
                        cdPrefix + shellCommand + " || exec bash --login");
            } else if (inTmux) {
                args = List.of("exec", container, "--force-interactive", "--", "su", "-", user, "-c",
                        cdPrefix + "exec bash --login");
            } else if (shouldAutoAttachTmux(container)) {
                args = List.of("exec", container, "--force-interactive", "--", "su", "-", user, "-c",
                        cdPrefix
                        + "if command -v tmux >/dev/null 2>&1; then "
                        + "infocmp \"$TERM\" >/dev/null 2>&1 || export TERM=xterm-256color; "
                        + "exec tmux new-session -A -s isx; fi; exec bash --login");
            } else {
                args = List.of("exec", container, "--force-interactive", "--", "su", "-", user, "-c",
                        cdPrefix + "exec bash --login");
            }
            return execInteractive(args);
        } finally {
            if (inTmux && savedWindowName != null) {
                hostExecQuiet("tmux", "rename-window", savedWindowName);
            }
            if (savedStatusRight != null) {
                if (savedStatusRight.isEmpty()) {
                    hostExecQuiet("tmux", "set-option", "-u", "status-right");
                } else {
                    hostExecQuiet("tmux", "set-option", "status-right", savedStatusRight);
                }
            }
            System.out.print("\033]0;\007");
            System.out.flush();
        }
    }

    private boolean shouldAutoAttachTmux(String container) {
        var json = configGet(container, Metadata.BUILD_SOURCE);
        var bs = BuildSource.fromJson(json);
        if (bs == null) return false;
        var tmux = bs.getToolInstances().get("tmux");
        return tmux != null && Boolean.parseBoolean(tmux.getParameterValues().get("auto_attach"));
    }

    private String setTmuxSubnetWarning() {
        var diagnostic = BridgeSubnetCheck.detectConflictDiagnostic(this);
        if (diagnostic == null) return null;
        var saved = hostExecCapture("tmux", "show-option", "-v", "status-right");
        hostExecQuiet("tmux", "set-option", "status-right",
                "#[bg=yellow,fg=black,bold] ⚠ Bridge subnet conflict — run 'isx init' #[default]");
        return saved != null ? saved : "";
    }

    private static String hostExecCapture(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            return process.exitValue() == 0 ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void hostExecQuiet(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start().waitFor();
        } catch (IOException e) {
            // best-effort
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void propagateTerminfo(String container) {
        String term = System.getenv("TERM");
        if (term == null || term.isEmpty()) return;
        var check = shellExec(container, "infocmp", term);
        if (check.exitCode() == 0) return;
        String terminfo = hostExecCapture("infocmp", "-x", term);
        if (terminfo == null) return;
        shellExec(container, "sh", "-c",
                "cat <<'TERMINFO_EOF' | tic -x -\n" + terminfo + "\nTERMINFO_EOF");
    }

    private static final java.util.Set<String> COW_DRIVERS = java.util.Set.of("btrfs", "zfs", "lvm");

    public record CowPoolProbe(boolean listed, String poolName) {
    }

    public CowPoolProbe probeCowPool() {
        var http = api();
        if (http != null) {
            var resp = http.get("/1.0/storage-pools?recursion=1");
            if (!resp.isSuccess()) return new CowPoolProbe(false, null);
            for (var pool : resp.body().path("metadata")) {
                var driver = pool.path("driver").asText("");
                if (COW_DRIVERS.contains(driver)) {
                    return new CowPoolProbe(true, pool.path("name").asText());
                }
            }
            return new CowPoolProbe(true, null);
        }
        var result = exec("storage", "list", "--format=csv", "--columns=nD");
        if (!result.success()) return new CowPoolProbe(false, null);
        for (var line : result.stdout().strip().lines().toList()) {
            var parts = line.split(",", 2);
            if (parts.length >= 2 && COW_DRIVERS.contains(parts[1].strip())) {
                return new CowPoolProbe(true, parts[0].strip());
            }
        }
        return new CowPoolProbe(true, null);
    }

    /**
     * Find the best copy-on-write storage pool, if one exists.
     * Returns the pool name, or null if no CoW pool is available.
     */
    public String findCowPool() {
        return probeCowPool().poolName();
    }

    /**
     * Get a config value from a named network (e.g. "incusbr0").
     * Returns empty string if the key is not set.
     */
    public String networkConfigGet(String networkName, String key) {
        var http = api();
        if (http != null) {
            var resp = http.get("/1.0/networks/" + networkName);
            if (!resp.isSuccess()) {
                throw new IncusException("Failed to get network config " + key + " from " + networkName);
            }
            var value = resp.body().path("metadata").path("config").path(key);
            return value.isMissingNode() || value.isNull() ? "" : value.asText();
        }
        return exec("network", "get", networkName, key)
                .assertSuccess("Failed to get network config " + key + " from " + networkName)
                .stdout().strip();
    }

    /**
     * Set a config value on a named network (e.g. "incusbr0").
     */
    public void networkConfigSet(String networkName, String key, String value) {
        var http = api();
        if (http != null) {
            var resp = http.requestAndWait("PATCH", "/1.0/networks/" + networkName,
                    Map.of("config", Map.of(key, value)));
            if (!resp.isSuccess()) {
                throw new IncusException("Failed to set network config " + key + " on " + networkName);
            }
            return;
        }
        exec("network", "set", networkName, key, value)
                .assertSuccess("Failed to set network config " + key + " on " + networkName);
    }

    /**
     * Launch a new container or VM from an image.
     * Uses the REST API when available; falls back to the CLI (which shows download progress).
     */
    public void launch(String image, String name, boolean vm) {
        var http = api();
        if (http != null) {
            var cowPool = findCowPool();
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("name", name);
            body.put("type", vm ? "virtual-machine" : "container");
            body.put("source", Map.of("type", "image", "alias", image));
            if (cowPool != null) body.put("storage", cowPool);
            var resp = http.requestAndWait("POST", "/1.0/instances", body);
            if (!resp.isSuccess()) throw new IncusException("Failed to launch " + name);
            // Start the instance after creation
            var startResp = http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                    Map.of("action", "start", "timeout", 30, "force", false));
            if (!startResp.isSuccess()) throw new IncusException("Failed to start " + name + " after launch");
            return;
        }
        var args = new ArrayList<String>();
        args.add("launch");
        args.add(image);
        args.add(name);
        if (vm) {
            args.add("--vm");
        }
        var cowPool = findCowPool();
        if (cowPool != null) {
            args.add("--storage");
            args.add(cowPool);
        }
        int exitCode = execInteractive(args);
        if (exitCode != 0) {
            throw new IncusException("Failed to launch " + name + " (exit code " + exitCode + ")");
        }
    }

    /**
     * Copy (clone) an existing container/VM.
     * Automatically selects the best CoW storage pool if available.
     */
    public void copy(String source, String target, String... extraArgs) {
        var http = api();
        if (http != null && extraArgs.length == 0) {
            var cowPool = findCowPool();
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("name", target);
            body.put("source", Map.of("type", "copy", "source", source));
            if (cowPool != null) body.put("storage", cowPool);
            var resp = http.requestAndWait("POST", "/1.0/instances", body);
            if (!resp.isSuccess()) throw new IncusException("Failed to copy " + source + " to " + target);
            return;
        }
        var args = new ArrayList<String>();
        args.add("copy");
        args.add(source);
        args.add(target);
        args.addAll(List.of(extraArgs));
        var cowPool = findCowPool();
        if (cowPool != null) {
            args.add("--storage");
            args.add(cowPool);
        }
        exec(args).assertSuccess("Failed to copy " + source + " to " + target);
    }

    public String getLog(String instance) {
        return exec("info", "--show-log", instance).stdout();
    }

    /**
     * Start a stopped container/VM.
     */
    public void start(String name) {
        var http = api();
        if (http != null) {
            var resp = http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                    Map.of("action", "start", "timeout", 30, "force", false));
            if (!resp.isSuccess()) throw new IncusException("Failed to start " + name);
            return;
        }
        exec("start", name).assertSuccess("Failed to start " + name);
    }

    /**
     * Stop a running container/VM.
     */
    public void stop(String name) {
        var http = api();
        if (http != null) {
            var resp = http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                    Map.of("action", "stop", "timeout", 30, "force", false));
            if (!resp.isSuccess()) throw new IncusException("Failed to stop " + name);
            return;
        }
        exec("stop", name).assertSuccess("Failed to stop " + name);
    }

    /**
     * Restart a container/VM.
     */
    public void restart(String name) {
        var http = api();
        if (http != null) {
            var resp = http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                    Map.of("action", "restart", "timeout", 30, "force", false));
            if (!resp.isSuccess()) throw new IncusException("Failed to restart " + name);
            return;
        }
        exec("restart", name).assertSuccess("Failed to restart " + name);
    }

    /**
     * Delete a container/VM.
     * If force is true, stops the instance first (REST API does not accept delete of a running instance).
     */
    public void delete(String name, boolean force) {
        var http = api();
        if (http != null) {
            if (force) {
                try {
                    http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                            Map.of("action", "stop", "timeout", 30, "force", true));
                } catch (Exception ignored) {
                    // May already be stopped — proceed to delete.
                }
            }
            var resp = http.requestAndWait("DELETE", "/1.0/instances/" + name, null);
            if (!resp.isSuccess()) throw new IncusException("Failed to delete " + name);
            return;
        }
        var args = new ArrayList<String>();
        args.add("delete");
        args.add(name);
        if (force) {
            args.add("--force");
        }
        exec(args).assertSuccess("Failed to delete " + name);
    }

    public void deleteIfExists(String name) {
        if (exists(name)) {
            delete(name, true);
        }
    }

    public void rename(String oldName, String newName) {
        var http = api();
        if (http != null) {
            var resp = http.requestAndWait("POST", "/1.0/instances/" + oldName,
                    Map.of("name", newName, "migration", false));
            if (!resp.isSuccess()) throw new IncusException("Failed to rename " + oldName + " to " + newName);
            return;
        }
        exec("rename", oldName, newName).assertSuccess("Failed to rename " + oldName + " to " + newName);
    }

    /**
     * Set a config key on a container/VM.
     */
    public void configSet(String name, String key, String value) {
        var http = api();
        if (http != null) {
            var resp = http.requestAndWait("PATCH", "/1.0/instances/" + name,
                    Map.of("config", Map.of(key, value)));
            if (!resp.isSuccess()) {
                throw new IncusException("Failed to set config " + key + " on " + name);
            }
            return;
        }
        exec("config", "set", name, key + "=" + value)
                .assertSuccess("Failed to set config " + key + " on " + name);
    }

    /**
     * Add a device to a container/VM.
     */
    public void deviceAdd(String container, String deviceName, String type, String... props) {
        var http = api();
        if (http != null) {
            var device = new java.util.LinkedHashMap<String, String>();
            device.put("type", type);
            for (var prop : props) {
                int eq = prop.indexOf('=');
                if (eq > 0) device.put(prop.substring(0, eq), prop.substring(eq + 1));
            }
            var resp = http.requestAndWait("PATCH", "/1.0/instances/" + container,
                    Map.of("devices", Map.of(deviceName, device)));
            if (!resp.isSuccess()) throw new IncusException("Failed to add device " + deviceName + " to " + container);
            return;
        }
        var args = new ArrayList<String>();
        args.add("config");
        args.add("device");
        args.add("add");
        args.add(container);
        args.add(deviceName);
        args.add(type);
        args.addAll(List.of(props));
        exec(args).assertSuccess("Failed to add device " + deviceName + " to " + container);
    }

    /**
     * Remove a device from a container/VM.
     * Sends a PATCH with the device set to null, which removes it from the devices map.
     */
    public void deviceRemove(String container, String deviceName) {
        var http = api();
        if (http != null) {
            var devicesMap = new java.util.HashMap<String, Object>();
            devicesMap.put(deviceName, null);
            var resp = http.requestAndWait("PATCH", "/1.0/instances/" + container,
                    Map.of("devices", devicesMap));
            if (!resp.isSuccess()) throw new IncusException("Failed to remove device " + deviceName + " from " + container);
            return;
        }
        exec("config", "device", "remove", container, deviceName)
                .assertSuccess("Failed to remove device " + deviceName + " from " + container);
    }

    /**
     * Set a single property on an existing device without replacing the whole device.
     * Merges into the current device config via PATCH.
     */
    public void deviceConfigSet(String container, String deviceName, String key, String value) {
        var http = api();
        if (http != null) {
            var devicePatch = new java.util.HashMap<String, String>();
            devicePatch.put(key, value);
            var resp = http.requestAndWait("PATCH", "/1.0/instances/" + container,
                    Map.of("devices", Map.of(deviceName, devicePatch)));
            if (!resp.isSuccess()) {
                throw new IncusException("Failed to set device " + deviceName + "." + key + " on " + container);
            }
            return;
        }
        exec("config", "device", "set", container, deviceName, key + "=" + value)
                .assertSuccess("Failed to set device " + deviceName + "." + key + " on " + container);
    }

    /**
     * Remove (unset) a config key from a container/VM.
     * Uses null in the REST API PATCH body, which fully removes the key.
     */
    public void configUnset(String name, String key) {
        var http = api();
        if (http != null) {
            var configMap = new java.util.HashMap<String, Object>();
            configMap.put(key, null);
            var resp = http.requestAndWait("PATCH", "/1.0/instances/" + name,
                    Map.of("config", configMap));
            if (!resp.isSuccess()) throw new IncusException("Failed to unset config " + key + " on " + name);
            return;
        }
        exec("config", "unset", name, key)
                .assertSuccess("Failed to unset config " + key + " on " + name);
    }

    /**
     * Get the current status of an instance (e.g. "Running", "Stopped").
     * Returns empty string if the instance does not exist.
     */
    public String getInstanceStatus(String name) {
        var http = api();
        if (http != null) {
            var resp = http.get("/1.0/instances/" + name);
            if (!resp.isSuccess()) return "";
            return resp.body().path("metadata").path("status").asText("");
        }
        return exec("list", name, "--format=csv", "--columns=s").stdout().strip();
    }

    /**
     * Get a specific config value. Returns empty string if the key is not set.
     */
    public String configGet(String name, String key) {
        var http = api();
        if (http != null) {
            var resp = http.get("/1.0/instances/" + name);
            if (!resp.isSuccess()) {
                throw new IncusException("Failed to get config " + key + " from " + name);
            }
            var value = resp.body().path("metadata").path("config").path(key);
            return value.isMissingNode() || value.isNull() ? "" : value.asText();
        }
        return exec("config", "get", name, key)
                .assertSuccess("Failed to get config " + key + " from " + name)
                .stdout().strip();
    }

    /**
     * Mark an instance as having a pending operation.
     * This metadata is visible to all processes.
     */
    public void setPendingOperation(String name, String operation) {
        try {
            configSet(name, Metadata.PENDING_OP, operation);
        } catch (Exception e) {
            // If setting metadata fails (e.g., instance already deleted), ignore
        }
    }

    /**
     * Clear the pending operation marker from an instance.
     */
    public void clearPendingOperation(String name) {
        try {
            var http = api();
            if (http != null) {
                http.requestAndWait("PATCH", "/1.0/instances/" + name,
                        Map.of("config", Map.of(Metadata.PENDING_OP, "")));
                return;
            }
            exec("config", "unset", name, Metadata.PENDING_OP)
                    .assertSuccess("Failed to clear pending-op on " + name);
        } catch (Exception e) {
            // Instance may have been deleted between setting and clearing
        }
    }

    /**
     * Get the pending operation for an instance, or empty string if none.
     */
    public String getPendingOperation(String name) {
        try {
            return configGet(name, Metadata.PENDING_OP);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * List containers/VMs with their status and type.
     * Returns a list of maps with keys: name, status, type.
     */
    public List<Map<String, String>> list() {
        var http = api();
        if (http != null) {
            var resp = http.get("/1.0/instances?recursion=1");
            if (!resp.isSuccess()) {
                throw new IncusException("Failed to list instances: " + resp.body().path("error").asText());
            }
            var result = new ArrayList<Map<String, String>>();
            for (var instance : resp.body().path("metadata")) {
                result.add(Map.of(
                        "name", instance.path("name").asText(""),
                        "status", instance.path("status").asText(""),
                        "type", instance.path("type").asText("")
                ));
            }
            return result;
        }
        var result = exec("list", "--format=csv", "--columns=nst")
                .assertSuccess("Failed to list instances");
        if (result.stdout().isBlank()) {
            return List.of();
        }
        return result.stdout().strip().lines()
                .map(line -> {
                    var parts = line.split(",", 3);
                    return Map.of(
                            "name", parts[0],
                            "status", parts.length > 1 ? parts[1] : "",
                            "type", parts.length > 2 ? parts[2] : ""
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * List all instances with full details as a JSON array.
     * Uses recursion=2 to include network state for running instances,
     * matching the output format of 'incus list --format=json'.
     */
    public String listJson() {
        var http = api();
        if (http != null) {
            var resp = http.get("/1.0/instances?recursion=2");
            if (!resp.isSuccess()) {
                throw new IncusException("Failed to list instances: " + resp.body().path("error").asText());
            }
            return resp.body().path("metadata").toString();
        }
        return exec("list", "--format=json")
                .assertSuccess("Failed to list instances")
                .stdout();
    }

    /**
     * Check if an instance exists.
     */
    public boolean exists(String name) {
        var http = api();
        if (http != null) {
            return http.get("/1.0/instances/" + name).isSuccess();
        }
        return exec("info", name).success();
    }

    /**
     * Push a file into a container.
     */
    public void filePush(String source, String container, String destPath) {
        var http = api();
        if (http != null) {
            var resp = http.filePush(container, destPath, java.nio.file.Path.of(source));
            if (!resp.isSuccess()) throw new IncusException("Failed to push file to " + container + destPath);
            return;
        }
        exec("file", "push", source, container + destPath)
                .assertSuccess("Failed to push file to " + container);
    }

    /**
     * Push a directory recursively into a container.
     */
    public void filePushRecursive(String sourceDir, String container, String destPath) {
        var http = api();
        if (http != null) {
            http.filePushRecursive(container, destPath, java.nio.file.Path.of(sourceDir));
            return;
        }
        exec("file", "push", "-r", sourceDir, container + destPath)
                .assertSuccess("Failed to push directory to " + container);
    }

    private String readStream(java.io.InputStream is) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
