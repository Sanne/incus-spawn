package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

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
    void parseEmailsFiltersNoreply() {
        var json = """
                [
                  {"email":"12345+user@users.noreply.github.com","primary":false,"verified":true},
                  {"email":"real@example.com","primary":false,"verified":true}
                ]""";
        var result = InitCommand.parseGitHubEmails(json);
        assertNotNull(result);
        assertEquals(java.util.List.of("real@example.com"), result.verified());
    }

    @Test
    void parseEmailsReturnsNullWhenAllNoreply() {
        var json = """
                [{"email":"12345+user@users.noreply.github.com","primary":true,"verified":true}]""";
        assertNull(InitCommand.parseGitHubEmails(json));
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
}
