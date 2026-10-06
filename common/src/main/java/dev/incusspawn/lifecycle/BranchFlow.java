package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.config.AccountOrigin;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.proxy.ToolProxyResolver;
import dev.incusspawn.proxy.CertificateAuthority.CaStatus;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.BuildOutput;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creating a branch from a source instance, without a terminal: everything {@code isx branch}
 * does up to (not including) opening the shell.
 *
 * <p>Split in two so a caller can refuse before anything exists: {@link #preflight} runs every
 * check that can reject the request and creates nothing; {@link #create} copies, configures and
 * (optionally) starts. Both {@code isx branch} and {@code isx mcp} go through here, so an agent
 * gets exactly the branch a user would -- same account resolution, same credential and CA checks.
 *
 * <p>Progress is reported through {@link BuildOutput} as before; a headless caller redirects
 * {@code System.out} rather than getting a second code path.
 */
public final class BranchFlow {

    private BranchFlow() {}

    /** Whether isx runs in a Wayland session; replaced by tests, so they do not depend on where they run. */
    static java.util.function.BooleanSupplier waylandSession = GuiPassthrough::inWaylandSession;

    /** Whether the proxy is up, warning if not; replaced by tests, which have no proxy. */
    static java.util.function.Predicate<IncusClient> proxyHealthCheck = ProxyHealthCheck::checkOrWarn;

    /**
     * What to branch. A null {@code gui}, {@code kvm}, {@code cpu}, {@code memory} or
     * {@code disk} means the {@linkplain #defaultsFor default} for the source.
     * {@code extraConfig} is stamped in the same writes as the branch's own metadata: on the copy
     * request itself and again in {@link InstanceLifecycle#configureBranch}, as the {@code clone}
     * type is.
     */
    public record Request(String source, String name, Boolean gui, Boolean kvm,
                          NetworkMode networkMode, Path inbox, Integer cpu, String memory,
                          String disk, List<String> accountOverrides, boolean start,
                          Map<String, String> extraConfig) {
        public Request {
            accountOverrides = accountOverrides == null ? List.of() : List.copyOf(accountOverrides);
            extraConfig = extraConfig == null ? Map.of() : Map.copyOf(extraConfig);
        }

        /**
         * A branch with every setting at the template's default, as {@code isx branch <name> --from <source>},
         * except GUI passthrough, which is always off: this is the agent's branch ({@code isx mcp}),
         * and nobody watches its display.
         */
        public static Request defaults(String source, String name) {
            return new Request(source, name, false, null, NetworkMode.FULL, null, null, null,
                    null, List.of(), true, Map.of());
        }

        public Request withExtraConfig(Map<String, String> extra) {
            return new Request(source, name, gui, kvm, networkMode, inbox, cpu, memory, disk,
                    accountOverrides, start, extra);
        }
    }

    /**
     * What a branch of a source gets for each setting its {@link Request} leaves open. One rule
     * for {@code isx branch} and the TUI's branch dialog, which shows these as its initial values.
     * The resource limits are worked out when asked for: on macOS each memory default forks
     * {@code sysctl}, which a request that sets its own limits should not pay.
     *
     * @param guiNote why {@code gui} is off although the source asks for GUI, or null
     */
    public record Defaults(MachineType machineType, boolean gui, boolean kvm, String guiNote) {
        /** The CPU limit, or null for a container, which gets none. */
        public Integer cpu() {
            return machineType == MachineType.VM ? Math.max(1, ResourceLimits.hostProcessorCount() - 2) : null;
        }

        public String memory() {
            return machineType == MachineType.VM ? ResourceLimits.defaultVmMemoryLimit()
                    : ResourceLimits.adaptiveMemoryLimit();
        }

        public String disk() {
            return ResourceLimits.defaultDiskLimit();
        }
    }

    /**
     * The {@link Defaults} for a branch of {@code source}, given that instance as
     * {@link IncusClient#instanceMetadata} reads it. GUI and KVM follow the source's definition
     * when it is a template, and what the source was built or branched with either way. For KVM
     * a template falls back to its type, a branch only to its own {@code kvm-enabled} stamp.
     *
     * <p>GUI is narrower, since it hands the branch the host's GPU and its whole
     * {@code XDG_RUNTIME_DIR}. Only for a container, which is all passthrough can start with;
     * only from a Wayland session, as outside one (SSH, a headless host) it cannot work and a
     * default should not fail with errors where nobody asked for it; and never from a
     * project-local definition, or a source built with one: a cloned repository's
     * {@code .incus-spawn/} gets only what stays inside its project, so its {@code gui: true}
     * still needs {@code --gui}.
     */
    public static Defaults defaultsFor(String source, JsonNode instance, Map<String, ImageDef> defs) {
        var def = defs.get(source);
        var config = instance.path("config");
        var machineType = IncusClient.machineType(instance);
        var wantsGui = machineType == MachineType.CONTAINER && ((def != null && def.isGui())
                || "true".equals(config.path(Metadata.GUI_ENABLED).asText("")));
        String guiNote = null;
        if (wantsGui && projectLocal(def, config)) {
            guiNote = "'" + source + "' asks for GUI passthrough from a project-local definition, "
                    + "so it is off unless chosen (--gui, or the branch dialog's GUI box).";
        } else if (wantsGui && !waylandSession.getAsBoolean()) {
            guiNote = "'" + source + "' asks for GUI passthrough, but isx is not running in a "
                    + "Wayland session, so it is off.";
        }
        var gui = wantsGui && guiNote == null;
        // A template's instance-mode records how it was built, never a per-branch choice; any other
        // source decides by its own kvm-enabled, since it inherits instance-mode even through --no-kvm (#1034)
        var type = config.path(Metadata.TYPE).asText("");
        var template = def != null || Metadata.TYPE_BASE.equals(type) || Metadata.TYPE_PROJECT.equals(type);
        var kvm = template
                ? (def != null && def.isKvm()) || "kvm".equals(config.path(Metadata.INSTANCE_MODE).asText(""))
                : "true".equals(config.path(Metadata.KVM_ENABLED).asText(""));
        return new Defaults(machineType, gui, kvm, guiNote);
    }

    /** Whether {@code def}, or any definition the source was built from, is project-local. */
    private static boolean projectLocal(ImageDef def, JsonNode config) {
        if (def != null && def.getProjectRoot() != null) return true;
        var built = BuildSource.fromJson(config.path(Metadata.BUILD_SOURCE).asText(""));
        return built != null && built.usedProjectLocal();
    }

    /** Whether a branch made from {@code req} gets GUI passthrough: the request's choice, else the default. */
    static boolean gui(Request req, Defaults defaults) {
        return req.gui() != null ? req.gui() : defaults.gui();
    }

    /**
     * A request that passed {@link #preflight}: nothing has been created yet.
     *
     * @param sourceInstance the source as preflight read it, so {@link #create} need not read it again
     * @param template the leaf template the source was built from ({@link Inherited#template}), which
     *                 the new branch's default action is resolved against
     */
    public record Preflight(Request request, Map<String, ImageDef> defs,
                            Map<String, String> accounts, Map<String, AccountOrigin> accountOrigins,
                            JsonNode sourceInstance, String template,
                            List<ProofToken.Placeholder> placeholders) {}

    /** An account selection and who chose each pin in it. */
    /** @param template the leaf template the source was built from ({@link Inherited#template}) */
    private record ResolvedAccounts(Inherited inherited, Map<String, String> accounts,
                                    Map<String, AccountOrigin> origins) {}

    /**
     * A refusal. {@code reported} is true when the reason was already printed (a banner, the
     * proxy's own diagnosis), so a CLI caller should not print it again; the message is always
     * a complete sentence for callers that did not see that output.
     */
    public static final class BranchException extends RuntimeException {
        private final boolean reported;

        public BranchException(String message) {
            this(message, false);
        }

        public BranchException(String message, boolean reported) {
            super(message);
            this.reported = reported;
        }

        public boolean reported() {
            return reported;
        }
    }

    /**
     * Run every check that can refuse the branch, before anything is created. The caller has
     * already established that {@code source} exists (the CLI while resolving it, the MCP server
     * through its template policy), so it is not looked up again here.
     */
    public static Preflight preflight(IncusClient incus, Request req) {
        return preflight(incus, req, ImageDef.loadAll());
    }

    /**
     * {@link #preflight(IncusClient, Request)} against a given set of definitions, e.g.
     * {@link ImageDef#loadTrusted()} for a caller that must not see project-local ones.
     */
    public static Preflight preflight(IncusClient incus, Request req, Map<String, ImageDef> defs) {
        if (incus.exists(req.name())) {
            throw new BranchException("an instance named '" + req.name() + "' already exists.");
        }

        // Resolve and validate the account selection before anything is created: a typo
        // should be reported now, not as a failed API call inside the container later.
        var config = SpawnConfig.load();
        var loader = new ToolDefLoader();
        var served = ToolProxyResolver.proxyToolSetups(config, loader);
        var sourceInstance = incus.instanceMetadata(req.source());
        ResolvedAccounts accounts;
        try {
            accounts = resolveAccountSelection(incus, req.source(), sourceInstance, req.accountOverrides(), defs, config,
                    AccountSelection.byNamespace(served));
        } catch (AccountSelection.InvalidSelectionException
                 | AccountResolver.UnknownAccountException e) {
            throw new BranchException(e.getMessage());
        }

        if (req.networkMode() != NetworkMode.AIRGAP) {
            if (!proxyHealthCheck.test(incus)) {
                throw new BranchException("the isx proxy is not running; "
                        + "run 'isx doctor' to diagnose.", true);
            }
            BridgeSubnetCheck.warnIfConflict(incus);
            FirewallDetector.warnIfNotRunning();
            checkCaMismatch(incus, req.source());
            var credError = missingCredentials(config, accounts.inherited(), accounts.accounts(), defs,
                    loader.allToolSetups(), served);
            if (!credError.isEmpty()) throw new BranchException(credError);
        }

        try {
            HostResourceSetup.requireBranchableTemplate(incus, req.source());
        } catch (HostResourceSetup.ForbiddenMountTargetException e) {
            throw new BranchException(e.getMessage());
        }
        return new Preflight(req, defs, accounts.accounts(), accounts.origins(), sourceInstance,
                accounts.inherited().template(), ProofToken.declaredBy(served.values()));
    }

    /**
     * Copy, configure and (unless the request says otherwise) start the branch. Returns the
     * runtime config prefetched for the start, or null when the request asked not to start it.
     */
    public static InstanceLifecycle.RuntimeConfig create(IncusClient incus, Preflight preflight) {
        var req = preflight.request();
        var name = req.name();
        var source = req.source();
        var defs = preflight.defs();
        var networkMode = req.networkMode();

        BuildOutput.branchHeader(name, source);

        // The source as preflight read it: the copy plan, its machine type and every default the request leaves open
        var sourceInstance = preflight.sourceInstance();
        var defaults = defaultsFor(source, sourceInstance, defs);

        var copyPlan = incus.planCopy(sourceInstance);
        if (!copyPlan.cow()) {
            BuildOutput.warn("This branch will be a full copy, not a CoW clone: "
                    + copyPlan.fullCopyReason() + ". Run 'isx doctor' for details.");
        }
        // The branch's own secret, its hash stamped by the copy request: no extra write, and no
        // moment in which the new instance carries the hash of its source's
        var copyConfig = new LinkedHashMap<>(req.extraConfig());
        var secret = InstanceSecret.stampInto(copyConfig);
        // An instance from the copy on, not its template's base: a branch interrupted before
        // configureBranch would otherwise pass for a template, which isx list -q never shows.
        copyConfig.put(Metadata.TYPE, Metadata.TYPE_CLONE);
        // Nor its source's isx mcp ownership: configureBranch drops what the caller did not
        // stamp, but the copy is listed before that, and an agent's idempotency key would make it
        // answer for its source's create meanwhile (#1011). An empty value unsets.
        sourceInstance.path("config").fieldNames().forEachRemaining(key -> {
            if (Metadata.isMcpKey(key)) copyConfig.putIfAbsent(key, "");
        });
        BuildOutput.stepStart("Copying from template...");
        incus.copy(source, name, copyPlan, copyConfig);
        BuildOutput.stepDone();

        // Configure GUI before start so environment.* keys are visible to init. First, because
        // it may push files: any push lands well before the start (see prefetchAndStart).
        if (req.gui() == null && defaults.guiNote() != null) BuildOutput.warn(defaults.guiNote());
        if (gui(req, defaults)) {
            if (GuiPassthrough.configureGui(incus, name)) {
                incus.configSet(name, Metadata.GUI_ENABLED, "true");
            } else {
                GuiPassthrough.removeGui(incus, name);
                System.err.println("Continuing without GUI passthrough.");
            }
        } else {
            // Clean up inherited GUI devices/env from incus copy
            GuiPassthrough.removeGui(incus, name);
        }

        var cpuLimit = req.cpu() != null ? req.cpu() : defaults.cpu();
        var cpu = cpuLimit == null ? null : String.valueOf(cpuLimit);
        var memory = req.memory() != null ? req.memory() : defaults.memory();
        var disk = req.disk() != null ? req.disk() : defaults.disk();

        BuildOutput.step("Resource limits: " +
                (cpu != null ? cpu + " CPUs, " : "") + memory + " memory, " + disk + " disk.");
        var enableKvm = req.kvm() != null ? req.kvm() : defaults.kvm();
        var machineType = defaults.machineType();
        InstanceLifecycle.configureBranch(incus, name, new InstanceLifecycle.BranchSettings(
                cpu, memory, disk, networkMode, source, preflight.accounts(),
                preflight.accountOrigins(), enableKvm, req.extraConfig(), req.start()));
        announceAccountSelection(preflight.accounts());
        InstanceLifecycle.integrateWithHost(incus, name, InstanceType.INSTANCE, machineType);

        // Inherited KVM passthrough was already dropped by configureBranch when not enabled.
        if (enableKvm && !KvmPassthrough.configureKvm(incus, name)) {
            System.err.println("Continuing without KVM — VMs inside this branch will not work.");
        }

        // Before the --no-start return: the inbox is plain device config, so it is there
        // however the instance is later started (#852).
        InstanceLifecycle.attachInbox(incus, name, req.inbox());

        if (!req.start()) return null;

        // Pre-fetch config while instance is stopped — the Incus daemon blocks
        // API calls after start due to seccomp_notify lock contention.
        var prefetched = InstanceLifecycle.prefetchAndStart(incus, name, machineType);

        if (machineType == MachineType.VM) {
            BuildOutput.stepStart("Waiting for VM agent...");
            incus.waitForReady(name, MachineType.VM);
            BuildOutput.stepDone();
            InstanceLifecycle.pushDeferredVmFiles(incus, name, networkMode);
        }

        // The grant is the request's own: a copy never carries its source's (configureBranch)
        InstanceLifecycle.setupRuntime(incus, name, networkMode, prefetched, secret,
                Metadata.isMcpCaller(req.extraConfig()), preflight.placeholders());
        // Or the first shell would take this boot for one isx did not start, and replace its secret
        var booted = InstanceLifecycle.recordSecretBoot(incus, name, machineType);

        if (networkMode != NetworkMode.AIRGAP) {
            CertificateAuthority.fixContainerCaIfNeeded(incus, name, booted);
            ProxyConfig.fixResolvConfIfNeeded(incus, name);
            // The template baked its own account's identity; a branch pinned to a different one
            // must not commit under it. Re-derived here rather than only on the next
            // InstancePrep, so an instance used via incus exec, SSH or an IDE is right too.
            // Needs the CA and resolv.conf above -- re-deriving goes through the proxy. From the
            // read before the start: nothing since has written a pin or a stamp.
            InstanceLifecycle.reconcileAccountIdentities(incus, name, prefetched.instance());
        }
        return prefetched;
    }

    /**
     * The credentials a branch would be missing, or why its account selection is refused, as a
     * message; {@code ""} when neither. The check {@link #preflight} makes, from an
     * {@link #inheritedAccounts} the caller already read -- for the TUI's branch dialog, which
     * must not wait on Incus from its event thread, to report before it hands the terminal back.
     * Not the auth-mode compatibility check, which needs the source's live state; preflight
     * still makes that one.
     *
     * @param accountOverrides {@code <ns>=<account>} selections, as {@code --account} takes them
     */
    public static String credentialProblem(Inherited inherited, List<String> accountOverrides,
                                           Map<String, ImageDef> defs, ToolDefLoader loader) {
        var config = SpawnConfig.load();
        try {
            var selection = selection(inherited, AccountSelection.parse(accountOverrides));
            var served = ToolProxyResolver.proxyToolSetups(config, loader);
            AccountSelection.validate(config, selection, AccountSelection.byNamespace(served));
            return missingCredentials(config, inherited, selection, defs, loader.allToolSetups(), served);
        } catch (AccountSelection.InvalidSelectionException
                 | AccountResolver.UnknownAccountException e) {
            return e.getMessage();
        }
    }

    /**
     * Checked against the template the branch inherits from -- for a branch of a branch, the leaf
     * template recorded on the source -- and the selection it will actually be stamped with.
     */
    private static String missingCredentials(SpawnConfig config, Inherited inherited,
                                             Map<String, String> selection, Map<String, ImageDef> defs,
                                             Map<String, ToolSetup> allTools, Map<String, ToolSetup> served) {
        // What the source was built from, where it recorded that: the template's YAML may have
        // changed since, and the branch gets what was built, not what the YAML says now.
        var definitions = inherited.builtFrom().containsKey(inherited.template()) ? inherited.builtFrom() : defs;
        var template = definitions.get(inherited.template());
        if (template == null) return "";
        return CredentialCheck.check(config, template, definitions, selection, allTools, served);
    }

    /** The selection a branch is stamped with: what it inherits, with {@code overrides} on top. */
    private static Map<String, String> selection(Inherited inherited, Map<String, String> overrides) {
        var selection = new java.util.LinkedHashMap<>(inherited.accounts());
        selection.putAll(overrides);
        return selection;
    }

    /**
     * The branch's account selection: what it {@linkplain #inheritedAccounts inherits}, with any
     * {@code --account} override applied on top, validated and checked against what the source
     * was built for.
     */
    private static ResolvedAccounts resolveAccountSelection(IncusClient incus, String source,
                                                            JsonNode sourceInstance,
                                                            List<String> accountOverrides,
                                                            Map<String, ImageDef> defs, SpawnConfig config,
                                                            Map<String, ToolSetup> setups) {
        var inherited = inheritedAccounts(sourceInstance, source, defs);
        var overrides = AccountSelection.parse(accountOverrides);
        var selection = selection(inherited, overrides);
        var origins = new java.util.LinkedHashMap<>(inherited.origins());
        overrides.keySet().forEach(ns -> origins.put(ns, AccountOrigin.EXPLICIT));

        AccountSelection.validate(config, selection, setups);

        // A branch is a CoW copy of an already-built template, so its environment is already
        // baked -- an --account that crosses auth modes is exactly as unhonourable here as it
        // is in 'isx account set', and must be refused the same way. Checked against the
        // source, whose env class the copy inherits.
        var reason = AccountSelection.incompatibilityReason(config, incus, source, selection, setups);
        if (!reason.isEmpty()) throw new AccountSelection.InvalidSelectionException(reason);

        return new ResolvedAccounts(inherited, selection, origins);
    }

    /**
     * What a branch of {@code source} inherits before any {@code --account}: the template it was
     * built from, and the pins -- with who chose each -- that the branch would be stamped with.
     * A namespace absent from {@code accounts} is not pinned and follows the global default.
     *
     * @param template  the leaf template the source was built from, or the source itself
     * @param builtFrom the definitions the source was built from, as its build recorded them
     *                  ({@link BuildSource#getDefinitions}); empty when it recorded none
     */
    public record Inherited(String template, Map<String, String> accounts,
                            Map<String, AccountOrigin> origins, Map<String, ImageDef> builtFrom) {
        public Inherited(String template, Map<String, String> accounts, Map<String, AccountOrigin> origins) {
            this(template, accounts, origins, Map.of());
        }
    }

    /**
     * The branch's inherited selection, lowest precedence first, each layer overwriting the last:
     * the template chain's {@code accounts:}, then the source instance's own pins. The source's
     * pins win because a source re-pointed with 'isx account set' shows that choice to the user,
     * and the CoW copy carries it across regardless -- resolving the template here would silently
     * stamp over it.
     *
     * <p>Resolved against the template the instance is branched from, which for a branch of a
     * branch is the leaf template recorded in {@link Metadata#PROFILE} -- the same rule
     * {@code InstancePrep} uses to find the chain. Shared by {@code isx branch} and the TUI's
     * branch dialog, which offers exactly this as the choice that changes nothing.
     */
    public static Inherited inheritedAccounts(IncusClient incus, String source, Map<String, ImageDef> defs) {
        // One read for the profile, the pins, their origins and the build record.
        return inheritedAccounts(incus.instanceMetadata(source), source, defs);
    }

    /** {@link #inheritedAccounts(IncusClient, String, Map)} from a source already read with {@link IncusClient#instanceMetadata}. */
    public static Inherited inheritedAccounts(JsonNode instance, String source, Map<String, ImageDef> defs) {
        if (!instance.isObject()) { // Incus answers a missing instance with "metadata": null
            throw new IncusException("Failed to read instance " + source);
        }
        var config = instance.path("config");
        var profile = config.path(Metadata.PROFILE).asText("");
        var templateName = !profile.isEmpty() ? profile : source;

        var selection = AccountSelection.resolve(defs.get(templateName), defs, Map.of());
        var origins = new java.util.LinkedHashMap<String, AccountOrigin>();
        selection.keySet().forEach(ns -> origins.put(ns, AccountOrigin.template(templateName)));

        var sourcePins = AccountSelection.fromConfig(config);
        var sourceOrigins = AccountSelection.originsFromConfig(config);
        sourcePins.forEach((ns, account) -> {
            // A pin the source recorded no origin for, but which is what the template chooses,
            // is the template's: that is how every pre-origin template build stamped it.
            var origin = sourceOrigins.getOrDefault(ns, AccountOrigin.UNKNOWN);
            if (origin.kind() == AccountOrigin.Kind.UNKNOWN && account.equals(selection.get(ns))) {
                origin = AccountOrigin.template(templateName);
            }
            selection.put(ns, account);
            origins.put(ns, origin.copiedOnto(source));
        });
        var built = BuildSource.fromJson(config.path(Metadata.BUILD_SOURCE).asText(""));
        return new Inherited(templateName, selection, origins,
                built == null ? Map.of() : built.getDefinitions());
    }

    /** Report the account pins {@code configureBranch} stamped, and tell the proxy. */
    private static void announceAccountSelection(Map<String, String> selection) {
        if (!selection.isEmpty()) {
            BuildOutput.step("Credential accounts: " + AccountSelection.describe(selection) + ".");
        }
        // Signalled even when this branch pins nothing. Static IPs are handed out lowest-free,
        // so a new instance frequently reuses a destroyed one's address; without this the proxy
        // would still map that address to the old instance and hand its account to this one.
        // Cheap (SIGUSR1 re-reads the instance list only) and happens before the guest boots,
        // so the first request from inside already sees the right answer.
        ProxyService.signalAccountRefresh();
    }

    private static void checkCaMismatch(IncusClient incus, String source) {
        var status = CertificateAuthority.CaTrust.snapshot()
                .classify(incus.configGet(source, Metadata.CA_FINGERPRINT));
        if (status != CaStatus.FOREIGN && status != CaStatus.REPAIRABLE) return;
        var profile = incus.configGet(source, Metadata.PROFILE);

        // REPAIRABLE is let through, FOREIGN is not — and the difference is provenance, not
        // repairability: InstancePrep pushes the current CA into the instance on first use
        // either way. But a CA this host never issued usually means a deleted config dir or
        // a ~/.config copied from another machine, which is worth stopping for.
        if (status == CaStatus.REPAIRABLE) {
            System.err.println("Note: '" + source + "' still carries the pre-upgrade MITM CA "
                    + "certificate. The new one is installed into the instance on first use."
                    + (profile.isEmpty() ? "" : " Rebuild when convenient: isx build " + profile));
            return;
        }

        if (!profile.isEmpty()) {
            BuildOutput.warnBanner("CA certificate mismatch",
                    "Template '" + source + "' was built with a different CA certificate.",
                    "TLS connections through the proxy will fail in branches.",
                    "Rebuild the template to fix: " + BuildOutput.styled(BuildOutput.BOLD, "isx build " + profile));
        } else {
            BuildOutput.warnBanner("CA certificate mismatch",
                    "Template '" + source + "' was built with a different CA certificate.",
                    "TLS connections through the proxy will fail in branches.");
        }
        throw new BranchException("template '" + source + "' was built with a different CA "
                + "certificate" + (profile.isEmpty() ? "." : "; rebuild it: isx build " + profile), true);
    }
}
