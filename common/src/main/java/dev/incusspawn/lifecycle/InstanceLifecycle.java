package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.AutoRemoteService;
import dev.incusspawn.incus.BridgeAddress;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.InstanceUpdate;
import dev.incusspawn.incus.ResourceLimits;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.util.BuildOutput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shared helpers for instance/template creation lifecycle.
 * Eliminates duplication between BranchCommand and the TUI.
 */
public final class InstanceLifecycle {

    private InstanceLifecycle() {}

    /**
     * What {@link #configureBranch} sets on a fresh copy before it starts.
     *
     * @param cpu      {@code limits.cpu}; null or empty leaves the CPU count unlimited
     * @param parent   the instance or template the branch was copied from
     * @param accounts credential account pins to stamp, which clear any the copy carried that
     *                 they do not name; empty leaves the copied pins as they are
     * @param accountOrigins who chose each of {@code accounts}, recorded beside the pin
     * @param kvm      whether KVM passthrough is configured next; if not, KVM devices and
     *                 metadata inherited from the source are dropped
     * @param extraConfig further {@code config} keys the caller stamps on its branch, in the
     *                 same write; applied last
     * @param startsNow whether the caller starts the branch and pushes a VM's {@code .network}
     *                 file itself; if not, a VM is marked to get it from {@link #ensureReady}
     */
    public record BranchSettings(String cpu, String memory, String disk, NetworkMode networkMode,
                                 String parent, Map<String, String> accounts,
                                 Map<String, dev.incusspawn.config.AccountOrigin> accountOrigins,
                                 boolean kvm, Map<String, String> extraConfig, boolean startsNow) {
        public BranchSettings {
            accountOrigins = accountOrigins == null ? Map.of() : Map.copyOf(accountOrigins);
            extraConfig = extraConfig == null ? Map.of() : Map.copyOf(extraConfig);
        }

        public BranchSettings(String cpu, String memory, String disk, NetworkMode networkMode,
                              String parent, Map<String, String> accounts, boolean kvm) {
            this(cpu, memory, disk, networkMode, parent, accounts, Map.of(), kvm, Map.of(), true);
        }
    }

    /**
     * Configure a freshly copied branch before its first start, in one write to Incus.
     *
     * <p>Resource limits, the network mode, the static IP and its spoofing protection, the
     * branch metadata, the account pins and the removal of inherited KVM passthrough used to be
     * nine writes. Each costs Incus a rewrite of the instance's backup file, which is what made
     * them add up to ~110 ms of the time before start (#804). They are collected into one
     * {@link InstanceUpdate} against a single read of the instance instead: one PATCH, or one PUT
     * when an inherited KVM device has to go.
     *
     * <p>Airgap masks every NIC with {@code type: none} in that same write, which is the only way
     * to take a profile's NIC away from one instance (#813). Branching with network from an
     * airgapped instance overwrites those masks with the profile's NICs, in the same write too.
     */
    public static void configureBranch(IncusClient incus, String name, BranchSettings settings) {
        var mode = settings.networkMode();
        var instance = incus.instanceMetadata(name);
        if (instance.isMissingNode()) throw new IncusException("Failed to read instance " + name);
        var update = new InstanceUpdate();
        if (mode != NetworkMode.AIRGAP && NetworkMode.isAirgapped(instance)) {
            instance = InstanceNetwork.unmaskNics(incus, instance, update);
        }

        var cpu = settings.cpu();
        update.config("limits.cpu", cpu != null && !cpu.isEmpty() ? cpu : null);
        update.config("limits.memory", settings.memory());
        update.device("root", "size", settings.disk());

        BridgeAddress bridge = null;
        String nicDevice = null;
        // Full internet is the absence of a mode, not whatever the source was copied with
        update.config(Metadata.NETWORK_MODE, mode == NetworkMode.FULL ? null : mode.name());
        if (mode == NetworkMode.AIRGAP) {
            BuildOutput.step("Enabling network airgap.");
            InstanceNetwork.maskNics(instance, update);
            // No address: the copy request already removed its source's (IncusClient.copy), and an
            // airgapped instance must not keep one the proxy could take for another's.
            update.unset(Metadata.STATIC_IP);
            update.unset(Metadata.STATIC_GATEWAY);
        } else {
            // One read gives the gateway, the subnet to allocate from and the prefix to push
            bridge = BridgeAddress.require(incus);
            if (mode == NetworkMode.PROXY_ONLY) BuildOutput.step("Configuring proxy-only network.");
            nicDevice = IncusClient.nicDeviceName(instance, BridgeAddress.BRIDGE);
            if (nicDevice == null) {
                throw new IncusException("No NIC device for " + BridgeAddress.BRIDGE + " found on " + name);
            }
            // The address itself is allocated in InstanceNetwork.claimAndWrite, under the allocation lock
            update.device(nicDevice, "security.ipv4_filtering", "true");
            update.config(Metadata.STATIC_GATEWAY, bridge.gateway());
            // A VM takes its .network file only while running: one not started now owes it to
            // its next start through ensureReady, or it stays on DHCP for good (#1004)
            if (!settings.startsNow() && IncusClient.machineType(instance) == MachineType.VM) {
                update.config(Metadata.NETWORK_PUSH_PENDING, "true");
            }
        }
        update.config(Metadata.PROXY_GATEWAY,
                mode == NetworkMode.PROXY_ONLY ? bridge.gateway() : null);

        update.config(Metadata.TYPE, Metadata.TYPE_CLONE);
        update.config(Metadata.PARENT, settings.parent());
        update.config(Metadata.CREATED, Metadata.today());
        if (!settings.accounts().isEmpty()) {
            update.config(AccountSelection.stampUpdates(settings.accounts(), settings.accountOrigins(),
                    AccountSelection.fromConfig(instance.path("config"))));
        }
        if (!settings.kvm()) KvmPassthrough.removeKvm(instance, update);
        // A copy carries its source's MCP ownership stamps. A branch of an agent's instance
        // (say, one it handed over with keep_instance) belongs to whoever made the branch, and
        // must not be mistaken for the dead session's orphan and reaped.
        instance.path("config").fieldNames().forEachRemaining(key -> {
            if (Metadata.isMcpKey(key) && !settings.extraConfig().containsKey(key)) update.unset(key);
        });
        update.config(settings.extraConfig());
        if (IncusClient.machineType(instance) == MachineType.VM) {
            // Templates built before free page reporting existed don't carry it to their copies
            if (instance.path("config").path(RAW_QEMU_CONF).asText("").isBlank()) {
                update.config(RAW_QEMU_CONF, FREE_PAGE_REPORTING_CONF);
            }
            // Nor do templates built before Secure Boot was turned off (#1238)
            update.config(SECURE_BOOT, "false");
        }

        if (nicDevice == null) incus.update(name, instance, update);
        else InstanceNetwork.claimAndWrite(incus, name, instance, update, nicDevice, bridge, Set.of(), true);
    }

    /**
     * Off for every VM (#1238, decided by Sanne). Its firmware, {@code OVMF_CODE.secboot.fd},
     * needs SMM, which costs per vCPU on every boot -- 20 s at 8 vCPUs under nested KVM, where
     * isx VMs often run -- and it guards the guest's boot chain against a guest root that an isx
     * box hands its agent anyway. The isolation isx stands for is the VM boundary and the proxy,
     * which this does not touch.
     */
    static final String SECURE_BOOT = "security.secureboot";

    /** Turn Secure Boot off for a VM about to start, keeping the rest of its config; see {@link #SECURE_BOOT}. */
    public static void disableSecureBoot(IncusClient incus, String name) {
        incus.configSet(name, SECURE_BOOT, "false");
    }

    /**
     * What every VM template build sets before its first start, in one write: the default memory
     * size, free page reporting unless someone else's {@code raw.qemu.conf} is there, and
     * Secure Boot off ({@link #SECURE_BOOT}). A build copied from a parent gets it too, since a
     * parent built by an older isx carries none of it.
     */
    public static void prepareVmBuild(IncusClient incus, String name) {
        var instance = incus.instanceMetadata(name);
        if (instance.isMissingNode()) throw new IncusException("Failed to read instance " + name);
        var update = new InstanceUpdate();
        update.config("limits.memory", ResourceLimits.defaultVmMemoryLimit());
        if (instance.path("config").path(RAW_QEMU_CONF).asText("").isBlank()) {
            update.config(RAW_QEMU_CONF, FREE_PAGE_REPORTING_CONF);
        }
        update.config(SECURE_BOOT, "false");
        incus.update(name, instance, update);
    }

    static final String RAW_QEMU_CONF = "raw.qemu.conf";

    /**
     * Virtio-balloon free page reporting, so memory the guest frees goes back to the host.
     * Without it QEMU keeps every page the guest ever touched until the VM stops, and a VM's page
     * cache grows until it has touched all of {@code limits.memory}. Incus merges this into its own
     * balloon device section. A {@code raw.qemu.conf} set by someone else is left alone: merging
     * into another author's QEMU config is not worth the risk of a VM that no longer starts.
     */
    static final String FREE_PAGE_REPORTING_CONF =
            "[device \"qemu_balloon\"]\nfree-page-reporting = \"on\"\n";

    /**
     * Re-derive anything the build baked from a credential account the instance is no longer
     * pinned to -- today, the git identity after {@code isx branch --account github=other} or
     * {@code isx account set}, or after the account's token is replaced with another user's.
     *
     * <p>Runs after start, because re-deriving means asking the API through the proxy, which is
     * also what makes it correct: the proxy already knows which account this instance uses, so
     * the tool needs no argument beyond the account name.
     *
     * <p>Called from {@code BranchCommand} so a freshly branched instance is right from its
     * first commit, and again from {@code InstancePrep} so one branched {@code --no-start}, or
     * re-pointed later, is reconciled on its next use. Both are needed: only the second can
     * catch a swap on an existing instance, and only the first stops an instance being wrong
     * for as long as nobody happens to run {@code isx shell}.
     *
     * <p>Only namespaces whose tool can re-derive appear here; the ones that cannot were
     * refused at selection time, so there is nothing to reconcile.
     *
     * <p>An instance from a template an older isx built carries no
     * {@link Metadata#ACCOUNT_IDENTITY_VERIFIED}: its stamp came from config.yaml and may claim
     * an identity its {@code .gitconfig} never got. Its first reconcile with an account
     * configured asks the guest once, re-derives what is missing and sets the marker -- one
     * exec and one write for such an instance, none for one whose template this isx built.
     */
    public static void reconcileAccountIdentities(IncusClient incus, String name) {
        reconcileAccountIdentities(incus, name, null);
    }

    /**
     * As {@link #reconcileAccountIdentities(IncusClient, String)}, from the instance as the
     * caller already read it -- the shell path's, which would otherwise read it again. Its
     * account pins, stamps and network mode are all this looks at.
     */
    public static void reconcileAccountIdentities(IncusClient incus, String name, JsonNode instance) {
        reconcileAccountIdentities(incus, name, instance, SpawnConfig.load(), null, BuildOutput::step,
                msg -> System.err.println("Warning: " + msg));
    }

    /**
     * As {@link #reconcileAccountIdentities(IncusClient, String)}, reporting through sinks rather
     * than the terminal -- the TUI's, where a line printed from a background thread would be
     * drawn over and lost.
     */
    public static void reconcileAccountIdentities(IncusClient incus, String name,
                                                  Consumer<String> progress, Consumer<String> warnings) {
        reconcileAccountIdentities(incus, name, null, SpawnConfig.load(), null, progress, warnings);
    }

    static void reconcileAccountIdentities(IncusClient incus, String name, SpawnConfig config,
                                           Map<String, dev.incusspawn.tool.ToolSetup> knownSetups,
                                           Consumer<String> progress, Consumer<String> warnings) {
        reconcileAccountIdentities(incus, name, null, config, knownSetups, progress, warnings);
    }

    /**
     * @param instance    the instance as the caller read it, or {@code null} to read it
     * @param knownSetups the credential namespaces' tools, when the caller has them -- the TUI
     *                    passes its own so the tool definitions are not re-read from disk;
     *                    {@code null} decides against the built-in ones that can re-derive
     */
    static void reconcileAccountIdentities(IncusClient incus, String name, JsonNode instance, SpawnConfig config,
                                           Map<String, dev.incusspawn.tool.ToolSetup> knownSetups,
                                           Consumer<String> progress, Consumer<String> warnings) {
        try {
            var plan = instance != null
                    ? AccountSelection.identityReconcile(config, instance, knownSetups)
                    : AccountSelection.identityReconcile(config, incus, name, knownSetups);
            if (plan.isEmpty()) return;

            var container = new Container(incus, name);
            var setups = knownSetups != null ? knownSetups : AccountSelection.rederivableSetups();
            var stale = new LinkedHashMap<>(plan.stale());
            var updates = new LinkedHashMap<String, String>();
            // From a template an older isx built: its stamp may claim an identity the guest never
            // got, so ask the guest once. The marker is set only once everything here succeeded.
            // Asked before waiting for an address, which only re-deriving needs: an instance
            // that has its identity is just marked -- and stamped with the account's identity, so
            // a later change of account or token is still seen as one. Whose identity the guest
            // holds is not something the check can tell (DESIGN.md).
            plan.unverified().forEach((namespace, account) -> {
                var setup = setups.get(namespace);
                if (setup == null) return;
                if (setup.lacksBakedIdentity(container)) {
                    stale.put(namespace, account);
                } else {
                    updates.put(Metadata.accountIdentityKey(namespace), setup.bakedAccountIdentity(config, account));
                }
            });

            // Re-deriving goes out through the proxy, and a just-started instance may not have
            // an address yet. Only paid for when something is actually stale, and returns as
            // soon as the address is up -- which for an instance that has been running a while
            // is the first poll.
            if (!stale.isEmpty() && !incus.pollUntilReady(name, 30, "sh", "-c",
                    "ip -4 -o addr show scope global | grep -q inet")) {
                throw new IncusException(name + " has no IPv4 address");
            }

            if (!plan.verified()) updates.put(Metadata.ACCOUNT_IDENTITY_VERIFIED, "true");
            stale.forEach((namespace, account) -> {
                var setup = setups.get(namespace);
                if (setup == null) return;
                progress.accept("Updating " + namespace + " identity for account '"
                        + account + "'...");
                setup.rebakeForAccount(container, account);
                updates.put(Metadata.accountIdentityKey(namespace), setup.bakedAccountIdentity(config, account));
            });
            if (!updates.isEmpty()) incus.configSetAll(name, updates);
        } catch (Exception e) {
            // Best effort: a stale identity is a wrong commit author, not a broken instance,
            // and the next use tries again because the stamp is only updated on success.
            warnings.accept("could not update credential identity for " + name
                    + ": " + e.getMessage());
        }
    }

    /**
     * Refuse an account change {@link #changeAccounts} would refuse, without making it -- for a
     * front end that wants to say no while the user is still choosing and make the change
     * elsewhere (the TUI, off its event thread).
     *
     * @param setups the credential namespaces' tools ({@link AccountSelection#namespaceSetups})
     * @throws AccountSelection.InvalidSelectionException  when the built instance cannot honour it
     * @throws dev.incusspawn.config.AccountResolver.UnknownAccountException  for an unknown or
     *         incomplete account
     */
    public static void checkAccountChange(IncusClient incus, String instance, SpawnConfig config,
                                          Map<String, dev.incusspawn.tool.ToolSetup> setups,
                                          Map<String, String> changes) {
        checkAccountChange(incus, instance, config, setups, changes, AccountSelection.read(incus, instance));
    }

    /**
     * Only the namespaces being changed are checked. A pin the change does not touch is not the
     * user's question right now -- and one naming an account since removed would otherwise
     * refuse every change to anything else on the instance, including the one that repairs it.
     *
     * @return the pins the change would leave
     */
    private static Map<String, String> checkAccountChange(IncusClient incus, String instance, SpawnConfig config,
                                                          Map<String, dev.incusspawn.tool.ToolSetup> setups,
                                                          Map<String, String> changes,
                                                          Map<String, String> current) {
        var pins = new LinkedHashMap<String, String>();
        changes.forEach((ns, account) -> { if (account != null) pins.put(ns, account); });
        AccountSelection.validate(config, pins, setups);

        // The check reads a null account as "the default": the default may be a Claude auth
        // mode the build did not bake, so unpinning is refused on the same grounds as pinning.
        var reason = AccountSelection.incompatibilityReason(config, incus, instance,
                new LinkedHashMap<>(changes), setups);
        if (!reason.isEmpty()) throw new AccountSelection.InvalidSelectionException(reason);

        var merged = new LinkedHashMap<>(current);
        changes.forEach((ns, account) -> {
            if (account == null) merged.remove(ns); else merged.put(ns, account);
        });
        return merged;
    }

    /**
     * Change which credential accounts an instance uses. {@code changes} maps a namespace to the
     * account to pin, or to {@code null} to remove the pin so the instance follows the default;
     * namespaces it does not name keep what they had -- naming one must never silently unpin
     * another.
     *
     * <p>The one path for every front end ({@code isx account set/unset}, the TUI), so none can
     * skip a step: validate, refuse what the built instance could not honour, stamp, tell the
     * proxy, and re-derive what the build baked while the instance is running (a stopped one
     * is reconciled by its next use). Refusing happens while the user is still choosing, rather
     * than surfacing later as a failing request inside.
     *
     * @param setups the credential namespaces' tools ({@link AccountSelection#namespaceSetups});
     *               the TUI passes those it already loaded, so nothing here re-reads the tool
     *               definitions from disk
     * @return the instance's pins afterwards
     * @throws AccountSelection.InvalidSelectionException  when the built instance cannot honour it
     * @throws dev.incusspawn.config.AccountResolver.UnknownAccountException  for an unknown or
     *         incomplete account
     */
    public static Map<String, String> changeAccounts(IncusClient incus, String instance, SpawnConfig config,
                                                     Map<String, dev.incusspawn.tool.ToolSetup> setups,
                                                     Map<String, String> changes,
                                                     Consumer<String> progress, Consumer<String> warnings) {
        var current = AccountSelection.read(incus, instance);
        var merged = checkAccountChange(incus, instance, config, setups, changes, current);

        // Untouched pins keep who chose them; every pin made here is an explicit choice --
        // including re-choosing the account a template pinned, which from now on is the user's
        // and no longer the template's.
        var currentOrigins = AccountSelection.readOrigins(incus, instance);
        var origins = new LinkedHashMap<>(currentOrigins);
        changes.forEach((ns, account) -> {
            if (account == null) origins.remove(ns); else origins.put(ns, dev.incusspawn.config.AccountOrigin.EXPLICIT);
        });
        if (merged.equals(current) && origins.equals(currentOrigins)) return merged;
        AccountSelection.stamp(incus, instance, merged, origins, current);
        if (merged.equals(current)) return merged; // only who chose it changed: nothing to re-derive
        dev.incusspawn.proxy.ProxyService.signalAccountRefresh();

        // The token swaps live, but anything the build *baked* from the old account -- the
        // git identity -- would still be the old one, so the instance would push with one
        // account and commit as another.
        if (!"Stopped".equalsIgnoreCase(incus.getInstanceStatus(instance))) {
            reconcileAccountIdentities(incus, instance, config, setups, progress, warnings);
        }
        return merged;
    }

    /**
     * Make {@code name} answer exec before a shell or command runs in it: start it if stopped,
     * or recover a VM whose agent does not answer ({@link VmAgentRecovery}). A running instance
     * rebooted without isx -- {@code incus restart}, a reboot in the guest, autostart after a
     * host reboot -- lost its secret with its {@code /run}, and gets a new one
     * ({@link #giveSecretToThisBoot}). Then push a VM's
     * deferred {@code .network} file if it still owes one ({@link Metadata#NETWORK_PUSH_PENDING}),
     * whatever the status it was in: a VM repaired while stopped and then started outside isx
     * (plain {@code incus start}, autostart after a host reboot) boots its old file, and only this
     * push brings it back onto the network.
     *
     * <p>Progress and warnings go to {@code say}, never straight to stdout or stderr, so a caller
     * that owns the terminal (the TUI) can route them.
     *
     * @param instance the instance as read, for its status and the pending mark
     */
    public static void ensureReady(IncusClient incus, String name, JsonNode instance,
                                   MachineType machineType, Consumer<String> say) {
        if ("Stopped".equalsIgnoreCase(instance.path("status").asText(""))) {
            say.accept("Starting " + name + "...");
            startForUse(incus, name, instance, machineType, say);
        } else if (machineType == MachineType.VM) {
            // QEMU may reboot a VM in place, which changes nothing the host can see: only the
            // guest can tell, so the agent probe asks it on the way, for no request of its own
            var probe = probeAgent(incus, name);
            if (probe == null) {
                VmAgentRecovery.restartForAgent(incus, name, say);
            } else if (InstanceSecret.missingIn(probe)) {
                giveSecretToThisBoot(incus, name, null, Metadata.isMcpCaller(instance), say);
            }
        } else {
            // Every container reboot is a start: the instance already read says which boot it is
            var bootedAt = bootOf(instance);
            if (!bootedAt.isEmpty()
                    && !secretBootStamp(bootedAt).equals(instance.path("config").path(Metadata.INSTANCE_SECRET_BOOT).asText(""))) {
                giveSecretToThisBoot(incus, name, bootedAt, Metadata.isMcpCaller(instance), say);
            }
        }
        // Only a VM is ever marked
        if (instance.path("config").has(Metadata.NETWORK_PUSH_PENDING)) {
            InstanceNetwork.pushDeferredNetworkConfig(incus, name, instance, InstanceNetwork.bridgePrefixLen(incus),
                    msg -> say.accept(BuildOutput.STEP_INDENT + "Warning: " + msg));
        }
    }

    /**
     * Start a stopped instance for use and wait until it answers exec: the start every path
     * that brings an existing instance up goes through -- {@code isx shell} and {@code isx run},
     * the TUI's shell, and their CA repairs. Prepares its host devices
     * ({@link #prepareHostDevicesForStart}), and gives it a new secret ({@link
     * #rotateInstanceSecret}), which the readiness probe itself puts in place, with the Claude
     * Code registration its {@code mcp-caller} grant calls for (#1182), read from the instance
     * the device repairs read: neither costs a request of its own.
     *
     * @return the instance as read once it answered ({@link #recordSecretBoot}), for the caller
     *         to reuse rather than read again; null when there is none
     */
    public static JsonNode startForUse(IncusClient incus, String name, MachineType machineType,
                                       Consumer<String> warn) {
        return startForUse(incus, name, incus.instanceMetadata(name), machineType, warn);
    }

    /** {@link #startForUse(IncusClient, String, MachineType, Consumer)}, with the stopped instance already read. */
    static JsonNode startForUse(IncusClient incus, String name, JsonNode instance, MachineType machineType,
                                Consumer<String> warn) {
        var placeholders = ProofToken.declaredInBackground();
        prepareHostDevicesForStart(incus, name, instance, warn);
        // A VM branched or built before #1238 still has Secure Boot on: it goes in the write the
        // secret makes anyway
        var secret = rotateInstanceSecret(incus, name, machineType == MachineType.VM
                && !"false".equals(instance.path("config").path(SECURE_BOOT).asText())
                ? Map.of(SECURE_BOOT, "false") : Map.of());
        startInstance(incus, name, warn);
        incus.waitForReady(name, machineType, InstanceSecret.GUEST_SCRIPT,
                InstanceSecret.guestEnv(secret, Metadata.isMcpCaller(instance), placeholders.join()));
        return recordSecretBoot(incus, name, machineType);
    }

    /**
     * Restart a running instance and wait until it answers, with a new secret (#934): the
     * reboot empties the guest's {@code /run}, so without one the box would be left with none.
     *
     * @param mcpCaller whether the instance holds the {@code mcp-caller} grant, as the caller's
     *                  listing says ({@link InstanceSecret#guestEnv})
     */
    public static void restartForUse(IncusClient incus, String name, MachineType machineType,
                                     boolean mcpCaller) {
        // Before, not after: once the restart returns the guest is booting, when Incus API calls
        // contend with it. A restart that then fails leaves a guest whose secret no longer
        // matches, which is refused -- the safe way for it to go wrong.
        var placeholders = ProofToken.declaredInBackground();
        var secret = rotateInstanceSecret(incus, name);
        incus.restart(name);
        incus.waitForReady(name, machineType, InstanceSecret.GUEST_SCRIPT,
                InstanceSecret.guestEnv(secret, mcpCaller, placeholders.join()));
        recordSecretBoot(incus, name, machineType);
    }

    /**
     * Give an instance a new secret for the start about to happen, and return it for the
     * caller to put in place once the guest answers (#934). Recording its hash is one write.
     *
     * <p>The proxy is not signalled: on Linux that costs a bridge read, a health call and a fork
     * on every start, and makes the proxy list every instance while this one boots. Nothing
     * needs it sooner -- the previous start's secret went with the guest's {@code /run}, and a
     * caller refused on a snapshot taken before the start refreshes and asks again
     * ({@link dev.incusspawn.proxy.InstanceRegistry#identify}).
     */
    static String rotateInstanceSecret(IncusClient incus, String name) {
        return rotateInstanceSecret(incus, name, Map.of());
    }

    /** {@link #rotateInstanceSecret}, with {@code alongside} recorded in the same write. */
    private static String rotateInstanceSecret(IncusClient incus, String name, Map<String, String> alongside) {
        var stamp = new HashMap<>(alongside);
        var secret = InstanceSecret.stampInto(stamp);
        incus.configSetAll(name, stamp);
        return secret;
    }

    /**
     * What {@link Metadata#INSTANCE_SECRET_BOOT} records for a delivery to boot {@code bootedAt}:
     * the boot, and that the delivery carried proof tokens (#1106). A stamp written by an isx
     * that predates them is the bare boot, so it no longer matches, and the next shell into a
     * container that isx started then gives it a new secret with its proofs -- once, from the
     * instance it already read.
     */
    static String secretBootStamp(String bootedAt) {
        return bootedAt + " proofs";
    }

    /** When Incus last started {@code instance} -- on every start, a reboot included -- or "". */
    private static String bootOf(JsonNode instance) {
        return instance.path("last_used_at").asText("");
    }

    /**
     * Record which boot of container {@code name} its new secret went to, once the guest
     * answers, so that {@link #ensureReady} can tell a later reboot from it at no cost
     * ({@link Metadata#INSTANCE_SECRET_BOOT}). Its boot is only known after the start, so this
     * reads the instance, and returns that read for the caller's next step to reuse rather than
     * make again (the CA check, {@link CertificateAuthority#fixContainerCaIfNeeded(IncusClient,
     * String, JsonNode)}): the write is the only request it adds. Best-effort: without it, the
     * next shell gives the box another secret, which is the safe way for it to go wrong. A VM is
     * never stamped.
     *
     * @return the instance as read once the guest answered, or null for a VM or a failed read
     */
    static JsonNode recordSecretBoot(IncusClient incus, String name, MachineType machineType) {
        if (machineType == MachineType.VM) return null;
        try {
            // A failed read must not reach the CA check as an empty instance, which it would
            // take for one with no CA to check: null makes it read again
            var instance = incus.instanceMetadataOrThrow(name);
            if (instance == null) return null;
            var bootedAt = bootOf(instance);
            if (!bootedAt.isEmpty()) incus.configSet(name, Metadata.INSTANCE_SECRET_BOOT, secretBootStamp(bootedAt));
            return instance;
        } catch (RuntimeException e) {
            // The next ensureReady sees an unrecorded boot
            return null;
        }
    }

    /**
     * Give running instance {@code name} a new secret, for a boot isx did not start (#1024):
     * its hash recorded first -- with {@code bootedAt}, the container boot it goes to, when
     * there is one -- then put in place by the script every start runs. Best-effort, like every
     * delivery: a box left without its secret is refused, and the shell must still open.
     */
    private static void giveSecretToThisBoot(IncusClient incus, String name, String bootedAt,
                                             boolean mcpCaller, Consumer<String> say) {
        String failure;
        try {
            var placeholders = ProofToken.declaredInBackground();
            var secret = rotateInstanceSecret(incus, name,
                    bootedAt == null ? Map.of() : Map.of(Metadata.INSTANCE_SECRET_BOOT, secretBootStamp(bootedAt)));
            // GUEST_SCRIPT never fails, so the same exec asks whether the secret is now there:
            // a write it skipped would otherwise give a VM a new secret on every shell, silently
            var delivery = incus.shellExec(name, InstanceSecret.guestEnv(secret, mcpCaller, placeholders.join()), "sh", "-c",
                    InstanceSecret.GUEST_SCRIPT + "\n" + InstanceSecret.GUEST_CHECK);
            if (delivery.success() && !InstanceSecret.missingIn(delivery.stdout())) return;
            failure = delivery.success() ? "the guest did not keep it" : "exit code " + delivery.exitCode();
        } catch (RuntimeException e) {
            failure = e.getMessage();
        }
        // The stamp went with the hash, before the guest had the secret: unset, so the next
        // shell tries again rather than taking this boot for one that holds it. Only on this
        // path, which costs the one that works nothing.
        if (bootedAt != null) {
            try {
                incus.configSet(name, Metadata.INSTANCE_SECRET_BOOT, "");
            } catch (RuntimeException ignored) {
                // Then the box stays without a secret until isx starts it: refused, which is safe
            }
        }
        say.accept(BuildOutput.STEP_INDENT + "Warning: could not give " + name
                + " a new instance secret after its reboot: " + failure);
    }

    /**
     * Ask a running VM's agent whether it answers, and its guest whether it still holds its
     * secret ({@link InstanceSecret#GUEST_CHECK}): what the guest printed, or null if the agent
     * does not answer.
     */
    private static String probeAgent(IncusClient incus, String name) {
        try {
            var result = incus.shellExec(name, "sh", "-c", InstanceSecret.GUEST_CHECK);
            return result != null && result.success() ? result.stdout() : null;
        } catch (RuntimeException agentDown) {
            return null;
        }
    }

    /**
     * Start an instance, falling back once if IP spoofing protection is what stops it.
     *
     * <p>Setting {@code security.ipv4_filtering} <em>succeeds</em> on a host that cannot enforce
     * it; the failure only appears when the instance starts and Incus tries to install the
     * per-NIC rules ("The kernel doesn't support the ebtables 'filter' table" on xtables, the
     * equivalent nft error otherwise). Without this, such a host would accept every branch and
     * then be unable to start any of them -- a working setup broken by a hardening step.
     *
     * <p>The fallback drops the setting, says plainly what is no longer enforced, and starts.
     * An instance that works without separation beats an instance that does not work, but the
     * user has to know which one they have.
     */
    public static void startInstance(IncusClient incus, String name) {
        startInstance(incus, name, System.err::println);
    }

    /**
     * As above, reporting the loss of protection to {@code warn} rather than stderr, for callers
     * that may own the terminal (the TUI).
     */
    public static void startInstance(IncusClient incus, String name, Consumer<String> warn) {
        try {
            incus.start(name);
        } catch (RuntimeException e) {
            if (!InstanceNetwork.looksLikeIpFilteringFailure(e) || !InstanceNetwork.disableIpFiltering(incus, name, warn)) throw e;
            incus.start(name);
        }
    }

    public static void tagMetadata(IncusClient incus, String name, String type, String parent) {
        incus.configSetAll(name, Map.of(
                Metadata.TYPE, type,
                Metadata.PARENT, parent,
                Metadata.CREATED, Metadata.today()));
    }

    /**
     * Remove host-side integration for a destroyed or renamed instance.
     */
    public static void removeHostIntegration(String name) {
        AutoRemoteService.removeRemotes(name, msg -> {});
        SshKeyManager.cleanupInstance(name);
        ZmxSocketForward.cleanup(name);
    }

    /**
     * Repair an existing, stopped instance before starting it. Every path that starts one for use
     * (the {@code isx shell}/{@code run} prep, the TUI, {@link #ensureReady}, VM agent recovery)
     * goes through here, so a repair placed here reaches all of them, and any path that merges
     * them must keep calling it.
     *
     * <ul>
     *   <li>Host-side devices: a host-resource source that has disappeared and a missing zmx socket
     *       directory otherwise fail Incus start validation with {@code Missing source path}.</li>
     *   <li>IP spoofing protection ({@link InstanceNetwork#rearmIpFiltering}): turned back on where it is off.</li>
     * </ul>
     */
    public static void prepareHostDevicesForStart(IncusClient incus, String name) {
        prepareHostDevicesForStart(incus, name, System.err::println);
    }

    /**
     * As above, reporting each device it removes to {@code warn}. The TUI starts instances while it
     * owns the terminal, where stderr would be drawn over and lost, so it passes a sink that puts
     * the warning on its status line instead.
     */
    public static void prepareHostDevicesForStart(IncusClient incus, String name, Consumer<String> warn) {
        // All repairs read the same instance, so fetch it once: start has to
        // stay as cheap as it was before the repairs existed.
        prepareHostDevicesForStart(incus, name, incus.instanceMetadata(name), warn);
    }

    /** {@link #prepareHostDevicesForStart(IncusClient, String, Consumer)} with the instance already read. */
    public static void prepareHostDevicesForStart(IncusClient incus, String name, JsonNode instance,
                                                  Consumer<String> warn) {
        HostResourceSetup.removeStaleDevices(incus, name, instance, warn);
        removeStaleInbox(incus, name, instance, warn);
        ZmxSocketForward.ensureHostDirForStart(incus, name, instance);
        InstanceNetwork.rearmIpFiltering(incus, name, instance, warn);
    }

    /**
     * Removes the {@code --inbox} device when its host directory is gone (#854), which would
     * otherwise fail Incus start validation with {@code Missing source path}. The inbox is not a
     * host resource, so {@link HostResourceSetup#removeStaleDevices} never sees it. The user asked
     * for this mount, so dropping it is announced, with the command to add it back.
     */
    static void removeStaleInbox(IncusClient incus, String name, JsonNode instance, Consumer<String> warn) {
        var source = IncusClient.deviceSource(instance, INBOX_DEVICE);
        if (source.isEmpty()) return;
        var hostPath = HostResourceSetup.hostPathOfDeviceSource(source);
        if (hostPath == null || Files.isDirectory(hostPath)) return;
        incus.deviceRemove(name, INBOX_DEVICE);
        // The hint reuses the device's own source: on macOS that is the appliance VM's /host view
        // of the path, which is what incus needs there, not the host path the user would type.
        warn.accept(BuildOutput.STEP_INDENT + "Warning: inbox directory not found: " + hostPath
                + " (device removed, starting without ~/inbox).\n" + BuildOutput.STEP_INDENT
                + "  To add it back, recreate it, then: incus config device add " + name + " " + INBOX_DEVICE
                + " disk source=" + source + " path=" + INBOX_PATH + " readonly=true");
    }

    /**
     * Apply host resource devices and (for instances) add git remotes.
     */
    public static void integrateWithHost(IncusClient incus, String name, InstanceType instanceType,
                                         MachineType machineType) {
        var hrJson = incus.configGet(name, Metadata.HOST_RESOURCES);
        var hostResources = HostResourceSetup.deserialize(hrJson);
        if (!hostResources.isEmpty()) {
            BuildOutput.step("Applying host resources.");
            HostResourceSetup.applyForInstance(incus, name, hostResources, machineType);
        }

        if (instanceType == InstanceType.INSTANCE) {
            AutoRemoteService.addRemotes(incus, name, BuildOutput::step);

            var buildSourceJson = incus.configGet(name, Metadata.BUILD_SOURCE);
            if (ZmxSocketForward.isZmxInstalled(buildSourceJson)) {
                ZmxSocketForward.configure(incus, name);
            }
        }
    }

    static final String INBOX_DEVICE = "inbox";
    static final String INBOX_PATH = "/home/agentuser/inbox";

    /**
     * Attach the {@code --inbox} directory, before start (#828): on a VM a device present at start
     * gets its own PCIe root port instead of one of the 8 spare hotplug slots.
     */
    public static void attachInbox(IncusClient incus, String name, Path inboxPath) {
        if (inboxPath == null) return;
        if (!java.nio.file.Files.isDirectory(inboxPath)) {
            System.err.println(BuildOutput.STEP_INDENT + "Warning: inbox path '" + inboxPath +
                    "' is not a directory, skipping.");
            return;
        }
        BuildOutput.step("Mounting inbox: " + inboxPath.toAbsolutePath() + ".");
        incus.deviceAdd(name, INBOX_DEVICE, "disk",
                "source=" + HostResourceSetup.translateForVm(inboxPath.toAbsolutePath().toString()),
                "path=" + INBOX_PATH,
                "readonly=true");
    }

    public static String getUid() {
        try {
            var pb = new ProcessBuilder("id", "-u");
            var p = pb.start();
            var output = new String(p.getInputStream().readAllBytes()).strip();
            int exitCode = p.waitFor();
            if (exitCode != 0 || output.isEmpty() || !output.chars().allMatch(Character::isDigit)) {
                return "1000";
            }
            return output;
        } catch (Exception e) {
            return "1000";
        }
    }
}
