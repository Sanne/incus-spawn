package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.config.AccountOrigin;
import dev.incusspawn.config.AccountSelection;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;

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

    private BuildAccounts() {}

    /**
     * What {@link #start} found and did.
     *
     * @param inheritedIdentities the {@code account-identity} stamps the container was copied
     *                            with, by namespace
     * @param address             the static address it was given, or null if it keeps DHCP
     */
    public record Started(Map<String, String> inheritedIdentities, String address) {}

    /**
     * Start the stopped build container, first making it known to the proxy as its template
     * when anything is pinned: a static address the proxy identifies it by
     * ({@link InstanceNetwork#assignBuildAddress}) and the template's pins, in one write, then
     * {@code proxyRefresh} so the proxy reads them before anything inside can make a request.
     *
     * <p>A build that pins nothing -- neither its template nor what it was copied with -- is
     * served the defaults either way, so it keeps DHCP and skips the address's round trips. The
     * pins travel to every branch through the CoW copy. A successful build gives the address
     * back once it has stopped ({@link InstanceNetwork#releaseBuildAddress}). A failed one
     * keeps it, and its pins, as {@code <template>-failed-build}: started for inspection, it is
     * served its template's accounts rather than the defaults. Deleting it frees the address.
     *
     * @param selection the template's resolved {@code accounts:}
     * @param template  the definition's name, which the pins' origin names: a rebuild runs in a
     *                  temporary container
     */
    public static Started start(IncusClient incus, String buildName, Map<String, String> selection,
                                String template, Runnable proxyRefresh) {
        var instance = incus.instanceMetadata(buildName);
        var config = instance.path("config");
        String address = null;
        if (!selection.isEmpty() || !AccountSelection.fromConfig(config).isEmpty()) {
            address = InstanceNetwork.assignBuildAddress(incus, buildName, instance,
                    startConfig(config, selection, template));
            // Before the start, so the first request from inside already sees the pins
            proxyRefresh.run();
        }
        InstanceLifecycle.startInstance(incus, buildName);
        return new Started(inheritedIdentities(config), address);
    }

    /**
     * The config a build container starts with, given the {@code config} it was created or
     * copied with: the template's account pins, and none of the {@code account-identity} stamps
     * a copy carries from its parent. Those describe what the parent's build baked, and the
     * proxy refuses an account that does not match one -- a pinned account's or the default's.
     * The build stamps what it bakes itself when it is done, and puts back the parent's for the
     * rest ({@link #identityStamps}).
     */
    static Map<String, String> startConfig(JsonNode config, Map<String, String> selection, String template) {
        var updates = new LinkedHashMap<String, String>();
        config.properties().forEach(e -> {
            if (e.getKey().startsWith(Metadata.ACCOUNT_IDENTITY_PREFIX)) updates.put(e.getKey(), null);
        });
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
    static Map<String, String> inheritedIdentities(JsonNode config) {
        var result = new LinkedHashMap<String, String>();
        config.properties().forEach(e -> {
            if (e.getKey().startsWith(Metadata.ACCOUNT_IDENTITY_PREFIX) && !e.getValue().isNull()) {
                result.put(e.getKey().substring(Metadata.ACCOUNT_IDENTITY_PREFIX.length()), e.getValue().asText(""));
            }
        });
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
                                                       java.util.function.Consumer<String> progress,
                                                       java.util.function.Consumer<String> warnings) {
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
            progress.accept("Updating " + namespace + " identity for account '" + account + "'...");
            setups.get(namespace).rebakeForAccount(container, account);
        });
        lacking.removeAll(rederived);
        for (var namespace : lacking) {
            var warning = setups.get(namespace).unbakedIdentityWarning(accountFor.apply(namespace));
            if (warning != null) warnings.accept(warning);
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
