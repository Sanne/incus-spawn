package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

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

    private HostRepoSource.Snapshot capture(String url) {
        return HostRepoSource.capture(url, null, tempDir);
    }

    private void shell(String script) throws Exception {
        var process = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private Path cloneRepo(Path source, String name) throws Exception {
        var destination = tempDir.resolve(name);
        var snapshot = capture(source.toString());
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
        runGit(source, "config", "--add", "remote.origin.pushurl", "ssh://invalid.example/push.git");
        runGit(source, "config", "--add", "remote.origin.pushurl", "ssh://invalid.example/second-push.git");
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
        var snapshot = capture(url(source));

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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://user:secret@example.com/repo.git | https://example.com/repo.git",
            "https://secret@example.com/repo.git | https://example.com/repo.git",
            "HTTPS://user:p%40ss@example.com:8443/a%20b.git | HTTPS://example.com:8443/a%20b.git",
            "https://user:p@ss@[::1]:8443/repo.git | https://[::1]:8443/repo.git",
            "ssh://user:secret@example.com/repo.git | ssh://example.com/repo.git",
            "https://example.com/repo@rev.git | https://example.com/repo@rev.git",
            "git@example.com:repo.git | git@example.com:repo.git"
    })
    void stripsUrlUserinfoBeforeSnapshotAndClone(String original, String sanitized) throws Exception {
        var source = repository("credentials");
        runGit(source, "remote", "add", "origin", original);
        runGit(source, "config", "--add", "remote.origin.url", "https://other:secret@example.com/second.git");
        // Included push URLs and multiple values must pass through the same sanitization.
        Files.writeString(source.resolve(".git/remotes.cfg"), "[remote \"origin\"]\n\tpushurl = " + original
                + "\n\tpushurl = https://other:secret@example.com/push.git\n");
        runGit(source, "config", "include.path", "remotes.cfg");
        runGit(source, "config", "remote.origin.proxy", "http://proxy-user:secret@example.com:8080");
        var snapshot = capture(url(source));
        assertEquals(List.of(sanitized, "https://example.com/second.git"), snapshot.config().stream()
                .filter(entry -> entry.key().equals("remote.origin.url")).map(HostRepoSource.ConfigEntry::value).toList());
        assertFalse(snapshot.cloneScript("/mnt/source", "/copy").contains("secret"));

        var destination = cloneRepo(source, "credentials-copy");
        assertEquals(sanitized + "\nhttps://example.com/second.git\n",
                runGit(destination, "config", "--get-all", "remote.origin.url"));
        assertEquals(sanitized + "\nhttps://example.com/push.git\n",
                runGit(destination, "config", "--get-all", "remote.origin.pushurl"));
        assertEquals(original + "\nhttps://other:secret@example.com/second.git\n",
                runGit(source, "config", "--get-all", "remote.origin.url"));
        assertFalse(Files.readString(destination.resolve(".git/config")).contains("secret"));
        assertEquals("http://example.com:8080\n", runGit(destination, "config", "remote.origin.proxy"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../repo", "./../repo", "file:../repo", "file://../repo",
            "file:./%2e%2e/repo", "file://./%2e%2e/repo"})
    void parentPathsResolveAgainstTemplateDirectoryWithinRoot(String url) throws Exception {
        var source = repository("root/repo");
        var root = tempDir.resolve("root");
        var templates = Files.createDirectory(root.resolve("images"));
        assertEquals(source.resolve(".git").toRealPath(), HostRepoSource.capture(url, templates, root).gitDirectory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../..", "file:../..", "file://../.."})
    void projectTemplateCanUseTheEnclosingRepository(String url) throws Exception {
        var project = repository("project");
        var templates = Files.createDirectories(project.resolve(".incus-spawn/images"));
        assertEquals(project.resolve(".git").toRealPath(),
                HostRepoSource.capture(url, templates, project).gitDirectory());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{absolute}", "file:{absolute}", "file://{absolute}",
            "~/root-other/repo", "file:~/root-other/repo", "file://~/root-other/repo",
            "../../root-other/repo", "file:../../root-other/repo", "file://../../root-other/repo",
            "file:../%2e%2e/root-other/repo"})
    void allLocalFormsRejectNormalizedPathsOutsideRoot(String form) throws Exception {
        var source = repository("root-other/repo");
        var root = Files.createDirectory(tempDir.resolve("root"));
        var templates = Files.createDirectory(root.resolve("images"));
        var originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            for (var suffix : List.of("", "/.git")) {
                var url = form.replace("{absolute}", source.toString()) + suffix;
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> HostRepoSource.capture(url, templates, root));
                assertTrue(failure.getMessage().contains("normalized-path rule failed"), failure.getMessage());
                assertTrue(failure.getMessage().contains("allowed root: " + root), failure.getMessage());
            }
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void rejectsSymlinkEscapesIncludingGitDirectoryAndSymlinkBeforeParent() throws Exception {
        var outside = repository("outside/repo");
        var root = Files.createDirectory(tempDir.resolve("root"));
        var templates = Files.createDirectory(root.resolve("images"));
        Files.createSymbolicLink(templates.resolve("linked"), outside);
        var inside = Files.createDirectory(templates.resolve("inside"));
        Files.createSymbolicLink(inside.resolve(".git"), outside.resolve(".git"));
        Files.createSymbolicLink(templates.resolve("via"), outside);
        // Lexically images/via/../repo is in the root; following via first reaches outside/repo.
        for (var path : List.of(templates.resolve("linked"), inside, templates.resolve("via/../repo"))) {
            for (var url : List.of(path.toString(), path.toUri().toString(), templates.relativize(path).toString())) {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> HostRepoSource.capture(url, templates, root));
                assertTrue(failure.getMessage().contains("canonical-path rule failed"), failure.getMessage());
                assertTrue(failure.getMessage().contains("allowed root: " + root), failure.getMessage());
            }
        }
    }

    @Test
    void canonicalizesAllowedSymlinksAndRootAndRequiresProvenance() throws Exception {
        var source = repository("root/repo");
        var root = tempDir.resolve("root");
        var rootLink = Files.createSymbolicLink(tempDir.resolve("root-link"), root);
        Files.createSymbolicLink(root.resolve("repo-link"), source);
        assertEquals(source.resolve(".git").toRealPath(),
                HostRepoSource.capture("repo-link", rootLink, rootLink).gitDirectory());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> HostRepoSource.capture(source.toString(), root, null));
        assertTrue(failure.getMessage().contains("no allowed root"));
        failure = assertThrows(IllegalArgumentException.class,
                () -> HostRepoSource.capture("repo", null, root));
        assertTrue(failure.getMessage().contains("relative-path rule failed"));
        assertTrue(failure.getMessage().contains(root.toString()));
    }

    @Test
    void parsesFileUrisWithoutLosingPathCaseOrEscaping() {
        var source = tempDir.resolve("Some Project #1/.git");
        var url = source.toUri().toString();
        assertEquals(source, HostRepoSource.path(url));
        assertEquals("Some Project #1", GitRemoteUtils.repoNameFromUrl(url));
        assertFalse(GitRemoteUtils.urlsMatch("file:///host/Repo/.git", "file:///host/repo/.git"));
        for (var invalid : List.of("file://remote/repo/.git", "file:///repo/.git?x", "file:///repo/.git#x")) {
            assertThrows(IllegalArgumentException.class, () -> HostRepoSource.path(invalid));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file://{absolute}", "file:{absolute}", "{absolute}",
            "file://~/templates/repo", "file:~/templates/repo", "~/templates/repo",
            "file://repo", "file:repo", "file://./repo", "file:./repo", "repo", "./repo",
            "file:dir/repo", "file://./dir/repo", "file:./dir/repo", "dir/repo", "./dir/repo"
    })
    void clonesLocalFormsFromTheirDefiningTemplate(String form) throws Exception {
        var originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            var relative = form.contains("dir/repo") ? "dir/repo" : "repo";
            var source = repository("templates/" + relative);
            var template = tempDir.resolve("templates/image.yaml");
            var url = form.replace("{absolute}", source.toString());
            // A bare file://name is the single-component shorthand, not a host/path URL.
            for (var suffix : form.equals("file://repo") ? List.of("") : List.of("", "/.git")) {
                Files.writeString(template, "name: tpl-local\nrepos:\n  - url: '" + url + suffix + "'\n");
                var image = ImageDef.parseFile(template);
                image.setHostRepoRoot(tempDir);
                var repo = image.getRepos().getFirst();
                assertEquals("~/repo", repo.getPath());
                assertTrue(HostRepoSource.isHostOnly(repo.getUrl()));
                var snapshot = HostRepoSource.capture(repo);
                assertEquals(source.resolve(".git").toRealPath(), snapshot.gitDirectory());
                var destination = tempDir.resolve("copy-" + (suffix.isEmpty() ? "worktree" : "gitdir"));
                Files.writeString(source.resolve("tracked"), "dirty\n");
                Files.writeString(source.resolve("untracked"), "private\n");
                shell(snapshot.cloneScript(snapshot.gitDirectory().toString(), destination.toString()));
                assertEquals("committed\n", Files.readString(destination.resolve("tracked")));
                assertFalse(Files.exists(destination.resolve("untracked")));
                assertEquals(runGit(source, "rev-parse", "HEAD"), runGit(destination, "rev-parse", "HEAD"));
                assertEquals("true", runGit(destination, "config", HostRepoSource.CONFIG_KEY).strip());

                var stored = new BuildSource(Map.of(image.getName(), image), Map.of(), Map.of(),
                        Map.of(image.getName(), image.getSource()));
                var restored = BuildSource.fromJson(stored.toJson()).getDefinitions().get(image.getName());
                var restoredRepo = restored.getRepos().getFirst();
                assertEquals(snapshot, HostRepoSource.capture(restoredRepo));
                var before = restored.contentFingerprint(Map.of());
                runGit(source, "commit", "--allow-empty", "-m", "Another local commit");
                assertNotEquals(before, restored.contentFingerprint(Map.of()));
            }
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"file://files.com/path/to/repo", "file://localhost/repo", "file://host:22/repo",
            "file://user@host/repo", "file:////host/repo", "//host/repo",
            "file:repo?query", "file:repo#fragment",
            "file:repo%00", "file:", "file://", "file:~someone/repo",
            "file:%2Frepo", "file://%2Frepo", "file://host%2Frepo", "file://host%3A22"})
    void rejectsUnsafeOrAmbiguousLocalForms(String url) {
        assertThrows(IllegalArgumentException.class, () -> HostRepoSource.path(url));
    }

    @Test
    void supportsBareRepositoriesWithoutSearchingAncestorDirectories() throws Exception {
        var source = repository("repo");
        var bare = tempDir.resolve("bare.git");
        runGit(tempDir, "clone", "--bare", source.toString(), bare.toString());
        assertEquals(capture(source.toString()).head(), capture(bare.toString()).head());
        var subdir = Files.createDirectory(source.resolve("subdir"));
        assertThrows(IllegalArgumentException.class, () -> capture(subdir.toString()));
    }

    @Test
    void inheritedRepositoriesKeepTheirOwnTemplateDirectory() throws Exception {
        var parentRepo = repository("parent/repo");
        var childRepo = repository("child/repo");
        runGit(childRepo, "commit", "--allow-empty", "-m", "Different child HEAD");
        var parentFile = tempDir.resolve("parent/image.yaml");
        var childFile = tempDir.resolve("child/image.yaml");
        Files.writeString(parentFile, "name: tpl-parent\nrepos:\n  - url: repo\n");
        Files.writeString(childFile, "name: tpl-child\nparent: tpl-parent\nrepos:\n  - url: repo\n");
        var parent = ImageDef.parseFile(parentFile);
        var child = ImageDef.parseFile(childFile);
        parent.setHostRepoRoot(tempDir.resolve("parent"));
        child.setHostRepoRoot(tempDir.resolve("child"));
        var definitions = Map.of(parent.getName(), parent, child.getName(), child);
        var stored = new BuildSource(definitions, Map.of(), Map.of(),
                Map.of(parent.getName(), parent.getSource(), child.getName(), child.getSource()));
        var restored = BuildSource.fromJson(stored.toJson()).getDefinitions();
        var repos = HostRepoRefresh.collectAllRepos(restored.get(child.getName()), restored);
        assertEquals(List.of(childRepo.resolve(".git").toRealPath(), parentRepo.resolve(".git").toRealPath()), repos.stream()
                .map(repo -> HostRepoSource.gitDirectory(repo)).toList());
    }

    @Test
    void plainPathsKeepLiteralReservedCharactersAndRemoteProtocolsStayRemote() throws Exception {
        var source = repository("Some Project #1%+?");
        assertEquals(capture(source.toUri().toString()), capture(source.toString()));
        for (var remote : List.of("https://github.com/org/repo.git", "ssh://git@example.com/repo",
                "git@example.com:org/repo", "example.com:org/repo", "git://example.com/repo", "isx://branch/~/repo")) {
            assertFalse(HostRepoSource.isHostOnly(remote), remote);
        }
    }

    @Test
    void rejectsMissingWorktreeAndExternalObjectSources() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> capture("file:///nonexistent/.git"));
        var source = repository("source");
        Files.writeString(source.resolve(".git/objects/info/alternates"), "/missing/objects\n");
        assertThrows(IllegalArgumentException.class, () -> capture(url(source)));
        Files.delete(source.resolve(".git/objects/info/alternates"));
        runGit(source, "config", "remote.origin.promisor", "true");
        assertThrows(IllegalArgumentException.class, () -> capture(url(source)));
        runGit(source, "config", "--unset", "remote.origin.promisor");
        runGit(source, "worktree", "add", tempDir.resolve("worktree").toString());
        assertThrows(IllegalArgumentException.class, () -> capture(url(tempDir.resolve("worktree"))));
        assertThrows(IllegalArgumentException.class, () -> capture(tempDir.resolve("worktree").toString()));
    }

    @Test
    void fingerprintTracksHeadBranchRefsAndRemotesButNotDirtyFiles() throws Exception {
        var source = repository("fingerprint");
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url(source));
        assertEquals("~/fingerprint", repo.getPath());
        var image = new ImageDef();
        image.setRepos(List.of(repo));
        image.setHostRepoRoot(tempDir);
        var snapshot = capture(url(source));
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
        HostRepoRefresh.refresh(List.of(repo), java.util.Map.of(), config, true, messages::add);
        assertTrue(messages.isEmpty());
        assertFalse(Files.exists(source.resolve(".git/FETCH_HEAD")));
    }
}
