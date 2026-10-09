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
     * ({@link InstanceLifecycle#assignBuildAddress}) and the template's pins, in one write, then
     * {@code proxyRefresh} so the proxy reads them before anything inside can make a request.
     *
     * <p>A build that pins nothing -- neither its template nor what it was copied with -- is
     * served the defaults either way, so it keeps DHCP and skips the address's round trips. The
     * pins travel to every branch through the CoW copy. A successful build gives the address
     * back once it has stopped ({@link InstanceLifecycle#releaseBuildAddress}). A failed one
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
            address = InstanceLifecycle.assignBuildAddress(incus, buildName, instance,
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
     * The namespaces whose identity a child build has to re-derive in what it copied from its
     * parent before stamping its own: the parent baked another account's identity (#281), or
     * none at all ({@link Metadata#ACCOUNT_IDENTITY_NONE}). A parent with no stamp for a
     * namespace its chain installs a re-deriving tool for ({@code parentRederivable}) was built
     * by an isx that did not mark a missing identity, and is treated as the marker. Only tools
     * that can re-derive; a namespace that bakes nothing now ({@code wanted} lacks it) has
     * nothing to re-derive from.
     *
     * @param wanted            what this build's accounts bake, by namespace
     *                          ({@link AccountSelection#bakedIdentities})
     * @param inherited         the parent's stamps ({@link #inheritedIdentities})
     * @param parentRederivable {@link AccountSelection#rederivableNamespaces} of the parent
     * @param setups            tool setups keyed by namespace
     */
    public static java.util.Set<String> inheritedToRederive(Map<String, String> wanted, Map<String, String> inherited,
                                                            java.util.Set<String> parentRederivable,
                                                            Map<String, dev.incusspawn.tool.ToolSetup> setups) {
        var result = new java.util.LinkedHashSet<String>();
        wanted.forEach((namespace, identity) -> {
            var baked = inherited.get(namespace);
            if (baked == null && parentRederivable.contains(namespace)) baked = Metadata.ACCOUNT_IDENTITY_NONE;
            var setup = setups.get(namespace);
            if (baked == null || baked.isBlank() || baked.equals(identity)
                    || setup == null || !setup.canRebakeForAccount()) {
                return;
            }
            result.add(namespace);
        });
        return result;
    }

    /**
     * The {@code account-identity} stamps a finished template gets: what this build baked, and
     * for every other namespace what it inherited. {@link #startConfig} cleared the inherited
     * ones before the start, but a namespace the build derives nothing for -- its credential no
     * longer configured -- still has the parent's {@code .gitconfig} or Claude environment in
     * the rootfs, and the stamp is how the reconcile and the proxy know it.
     */
    public static Map<String, String> identityStamps(Map<String, String> baked, Map<String, String> inherited) {
        return identityStamps(baked, inherited, java.util.Set.of());
    }

    /**
     * As {@link #identityStamps(Map, Map)}, also marking what the image could have baked but did
     * not: each of {@code rederivable} -- a namespace whose tool is in the template's chain and
     * {@linkplain dev.incusspawn.tool.ToolSetup#canRebakeForAccount can re-derive} -- that
     * neither this build nor the parent stamped gets {@link Metadata#ACCOUNT_IDENTITY_NONE}, so
     * configuring an account later is seen as a change of identity and reconciled, rather than
     * leaving every branch without one. A parent's real identity is kept over the marker: its
     * {@code .gitconfig} still carries it.
     */
    public static Map<String, String> identityStamps(Map<String, String> baked, Map<String, String> inherited,
                                                     java.util.Collection<String> rederivable) {
        var updates = new LinkedHashMap<String, String>();
        rederivable.forEach(namespace -> {
            if (!baked.containsKey(namespace) && !inherited.containsKey(namespace)) {
                updates.put(Metadata.accountIdentityKey(namespace), Metadata.ACCOUNT_IDENTITY_NONE);
            }
        });
        inherited.forEach((namespace, identity) -> updates.put(Metadata.accountIdentityKey(namespace), identity));
        baked.forEach((namespace, identity) -> updates.put(Metadata.accountIdentityKey(namespace), identity));
        return updates;
    }
}
