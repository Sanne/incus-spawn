package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.git.GitTestUtils.runGit;
import static org.junit.jupiter.api.Assertions.*;

class HostRepoSourceTest {
    @TempDir Path tempDir;

    private Path repository(String name) throws Exception {
        var dir = Files.createDirectories(tempDir.resolve(name));
        runGit(dir, "init", "-b", "feature/local");
        runGit(dir, "config", "user.name", "Test");
        runGit(dir, "config", "user.email", "test@example.com");
        Files.writeString(dir.resolve("tracked"), "committed\n");
        runGit(dir, "add", "tracked");
        runGit(dir, "commit", "-m", "Unpushed local commit");
        return dir;
    }

    private String url(Path repo) { return repo.resolve(".git").toUri().toString(); }

    private void shell(String script) throws Exception {
        var process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private Path cloneRepo(Path source, String name) throws Exception {
        var destination = tempDir.resolve(name);
        var snapshot = HostRepoSource.capture(url(source));
        shell(snapshot.cloneScript(source.resolve(".git").toString(), destination.toString()));
        return destination;
    }

    @Test
    void preservesHeadRefsRemotesAndTrackingWithoutFetchingOrCopyingDirtyFiles() throws Exception {
        var source = repository("Host project 'with spaces'");
        var head = runGit(source, "rev-parse", "HEAD").strip();
        runGit(source, "remote", "add", "origin", "https://invalid.example/fork.git");
        runGit(source, "remote", "add", "upstream", "git@invalid.example:upstream.git");
        runGit(source, "config", "--add", "remote.origin.url", "https://invalid.example/second.git");
        runGit(source, "config", "--add", "remote.origin.pushurl", "ssh://git@invalid.example/push.git");
        runGit(source, "config", "--add", "remote.origin.pushurl", "ssh://git@invalid.example/second-push.git");
        runGit(source, "config", "remote.origin.tagOpt", "--no-tags");
        runGit(source, "update-ref", "refs/remotes/upstream/main", head);
        runGit(source, "symbolic-ref", "refs/remotes/upstream/HEAD", "refs/remotes/upstream/main");
        runGit(source, "config", "branch.feature/local.remote", "upstream");
        runGit(source, "config", "branch.feature/local.merge", "refs/heads/main");
        runGit(source, "branch", "other");
        runGit(source, "config", "uploadpack.hideRefs", "refs/heads/other");
        runGit(source, "tag", "local-tag");
        Files.writeString(source.resolve("tracked"), "dirty\n");
        runGit(source, "add", "tracked");
        Files.writeString(source.resolve("untracked"), "not copied\n");
        var status = runGit(source, "status", "--porcelain");
        var snapshot = HostRepoSource.capture(url(source));

        var destination = cloneRepo(source, "container project");

        assertEquals(head, runGit(destination, "rev-parse", "HEAD").strip());
        assertEquals("feature/local", runGit(destination, "branch", "--show-current").strip());
        assertEquals(snapshot.refs(), runGit(destination, "for-each-ref", "--format=%(refname) %(objectname) %(symref)"));
        assertEquals(runGit(source, "config", "--null", "--get-regexp", "^(remote\\.|branch\\.)"),
                runGit(destination, "config", "--null", "--get-regexp", "^(remote\\.|branch\\.)"));
        assertEquals("committed\n", Files.readString(destination.resolve("tracked")));
        assertFalse(Files.exists(destination.resolve("untracked")));
        assertEquals("", runGit(destination, "status", "--porcelain"));
        assertEquals(status, runGit(source, "status", "--porcelain"));
        assertFalse(Files.exists(destination.resolve(".git/objects/info/alternates")));
        assertFalse(Files.isSameFile(source.resolve(".git/objects/" + head.substring(0, 2) + "/" + head.substring(2)),
                destination.resolve(".git/objects/" + head.substring(0, 2) + "/" + head.substring(2))));
        Files.move(source, tempDir.resolve("unavailable-source"));
        runGit(destination, "fsck", "--full");
    }

    @Test
    void preservesDetachedHeadThatIsNotReachableFromABranch() throws Exception {
        var source = repository("detached");
        runGit(source, "checkout", "--detach");
        runGit(source, "commit", "--allow-empty", "-m", "Detached commit");
        var destination = cloneRepo(source, "detached-copy");
        assertEquals(runGit(source, "rev-parse", "HEAD"), runGit(destination, "rev-parse", "HEAD"));
        assertEquals("", runGit(destination, "branch", "--show-current"));
        assertEquals("", runGit(destination, "remote"));
    }

    @Test
    void preservesUnbornBranchAndEmptyRepository() throws Exception {
        var source = Files.createDirectories(tempDir.resolve("empty"));
        runGit(source, "init", "-b", "not-main");
        var destination = cloneRepo(source, "empty-copy");
        assertEquals("not-main", runGit(destination, "branch", "--show-current").strip());
        assertEquals("", runGit(destination, "remote"));
    }

    @Test
    void readsRemoteConfigIncludes() throws Exception {
        var source = repository("includes");
        Files.writeString(source.resolve(".git/remotes.cfg"), "[remote \"upstream\"]\n\turl = https://invalid.example/repo.git\n\tfetch = +refs/heads/*:refs/remotes/upstream/*\n\tskipDefaultUpdate\n");
        runGit(source, "config", "include.path", "remotes.cfg");
        var destination = cloneRepo(source, "includes-copy");
        assertEquals("https://invalid.example/repo.git", runGit(destination, "remote", "get-url", "upstream").strip());
        assertEquals("true", runGit(destination, "config", "--bool", "remote.upstream.skipDefaultUpdate").strip());
        assertFalse(Files.exists(destination.resolve(".git/remotes.cfg")));
    }

    @Test
    void parsesFileUrisWithoutLosingPathCaseOrEscaping() {
        var source = tempDir.resolve("Some Project #1/.git");
        var url = source.toUri().toString();
        assertEquals(source, HostRepoSource.path(url));
        assertEquals("Some Project #1", GitRemoteUtils.repoNameFromUrl(url));
        assertFalse(GitRemoteUtils.urlsMatch("file:///host/Repo/.git", "file:///host/repo/.git"));
        for (var invalid : List.of("file:relative/.git", "file://remote/repo/.git", "file:///repo/.git?x", "file:///repo/.git#x")) {
            assertThrows(IllegalArgumentException.class, () -> HostRepoSource.path(invalid));
        }
    }

    @Test
    void rejectsMissingWorktreeAndExternalObjectSources() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture("file:///nonexistent/.git"));
        var source = repository("source");
        Files.writeString(source.resolve(".git/objects/info/alternates"), "/missing/objects\n");
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture(url(source)));
        Files.delete(source.resolve(".git/objects/info/alternates"));
        runGit(source, "config", "remote.origin.promisor", "true");
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture(url(source)));
        runGit(source, "config", "--unset", "remote.origin.promisor");
        runGit(source, "worktree", "add", tempDir.resolve("worktree").toString());
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.capture(url(tempDir.resolve("worktree"))));
    }

    @Test
    void fingerprintTracksHeadBranchRefsAndRemotesButNotDirtyFiles() throws Exception {
        var source = repository("fingerprint");
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url(source));
        assertEquals("~/fingerprint", repo.getPath());
        var image = new ImageDef();
        image.setRepos(List.of(repo));
        var snapshot = HostRepoSource.capture(url(source));
        var initial = image.contentFingerprint(Map.of());
        Files.writeString(source.resolve("tracked"), "dirty\n");
        assertEquals(initial, image.contentFingerprint(Map.of()));
        runGit(source, "commit", "--allow-empty", "-m", "Another commit");
        var newHead = image.contentFingerprint(Map.of());
        assertNotEquals(initial, newHead);
        assertEquals(initial, image.contentFingerprint(Map.of(), Map.of(url(source), snapshot.fingerprint())));
        runGit(source, "checkout", "-b", "another-branch");
        var newBranch = image.contentFingerprint(Map.of());
        assertNotEquals(newHead, newBranch);
        runGit(source, "remote", "add", "origin", "https://invalid.example/repo");
        assertNotEquals(newBranch, image.contentFingerprint(Map.of()));
        Files.move(source, tempDir.resolve("moved"));
        assertNotEquals(initial, image.contentFingerprint(Map.of()));
    }

    @Test
    void automaticMaintenanceDoesNotFetchHostOnlyRepositories() throws Exception {
        var source = repository("maintenance");
        runGit(source, "remote", "add", "origin", "https://invalid.example/repo");
        var destination = cloneRepo(source, "maintenance-copy");
        var script = GitRemoteUtils.automaticFetchScript().replace("~/*/", "'" + destination + "/'");
        shell(script);
        assertFalse(Files.exists(destination.resolve(".git/FETCH_HEAD")));

        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url(source));
        var config = new SpawnConfig();
        runGit(source, "remote", "add", "local-source", url(source));
        config.setHostPaths(List.of(tempDir.toString()));
        config.setRepoPaths(Map.of("maintenance", source.toString()));
        var messages = new java.util.ArrayList<String>();
        HostRepoRefresh.refresh(List.of(repo), config, true, messages::add);
        assertTrue(messages.isEmpty());
        assertFalse(Files.exists(source.resolve(".git/FETCH_HEAD")));
    }
}
