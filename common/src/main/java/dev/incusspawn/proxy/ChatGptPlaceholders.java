package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

/**
 * The tokens a guest holds for a ChatGPT session (#1332): look-alikes of the real ones that carry
 * only what Codex reads from them, and the caller's proofs ({@link ChatGptProof}) where Codex would
 * send a credential.
 *
 * <p>Codex decodes the payload of the id token (email, plan, account) and of the access token
 * (its {@code exp}, to decide when to refresh) and checks neither signature (codex-rs
 * {@code token_data.rs}). So both are unsigned JWTs ({@code alg: none}) with the real tokens'
 * claims filtered down to {@link #AUTH_CLAIMS}: the guest learns the account's email, plan and
 * ids -- identifiers, not credentials -- and the access token's {@code exp}, so it refreshes when
 * the real token would. The real id token is never handed over: it is signed, and can be
 * exchanged for an API key.
 */
public final class ChatGptPlaceholders {

    private static final String AUTH = "https://api.openai.com/auth";
    private static final String PROFILE = "https://api.openai.com/profile";
    /** The claims under {@link #AUTH} that Codex reads; every other one is dropped. */
    private static final Set<String> AUTH_CLAIMS = Set.of("chatgpt_plan_type", "chatgpt_user_id", "user_id",
            "chatgpt_account_id", "chatgpt_account_user_id", "chatgpt_account_is_fedramp");
    private static final String HEADER = encode("{\"alg\":\"none\",\"typ\":\"JWT\"}");
    /** The id token's signature segment: says what it is, proves nothing. */
    static final String ID_TOKEN_SIGNATURE = ChatGptProof.MARKER + "placeholder";

    private ChatGptPlaceholders() {}

    /** The guest's id token for {@code realIdToken}: its identity claims, unsigned. */
    public static String idToken(String realIdToken) {
        var claims = claims(realIdToken);
        var out = JqObject.builder(5);
        copy(claims, out, "exp", "iat", "email");
        if (claims.path(PROFILE) instanceof JqObject profile) {
            var filtered = JqObject.builder(1);
            copy(profile, filtered, "email");
            out.put(PROFILE, filtered.build());
        }
        authClaims(claims, out);
        return HEADER + "." + encode(out.build().toJsonString()) + "." + ID_TOKEN_SIGNATURE;
    }

    /** The guest's access token for {@code realAccessToken}: its expiry and account, and the access proof. */
    public static String accessToken(String realAccessToken, String accessDigest) {
        var claims = claims(realAccessToken);
        var out = JqObject.builder(3);
        copy(claims, out, "exp", "iat");
        authClaims(claims, out);
        return HEADER + "." + encode(out.build().toJsonString()) + "." + ChatGptProof.MARKER + accessDigest;
    }

    /**
     * What a refresh answers a guest with: the shape of OpenAI's token response, with this
     * caller's proofs in place of the real tokens.
     */
    public static String refreshResponse(ChatGptSessionStore.Tokens real, ChatGptProof.Proofs proofs) {
        return JqObject.builder(3)
                .put("id_token", JqString.of(idToken(real.idToken())))
                .put("access_token", JqString.of(accessToken(real.accessToken(), proofs.access())))
                .put("refresh_token", JqString.of(proofs.refreshToken()))
                .build().toJsonString();
    }

    /** The {@code exp} of a JWT, in epoch seconds, or 0 when it has none or is no JWT. */
    public static long expiry(String jwt) {
        try {
            return claims(jwt).path("exp").asLong(0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static void authClaims(JqValue claims, JqObject.Builder out) {
        if (claims.path(AUTH) instanceof JqObject auth) {
            var filtered = JqObject.builder(AUTH_CLAIMS.size());
            auth.forEach((key, value) -> { if (AUTH_CLAIMS.contains(key)) filtered.put(key, value); });
            out.put(AUTH, filtered.build());
        }
    }

    private static void copy(JqValue from, JqObject.Builder to, String... keys) {
        for (var key : keys) {
            var value = from.path(key);
            if (!value.isNull()) to.put(key, value);
        }
    }

    private static JqValue claims(String jwt) {
        var parts = jwt == null ? new String[0] : jwt.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Not a JWT");
        return JqValues.parseStrict(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
