package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.Set;

/**
 * Which config makes the proxy serve ChatGPT sessions (#1332), for everything that has to agree on
 * it: the proxy's route and the bridge DNS block the CLI rewrites.
 */
public final class ChatGptAccounts {

    /** The credential namespace whose accounts may be {@code type: chatgpt}. */
    public static final String NAMESPACE = "openai";
    /** Codex's backend. */
    public static final String CHATGPT = "chatgpt.com";
    /** OpenAI's token endpoint. */
    public static final String AUTH = "auth.openai.com";
    /** The hosts the proxy intercepts while any such account exists, for every instance. */
    public static final Set<String> DOMAINS = Set.of(CHATGPT, AUTH);

    private ChatGptAccounts() {}

    /** Whether {@code account}, an {@code openai} account's node, is a ChatGPT session. */
    public static boolean isChatGpt(JqValue account) {
        return "chatgpt".equals(account.pathText("type", ""));
    }

    /** Whether {@code tree} configures any {@code openai} account of {@code type: chatgpt}. */
    public static boolean enabledBy(JqValue tree) {
        if (!(tree.path(NAMESPACE).path("accounts") instanceof JqObject accounts)) return false;
        var any = new boolean[1];
        accounts.forEach((name, account) -> { if (isChatGpt(account)) any[0] = true; });
        return any[0];
    }
}
