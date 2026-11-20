package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** What a guest holds for a Google sign-in (#2443): look-alikes that carry a digest and an email, and nothing of the real tokens. */
class GooglePlaceholdersTest {

    private static final String DIGEST = "0123456789abcdef0123456789abcdef";

    @Test
    void thePlaceholdersHaveGooglesPrefixesAndCarryTheDigest() {
        assertEquals("ya29.isx_" + DIGEST, GooglePlaceholders.accessToken(DIGEST));
        assertEquals("1//isx_" + DIGEST, GooglePlaceholders.refreshToken(DIGEST));
        assertEquals(DIGEST, GooglePlaceholders.digestOf("ya29.isx_" + DIGEST));
        assertEquals(DIGEST, GooglePlaceholders.digestOf("1//isx_" + DIGEST));
        assertTrue(GooglePlaceholders.claims("ya29.isx_short"));
        assertNull(GooglePlaceholders.digestOf("ya29.isx_short"), "a claim with a malformed digest proves nothing");
        assertFalse(GooglePlaceholders.claims("ya29.real-token"));
        assertNull(GooglePlaceholders.digestOf("ya29.real-token"));
        assertNull(GooglePlaceholders.digestOf(null));
        assertThrows(IllegalArgumentException.class, () -> GooglePlaceholders.accessToken("nope"));
    }

    @Test
    void theTokenResponseHoldsTheRealTokensTimeLeftAndEmailAndNoneOfTheirValues() {
        var now = System.currentTimeMillis() / 1000;
        var real = new GoogleSessionStore.Tokens("ya29.real-secret", "1//real-secret", now + 1000, "user@example.invalid");
        var response = GooglePlaceholders.tokenResponse(real, DIGEST, "openid email");
        assertFalse(response.contains("real-secret"));
        var json = JqValues.parseStrict(response);
        assertEquals("ya29.isx_" + DIGEST, json.pathText("access_token", ""));
        assertEquals("1//isx_" + DIGEST, json.pathText("refresh_token", ""));
        assertEquals("Bearer", json.pathText("token_type", ""));
        assertEquals("openid email", json.pathText("scope", ""));
        var expiresIn = json.path("expires_in").asLong(-1);
        assertTrue(expiresIn >= 995 && expiresIn <= 1000, String.valueOf(expiresIn));
        var idToken = json.pathText("id_token", "");
        assertEquals("user@example.invalid", GooglePlaceholders.emailOf(idToken));
        var header = new String(Base64.getUrlDecoder().decode(idToken.split("\\.")[0]), StandardCharsets.UTF_8);
        assertTrue(header.contains("\"alg\":\"none\""), header);
        assertTrue(idToken.endsWith("."), "unsigned: an empty signature segment");

        var expired = GooglePlaceholders.tokenResponse(new GoogleSessionStore.Tokens("a", "r", now - 10, ""), DIGEST, "");
        assertEquals(0, JqValues.parseStrict(expired).path("expires_in").asLong(-1));
        assertFalse(expired.contains("scope"));
        assertEquals("", GooglePlaceholders.emailOf(JqValues.parseStrict(expired).pathText("id_token", "")));
    }

    @Test
    void theEmailOfARealIdTokenIsReadAndAnythingElseIsNone() {
        var enc = Base64.getUrlEncoder().withoutPadding();
        var jwt = enc.encodeToString("{\"alg\":\"RS256\"}".getBytes()) + "."
                + enc.encodeToString("{\"email\":\"me@example.invalid\",\"sub\":\"1\"}".getBytes()) + ".sig";
        assertEquals("me@example.invalid", GooglePlaceholders.emailOf(jwt));
        assertEquals("", GooglePlaceholders.emailOf("not-a-jwt"));
        assertEquals("", GooglePlaceholders.emailOf(null));
        assertEquals("", GooglePlaceholders.emailOf(""));
    }
}
