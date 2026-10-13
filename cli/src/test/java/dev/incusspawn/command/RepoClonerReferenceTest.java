package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Host repo references on a VM build, against a {@link FakeIncusDaemon} that gives a running VM
 * the 8 PCI hotplug slots Incus does (#826): more referenced repos than slots must all still clone
 * from their reference, and no prime may run while any reference is attached (#765).
 */
@ExtendWith(IsolatedHome.class)
class RepoClonerReferenceTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult NOT_A_REPOSITORY =
            new IncusClient.ExecResult(128, "", "fatal: repository '/mnt/ref' does not exist\n");
    private static final IncusClient.ExecResult MOUNT_TIMEOUT = new IncusClient.ExecResult(124, "", "");
    private static final String VM = "tpl-vm-rebuilding";

    @TempDir
    Path checkouts;

    /** {@code clones} holds the clones that succeeded. */
    private record Build(FakeIncusDaemon daemon, List<String> clones, AtomicInteger primes,
                         AtomicInteger primesWhileAttached, String output, List<String> deviceNames) {}

    /** Builds {@code count} referenced repos, each with a prime, into a running VM. */
    private Build build(int count, int hotplugSlots) throws IOException {
        return build(count, hotplugSlots, true);
    }

    /**
     * As above, with incus-agent mounting each hot-plugged share some time after Incus has added the
     * device (#1233): a clone from the mount point fails unless it first waits for the mount, and
     * with {@code mountAppears} false that wait times out.
     */
    private Build build(int count, int hotplugSlots, boolean mountAppears) throws IOException {
        return build(count, hotplugSlots, mountAppears, false);
    }

    private Build build(int count, int hotplugSlots, boolean mountAppears, boolean sameUrl) throws IOException {
        IsolatedHome.seed("host-paths:\n  - " + checkouts + "\n");
        var repos = new ArrayList<ImageDef.RepoEntry>();
        for (int i = 0; i < count; i++) {
            var url = "https://github.com/owner/repo" + (sameUrl ? 0 : i) + ".git";
            var git = Files.createDirectories(checkouts.resolve("repo" + i).resolve(".git"));
            Files.writeString(git.resolve("config"), "[remote \"origin\"]\n\turl = " + url + "\n");
            var repo = new ImageDef.RepoEntry();
            repo.setUrl(url);
            repo.setPath("~/repo" + i);
            repo.setPrime("echo primed");
            repos.add(repo);
        }
        var imageDef = new ImageDef();
        imageDef.setName("tpl-vm");
        imageDef.setRepos(repos);

        var daemon = new FakeIncusDaemon()
                .instance(VM, "virtual-machine", "Running", Map.of())
                .hotplugSlots(hotplugSlots);
        var incus = spy(daemon.client());
        var deviceNames = java.util.Collections.synchronizedList(new ArrayList<String>());
        doAnswer(inv -> {
            deviceNames.add(inv.getArgument(1));
            return inv.callRealMethod();
        }).when(incus).deviceAdd(eq(VM), anyString(), eq("disk"), any(String[].class));
        var clones = java.util.Collections.synchronizedList(new ArrayList<String>());
        var primes = new AtomicInteger();
        var primesWhileAttached = new AtomicInteger();
        doReturn(OK).when(incus).shellExec(eq(VM), any(String[].class));
        doAnswer(inv -> {
            String script = inv.getArgument(2);
            if (script.contains("git clone")) {
                Thread.sleep(20); // let the clones overlap
                if (script.contains("--no-hardlinks")) {
                    // Straight after the attach the mount point is still an empty directory.
                    if (!script.contains("mountpoint -q")) return NOT_A_REPOSITORY;
                    if (!mountAppears) return MOUNT_TIMEOUT;
                }
                clones.add(script);
            }
            if (script.contains("echo primed")) {
                primes.incrementAndGet();
                var devices = daemon.instance(VM).path("devices");
                devices.fieldNames().forEachRemaining(d -> {
                    if (d.startsWith("ref-")) primesWhileAttached.incrementAndGet();
                });
            }
            return OK;
        }).when(incus).execInContainer(eq(VM), eq("agentuser"), anyString());

        var cmd = spy(new RepoCloner(incus));
        doAnswer(inv -> {
            RepoCloner.RepoReference ref = inv.getArgument(1);
            return RepoClonerTest.reference(ref.deviceName(), ref.containerPath(), ref.source(), null);
        }).when(cmd).prepareReference(any(), any(), any(), anyInt());
        doReturn(count).when(cmd).repoConcurrency(anyInt()); // every clone at once: the most slot pressure
        var out = new ByteArrayOutputStream();
        var originalOut = System.out;
        var originalErr = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(out, true));
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(30),
                    () -> cmd.cloneRepos(new Container(incus, VM), imageDef, java.util.Map.of(), MachineType.VM));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Build(daemon, clones, primes, primesWhileAttached, out.toString(), deviceNames);
    }

    @Test
    void twoEntriesForOneUrlHaveIndependentMounts() throws IOException {
        var build = build(3, 8, true, true);
        assertEquals(3, viaReference(build), build.output());
        assertEquals(3, new java.util.HashSet<>(build.deviceNames()).size());
        assertEquals(0, build.primesWhileAttached().get());
        assertEquals(List.of(), referenceDevices(build));
    }

    private static long viaReference(Build build) {
        return build.clones().stream().filter(c -> c.contains("git clone --no-hardlinks")).count();
    }

    private static List<String> referenceDevices(Build build) {
        var names = new ArrayList<String>();
        build.daemon().instance(VM).path("devices").fieldNames().forEachRemaining(d -> {
            if (d.startsWith("ref-")) names.add(d);
        });
        return names;
    }

    @Test
    void moreReferencesThanHotplugSlotsAllCloneFromTheirReference() throws IOException {
        var build = build(12, 8);

        assertEquals(12, viaReference(build), build.output());
        assertEquals(12, build.primes().get());
        assertEquals(0, build.primesWhileAttached().get());
        assertEquals(List.of(), referenceDevices(build));
    }

    @Test
    void slotsTakenByDevicesNobodyCountedStillLeaveEveryRepoItsReference() throws IOException {
        // Fewer slots than the budget assumes: each exhausted attach retires a slot and waits for another.
        var build = build(12, 3);

        assertEquals(12, viaReference(build), build.output());
        assertEquals(0, build.primesWhileAttached().get());
        assertEquals(List.of(), referenceDevices(build));
    }

    @Test
    void noHotplugSlotAtAllClonesFromTheNetworkAndSaysWhy() throws IOException {
        var build = build(3, 0);

        assertEquals(0, viaReference(build), build.output());
        assertEquals(3, build.clones().size());
        assertEquals(3, build.primes().get());
        assertFalse(build.output().contains("Warning: could not set up repo reference"), build.output());
        assertTrue(build.output().contains("no free PCI hotplug slot"), build.output());
        assertEquals(List.of(), referenceDevices(build));
    }

    @Test
    void aReferenceIsClonedOnlyOnceTheGuestHasMountedIt() throws IOException {
        // incus-agent mounts a hot-plugged share after the device add returns (#1233).
        var build = build(3, 8);

        assertEquals(3, viaReference(build), build.output());
        assertTrue(build.output().contains("via prepared Git packs"), build.output());
        assertEquals(List.of(), referenceDevices(build));
    }

    @Test
    void aMountThatNeverAppearsClonesFromTheNetworkAndSaysWhy() throws IOException {
        var build = build(2, 8, false);

        assertEquals(0, viaReference(build), build.output());
        assertEquals(2, build.clones().size(), build.output());
        assertEquals(2, build.primes().get());
        assertTrue(build.output().contains("could not use the host reference: not mounted in the guest within 30s"), build.output());
        assertEquals(List.of(), referenceDevices(build));
    }
}
