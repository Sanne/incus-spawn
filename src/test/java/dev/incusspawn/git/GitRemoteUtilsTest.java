package dev.incusspawn.git;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void resolveThrowsOnAmbiguousHostPaths() throws IOException {
        // Create two directories with the same repo subdirectory
        var path1 = tempDir.resolve("projects");
        var path2 = tempDir.resolve("workspace");
        Files.createDirectories(path1.resolve("quarkus"));
        Files.createDirectories(path2.resolve("quarkus"));

        var config = new SpawnConfig();
        config.setHostPaths(java.util.List.of(path1.toString(), path2.toString()));

        var exception = assertThrows(IllegalStateException.class,
            () -> GitRemoteUtils.resolveHostRepoPath("quarkus", config));
        assertTrue(exception.getMessage().contains("Found multiple host directories"));
        assertTrue(exception.getMessage().contains("repo-paths"));
    }

    // ── referenceDeviceName ───────────────────────────────────────────────

    private static final String QUARKUS_URL = "https://github.com/quarkusio/quarkus.git";
    private static final String INCUS_SPAWN_URL = "https://github.com/Sanne/incus-spawn.git";

    @Test
    void referenceDeviceNameHasRefPrefixAndHash() {
        var result = GitRemoteUtils.referenceDeviceName("quarkus", QUARKUS_URL);
        assertTrue(result.startsWith("ref-quarkus-"), result);
        assertTrue(result.length() <= 64, result);
    }

    @Test
    void referenceDeviceNameSanitizesSpecialChars() {
        var result = GitRemoteUtils.referenceDeviceName("my-repo.name", QUARKUS_URL);
        assertTrue(result.startsWith("ref-my-repo-name-"), result);
    }

    @Test
    void referenceDeviceNamePreservesDashes() {
        var result = GitRemoteUtils.referenceDeviceName("incus-spawn", INCUS_SPAWN_URL);
        assertTrue(result.startsWith("ref-incus-spawn-"), result);
    }

    @Test
    void referenceDeviceNameTruncatesLongNames() {
        var longName = "a".repeat(100);
        var result = GitRemoteUtils.referenceDeviceName(longName, QUARKUS_URL);
        assertTrue(result.length() <= 64);
        assertTrue(result.startsWith("ref-"));
    }

    @Test
    void referenceDeviceNameRejectsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> GitRemoteUtils.referenceDeviceName("", QUARKUS_URL));
        assertThrows(IllegalArgumentException.class,
                () -> GitRemoteUtils.referenceDeviceName("  ", QUARKUS_URL));
    }

    @Test
    void referenceDeviceNameDistinguishesSameNameDifferentOrg() {
        var nameA = GitRemoteUtils.referenceDeviceName("common", "https://github.com/orgA/common.git");
        var nameB = GitRemoteUtils.referenceDeviceName("common", "https://github.com/orgB/common.git");
        assertNotEquals(nameA, nameB);
    }

    // ── referenceContainerPath ────────────────────────────────────────────

    @Test
    void referenceContainerPathIsUnderVarLib() {
        var result = GitRemoteUtils.referenceContainerPath("quarkus", QUARKUS_URL);
        assertTrue(result.startsWith("/var/lib/incus-spawn/repo-ref/quarkus-"), result);
    }

    @Test
    void referenceContainerPathPreservesRepoName() {
        var result = GitRemoteUtils.referenceContainerPath("incus-spawn", INCUS_SPAWN_URL);
        assertTrue(result.startsWith("/var/lib/incus-spawn/repo-ref/incus-spawn-"), result);
    }

    @Test
    void referenceContainerPathRejectsDotDot() {
        assertThrows(IllegalArgumentException.class,
                () -> GitRemoteUtils.referenceContainerPath("..", QUARKUS_URL));
    }

    @Test
    void referenceContainerPathRejectsDot() {
        assertThrows(IllegalArgumentException.class,
                () -> GitRemoteUtils.referenceContainerPath(".", QUARKUS_URL));
    }

    @Test
    void referenceContainerPathRejectsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> GitRemoteUtils.referenceContainerPath("", QUARKUS_URL));
        assertThrows(IllegalArgumentException.class,
                () -> GitRemoteUtils.referenceContainerPath("  ", QUARKUS_URL));
    }

    @Test
    void referenceContainerPathRejectsSlashes() {
        assertThrows(IllegalArgumentException.class,
                () -> GitRemoteUtils.referenceContainerPath("foo/bar", QUARKUS_URL));
    }

    @Test
    void referenceContainerPathDistinguishesSameNameDifferentOrg() {
        var pathA = GitRemoteUtils.referenceContainerPath("common", "https://github.com/orgA/common.git");
        var pathB = GitRemoteUtils.referenceContainerPath("common", "https://github.com/orgB/common.git");
        assertNotEquals(pathA, pathB);
    }

    // ── anyRemoteMatches ──────────────────────────────────────────────────

    @TempDir
    Path tempDir;

    @Test
    void anyRemoteMatchesFindsOrigin() throws IOException, InterruptedException {
        initGitRepoWithRemote(tempDir, "origin", "https://github.com/quarkusio/quarkus.git");
        assertTrue(GitRemoteUtils.anyRemoteMatches(tempDir, "https://github.com/quarkusio/quarkus.git"));
    }

    @Test
    void anyRemoteMatchesFindsFork() throws IOException, InterruptedException {
        initGitRepoWithRemote(tempDir, "origin", "git@github.com:Sanne/quarkus.git");
        runGit(tempDir, "remote", "add", "upstream", "https://github.com/quarkusio/quarkus.git");
        assertTrue(GitRemoteUtils.anyRemoteMatches(tempDir, "https://github.com/quarkusio/quarkus.git"));
    }

    @Test
    void anyRemoteMatchesReturnsFalseWhenNoMatch() throws IOException, InterruptedException {
        initGitRepoWithRemote(tempDir, "origin", "https://github.com/other/repo.git");
        assertFalse(GitRemoteUtils.anyRemoteMatches(tempDir, "https://github.com/quarkusio/quarkus.git"));
    }

    @Test
    void anyRemoteMatchesHandlesSshVsHttps() throws IOException, InterruptedException {
        initGitRepoWithRemote(tempDir, "origin", "git@github.com:quarkusio/quarkus.git");
        assertTrue(GitRemoteUtils.anyRemoteMatches(tempDir, "https://github.com/quarkusio/quarkus.git"));
    }

    private void initGitRepoWithRemote(Path dir, String remoteName, String remoteUrl)
            throws IOException, InterruptedException {
        runGit(dir, "init");
        runGit(dir, "remote", "add", remoteName, remoteUrl);
    }

    private void runGit(Path dir, String... args) throws IOException, InterruptedException {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        var process = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, "git " + String.join(" ", args) + " failed in " + dir + ": " + output);
    }
}
