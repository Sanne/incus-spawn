package dev.incusspawn.config;

import java.util.List;

/**
 * Editing a credential namespace's accounts, generically.
 *
 * <p>The read side ({@link AccountResolver}) already works for any namespace without Java of
 * its own. This is the write side, and it is deliberately the same shape: paths are built from
 * the namespace name, values go through {@link SpawnConfig#setConfigByPath}, and nothing here
 * knows what a GitHub token or a Bob API key is. A credential that grows accounts therefore
 * needs no new mechanism, only the prompts that collect it.
 *
 * <h3>Always written as accounts</h3>
 * Every write produces the accounts layout -- a single credential is {@code accounts.default}
 * with {@code default: default} -- so there is one shape to reason about and one vocabulary
 * for choosing where a credential goes. A file still in the flat, pre-accounts layout is read
 * as-is (its credential already presents as the account {@code default}), and is only
 * {@linkplain #materialize moved} into that account on the first write that has something to
 * save. Doing it then rather than earlier matters: an abandoned {@code isx init} must leave the
 * file exactly as it found it.
 */
public final class NamespaceAccounts {

    /** Name a single credential is saved under, and the name a flat one presents as. */
    public static final String DEFAULT_ACCOUNT_NAME = AccountResolver.FLAT_ACCOUNT_NAME;

    private NamespaceAccounts() {}

    /** Accounts configured under a namespace, in file order, usable or not. */
    public static List<String> names(SpawnConfig config, String namespace) {
        return AccountResolver.accountNames(config, namespace);
    }

    /** The account that applies when nothing narrower does, or {@code ""}. */
    public static String defaultName(SpawnConfig config, String namespace) {
        return AccountResolver.effectiveAccount(config, namespace, null);
    }

    /**
     * Move a flat credential into {@code accounts.default} and point the default at it, so
     * whatever is written next lands beside it rather than replacing it.
     *
     * <p>Only the keys the namespace's {@link AccountShape} names as its credential move.
     * Anything else at the namespace level -- a licence consent, an org-wide setting -- stays
     * there, where every account inherits it.
     *
     * <p>No-op when the namespace already uses accounts, or holds no flat credential.
     */
    public static void materialize(SpawnConfig config, String namespace) {
        materialize(config, namespace, AccountResolver.shapeOf(config, namespace));
    }

    /** As {@link #materialize(SpawnConfig, String)}, with the shape the caller already has. */
    public static void materialize(SpawnConfig config, String namespace, AccountShape shape) {
        var tree = config.tree();
        if (AccountResolver.hasAccountsBlock(tree, namespace)) return;
        var ns = tree.get(namespace);
        if (ns == null || !ns.isObject() || !shape.hasFlatCredential(ns)) return;
        var account = shape.flatAccount(ns);
        leaves("", account, (key, value) ->
                config.setConfigByPath(accountPath(namespace, DEFAULT_ACCOUNT_NAME, key), value));
        for (var key : shape.flatKeys()) {
            config.removeConfigPath(namespace + "." + key);
        }
        setDefault(config, namespace, DEFAULT_ACCOUNT_NAME);
    }

    /**
     * Write one field of an account. A blank value removes the field rather than emptying it.
     * A flat credential is materialized first, so writing a second account never displaces it.
     */
    public static void put(SpawnConfig config, String namespace,
                           String account, String key, String value) {
        materialize(config, namespace);
        if (value == null || value.isBlank()) {
            config.removeConfigPath(accountPath(namespace, account, key));
        } else {
            config.setConfigByPath(accountPath(namespace, account, key), value);
        }
        // The first account written becomes the default. Adding one later must not re-point it --
        // including when there is no default: line and the first in file order is serving.
        if (AccountResolver.defaultName(config.tree(), namespace).isBlank()
                && names(config, namespace).equals(List.of(account))) {
            setDefault(config, namespace, account);
        }
    }

    /**
     * Write a value shared by every account of the namespace -- one that belongs to the
     * namespace rather than to any credential, like Bob's licence consent. Every account
     * inherits it through {@link AccountResolver#value}'s fallback.
     */
    public static void putShared(SpawnConfig config, String namespace, String key, String value) {
        if (value == null || value.isBlank()) {
            config.removeConfigPath(namespace + "." + key);
        } else {
            config.setConfigByPath(namespace + "." + key, value);
        }
    }

    public static void setDefault(SpawnConfig config, String namespace, String account) {
        config.setConfigByPath(namespace + "." + AccountResolver.DEFAULT_KEY, account);
    }

    /**
     * Remove an account, re-pointing the default when it named the one going away so the
     * namespace is never left pointing at something that is gone.
     */
    public static void remove(SpawnConfig config, String namespace, String account) {
        materialize(config, namespace);
        var wasDefault = account.equals(
                AccountResolver.defaultName(config.tree(), namespace));
        config.removeConfigPath(namespace + "." + AccountResolver.ACCOUNTS_KEY + "." + account);
        if (!wasDefault) return;
        var remaining = names(config, namespace);
        if (remaining.isEmpty()) {
            config.removeConfigPath(namespace + "." + AccountResolver.DEFAULT_KEY);
        } else {
            setDefault(config, namespace, remaining.get(0));
        }
    }

    /**
     * Remove every account under a namespace, and the flat credential of a pre-accounts file.
     * Values shared by every account stay: they describe the namespace, not a credential.
     */
    public static void clear(SpawnConfig config, String namespace) {
        clear(config, namespace, AccountResolver.shapeOf(config, namespace));
    }

    /** As {@link #clear(SpawnConfig, String)}, with the shape the caller already has. */
    public static void clear(SpawnConfig config, String namespace, AccountShape shape) {
        config.removeConfigPath(namespace + "." + AccountResolver.ACCOUNTS_KEY);
        config.removeConfigPath(namespace + "." + AccountResolver.DEFAULT_KEY);
        for (var key : shape.flatKeys()) {
            config.removeConfigPath(namespace + "." + key);
        }
    }

    /**
     * Replace every account of a namespace with a single one named {@code account}: what
     * "replace all" means, applied only once there is a credential to save.
     */
    public static void replaceAll(SpawnConfig config, String namespace, String account,
                                  java.util.Map<String, String> values) {
        clear(config, namespace);
        values.forEach((key, value) -> put(config, namespace, account, key, value));
        setDefault(config, namespace, account);
    }

    private static String accountPath(String namespace, String account, String key) {
        return namespace + "." + AccountResolver.ACCOUNTS_KEY + "." + account + "." + key;
    }

    private static void leaves(String prefix, com.fasterxml.jackson.databind.JsonNode node,
                               java.util.function.BiConsumer<String, String> sink) {
        node.fields().forEachRemaining(e -> {
            var key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue().isObject()) {
                leaves(key, e.getValue(), sink);
            } else if (e.getValue().isValueNode() && !e.getValue().asText().isBlank()) {
                sink.accept(key, e.getValue().asText());
            }
        });
    }
}
