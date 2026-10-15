package dev.incusspawn.git;

import dev.incusspawn.config.GitCacheConfig;
import dev.incusspawn.config.GitTransferConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static dev.incusspawn.git.GitTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class HostRepoPreparationTest {
    @TempDir Path tmp;

    private GitPreparation preparation() throws Exception {
        return new GitPreparation(tmp.resolve("cache"), GitCacheConfig.DEFAULT, GitTransferConfig.DEFAULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sha1", "sha256"})
    void exportsExactlyLocalBranchAndTagClosureWithoutChangingSource(String format) throws Exception {
        var host = tmp.resolve("host");
        git(tmp, "init", "-q", "-b", "main", "--object-format=" + format, host.toString());
        commitBinary(host, "base");
        git(host, "checkout", "-q", "-b", "unpushed");
        commitBinary(host, "unpushed branch");
        var unpushed = git(host, "rev-parse", "HEAD");
        git(host, "-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "release", "-m", "release");
        git(host, "checkout", "-q", "main");
        commitBinary(host, "reflog only");
        git(host, "reset", "--hard", "HEAD~1");
        Files.writeString(host.resolve("stash-only"), "private stash");
        git(host, "add", ".");
        git(host, "-c", "user.name=t", "-c", "user.email=t@t", "stash", "push");
        Files.writeString(host.resolve("staged"), "private index");
        git(host, "add", ".");
        Files.writeString(host.resolve("untracked"), "private working file");
        // A remote-tracking-only commit must not be exported either.
        git(host, "update-ref", "refs/remotes/origin/secret", git(host, "rev-parse", "refs/stash"));
        git(host, "remote", "add", "origin", "ssh://corporate.invalid/private");
        git(host, "config", "core.sshCommand", "touch " + tmp.resolve("contacted") + "; false");
        Files.writeString(host.resolve(".git/hooks/post-checkout"), "#!/bin/sh\ntouch " + tmp.resolve("hook-ran") + "\n");
        host.resolve(".git/hooks/post-checkout").toFile().setExecutable(true);
        // Mix selected and excluded objects in packs; a raw object-store copy would fail the assertion.
        git(host, "repack", "-a", "-d");
        var expected = git(host, "rev-list", "--objects", "--no-object-names", "--branches", "--tags")
                .lines().collect(java.util.stream.Collectors.toSet());
        var before = inventory(host);
        Path preparedPath;
        try (var prepared = preparation().prepareHost(host, "release", phase -> {})) {
            preparedPath = prepared.repository();
            var actual = git(preparedPath, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)")
                    .lines().collect(java.util.stream.Collectors.toSet());
            assertEquals(expected, actual);
            assertEquals(unpushed, git(preparedPath, "rev-parse", "refs/heads/unpushed"));
            assertEquals("", git(preparedPath, "for-each-ref", "refs/stash", "refs/remotes/"));
            assertEquals("", git(preparedPath, "remote"));
            assertFalse(Files.exists(preparedPath.resolve("hooks")));
            assertFalse(Files.exists(preparedPath.resolve("objects/info/alternates")));
            assertEquals(before, inventory(host), "build must leave every source byte unchanged");
            PreparationGit.delete(host);
            assertEquals("", git(preparedPath, "fsck", "--full", "--no-dangling"));
        }
        assertFalse(Files.exists(preparedPath));
        assertFalse(Files.exists(tmp.resolve("contacted")));
        assertFalse(Files.exists(tmp.resolve("hook-ran")));
    }

    @Test void supportsWorktreesAndBareRepositories() throws Exception {
        var host = releasesFixture(tmp);
        var worktree = tmp.resolve("worktree");
        git(host, "worktree", "add", worktree.toString(), "stable");
        var bare = tmp.resolve("bare.git");
        git(tmp, "clone", "--bare", host.toString(), bare.toString());
        for (var source : java.util.List.of(worktree, bare)) {
            try (var prepared = preparation().prepareHost(source, null, phase -> {})) {
                assertEquals(git(source, "rev-parse", "HEAD"), git(prepared.repository(), "rev-parse", "HEAD"));
                assertEquals("", git(prepared.repository(), "fsck", "--full", "--no-dangling"));
            }
        }
    }

    @Test void detachedUnexportedHeadNeedsAnExplicitBranchOrTag() throws Exception {
        var host = releasesFixture(tmp);
        git(host, "checkout", "--detach");
        commitBinary(host, "detached private");
        assertThrows(java.io.IOException.class, () -> preparation().prepareHost(host, null, phase -> {}));
        try (var prepared = preparation().prepareHost(host, "main", phase -> {})) {
            assertEquals(git(host, "rev-parse", "main"), git(prepared.repository(), "rev-parse", "HEAD"));
        }
        assertThrows(java.io.IOException.class, () -> preparation().prepareHost(host, "missing", phase -> {}));
    }

    @Test void refusesShallowSourcesWithoutContactingTheirRemote() throws Exception {
        var host = releasesFixture(tmp);
        var shallow = tmp.resolve("shallow");
        git(tmp, "clone", "--depth=1", host.toUri().toString(), shallow.toString());
        git(shallow, "remote", "set-url", "origin", "ssh://corporate.invalid/private");
        assertTrue(assertThrows(java.io.IOException.class,
                () -> preparation().prepareHost(shallow, null, phase -> {})).getMessage().contains("shallow"));
    }

    @Test void missingPromisorObjectsFailWithoutLazyFetching() throws Exception {
        var host = releasesFixture(tmp);
        var blob = git(host, "rev-parse", "HEAD:isx.deb");
        Files.delete(host.resolve(".git/objects").resolve(blob.substring(0, 2)).resolve(blob.substring(2)));
        git(host, "remote", "add", "origin", "ssh://corporate.invalid/private");
        git(host, "config", "remote.origin.promisor", "true");
        git(host, "config", "core.sshCommand", "touch " + tmp.resolve("contacted") + "; false");
        assertThrows(java.io.IOException.class, () -> preparation().prepareHost(host, null, phase -> {}));
        assertFalse(Files.exists(tmp.resolve("contacted")));
    }

    @Test void stashContentsReachableFromAnExportedTagAreIncluded() throws Exception {
        var host = releasesFixture(tmp);
        Files.writeString(host.resolve("shared"), "this stash is explicitly published by a tag");
        git(host, "add", ".");
        git(host, "-c", "user.name=t", "-c", "user.email=t@t", "stash", "push");
        git(host, "tag", "shared-stash", "refs/stash");
        var stash = git(host, "rev-parse", "refs/stash");
        try (var prepared = preparation().prepareHost(host, null, phase -> {})) {
            assertEquals(stash, git(prepared.repository(), "rev-parse", "shared-stash"));
            assertEquals("this stash is explicitly published by a tag", git(prepared.repository(), "show", "shared-stash:shared"));
            assertEquals("", git(prepared.repository(), "for-each-ref", "refs/stash"));
        }
    }

    private static Map<String, String> inventory(Path root) throws Exception {
        var result = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path).toString(), PreparationGit.hash(path));
            }
        }
        return result;
    }
}
