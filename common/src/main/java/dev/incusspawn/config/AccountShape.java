package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.List;

/**
 * What a credential namespace's accounts look like: which keys hold its credential, and when an
 * account cannot be used.
 *
 * <p>This is the only per-namespace knowledge {@link AccountResolver} needs, and every namespace
 * gets it from a declaration rather than from code. {@link #declaredBy} derives it from a tool's
 * proxy definition -- the {@code secret: true} entries that already tell the proxy what to inject
 * and the redactor what to hide -- so a tool defined purely in YAML has a shape with no Java.
 * Claude overrides it ({@code ClaudeConfig.ACCOUNT_SHAPE}), because its pre-accounts layout
 * includes a Vertex credential that is not a secret at all, and because whether one of its
 * accounts is usable depends on the account's type.
 *
 * <h3>Why the resolver needs it</h3>
 * A namespace written by an isx that predates accounts has no {@code accounts:} block, only the
 * credential itself ({@code github.token}). That credential is presented everywhere as an
 * account named {@code default}, so pinning, listing and serving it work exactly as they do
 * for one written in the accounts layout. Deciding whether such a namespace holds a credential at
 * all is what {@link #hasFlatCredential} answers: a leftover {@code github.email}, or an
 * {@code apiKey: ""} an older isx wrote for every namespace, is not one.
 */
public interface AccountShape {

    /** A namespace nothing declares: no flat credential is recognised, and every account is usable. */
    AccountShape UNDECLARED = declared(List.of(), List.of());

    /**
     * Whether a namespace with no {@code accounts:} block holds a credential in the flat,
     * pre-accounts layout -- and so presents as the account {@code default}.
     *
     * @param namespace the namespace's node, never null (an empty object when absent)
     */
    boolean hasFlatCredential(JqValue namespace);

    /**
     * The flat credential rewritten as the account it presents as, for moving it into
     * {@code accounts.default} when the namespace is next written.
     */
    JqValue flatAccount(JqValue namespace);

    /**
     * Whether values collected for one account -- keys relative to the namespace, as in
     * {@link #flatKeys()} -- include this shape's credential, i.e. whether writing them alone
     * would give a usable account rather than one holding only its non-secret settings.
     */
    default boolean hasCredential(java.util.Map<String, String> values) {
        var builder = JqObject.builder(values.size());
        values.forEach((key, value) -> set(builder, key, value));
        return hasFlatCredential(builder.build());
    }

    /**
     * The flat keys {@link #flatAccount} replaces, removed once the account is written.
     * Anything else at the namespace level stays there, where every account inherits it.
     */
    List<String> flatKeys();

    /**
     * Every key an account of this shape may hold, relative to the account: what
     * {@link UnreadConfigKeys} counts as read. The flat keys, unless an account holds more.
     */
    default List<String> accountKeys() { return flatKeys(); }

    /**
     * Why an account cannot be used, or {@code ""} when it can.
     *
     * <p>An unusable account is skipped when picking a namespace's default, and pinning one is
     * refused with this reason rather than with "not configured" -- an incomplete account is
     * easy to miss in {@code isx init}, so the two must not read alike (#742).
     *
     * @param account the account's own node
     * @param namespace the namespace's node, for keys the account inherits from the flat level
     */
    default String problem(JqValue account, JqValue namespace) { return ""; }

    /**
     * Whether {@code account}'s credential lives outside {@code config.yaml}: a sign-in the proxy
     * keeps on the host, such as a {@code gemini} account of {@code type: google} (#2443) or an
     * {@code openai} one of {@code type: chatgpt} (#1332). None of the shape's credential keys is
     * then expected to resolve for it, so a branch check or the proxy's startup report does not call the account incomplete for lacking a key.
     *
     * @param account the account's own node
     */
    default boolean credentialOutsideConfig(JqValue account) { return false; }

    /**
     * A shape whose credential is {@code credentialKeys}, and whose accounts each hold
     * {@code accountKeys} -- the credential plus anything else that belongs to one identity
     * rather than to the whole namespace (GitHub's commit email). Keys are relative to the
     * namespace; everything not named here is shared by every account.
     */
    static AccountShape declared(List<String> credentialKeys, List<String> accountKeys) {
        return declared(credentialKeys, accountKeys, java.util.Set.of());
    }

    /**
     * As above, where each of {@code listKeys} holds a list of items rather than one value
     * ({@link ToolDef.ConfigEntry#isList}): such a credential is present while its list holds an
     * item, and the account it moves into keeps the whole list.
     */
    static AccountShape declared(List<String> credentialKeys, List<String> accountKeys, java.util.Set<String> listKeys) {
        var lists = java.util.Set.copyOf(listKeys);
        var credential = List.copyOf(credentialKeys);
        var perAccount = new ArrayList<>(accountKeys);
        credential.forEach(key -> { if (!perAccount.contains(key)) perAccount.add(0, key); });
        var keys = List.copyOf(perAccount);
        return new AccountShape() {
            @Override
            public boolean hasFlatCredential(JqValue namespace) {
                return credential.stream().anyMatch(key -> lists.contains(key)
                        ? listAt(namespace, key) != null
                        : !AccountResolver.navigate(namespace, key).isBlank());
            }

            @Override
            public JqValue flatAccount(JqValue namespace) {
                var builder = JqObject.builder(keys.size());
                for (var key : keys) {
                    if (lists.contains(key)) {
                        var list = listAt(namespace, key);
                        if (list != null) builder.put(key, list);
                        continue;
                    }
                    var value = AccountResolver.navigate(namespace, key);
                    if (!value.isBlank()) set(builder, key, value);
                }
                return builder.build();
            }

            @Override
            public List<String> flatKeys() { return keys; }
        };
    }

    /**
     * The shape a proxy definition declares. Its {@code secret: true} entries are the
     * credential; every entry the user supplies is held per account, except {@code confirm}
     * entries, which record a decision about the tool (accepting a licence) rather than
     * anything about one identity, and so stay shared.
     *
     * <p>Entries a tool borrows from another namespace ({@code CopilotSetup} naming
     * {@code github.token}) are left out -- they describe the other tool's credential, and
     * that tool declares it for itself.
     */
    static AccountShape declaredBy(ToolDef.ProxyDef proxy) {
        if (proxy == null || proxy.getConfigNamespace().isBlank()) return UNDECLARED;
        var namespace = proxy.getConfigNamespace();
        var credential = new ArrayList<String>();
        var perAccount = new ArrayList<String>();
        var lists = new java.util.HashSet<String>();
        for (var entry : proxy.getConfiguration().values()) {
            if (entry.isConfirm() || !entry.getValue().isBlank()) continue;
            if (!namespace.equals(proxy.namespaceOf(entry))) continue;
            var path = proxy.fullConfigPath(entry);
            if (path.length() <= namespace.length() + 1) continue;
            var key = path.substring(namespace.length() + 1);
            // A list whose items hold a secret is a credential: one with an item is configured
            var secret = entry.isSecret() || entry.hasSecretField();
            if (entry.isList()) lists.add(key);
            if (secret && !credential.contains(key)) credential.add(key);
            if (!perAccount.contains(key)) perAccount.add(key);
        }
        return declared(credential, perAccount, lists);
    }

    /** The non-empty object at dotted {@code key} under {@code namespace}, or null. */
    private static JqValue listAt(JqValue namespace, String key) {
        var node = namespace;
        for (var segment : key.split("\\.")) {
            if (!(node instanceof JqObject object) || !object.keys().contains(segment)) return null;
            node = object.getField(segment);
        }
        return node instanceof JqObject object && object.size() > 0 ? node : null;
    }

    /** Set a dotted key on a builder, creating intermediate objects. Keys collide by replacement. */
    private static void set(JqObject.Builder builder, String dottedKey, String value) {
        var segments = dottedKey.split("\\.");
        if (segments.length == 1) {
            builder.put(segments[0], JqString.of(value));
            return;
        }
        var childBuilder = JqObject.builder(1);
        var existing = builder.build().getField(segments[0]);
        if (existing instanceof JqObject object) {
            object.forEach(childBuilder::put);
        }
        set(childBuilder, String.join(".", java.util.Arrays.copyOfRange(segments, 1, segments.length)), value);
        builder.put(segments[0], childBuilder.build());
    }
}
