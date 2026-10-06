package dev.incusspawn.proxy;

import dev.incusspawn.RuntimeConstants;
import dev.incusspawn.lifecycle.TempHome;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.tool.YamlToolSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The proof token each credential placeholder carries (#1106): what it is, and who declares it. */
class ProofTokenTest {

    @Test
    void aProofIsFixedPerSecretAndNamespaceAndDiffersAcrossEither() {
        var secret = InstanceSecret.generate();
        var github = ProofToken.derive(secret, "github");
        assertEquals(github, ProofToken.derive(secret, "github"), "the guest, the proxy and isx doctor must agree");
        assertTrue(github.matches("[0-9a-f]{" + ProofToken.DIGEST_HEX_CHARS + "}"), github);

        assertNotEquals(github, ProofToken.derive(secret, "claude"),
                "one namespace's proof must not open another's credential");
        assertNotEquals(github, ProofToken.derive(InstanceSecret.generate(), "github"),
                "a new start retires the previous start's proofs");
    }

    @Test
    void aProofRevealsNeitherTheSecretNorItsRecordedHash() {
        var secret = InstanceSecret.generate();
        var token = new ProofToken.Placeholder("GH_TOKEN", "gho_", "github").tokenFor(secret);
        assertEquals("gho_isx_" + ProofToken.derive(secret, "github"), token,
                "the tool's prefix, then isx's marker: recognisably ours to anyone debugging");
        // The raw secret is what the MCP bridge accepts, so it must never be in an env dump
        assertFalse(token.contains(secret.substring(0, 16)));
        assertFalse(token.contains(InstanceSecret.sha256(secret).substring(0, 16)));
    }

    @Test
    void onlyAnInstanceSecretKeysAProof() {
        assertThrows(IllegalArgumentException.class, () -> ProofToken.derive("", "github"));
        assertThrows(IllegalArgumentException.class, () -> ProofToken.derive(null, "github"));
        assertThrows(IllegalArgumentException.class, () -> ProofToken.derive("not-hex", "github"));
        assertThrows(IllegalArgumentException.class, () -> ProofToken.derive(InstanceSecret.generate(), ""));
    }

    @Test
    void theProfileReplacesOnlyWhatTheBuildExported() {
        var secret = InstanceSecret.generate();
        var profile = ProofToken.profile(secret, List.of(new ProofToken.Placeholder("GH_TOKEN", "gho_", "github")));
        assertEquals("if [ -n \"${GH_TOKEN+x}\" ]; then export GH_TOKEN='gho_isx_"
                + ProofToken.derive(secret, "github") + "'; fi\n", profile);
    }

    @Test
    void aVariableDeclaredTwoWaysGetsNoProof() {
        // Guessing which tool the image's variable belongs to could hand it another namespace's proof
        var gh = tool("gh", new ProofToken.Placeholder("GH_TOKEN", "gho_", "github"));
        var other = tool("other", new ProofToken.Placeholder("GH_TOKEN", "gho_", "other"));
        var codex = tool("codex", new ProofToken.Placeholder("OPENAI_API_KEY", "sk-", "openai"));
        var pi = tool("pi", new ProofToken.Placeholder("OPENAI_API_KEY", "sk-", "openai"));

        assertEquals(List.of(new ProofToken.Placeholder("OPENAI_API_KEY", "sk-", "openai")),
                ProofToken.declaredBy(List.of(gh, other, codex, pi)),
                "the same declaration from two tools is one variable; two different ones are none");
    }

    @Test
    void aDeclarationThatCannotBeWrittenSafelyIsDropped() {
        var bad = tool("bad",
                new ProofToken.Placeholder("GH_TOKEN; rm -rf /", "gho_", "github"),
                new ProofToken.Placeholder("GH_TOKEN", "gho_'$(id)", "github"),
                new ProofToken.Placeholder("GH_TOKEN", "gho_", ""));
        assertEquals(List.of(), ProofToken.declaredBy(List.of(bad)));
        assertEquals("", ProofToken.profile(InstanceSecret.generate(), bad.placeholders()));
    }

    @Test
    void aYamlToolDeclaresItsPlaceholdersOnItsProxyEntry() throws Exception {
        var def = ToolDef.loadFromStream(new java.io.ByteArrayInputStream("""
                name: acme
                proxy:
                  config-namespace: acme
                  placeholders:
                    - env: ACME_TOKEN
                      prefix: acme_
                  configuration:
                    token:
                      config-path: token
                      secret: true
                  auth:
                    - domains: [api.acme.example]
                      type: bearer
                      token: "${token}"
                """.getBytes()));
        assertEquals(List.of(new ProofToken.Placeholder("ACME_TOKEN", "acme_", "acme")),
                new YamlToolSetup(def).placeholders());
    }

    @Test
    void aBorrowedCredentialsPlaceholderBelongsToTheNamespaceItBorrows() {
        var copilot = RuntimeConstants.CDI_TOOLS.stream().filter(t -> t.name().equals("copilot")).findFirst().orElseThrow();
        assertEquals(List.of(new ProofToken.Placeholder("COPILOT_GITHUB_TOKEN", "gho_", "github")), copilot.placeholders());
    }

    /**
     * A static placeholder a built-in tool exports and nothing declares would keep proving
     * nothing once the proxy checks proofs (#1107): that tool would silently lose its credential.
     */
    @Test
    @ExtendWith(TempHome.class)
    void everyPlaceholderABuiltInToolExportsIsDeclared() {
        var tools = new ToolDefLoader(List.of()).allToolSetups();
        var declared = ProofToken.declaredBy(tools.values()).stream()
                .map(ProofToken.Placeholder::env).collect(Collectors.toSet());

        var exported = new HashSet<String>();
        for (var tool : tools.values()) {
            var variants = tool.name().equals("pi")
                    ? List.of(Map.of("provider", "anthropic"), Map.of("provider", "openai"))
                    : List.of(Map.<String, String>of());
            for (var params : variants) {
                tool.envEntries(params).stream()
                        .filter(e -> e.getValue() != null && e.getValue().contains("placeholder"))
                        .forEach(e -> exported.add(e.getName()));
            }
        }
        assertTrue(exported.containsAll(List.of("GH_TOKEN", "COPILOT_GITHUB_TOKEN", "ANTHROPIC_API_KEY",
                "OPENAI_API_KEY", "BOBSHELL_API_KEY", "TYPESAFE_API_KEY")), exported.toString());
        exported.removeAll(declared);
        assertEquals(java.util.Set.of(), exported, "exported as a credential placeholder, declared by no tool");
        // The OAuth modes' variables, which a build only exports for an OAuth account,
        // and Vertex's, which a build only exports for a Vertex account (#1108)
        assertTrue(declared.containsAll(List.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_OAUTH_TOKEN",
                "ISX_VERTEX_ACCESS_TOKEN")), declared.toString());
    }

    private static ToolSetup tool(String name, ProofToken.Placeholder... placeholders) {
        return new ToolSetup() {
            @Override public String name() { return name; }
            @Override public List<ProofToken.Placeholder> placeholders() { return List.of(placeholders); }
            @Override public void install(dev.incusspawn.incus.Container c, Map<String, String> params) {}
        };
    }
}
