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

    /** Pi's google-vertex provider sends its key as x-goog-api-key; the claude namespace's proof is read there. */
    @Test void theVertexDomainReadsTheClaudeProofFromTheGoogleKeyHeader() {
        var proof = "AIzaisx_" + ProofToken.derive(SECRET, "claude");
        var carriers = new ProofCarriers(List.of(new ClaudeSetup(), new dev.incusspawn.tool.PiSetup()));
        var presented = carriers.read("aiplatform.googleapis.com",
                name -> name.equalsIgnoreCase("x-goog-api-key") ? List.of(proof) : List.of());
        assertEquals(Map.of("claude", List.of(ProofToken.derive(SECRET, "claude"))), presented.digests());
        assertTrue(presented.claimsProof());
        assertEquals(Map.of(), new ProofCarriers(List.of(new GhSetup())).read("aiplatform.googleapis.com",
                name -> List.of(proof)).digests(), "no claude placeholders, no carrier");
    }

    @Test void anEmptyAuthItemDeclaresNothing() throws Exception {
        var tool = new YamlToolSetup(Jjq.toolDef("""
                name: empty-auth
                proxy:
                  config-namespace: emptyauth
                  auth:
                    -
                    - domains: [empty.example]
                      type: bearer
                      token: literal
                """));
        assertEquals(1, tool.proxy().getAuth().size());
        assertTrue(new ProofCarriers(List.of(tool)).warnings().isEmpty());
        assertEquals(List.of("empty.example"), ToolProxyResolver.resolve(new dev.incusspawn.config.SpawnConfig(),
                Map.of("empty-auth", tool)).stream().map(ResolvedToolProxy::domain).toList());
    }

    @Test void borrowedNamespaceUsesItsOwnersProofDeclarations() throws Exception {
        var tool = new YamlToolSetup(Jjq.toolDef("""
                name: custom
                proxy:
                  configuration:
                    token:
                      config-path: github.token
                  auth:
                    - domains: [custom.example]
                      type: bearer
                      token: '${token}'
                """));
        var alone = new ProofCarriers(List.of(tool));
        assertTrue(alone.warningsForNamespace(tool.proxy().getConfigNamespace()).isEmpty(),
                "single-tool validation cannot know whether the namespace owner is installed");
        assertEquals(1, alone.warnings().size(), "proxy load must still diagnose a missing owner");
        assertTrue(alone.warnings().getFirst().contains("namespace 'github'"));
        assertEquals(List.of(), alone.read("custom.example", name -> List.of("Bearer " + GH)).digests().get("github"));

        var together = new ProofCarriers(List.of(tool, new GhSetup()));
        assertTrue(together.warnings().isEmpty());
        assertEquals(Map.of("github", List.of(ProofToken.derive(SECRET, "github"))),
                together.read("custom.example", name -> List.of("Bearer " + GH)).digests());
    }

    @Test void topLevelCredentialNeedsANamespaceBeforeItCanDeclareProofs() throws Exception {
        var tool = new YamlToolSetup(Jjq.toolDef("""
                name: custom
                proxy:
                  configuration:
                    token:
                      config-path: token
                  auth:
                    - domains: [custom.example]
                      type: bearer
                      token: '${token}'
                """));
        var carriers = new ProofCarriers(List.of(tool));
        assertEquals(1, carriers.warnings().size());
        var warning = carriers.warnings().getFirst();
        assertTrue(warning.contains("set proxy.config-namespace"), warning);
        assertTrue(warning.contains("config-path entries relative to it"), warning);
        assertTrue(warning.indexOf("config-namespace") < warning.indexOf("proxy.placeholders"), warning);
        assertEquals(List.of(), carriers.read("custom.example", name -> List.of("Bearer " + GH)).digests().get(""));
    }

    @Test void missingPlaceholdersWarnOncePerNamespaceAndProveNothing() throws Exception {
        var tool = new YamlToolSetup(Jjq.toolDef("""
                name: custom
                proxy:
                  config-namespace: custom
                  configuration:
                    key:
                      config-path: token
                  auth:
                    - domains: [api.github.com]
                      type: bearer
                      token: '${key}'
                    - domains: [other.custom.example]
                      type: header
                      name: X-Custom-Key
                      value: '${key}'
                """));
        var carriers = new ProofCarriers(List.of(tool, new GhSetup()));
        assertEquals(1, carriers.warnings().size());
        var warning = carriers.warnings().getFirst();
        for (var detail : List.of("tool 'custom'", "namespace 'custom'", "no declared placeholders",
                "api.github.com", "other.custom.example", "proxy.placeholders", "env")) {
            assertTrue(warning.contains(detail), warning);
        }
        var presented = carriers.read("api.github.com", name -> List.of("Bearer " + GH));
        assertEquals(List.of(), presented.digests().get("custom"));
        assertEquals(List.of(ProofToken.derive(SECRET, "github")), presented.digests().get("github"));
    }

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
        assertTrue(carriers.warnings().isEmpty());
        var digest = ProofToken.derive(SECRET, "custom");
        var presented = carriers.read("api.custom.example", name -> name.equals("X-Custom-Key")
                ? List.of("Token ct_isx_" + digest) : List.of());
        assertEquals(Map.of("custom", List.of(digest)), presented.digests());
        assertTrue(presented.claimsProof());
        assertFalse(carriers.read("api.custom.example", name -> List.of("real-account-B")).claimsProof());
        assertTrue(carriers.read("api.custom.example", name -> List.of("ct_placeholder")).claimsProof());
        assertTrue(carriers.read("api.custom.example", name -> List.of("ct_isx_malformed")).claimsProof());
    }

    @Test void emptyPlaceholderAndDomainItemsLoadWithoutACarrierForThem() throws Exception {
        var tool = new YamlToolSetup(Jjq.toolDef("""
                name: p
                proxy:
                  config-namespace: p
                  configuration:
                    key:
                      config-path: token
                  placeholders:
                    -
                  auth:
                    - domains:
                        -
                        - api.p.example
                      type: bearer
                      token: '${key}'
                """));
        var carriers = new ProofCarriers(List.of(tool));
        assertEquals(1, carriers.warnings().size(), carriers.warnings().toString());
        var warning = carriers.warnings().getFirst();
        assertTrue(warning.contains("api.p.example"), warning);
        assertFalse(warning.contains("null"), warning);
        // The declared domain keeps its carrier, which accepts no proof: an empty placeholder item
        // declares no prefix, so even a bare 'isx_' token for the namespace neither proves nor claims
        var presented = carriers.read("api.p.example", name -> List.of("Bearer isx_" + ProofToken.derive(SECRET, "p")));
        assertEquals(List.of(), presented.digests().get("p"));
        assertFalse(presented.claimsProof());
    }
}
