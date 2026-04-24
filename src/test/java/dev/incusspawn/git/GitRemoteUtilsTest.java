package dev.incusspawn.git;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitRemoteUtilsTest {

    // ── parseIsxUrl ────────────────────────────────────────────────────────

    @Test
    void parseValidUrl() {
        var result = GitRemoteUtils.parseIsxUrl("isx://my-instance/home/user/project");
        assertNotNull(result);
        assertEquals("my-instance", result.instance());
        assertEquals("/home/user/project", result.path());
    }

    @Test
    void parseUrlWithTilde() {
        var result = GitRemoteUtils.parseIsxUrl("isx://my-instance/~/project");
        assertNotNull(result);
        assertEquals("my-instance", result.instance());
        assertEquals("/home/agentuser/project", result.path());
    }

    @Test
    void parseUrlTildeOnly() {
        var result = GitRemoteUtils.parseIsxUrl("isx://inst/~");
        assertNotNull(result);
        assertEquals("/home/agentuser", result.path());
    }

    @Test
    void parseInvalidUrls() {
        assertNull(GitRemoteUtils.parseIsxUrl(null));
        assertNull(GitRemoteUtils.parseIsxUrl(""));
        assertNull(GitRemoteUtils.parseIsxUrl("https://github.com/org/repo"));
        assertNull(GitRemoteUtils.parseIsxUrl("isx://"));
        assertNull(GitRemoteUtils.parseIsxUrl("isx:///path"));
    }

    // ── repoNameFromUrl ────────────────────────────────────────────────────

    @Test
    void repoNameFromHttpsUrl() {
        assertEquals("quarkus", GitRemoteUtils.repoNameFromUrl("https://github.com/quarkusio/quarkus.git"));
    }

    @Test
    void repoNameFromHttpsUrlNoGitSuffix() {
        assertEquals("quarkus", GitRemoteUtils.repoNameFromUrl("https://github.com/quarkusio/quarkus"));
    }

    @Test
    void repoNameFromSshUrl() {
        assertEquals("my-project", GitRemoteUtils.repoNameFromUrl("git@github.com:org/my-project.git"));
    }

    @Test
    void repoNameFromSshUrlNoGitSuffix() {
        assertEquals("repo", GitRemoteUtils.repoNameFromUrl("git@gitlab.com:team/repo"));
    }

    @Test
    void repoNameFromEmptyOrNull() {
        assertEquals("", GitRemoteUtils.repoNameFromUrl(null));
        assertEquals("", GitRemoteUtils.repoNameFromUrl(""));
    }

    @Test
    void repoNameWithTrailingSlash() {
        assertEquals("repo", GitRemoteUtils.repoNameFromUrl("https://github.com/org/repo/"));
    }

    // ── normalizeGitUrl ────────────────────────────────────────────────────

    @Test
    void normalizeHttpsUrl() {
        assertEquals("github.com/org/repo", GitRemoteUtils.normalizeGitUrl("https://github.com/org/repo.git"));
    }

    @Test
    void normalizeSshUrl() {
        assertEquals("github.com/org/repo", GitRemoteUtils.normalizeGitUrl("git@github.com:org/repo.git"));
    }

    @Test
    void normalizeHttpsNoGitSuffix() {
        assertEquals("github.com/org/repo", GitRemoteUtils.normalizeGitUrl("https://github.com/org/repo"));
    }

    @Test
    void normalizeSshSchemeUrl() {
        assertEquals("github.com/org/repo", GitRemoteUtils.normalizeGitUrl("ssh://git@github.com/org/repo.git"));
    }

    @Test
    void normalizeStripsWww() {
        assertEquals("github.com/org/repo", GitRemoteUtils.normalizeGitUrl("https://www.github.com/org/repo"));
    }

    @Test
    void normalizeCaseInsensitive() {
        assertEquals("github.com/org/repo", GitRemoteUtils.normalizeGitUrl("https://GitHub.COM/Org/Repo.git"));
    }

    @Test
    void normalizeGitSuffixWithTrailingSlash() {
        assertEquals("github.com/org/repo", GitRemoteUtils.normalizeGitUrl("https://github.com/org/repo.git/"));
    }

    // ── urlsMatch ──────────────────────────────────────────────────────────

    @Test
    void matchHttpsAndSsh() {
        assertTrue(GitRemoteUtils.urlsMatch(
                "https://github.com/org/repo.git",
                "git@github.com:org/repo.git"));
    }

    @Test
    void matchWithAndWithoutGitSuffix() {
        assertTrue(GitRemoteUtils.urlsMatch(
                "https://github.com/org/repo.git",
                "https://github.com/org/repo"));
    }

    @Test
    void noMatchDifferentRepos() {
        assertFalse(GitRemoteUtils.urlsMatch(
                "https://github.com/org/repo-a.git",
                "https://github.com/org/repo-b.git"));
    }

    // ── resolveHostRepoPath ────────────────────────────────────────────────

    @Test
    void resolveWithRepoPathOverride() {
        var config = new SpawnConfig();
        config.setRepoPaths(Map.of("quarkus", "/custom/path/quarkus"));
        var result = GitRemoteUtils.resolveHostRepoPath("quarkus", config);
        assertNotNull(result);
        assertEquals("/custom/path/quarkus", result.toString());
    }

    @Test
    void resolveWithHostPathFallback() {
        var config = new SpawnConfig();
        config.setHostPath("/home/user/projects");
        var result = GitRemoteUtils.resolveHostRepoPath("quarkus", config);
        assertNotNull(result);
        assertEquals("/home/user/projects/quarkus", result.toString());
    }

    @Test
    void resolveReturnsNullWhenUnconfigured() {
        var config = new SpawnConfig();
        assertNull(GitRemoteUtils.resolveHostRepoPath("quarkus", config));
    }

    @Test
    void repoPathOverrideTakesPrecedence() {
        var config = new SpawnConfig();
        config.setHostPath("/home/user/projects");
        config.setRepoPaths(Map.of("quarkus", "/custom/quarkus"));
        var result = GitRemoteUtils.resolveHostRepoPath("quarkus", config);
        assertNotNull(result);
        assertEquals("/custom/quarkus", result.toString());
    }
}
