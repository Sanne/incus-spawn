package dev.incusspawn.vm;

import dev.incusspawn.util.Output;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VmManagerTest {

    @TempDir Path tempHome;
    private String originalHome;

    @BeforeEach
    void isolateEnvironment() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreEnvironment() {
        System.setProperty("user.home", originalHome);
    }

    /** A missing isx-vm is installed the way isx was (#2552): Homebrew, install.sh, or the release. */
    @Test
    void aMissingLauncherIsInstalledTheWayIsxWas() {
        var localBin = Path.of("/Users/u/.local/bin");
        var release = new dev.incusspawn.BuildInfo("0.6.0", "abc", "native");

        var brew = VmManager.launcherMissing("/opt/homebrew/Cellar/incus-spawn-dev/0.6.0-dev.1/bin/isx", localBin, false, release);
        assertTrue(brew.contains("brew reinstall Sanne/tap/incus-spawn-dev"), brew);

        var script = VmManager.launcherMissing("/Users/u/.local/bin/isx", localBin, true, release);
        assertTrue(script.contains("./install.sh"), script);
        assertFalse(script.contains("brew"), script);

        var jbang = VmManager.launcherMissing("/Users/u/.jbang/bin/isx", localBin, true, release);
        assertFalse(jbang.contains("brew"), jbang);
        assertTrue(jbang.contains("JBang"), jbang);
        // One && chain, so a failed download or checksum check installs nothing
        assertTrue(jbang.contains("""
                  cd "$(mktemp -d)" &&
                  curl -fL -O https://github.com/Sanne/incus-spawn/releases/download/v0.6.0/isx-vm-macos-aarch64 \
                -O https://github.com/Sanne/incus-spawn/releases/download/v0.6.0/SHA256SUMS &&
                  grep ' isx-vm-macos-aarch64$' SHA256SUMS | shasum -a 256 -c &&
                  mkdir -p "/Users/u/.local/bin" && install -m 755 isx-vm-macos-aarch64 "/Users/u/.local/bin/isx-vm\""""), jbang);

        var dev = VmManager.launcherMissing(null, localBin, true, new dev.incusspawn.BuildInfo("0.0.0-SNAPSHOT", "abc", "jvm"));
        assertTrue(dev.contains("launcher/build-isx-vm.sh"), dev);
        assertFalse(dev.contains("curl"), dev);
    }

    @Test
    void aDataDiskResetIsRememberedWithItsDate() {
        // #1393: 'isx list' names the reset when the list is empty because of it.
        assertNull(VmManager.dataDiskResetOn(), "never reset");
        VmManager.markDataDiskReset(java.time.LocalDate.of(2026, 10, 12));
        assertEquals(java.time.LocalDate.of(2026, 10, 12), VmManager.dataDiskResetOn());
    }

    /**
     * The launcher's own arguments (launcher/Sources/IsxVmCore/VmSpec.swift reads them, and
     * VmSpecTests holds this same line): the disks in the guest's order, the home shared, both
     * vsock sockets and the control socket in the state directory (#2031).
     */
    @Test
    void theLauncherIsGivenTheMachineInItsOwnArguments() {
        var cmd = VmManager.launcherCommand("isx-vm", 2, 2048);
        var state = Environment.vmStateDir();

        assertEquals(List.of("--disk", state.resolve("disk.img").toString(), "--disk", state.resolve("swap.img").toString(),
                "--disk", state.resolve("data.img").toString()), cmd.subList(cmd.indexOf("--disk"), cmd.indexOf("--disk") + 6));
        assertEquals("hostfs=" + System.getProperty("user.home"), cmd.get(cmd.indexOf("--share") + 1));
        assertEquals("8443=" + state.resolve("vm.incus.sock"), cmd.get(cmd.indexOf("--vsock") + 1));
        assertEquals("1025=" + state.resolve("vm.agent.sock"), cmd.get(cmd.lastIndexOf("--vsock") + 1));
        assertEquals(state.resolve("vm.control.sock").toString(), cmd.get(cmd.indexOf("--control") + 1));
        assertTrue(cmd.stream().noneMatch(arg -> arg.startsWith("--device") || arg.contains("initrd")), cmd.toString());
    }

    @Test
    void theLaunchersPidIsReadFromItsState() {
        assertEquals(java.util.Optional.of(new VmControl.State(false, 4242)), VmControl.state("running 4242\n"));
        assertEquals(java.util.Optional.of(new VmControl.State(true, 7)), VmControl.state("stopping 7"));
        assertTrue(VmControl.state("running").isEmpty());
        assertTrue(VmControl.state("unknown verb").isEmpty());
    }

    @Test
    void theRelaysCountIsReadForThePortAsked() {
        var reply = "8443 waiting 0 held 3 total 40 refused 1\n1025 waiting 0 held 1 total 9 refused 0";
        assertEquals(java.util.OptionalInt.of(3), VmControl.held(reply, 8443));
        assertEquals(java.util.OptionalInt.of(1), VmControl.held(reply, 1025));
        assertTrue(VmControl.held(reply, 8444).isEmpty());
        assertTrue(VmControl.held("unknown verb", 8443).isEmpty());
    }

    @Test
    void detectCpusReturnsAtLeastOne() {
        assertTrue(VmManager.detectCpus() >= 1);
    }

    @Test
    void detectMemoryReturnsAtLeast2048() {
        assertTrue(VmManager.detectMemoryMiB() >= 2048);
    }

    @Test
    void diskSizeDefaultIs60G() {
        if (System.getenv("ISX_VM_DISK") == null) {
            assertEquals("60G", VmManager.diskSize());
        }
    }

    @Test
    void isRunningReturnsFalseWhenNoPidFile() {
        assertFalse(VmManager.isRunning());
    }

    /**
     * #1695: the appliance runs only under isx-vm on macOS. On Linux isx uses the host's Incus, and
     * a start is refused before it creates a disk or launches anything.
     */
    @Test
    void theApplianceIsNeverStartedOnLinux() {
        assumeTrue(Platform.isLinux());
        var started = new boolean[1];
        var out = RecordingOutput.during(() -> started[0] = VmManager.start());

        assertFalse(started[0]);
        assertTrue(out.events().stream().anyMatch(e -> e.kind() == RecordingOutput.Kind.ERROR
                && e.msg().contains("only on macOS")), out.events().toString());
        assertFalse(Files.exists(Environment.vmPidFile()));
        assertFalse(Files.exists(Environment.vmDiskImage()));
    }

    @Test
    void gatewayIpDefaultIs10_166_11_1() {
        if (System.getenv("ISX_GATEWAY") == null) {
            assertEquals("10.166.11.1", VmManager.gatewayIp());
        }
    }

    @Test
    void mitmPortDefaultIs18443() {
        if (System.getenv("ISX_MITM_PORT") == null) {
            assertEquals("18443", VmManager.mitmPort());
        }
    }

    @Test
    void checkArtifactsThrowsWhenNoKernel() {
        var ex = assertThrows(VmException.class, VmManager::checkArtifacts);
        assertTrue(ex.getMessage().contains("vmlinuz"));
    }

    @Test
    void statusReportsNotRunningWhenVmIsStopped() {
        assertEquals("VM not running", VmManager.status());
    }

    @Test
    void parseDiskSizeGigabytes() {
        assertEquals(60L * 1024 * 1024 * 1024, VmManager.parseDiskSize("60G"));
    }

    @Test
    void parseDiskSizeMegabytes() {
        assertEquals(512L * 1024 * 1024, VmManager.parseDiskSize("512M"));
    }

    @Test
    void parseDiskSizeTerabytes() {
        assertEquals(1024L * 1024 * 1024 * 1024, VmManager.parseDiskSize("1T"));
    }

    @Test
    void parseDiskSizeCaseInsensitive() {
        assertEquals(60L * 1024 * 1024 * 1024, VmManager.parseDiskSize("60g"));
    }

    @Test
    void ensureDiskThrowsWhenNoCompressedImage() {
        var ex = assertThrows(VmException.class, VmManager::ensureDisk);
        assertTrue(ex.getMessage().contains("disk.img.gz"));
    }

    /**
     * A new release's appliance that cannot be downloaded leaves the old one to boot (#2552):
     * the root disk, its stamp, the kernel and the compressed image all stay, and the old image
     * is never extracted under the new version's stamp.
     */
    @Test
    void aFailedApplianceDownloadKeepsTheOldDisk() throws IOException {
        // The appliance files below are written where ISX_APPLIANCE_DIR points, outside the test's home
        assumeTrue(System.getenv("ISX_APPLIANCE_DIR") == null || System.getenv("ISX_APPLIANCE_DIR").isBlank(),
                "ISX_APPLIANCE_DIR names a real appliance directory");
        var version = VmManager.resolvedApplianceVersion;
        var downloads = VmManager.releaseDownloads;
        VmManager.resolvedApplianceVersion = "0.6.0";
        // A loopback host, which DownloadCache refuses before it connects: no DNS lookup, no network
        VmManager.releaseDownloads = "https://127.0.0.1/download";
        try {
            Files.createDirectories(Environment.vmStateDir());
            Files.createDirectories(Environment.applianceDir());
            Files.writeString(Environment.vmDiskImage(), "old root disk");
            Files.writeString(Environment.vmDiskVersion(), "0.5.3");
            Files.writeString(Environment.applianceDir().resolve("version"), "0.5.3");
            Files.writeString(Environment.applianceKernel(), "old kernel");
            Files.writeString(Environment.applianceDiskImage(), "old image");

            var out = RecordingOutput.during(VmManager::ensureDisk);

            var warnings = out.events(RecordingOutput.Kind.WARN);
            assertTrue(warnings.stream().anyMatch(w -> w.msg().contains("Could not download appliance 0.6.0")), warnings.toString());
            assertTrue(out.events(RecordingOutput.Kind.STEP).stream().noneMatch(e -> e.msg().contains("Replacing appliance root disk")),
                    "a failed download must not claim the disk is replaced: " + out.events());
            assertEquals("old root disk", Files.readString(Environment.vmDiskImage()));
            assertEquals("0.5.3", Files.readString(Environment.vmDiskVersion()));
            assertEquals("0.5.3", Files.readString(Environment.applianceDir().resolve("version")));
            assertEquals("old kernel", Files.readString(Environment.applianceKernel()));
            assertEquals("old image", Files.readString(Environment.applianceDiskImage()));
        } finally {
            VmManager.resolvedApplianceVersion = version;
            VmManager.releaseDownloads = downloads;
        }
    }

    @Test
    void swapSizeDefaultIs12G() {
        if (System.getenv("ISX_VM_SWAP") == null) {
            assertEquals("12G", VmManager.swapSize());
        }
    }

    @Test
    void ensureSwapCreatesSparseFile() {
        VmManager.ensureSwap();

        var swapImage = Environment.vmSwapImage();
        assertTrue(Files.exists(swapImage));
        assertEquals(12L * 1024 * 1024 * 1024, swapImage.toFile().length());
    }

    @Test
    void ensureSwapIsIdempotent() throws Exception {
        VmManager.ensureSwap();
        var swapImage = Environment.vmSwapImage();
        var modifiedFirst = Files.getLastModifiedTime(swapImage);

        Thread.sleep(50);
        VmManager.ensureSwap();
        var modifiedSecond = Files.getLastModifiedTime(swapImage);

        assertEquals(modifiedFirst, modifiedSecond);
    }

    // --- data disk resize (grow-only) ---

    @Test
    void dataDiskSizeBytesIsMinusOneWhenAbsent() {
        assertEquals(-1, VmManager.dataDiskSizeBytes());
    }

    @Test
    void dataDiskSizeBytesReflectsCreatedDisk() {
        VmManager.ensureDataDisk();
        assertEquals(VmManager.parseDiskSize(VmManager.diskSize()), VmManager.dataDiskSizeBytes());
    }

    @Test
    void resizeDataDiskGrowsTheImage() {
        VmManager.ensureDataDisk();
        long current = VmManager.dataDiskSizeBytes();
        long target = current * 2;
        long targetGiB = target / (1024L * 1024 * 1024);
        long result = VmManager.resizeDataDisk(targetGiB + "G");
        assertEquals(targetGiB * 1024 * 1024 * 1024, result);
        assertEquals(result, VmManager.dataDiskSizeBytes());
    }

    @Test
    void resetDataDiskKeepsAGrownSizeButNotTheContents() throws Exception {
        // No appliance artifacts here, so the VM cannot start: the reset still has to leave a
        // blank disk of the same size behind.
        assumeFalse(Files.exists(Environment.applianceKernel()), "appliance artifacts present");
        VmManager.ensureDataDisk();
        long grown = VmManager.dataDiskSizeBytes() * 2;
        VmManager.resizeDataDisk(grown / (1024L * 1024 * 1024) + "G");
        try (var raf = new RandomAccessFile(Environment.vmDataImage().toFile(), "rw")) {
            raf.write("_BHRfS_M".getBytes(StandardCharsets.US_ASCII));
        }

        assertEquals(VmManager.ResetResult.VM_DOWN, VmManager.resetDataDisk(),
                "the disk is replaced even though the VM cannot start without artifacts");

        assertEquals(grown, VmManager.dataDiskSizeBytes());
        try (var in = Files.newInputStream(Environment.vmDataImage())) {
            assertArrayEquals(new byte[8], in.readNBytes(8), "the old contents must be gone");
        }
    }

    @Test
    void resetDataDiskThatCannotDeleteTheDiskReportsNothingWasReset() throws Exception {
        // Callers clean up after the instances on a reset disk, so a reset that never happened
        // must not look like one whose VM failed to come back.
        var disk = Environment.vmDataImage();
        Files.createDirectories(disk);
        Files.writeString(disk.resolve("keep"), "x");

        assertEquals(VmManager.ResetResult.NOT_RESET, VmManager.resetDataDisk());

        assertTrue(Files.exists(disk.resolve("keep")));
    }

    @Test
    void resizeDataDiskThrowsWhenAbsent() {
        var ex = assertThrows(VmException.class, () -> VmManager.resizeDataDisk("100G"));
        assertTrue(ex.getMessage().contains("No data disk"));
    }

    @Test
    void resizeDataDiskRejectsShrink() {
        VmManager.ensureDataDisk();
        long currentGiB = VmManager.dataDiskSizeBytes() / (1024L * 1024 * 1024);
        long smaller = Math.max(1, currentGiB / 2);
        if (smaller >= currentGiB) return; // disk too small to express a smaller whole-GiB size
        var ex = assertThrows(VmException.class, () -> VmManager.resizeDataDisk(smaller + "G"));
        assertTrue(ex.getMessage().contains("larger"));
    }

    @Test
    void resizeDataDiskRejectsEqualSize() {
        VmManager.ensureDataDisk();
        var ex = assertThrows(VmException.class, () -> VmManager.resizeDataDisk(VmManager.diskSize()));
        assertTrue(ex.getMessage().contains("larger"));
    }

    // --- btrfs superblock validation ---

    @Test
    void validateBtrfsMagicAcceptsValidImage() throws Exception {
        var img = tempHome.resolve("valid.img");
        try (var raf = new RandomAccessFile(img.toFile(), "rw")) {
            raf.setLength(0x10048);
            raf.seek(0x10040);
            raf.write("_BHRfS_M".getBytes(StandardCharsets.US_ASCII));
        }
        assertDoesNotThrow(() -> VmManager.validateBtrfsMagic(img));
    }

    @Test
    void validateBtrfsMagicRejectsTruncatedImage() throws Exception {
        var img = tempHome.resolve("tiny.img");
        try (var raf = new RandomAccessFile(img.toFile(), "rw")) {
            raf.setLength(1024);
        }
        var ex = assertThrows(IOException.class, () -> VmManager.validateBtrfsMagic(img));
        assertTrue(ex.getMessage().contains("too small"));
    }

    @Test
    void validateBtrfsMagicRejectsZeroedImage() throws Exception {
        var img = tempHome.resolve("zeroed.img");
        try (var raf = new RandomAccessFile(img.toFile(), "rw")) {
            raf.setLength(0x10048);
        }
        var ex = assertThrows(IOException.class, () -> VmManager.validateBtrfsMagic(img));
        assertTrue(ex.getMessage().contains("no valid btrfs superblock"));
    }

    // --- recoverReachability orchestration (grace -> layer check -> forwarder restart -> backstop) ---

    @Test
    void recoverReachabilityReturnsEarlyWhenGraceClears() {
        var restarts = new java.util.concurrent.atomic.AtomicInteger();
        boolean ok = VmManager.recoverReachability(
                secs -> true,                                       // reachable on the grace probe
                () -> { restarts.incrementAndGet(); return true; },
                java.util.Optional::empty);
        assertTrue(ok);
        assertEquals(0, restarts.get(), "must not restart the forwarder if the grace probe succeeds");
    }

    @Test
    void recoverReachabilityRestartsForwarderThenRecovers() {
        var probes = new java.util.ArrayDeque<>(java.util.List.of(false, true)); // grace fails, post-restart succeeds
        var restarts = new java.util.concurrent.atomic.AtomicInteger();
        boolean ok = VmManager.recoverReachability(
                secs -> probes.poll(),
                () -> { restarts.incrementAndGet(); return true; },
                java.util.Optional::empty);                         // unknown layer — attempt restart
        assertTrue(ok);
        assertEquals(1, restarts.get());
        assertTrue(probes.isEmpty(), "must not fall through to the backstop probe once recovery succeeds");
    }

    @Test
    void recoverReachabilityRestartsWhenLayerIsForwarder() {
        var probes = new java.util.ArrayDeque<>(java.util.List.of(false, true)); // grace fails, post-restart succeeds
        var restarts = new java.util.concurrent.atomic.AtomicInteger();
        boolean ok = VmManager.recoverReachability(
                secs -> probes.poll(),
                () -> { restarts.incrementAndGet(); return true; },
                () -> java.util.Optional.of(VmManager.LeakLayer.FORWARDER));
        assertTrue(ok);
        assertEquals(1, restarts.get(), "forwarder-layer wedge should attempt a restart");
    }

    @Test
    void recoverReachabilityFailsFastWhenLayerIsLauncher() {
        var restarts = new java.util.concurrent.atomic.AtomicInteger();
        boolean ok = VmManager.recoverReachability(
                secs -> false,                                      // grace fails
                () -> { restarts.incrementAndGet(); return true; },
                () -> java.util.Optional.of(VmManager.LeakLayer.LAUNCHER));
        assertFalse(ok, "launcher wedge cannot be fixed by a forwarder restart — must fail fast");
        assertEquals(0, restarts.get(), "must not attempt a forwarder restart for a launcher wedge");
    }

    @Test
    void recoverReachabilityFallsBackToBackstopWhenAgentDoesNotConfirm() {
        var probes = new java.util.ArrayDeque<>(java.util.List.of(false, true)); // grace fails, backstop succeeds
        boolean ok = VmManager.recoverReachability(
                secs -> probes.poll(),
                () -> false,                                        // agent unreachable or unconfirmed
                java.util.Optional::empty);
        assertTrue(ok);
        assertTrue(probes.isEmpty(), "must probe the backstop after the restart cannot be confirmed");
    }

    @Test
    void recoverReachabilityGivesUpWhenNothingRecovers() {
        boolean ok = VmManager.recoverReachability(secs -> false, () -> false, java.util.Optional::empty);
        assertFalse(ok, "must return false so the caller can surface an actionable error");
    }

    // --- leakLayer (moved from DoctorCommandTest — canonical location is VmManager) ---

    @Test
    void leakLayerLocatesLauncherWhenGuestCountStaysLow() {
        assertEquals(VmManager.LeakLayer.LAUNCHER, VmManager.leakLayer(300, 5));
        assertEquals(VmManager.LeakLayer.LAUNCHER, VmManager.leakLayer(100, 50), "boundary: guest*2 == host");
    }

    @Test
    void leakLayerLocatesForwarderWhenBothCountsClimb() {
        assertEquals(VmManager.LeakLayer.FORWARDER, VmManager.leakLayer(300, 280));
        assertEquals(VmManager.LeakLayer.FORWARDER, VmManager.leakLayer(100, 51));
    }

    @Test
    void leakLayerTreatsZeroGuestAsForwarderNotRunning() {
        assertEquals(VmManager.LeakLayer.FORWARDER, VmManager.leakLayer(0, 0),
                "both zero: forwarder not running, not a launcher wedge");
        assertEquals(VmManager.LeakLayer.FORWARDER, VmManager.leakLayer(5, 0),
                "host fds leaked but forwarder gone: restarting it is the correct fix");
    }

    @Test
    void applianceSkewReportedWhenRestartReExtractsDisk() {
        var skew = VmManager.applianceSkew("0.3.7", "0.3.8", "0.3.7");
        assertEquals(new VmManager.ApplianceSkew("0.3.7", "0.3.8"), skew);
        assertNotNull(VmManager.applianceSkew("0.3.7", "0.3.8", ""),
                "an untracked disk is re-extracted on start");
    }

    @Test
    void noApplianceSkewWhenVersionsMatch() {
        assertNull(VmManager.applianceSkew("0.3.8", "0.3.8", "0.3.8"));
    }

    @Test
    void noApplianceSkewWhenRestartKeepsTheSameDisk() {
        // A locally built appliance embeds 0.0.0-SNAPSHOT, but a dev CLI stamps the disk with
        // the latest release: restarting keeps that disk, so "restart for 0.3.8" never clears.
        assertNull(VmManager.applianceSkew("0.0.0-SNAPSHOT", "0.3.8", "0.3.8"));
    }

    @Test
    void applianceSkewIsAWarningWhoeverOwnsTheTerminalCanRoute() throws IOException {
        // Bare `isx` checks the VM before its TUI opens; printed straight to stderr, the notice
        // was drawn over and read only after quitting (#1154).
        var seen = new ArrayList<String>();
        var marker = tempHome.resolve("vm/.appliance-skew-warned");
        var skew = new VmManager.ApplianceSkew("0.3.9", "0.3.10");
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(seen::add))) {
            VmManager.warnOfSkew(skew, marker);
            VmManager.warnOfSkew(skew, marker);
        }
        assertEquals(java.util.List.of(skew.message()), seen, "once, then quiet for the interval");
        assertTrue(Files.exists(marker));
    }
}
