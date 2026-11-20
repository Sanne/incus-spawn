package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.Set;

/**
 * Which config makes the proxy keep Google sign-ins on the host (#2443), for everything that has
 * to agree on it: the proxy's route and the bridge DNS block the CLI rewrites.
 *
 * <p>A {@code gemini} account of {@code type: google} holds no key in {@code config.yaml}: its
 * credential is the Google session Antigravity CLI signs in to through the proxy, kept in
 * {@link GoogleSessionStore}. While any such account exists, the four hosts below are intercepted
 * for every instance; an instance whose {@code gemini} account is an API key only ever gets the relay.
 */
public final class GoogleAccounts {

    /** The credential namespace whose accounts may be {@code type: google}. */
    public static final String NAMESPACE = "gemini";
    /** The account type: a Google sign-in held on the host. */
    public static final String TYPE = "google";
    /** Google's token endpoint: the sign-in's code exchange, refreshes and revocations. */
    public static final String TOKEN = "oauth2.googleapis.com";
    /** Where Antigravity CLI asks who signed in ({@code GET /oauth2/v2/userinfo}). */
    public static final String USERINFO = "www.googleapis.com";
    /** The Code Assist backend Antigravity CLI's personal plans use. */
    public static final String BACKEND = "daily-cloudcode-pa.googleapis.com";
    /** The production Code Assist host, which Gemini CLI uses and agy may fall back to. */
    public static final String BACKEND_PROD = "cloudcode-pa.googleapis.com";
    /** The hosts the proxy intercepts while any such account exists, for every instance. */
    public static final Set<String> DOMAINS = Set.of(TOKEN, USERINFO, BACKEND, BACKEND_PROD);
    /** The hosts a proven request is relayed to with the session's token. */
    public static final Set<String> BACKENDS = Set.of(BACKEND, BACKEND_PROD);

    private GoogleAccounts() {}

    /** Whether {@code account}, a {@code gemini} account's node, is a Google sign-in. */
    public static boolean isGoogle(JqValue account) {
        return account != null && TYPE.equals(account.pathText("type", ""));
    }

    /** Whether {@code tree} configures any {@code gemini} account of {@code type: google}. */
    public static boolean enabledBy(JqValue tree) {
        return googleAccounts(tree).length > 0;
    }

    /** The names of every {@code gemini} account of {@code type: google} in {@code tree}, in file order. */
    public static String[] googleAccounts(JqValue tree) {
        if (tree == null || !(tree.path(NAMESPACE).path("accounts") instanceof JqObject accounts)) return new String[0];
        var names = new java.util.ArrayList<String>();
        accounts.forEach((name, account) -> { if (isGoogle(account)) names.add(name); });
        return names.toArray(String[]::new);
    }

    /** Whether the {@code gemini} account {@code name} in {@code tree} is a Google sign-in. */
    public static boolean isGoogleAccount(JqValue tree, String name) {
        return tree != null && name != null && !name.isBlank()
                && isGoogle(tree.path(NAMESPACE).path("accounts").path(name));
    }
}
