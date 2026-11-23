package dev.incusspawn.lifecycle;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #2555: a branch into a pool with almost nothing free is refused before anything is created.
 * Its first writes would fill the disk, and a full disk stops Incus, so nothing could be deleted
 * through it any more. A pool running short only warns; one that cannot say lets the branch through.
 */
@ExtendWith(TempHome.class)
class BranchPoolSpaceTest {

    private static final long GIB = 1024L * 1024 * 1024;

    private static BranchFlow.Request request() {
        // Airgapped, so the branch asks no proxy; --no-start, so it ends at the copy and its write
        return new BranchFlow.Request("tpl-dev", "dev-1", false, false, NetworkMode.AIRGAP,
                null, null, null, null, List.of(), false, Map.of());
    }

    private static Map<String, ImageDef> template() {
        var def = new ImageDef();
        def.setName("tpl-dev");
        return Map.of("tpl-dev", def);
    }

    @Test
    void aBranchIntoAnExhaustedPoolIsRefusedBeforeAnythingIsCreated() {
        // 60 GiB, the macOS VM's default disk, with 1.5 GiB left: the fake instances' root is on 'default'
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of())
                .poolSpace("default", 60 * GIB - 3 * GIB / 2, 60 * GIB);
        var e = assertThrows(BranchFlow.BranchException.class,
                () -> BranchFlow.preflight(daemon.client(), request(), template()));
        assertTrue(e.getMessage().contains("'default' has 1.5 GiB free (97% full)"), e.getMessage());
        assertTrue(e.getMessage().contains("isx clean pool"), e.getMessage());
        assertFalse(daemon.client().exists("dev-1"));
        assertEquals(1, daemon.requests().stream().filter(r -> r.endsWith("/resources")).count(),
                () -> String.join("\n", daemon.requests()));
    }

    @Test
    void aPoolRunningShortWarnsAndBranches() {
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of())
                .poolSpace("default", 60 * GIB - 4 * GIB, 60 * GIB);
        var recorded = RecordingOutput.during(() ->
                BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request(), template())));
        assertTrue(daemon.client().exists("dev-1"));
        assertTrue(recorded.warnings().stream().anyMatch(w -> w.contains("'default' has only 4.0 GiB free")),
                () -> String.join("\n", recorded.warnings()));
    }

    @Test
    void aPoolThatCannotSayLetsTheBranchThrough() {
        // No /resources answer: Incus decides, as before
        var daemon = new FakeIncusDaemon().container("tpl-dev", Map.of());
        var recorded = RecordingOutput.during(() ->
                BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request(), template())));
        assertTrue(daemon.client().exists("dev-1"));
        assertTrue(recorded.warnings().stream().noneMatch(w -> w.contains("storage pool")), () -> String.join("\n", recorded.warnings()));
    }
}
