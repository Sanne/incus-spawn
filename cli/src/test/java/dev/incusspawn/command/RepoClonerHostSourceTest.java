package dev.incusspawn.command;

import dev.incusspawn.config.GitCacheConfig;
import dev.incusspawn.config.GitTransferConfig;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.git.GitPreparation;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static dev.incusspawn.git.GitTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real export and guest shell commands, also run by the macOS unit-test job. */
@ExtendWith(IsolatedHome.class)
class RepoClonerHostSourceTest {
    @TempDir Path tmp;
    final List<String> scripts = new ArrayList<>();
    final AtomicReferenceArray<BuildProgress.StepProgress> states = new AtomicReferenceArray<>(1);
    boolean mounted;
    boolean damageCopy;
    boolean failMount;
    boolean failDetach;
    Path snapshot;

    @org.junit.jupiter.api.BeforeEach void canonicalHome() throws Exception {
        // Preparation resolves cache paths; use the same spelling for macOS's /var -> /private/var.
        System.setProperty("user.home", Path.of(System.getProperty("user.home")).toRealPath().toString());
    }

    private BuildProgress.StepProgress copy(Path host, String pin) throws Exception {
        var incus = mock(IncusClient.class);
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(2);
            scripts.add(script);
            if (script.startsWith("cd ")) assertFalse(mounted, "prime must follow detachment");
            script = script.replaceAll("\\{ ! command -v mountpoint .*?; \\} && ", "");
            if (damageCopy && script.contains("git clone")) {
                script = script.replace(" && { g=", " && printf damage >> "
                        + Container.shellQuote(tmp.resolve("guest/.git/objects/extra").toString()) + " && { g=");
            }
            var out = tmp.resolve("stdout");
            var err = tmp.resolve("stderr");
            var process = new ProcessBuilder("sh", "-c", script)
                    .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            return new IncusClient.ExecResult(process.waitFor(), Files.readString(out), Files.readString(err));
        });
        var repo = new ImageDef.RepoEntry();
        repo.setHostSource(host.toString());
        repo.setPath(tmp.resolve("guest").toString());
        repo.setBranch(pin);
        repo.setPrime("git fsck --full --no-dangling");
        var planned = new RepoCloner.RepoReference("ref", "/mnt/ref", host.toString(), null);
        var mounts = new RepoCloner.ReferenceMounts() {
            public RepoCloner.RepoReference attach(int idx, RepoCloner.RepoReference ref) {
                assertNotNull(ref.prepared());
                snapshot = ref.prepared().repository();
                assertNotEquals(host, snapshot);
                if (failMount) return RepoCloner.RepoReference.skipped("mount failed");
                mounted = true;
                return new RepoCloner.RepoReference("ref", snapshot.toString(), ref.source(), null, null, ref.prepared());
            }
            public boolean detach(int idx) {
                assertTrue(Files.isDirectory(snapshot));
                if (failDetach) return false;
                mounted = false;
                return true;
            }
        };
        var preparation = new GitPreparation(tmp.resolve("cache"), GitCacheConfig.DEFAULT, GitTransferConfig.DEFAULT);
        new RepoCloner(incus, preparation).prepareOne(new Container(incus, "test"), repo, planned, states, 0,
                new AtomicBoolean(), new java.util.concurrent.Semaphore(1), new java.util.concurrent.CountDownLatch(0), mounts);
        return states.get(0);
    }

    private Path source() throws Exception {
        var host = releasesFixture(tmp);
        git(host, "-c", "user.name=t", "-c", "user.email=t@t", "tag", "-a", "release", "stable", "-m", "release");
        git(host, "tag", "stable", "main"); // A branch wins over a same-named tag.
        git(host, "remote", "add", "origin", "ssh://corporate.invalid/private");
        return host;
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"main", "stable", "release"})
    void checkoutSelectionKeepsAllLocalBranchesAndTagsAndIsIndependent(String pin) throws Exception {
        var host = source();
        var expected = git(host, "rev-parse", pin == null ? "HEAD" : pin.equals("stable") ? "refs/heads/stable" : pin + "^{commit}");
        var refs = git(host, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads", "refs/tags");
        var result = copy(host, pin);
        assertEquals(BuildProgress.StepState.DONE, result.state(), result.detail());
        assertEquals("via host source", result.note());
        assertFalse(Files.exists(snapshot));
        var guest = tmp.resolve("guest");
        assertEquals(expected, git(guest, "rev-parse", "HEAD"));
        assertEquals(refs, git(guest, "for-each-ref", "--format=%(refname) %(objectname)", "refs/heads", "refs/tags"));
        assertEquals("", git(guest, "remote"));
        assertTrue(scripts.stream().noneMatch(s -> s.contains("ls-remote") || s.contains("corporate.invalid")));
        try (var paths = Files.walk(host)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        assertEquals("", git(guest, "fsck", "--full", "--no-dangling"));
        if ("release".equals(pin)) assertEquals("HEAD", git(guest, "rev-parse", "--abbrev-ref", "HEAD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"copy", "mount", "detach", "missing"})
    void failuresNeverFallbackToNetworkOrPrime(String failure) throws Exception {
        var host = source();
        damageCopy = failure.equals("copy");
        failMount = failure.equals("mount");
        failDetach = failure.equals("detach");
        var result = copy(host, failure.equals("missing") ? "missing" : null);
        assertEquals(BuildProgress.StepState.FAILED, result.state());
        assertTrue(scripts.stream().noneMatch(s -> s.startsWith("cd ") || s.contains("ls-remote") || s.contains("corporate.invalid")));
        assertEquals(failure.equals("copy") || failure.equals("detach") ? 1 : 0,
                scripts.stream().filter(s -> s.contains("git clone")).count());
        if (snapshot != null) assertEquals(failDetach, Files.exists(snapshot), "retain lease if still mounted");
    }
    @Test void projectLocalDefinitionCannotExportAHostSourceEvenFromStoredMetadata() throws Exception {
        var def = new ImageDef();
        def.setName("tpl-private");
        def.setProjectRoot(tmp);
        var repo = new ImageDef.RepoEntry();
        repo.setHostSource(source().toString());
        def.setRepos(List.of(repo));
        var incus = mock(IncusClient.class);
        assertThrows(dev.incusspawn.incus.IncusException.class, () -> new RepoCloner(incus).cloneRepos(
                new Container(incus, "test"), def, java.util.Map.of(), dev.incusspawn.incus.MachineType.CONTAINER));
        verifyNoInteractions(incus);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(dev.incusspawn.incus.MachineType.class)
    void buildTransfersOnlyPreparedRepositoryAndRecordsCanonicalAssociation(dev.incusspawn.incus.MachineType type) throws Exception {
        var host = source();
        var link = tmp.resolve("source-link");
        Files.createSymbolicLink(link, host);
        var guest = tmp.resolve("guest");
        var incus = mock(IncusClient.class);
        var deviceSource = new java.util.concurrent.atomic.AtomicReference<Path>();
        doAnswer(inv -> {
            String[] args = (String[]) inv.getRawArguments()[3];
            for (var arg : args) if (arg.startsWith("source=")) {
                var path = dev.incusspawn.config.HostResourceSetup.hostPathOfDeviceSource(arg.substring(7));
                deviceSource.set(path);
                assertNotEquals(host, path);
                assertTrue(Files.exists(path.resolve("HEAD")));
            }
            assertTrue(List.of(args).contains("readonly=true"));
            return null;
        }).when(incus).deviceAdd(eq("test"), anyString(), eq("disk"), any(String[].class));
        when(incus.execInContainer(eq("test"), eq("agentuser"), anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(2);
            script = script.replaceAll("\\{ ! command -v mountpoint .*?; \\} && ", "")
                    .replace("/mnt/isx-host-repo-0", deviceSource.get().toString());
            var out = tmp.resolve("stdout");
            var err = tmp.resolve("stderr");
            var process = new ProcessBuilder("sh", "-c", script).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            return new IncusClient.ExecResult(process.waitFor(), Files.readString(out), Files.readString(err));
        });
        var repo = new ImageDef.RepoEntry();
        repo.setHostSource(link.toString());
        repo.setPath(guest.toString());
        var def = new ImageDef();
        def.setName("tpl-private");
        def.setRepos(List.of(repo));
        new RepoCloner(incus).cloneRepos(new Container(incus, "test"), def, java.util.Map.of(), type);
        var stamp = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(incus).configSet(eq("test"), eq(dev.incusspawn.incus.Metadata.HOST_REPOSITORIES), stamp.capture());
        assertEquals(java.util.Map.of(guest.toString(), host.toRealPath().toString()),
                dev.incusspawn.git.HostRepoSources.parse(stamp.getValue()));
        verify(incus).deviceRemove("test", "ref-host-0");
        assertFalse(Files.exists(deviceSource.get()));
        assertEquals("", git(guest, "remote"));
    }

}
