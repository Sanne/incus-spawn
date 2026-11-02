package dev.incusspawn.proxy;

import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGptPlaceholdersTest {

    private static String jwt(String payload) {
        var enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"RS256\"}".getBytes()) + "." + enc.encodeToString(payload.getBytes())
                + ".REALSIGNATURE";
    }

    private static String payload(String token) {
        return new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private static final String AUTH = "{\"chatgpt_plan_type\":\"enterprise\",\"chatgpt_account_id\":\"acct\","
            + "\"chatgpt_user_id\":\"user\",\"organizations\":[{\"id\":\"org-x\"}],\"poid\":\"p\"}";

    @Test
    void theIdTokenKeepsOnlyWhatCodexReadsAndIsUnsigned() {
        var real = jwt("{\"exp\":100,\"iat\":1,\"email\":\"a@b\",\"sub\":\"auth0|x\",\"https://api.openai.com/auth\":" + AUTH
                + ",\"https://api.openai.com/profile\":{\"email\":\"a@b\",\"phone\":\"1\"}}");
        var placeholder = ChatGptPlaceholders.idToken(real);

        var claims = JqValues.parseStrict(payload(placeholder));
        assertEquals("a@b", claims.pathText("email", ""));
        assertEquals("acct", claims.path("https://api.openai.com/auth").pathText("chatgpt_account_id", ""));
        assertEquals("enterprise", claims.path("https://api.openai.com/auth").pathText("chatgpt_plan_type", ""));
        assertFalse(placeholder.contains("REALSIGNATURE"));
        var text = payload(placeholder);
        for (var dropped : new String[]{"sub", "organizations", "poid", "phone"}) {
            assertFalse(text.contains(dropped), dropped + " in " + text);
        }
        assertTrue(new String(Base64.getUrlDecoder().decode(placeholder.split("\\.")[0])).contains("\"none\""));
    }

    @Test
    void theAccessTokenMirrorsExpiryAndCarriesTheProof() {
        var real = jwt("{\"exp\":1234,\"https://api.openai.com/auth\":" + AUTH + "}");
        var proofs = ChatGptProof.derive("11".repeat(32), "sso", "s");
        var placeholder = ChatGptPlaceholders.accessToken(real, proofs.access());

        assertEquals(1234, ChatGptPlaceholders.expiry(placeholder));
        assertEquals(proofs.access(), ChatGptProof.accessDigestOf(placeholder));
        assertFalse(placeholder.contains("REALSIGNATURE"));
    }

    @Test
    void aRefreshAnswerHoldsOnlyPlaceholders() {
        var real = new ChatGptSessionStore.Tokens(jwt("{\"exp\":5}"), jwt("{\"exp\":6}"), "rt.real", "acct");
        var proofs = ChatGptProof.derive("11".repeat(32), "sso", "s");
        var answer = ChatGptPlaceholders.refreshResponse(real, proofs);

        assertFalse(answer.contains("rt.real"));
        assertFalse(answer.contains("REALSIGNATURE"));
        assertEquals(proofs.refreshToken(), JqValues.parseStrict(answer).pathText("refresh_token", ""));
    }
}
