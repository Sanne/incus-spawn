package dev.incusspawn.git;

import dev.incusspawn.config.GitCacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

class GitPreparationTest {
    @TempDir Path tmp;
    private Path remote;
    private Path host;
    private Path cache;
    private GitPreparation preparation;

    @BeforeEach void fixture() throws Exception {
        remote = releasesFixture(tmp);
        git(remote, "-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "v1", "HEAD~4", "-m", "first");
    }

    private void start() throws Exception {
        host = tmp.resolve("host");
        git(tmp, "clone", "-q", "--", remote.toUri().toString(), host.toString());
        git(host, "repack", "-a", "-d");
        cache = tmp.resolve("cache");
        preparation = new GitPreparation(cache, GitCacheConfig.DEFAULT);
    }

    private GitPreparation.Prepared prepare(Path checkout, String branch) throws IOException {
        return preparation.prepare(new GitPreparation.Request(remote.toUri().toString(), branch, checkout), s -> {});
    }

    @Test void cleanSnapshotsAreReusedWithoutChangingOrRequiringTheCheckout() throws Exception {
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

    @Test void mixedPacksExcludePrivateBranchesStashesAndUnreachableObjects() throws Exception {
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

    @Test void remoteAdvanceRewindDeletedBranchAndMovedTagsRefreshTheSnapshot() throws Exception {
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

    @Test void branchAndAnnotatedTagSelectionSurviveRemovingEverySource() throws Exception {
        start();
        try (var result = prepare(host, "v1")) {
            var guest = tmp.resolve("guest");
            git(tmp, "clone", "--no-hardlinks", "--branch", "v1", "--", result.repository().toString(), guest.toString());
            git(guest, "remote", "set-url", "origin", remote.toUri().toString());
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

    @Test void aPrivateBranchCannotSatisfyARequestedRemoteBranch() throws Exception {
        start();
        git(host, "branch", "only-here");
        assertThrows(IOException.class, () -> prepare(host, "only-here"));
        assertEquals(List.of(), PreparationGit.children(cache.resolve("work")));
    }

    @Test void sha256RepositoriesKeepTheirObjectFormat() throws Exception {
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

    @ParameterizedTest
    @ValueSource(strings = {"remove", "corrupt", "prune"})
    void changedSourceEitherRecoversOrRetriesWithoutAReference(String mutation) throws Exception {
        start();
        var phases = new ArrayList<String>();
        try (var result = preparation.prepare(new GitPreparation.Request(remote.toUri().toString(), null, host), phase -> {
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

    @Test void unavailableCheckoutDuringSelectionUsesTheRemoteDirectly() throws Exception {
        start();
        PreparationGit.delete(host);
        var phases = new ArrayList<String>();
        try (var result = preparation.prepare(new GitPreparation.Request(remote.toUri().toString(), null, host), phases::add)) {
            assertEquals("new clean snapshot", result.strategy());
            assertTrue(phases.contains("Host reference unavailable; cloning remote"));
            assertEquals(1, phases.stream().filter("Creating clean Git snapshot"::equals).count());
            sameObjects(remote, result.repository());
        }
    }

    @Test void failedIntegrityCheckRetriesBeforePublishing() throws Exception {
        start();
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        try (var result = preparation.prepare(new GitPreparation.Request(remote.toUri().toString(), null, host), phase -> {
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
            assertEquals(2, checks.get());
            assertEquals("new clean snapshot (reference retry)", result.strategy());
            sameObjects(remote, result.repository());
        }
    }

    @Test void aFailedRemoteRetryLeavesNoSnapshotOrWork() throws Exception {
        start();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IOException.class, () -> preparation.prepare(
                new GitPreparation.Request(remote.toUri().toString(), null, host), phase -> {
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

    @Test void completedDissociationNeedsNoFurtherAccessToTheSource() throws Exception {
        start();
        try (var result = preparation.prepare(new GitPreparation.Request(remote.toUri().toString(), null, host), phase -> {
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

    @Test void directReferenceUsesOnlyMatchingTrackingTipsAndCanReadLooseObjects() throws Exception {
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
        preparation = new GitPreparation(cache, GitCacheConfig.DEFAULT);
        var before = contents(host.resolve(".git"));
        try (var result = preparation.prepare(new GitPreparation.Request(remote.toUri().toString(), null, host), phase -> {
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

    @Test void looseOnlyCheckoutsCanSupplyAReferenceWithoutBeingPacked() throws Exception {
        start();
        PreparationGit.delete(host);
        git(tmp, "clone", "-q", "--no-hardlinks", "--", remote.toString(), host.toString());
        assertTrue(PreparationGit.children(host.resolve(".git/objects/pack")).isEmpty());
        var before = contents(host.resolve(".git"));
        try (var result = prepare(host, null)) {
            assertEquals("reference-seeded clean snapshot", result.strategy());
            sameObjects(remote, result.repository());
        }
        assertEquals(before, contents(host.resolve(".git")));
    }

    @Test void corruptSnapshotBytesAndMetadataAreRebuiltWithoutBorrowingThem() throws Exception {
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

    @Test void remoteHeadSwitchAndBranchTagPinsShareOnlyTheSameFullSelection() throws Exception {
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
            assertEquals("reference-seeded clean snapshot", result.strategy());
            assertEquals("ref: refs/heads/stable", Files.readString(result.repository().resolve("HEAD")).strip());
        }
        try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy()); }
    }

    @Test void aRemoteChangeBetweenAdvertisementAndCloneCannotMislabelTheSnapshot() throws Exception {
        start();
        try (var result = preparation.prepare(new GitPreparation.Request(remote.toUri().toString(), null, host), phase -> {
            if (phase.equals("Creating clean Git snapshot")) {
                try { commitBinary(remote, "published after advertisement"); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        })) { sameObjects(remote, result.repository()); }
        try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy()); }
    }

    @Test void unicodeRefNamesUseTheSameOrderingForAdvertisementsAndSnapshots() throws Exception {
        start();
        git(remote, "branch", "\uE000");
        git(remote, "branch", "\uD83D\uDE04");
        try (var result = prepare(host, null)) { sameObjects(remote, result.repository()); }
        try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy()); }
    }

    @Test void cachedSnapshotsStillRequireAnAccessibleAuthoritativeRemote() throws Exception {
        start();
        try (var result = prepare(host, null)) { }
        PreparationGit.delete(remote);
        assertThrows(IOException.class, () -> prepare(host, null));
        assertEquals(List.of(), PreparationGit.children(cache.resolve("work")));
    }

    @Test void emptyRemotesAreIndependentAndRefreshedWithoutGuessingTheUnbornHead() throws Exception {
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
            assertEquals("reference-seeded clean snapshot", result.strategy());
            sameObjects(remote, result.repository());
        }
        try (var result = prepare(null, null)) { assertEquals("cached clean snapshot", result.strategy()); }
    }

    @Test void snapshotsReplaceTheirSelectedClosureAndActiveReadersSurviveEviction() throws Exception {
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
            var disabled = new GitPreparation(cache, new GitCacheConfig(0, Duration.ZERO));
            try (var next = disabled.prepare(new GitPreparation.Request(remote.toUri().toString(), null, null), s -> {})) {
                assertEquals(oldObjects, allObjects(first.repository()));
                assertEquals(List.of(), PreparationGit.children(cache.resolve("snapshots")));
            }
        }
    }

    @Test void concurrentWritersPublishCompleteGenerationsAndReadersHaveSeparateLeases() throws Exception {
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

    @Test void aPublicDeltaIsReencodedWhenItsOnlyStoredBaseIsPrivate() throws Exception {
        start();
        byte[] publicBytes = Files.readAllBytes(remote.resolve("isx.deb"));
        var publicId = git(host, "rev-parse", "origin/main:isx.deb");
        var privateFile = host.resolve("private-base");
        Files.write(privateFile, publicBytes);
        Files.writeString(privateFile, "PRIVATE BASE SUFFIX", java.nio.file.StandardOpenOption.APPEND);
        var privateId = git(host, "hash-object", "-w", privateFile.toString());
        var others = allObjects(host);
        others.remove(publicId);
        others.remove(privateId);
        var objects = host.resolve(".git/new-objects");
        var packs = Files.createDirectories(objects.resolve("pack"));
        var input = tmp.resolve("other-objects");
        Files.write(input, others);
        PreparationGit.run(host, input, null, List.of("pack-objects", packs.resolve("pack").toString()));
        var bytes = new java.io.ByteArrayOutputStream();
        var stream = new java.io.DataOutputStream(bytes);
        stream.writeBytes("PACK");
        stream.writeInt(2);
        stream.writeInt(2);
        byte[] secret = Files.readAllBytes(privateFile);
        header(bytes, 3, secret.length);
        deflate(bytes, secret);
        int distance = bytes.size() - 12;
        var offset = new java.util.ArrayDeque<Integer>();
        offset.addFirst(distance & 127);
        while ((distance >>= 7) != 0) offset.addFirst(128 | (--distance & 127));
        var delta = new java.io.ByteArrayOutputStream();
        varint(delta, secret.length);
        varint(delta, publicBytes.length);
        delta.write(0xf0); // Copy from offset zero, with an explicit three-byte size.
        for (int i = 0; i < 3; i++) delta.write((publicBytes.length >> (i * 8)) & 255);
        header(bytes, 6, delta.size());
        for (int n : offset) bytes.write(n);
        deflate(bytes, delta.toByteArray());
        byte[] checksum = java.security.MessageDigest.getInstance("SHA-1").digest(bytes.toByteArray());
        bytes.write(checksum);
        var pack = packs.resolve("pack-" + java.util.HexFormat.of().formatHex(checksum) + ".pack");
        Files.write(pack, bytes.toByteArray());
        git(host, "index-pack", pack.toString());
        assertTrue(git(host, "verify-pack", "-v", pack.toString().replace(".pack", ".idx")).lines()
                .anyMatch(s -> s.startsWith(publicId) && s.endsWith(privateId)), "fixture must force the private delta base");
        PreparationGit.delete(host.resolve(".git/objects"));
        Files.move(objects, host.resolve(".git/objects"));
        try (var result = prepare(host, null)) {
            sameObjects(remote, result.repository());
            assertFalse(allObjects(result.repository()).contains(privateId));
            PreparationGit.delete(host);
            assertEquals("", git(result.repository(), "fsck", "--full", "--no-dangling"));
        }
    }

    @Test void readerLeaseWorksAcrossProcessesAndDeadReadersAreReclaimed() throws Exception {
        start();
        var signal = tmp.resolve("ready");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", System.getProperty("java.class.path"), Reader.class.getName(), cache.toString(),
                remote.toUri().toString(), signal.toString()).redirectErrorStream(true)
                .redirectOutput(tmp.resolve("reader.log").toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(signal) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(Files.exists(signal), Files.readString(tmp.resolve("reader.log")));
            var active = Path.of(Files.readString(signal));
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
            try (var result = new GitPreparation(Path.of(args[0]), GitCacheConfig.DEFAULT)
                    .prepare(new GitPreparation.Request(args[1], null, null), s -> {})) {
                Files.writeString(Path.of(args[2]), result.repository().toString());
                Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            }
        }
    }

    private static void header(java.io.ByteArrayOutputStream out, int kind, int size) {
        int first = (kind << 4) | (size & 15);
        size >>= 4;
        out.write(first | (size == 0 ? 0 : 128));
        while (size != 0) {
            int n = size & 127;
            size >>= 7;
            out.write(n | (size == 0 ? 0 : 128));
        }
    }

    private static void varint(java.io.ByteArrayOutputStream out, int size) {
        while (size >= 128) { out.write((size & 127) | 128); size >>= 7; }
        out.write(size);
    }

    private static void deflate(java.io.ByteArrayOutputStream out, byte[] bytes) throws IOException {
        var compressed = new java.util.zip.DeflaterOutputStream(out);
        compressed.write(bytes);
        compressed.finish();
    }

    private static void sameObjects(Path expected, Path actual) throws Exception {
        assertEquals(reachable(expected), allObjects(actual), "the physical object store must equal the selected remote closure");
    }

    private static java.util.Set<String> reachable(Path repo) throws Exception {
        return new HashSet<>(git(repo, "rev-list", "--objects", "--all", "--no-object-names").lines().toList());
    }

    private static java.util.Set<String> allObjects(Path repo) throws Exception {
        return new HashSet<>(git(repo, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").lines().toList());
    }

    private static Map<String, String> contents(Path root) throws IOException {
        var result = new TreeMap<String, String>();
        try (var files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) result.put(root.relativize(file).toString(), PreparationGit.hash(file));
        }
        return result;
    }
}
