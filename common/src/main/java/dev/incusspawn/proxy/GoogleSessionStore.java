package dev.incusspawn.proxy;

import dev.incusspawn.config.SpawnConfig;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The host's copy of a Google sign-in (#2443): one file per {@code gemini} account of
 * {@code type: google}, under {@code ~/.config/incus-spawn/gemini/<account>/session.json},
 * readable by the user only. Antigravity CLI in an instance signs in through the proxy, which
 * keeps what Google issued here and hands the guest look-alikes ({@code GooglePlaceholders}).
 *
 * <p>Google's access token is opaque, so its expiry is kept beside it, as the epoch second
 * Google's {@code expires_in} named. Google does not rotate the refresh token on a refresh; a
 * refresh answer that names none keeps the one the session has.
 */
public final class GoogleSessionStore extends SessionStore<GoogleSessionStore.Tokens> {

    /**
     * The tokens of a session, as Google issued them. Real credentials: never logged.
     *
     * @param expiry  when the access token expires, in epoch seconds; 0 when unknown
     * @param email   the account's email from the id token, for the guest's header and
     *                {@code isx account list}; "" when the id token named none
     */
    public record Tokens(String accessToken, String refreshToken, long expiry, String email) {
        @Override
        public String toString() { return "Tokens[redacted]"; }

        /** These tokens with a refreshed access token, keeping the refresh token unless Google rotated it. */
        public Tokens refreshed(String accessToken, String refreshToken, long expiry, String email) {
            return new Tokens(accessToken, refreshToken == null || refreshToken.isEmpty() ? this.refreshToken : refreshToken,
                    expiry, email == null || email.isEmpty() ? this.email : email);
        }
    }

    /** The store under {@code ~/.config/incus-spawn/gemini}. */
    public GoogleSessionStore() {
        this(() -> SpawnConfig.configDir().resolve(GoogleAccounts.NAMESPACE));
    }

    public GoogleSessionStore(Supplier<Path> root) {
        super(root);
    }

    @Override
    protected JqObject encode(Tokens tokens) {
        return JqObject.builder(4)
                .put("access_token", JqString.of(tokens.accessToken()))
                .put("refresh_token", JqString.of(tokens.refreshToken()))
                .put("expiry", JqNumber.of(tokens.expiry()))
                .put("email", JqString.of(tokens.email()))
                .build();
    }

    @Override
    protected Tokens decode(JqValue tokens) {
        return new Tokens(tokens.pathText("access_token", ""), tokens.pathText("refresh_token", ""),
                tokens.path("expiry").asLong(0), tokens.pathText("email", ""));
    }
}
