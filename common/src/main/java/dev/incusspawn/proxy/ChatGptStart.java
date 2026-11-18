package dev.incusspawn.proxy;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Metadata;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.Map;

/**
 * What a start writes for a ChatGPT session (#1332): the instance's proof verifiers in its
 * metadata, and the guest's Codex {@code auth.json} content, both derived from the per-start
 * instance secret the same start mints.
 *
 * <p>The proofs are keyed with that rotating secret -- the keying {@code InstanceRegistry}'s
 * binding check requires ({@code chatgpt-proof-secret-sha256} must be the instance's current
 * {@code instance-secret-sha256}), which is the decision this milestone owed the design page: a
 * copy gets a secret of its own and so never inherits its source's ChatGPT access, a reboot isx
 * did not do leaves the guest's proofs unverifiable until the next start renews them, and the
 * stable credential seed's own machinery ({@code CredentialProof}) stays untouched.
 *
 * <p>Fail-closed everywhere: an account that cannot be resolved, an {@code inherit} pin, an
 * account that is not a ChatGPT one, and a session that is gone all leave the instance with no
 * binding, clearing stamps it may still carry, and the guest with no ChatGPT {@code auth.json}
 * to write.
 */
public final class ChatGptStart {

    private ChatGptStart() {}

    /**
     * Stamps {@code updates} for the start that minted {@code secret}, and returns what that
     * start's delivery writes the guest's Codex as its session: {@code ""} when this instance is
     * served no ChatGPT session, in which case stale stamps are cleared as well.
     *
     * @param instanceConfig the instance's config as Incus lists it, for the account it is pinned
     *                       to and the stamps it carries
     * @param settings       the host's config, for the account selection and the account's type
     * @param store          the sessions on the host
     */
    public static String stampInto(Map<String, String> updates, JqValue instanceConfig, String secret,
                                   SpawnConfig settings, ChatGptSessionStore store) {
        var pinned = instanceConfig.pathText(Metadata.accountKey(ChatGptAccounts.NAMESPACE), "");
        var auth = stampFor(updates, pinned, secret, settings, store);
        if (auth.isEmpty()) {
            // Only what the instance carries: a start that serves no session pays for none of
            // this in its write
            for (var key : Metadata.CHATGPT_KEYS) {
                if (!instanceConfig.pathText(key, "").isEmpty()) updates.put(key, "");
            }
        }
        return auth;
    }

    /**
     * As above, for a branch, whose secret the copy request mints: its account pin is the
     * selection the branch was asked for, known ahead of the config {@code configureBranch}
     * writes, and a copy would carry its source's stamps alongside its files, so when it is
     * served no session they clear whatever the source had.
     *
     * @param pinned the account this branch is pinned to, or {@code null}/blank for the default
     */
    public static String stampBranchInto(Map<String, String> updates, String pinned, String secret,
                                         SpawnConfig settings, ChatGptSessionStore store) {
        var auth = stampFor(updates, pinned, secret, settings, store);
        if (auth.isEmpty()) {
            for (var key : Metadata.CHATGPT_KEYS) updates.put(key, "");
        }
        return auth;
    }

    /** The stamps and the guest auth for {@code pinned}, or {@code ""} with no stamps at all. */
    private static String stampFor(Map<String, String> updates, String pinned, String secret,
                                   SpawnConfig settings, ChatGptSessionStore store) {
        var session = sessionFor(settings, pinned, store);
        if (session == null) return "";
        var account = session.account();
        var proofs = ChatGptProof.derive(secret, account, session.sessionId());
        updates.put(Metadata.CHATGPT_SESSION, account + ":" + session.sessionId());
        updates.put(Metadata.CHATGPT_ACCESS_PROOF_SHA256, ChatGptProof.verifier(proofs.access()));
        updates.put(Metadata.CHATGPT_REFRESH_PROOF_SHA256, ChatGptProof.verifier(proofs.refresh()));
        updates.put(Metadata.CHATGPT_PROOF_SECRET_SHA256, InstanceSecret.sha256(secret));
        return ChatGptPlaceholders.codexAuth(session.tokens(), proofs);
    }

    /** The session the instance is served for its pinned account, or null when it is served none. */
    private static ChatGptSessionStore.Session sessionFor(SpawnConfig settings, String pinned,
                                                          ChatGptSessionStore store) {
        String account;
        try {
            account = AccountResolver.effectiveAccount(settings, ChatGptAccounts.NAMESPACE, pinned);
        } catch (RuntimeException unresolvedPin) {
            // A pin that no longer resolves must not fall back to another account's session
            return null;
        }
        if (account == null || account.isBlank() || AccountResolver.INHERIT.equals(account)) return null;
        if (!ChatGptAccounts.isChatGpt(settings.tree().path(ChatGptAccounts.NAMESPACE)
                .path("accounts").path(account))) return null;
        return store.read(account);
    }
}
