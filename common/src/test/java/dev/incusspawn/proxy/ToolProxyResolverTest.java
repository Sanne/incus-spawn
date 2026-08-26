package dev.incusspawn.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.incusspawn.tool.ToolDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolProxyResolverTest {

    @Test
    void fingerprintEmptyListReturnsEmpty() {
        assertEquals("", ToolProxyResolver.fingerprint(List.of()));
        assertEquals("", ToolProxyResolver.fingerprint(null));
    }

    @Test
    void fingerprintIsDeterministic() {
        var proxies = List.of(
                new ResolvedToolProxy("gh", "github.com",
                        authDef("bearer"), Map.of("token", "my-token")),
                new ResolvedToolProxy("openai", "api.openai.com",
                        authDef("bearer"), Map.of("api-key", "sk-123"))
        );
        var fp1 = ToolProxyResolver.fingerprint(proxies);
        var fp2 = ToolProxyResolver.fingerprint(proxies);
        assertEquals(fp1, fp2);
        assertFalse(fp1.isEmpty());
        assertEquals(64, fp1.length());
    }

    @Test
    void fingerprintChangesWhenCredentialChanges() {
        var proxies1 = List.of(
                new ResolvedToolProxy("gh", "github.com",
                        authDef("bearer"), Map.of("token", "token-A"))
        );
        var proxies2 = List.of(
                new ResolvedToolProxy("gh", "github.com",
                        authDef("bearer"), Map.of("token", "token-B"))
        );
        assertNotEquals(ToolProxyResolver.fingerprint(proxies1),
                ToolProxyResolver.fingerprint(proxies2));
    }

    @Test
    void fingerprintChangesWhenDomainChanges() {
        var proxies1 = List.of(
                new ResolvedToolProxy("gh", "github.com",
                        authDef("bearer"), Map.of("token", "t"))
        );
        var proxies2 = List.of(
                new ResolvedToolProxy("gh", "api.github.com",
                        authDef("bearer"), Map.of("token", "t"))
        );
        assertNotEquals(ToolProxyResolver.fingerprint(proxies1),
                ToolProxyResolver.fingerprint(proxies2));
    }

    @Test
    void fingerprintOrderIndependent() {
        var a = new ResolvedToolProxy("gh", "github.com",
                authDef("bearer"), Map.of("token", "t1"));
        var b = new ResolvedToolProxy("openai", "api.openai.com",
                authDef("bearer"), Map.of("key", "t2"));
        assertEquals(ToolProxyResolver.fingerprint(List.of(a, b)),
                ToolProxyResolver.fingerprint(List.of(b, a)));
    }

    @Test
    void fingerprintChangesWhenToolAdded() {
        var a = new ResolvedToolProxy("gh", "github.com",
                authDef("bearer"), Map.of("token", "t1"));
        var b = new ResolvedToolProxy("openai", "api.openai.com",
                authDef("bearer"), Map.of("key", "t2"));
        assertNotEquals(ToolProxyResolver.fingerprint(List.of(a)),
                ToolProxyResolver.fingerprint(List.of(a, b)));
    }

    // --- navigateConfigPath tests ---

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void navigateConfigPathSimpleDotPath() {
        var tree = JSON.createObjectNode();
        tree.putObject("github").put("token", "ghp_abc123");
        assertEquals("ghp_abc123", ToolProxyResolver.navigateConfigPath(tree, "github.token"));
    }

    @Test
    void navigateConfigPathTopLevelKey() {
        var tree = JSON.createObjectNode();
        tree.put("apiKey", "sk-test");
        assertEquals("sk-test", ToolProxyResolver.navigateConfigPath(tree, "apiKey"));
    }

    @Test
    void navigateConfigPathMissingIntermediateReturnsEmpty() {
        var tree = JSON.createObjectNode();
        tree.put("other", "value");
        assertEquals("", ToolProxyResolver.navigateConfigPath(tree, "github.token"));
    }

    @Test
    void navigateConfigPathMissingLeafReturnsEmpty() {
        var tree = JSON.createObjectNode();
        tree.putObject("github").put("user", "octocat");
        assertEquals("", ToolProxyResolver.navigateConfigPath(tree, "github.token"));
    }

    @Test
    void navigateConfigPathNonTextLeafReturnsEmpty() {
        var tree = JSON.createObjectNode();
        tree.putObject("github").put("count", 42);
        assertEquals("", ToolProxyResolver.navigateConfigPath(tree, "github.count"));
    }

    @Test
    void navigateConfigPathNullTreeReturnsEmpty() {
        assertEquals("", ToolProxyResolver.navigateConfigPath(null, "github.token"));
    }

    @Test
    void navigateConfigPathObjectLeafReturnsEmpty() {
        var tree = JSON.createObjectNode();
        tree.putObject("github").putObject("nested").put("deep", "value");
        assertEquals("", ToolProxyResolver.navigateConfigPath(tree, "github.nested"));
    }

    private static ToolDef.AuthDef authDef(String type) {
        var auth = new ToolDef.AuthDef();
        auth.setType(type);
        return auth;
    }
}
