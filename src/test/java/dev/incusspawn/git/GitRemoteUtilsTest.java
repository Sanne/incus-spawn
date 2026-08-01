package dev.incusspawn.git;

import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.git.GitTestUtils.runGit;
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
    void resolveThrowsOnAmbiguousHostPaths() throws Exception {
        // Create two directories with the same repo subdirectory, both git repos
        var path1 = tempDir.resolve("projects");
        var path2 = tempDir.resolve("workspace");
        Files.createDirectories(path1.resolve("quarkus"));
        Files.createDirectories(path2.resolve("quarkus"));
        runGit(path1.resolve("quarkus"), "init");
        runGit(path2.resolve("quarkus"), "init");

        var config = new SpawnConfig();
        config.setHostPaths(java.util.List.of(path1.toString(), path2.toString()));

        var exception = assertThrows(IllegalStateException.class,
            () -> GitRemoteUtils.resolveHostRepoPath("quarkus", config));
        assertTrue(exception.getMessage().contains("Found multiple host directories"));
        assertTrue(exception.getMessage().contains("repo-paths"));
    }

    @Test
    void resolveDuplicateDirectoryNamesPicksGitRepo() throws Exception {
        // ~/Code/quarkus/quarkus layout: parent is a plain dir, child is the git repo
        var codePath = tempDir.resolve("Code");
        var quarkusParent = codePath.resolve("quarkus");
        var quarkusRepo = quarkusParent.resolve("quarkus");
        Files.createDirectories(quarkusRepo);
        runGit(quarkusRepo, "init");

        var config = new SpawnConfig();
        config.setHostPaths(java.util.List.of(codePath.toString(), quarkusParent.toString()));

        var result = GitRemoteUtils.resolveHostRepoPath("quarkus", config);
        assertNotNull(result);
        assertEquals(quarkusRepo, result);
    }

    @Test
    void resolveMultipleHostPathsIgnoresNonGitDirs() throws Exception {
        // Only git repos should be considered as matches
        var path1 = tempDir.resolve("projects");
        var path2 = tempDir.resolve("workspace");
        Files.createDirectories(path1.resolve("myrepo"));
        Files.createDirectories(path2.resolve("myrepo"));
        // Only the second one is a git repo
        runGit(path2.resolve("myrepo"), "init");

        var config = new SpawnConfig();
        config.setHostPaths(java.util.List.of(path1.toString(), path2.toString()));

        var result = GitRemoteUtils.resolveHostRepoPath("myrepo", config);
        assertNotNull(result);
        assertEquals(path2.resolve("myrepo"), result);
    }

    // ── resolveHostRepoPath: recursive scanning ─────────────────────────

    @Test
    void resolveSingleHostPathFindsNestedRepo() throws IOException {
        var nested = tempDir.resolve("java/quarkus");
        Files.createDirectories(nested.resolve(".git"));

        var config = new SpawnConfig();
        config.setHostPath(tempDir.toString());
        var result = GitRemoteUtils.resolveHostRepoPath("quarkus", config);
        assertNotNull(result);
        assertEquals(nested, result);
    }

    @Test
    void resolveSingleHostPathPrefersDirectChild() throws Exception {
        var direct = tempDir.resolve("quarkus");
        Files.createDirectories(direct);
        runGit(direct, "init");
        Files.createDirectories(tempDir.resolve("java/quarkus/.git"));

        var config = new SpawnConfig();
        config.setHostPath(tempDir.toString());
        var result = GitRemoteUtils.resolveHostRepoPath("quarkus", config);
        assertNotNull(result);
        assertEquals(direct, result);
    }

    @Test
    void resolveSingleHostPathNonGitDirectChildDoesNotBlockNested() throws IOException {
        // ~/Code/quarkus exists as a plain dir (not a git repo)
        Files.createDirectories(tempDir.resolve("quarkus"));
        // ~/Code/java/quarkus is the actual git repo
        var nested = tempDir.resolve("java/quarkus");
        Files.createDirectories(nested.resolve(".git"));

        var config = new SpawnConfig();
        config.setHostPath(tempDir.toString());
        var result = GitRemoteUtils.resolveHostRepoPath("quarkus", config);
        assertNotNull(result);
        assertEquals(nested, result);
    }

    @Test
    void resolveSingleHostPathFallsBackToDirectChildWhenNotFound() {
        var config = new SpawnConfig();
        config.setHostPath(tempDir.toString());
        var result = GitRemoteUtils.resolveHostRepoPath("nonexistent", config);
        assertNotNull(result);
        assertEquals(tempDir.resolve("nonexistent"), result);
    }

    @Test
    void resolveMultipleHostPathsFindsNestedRepo() throws IOException {
        var base1 = tempDir.resolve("code1");
        var base2 = tempDir.resolve("code2");
        Files.createDirectories(base1);
        var nested = base2.resolve("go/myrepo");
        Files.createDirectories(nested.resolve(".git"));

        var config = new SpawnConfig();
        config.setHostPaths(java.util.List.of(base1.toString(), base2.toString()));
        var result = GitRemoteUtils.resolveHostRepoPath("myrepo", config);
        assertNotNull(result);
        assertEquals(nested, result);
    }

    @Test
    void resolveMultipleHostPathsDirectChildWinsOverNested() throws Exception {
        var base1 = tempDir.resolve("code1");
        var base2 = tempDir.resolve("code2");
        var directChild = base1.resolve("quarkus");
        Files.createDirectories(directChild);
        runGit(directChild, "init");
        Files.createDirectories(base2.resolve("java/quarkus/.git"));

        var config = new SpawnConfig();
        config.setHostPaths(java.util.List.of(base1.toString(), base2.toString()));
        var result = GitRemoteUtils.resolveHostRepoPath("quarkus", config);
        assertNotNull(result);
        assertEquals(directChild, result);
    }

    @Test
    void resolveThrowsOnAmbiguousNestedPaths() throws IOException {
        var base = tempDir.resolve("code");
        Files.createDirectories(base.resolve("java/quarkus/.git"));
        Files.createDirectories(base.resolve("go/quarkus/.git"));

        var config = new SpawnConfig();
        config.setHostPath(base.toString());
        var exception = assertThrows(IllegalStateException.class,
                () -> GitRemoteUtils.resolveHostRepoPath("quarkus", config));
        assertTrue(exception.getMessage().contains("Found multiple host directories"));
    }

    @Test
    void resolveSkipsJunkDirectories() throws IOException {
        Files.createDirectories(tempDir.resolve("node_modules/quarkus/.git"));

        var config = new SpawnConfig();
        config.setHostPath(tempDir.toString());
        var result = GitRemoteUtils.resolveHostRepoPath("quarkus", config);
        assertNotNull(result);
        assertEquals(tempDir.resolve("quarkus"), result);
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

    // ── remoteUrlsFromGitConfig ────────────────────────────────────────────

    @Test
    void remoteUrlsFromGitConfigReadsAllRemotes() throws IOException, InterruptedException {
        var repo = tempDir.resolve("multi-remote");
        Files.createDirectories(repo);
        runGit(repo, "init");
        runGit(repo, "remote", "add", "origin", "git@github.com:user/repo.git");
        runGit(repo, "remote", "add", "upstream", "https://github.com/org/repo.git");

        var urls = GitRemoteUtils.remoteUrlsFromGitConfig(repo).toList();
        assertEquals(2, urls.size());
        assertTrue(urls.contains("git@github.com:user/repo.git"));
        assertTrue(urls.contains("https://github.com/org/repo.git"));
    }

    @Test
    void remoteUrlsFromGitConfigReturnsEmptyForNonRepo() {
        var urls = GitRemoteUtils.remoteUrlsFromGitConfig(tempDir.resolve("nonexistent")).toList();
        assertTrue(urls.isEmpty());
    }

    @Test
    void remoteUrlsFromGitConfigFollowsWorktreeGitdir() throws IOException, InterruptedException {
        var mainRepo = tempDir.resolve("main-repo");
        Files.createDirectories(mainRepo);
        runGit(mainRepo, "init");
        runGit(mainRepo, "config", "user.name", "Test");
        runGit(mainRepo, "config", "user.email", "test@test.com");
        runGit(mainRepo, "commit", "--allow-empty", "-m", "init");
        runGit(mainRepo, "remote", "add", "origin", "https://github.com/org/repo.git");
        runGit(mainRepo, "worktree", "add", tempDir.resolve("wt").toString(), "-b", "wt-branch");

        var wtUrls = GitRemoteUtils.remoteUrlsFromGitConfig(tempDir.resolve("wt")).toList();
        assertEquals(1, wtUrls.size());
        assertEquals("https://github.com/org/repo.git", wtUrls.get(0));
    }

    // ── findHostReposByUrl ──────────────────────────────────────────────

    @Test
    void findHostReposByUrlFindsMultipleClones() throws IOException, InterruptedException {
        var base = tempDir.resolve("projects");

        var clone1 = base.resolve("upstream/cxf");
        var clone2 = base.resolve("upstream/cxf-4.1");
        var clone3 = base.resolve("downstream/cxf");
        for (var clone : List.of(clone1, clone2, clone3)) {
            Files.createDirectories(clone);
            runGit(clone, "init");
            runGit(clone, "remote", "add", "origin", "https://github.com/apache/cxf.git");
        }

        var config = new SpawnConfig();
        config.setHostPaths(List.of(base.toString()));

        var result = GitRemoteUtils.findHostReposByUrl(config, "https://github.com/apache/cxf.git");
        assertEquals(3, result.size());
        assertTrue(result.containsAll(List.of(clone1, clone2, clone3)));
    }

    @Test
    void findHostReposByUrlMatchesDifferentlyNamedDir() throws IOException, InterruptedException {
        var base = tempDir.resolve("work");
        var repo = base.resolve("cq");
        Files.createDirectories(repo);
        runGit(repo, "init");
        runGit(repo, "remote", "add", "origin", "https://github.com/apache/camel-quarkus.git");

        var config = new SpawnConfig();
        config.setHostPaths(List.of(base.toString()));

        var result = GitRemoteUtils.findHostReposByUrl(config, "https://github.com/apache/camel-quarkus.git");
        assertEquals(1, result.size());
        assertEquals(repo, result.get(0));
    }

    @Test
    void findHostReposByUrlMatchesSshVsHttps() throws IOException, InterruptedException {
        var base = tempDir.resolve("code");
        var repo = base.resolve("myrepo");
        Files.createDirectories(repo);
        runGit(repo, "init");
        runGit(repo, "remote", "add", "origin", "git@github.com:org/project.git");

        var config = new SpawnConfig();
        config.setHostPaths(List.of(base.toString()));

        var result = GitRemoteUtils.findHostReposByUrl(config, "https://github.com/org/project.git");
        assertEquals(1, result.size());
    }

    @Test
    void findHostReposByUrlMatchesUpstreamRemote() throws IOException, InterruptedException {
        var base = tempDir.resolve("forks");
        var repo = base.resolve("my-fork");
        Files.createDirectories(repo);
        runGit(repo, "init");
        runGit(repo, "remote", "add", "origin", "git@github.com:user/quarkus.git");
        runGit(repo, "remote", "add", "upstream", "https://github.com/quarkusio/quarkus.git");

        var config = new SpawnConfig();
        config.setHostPaths(List.of(base.toString()));

        var result = GitRemoteUtils.findHostReposByUrl(config, "https://github.com/quarkusio/quarkus.git");
        assertEquals(1, result.size());
        assertEquals(repo, result.get(0));
    }

    @Test
    void findHostReposByUrlExcludesNonMatchingRepos() throws IOException, InterruptedException {
        var base = tempDir.resolve("all");
        var match = base.resolve("cxf");
        var noMatch = base.resolve("other");
        for (var dir : List.of(match, noMatch)) {
            Files.createDirectories(dir);
            runGit(dir, "init");
        }
        runGit(match, "remote", "add", "origin", "https://github.com/apache/cxf.git");
        runGit(noMatch, "remote", "add", "origin", "https://github.com/apache/camel.git");

        var config = new SpawnConfig();
        config.setHostPaths(List.of(base.toString()));

        var result = GitRemoteUtils.findHostReposByUrl(config, "https://github.com/apache/cxf.git");
        assertEquals(1, result.size());
        assertEquals(match, result.get(0));
    }

    @Test
    void findHostReposByUrlDeduplicatesWhenMultipleRemotesMatchSameUrl() throws IOException, InterruptedException {
        var base = tempDir.resolve("dedup");
        var repo = base.resolve("myrepo");
        Files.createDirectories(repo);
        runGit(repo, "init");
        runGit(repo, "remote", "add", "origin", "https://github.com/org/repo.git");
        runGit(repo, "remote", "add", "upstream", "https://github.com/org/repo.git");

        var config = new SpawnConfig();
        config.setHostPaths(List.of(base.toString()));

        var result = GitRemoteUtils.findHostReposByUrl(config, "https://github.com/org/repo.git");
        assertEquals(1, result.size(), "Repo should appear only once even with multiple matching remotes");
    }

    @Test
    void findHostReposByUrlIncludesRepoPaths() throws IOException, InterruptedException {
        var external = tempDir.resolve("elsewhere/myrepo");
        Files.createDirectories(external);
        runGit(external, "init");
        runGit(external, "remote", "add", "origin", "https://github.com/org/myrepo.git");

        var config = new SpawnConfig();
        config.setRepoPaths(Map.of("myrepo", external.toString()));

        var result = GitRemoteUtils.findHostReposByUrl(config, "https://github.com/org/myrepo.git");
        assertEquals(1, result.size());
        assertEquals(external, result.get(0));
    }

    private void initGitRepoWithRemote(Path dir, String remoteName, String remoteUrl)
            throws IOException, InterruptedException {
        runGit(dir, "init");
        runGit(dir, "remote", "add", remoteName, remoteUrl);
    }
}
