package dev.incusspawn.tool;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.NamespaceAccounts;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.proxy.ChatGptAccounts;
import dev.incusspawn.proxy.GoogleAccounts;
import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.List;
import java.util.Map;

/**
 * Whether the {@code openai} or {@code gemini} account an instance uses holds an API key, for Pi
 * and opencode, which spend nothing else. A {@code type: chatgpt} account is a session that
 * {@code ChatGptRoute} serves for Codex's operations alone, and a {@code type: google} one a sign-in
 * that {@code GoogleAuthRoute} serves for Antigravity CLI alone. The check of every tool's
 * {@code secret: true} entries counts either as configured, so without this an instance would be
 * branched and then fail at its first model call, with no key injected at {@code api.openai.com}
 * or {@code generativelanguage.googleapis.com}.
 */
final class ApiKeyAccounts {

    private ApiKeyAccounts() {}

    /** {@code ""}, or the OpenAI API key the selected {@code openai} account lacks, being a ChatGPT session. */
    static String openaiKeyProblem(SpawnConfig config, Map<String, String> selection) {
        var account = chatGptInUse(config, selection);
        return account.isEmpty() ? "" : "OpenAI API key (the account in use, '" + OutputFormat.oneLine(account)
                + "', is a ChatGPT sign-in, which only Codex can use)";
    }

    /** {@code ""}, or the Gemini API key the selected {@code gemini} account lacks, being a Google sign-in. */
    static String geminiKeyProblem(SpawnConfig config, Map<String, String> selection) {
        var account = googleInUse(config, selection);
        return account.isEmpty() ? "" : "Gemini API key (the account in use, '" + OutputFormat.oneLine(account)
                + "', is a Google sign-in, which only Antigravity CLI can use)";
    }

    /** What fixes {@link #openaiKeyProblem}, or {@code null} when it reports nothing. */
    static ToolSetup.Remedy openaiKeyRemedy(SpawnConfig config, Map<String, String> selection) {
        return chatGptInUse(config, selection).isEmpty() ? null
                : remedy(config, ChatGptAccounts.NAMESPACE, "Codex", selection);
    }

    /** What fixes {@link #geminiKeyProblem}, or {@code null} when it reports nothing. */
    static ToolSetup.Remedy geminiKeyRemedy(SpawnConfig config, Map<String, String> selection) {
        return googleInUse(config, selection).isEmpty() ? null
                : remedy(config, GoogleAccounts.NAMESPACE, "Antigravity CLI", selection);
    }

    private static String chatGptInUse(SpawnConfig config, Map<String, String> selection) {
        var tree = config.tree();
        var account = accountInUse(config, tree, ChatGptAccounts.NAMESPACE, selection);
        return account.isEmpty() || !isChatGpt(tree, account) ? "" : account;
    }

    private static String googleInUse(SpawnConfig config, Map<String, String> selection) {
        var tree = config.tree();
        var account = accountInUse(config, tree, GoogleAccounts.NAMESPACE, selection);
        return GoogleAccounts.isGoogleAccount(tree, account) ? account : "";
    }

    private static boolean isChatGpt(JqValue tree, String account) {
        return ChatGptAccounts.isChatGpt(tree.path(ChatGptAccounts.NAMESPACE).path(AccountResolver.ACCOUNTS_KEY).path(account));
    }

    /**
     * Whether {@code account} holds a key of its own. A flat namespace's key counts only for the
     * account it presents as, {@code default}, never for every account that lacks one.
     */
    private static boolean holdsKey(JqValue tree, String namespace, String account) {
        var accounts = namespace + "." + AccountResolver.ACCOUNTS_KEY;
        if (!AccountResolver.navigate(tree, accounts + "." + account + ".apiKey").isBlank()) return true;
        return !(tree.path(namespace).path(AccountResolver.ACCOUNTS_KEY) instanceof JqObject)
                && AccountResolver.FLAT_ACCOUNT_NAME.equals(account)
                && !AccountResolver.navigate(tree, namespace + ".apiKey").isBlank();
    }

    /** Whether {@code account} of {@code namespace} is that namespace's own sign-in. */
    private static boolean isSignIn(JqValue tree, String namespace, String account) {
        return namespace.equals(ChatGptAccounts.NAMESPACE) ? isChatGpt(tree, account)
                : GoogleAccounts.isGoogleAccount(tree, account);
    }

    /**
     * An API key account is needed in use, not only configured: one added beside the sign-in is
     * not used while the sign-in stays the default or stays pinned. So the command pins the key
     * account for the branch, naming it when there is one, and is the add step only when there is
     * none. The note says what else does it: the template's {@code accounts:} map when the sign-in
     * is pinned there or on the source, and otherwise the default, which also moves every
     * instance following it, {@code agent}'s included, off the sign-in and onto the key. Either
     * way the remedy is never asked for at a branch or a build (a step that ran would leave the
     * branch refused). Worded for every place the check is shown, a build's warning and init's
     * summary included.
     */
    private static ToolSetup.Remedy remedy(SpawnConfig config, String namespace, String agent,
                                           Map<String, String> selection) {
        var tree = config.tree();
        var keys = NamespaceAccounts.names(config, namespace).stream()
                .filter(name -> !isSignIn(tree, namespace, name))
                .filter(name -> holdsKey(tree, namespace, name))
                .toList();
        var name = keys.size() == 1 ? OutputFormat.oneLine(keys.getFirst()) : "<API key account>";
        var pin = "isx branch <name> --from <template> --account " + namespace + "=" + (keys.isEmpty() ? "<account>" : name);
        var pinned = selection.get(namespace) != null && !selection.get(namespace).isBlank();
        var notes = new java.util.ArrayList<String>();
        if (keys.isEmpty()) notes.add("Then pin it with '" + pin + "'.");
        notes.add(pinned
                ? "The sign-in is pinned, by --account, the template's accounts: map or the instance branched from,"
                        + " and naming the key account in the map does it for every branch."
                : "Making it the default with 'isx account default " + namespace + "=" + (keys.isEmpty() ? "<account>" : name)
                        + "' does it too, but also moves every instance that follows the default, "
                        + agent + "'s included, onto the key.");
        return new ToolSetup.Remedy(keys.isEmpty() ? "isx account add " + namespace : pin, List.copyOf(notes));
    }

    /**
     * The account {@code namespace} resolves to, or {@code ""} when there is none to judge here: none
     * configured, a pin to a missing or incomplete one (the generic check and the branch report
     * those), or {@code inherit}, which is the parent instance's to answer for.
     */
    private static String accountInUse(SpawnConfig config, JqValue tree, String namespace,
                                       Map<String, String> selection) {
        String account;
        try {
            // One serialized tree for the lookup and the resolution: config.tree() serializes the whole config
            account = AccountResolver.effectiveAccount(tree, namespace,
                    AccountResolver.shapeOf(config, namespace), selection.get(namespace));
        } catch (AccountResolver.UnknownAccountException e) {
            return "";
        }
        return AccountResolver.INHERIT.equals(account) ? "" : account;
    }
}
