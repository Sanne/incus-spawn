package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(IsolatedHome.class)
class CleanCommandTest {

    private static IncusClient withInstances(String... names) {
        var incus = mock(IncusClient.class);
        var list = new ArrayList<Map<String, String>>();
        for (var name : names) list.add(Map.of("name", name));
        when(incus.list()).thenReturn(list);
        return incus;
    }

    @Test
    void deletingFailedBuildsTellsTheProxyOnce() {
        // Each held a static IP and its template's pins (#903): the proxy must stop serving them
        var incus = withInstances("tpl-a-failed-build", "dev-1", "tpl-b-failed-build");
        var warnings = new ArrayList<String>();

        assertEquals(2, CleanCommand.deleteFailedBuilds(incus, warnings));

        verify(incus).delete("tpl-a-failed-build", true);
        verify(incus).delete("tpl-b-failed-build", true);
        verify(incus, never()).delete(eq("dev-1"), anyBoolean());
        assertEquals(1, IsolatedHome.proxySignals());
        assertEquals(List.of(), warnings);
    }

    @Test
    void nothingDeletedSignalsNothing() {
        var incus = withInstances("tpl-a-failed-build");
        doThrow(new RuntimeException("busy")).when(incus).delete("tpl-a-failed-build", true);
        var warnings = new ArrayList<String>();

        assertEquals(0, CleanCommand.deleteFailedBuilds(incus, warnings));

        assertEquals(0, IsolatedHome.proxySignals());
        assertEquals(1, warnings.size());
    }

    @Test
    void thePoolCleanupReportsEachAndTellsTheProxyOnce() {
        // isx clean pool lists and confirms the failed builds itself, then deletes the ones shown
        var incus = withInstances();
        var deleted = new ArrayList<String>();

        assertEquals(2, CleanCommand.deleteFailedBuilds(incus, List.of("tpl-a-failed-build", "tpl-b-failed-build"),
                deleted::add, (name, e) -> { throw new AssertionError(name, e); }));

        assertEquals(List.of("tpl-a-failed-build", "tpl-b-failed-build"), deleted);
        assertEquals(1, IsolatedHome.proxySignals());
    }

    @Test
    void doctorsSingleDeleteTellsTheProxy() {
        var incus = withInstances();
        CleanCommand.deleteFailedBuild(incus, "tpl-a-failed-build");
        verify(incus).delete("tpl-a-failed-build", true);
        assertEquals(1, IsolatedHome.proxySignals());
    }

    private static IncusClient.ImageInfo image(String fingerprint, String... aliases) {
        return new IncusClient.ImageInfo(fingerprint, 100, List.of(aliases));
    }

    private static ImageDef def(String image, String imageUrl, String vmImageUrl) {
        var def = new ImageDef();
        def.setName("tpl-" + image);
        def.setImage(image);
        def.setImageUrl(imageUrl);
        def.setVmImageUrl(vmImageUrl);
        return def;
    }

    private static List<String> fingerprints(List<IncusClient.ImageInfo> images) {
        return images.stream().map(IncusClient.ImageInfo::fingerprint).toList();
    }

    @Test
    void downloadedBaseImagesAreTheirOwnCategory() {
        var defs = List.of(
                def("fedora-44-base", "https://example.com/fedora.tar.xz", "https://example.com/fedora-vm.tar.xz"),
                def("hand-made", null, null),
                def("images:debian/13", null, null));
        var scan = CleanCommand.classifyImages(List.of(
                image("container", "fedora-44-base"),
                // The VM image lives under a derived alias, which once read as unused, so a
                // clean deleted it and the next VM build downloaded gigabytes again.
                image("vm", "fedora-44-base-vm-direct"),
                // The stock VM image, which an older isx on the same host also uses (#1255): a base image, not unused.
                image("legacy-vm", "fedora-44-base-vm"),
                image("manual", "hand-made"),
                image("orphan", "something-else"),
                image("unaliased")), defs);

        assertEquals(List.of("orphan", "unaliased"), fingerprints(scan.unused()));
        assertEquals(List.of("container", "vm", "legacy-vm"), fingerprints(scan.base()));
    }

    @Test
    void vmAliasWithoutAVmImageUrlIsUnused() {
        var scan = CleanCommand.classifyImages(List.of(image("vm", "fedora-44-base-vm-direct")),
                List.of(def("fedora-44-base", "https://example.com/fedora.tar.xz", null)));

        assertEquals(List.of("vm"), fingerprints(scan.unused()));
        assertEquals(List.of(), scan.base());
    }

    // -- the macOS data disk: every instance, template and image (#1155) --

    /** A stopped macOS install: VM state beside the data disk, and the appliance artifacts. */
    private static void seedVmState() throws Exception {
        Files.createDirectories(Environment.vmStateDir());
        Files.writeString(Environment.vmDataImage(), "the cow pool");
        Files.writeString(Environment.vmLogFile(), "boot log");
        Files.writeString(Environment.vmDiskImage(), "root disk");
        Files.createDirectories(Environment.applianceDir());
        Files.writeString(Environment.applianceKernel(), "kernel");
    }

    private static String run(BaseCommand command) throws Exception {
        return capture(command::doExecute);
    }

    @Test
    void cleanStateKeepsTheDataDisk() throws Exception {
        seedVmState();
        var state = new CleanCommand.State();
        state.skipConfirmation = true;

        var out = run(state);

        assertTrue(Files.exists(Environment.vmDataImage()), "the data disk holds every instance:\n" + out);
        assertFalse(Files.exists(Environment.vmLogFile()));
        assertFalse(Files.exists(Environment.vmDiskImage()));
        assertFalse(Files.exists(Environment.dataDir()));
        assertTrue(out.contains("Kept " + Environment.vmDataImage()), out);
        assertTrue(out.contains("--delete-instances"), out);
    }

    @Test
    void cleanAllKeepsTheDataDiskAndSaysSo() throws Exception {
        seedVmState();
        var all = new CleanCommand.All();
        all.skipConfirmation = true;
        all.noBackup = true;

        var out = run(all);

        assertTrue(Files.exists(Environment.vmDataImage()), out);
        assertFalse(Files.exists(Environment.vmLogFile()));
        assertTrue(out.contains("Kept " + Environment.vmDataImage()), out);
    }

    @Test
    void deleteInstancesTakesTheDataDiskAndSaysWhatItHolds() throws Exception {
        seedVmState();
        var all = new CleanCommand.All();
        all.skipConfirmation = true;
        all.noBackup = true;
        all.deleteInstances = true;

        var out = run(all);

        assertFalse(Files.exists(Environment.vmStateDir()), out);
        assertTrue(out.contains("every instance, template and image"), out);
        // The note that the pool is untouched was false here: the pool is this disk
        assertFalse(out.contains("are not affected"), out);
    }

    @Test
    void aDryRunNamesTheKeptDataDisk() throws Exception {
        seedVmState();
        var state = new CleanCommand.State();
        state.dryRun = true;

        var out = run(state);

        assertTrue(Files.exists(Environment.vmLogFile()));
        assertTrue(out.contains("Kept " + Environment.vmDataImage()), out);
    }

    @Test
    void aStateDirectoryHoldingOnlyTheDataDiskIsNothingToClean() throws Exception {
        Files.createDirectories(Environment.vmStateDir());
        Files.writeString(Environment.vmDataImage(), "the cow pool");
        var state = new CleanCommand.State();
        state.skipConfirmation = true;

        var out = run(state);

        assertTrue(out.contains("Nothing to clean"), out);
        assertTrue(Files.exists(Environment.vmDataImage()), out);
    }

    @Test
    void deleteInstancesWithNoDataDiskSaysItDidNothing() throws Exception {
        // Linux: the instances are not in any file this command deletes
        Files.createDirectories(Environment.vmStateDir());
        Files.writeString(Environment.vmLogFile(), "log");
        var state = new CleanCommand.State();
        state.skipConfirmation = true;
        state.deleteInstances = true;

        var out = run(state);

        assertTrue(out.contains("--delete-instances only deletes the macOS VM's data disk"), out);
    }

    // -- the DNF cache volume is part of what isx clean cache asks about (#1627) --

    private static IncusClient withDnfCacheVolume() {
        var incus = mock(IncusClient.class);
        when(incus.probeCowPool()).thenReturn(new IncusClient.CowPoolProbe(true, "cow", "btrfs"));
        when(incus.storageVolumeExists("cow", GuestProvisioning.DNF_CACHE_VOLUME)).thenReturn(true);
        when(incus.deleteStorageVolume("cow", GuestProvisioning.DNF_CACHE_VOLUME)).thenReturn(true);
        return incus;
    }

    private interface Action {
        void run() throws Exception;
    }

    /** What {@code action} reported, every kind of message, one per line. */
    private static String capture(Action action) throws Exception {
        return String.join("\n", RecordingOutput.during(action::run).lines());
    }

    private static String runCache(IncusClient incus, boolean dryRun, boolean answer) throws Exception {
        return capture(() -> CleanCommand.Cache.clean(incus, dryRun, question -> answer));
    }

    @Test
    void decliningCleanCacheKeepsTheDnfCacheVolume() throws Exception {
        Files.createDirectories(Environment.cacheDir());
        var blob = Files.writeString(Environment.cacheDir().resolve("blob"), "cached");
        var incus = withDnfCacheVolume();

        var out = runCache(incus, false, false);

        verify(incus, never()).deleteStorageVolume(anyString(), anyString());
        assertTrue(Files.exists(blob), out);
        assertFalse(out.contains("Deleted DNF cache volume"), out);
    }

    @Test
    void cleanCacheListsTheDnfCacheVolumeBeforeAsking() throws Exception {
        Files.createDirectories(Environment.cacheDir());
        var blob = Files.writeString(Environment.cacheDir().resolve("blob"), "cached");
        var incus = withDnfCacheVolume();

        var out = runCache(incus, false, true);

        verify(incus).deleteStorageVolume("cow", GuestProvisioning.DNF_CACHE_VOLUME);
        assertFalse(Files.exists(blob), out);
        var listed = out.indexOf("DNF cache volume (" + GuestProvisioning.DNF_CACHE_VOLUME + ") in pool cow");
        assertTrue(listed >= 0 && listed < out.indexOf("Deleted DNF cache volume"), out);
    }

    @Test
    void cleanCacheAsksForTheDnfCacheVolumeAlone() throws Exception {
        // No host cache: the volume is still something to delete, and still asked about
        var incus = withDnfCacheVolume();

        var out = runCache(incus, false, false);

        verify(incus, never()).deleteStorageVolume(anyString(), anyString());
        assertFalse(out.contains("Nothing to clean"), out);
        assertTrue(out.contains("DNF cache volume"), out);
    }

    @Test
    void aCleanCacheDryRunDeletesNothing() throws Exception {
        Files.createDirectories(Environment.cacheDir());
        Files.writeString(Environment.cacheDir().resolve("blob"), "cached");
        var incus = withDnfCacheVolume();

        var out = runCache(incus, true, true);

        verify(incus, never()).deleteStorageVolume(anyString(), anyString());
        assertTrue(out.contains("Would delete"), out);
        assertTrue(out.contains("DNF cache volume"), out);
    }

    @Test
    void decliningThePoolCleansDnfCacheKeepsIt() {
        // isx clean pool asks about each category; the DNF cache was deleted without asking
        var incus = withDnfCacheVolume();
        var asked = new ArrayList<String>();

        assertTrue(CleanCommand.Pool.cleanDnfCacheFromPool(incus, "cow", false, q -> asked.add(q) && false));

        assertEquals(1, asked.size());
        verify(incus, never()).deleteStorageVolume(anyString(), anyString());
    }

    @Test
    void cleanDnfDeletesOnlyTheVolume() throws Exception {
        Files.createDirectories(Environment.cacheDir());
        var blob = Files.writeString(Environment.cacheDir().resolve("blob"), "cached");
        var incus = withDnfCacheVolume();

        var out = capture(() -> CleanCommand.Dnf.clean(incus, false, q -> true));

        verify(incus).deleteStorageVolume("cow", GuestProvisioning.DNF_CACHE_VOLUME);
        assertTrue(Files.exists(blob), out);
        assertFalse(out.contains("Freed"), out);
    }

    @Test
    void cleanDnfWithNoVolumeSaysSo() throws Exception {
        var incus = mock(IncusClient.class);
        when(incus.probeCowPool()).thenReturn(new IncusClient.CowPoolProbe(true, "cow", "btrfs"));

        var out = capture(() -> CleanCommand.Dnf.clean(incus, false, q -> true));

        assertTrue(out.contains("Nothing to clean"), out);
    }

    @Test
    void cleanDnfFailsWhenItCouldNotLook() {
        // Its whole job is the volume: Incus not answering is not "nothing to clean"
        var incus = mock(IncusClient.class);
        when(incus.probeCowPool()).thenReturn(new IncusClient.CowPoolProbe(false, null, null));

        var e = assertThrows(IllegalStateException.class,
                () -> capture(() -> CleanCommand.Dnf.clean(incus, false, q -> true)));

        assertTrue(e.getMessage().contains("could not look for the DNF cache volume"), e.getMessage());
        verify(incus, never()).deleteStorageVolume(anyString(), anyString());
    }

    @Test
    void cleanCacheGoesOnWhenItCouldNotLookForTheVolume() throws Exception {
        Files.createDirectories(Environment.cacheDir());
        var blob = Files.writeString(Environment.cacheDir().resolve("blob"), "cached");
        var incus = mock(IncusClient.class);
        when(incus.probeCowPool()).thenReturn(new IncusClient.CowPoolProbe(false, null, null));

        var out = runCache(incus, false, true);

        assertFalse(Files.exists(blob), out);
    }

    @Test
    void decliningCleanAllKeepsTheDnfCacheVolume() throws Exception {
        Files.createDirectories(Environment.cacheDir());
        var blob = Files.writeString(Environment.cacheDir().resolve("blob"), "cached");
        var incus = withDnfCacheVolume();

        var out = capture(() -> CleanCommand.All.clean(incus, false, false, q -> false, null));

        assertTrue(out.contains("DNF cache volume"), out);
        verify(incus, never()).deleteStorageVolume(anyString(), anyString());
        assertTrue(Files.exists(blob), out);
    }

    @Test
    void cleanAllDeletesTheDnfCacheVolumeOnTheSameAnswer() throws Exception {
        Files.createDirectories(Environment.cacheDir());
        Files.writeString(Environment.cacheDir().resolve("blob"), "cached");
        var incus = withDnfCacheVolume();

        capture(() -> CleanCommand.All.clean(incus, false, false, q -> true, null));

        verify(incus).deleteStorageVolume("cow", GuestProvisioning.DNF_CACHE_VOLUME);
    }

    @Test
    void cleanAllTakingTheDataDiskDoesNotLookForTheVolume() throws Exception {
        // The pool is on the disk being deleted: there is no volume left to ask about
        seedVmState();
        var incus = mock(IncusClient.class);

        capture(() -> CleanCommand.All.clean(incus, true, false, q -> true, null));

        verifyNoInteractions(incus);
        assertFalse(Files.exists(Environment.vmDataImage()));
    }

    @Test
    void cleanDnfFailsWhenTheDeleteFails() {
        var incus = withDnfCacheVolume();
        when(incus.deleteStorageVolume("cow", GuestProvisioning.DNF_CACHE_VOLUME))
                .thenThrow(new IncusException("Failed to delete storage volume dnf-cache from pool cow: "
                        + "Storage volume is still in use"));

        var e = assertThrows(IllegalStateException.class,
                () -> capture(() -> CleanCommand.Dnf.clean(incus, false, q -> true)));

        assertTrue(e.getMessage().contains("in use by a running build"), e.getMessage());
    }

    @Test
    void cleanDnfFailsWhenIncusCouldNotSayWithNoMessage() {
        var incus = mock(IncusClient.class);
        when(incus.probeCowPool()).thenThrow(new IncusException(null));

        var e = assertThrows(IllegalStateException.class,
                () -> capture(() -> CleanCommand.Dnf.clean(incus, false, q -> true)));

        assertFalse(e.getMessage().endsWith("null"), e.getMessage());
    }

    @Test
    void cleanCacheWithNoIncusCleansTheCacheAlone() throws Exception {
        // macOS with its VM stopped: nothing to ask, and nothing to warn about
        Files.createDirectories(Environment.cacheDir());
        var blob = Files.writeString(Environment.cacheDir().resolve("blob"), "cached");

        capture(() -> CleanCommand.Cache.clean(null, false, q -> true));

        assertFalse(Files.exists(blob));
    }

    @Test
    void theTotalCountsOnlyTheDirectories() throws Exception {
        Files.createDirectories(Environment.cacheDir());
        Files.writeString(Environment.cacheDir().resolve("blob"), "cached");

        var out = runCache(withDnfCacheVolume(), true, true);

        assertTrue(out.indexOf("Total:") < out.indexOf("DNF cache volume"), out);
    }

    @Test
    void cleanDnfSaysWhenTheVolumeWasAlreadyGone() throws Exception {
        // Another clean deleted it between the listing and the answer
        var incus = withDnfCacheVolume();
        when(incus.deleteStorageVolume("cow", GuestProvisioning.DNF_CACHE_VOLUME)).thenReturn(false);

        var out = capture(() -> CleanCommand.Dnf.clean(incus, false, q -> true));

        assertTrue(out.contains("already gone"), out);
    }

    @Test
    void cleanAllWithOnlyTheVolumeLeftDoesNotWarnAboutTheConfiguration() throws Exception {
        var incus = withDnfCacheVolume();

        var out = capture(() -> CleanCommand.All.clean(incus, false, false, q -> true, null));

        assertFalse(out.contains("SSH keys"), out);
        verify(incus).deleteStorageVolume("cow", GuestProvisioning.DNF_CACHE_VOLUME);
    }

    /**
     * What a clean would delete is a group of its directories and their total, and what it costs
     * is a warning with its consequence under it, which the TUI's log and a recorder keep (#1703).
     */
    @Test
    void whatACleanDeletesIsAGroupAndItsCostAWarning() throws Exception {
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.configDir().resolve("config.yaml"), "x: 1\n");
        var config = new CleanCommand.Config();
        config.skipConfirmation = true;
        config.noBackup = true;

        var recorded = RecordingOutput.during(config::doExecute);

        var group = recorded.events(RecordingOutput.Kind.GROUP);
        assertEquals(List.of("Will delete"), group.stream().map(RecordingOutput.Event::msg).toList());
        assertTrue(recorded.events(RecordingOutput.Kind.STEP).stream()
                .anyMatch(e -> e.msg().startsWith(Environment.configDir().toString())), recorded.lines().toString());
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.WARN,
                "This will permanently delete your SSH keys, CA certificate, and configuration.",
                "You will need to run 'isx init' again and rebuild all templates.")),
                recorded.events(RecordingOutput.Kind.WARN));
        assertFalse(Files.exists(Environment.configDir()));
    }
}
