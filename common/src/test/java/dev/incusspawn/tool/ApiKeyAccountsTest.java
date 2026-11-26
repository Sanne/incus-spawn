package dev.incusspawn.tool;

import dev.incusspawn.config.Jjq;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A sign-in is judged only when it is this instance's own account in use (#2525). */
class ApiKeyAccountsTest {

    private static final String SIGN_INS = """
            openai:
              accounts:
                sub: { type: chatgpt }
              default: sub
            gemini:
              accounts:
                g: { type: google }
              default: g
            """;

    @Test
    void anInheritedAccountIsTheParentsToAnswerFor() throws Exception {
        var config = Jjq.spawnConfig(SIGN_INS);
        assertEquals("", ApiKeyAccounts.openaiKeyProblem(config, Map.of("openai", "inherit")));
        assertEquals("", ApiKeyAccounts.geminiKeyProblem(config, Map.of("gemini", "inherit")));
    }

    @Test
    void aPinToAMissingAccountIsLeftToTheGenericCheck() throws Exception {
        var config = Jjq.spawnConfig(SIGN_INS);
        assertEquals("", ApiKeyAccounts.openaiKeyProblem(config, Map.of("openai", "nope")));
        assertEquals("", ApiKeyAccounts.geminiKeyProblem(config, Map.of("gemini", "nope")));
    }

    @Test
    void theDefaultSignInIsReported() throws Exception {
        var config = Jjq.spawnConfig(SIGN_INS);
        assertFalse(ApiKeyAccounts.openaiKeyProblem(config, Map.of()).isEmpty());
        assertFalse(ApiKeyAccounts.geminiKeyProblem(config, Map.of()).isEmpty());
    }
}
