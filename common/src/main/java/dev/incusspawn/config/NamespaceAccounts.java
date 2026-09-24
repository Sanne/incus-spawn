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
 * <h3>Flat until a second account exists</h3>
 * A namespace with one credential stays in the flat layout ({@code github.token}), which is
 * what every pre-accounts config.yaml looks like and what a single-account user should keep
 * seeing. The flat credential is only {@linkplain #adoptFlat materialized} into
 * {@code accounts.default} when a second account is added -- the same rule
 * {@code ClaudeConfig.adoptLegacyAccount} follows, and for the same reason: clearing the flat
 * fields before copying them out would destroy a working credential.
 */
public final class NamespaceAccounts {

    /** Name given to a flat credential when it is first materialized as an account. */
    public static final String DEFAULT_ACCOUNT_NAME = "default";

    private NamespaceAccounts() {}

    /** Accounts configured under a namespace, in file order. */
    public static List<String> names(SpawnConfig config, String namespace) {
        return AccountResolver.accountNames(tree(config), namespace);
    }

    /** The account that applies when nothing narrower does, or {@code ""}. */
    public static String defaultName(SpawnConfig config, String namespace) {
        return AccountResolver.effectiveAccount(tree(config), namespace, null);
    }

    /** Whether the namespace still holds its credential in the flat, pre-accounts layout. */
    public static boolean hasFlatCredential(SpawnConfig config, String namespace, List<String> keys) {
        var tree = tree(config);
        return names(config, namespace).isEmpty()
                && keys.stream().anyMatch(key -> !AccountResolver.navigate(tree, namespace + "." + key).isBlank());
    }

    /**
     * Move a flat credential into {@code accounts.<name>} and point the default at it, so a
     * second account can be added beside it rather than replacing it.
     *
     * <p>No-op when the namespace already uses accounts, or has no flat credential to move.
     */
    public static void adoptFlat(SpawnConfig config, String namespace,
                                 List<String> keys, String name) {
        if (!hasFlatCredential(config, namespace, keys)) return;
        var tree = tree(config);
        for (var key : keys) {
            var value = AccountResolver.navigate(tree, namespace + "." + key);
            if (!value.isBlank()) config.setConfigByPath(accountPath(namespace, name, key), value);
        }
        for (var key : keys) {
            config.removeConfigPath(namespace + "." + key);
        }
        setDefault(config, namespace, name);
    }

    /** Write one field of an account. A blank value removes the field rather than emptying it. */
    public static void put(SpawnConfig config, String namespace,
                           String account, String key, String value) {
        if (value == null || value.isBlank()) {
            config.removeConfigPath(accountPath(namespace, account, key));
        } else {
            config.setConfigByPath(accountPath(namespace, account, key), value);
        }
    }

    /** Write one field of the flat, pre-accounts layout. */
    public static void putFlat(SpawnConfig config, String namespace, String key, String value) {
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
        var wasDefault = account.equals(
                AccountResolver.defaultName(tree(config), namespace));
        config.removeConfigPath(namespace + "." + AccountResolver.ACCOUNTS_KEY + "." + account);
        if (!wasDefault) return;
        var remaining = names(config, namespace);
        if (remaining.isEmpty()) {
            config.removeConfigPath(namespace + "." + AccountResolver.DEFAULT_KEY);
        } else {
            setDefault(config, namespace, remaining.get(0));
        }
    }

    /** Replace every account under a namespace with nothing, flat fields included. */
    public static void clear(SpawnConfig config, String namespace, List<String> keys) {
        for (var account : names(config, namespace)) {
            config.removeConfigPath(namespace + "." + AccountResolver.ACCOUNTS_KEY + "." + account);
        }
        config.removeConfigPath(namespace + "." + AccountResolver.DEFAULT_KEY);
        for (var key : keys) {
            config.removeConfigPath(namespace + "." + key);
        }
    }

    private static String accountPath(String namespace, String account, String key) {
        return namespace + "." + AccountResolver.ACCOUNTS_KEY + "." + account + "." + key;
    }

    private static com.fasterxml.jackson.databind.JsonNode tree(SpawnConfig config) {
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(config);
    }
}
