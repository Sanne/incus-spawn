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
 * Wraps the incus CLI for container/VM lifecycle operations.
 */
@ApplicationScoped
public class IncusClient {

    private volatile Boolean needsSg;

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
            var uidGid = getUserUidGid(container, user);
            var homeDir = "/home/" + user;
            var targetCwd = workdir != null ? workdir : homeDir;

            var args = new ArrayList<String>();
            args.add("exec");
            args.add(container);
            args.add("--force-interactive");
            args.add("--user");
            args.add(uidGid.uid);
            args.add("--group");
            args.add(uidGid.gid);
            args.add("--cwd");
            args.add(targetCwd);
            args.add("--env");
            args.add("HOME=" + homeDir);
            args.add("--");

            if (shellCommand != null) {
                args.add("bash");
                args.add("--login");
                args.add("-c");
                args.add(shellCommand + " || exec bash --login");
            } else if (inTmux) {
                args.add("bash");
                args.add("--login");
            } else if (shouldAutoAttachTmux(container)) {
                args.add("bash");
                args.add("--login");
                args.add("-c");
                args.add("if command -v tmux >/dev/null 2>&1; then "
                        + "infocmp \"$TERM\" >/dev/null 2>&1 || export TERM=xterm-256color; "
                        + "exec tmux new-session -A -s isx; fi; exec bash --login");
            } else {
                args.add("bash");
                args.add("--login");
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

    private record UidGid(String uid, String gid) {}

    private UidGid getUserUidGid(String container, String username) {
        var result = shellExec(container, "id", "-u", username);
        if (!result.success()) {
            throw new IncusException("Failed to get UID for user " + username);
        }
        var uid = result.stdout().strip();

        result = shellExec(container, "id", "-g", username);
        if (!result.success()) {
            throw new IncusException("Failed to get GID for user " + username);
        }
        var gid = result.stdout().strip();

        return new UidGid(uid, gid);
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
     * Launch a new container or VM from an image.
     */
    public void launch(String image, String name, boolean vm) {
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
        exec("start", name).assertSuccess("Failed to start " + name);
    }

    /**
     * Stop a running container/VM.
     */
    public void stop(String name) {
        exec("stop", name).assertSuccess("Failed to stop " + name);
    }

    /**
     * Restart a container/VM.
     */
    public void restart(String name) {
        exec("restart", name).assertSuccess("Failed to restart " + name);
    }

    /**
     * Delete a container/VM.
     */
    public void delete(String name, boolean force) {
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
        exec("rename", oldName, newName).assertSuccess("Failed to rename " + oldName + " to " + newName);
    }

    /**
     * Set a config key on a container/VM.
     */
    public void configSet(String name, String key, String value) {
        exec("config", "set", name, key + "=" + value)
                .assertSuccess("Failed to set config " + key + " on " + name);
    }

    /**
     * Add a device to a container/VM.
     */
    public void deviceAdd(String container, String deviceName, String type, String... props) {
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
     */
    public void deviceRemove(String container, String deviceName) {
        exec("config", "device", "remove", container, deviceName)
                .assertSuccess("Failed to remove device " + deviceName + " from " + container);
    }

    /**
     * Get a specific config value.
     */
    public String configGet(String name, String key) {
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
     * List all instances with full details as JSON.
     * Returns the raw JSON string from 'incus list --format=json'.
     */
    public String listJson() {
        return exec("list", "--format=json")
                .assertSuccess("Failed to list instances")
                .stdout();
    }

    /**
     * Check if an instance exists.
     */
    public boolean exists(String name) {
        return exec("info", name).success();
    }

    /**
     * Push a file into a container.
     */
    public void filePush(String source, String container, String destPath) {
        exec("file", "push", source, container + destPath)
                .assertSuccess("Failed to push file to " + container);
    }

    /**
     * Push a directory recursively into a container.
     */
    public void filePushRecursive(String sourceDir, String container, String destPath) {
        exec("file", "push", "-r", sourceDir, container + destPath)
                .assertSuccess("Failed to push directory to " + container);
    }

    private String readStream(java.io.InputStream is) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(is))) {
            return reader.lines().collect(Collectors.joining("\n"));
        }
    }
}
