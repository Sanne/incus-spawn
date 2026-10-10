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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
        assertEquals("case \"${GH_TOKEN-}\" in gho_placeholder*|gho_isx_*) export GH_TOKEN='gho_isx_"
                + ProofToken.derive(secret, "github") + "' ;; esac\n", profile);
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
    void aGuardOneToolNeedsHoldsForEveryDeclarationOfTheSameCredential() {
        // A YAML tool declaring Claude's key unguarded must neither drop it nor lift the guard
        var guarded = tool("claude", new ProofToken.Placeholder("ANTHROPIC_API_KEY", "sk-ant-", "claude").onlyWhen("[ -e /a ]"));
        var plain = tool("mine", new ProofToken.Placeholder("ANTHROPIC_API_KEY", "sk-ant-", "claude"));
        var other = tool("other", new ProofToken.Placeholder("ANTHROPIC_API_KEY", "sk-ant-", "claude").onlyWhen("[ -e /b ]"));

        assertEquals(List.of(new ProofToken.Placeholder("ANTHROPIC_API_KEY", "sk-ant-", "claude").onlyWhen("[ -e /a ]")),
                ProofToken.declaredBy(List.of(plain, guarded)));
        assertEquals("{ [ -e /a ]; } && { [ -e /b ]; }",
                ProofToken.declaredBy(List.of(guarded, plain, other)).getFirst().guard());
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
                env:
                  - name: ACME_TOKEN
                    value: acme_placeholder
                  - name: PATH
                    value: /opt/acme/bin
                    strategy: prepend
                    separator: ":"
                proxy:
                  config-namespace: acme
                  placeholders:
                    - env: ACME_TOKEN
                      prefix: acme_
                    - env: LD_PRELOAD
                      prefix: acme_
                    - env: PATH
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
                new YamlToolSetup(def).placeholders(),
                "only a variable the tool exports: a proof must never clobber another one");
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

        var byEnv = ProofToken.declaredBy(tools.values()).stream()
                .collect(Collectors.toMap(ProofToken.Placeholder::env, p -> p));
        var exported = new HashSet<String>();
        for (var tool : tools.values()) {
            var variants = tool.name().equals("pi")
                    ? List.of(Map.of("provider", "anthropic"), Map.of("provider", "openai"))
                    : List.of(Map.<String, String>of());
            for (var params : variants) {
                tool.envEntries(params).stream()
                        .filter(e -> e.getValue() != null && e.getValue().contains("placeholder"))
                        .forEach(e -> {
                            exported.add(e.getName());
                            var declaration = byEnv.get(e.getName());
                            // A start replaces only the build's own placeholder, which it knows by this shape
                            if (declaration != null) assertTrue(e.getValue().startsWith(declaration.staticValuePrefix()),
                                    e.getName() + "=" + e.getValue() + " does not start with " + declaration.staticValuePrefix());
                        });
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

    /**
     * A cloned repository's own tool definitions must not change what a start delivers (#765):
     * the proxy never reads them, so a project {@code gh.yaml} that shadows the built-in would
     * otherwise leave {@code GH_TOKEN} without a proof whenever isx runs from that repository.
     */
    @Test
    @ExtendWith(TempHome.class)
    void aProjectsOwnToolsNeverChangeWhatAStartDelivers() throws Exception {
        var project = java.nio.file.Path.of(".incus-spawn");
        assumeFalse(java.nio.file.Files.exists(project), "the working directory is already a project");
        try {
            java.nio.file.Files.createDirectories(project.resolve("tools"));
            java.nio.file.Files.writeString(project.resolve("tools/gh.yaml"), """
                    name: gh
                    description: a repository's own gh, with no proxy entry
                    """);
            assertTrue(new ToolDefLoader().projectLocalToolNames().contains("gh"),
                    "the fixture shadows the built-in gh for a loader that reads the project");

            var declared = ProofToken.declared().stream().map(ProofToken.Placeholder::env).toList();
            assertTrue(declared.contains("GH_TOKEN"), declared.toString());
        } finally {
            try (var walk = java.nio.file.Files.walk(project)) {
                for (var path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    java.nio.file.Files.deleteIfExists(path);
                }
            }
        }
    }

    /**
     * Each line replaces the build's own placeholder, or an earlier proof, and nothing else: a
     * value a template's own {@code env:} or the user put in the variable stays theirs.
     */
    @Test
    void theProfileLeavesAValueThatIsNotThePlaceholderAlone() throws Exception {
        var secret = InstanceSecret.generate();
        var profile = ProofToken.profile(secret, List.of(new ProofToken.Placeholder("GH_TOKEN", "gho_", "github")));
        var proof = "gho_isx_" + ProofToken.derive(secret, "github");
        for (var before : List.of("gho_placeholder", "gho_isx_0123456789abcdef0123456789abcdef")) {
            assertEquals(proof, loginValue(profile, "GH_TOKEN", before), before);
        }
        assertEquals("ghp_mine", loginValue(profile, "GH_TOKEN", "ghp_mine"));
        assertEquals("unset", loginValue(profile, "GH_TOKEN", null));
    }

    /** What {@code env} holds after a login that set it to {@code before} sources {@code profile}. */
    static String loginValue(String profile, String env, String before) throws Exception {
        var script = (before == null ? "" : "export " + env + "='" + before + "'\n") + profile
                + "echo \"${" + env + "-unset}\"";
        var builder = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        builder.environment().clear();
        var process = builder.start();
        var out = new String(process.getInputStream().readAllBytes()).strip();
        process.waitFor();
        return out;
    }

    /**
     * An image built before #1108's approval script approves only the static key: a key that
     * changed on every start would be asked about each time, defaulting to no. Claude's API key
     * gets a proof only where the script is there, or where there is no Claude Code to ask (pi).
     */
    @Test
    void claudesApiKeyGetsAProofOnlyWhereClaudeCodeApprovesIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {
        var claude = RuntimeConstants.CDI_TOOLS.stream().filter(t -> t.name().equals("claude")).findFirst().orElseThrow();
        var apiKey = claude.placeholders().stream().filter(p -> p.env().equals("ANTHROPIC_API_KEY")).findFirst().orElseThrow();
        var authScript = home.resolve("isx-zz-claude-auth.sh");
        var guarded = apiKey.onlyWhen(apiKey.guard().replace(dev.incusspawn.tool.ClaudeSetup.LOGIN_AUTH_PATH, authScript.toString()));
        var secret = InstanceSecret.generate();
        var profile = "HOME='" + home + "'\n" + ProofToken.profile(secret, List.of(guarded));
        var proof = "sk-ant-isx_" + ProofToken.derive(secret, "claude");

        assertEquals(proof, loginValue(profile, "ANTHROPIC_API_KEY", "sk-ant-placeholder"), "pi alone: nothing to approve the key");
        java.nio.file.Files.writeString(home.resolve(".claude.json"), "{}");
        assertEquals("sk-ant-placeholder", loginValue(profile, "ANTHROPIC_API_KEY", "sk-ant-placeholder"),
                "Claude Code built before #1108 approves only the static key: it keeps it");
        java.nio.file.Files.writeString(authScript, "");
        assertEquals(proof, loginValue(profile, "ANTHROPIC_API_KEY", "sk-ant-placeholder"), "the approval script takes each new key");
    }

    private static ToolSetup tool(String name, ProofToken.Placeholder... placeholders) {
        return new ToolSetup() {
            @Override public String name() { return name; }
            @Override public List<ProofToken.Placeholder> placeholders() { return List.of(placeholders); }
            @Override public void install(dev.incusspawn.incus.Container c, Map<String, String> params) {}
        };
    }
}
