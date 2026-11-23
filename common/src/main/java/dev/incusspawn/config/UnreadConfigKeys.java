package dev.incusspawn.config;

import dev.incusspawn.tool.ToolSetup;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keys in a credential namespace of {@code config.yaml} that no tool reads (#1733).
 *
 * <p>A credential typed by hand under a name isx does not know ({@code openai.token} for
 * {@code openai.apiKey}) is kept like any other key and never used, so without this nothing says
 * why the credential is still missing. The keys a namespace reads are declared, never listed: its
 * tools' {@code proxy.configuration} paths and {@link AccountShape} keys, at the namespace level
 * and in each account, plus {@code accounts:} and {@code default:}.
 *
 * <p>What counts as read is decided by trusted tools alone ({@link #trustedTools}): a cloned
 * repository's {@code .incus-spawn/tools/} must not decide what isx reports, as it cannot
 * decide what the proxy serves (#765).
 *
 * <p>Only namespaces a tool declares are checked; one no tool declares belongs to a tool isx
 * does not have, and is left alone.
 */
public final class UnreadConfigKeys {

    private UnreadConfigKeys() {}

    /**
     * The tools whose declarations decide what is read: what the proxy serves, so never a
     * project's {@code .incus-spawn/tools/} nor a tool whose feature is off.
     */
    public static Map<String, ToolSetup> trustedTools(SpawnConfig config) {
        return trustedTools(config, dev.incusspawn.tool.ToolDefLoader.withoutProjectTools(config.getSearchPaths()));
    }

    /** As {@link #trustedTools(SpawnConfig)}, from a loader over no project's tools the caller holds. */
    public static Map<String, ToolSetup> trustedTools(SpawnConfig config, dev.incusspawn.tool.ToolDefLoader loader) {
        return dev.incusspawn.proxy.ToolProxyResolver.proxyToolSetups(config, loader);
    }

    /**
     * Every checked namespace's unread keys, as full dotted paths in file order; namespaces with
     * none are left out.
     */
    public static Map<String, List<String>> all(JqValue tree, Map<String, ToolSetup> tools) {
        var result = new LinkedHashMap<String, List<String>>();
        if (!(tree instanceof JqObject root)) return result;
        for (var namespace : root.keys()) {
            var unread = in(tree, namespace, tools);
            if (!unread.isEmpty()) result.put(namespace, unread);
        }
        return result;
    }

    /** {@code namespace}'s unread keys, as full dotted paths ({@code openai.token}), in file order. */
    public static List<String> in(JqValue tree, String namespace, Map<String, ToolSetup> tools) {
        var read = readKeys(namespace, tools);
        if (read.isEmpty() || !(tree instanceof JqObject root)) return List.of();
        var node = root.getField(namespace);
        if (node == null || node.isNull()) return List.of();
        if (!(node instanceof JqObject ns)) {
            // A credential pasted straight under the namespace (`acme: <key>`, or a list of them)
            // is read by nothing; the namespace is a tool's declared name, never a value
            if (node instanceof JqArray array ? array.size() == 0 : node.asText().isBlank()) return List.of();
            return List.of(namespace);
        }
        var unread = new ArrayList<String>();
        ns.forEach((key, value) -> {
            if (key.equals(AccountResolver.DEFAULT_KEY)) return;
            if (key.equals(AccountResolver.ACCOUNTS_KEY)) {
                if (value instanceof JqObject accounts) {
                    accounts.forEach((account, fields) -> {
                        // An account with nothing in it holds no unread key: it is listed, and
                        // reported as unusable where accounts are (AccountShape.problem)
                        if (fields == null || fields.isNull()
                                || fields instanceof JqObject object && object.size() == 0
                                || !fields.isContainer() && fields.asText().isBlank()) return;
                        collect(namespace + "." + key + "." + account + ".", "", fields, read, unread);
                    });
                }
                return;
            }
            collect(namespace + ".", key, value, read, unread);
        });
        // A key name can itself be the token (`openai: { sk-proj-... }`), and these paths are
        // printed and carried into refusals: they name where a value is, never a value. Two
        // such keys redact to one path, which is named once
        return unread.stream().map(path -> SecretRedactor.scrubText(path, Map.of()).text()).distinct().toList();
    }

    /**
     * What to tell someone whose {@code namespace} holds unread keys, or {@code ""} when it holds
     * none: which keys are not read, and where the credential goes instead.
     */
    public static String note(JqValue tree, String namespace, Map<String, ToolSetup> tools) {
        return note(namespace, in(tree, namespace, tools), tools);
    }

    /** As {@link #note(JqValue, String, Map)}, for the {@code unread} keys the caller already has. */
    public static String note(String namespace, List<String> unread, Map<String, ToolSetup> tools) {
        if (unread.isEmpty()) return "";
        var expected = credentialKeys(namespace, tools).stream().map(k -> namespace + "." + k).toList();
        return "config.yaml has " + String.join(", ", unread) + ", which isx does not read"
                + (expected.isEmpty() ? "." : "; it reads " + String.join(", ", expected)
                        + " or the same under " + namespace + ".accounts.<name>.");
    }

    /** Leaves under {@code key} that are neither a read key nor below one. */
    private static void collect(String prefix, String key, JqValue value, Set<String> read, List<String> unread) {
        if (!key.isEmpty() && read.contains(key)) return;
        if (value instanceof JqObject object && object.size() > 0
                && (key.isEmpty() || read.stream().anyMatch(k -> k.startsWith(key + ".")))) {
            object.forEach((child, childValue) ->
                    collect(prefix, key.isEmpty() ? child : key + "." + child, childValue, read, unread));
            return;
        }
        // An account that is not a mapping of keys is named itself, without a trailing dot
        unread.add(key.isEmpty() ? prefix.substring(0, prefix.length() - 1) : prefix + key);
    }

    /** The keys relative to {@code namespace} that some tool reads; empty when no tool declares it. */
    private static Set<String> readKeys(String namespace, Map<String, ToolSetup> tools) {
        var read = new LinkedHashSet<String>();
        for (var tool : tools.values()) {
            var proxy = tool.proxy();
            if (proxy == null) continue;
            if (namespace.equals(proxy.getConfigNamespace())) {
                read.addAll(tool.accountShape().flatKeys());
                read.addAll(tool.accountShape().accountKeys());
            }
            for (var entry : proxy.getConfiguration().values()) {
                if (!namespace.equals(proxy.namespaceOf(entry))) continue;
                var path = proxy.fullConfigPath(entry);
                if (path.length() > namespace.length() + 1) read.add(path.substring(namespace.length() + 1));
            }
        }
        return read;
    }

    /**
     * The keys that hold {@code namespace}'s credential, as its owning tools declare them: their
     * {@code secret: true} entries, not the settings an account also holds (GitHub's email).
     */
    private static List<String> credentialKeys(String namespace, Map<String, ToolSetup> tools) {
        var keys = new LinkedHashSet<String>();
        for (var tool : tools.values()) {
            var proxy = tool.proxy();
            if (proxy == null || !namespace.equals(proxy.getConfigNamespace())) continue;
            for (var entry : proxy.getConfiguration().values()) {
                var secret = entry.isSecret() || entry.hasSecretField();
                if (!secret || !namespace.equals(proxy.namespaceOf(entry))) continue;
                var path = proxy.fullConfigPath(entry);
                if (path.length() > namespace.length() + 1) keys.add(path.substring(namespace.length() + 1));
            }
        }
        return List.copyOf(keys);
    }
}
