package dev.incusspawn.git;

import dev.incusspawn.config.GitCacheConfig;
import dev.incusspawn.config.GitTransferConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.incusspawn.git.GitTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.parallel.ExecutionMode.CONCURRENT;

// Each test owns its directories and Git daemon, and its time goes on starting Git processes, which
// a macOS runner does slowly: concurrently the class fits its job's timeout (#1839). The methods
// opt in, not the class: a concurrent class runs beside every other class in the module, and its
// forks then hold other tests' freshly written stub scripts open, failing their exec (#1846).
class GitPreparationTest {
    @TempDir Path tmp;
    private Path remote;
    private Path host;
    private Path cache;
    private GitPreparation preparation;
    private GitTestServer server;

    @BeforeEach void fixture() throws Exception {
        remote = releasesFixture(tmp);
        git(remote, "-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "v1", "HEAD~4", "-m", "first");
    }

    @org.junit.jupiter.api.AfterEach void stopServer() throws Exception { if (server != null) server.close(); }

    private void start() throws Exception {
        server = new GitTestServer(tmp);
        host = tmp.resolve("host");
        git(tmp, "clone", "-q", "--", server.url(remote), host.toString());
        git(host, "repack", "-a", "-d");
        cache = tmp.resolve("cache");
        preparation = new GitPreparation(cache, GitCacheConfig.DEFAULT, GitTransferConfig.DEFAULT);
    }

    private GitPreparation.Prepared prepare(Path checkout, String branch) throws IOException {
        return preparation.prepare(new GitPreparation.Request(server.url(remote), branch, checkout), s -> {});
    }

    @Execution(CONCURRENT) @Test void hostPathsAndFileUrlsCannotPublishPrivateBranches() throws Exception {
        git(remote, "branch", "unpushed-secret");
        var preparation = new GitPreparation(tmp.resolve("blocked-cache"), GitCacheConfig.DEFAULT, GitTransferConfig.DEFAULT);
        for (var url : List.of(remote.toString(), remote.toUri().toString(), "../private", "./private", "~/private",
                "C:/private", "ext::cat private", "file:private", "https::private")) {
            assertThrows(IOException.class, () -> preparation.prepare(new GitPreparation.Request(url, null, remote), s -> {}), url);
        }
        assertFalse(Files.exists(tmp.resolve("blocked-cache/work")), "reject before creating preparation work");
        assertFalse(Files.exists(tmp.resolve("blocked-cache/snapshots")));
    }

    @Execution(CONCURRENT)
    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/repo", "ssh://git@example.com/repo", "git://localhost:9418/repo",
            "git@example.com:owner/repo", "example.com:repo", "git@[::1]:repo"})
    void supportedRemoteSpellingsPassTransportValidation(String url) {
        assertTrue(GitPreparation.isRemoteUrl(url), url);
    }

    @Execution(CONCURRENT) @Test void fileTransportIsDeniedEvenAfterAnAllowedUrlIsRewritten() throws Exception {
        git(remote, "config", "url." + remote.toUri() + ".insteadOf", "https://example.invalid/repo");
        git(remote, "config", "protocol.file.allow", "always");
        assertThrows(IOException.class, () -> PreparationGit.git(remote, "ls-remote", "https://example.invalid/repo"));
    }

    @Execution(CONCURRENT) @Test void sshDefaultsToBatchModeAndRespectsExplicitTransports() {
        var environment = new java.util.HashMap<String, String>();
        PreparationGit.nonInteractiveSsh(environment, null, PreparationGit.SshScope.CHECKOUT);
        assertEquals("ssh -o BatchMode=yes", environment.get("GIT_SSH_COMMAND"));
        environment.put("GIT_SSH_COMMAND", "custom-ssh --identity test");
        PreparationGit.nonInteractiveSsh(environment, null, PreparationGit.SshScope.CHECKOUT);
        assertEquals("custom-ssh --identity test", environment.get("GIT_SSH_COMMAND"));
        environment.clear();
        environment.put("GIT_SSH", "/custom/ssh");
        PreparationGit.nonInteractiveSsh(environment, null, PreparationGit.SshScope.CHECKOUT);
        assertFalse(environment.containsKey("GIT_SSH_COMMAND"));
    }

    @Execution(CONCURRENT) @Test void cleanSnapshotsAreReusedWithoutChangingOrRequiringTheCheckout() throws Exception {
        start();
        var before = contents(host.resolve(".git"));
        try (var first = prepare(host, null)) {
            assertEquals("reference-seeded clean snapshot", first.strategy());
            sameObjects(remote, first.repository());
            assertEquals("ref: refs/heads/main", Files.readString(first.repository().resolve("HEAD")).strip());
            assertFalse(Files.exists(first.repository().resolve("objects/info/alternates")));
        }
        try (var second = prepare(tmp.resolve("nonexistent-checkout"), null)) {
            assertEquals("cached clean snapshot", second.strategy());
            assertEquals("", git(second.repository(), "fsck", "--full", "--no-dangling"));
            assertTrue(second.checksums().size() >= 2);
        }
        assertEquals(before, contents(host.resolve(".git")));
        assertEquals(1, PreparationGit.children(cache.resolve("snapshots")).size());
        assertEquals(List.of(), PreparationGit.children(cache.resolve("work")));
    }

    @Execution(CONCURRENT) @Test void remoteRefsReadEarlyFetchNothingAndAreUsedOnce() throws Exception {
        start();
        var url = server.url(remote);
        // Discarded, as when the guest's access check is denied: nothing fetched, its lease released.
        preparation.readRemote(url, s -> {}).close();
        assertFalse(Files.exists(cache.resolve("snapshots")) && !PreparationGit.children(cache.resolve("snapshots")).isEmpty());
        assertEquals(List.of(), PreparationGit.children(cache.resolve("work")));

        var other = preparation.readRemote(url, s -> {});
        assertThrows(IOException.class, () -> preparation.prepare(
                new GitPreparation.Request(url + "-other", null, null), other, s -> {}), "refs read for another URL");
        assertEquals(List.of(), PreparationGit.children(cache.resolve("work")), "a refused handoff releases its lease");

        try (var first = preparation.prepare(new GitPreparation.Request(url, null, null), preparation.readRemote(url, s -> {}), s -> {})) {
            assertEquals("new clean snapshot", first.strategy());
            assertTrue(first.timingsMillis().containsKey("remote"));
        }
        var refs = preparation.readRemote(url, s -> {});
        try (var second = preparation.prepare(new GitPreparation.Request(url, null, null), refs, s -> {})) {
            assertEquals("cached clean snapshot", second.strategy());
            refs.close(); // the snapshot's reader owns the lease now
            assertTrue(Files.isDirectory(second.repository()));
            assertThrows(IOException.class, () -> preparation.prepare(new GitPreparation.Request(url, null, null), refs, s -> {}));
        }
        assertEquals(List.of(), PreparationGit.children(cache.resolve("work")));
    }

    @Execution(CONCURRENT) @Test void anUnchangedRemoteWithAnotherRefEndingInHeadIsACacheHit() throws Exception {
        // ls-remote's HEAD pattern also matches refs/remotes/origin/HEAD, which mirrors advertise.
        git(remote, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/heads/stable");
        start();
        try (var first = prepare(null, null)) { assertEquals("new clean snapshot", first.strategy()); }
        try (var second = prepare(null, null)) { assertEquals("cached clean snapshot", second.strategy()); }
    }

    @Execution(CONCURRENT) @Test void mixedPacksExcludePrivateBranchesStashesAndUnreachableObjects() throws Exception {
        start();
        commitBinary(host, "private commit");
        var secret = git(host, "rev-parse", "HEAD");
        git(host, "tag", "private-tag");
        Files.writeString(host.resolve("secret"), "private staged blob");
        git(host, "add", "secret");
        var staged = git(host, "rev-parse", ":secret");
        git(host, "-c", "user.name=t", "-c", "user.email=t@t", "stash", "-q");
        var stash = git(host, "rev-parse", "refs/stash");
        git(host, "repack", "-a", "-d");
        var before = contents(host.resolve(".git"));
        for (int i = 0; i < 2; i++) {
            try (var result = prepare(host, null)) {
                assertEquals(i == 0 ? "reference-seeded clean snapshot" : "cached clean snapshot", result.strategy());
                sameObjects(remote, result.repository());
                var objects = allObjects(result.repository());
                assertFalse(objects.contains(secret));
                assertFalse(objects.contains(staged));
                assertFalse(objects.contains(stash));
                assertEquals("", git(result.repository(), "fsck", "--full", "--no-dangling"));
                assertEquals("v1", git(result.repository(), "tag", "-l"));
            }
        }
        assertEquals(before, contents(host.resolve(".git")), "preparation must not run maintenance in the source");
        assertFalse(Files.exists(cache.resolve("metadata")));
    }

    @Execution(CONCURRENT) @Test void remoteAdvanceRewindDeletedBranchAndMovedTagsRefreshTheSnapshot() throws Exception {
        start();
        try (var result = prepare(host, null)) { assertTrue(result.strategy().startsWith("reference-seeded")); }
        commitBinary(remote, "new public commit");
        try (var result = prepare(host, null)) { sameObjects(remote, result.repository()); }
        var removed = git(remote, "rev-parse", "stable");
        git(remote, "branch", "-D", "stable");
        git(remote, "reset", "--hard", "HEAD~3");
        git(remote, "tag", "-f", "v1", "HEAD");
        try (var result = prepare(host, null)) {
            sameObjects(remote, result.repository());
            assertEquals("reference-seeded clean snapshot", result.strategy());
            assertFalse(allObjects(result.repository()).contains(removed));
            assertEquals(git(remote, "rev-parse", "v1"), git(result.repository(), "rev-parse", "v1"));
        }
    }

    @Execution(CONCURRENT)
    @ParameterizedTest
    @ValueSource(strings = {"sha1", "sha256"})
    void incrementalGenerationsKeepOldPacksAndReadersAndProduceIndependentGuests(String format) throws Exception {
        remote = tmp.resolve("incremental");
        git(tmp, "init", "--object-format=" + format, "-b", "main", remote.toString());
        commitBinary(remote, "initial public commit");
        start();
        try (var first = prepare(host, null)) {
            var before = contents(first.repository());
            PreparationGit.delete(host);
            for (int i = 0; i < 3; i++) {
                commitBinary(remote, "incremental public commit " + i);
                git(remote, "branch", "added-" + i);
                git(remote, "-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "release-" + i, "-m", "new tag");
                var phases = new ArrayList<String>();
                try (var next = preparation.prepare(new GitPreparation.Request(server.url(remote), null, host), phases::add)) {
                    assertEquals("incremental clean snapshot", next.strategy());
                    assertFalse(phases.contains("Creating clean Git snapshot"));
                    assertFalse(phases.contains("Verifying Git snapshot"));
                    assertEquals(before, contents(first.repository()), "active readers must keep their old refs and bytes");
                    for (var file : first.checksums().keySet()) {
                        assertTrue(Files.isSameFile(first.repository().resolve(file), next.repository().resolve(file)),
                                "old packs must be reused without copying or repacking");
                    }
                    assertEquals(i + 2, packCount(next.repository()));
                    sameObjects(remote, next.repository());
                    assertEquals("", git(next.repository(), "fsck", "--full", "--no-dangling"));
                    var guest = tmp.resolve("incremental-guest-" + i);
                    git(tmp, "clone", "--no-hardlinks", next.repository().toString(), guest.toString());
                    next.close();
                    assertEquals("", git(guest, "fsck", "--full", "--no-dangling"));
                    sameObjects(remote, guest);
                }
            }
        }
        try (var hit = prepare(null, null)) { assertEquals("cached clean snapshot", hit.strategy()); }
    }

    @Execution(CONCURRENT)
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detachedRemoteHeadIsFetchedEvenWithoutAnyBranches(boolean removeBranch) throws Exception {
        remote = tmp.resolve("detached");
        git(tmp, "init", "-b", "main", remote.toString());
        commitBinary(remote, "initial public commit");
        start();
        try (var first = prepare(null, null)) { }
        git(remote, "checkout", "--detach");
        if (removeBranch) git(remote, "branch", "-D", "main");
        for (int i = 0; i < 2; i++) {
            commitBinary(remote, "detached public commit " + i);
            try (var next = prepare(null, null)) {
                assertEquals("incremental clean snapshot", next.strategy());
                assertEquals(git(remote, "rev-parse", "HEAD"), git(next.repository(), "rev-parse", "HEAD"));
                sameObjects(remote, next.repository());
                assertEquals("", git(next.repository(), "fsck", "--full", "--no-dangling"));
            }
            try (var hit = prepare(null, null)) { assertEquals("cached clean snapshot", hit.strategy()); }
        }
    }

    @Execution(CONCURRENT)
    @ParameterizedTest
    @ValueSource(strings = {"merged-branch", "renamed-branch", "rewind-retained", "lightweight-tag"})
    void refsCanDisappearIncrementallyWhenTheirObjectsRemainSelected(String change) throws Exception {
        start();
        git(remote, "tag", "lightweight", "HEAD");
        try (var first = prepare(null, null)) {
            switch (change) {
                case "merged-branch" -> {
                    git(remote, "-c", "user.name=t", "-c", "user.email=t@t", "merge", "--no-ff", "stable", "-m", "merge stable");
                    git(remote, "branch", "-D", "stable");
                }
                case "renamed-branch" -> git(remote, "branch", "-m", "stable", "renamed");
                case "rewind-retained" -> git(remote, "reset", "--hard", "HEAD~2");
                case "lightweight-tag" -> git(remote, "tag", "-d", "lightweight");
            }
            try (var next = prepare(null, null)) {
                assertEquals("incremental clean snapshot", next.strategy());
                sameObjects(remote, next.repository());
                for (var file : first.checksums().keySet()) {
                    assertTrue(Files.isSameFile(first.repository().resolve(file), next.repository().resolve(file)));
                }
            }
        }
    }

    @Execution(CONCURRENT)
    @ParameterizedTest
    @ValueSource(strings = {"unmerged-branch", "rewind", "moved-tag", "deleted-tag"})
    void removedHistoryRebuildsAndExcludesFormerlySelectedObjects(String change) throws Exception {
        start();
        try (var first = prepare(null, null)) {
            var before = contents(first.repository());
            switch (change) {
                case "unmerged-branch" -> git(remote, "branch", "-D", "stable");
                case "rewind" -> {
                    git(remote, "branch", "-D", "stable");
                    git(remote, "reset", "--hard", "HEAD~2");
                }
                case "moved-tag" -> git(remote, "tag", "-f", "v1", "HEAD");
                case "deleted-tag" -> git(remote, "tag", "-d", "v1");
            }
            var removed = allObjects(first.repository());
            removed.removeAll(reachable(remote));
            assertFalse(removed.isEmpty(), "fixture must remove selected objects, including annotated tag objects");
            var phases = new ArrayList<String>();
            try (var next = preparation.prepare(new GitPreparation.Request(server.url(remote), null, null), phases::add)) {
                assertEquals("reference-seeded clean snapshot", next.strategy());
                assertTrue(phases.contains("Rebuilding Git snapshot: selected history was removed"));
                assertTrue(phases.contains("Verifying Git snapshot"));
                sameObjects(remote, next.repository());
                assertEquals(before, contents(first.repository()));
            }
        }
    }

    @Execution(CONCURRENT)
    @ParameterizedTest
    @ValueSource(strings = {"new-pack", "new-idx", "retained-pack", "retained-idx"})
    void incrementalCorruptionNeverPublishesUncheckedBytes(String damage) throws Exception {
        start();
        try (var first = prepare(null, null)) {
            var before = contents(first.repository());
            commitBinary(remote, "new objects to corrupt");
            var phases = new ArrayList<String>();
            try (var next = preparation.prepare(new GitPreparation.Request(server.url(remote), null, null), phase -> {
                phases.add(phase);
                if (phase.equals("Verifying incremental Git snapshot")) {
                    try {
                        var work = PreparationGit.children(cache.resolve("work")).stream()
                                .filter(p -> Files.isDirectory(p.resolve("snapshot/repo.git"))).findFirst().orElseThrow();
                        var repo = work.resolve("snapshot/repo.git");
                        var suffix = damage.endsWith("idx") ? ".idx" : ".pack";
                        var corrupt = PreparationGit.children(repo.resolve("objects/pack")).stream()
                                .filter(p -> p.toString().endsWith(suffix))
                                .filter(p -> first.checksums().containsKey("objects/pack/" + p.getFileName()) == damage.startsWith("retained"))
                                .findFirst().orElseThrow();
                        assertTrue(corrupt.toFile().setWritable(true));
                        Files.writeString(corrupt, "corrupt incremental object data");
                    } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }
            })) {
                assertNotEquals("incremental clean snapshot", next.strategy());
                assertTrue(phases.contains("Incremental Git refresh failed; rebuilding snapshot"));
                sameObjects(remote, next.repository());
                assertEquals("", git(next.repository(), "fsck", "--full", "--no-dangling"));
                if (damage.startsWith("new")) assertEquals(before, contents(first.repository()));
            }
        }
        try (var hit = prepare(null, null)) { assertEquals("cached clean snapshot", hit.strategy()); }
    }

    @Execution(CONCURRENT) @Test void failedIncrementalFetchLeavesThePublishedGenerationIntact() throws Exception {
        start();
        try (var first = prepare(null, null)) {
            var published = PreparationGit.children(cache.resolve("snapshots")).getFirst();
            var before = contents(published);
            commitBinary(remote, "unavailable during refresh");
            assertThrows(IOException.class, () -> preparation.prepare(
                    new GitPreparation.Request(server.url(remote), null, null), phase -> {
                        if (phase.equals("Fetching incremental Git snapshot")) {
                            try { PreparationGit.delete(remote); }
                            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                        }
                    }));
            assertEquals(before, contents(published));
            assertEquals(List.of(published), PreparationGit.children(cache.resolve("snapshots")));
            assertEquals(1, PreparationGit.children(cache.resolve("work")).size(), "only the original reader may remain");
            assertEquals("", git(first.repository(), "fsck", "--full", "--no-dangling"));
        }
        assertTrue(PreparationGit.children(cache.resolve("work")).isEmpty());
    }

    @Execution(CONCURRENT)
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incrementalFetchRacingAnUpstreamChangeFallsBackToFreshSelection(boolean detachedHead) throws Exception {
        start();
        if (detachedHead) git(remote, "checkout", "--detach");
        try (var first = prepare(null, null)) { }
        commitBinary(remote, "advertised update");
        var phases = new ArrayList<String>();
        try (var next = preparation.prepare(new GitPreparation.Request(server.url(remote), null, null), phase -> {
            phases.add(phase);
            if (phase.equals("Fetching incremental Git snapshot")) {
                try { commitBinary(remote, "published after advertisement"); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        })) {
            assertEquals("reference-seeded clean snapshot", next.strategy());
            assertTrue(phases.contains("Rebuilding Git snapshot: remote refs changed during fetch"));
            sameObjects(remote, next.repository());
        }
        try (var hit = prepare(null, null)) { assertEquals("cached clean snapshot", hit.strategy()); }
    }

    @Execution(CONCURRENT) @Test void interruptedIncrementalRefreshDoesNotRetryOrPublish() throws Exception {
        start();
        try (var first = prepare(null, null)) { }
        var published = PreparationGit.children(cache.resolve("snapshots")).getFirst();
        var before = contents(published);
        commitBinary(remote, "interrupted update");
        var phases = new ArrayList<String>();
        try {
            assertThrows(IOException.class, () -> preparation.prepare(
                    new GitPreparation.Request(server.url(remote), null, null), phase -> {
                        phases.add(phase);
                        if (phase.equals("Fetching incremental Git snapshot")) Thread.currentThread().interrupt();
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertFalse(phases.contains("Creating clean Git snapshot"));
        assertEquals(before, contents(published));
        assertTrue(PreparationGit.children(cache.resolve("work")).isEmpty());
    }

    @Execution(CONCURRENT) @Test void incrementalPackGrowthEventuallyCompactsWithoutChangingReaders() throws Exception {
        remote = tmp.resolve("small");
        git(tmp, "init", "-b", "main", remote.toString());
        git(remote, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--allow-empty", "-m", "initial");
        start();
        try (var first = prepare(null, null)) {
            var before = contents(first.repository());
            for (int i = 0; i < GitPreparation.MAX_INCREMENTAL_PACKS; i++) {
                git(remote, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--allow-empty", "-m", "update " + i);
                var phases = new ArrayList<String>();
                try (var next = preparation.prepare(new GitPreparation.Request(server.url(remote), null, null), phases::add)) {
                    assertTrue(packCount(next.repository()) <= GitPreparation.MAX_INCREMENTAL_PACKS);
                    if (i + 1 < GitPreparation.MAX_INCREMENTAL_PACKS) {
                        assertEquals("incremental clean snapshot", next.strategy());
                    } else {
                        assertEquals("reference-seeded clean snapshot", next.strategy());
                        assertTrue(phases.contains("Compacting Git snapshot"));
                        assertEquals(1, packCount(next.repository()));
                    }
                    sameObjects(remote, next.repository());
                    assertEquals(before, contents(first.repository()));
                }
            }
        }
    }

    private static long packCount(Path repo) throws IOException {
        return PreparationGit.children(repo.resolve("objects/pack")).stream()
                .filter(p -> p.toString().endsWith(".pack")).count();
    }

    @Execution(CONCURRENT) @Test void branchAndAnnotatedTagSelectionSurviveRemovingEverySource() throws Exception {
        start();
        try (var result = prepare(host, "v1")) {
            var guest = tmp.resolve("guest");
            git(tmp, "clone", "--no-hardlinks", "--branch", "v1", "--", result.repository().toString(), guest.toString());
            git(guest, "remote", "set-url", "origin", server.url(remote));
            assertEquals(git(remote, "rev-parse", "v1^{commit}"), git(guest, "rev-parse", "HEAD"));
            assertEquals("", git(guest, "branch", "--show-current"));
            result.close();
            PreparationGit.delete(host);
            assertEquals("", git(guest, "fsck", "--full", "--no-dangling"));
            commitBinary(remote, "later fetch");
            git(guest, "fetch", "origin");
            assertEquals(git(remote, "rev-parse", "HEAD"), git(guest, "rev-parse", "origin/main"));
        }
    }

    @Execution(CONCURRENT) @Test void aPrivateBranchCannotSatisfyARequestedRemoteBranch() throws Exception {
        start();
        git(host, "branch", "only-here");
        assertThrows(IOException.class, () -> prepare(host, "only-here"));
        assertEquals(List.of(), PreparationGit.children(cache.resolve("work")));
    }

    @Execution(CONCURRENT) @Test void sha256RepositoriesKeepTheirObjectFormat() throws Exception {
        remote = tmp.resolve("sha256");
        git(tmp, "init", "--object-format=sha256", "-b", "main", remote.toString());
        commitBinary(remote, "sha256 fixture");
        start();
        try (var result = prepare(host, null)) {
            assertEquals("sha256", git(result.repository(), "rev-parse", "--show-object-format"));
            sameObjects(remote, result.repository());
            assertEquals("", git(result.repository(), "fsck", "--full", "--no-dangling"));
        }
        try (var result = prepare(null, null)) {
            assertEquals("cached clean snapshot", result.strategy());
            assertEquals("sha256", git(result.repository(), "rev-parse", "--show-object-format"));
        }
    }

    @Execution(CONCURRENT)
    @ParameterizedTest
    @ValueSource(strings = {"remove", "corrupt", "prune"})
    void changedSourceEitherRecoversOrRetriesWithoutAReference(String mutation) throws Exception {
        start();
        var phases = new ArrayList<String>();
        try (var result = preparation.prepare(new GitPreparation.Request(server.url(remote), null, host), phase -> {
            phases.add(phase);
            if (phase.equals("Creating clean Git snapshot") && phases.stream().filter(phase::equals).count() == 1) {
                try {
                    var work = PreparationGit.children(cache.resolve("work")).getFirst();
                    try (var files = Files.walk(work)) {
                        assertFalse(files.anyMatch(p -> p.toString().endsWith(".pack") || p.toString().endsWith(".idx")),
                                "reading a host reference must not pre-copy any pack/index files");
                    }
                    if (mutation.equals("remove")) PreparationGit.delete(host);
                    else if (mutation.equals("corrupt")) {
                        for (var file : PreparationGit.children(host.resolve(".git/objects/pack"))) {
                            assertTrue(file.toFile().setWritable(true));
                            Files.writeString(file, "source replaced during preparation");
                        }
                    } else {
                        for (var ref : git(host, "for-each-ref", "--format=%(refname)").lines().toList()) {
                            git(host, "update-ref", "--no-deref", "-d", ref);
                        }
                        git(host, "reflog", "expire", "--expire=now", "--all");
                        git(host, "gc", "--prune=now");
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        })) {
            long attempts = phases.stream().filter("Creating clean Git snapshot"::equals).count();
            if (mutation.equals("remove")) {
                assertEquals("new clean snapshot (reference retry)", result.strategy());
                assertEquals(2, attempts);
            } else {
                // Git can discard missing negotiation tips itself and download the complete closure.
                assertTrue(attempts == 1 || attempts == 2);
            }
            sameObjects(remote, result.repository());
            assertEquals("", git(result.repository(), "fsck", "--full", "--no-dangling"));
        }
        try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy()); }
    }

    @Execution(CONCURRENT) @Test void unavailableCheckoutDuringSelectionUsesTheRemoteDirectly() throws Exception {
        start();
        PreparationGit.delete(host);
        var phases = new ArrayList<String>();
        try (var result = preparation.prepare(new GitPreparation.Request(server.url(remote), null, host), phases::add)) {
            assertEquals("new clean snapshot", result.strategy());
            assertTrue(phases.contains("Host reference unavailable; cloning remote"));
            assertEquals(1, phases.stream().filter("Creating clean Git snapshot"::equals).count());
            sameObjects(remote, result.repository());
        }
    }

    @Execution(CONCURRENT) @Test void failedIntegrityCheckRetriesBeforePublishing() throws Exception {
        start();
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try (var result = preparation.prepare(new GitPreparation.Request(server.url(remote), null, host), phase -> {
            if (phase.equals("Verifying Git snapshot") && checks.incrementAndGet() == 1) {
                try {
                    assertTrue(PreparationGit.children(cache.resolve("snapshots")).isEmpty());
                    var work = PreparationGit.children(cache.resolve("work")).getFirst();
                    var pack = PreparationGit.children(work.resolve("snapshot/repo.git/objects/pack")).stream()
                            .filter(p -> p.toString().endsWith(".pack")).findFirst().orElseThrow();
                    assertTrue(pack.toFile().setWritable(true));
                    Files.writeString(pack, "incomplete or damaged dissociation");
                } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }
        })) {
            // The retry has no reference: its strict transfer replaces the separate check.
            assertEquals(1, checks.get());
            assertEquals("new clean snapshot (reference retry)", result.strategy());
            sameObjects(remote, result.repository());
        }
    }

    @Execution(CONCURRENT) @Test void aCheckoutObjectWhoseBytesDoNotMatchItsNameIsCaughtAfterDissociation() throws Exception {
        start();
        // --dissociate copies borrowed objects without rehashing them, and clone's connectivity check
        // and fsck --connectivity-only accept the result: only a check that rehashes catches this (#1548).
        var objects = host.resolve(".git/objects");
        var moved = Files.createDirectory(tmp.resolve("host-packs"));
        for (var file : PreparationGit.children(objects.resolve("pack"))) Files.move(file, moved.resolve(file.getFileName()));
        for (var pack : PreparationGit.children(moved)) {
            if (pack.toString().endsWith(".pack")) PreparationGit.run(host, pack, null, List.of("unpack-objects", "-q"));
        }
        corruptLooseBlob(host, git(remote, "rev-parse", "HEAD:isx.deb"));
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try (var result = preparation.prepare(new GitPreparation.Request(server.url(remote), null, host), phase -> {
            if (phase.equals("Verifying Git snapshot")) checks.incrementAndGet();
        })) {
            assertEquals(1, checks.get(), "only the reference clone needs the separate check");
            assertEquals("new clean snapshot (reference retry)", result.strategy());
            sameObjects(remote, result.repository());
            assertEquals("", git(result.repository(), "fsck", "--full", "--no-dangling"));
        }
    }

    @Execution(CONCURRENT) @Test void aCloneWithoutAReferenceIsCheckedByTheTransferInsteadOfASeparateFsck() throws Exception {
        start();
        var phases = new ArrayList<String>();
        try (var result = preparation.prepare(new GitPreparation.Request(server.url(remote), null, null), phases::add)) {
            assertEquals("new clean snapshot", result.strategy());
            assertFalse(phases.contains("Verifying Git snapshot"), "every object went through index-pack --strict");
            sameObjects(remote, result.repository());
            assertEquals("", git(result.repository(), "fsck", "--full", "--no-dangling"));
        }
    }

    @Execution(CONCURRENT) @Test void aRemoteObjectWithBrokenStructureIsRejectedWithoutAReference() throws Exception {
        // A tree listing the same name twice: its ID matches its bytes, so only a structural check rejects it.
        literalTreeBranch("bad", "100644", 2);
        start();
        var phases = new ArrayList<String>();
        var failure = assertThrows(IOException.class, () -> preparation.prepare(
                new GitPreparation.Request(server.url(remote), null, null), phases::add));
        assertTrue(phases.contains("Verifying Git snapshot"), "the strict receive's rejection falls back to the fsck, which rejects it too");
        assertEquals("Git preparation failed: fsck", failure.getMessage());
        assertTrue(GitPreparation.fsckRejected(((GitCommandException) failure.getSuppressed()[0]).details()),
                "the fsck failure keeps why the snapshot was cloned twice");
        assertTrue(PreparationGit.children(cache.resolve("snapshots")).isEmpty());
    }

    @Execution(CONCURRENT) @Test void historyThatFsckOnlyWarnsAboutIsClonedAgainForTheFullFsck() throws Exception {
        // --strict makes fsck's warnings fatal; fsck --full accepts a zero-padded mode, as main did.
        literalTreeBranch("old", "0100644", 1);
        start();
        var phases = new ArrayList<String>();
        try (var result = preparation.prepare(new GitPreparation.Request(server.url(remote), null, null), phases::add)) {
            assertEquals("new clean snapshot", result.strategy());
            assertTrue(phases.contains("Recloning Git snapshot for a full check"));
            assertTrue(phases.contains("Verifying Git snapshot"));
            sameObjects(remote, result.repository());
        }
        try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy()); }
    }

    /** A branch whose commit's tree, written without Git's checks, lists {@code isx.deb} as "a" {@code copies} times. */
    private void literalTreeBranch(String branch, String mode, int copies) throws Exception {
        var blob = git(remote, "rev-parse", "HEAD:isx.deb");
        var entry = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < copies; i++) {
            entry.writeBytes((mode + " a\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            entry.writeBytes(java.util.HexFormat.of().parseHex(blob));
        }
        var tree = Files.write(tmp.resolve(branch + "-tree"), entry.toByteArray());
        var literal = git(remote, "hash-object", "--literally", "-t", "tree", "-w", tree.toString());
        var commit = git(remote, "-c", "user.name=t", "-c", "user.email=t@t", "commit-tree", literal, "-m", branch);
        git(remote, "update-ref", "refs/heads/" + branch, commit);
    }

    @Execution(CONCURRENT) @Test void aRemoteObjectWhoseBytesDoNotMatchItsNameIsRejectedWithoutAReference() throws Exception {
        start();
        // The pack carries other content under the blob's name, so the tree's link to that name is missing.
        corruptLooseBlob(remote, git(remote, "rev-parse", "HEAD:isx.deb"));
        var phases = new ArrayList<String>();
        assertThrows(IOException.class, () -> preparation.prepare(
                new GitPreparation.Request(server.url(remote), null, null), phases::add));
        assertFalse(phases.contains("Recloning Git snapshot for a full check"), "only an fsck rejection is cloned again");
        assertTrue(PreparationGit.children(cache.resolve("snapshots")).isEmpty());
    }

    @Execution(CONCURRENT) @Test void onlyGitsOwnFsckRejectionClonesAgain() {
        var rejected = new GitCommandException("Git preparation failed: clone", null,
                "error: object 1234: zeroPaddedFilemode: contains zero-padded file modes\n"
                        + "fatal: fsck error in packed object\nfatal: fetch-pack: invalid index-pack output\n");
        assertTrue(GitPreparation.fsckRejected(rejected.details()));
        assertTrue(GitPreparation.fsckRejected(new GitCommandException("x", null, "fatal: fsck error in pack objects\n").details()));
        // A server's sideband line is prefixed, and cannot ask for the second download.
        var sideband = new GitCommandException("Git preparation failed: clone", null,
                "remote: x | fatal: fsck error in packed object\nremote: fatal: fsck error in packed object\n"
                        + "fatal: early EOF\nfatal: fetch-pack: invalid index-pack output\n");
        assertFalse(GitPreparation.fsckRejected(sideband.details()));
    }

    @Execution(CONCURRENT) @Test void onlyAStrictReceiveOfEveryObjectReplacesTheSeparateFsck() {
        var strict = "12:00:00.000000 git.c:506               trace: built-in: git index-pack --stdin --fix-thin "
                + "'--keep=fetch-pack 1 on box' --check-self-contained-and-connected --strict\n";
        assertEquals(1, GitPreparation.strictReceives(strict));
        assertEquals(2, GitPreparation.strictReceives(strict + strict));
        // A user's fetch.fsck.<msg-id> severities or skip list may accept what fsck --full rejects.
        assertEquals(0, GitPreparation.strictReceives(strict.replace("--strict", "--strict=duplicateentries=ignore")));
        // Dumb HTTP indexes the packs it downloads without --strict, and writes loose objects with no index-pack at all.
        assertEquals(0, GitPreparation.strictReceives("12:00:00.000000 git.c:506 trace: built-in: git index-pack --stdin\n"));
        assertEquals(0, GitPreparation.strictReceives(
                "12:00:00.000000 git.c:506 trace: built-in: git clone -q --bare --no-local http://h/r r.git\n"));
        assertEquals(0, GitPreparation.strictReceives(""));
        // Packfile URIs and bundles are indexed with --fsck-objects, which does not check links.
        assertEquals(0, GitPreparation.strictReceives(strict
                + "12:00:00.000000 git.c:506 trace: built-in: git index-pack --stdin --fsck-objects\n"));
        assertEquals(0, GitPreparation.strictReceives(strict.replace("--strict", "--strict-later")));
    }

    @Execution(CONCURRENT) @Test void aFailedRemoteRetryLeavesNoSnapshotOrWork() throws Exception {
        start();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IOException.class, () -> preparation.prepare(
                new GitPreparation.Request(server.url(remote), null, host), phase -> {
                    if (phase.equals("Creating clean Git snapshot") && attempts.incrementAndGet() == 1) {
                        try {
                            PreparationGit.delete(host);
                            PreparationGit.delete(remote);
                        } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                    }
                }));
        assertEquals(2, attempts.get(), "retry must be bounded and must not keep borrowing the failed source");
        assertTrue(PreparationGit.children(cache.resolve("snapshots")).isEmpty());
        assertTrue(PreparationGit.children(cache.resolve("work")).isEmpty());
    }

    @Execution(CONCURRENT) @Test void completedDissociationNeedsNoFurtherAccessToTheSource() throws Exception {
        start();
        try (var result = preparation.prepare(new GitPreparation.Request(server.url(remote), null, host), phase -> {
            if (phase.equals("Verifying Git snapshot")) {
                try { PreparationGit.delete(host); }
                catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            }
        })) {
            assertEquals("reference-seeded clean snapshot", result.strategy());
            sameObjects(remote, result.repository());
            assertEquals("", git(result.repository(), "fsck", "--full", "--no-dangling"));
        }
    }

    @Execution(CONCURRENT) @Test void directReferenceUsesOnlyMatchingTrackingTipsAndCanReadLooseObjects() throws Exception {
        start();
        git(host, "remote", "rename", "origin", "upstream");
        commitBinary(host, "private negotiation tip");
        var secret = git(host, "rev-parse", "HEAD");
        git(host, "tag", "private-tag");
        git(host, "update-ref", "refs/remotes/private/main", secret);
        git(host, "repack", "-a", "-d");
        commitBinary(remote, "new loose public tip");
        git(host, "fetch", "upstream");
        var tip = git(remote, "rev-parse", "HEAD");
        assertTrue(Files.isRegularFile(host.resolve(".git/objects/" + tip.substring(0, 2) + "/" + tip.substring(2))));
        // This path is passed to Git's shell-based alternateRefsCommand; it must be quoted literally.
        cache = tmp.resolve("cache ' $(touch unexpected) $literal");
        preparation = new GitPreparation(cache, GitCacheConfig.DEFAULT, GitTransferConfig.DEFAULT);
        var before = contents(host.resolve(".git"));
        try (var result = preparation.prepare(new GitPreparation.Request(server.url(remote), null, host), phase -> {
            if (phase.equals("Creating clean Git snapshot")) {
                try {
                    var work = PreparationGit.children(cache.resolve("work")).getFirst();
                    var hints = Files.readString(work.resolve("reference-tips"));
                    assertFalse(hints.contains(secret), hints);
                    assertTrue(hints.contains(tip), hints);
                    try (var files = Files.walk(work)) {
                        assertFalse(files.anyMatch(p -> p.toString().endsWith(".pack") || p.toString().endsWith(".idx")));
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        })) {
            assertEquals("reference-seeded clean snapshot", result.strategy());
            sameObjects(remote, result.repository());
        }
        assertEquals(before, contents(host.resolve(".git")));
        try (var paths = Files.walk(cache)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().equals("unexpected")));
        }
    }

    @Execution(CONCURRENT) @Test void looseOnlyCheckoutsCanSupplyAReferenceWithoutBeingPacked() throws Exception {
        start();
        PreparationGit.delete(host);
        git(tmp, "clone", "-q", "--no-hardlinks", "--", remote.toString(), host.toString());
        git(host, "remote", "set-url", "origin", server.url(remote));
        assertTrue(PreparationGit.children(host.resolve(".git/objects/pack")).isEmpty());
        var before = contents(host.resolve(".git"));
        try (var result = prepare(host, null)) {
            assertEquals("reference-seeded clean snapshot", result.strategy());
            sameObjects(remote, result.repository());
        }
        assertEquals(before, contents(host.resolve(".git")));
    }

    @Execution(CONCURRENT) @Test void corruptSnapshotBytesAndMetadataAreRebuiltWithoutBorrowingThem() throws Exception {
        start();
        for (var damage : List.of("seal", "HEAD", "packed-refs", "config", "pack", "idx", "extra-object", "alternates")) {
            try (var ignored = prepare(host, null)) { }
            var snapshot = PreparationGit.children(cache.resolve("snapshots")).getFirst();
            var repo = snapshot.resolve("repo.git");
            Path file;
            if (damage.equals("seal")) file = snapshot.resolve("seal");
            else if (damage.equals("pack") || damage.equals("idx")) {
                file = PreparationGit.children(repo.resolve("objects/pack")).stream()
                        .filter(p -> p.toString().endsWith("." + damage)).findFirst().orElseThrow();
            } else if (damage.equals("extra-object")) {
                file = Files.createDirectories(repo.resolve("objects/aa")).resolve("extra");
            } else if (damage.equals("alternates")) {
                file = Files.createDirectories(repo.resolve("objects/info")).resolve("alternates");
            } else file = repo.resolve(damage);
            if (Files.exists(file)) assertTrue(file.toFile().setWritable(true));
            Files.writeString(file, "damaged " + damage);
            try (var result = prepare(host, null)) {
                assertEquals("reference-seeded clean snapshot", result.strategy(), damage);
                sameObjects(remote, result.repository());
                assertEquals("", git(result.repository(), "fsck", "--full", "--no-dangling"));
            }
            try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy(), damage); }
        }
    }

    @Execution(CONCURRENT) @Test void remoteHeadSwitchAndBranchTagPinsShareOnlyTheSameFullSelection() throws Exception {
        start();
        try (var result = prepare(host, null)) { }
        for (var pin : List.of("stable", "v1", "main")) {
            try (var result = prepare(null, pin)) {
                assertEquals("cached clean snapshot", result.strategy());
                sameObjects(remote, result.repository());
            }
        }
        assertThrows(IOException.class, () -> prepare(null, "private"));
        git(remote, "symbolic-ref", "HEAD", "refs/heads/stable");
        try (var result = prepare(null, null)) {
            assertEquals("incremental clean snapshot", result.strategy());
            assertEquals("ref: refs/heads/stable", Files.readString(result.repository().resolve("HEAD")).strip());
        }
        try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy()); }
    }

    @Execution(CONCURRENT) @Test void aRemoteChangeBetweenAdvertisementAndCloneCannotMislabelTheSnapshot() throws Exception {
        start();
        try (var result = preparation.prepare(new GitPreparation.Request(server.url(remote), null, host), phase -> {
            if (phase.equals("Creating clean Git snapshot")) {
                try { commitBinary(remote, "published after advertisement"); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        })) { sameObjects(remote, result.repository()); }
        try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy()); }
    }

    @Execution(CONCURRENT) @Test void unicodeRefNamesUseTheSameOrderingForAdvertisementsAndSnapshots() throws Exception {
        start();
        git(remote, "branch", "\uE000");
        git(remote, "branch", "\uD83D\uDE04");
        try (var result = prepare(host, null)) { sameObjects(remote, result.repository()); }
        try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy()); }
    }

    @Execution(CONCURRENT) @Test void cachedSnapshotsStillRequireAnAccessibleAuthoritativeRemote() throws Exception {
        start();
        try (var result = prepare(host, null)) { }
        PreparationGit.delete(remote);
        assertThrows(IOException.class, () -> prepare(host, null));
        assertEquals(List.of(), PreparationGit.children(cache.resolve("work")));
    }

    @Execution(CONCURRENT) @Test void emptyRemotesAreIndependentAndRefreshedWithoutGuessingTheUnbornHead() throws Exception {
        remote = tmp.resolve("empty.git");
        git(tmp, "init", "--bare", "-b", "next", remote.toString());
        start();
        for (int i = 0; i < 2; i++) {
            try (var result = prepare(null, null)) {
                assertTrue(result.checksums().isEmpty());
                assertNotEquals("cached clean snapshot", result.strategy());
                assertEquals("ref: refs/heads/next", Files.readString(result.repository().resolve("HEAD")).strip());
            }
        }
        commitBinary(host, "first published commit");
        git(host, "push", "origin", "HEAD:refs/heads/next");
        try (var result = prepare(null, null)) {
            assertEquals("incremental clean snapshot", result.strategy());
            sameObjects(remote, result.repository());
        }
        try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy()); }
    }

    @Execution(CONCURRENT) @Test void snapshotsReplaceTheirSelectedClosureAndActiveReadersSurviveEviction() throws Exception {
        start();
        try (var first = prepare(null, null)) {
            var oldObjects = allObjects(first.repository());
            var oldTip = git(remote, "rev-parse", "HEAD");
            git(remote, "reset", "--hard", "HEAD~2");
            git(remote, "branch", "-D", "stable");
            git(remote, "tag", "-d", "v1");
            try (var next = prepare(null, null)) {
                sameObjects(remote, next.repository());
                assertFalse(allObjects(next.repository()).contains(oldTip));
                assertEquals(oldObjects, allObjects(first.repository()));
                assertEquals(1, PreparationGit.children(cache.resolve("snapshots")).size());
            }
            // A different cache policy can evict every persistent entry, while this reader stays valid.
            var disabled = new GitPreparation(cache, new GitCacheConfig(0, Duration.ZERO), GitTransferConfig.DEFAULT);
            try (var next = disabled.prepare(new GitPreparation.Request(server.url(remote), null, null), s -> {})) {
                assertEquals(oldObjects, allObjects(first.repository()));
                assertEquals(List.of(), PreparationGit.children(cache.resolve("snapshots")));
            }
        }
    }

    @Execution(CONCURRENT) @Test void aSnapshotThatIsNotKeptSaysWhyAndWhichSettingToChange() throws Exception {
        start();
        try (var kept = prepare(null, null)) { assertNull(kept.notRetained()); }
        try (var hit = prepare(null, null)) { assertNull(hit.notRetained()); }
        var cases = Map.of(
                new GitCacheConfig(1, Duration.ofDays(7)), "exceeds git-cache.snapshot-max-mib (0 MiB); it was used for this build but not kept",
                new GitCacheConfig(0, Duration.ofDays(7)), "Git snapshot retention is disabled (git-cache.snapshot-max-mib: 0); it was used for this build but not kept",
                new GitCacheConfig(1L << 30, Duration.ZERO), "Git snapshot retention is disabled (git-cache.max-age-days: 0); it was used for this build but not kept");
        for (var entry : cases.entrySet()) {
            var policy = new GitPreparation(tmp.resolve("cache-" + entry.getKey().snapshotBytes() + "-" + entry.getKey().maxAge().toDays()),
                    entry.getKey(), GitTransferConfig.DEFAULT);
            for (int build = 0; build < 2; build++) {
                try (var result = policy.prepare(new GitPreparation.Request(server.url(remote), null, null), s -> {})) {
                    assertEquals("new clean snapshot", result.strategy(), "nothing was kept for this build to reuse");
                    assertNotNull(result.notRetained(), entry.getKey().toString());
                    assertTrue(result.notRetained().endsWith(entry.getValue()), result.notRetained());
                }
            }
        }
    }

    @Execution(CONCURRENT) @Test void concurrentWritersPublishCompleteGenerationsAndReadersHaveSeparateLeases() throws Exception {
        start();
        var ready = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var cold = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(3)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 3; i++) tasks.add(pool.submit(() -> {
                try (var result = prepare(null, null)) {
                    if (!result.strategy().equals("cached clean snapshot")) cold.incrementAndGet();
                    ready.countDown();
                    assertTrue(release.await(20, TimeUnit.SECONDS));
                    sameObjects(remote, result.repository());
                    assertEquals("", git(result.repository(), "fsck", "--full", "--no-dangling"));
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
            try {
                assertTrue(ready.await(20, TimeUnit.SECONDS));
                assertEquals(3, PreparationGit.children(cache.resolve("work")).size());
                assertEquals(1, PreparationGit.children(cache.resolve("snapshots")).size());
            } finally { release.countDown(); }
            for (var task : tasks) task.get(30, TimeUnit.SECONDS);
        }
        assertEquals(1, cold.get(), "waiting writers should reuse the first complete publication");
        assertEquals(List.of(), PreparationGit.children(cache.resolve("work")));
    }

    @Execution(CONCURRENT) @Test void aPublicDeltaIsReencodedWhenItsOnlyStoredBaseIsPrivate() throws Exception {
        start();
        var publicId = git(host, "rev-parse", "origin/main:isx.deb");
        var privateId = forcePrivateDeltaBase(host.resolve(".git"), publicId, Files.readAllBytes(remote.resolve("isx.deb")));
        try (var result = prepare(host, null)) {
            sameObjects(remote, result.repository());
            assertFalse(allObjects(result.repository()).contains(privateId));
            PreparationGit.delete(host);
            assertEquals("", git(result.repository(), "fsck", "--full", "--no-dangling"));
        }
    }

    @Execution(CONCURRENT) @Test void readerLeaseWorksAcrossProcessesAndDeadReadersAreReclaimed() throws Exception {
        start();
        var signal = tmp.resolve("ready");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), Reader.class.getName(), cache.toString(),
                server.url(remote), signal.toString()).redirectErrorStream(true)
                .redirectOutput(tmp.resolve("reader.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(signal) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(signal), Files.readString(tmp.resolve("reader.log")));
            var active = Path.of(Files.readString(signal));
            assertTrue(active.isAbsolute(), "the reader published no lease path: " + active);
            try (var result = prepare(null, null)) {
                assertTrue(Files.isDirectory(active));
                sameObjects(remote, active);
            }
            process.destroyForcibly().waitFor();
            try (var result = prepare(null, null)) {
                assertFalse(Files.exists(active));
                assertEquals(1, PreparationGit.children(cache.resolve("work")).size());
            }
        } finally { process.destroyForcibly().waitFor(); }
    }

    public static class Reader {
        public static void main(String[] args) throws Exception {
            try (var result = new GitPreparation(Path.of(args[0]), GitCacheConfig.DEFAULT, GitTransferConfig.DEFAULT)
                    .prepare(new GitPreparation.Request(args[1], null, null), s -> {})) {
                // The test reads the signal as soon as it exists, so it must never see it empty (#1844)
                var signal = Path.of(args[2]);
                var partial = Files.writeString(signal.resolveSibling(signal.getFileName() + ".partial"), result.repository().toString());
                Files.move(partial, signal, StandardCopyOption.ATOMIC_MOVE);
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            }
        }
    }

    private static void sameObjects(Path expected, Path actual) throws Exception {
        assertEquals(reachable(expected), allObjects(actual), "the physical object store must equal the selected remote closure");
    }

    private static java.util.Set<String> reachable(Path repo) throws Exception {
        return new HashSet<>(git(repo, "rev-list", "--objects", "--all", "--no-object-names").lines().toList());
    }

    private static Map<String, String> contents(Path root) throws IOException {
        var result = new TreeMap<String, String>();
        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) result.put(root.relativize(file).toString(), PreparationGit.hash(file));
        }
        return result;
    }
}
