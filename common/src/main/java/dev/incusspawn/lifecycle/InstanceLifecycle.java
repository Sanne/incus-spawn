package dev.incusspawn.lifecycle;

import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
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
import dev.incusspawn.proxy.CredentialProof;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.ssh.SshKeyManager;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostLock;
import dev.incusspawn.util.Output;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared helpers for instance/template creation lifecycle.
 * Eliminates duplication between BranchCommand and the TUI.
 */
public final class InstanceLifecycle {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PROOFS_SUFFIX = Pattern.compile(" proofs-v[0-9]+:");

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
     *
     * <p>When another process changes the instance between the read and a write that removes a
     * device, the instance is read and the update built again ({@link IncusClient#retryWhileChanged}).
     */
    public static void configureBranch(IncusClient incus, String name, BranchSettings settings) {
        var mode = settings.networkMode();
        if (mode == NetworkMode.AIRGAP) BuildOutput.step("Enabling network airgap.");
        if (mode == NetworkMode.PROXY_ONLY) BuildOutput.step("Configuring proxy-only network.");
        // One read gives the gateway, the subnet to allocate from and the prefix to push; none of
        // it depends on the instance, so a retry does not read it again
        var bridge = mode == NetworkMode.AIRGAP ? null : BridgeAddress.require(incus);
        IncusClient.retryWhileChanged(() -> configureBranchOnce(incus, name, settings, bridge));
    }

    private static void configureBranchOnce(IncusClient incus, String name, BranchSettings settings,
                                            BridgeAddress bridge) {
        var mode = settings.networkMode();
        var read = incus.readInstance(name);
        var instance = read.metadata();
        if (instance.isMissingNode()) throw new IncusException("Failed to read instance " + name);
        var update = new InstanceUpdate();
        if (mode != NetworkMode.AIRGAP && NetworkMode.isAirgapped(instance)) {
            instance = InstanceNetwork.unmaskNics(incus, instance, update);
            // Written as it will then expand, still conditional on the read
            read = new IncusClient.InstanceRead(instance, read.etag());
        }

        var cpu = settings.cpu();
        update.config("limits.cpu", cpu != null && !cpu.isEmpty() ? cpu : null);
        update.config("limits.memory", settings.memory());
        update.device("root", "size", settings.disk());

        String nicDevice = null;
        // Full internet is the absence of a mode, not whatever the source was copied with
        update.config(Metadata.NETWORK_MODE, mode == NetworkMode.FULL ? null : mode.name());
        if (mode == NetworkMode.AIRGAP) {
            InstanceNetwork.maskNics(incus, instance, update);
            // No address: the copy request already removed its source's (IncusClient.copy), and an
            // airgapped instance must not keep one the proxy could take for another's.
            update.unset(Metadata.STATIC_IP);
            update.unset(Metadata.STATIC_GATEWAY);
        } else {
            update.unset(Metadata.AIRGAP_MASKED);
            var nic = IncusClient.nic(instance, BridgeAddress.BRIDGE);
            if (nic == null) {
                throw new IncusException("No NIC device for " + BridgeAddress.BRIDGE + " found on " + name);
            }
            nicDevice = nic.name();
            // The address itself is allocated in InstanceNetwork.claimAndWrite, under the allocation lock
            update.device(nicDevice, "security.ipv4_filtering", "true");
            // Proxy-only is the host's ACL on this NIC, which BranchFlow.preflight has written. A
            // branch with full network of a proxy-only instance drops it.
            if (mode == NetworkMode.PROXY_ONLY) {
                ProxyOnlyAcl.requireOneNic("'" + name + "' is proxy-only, but it", instance,
                        "Remove the device from its source or profile, or use full network or --airgap.");
                ProxyOnlyAcl.NIC_KEYS.forEach((key, value) -> update.device(nic.name(), key, value));
            } else if (ProxyOnlyAcl.anyKey(nic.config())) {
                ProxyOnlyAcl.NIC_KEYS.keySet().forEach(key -> update.device(nic.name(), key, null));
            }
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
        // A copy carries its source's ownership stamps, isx mcp's and an external owner's. A branch of an agent's instance
        // (say, one it handed over with keep_instance) belongs to whoever made the branch, and
        // must not be mistaken for the dead session's orphan and reaped.
        var branchConfig = instance.path("config");
        if (branchConfig instanceof io.hyperfoil.tools.jjq.value.JqObject branchObject) {
            branchObject.forEach((key, value) -> {
                if (Metadata.isOwnershipKey(key) && !settings.extraConfig().containsKey(key)) update.unset(key);
            });
        }
        update.config(settings.extraConfig());
        if (IncusClient.machineType(instance) == MachineType.VM) {
            // Templates built before free page reporting existed don't carry it to their copies
            if (instance.path("config").path(RAW_QEMU_CONF).asText("").isBlank()) {
                update.config(RAW_QEMU_CONF, FREE_PAGE_REPORTING_CONF);
            }
            // Nor do templates built before Secure Boot was turned off (#1238)
            update.config(SECURE_BOOT, "false");
        }

        if (nicDevice == null) incus.update(name, read, update);
        else InstanceNetwork.claimAndWrite(incus, name, read, update, nicDevice, bridge, Set.of(), true);
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
     * size, free page reporting unless someone else's {@code raw.qemu.conf} is there,
     * Secure Boot off ({@link #SECURE_BOOT}) and the root disk on virtio-blk ({@link #ROOT_BUS}).
     * A build copied from a parent gets it too, since a parent built by an older isx carries
     * none of it. Branches copy the template's devices, so they boot the same way.
     */
    public static void prepareVmBuild(IncusClient incus, String name) {
        var read = incus.readInstance(name);
        var instance = read.metadata();
        if (instance.isMissingNode()) throw new IncusException("Failed to read instance " + name);
        var update = new InstanceUpdate();
        update.config("limits.memory", ResourceLimits.defaultVmMemoryLimit());
        if (instance.path("config").path(RAW_QEMU_CONF).asText("").isBlank()) {
            update.config(RAW_QEMU_CONF, FREE_PAGE_REPORTING_CONF);
        }
        update.config(SECURE_BOOT, "false");
        update.device("root", ROOT_BUS, "virtio-blk");
        incus.update(name, read, update);
    }

    /**
     * The root disk's bus. Incus defaults to virtio-scsi, which the base VM image's own kernel
     * does not have: it boots with no initrd and no modules, and mounts its root from virtio-blk
     * (#1255). The stock Fedora kernel builds virtio-blk in as well, so an image built from it
     * boots either way. virtio-blk is also the cheaper path per I/O: about 18 us less per request
     * at queue depth 1 than virtio-scsi, whose SCSI layer it skips in the guest and in QEMU (#810).
     */
    static final String ROOT_BUS = "io.bus";

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
     * first commit, and again from {@code InstancePrep.readyForUse} (the shell of {@code isx
     * shell}, {@code isx run} and the TUI) so one branched {@code --no-start}, or re-pointed
     * later, is reconciled on its next use. Both are needed: only the second can
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
     *
     * <p>It reports through the caller's output, never the terminal: the TUI's, where a line
     * printed from a background thread would be drawn over and lost, or a branch's, which the
     * TUI keeps in its warning log (#1646).
     */
    public static void reconcileAccountIdentities(IncusClient incus, String name,
                                                  Output out) {
        reconcileAccountIdentities(incus, name, null, SpawnConfig.load(), null, out);
    }

    /**
     * As above, from the instance as the caller already read it -- the shell path's, which would
     * otherwise read it again. Its account pins, stamps and network mode are all this looks at.
     */
    public static void reconcileAccountIdentities(IncusClient incus, String name, JqValue instance,
                                                  Output out) {
        reconcileAccountIdentities(incus, name, instance, SpawnConfig.load(), null, out);
    }

    static void reconcileAccountIdentities(IncusClient incus, String name, SpawnConfig config,
                                           Map<String, dev.incusspawn.tool.ToolSetup> knownSetups,
                                           Output out) {
        reconcileAccountIdentities(incus, name, null, config, knownSetups, out);
    }

    /**
     * @param instance    the instance as the caller read it, or {@code null} to read it
     * @param knownSetups the credential namespaces' tools, when the caller has them -- the TUI
     *                    passes its own so the tool definitions are not re-read from disk;
     *                    {@code null} decides against the built-in ones that can re-derive
     */
    static void reconcileAccountIdentities(IncusClient incus, String name, JqValue instance, SpawnConfig config,
                                           Map<String, dev.incusspawn.tool.ToolSetup> knownSetups,
                                           Output out) {
        // Whether this method has a step live: a failure before one started leaves the caller's alone
        var live = new boolean[1];
        try {
            var plan = instance != null
                    ? AccountSelection.identityReconcile(config, instance, knownSetups)
                    : AccountSelection.identityReconcile(config, incus, name, knownSetups);
            if (plan.isEmpty()) return;

            var container = new Container(incus, name, out);
            var setups = knownSetups != null ? knownSetups : AccountSelection.rederivableSetups();
            var stale = new LinkedHashMap<>(plan.stale());
            var updates = new LinkedHashMap<String, String>();
            // From a template an older isx built: its stamp may claim an identity the guest never
            // got, so ask the guest once. The marker is set only once everything here succeeded.
            // Asked before waiting for an address, which only re-deriving needs: an instance
            // that has its identity is just marked -- and stamped with the account's identity, so
            // a later change of account or token is still seen as one. Whose identity the guest
            // holds is not something the check can tell (design/accounts.md "An identity that was never baked is reconciled too").
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
                out.stepStart("Updating " + namespace + " identity for account '" + account + "'");
                live[0] = true;
                setup.rebakeForAccount(container, account);
                out.stepDone(null);
                live[0] = false;
                updates.put(Metadata.accountIdentityKey(namespace), setup.bakedAccountIdentity(config, account));
            });
            if (!updates.isEmpty()) incus.configSetAll(name, updates);
        } catch (Exception e) {
            // Best effort: a stale identity is a wrong commit author, not a broken instance,
            // and the next use tries again because the stamp is only updated on success.
            if (live[0]) out.abandonStep();
            out.warn("could not update credential identity for " + name
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
                                                     Output out) {
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
            reconcileAccountIdentities(incus, instance, config, setups, out);
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
     * <p>Progress and warnings go to {@code out}, never straight to stdout or stderr, so a caller
     * that owns the terminal (the TUI) can route them.
     *
     * @param instance the instance as read, for its status and the pending mark
     */
    public static void ensureReady(IncusClient incus, String name, JqValue instance,
                                   MachineType machineType, Output out) {
        var stopped = "Stopped".equalsIgnoreCase(instance.path("status").asText(""));
        // A running instance too: one branched before #1137 and restarted outside isx runs unrestricted
        if (!stopped) enforceNetworkMode(incus, name, instance, out);
        if (stopped) {
            // A header, as every multi-step operation has: the start's own steps go under it
            out.header("Starting " + name, null);
            startForUse(incus, name, instance, machineType, out);
        } else if (machineType == MachineType.VM) {
            // QEMU may reboot a VM in place, which changes nothing the host can see: only the
            // guest can tell, so the agent probe asks it on the way, for no request of its own
            var probe = probeAgent(incus, name, instance);
            if (probe == null) {
                VmAgentRecovery.restartForAgent(incus, name, out);
            } else if (InstanceSecret.missingIn(probe)) {
                giveSecretToThisBoot(incus, name, instance, MachineType.VM, out);
            }
        } else {
            // Every container reboot is a start: the instance already read says which boot it is.
            // The step isx mcp and a plain ssh take too (#1975)
            giveSecretIfMissing(incus, name, instance, out);
        }
        // Only a VM is ever marked
        if (instance.path("config").has(Metadata.NETWORK_PUSH_PENDING)) {
            InstanceNetwork.pushDeferredNetworkConfig(incus, name, instance, InstanceNetwork.bridgePrefixLen(incus),
                    out);
        }
    }

    /**
     * Start a stopped instance for use and wait until it answers exec: the start every path
     * that brings an existing instance up goes through -- {@code isx shell} and {@code isx run},
     * the TUI's shell, and their CA repairs. Prepares its host devices
     * ({@link #prepareForStart}), and gives it a new secret ({@link
     * #rotateInstanceSecret}), which the readiness probe itself puts in place, with the Claude
     * Code registration its {@code mcp-caller} grant calls for (#1182), read from the instance
     * the device repairs read: neither costs a request of its own.
     *
     * <p>Held under the instance's {@link #secretLock}, so another isx process starting it at the
     * same time waits, and then finds it running rather than giving it a second secret (#1065).
     *
     * @return the instance as read once it answered ({@link #recordSecretBoot}), for the caller
     *         to reuse rather than read again; null when there is none
     */
    public static JqValue startForUse(IncusClient incus, String name, MachineType machineType,
                                       Output out) {
        try (var ignored = secretLock(incus, name, machineType, "starting " + name, out)) {
            // Read under the lock, so a start another isx made while this one waited is seen
            var instance = incus.instanceMetadata(name);
            return isRunning(instance) ? alreadyRunning(incus, name, instance, machineType, out)
                    : startHeld(incus, name, instance, machineType, out);
        }
    }

    /** {@link #startForUse(IncusClient, String, MachineType, Output)}, with the stopped instance already read. */
    static JqValue startForUse(IncusClient incus, String name, JqValue instance, MachineType machineType,
                                Output out) {
        try (var lock = secretLock(incus, name, machineType, "starting " + name, out)) {
            // The read predates the wait: the holder may have started it, and a second start
            // would replace the secret it just delivered, then fail on a running instance
            if (lock.waited()) {
                instance = incus.instanceMetadata(name);
                if (isRunning(instance)) return alreadyRunning(incus, name, instance, machineType, out);
            }
            return startHeld(incus, name, instance, machineType, out);
        }
    }

    /**
     * An instance another isx started while this one waited, or that runs already: whoever started
     * it may have failed before its guest took the secret, so give it one if this boot still lacks
     * it, as {@link #ensureReady} would. Free for a container that holds it, one probe for a VM.
     *
     * @return {@code instance}, for the caller to reuse
     */
    private static JqValue alreadyRunning(IncusClient incus, String name, JqValue instance, MachineType machineType,
                                          Output out) {
        giveSecretIfStillNeeded(incus, name, instance, machineType, out);
        return instance;
    }

    /**
     * Give running {@code instance} a new secret if its boot has none, for a caller that runs
     * something in it without {@link #ensureReady}: {@code isx mcp}'s {@code exec}, {@code delegate}
     * and task tools, on a box Incus restarted itself, after a host reboot say (#1975). Free for a
     * container whose boot is stamped as holding it; a VM's guest is probed. Never starts or
     * recovers anything, and never throws: a box left without its secret is refused by the proxy.
     *
     * @return whether this boot holds its secret as far as isx can tell: false for one not running,
     *         a VM whose agent does not answer, or a delivery that failed (and warned)
     */
    public static boolean giveSecretIfMissing(IncusClient incus, String name, JqValue instance, Output out) {
        if (!isRunning(instance)) return false;
        var machineType = IncusClient.machineType(instance);
        if (machineType == MachineType.CONTAINER && secretRecordedForBoot(instance)) return true;
        // A VM's guest is asked before the lock, so one that holds its secret never waits on another isx
        if (machineType == MachineType.VM) {
            var probe = probeAgent(incus, name, instance);
            if (probe == null) return false;
            if (!InstanceSecret.missingIn(probe)) return true;
        }
        return giveSecretToThisBoot(incus, name, instance, machineType, out);
    }

    /**
     * Give running {@code instance} a new secret, once its boot was found without one (from its
     * boot stamp, or for a VM from the guest): its hash recorded first, then put in place by the
     * script every start runs. Best-effort, like every delivery: a box left without its secret is
     * refused, and the shell must still open.
     *
     * <p>Under the instance's {@link #secretLock}. After waiting for another isx, it asks again
     * whether this boot still needs a secret, since the holder may have given it one or stopped it;
     * otherwise what the caller read or probed still stands, so a VM is not asked twice.
     *
     * @return whether the boot holds its secret now
     */
    private static boolean giveSecretToThisBoot(IncusClient incus, String name, JqValue instance,
                                                MachineType machineType, Output out) {
        try (var lock = secretLock(incus, name, machineType, "giving " + name + " a new secret", out)) {
            if (lock.waited()) {
                return giveSecretIfStillNeeded(incus, name, incus.instanceMetadataOrThrow(name), machineType, out);
            }
            return giveSecretHeld(incus, name, instance, machineType == MachineType.VM ? null : bootOf(instance),
                    Metadata.isMcpCaller(instance), out);
        } catch (HostLock.HostLockException | IncusException e) {
            // The wait or the re-read after it; a nesting bug (already held by this thread) still throws
            out.warn("could not give " + name + " a new instance secret: " + e.getMessage());
            return false;
        }
    }

    /**
     * With the {@link #secretLock} held: give running {@code instance} a new secret if its boot has
     * none, for a container from its boot stamp, for a VM from the guest. A VM whose agent does not
     * answer is left to the next shell, which recovers it.
     *
     * @return whether the boot holds its secret now; false for one not running or a VM whose agent does not answer
     */
    private static boolean giveSecretIfStillNeeded(IncusClient incus, String name, JqValue instance,
                                                   MachineType machineType, Output out) {
        if (!isRunning(instance)) return false;
        if (machineType == MachineType.VM) {
            var probe = probeAgent(incus, name, instance);
            if (probe == null) return false;
            return !InstanceSecret.missingIn(probe)
                    || giveSecretHeld(incus, name, instance, null, Metadata.isMcpCaller(instance), out);
        }
        return secretRecordedForBoot(instance)
                || giveSecretHeld(incus, name, instance, bootOf(instance), Metadata.isMcpCaller(instance), out);
    }

    private static boolean isRunning(JqValue instance) {
        return instance != null && "Running".equalsIgnoreCase(instance.pathText("status", ""));
    }

    /**
     * Whether a start that failed left the instance running all the same: another isx started it
     * since the caller's read, which the lock cannot see when it was free by then (#1065), or
     * Incus started it but its answer was lost. The hash just recorded has replaced the one its
     * guest holds, and a VM's probe would never tell, so the start goes on as if it had made it:
     * the readiness probe delivers the secret, and the boot is recorded. One read, only when a
     * start fails; a read that fails too leaves the start's own error to report.
     */
    private static boolean runsAnyway(IncusClient incus, String name, RuntimeException startFailure) {
        try {
            return isRunning(incus.instanceMetadata(name));
        } catch (RuntimeException readFailure) {
            startFailure.addSuppressed(readFailure);
            return false;
        }
    }

    /**
     * Serializes giving an instance a new secret with delivering it, across every isx process of
     * this user (#1065): rotating records the hash and delivering hands the guest the secret,
     * and two of them interleaved can leave the guest holding one and the host recording the
     * other, with the boot stamped as holding it. Taken around a whole start, which the waiter
     * may have to sit out, so its bound is the instance's ready timeout, what Incus is given to
     * stop or restart it and then start it ({@link #STATE_CHANGES}), the usual stuck-holder
     * margin, and for a VM the graceful stop {@link VmAgentRecovery} tries before forcing one. Not reentrant: code under it calls the
     * {@code *Held} variants.
     *
     * <p>A lock file per instance name, never deleted, under its own directory, so it can never be
     * the TUI's {@code <instance>.lock}. Uncontended it costs no Incus request.
     */
    static HostLock secretLock(IncusClient incus, String name, MachineType machineType, String activity, Output out) {
        return HostLock.acquire(Environment.lockDir().resolve("instance-secret").resolve(name + ".lock"),
                activity, out::step, incus.readyTimeout(machineType).plus(HostLock.TIMEOUT).plus(STATE_CHANGES)
                        .plusSeconds(machineType == MachineType.VM ? VmAgentRecovery.GRACEFUL_STOP_SECONDS : 0));
    }

    /**
     * What a {@link #secretLock} holder may give Incus besides the ready wait: 30 s to stop or
     * restart ({@link IncusClient#stop(String)}, {@link IncusClient#restart}, a forced stop), then
     * 30 s to start ({@link IncusClient#start}).
     */
    private static final java.time.Duration STATE_CHANGES = java.time.Duration.ofSeconds(60);

    /** {@link #startForUse}, with its {@link #secretLock} held. */
    private static JqValue startHeld(IncusClient incus, String name, JqValue instance, MachineType machineType,
                                     Output out) {
        var placeholders = ProofToken.declaredInBackground();
        var secret = ProxyOnlyAcl.explaining(incus, name, instance, () -> {
            prepareForStart(incus, name, instance, out);
            // A VM branched or built before #1238 still has Secure Boot on: it goes in the write the
            // secret makes anyway
            var delivery = rotateInstanceSecret(incus, name, instance, machineType == MachineType.VM
                    && !"false".equals(instance.path("config").path(SECURE_BOOT).asText())
                    ? Map.of(SECURE_BOOT, "false") : Map.of(), placeholders.join());
            try {
                startInstance(incus, name, out);
            } catch (RuntimeException e) {
                if (!runsAnyway(incus, name, e)) throw e;
            }
            return delivery;
        });
        // The SSH keys ride in the same probe, and the Host entry is a local file (#1640): a
        // --no-start branch is first started here, with neither
        var ssh = RuntimeSetup.hasSshCapability(instance);
        var guestScript = InstanceSecret.GUEST_SCRIPT + (ssh ? RuntimeSetup.sshStartScript() : "");
        var started = withCurrentProofs(incus, name, secret, current -> {
            incus.waitForReady(name, machineType, guestScript,
                    InstanceSecret.guestEnv(current, Metadata.isMcpCaller(instance), placeholders.join()));
            return recordSecretBoot(incus, name, machineType, current.proofGeneration());
        });
        if (ssh) {
            try {
                RuntimeSetup.ensureSshHostEntry(name, instance);
            } catch (RuntimeException e) {
                // A convenience: the instance is up, and isx shell still reaches it
                out.warn("failed to configure SSH host entry: " + e.getMessage());
            }
        }
        return started;
    }

    /**
     * What {@link #startTemplate} gave the template: its pinned address, and what the readiness
     * exec delivers ({@link InstanceSecret#GUEST_SCRIPT}).
     */
    public record StartedTemplate(String address, Map<String, String> guestEnv) {}

    /**
     * Start a stopped template for {@code isx update-all} (#1457) the way isx starts an instance,
     * with {@link #startInstance}'s fallback where the host cannot enforce IP spoofing protection.
     * None of {@link #prepareForStart}'s repairs applies to a template: its build removes the
     * host-resource devices (the recorded list is for its branches), it never has an inbox or a
     * zmx device, and each branch sets its own filtering ({@link #configureBranch}).
     *
     * <p>It runs its prime commands as a build does, so it is started as a build is (#1743): pinned
     * to a filtered address ({@link InstanceNetwork#assignBuildAddress}), since unpinned code in it
     * could take a stopped branch's address, and granted temporary proofs for its own accounts
     * ({@link BuildAccounts#templateUpdate}), in the one write before the start. The caller
     * delivers the proofs as the guest answers, checks the address
     * ({@link InstanceNetwork#requireBuildAddress}) and, however the update ends, revokes the
     * proofs ({@link BuildAccounts#revoke}) and gives the address back once the template
     * is stopped ({@link InstanceNetwork#releaseBuildAddressOrWarn}).
     *
     * @param read the template as read; anything else is refused, since an instance starts
     *             through {@link #startForUse}
     */
    public static StartedTemplate startTemplate(IncusClient incus, String name, IncusClient.InstanceRead read, Output out) {
        requireStoppedTemplate(name, read);
        var grant = BuildAccounts.templateUpdate();
        var address = InstanceNetwork.assignBuildAddress(incus, name, read, grant.updates());
        // As a build does: the proxy knows the address and its proofs before anything inside asks
        ProxyService.signalAccountRefresh();
        startInstance(incus, name, out);
        return new StartedTemplate(address, grant.guestEnv());
    }

    /**
     * Refuse what {@link #startTemplate} must not start: anything but a template, and a template
     * that is not stopped, whose NIC the pinning would change under a running guest.
     */
    public static void requireStoppedTemplate(String name, IncusClient.InstanceRead read) {
        var instance = read.metadata();
        if (instance.isMissingNode()) throw new IncusException("Failed to read instance " + name);
        var type = instance.path("config").path(Metadata.TYPE).asText("");
        if (!Metadata.TYPE_BASE.equals(type) && !Metadata.TYPE_PROJECT.equals(type)) {
            throw new IllegalArgumentException("'" + name + "' is not a template, or no longer exists");
        }
        if (!"Stopped".equalsIgnoreCase(instance.pathText("status", ""))) {
            throw new IllegalStateException("Template " + name + " must be stopped before it is updated; it is "
                    + instance.pathText("status", "in an unknown state") + ". Stop it with 'incus stop " + name + "'.");
        }
    }

    /**
     * Restart a running instance and wait until it answers, with a new secret (#934): the
     * reboot empties the guest's {@code /run}, so without one the box would be left with none.
     *
     * @param mcpCaller whether the instance holds the {@code mcp-caller} grant, as the caller's
     *                  listing says ({@link InstanceSecret#guestEnv})
     */
    public static void restartForUse(IncusClient incus, String name, MachineType machineType,
                                     boolean mcpCaller, Output out) {
        restartForUse(incus, name, machineType, mcpCaller, incus.instanceMetadataOrThrow(name), out);
    }

    /** Reuses the TUI's listing, including its proof generation and verifiers. */
    public static void restartForUse(IncusClient incus, String name, MachineType machineType,
                                     boolean mcpCaller, JqValue instance, Output out) {
        // A restart is asked for: no re-check after a wait, just not in the middle of another's delivery
        try (var lock = secretLock(incus, name, machineType, "restarting " + name, out)) {
            // Still restarted, but from what the holder left rather than the listing from before it
            if (lock.waited()) {
                instance = incus.instanceMetadataOrThrow(name);
                mcpCaller = Metadata.isMcpCaller(instance);
            }
            restartHeld(incus, name, machineType, mcpCaller, instance, out);
        }
    }

    private static void restartHeld(IncusClient incus, String name, MachineType machineType,
                                    boolean mcpCaller, JqValue instance, Output out) {
        if (NetworkMode.isAirgapped(instance)) {
            // Stopped and started as any start for use, from a read after the stop rather than the
            // listing: a network device a profile gained is masked while the instance is down, which
            // Incus may refuse to do to a running one (#1518). It has no proxy, so the speed of
            // one restart buys little here.
            AirgapGuard.refusePassthrough(name, instance);
            incus.stop(name);
            startHeld(incus, name, incus.instanceMetadata(name), machineType, out);
            return;
        }
        // Before, not after: once the restart returns the guest is booting, when Incus API calls
        // contend with it. A restart that then fails leaves a guest whose secret no longer
        // matches, which is refused -- the safe way for it to go wrong.
        var placeholders = ProofToken.declaredInBackground();
        // To the caller's output: the TUI's log, since it owns the terminal here; a refusal throws, which it reports
        var secret = ProxyOnlyAcl.explaining(incus, name, instance, () -> {
            enforceNetworkMode(incus, name, instance, out);
            var delivery = rotateInstanceSecret(incus, name, instance, Map.of(), placeholders.join());
            incus.restart(name);
            return delivery;
        });
        // The keys ride in the probe as on a start (#1640), from the listing: no request of their own
        var guestScript = InstanceSecret.GUEST_SCRIPT
                + (RuntimeSetup.hasSshCapability(instance) ? RuntimeSetup.sshStartScript() : "");
        withCurrentProofs(incus, name, secret, current -> {
            incus.waitForReady(name, machineType, guestScript,
                    InstanceSecret.guestEnv(current, mcpCaller, placeholders.join()));
            return recordSecretBoot(incus, name, machineType, current.proofGeneration());
        });
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
    static InstanceSecret.Delivery rotateInstanceSecret(IncusClient incus, String name) {
        return rotateInstanceSecret(incus, name, incus.instanceMetadataOrThrow(name), Map.of(), ProofToken.declared());
    }

    /** {@link #rotateInstanceSecret}, with {@code alongside} recorded in the same write. */
    static InstanceSecret.Delivery rotateInstanceSecret(IncusClient incus, String name, JqValue instance, Map<String, String> alongside,
                                                java.util.List<ProofToken.Placeholder> placeholders) {
        try (var ignored = CredentialProof.lock(name)) {
            // Reset marks the old generation before changing Incus. Only stale listings,
            // migrations and changed declarations need a read under the lock.
            var config = instance.path("config");
            var generation = config.pathText(Metadata.INSTANCE_PROOF_ID, "");
            var namespaces = placeholders.stream().filter(p -> p.problem().isEmpty())
                    .map(ProofToken.Placeholder::namespace).collect(java.util.stream.Collectors.toSet());
            if (generation.isBlank() || CredentialProof.invalidated(generation)
                    || !namespaces.equals(dev.incusspawn.proxy.ProofVerifier.parse(
                            config.pathText(Metadata.INSTANCE_PROOF_SHA256, "")).keySet())) {
                instance = incus.instanceMetadataOrThrow(name);
                config = instance.path("config");
            }
            var seed = CredentialProof.retain(config.pathText(Metadata.INSTANCE_PROOF_ID, ""),
                    config.pathText(Metadata.INSTANCE_PROOF_SHA256, ""));
            var verifier = dev.incusspawn.proxy.ProofVerifier.stamp(seed.secret(), placeholders);
            var stamp = new HashMap<>(alongside);
            var secret = InstanceSecret.retainInto(stamp, config, seed, verifier);
            if (stamp.containsKey(Metadata.INSTANCE_SECRET_BOOT)) {
                var boot = PROOFS_SUFFIX.split(alongside.get(Metadata.INSTANCE_SECRET_BOOT), 2)[0];
                stamp.put(Metadata.INSTANCE_SECRET_BOOT, secretBootStamp(boot, secret.proofGeneration()));
            }
            incus.configSetAll(name, stamp);
            return secret;
        }
    }

    /**
     * Serialize guest delivery and its boot stamp with explicit reset. A reset may have
     * finished while the instance was booting, after its secret was prepared: replace only
     * that delivery's credential seed, preserving its independent MCP secret.
     */
    static <T> T withCurrentProofs(IncusClient incus, String name, InstanceSecret.Delivery planned,
                                   java.util.function.Function<InstanceSecret.Delivery, T> deliver) {
        try (var ignored = CredentialProof.lock(name)) {
            var current = planned;
            if (CredentialProof.invalidated(planned.proofGeneration())) {
                var config = incus.instanceMetadataOrThrow(name).path("config");
                var seed = CredentialProof.retain(config.pathText(Metadata.INSTANCE_PROOF_ID, ""),
                        config.pathText(Metadata.INSTANCE_PROOF_SHA256, ""));
                current = new InstanceSecret.Delivery(planned.secret(), seed.secret(), seed.generation());
            }
            return deliver.apply(current);
        }
    }

    /**
     * What {@link Metadata#INSTANCE_SECRET_BOOT} records for a delivery to boot {@code bootedAt}:
     * the boot, and that the delivery carried proof tokens (#1106). A stamp written by an isx
     * that predates them is the bare boot, so it no longer matches, and the next shell into a
     * container that isx started then gives it a new secret with its proofs -- once, from the
     * instance it already read. Version 4 also installs Git's GitHub proof helper (#1420).
     */
    static String secretBootStamp(String bootedAt) {
        return secretBootStamp(bootedAt, "");
    }

    static String secretBootStamp(String bootedAt, String generation) {
        return bootedAt + " proofs-v4:" + generation;
    }

    /** When Incus last started {@code instance} -- on every start, a reboot included -- or "". */
    private static String bootOf(JqValue instance) {
        return instance.pathText("last_used_at", "");
    }

    /**
     * Whether running {@code instance}, as already read, can be used as it is: a container whose
     * boot is stamped as holding the secret isx gave it, as {@link #ensureReady} judges. A VM's can
     * only be asked of the guest, so it never can (#1720: {@code isx ssh-proxy --start}'s fast path).
     * After a host reboot Incus restores a running instance with an empty {@code /run/isx}, and its
     * new boot has no stamp.
     */
    public static boolean readyForThisBoot(JqValue instance) {
        return IncusClient.machineType(instance) == MachineType.CONTAINER && secretRecordedForBoot(instance);
    }

    /** Whether container {@code instance}'s boot is stamped as holding the secret isx gave it, or unknown. */
    private static boolean secretRecordedForBoot(JqValue instance) {
        var config = instance.path("config");
        return secretRecordedForBoot(bootOf(instance), config.pathText(Metadata.INSTANCE_PROOF_ID, ""),
                config.pathText(Metadata.INSTANCE_SECRET_BOOT, ""));
    }

    /**
     * {@link #secretRecordedForBoot(JqValue)} from the values it reads: the boot ({@code last_used_at}),
     * the {@link Metadata#INSTANCE_PROOF_ID} and the {@link Metadata#INSTANCE_SECRET_BOOT} stamp,
     * for a caller holding them rather than the instance ({@code isx mcp}, #1975).
     */
    public static boolean secretRecordedForBoot(String bootedAt, String proofId, String bootStamp) {
        return bootedAt.isEmpty() || secretBootStamp(bootedAt, proofId).equals(bootStamp);
    }

    /**
     * Record which boot of container {@code name} its new secret went to, once the guest
     * answers, so that {@link #ensureReady} can tell a later reboot from it at no cost
     * ({@link Metadata#INSTANCE_SECRET_BOOT}). Its boot is only known after the start, so this
     * reads the instance, and returns that read for the caller's next step to reuse rather than
     * make again (the CA check, {@link CertificateAuthority#fixContainerCaIfNeeded}): the write
     * is the only request it adds. Best-effort: without it, the next shell gives the box another
     * secret, which is the safe way for it to go wrong. A VM is never stamped.
     *
     * @return the instance as read once the guest answered, or null for a VM or a failed read
     */
    static JqValue recordSecretBoot(IncusClient incus, String name, MachineType machineType) {
        return recordSecretBoot(incus, name, machineType, null);
    }

    static JqValue recordSecretBoot(IncusClient incus, String name, MachineType machineType, String generation) {
        if (machineType == MachineType.VM) return null;
        try {
            // A failed read must not reach the CA check as an empty instance, which it would
            // take for one with no CA to check: null makes it read again
            var instance = incus.instanceMetadataOrThrow(name);
            if (instance == null) return null;
            var bootedAt = bootOf(instance);
            if (!bootedAt.isEmpty()) incus.configSet(name, Metadata.INSTANCE_SECRET_BOOT, secretBootStamp(bootedAt,
                    generation == null ? instance.path("config").pathText(Metadata.INSTANCE_PROOF_ID, "") : generation));
            return instance;
        } catch (RuntimeException e) {
            // The next ensureReady sees an unrecorded boot
            return null;
        }
    }

    /**
     * {@link #giveSecretToThisBoot}, with the {@link #secretLock} held. Never throws.
     *
     * @return whether the guest kept the secret; on false, why was warned
     */
    static boolean giveSecretHeld(IncusClient incus, String name, JqValue instance, String bootedAt,
                                  boolean mcpCaller, Output out) {
        String failure;
        try {
            var placeholders = ProofToken.declaredInBackground();
            var secret = rotateInstanceSecret(incus, name, instance,
                    bootedAt == null ? Map.of() : Map.of(Metadata.INSTANCE_SECRET_BOOT, secretBootStamp(bootedAt)),
                    placeholders.join());
            // GUEST_SCRIPT never fails, so the same exec asks whether the secret is now there:
            // a write it skipped would otherwise give a VM a new secret on every shell, silently
            var delivery = withCurrentProofs(incus, name, secret, current -> {
                var result = incus.shellExec(name, InstanceSecret.guestEnv(current, mcpCaller, placeholders.join()), "sh", "-c",
                        InstanceSecret.GUEST_SCRIPT + "\n" + InstanceSecret.GUEST_CHECK);
                if (bootedAt != null && !secret.proofGeneration().equals(current.proofGeneration())) {
                    incus.configSet(name, Metadata.INSTANCE_SECRET_BOOT, secretBootStamp(bootedAt, current.proofGeneration()));
                }
                return result;
            });
            if (delivery.success() && !InstanceSecret.missingIn(delivery.stdout())) return true;
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
        out.warn("could not give " + name + " a new instance secret: " + failure);
        return false;
    }

    /**
     * Ask a running VM's agent whether it answers, and its guest whether it still holds its
     * secret ({@link InstanceSecret#GUEST_CHECK}): what the guest printed, or null if the agent
     * does not answer.
     */
    static String probeAgent(IncusClient incus, String name, JqValue instance) {
        var generation = instance.path("config").pathText(Metadata.INSTANCE_PROOF_ID, "");
        try {
            var result = incus.shellExec(name, "sh", "-c", InstanceSecret.GUEST_CHECK + "\n" + InstanceSecret.generationCheck(generation));
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
     *
     * <p>Package-private, as {@link #prepareForStart} is: code outside {@code lifecycle} starts an
     * existing instance through {@link #startForUse} (a template through {@link #startTemplate}),
     * so it cannot skip the re-arm before this.
     * The other public paths here that start one ({@code RuntimeSetup.prefetchAndStart},
     * {@code BuildAccounts.start}) start an instance just created, which has it set already.
     */
    static void startInstance(IncusClient incus, String name) {
        startInstance(incus, name, BuildOutput.bound());
    }

    /**
     * As above, reporting the loss of protection to {@code out} rather than stderr: the output its
     * caller was given, which is the TUI's warning log when the TUI starts while it owns the terminal.
     */
    static void startInstance(IncusClient incus, String name, Output out) {
        try {
            incus.start(name);
        } catch (RuntimeException e) {
            if (!InstanceNetwork.looksLikeIpFilteringFailure(e) || !InstanceNetwork.disableIpFiltering(incus, name, out)) throw e;
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
     * Remove host-side integration for a destroyed or renamed instance, reporting to {@code out}
     * a git remote it could not clean up. The progress (each remote removed) stays unsaid, as it
     * always was, under the caller's live step.
     */
    public static void removeHostIntegration(String name, Output out) {
        AutoRemoteService.removeRemotes(name, Output.diagnosticsTo(out::warn));
        SshKeyManager.cleanupInstance(name);
        ZmxSocketForward.cleanup(name, out);
        HostIntegration.destroyedAll(name, out);
    }

    /**
     * Repair an existing, stopped instance before starting it. Every path that starts one for use
     * (the {@code isx shell}/{@code run} prep, the TUI, {@link #ensureReady}, VM agent recovery)
     * goes through here, so a repair placed here reaches all of them. Package-private, so code
     * outside {@code lifecycle} cannot prepare and start by hand and has to call
     * {@link #startForUse}, which carries it (#907).
     *
     * <ul>
     *   <li>Host-side devices: a host-resource source that has disappeared and a missing zmx socket
     *       directory otherwise fail Incus start validation with {@code Missing source path}.</li>
     *   <li>IP spoofing protection ({@link InstanceNetwork#rearmIpFiltering}): turned back on where it is off.</li>
     *   <li>A proxy-only instance's host ACL ({@link ProxyOnlyAcl#enforce}): put in force where it is
     *       missing, or the start is refused.</li>
     *   <li>An airgapped instance's masks ({@link AirgapGuard#enforce}): a network device it gained
     *       is masked, and passthrough hardware refuses the start.</li>
     * </ul>
     */
    static void prepareForStart(IncusClient incus, String name) {
        prepareForStart(incus, name, BuildOutput.bound());
    }

    /**
     * As above, reporting each device it removes to {@code out}. The TUI starts instances while it
     * owns the terminal, where stderr would be drawn over and lost, so it passes its warning log.
     */
    static void prepareForStart(IncusClient incus, String name, Output out) {
        // All repairs read the same instance, so fetch it once: start has to
        // stay as cheap as it was before the repairs existed.
        prepareForStart(incus, name, incus.instanceMetadata(name), out);
    }

    /** {@link #prepareForStart(IncusClient, String, Output)} with the instance already read. */
    static void prepareForStart(IncusClient incus, String name, JqValue instance,
                                                  Output out) {
        HostResourceSetup.removeStaleDevices(incus, name, instance, out);
        removeStaleInbox(incus, name, instance, out);
        restoreInbox(incus, name, instance, out);
        ZmxSocketForward.ensureHostDirForStart(incus, name, instance, out);
        InstanceNetwork.rearmIpFiltering(incus, name, instance, out);
        enforceNetworkMode(incus, name, instance, out);
    }

    /**
     * Hold the instance to its network mode before it is used: the proxy-only ACL
     * ({@link ProxyOnlyAcl#enforce}) or the airgap masks ({@link AirgapGuard#enforce}). Every path
     * that starts or uses an instance calls this, never one of them alone.
     */
    private static void enforceNetworkMode(IncusClient incus, String name, JqValue instance, Output out) {
        ProxyOnlyAcl.enforce(incus, name, instance, out);
        AirgapGuard.enforce(incus, name, instance, out);
    }

    /**
     * Removes the {@code --inbox} device when its host directory is gone (#854), which would
     * otherwise fail Incus start validation with {@code Missing source path}. The inbox is not a
     * host resource, so {@link HostResourceSetup#removeStaleDevices} never sees it. The user asked
     * for this mount, so its source is kept ({@link Metadata#INBOX_SOURCE}) and dropping it is
     * announced; the first start that finds the directory back mounts it again. isx does that
     * step itself: the macOS host has no {@code incus} CLI to re-add a device with (#1139).
     */
    static void removeStaleInbox(IncusClient incus, String name, JqValue instance, Output out) {
        var source = IncusClient.deviceSource(instance, INBOX_DEVICE);
        if (source.isEmpty()) return;
        var hostPath = HostResourceSetup.hostPathOfDeviceSource(source);
        if (hostPath == null || Files.isDirectory(hostPath)) return;
        incus.updateFresh(name, new InstanceUpdate().removeDevice(INBOX_DEVICE).config(Metadata.INBOX_SOURCE, source));
        out.warn("inbox directory not found: " + hostPath + " (device removed, starting without ~/inbox).",
                "To mount it again, recreate it; the next start of " + name + " does.");
    }

    /**
     * Mount the inbox {@link #removeStaleInbox} removed, once its directory is back. Given the
     * same instance read, it skips one that still has the device, so also one removed by this start.
     * It says so on the same output: the remembered path may name a different directory by now, and
     * a mount into the guest is never silent.
     */
    static void restoreInbox(IncusClient incus, String name, JqValue instance, Output out) {
        var source = instance.path("config").path(Metadata.INBOX_SOURCE).asText("");
        if (source.isEmpty() || !IncusClient.deviceSource(instance, INBOX_DEVICE).isEmpty()) return;
        var hostPath = HostResourceSetup.hostPathOfDeviceSource(source);
        if (hostPath == null || !Files.isDirectory(hostPath)) return;
        incus.updateFresh(name, new InstanceUpdate().unset(Metadata.INBOX_SOURCE)
                .replaceDevice(INBOX_DEVICE, Map.of("type", "disk", "source", source, "path", INBOX_PATH, "readonly", "true")));
        // A warning, not a note: a note is not kept off a terminal, and a mount is never silent
        out.warn("Inbox mounted again: " + hostPath + " (read-only, ~/inbox).");
    }

    /**
     * Apply host resource devices and (for instances) add git remotes.
     */
    public static void integrateWithHost(IncusClient incus, String name, InstanceType instanceType,
                                         MachineType machineType) {
        var instance = incus.instanceMetadataOrThrow(name);
        var hrJson = instance.path("config").path(Metadata.HOST_RESOURCES).asText("");
        var hostResources = HostResourceSetup.deserialize(hrJson);
        if (!hostResources.isEmpty()) {
            BuildOutput.step("Applying host resources.");
            HostResourceSetup.applyForInstance(incus, name, hostResources, machineType);
        }

        if (instanceType == InstanceType.INSTANCE) {
            AutoRemoteService.addRemotes(name, instance, BuildOutput.bound());

            var buildSourceJson = instance.path("config").path(Metadata.BUILD_SOURCE).asText("");
            if (ZmxSocketForward.isZmxInstalled(buildSourceJson)) {
                ZmxSocketForward.configure(incus, name, BuildOutput.bound());
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
            BuildOutput.warn("Inbox path '" + inboxPath + "' is not a directory, skipping.");
            return;
        }
        BuildOutput.step("Mounting inbox: " + inboxPath.toAbsolutePath() + ".");
        incus.deviceAdd(name, INBOX_DEVICE, "disk",
                "source=" + HostResourceSetup.translateForVm(inboxPath.toAbsolutePath().toString()),
                "path=" + INBOX_PATH,
                "readonly=true");
    }

    /**
     * The uid isx runs as. On Linux it comes from {@code /proc/self/status}, without the
     * {@code id -u} process the start and shell paths would otherwise pay for (#1804).
     */
    public static String getUid() {
        if (!Platform.isMacOS()) {
            var uid = effectiveUid(Path.of("/proc/self/status"));
            if (uid != null) return uid;
        }
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

    /**
     * The effective uid, what {@code id -u} prints, from the {@code Uid:} line of
     * {@code /proc/self/status} (real, effective, saved, filesystem); null when
     * it cannot be read. Unlike the owner of {@code /proc/self}, it stays right
     * for a non-dumpable process.
     */
    static String effectiveUid(Path status) {
        // Latin-1: the Name: line above is the comm, any bytes a process gave itself
        try (var lines = Files.lines(status, StandardCharsets.ISO_8859_1)) {
            return lines.filter(l -> l.startsWith("Uid:"))
                    .map(l -> WHITESPACE.split(l.substring(4).strip()))
                    .filter(f -> f.length >= 2 && !f[1].isEmpty() && f[1].chars().allMatch(Character::isDigit))
                    .map(f -> f[1])
                    .findFirst().orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
