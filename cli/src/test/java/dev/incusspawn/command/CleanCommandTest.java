package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
                image("vm", "fedora-44-base-vm"),
                image("manual", "hand-made"),
                image("orphan", "something-else"),
                image("unaliased")), defs);

        assertEquals(List.of("orphan", "unaliased"), fingerprints(scan.unused()));
        assertEquals(List.of("container", "vm"), fingerprints(scan.base()));
    }

    @Test
    void vmAliasWithoutAVmImageUrlIsUnused() {
        var scan = CleanCommand.classifyImages(List.of(image("vm", "fedora-44-base-vm")),
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
        var out = new ByteArrayOutputStream();
        var oldOut = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            command.doExecute();
        } finally {
            System.setOut(oldOut);
        }
        return out.toString(StandardCharsets.UTF_8);
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
}
