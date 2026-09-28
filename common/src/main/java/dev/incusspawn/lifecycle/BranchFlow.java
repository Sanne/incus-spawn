package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.config.AccountOrigin;
import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.CredentialCheck;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.BridgeSubnetCheck;
import dev.incusspawn.incus.FirewallDetector;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.CertificateAuthority.CaStatus;
import dev.incusspawn.proxy.ProxyConfig;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.util.BuildOutput;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Creating a branch from a source instance, without a terminal: everything {@code isx branch}
 * does up to (not including) opening the shell.
 *
 * <p>Split in two so a caller can refuse before anything exists: {@link #preflight} runs every
 * check that can reject the request and creates nothing; {@link #create} copies, configures and
 * (optionally) starts. Any caller that branches for someone -- {@code isx branch} today, other
 * front ends later -- goes through here, so every branch gets the same account resolution and
 * the same credential and CA checks.
 *
 * <p>Progress is reported through {@link BuildOutput} as before; a headless caller redirects
 * {@code System.out} rather than getting a second code path.
 */
public final class BranchFlow {

    private BranchFlow() {}

    /** Tells the proxy to re-read the instance list; replaced by tests, which have no proxy. */
    static Runnable proxyRefresh = ProxyService::signalAccountRefresh;

    /** Whether the proxy is up, warning if not; replaced by tests, which have no proxy. */
    static java.util.function.Predicate<IncusClient> proxyHealthCheck = ProxyHealthCheck::checkOrWarn;

    /**
     * What to branch. {@code kvm} null means "whatever the source template was built with";
     * null {@code cpu}/{@code memory}/{@code disk} mean the adaptive defaults.
     * {@code extraConfig} is stamped in the same writes as the branch's own metadata: on the copy
     * request itself and again in {@link InstanceLifecycle#configureBranch}.
     */
    public record Request(String source, String name, boolean gui, Boolean kvm,
                          NetworkMode networkMode, Path inbox, Integer cpu, String memory,
                          String disk, List<String> accountOverrides, boolean start,
                          Map<String, String> extraConfig) {
        public Request {
            accountOverrides = accountOverrides == null ? List.of() : List.copyOf(accountOverrides);
            extraConfig = extraConfig == null ? Map.of() : Map.copyOf(extraConfig);
        }

        /** A branch with every setting at the template's default, as {@code isx branch <name> --from <source>}. */
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
     * A request that passed {@link #preflight}: nothing has been created yet.
     *
     * <p>It carries what preflight read, so {@link #create} -- which runs straight after -- does
     * not ask Incus again: each round trip is paid in sequence before the user gets a prompt.
     *
     * @param template   the leaf template the source was built from ({@link Inherited#template})
     * @param source     the source's instance metadata, read once ({@link IncusClient#instanceMetadata})
     * @param bridgeCidr the bridge's {@code ipv4.address}, or null when preflight had no need to read it
     */
    public record Preflight(Request request, Map<String, ImageDef> defs,
                            Map<String, String> accounts, Map<String, AccountOrigin> accountOrigins,
                            String template, JsonNode source, String bridgeCidr) {}

    /**
     * An account selection and who chose each pin in it.
     *
     * @param template the leaf template the source was built from ({@link Inherited#template})
     */
    private record ResolvedAccounts(String template, Map<String, String> accounts,
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
     * already established that {@code source} exists (the CLI does, while resolving it), so it is
     * not looked up again here.
     */
    public static Preflight preflight(IncusClient incus, Request req) {
        return preflight(incus, req, ImageDef.loadAll());
    }

    /**
     * {@link #preflight(IncusClient, Request)} against a given set of definitions, for a caller
     * that must not see all of them (e.g. none from the working directory's project).
     */
    public static Preflight preflight(IncusClient incus, Request req, Map<String, ImageDef> defs) {
        if (incus.exists(req.name())) {
            throw new BranchException("an instance named '" + req.name() + "' already exists.");
        }
        // The one read of the source: every check below, and create(), work from it.
        var source = incus.instanceMetadata(req.source());
        if (!source.isObject()) { // Incus answers a missing instance with "metadata": null
            throw new BranchException("'" + req.source() + "' does not exist.");
        }
        var sourceConfig = source.path("config");

        // Resolve and validate the account selection before anything is created: a typo
        // should be reported now, not as a failed API call inside the container later.
        var config = SpawnConfig.load();
        var loader = new ToolDefLoader();
        var setups = AccountSelection.namespaceSetups(config, loader);
        ResolvedAccounts accounts;
        try {
            accounts = resolveAccountSelection(req.source(), sourceConfig, req.accountOverrides(), defs, config, setups);
        } catch (AccountSelection.InvalidSelectionException
                 | AccountResolver.UnknownAccountException e) {
            throw new BranchException(e.getMessage());
        }

        String bridgeCidr = null;
        if (req.networkMode() != NetworkMode.AIRGAP) {
            if (!proxyHealthCheck.test(incus)) {
                throw new BranchException("the isx proxy is not running; "
                        + "run 'isx doctor' to diagnose.", true);
            }
            // Read once: create() configures the branch's address and its start diagnostics from it.
            bridgeCidr = BridgeSubnetCheck.resolveBridgeCidr(incus);
            BridgeSubnetCheck.warnIfConflict(bridgeCidr);
            FirewallDetector.warnIfNotRunning();
            checkCaMismatch(sourceConfig, req.source());
            var credError = missingCredentials(config, accounts.template(), accounts.accounts(), defs, loader);
            if (!credError.isEmpty()) throw new BranchException(credError);
        }

        try {
            HostResourceSetup.requireBranchableTemplate(req.source(),
                    IncusClient.configValue(sourceConfig, Metadata.HOST_RESOURCES));
        } catch (HostResourceSetup.ForbiddenMountTargetException e) {
            throw new BranchException(e.getMessage());
        }
        return new Preflight(req, defs, accounts.accounts(), accounts.origins(),
                accounts.template(), source, bridgeCidr);
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
        // What preflight read of the source stands for it throughout: a template is not
        // changed while it is branched from, and the copy is of the same type.
        var sourceConfig = preflight.source().path("config");
        boolean isVm = IncusClient.isVm(preflight.source());

        BuildOutput.branchHeader(name, source);

        var copyPlan = incus.planCopy(preflight.source());
        if (!copyPlan.cow()) {
            BuildOutput.warn("This branch will be a full copy, not a CoW clone: "
                    + copyPlan.fullCopyReason() + ". Run 'isx doctor' for details.");
        }
        BuildOutput.stepStart("Copying from template...");
        incus.copy(source, name, copyPlan, req.extraConfig());
        BuildOutput.stepDone();

        // The one read of the new branch until it starts. Re-read only after a step that wrote
        // to it; configureBranch's own write is the last thing that reads it.
        var branch = incus.instanceMetadata(name);

        // Configure GUI before start so environment.* keys are visible to init. First, because
        // it may push files: any push lands well before the start (see prefetchAndStart).
        if (req.gui()) {
            if (GuiPassthrough.configureGui(incus, name)) {
                incus.configSet(name, Metadata.GUI_ENABLED, "true");
            } else {
                GuiPassthrough.removeGui(incus, name);
                System.err.println("Continuing without GUI passthrough.");
            }
            branch = incus.instanceMetadata(name);
        } else {
            // Clean up inherited GUI devices/env from incus copy
            if (GuiPassthrough.removeGui(incus, name, branch)) branch = incus.instanceMetadata(name);
            warnIfTemplateWantsGui(sourceConfig, source, defs);
        }

        String cpu;
        if (req.cpu() != null) {
            cpu = String.valueOf(req.cpu());
        } else if (isVm) {
            cpu = String.valueOf(Math.max(1, ResourceLimits.hostProcessorCount() - 2));
        } else {
            cpu = null;
        }
        var memory = req.memory() != null ? req.memory()
                : isVm ? ResourceLimits.defaultVmMemoryLimit() : ResourceLimits.adaptiveMemoryLimit();
        var disk = req.disk() != null ? req.disk() : ResourceLimits.defaultDiskLimit();

        BuildOutput.step("Resource limits: " +
                (cpu != null ? cpu + " CPUs, " : "") + memory + " memory, " + disk + " disk.");
        var enableKvm = req.kvm() != null ? req.kvm()
                : "kvm".equals(IncusClient.configValue(sourceConfig, Metadata.INSTANCE_MODE));
        InstanceLifecycle.configureBranch(incus, name, new InstanceLifecycle.BranchSettings(
                cpu, memory, disk, networkMode, source, preflight.accounts(),
                preflight.accountOrigins(), enableKvm, req.extraConfig()), branch, preflight.bridgeCidr());
        announceAccountSelection(preflight.accounts());
        // Past configureBranch's write, the snapshot still holds everything read from here on:
        // keys the branch copied from its source, which that write does not touch.
        InstanceLifecycle.integrateWithHost(incus, name, InstanceType.INSTANCE, branch,
                GitRemoteUtils.collectRepos(preflight.template(), defs));

        // Inherited KVM passthrough was already dropped by configureBranch when not enabled.
        if (enableKvm && !KvmPassthrough.configureKvm(incus, name)) {
            System.err.println("Continuing without KVM — VMs inside this branch will not work.");
        }

        // Before the --no-start return: the inbox is plain device config, so it is there
        // however the instance is later started (#852).
        InstanceLifecycle.attachInbox(incus, name, req.inbox());

        if (!req.start()) return null;

        // Read nothing more once it starts: the Incus daemon blocks API calls after start due
        // to seccomp_notify lock contention. The bridge is read here only if preflight did not.
        var bridgeCidr = preflight.bridgeCidr() != null ? preflight.bridgeCidr()
                : BridgeSubnetCheck.resolveBridgeCidr(incus);
        var prefetched = InstanceLifecycle.runtimeConfig(branch.path("config"), bridgeCidr);
        InstanceLifecycle.startShowingProgress(incus, name, isVm);

        if (isVm) {
            BuildOutput.stepStart("Waiting for VM agent...");
            incus.waitForReady(name);
            BuildOutput.stepDone();
            InstanceLifecycle.pushDeferredVmFiles(incus, name, networkMode);
        }

        InstanceLifecycle.setupRuntime(incus, name, networkMode, prefetched);

        if (networkMode != NetworkMode.AIRGAP) {
            CertificateAuthority.fixContainerCaIfNeeded(incus, name);
            ProxyConfig.fixResolvConfIfNeeded(incus, name);
            // The template baked its own account's identity; a branch pinned to a different one
            // must not commit under it. Re-derived here rather than only on the next
            // InstancePrep, so an instance used via incus exec, SSH or an IDE is right too.
            // Needs the CA and resolv.conf above -- re-deriving goes through the proxy.
            InstanceLifecycle.reconcileAccountIdentities(incus, name);
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
            var selection = new java.util.LinkedHashMap<>(inherited.accounts());
            selection.putAll(AccountSelection.parse(accountOverrides));
            AccountSelection.validate(config, selection, AccountSelection.namespaceSetups(config, loader));
            return missingCredentials(config, inherited.template(), selection, defs, loader);
        } catch (AccountSelection.InvalidSelectionException
                 | AccountResolver.UnknownAccountException e) {
            return e.getMessage();
        }
    }

    /**
     * Checked against the template the branch inherits from -- for a branch of a branch, the leaf
     * template recorded on the source -- and the selection it will actually be stamped with.
     */
    private static String missingCredentials(SpawnConfig config, String templateName,
                                             Map<String, String> selection,
                                             Map<String, ImageDef> defs, ToolDefLoader loader) {
        var template = defs.get(templateName);
        if (template == null) return "";
        return CredentialCheck.check(config, template, defs, selection, loader);
    }

    /**
     * The branch's account selection: what it {@linkplain #inheritedAccounts inherits}, with any
     * {@code --account} override applied on top, validated and checked against what the source
     * was built for.
     */
    private static ResolvedAccounts resolveAccountSelection(String source, JsonNode sourceConfig,
                                                            List<String> accountOverrides,
                                                            Map<String, ImageDef> defs, SpawnConfig config,
                                                            Map<String, ToolSetup> setups) {
        var inherited = inheritedAccounts(source, sourceConfig, defs);
        var selection = new java.util.LinkedHashMap<>(inherited.accounts());
        var origins = new java.util.LinkedHashMap<>(inherited.origins());

        var overrides = AccountSelection.parse(accountOverrides);
        selection.putAll(overrides);
        overrides.keySet().forEach(ns -> origins.put(ns, AccountOrigin.EXPLICIT));

        AccountSelection.validate(config, selection, setups);

        // A branch is a CoW copy of an already-built template, so its environment is already
        // baked -- an --account that crosses auth modes is exactly as unhonourable here as it
        // is in 'isx account set', and must be refused the same way. Checked against the
        // source, whose env class the copy inherits.
        var reason = AccountSelection.incompatibilityReason(config, sourceConfig, source, selection, setups);
        if (!reason.isEmpty()) throw new AccountSelection.InvalidSelectionException(reason);

        return new ResolvedAccounts(inherited.template(), selection, origins);
    }

    /**
     * What a branch of {@code source} inherits before any {@code --account}: the template it was
     * built from, and the pins -- with who chose each -- that the branch would be stamped with.
     * A namespace absent from {@code accounts} is not pinned and follows the global default.
     *
     * @param template the leaf template the source was built from, or the source itself
     */
    public record Inherited(String template, Map<String, String> accounts,
                            Map<String, AccountOrigin> origins) {}

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
        return inheritedAccounts(source, incus.instanceMetadata(source).path("config"), defs);
    }

    /** As {@link #inheritedAccounts(IncusClient, String, Map)}, from the source's {@code config} already read. */
    public static Inherited inheritedAccounts(String source, JsonNode sourceConfig, Map<String, ImageDef> defs) {
        var profile = IncusClient.configValue(sourceConfig, Metadata.PROFILE);
        var templateName = !profile.isEmpty() ? profile : source;

        var selection = AccountSelection.resolve(defs.get(templateName), defs, Map.of());
        var origins = new java.util.LinkedHashMap<String, AccountOrigin>();
        selection.keySet().forEach(ns -> origins.put(ns, AccountOrigin.template(templateName)));

        var sourcePins = AccountSelection.fromConfig(sourceConfig);
        var sourceOrigins = AccountSelection.originsFromConfig(sourceConfig);
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
        return new Inherited(templateName, selection, origins);
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
        proxyRefresh.run();
    }

    private static void warnIfTemplateWantsGui(JsonNode sourceConfig, String source,
                                               Map<String, ImageDef> defs) {
        if ("true".equals(IncusClient.configValue(sourceConfig, Metadata.GUI_ENABLED))) {
            System.err.println("Note: '" + source + "' has GUI passthrough — consider using --gui.");
            return;
        }
        var def = defs.get(source);
        if (def != null && def.isGui()) {
            System.err.println("Note: '" + source + "' has GUI passthrough — consider using --gui.");
        }
    }

    private static void checkCaMismatch(JsonNode sourceConfig, String source) {
        var status = CertificateAuthority.CaTrust.snapshot()
                .classify(IncusClient.configValue(sourceConfig, Metadata.CA_FINGERPRINT));
        if (status != CaStatus.FOREIGN && status != CaStatus.REPAIRABLE) return;
        var profile = IncusClient.configValue(sourceConfig, Metadata.PROFILE);

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
                    "Rebuild the template to fix: \033[1misx build " + profile + "\033[0m");
        } else {
            BuildOutput.warnBanner("CA certificate mismatch",
                    "Template '" + source + "' was built with a different CA certificate.",
                    "TLS connections through the proxy will fail in branches.");
        }
        throw new BranchException("template '" + source + "' was built with a different CA "
                + "certificate" + (profile.isEmpty() ? "." : "; rebuild it: isx build " + profile), true);
    }
}
