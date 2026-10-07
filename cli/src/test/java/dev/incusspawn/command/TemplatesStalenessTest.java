package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.OutputFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The build state and staleness {@code isx templates --format=plain|json} reports (#1115), and
 * the one judgement ({@link TemplateStaleness}) the TUI's {@code ! △ ↑} marks share with it.
 * A golden output: fields are added at the end, never renamed, removed or reordered.
 */
@ExtendWith(IsolatedHome.class)
class TemplatesStalenessTest {

    private static final ZoneOffset ZONE = ZoneOffset.ofHours(2);
    private static final String CURRENT = "1.4.0";

    /** The built-in minimal → dev → java chain. */
    private static Map<String, ImageDef> chain() {
        var all = ImageDef.loadAll();
        var defs = new LinkedHashMap<String, ImageDef>();
        for (var name : List.of("tpl-minimal", "tpl-dev", "tpl-java")) defs.put(name, all.get(name));
        return defs;
    }

    private static Map<String, String> toolFingerprints(Map<String, ImageDef> defs) {
        return TemplateStaleness.toolFingerprints(defs.values(), RuntimeServices.toolDefLoader());
    }

    private static String json(Object value) {
        var bytes = new ByteArrayOutputStream();
        OutputFormat.printJson(new PrintStream(bytes, true, StandardCharsets.UTF_8), value);
        return bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void templatesReportTheirBuildAndWhyItIsStale() {
        var defs = chain();
        var fps = toolFingerprints(defs);
        var minimalSha = defs.get("tpl-minimal").contentFingerprint(fps);
        // tpl-minimal is current. tpl-dev was built by another isx, from another definition,
        // before its parent was rebuilt. tpl-java is not built.
        var built = List.of(
                new TemplateStaleness.Built("tpl-minimal", "2026-10-05T10:00:00", CURRENT, minimalSha, MachineType.CONTAINER),
                new TemplateStaleness.Built("tpl-dev", "2026-10-01T09:30:00", "1.3.0", "an-older-definition", MachineType.CONTAINER));
        var records = TemplatesCommand.ListSub.records(defs, built, () -> fps, CURRENT, ZONE);
        assertEquals("""
                [ {
                  "name" : "tpl-minimal",
                  "parent" : null,
                  "source" : "built-in",
                  "description" : "Base OS only",
                  "built" : true,
                  "built_at" : "2026-10-05T10:00:00+02:00",
                  "version_outdated" : false,
                  "definition_changed" : false,
                  "parent_rebuilt" : false
                }, {
                  "name" : "tpl-dev",
                  "parent" : "tpl-minimal",
                  "source" : "built-in",
                  "description" : "Podman, GitHub CLI, Starship",
                  "built" : true,
                  "built_at" : "2026-10-01T09:30:00+02:00",
                  "version_outdated" : true,
                  "definition_changed" : true,
                  "parent_rebuilt" : true
                }, {
                  "name" : "tpl-java",
                  "parent" : "tpl-dev",
                  "source" : "built-in",
                  "description" : "JDK + Maven",
                  "built" : false,
                  "built_at" : null,
                  "version_outdated" : null,
                  "definition_changed" : null,
                  "parent_rebuilt" : null
                } ]
                """, json(records));
    }

    @Test
    void eachReasonStandsAlone() {
        var defs = chain();
        var fps = toolFingerprints(defs);
        var sha = (java.util.function.Function<String, String>) n -> defs.get(n).contentFingerprint(fps);
        // Every one current; tpl-java built before tpl-dev, so only its parent_rebuilt is set.
        var stale = TemplateStaleness.assess(List.of(
                        new TemplateStaleness.Built("tpl-minimal", "2026-10-01T08:00:00", CURRENT, sha.apply("tpl-minimal"), MachineType.CONTAINER),
                        new TemplateStaleness.Built("tpl-dev", "2026-10-03T08:00:00", CURRENT, sha.apply("tpl-dev"), MachineType.CONTAINER),
                        new TemplateStaleness.Built("tpl-java", "2026-10-02T08:00:00", CURRENT, sha.apply("tpl-java"), MachineType.CONTAINER)),
                defs, Set.of(), () -> fps, CURRENT);
        assertEquals(new TemplateStaleness.Staleness(false, false, false), stale.get("tpl-minimal"));
        assertEquals(new TemplateStaleness.Staleness(false, false, false), stale.get("tpl-dev"));
        assertEquals(new TemplateStaleness.Staleness(false, false, true), stale.get("tpl-java"));

        // Only the definition: same version, same times, another fingerprint.
        var changed = TemplateStaleness.assess(List.of(
                        new TemplateStaleness.Built("tpl-minimal", "2026-10-01T08:00:00", CURRENT, "another", MachineType.CONTAINER)),
                defs, Set.of(), () -> fps, CURRENT);
        assertEquals(new TemplateStaleness.Staleness(false, true, false), changed.get("tpl-minimal"));
    }

    @Test
    void whenIncusCannotBeAskedTheBuildFieldsAreUnknownNotFalse() {
        var records = TemplatesCommand.ListSub.records(chain(), null,
                () -> fail("nothing is built, so no definition is compared"), CURRENT, ZONE);
        for (var record : records) {
            assertTrue(record.containsKey("built"));
            assertNull(record.get("built"), record.toString());
            assertNull(record.get("parent_rebuilt"), record.toString());
        }
    }

    @Test
    void theBuiltTemplatesComeFromOneListingWithoutLiveState() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-minimal", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                        Metadata.CREATED, "2026-10-05T10:00:00", Metadata.BUILD_VERSION, CURRENT))
                .container("tpl-dev" + BuildCommand.REBUILDING_SUFFIX, Map.of(Metadata.TYPE, Metadata.TYPE_BASE))
                .container("dev-1", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "tpl-minimal"))
                .container("not-ours", Map.of());
        daemon.clearRequests();
        var built = ListCommand.builtTemplates(daemon.client().listJsonConfig());
        assertEquals(List.of("GET /1.0/instances?recursion=1"), daemon.requests());
        assertEquals(List.of(new TemplateStaleness.Built("tpl-minimal", "2026-10-05T10:00:00", CURRENT, "", MachineType.CONTAINER)), built);
    }

    @Test
    void aBuildWithoutARecordedVersionIsOutdatedAndAStoredSourceIsNeverCompared() {
        var defs = chain();
        var calls = new AtomicInteger();
        var stale = TemplateStaleness.assess(List.of(
                        new TemplateStaleness.Built("tpl-minimal", "built", "", "x", MachineType.CONTAINER),
                        new TemplateStaleness.Built("tpl-dev", "2026-10-01", CURRENT, "", MachineType.CONTAINER)),
                defs, Set.of("tpl-minimal"), () -> { calls.incrementAndGet(); return Map.of(); }, CURRENT);
        assertEquals(new TemplateStaleness.Staleness(true, false, false), stale.get("tpl-minimal"));
        // A parent with no readable build time cannot be said to be newer.
        assertEquals(new TemplateStaleness.Staleness(false, false, false), stale.get("tpl-dev"));
        assertEquals(0, calls.get(), "no definition was compared, so no tool was fingerprinted");
    }

    /**
     * Each reason alone makes a template out of sync, a rebuilt parent's included: the TUI's
     * "Rebuild out of sync templates" count is what {@code isx build --out-of-sync} rebuilds (#1130).
     */
    @Test
    void anyReasonMakesATemplateOutOfSync() {
        assertFalse(new TemplateStaleness.Staleness(false, false, false).outOfSync());
        assertTrue(new TemplateStaleness.Staleness(true, false, false).outOfSync());
        assertTrue(new TemplateStaleness.Staleness(false, true, false).outOfSync());
        assertTrue(new TemplateStaleness.Staleness(false, false, true).outOfSync());
    }

    private static TemplateStaleness.Built built(String created, MachineType type) {
        return new TemplateStaleness.Built("t", created, CURRENT, "", type);
    }

    @Test
    void aParentIsRebuiltOnlyWhenBothTimesAreKnownAndItsIsLater() {
        var child = built("2026-10-05T10:00:00", MachineType.CONTAINER);
        assertTrue(TemplateStaleness.parentRebuilt(built("2026-10-05T10:00:01", MachineType.CONTAINER), child));
        assertFalse(TemplateStaleness.parentRebuilt(built("2026-10-05T10:00:00", MachineType.CONTAINER), child));
        assertFalse(TemplateStaleness.parentRebuilt(built("2026-10-04T10:00:00", MachineType.CONTAINER), child));
        assertFalse(TemplateStaleness.parentRebuilt(null, child));
        assertFalse(TemplateStaleness.parentRebuilt(built("2026-10-05T10:00:01", MachineType.CONTAINER),
                built("", MachineType.CONTAINER)));
        assertFalse(TemplateStaleness.parentRebuilt(built("built", MachineType.CONTAINER), child));
    }

    /**
     * A VM over a container parent was built from the definitions, not copied, so the parent's
     * later build is no {@code ↑} for the TUI or {@code isx templates} either (#1130 review).
     */
    @Test
    void aTemplateOfAnotherMachineTypeIsNotMarkedForItsParentsRebuild() {
        var defs = chain();
        var stale = TemplateStaleness.assess(List.of(
                        new TemplateStaleness.Built("tpl-minimal", "2026-10-07T08:00:00", CURRENT, "", MachineType.CONTAINER),
                        new TemplateStaleness.Built("tpl-dev", "2026-10-01T08:00:00", CURRENT, "", MachineType.VM)),
                defs, Set.of(), Map::of, CURRENT);
        assertFalse(stale.get("tpl-dev").parentRebuilt());
        assertFalse(TemplateStaleness.parentRebuilt(built("2026-10-05T10:00:01", MachineType.VM),
                built("2026-10-05T10:00:00", MachineType.CONTAINER)));
    }
}
