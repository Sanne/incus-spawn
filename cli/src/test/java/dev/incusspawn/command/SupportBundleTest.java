package dev.incusspawn.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SupportBundleTest {

    private static final String TOKEN = "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789";
    private static final Map<String, String> SECRETS = Map.of(TOKEN, "github.token");

    @TempDir
    Path dir;

    @Test
    void everyFileIsScrubbedOnTheWayIn() throws Exception {
        var bundle = new SupportBundle(dir, SECRETS);
        bundle.add("proxy.log", "injecting " + TOKEN + " for github.com\n");

        var written = Files.readString(dir.resolve("proxy.log"));
        assertFalse(written.contains(TOKEN), "the funnel exists so this cannot happen");
        assertTrue(written.contains("<isx:redacted:github.token>"));
        assertTrue(written.contains("for github.com"), "context around the secret survives");
    }

    @Test
    void scrubbingIsTalliedAcrossFiles() throws Exception {
        var bundle = new SupportBundle(dir, SECRETS);
        bundle.add("proxy.log", TOKEN + "\n" + TOKEN + "\n");
        bundle.add("client.log", "auth with " + TOKEN + "\n");

        assertEquals(3, bundle.scrubHits().get("github.token"));
    }

    @Test
    void cleanContentIsWrittenThrough() throws Exception {
        var bundle = new SupportBundle(dir, SECRETS);
        var content = "Status: RUNNING\nCA fingerprint: ab:cd\n";
        bundle.add("proxy-status.txt", content);

        assertEquals(content, Files.readString(dir.resolve("proxy-status.txt")));
        assertTrue(bundle.scrubHits().isEmpty());
    }

    @Test
    void manifestListsRedactedKeysAndScrubCounts() throws Exception {
        var bundle = new SupportBundle(dir, SECRETS);
        bundle.add("proxy.log", "leaked " + TOKEN + "\n");

        var manifest = bundle.redactionManifest(List.of("claude.oauthToken", "github.token"));
        assertTrue(manifest.contains("claude.oauthToken"), manifest);
        assertTrue(manifest.contains("github.token: 1"), manifest);
        assertTrue(manifest.contains("Config keys redacted (2)"), manifest);
    }

    @Test
    void manifestSaysWhenNothingWasConfigured() {
        var manifest = new SupportBundle(dir, Map.of()).redactionManifest(List.of());
        assertTrue(manifest.contains("(none were configured)"), manifest);
        assertTrue(manifest.contains("(none)"), manifest);
        // The distinction the marker exists for — a reader of the bundle has to be told.
        assertTrue(manifest.contains("genuinely unset"), manifest);
    }
}
