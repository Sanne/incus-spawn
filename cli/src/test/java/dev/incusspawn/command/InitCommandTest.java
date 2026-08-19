package dev.incusspawn.command;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InitCommandTest {

    @Test
    void maskSecretApiKey() {
        assertEquals("sk-ant-...7x3Q", InitCommand.maskSecret("sk-ant-api03-abcdefghij7x3Q"));
    }

    @Test
    void maskSecretGhpToken() {
        assertEquals("ghp_...aB9z", InitCommand.maskSecret("ghp_1234567890aB9z"));
    }

    @Test
    void maskSecretGithubPatToken() {
        assertEquals("github_pat_...Yz12", InitCommand.maskSecret("github_pat_ABCDEFGHIJKLMNOPYz12"));
    }

    @Test
    void maskSecretOauthToken() {
        assertEquals("eyJh...xK2m", InitCommand.maskSecret("eyJhbGciOiJSUzI1NixK2m"));
    }

    @Test
    void maskSecretShortValue() {
        assertEquals("****", InitCommand.maskSecret("short"));
    }

    @Test
    void maskSecretNull() {
        assertEquals("****", InitCommand.maskSecret(null));
    }

    @Test
    void maskSecretFallsBackWhenPrefixPlusSuffixOverlap() {
        assertEquals("****", InitCommand.maskSecret("github_pat_ABCD"));
        assertEquals("****", InitCommand.maskSecret("sk-ant-ABCD"));
        assertEquals("****", InitCommand.maskSecret("ghp_ABCD"));
    }

    @Test
    void subidRangeCoversExactMatch() {
        assertTrue(InitCommand.subidRangeCovers("root:1000:1", "root", 1000, 1));
        assertTrue(InitCommand.subidRangeCovers("root:1000000:1000000000", "root", 1000000, 1000000000));
    }

    @Test
    void subidRangeCoversSupersetCovers() {
        assertTrue(InitCommand.subidRangeCovers("root:1000000:2000000000", "root", 1000000, 1000000000));
    }

    @Test
    void subidRangeCoversSmallerCountDoesNotCover() {
        assertFalse(InitCommand.subidRangeCovers("root:1000000:100", "root", 1000000, 1000000000));
    }

    @Test
    void subidRangeCoversDifferentUserDoesNotCover() {
        assertFalse(InitCommand.subidRangeCovers("nobody:1000:1", "root", 1000, 1));
    }

    @Test
    void subidRangeCoversMalformedLine() {
        assertFalse(InitCommand.subidRangeCovers("root:abc:1", "root", 1000, 1));
        assertFalse(InitCommand.subidRangeCovers("root", "root", 1000, 1));
    }

    // --- parseGitHubEmails ---

    @Test
    void parseEmailsReturnsPrimaryVerifiedEmail() {
        var json = """
                [
                  {"email":"primary@example.com","primary":true,"verified":true},
                  {"email":"other@example.com","primary":false,"verified":true}
                ]""";
        var result = InitCommand.parseGitHubEmails(json);
        assertNotNull(result);
        assertEquals(java.util.List.of("primary@example.com", "other@example.com"), result.verified());
        assertEquals("primary@example.com", result.primary());
    }

    @Test
    void parseEmailsFiltersUnverified() {
        var json = """
                [
                  {"email":"unverified@example.com","primary":false,"verified":false},
                  {"email":"verified@example.com","primary":false,"verified":true}
                ]""";
        var result = InitCommand.parseGitHubEmails(json);
        assertNotNull(result);
        assertEquals(java.util.List.of("verified@example.com"), result.verified());
        assertNull(result.primary());
    }

    @Test
    void parseEmailsIncludesNoreplyFirst() {
        var json = """
                [
                  {"email":"12345+user@users.noreply.github.com","primary":false,"verified":true},
                  {"email":"real@example.com","primary":false,"verified":true}
                ]""";
        var result = InitCommand.parseGitHubEmails(json);
        assertNotNull(result);
        assertEquals(java.util.List.of("12345+user@users.noreply.github.com", "real@example.com"), result.verified());
    }

    @Test
    void parseEmailsReturnsNoreplyWhenOnly() {
        var json = """
                [{"email":"12345+user@users.noreply.github.com","primary":true,"verified":true}]""";
        var result = InitCommand.parseGitHubEmails(json);
        assertNotNull(result);
        assertEquals(java.util.List.of("12345+user@users.noreply.github.com"), result.verified());
    }

    @Test
    void parseEmailsReturnsNullOnEmptyArray() {
        assertNull(InitCommand.parseGitHubEmails("[]"));
    }

    @Test
    void parseEmailsReturnsNullOnMalformedJson() {
        assertNull(InitCommand.parseGitHubEmails("not json"));
    }

    @Test
    void parseEmailsDoesNotMisidentifyPrimaryFalseAsTrue() {
        var json = """
                [
                  {"email":"not-primary@example.com","primary":false,"verified":true},
                  {"email":"actual-primary@example.com","primary":true,"verified":true}
                ]""";
        var result = InitCommand.parseGitHubEmails(json);
        assertNotNull(result);
        assertEquals("actual-primary@example.com", result.primary());
    }

    @Test
    void parseEmailsHandlesFieldsInAnyOrder() {
        var json = """
                [{"verified":true,"primary":true,"email":"any-order@example.com"}]""";
        var result = InitCommand.parseGitHubEmails(json);
        assertNotNull(result);
        assertEquals(java.util.List.of("any-order@example.com"), result.verified());
        assertEquals("any-order@example.com", result.primary());
    }

    /**
     * Runs {@code body} with {@code user.home} pointed at {@code home}, so the tests below
     * exercise the real {@link Environment#initCompleteMarker()} path.
     */
    private static void withHome(Path home, Runnable body) {
        var original = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            body.run();
        } finally {
            if (original == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", original);
            }
        }
    }

    /** Writes the sentinel at whatever path the production code reads it from. */
    private static void writeSentinel(Path home, String contents) {
        withHome(home, () -> {
            try {
                var marker = Environment.initCompleteMarker();
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, contents);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Test
    void notInitializedWhenSentinelAbsent(@TempDir Path home) {
        withHome(home, () -> assertFalse(InitCommand.hasBeenInitialized()));
    }

    @Test
    void initializedAtCurrentVersion(@TempDir Path home) {
        writeSentinel(home, String.valueOf(Environment.INIT_VERSION));
        withHome(home, () -> assertTrue(InitCommand.hasBeenInitialized()));
    }

    @Test
    void notInitializedWhenSentinelIsOlder(@TempDir Path home) {
        writeSentinel(home, String.valueOf(Environment.INIT_VERSION - 1));
        withHome(home, () -> assertFalse(InitCommand.hasBeenInitialized()));
    }

    /**
     * The regression: an older binary reading a sentinel written by a newer install must not
     * conclude it was never initialized. Equality here crash-looped the proxy service and made
     * two co-installed binaries re-run init in a loop.
     */
    @Test
    void initializedWhenSentinelIsNewer(@TempDir Path home) {
        writeSentinel(home, String.valueOf(Environment.INIT_VERSION + 1));
        withHome(home, () -> assertTrue(InitCommand.hasBeenInitialized()));
    }

    @Test
    void notInitializedWhenSentinelIsUnparseable(@TempDir Path home) {
        writeSentinel(home, "garbage");
        withHome(home, () -> assertFalse(InitCommand.hasBeenInitialized()));
    }

    @Test
    void sentinelToleratesSurroundingWhitespace(@TempDir Path home) {
        writeSentinel(home, "  " + Environment.INIT_VERSION + "\n");
        withHome(home, () -> assertTrue(InitCommand.hasBeenInitialized()));
    }

    // --- hasExistingTemplatesSearchPath ---

    @Test
    void detectsExactTemplatesRepoPath() {
        assertTrue(InitCommand.hasExistingTemplatesSearchPath(
                java.util.List.of("/home/user/.config/incus-spawn/incus-spawn-templates")));
    }

    @Test
    void detectsTemplatesRepoInCustomLocation() {
        assertTrue(InitCommand.hasExistingTemplatesSearchPath(
                java.util.List.of("/home/user/sources/incus-spawn-templates")));
    }

    @Test
    void doesNotMatchSubstringInParentDir() {
        assertFalse(InitCommand.hasExistingTemplatesSearchPath(
                java.util.List.of("/home/user/not-incus-spawn-templates-backup/stuff")));
    }

    @Test
    void doesNotMatchPartialRepoName() {
        assertFalse(InitCommand.hasExistingTemplatesSearchPath(
                java.util.List.of("/home/user/incus-spawn-templates-old")));
    }

    @Test
    void emptySearchPathsReturnsFalse() {
        assertFalse(InitCommand.hasExistingTemplatesSearchPath(java.util.List.of()));
    }

    @Test
    void matchesAmongMultiplePaths() {
        assertTrue(InitCommand.hasExistingTemplatesSearchPath(
                java.util.List.of("/home/user/other-templates", "/home/user/incus-spawn-templates")));
    }
}
