package dev.incusspawn.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.command.BuildTools.ResolvedTool;
import dev.incusspawn.command.BuildTools.ToolResolution;
import dev.incusspawn.config.AgentContextGenerator;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.EnvResolver;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.CodexSetup;
import dev.incusspawn.tool.ToolVerifier;
import dev.incusspawn.util.BuildOutput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.command.BuildProgress.dnfCommand;
import static dev.incusspawn.command.BuildProgress.runDnf;
import static dev.incusspawn.command.BuildProgress.runWithSpinner;
import static dev.incusspawn.command.BuildProgress.stepFrom;

/**
 * The steps a build runs in its guest: packages and their repos, tool setup, the environment
 * and agent context files, SELinux on a VM, the agents' repo trust, and the shared DNF cache.
 */
class GuestProvisioning {

    private final IncusClient incus;

    GuestProvisioning(IncusClient incus) {
        this.incus = incus;
    }

    private static final String DNF_CACHE_DEVICE = "dnf-cache";

    static void removePackages(Container container, ImageDef imageDef) {
        var pkgs = imageDef.getRemovePackages();
        if (pkgs.isEmpty()) return;
        BuildOutput.step("Removing unnecessary packages...");
        container.sh(
                "dnf remove -y --setopt=clean_requirements_on_remove=True " +
                String.join(" ", pkgs) + " 2>/dev/null; true");
    }

    static final String SELINUX_CONFIG = "/etc/selinux/config";

    /**
     * Pins a VM guest's SELinux to {@code disabled} before any package is installed (#842).
     * The base image ships no policy and a filesystem nothing ever labelled, but a package
     * like {@code perl} pulls in {@code selinux-policy-targeted}, whose {@code %post} writes
     * {@code SELINUX=enforcing}: the next boot then denies the incus-agent's vsock
     * {@code listen} and the instance is unreachable. Relabelling cannot fix that -- the
     * targeted policy has no rule for the agent at all. The {@code %post} only writes the
     * file when it is missing or empty, so a non-empty file seeded here also survives every
     * later install, in the template and in its branches. An existing file is rewritten, not
     * skipped. That cannot rescue a parent whose file already says {@code enforcing}: its copy
     * boots enforcing and the agent is gone before this runs. Such a parent was built by an
     * older isx, so {@code isImageOutdated} has {@code buildChain} rebuild it first.
     */
    static void disableGuestSelinux(Container container) {
        container.sh(disableSelinuxScript(SELINUX_CONFIG))
                .assertSuccess("Failed to disable SELinux in the VM guest");
    }

    static String disableSelinuxScript(String config) {
        return "if grep -q '^[[:space:]]*SELINUX=' " + config + " 2>/dev/null; then "
                + "sed -i 's/^[[:space:]]*SELINUX=.*/SELINUX=disabled/' " + config + "; "
                + "else mkdir -p \"$(dirname " + config + ")\" && "
                + "printf '%s\\n' '# Set by incus-spawn: the guest filesystem is not labelled (issue #842).' "
                + "SELINUX=disabled SELINUXTYPE=targeted >> " + config + "; fi";
    }

    /**
     * Fails the build if the VM guest would boot SELinux enforcing, which kills the
     * incus-agent on the next boot. The build itself runs on the boot before the policy
     * takes effect, so without this the breakage only shows up later, on {@code isx shell}.
     */
    static void assertGuestSelinuxNotEnforcing(Container container) {
        if (!container.sh(selinuxNotEnforcingScript(SELINUX_CONFIG)).success()) {
            throw new IllegalStateException("The VM guest would boot SELinux enforcing: "
                    + SELINUX_CONFIG + " was set to 'enforcing' during the build (a package or "
                    + "tool setup rewrote it). The guest filesystem is not labelled and the "
                    + "policy denies the incus-agent, so the instance would be unreachable.");
        }
    }

    static String selinuxNotEnforcingScript(String config) {
        return "! grep -qiE '^[[:space:]]*SELINUX=[[:space:]]*\"?enforcing' " + config + " 2>/dev/null";
    }

    static void maskServices(Container container, ImageDef imageDef) {
        var services = imageDef.getMaskServices();
        if (services.isEmpty()) return;
        BuildOutput.step("Masking unnecessary services...");
        container.sh(
                "systemctl mask " + String.join(" ", services) + " 2>/dev/null; true");
    }

    /**
     * Collect all packages from the image definition and its tools,
     * subtract those already installed by ancestor images, and install
     * only the remaining packages. Accepts pre-resolved ancestor tools
     * to avoid redundant resolution.
     */
    static void installAllPackages(Container container, ImageDef imageDef,
                                    List<ResolvedTool> tools,
                                    List<ResolvedTool> ancestorTools,
                                    Map<String, ImageDef> defs) {
        var allPackages = new LinkedHashSet<>(imageDef.getPackages());
        for (var tool : tools) {
            allPackages.addAll(tool.setup().packages());
        }
        if (allPackages.isEmpty()) return;

        // Collect packages already installed by ancestor images
        var ancestorPackages = new LinkedHashSet<String>();
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            ancestorPackages.addAll(ancestor.getPackages());
        }
        for (var tool : ancestorTools) {
            ancestorPackages.addAll(tool.setup().packages());
        }

        var totalCount = allPackages.size();
        allPackages.removeAll(ancestorPackages);

        if (allPackages.isEmpty()) {
            BuildOutput.ok("All " + totalCount + " packages already installed");
            return;
        }

        var alreadyInstalled = totalCount - allPackages.size();
        var pkgDetail = allPackages.size() + " to install"
                + (alreadyInstalled > 0 ? " (" + alreadyInstalled + " already installed)" : "");
        try (var group = BuildOutput.group("Packages", pkgDetail)) {
            BuildOutput.list(allPackages);
            var rest = new ArrayList<String>(List.of("install", "-y"));
            rest.addAll(allPackages);
            // Label carries no count: the group header states the requested packages, while
            // dnf's own N/M in the spinner detail counts the fully-resolved transaction
            // (requested packages + their dependencies, one step per action phase), so a count
            // here would look like it should match dnf's much larger N when it never will.
            runDnf(container, "Installing packages and dependencies", "Failed to install packages",
                    dnfCommand(rest.toArray(String[]::new)));
        }
    }

    /**
     * Enable package repositories (e.g. COPR) from the image and its tools,
     * skipping any already enabled by ancestor images. Must be called before
     * {@link #installAllPackages}.
     */
    private record RepoKey(String type, String name) {
        RepoKey(ImageDef.PackageRepo repo) { this(repo.getType(), repo.getName()); }
    }

    static void enablePackageRepos(Container container, ImageDef imageDef,
                                    List<ResolvedTool> tools,
                                    List<ResolvedTool> ancestorTools,
                                    Map<String, ImageDef> defs) {
        var allRepos = new LinkedHashSet<RepoKey>();
        for (var repo : imageDef.getPackageRepos()) {
            allRepos.add(new RepoKey(repo));
        }
        for (var tool : tools) {
            for (var repo : tool.setup().packageRepos()) {
                allRepos.add(new RepoKey(repo));
            }
        }
        if (allRepos.isEmpty()) return;

        var ancestorRepos = new LinkedHashSet<RepoKey>();
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            for (var repo : ancestor.getPackageRepos()) {
                ancestorRepos.add(new RepoKey(repo));
            }
        }
        for (var tool : ancestorTools) {
            for (var repo : tool.setup().packageRepos()) {
                ancestorRepos.add(new RepoKey(repo));
            }
        }

        allRepos.removeAll(ancestorRepos);
        if (allRepos.isEmpty()) return;

        for (var key : allRepos) {
            switch (key.type()) {
                case "copr" -> runWithSpinner("Enabling", "COPR repo " + key.name(),
                        "Failed to enable COPR repo " + key.name(),
                        state -> state.set(0, stepFrom(
                                container.exec("dnf", "copr", "enable", "-y", key.name()))));
                default -> System.err.println("Warning: unknown package_repos type '" + key.type()
                        + "' for '" + key.name() + "', skipping.");
            }
        }
    }

    /**
     * Run the non-package setup steps for each tool (scripts, files, env, verify).
     */
    static void runToolSetup(Container container, List<ResolvedTool> tools,
                              Map<String, String> accountSelection) {
        if (tools.isEmpty()) return;
        var names = tools.stream().map(ResolvedTool::name).toList();
        try (var group = BuildOutput.group("Tools", String.join(", ", names))) {
            for (var resolved : tools) {
                if (resolved.reconfigureOnly()) {
                    resolved.setup().reconfigure(container, resolved.parameters());
                } else {
                    resolved.setup().install(container, resolved.parameters(), accountSelection);
                }
            }
        }
    }

    /**
     * Verify the tools this build installed, after {@link #writeEnvFile}: a verify runs in the
     * environment the image will have, so one tool may rely on another's (Maven on the JDK's
     * {@code JAVA_HOME}) -- except a {@code verify_as_root} check, which gets root's own
     * environment (see {@link ToolVerifier}). A reconfigure-only tool was verified when its
     * ancestor installed it.
     */
    static void verifyTools(Container container, List<ResolvedTool> tools) {
        var checks = tools.stream()
                .filter(t -> !t.reconfigureOnly())
                .map(t -> new ToolVerifier.Check(t.name(), t.setup().verifyCommand(t.parameters()),
                        t.setup().verifyAsRoot()))
                .filter(check -> check.command() != null)
                .toList();
        ToolVerifier.verifyAll(container, checks);
    }

    /** Refreshes what isx owns of each tool this layer inherits without setting it up again. */
    static void refreshInheritedTools(Container container, ToolResolution toolResolution) {
        var effectiveNames = toolResolution.effective().stream()
                .map(ResolvedTool::name).collect(java.util.stream.Collectors.toSet());
        for (var tool : toolResolution.ancestors()) {
            if (!effectiveNames.contains(tool.name())) {
                tool.setup().refreshInherited(container);
            }
        }
    }

    static void writeEnvFile(Container container, ImageDef imageDef, Map<String, ImageDef> defs,
                               List<ResolvedTool> allTools, String canonicalName) {
        var resolver = new EnvResolver();

        resolver.add(EnvEntry.set("ISX_CONTAINER", "${HOSTNAME}").expandingAtLogin(), "built-in");
        resolver.add(EnvEntry.set("ISX_TEMPLATE", canonicalName), "built-in");
        resolver.add(EnvEntry.set("ISX_VERSION", BuildInfo.instance().version()), "built-in");
        for (var layer : ImageDef.chain(imageDef, defs)) {
            resolver.addAll(layer.getEnv(), "template " + layer.getName());
        }

        // The account the template selected decides which auth mode gets baked, so it has to
        // reach envEntries -- resolving the default here would ignore the template's choice.
        var accountSelection = ImageDef.resolveAccounts(imageDef, defs);
        for (var resolved : allTools) {
            var entries = resolved.setup().envEntries(resolved.parameters(), accountSelection);
            resolver.addAll(entries, "tool " + resolved.name());
        }

        var script = resolver.resolve();
        container.writeFile("/etc/profile.d/isx-env.sh", script);
    }

    /**
     * Write the managed-policy CLAUDE.md that tells an agent what this box already
     * provides. Runs once per build, after the chain loop, so it sees the fully
     * resolved image rather than one layer at a time.
     *
     * <p>Written to {@code /etc/claude-code/CLAUDE.md} — Claude Code's managed policy
     * layer, beside the managed-settings.json that {@code ClaudeSetup} already owns.
     * That layer loads ahead of, and concatenates with, {@code ~/.claude/CLAUDE.md} and
     * any project CLAUDE.md, so isx never merges with or overwrites a file someone else
     * owns. Root ownership is correct here; no chown.
     */
    static void writeAgentContext(Container container, ImageDef imageDef, Map<String, ImageDef> defs,
                           List<ResolvedTool> allTools, String canonicalName) {
        var chain = ImageDef.chain(imageDef, defs);

        // generate() drops blanks and repeats, so collect freely here.
        var notes = new ArrayList<String>();
        var repos = new ArrayList<AgentContextGenerator.Repo>();
        var seenRepoPaths = new HashSet<String>();
        for (var layer : chain) {
            notes.add(layer.getAgentNote());
            // Ancestor repos are in the final image too: buildFromScratch clones them
            // per-layer in the chain loop, buildFromParent inherits them with the copy.
            for (var repo : layer.getRepos()) {
                // getPath() derives ~/<name> from the url, but yields null for an entry
                // with neither — nothing was cloned for it, so there is nothing to list.
                var path = repo.getPath();
                if (path == null || path.isBlank()) continue;
                // Dedupe on the resolved path: ~/jdk and /home/agentuser/jdk are one clone.
                if (seenRepoPaths.add(expandHome(path))) {
                    repos.add(new AgentContextGenerator.Repo(path, repo.getUrl()));
                }
            }
        }

        var toolNames = new ArrayList<String>(allTools.size());
        for (var resolved : allTools) {
            toolNames.add(resolved.name());
            notes.add(resolved.setup().agentNote());
        }

        var content = AgentContextGenerator.generate(canonicalName, toolNames, repos, notes);
        container.writeFile(ClaudeSetup.MANAGED_MEMORY_PATH, content);
    }

    static void linkJavaTrustStores(Container container) {
        container.sh(
                "find /usr/lib/jvm /opt -name cacerts -path '*/lib/security/cacerts' 2>/dev/null | while IFS= read -r f; do " +
                "t=$(readlink -f \"$f\" 2>/dev/null); " +
                "if [ \"$t\" != /etc/pki/java/cacerts ]; then " +
                "ln -sf /etc/pki/java/cacerts \"$f\"; " +
                "fi; done");
    }

    /**
     * Mount a shared DNF cache volume into the container. This shares
     * metadata and downloaded packages across builds, avoiding redundant
     * downloads when building a parent→child image chain.
     */
    static final String DNF_CACHE_VOLUME = "dnf-cache";

    static final String DNF_CACHE_PATH = "/var/cache/libdnf5";

    void cleanCaches(String container) {
        BuildOutput.stepStart("Cleaning up caches...");
        // If the shared cache volume is somehow still mounted, dnf clean / rm -rf would
        // wipe it for every later build, not just this image: clean only /tmp then.
        incus.shellExec(container, "sh", "-c",
                "if mountpoint -q " + DNF_CACHE_PATH + "; then "
                        + "echo 'Warning: DNF cache volume still mounted, not cleaning it' >&2; "
                        + "else dnf clean all; rm -rf " + DNF_CACHE_PATH + "; fi; "
                        + "rm -rf /tmp/* /var/tmp/*; true");
        BuildOutput.stepDone();
    }

    /**
     * Attach the DNF cache volume to a stopped build instance. Returns why it could not be
     * attached, or null: the caller is inside a progress line and reports it after the step.
     */
    String attachDnfCache(String container) {
        try {
            var pool = incus.findCowPool();
            if (pool == null) return null;
            incus.ensureStorageVolume(pool, DNF_CACHE_VOLUME);
            incus.deviceAdd(container, DNF_CACHE_DEVICE, "disk",
                    "pool=" + pool,
                    "source=" + DNF_CACHE_VOLUME,
                    "path=" + DNF_CACHE_PATH);
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    static void warnDnfCacheUnavailable(String reason) {
        if (reason != null) {
            System.err.println("Warning: could not mount DNF cache (builds will be slower): " + reason);
        }
    }

    void unmountDnfCache(String container, MachineType machineType) {
        // A VM's virtiofs mount is owned by incus-agent and goes away asynchronously after
        // deviceRemove; unmount it in the guest first so cleanCaches can't run against it.
        if (machineType == MachineType.VM) {
            incus.shellExec(container, "sh", "-c",
                    "mountpoint -q " + DNF_CACHE_PATH + " && umount " + DNF_CACHE_PATH + "; true");
        }
        // Safe even if mountDnfCache was skipped: deviceRemove is a read-modify-write
        // that filters the device map — a missing device is a no-op, not an error.
        incus.deviceRemove(container, DNF_CACHE_DEVICE);
    }

    private static final String CODEX_CONFIG_PATH = CodexSetup.CONFIG_PATH;

    static void updateCodexTrust(Container container, ImageDef imageDef) {
        if (imageDef.getRepos().isEmpty()) return;

        var checkResult = container.exec("test", "-f", CODEX_CONFIG_PATH);
        if (!checkResult.success()) return;

        var catResult = container.exec("cat", CODEX_CONFIG_PATH);
        if (!catResult.success()) return;

        var existing = catResult.stdout();
        var sb = new StringBuilder();

        for (var repo : imageDef.getRepos()) {
            var expandedPath = expandHome(repo.getPath());
            var section = "[projects.\"" + expandedPath + "\"]";
            if (!existing.contains(section) && !sb.toString().contains(section)) {
                sb.append("\n").append(section).append("\n");
                sb.append("trust_level = \"trusted\"\n");
            }
        }

        if (sb.isEmpty()) return;

        container.writeFile(CODEX_CONFIG_PATH, existing + sb);
        container.chown(CODEX_CONFIG_PATH, "agentuser:agentuser");
    }

    private static final String CLAUDE_JSON_PATH = "/home/agentuser/.claude.json";
    private static final String AGENTUSER_HOME = "/home/agentuser";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Update .claude.json to pre-trust cloned repo directories and register GitHub repo paths.
     */
    static void updateClaudeJsonTrust(Container container, ImageDef imageDef) {
        if (imageDef.getRepos().isEmpty()) return;

        var checkResult = container.exec("test", "-f", CLAUDE_JSON_PATH);
        if (!checkResult.success()) return;

        var catResult = container.exec("cat", CLAUDE_JSON_PATH);
        if (!catResult.success()) {
            System.err.println("Warning: could not read " + CLAUDE_JSON_PATH);
            return;
        }

        try {
            var root = (ObjectNode) JSON.readTree(catResult.stdout());

            var projects = root.has("projects")
                    ? (ObjectNode) root.get("projects")
                    : root.putObject("projects");

            var githubRepoPaths = root.has("githubRepoPaths")
                    ? (ObjectNode) root.get("githubRepoPaths")
                    : root.putObject("githubRepoPaths");

            for (var repo : imageDef.getRepos()) {
                var expandedPath = expandHome(repo.getPath());

                if (!projects.has(expandedPath)) {
                    var projectEntry = projects.putObject(expandedPath);
                    projectEntry.putArray("allowedTools");
                    projectEntry.put("hasTrustDialogAccepted", true);
                }

                var ownerRepo = parseGitHubOwnerRepo(repo.getUrl());
                if (ownerRepo != null) {
                    ArrayNode paths;
                    if (githubRepoPaths.has(ownerRepo)) {
                        paths = (ArrayNode) githubRepoPaths.get(ownerRepo);
                    } else {
                        paths = githubRepoPaths.putArray(ownerRepo);
                    }
                    boolean found = false;
                    for (var node : paths) {
                        if (node.asText().equals(expandedPath)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        paths.add(expandedPath);
                    }
                }
            }

            var updatedJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            container.writeFile(CLAUDE_JSON_PATH, updatedJson);
            container.chown(CLAUDE_JSON_PATH, "agentuser:agentuser");
        } catch (Exception e) {
            System.err.println("Warning: failed to update " + CLAUDE_JSON_PATH + ": " + e.getMessage());
        }
    }

    static String expandHome(String path) {
        if (path.startsWith("~/")) {
            return AGENTUSER_HOME + path.substring(1);
        }
        if (path.equals("~")) {
            return AGENTUSER_HOME;
        }
        return path;
    }

    static String parseGitHubOwnerRepo(String url) {
        if (url == null) return null;
        var prefix = "https://github.com/";
        if (!url.startsWith(prefix)) return null;
        var rest = url.substring(prefix.length());
        if (rest.endsWith(".git")) {
            rest = rest.substring(0, rest.length() - 4);
        }
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        var parts = rest.split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }
        return parts[0] + "/" + parts[1];
    }
}
