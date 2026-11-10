package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.RecordingOutput;
import io.hyperfoil.tools.jjq.value.JqObject;
import dev.incusspawn.BuildInfo;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;
import dev.incusspawn.RuntimeConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Which templates one {@code isx build} invocation rebuilds, and in what order, driven through
 * {@link BuildCommand#dispatch} against {@link FakeIncusDaemon} with each build recorded instead
 * of run (#1130): several targets for {@code --with-parents} and {@code --with-descendants}, and
 * {@code --out-of-sync} repairing a template whose parent was rebuilt after it.
 */
@ExtendWith(IsolatedHome.class)
class BuildCommandBatchTest {

    private static final String NOW = BuildInfo.instance().version();

    /** base → layer → {tpl-isx, tpl-quarkus}, and an unrelated root with a child. */
    private static Map<String, ImageDef> tree() {
        var defs = new LinkedHashMap<String, ImageDef>();
        for (var pair : List.of(
                List.of("tpl-base", ""), List.of("tpl-layer", "tpl-base"),
                List.of("tpl-isx", "tpl-layer"), List.of("tpl-quarkus", "tpl-layer"),
                List.of("tpl-other", ""), List.of("tpl-other-child", "tpl-other"))) {
            var def = new ImageDef();
            def.setName(pair.get(0));
            if (!pair.get(1).isEmpty()) def.setParent(pair.get(1));
            defs.put(def.getName(), def);
        }
        return defs;
    }

    private final FakeIncusDaemon daemon = new FakeIncusDaemon();
    private final List<String> built = new ArrayList<>();
    private String stderr = "";
    /** What BuildOutput warned, before the first build and in all. */
    private List<String> warningsBeforeBuild;
    private List<String> warnings = List.of();

    /** Every template of {@link #tree()} built, current, at {@code created}. */
    private void builtAt(Map<String, String> created) {
        created.forEach((name, at) -> daemon.container(name, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_VERSION, NOW,
                Metadata.CREATED, at)));
    }

    /** Each recorded build leaves its image behind, current, as a real build's swap does. */
    private boolean buildsLeaveImages;
    /** Recorded builds of these fail, as a real failed build does. */
    private final Set<String> failing = new HashSet<>();
    /** Tools the loader knows, each taking no parameters. */
    private final Set<String> tools = new HashSet<>();
    /** What the loader lists as every known tool; none unless a test sets them. */
    private Map<String, ToolSetup> allToolSetups = Map.of();
    /** Definitions besides {@link #tree()}. */
    private final Map<String, ImageDef> extraDefs = new LinkedHashMap<>();

    private int build(String... args) {
        var cmd = spy(new BuildCommand());
        cmd.incus = daemon.client();
        cmd.toolDefLoader = mock(ToolDefLoader.class);
        tools.forEach(name -> when(cmd.toolDefLoader.find(name)).thenReturn(BuildCommandTest.simpleToolSetup(name)));
        when(cmd.toolDefLoader.allToolSetups()).thenReturn(allToolSetups);
        cmd.yes = true;
        cmd.skipGitRefresh = true;
        var names = new ArrayList<String>();
        for (var arg : args) {
            switch (arg) {
                case "--with-parents" -> cmd.withParents = true;
                case "--with-descendants" -> cmd.withDescendants = true;
                case "--out-of-sync" -> cmd.outOfSync = true;
                case "--missing" -> cmd.missing = true;
                default -> names.add(arg);
            }
        }
        cmd.names = names.isEmpty() ? null : names;
        var recorder = new RecordingOutput();
        doAnswer(i -> {
            if (warningsBeforeBuild == null) warningsBeforeBuild = recorder.warnings();
            var name = i.<ImageDef>getArgument(0).getName();
            built.add(name);
            if (failing.contains(name)) throw new BuildCommand.BuildFailedException();
            if (buildsLeaveImages) {
                daemon.container(name, Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_VERSION, NOW,
                        Metadata.CREATED, "2026-10-07T10:0" + built.size() + ":00"));
            }
            return null;
        }).when(cmd).buildSingleImage(any(), any());

        try (var bound = BuildOutput.bind(recorder)) {
            var defs = tree();
            defs.putAll(extraDefs);
            return cmd.dispatch(defs).getResultValue();
        } finally {
            // What a terminal shows on stderr: diagnostics, each with its details, and notes
            stderr = String.join("\n", recorder.events().stream()
                    .filter(e -> RecordingOutput.onStderr(e.kind())).map(RecordingOutput.Event::text).toList());
            warnings = recorder.warnings();
        }
    }

    @Test
    void withParentsOfTwoLeavesBuildsTheirSharedParentsOnceAndFirst() {
        assertEquals(0, build("tpl-isx", "tpl-quarkus", "--with-parents"));

        assertEquals(List.of("tpl-base", "tpl-layer", "tpl-isx", "tpl-quarkus"), built);
    }

    @Test
    void withParentsOfUnrelatedTemplatesBuildsBothChains() {
        assertEquals(0, build("tpl-other-child", "tpl-isx", "--with-parents"));

        assertEquals(List.of("tpl-other", "tpl-other-child", "tpl-base", "tpl-layer", "tpl-isx"), built);
    }

    @Test
    void withDescendantsBuildsEachOnceParentsFirstWhateverTheOrderNamed() {
        assertEquals(0, build("tpl-isx", "tpl-other", "tpl-layer", "--with-descendants"));

        assertEquals(List.of("tpl-other", "tpl-other-child", "tpl-layer", "tpl-isx", "tpl-quarkus"), built);
    }

    @Test
    void anUnknownNameAnywhereBuildsNothing() {
        assertEquals(1, build("tpl-isx", "tpl-nope", "tpl-gone", "--with-parents"));
        assertEquals(List.of(), built);
        assertTrue(stderr.contains("Unknown images: tpl-nope, tpl-gone"), stderr);

        assertEquals(1, build("tpl-isx", "tpl-nope", "--with-descendants"));
        assertEquals(1, build("tpl-isx", "tpl-nope"));
        assertEquals(List.of(), built);
        assertTrue(stderr.contains("Unknown image: tpl-nope"), stderr);
    }

    /** Plain {@code isx build a b} rebuilds a shared missing parent for the first only. */
    @Test
    void severalPlainTargetsBuildTheirMissingParentOnce() {
        buildsLeaveImages = true;
        daemon.container("tpl-base", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_VERSION, NOW,
                Metadata.CREATED, "2026-10-01T09:00:00"));
        assertEquals(0, build("tpl-isx", "tpl-quarkus"));

        assertEquals(List.of("tpl-layer", "tpl-isx", "tpl-quarkus"), built);
    }

    /** A named parent is built before its named child, whatever the order on the command line. */
    @Test
    void severalPlainTargetsBuildANamedParentFirst() {
        buildsLeaveImages = true;
        daemon.container("tpl-base", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_VERSION, NOW,
                Metadata.CREATED, "2026-10-01T09:00:00"));
        assertEquals(0, build("tpl-isx", "tpl-layer"));

        assertEquals(List.of("tpl-layer", "tpl-isx"), built);
    }

    /**
     * A failed target does not stop the others: an unrelated one is still built, one inheriting
     * from it is skipped (its build would use the old image), and the build fails at the end.
     */
    @Test
    void severalPlainTargetsCarryOnPastAFailure() {
        buildsLeaveImages = true;
        daemon.container("tpl-base", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_VERSION, NOW,
                Metadata.CREATED, "2026-10-01T09:00:00"));
        daemon.container("tpl-other", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_VERSION, NOW,
                Metadata.CREATED, "2026-10-01T09:00:00"));
        failing.add("tpl-layer");

        assertThrows(BuildCommand.BuildFailedException.class,
                () -> build("tpl-layer", "tpl-isx", "tpl-other-child"));

        assertEquals(List.of("tpl-layer", "tpl-other-child"), built);
        assertTrue(stderr.contains("Some templates failed to build: tpl-layer, tpl-isx"), stderr);
    }

    /** The run's last word names the parent a target's chain failed on, not only the target. */
    @Test
    void severalPlainTargetsNameAParentTheirChainFailedOn() {
        buildsLeaveImages = true;
        daemon.container("tpl-base", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_VERSION, NOW,
                Metadata.CREATED, "2026-10-01T09:00:00"));
        daemon.container("tpl-other", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_VERSION, NOW,
                Metadata.CREATED, "2026-10-01T09:00:00"));
        failing.add("tpl-layer");

        assertThrows(BuildCommand.BuildFailedException.class,
                () -> build("tpl-isx", "tpl-quarkus", "tpl-other-child"));

        // tpl-quarkus is not retried on the layer that just failed under tpl-isx.
        assertEquals(List.of("tpl-layer", "tpl-other-child"), built);
        // Said once, at the end, naming the parent the chain failed on.
        assertEquals(1, stderr.split("Some templates failed to build", -1).length - 1, stderr);
        assertTrue(stderr.contains("Some templates failed to build: tpl-layer, tpl-isx, tpl-quarkus"), stderr);
    }

    /**
     * A target whose parent is not defined is reported and does not stop the others: the next is
     * still built, and the run ends on the list of what failed.
     */
    @Test
    void severalPlainTargetsCarryOnPastAMissingParent() {
        extra(List.of("tpl-a", "tpl-gone"), List.of("tpl-b", ""));

        assertThrows(BuildCommand.BuildFailedException.class, () -> build("tpl-a", "tpl-b"));

        assertEquals(List.of("tpl-b"), built);
        assertTrue(stderr.contains("parent image 'tpl-gone' not found in definitions."), stderr);
        assertTrue(stderr.endsWith("Some templates failed to build: tpl-a"), stderr);
    }

    /** Nothing missing is nothing to do: no proxy check, which could fail a run with no work. */
    @Test
    void missingWithNothingMissingDoesNothing() {
        builtAt(Map.of(
                "tpl-base", "2026-10-01T09:00:00", "tpl-layer", "2026-10-01T09:01:00",
                "tpl-isx", "2026-10-01T09:02:00", "tpl-quarkus", "2026-10-01T09:02:00",
                "tpl-other", "2026-10-01T09:00:00", "tpl-other-child", "2026-10-01T09:01:00"));
        daemon.clearRequests();

        assertEquals(0, build("--missing"));

        assertEquals(List.of(), built);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.contains("/networks")), daemon.requests().toString());
    }

    /**
     * Which templates are out of sync is judged from one instance listing, as {@code isx
     * templates} and the TUI judge it, not from a read per template and its parent (#1142).
     */
    @Test
    void outOfSyncReadsTheInstancesOnce() {
        builtAt(Map.of(
                "tpl-base", "2026-10-01T09:00:00", "tpl-layer", "2026-10-01T09:01:00",
                "tpl-isx", "2026-10-01T09:02:00", "tpl-quarkus", "2026-10-01T09:02:00",
                "tpl-other", "2026-10-01T09:00:00", "tpl-other-child", "2026-10-01T09:01:00"));
        daemon.clearRequests();

        assertEquals(0, build("--out-of-sync"));

        assertEquals(List.of(), built);
        assertEquals(List.of("GET /1.0/instances?recursion=1"),
                daemon.requests().stream().filter(r -> r.startsWith("GET /1.0/instances")).toList());
    }

    /** A template that is defined but not built is built, its descendants with it. */
    @Test
    void outOfSyncBuildsAMissingTemplate() {
        builtAt(Map.of(
                "tpl-base", "2026-10-01T09:00:00", "tpl-layer", "2026-10-01T09:01:00",
                "tpl-quarkus", "2026-10-01T09:02:00",
                "tpl-other", "2026-10-01T09:00:00", "tpl-other-child", "2026-10-01T09:01:00"));

        assertEquals(0, build("--out-of-sync"));

        assertEquals(List.of("tpl-isx"), built);
    }

    /**
     * Tool parameters that no longer validate stop the run before anything is built, as the
     * per-template check did, not halfway through the batch (#1142).
     */
    @Test
    void outOfSyncRefusesATemplateWhoseToolParametersNoLongerValidateBeforeBuilding() {
        tools.add("maven-3");
        extra(List.of("tpl-broken", "tpl-layer"));
        extraDefs.get("tpl-broken").setTools(List.of(new ToolDef.ToolRef("maven-3", Map.of("version", "9"))));
        builtAt(Map.of(
                "tpl-base", "2026-10-01T09:00:00", "tpl-layer", "2026-10-07T09:01:00",
                "tpl-isx", "2026-10-01T09:02:00", "tpl-quarkus", "2026-10-01T09:02:00",
                "tpl-other", "2026-10-01T09:00:00", "tpl-other-child", "2026-10-01T09:01:00"));

        var e = assertThrows(IllegalArgumentException.class, () -> build("--out-of-sync"));

        assertTrue(e.getMessage().contains("tpl-broken"), "names the template: " + e.getMessage());
        assertEquals(List.of(), built);
    }

    /** An Incus that cannot list its instances fails the run; it is never "nothing is built". */
    @Test
    void outOfSyncFailsWhenTheInstancesCannotBeListed() {
        builtAt(Map.of("tpl-base", "2026-10-01T09:00:00"));
        daemon.listingAnswers(JqObject.EMPTY);

        assertThrows(IncusException.class, () -> build("--out-of-sync"));

        assertEquals(List.of(), built);
    }

    /**
     * {@code tpl-isx-vm} over its container parent was built from the definitions, not copied:
     * the parent's rebuild leaves it current, so {@code --out-of-sync} does not rebuild it.
     */
    @Test
    void outOfSyncLeavesAVmOverARebuiltContainerParentAlone() {
        var vm = new ImageDef();
        vm.setName("tpl-isx-vm");
        vm.setParent("tpl-isx");
        vm.setType("vm");
        extraDefs.put("tpl-isx-vm", vm);
        builtAt(Map.of(
                "tpl-base", "2026-10-01T09:00:00",
                "tpl-layer", "2026-10-01T09:01:00",
                "tpl-isx", "2026-10-07T09:02:00",
                "tpl-quarkus", "2026-10-01T09:02:00",
                "tpl-other", "2026-10-01T09:00:00",
                "tpl-other-child", "2026-10-01T09:01:00"));
        daemon.instance("tpl-isx-vm", "virtual-machine", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_VERSION, NOW, Metadata.CREATED, "2026-10-01T09:03:00"));

        assertEquals(0, build("--out-of-sync"));

        assertEquals(List.of(), built);
    }

    /**
     * The two-step workflow of #1130 self-heals: after {@code --with-parents tpl-quarkus},
     * {@code tpl-isx} was copied from the layer's earlier build, and {@code --out-of-sync}
     * rebuilds it, and only it.
     */
    @Test
    void outOfSyncRebuildsAChildWhoseParentWasRebuiltAfterIt() {
        builtAt(Map.of(
                "tpl-base", "2026-10-01T09:00:00",
                "tpl-layer", "2026-10-07T09:01:00",
                "tpl-quarkus", "2026-10-07T09:02:00",
                "tpl-isx", "2026-10-01T09:02:00",
                "tpl-other", "2026-10-01T09:00:00",
                "tpl-other-child", "2026-10-01T09:01:00"));

        assertEquals(0, build("--out-of-sync"));

        assertEquals(List.of("tpl-isx"), built);
    }

    /** A rebuilt mid-level parent re-derives everything under it in the same batch. */
    @Test
    void outOfSyncRebuildsTheDescendantsOfWhatItRebuilds() {
        builtAt(Map.of(
                "tpl-base", "2026-10-07T09:00:00",
                "tpl-layer", "2026-10-01T09:01:00",
                "tpl-quarkus", "2026-10-01T09:02:00",
                "tpl-isx", "2026-10-01T09:02:00",
                "tpl-other", "2026-10-01T09:00:00",
                "tpl-other-child", "2026-10-01T09:01:00"));

        assertEquals(0, build("--out-of-sync"));

        assertEquals(List.of("tpl-layer", "tpl-isx", "tpl-quarkus"), built);
    }

    /**
     * A parent pulled into the batch as the ancestor of an outdated child re-derives its other
     * children too, though the walk reaches that child before the parent (#1150).
     */
    @Test
    void outOfSyncRebuildsTheOtherChildrenOfAnAncestorItPullsIn() {
        extra(List.of("tpl-a", "tpl-p"), List.of("tpl-p", ""), List.of("tpl-b", "tpl-p"));
        builtAt(Map.of(
                "tpl-base", "2026-10-01T09:00:00", "tpl-layer", "2026-10-01T09:01:00",
                "tpl-isx", "2026-10-01T09:02:00", "tpl-quarkus", "2026-10-01T09:02:00",
                "tpl-other", "2026-10-01T09:00:00", "tpl-other-child", "2026-10-01T09:01:00",
                "tpl-b", "2026-10-01T09:01:00"));
        daemon.container("tpl-p", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_VERSION, "0.0.1", Metadata.CREATED, "2026-10-01T09:00:00"));

        assertEquals(0, build("--out-of-sync"));

        assertEquals(List.of("tpl-p", "tpl-a", "tpl-b"), built);
    }

    /**
     * An outdated grandparent the walk reaches after its outdated grandchild is still built first,
     * and the current parent between them with it (#1150).
     */
    @Test
    void outOfSyncBuildsAnOutdatedGrandparentBeforeTheGrandchildItReachedFirst() {
        extra(List.of("tpl-a", "tpl-q"), List.of("tpl-g", ""), List.of("tpl-q", "tpl-g"));
        builtAt(Map.of(
                "tpl-base", "2026-10-01T09:00:00", "tpl-layer", "2026-10-01T09:01:00",
                "tpl-isx", "2026-10-01T09:02:00", "tpl-quarkus", "2026-10-01T09:02:00",
                "tpl-other", "2026-10-01T09:00:00", "tpl-other-child", "2026-10-01T09:01:00",
                "tpl-q", "2026-10-01T09:01:00"));
        daemon.container("tpl-g", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_VERSION, "0.0.1", Metadata.CREATED, "2026-10-01T09:00:00"));

        assertEquals(0, build("--out-of-sync"));

        assertEquals(List.of("tpl-g", "tpl-q", "tpl-a"), built);
    }

    /** {@code name} built, current, from {@code def} as recorded in {@code file}. */
    private void builtFromStamp(String name, ImageDef def, String file) {
        var snapshot = new BuildSource(Map.of(name, def), Map.of(), Map.of(), Map.of(name, file));
        daemon.container(name, Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_VERSION, NOW,
                Metadata.BUILD_SOURCE, snapshot.toJson()));
    }

    /**
     * A template whose {@code name:} was changed in its file is never rebuilt from the definition
     * stamped on it (#2057): the build refuses, names the file and the template it now defines.
     */
    @Test
    void aRenamedTemplateIsRefusedAndTheNewNameIsGiven() {
        var old = new ImageDef();
        old.setName("tpl-old");
        old.setParent("tpl-base");
        builtFromStamp("tpl-old", old, "/defs/images/presentation.yaml");
        extra(List.of("tpl-new", "tpl-base"));
        extraDefs.get("tpl-new").setSource("/defs/images/presentation.yaml");

        assertEquals(1, build("tpl-old"));

        assertEquals(List.of(), built);
        assertTrue(stderr.contains("Cannot build tpl-old: no definition of that name is loaded"), stderr);
        assertTrue(stderr.contains("/defs/images/presentation.yaml now defines tpl-new"), stderr);
        assertTrue(stderr.contains("Build tpl-new instead"), stderr);
    }

    /** With its parents, a renamed template gets the same refusal, never a bare unknown name. */
    @Test
    void aRenamedTemplateIsRefusedWithItsParentsToo() {
        var old = new ImageDef();
        old.setName("tpl-old");
        old.setParent("tpl-base");
        builtFromStamp("tpl-old", old, "/defs/images/presentation.yaml");
        extra(List.of("tpl-new", "tpl-base"));
        extraDefs.get("tpl-new").setSource("/defs/images/presentation.yaml");

        assertEquals(1, build("tpl-old", "--with-parents"));

        assertEquals(List.of(), built);
        assertTrue(stderr.contains("Build tpl-new instead"), stderr);
        assertFalse(stderr.contains("Unknown image"), stderr);
    }

    /**
     * A template whose file is gone is refused, never rebuilt from its stamp, and so are the
     * other names given with it. The stamp's chain cannot reach the build either, which is how a
     * deleted file once closed a parent cycle (#1443).
     */
    @Test
    void aTemplateWhoseFileIsGoneIsRefusedWithTheNamesGivenWithIt() {
        var a = new ImageDef();
        a.setName("tpl-a");
        a.setParent("tpl-b");
        builtFromStamp("tpl-a", a, "/nonexistent/images/a.yaml");
        extra(List.of("tpl-b", "tpl-a"));

        assertEquals(1, build("tpl-isx", "tpl-a", "tpl-nope"));

        assertEquals(List.of(), built);
        assertTrue(stderr.contains("Cannot build tpl-a: no definition of that name is loaded"), stderr);
        assertTrue(stderr.contains("/nonexistent/images/a.yaml no longer exists"), stderr);
        assertTrue(stderr.contains("Unknown image: tpl-nope"), stderr);
        assertFalse(stderr.contains("parent cycle"), stderr);
    }

    /**
     * A template whose tools need a credential that is not configured still builds, but the build
     * says up front that {@code isx branch} will refuse it, rather than leaving that to the branch
     * after the build's time is spent (#1438). Every template the build may build is named, the
     * existing parent it checked included.
     */
    @Test
    void aMissingCredentialIsReportedBeforeTheBuildAndTheBuildGoesAhead() throws Exception {
        allToolSetups = builtInTools();
        var dev = ImageDef.parseYaml("name: tpl-dev\nparent: tpl-base\ntools: [gh]\n");
        var agent = ImageDef.parseYaml("name: tpl-agent\nparent: tpl-dev\n");
        extraDefs.put(dev.getName(), dev);
        extraDefs.put(agent.getName(), agent);
        builtAt(Map.of("tpl-base", "2026-10-01T09:00:00", "tpl-dev", "2026-10-01T09:01:00"));

        assertEquals(0, build("tpl-agent"));

        assertEquals(List.of("tpl-agent"), built);
        var missing = "Missing credential: GitHub personal access token. "
                + "'isx branch' will refuse tpl-dev and tpl-agent until it is configured with 'isx account add github'.";
        // Once, and before the build
        assertEquals(List.of(missing), warnings.stream().filter(w -> w.contains("GitHub personal access token")).toList());
        assertTrue(warningsBeforeBuild.contains(missing), warningsBeforeBuild::toString);
    }

    @Test
    void aConfiguredCredentialIsNotReported() throws Exception {
        IsolatedHome.seed("github:\n  token: \"ghp_x\"\n");
        allToolSetups = builtInTools();
        extraDefs.put("tpl-dev", ImageDef.parseYaml("name: tpl-dev\nparent: tpl-base\ntools: [gh]\n"));
        builtAt(Map.of("tpl-base", "2026-10-01T09:00:00"));

        assertEquals(0, build("tpl-dev"));

        assertEquals(List.of("tpl-dev"), built);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("Missing credential")), warnings::toString);
    }

    private static Map<String, ToolSetup> builtInTools() {
        var map = new LinkedHashMap<String, ToolSetup>();
        RuntimeConstants.CDI_TOOLS.forEach(t -> map.put(t.name(), t));
        return map;
    }

    /** Adds definitions as (name, parent) pairs, in this order; an empty parent is a root. */
    @SafeVarargs
    private void extra(List<String>... pairs) {
        for (var pair : pairs) {
            var def = new ImageDef();
            def.setName(pair.get(0));
            if (!pair.get(1).isEmpty()) def.setParent(pair.get(1));
            extraDefs.put(def.getName(), def);
        }
    }
}
