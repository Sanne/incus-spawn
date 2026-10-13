package dev.incusspawn.command;

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
    private final List<String> scripts = new ArrayList<>();
    private final List<Path> mounts = new ArrayList<>();
    private boolean breakCopy;
    private String checksumPrelude = "";

    @BeforeEach void fixture() throws Exception {
        remote = releasesFixture(tmp);
        host = tmp.resolve("host");
        git(tmp, "clone", "-q", "--", remote.toUri().toString(), host.toString());
        commitBinary(host, "private work");
        git(host, "repack", "-a", "-d");
        incus = mock(IncusClient.class);
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(2);
            scripts.add(script);
            script = script.replaceAll("\\{ ! command -v mountpoint .*?; \\} && ", "");
            if (breakCopy && script.contains("git clone --no-hardlinks")) {
                // Produce a valid copy first, then alter a byte before the checksum phase.
                script = script.replace(" && { g=", " && for pack in "
                        + shellQuote(tmp.resolve("guest/.git/objects/pack").toString()) + "/*.pack; do "
                        + "chmod u+w \"$pack\" && printf damage >> \"$pack\" || exit 1; done && { g=");
            }
            var out = tmp.resolve("stdout");
            var err = tmp.resolve("stderr");
            var process = new ProcessBuilder("sh", "-c", checksumPrelude + script)
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            return new IncusClient.ExecResult(process.waitFor(), Files.readString(out), Files.readString(err));
        });
    }

    private BuildProgress.StepProgress run(String branch) {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl(remote.toUri().toString());
        repo.setBranch(branch);
        repo.setPath(tmp.resolve("guest").toString());
        repo.setPrime("git fsck --full --no-dangling");
        var planned = new RepoCloner.RepoReference("ref", "/mnt/ref", host.toString(), null,
                new GitPreparation.Request(repo.getUrl(), branch, host), null);
        var states = new AtomicReferenceArray<BuildProgress.StepProgress>(1);
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
        new RepoCloner(incus).prepareOne(new Container(incus, "test"), repo, planned, states, 0,
                new AtomicBoolean(), new java.util.concurrent.Semaphore(1), new java.util.concurrent.CountDownLatch(1), mounts);
        return states.get(0);
    }

    @Test void guestIsIndependentAndHasTheSameRefScopeAsAFullNetworkClone() throws Exception {
        var secret = git(host, "rev-parse", "HEAD");
        var result = run(null);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertTrue(result.note().startsWith("via "), result.note());
        assertEquals(1, scripts.stream().filter(s -> s.contains("git clone")).count());
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
        assertEquals(2, scripts.stream().filter(s -> s.contains("git clone")).count());
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
        assertEquals(corrupt ? 2 : 1, scripts.stream().filter(s -> s.contains("git clone")).count());
    }
}
