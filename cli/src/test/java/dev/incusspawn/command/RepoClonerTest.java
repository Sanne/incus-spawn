package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepoClonerTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult FAIL = new IncusClient.ExecResult(1, "", "");

    @Test
    void cloneReposRunsPrimeCommand() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        // Clone, refspec restore, and prime all run as captured exec as agentuser.
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/quarkusio/quarkus.git");
        repo.setPath("~/quarkus");
        repo.setPrime("mvn -B dependency:go-offline");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-quarkus");
        imageDef.setRepos(List.of(repo));

        var cmd = new RepoCloner(incus);
        cmd.cloneRepos(container, imageDef, MachineType.CONTAINER);

        verify(incus).execInContainer("test", "agentuser",
                "git clone --single-branch -- 'https://github.com/quarkusio/quarkus.git' '/home/agentuser/quarkus'");
        verify(incus).execInContainer("test", "agentuser",
                "git -C '/home/agentuser/quarkus' remote set-branches origin '*'");
        verify(incus).execInContainer("test", "agentuser",
                "cd '/home/agentuser/quarkus' && mvn -B dependency:go-offline");
    }

    @Test
    void cloneReposWithBranch() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        repo.setBranch("feature/my branch");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = new RepoCloner(incus);
        cmd.cloneRepos(container, imageDef, MachineType.CONTAINER);

        verify(incus).execInContainer("test", "agentuser",
                "git clone --single-branch --branch 'feature/my branch' -- 'https://github.com/owner/repo.git' '/home/agentuser/repo'");
        verify(incus).execInContainer("test", "agentuser",
                "git -C '/home/agentuser/repo' remote set-branches origin '*'");
    }

    @Test
    void cloneReposSkipsPrimeWhenNotSet() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");

        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        // no prime set

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = new RepoCloner(incus);
        cmd.cloneRepos(container, imageDef, MachineType.CONTAINER);

        // Clone call + refspec restore, but no prime
        verify(incus, times(2)).execInContainer(eq("test"), anyString(), anyString());
    }

    @Test
    void cloneReposWithReferenceLocalClone() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = spy(new RepoCloner(incus));
        var ref = new RepoCloner.RepoReference("ref-repo", "/mnt/ref/repo", "/host/repo", null);
        doReturn(ref).when(cmd).planReference(eq(repo.getUrl()), any());

        cmd.cloneRepos(container, imageDef, MachineType.CONTAINER);

        // Local clone from mounted reference
        verify(incus).execInContainer("test", "agentuser",
                "git clone --no-hardlinks -- '/mnt/ref/repo' '/home/agentuser/repo'");
        // URL fixup + fetch + detect default branch
        verify(incus).execInContainer("test", "agentuser",
                "git -C '/home/agentuser/repo' remote set-url origin 'https://github.com/owner/repo.git'"
                        + " && git -C '/home/agentuser/repo' fetch --quiet origin"
                        + " && git -C '/home/agentuser/repo' remote set-head origin --auto");
        // Checkout remote's default branch (host ref may have a different HEAD)
        verify(incus).execInContainer("test", "agentuser",
                "b=$(git -C '/home/agentuser/repo' for-each-ref --format='%(symref:lstrip=3)' refs/remotes/origin/HEAD)"
                        + " && git -C '/home/agentuser/repo' checkout -B \"$b\" --track \"origin/$b\"");
        // Reference device cleaned up
        verify(incus).deviceRemove("test", "ref-repo");
    }

    @Test
    void cloneReposWithReferenceFailsFallsBack() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        // First call (local clone from reference) fails, rest succeed
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(FAIL)  // local clone fails
                .thenReturn(OK)    // cleanup rm -rf
                .thenReturn(OK)    // normal clone
                .thenReturn(OK);   // refspec restore

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = spy(new RepoCloner(incus));
        var ref = new RepoCloner.RepoReference("ref-repo", "/mnt/ref/repo", "/host/repo", null);
        doReturn(ref).when(cmd).planReference(eq(repo.getUrl()), any());

        cmd.cloneRepos(container, imageDef, MachineType.CONTAINER);

        // Should fall back to normal clone
        verify(incus).execInContainer("test", "agentuser",
                "git clone --single-branch -- 'https://github.com/owner/repo.git' '/home/agentuser/repo'");
        // Reference device still cleaned up
        verify(incus).deviceRemove("test", "ref-repo");
    }

    @Test
    void cloneReposWithReferenceAndBranchCheckout() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        repo.setBranch("feature/x");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = spy(new RepoCloner(incus));
        var ref = new RepoCloner.RepoReference("ref-repo", "/mnt/ref/repo", "/host/repo", null);
        doReturn(ref).when(cmd).planReference(eq(repo.getUrl()), any());

        cmd.cloneRepos(container, imageDef, MachineType.CONTAINER);

        // Local clone (no --branch — handled after fetch)
        verify(incus).execInContainer("test", "agentuser",
                "git clone --no-hardlinks -- '/mnt/ref/repo' '/home/agentuser/repo'");
        // URL fixup + fetch + detect default branch
        verify(incus).execInContainer("test", "agentuser",
                "git -C '/home/agentuser/repo' remote set-url origin 'https://github.com/owner/repo.git'"
                        + " && git -C '/home/agentuser/repo' fetch --quiet origin"
                        + " && git -C '/home/agentuser/repo' remote set-head origin --auto");
        // Branch checkout (explicit branch overrides detected default)
        verify(incus).execInContainer("test", "agentuser",
                "b='feature/x' && git -C '/home/agentuser/repo' checkout -B \"$b\" --track \"origin/$b\"");
    }

    @Test
    void cloneReposWithReferenceCheckoutFailsFallsBack() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(OK)    // local clone
                .thenReturn(OK)    // fixup (set-url + fetch + set-head)
                .thenReturn(FAIL)  // checkout fails
                .thenReturn(OK)    // cleanup rm -rf
                .thenReturn(OK)    // normal remote clone
                .thenReturn(OK);   // set-branches

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        repo.setBranch("nonexistent");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = spy(new RepoCloner(incus));
        var ref = new RepoCloner.RepoReference("ref-repo", "/mnt/ref/repo", "/host/repo", null);
        doReturn(ref).when(cmd).planReference(eq(repo.getUrl()), any());

        cmd.cloneRepos(container, imageDef, MachineType.CONTAINER);

        // Should fall back to normal clone after checkout failure
        verify(incus).execInContainer("test", "agentuser",
                "git clone --single-branch --branch 'nonexistent'"
                        + " -- 'https://github.com/owner/repo.git' '/home/agentuser/repo'");
        verify(incus).deviceRemove("test", "ref-repo");
    }

    @Test
    void cloneReposClonesMultipleReposInParallel() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var a = new ImageDef.RepoEntry();
        a.setUrl("https://github.com/owner/alpha.git");
        a.setPath("~/alpha");
        var b = new ImageDef.RepoEntry();
        b.setUrl("https://github.com/owner/beta.git");
        b.setPath("~/beta");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(a, b));

        var cmd = new RepoCloner(incus);
        cmd.cloneRepos(container, imageDef, MachineType.CONTAINER);

        verify(incus).execInContainer("test", "agentuser",
                "git clone --single-branch -- 'https://github.com/owner/alpha.git' '/home/agentuser/alpha'");
        verify(incus).execInContainer("test", "agentuser",
                "git clone --single-branch -- 'https://github.com/owner/beta.git' '/home/agentuser/beta'");
        verify(incus).execInContainer("test", "agentuser",
                "git -C '/home/agentuser/alpha' remote set-branches origin '*'");
        verify(incus).execInContainer("test", "agentuser",
                "git -C '/home/agentuser/beta' remote set-branches origin '*'");
    }

    @Test
    void cloneReposPrimesEachRepoInItsOwnWorker() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var a = new ImageDef.RepoEntry();
        a.setUrl("https://github.com/owner/alpha.git");
        a.setPath("~/alpha");
        a.setPrime("build-alpha");
        var b = new ImageDef.RepoEntry();
        b.setUrl("https://github.com/owner/beta.git");
        b.setPath("~/beta");
        b.setPrime("build-beta");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(a, b));

        var cmd = new RepoCloner(incus);
        cmd.cloneRepos(container, imageDef, MachineType.CONTAINER);

        // Each repo's prime runs (pipelined with the other repo's clone), scoped to
        // its own working directory.
        verify(incus).execInContainer("test", "agentuser", "cd '/home/agentuser/alpha' && build-alpha");
        verify(incus).execInContainer("test", "agentuser", "cd '/home/agentuser/beta' && build-beta");
    }

    @Test
    void prepareOneSkipsPrimeWhenAnotherRepoAlreadyFailed() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/beta.git");
        repo.setPath("~/beta");
        repo.setPrime("build-beta");

        var states = new java.util.concurrent.atomic.AtomicReferenceArray<BuildProgress.StepProgress>(1);
        states.set(0, BuildProgress.StepProgress.running("Cloning"));
        // Another repo has already failed → the build will abort.
        var failureSeen = new java.util.concurrent.atomic.AtomicBoolean(true);

        new RepoCloner(incus).prepareOne(container, repo, null, states, 0, failureSeen);

        // The clone still ran, but priming was skipped rather than doing work the
        // aborting build will throw away.
        verify(incus).execInContainer("test", "agentuser",
                "git clone --single-branch -- 'https://github.com/owner/beta.git' '/home/agentuser/beta'");
        verify(incus, never()).execInContainer("test", "agentuser",
                "cd '/home/agentuser/beta' && build-beta");
        assertEquals(BuildProgress.StepState.DONE, states.get(0).state());
        assertTrue(states.get(0).note().contains("priming skipped"),
                "the skipped-prime clone should say so");
    }

    @Test
    void cloneReposThrowsWhenCloneFails() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString()))
                .thenReturn(new IncusClient.ExecResult(128, "", "fatal: repository not found"));

        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/missing.git");
        repo.setPath("~/missing");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = new RepoCloner(incus);
        var ex = assertThrows(dev.incusspawn.incus.IncusException.class,
                () -> cmd.cloneRepos(container, imageDef, MachineType.CONTAINER));
        assertTrue(ex.getMessage().contains("fatal: repository not found"),
                "error message should carry the git failure detail");
    }

    // --- firstGitError ---

    @Test
    void firstGitErrorPrefersFatalLine() {
        var text = "Cloning into 'x'...\nremote: Counting objects\nfatal: could not read Username\nmore noise";
        assertEquals("fatal: could not read Username", RepoCloner.firstGitError(text));
    }

    @Test
    void firstGitErrorFallsBackToLastNonEmptyLine() {
        assertEquals("some trailing message",
                RepoCloner.firstGitError("first line\n\nsome trailing message\n"));
    }

    @Test
    void firstGitErrorEmptyForBlank() {
        assertEquals("", RepoCloner.firstGitError(""));
        assertEquals("", RepoCloner.firstGitError(null));
    }

    private static ImageDef projectLocal(String name, Path root) {
        var def = new ImageDef();
        def.setName(name);
        def.setSource(root.resolve(".incus-spawn/images/x.yaml").toString());
        def.setProjectRoot(root);
        return def;
    }

    private static ImageDef trusted(String name) {
        var def = new ImageDef();
        def.setName(name);
        return def;
    }

    @Test
    void projectLocalTemplatesNeverMountHostCheckouts() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        var imageDef = projectLocal("tpl-proj", Path.of("/work/repo"));
        imageDef.setRepos(List.of(repo));

        var cmd = spy(new RepoCloner(incus));
        cmd.cloneRepos(container, imageDef, MachineType.CONTAINER);

        verify(cmd, never()).planReference(any(), any());
        verify(incus, never()).deviceAdd(any(), any(), any(), any(String[].class));
        verify(incus).execInContainer("test", "agentuser",
                "git clone --single-branch -- 'https://github.com/owner/repo.git' '/home/agentuser/repo'");
    }

    @Test
    void noPrimeRunsWhileAHostReferenceIsMounted() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        var mounted = new java.util.concurrent.atomic.AtomicInteger();
        var primesWhileMounted = new java.util.concurrent.atomic.AtomicInteger();
        var primes = new java.util.concurrent.atomic.AtomicInteger();
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenAnswer(inv -> {
            String cmdLine = inv.getArgument(2);
            if (cmdLine.contains("echo primed")) {
                primes.incrementAndGet();
                if (mounted.get() > 0) primesWhileMounted.incrementAndGet();
            }
            if (cmdLine.startsWith("git clone --no-hardlinks")) Thread.sleep(50); // slow local clones
            return OK;
        });
        doAnswer(inv -> { mounted.decrementAndGet(); return null; }).when(incus).deviceRemove(eq("test"), anyString());

        // More referenced repos than any worker limit, each with a prime: the waiting primes must
        // not starve the clones that detach the references.
        var repos = new java.util.ArrayList<ImageDef.RepoEntry>();
        for (int i = 0; i < 12; i++) {
            var repo = new ImageDef.RepoEntry();
            repo.setUrl("https://github.com/owner/repo" + i + ".git");
            repo.setPath("~/repo" + i);
            repo.setPrime("echo primed");
            repos.add(repo);
        }
        var imageDef = trusted("tpl-test");
        imageDef.setRepos(repos);

        var cmd = spy(new RepoCloner(incus));
        doReturn(1).when(cmd).repoConcurrency(anyInt()); // one slot: 6 references > 1 worker
        for (int i = 0; i < 12; i++) {
            if (i % 2 == 1) continue; // half via host reference, half from the network
            var url = repos.get(i).getUrl();
            doReturn(new RepoCloner.RepoReference("ref-" + url.hashCode(), "/mnt/ref/" + url.hashCode(), "/host/repo", null))
                    .when(cmd).planReference(eq(url), any());
        }
        doAnswer(inv -> { mounted.incrementAndGet(); return null; })
                .when(incus).deviceAdd(eq("test"), anyString(), eq("disk"), any(String[].class));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30),
                () -> cmd.cloneRepos(container, imageDef, MachineType.CONTAINER));
        assertEquals(12, primes.get());
        assertEquals(0, primesWhileMounted.get());
        assertEquals(0, mounted.get());
    }

    @Test
    void failedDetachFailsTheBuildInsteadOfPriming() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);
        doThrow(new IncusException("busy")).when(incus).deviceRemove(eq("test"), anyString());
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");
        repo.setPrime("echo primed");
        var imageDef = trusted("tpl-test");
        imageDef.setRepos(List.of(repo));

        var cmd = spy(new RepoCloner(incus));
        doReturn(new RepoCloner.RepoReference("ref-repo", "/mnt/ref/repo", "/host/repo", null))
                .when(cmd).planReference(eq(repo.getUrl()), any());

        assertThrows(RuntimeException.class, () -> cmd.cloneRepos(container, imageDef, MachineType.CONTAINER));
        verify(incus, never()).execInContainer(eq("test"), eq("agentuser"), contains("echo primed"));
    }

    @Test
    void failedDetachDoesNotStrandAnAttachWaitingForASlot() {
        var incus = mock(IncusClient.class);
        var container = new Container(incus, "test");
        when(incus.execInContainer(eq("test"), anyString(), anyString())).thenReturn(OK);
        var stillPlugged = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv -> {
            if (stillPlugged.get()) throw new IncusClient.NoHotplugSlotException("No available PCI hotplug slots");
            return null;
        }).when(incus).deviceAdd(eq("test"), anyString(), eq("disk"), any(String[].class));
        doAnswer(inv -> { stillPlugged.set(true); throw new IncusException("busy"); })
                .when(incus).deviceRemove("test", "ref-0");
        var repos = new java.util.ArrayList<ImageDef.RepoEntry>();
        var cmd = spy(new RepoCloner(incus));
        for (int i = 0; i < 2; i++) {
            var repo = new ImageDef.RepoEntry();
            repo.setUrl("https://github.com/owner/repo" + i + ".git");
            repo.setPath("~/repo" + i);
            repos.add(repo);
            doReturn(new RepoCloner.RepoReference("ref-" + i, "/mnt/ref/" + i, "/host/repo" + i, null))
                    .when(cmd).planReference(eq(repo.getUrl()), any());
        }
        var imageDef = trusted("tpl-test");
        imageDef.setRepos(repos);
        doReturn(1).when(cmd).repoConcurrency(anyInt()); // repo0 detaches (and fails) before repo1 attaches

        // The device that would not detach still holds its slot: repo1 runs out, and must give up, not wait.
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(30),
                () -> assertThrows(RuntimeException.class, () -> cmd.cloneRepos(container, imageDef, MachineType.VM)));
    }
}
