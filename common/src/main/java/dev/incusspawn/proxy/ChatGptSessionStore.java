package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The host's copy of a ChatGPT session (#1332): one file per {@code openai} account, under
 * {@code ~/.config/incus-spawn/openai/<account>/session.json}, readable by the user only. The
 * mechanics -- one writer at a time, a refresh committed only to the session and generation it
 * started from -- are {@link SessionStore}'s; what a session holds is below.
 */
public final class ChatGptSessionStore extends SessionStore<ChatGptSessionStore.Tokens> {

    /** The tokens of a session, as OpenAI issued them. Real credentials: never logged. */
    public record Tokens(String idToken, String accessToken, String refreshToken, String accountId) {
        @Override
        public String toString() { return "Tokens[redacted]"; }
    }

    /** The store under {@code ~/.config/incus-spawn/openai}. */
    public ChatGptSessionStore() {
        this(() -> SpawnConfig.configDir().resolve(ChatGptAccounts.NAMESPACE));
    }

    ChatGptSessionStore(Supplier<Path> root) {
        super(root);
    }

    @Override
    protected JqObject encode(Tokens tokens) {
        return JqObject.builder(4)
                .put("id_token", JqString.of(tokens.idToken()))
                .put("access_token", JqString.of(tokens.accessToken()))
                .put("refresh_token", JqString.of(tokens.refreshToken()))
                .put("account_id", JqString.of(tokens.accountId()))
                .build();
    }

    @Override
    protected Tokens decode(JqValue tokens) {
        return new Tokens(tokens.pathText("id_token", ""), tokens.pathText("access_token", ""),
                tokens.pathText("refresh_token", ""), tokens.pathText("account_id", ""));
    }
}
