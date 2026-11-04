package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.Environment;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.CertificateAuthority;
import dev.incusspawn.ssh.HostKeys;
import dev.incusspawn.ssh.TestHostKeys;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.Output;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * Which warnings a branch's setup raises (#1646), under the binding the TUI makes for a branch
 * (a copying terminal, here a quiet one): what it keeps in its log, since the shell that follows
 * draws over the terminal. A failed step it does not copy, so one would fail these tests.
 */
@ExtendWith({TempHome.class, LinuxBranching.class})
class BranchSetupWarningsTest {

    private static List<String> branchWarnings(IncusClient incus) {
        return branchWarnings(incus, null);
    }

    private static List<String> branchWarnings(IncusClient incus, Path inbox) {
        return branchWarnings(incus, inbox, false, false);
    }

    private static List<String> branchWarnings(IncusClient incus, Path inbox, boolean gui, boolean kvm) {
        var request = new BranchFlow.Request("tpl-dev", "dev-1", gui, kvm, NetworkMode.AIRGAP,
                inbox, null, null, null, List.of(), true, Map.of());
        var warnings = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.copying(RecordingOutput.QUIET, Output.diagnosticsTo(warnings::add)))) {
            BranchFlow.create(incus, BranchFlow.preflight(incus, request, Map.of()));
        }
        return warnings;
    }

    private static IncusClient settingUp(FakeIncusDaemon daemon, boolean ready) {
        var incus = spy(daemon.client());
        doReturn(ready ? "" : null).when(incus).pollUntilReadyOutput(eq("dev-1"), anyInt(), anyMap(), any(String[].class));
        return incus;
    }

    @Test
    void aSetupThatDidNotCompleteIsWarnedOfInTheLog() {
        var daemon = new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));

        var warnings = branchWarnings(settingUp(daemon, false));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("Container setup may not be complete")),
                "warned " + warnings);
    }

    @Test
    void anSshHostEntryThatFailedIsWarnedOfInTheLog() throws IOException {
        Files.createDirectories(Environment.sshDir());
        Files.writeString(Environment.sshKeyFile(), "private\n");
        Files.writeString(Environment.sshPubKeyFile(), "ssh-ed25519 AAAA isx\n");
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.PROFILE, "tpl-dev", Metadata.SSH_SETUP, "true"));
        var incus = settingUp(daemon, true);
        doThrow(new IllegalStateException("no address yet")).when(incus).getContainerIpv4("dev-1");

        var warnings = branchWarnings(incus);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("Failed to configure SSH host entry: no address yet")),
                "warned " + warnings);
    }

    /** A branch pins the host key its setup script reported (#1863), so herdr's strict check finds it. */
    @Test
    void aBranchPinsTheHostKeyItsSetupReported() throws IOException {
        Files.createDirectories(Environment.sshDir());
        Files.writeString(Environment.sshKeyFile(), "private\n");
        Files.writeString(Environment.sshPubKeyFile(), "ssh-ed25519 AAAA isx\n");
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.PROFILE, "tpl-dev", Metadata.SSH_SETUP, "true"));
        var incus = spy(daemon.client());
        var key = TestHostKeys.key("ssh-ed25519", 9);
        var script = org.mockito.ArgumentCaptor.forClass(String[].class);
        doReturn(TestHostKeys.printed(key)).when(incus)
                .pollUntilReadyOutput(eq("dev-1"), anyInt(), anyMap(), script.capture());
        doReturn("10.166.11.9").when(incus).getContainerIpv4("dev-1");

        assertEquals(List.of(), branchWarnings(incus));

        assertTrue(String.join(" ", script.getAllValues().getFirst()).contains(HostKeys.GUEST_SCRIPT));
        // An airgapped branch has no stamped address, so its keys are pinned under its name alone
        assertEquals(List.of("dev-1 ssh-ed25519 " + key.blob() + " isx:dev-1"),
                Files.readAllLines(Environment.sshKnownHostsFile()));
    }

    /**
     * The pin's address is the one isx stamped, never one the guest reports (#1863): the guest
     * owns its interfaces, and pinning an address takes it off every other instance's line.
     */
    @Test
    void aBranchPinsUnderItsStampedAddressNotTheGuestsOwn() throws IOException {
        Files.createDirectories(Environment.sshDir());
        Files.writeString(Environment.sshKeyFile(), "private\n");
        Files.writeString(Environment.sshPubKeyFile(), "ssh-ed25519 AAAA isx\n");
        var incus = spy(new FakeIncusDaemon().container("dev-1", Map.of()).client());
        var key = TestHostKeys.key("ssh-ed25519", 9);
        doReturn(TestHostKeys.printed(key)).when(incus)
                .pollUntilReadyOutput(eq("dev-1"), anyInt(), anyMap(), any(String[].class));
        // A sibling's address, which the guest put on one of its interfaces
        doReturn("10.166.11.250").when(incus).getContainerIpv4("dev-1");
        var prefetched = new RuntimeSetup.RuntimeConfig(null, true, null, null, null, null, "10.166.11.9", null, null);

        RuntimeSetup.setupRuntime(incus, "dev-1", NetworkMode.FULL, prefetched, null, false, List.of(),
                RecordingOutput.QUIET);

        assertEquals(List.of("dev-1,10.166.11.9 ssh-ed25519 " + key.blob() + " isx:dev-1"),
                Files.readAllLines(Environment.sshKnownHostsFile()));
    }

    @Test
    void anInboxThatIsNoDirectoryIsWarnedOfInTheLog() throws IOException {
        var notADirectory = Files.writeString(Path.of(System.getProperty("user.home"), "inbox"), "");
        var daemon = new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));

        var warnings = branchWarnings(settingUp(daemon, true), notADirectory);

        assertTrue(warnings.stream().anyMatch(w -> w.contains("is not a directory, skipping")),
                "warned " + warnings);
    }

    /** Configuring the branch, before its start: a host that refuses the filtering is told of in the log too. */
    @Test
    void ipFilteringTheHostRefusedIsWarnedOfInTheLog() {
        var daemon = new FakeIncusDaemon().container("dev-1", Map.of())
                .refuseWritesContaining("security.ipv4_filtering");
        var warnings = new ArrayList<String>();

        try (var ignored = BuildOutput.bind(Output.copying(RecordingOutput.QUIET, Output.diagnosticsTo(warnings::add)))) {
            InstanceLifecycle.configureBranch(daemon.client(), "dev-1", new InstanceLifecycle.BranchSettings(
                    null, "8GiB", "20GiB", NetworkMode.FULL, "tpl-dev", Map.of(), false));
        }

        // One warning, naming the instance and what it means
        assertEquals(1, warnings.size(), "warned " + warnings);
        assertTrue(warnings.get(0).startsWith("could not enable IP spoofing protection on dev-1: "), warnings.get(0));
        assertTrue(warnings.get(0).endsWith("; instances on this host can impersonate each other's credential accounts."),
                warnings.get(0));
    }

    /**
     * A branch that asks for GUI outside a Wayland session warns once, in the log: the cause, its
     * advice, and that it goes on without (#1690). The log used to keep only the last of the three.
     */
    @Test
    void guiOutsideAWaylandSessionIsOneWarningWithItsCauseInTheLog() {
        var daemon = new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));
        var original = BranchFlow.environment;
        BranchFlow.environment = Map::of;
        try {
            var warnings = branchWarnings(settingUp(daemon, true), null, true, false);

            assertEquals(List.of("GUI passthrough requires WAYLAND_DISPLAY and XDG_RUNTIME_DIR.\n"
                    + "Make sure you are running isx from a Wayland graphical session.\n"
                    + "Continuing without GUI passthrough."), warnings);
        } finally {
            BranchFlow.environment = original;
        }
    }

    /** A branch that asks for KVM on a host without /dev/kvm warns once, in the log, with why and what to do (#1690). */
    @Test
    void kvmWithoutDevKvmIsOneWarningWithItsCauseInTheLog() {
        var daemon = new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));
        var original = BranchFlow.hostPathExists;
        BranchFlow.hostPathExists = path -> false;
        try {
            var warnings = branchWarnings(settingUp(daemon, true), null, false, true);

            assertEquals(List.of("/dev/kvm not found on the host.\n"
                    + "Ensure your CPU supports hardware virtualization (VT-x/AMD-V) and load the kvm module:\n"
                    + "sudo modprobe kvm_intel  (or kvm_amd)\n"
                    + "Continuing without KVM — VMs inside this branch will not work."), warnings);
        } finally {
            BranchFlow.hostPathExists = original;
        }
    }

    /**
     * A source still carrying the CA this host superseded is let through, and said so as a warning
     * the log keeps, not a note it drops (#1690): a rebuild is still worth doing.
     */
    @Test
    void aSourceOnTheSupersededCaIsWarnedOfInTheLog() {
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.PROFILE, "tpl-dev", Metadata.CA_FINGERPRINT, "superseded"));
        var request = new BranchFlow.Request("tpl-dev", "dev-1", false, false, NetworkMode.FULL,
                null, null, null, null, List.of(), true, Map.of());
        var originalProxy = BranchFlow.proxyHealthCheck;
        var originalTrust = BranchFlow.caTrust;
        var warnings = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.copying(RecordingOutput.QUIET, Output.diagnosticsTo(warnings::add)))) {
            BranchFlow.proxyHealthCheck = incus -> true;
            BranchFlow.caTrust = () -> new CertificateAuthority.CaTrust("current", "superseded");
            BranchFlow.preflight(daemon.client(), request, Map.of());
        } finally {
            BranchFlow.proxyHealthCheck = originalProxy;
            BranchFlow.caTrust = originalTrust;
        }

        // One about the CA (the host's bridge and firewall checks may add their own)
        assertEquals(List.of("'tpl-dev' still carries the pre-upgrade MITM CA certificate.\n"
                + "The new one is installed into the instance on first use. Rebuild when convenient: isx build tpl-dev"),
                warnings.stream().filter(w -> w.contains("CA")).toList(), warnings::toString);
    }
}
