package dev.incusspawn.command;

import dev.incusspawn.config.McpConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SupportBundleTest {

    private static final String TOKEN = "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789";
    private static final Map<String, String> SECRETS = Map.of(TOKEN, "github.token");

    @Test
    @ExtendWith(IsolatedHome.class)
    void theMcpPolicyIsBundledAsParsedOrWithWhyItCannotBeUsed() throws Exception {
        assertTrue(SupportBundle.collectMcpPolicy().startsWith("# no mcp.yaml"));
        var file = McpConfig.file();
        Files.createDirectories(file.getParent());
        Files.writeString(file, "templates: [tpl-dev]  # approved by hand\nmax-instances: 3\n");
        var policy = SupportBundle.collectMcpPolicy();
        assertTrue(policy.contains("tpl-dev") && policy.contains("max-instances: 3"), policy);
        assertFalse(policy.contains("approved by hand"), "written from the parsed policy, not copied: " + policy);
        Files.writeString(file, "templates: [tpl-dev\n");
        assertTrue(SupportBundle.collectMcpPolicy().startsWith("# " + file), SupportBundle.collectMcpPolicy());
    }

    /** The upgrade state: isx mcp refuses everything, and the bundle must say so, not "approves none". */
    @Test
    @ExtendWith(IsolatedHome.class)
    void theMcpPolicyInTheBundleReportsALegacySectionWithNoMcpYaml() throws Exception {
        IsolatedHome.seed("mcp:\n  templates: [tpl-dev]\n");
        var policy = SupportBundle.collectMcpPolicy();
        assertTrue(policy.startsWith("# " + McpConfig.file() + " does not exist, and the mcp: section"),
                policy);
        assertTrue(policy.contains("refuses everything"), policy);

        Files.writeString(McpConfig.file(), "templates: [tpl-java]\n");
        policy = SupportBundle.collectMcpPolicy();
        assertTrue(policy.startsWith("# the mcp: section of config.yaml is no longer read"), policy);
        assertTrue(policy.contains("tpl-java"), policy);
    }

    @Test
    void everyFileIsScrubbedOnTheWayIn() throws Exception {
        var bundle = new SupportBundle(SECRETS);
        bundle.add("proxy.log", "injecting " + TOKEN + " for github.com\n");

        var written = bundle.files().get("proxy.log");
        assertFalse(written.contains(TOKEN), "the funnel exists so this cannot happen");
        assertTrue(written.contains("<isx:redacted:github.token>"));
        assertTrue(written.contains("for github.com"), "context around the secret survives");
    }

    @Test
    void scrubbingIsTalliedAcrossFiles() throws Exception {
        var bundle = new SupportBundle(SECRETS);
        bundle.add("proxy.log", TOKEN + "\n" + TOKEN + "\n");
        bundle.add("client.log", "auth with " + TOKEN + "\n");

        assertEquals(3, bundle.scrubHits().get("github.token"));
    }

    @Test
    void cleanContentIsWrittenThrough() throws Exception {
        var bundle = new SupportBundle(SECRETS);
        var content = "Status: RUNNING\nCA fingerprint: ab:cd\n";
        bundle.add("proxy-status.txt", content);

        assertEquals(content, bundle.files().get("proxy-status.txt"));
        assertTrue(bundle.scrubHits().isEmpty());
    }

    @Test
    void manifestListsRedactedKeysAndScrubCounts() throws Exception {
        var bundle = new SupportBundle(SECRETS);
        bundle.add("proxy.log", "leaked " + TOKEN + "\n");

        var manifest = bundle.redactionManifest(List.of("claude.oauthToken", "github.token"));
        assertTrue(manifest.contains("claude.oauthToken"), manifest);
        assertTrue(manifest.contains("github.token: 1"), manifest);
        assertTrue(manifest.contains("Config keys redacted (2)"), manifest);
    }

    @Test
    void manifestSaysWhenNothingWasConfigured() {
        var manifest = new SupportBundle(Map.of()).redactionManifest(List.of());
        assertTrue(manifest.contains("(none were configured)"), manifest);
        assertTrue(manifest.contains("(none)"), manifest);
        // The distinction the marker exists for — a reader of the bundle has to be told.
        assertTrue(manifest.contains("genuinely unset"), manifest);
    }
}
