package dev.incusspawn.incus;

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

    private List<String> buildCommand(List<String> args) {
        var command = new ArrayList<String>();
        if (needsSg()) {
            command.add("sg");
            command.add("incus-admin");
            command.add("-c");
            var sb = new StringBuilder("incus");
            for (var arg : args) {
                sb.append(' ');
                if (arg.contains(" ") || arg.contains("'") || arg.contains("\"")) {
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
     * Open an interactive shell in a container, inheriting stdio.
     */
    public int interactiveShell(String container, String user) {
        System.out.print("\033]0;" + container + "\007");
        System.out.flush();
        try {
            var args = List.of("exec", container, "--", "su", "-", user);
            return execInteractive(args);
        } finally {
            System.out.print("\033]0;\007");
            System.out.flush();
        }
    }

    private static final java.util.Set<String> COW_DRIVERS = java.util.Set.of("btrfs", "zfs", "lvm");

    /**
     * Find the best copy-on-write storage pool, if one exists.
     * Returns the pool name, or null if no CoW pool is available.
     */
    public String findCowPool() {
        var result = exec("storage", "list", "--format=csv", "--columns=nD");
        if (!result.success()) return null;
        for (var line : result.stdout().strip().lines().toList()) {
            var parts = line.split(",", 2);
            if (parts.length >= 2 && COW_DRIVERS.contains(parts[1].strip())) {
                return parts[0].strip();
            }
        }
        return null;
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
