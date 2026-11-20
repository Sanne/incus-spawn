package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqBoolean;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * The tokens a guest holds for a Google sign-in (#2443): look-alikes of the real ones that carry
 * only what Antigravity CLI reads from them, and a digest the proxy can verify where agy would send
 * a credential.
 *
 * <p>The access placeholder is {@code ya29.isx_<digest>} and the refresh placeholder
 * {@code 1//isx_<digest>}: the prefixes Google's tokens have, then isx's marker and a digest. The
 * digest is the instance's {@code gemini} proof ({@link ProofToken#derive}), the same one its
 * {@code GEMINI_API_KEY} carries, so a start, a copy or a reset gives the guest new ones by the
 * mechanism every proof uses; or a digest the proxy minted for the run that signed in, until the
 * next login replaces it. The id token is an unsigned JWT ({@code alg: none}) holding the email
 * and subject of the real one: agy reads the email claim and checks no signature, as a probe of
 * agy 1.5.12 against a stub showed.
 */
public final class GooglePlaceholders {

    /** Marks a value as an isx proof, after the prefix the client expects. */
    public static final String MARKER = ProofToken.MARKER;
    /** Google's access tokens start so. */
    public static final String ACCESS_PREFIX = "ya29.";
    /** Google's refresh tokens start so. */
    public static final String REFRESH_PREFIX = "1//";
    /** How many hex characters a digest has: what the login script's edit matches ({@code AntigravitySetup.LOGIN_SCRIPT}). */
    public static final int DIGEST_HEX_CHARS = ProofToken.DIGEST_HEX_CHARS;
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{" + DIGEST_HEX_CHARS + "}");
    private static final String HEADER = encode("{\"alg\":\"none\",\"typ\":\"JWT\"}");

    private GooglePlaceholders() {}

    /** The guest's access token for {@code digest}. */
    public static String accessToken(String digest) {
        return ACCESS_PREFIX + MARKER + requireDigest(digest);
    }

    /** The guest's refresh token for {@code digest}. */
    public static String refreshToken(String digest) {
        return REFRESH_PREFIX + MARKER + requireDigest(digest);
    }

    /** Whether {@code token} claims to be an isx placeholder of either kind. */
    public static boolean claims(String token) {
        return token != null && (token.startsWith(ACCESS_PREFIX + MARKER) || token.startsWith(REFRESH_PREFIX + MARKER));
    }

    /** The digest an access or refresh placeholder carries, or null when {@code token} is not one. */
    public static String digestOf(String token) {
        if (!claims(token)) return null;
        var digest = token.substring(token.indexOf(MARKER) + MARKER.length());
        return DIGEST.matcher(digest).matches() ? digest : null;
    }

    /** Whether {@code digest} has the form of one. */
    public static boolean isDigest(String digest) {
        return digest != null && DIGEST.matcher(digest).matches();
    }

    /**
     * The guest's id token: the real one's email ({@code email}, {@code email_verified}) and the
     * placeholder's own times, unsigned. Nothing else of the real token travels: its subject,
     * audience and the rest name the account to Google, which the guest has no call for.
     */
    public static String idToken(String email, long issuedAt, long expiresAt) {
        var claims = JqObject.builder(4)
                .put("iat", JqNumber.of(issuedAt))
                .put("exp", JqNumber.of(expiresAt));
        if (!email.isEmpty()) claims.put("email", JqString.of(email)).put("email_verified", JqBoolean.TRUE);
        return HEADER + "." + encode(claims.build().toJsonString()) + ".";
    }

    /**
     * What a token request is answered with: the shape of Google's token response, with
     * {@code digest}'s placeholders in place of the real tokens and the real token's time left.
     *
     * @param scope the scope Google granted, relayed as it came; "" leaves it out
     */
    public static String tokenResponse(GoogleSessionStore.Tokens real, String digest, String scope) {
        var now = System.currentTimeMillis() / 1000;
        var expiresIn = Math.max(0, real.expiry() - now);
        var out = JqObject.builder(6)
                .put("access_token", JqString.of(accessToken(digest)))
                .put("expires_in", JqNumber.of(expiresIn))
                .put("refresh_token", JqString.of(refreshToken(digest)))
                .put("token_type", JqString.of("Bearer"))
                .put("id_token", JqString.of(idToken(real.email(), now, Math.max(now, real.expiry()))));
        if (!scope.isEmpty()) out.put("scope", JqString.of(scope));
        return out.build().toJsonString();
    }

    /** The {@code email} claim of a JWT, or "" when it has none or is no JWT. */
    public static String emailOf(String jwt) {
        return claim(jwt, "email");
    }

    private static String claim(String jwt, String name) {
        try {
            var value = payload(jwt).path(name);
            return value.isString() ? value.asText() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The payload claims of {@code jwt}; throws when it is no JWT. */
    static JqValue payload(String jwt) {
        if (jwt == null) throw new IllegalArgumentException("no token");
        var parts = jwt.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("not a JWT");
        var payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return dev.incusspawn.config.Jjq.parseForeign(payload, "a Google id token");
    }

    private static String requireDigest(String digest) {
        if (!isDigest(digest)) throw new IllegalArgumentException("not a proof digest");
        return digest;
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
