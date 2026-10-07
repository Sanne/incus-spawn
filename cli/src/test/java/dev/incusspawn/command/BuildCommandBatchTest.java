package dev.incusspawn.command;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDefLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Which templates one {@code isx build} invocation rebuilds, and in what order, driven through
 * {@link BuildCommand#dispatch} against {@link FakeIncusDaemon} with each build recorded instead
 * of run (#1130): several targets for {@code --with-parents} and {@code --with-descendants}.
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

    /** Every template of {@link #tree()} built, current, at {@code created}. */
    private void builtAt(Map<String, String> created) {
        created.forEach((name, at) -> daemon.container(name, Map.of(
                Metadata.TYPE, Metadata.TYPE_BASE,
                Metadata.BUILD_VERSION, NOW,
                Metadata.CREATED, at)));
    }

    /** Each recorded build leaves its image behind, current, as a real build's swap does. */
    private boolean buildsLeaveImages;

    private int build(String... args) {
        var cmd = spy(new BuildCommand());
        cmd.incus = daemon.client();
        cmd.toolDefLoader = mock(ToolDefLoader.class);
        cmd.yes = true;
        cmd.skipGitRefresh = true;
        var names = new ArrayList<String>();
        for (var arg : args) {
            switch (arg) {
                case "--with-parents" -> cmd.withParents = true;
                case "--with-descendants" -> cmd.withDescendants = true;
                case "--out-of-sync" -> cmd.outOfSync = true;
                default -> names.add(arg);
            }
        }
        cmd.names = names.isEmpty() ? null : names;
        doAnswer(i -> {
            var name = i.<ImageDef>getArgument(0).getName();
            built.add(name);
            if (buildsLeaveImages) {
                daemon.container(name, Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_VERSION, NOW,
                        Metadata.CREATED, "2026-10-07T10:0" + built.size() + ":00"));
            }
            return null;
        }).when(cmd).buildSingleImage(any(), any());

        var executor = Executors.newSingleThreadExecutor();
        var originalErr = System.err;
        var err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return cmd.dispatch(tree(), executor).getResultValue();
        } finally {
            System.setErr(originalErr);
            executor.shutdownNow();
            stderr = err.toString(StandardCharsets.UTF_8);
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
}
