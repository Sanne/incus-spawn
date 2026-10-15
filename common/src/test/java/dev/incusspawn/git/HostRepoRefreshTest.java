package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.BuildOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HostRepoRefreshTest {

    // ── deduplicateByUrl ──────────────────────────────────────────────────

    @Test
    void deduplicateByUrlRemovesDuplicates() {
        var repos = List.of(
                repoEntry("https://github.com/org/repo.git"),
                repoEntry("https://github.com/org/repo"),
                repoEntry("https://github.com/org/other.git")
        );
        var result = HostRepoRefresh.deduplicateByUrl(repos);
        assertEquals(2, result.size());
        assertEquals("https://github.com/org/repo.git", result.get(0).getUrl());
        assertEquals("https://github.com/org/other.git", result.get(1).getUrl());
    }

    @Test
    void deduplicateByUrlHandlesSshAndHttps() {
        var repos = List.of(
                repoEntry("https://github.com/org/repo.git"),
                repoEntry("git@github.com:org/repo.git")
        );
        var result = HostRepoRefresh.deduplicateByUrl(repos);
        assertEquals(1, result.size());
    }

    @Test
    void deduplicateByUrlPreservesOrder() {
        var repos = List.of(
                repoEntry("https://github.com/org/c.git"),
                repoEntry("https://github.com/org/a.git"),
                repoEntry("https://github.com/org/b.git")
        );
        var result = HostRepoRefresh.deduplicateByUrl(repos);
        assertEquals(3, result.size());
        assertEquals("https://github.com/org/c.git", result.get(0).getUrl());
        assertEquals("https://github.com/org/a.git", result.get(1).getUrl());
        assertEquals("https://github.com/org/b.git", result.get(2).getUrl());
    }

    @Test void hostSourcesAreNeverRefreshedEvenWithAGlobalMapping(@TempDir Path dir) {
        var repo = new ImageDef.RepoEntry();
        repo.setHostSource(dir.resolve("private").toString());
        var config = new SpawnConfig();
        config.setRepoPaths(Map.of("private", repo.getHostSource()));
        assertTrue(HostRepoRefresh.deduplicateByUrl(List.of(repo)).isEmpty());
        var output = new ArrayList<String>();
        HostRepoRefresh.refresh(List.of(repo), Map.of(), config, true, output::add);
        assertTrue(output.isEmpty());
        assertFalse(java.nio.file.Files.exists(repo.hostSourcePath()));
    }

    // ── resolveCloneTarget ────────────────────────────────────────────────

    @Test
    void resolveCloneTargetUsesRepoPathsOverride(@TempDir Path tempDir) {
        var config = new SpawnConfig();
        config.setHostPaths(List.of(tempDir.resolve("default").toString()));
        config.setRepoPaths(Map.of("myrepo", tempDir.resolve("custom/myrepo").toString()));

        var target = HostRepoRefresh.resolveCloneTarget("myrepo", config);
        assertEquals(tempDir.resolve("custom/myrepo"), target);
    }

    @Test
    void resolveCloneTargetFallsBackToFirstHostPath(@TempDir Path tempDir) {
        var config = new SpawnConfig();
        config.setHostPaths(List.of(tempDir.toString()));
        config.setRepoPaths(Map.of());

        var target = HostRepoRefresh.resolveCloneTarget("myrepo", config);
        assertEquals(tempDir.resolve("myrepo"), target);
    }

    @Test
    void resolveCloneTargetReturnsNullWhenNoHostPaths() {
        var config = new SpawnConfig();
        config.setHostPaths(List.of());
        config.setRepoPaths(Map.of());

        var target = HostRepoRefresh.resolveCloneTarget("myrepo", config);
        assertNull(target);
    }

    @Test
    void resolveCloneTargetWorksWithRepoPathsOnly(@TempDir Path tempDir) {
        var config = new SpawnConfig();
        config.setHostPaths(List.of());
        config.setRepoPaths(Map.of("myrepo", tempDir.resolve("repos/myrepo").toString()));

        var target = HostRepoRefresh.resolveCloneTarget("myrepo", config);
        assertEquals(tempDir.resolve("repos/myrepo"), target);
    }

    // ── collectAllRepos ───────────────────────────────────────────────────

    @Test
    void collectAllReposIncludesAncestors() {
        var child = new ImageDef();
        child.setName("child");
        child.setParent("parent");
        child.setRepos(List.of(repoEntry("https://github.com/org/child-repo.git")));

        var parent = new ImageDef();
        parent.setName("parent");
        parent.setRepos(List.of(repoEntry("https://github.com/org/parent-repo.git")));

        var defs = Map.of("child", child, "parent", parent);
        var repos = HostRepoRefresh.collectAllRepos(child, defs);

        assertEquals(2, repos.size());
        assertEquals("https://github.com/org/child-repo.git", repos.get(0).getUrl());
        assertEquals("https://github.com/org/parent-repo.git", repos.get(1).getUrl());
    }

    @Test
    void collectAllReposFromMultipleTemplates() {
        var a = new ImageDef();
        a.setName("a");
        a.setRepos(List.of(repoEntry("https://github.com/org/repo-a.git")));

        var b = new ImageDef();
        b.setName("b");
        b.setRepos(List.of(repoEntry("https://github.com/org/repo-b.git")));

        var defs = Map.of("a", a, "b", b);
        var repos = HostRepoRefresh.collectAllRepos(List.of(a, b), defs);

        assertEquals(2, repos.size());
    }

    @Test
    void aFailureQuotesGitsOutputUnderIsxsWarning() {
        var task = new HostRepoRefresh.FetchTask("myrepo", Path.of("/tmp/myrepo"), "origin", null, null);
        var progress = new HostRepoRefresh.TaskProgress(HostRepoRefresh.FetchState.FAILED, null,
                GitCommandException.details("isx ran Git without prompts.", "fatal: could not read Username"));
        assertTrue(HostRepoRefresh.formatFetchLine(task, progress, 0).contains("details below"));
        var in = BuildOutput.STEP_INDENT;
        assertEquals(String.join("\n",
                in + "Warning: fetch failed for myrepo at /tmp/myrepo; keeping the existing checkout",
                in + "  isx ran Git without prompts.",
                in + "  Git's output:",
                in + "    | fatal: could not read Username"), HostRepoRefresh.plainFetchLine(task, progress));
    }

    // ── formatFetchLine ────────────────────────────────────────────────────

    @Test
    void formatFetchLineShowsSpinnerWhenFetching() {
        var task = new HostRepoRefresh.FetchTask("myrepo", Path.of("/tmp/myrepo"), "origin", null, null);
        var progress = new HostRepoRefresh.TaskProgress(HostRepoRefresh.FetchState.FETCHING, null);
        var line = HostRepoRefresh.formatFetchLine(task, progress, 0);
        assertTrue(line.contains("Fetching"), "should contain 'Fetching'");
        assertTrue(line.contains("myrepo"), "should contain repo name");
        assertTrue(line.contains("/tmp/myrepo"), "should contain path");
    }

    @Test
    void formatFetchLineShowsCheckmarkWhenDone() {
        var task = new HostRepoRefresh.FetchTask("myrepo", Path.of("/tmp/myrepo"), "origin", null, null);
        var progress = new HostRepoRefresh.TaskProgress(HostRepoRefresh.FetchState.DONE, null);
        var line = HostRepoRefresh.formatFetchLine(task, progress, 0);
        assertTrue(line.contains("Fetched"), "should contain 'Fetched'");
        assertTrue(line.contains("✓"), "should contain checkmark");
    }

    @Test
    void formatFetchLineShowsErrorDetailWhenFailed() {
        var task = new HostRepoRefresh.FetchTask("myrepo", Path.of("/tmp/myrepo"), "origin", null, null);
        var progress = new HostRepoRefresh.TaskProgress(HostRepoRefresh.FetchState.FAILED, "stalled: no output for 5m");
        var line = HostRepoRefresh.formatFetchLine(task, progress, 0);
        assertTrue(line.contains("Failed"), "should contain 'Failed'");
        assertTrue(line.contains("✗"), "should contain cross");
        assertTrue(line.contains("stalled: no output for 5m"), "should contain isx's reason");
    }

    @Test
    void formatFetchLineAlignsPrefixAcrossStates() {
        var task = new HostRepoRefresh.FetchTask("myrepo", Path.of("/tmp/myrepo"), "origin", null, null);
        var fetching = HostRepoRefresh.formatFetchLine(task,
                new HostRepoRefresh.TaskProgress(HostRepoRefresh.FetchState.FETCHING, null), 0);
        var done = HostRepoRefresh.formatFetchLine(task,
                new HostRepoRefresh.TaskProgress(HostRepoRefresh.FetchState.DONE, null), 0);
        var failed = HostRepoRefresh.formatFetchLine(task,
                new HostRepoRefresh.TaskProgress(HostRepoRefresh.FetchState.FAILED, null), 0);

        int fetchingIdx = stripAnsi(fetching).indexOf("myrepo");
        int doneIdx = stripAnsi(done).indexOf("myrepo");
        int failedIdx = stripAnsi(failed).indexOf("myrepo");
        assertEquals(fetchingIdx, doneIdx, "repo name should align between FETCHING and DONE");
        assertEquals(doneIdx, failedIdx, "repo name should align between DONE and FAILED");
    }

    @Test
    void formatFetchLineCyclesSpinnerFrames() {
        var task = new HostRepoRefresh.FetchTask("myrepo", Path.of("/tmp/myrepo"), "origin", null, null);
        var progress = new HostRepoRefresh.TaskProgress(HostRepoRefresh.FetchState.FETCHING, null);
        var line0 = stripAnsi(HostRepoRefresh.formatFetchLine(task, progress, 0));
        var line3 = stripAnsi(HostRepoRefresh.formatFetchLine(task, progress, 3));
        assertNotEquals(line0, line3, "different frames should produce different spinner chars");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static String stripAnsi(String s) {
        return s.replaceAll("\033\\[[0-9;]*m", "");
    }

    private static ImageDef.RepoEntry repoEntry(String url) {
        var entry = new ImageDef.RepoEntry();
        entry.setUrl(url);
        return entry;
    }

    @Test
    void projectLocalReposAreNeverRefreshedOnTheHost() {
        var parent = new ImageDef();
        parent.setName("tpl-user");
        var trustedRepo = new ImageDef.RepoEntry();
        trustedRepo.setUrl("https://github.com/org/trusted.git");
        parent.setRepos(List.of(trustedRepo));

        var child = new ImageDef();
        child.setName("tpl-project");
        child.setParent("tpl-user");
        child.setProjectRoot(java.nio.file.Path.of("/work/repo"));
        var localRepo = new ImageDef.RepoEntry();
        localRepo.setUrl("file:///home/me/private");
        child.setRepos(List.of(localRepo));

        var repos = HostRepoRefresh.collectAllRepos(child, Map.of("tpl-user", parent, "tpl-project", child));
        assertEquals(List.of("https://github.com/org/trusted.git"), repos.stream().map(ImageDef.RepoEntry::getUrl).toList());
    }
}
