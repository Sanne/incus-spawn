package dev.incusspawn.proxy;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.tool.YamlToolSetup;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProofCarriersTest {
    static final String SECRET = "12".repeat(32);
    static final String GH = "gho_isx_" + ProofToken.derive(SECRET, "github");

    @Test void proofsInUnrelatedHeadersOrDomainsDoNotAuthorize() {
        var carriers = new ProofCarriers(List.of(new GhSetup(), new ClaudeSetup()));
        assertTrue(carriers.read("api.github.com", name -> name.equals("X-Comment") ? List.of(GH) : List.of())
                .digests().values().stream().allMatch(List::isEmpty));
        assertTrue(carriers.read("unrelated.example", name -> List.of(GH)).digests().isEmpty());
        var unrelated = carriers.read("api.anthropic.com", name -> List.of("Bearer " + GH));
        assertTrue(unrelated.digests().get("claude").isEmpty());
        var basic = "Basic " + Base64.getEncoder().encodeToString(("x-access-token:" + GH).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(List.of(ProofToken.derive(SECRET, "github")), carriers.read("github.com", name -> List.of(basic))
                .digests().get("github"));
    }

    @Test void yamlCustomHeadersAndWildcardDomainsUseTheirDeclaredNamespace() throws Exception {
        var tool = new YamlToolSetup(Jjq.toolDef("""
                name: custom
                env:
                  - name: CUSTOM_KEY
                    value: ct_placeholder
                proxy:
                  config-namespace: custom
                  placeholders:
                    - env: CUSTOM_KEY
                      prefix: ct_
                  configuration:
                    key:
                      config-path: token
                      secret: true
                  auth:
                    - domains: ['*.custom.example']
                      type: header
                      name: X-Custom-Key
                      value: 'Token ${key}'
                """));
        var carriers = new ProofCarriers(List.of(tool));
        var digest = ProofToken.derive(SECRET, "custom");
        var presented = carriers.read("api.custom.example", name -> name.equals("X-Custom-Key")
                ? List.of("Token ct_isx_" + digest) : List.of());
        assertEquals(Map.of("custom", List.of(digest)), presented.digests());
        assertTrue(presented.claimsProof());
        assertFalse(carriers.read("api.custom.example", name -> List.of("real-account-B")).claimsProof());
        assertTrue(carriers.read("api.custom.example", name -> List.of("ct_placeholder")).claimsProof());
        assertTrue(carriers.read("api.custom.example", name -> List.of("ct_isx_malformed")).claimsProof());
    }
}
