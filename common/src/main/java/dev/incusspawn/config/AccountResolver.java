package dev.incusspawn.config;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves which named credential account a config namespace uses.
 *
 * <p>Accounts are per <em>config namespace</em> -- the {@code config-namespace} a tool
 * declares in its {@link dev.incusspawn.tool.ToolDef.ProxyDef} ({@code claude},
 * {@code github}, ...). A namespace holds its accounts under {@code <ns>.accounts.<name>}
 * and names one of them in {@code <ns>.default}:
 *
 * <pre>
 * github:
 *   accounts:
 *     personal: { token: "ghp_..." }
 *     acme:     { token: "ghp_..." }
 *   default: personal
 * </pre>
 *
 * <p>This operates on the serialized config tree rather than on typed classes, so a tool
 * defined purely in YAML gets named accounts with no Java change. {@link SpawnConfig.ClaudeConfig}
 * additionally has a typed API ({@code accountFor}) that stays authoritative for Claude,
 * because it also synthesizes an account from the pre-accounts flat layout.
 *
 * <p>Selection has three layers, each explicit, resolved highest-first:
 * <ol>
 *   <li>per-instance ({@code isx branch --account}, {@code isx account set})</li>
 *   <li>the template's {@code accounts:} map, merged down the inheritance chain</li>
 *   <li>the namespace's own {@code default}</li>
 * </ol>
 * The first two arrive here already collapsed into {@code selected}; this class resolves
 * the third and validates the result.
 */
public final class AccountResolver {

    /** Key naming a namespace's default account, and the key holding the account map. */
    public static final String DEFAULT_KEY = "default";
    public static final String ACCOUNTS_KEY = "accounts";

    private AccountResolver() {}

    /** Thrown when an instance names an account that is not configured. */
    public static class UnknownAccountException extends RuntimeException {
        private final String namespace;
        private final String accountName;

        public UnknownAccountException(String namespace, String accountName, String message) {
            super(message);
            this.namespace = namespace;
            this.accountName = accountName;
        }

        public String namespace() { return namespace; }
        public String accountName() { return accountName; }
    }

    /**
     * The account name a namespace resolves to, or {@code ""} when the namespace has no
     * accounts at all (a pre-accounts flat config, which callers serve from the flat fields).
     *
     * <p><strong>Fails closed.</strong> A {@code selected} name that is not configured raises
     * rather than quietly falling back to the default: an instance pinned to a client's
     * account must never silently start spending another's. That is the whole point of
     * <a href="https://github.com/Sanne/incus-spawn/issues/351">#351</a>.
     *
     * @param selected the name chosen by instance or template, or null/blank for none
     */
    public static String effectiveAccount(JsonNode configTree, String namespace, String selected) {
        var accounts = accountsNode(configTree, namespace);
        if (accounts == null || accounts.isEmpty()) {
            if (selected != null && !selected.isBlank()) {
                throw new UnknownAccountException(namespace, selected,
                        "No '" + namespace + "' accounts are configured, but this instance is"
                                + " pinned to account '" + selected + "'.");
            }
            return "";
        }
        if (selected != null && !selected.isBlank()) {
            if (!accounts.has(selected)) {
                throw new UnknownAccountException(namespace, selected,
                        "Account '" + selected + "' is not configured under '" + namespace
                                + "'. Configured: " + String.join(", ", accountNames(configTree, namespace)));
            }
            return selected;
        }
        var configured = defaultName(configTree, namespace);
        if (!configured.isBlank() && accounts.has(configured)) return configured;
        // No usable default: take the first in file order, matching how
        // ClaudeConfig.accountNameIn falls through to the first account that qualifies.
        var it = accounts.fieldNames();
        return it.hasNext() ? it.next() : "";
    }

    /** Names of every account configured under a namespace, in file order. */
    public static List<String> accountNames(JsonNode configTree, String namespace) {
        var accounts = accountsNode(configTree, namespace);
        var names = new ArrayList<String>();
        if (accounts != null) accounts.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * A value under a namespace, preferring the selected account's copy of the key and falling
     * back to the flat one.
     *
     * <p>The fallback is not just for pre-accounts configs: a namespace may hold some keys per
     * account (the token) and others globally (an org-wide setting), so an account that omits a
     * key inherits it rather than resolving to blank.
     *
     * @param accountName the resolved account, or blank for the flat layout
     */
    public static String value(JsonNode configTree, String namespace,
                               String accountName, String key) {
        if (namespace.isBlank() || key.isBlank()) return "";
        if (accountName != null && !accountName.isBlank()) {
            var fromAccount = navigate(configTree,
                    namespace + "." + ACCOUNTS_KEY + "." + accountName + "." + key);
            if (!fromAccount.isBlank()) return fromAccount;
        }
        return navigate(configTree, namespace + "." + key);
    }

    /**
     * Read a dotted path out of a serialized config tree, or {@code ""} when any segment is
     * missing or the leaf is not a value.
     *
     * <p>Lives here rather than in the proxy package because it is a plain config utility with
     * two owners now: tool-proxy credential resolution and every account lookup above.
     */
    public static String navigate(JsonNode tree, String path) {
        var node = tree;
        for (var segment : path.split("\\.")) {
            if (node == null || !node.isObject()) return "";
            node = node.get(segment);
        }
        return node != null && node.isValueNode() ? node.asText() : "";
    }

    /** The name in {@code <ns>.default}, or {@code ""}. */
    public static String defaultName(JsonNode configTree, String namespace) {
        var ns = configTree == null ? null : configTree.get(namespace);
        if (ns == null || !ns.isObject()) return "";
        var node = ns.get(DEFAULT_KEY);
        return node != null && node.isTextual() ? node.asText().strip() : "";
    }

    private static JsonNode accountsNode(JsonNode configTree, String namespace) {
        var ns = configTree == null ? null : configTree.get(namespace);
        if (ns == null || !ns.isObject()) return null;
        var accounts = ns.get(ACCOUNTS_KEY);
        return accounts != null && accounts.isObject() ? accounts : null;
    }
}
