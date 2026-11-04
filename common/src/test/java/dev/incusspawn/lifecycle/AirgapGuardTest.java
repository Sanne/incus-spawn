package dev.incusspawn.lifecycle;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.Platform;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * An airgapped instance keeps no way out that isx can see in its config (#1518): the network
 * devices it masks at branch time are masked again when a profile brings one back later, and
 * passthrough hardware, which may be a network card, is refused rather than silently taken away.
 */
@ExtendWith(TempHome.class)
@ExtendWith(LinuxBranching.class)
class AirgapGuardTest {

    private static FakeIncusDaemon template() {
        return new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));
    }

    /** Branch {@code dev-2} from {@code source} without starting it, as {@code --no-start} does. */
    private static void branch(FakeIncusDaemon daemon, String source, NetworkMode mode) {
        var request = new BranchFlow.Request(source, "dev-2", false, false, mode,
                null, null, null, null, List.of(), false, Map.of());
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = i -> true;
        try {
            BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }
    }

    private static String assertRefusedBeforeAnythingIsCreated(FakeIncusDaemon daemon, String device) {
        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.AIRGAP));
        assertTrue(e.getMessage().contains(device), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
        return e.getMessage();
    }

    @Test
    void anAirgappedBranchOfASourceWithAPciDeviceIsRefused() {
        var daemon = template();
        daemon.device("tpl-dev", "card0", Map.of("type", "pci", "address", "0000:03:00.0"));
        var refusal = assertRefusedBeforeAnythingIsCreated(daemon, "card0");
        assertTrue(refusal.contains("'gpu' device"), refusal);
    }

    @Test
    void anAirgappedBranchOfASourceWithAUsbDeviceIsRefused() {
        var daemon = template();
        daemon.device("tpl-dev", "dongle", Map.of("type", "usb", "vendorid", "0bda", "productid", "8153"));
        var refusal = assertRefusedBeforeAnythingIsCreated(daemon, "dongle");
        assertFalse(refusal.contains("'pci'"), "a GPU's remedy does not apply to a USB device: " + refusal);
    }

    @Test
    void aGpuDeviceSurvivesAirgap() {
        // Incus accepts a gpu device only for a real GPU, so it is the way to keep one airgapped
        var daemon = template();
        daemon.device("tpl-dev", "gpu0", Map.of("type", "gpu", "gputype", "physical", "pci", "0000:03:00.0"));

        branch(daemon, "tpl-dev", NetworkMode.AIRGAP);

        assertEquals("gpu", daemon.instance("dev-2").path("expanded_devices").path("gpu0").path("type").asText());
    }

    /** An instance airgapped with its eth0 masked, as a branch leaves it. */
    private static FakeIncusDaemon airgapped(String status) {
        return airgapped(new FakeIncusDaemon(), status);
    }

    private static FakeIncusDaemon airgapped(FakeIncusDaemon daemon, String status) {
        daemon.instance("box", "container", status, Map.of(
                Metadata.NETWORK_MODE, NetworkMode.AIRGAP.name(), Metadata.AIRGAP_MASKED, "eth0"));
        daemon.device("box", "eth0", Map.of("type", "none"));
        return daemon;
    }

    private static String expandedType(FakeIncusDaemon daemon, String device) {
        return daemon.instance("box").path("expanded_devices").path(device).path("type").asText();
    }

    @Test
    void anAirgappedInstanceWhoseProfileGainedInfinibandIsMaskedBeforeItStarts() {
        // Masks are written at branch time: a profile device added afterwards attached at the next start
        var daemon = airgapped(new FakeIncusDaemon().profile("default", Map.of(
                "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0"),
                "ib1", Map.of("type", "infiniband", "nictype", "physical", "parent", "ibp1s0"),
                "root", Map.of("type", "disk", "path", "/", "pool", "default"))), "Stopped");
        var said = new ArrayList<String>();

        // FakeIncusDaemon serves no exec, so the readiness wait after the start can only time out
        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(daemon.clientWithShortReadyWait(), "box",
                daemon.instance("box"), MachineType.CONTAINER, RecordingOutput.into(said)));

        assertEquals("none", expandedType(daemon, "ib1"));
        assertEquals("eth0,ib1", daemon.instance("box").path("config").path(Metadata.AIRGAP_MASKED).asText());
        var requests = daemon.requests();
        int write = requests.lastIndexOf("PATCH /1.0/instances/box");
        int start = requests.indexOf("PUT /1.0/instances/box/state");
        assertTrue(write >= 0 && start > write, "masked before the start:\n" + String.join("\n", requests));
        assertTrue(said.stream().anyMatch(s -> s.contains("ib1")), said::toString);
    }

    @Test
    void aRunningAirgappedInstanceThatGainedAProxyIsMaskedBeforeAShellOpensInIt() {
        var daemon = airgappedWithAProxy("Running");

        InstanceLifecycle.ensureReady(daemon.client(), "box", daemon.instance("box"),
                MachineType.CONTAINER, RecordingOutput.QUIET);

        assertEquals("none", expandedType(daemon, "fwd"));
        assertEquals(List.of(), daemon.stateActions());
    }

    @Test
    void anAirgappedInstanceThatGainedAUsbDeviceIsNotStarted() {
        var daemon = airgapped("Stopped");
        daemon.device("box", "dongle", Map.of("type", "usb", "vendorid", "0bda", "productid", "8153"));

        var e = assertThrows(AirgapGuard.NotIsolatedException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", daemon.instance("box"), MachineType.CONTAINER, RecordingOutput.QUIET));

        assertTrue(e.getMessage().contains("dongle"), e::getMessage);
        assertEquals(List.of(), daemon.stateActions());
        assertEquals("usb", expandedType(daemon, "dongle"), "refused, not masked");
    }

    @Test
    void anAirgappedInstanceThatGainedNothingCostsNoRequestBeyondTheReadTheRepairsShare() {
        var daemon = airgapped("Stopped");
        int before = daemon.requests().size();

        InstanceLifecycle.prepareForStart(daemon.client(), "box",
                daemon.instance("box"), RecordingOutput.FAIL_ON_WARN);

        assertEquals(List.of(), daemon.requests().subList(before, daemon.requests().size()));
    }

    @Test
    void aBridgeNicAnAirgappedInstanceGainedIsMaskedNotFiltered() {
        var daemon = airgapped(new FakeIncusDaemon().profile("default", Map.of(
                "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0"),
                "eth1", Map.of("type", "nic", "network", "incusbr0", "name", "eth1"),
                "root", Map.of("type", "disk", "path", "/", "pool", "default"))), "Stopped");
        var out = new RecordingOutput();

        InstanceLifecycle.prepareForStart(daemon.client(), "box", daemon.instance("box"), out);

        assertEquals("none", expandedType(daemon, "eth1"));
        // A warning, which the TUI's log keeps: a step would go unsaid there
        assertEquals(out.lines(), out.warnings(), "nothing but the warning");
        assertEquals(1, out.warnings().size(), out.warnings()::toString);
        assertTrue(out.warnings().getFirst().contains("Masked eth1"), out.warnings()::toString);
    }

    private static FakeIncusDaemon airgappedWithAProxy(String status) {
        return airgapped(status).device("box", "fwd", Map.of("type", "proxy", "bind", "instance",
                "listen", "tcp:127.0.0.1:8080", "connect", "tcp:192.0.2.1:80"));
    }

    @Test
    void aRunningInstanceWhoseGainedDeviceIncusWillNotUnplugIsNotUsedAndSaysToStopIt() {
        var daemon = airgappedWithAProxy("Running").refuseWritesContaining(Metadata.AIRGAP_MASKED);

        var e = assertThrows(AirgapGuard.NotIsolatedException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", daemon.instance("box"), MachineType.CONTAINER, RecordingOutput.QUIET));

        assertTrue(e.getMessage().contains("stop it"), e::getMessage);
    }

    @Test
    void aStoppedInstanceWhoseMaskIsRefusedGetsIncusErrorNotAdviceToStopIt() {
        var daemon = airgappedWithAProxy("Stopped").refuseWritesContaining(Metadata.AIRGAP_MASKED);

        var e = assertThrows(IncusException.class, () -> InstanceLifecycle.prepareForStart(daemon.client(), "box",
                daemon.instance("box"), RecordingOutput.QUIET));

        assertFalse(e instanceof AirgapGuard.NotIsolatedException, e::getMessage);
        assertFalse(e.getMessage().contains("stop it"), e::getMessage);
        assertEquals(List.of(), daemon.stateActions());
    }

    /**
     * A restart from the TUI, on a background thread while it draws, reports what its start drops
     * to the output the TUI hands it (#1703): its log. It used to pass the start nothing to report
     * to, so a missing inbox was dropped without a word.
     */
    @Test
    void aTuiRestartReportsADroppedInboxToTheOutputItIsGiven(@TempDir Path tmp) {
        assumeFalse(Platform.isMacOS(), "on macOS the device source is the appliance VM's /host view of the path");
        var daemon = airgapped("Running").device("box", InstanceLifecycle.INBOX_DEVICE, Map.of("type", "disk",
                "source", tmp.resolve("deleted").toString(), "path", InstanceLifecycle.INBOX_PATH, "readonly", "true"));
        var listed = daemon.instance("box");
        var incus = spy(daemon.client());
        doReturn("").when(incus).waitForReady(eq("box"), any(), any(), anyMap());
        var out = new RecordingOutput();

        InstanceLifecycle.restartForUse(incus, "box", MachineType.CONTAINER, false, listed, out);

        assertEquals(1, out.warnings().size(), out.lines().toString());
        assertTrue(out.warnings().getFirst().startsWith("inbox directory not found: " + tmp.resolve("deleted")),
                out.warnings().getFirst());
    }

    @Test
    void aTuiRestartMasksADeviceGainedSinceItsListingWhileTheInstanceIsStopped() {
        // The listing predates the device: only a read after the stop sees it
        var daemon = airgapped("Running");
        var listed = daemon.instance("box");
        daemon.device("box", "fwd", Map.of("type", "proxy", "bind", "instance",
                "listen", "tcp:127.0.0.1:8080", "connect", "tcp:192.0.2.1:80"));
        var incus = spy(daemon.client());
        doReturn("").when(incus).waitForReady(eq("box"), any(), any(), anyMap());

        InstanceLifecycle.restartForUse(incus, "box", MachineType.CONTAINER, false, listed, RecordingOutput.QUIET);

        assertEquals(List.of("box stop", "box start"), daemon.stateActions());
        assertEquals("none", expandedType(daemon, "fwd"));
        var requests = daemon.requests();
        int stop = requests.indexOf("PUT /1.0/instances/box/state");
        int start = requests.lastIndexOf("PUT /1.0/instances/box/state");
        assertTrue(stop < start && requests.subList(stop, start).contains("PATCH /1.0/instances/box"),
                "masked between the stop and the start:\n" + String.join("\n", requests));
    }
}
