package dev.incusspawn.incus;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.incusspawn.config.BuildSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages Incus container/VM lifecycle operations.
 * Uses the Incus REST API over Unix socket (Linux) or HTTPS (macOS/remote).
 */
public class IncusClient {

    private volatile boolean apiInitialized;
    private volatile IncusApi api;

    private IncusApi api() {
        if (!apiInitialized) {
            synchronized (this) {
                if (!apiInitialized) {
                    api = IncusApi.tryConnect();
                    apiInitialized = true;
                }
            }
        }
        return api;
    }

    private IncusApi http() {
        var result = api();
        if (result == null) {
            throw new IncusException(IncusApi.diagnoseConnectionFailure());
        }
        return result;
    }

    /**
     * Probe the daemon and return its version string, or "unknown" if unreachable.
     * Used for informational display (isx version, proxy status). Static so it can
     * be called at class-init time from Environment.java.
     */
    public static String daemonVersion() {
        var http = IncusApi.tryConnect();
        if (http == null) return "unknown";
        try {
            var resp = http.get("/1.0");
            if (resp.isSuccess()) {
                var ver = resp.body().path("metadata").path("environment")
                        .path("server_version").asText("");
                if (!ver.isEmpty()) return ver;
                return resp.body().path("metadata").path("api_version").asText("unknown");
            }
        } catch (Exception ignored) {}
        return "unknown";
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

    /**
     * Execute a command inside a container as root.
     */
    public ExecResult shellExec(String container, String... command) {
        return http().execCapture(container, List.of(command), 0, 0, null, Map.of());
    }

    /**
     * Execute a command inside a container as root with inherited IO (visible output).
     */
    public int shellExecInteractive(String container, String... command) {
        return http().execStream(container, List.of(command), 0, 0, null, Map.of(),
                System.out, System.err);
    }

    // su - replaces the process environment with the user's login environment,
    // which on Fedora/RHEL defaults to PATH=/bin:/usr/bin (from login.defs).
    // The old incus CLI injected /usr/local/bin into PATH before su ran, so it
    // was inherited. With the REST API we must inject it into the script itself.
    static final String LOGIN_PATH_PREFIX =
            "export PATH=/usr/local/sbin:/usr/local/bin:$PATH; ";

    /**
     * Run a shell script inside a container as a specific user with inherited IO.
     * Uses 'su - user -c script' to establish a login session (sources /etc/profile
     * and ~/.profile or ~/.bash_profile) so that SDKMAN, custom PATH additions,
     * and other user-level environment setup are available.
     */
    public int shellExecInteractiveAsUser(String container, String user, String script) {
        return http().execStream(container,
                List.of("su", "-", user, "-c", LOGIN_PATH_PREFIX + script),
                0, 0, null, Map.of(), System.out, System.err);
    }

    /**
     * Execute a command inside a container as a given user.
     * Uses 'su - user -c "joined cmd"' to replicate the original CLI behaviour,
     * giving the command access to the user's full login environment.
     */
    public ExecResult execInContainer(String container, String user, String... command) {
        var script = command.length > 0 ? String.join(" ", command) : "bash";
        return http().execCapture(container,
                List.of("su", "-", user, "-c", LOGIN_PATH_PREFIX + script),
                0, 0, null, Map.of());
    }

    /**
     * Execute a bidirectional command inside a container, forwarding stdin from the host.
     * Used for binary protocols like the git pack protocol.
     */
    public int execBidirectional(String container, int uid, int gid, String home, String[] command,
                                  InputStream stdin, OutputStream stdout, OutputStream stderr) {
        return http().execBidirectional(container, List.of(command), uid, gid, home,
                Map.of("HOME", home), stdin, stdout, stderr);
    }

    /**
     * Poll a command inside a container until it succeeds or the timeout expires.
     * @param timeoutSeconds maximum wait time in seconds; polls every 200ms for fast detection.
     */
    public boolean pollUntilReady(String name, int timeoutSeconds, String... command) {
        int maxAttempts = timeoutSeconds * 5;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                if (shellExec(name, command).success()) return true;
            } catch (Exception ignored) {
                // Container may not be Running yet — treat any exec failure as not-ready and retry.
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    public void waitForReady(String name) {
        if (!pollUntilReady(name, 30, "echo", "ready")) {
            throw new IncusException("Container " + name + " failed to become ready after 30 seconds");
        }
    }

    /**
     * Check connectivity to the Incus daemon.
     * Returns null if connected successfully, or a diagnostic message if not.
     */
    public String checkConnectivity() {
        if (api() != null) return null;
        return IncusApi.diagnoseConnectionFailure();
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

            List<String> shellArgs;
            if (shellCommand != null) {
                shellArgs = List.of("bash", "--login", "-c", shellCommand + " || exec bash --login");
            } else if (inTmux) {
                shellArgs = List.of("bash", "--login");
            } else if (shouldAutoAttachTmux(container)) {
                shellArgs = List.of("bash", "--login", "-c",
                        "if command -v tmux >/dev/null 2>&1; then "
                        + "infocmp \"$TERM\" >/dev/null 2>&1 || export TERM=xterm-256color; "
                        + "exec tmux new-session -A -s isx; fi; exec bash --login");
            } else {
                shellArgs = List.of("bash", "--login");
            }

            var size = IncusApi.terminalSize();
            return http().execPty(container, shellArgs,
                    Integer.parseInt(uidGid.uid()), Integer.parseInt(uidGid.gid()),
                    targetCwd, Map.of("HOME", homeDir),
                    size[0], size[1]);
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

    private static final Set<String> COW_DRIVERS = Set.of("btrfs", "zfs", "lvm");

    public record CowPoolProbe(boolean listed, String poolName) {
    }

    public CowPoolProbe probeCowPool() {
        var resp = http().get("/1.0/storage-pools?recursion=1");
        if (!resp.isSuccess()) return new CowPoolProbe(false, null);
        for (var pool : resp.body().path("metadata")) {
            var driver = pool.path("driver").asText("");
            if (COW_DRIVERS.contains(driver)) {
                return new CowPoolProbe(true, pool.path("name").asText());
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
        var resp = http().get("/1.0/networks/" + networkName);
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to get network config " + key + " from " + networkName);
        }
        var value = resp.body().path("metadata").path("config").path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    /**
     * Set a config value on a named network (e.g. "incusbr0").
     */
    public void networkConfigSet(String networkName, String key, String value) {
        var resp = http().requestAndWait("PATCH", "/1.0/networks/" + networkName,
                Map.of("config", Map.of(key, value)));
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to set network config " + key + " on " + networkName);
        }
    }

    /**
     * Detach the named network from an instance by finding and removing the matching NIC device.
     * If the NIC is inherited from a profile (not in instance devices), it is first overridden
     * into the instance's own devices so it can be removed.
     */
    public void networkDetach(String instance, String networkName) {
        var resp = http().get("/1.0/instances/" + instance);
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to get instance " + instance + " for network detach");
        }
        var metadata = resp.body().path("metadata");
        var instanceDevices = metadata.path("devices");
        var expandedDevices = metadata.path("expanded_devices");
        for (var it = expandedDevices.fields(); it.hasNext(); ) {
            var entry = it.next();
            var dev = entry.getValue();
            if ("nic".equals(dev.path("type").asText()) &&
                (networkName.equals(dev.path("network").asText()) ||
                 networkName.equals(dev.path("parent").asText()))) {
                var devName = entry.getKey();
                if (instanceDevices.path(devName).isMissingNode()) {
                    var override = new LinkedHashMap<String, String>();
                    dev.fields().forEachRemaining(e -> override.put(e.getKey(), e.getValue().asText()));
                    var overrideResp = http().requestAndWait("PATCH", "/1.0/instances/" + instance,
                            Map.of("devices", Map.of(devName, override)));
                    if (!overrideResp.isSuccess()) {
                        throw new IncusException("Failed to override profile device " + devName
                                + " on " + instance + " for network detach");
                    }
                }
                deviceRemove(instance, devName);
                return;
            }
        }
    }

    /**
     * Launch a new container or VM from an image.
     * The image may be a local alias ("my-image") or a remote reference
     * ("images:fedora/44"). Remote references are resolved by reading the
     * Incus client config (~/.config/incus/config.yml) to get the server
     * URL and protocol — the REST API does not understand the "remote:alias"
     * CLI shorthand and needs the full server URL instead.
     */
    public void launch(String image, String name, boolean vm) {
        var http = http();
        var cowPool = findCowPool();
        var body = new LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("type", vm ? "virtual-machine" : "container");
        body.put("source", resolveImageSource(image));
        if (cowPool != null) body.put("storage", cowPool);
        var resp = http.requestAndWait("POST", "/1.0/instances", body);
        if (!resp.isSuccess()) throw new IncusException("Failed to launch " + name);
        var startResp = http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                Map.of("action", "start", "timeout", 30, "force", false));
        if (!startResp.isSuccess()) throw new IncusException("Failed to start " + name + " after launch");
    }

    /**
     * Build the REST API source object for an image reference.
     * Handles both local aliases and "remote:alias" notation by reading
     * the Incus client config to resolve the remote's server URL.
     */
    private static Map<String, Object> resolveImageSource(String image) {
        if (image.startsWith("sha256:")) {
            return Map.of("type", "image", "fingerprint", image.substring("sha256:".length()));
        }
        int colon = image.indexOf(':');
        if (colon < 0) {
            return Map.of("type", "image", "alias", image);
        }
        var remoteName = image.substring(0, colon);
        var alias = image.substring(colon + 1);
        var remote = readIncusRemote(remoteName);
        if (remote == null) {
            throw new IncusException(
                    "Unknown Incus remote '" + remoteName + "' for image '" + image + "'. " +
                    "Add it with: incus remote add " + remoteName + " <url>");
        }
        var source = new LinkedHashMap<String, Object>();
        source.put("type", "image");
        source.put("mode", "pull");
        source.put("server", remote.addr());
        source.put("protocol", remote.protocol());
        source.put("alias", alias);
        return source;
    }

    private record RemoteConfig(String addr, String protocol) {}

    /**
     * Read a named remote's configuration from the Incus client config file.
     * Returns null if the remote is not found.
     */
    private static RemoteConfig readIncusRemote(String name) {
        var home = System.getProperty("user.home", "");
        var candidates = List.of(
                Path.of(home, ".config", "incus", "config.yml"),
                Path.of(home, ".local", "share", "incus", "config.yml")
        );
        for (var path : candidates) {
            if (!Files.exists(path)) continue;
            try {
                var yaml = new YAMLMapper();
                var root = yaml.readTree(path.toFile());
                var remoteNode = root.path("remotes").path(name);
                if (remoteNode.isMissingNode()) continue;
                var addr = remoteNode.path("addr").asText("");
                var protocol = remoteNode.path("protocol").asText("simplestreams");
                if (!addr.isEmpty()) return new RemoteConfig(addr, protocol);
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Copy (clone) an existing container/VM.
     * Automatically selects the best CoW storage pool if available.
     */
    public void copy(String source, String target) {
        var http = http();
        var cowPool = findCowPool();
        var body = new LinkedHashMap<String, Object>();
        body.put("name", target);
        body.put("source", Map.of("type", "copy", "source", source));
        if (cowPool != null) body.put("storage", cowPool);
        var resp = http.requestAndWait("POST", "/1.0/instances", body);
        if (!resp.isSuccess()) throw new IncusException("Failed to copy " + source + " to " + target);
    }

    public String getLog(String instance) {
        var logsResp = http().get("/1.0/instances/" + instance + "/logs");
        if (!logsResp.isSuccess()) return "";
        var logs = logsResp.body().path("metadata");
        // Prefer lxc.console (actual console output with kernel messages like "Exec format error").
        // Fall back to the last entry in the list if the console log isn't present.
        String consolePath = null, lastPath = null;
        for (var log : logs) {
            var path = log.asText();
            lastPath = path;
            if (path.endsWith("lxc.console")) consolePath = path;
        }
        var logPath = consolePath != null ? consolePath : lastPath;
        if (logPath == null) return "";
        return http().getText(logPath);
    }

    /**
     * Start a stopped container/VM.
     */
    public void start(String name) {
        var resp = http().requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                Map.of("action", "start", "timeout", 30, "force", false));
        if (!resp.isSuccess()) throw new IncusException("Failed to start " + name);
    }

    /**
     * Stop a running container/VM.
     */
    public void stop(String name) {
        var resp = http().requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                Map.of("action", "stop", "timeout", 30, "force", false));
        if (!resp.isSuccess()) throw new IncusException("Failed to stop " + name);
    }

    /**
     * Force-stop a container/VM, even if it is in an Error state.
     */
    public void forceStop(String name) {
        var resp = http().requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                Map.of("action", "stop", "timeout", 30, "force", true));
        if (!resp.isSuccess()) throw new IncusException("Failed to force-stop " + name);
    }

    /**
     * Restart a container/VM.
     */
    public void restart(String name) {
        var resp = http().requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                Map.of("action", "restart", "timeout", 30, "force", false));
        if (!resp.isSuccess()) throw new IncusException("Failed to restart " + name);
    }

    /**
     * Delete a container/VM.
     * If force is true, stops the instance first (REST API does not accept delete of a running instance).
     */
    public void delete(String name, boolean force) {
        var http = http();
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
    }

    public void deleteIfExists(String name) {
        if (exists(name)) {
            delete(name, true);
        }
    }

    public void rename(String oldName, String newName) {
        var resp = http().requestAndWait("POST", "/1.0/instances/" + oldName,
                Map.of("name", newName, "migration", false));
        if (!resp.isSuccess()) throw new IncusException("Failed to rename " + oldName + " to " + newName);
    }

    /**
     * Set a config key on a container/VM.
     */
    public void configSet(String name, String key, String value) {
        var resp = http().requestAndWait("PATCH", "/1.0/instances/" + name,
                Map.of("config", Map.of(key, value)));
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to set config " + key + " on " + name);
        }
    }

    public void configSetAll(String name, Map<String, String> config) {
        if (config.isEmpty()) return;
        var resp = http().requestAndWait("PATCH", "/1.0/instances/" + name,
                Map.of("config", config));
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to set config on " + name);
        }
    }

    public void configUpdate(String name, Map<String, Object> config) {
        if (config.isEmpty()) return;
        var resp = http().requestAndWait("PATCH", "/1.0/instances/" + name,
                Map.of("config", config));
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to update config on " + name);
        }
    }

    /**
     * Add a device to a container/VM.
     */
    public void deviceAdd(String container, String deviceName, String type, String... props) {
        var device = new LinkedHashMap<String, String>();
        device.put("type", type);
        for (var prop : props) {
            int eq = prop.indexOf('=');
            if (eq > 0) device.put(prop.substring(0, eq), prop.substring(eq + 1));
        }
        var resp = http().requestAndWait("PATCH", "/1.0/instances/" + container,
                Map.of("devices", Map.of(deviceName, device)));
        if (!resp.isSuccess()) throw new IncusException("Failed to add device " + deviceName + " to " + container);
    }

    /**
     * Remove a device from a container/VM.
     * Uses a read-modify-write via GET + PUT because Incus PATCH cannot remove devices
     * (null values are rejected with 400, empty objects with 500).
     */
    public void deviceRemove(String container, String deviceName) {
        var resp = http().removeDevice(container, deviceName);
        if (!resp.isSuccess()) throw new IncusException("Failed to remove device " + deviceName + " from " + container);
    }

    public void devicesRemoveAll(String container, Collection<String> deviceNames) {
        if (deviceNames.isEmpty()) return;
        var resp = http().removeDevices(container, deviceNames);
        if (!resp.isSuccess()) throw new IncusException(
                "Failed to remove devices " + deviceNames + " from " + container);
    }

    /**
     * Set a single property on an existing device, merging with its current expanded config.
     * Incus requires a complete device config in PATCH; a partial device (missing type) is rejected.
     * Uses a read-modify-write: GET expanded_devices, merge the key, PATCH back.
     */
    public void deviceConfigSet(String container, String deviceName, String key, String value) {
        var resp = http().deviceConfigSet(container, deviceName, key, value);
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to set device " + deviceName + "." + key + " on " + container);
        }
    }

    /**
     * Remove (unset) a config key from a container/VM.
     * Uses null in the REST API PATCH body, which fully removes the key.
     */
    public void configUnset(String name, String key) {
        var configMap = new HashMap<String, Object>();
        configMap.put(key, null);
        var resp = http().requestAndWait("PATCH", "/1.0/instances/" + name,
                Map.of("config", configMap));
        if (!resp.isSuccess()) throw new IncusException("Failed to unset config " + key + " on " + name);
    }

    /**
     * Get the current status of an instance (e.g. "Running", "Stopped").
     * Returns empty string if the instance does not exist.
     */
    public String getInstanceStatus(String name) {
        var resp = http().get("/1.0/instances/" + name);
        if (!resp.isSuccess()) return "";
        return resp.body().path("metadata").path("status").asText("");
    }

    /**
     * Get a specific config value. Returns empty string if the key is not set.
     */
    public String configGet(String name, String key) {
        var resp = http().get("/1.0/instances/" + name);
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to get config " + key + " from " + name);
        }
        var value = resp.body().path("metadata").path("config").path(key);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    /**
     * Get the container's architecture (e.g. "x86_64", "aarch64").
     * Throws IncusException if the instance does not exist or has no architecture metadata.
     */
    public String getInstanceArchitecture(String name) {
        var resp = http().get("/1.0/instances/" + name);
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to get architecture for instance " + name);
        }
        var arch = resp.body().path("metadata").path("architecture").asText("");
        if (arch.isEmpty()) {
            throw new IncusException("Instance " + name + " has no architecture metadata");
        }
        return arch;
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
            configUnset(name, Metadata.PENDING_OP);
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
        var resp = http().get("/1.0/instances?recursion=1");
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

    /**
     * List all instances with full details as a JSON array.
     * Uses recursion=2 to include network state for running instances,
     * matching the output format of 'incus list --format=json'.
     */
    public String listJson() {
        var resp = http().get("/1.0/instances?recursion=2");
        if (!resp.isSuccess()) {
            throw new IncusException("Failed to list instances: " + resp.body().path("error").asText());
        }
        return resp.body().path("metadata").toString();
    }

    /**
     * Check if an instance exists.
     */
    public boolean exists(String name) {
        return http().get("/1.0/instances/" + name).isSuccess();
    }

    /**
     * Push a file into a container.
     */
    public void filePush(String source, String container, String destPath) {
        var resp = http().filePush(container, destPath, Path.of(source));
        if (!resp.isSuccess()) throw new IncusException("Failed to push file to " + container + destPath);
    }

    /**
     * Push a directory recursively into a container.
     */
    public void filePushRecursive(String sourceDir, String container, String destPath) {
        http().filePushRecursive(container, destPath, Path.of(sourceDir));
    }
}
