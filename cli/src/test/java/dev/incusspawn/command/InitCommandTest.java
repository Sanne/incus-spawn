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
    void verificationFailureActionParsesAllSupportedResponses() {
        assertEquals(InitCommand.VerificationFailureAction.RETRY,
                InitCommand.parseVerificationFailureAction(" y "));
        assertEquals(InitCommand.VerificationFailureAction.RETRY,
                InitCommand.parseVerificationFailureAction(""));
        assertEquals(InitCommand.VerificationFailureAction.SKIP,
                InitCommand.parseVerificationFailureAction("n"));
        assertEquals(InitCommand.VerificationFailureAction.SKIP,
                InitCommand.parseVerificationFailureAction(null));
        assertEquals(InitCommand.VerificationFailureAction.SAVE_UNVERIFIED,
                InitCommand.parseVerificationFailureAction("S"));
    }

    @Test
    void verificationFailureActionRejectsUnsupportedResponses() {
        assertNull(InitCommand.parseVerificationFailureAction("later"));
    }

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
        // 22 characters, no known prefix: both ends would be 8 of 22, so only the tail shows.
        assertEquals("...xK2m", InitCommand.maskSecret("eyJhbGciOiJSUzI1NixK2m"));
        assertEquals("eyJh...xK2m", InitCommand.maskSecret("eyJhbGciOiJSUzI1NiIsInR5xK2m"));
    }

    /** The review finding: a flat first-4/last-4 showed 8 of a 9-character password. */
    @Test
    void maskSecretHidesShortSecretsEntirely() {
        assertEquals("****", InitCommand.maskSecret("abcdefghi"));
        assertEquals("****", InitCommand.maskSecret("abcdefghijk"));
        assertEquals("...ijkl", InitCommand.maskSecret("abcdefghijkl"));
        assertEquals("****", InitCommand.maskSecret("ghp_abcdefghijk"));
        assertEquals("ghp_...ijkl", InitCommand.maskSecret("ghp_abcdefghijkl"));
    }

    /** Whatever the length, at most a third of the secret material is ever shown. */
    @Test
    void maskSecretNeverRevealsMoreThanAThird() {
        var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (var prefix : new String[] {"", "ghp_", "sk-ant-", "github_pat_"}) {
            for (int n = 0; n <= 40; n++) {
                var secret = prefix + alphabet.substring(0, n);
                var masked = InitCommand.maskSecret(secret);
                var shown = masked.replace("...", "").replace("****", "");
                if (shown.startsWith(prefix)) shown = shown.substring(prefix.length());
                assertTrue(3 * shown.length() <= n,
                        secret + " -> " + masked + " shows " + shown.length() + " of " + n);
            }
        }
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

    // --- computeSubidUpdate ---

    @Test
    void computeSubidUpdateNoChangeWhenEntryPresent() {
        var result = InitCommand.computeSubidUpdate("root:1000:1\n", "root:1000:1", null);
        assertEquals(InitCommand.SubidAction.UNCHANGED, result.action());
    }

    @Test
    void computeSubidUpdateAppendsWhenNoPrefixMatch() {
        var result = InitCommand.computeSubidUpdate("nobody:1000:1\n", "root:1000:1", null);
        assertEquals(InitCommand.SubidAction.UPDATED, result.action());
        assertEquals("nobody:1000:1\nroot:1000:1\n", result.newContent());
    }

    @Test
    void computeSubidUpdateAppendsNewlineWhenContentLacksTrailingNewline() {
        var result = InitCommand.computeSubidUpdate("nobody:1000:1", "root:1000:1", null);
        assertEquals(InitCommand.SubidAction.UPDATED, result.action());
        assertEquals("nobody:1000:1\nroot:1000:1\n", result.newContent());
    }

    @Test
    void computeSubidUpdateReplacesOldEntry() {
        var result = InitCommand.computeSubidUpdate(
                "root:1000000:65536\n", "root:1000000:1000000000", "root:1000000:65536");
        assertEquals(InitCommand.SubidAction.UPDATED, result.action());
        assertEquals("root:1000000:1000000000\n", result.newContent());
    }

    @Test
    void computeSubidUpdateReplacesOldEntryPreservingOtherLines() {
        var content = "root:1000:1\nroot:1000000:65536\n";
        var result = InitCommand.computeSubidUpdate(
                content, "root:1000000:1000000000", "root:1000000:65536");
        assertEquals(InitCommand.SubidAction.UPDATED, result.action());
        assertEquals("root:1000:1\nroot:1000000:1000000000\n", result.newContent());
    }

    @Test
    void computeSubidUpdateUnchangedWhenExistingRangeCovers() {
        var result = InitCommand.computeSubidUpdate(
                "root:1000000:2000000000\n", "root:1000000:1000000000", null);
        assertEquals(InitCommand.SubidAction.UNCHANGED, result.action());
    }

    @Test
    void computeSubidUpdateNeedsConfirmationWhenRangeInsufficient() {
        var result = InitCommand.computeSubidUpdate(
                "root:1000000:100\n", "root:1000000:1000000000", null);
        assertEquals(InitCommand.SubidAction.NEEDS_CONFIRMATION, result.action());
        assertEquals("root:1000000:100", result.conflictingEntry());
    }

    @Test
    void computeSubidUpdatePrefersExactOldEntryOverPrefix() {
        var content = "root:1000000:65536\n";
        var result = InitCommand.computeSubidUpdate(
                content, "root:1000000:1000000000", "root:1000000:65536");
        assertEquals(InitCommand.SubidAction.UPDATED, result.action());
        assertTrue(result.newContent().contains("root:1000000:1000000000"));
        assertFalse(result.newContent().contains("root:1000000:65536"));
    }

    // --- replaceSubidLine ---

    @Test
    void replaceSubidLineReplacesExactLine() {
        assertEquals("root:1000000:1000000000\n",
                InitCommand.replaceSubidLine("root:1000000:65536\n", "root:1000000:65536", "root:1000000:1000000000"));
    }

    @Test
    void replaceSubidLinePreservesOtherLines() {
        String content = "nobody:100000:65536\nroot:1000000:65536\n";
        assertEquals("nobody:100000:65536\nroot:1000000:1000000000\n",
                InitCommand.replaceSubidLine(content, "root:1000000:65536", "root:1000000:1000000000"));
    }

    @Test
    void replaceSubidLineSafeWithDollarInReplacement() {
        assertEquals("$1:1000:1\n",
                InitCommand.replaceSubidLine("old:1000:1\n", "old:1000:1", "$1:1000:1"));
    }

    @Test
    void replaceSubidLineSafeWithBackslashInReplacement() {
        assertEquals("user\\1:1000:1\n",
                InitCommand.replaceSubidLine("old:1000:1\n", "old:1000:1", "user\\1:1000:1"));
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
    static void withHome(Path home, Runnable body) {
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

    // --- pasted secrets ---

    @Test
    void receivedSecretLineShowsLengthAndOnlyTheMaskedForm() {
        assertEquals("\u2713 Received 27 characters (github_pat_...0000)",
                InitCommand.describeReceivedSecret("github_pat_agent_0000000000"));
        // Too short to mask safely: nothing of it is shown.
        assertEquals("\u2713 Received 1 character (****)", InitCommand.describeReceivedSecret("x"));
    }

    @Test
    void readSecretStripsSurroundingWhitespaceAndToleratesNull() {
        assertEquals("sk-ant-oat01-abc", InitCommand.readSecret("  sk-ant-oat01-abc\t".toCharArray()));
        assertEquals("", InitCommand.readSecret(null));
    }

    @Test
    void readInputStripsSurroundingWhitespaceAndToleratesNull() {
        assertEquals("personal", InitCommand.readInput("  personal\t"));
        assertEquals("", InitCommand.readInput(""));
        // Console.readLine() returns null once stdin is closed. Every prompt in InitCommand
        // reads "" as skip/finish/take-the-default, so EOF ends the prompt the way pressing
        // Enter would instead of throwing out of init.
        assertEquals("", InitCommand.readInput(null));
    }

    @Test
    void entryNumberAcceptsBareAndHashPrefixedNumbers() {
        // The list prints "1. /path", so "#2" is a natural way to name an entry (#777).
        assertEquals(2, InitCommand.entryNumber("2"));
        assertEquals(2, InitCommand.entryNumber("#2"));
        assertEquals(12, InitCommand.entryNumber("# 12"));
        assertNull(InitCommand.entryNumber("#"));
        assertNull(InitCommand.entryNumber("~/code"));
        assertNull(InitCommand.entryNumber("2a"));
        assertNull(InitCommand.entryNumber("99999999999"));
    }

    // --- OAuth token shape check ---

    private static String oauthToken(int length) {
        var prefix = "sk-ant-oat01-";
        return prefix + "a".repeat(length - prefix.length());
    }

    @Test
    void wellFormedOauthTokenProducesNoWarning() {
        assertTrue(InitCommand.oauthTokenShapeWarning(oauthToken(108)).isEmpty());
    }

    @Test
    void truncatedOauthTokenWarnsWithItsLength() {
        var warning = InitCommand.oauthTokenShapeWarning(oauthToken(73));
        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains("73"), warning.get());
        assertTrue(warning.get().contains("wrapped"), warning.get());
    }

    @Test
    void unexpectedPrefixWarns() {
        var warning = InitCommand.oauthTokenShapeWarning("sk-ant-api03-" + "a".repeat(95));
        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains("sk-ant-oat01-"), warning.get());
    }

    @Test
    void blankOauthTokenProducesNoWarning() {
        assertTrue(InitCommand.oauthTokenShapeWarning("   ").isEmpty());
        assertTrue(InitCommand.oauthTokenShapeWarning(null).isEmpty());
    }

    // --- API error detail ---

    @Test
    void apiErrorSuffixExtractsMessage() {
        assertEquals(" API said: model: Field required", InitCommand.apiErrorSuffix(
                "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"model: Field required\"}}"));
    }

    @Test
    void apiErrorSuffixCollapsesWhitespaceToOneLine() {
        assertEquals(" API said: first second", InitCommand.apiErrorSuffix(
                "{\"error\":{\"message\":\"first\\n  second\"}}"));
    }

    @Test
    void apiErrorSuffixTruncatesOverlongMessage() {
        var suffix = InitCommand.apiErrorSuffix(
                "{\"error\":{\"message\":\"" + "x".repeat(500) + "\"}}");
        assertTrue(suffix.endsWith("\u2026"), suffix);
        assertEquals(" API said: ".length() + 201, suffix.length());
    }

    @Test
    void apiErrorSuffixIgnoresMalformedBody() {
        assertEquals("", InitCommand.apiErrorSuffix("not json at all"));
        assertEquals("", InitCommand.apiErrorSuffix(""));
        assertEquals("", InitCommand.apiErrorSuffix(null));
    }

    @Test
    void apiErrorSuffixIgnoresNonTextualMessage() {
        assertEquals("", InitCommand.apiErrorSuffix("{\"error\":{\"message\":{\"nested\":1}}}"));
        assertEquals("", InitCommand.apiErrorSuffix("{\"error\":{\"message\":\"  \"}}"));
    }
}
