package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * defined purely in YAML gets named accounts with no Java change. The little a namespace does
 * need to say about itself -- which keys hold its credential, and when an account is unusable --
 * arrives as an {@link AccountShape}, which its tool declares.
 *
 * <h3>A flat credential is the account {@code default}</h3>
 * A namespace written before accounts existed holds its credential directly
 * ({@code github.token}). It presents as a single account named {@code default}: listed,
 * pinnable ({@code --account github=default}) and served like any other. Nothing about that is
 * synthesized into the file -- {@link #value} already falls back from an account's key to the
 * flat one, so an account with no fields of its own reads the flat credential. It is the same
 * rule for every namespace, Claude included, so no caller has to know which layout a file uses.
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

    /** Name a flat, pre-accounts credential presents as, and the account a fresh one is saved to. */
    public static final String FLAT_ACCOUNT_NAME = "default";

    private AccountResolver() {}

    /** Thrown when an instance names an account that is not configured, or cannot be used. */
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
     * The account name a namespace resolves to, or {@code ""} when it has no usable account.
     *
     * <p><strong>Fails closed.</strong> A {@code selected} name that is not configured, or is
     * configured but unusable, raises rather than quietly falling back to the default: an
     * instance pinned to a client's account must never silently start spending another's. That
     * is the whole point of <a href="https://github.com/Sanne/incus-spawn/issues/351">#351</a>.
     *
     * <p>With nothing selected, the namespace's {@code default} applies when it names a usable
     * account, and otherwise the first usable account in file order.
     *
     * @param selected the name chosen by instance or template, or null/blank for none
     */
    public static String effectiveAccount(JqValue configTree, String namespace,
                                          AccountShape shape, String selected) {
        var ns = namespaceNode(configTree, namespace);
        var accounts = accounts(ns, shape);
        if (selected != null && !selected.isBlank()) {
            var account = accounts.get(selected);
            if (account == null) {
                throw new UnknownAccountException(namespace, selected, accounts.isEmpty()
                        ? "No '" + namespace + "' accounts are configured, but this instance is"
                                + " pinned to account '" + selected + "'."
                        : "Account '" + selected + "' is not configured under '" + namespace
                                + "'. Configured: " + String.join(", ", accounts.keySet()));
            }
            var problem = accountProblem(shape, account, ns);
            if (!problem.isEmpty()) {
                throw new UnknownAccountException(namespace, selected,
                        "Account '" + selected + "' under '" + namespace + "' is configured but"
                                + " incomplete: " + problem + ". Repair it with 'isx init' or in"
                                + " ~/.config/incus-spawn/config.yaml.");
            }
            return selected;
        }
        var configured = defaultName(configTree, namespace);
        var preferred = accounts.get(configured);
        if (preferred != null && accountProblem(shape, preferred, ns).isEmpty()) return configured;
        for (var entry : accounts.entrySet()) {
            if (accountProblem(shape, entry.getValue(), ns).isEmpty()) return entry.getKey();
        }
        return "";
    }

    // Config-taking forms. Every caller outside the proxy's resolution loop has a SpawnConfig
    // rather than a tree, and having each build its own view was the single most duplicated
    // thing in this feature. They look the namespace's shape up themselves; the tree-taking
    // forms stay for callers that resolve repeatedly against one snapshot and one set of tools.

    /** @see #effectiveAccount(JqValue, String, AccountShape, String) */
    public static String effectiveAccount(SpawnConfig config, String namespace, String selected) {
        return effectiveAccount(config.tree(), namespace, shapeOf(config, namespace), selected);
    }

    /** @see #accountNames(JqValue, String, AccountShape) */
    public static List<String> accountNames(SpawnConfig config, String namespace) {
        return accountNames(config.tree(), namespace, shapeOf(config, namespace));
    }

    /** @see #usableAccountNames(JqValue, String, AccountShape) */
    public static List<String> usableAccountNames(SpawnConfig config, String namespace) {
        return usableAccountNames(config.tree(), namespace, shapeOf(config, namespace));
    }

    /** @see #value(JqValue, String, String, String) */
    public static String value(SpawnConfig config, String namespace, String account, String key) {
        return value(config.tree(), namespace, account, key);
    }

    /**
     * The value of {@code key} for whichever account the namespace resolves to with no
     * selection -- "is this credential configured" for callers that only ever use the default.
     */
    public static String defaultValue(SpawnConfig config, String namespace, String key) {
        var tree = config.tree();
        var account = effectiveAccount(tree, namespace, shapeOf(config, namespace), null);
        return value(tree, namespace, account, key);
    }

    /**
     * Names of every account configured under a namespace, in file order, usable or not -- the
     * account a flat credential presents as included.
     */
    public static List<String> accountNames(JqValue configTree, String namespace, AccountShape shape) {
        return new ArrayList<>(accounts(namespaceNode(configTree, namespace), shape).keySet());
    }

    /** As {@link #accountNames(JqValue, String, AccountShape)}, without the unusable ones. */
    public static List<String> usableAccountNames(JqValue configTree, String namespace, AccountShape shape) {
        var ns = namespaceNode(configTree, namespace);
        var names = new ArrayList<String>();
        accounts(ns, shape).forEach((name, account) -> {
            if (accountProblem(shape, account, ns).isEmpty()) names.add(name);
        });
        return names;
    }

    /** Whether the namespace holds its accounts under {@code accounts:}, rather than flat or not at all. */
    public static boolean hasAccountsBlock(JqValue configTree, String namespace) {
        return accountsNode(namespaceNode(configTree, namespace)) != null;
    }

    /**
     * A value under a namespace, preferring the selected account's copy of the key and falling
     * back to the flat one.
     *
     * <p>The fallback is what makes a flat credential the account {@code default}, but it is not
     * only for pre-accounts configs: a namespace may hold some keys per account (the token) and
     * others globally (an org-wide setting, a licence consent), so an account that omits a key
     * inherits it rather than resolving to blank.
     *
     * @param accountName the resolved account, or blank for none
     */
    public static String value(JqValue configTree, String namespace,
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
     * The shape the tool owning {@code namespace} declares.
     *
     * <p>The built-in Java tools are consulted first, because answering from them costs nothing
     * and they own every namespace but a YAML tool's. Only a namespace none of them declares
     * scans for tool definitions -- the same set, feature gate and project-local rejection
     * included, that the proxy resolves against.
     */
    public static AccountShape shapeOf(SpawnConfig config, String namespace) {
        var builtIn = shapeAmong(dev.incusspawn.RuntimeConstants.CDI_TOOLS, namespace);
        if (builtIn != null) return builtIn;
        var declared = shapeAmong(
                dev.incusspawn.proxy.ToolProxyResolver.proxyToolSetups(config).values(), namespace);
        return declared != null ? declared : AccountShape.UNDECLARED;
    }

    /** The shape among tools the caller already loaded, falling back to the built-in tools. */
    public static AccountShape shapeOf(java.util.Map<String, dev.incusspawn.tool.ToolSetup> toolSetups,
                                       String namespace) {
        var found = shapeAmong(toolSetups.values(), namespace);
        if (found == null) found = shapeAmong(dev.incusspawn.RuntimeConstants.CDI_TOOLS, namespace);
        return found != null ? found : AccountShape.UNDECLARED;
    }

    private static AccountShape shapeAmong(java.util.Collection<dev.incusspawn.tool.ToolSetup> tools,
                                           String namespace) {
        for (var tool : tools) {
            var proxy = tool.proxy();
            if (proxy != null && namespace.equals(proxy.getConfigNamespace())) return tool.accountShape();
        }
        return null;
    }

    /**
     * Read a dotted path out of a serialized config tree, or {@code ""} when any segment is
     * missing or the leaf is not a value.
     *
     * <p>Lives here rather than in the proxy package because it is a plain config utility with
     * two owners now: tool-proxy credential resolution and every account lookup above.
     */
    public static String navigate(JqValue tree, String path) {
        var node = tree;
        var segments = path.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            // Jackson parity: node.get() returns null for a missing key (reads as ""),
            // while an explicit null is a NullNode (a value node reading as "null").
            if (!(node instanceof JqObject object) || !object.keys().contains(segments[i])) return "";
            node = object.getField(segments[i]);
        }
        if (node == null) return "";
        if (node.isNull()) return "null";
        return !node.isContainer() ? node.asText() : "";
    }

    /** The name in {@code <ns>.default}, or {@code ""}. */
    public static String defaultName(JqValue configTree, String namespace) {
        var ns = namespaceNode(configTree, namespace);
        var node = ns.getField(DEFAULT_KEY);
        return node.isString() ? node.asText().strip() : "";
    }

    /**
     * Every account under a namespace, in file order: its {@code accounts:} block, or the
     * account {@code default} when the namespace holds a flat credential instead. That account
     * has no fields of its own -- {@link #value} reads them from the flat level.
     */
    /**
     * An account, and whether it is the marker for a flat credential rather than a
     * configured one. The marker must be explicit: empty {@link JqObject}s are
     * canonicalized to one instance, so identity cannot tell a genuinely empty
     * account (unusable, must be reported) from the flat one (usable, never asked).
     */
    private record Account(JqValue node, boolean flat) {}

    private static Map<String, Account> accounts(JqValue namespace, AccountShape shape) {
        var result = new LinkedHashMap<String, Account>();
        var block = accountsNode(namespace);
        if (block != null) {
            block.asMap().forEach((name, node) -> result.put(name, new Account(node, false)));
        } else if (shape.hasFlatCredential(namespace)) {
            result.put(FLAT_ACCOUNT_NAME, new Account(FLAT_ACCOUNT, true));
        }
        return result;
    }

    /**
     * The shape's verdict on an account. The flat credential is not asked: the shape already
     * judged it when deciding it was one, and Claude relies on a flat Vertex setup with no region
     * still presenting as an account so the proxy can report exactly that misconfiguration.
     */
    private static String accountProblem(AccountShape shape, Account account, JqValue namespace) {
        if (account.flat()) return "";
        var problem = shape.problem(account.node() == null ? io.hyperfoil.tools.jjq.value.JqNull.NULL
                : account.node(), namespace);
        return problem == null ? "" : problem;
    }

    /** Identity marker for the account a flat credential presents as. */
    private static final JqValue FLAT_ACCOUNT = JqObject.builder(0).build();

    private static JqValue namespaceNode(JqValue configTree, String namespace) {
        var ns = configTree == null ? null : configTree.getField(namespace);
        return ns != null && ns.isObject() ? ns : JqObject.builder(0).build();
    }

    private static JqValue accountsNode(JqValue namespace) {
        if (namespace instanceof JqObject object) {
            var accounts = object.getField(ACCOUNTS_KEY);
            if (accounts instanceof JqObject acc && acc.size() > 0) return acc;
        }
        return null;
    }
}
