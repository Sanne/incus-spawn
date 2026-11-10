package dev.incusspawn.command;

import dev.incusspawn.config.GitCacheConfig;
import dev.incusspawn.config.GitTransferConfig;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.git.GitPreparation;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static dev.incusspawn.git.GitTestUtils.*;
import static dev.incusspawn.incus.Container.shellQuote;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Execute the production preparation and guest-copy path with real Git and a shell as the guest. */
@ExtendWith(IsolatedHome.class)
class RepoClonerPreparedTest {
    @TempDir Path tmp;
    private Path remote;
    private Path host;
    private IncusClient incus;
    private dev.incusspawn.git.GitPreparation preparation;
    private dev.incusspawn.git.GitTestServer server;
    private final List<String> scripts = new ArrayList<>();
    private final List<Path> mounts = new ArrayList<>();
    private boolean breakCopy;
    private boolean foreignSnapshot;
    private int foreignClones;
    private Path guestConfig;
    private String checksumPrelude = "";
    /** Run before and after the guest's n-th ls-remote, counting from 1, so a test can move the remote between readings. */
    private java.util.function.IntConsumer beforeGuestRead = n -> {}, afterGuestRead = n -> {};
    private int guestReads;
    private final AtomicReferenceArray<BuildProgress.StepProgress> states = new AtomicReferenceArray<>(1);
    private final List<BuildProgress.StepProgress> statesWhileCloning = new ArrayList<>();

    @org.junit.jupiter.api.AfterEach void stopServer() throws Exception { if (server != null) server.close(); }

    @BeforeEach void fixture() throws Exception {
        server = new dev.incusspawn.git.GitTestServer(tmp);
        remote = releasesFixture(tmp);
        host = tmp.resolve("host");
        git(tmp, "clone", "-q", "--", server.url(remote), host.toString());
        commitBinary(host, "private work");
        git(host, "repack", "-a", "-d");
        preparation = new GitPreparation(GitCacheConfig.DEFAULT, GitTransferConfig.DEFAULT);
        incus = mock(IncusClient.class);
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(2);
            scripts.add(script);
            if (script.contains(" ls-remote ")) beforeGuestRead.accept(++guestReads);
            if (script.contains(" clone ")) statesWhileCloning.add(states.get(0));
            script = script.replaceAll("\\{ ! command -v mountpoint .*?; \\} && ", "");
            if (breakCopy && script.contains("clone --no-hardlinks")) {
                // Produce a valid copy first, then alter a byte before the checksum phase.
                script = script.replace(" && { g=", " && for pack in "
                        + shellQuote(tmp.resolve("guest/.git/objects/pack").toString()) + "/*.pack; do "
                        + "chmod u+w \"$pack\" && printf damage >> \"$pack\" || exit 1; done && { g=");
            }
            if (foreignSnapshot) {
                // The macOS guest sees the snapshot owned by the Mac user's unmapped uid (#1590).
                // Only the clone that reads it sees a foreign owner; the guest's own checkout stays ours.
                // The pattern matches the clone with or without its exception, so this test fails without the fix.
                var foreign = script.replaceFirst("git (-c 'safe\\.directory=[^']*' )?clone --no-hardlinks",
                        "GIT_TEST_ASSUME_DIFFERENT_OWNER=1 $0");
                if (!foreign.equals(script)) foreignClones++;
                script = foreign;
            }
            var out = tmp.resolve("stdout");
            var err = tmp.resolve("stderr");
            var builder = new ProcessBuilder("sh", "-c", checksumPrelude + script)
                    .redirectOutput(out.toFile()).redirectError(err.toFile());
            if (guestConfig != null) builder.environment().put("GIT_CONFIG_GLOBAL", guestConfig.toString());
            var process = builder.start();
            var result = new IncusClient.ExecResult(process.waitFor(), Files.readString(out), Files.readString(err));
            if (script.contains(" ls-remote ")) afterGuestRead.accept(guestReads);
            return result;
        });
    }

    private BuildProgress.StepProgress run(String branch) {
        return run(branch, server.url(remote));
    }

    private BuildProgress.StepProgress run(String branch, String url) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(url);
        repo.setBranch(branch);
        repo.setPath(tmp.resolve("guest").toString());
        repo.setPrime(List.of("git fsck --full --no-dangling"));
        var planned = new RepoCloner.RepoReference("ref", "/mnt/ref", host.toString(), null,
                new GitPreparation.Request(repo.getUrl(), branch, host), null);
        var mounts = new RepoCloner.ReferenceMounts() {
            @Override public RepoCloner.RepoReference attach(int index, RepoCloner.RepoReference ref) {
                var path = ref.prepared().repository();
                RepoClonerPreparedTest.this.mounts.add(path);
                assertFalse(Files.exists(path.resolve("objects/info/alternates")));
                return new RepoCloner.RepoReference(ref.deviceName(), path.toString(), ref.source(), null, null, ref.prepared());
            }
            @Override public boolean detach(int index) {
                assertTrue(Files.isDirectory(RepoClonerPreparedTest.this.mounts.getLast()), "lease released before detach");
                return true;
            }
        };
        var cloner = new RepoCloner(incus, preparation);
        try {
            cloner.prepareOne(new Container(incus, "test"), repo, planned, states, 0,
                    new AtomicBoolean(), new java.util.concurrent.Semaphore(1), new java.util.concurrent.CountDownLatch(0), mounts);
        } finally {
            // As cloneRepos does: a read a denied guest cut may still be releasing its lease in the cache (#2102).
            cloner.awaitRemoteReads();
        }
        return states.get(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deniedTemplateAccountCannotReceiveEvenAWarmHostSnapshot(boolean warm) throws Exception {
        if (warm) {
            try (var snapshot = new GitPreparation(GitCacheConfig.DEFAULT, GitTransferConfig.DEFAULT)
                    .prepare(new GitPreparation.Request(server.url(remote), null, host), phase -> {})) { }
        }
        guestConfig = tmp.resolve("guest.config");
        git(tmp, "config", "--file", guestConfig.toString(),
                "url." + server.url(tmp.resolve("denied")) + ".insteadOf", server.url(remote));
        var result = run(null);
        assertEquals(BuildProgress.StepState.FAILED, result.state());
        assertTrue(mounts.isEmpty(), "no prepared data may enter an unauthorized guest");
        assertFalse(Files.exists(tmp.resolve("guest")));
        assertTrue(scripts.stream().noneMatch(script -> script.contains("git fsck")), "no prime on denied access");
    }

    @Test void aDeniedGuestReturnsOnlyOnceTheHostReadHasReleasedItsLease() throws Exception {
        // The host's read outlives the guest's denial (#2102): a remote that never answers keeps it running,
        // and holding the cache's catalogue keeps the cut read from releasing its lease until the test lets it.
        try (var silent = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            var connected = new java.util.concurrent.CountDownLatch(1);
            var held = new java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket>();
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) { held.add(silent.accept()); connected.countDown(); }
                } catch (java.io.IOException closed) { }
            });
            var url = "git://127.0.0.1:" + silent.getLocalPort() + "/remote";
            guestConfig = tmp.resolve("guest.config");
            git(tmp, "config", "--file", guestConfig.toString(), "url." + server.url(tmp.resolve("denied")) + ".insteadOf", url);
            var work = dev.incusspawn.Environment.cacheDir().resolve("git/work");
            var locked = new java.util.concurrent.CountDownLatch(1);
            var returned = new java.util.concurrent.CountDownLatch(1);
            var tester = Thread.currentThread();
            Thread[] holder = new Thread[1];
            beforeGuestRead = n -> {
                try {
                    // The host's read holds its lease once it has connected.
                    assertTrue(connected.await(30, java.util.concurrent.TimeUnit.SECONDS), "the host never read the remote");
                    holder[0] = Thread.ofPlatform().start(() -> {
                        try (var catalogue = dev.incusspawn.util.HostLock.acquire(
                                work.resolveSibling("catalogue.lock"), "test", s -> {})) {
                            locked.countDown();
                            // Released once the call waits for the cut read, or once the test has listed the leases:
                            // a call that returns without waiting always finds the lease, however slow the guest.
                            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                            while (returned.getCount() > 0 && System.nanoTime() < deadline
                                    && java.util.Arrays.stream(tester.getStackTrace())
                                            .noneMatch(frame -> frame.getMethodName().equals("awaitRemoteReads"))) {
                                Thread.sleep(1);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    assertTrue(locked.await(30, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            };
            try {
                var result = run(null, url);
                String[] leases;
                try (var dirs = Files.list(work)) { leases = dirs.map(Path::toString).toArray(String[]::new); }
                assertEquals(BuildProgress.StepState.FAILED, result.state());
                assertArrayEquals(new String[0], leases, "the host's read must release its lease before the test's home is removed");
            } finally {
                returned.countDown();
                if (holder[0] != null) holder[0].join();
                for (var socket : held) socket.close();
            }
        }
    }

    @Test void aFailedPreparationKeepsGitsWordsApartForTheWarning() {
        var cloner = new RepoCloner(incus, preparation);
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(server.url(tmp.resolve("missing")));
        var planned = new RepoCloner.RepoReference("ref", "/mnt/ref", host.toString(), null,
                new GitPreparation.Request(repo.getUrl(), null, host), null);
        var ref = cloner.prepareReference(repo, planned, states, 0, "", () -> null);
        assertEquals("Git preparation unavailable: Git preparation failed: ls-remote", ref.skipReason());
        var details = cloner.preparationFailures.get(0).details();
        assertEquals("Git's output:", details.getFirst(), details.toString());
        assertTrue(details.get(1).startsWith("  | fatal: "), details.toString());
    }

    @Test void aSnapshotTooLargeToKeepIsReportedForTheWarning() throws Exception {
        var cloner = new RepoCloner(incus, new GitPreparation(tmp.resolve("small-cache"),
                new GitCacheConfig(1, java.time.Duration.ofDays(7)), GitTransferConfig.DEFAULT));
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(server.url(remote));
        var planned = new RepoCloner.RepoReference("ref", "/mnt/ref", host.toString(), null,
                new GitPreparation.Request(repo.getUrl(), null, host), null);
        var advertisement = git(tmp, "ls-remote", "--symref", "--", repo.getUrl(), "HEAD", "refs/heads/*", "refs/tags/*");
        var ref = cloner.prepareReference(repo, planned, states, 0, advertisement, () -> advertisement);
        assertNotNull(ref.prepared(), ref.skipReason());
        try {
            assertTrue(cloner.preparationNotRetained.get(0).endsWith(
                    "exceeds git-cache.snapshot-max-mib (0 MiB); it was used for this build but not kept"),
                    String.valueOf(cloner.preparationNotRetained));
        } finally {
            ref.prepared().close();
        }
    }

    @Test void aGuestThatSeesTheRequestedBranchAtAnotherIdUsesOnlyTheGuestRemote() throws Exception {
        // The guest's account is served another main than the host's: the host's must never be mounted.
        var visible = tmp.resolve("visible.git");
        git(tmp, "clone", "--bare", remote.toString(), visible.toString());
        var work = tmp.resolve("guest-work");
        git(tmp, "clone", "-q", visible.toString(), work.toString());
        commitBinary(work, "only the guest's account sees this");
        git(work, "push", "-q", "origin", "main");
        guestConfig = tmp.resolve("guest.config");
        git(tmp, "config", "--file", guestConfig.toString(),
                "url." + server.url(visible) + ".insteadOf", server.url(remote));
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertEquals("template and host repository refs differ", result.note());
        // The fallback clone in the guest says so, rather than leaving the last preparation phase up.
        assertEquals(List.of(BuildProgress.StepProgress.running("Cloning")), statesWhileCloning);
        assertTrue(mounts.isEmpty(), "a snapshot of another ID must never be mounted");
        assertEquals(git(work, "rev-parse", "HEAD"), git(tmp.resolve("guest"), "rev-parse", "HEAD"));
        assertEquals("", git(tmp.resolve("guest"), "fsck", "--full", "--no-dangling"));
    }

    @Test void aBranchHiddenFromTheGuestIsNeitherInItsSnapshotNorFetchedLater() throws Exception {
        // Before #1990 a guest whose account saw fewer refs never matched; the snapshot now holds main alone.
        var visible = tmp.resolve("visible.git");
        git(tmp, "clone", "--bare", remote.toString(), visible.toString());
        git(visible, "config", "uploadpack.hideRefs", "refs/heads/stable");
        guestConfig = tmp.resolve("guest.config");
        git(tmp, "config", "--file", guestConfig.toString(),
                "url." + server.url(visible) + ".insteadOf", server.url(remote));
        var hidden = git(remote, "rev-parse", "stable");
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertEquals("via reference-seeded clean snapshot", result.note());
        assertEquals(1, mounts.size());
        var guest = tmp.resolve("guest");
        assertFalse(allObjects(guest.resolve(".git")).contains(hidden));
        // A later fetch is the guest's own, so it brings only what the guest's account sees.
        git(guest, "-c", "url." + server.url(visible) + ".insteadOf=" + server.url(remote), "fetch", "-q", "--tags", "origin");
        assertEquals("", git(guest, "for-each-ref", "refs/remotes/origin/stable"));
        assertFalse(allObjects(guest.resolve(".git")).contains(hidden));
        assertEquals("", git(guest, "fsck", "--full", "--no-dangling"));
    }

    private void advanceRemote(String message) {
        try {
            commitBinary(remote, message);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test void aRemoteThatMovedBeforePreparationIsMatchedByReadingTheGuestAgain() throws Exception {
        // The guest's access check predates the snapshot, which already holds the newer commit (#1980).
        afterGuestRead = n -> { if (n == 1) advanceRemote("pushed after the access check"); };
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertEquals("via reference-seeded clean snapshot", result.note());
        assertEquals(2, guestReads);
        assertEquals(1, mounts.size());
        assertEquals(git(remote, "rev-parse", "HEAD"), git(tmp.resolve("guest"), "rev-parse", "HEAD"));
    }

    @Test void aRemoteThatMovedDuringPreparationRefreshesTheSnapshotInsteadOfCloningInTheGuest() throws Exception {
        // The snapshot is newer than the access check, and the remote moves again before the guest reads it.
        afterGuestRead = n -> { if (n == 1) advanceRemote("pushed after the access check"); };
        beforeGuestRead = n -> { if (n == 2) advanceRemote("pushed while the snapshot was prepared"); };
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertEquals("via reference-seeded clean snapshot (refreshed)", result.note());
        // The refresh fetched what the guest's second reading saw, so it needs no third (#1992).
        assertEquals(2, guestReads);
        assertEquals(1, mounts.size(), "only the refreshed snapshot is mounted");
        assertEquals(1, scripts.stream().filter(s -> s.contains(" clone ")).count(), "no network clone in the guest");
        var guest = tmp.resolve("guest");
        assertEquals(git(remote, "rev-parse", "HEAD"), git(guest, "rev-parse", "HEAD"));
        assertEquals("", git(guest, "fsck", "--full", "--no-dangling"));
        assertEquals(new java.util.HashSet<>(git(remote, "rev-list", "--objects", "HEAD", "--no-object-names").lines().toList()),
                new java.util.HashSet<>(git(guest, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").lines().toList()));
    }

    @Test void aRemoteThatNeverHoldsStillFallsBackToTheGuestAfterBoundedReadings() throws Exception {
        // The guest's second reading is newer than the snapshot, and every later push lands between the
        // guest's reading and the host's fetch, so the host never holds the guest's ID for the branch.
        beforeGuestRead = n -> { if (n == 2) advanceRemote("pushed while the snapshot was prepared"); };
        afterGuestRead = n -> advanceRemote("push " + n);
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertEquals("template and host repository refs differ", result.note());
        // The access check, the reading after preparation, and one after each refresh that could not settle.
        assertEquals(2 + RepoCloner.REF_DRIFT_REFRESHES, guestReads);
        assertTrue(mounts.isEmpty(), "a snapshot the guest did not see last must never be mounted");
        assertEquals(git(remote, "rev-parse", "HEAD"), git(tmp.resolve("guest"), "rev-parse", "HEAD"));
    }

    @Test void guestIsIndependentAndFetchesOtherBranchesItself() throws Exception {
        var secret = git(host, "rev-parse", "HEAD");
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertTrue(result.note().startsWith("via "), result.note());
        assertEquals(1, scripts.stream().filter(s -> s.contains(" clone ")).count());
        assertEquals(1, mounts.size());
        assertFalse(Files.exists(mounts.getFirst()), "preparation lease should be released after detach");
        var guest = tmp.resolve("guest");
        assertEquals(git(remote, "rev-parse", "HEAD"), git(guest, "rev-parse", "HEAD"));
        assertFalse(git(guest, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").contains(secret));
        // Only the requested branch is copied (#1990); the broad refspec brings the others with a fetch.
        assertEquals("", git(guest, "for-each-ref", "refs/remotes/origin/stable"));
        assertEquals("+refs/heads/*:refs/remotes/origin/*", git(guest, "config", "remote.origin.fetch"));
        git(guest, "fetch", "-q", "origin");
        assertEquals(git(remote, "rev-parse", "stable"), git(guest, "rev-parse", "origin/stable"));
        commitBinary(remote, "later public change");
        git(guest, "pull", "--ff-only");
        assertEquals(git(remote, "rev-parse", "HEAD"), git(guest, "rev-parse", "HEAD"));
        assertTrue(scripts.stream().noneMatch(s -> s.contains("pack-objects")), "guest copy must not repack");
    }

    @Test void aMountedSnapshotDescribesAsTheNetworkCloneDoes() throws Exception {
        // A prime reading tags (git describe, setuptools-scm, jgitver) must not depend on the clone's path (#2051).
        var tag = List.of("-c", "user.name=t", "-c", "user.email=t@t", "tag");
        gitArgs(remote, tag, "-a", "v1.0", "main~3", "-m", "annotated");
        gitArgs(remote, tag, "v1.1", "main~1");
        gitArgs(remote, tag, "-a", "nested", "v1.0", "-m", "a tag of a tag");
        gitArgs(remote, tag, "-a", "stable-only", "stable", "-m", "outside main's history");
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertTrue(result.note().startsWith("via "), result.note());
        assertEquals(1, mounts.size());
        var guest = tmp.resolve("guest");
        var network = tmp.resolve("network");
        git(tmp, "clone", "-q", "--", server.url(remote), network.toString());
        for (var describe : List.of(List.of("describe", "--tags"), List.of("describe"), List.of("describe", "--tags", "HEAD~2"))) {
            assertEquals(gitArgs(network, describe), gitArgs(guest, describe), describe.toString());
        }
        assertEquals(git(network, "tag", "-l", "--merged", "HEAD"), git(guest, "tag", "-l"));
        assertEquals(git(network, "for-each-ref", "refs/tags/v1.0", "refs/tags/v1.1", "refs/tags/nested"),
                git(guest, "for-each-ref", "refs/tags/"));
        assertFalse(allObjects(guest.resolve(".git")).contains(git(remote, "rev-parse", "stable")),
                "a tag outside the requested history brings no history");
        assertEquals("", git(guest, "fsck", "--full", "--no-dangling"));
    }

    private static String gitArgs(Path repo, List<String> args, String... more) throws Exception {
        var all = new ArrayList<>(args);
        all.addAll(List.of(more));
        return git(repo, all.toArray(String[]::new));
    }

    @Test void snapshotOwnedByAnotherUidIsTrustedForItsOwnCloneOnly() throws Exception {
        foreignSnapshot = true;
        guestConfig = tmp.resolve("guest.config");
        Files.writeString(guestConfig, "");
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertTrue(result.note().startsWith("via "), result.note());
        assertEquals(1, foreignClones, "the clone must have seen a foreign owner");
        // Exactly the mounted snapshot is trusted, never "*" or a wider path.
        var exception = "git -c " + shellQuote("safe.directory=" + mounts.getFirst()) + " clone --no-hardlinks";
        assertEquals(1, scripts.stream().filter(s -> s.contains(" clone ")).count());
        assertTrue(scripts.stream().anyMatch(s -> s.contains(exception)), String.join("\n", scripts));
        // The exception is on the command line only: neither the guest's global config nor the checkout keeps it.
        assertEquals("", Files.readString(guestConfig));
        assertFalse(git(tmp.resolve("guest"), "config", "--list", "--local").contains("safe.directory"));
    }

    @Test void incrementalSnapshotCopiesAllPacksAndVerifiesTheGuestBeforePriming() throws Exception {
        var first = run(null);
        assertEquals(BuildProgress.StepState.DONE, first.state(), first.detail());
        try (var files = Files.walk(tmp.resolve("guest"))) {
            for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }
        commitBinary(remote, "incremental upstream update");
        var next = run(null);
        assertEquals(BuildProgress.StepState.DONE, next.state(), next.detail());
        assertEquals("via incremental clean snapshot", next.note());
        var guest = tmp.resolve("guest");
        assertEquals(git(remote, "rev-parse", "HEAD"), git(guest, "rev-parse", "HEAD"));
        assertEquals("", git(guest, "fsck", "--full", "--no-dangling"));
        assertEquals(new java.util.HashSet<>(git(remote, "rev-list", "--objects", "HEAD", "--no-object-names").lines().toList()),
                new java.util.HashSet<>(git(guest, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").lines().toList()));
        try (var packs = Files.list(guest.resolve(".git/objects/pack"))) {
            assertEquals(2, packs.filter(p -> p.toString().endsWith(".pack")).count());
        }
        assertTrue(mounts.stream().noneMatch(Files::exists), "all generations must detach before lease cleanup");
    }

    @Test void aPinnedBranchGetsOnlyItsOwnHistoryAndNamesNoWrongDefault() throws Exception {
        // The template asks for stable: the snapshot is stable's history alone, not every branch (#1990).
        commitBinary(remote, "only on main");
        var onlyMain = git(remote, "rev-parse", "main");
        var result = run("stable");
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertTrue(result.note().startsWith("via "), result.note());
        assertEquals(1, mounts.size());
        var guest = tmp.resolve("guest");
        assertEquals("stable", git(guest, "branch", "--show-current"));
        assertEquals(git(remote, "rev-parse", "stable"), git(guest, "rev-parse", "HEAD"));
        assertFalse(allObjects(guest.resolve(".git")).contains(onlyMain));
        assertEquals("refs/remotes/origin/stable", git(guest, "for-each-ref", "--format=%(refname)", "refs/remotes"),
                "no origin/HEAD naming stable as the remote's default");
        assertEquals("", git(guest, "fsck", "--full", "--no-dangling"));
        git(guest, "fetch", "-q", "origin");
        assertEquals(onlyMain, git(guest, "rev-parse", "origin/main"));
    }

    @Test void requestedAnnotatedTagChecksOutDetached() throws Exception {
        git(remote, "-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "release", "HEAD~2", "-m", "tag");
        var result = run("release");
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertTrue(result.note().startsWith("via "), result.note());
        assertEquals("", git(tmp.resolve("guest"), "branch", "--show-current"));
        assertEquals(git(remote, "rev-parse", "release^{commit}"), git(tmp.resolve("guest"), "rev-parse", "HEAD"));
    }

    @Test void changedCopyIsDiscardedAndFallsBackToTheRemoteBeforePriming() throws Exception {
        breakCopy = true;
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertTrue(result.note().contains("checksum failed"), result.note());
        assertEquals(2, scripts.stream().filter(s -> s.contains(" clone ")).count());
        assertEquals("", git(tmp.resolve("guest"), "fsck", "--full", "--no-dangling"));
        assertFalse(Files.exists(mounts.getFirst()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checksumToolRequiringExplicitStdinAcceptsGoodCopiesAndRejectsCorruptOnes(boolean corrupt) {
        // Reproduce macOS sha256sum's requirement for an input file even on GNU/Linux.
        // Keep a real SHA-256 check behind it, including the shasum fallback on older macOS.
        checksumPrelude = "sha256sum() { [ \"$#\" -eq 2 ] && [ \"$1\" = -c ] && [ \"$2\" = - ] || return 64; "
                + "if command -v shasum >/dev/null 2>&1; then command shasum -a 256 -c -; "
                + "else command sha256sum -c -; fi; }; ";
        breakCopy = corrupt;
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertTrue(corrupt ? result.note().contains("checksum failed") : result.note().startsWith("via "), result.note());
        assertEquals(corrupt ? 2 : 1, scripts.stream().filter(s -> s.contains(" clone ")).count());
    }
}
