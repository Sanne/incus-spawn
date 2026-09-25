package dev.incusspawn.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.tool.ToolDef;

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
    boolean hasFlatCredential(JsonNode namespace);

    /**
     * The flat credential rewritten as the account it presents as, for moving it into
     * {@code accounts.default} when the namespace is next written.
     */
    ObjectNode flatAccount(JsonNode namespace);

    /**
     * Whether values collected for one account -- keys relative to the namespace, as in
     * {@link #flatKeys()} -- include this shape's credential, i.e. whether writing them alone
     * would give a usable account rather than one holding only its non-secret settings.
     */
    default boolean hasCredential(java.util.Map<String, String> values) {
        var account = JsonNodeFactory.instance.objectNode();
        values.forEach((key, value) -> set(account, key, value));
        return hasFlatCredential(account);
    }

    /**
     * The flat keys {@link #flatAccount} replaces, removed once the account is written.
     * Anything else at the namespace level stays there, where every account inherits it.
     */
    List<String> flatKeys();

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
    default String problem(JsonNode account, JsonNode namespace) { return ""; }

    /**
     * A shape whose credential is {@code credentialKeys}, and whose accounts each hold
     * {@code accountKeys} -- the credential plus anything else that belongs to one identity
     * rather than to the whole namespace (GitHub's commit email). Keys are relative to the
     * namespace; everything not named here is shared by every account.
     */
    static AccountShape declared(List<String> credentialKeys, List<String> accountKeys) {
        var credential = List.copyOf(credentialKeys);
        var perAccount = new ArrayList<>(accountKeys);
        credential.forEach(key -> { if (!perAccount.contains(key)) perAccount.add(0, key); });
        var keys = List.copyOf(perAccount);
        return new AccountShape() {
            @Override
            public boolean hasFlatCredential(JsonNode namespace) {
                return credential.stream().anyMatch(key -> !AccountResolver.navigate(namespace, key).isBlank());
            }

            @Override
            public ObjectNode flatAccount(JsonNode namespace) {
                var account = JsonNodeFactory.instance.objectNode();
                for (var key : keys) {
                    var value = AccountResolver.navigate(namespace, key);
                    if (!value.isBlank()) set(account, key, value);
                }
                return account;
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
        for (var entry : proxy.getConfiguration().values()) {
            if (entry.isConfirm() || !entry.getValue().isBlank()) continue;
            if (!namespace.equals(proxy.namespaceOf(entry))) continue;
            var path = proxy.fullConfigPath(entry);
            if (path.length() <= namespace.length() + 1) continue;
            var key = path.substring(namespace.length() + 1);
            if (entry.isSecret() && !credential.contains(key)) credential.add(key);
            if (!perAccount.contains(key)) perAccount.add(key);
        }
        return declared(credential, perAccount);
    }

    /** Set a dotted key under an object, creating intermediate objects. */
    private static void set(ObjectNode node, String dottedKey, String value) {
        var segments = dottedKey.split("\\.");
        var current = node;
        for (int i = 0; i < segments.length - 1; i++) {
            var child = current.get(segments[i]);
            current = child instanceof ObjectNode object ? object : current.putObject(segments[i]);
        }
        current.put(segments[segments.length - 1], value);
    }
}
