package dev.incusspawn.command;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValues;
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
import dev.incusspawn.tool.GeminiSetup;
import dev.incusspawn.tool.ToolVerifier;
import dev.incusspawn.util.BuildOutput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
public class GuestProvisioning {

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
        // Before anything a login shell runs reads TRUST_ENV: verify commands, primes.
        assertSystemStoreExists(container);
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

        // Added last, as setIfUnset: the first setIfUnset for a name wins, so a template's or
        // tool's own value for the same variable keeps precedence over isx's default.
        TRUST_ENV.forEach((name, value) -> resolver.add(EnvEntry.setIfUnset(name, value), "built-in trust"));

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
     * Images containing Gemini CLI also receive this content in its build-owned
     * {@code ~/.gemini/GEMINI.md}, with agentuser ownership.
     */
    static void writeAgentContext(Container container, ImageDef imageDef, Map<String, ImageDef> defs,
                           List<ResolvedTool> allTools, String canonicalName) {
        var chain = ImageDef.chain(imageDef, defs);

        // generate() drops blanks and repeats, so collect freely here.
        var notes = new ArrayList<String>();
        for (var layer : chain) {
            notes.add(layer.getAgentNote());
        }
        // Ancestor repos are in the final image too: buildFromScratch clones them
        // per-layer in the chain loop, buildFromParent inherits them with the copy.
        var repos = new ArrayList<AgentContextGenerator.Repo>();
        for (var repo : chainRepos(imageDef, defs)) {
            repos.add(new AgentContextGenerator.Repo(repo.getPath(), repo.getUrl()));
        }

        var toolNames = new ArrayList<String>(allTools.size());
        for (var resolved : allTools) {
            toolNames.add(resolved.name());
            notes.add(resolved.setup().agentNote());
        }

        var content = AgentContextGenerator.generate(canonicalName, toolNames, repos, notes);
        container.writeFile(ClaudeSetup.MANAGED_MEMORY_PATH, content);
        if (toolNames.contains("gemini-cli")) {
            GeminiSetup.writeEnvironmentContext(container, content);
        }
    }

    private static final String AGENTUSER_HOME = "/home/agentuser";

    /** Where {@link #installTrustHelper} puts the helper. */
    static final String TRUST_HELPER_PATH = "/usr/local/bin/isx-trust";

    /**
     * The system store as {@code update-ca-trust} extracts it, which every Fedora ships. Not
     * the {@code /etc/pki/tls/certs/ca-bundle.crt} compat link: ca-certificates dropped it, and a
     * variable naming a missing file fails every TLS call it touches (#713).
     */
    static final String SYSTEM_PEM_BUNDLE = "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem";
    static final String SYSTEM_CERT_DIR = "/etc/pki/ca-trust/extracted/pem/directory-hash";
    /** The Java form of the same store, what {@code isx-trust} links a JDK's cacerts to. */
    static final String SYSTEM_JAVA_STORE = "/etc/pki/ca-trust/extracted/java/cacerts";

    /**
     * Runtimes that read a variable for their root bundle, pointed at the system store that
     * {@code update-ca-trust} keeps the isx CA in. Read at process start, so they cover a
     * runtime installed at any time in any directory. None of them prints a "Picked up" line
     * (what {@code JAVA_TOOL_OPTIONS} did, #464).
     */
    static final Map<String, String> TRUST_ENV = trustEnv();

    private static Map<String, String> trustEnv() {
        var env = new LinkedHashMap<String, String>();
        env.put("SSL_CERT_FILE", SYSTEM_PEM_BUNDLE);
        env.put("SSL_CERT_DIR", SYSTEM_CERT_DIR);
        env.put("REQUESTS_CA_BUNDLE", SYSTEM_PEM_BUNDLE);
        env.put("CURL_CA_BUNDLE", SYSTEM_PEM_BUNDLE);
        env.put("PIP_CERT", SYSTEM_PEM_BUNDLE);
        env.put("NODE_EXTRA_CA_CERTS", SYSTEM_PEM_BUNDLE);
        env.put("CARGO_HTTP_CAINFO", SYSTEM_PEM_BUNDLE);
        env.put("AWS_CA_BUNDLE", SYSTEM_PEM_BUNDLE);
        env.put("NIX_SSL_CERT_FILE", SYSTEM_PEM_BUNDLE);
        env.put("DENO_TLS_CA_STORE", "system");
        env.put("UV_NATIVE_TLS", "1");
        return Collections.unmodifiableMap(env);
    }

    /**
     * Fails the build when the base image lacks the bundle {@link #TRUST_ENV} points at, which
     * would make every TLS call that reads those variables fail, or the Java store
     * {@code isx-trust} links JDKs to, which would leave every JDK it finds on its vendor roots
     * (#713). {@code update-ca-trust} writes both on every Fedora. A missing
     * {@link #SYSTEM_CERT_DIR} is only skipped by OpenSSL, so it is not checked.
     */
    static void assertSystemStoreExists(Container container) {
        for (var store : List.of(SYSTEM_PEM_BUNDLE, SYSTEM_JAVA_STORE)) {
            container.exec("test", "-s", store)
                    .assertSuccess("The base image has no system CA store at " + store
                            + " (what ca-certificates' update-ca-trust extracts)");
        }
    }

    /**
     * Writes {@link #TRUST_ENV} for systemd user sessions ({@code environment.d}) as plain
     * assignments in {@code 00-isx-trust.conf}. {@code environment.d} files are applied in
     * filename order and the last assignment wins, so the first-sorting name lets any other
     * file (a user's {@code ~/.config/environment.d/10-*.conf}) override these defaults.
     * ({@code NAME=${NAME:-value}} would say the same but broke rootless podman's registry
     * pulls in the integration run.) Login shells get the same names from {@code isx-env.sh}
     * (see {@link #writeEnvFile}).
     */
    static void writeTrustEnvironmentD(Container container) {
        var sb = new StringBuilder();
        TRUST_ENV.forEach((name, value) -> sb.append(name).append('=').append(value).append('\n'));
        container.writeFile("/etc/environment.d/00-isx-trust.conf", sb.toString());
    }

    /** Installs {@code isx-trust}, the one helper that applies the file bindings. */
    static void installTrustHelper(Container container) {
        String script;
        try (var is = GuestProvisioning.class.getClassLoader().getResourceAsStream("isx-trust")) {
            if (is == null) throw new IllegalStateException("isx-trust resource missing");
            script = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        container.writeFile(TRUST_HELPER_PATH, script);
        container.exec("chmod", "755", TRUST_HELPER_PATH).assertSuccess("Failed to install " + TRUST_HELPER_PATH);
    }

    /**
     * Where runtimes with their own root store land, by owner: by hand ({@code /opt},
     * {@code ~/opt}), or through SDKMAN, Gradle toolchains, jbang, mise, asdf and IntelliJ.
     * The build binds what is already there, and an {@code isx-trust-<group>.path} unit watches
     * each directory and binds what lands in it later (#515). A watch is not recursive and costs
     * one inotify instance, so this lists landing directories, not trees.
     */
    static final List<String> TRUST_SYSTEM_DIRS = List.of("/usr/lib/jvm", "/opt", "/usr/local");
    static final List<String> TRUST_HOME_DIRS = List.of(
            AGENTUSER_HOME + "/opt",
            AGENTUSER_HOME + "/.jdks",
            AGENTUSER_HOME + "/.sdkman/candidates/java",
            AGENTUSER_HOME + "/.gradle/jdks",
            AGENTUSER_HOME + "/.jbang/cache/jdks",
            AGENTUSER_HOME + "/.local/share/mise/installs/java",
            AGENTUSER_HOME + "/.asdf/installs/java");

    static final String TRUST_UNIT_DIR = "/etc/systemd/system";

    /**
     * Installs the trust helper, the environment drop-in and the path units, then applies the
     * file bindings to what the image already carries: a JDK under {@code /usr/lib/jvm} or
     * {@code /opt} (GraalVM, labsjdk, ...) trusts the isx CA without {@code JAVA_TOOL_OPTIONS}.
     * The home directories are bound as {@code agentuser}, who owns them.
     */
    static void bindTrustStores(Container container) {
        installTrustHelper(container);
        writeTrustEnvironmentD(container);
        installTrustPathUnits(container);
        runTrustHelper(container);
    }

    /**
     * Installs the helper and binds what is on disk now, nothing else. A child build runs it
     * before its repos' prime commands, so a vendor JDK this layer's tools just installed
     * already trusts the isx CA when a prime uses it; {@link #bindTrustStores} follows the
     * primes, for what they download.
     */
    static void bindInstalledRuntimes(Container container) {
        installTrustHelper(container);
        runTrustHelper(container);
    }

    private static void runTrustHelper(Container container) {
        var system = new ArrayList<>(List.of(TRUST_HELPER_PATH));
        system.addAll(TRUST_SYSTEM_DIRS);
        container.exec(system.toArray(String[]::new));
        container.shAsUser("agentuser", TRUST_HELPER_PATH + " "
                + String.join(" ", TRUST_HOME_DIRS.stream().map(Container::shellQuote).toList()));
    }

    /**
     * Two path units, one per owner, run {@code isx-trust --settle} on their directories when an
     * entry appears in one of them: root for the system directories, {@code agentuser} for the
     * home ones. No resident process: systemd holds the watches.
     */
    static void installTrustPathUnits(Container container) {
        writeTrustUnits(container, "system", null, TRUST_SYSTEM_DIRS);
        writeTrustUnits(container, "home", "agentuser", TRUST_HOME_DIRS);
        container.exec("systemctl", "enable", "isx-trust-system.path", "isx-trust-home.path")
                .assertSuccess("Failed to enable the isx-trust path units");
    }

    private static void writeTrustUnits(Container container, String group, String user, List<String> dirs) {
        var watches = new StringBuilder();
        dirs.forEach(dir -> watches.append("PathChanged=").append(dir).append('\n'));
        container.writeFile(TRUST_UNIT_DIR + "/isx-trust-" + group + ".path", """
                [Unit]
                Description=Watch where runtimes land, to bind them to the system trust store (isx-trust)

                [Path]
                %s
                [Install]
                WantedBy=paths.target
                """.formatted(watches));
        container.writeFile(TRUST_UNIT_DIR + "/isx-trust-" + group + ".service", """
                [Unit]
                Description=Bind runtimes that just landed to the system trust store (isx-trust)

                [Service]
                Type=oneshot
                %sExecStart=%s --settle %s
                """.formatted(user == null ? "" : "User=" + user + "\n", TRUST_HELPER_PATH, String.join(" ", dirs)));
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

    /**
     * Repos declared across the template chain, one per destination ({@link ImageDef#chainRepos}).
     * Tool setup in a child layer can rewrite Claude's or Codex's config, so trust is restored for
     * all of them after it, not just for the current layer's.
     */
    static List<ImageDef.RepoEntry> chainRepos(ImageDef imageDef, Map<String, ImageDef> defs) {
        return ImageDef.chainRepos(imageDef, defs).stream().map(ImageDef.ChainRepo::repo).toList();
    }

    static void updateCodexTrust(Container container, ImageDef imageDef, Map<String, ImageDef> defs) {
        var repos = chainRepos(imageDef, defs);
        if (repos.isEmpty()) return;

        var checkResult = container.exec("test", "-f", CODEX_CONFIG_PATH);
        if (!checkResult.success()) return;

        var catResult = container.exec("cat", CODEX_CONFIG_PATH);
        if (!catResult.success()) return;

        var existing = catResult.stdout();
        var sb = new StringBuilder();

        for (var repo : repos) {
            var expandedPath = expandHome(repo.getPath());
            var section = "[projects.\"" + expandedPath + "\"]";
            if (!existing.contains(section)) {
                sb.append("\n").append(section).append("\n");
                sb.append("trust_level = \"trusted\"\n");
            }
        }

        if (sb.isEmpty()) return;

        container.writeFile(CODEX_CONFIG_PATH, existing + sb);
        container.chown(CODEX_CONFIG_PATH, "agentuser:agentuser");
    }

    private static final String CLAUDE_JSON_PATH = "/home/agentuser/.claude.json";

    /**
     * Update .claude.json to pre-trust cloned repo directories and register GitHub repo paths.
     */
    static void updateClaudeJsonTrust(Container container, ImageDef imageDef, Map<String, ImageDef> defs) {
        var repos = chainRepos(imageDef, defs);
        if (repos.isEmpty()) return;

        var checkResult = container.exec("test", "-f", CLAUDE_JSON_PATH);
        if (!checkResult.success()) return;

        var catResult = container.exec("cat", CLAUDE_JSON_PATH);
        if (!catResult.success()) {
            System.err.println("Warning: could not read " + CLAUDE_JSON_PATH);
            return;
        }

        try {
            // Like the old casts: a file that is not the shape Claude Code writes is left alone,
            // never rewritten from the part that could be read.
            if (!(JqValues.parseStrict(catResult.stdout()) instanceof JqObject root)) {
                throw new IllegalStateException("not a JSON object");
            }
            var projects = objectOrEmpty(root, "projects");
            var githubRepoPaths = objectOrEmpty(root, "githubRepoPaths");

            var projectsBuilder = JqObject.builder(8);
            projects.forEach(projectsBuilder::put);
            var pathsBuilder = JqObject.builder(8);
            githubRepoPaths.forEach(pathsBuilder::put);

            // Inherited repos are usually trusted already: skip the write when nothing changes.
            boolean changed = false;
            for (var repo : repos) {
                var expandedPath = expandHome(repo.getPath());

                if (!projectsBuilder.build().keys().contains(expandedPath)) {
                    projectsBuilder.put(expandedPath, JqObject.builder(2)
                            .put("allowedTools", JqArray.arrayBuilder().build())
                            .put("hasTrustDialogAccepted", true)
                            .build());
                    changed = true;
                }

                var ownerRepo = parseGitHubOwnerRepo(repo.getUrl());
                if (ownerRepo != null) {
                    var existing = pathsBuilder.build().getField(ownerRepo);
                    var entries = JqArray.arrayBuilder();
                    if (existing.isArray()) {
                        existing.asList().forEach(entries::add);
                    } else if (!existing.isNull()) {
                        // Like the old cast: a non-array here is corrupt; warn instead of writing.
                        throw new IllegalStateException(ownerRepo + " is not an array in " + CLAUDE_JSON_PATH);
                    }
                    boolean found = false;
                    for (var node : entries.build().asList()) {
                        if (expandedPath.equals(node.asDisplayText(""))) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        entries.add(expandedPath);
                        pathsBuilder.put(ownerRepo, entries.build());
                        changed = true;
                    }
                }
            }
            if (!changed) return;

            var updated = JqObject.builder(4);
            root.forEach((key, value) -> {
                if (!key.equals("projects") && !key.equals("githubRepoPaths")) updated.put(key, value);
            });
            updated.put("projects", projectsBuilder.build());
            updated.put("githubRepoPaths", pathsBuilder.build());
            // Pretty style differs from Jackson's; Claude Code parses the file, so only the shape matters.
            var updatedJson = JqValues.toPrettyJsonString(updated.build());
            container.writeFile(CLAUDE_JSON_PATH, updatedJson);
            container.chown(CLAUDE_JSON_PATH, "agentuser:agentuser");
        } catch (Exception e) {
            System.err.println("Warning: failed to update " + CLAUDE_JSON_PATH + ": " + e.getMessage());
        }
    }

    /** The object under {@code key}, empty when absent; anything else there is corrupt. */
    private static JqObject objectOrEmpty(JqObject root, String key) {
        if (!root.keys().contains(key)) return JqObject.builder(0).build();
        var value = root.getField(key);
        if (value instanceof JqObject object) return object;
        throw new IllegalStateException("'" + key + "' is not an object in " + CLAUDE_JSON_PATH);
    }

    public static String expandHome(String path) {
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
