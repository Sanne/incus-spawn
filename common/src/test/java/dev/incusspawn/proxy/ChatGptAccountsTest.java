package dev.incusspawn.proxy;

import dev.incusspawn.config.HostRoutes;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGptAccountsTest {

    @Test
    void noHostRouteCanTakeChatGptsHosts() {
        // ToolProxyResolver.hostRoutes leaves them out of its clash check because of this
        for (var domain : ChatGptAccounts.DOMAINS) {
            assertFalse(domain.endsWith(HostRoutes.SUFFIX), domain);
        }
    }

    @Test
    void onlyAChatGptAccountTurnsItOn() {
        assertTrue(ChatGptAccounts.enabledBy(JqValues.parse("{\"openai\":{\"accounts\":{\"a\":{\"type\":\"chatgpt\"}}}}")));
        assertFalse(ChatGptAccounts.enabledBy(JqValues.parse("{\"openai\":{\"accounts\":{\"a\":{\"apiKey\":\"sk-x\"}}}}")));
        assertFalse(ChatGptAccounts.enabledBy(JqValues.parse("{}")));
    }
}
