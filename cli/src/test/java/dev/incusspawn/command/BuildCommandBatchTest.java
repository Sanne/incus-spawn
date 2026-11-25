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
    /** Everything the last build reported. */
    private List<RecordingOutput.Event> events = List.of();
    /** The architecture the build runs on. */
    private String architecture = "x86_64";

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
        onLoader.accept(cmd);
        cmd.architecture = architecture;
        cmd.skipGitRefresh = true;
        var names = new ArrayList<String>();
        for (var arg : args) {
            switch (arg) {
                case "--with-parents" -> cmd.withParents = true;
                case "--with-descendants" -> cmd.withDescendants = true;
                case "--out-of-sync" -> cmd.outOfSync = true;
                case "--missing" -> cmd.missing = true;
                case "--all" -> cmd.all = true;
                case "--skip-missing-accounts" -> cmd.skipMissingAccounts = true;
                default -> names.add(arg);
            }
        }
        cmd.names = names.isEmpty() ? null : names;
        var recorder = new RecordingOutput();
        doAnswer(i -> {
            // What the real one does once the build goes ahead
            cmd.askPendingAccounts();
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
            events = recorder.events();
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
        assertTrue(stderr.contains("no templates named tpl-nope and tpl-gone are defined."), stderr);

        assertEquals(1, build("tpl-isx", "tpl-nope", "--with-descendants"));
        assertEquals(1, build("tpl-isx", "tpl-nope"));
        assertEquals(List.of(), built);
        assertTrue(stderr.contains("no template named tpl-nope is defined."), stderr);
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
        assertTrue(stderr.contains("tpl-a is built on tpl-gone, and no template of that name is defined."), stderr);
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
        // It used to say nothing at all, so a person could not tell it from a build that hung (#2542)
        assertEquals(List.of("Every template is built already."), said(RecordingOutput.Kind.STEP));
    }

    /** A parent that is not built, below a child that is, is named: {@code --missing} leaves it unbuilt. */
    @Test
    void missingNamesTheParentItLeavesUnbuilt() {
        builtAt(Map.of(
                "tpl-base", "2026-10-01T09:00:00",
                "tpl-isx", "2026-10-01T09:02:00", "tpl-quarkus", "2026-10-01T09:02:00",
                "tpl-other", "2026-10-01T09:00:00", "tpl-other-child", "2026-10-01T09:01:00"));

        assertEquals(0, build("--missing"));

        assertEquals(List.of(), built);
        assertEquals(List.of("Every template at the end of a chain is built already."), said(RecordingOutput.Kind.STEP));
        assertEquals(List.of("--missing builds a parent only for a template that is not built, so it leaves tpl-layer"
                + " unbuilt. 'isx build tpl-layer' builds it."), said(RecordingOutput.Kind.NOTE));
    }

    private List<String> said(RecordingOutput.Kind kind) {
        return events.stream().filter(e -> e.kind() == kind).map(RecordingOutput.Event::msg).toList();
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
        assertTrue(stderr.contains("no template named tpl-nope is defined."), stderr);
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
        var missing = "'isx branch' cannot copy tpl-dev or tpl-agent until a credential they need is set up. "
                + "They need: GitHub personal access token. 'isx account add github' sets it up.";
        // Once, and before the build
        assertEquals(List.of(missing), oneLine(warnings).filter(w -> w.contains("GitHub personal access token")).toList());
        assertTrue(oneLine(warningsBeforeBuild).toList().contains(missing), warningsBeforeBuild::toString);
        // Wrapped to init's frame, which a first run's build prints inside (#2528)
        assertTrue(warnings.stream().flatMap(String::lines).allMatch(l -> l.length() <= Lines.WIDTH - 2), warnings::toString);
    }

    @Test
    void aConfiguredCredentialIsNotReported() throws Exception {
        IsolatedHome.seed("github:\n  token: \"ghp_x\"\n");
        allToolSetups = builtInTools();
        extraDefs.put("tpl-dev", ImageDef.parseYaml("name: tpl-dev\nparent: tpl-base\ntools: [gh]\n"));
        builtAt(Map.of("tpl-base", "2026-10-01T09:00:00"));

        assertEquals(0, build("tpl-dev"));

        assertEquals(List.of("tpl-dev"), built);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("a credential:")), warnings::toString);
    }

    /** A build at a terminal, without {@code --yes}, whose missing accounts are asked for through {@code prompts}. */
    private void atATerminal(ScriptedPrompts prompts) throws Exception {
        MissingAccountsAskTest.acme();
        var tools = new LinkedHashMap<>(builtInTools());
        tools.putAll(new ToolDefLoader().allToolSetups().entrySet().stream()
                .filter(e -> e.getKey().equals("acme"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        allToolSetups = tools;
        extraDefs.put("tpl-dev", ImageDef.parseYaml("name: tpl-dev\nparent: tpl-base\ntools: [gh, acme]\n"));
        builtAt(Map.of("tpl-base", "2026-10-01T09:00:00"));
        onLoader = c -> {
            when(c.toolDefLoader.find("acme")).thenReturn(tools.get("acme"));
            c.yes = false;
            var setup = MissingAccountsAskTest.ScriptedInit.with(prompts);
            doAnswer(i -> {
                asked++;
                return setup;
            }).when(c).accountSetup();
        };
    }

    private int asked;

    /**
     * At a terminal the build asks for each missing account before it starts (#2405): what is
     * given is configured and no longer warned of, and what is skipped is warned of as before.
     */
    @Test
    void aBuildAtATerminalAsksForEachMissingAccountFirst() throws Exception {
        // GitHub skipped; Acme given
        var prompts = new ScriptedPrompts().line("n", "y").secret("acme-secret");
        atATerminal(prompts);

        assertEquals(0, build("tpl-dev"));

        prompts.assertFullyConsumed();
        assertEquals(List.of("tpl-dev"), built);
        assertEquals("acme-secret", IsolatedHome.saved("acmeCloud.accounts.default.apiKey"));
        assertEquals(List.of("'isx branch' cannot copy tpl-dev until a credential it needs is set up. "
                        + "It needs: GitHub personal access token. 'isx account add github' sets it up."),
                oneLine(warningsBeforeBuild).filter(w -> w.contains("cannot copy")).toList());
    }

    /**
     * A batch rebuild asks for accounts only once it is confirmed: a person who turns the rebuild
     * down has typed no secret for it, and one who goes ahead is asked before anything is built.
     */
    @Test
    void aBatchAsksForAccountsOnlyOnceItIsConfirmed() throws Exception {
        var accounts = new ScriptedPrompts().line("n", "y").secret("acme-secret");
        atATerminal(accounts);
        daemon.container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_VERSION, NOW));
        var asks = onLoader;
        var confirmation = new String[]{"n"};
        onLoader = c -> {
            asks.accept(c);
            doAnswer(i -> ScriptedPrompts.lines(confirmation[0])).when(c).prompts();
        };

        build("tpl-dev", "--with-parents");
        assertEquals(List.of(), built);
        assertEquals(List.of(), accounts.questions(), "declined: nothing asked");

        confirmation[0] = "y";
        assertEquals(0, build("tpl-dev", "--with-parents"));
        accounts.assertFullyConsumed();
        assertEquals("acme-secret", IsolatedHome.saved("acmeCloud.accounts.default.apiKey"));
        assertFalse(built.isEmpty());
        assertEquals(List.of("'isx branch' cannot copy tpl-dev until a credential it needs is set up. "
                        + "It needs: GitHub personal access token. 'isx account add github' sets it up."),
                oneLine(warningsBeforeBuild).filter(w -> w.contains("cannot copy")).toList());
    }

    /** A caller that has just asked turns the question off: the build warns as without a terminal. */
    @Test
    void aCallerCanTurnTheQuestionOff() throws Exception {
        atATerminal(new ScriptedPrompts());
        var asks = onLoader;
        onLoader = c -> {
            asks.accept(c);
            c.askForAccounts = false;
        };

        assertEquals(0, build("tpl-dev"));

        assertEquals(0, asked);
        assertEquals(2, warnings.stream().filter(w -> w.contains("'isx branch' cannot copy")).count(), warnings::toString);
    }

    /** {@code --yes} is a build nobody watches: nothing is asked. */
    @Test
    void withYesNothingIsAsked() throws Exception {
        atATerminal(new ScriptedPrompts());
        var asks = onLoader;
        onLoader = c -> {
            asks.accept(c);
            c.yes = true;
        };

        assertEquals(0, build("tpl-dev"));

        assertEquals(0, asked);
        assertEquals(2, warnings.stream().filter(w -> w.contains("'isx branch' cannot copy")).count(), warnings::toString);
    }

    /**
     * tpl-pinned pins a GitHub account this host has not configured, and tpl-pinned-child, below it,
     * selects the one it has: only a host with a {@code readonly} account can build either.
     */
    private static final String SKIPPED = "Skipped template 'tpl-pinned': an account it selects is not configured.";

    private void pinnedToAMissingAccount() throws Exception {
        IsolatedHome.seed("github:\n  token: \"ghp_x\"\n");
        extraDefs.put("tpl-pinned", ImageDef.parseYaml(
                "name: tpl-pinned\nparent: tpl-layer\naccounts:\n  github: readonly\n"));
        extraDefs.put("tpl-pinned-child", ImageDef.parseYaml(
                "name: tpl-pinned-child\nparent: tpl-pinned\naccounts:\n  github: default\n"));
    }

    /** By default a batch stops at a missing account before building anything: likely a mistake (#2345). */
    @Test
    void aBatchWithATemplatePinnedToAMissingAccountBuildsNothing() throws Exception {
        pinnedToAMissingAccount();

        assertThrows(BuildCommand.BuildFailedException.class, () -> build("--all"));
        assertThrows(BuildCommand.BuildFailedException.class, () -> build("--missing"));

        assertEquals(List.of(), built);
        assertTrue(stderr.contains("Cannot build: template 'tpl-pinned': Account 'readonly' is not configured under 'github'"),
                stderr);
    }

    /**
     * With {@code --skip-missing-accounts}, {@code --all} builds every other template, and names the
     * skipped one before building, with what inherits from it, even a child that selects a configured
     * account: it would be copied from an image this run did not build (#2345).
     */
    @Test
    void skipMissingAccountsBuildsEveryOtherTemplateOfAll() throws Exception {
        pinnedToAMissingAccount();

        assertEquals(0, build("--all", "--skip-missing-accounts"));

        assertEquals(List.of("tpl-base", "tpl-layer", "tpl-isx", "tpl-quarkus", "tpl-other", "tpl-other-child"), built);
        assertTrue(warningsBeforeBuild.stream().anyMatch(w -> w.startsWith(SKIPPED)), warningsBeforeBuild::toString);
        assertTrue(stderr.contains("Also skipped, since it inherits from it: tpl-pinned-child."), stderr);
        assertFalse(stderr.contains("Cannot build"), stderr);
    }

    /** {@code --missing} and {@code --out-of-sync} skip the same way, and still build the parent of a skipped one. */
    @Test
    void skipMissingAccountsBuildsEveryOtherMissingTemplate() throws Exception {
        pinnedToAMissingAccount();
        buildsLeaveImages = true;

        assertEquals(0, build("--missing", "--skip-missing-accounts"));
        assertEquals(List.of("tpl-base", "tpl-layer", "tpl-isx", "tpl-quarkus", "tpl-other", "tpl-other-child"), built);

        built.clear();
        warnings = List.of();
        warningsBeforeBuild = null;
        assertEquals(0, build("--out-of-sync", "--skip-missing-accounts"));
        assertEquals(List.of(), built);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith(SKIPPED)), warnings::toString);
    }

    /**
     * {@code --missing} builds a missing parent that only a skipped template inherits from, as
     * {@code --all} does, each template of its chain once.
     */
    @Test
    void skipMissingAccountsStillBuildsAParentOnlyASkippedTemplateInheritsFrom() throws Exception {
        IsolatedHome.seed("github:\n  token: \"ghp_x\"\n");
        extraDefs.put("tpl-solo", ImageDef.parseYaml("name: tpl-solo\n"));
        extraDefs.put("tpl-solo-mid", ImageDef.parseYaml("name: tpl-solo-mid\nparent: tpl-solo\n"));
        extraDefs.put("tpl-solo-pinned", ImageDef.parseYaml(
                "name: tpl-solo-pinned\nparent: tpl-solo-mid\naccounts:\n  github: readonly\n"));
        buildsLeaveImages = true;

        assertEquals(0, build("--missing", "--skip-missing-accounts"));

        assertEquals(List.of("tpl-base", "tpl-layer", "tpl-isx", "tpl-quarkus", "tpl-other", "tpl-other-child",
                "tpl-solo", "tpl-solo-mid"), built);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Skipped template 'tpl-solo-pinned'")), warnings::toString);
    }

    /** A template named on the command line keeps refusing a missing account, so the flag is refused there. */
    @Test
    void skipMissingAccountsIsRefusedForNamedTemplates() throws Exception {
        pinnedToAMissingAccount();

        assertEquals(1, build("tpl-pinned", "--skip-missing-accounts"));
        assertEquals(1, build("tpl-pinned", "--with-parents", "--skip-missing-accounts"));

        assertEquals(List.of(), built);
        assertTrue(stderr.contains("--skip-missing-accounts works only with --all, --missing or --out-of-sync."), stderr);
    }

    private static Map<String, ToolSetup> builtInTools() {
        var map = new LinkedHashMap<String, ToolSetup>();
        RuntimeConstants.CDI_TOOLS.forEach(t -> map.put(t.name(), t));
        return map;
    }

    /**
     * A template that lists a tool isx does not know is refused before anything is built, with
     * every unknown tool of every target named, rather than built without the tool (#2291).
     */
    @Test
    void aTemplateListingAnUnknownToolIsRefusedBeforeAnythingIsBuilt() {
        tools.add("known");
        extra(List.of("tpl-a", ""), List.of("tpl-b", ""));
        extraDefs.get("tpl-a").setTools(List.of(new ToolDef.ToolRef("known"), new ToolDef.ToolRef("gradlex")));
        extraDefs.get("tpl-b").setTools(List.of(new ToolDef.ToolRef("typo")));

        assertThrows(BuildCommand.BuildFailedException.class, () -> build("tpl-a", "tpl-b"));

        assertEquals(List.of(), built);
        assertTrue(stderr.contains("Cannot build: templates use tools isx does not know."), stderr);
        assertTrue(stderr.contains("template 'tpl-a' lists 'gradlex', a tool isx does not know."), stderr);
        assertTrue(stderr.contains("template 'tpl-b' lists 'typo', a tool isx does not know."), stderr);
    }

    /** Unknown tools of one template are named under that template, each once. */
    @Test
    void oneTemplatesUnknownToolsAreNamedUnderItEachOnce() {
        extra(List.of("tpl-a", ""));
        extraDefs.get("tpl-a").setTools(List.of(new ToolDef.ToolRef("typo"), new ToolDef.ToolRef("gradlex"),
                new ToolDef.ToolRef("typo")));

        assertThrows(BuildCommand.BuildFailedException.class, () -> build("tpl-a"));

        assertEquals(List.of(), built);
        assertTrue(stderr.contains("Cannot build: template 'tpl-a' uses tools isx does not know."), stderr);
        var line = "template 'tpl-a' lists 'typo', a tool isx does not know.";
        assertEquals(stderr.indexOf(line), stderr.lastIndexOf(line), stderr);
    }

    /** One a tool reaches through {@code requires} is refused too, naming the tool that requires it. */
    @Test
    void aToolRequiringAnUnknownToolIsRefused() {
        var mine = new ToolDef();
        mine.setName("mine");
        mine.setRequires(List.of(new ToolDef.ToolRef("nonesuch")));
        extra(List.of("tpl-a", ""));
        extraDefs.get("tpl-a").setTools(List.of(new ToolDef.ToolRef("mine")));

        onLoader = c -> when(c.toolDefLoader.find("mine")).thenReturn(new dev.incusspawn.tool.YamlToolSetup(mine));
        assertThrows(BuildCommand.BuildFailedException.class, () -> build("tpl-a"));

        assertEquals(List.of(), built);
        assertTrue(stderr.contains("Cannot build: template 'tpl-a' uses 'mine', whose requires names 'nonesuch',"
                + " a tool isx does not know."), stderr);
    }

    /** A tool that ships only for x86_64, as CoreStory's CLI does, and one that requires it. */
    private void x86OnlyTool(boolean declared) {
        var corestory = new ToolDef();
        corestory.setName("corestory");
        var download = new ToolDef.DownloadEntry();
        download.setUrl("https://example.com/corestory-linux-x86_64");
        download.setDestinationFile("/usr/local/bin/corestory");
        download.setArch("x86_64");
        corestory.setDownloads(List.of(download));
        if (declared) corestory.setArchitectures(List.of("x86_64"));
        var mine = new ToolDef();
        mine.setName("mine");
        mine.setRequires(List.of(new ToolDef.ToolRef("corestory")));
        onLoader = c -> {
            when(c.toolDefLoader.find("corestory")).thenReturn(new dev.incusspawn.tool.YamlToolSetup(corestory));
            when(c.toolDefLoader.find("mine")).thenReturn(new dev.incusspawn.tool.YamlToolSetup(mine));
        };
    }

    /**
     * One definition built for both architectures: on x86_64 the template builds; on aarch64,
     * where the tool has no distribution, the build is refused before anything is created, with
     * an error naming the template, the tool and why (#2357), rather than built without it.
     */
    @Test
    void aToolWithNoDistributionForTheHostsArchitectureRefusesTheBuild() {
        x86OnlyTool(true);
        extra(List.of("tpl-a", ""));
        extraDefs.get("tpl-a").setTools(List.of(new ToolDef.ToolRef("corestory")));

        build("tpl-a");
        assertEquals(List.of("tpl-a"), built);
        assertFalse(stderr.contains("Cannot build"), stderr);

        built.clear();
        architecture = "aarch64";
        assertThrows(BuildCommand.BuildFailedException.class, () -> build("tpl-a"));

        assertEquals(List.of(), built);
        var errors = events.stream().filter(e -> e.kind() == RecordingOutput.Kind.ERROR).toList();
        assertEquals(1, errors.size(), events.toString());
        assertEquals("Cannot build: a tool has no distribution for aarch64.", errors.getFirst().msg());
        assertEquals(List.of("template 'tpl-a' lists 'corestory', which supports only x86_64.",
                "Remove the tool from 'tools:', or build the template on a host the tool supports."),
                errors.getFirst().details());
        assertTrue(events.stream().noneMatch(e -> e.kind() == RecordingOutput.Kind.WARN), events.toString());
    }

    /**
     * Without a declaration, a tool whose every download is for another architecture has no
     * distribution for this one; a tool that requires it is refused too, naming the entry.
     */
    @Test
    void aToolRequiringOneWhoseDownloadsAreAllForAnotherArchitectureIsRefused() {
        x86OnlyTool(false);
        architecture = "aarch64";
        extra(List.of("tpl-a", ""));
        extraDefs.get("tpl-a").setTools(List.of(new ToolDef.ToolRef("mine")));

        assertThrows(BuildCommand.BuildFailedException.class, () -> build("tpl-a"));

        assertEquals(List.of(), built);
        assertTrue(stderr.contains("template 'tpl-a' uses 'mine', which requires 'corestory',"
                + " which has downloads only for x86_64."), stderr);
    }

    /**
     * An ancestor's unknown tool does not refuse a child up front: the run builds the ancestor
     * only when it is missing or outdated, and its own build refuses it then.
     */
    @Test
    void aBuiltAncestorsUnknownToolDoesNotRefuseItsChildUpFront() {
        builtAt(Map.of("tpl-base", "2026-10-01T09:00:00", "tpl-layer", "2026-10-01T09:01:00"));
        // Not a ToolRef of tree(): replaced per test, as extra() does
        var base = tree().get("tpl-base");
        base.setTools(List.of(new ToolDef.ToolRef("typo")));
        extraDefs.put("tpl-base", base);

        build("tpl-isx");

        assertFalse(stderr.contains("Cannot build"), stderr);
        assertTrue(built.contains("tpl-isx"), built.toString());
    }

    /**
     * The build of one template refuses before it creates anything when the tools it applies hold
     * one isx does not know: a VM over a container parent applies the parent's too.
     */
    @Test
    void aTemplatesOwnBuildRefusesAnUnknownToolOfALayerItApplies() {
        var parent = new ImageDef();
        parent.setName("tpl-c");
        parent.setTools(List.of(new ToolDef.ToolRef("typo")));
        var vm = new ImageDef();
        vm.setName("tpl-vm");
        vm.setParent("tpl-c");
        vm.setType("vm");
        var defs = Map.of("tpl-c", parent, "tpl-vm", vm);
        var cmd = new BuildCommand();
        cmd.incus = mock(dev.incusspawn.incus.IncusClient.class);
        cmd.toolDefLoader = mock(ToolDefLoader.class);

        var out = RecordingOutput.during(() -> assertThrows(BuildCommand.BuildFailedException.class,
                () -> cmd.buildSingleImage(vm, defs)));

        assertTrue(out.events(RecordingOutput.Kind.ERROR).getFirst().msg()
                .contains("template 'tpl-c' lists 'typo', a tool isx does not know."), out.events().toString());
        verifyNoInteractions(cmd.incus);
    }

    /** Further stubbing of a build's tool loader, for a test that needs more than {@link #tools}. */
    private java.util.function.Consumer<BuildCommand> onLoader = cmd -> { };

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

    /** Each warning with its wrapped lines joined again by a space. */
    private static java.util.stream.Stream<String> oneLine(List<String> warnings) {
        return warnings.stream().map(w -> w.replace("\n", " "));
    }
}
