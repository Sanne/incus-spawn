package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
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

    // ── helpers ───────────────────────────────────────────────────────────

    private static ImageDef.RepoEntry repoEntry(String url) {
        var entry = new ImageDef.RepoEntry();
        entry.setUrl(url);
        return entry;
    }
}
