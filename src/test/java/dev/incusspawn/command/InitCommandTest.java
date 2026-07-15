package dev.incusspawn.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
