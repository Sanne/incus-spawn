package dev.incusspawn.lifecycle;

import dev.incusspawn.util.Output;
import dev.incusspawn.config.AccountOrigin;
import dev.incusspawn.config.AccountSelection;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;
import dev.incusspawn.proxy.ProxyService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a template build is served its template's accounts rather than the defaults (#903), from
 * its first start to the stamps it leaves on the finished template.
 *
 * <p>The proxy picks credentials by source address, and a build container used to keep a DHCP
 * one, so it could not be told from host traffic: repos were cloned, {@code prime} ran and the
 * git identity was derived with credentials the template chose not to use.
 */
public final class BuildAccounts {

    /** The width a warning's details wrap to: init's frame, which a first run's build prints inside. */
    private static final int DETAIL_WIDTH = 56;

    private BuildAccounts() {}

    /**
     * What {@link #start} found and did.
     *
     * @param inheritedIdentities the {@code account-identity} stamps the container was copied
     *                            with, by namespace
     * @param address             the registered static address it was given
     */
    public record Started(Map<String, String> inheritedIdentities, String address, Map<String, String> guestEnv) {}

    /**
     * Register a stopped build before starting it, with its address, account pins and fresh
     * proof verifiers in one write. Configured defaults require the same proof as explicit pins.
     * The caller delivers {@link Started#guestEnv} in its readiness exec before any authenticated
     * step. Successful builds revoke proofs and return the address; retained failures revoke
     * proofs and receive fresh ones when started for inspection.
     *
     * @param selection the template's resolved {@code accounts:}
     * @param template  the definition's name, which the pins' origin names: a rebuild runs in a
     *                  temporary container
     */
    public static Started start(IncusClient incus, String buildName, Map<String, String> selection,
                                String template, Runnable proxyRefresh) {
        var read = incus.readInstance(buildName);
        var config = read.metadata().path("config");
        var placeholders = dev.incusspawn.proxy.ProofToken.declared();
        var updates = startConfig(config, selection, template);
        var secret = grantInto(updates, placeholders);
        // Defaults spend credentials too. Every build needs the same address-and-proof identity.
        var address = InstanceNetwork.assignBuildAddress(incus, buildName, read, updates);
        proxyRefresh.run();
        InstanceLifecycle.startInstance(incus, buildName);
        return new Started(inheritedIdentities(config), address,
                dev.incusspawn.proxy.InstanceSecret.guestEnv(secret, false, placeholders));
    }

    /** The stamps that let the proxy accept an instance's secret and proofs, which {@link #revoke} empties. */
    static final java.util.List<String> PROOF_KEYS = java.util.List.of(Metadata.INSTANCE_SECRET_SHA256,
            Metadata.INSTANCE_PROOF_SHA256, Metadata.INSTANCE_PROOF_ID, Metadata.INSTANCE_SECRET_BOOT);

    /** Revoke before publication or inspection. A later isx start delivers fresh proofs. */
    public static void revoke(IncusClient incus, String name) {
        incus.configSetAll(name, revoked());
    }

    private static Map<String, String> revoked() {
        var updates = new LinkedHashMap<String, String>();
        PROOF_KEYS.forEach(key -> updates.put(key, ""));
        return updates;
    }

    /**
     * Stamp fresh proofs and a per-start secret into {@code updates}, the write that starts a build
     * container, a project template or a template {@code isx update-all} runs, clearing any MCP
     * grant and boot stamp the instance was copied or left with. The proxy then serves the
     * instance its own accounts, by its pins or the configured defaults, as for any instance.
     *
     * @return what the readiness exec delivers ({@link InstanceSecret#guestEnv})
     */
    static InstanceSecret.Delivery grantInto(Map<String, String> updates, java.util.List<ProofToken.Placeholder> placeholders) {
        updates.put(Metadata.MCP_CALLER, null);
        updates.put(Metadata.INSTANCE_SECRET_BOOT, null);
        return InstanceSecret.stampInto(updates, placeholders);
    }

    /**
     * What {@link #templateUpdate} writes with a template's address, and delivers once its guest
     * answers.
     */
    public record TemplateGrant(Map<String, String> updates, Map<String, String> guestEnv) {}

    /**
     * Temporary proofs for a template {@code isx update-all} runs (#1743), as a build and
     * {@code isx project update} have: its prime commands, npm updates and git fetches are served
     * the template's own accounts, the pins its build stamped on it or the configured defaults.
     * Revoked with {@link #revoke} however the update ends. Host routes refuse it while it holds
     * them, as they refuse a build container: the registry never trusts a template as a caller
     * ({@link dev.incusspawn.proxy.InstanceRegistry}), so no stamp is needed that its branches,
     * which ask their ancestors, would also be refused by.
     */
    public static TemplateGrant templateUpdate() {
        var placeholders = ProofToken.declared();
        var updates = new LinkedHashMap<String, String>();
        var secret = grantInto(updates, placeholders);
        return new TemplateGrant(updates, InstanceSecret.guestEnv(secret, false, placeholders));
    }

    /**
     * Remove managed tool caches of the build's temporary proof before publishing its rootfs, and
     * the build's machine-id, which a container branch whose push failed, or a VM branch on an image
     * that commits its ID, would otherwise boot with ({@link MachineId#CLEAR_SCRIPT}).
     */
    public static void clearGuestProofs(dev.incusspawn.incus.Container container) {
        container.runAsUserQuiet("agentuser", """
                export ANTHROPIC_API_KEY=sk-ant-placeholder OPENAI_API_KEY=sk-placeholder
                for script in /etc/profile.d/isx-zz-claude-auth.sh /etc/profile.d/isx-zz-codex-auth.sh; do
                  if [ -r "$script" ]; then . "$script"; fi
                done
                """, "Failed to clear build credential caches");
        container.sh("rm -f /run/isx/instance-secret /run/isx/credential-secret /run/isx/proof-tokens /run/isx/proof-version /run/isx/proof-generation || exit 1; "
                        + MachineId.CLEAR_SCRIPT)
                .assertSuccess("Failed to clear build proofs");
    }

    /**
     * Temporary credentials for project create/update. Unlike a template rebuild, these
     * operations keep the parent's tools and baked identities, so keep their compatibility
     * checks and account pins too. They never acquire trusted host-route lineage.
     */
    public static Project project(IncusClient incus, String name) {
        var read = incus.readInstance(name);
        if (!"Stopped".equalsIgnoreCase(read.metadata().pathText("status", ""))) {
            throw new IllegalStateException("Project template " + name + " must be stopped before building or updating it");
        }
        return new Project(incus, name, read);
    }

    public static final class Project implements AutoCloseable {
        private final IncusClient incus;
        private final String name;
        private final IncusClient.InstanceRead read;
        private boolean grantAttempted;
        private boolean startAttempted;
        private String address;

        private Project(IncusClient incus, String name, IncusClient.InstanceRead read) {
            this.incus = incus;
            this.name = name;
            this.read = read;
        }

        public void start(MachineType machineType) {
            var placeholders = ProofToken.declared();
            var updates = new LinkedHashMap<String, String>();
            updates.put(Metadata.UNTRUSTED_LINEAGE, Metadata.TYPE_PROJECT);
            var secret = grantInto(updates, placeholders);
            grantAttempted = true;
            address = InstanceNetwork.assignBuildAddress(incus, name, read, updates);
            ProxyService.signalAccountRefresh();
            startAttempted = true;
            InstanceLifecycle.startInstance(incus, name);
            incus.waitForReady(name, machineType, InstanceSecret.GUEST_SCRIPT,
                    InstanceSecret.guestEnv(secret, false, placeholders));
            InstanceNetwork.requireBuildAddress(incus, name, address);
        }

        /** Try every cleanup step even when an earlier one fails; never publish on failure. */
        @Override public void close() {
            if (!grantAttempted) return;
            RuntimeException failure = cleanup(null, () -> revoke(incus, name));
            failure = cleanup(failure, ProxyService::signalAccountRefresh);
            boolean stopped = !startAttempted;
            if (startAttempted) {
                failure = cleanup(failure, () -> clearGuestProofs(new Container(incus, name)));
                try {
                    incus.stop(name);
                    stopped = true;
                } catch (RuntimeException stopFailed) {
                    failure = combine(failure, stopFailed);
                    try {
                        if (!"Stopped".equalsIgnoreCase(incus.instanceMetadataOrThrow(name).pathText("status", ""))) {
                            incus.forceStop(name);
                        }
                        stopped = true;
                    } catch (RuntimeException forceFailed) {
                        failure = combine(failure, forceFailed);
                    }
                }
            }
            // An assignment may have reached Incus before its response was lost.
            if (stopped) {
                failure = cleanup(failure, () -> InstanceNetwork.releaseBuildAddress(incus, name));
            }
            if (failure != null) throw failure;
        }

        private static RuntimeException cleanup(RuntimeException failure, Runnable step) {
            try { step.run(); }
            catch (RuntimeException e) { return combine(failure, e); }
            return failure;
        }

        private static RuntimeException combine(RuntimeException failure, RuntimeException next) {
            if (failure == null) return next;
            failure.addSuppressed(next);
            return failure;
        }
    }

    /**
     * The config a build container starts with, given the {@code config} it was created or
     * copied with: the template's account pins, and none of the {@code account-identity} stamps
     * a copy carries from its parent. Those describe what the parent's build baked, and the
     * proxy refuses an account that does not match one -- a pinned account's or the default's.
     * The build stamps what it bakes itself when it is done, and puts back the parent's for the
     * rest ({@link #identityStamps}).
     */
    static Map<String, String> startConfig(JqValue config, Map<String, String> selection, String template) {
        var updates = new LinkedHashMap<String, String>();
        if (config instanceof JqObject object) {
            object.forEach((key, value) -> {
                if (key.startsWith(Metadata.ACCOUNT_IDENTITY_PREFIX)) updates.put(key, null);
            });
        }
        if (!selection.isEmpty()) {
            var origins = new LinkedHashMap<String, AccountOrigin>();
            selection.keySet().forEach(ns -> origins.put(ns, AccountOrigin.template(template)));
            updates.putAll(AccountSelection.stampUpdates(selection, origins, AccountSelection.fromConfig(config)));
        }
        return updates;
    }

    /**
     * The {@code account-identity} stamps, by namespace, that a build container was copied with
     * from its parent. Read before {@link #startConfig} clears them: the build's re-derivation
     * of a parent's identity that no longer matches (#281) needs them after tool setup, and
     * {@link #identityStamps} puts back the ones the build does not stamp itself.
     */
    static Map<String, String> inheritedIdentities(JqValue config) {
        var result = new LinkedHashMap<String, String>();
        if (config instanceof JqObject object) {
            object.forEach((key, value) -> {
                if (key.startsWith(Metadata.ACCOUNT_IDENTITY_PREFIX) && !value.isNull()) {
                    result.put(key.substring(Metadata.ACCOUNT_IDENTITY_PREFIX.length()), value.asText());
                }
            });
        }
        return result;
    }

    /**
     * The namespaces whose identity a build has to re-derive, once its tools are set up, before
     * stamping: the parent baked another account's identity (#281), or the guest has none
     * ({@code lacking}) -- a parent stamped {@link Metadata#ACCOUNT_IDENTITY_NONE}, one an older
     * isx built without a token and so without a stamp, or one whose stamp claims an identity
     * its {@code .gitconfig} never got. Decided by the guest rather than the parent's stamp, so a
     * parent with no stamp but an identity -- built by an isx before stamps -- keeps it. Only
     * where an account bakes something now ({@code wanted}); without one there is nothing to
     * derive from.
     *
     * @param wanted    what this build's accounts bake, by namespace
     *                  ({@link AccountSelection#bakedIdentities})
     * @param inherited the parent's stamps ({@link #inheritedIdentities})
     * @param lacking   namespaces whose tool is in the image without its identity
     *                  ({@link dev.incusspawn.tool.ToolSetup#lacksBakedIdentity})
     * @param setups    tool setups keyed by namespace
     */
    public static java.util.Set<String> toRederive(Map<String, String> wanted, Map<String, String> inherited,
                                                   java.util.Set<String> lacking,
                                                   Map<String, dev.incusspawn.tool.ToolSetup> setups) {
        var result = new java.util.LinkedHashSet<String>();
        wanted.forEach((namespace, identity) -> {
            var setup = setups.get(namespace);
            if (lacking.contains(namespace)) {
                if (setup != null && setup.canRebakeForAccount()) result.add(namespace);
            } else if (AccountSelection.needsRederive(setup, inherited.get(namespace), identity)) {
                result.add(namespace);
            }
        });
        return result;
    }

    /**
     * Bring the identities a build bakes in line with its accounts, once its tools are set up,
     * and say what to stamp for them: decided by what the guest holds, not by what config.yaml
     * says it should ({@link #toRederive}). Where an identity is missing and no account can
     * supply it, warns ({@link dev.incusspawn.tool.ToolSetup#unbakedIdentityWarning}) and stamps
     * {@link Metadata#ACCOUNT_IDENTITY_NONE}. Every result carries
     * {@link Metadata#ACCOUNT_IDENTITY_VERIFIED}, without which every branch of the template
     * would pay the check an older template's branches pay on first use.
     *
     * @param selection   the template's pins, by namespace ({@code ImageDef.resolveAccounts})
     * @param rederivable the namespaces to ask the guest about
     *                    ({@link AccountSelection#rederivableNamespaces})
     * @param inherited   the parent's stamps ({@link #inheritedIdentities})
     * @return the stamps the finished template gets
     */
    public static Map<String, String> settleIdentities(dev.incusspawn.incus.Container container,
                                                       dev.incusspawn.config.SpawnConfig config,
                                                       Map<String, dev.incusspawn.tool.ToolSetup> setups,
                                                       Map<String, String> selection,
                                                       java.util.Collection<String> rederivable,
                                                       Map<String, String> inherited,
                                                       Output out) {
        // Every namespace gets a say, not just the ones this template selected: the build baked
        // *some* auth mode either way, and a later swap has to be checked against it. A null
        // account means "this template made no choice", which resolves to the namespace's
        // configured default -- the account the build actually used.
        var wanted = AccountSelection.bakedIdentities(config,
                AccountSelection.effectiveSelection(selection, setups), setups);
        var lacking = new java.util.LinkedHashSet<String>();
        for (var namespace : rederivable) {
            if (setups.get(namespace).lacksBakedIdentity(container)) lacking.add(namespace);
        }
        java.util.function.Function<String, String> accountFor = namespace ->
                dev.incusspawn.config.AccountResolver.effectiveAccount(config, namespace, selection.get(namespace));
        var rederived = toRederive(wanted, inherited, lacking, setups);
        rederived.forEach(namespace -> {
            var account = accountFor.apply(namespace);
            out.stepStart("Updating " + namespace + " identity for account '" + account + "'");
            try {
                setups.get(namespace).rebakeForAccount(container, account);
            } catch (RuntimeException e) {
                out.abandonStep();
                throw e;
            }
            out.stepDone(null);
        });
        lacking.removeAll(rederived);
        for (var namespace : lacking) {
            var warning = setups.get(namespace).unbakedIdentityWarning(config, accountFor.apply(namespace));
            if (warning == null) continue;
            // Its first line is the warning, the rest what to do about it, wrapped to init's frame (#2542)
            var lines = warning.lines().toList();
            var details = lines.subList(1, lines.size()).stream()
                    .flatMap(line -> dev.incusspawn.util.OutputFormat.wrap(line, DETAIL_WIDTH).stream())
                    .toArray(String[]::new);
            out.warn(lines.getFirst(), details);
        }
        var stamps = identityStamps(wanted, inherited, lacking);
        stamps.put(Metadata.ACCOUNT_IDENTITY_VERIFIED, "true");
        return stamps;
    }

    /**
     * The {@code account-identity} stamps a finished template gets: what this build baked, and
     * for every other namespace what it inherited. {@link #startConfig} cleared the inherited
     * ones before the start, but a namespace the build derives nothing for -- its credential no
     * longer configured -- still has the parent's {@code .gitconfig} or Claude environment in
     * the rootfs, and the stamp is how the reconcile and the proxy know it.
     *
     * <p>Also marks what the image has the tool for but no identity in: each of {@code unbaked} -- the guest lacks it
     * ({@link dev.incusspawn.tool.ToolSetup#lacksBakedIdentity}) and no account could supply it
     * -- gets {@link Metadata#ACCOUNT_IDENTITY_NONE}, over whatever the parent's stamp claimed, so
     * configuring an account later is seen as a change of identity and reconciled, rather than
     * leaving every branch without one.
     */
    public static Map<String, String> identityStamps(Map<String, String> baked, Map<String, String> inherited,
                                                     java.util.Collection<String> unbaked) {
        var updates = new LinkedHashMap<String, String>();
        inherited.forEach((namespace, identity) -> updates.put(Metadata.accountIdentityKey(namespace), identity));
        baked.forEach((namespace, identity) -> updates.put(Metadata.accountIdentityKey(namespace), identity));
        unbaked.forEach(namespace -> {
            if (!baked.containsKey(namespace)) {
                updates.put(Metadata.accountIdentityKey(namespace), Metadata.ACCOUNT_IDENTITY_NONE);
            }
        });
        return updates;
    }
}
