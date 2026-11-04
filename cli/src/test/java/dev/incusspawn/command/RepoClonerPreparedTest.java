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
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(server.url(remote));
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
        new RepoCloner(incus, preparation).prepareOne(new Container(incus, "test"), repo, planned, states, 0,
                new AtomicBoolean(), new java.util.concurrent.Semaphore(1), new java.util.concurrent.CountDownLatch(0), mounts);
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

    @Test void differentGuestCredentialRefScopeUsesOnlyTheGuestRemote() throws Exception {
        var visible = tmp.resolve("visible.git");
        git(tmp, "clone", "--bare", remote.toString(), visible.toString());
        git(visible, "config", "uploadpack.hideRefs", "refs/heads/stable");
        guestConfig = tmp.resolve("guest.config");
        git(tmp, "config", "--file", guestConfig.toString(),
                "url." + server.url(visible) + ".insteadOf", server.url(remote));
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertEquals("template and host repository refs differ", result.note());
        // The fallback clone in the guest says so, rather than leaving the last preparation phase up.
        assertEquals(List.of(BuildProgress.StepProgress.running("Cloning")), statesWhileCloning);
        assertTrue(mounts.isEmpty(), "the wider host selection must never be mounted");
        assertEquals("", git(tmp.resolve("guest"), "for-each-ref", "refs/remotes/origin/stable"));
        assertEquals("", git(tmp.resolve("guest"), "fsck", "--full", "--no-dangling"));
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
        assertEquals(new java.util.HashSet<>(git(remote, "rev-list", "--objects", "--all", "--no-object-names").lines().toList()),
                new java.util.HashSet<>(git(guest, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").lines().toList()));
    }

    @Test void aRemoteThatNeverHoldsStillFallsBackToTheGuestAfterBoundedReadings() throws Exception {
        // The guest's second reading is newer than the snapshot, and every later push lands between
        // the guest's reading and the host's, so the two never agree and no refresh may fetch.
        beforeGuestRead = n -> { if (n == 2) advanceRemote("pushed while the snapshot was prepared"); };
        afterGuestRead = n -> advanceRemote("push " + n);
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertEquals("template and host repository refs differ", result.note());
        // The access check, the reading after preparation, and one after each refresh the host declined.
        assertEquals(2 + RepoCloner.REF_DRIFT_REFRESHES, guestReads);
        assertTrue(mounts.isEmpty(), "a snapshot the guest did not see last must never be mounted");
        assertEquals(git(remote, "rev-parse", "HEAD"), git(tmp.resolve("guest"), "rev-parse", "HEAD"));
    }

    @Test void guestIsIndependentAndHasTheSameRefScopeAsAFullNetworkClone() throws Exception {
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
        assertEquals(git(remote, "rev-parse", "stable"), git(guest, "rev-parse", "origin/stable"));
        assertEquals("+refs/heads/*:refs/remotes/origin/*", git(guest, "config", "remote.origin.fetch"));
        commitBinary(remote, "later public change");
        git(guest, "pull", "--ff-only");
        assertEquals(git(remote, "rev-parse", "HEAD"), git(guest, "rev-parse", "HEAD"));
        assertTrue(scripts.stream().noneMatch(s -> s.contains("pack-objects")), "guest copy must not repack");
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
        assertEquals(new java.util.HashSet<>(git(remote, "rev-list", "--objects", "--all", "--no-object-names").lines().toList()),
                new java.util.HashSet<>(git(guest, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").lines().toList()));
        try (var packs = Files.list(guest.resolve(".git/objects/pack"))) {
            assertEquals(2, packs.filter(p -> p.toString().endsWith(".pack")).count());
        }
        assertTrue(mounts.stream().noneMatch(Files::exists), "all generations must detach before lease cleanup");
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
