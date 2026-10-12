package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.Platform;
import dev.incusspawn.baseimage.BaseImageReleases;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.InstanceSubvolumes;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BuildCommandTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult FAIL = new IncusClient.ExecResult(1, "", "");

    @Test
    void unparsableDefinitionsBlockTheBuild() {
        assertNull(BuildCommand.unparsableDefinitionsError(List.of()));

        var msg = BuildCommand.unparsableDefinitionsError(
                List.of(Path.of("/defs/images/isx.yaml"), Path.of("/defs/tools/foo.yaml")));
        assertTrue(msg.startsWith("Cannot build: 2 definition files could not be loaded"), msg);
        assertTrue(msg.contains("/defs/images/isx.yaml"), msg);
        assertTrue(msg.contains("/defs/tools/foo.yaml"), msg);
    }

    // --- reportBuildFailure ---

    /**
     * The in-container report is unreachable whenever the build container is stopped, crashed, or
     * its exec channel is wedged. The host copy has to survive that, or the failure the user most
     * needs to read about is the one that leaves no trace.
     */
    @Test
    void reportBuildFailureWritesHostLogWhenContainerWriteFails(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class)))
                    .thenThrow(new IncusException("exec channel wedged"));
            var cmd = new BuildCommand();
            cmd.incus = incus;

            cmd.reportBuildFailure("tpl-minimal-rebuilding", "tpl-minimal",
                    "Build failed for tpl-minimal: boom");

            var log = Environment.buildFailureLogFile("tpl-minimal");
            assertTrue(Files.exists(log), "host log should exist at " + log);
            assertTrue(assertDoesNotThrow(() -> Files.readString(log))
                    .contains("Build failed for tpl-minimal: boom"));
        });
    }

    /**
     * The final swap is where an orphaned subvolume ("file exists") surfaces, after the whole build.
     * It must fail like any other build step: a real report, the container kept for inspection, and
     * a BuildFailedException so --all skips dependents instead of aborting on a raw IncusException.
     */
    @Test
    void failedSwapIsReportedAndPromotedLikeAnyBuildFailure(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var incus = mock(IncusClient.class);
            doThrow(new IncusException("Failed to rename tpl-minimal-rebuilding to tpl-minimal: file exists"))
                    .when(incus).rename("tpl-minimal-rebuilding", "tpl-minimal");
            var cmd = spy(new BuildCommand());
            cmd.incus = incus;
            cmd.yes = true;
            doNothing().when(cmd).buildInto(any(), any(), anyString());
            var imageDef = new ImageDef();
            imageDef.setName("tpl-minimal");

            assertThrows(BuildCommand.BuildFailedException.class,
                    () -> cmd.buildSingleImage(imageDef, Map.of("tpl-minimal", imageDef)));

            var log = Environment.buildFailureLogFile("tpl-minimal");
            assertTrue(assertDoesNotThrow(() -> Files.readString(log)).contains("file exists"),
                    "the report should carry the swap's real error");
            verify(incus).rename("tpl-minimal-rebuilding", "tpl-minimal-failed-build");
        });
    }

    @Test
    void stepOutputOnExceptionReachesHostLog(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var incus = mock(IncusClient.class);
            when(incus.shellExec(anyString(), any(String[].class)))
                    .thenThrow(new IncusException("exec channel wedged"));
            var cmd = new BuildCommand();
            cmd.incus = incus;

            var cause = (IncusException) new IncusException("Failed to prepare 1 repo(s):\n  my-repo: prime failed")
                    .withStepOutput("─── prepare output: my-repo ───\nFATAL: npm ERR! could not resolve\n─── end prepare output: my-repo ───\n\n");

            cmd.reportBuildFailure("tpl-java-rebuilding", "tpl-java",
                    "Build failed for tpl-java: " + cause.getMessage(), cause);

            var log = Environment.buildFailureLogFile("tpl-java");
            assertTrue(Files.exists(log), "host log should exist at " + log);
            var content = assertDoesNotThrow(() -> Files.readString(log));
            assertTrue(content.contains("npm ERR! could not resolve"),
                    "host log should contain the step's stdout, not just the error summary");
        });
    }

    @Test
    void stepOutputIncludedInEarlyHostReport(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var incus = mock(IncusClient.class);
            // Simulate diagnostics hanging by throwing on every exec
            when(incus.shellExec(anyString(), any(String[].class)))
                    .thenThrow(new IncusException("exec channel wedged"));
            var cmd = new BuildCommand();
            cmd.incus = incus;

            var cause = (IncusException) new IncusException("prime failed")
                    .withStepOutput("─── prepare output: my-repo ───\nconnection refused\n─── end prepare output: my-repo ───\n\n");

            cmd.reportBuildFailure("tpl-dev-rebuilding", "tpl-dev",
                    "Build failed for tpl-dev: prime failed", cause);

            // The early report is overwritten by the full one when diagnostics succeed,
            // but when diagnostics hang (exec wedged), only the early report survives.
            // Since our mock throws on all execs, diagnostics "succeed" with the error text,
            // so we check the final report instead, which must also contain the step output.
            var log = Environment.buildFailureLogFile("tpl-dev");
            var content = assertDoesNotThrow(() -> Files.readString(log));
            assertTrue(content.contains("connection refused"),
                    "report should contain step output");
        });
    }

    /**
     * A deprecated template still builds, and so does what derives from it, but each build says
     * so, naming the template built (the deprecated parent's own build, then the user's), and
     * once per template however many times it is built in the run (#1195).
     */
    @Test
    void everyTemplateBuiltOnADeprecatedOneIsNamedOnce(@TempDir Path tmp) throws Exception {
        InitCommandTest.withHome(tmp, () -> {
            var cmd = spy(new BuildCommand());
            cmd.incus = mock(IncusClient.class);
            cmd.yes = true;
            doNothing().when(cmd).buildInto(any(), any(), anyString());
            var root = new ImageDef();
            root.setName("tpl-root");
            var old = new ImageDef();
            old.setName("tpl-old");
            old.setParent("tpl-root");
            old.setDeprecated("use tpl-new instead");
            var mine = new ImageDef();
            mine.setName("tpl-mine");
            mine.setParent("tpl-old");
            var defs = Map.of("tpl-root", root, "tpl-old", old, "tpl-mine", mine);

            var out = bothStreams(() -> {
                cmd.buildSingleImage(root, defs);
                cmd.buildSingleImage(old, defs);
                cmd.buildSingleImage(mine, defs);
                cmd.buildSingleImage(mine, defs);
            });

            assertEquals(2, out.split("deprecated", -1).length - 1, out);
            assertTrue(out.contains("tpl-old is deprecated: use tpl-new instead"), out);
            assertTrue(out.contains("tpl-mine derives from tpl-old, which is deprecated: use tpl-new instead"), out);
            verify(cmd.incus, times(2)).rename("tpl-mine-rebuilding", "tpl-mine");
        });
    }

    private static InstanceSubvolumes.Scan scan(Set<String> orphans, Set<String> dangling) {
        var kind = InstanceSubvolumes.Kind.CONTAINER;
        return new InstanceSubvolumes.Scan("cow",
                orphans.stream().map(n -> new InstanceSubvolumes.Ref(kind, n)).collect(Collectors.toSet()),
                dangling.stream().map(n -> new InstanceSubvolumes.Ref(kind, n)).collect(Collectors.toSet()));
    }

    /** A build whose swap cannot succeed fails before building, not after (#717). */
    @Test
    void strandedStorageUnderEitherNameStopsTheBuildBeforeItStarts(@TempDir Path tmp) {
        var cases = List.of(
                scan(Set.of("tpl-minimal"), Set.of()),
                scan(Set.of("tpl-minimal-rebuilding"), Set.of()),
                scan(Set.of(), Set.of("tpl-minimal")),
                scan(Set.of(), Set.of("tpl-minimal-rebuilding")));
        for (var stranded : cases) {
            InitCommandTest.withHome(tmp, () -> {
                var incus = mock(IncusClient.class);
                when(incus.scanSubvolumes()).thenReturn(Optional.of(stranded));
                var cmd = spy(new BuildCommand());
                cmd.incus = incus;
                cmd.yes = true;
                var imageDef = new ImageDef();
                imageDef.setName("tpl-minimal");

                assertThrows(BuildCommand.BuildFailedException.class,
                        () -> cmd.buildSingleImage(imageDef, Map.of("tpl-minimal", imageDef)), stranded.toString());

                verify(cmd, never()).buildInto(any(), any(), anyString());
                verify(incus, never()).deleteIfExists(anyString());
            });
        }
    }

    @Test
    void anUnrelatedOrphanDoesNotStopTheBuild(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var incus = mock(IncusClient.class);
            when(incus.scanSubvolumes()).thenReturn(Optional.of(scan(Set.of("dev-old"), Set.of())));
            var cmd = spy(new BuildCommand());
            cmd.incus = incus;
            cmd.yes = true;
            doNothing().when(cmd).buildInto(any(), any(), anyString());
            var imageDef = new ImageDef();
            imageDef.setName("tpl-minimal");

            cmd.buildSingleImage(imageDef, Map.of("tpl-minimal", imageDef));

            verify(incus).rename("tpl-minimal-rebuilding", "tpl-minimal");
        });
    }

    /**
     * A multi-template rebuild asks once, up front, and says there everything it would otherwise
     * have said after the answer: which images exist and will be replaced, and which tools a
     * rebuild drops (#911).
     */
    @Test
    void withParentsAsksOnceAndWarnsBeforeTheQuestion(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var parent = new ImageDef();
            parent.setName("tpl-minimal");
            var child = new ImageDef();
            child.setName("tpl-dev");
            child.setParent("tpl-minimal");
            var defs = Map.of("tpl-minimal", parent, "tpl-dev", child);
            var oldChild = new ImageDef();
            oldChild.setName("tpl-dev");
            oldChild.setTools(List.of(new ToolDef.ToolRef("claude")));
            var oldSource = new BuildSource(Map.of("tpl-dev", oldChild), Map.of(), Map.of(), Map.of());

            var incus = mock(IncusClient.class);
            when(incus.exists(anyString())).thenReturn(true);
            when(incus.configGet("tpl-dev", Metadata.BUILD_SOURCE)).thenReturn(oldSource.toJson());
            var prompts = ScriptedPrompts.lines("y");
            var cmd = spy(new BuildCommand());
            cmd.incus = incus;
            doReturn(prompts).when(cmd).prompts();
            doNothing().when(cmd).buildInto(any(), any(), anyString());

            // Both streams into one, as a terminal shows them: the warnings are on stderr.
            var text = bothStreams(() -> cmd.buildWithParents(List.of(child), defs));

            prompts.assertFullyConsumed();
            verify(incus).rename("tpl-minimal-rebuilding", "tpl-minimal");
            verify(incus).rename("tpl-dev-rebuilding", "tpl-dev");
            int question = text.indexOf("Continue?");
            assertTrue(question >= 0, text);
            assertTrue(text.indexOf("tpl-minimal, tpl-dev will be replaced") >= 0
                    && text.indexOf("tpl-minimal, tpl-dev will be replaced") < question, text);
            assertTrue(text.indexOf("Tools no longer included in tpl-dev: claude") >= 0
                    && text.indexOf("Tools no longer included in tpl-dev: claude") < question, text);
            assertEquals(-1, text.indexOf("Rebuild?"), text);

            // The batch's answer does not carry over to a later build.
            var later = ScriptedPrompts.lines("n");
            doReturn(later).when(cmd).prompts();
            cmd.buildSingleImage(parent, defs);
            later.assertFullyConsumed();
        });
    }

    /** A single-template build still asks before replacing an existing image, and "n" keeps it. */
    @Test
    void singleBuildStillAsksBeforeReplacingAnExistingImage(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var imageDef = new ImageDef();
            imageDef.setName("tpl-minimal");
            var incus = mock(IncusClient.class);
            when(incus.exists(anyString())).thenReturn(true);
            var prompts = ScriptedPrompts.lines("n");
            var cmd = spy(new BuildCommand());
            cmd.incus = incus;
            doReturn(prompts).when(cmd).prompts();

            cmd.buildSingleImage(imageDef, Map.of("tpl-minimal", imageDef));

            prompts.assertFullyConsumed();
            verify(cmd, never()).buildInto(any(), any(), anyString());
            verify(incus, never()).rename(anyString(), anyString());
        });
    }

    /**
     * {@code isx build X} that has to rebuild an outdated parent first asks once for the whole
     * chain, not "Rebuild?" for the parent and again for X (#1069).
     */
    @Test
    void buildWithAnOutdatedParentAsksOnceForTheChain(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var parent = new ImageDef();
            parent.setName("tpl-minimal");
            var child = new ImageDef();
            child.setName("tpl-dev");
            child.setParent("tpl-minimal");
            var defs = Map.of("tpl-minimal", parent, "tpl-dev", child);

            // Both exist; no build version stamped, so the parent is outdated.
            var incus = mock(IncusClient.class);
            when(incus.exists(anyString())).thenReturn(true);
            var prompts = ScriptedPrompts.lines("y");
            var cmd = spy(new BuildCommand());
            cmd.incus = incus;
            doReturn(prompts).when(cmd).prompts();
            doNothing().when(cmd).buildInto(any(), any(), anyString());

            cmd.buildChain(child, defs);

            prompts.assertFullyConsumed();
            verify(incus).rename("tpl-minimal-rebuilding", "tpl-minimal");
            verify(incus).rename("tpl-dev-rebuilding", "tpl-dev");
        });
    }

    /** Declining the chain's one question builds nothing, not even the parent (#1069). */
    @Test
    void decliningTheChainBuildsNothing(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var parent = new ImageDef();
            parent.setName("tpl-minimal");
            var child = new ImageDef();
            child.setName("tpl-dev");
            child.setParent("tpl-minimal");
            var defs = Map.of("tpl-minimal", parent, "tpl-dev", child);

            var incus = mock(IncusClient.class);
            when(incus.exists(anyString())).thenReturn(true);
            var prompts = ScriptedPrompts.lines("n");
            var cmd = spy(new BuildCommand());
            cmd.incus = incus;
            doReturn(prompts).when(cmd).prompts();

            cmd.buildChain(child, defs);

            prompts.assertFullyConsumed();
            verify(cmd, never()).buildInto(any(), any(), anyString());
            verify(incus, never()).rename(anyString(), anyString());
        });
    }

    /** A chain that replaces nothing asks nothing, as a single build of a missing image does (#1069). */
    @Test
    void aChainOfMissingImagesBuildsWithoutAsking(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var defs = sharedParentDefs();
            var prompts = ScriptedPrompts.lines();
            var cmd = chainCommand(incusBuildingInto(new java.util.HashSet<>()), prompts);

            cmd.buildChain(defs.get("tpl-a"), defs);

            prompts.assertFullyConsumed();
            verify(cmd.incus).rename("tpl-minimal-rebuilding", "tpl-minimal");
            verify(cmd.incus).rename("tpl-a-rebuilding", "tpl-a");
        });
    }

    /**
     * The chain's build counter ends with its chain: {@code isx build --missing} builds one leaf's
     * chain, then a sibling whose parent that chain just built, which is a single build (#1069).
     */
    @Test
    void aSingleBuildAfterAChainShowsNoCounter(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var defs = sharedParentDefs();
            var cmd = chainCommand(incusBuildingInto(new java.util.HashSet<>()), ScriptedPrompts.lines());
            cmd.buildChain(defs.get("tpl-a"), defs);

            var originalOut = System.out;
            var out = new java.io.ByteArrayOutputStream();
            System.setOut(new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
            try {
                cmd.buildChain(defs.get("tpl-b"), defs);
            } finally {
                System.setOut(originalOut);
            }

            var text = out.toString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("Building tpl-b"), text);
            assertFalse(text.contains("/2]"), text);
        });
    }

    /** {@code tpl-a} and {@code tpl-b}, both children of {@code tpl-minimal}. */
    private static Map<String, ImageDef> sharedParentDefs() {
        var parent = new ImageDef();
        parent.setName("tpl-minimal");
        var a = new ImageDef();
        a.setName("tpl-a");
        a.setParent("tpl-minimal");
        var b = new ImageDef();
        b.setName("tpl-b");
        b.setParent("tpl-minimal");
        return Map.of("tpl-minimal", parent, "tpl-a", a, "tpl-b", b);
    }

    /** An Incus where only {@code existing} exists, and a build's swap adds its image, up to date. */
    private static IncusClient incusBuildingInto(Set<String> existing) {
        var incus = mock(IncusClient.class);
        when(incus.exists(anyString())).thenAnswer(i -> existing.contains(i.<String>getArgument(0)));
        doAnswer(i -> existing.add(i.getArgument(1))).when(incus).rename(anyString(), anyString());
        when(incus.instanceMetadataOrThrow(anyString()))
                .thenReturn(instance("container", Map.of(Metadata.BUILD_VERSION, dev.incusspawn.BuildInfo.instance().version())));
        return incus;
    }

    private static BuildCommand chainCommand(IncusClient incus, ScriptedPrompts prompts) {
        var cmd = spy(new BuildCommand());
        cmd.incus = incus;
        doReturn(prompts).when(cmd).prompts();
        doNothing().when(cmd).buildInto(any(), any(), anyString());
        return cmd;
    }

    @Test
    void buildThatFailedBeforeItsInstanceExistedDoesNotTryToRemoveDevices(@TempDir Path tmp) {
        // Otherwise each host resource prints a "failed to remove build device" warning.
        InitCommandTest.withHome(tmp, () -> {
            var incus = mock(IncusClient.class);
            when(incus.exists(anyString())).thenReturn(false);
            var cmd = spy(new BuildCommand());
            cmd.incus = incus;
            cmd.yes = true;
            doThrow(new IncusException("no vsock")).when(cmd).buildInto(any(), any(), anyString());
            var imageDef = new ImageDef();
            imageDef.setName("tpl-minimal");
            imageDef.setHostResources(List.of(
                    new ImageDef.HostResource("~/a", "/opt/a", "readonly"),
                    new ImageDef.HostResource("~/b", "/opt/b", "overlay")));

            assertThrows(BuildCommand.BuildFailedException.class,
                    () -> cmd.buildSingleImage(imageDef, Map.of("tpl-minimal", imageDef)));
            verify(incus, never()).deviceRemove(anyString(), startsWith("hr-"));
            verify(incus, never()).shellExec(eq("tpl-minimal-rebuilding"), eq("umount"), anyString());
        });
    }

    // --- resolveEffectiveWorkdir ---

    @Test
    void resolveEffectiveWorkdirExplicit() {
        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setWorkdir("~/my-project");

        assertEquals("/home/agentuser/my-project",
                BuildCommand.resolveEffectiveWorkdir(imageDef, java.util.Map.of()));
    }

    @Test
    void resolveEffectiveWorkdirFromOwnRepo() {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo));

        assertEquals("/home/agentuser/repo",
                BuildCommand.resolveEffectiveWorkdir(imageDef, java.util.Map.of()));
    }

    @Test
    void resolveEffectiveWorkdirFromOwnRepoFirstWins() {
        var repo1 = new ImageDef.RepoEntry();
        repo1.setUrl("https://github.com/owner/first.git");
        repo1.setPath("~/first");
        var repo2 = new ImageDef.RepoEntry();
        repo2.setUrl("https://github.com/owner/second.git");
        repo2.setPath("~/second");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setRepos(List.of(repo1, repo2));

        assertEquals("/home/agentuser/first",
                BuildCommand.resolveEffectiveWorkdir(imageDef, java.util.Map.of()));
    }

    @Test
    void resolveEffectiveWorkdirFromParentRepo() {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setRepos(List.of(repo));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        assertEquals("/home/agentuser/repo",
                BuildCommand.resolveEffectiveWorkdir(child, defs));
    }

    @Test
    void resolveEffectiveWorkdirChildRepoTakesPriority() {
        var parentRepo = new ImageDef.RepoEntry();
        parentRepo.setUrl("https://github.com/owner/parent-repo.git");
        parentRepo.setPath("~/parent-repo");

        var childRepo = new ImageDef.RepoEntry();
        childRepo.setUrl("https://github.com/owner/child-repo.git");
        childRepo.setPath("~/child-repo");

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setRepos(List.of(parentRepo));

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setRepos(List.of(childRepo));

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        assertEquals("/home/agentuser/child-repo",
                BuildCommand.resolveEffectiveWorkdir(child, defs));
    }

    @Test
    void resolveEffectiveWorkdirNoRepos() {
        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");

        assertNull(BuildCommand.resolveEffectiveWorkdir(imageDef, java.util.Map.of()));
    }

    @Test
    void resolveEffectiveWorkdirExplicitOverridesRepo() {
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/repo.git");
        repo.setPath("~/repo");

        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setWorkdir("~/custom-dir");
        imageDef.setRepos(List.of(repo));

        assertEquals("/home/agentuser/custom-dir",
                BuildCommand.resolveEffectiveWorkdir(imageDef, java.util.Map.of()));
    }

    @Test
    void resolveEffectiveWorkdirParentExplicitBeatsChildRepo() {
        var parentRepo = new ImageDef.RepoEntry();
        parentRepo.setUrl("https://github.com/owner/main.git");
        parentRepo.setPath("~/main");

        var helperRepo = new ImageDef.RepoEntry();
        helperRepo.setUrl("https://github.com/owner/helper.git");
        helperRepo.setPath("~/helper");

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setRepos(List.of(parentRepo));
        parent.setWorkdir("~/main");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setRepos(List.of(helperRepo));

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        assertEquals("/home/agentuser/main",
                BuildCommand.resolveEffectiveWorkdir(child, defs));
    }

    @Test
    void resolveEffectiveWorkdirNearestExplicitWins() {
        var grandparent = new ImageDef();
        grandparent.setName("tpl-grandparent");
        grandparent.setWorkdir("~/far");

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setParent("tpl-grandparent");
        parent.setWorkdir("~/near");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var defs = java.util.Map.of("tpl-grandparent", grandparent, "tpl-parent", parent, "tpl-child", child);
        assertEquals("/home/agentuser/near",
                BuildCommand.resolveEffectiveWorkdir(child, defs));
    }

    // --- resolveEffectiveDefaultAction ---

    @Test
    void resolveEffectiveDefaultActionExplicit() {
        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");
        imageDef.setDefaultAction("claude");

        assertEquals("claude",
                BuildCommand.resolveEffectiveDefaultAction(imageDef, java.util.Map.of()));
    }

    @Test
    void resolveEffectiveDefaultActionNone() {
        var imageDef = new ImageDef();
        imageDef.setName("tpl-test");

        assertNull(BuildCommand.resolveEffectiveDefaultAction(imageDef, java.util.Map.of()));
    }

    @Test
    void resolveEffectiveDefaultActionFromParent() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setDefaultAction("claude");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        assertEquals("claude",
                BuildCommand.resolveEffectiveDefaultAction(child, defs));
    }

    @Test
    void resolveEffectiveDefaultActionChildOverridesParent() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setDefaultAction("pi");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setDefaultAction("claude");

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        assertEquals("claude",
                BuildCommand.resolveEffectiveDefaultAction(child, defs));
    }



    // --- findDroppedTools ---

    @Test
    void findDroppedToolsDetectsRemovedTool() {
        var oldDef = new ImageDef();
        oldDef.setName("tpl-dev");
        oldDef.setTools(List.of(new ToolDef.ToolRef("podman"), new ToolDef.ToolRef("claude"),
                new ToolDef.ToolRef("gh")));

        var oldSource = new BuildSource(
                Map.of("tpl-dev", oldDef), Map.of(), Map.of(), Map.of());

        var newDef = new ImageDef();
        newDef.setName("tpl-dev");
        newDef.setTools(List.of(new ToolDef.ToolRef("podman"), new ToolDef.ToolRef("gh")));

        var dropped = BuildCommand.findDroppedTools(oldSource.toJson(), newDef, Map.of("tpl-dev", newDef));
        assertEquals(java.util.Set.of("claude"), dropped);
    }

    @Test
    void findDroppedToolsNoChanges() {
        var oldDef = new ImageDef();
        oldDef.setName("tpl-dev");
        oldDef.setTools(List.of(new ToolDef.ToolRef("podman"), new ToolDef.ToolRef("gh")));

        var oldSource = new BuildSource(
                Map.of("tpl-dev", oldDef), Map.of(), Map.of(), Map.of());

        var newDef = new ImageDef();
        newDef.setName("tpl-dev");
        newDef.setTools(List.of(new ToolDef.ToolRef("podman"), new ToolDef.ToolRef("gh")));

        var dropped = BuildCommand.findDroppedTools(oldSource.toJson(), newDef, Map.of("tpl-dev", newDef));
        assertTrue(dropped.isEmpty());
    }

    @Test
    void findDroppedToolsNullBuildSourceReturnsEmpty() {
        var newDef = new ImageDef();
        newDef.setName("tpl-dev");
        newDef.setTools(List.of(new ToolDef.ToolRef("podman")));

        var dropped = BuildCommand.findDroppedTools(null, newDef, Map.of("tpl-dev", newDef));
        assertTrue(dropped.isEmpty());
    }

    @Test
    void findDroppedToolsEmptyBuildSourceReturnsEmpty() {
        var newDef = new ImageDef();
        newDef.setName("tpl-dev");

        var dropped = BuildCommand.findDroppedTools("", newDef, Map.of("tpl-dev", newDef));
        assertTrue(dropped.isEmpty());
    }

    @Test
    void findDroppedToolsConsidersParentChain() {
        // Old build had claude in tpl-dev
        var oldDev = new ImageDef();
        oldDev.setName("tpl-dev");
        oldDev.setTools(List.of(new ToolDef.ToolRef("podman"), new ToolDef.ToolRef("claude")));

        var oldSource = new BuildSource(
                Map.of("tpl-dev", oldDev), Map.of(), Map.of(), Map.of());

        // New definitions: claude removed from tpl-dev but added in child tpl-mydev
        var newMinimal = new ImageDef();
        newMinimal.setName("tpl-minimal");
        newMinimal.setImage("images:fedora/44");

        var newDev = new ImageDef();
        newDev.setName("tpl-dev");
        newDev.setParent("tpl-minimal");
        newDev.setTools(List.of(new ToolDef.ToolRef("podman")));

        var newMydev = new ImageDef();
        newMydev.setName("tpl-mydev");
        newMydev.setParent("tpl-dev");
        newMydev.setTools(List.of(new ToolDef.ToolRef("claude")));

        var defs = Map.of(
                "tpl-minimal", newMinimal,
                "tpl-dev", newDev,
                "tpl-mydev", newMydev);

        // When rebuilding tpl-dev, claude IS dropped (child doesn't count)
        var droppedFromDev = BuildCommand.findDroppedTools(oldSource.toJson(), newDev, defs);
        assertEquals(java.util.Set.of("claude"), droppedFromDev);

        // When rebuilding tpl-mydev with same old source, claude is NOT dropped (it's in mydev)
        var droppedFromMydev = BuildCommand.findDroppedTools(oldSource.toJson(), newMydev, defs);
        assertTrue(droppedFromMydev.isEmpty());
    }

    @Test
    void findDroppedToolsMultipleDefinitionsInOldSource() {
        // Old build stored definitions for the whole chain
        var oldMinimal = new ImageDef();
        oldMinimal.setName("tpl-minimal");
        oldMinimal.setTools(List.of(new ToolDef.ToolRef("tmux")));

        var oldDev = new ImageDef();
        oldDev.setName("tpl-dev");
        oldDev.setTools(List.of(new ToolDef.ToolRef("podman"), new ToolDef.ToolRef("claude")));

        var oldDefs = new LinkedHashMap<String, ImageDef>();
        oldDefs.put("tpl-minimal", oldMinimal);
        oldDefs.put("tpl-dev", oldDev);

        var oldSource = new BuildSource(oldDefs, Map.of(), Map.of(), Map.of());

        // New: tpl-dev dropped claude, tmux still inherited from parent
        var newMinimal = new ImageDef();
        newMinimal.setName("tpl-minimal");
        newMinimal.setImage("images:fedora/44");
        newMinimal.setTools(List.of(new ToolDef.ToolRef("tmux")));

        var newDev = new ImageDef();
        newDev.setName("tpl-dev");
        newDev.setParent("tpl-minimal");
        newDev.setTools(List.of(new ToolDef.ToolRef("podman")));

        var defs = Map.of("tpl-minimal", newMinimal, "tpl-dev", newDev);

        var dropped = BuildCommand.findDroppedTools(oldSource.toJson(), newDev, defs);
        assertEquals(java.util.Set.of("claude"), dropped,
                "claude should be detected as dropped; tmux and podman remain");
    }

    static ToolSetup simpleToolSetup(String toolName) {
        return new ToolSetup() {
            @Override public String name() { return toolName; }
            @Override public void install(Container container, java.util.Map<String, String> params) {}
        };
    }

    @Test
    void shellQuoteWrapsInSingleQuotes() {
        assertEquals("'hello'", Container.shellQuote("hello"));
    }

    @Test
    void shellQuoteEscapesSingleQuotes() {
        assertEquals("'it'\"'\"'s'", Container.shellQuote("it's"));
    }

    @Test
    void shellQuoteHandlesEmpty() {
        assertEquals("''", Container.shellQuote(""));
    }

    @Test
    void shouldSkipDueToFailedParentDirectParent() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        var failedBuilds = new java.util.HashSet<String>();
        failedBuilds.add("tpl-parent");

        var cmd = new BuildCommand();
        assertTrue(cmd.shouldSkipDueToFailedParent(child, defs, failedBuilds),
                "Child should be skipped when parent failed");
    }

    @Test
    void shouldSkipDueToFailedParentGrandparent() {
        var grandparent = new ImageDef();
        grandparent.setName("tpl-grandparent");

        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setParent("tpl-grandparent");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var defs = java.util.Map.of(
                "tpl-grandparent", grandparent,
                "tpl-parent", parent,
                "tpl-child", child);
        var failedBuilds = new java.util.HashSet<String>();
        failedBuilds.add("tpl-grandparent");

        var cmd = new BuildCommand();
        assertTrue(cmd.shouldSkipDueToFailedParent(child, defs, failedBuilds),
                "Child should be skipped when grandparent failed");
    }

    @Test
    void shouldSkipDueToFailedParentNoFailures() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var defs = java.util.Map.of("tpl-parent", parent, "tpl-child", child);
        var failedBuilds = new java.util.HashSet<String>();

        var cmd = new BuildCommand();
        assertFalse(cmd.shouldSkipDueToFailedParent(child, defs, failedBuilds),
                "Child should not be skipped when no failures");
    }

    @Test
    void shouldSkipDueToFailedParentUnrelatedFailure() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");

        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");

        var unrelated = new ImageDef();
        unrelated.setName("tpl-unrelated");

        var defs = java.util.Map.of(
                "tpl-parent", parent,
                "tpl-child", child,
                "tpl-unrelated", unrelated);
        var failedBuilds = new java.util.HashSet<String>();
        failedBuilds.add("tpl-unrelated");

        var cmd = new BuildCommand();
        assertFalse(cmd.shouldSkipDueToFailedParent(child, defs, failedBuilds),
                "Child should not be skipped when only unrelated template failed");
    }

    @Test
    void shouldSkipDueToFailedParentRootImage() {
        var root = new ImageDef();
        root.setName("tpl-root");
        root.setImage("fedora/41");

        var defs = java.util.Map.of("tpl-root", root);
        var failedBuilds = new java.util.HashSet<String>();

        var cmd = new BuildCommand();
        assertFalse(cmd.shouldSkipDueToFailedParent(root, defs, failedBuilds),
                "Root image should never be skipped due to parent");
    }

    /** An instance's metadata as Incus reports it: its type ({@code container} or {@code virtual-machine}) and config. */
    private static io.hyperfoil.tools.jjq.value.JqObject instance(String type, Map<String, String> config) {
        var cfg = io.hyperfoil.tools.jjq.value.JqObject.builder(config.size());
        config.forEach(cfg::put);
        return io.hyperfoil.tools.jjq.value.JqObject.builder(2).put("type", type).put("config", cfg.build()).build();
    }

    /** An Incus whose container {@code name} has these stamps. */
    private static IncusClient stamped(String name, Map<String, String> stamps) {
        var incus = mock(IncusClient.class);
        when(incus.instanceMetadataOrThrow(name)).thenReturn(instance("container", stamps));
        return incus;
    }

    private static boolean outdated(IncusClient incus, ImageDef imageDef) {
        return BuildCommand.isImageOutdated(imageDef.getName(), imageDef, incus,
                mock(dev.incusspawn.tool.ToolDefLoader.class));
    }

    private static ImageDef def(String name, String parent) {
        var imageDef = new ImageDef();
        imageDef.setName(name);
        imageDef.setParent(parent);
        return imageDef;
    }

    @Test
    void isImageOutdatedDifferentVersion() {
        var imageDef = def("tpl-test", null);
        assertTrue(outdated(stamped("tpl-test", Map.of(Metadata.BUILD_VERSION, "0.0.1")),
                imageDef), "Image with different version should be outdated");
    }

    @Test
    void isImageOutdatedMissingVersion() {
        var imageDef = def("tpl-test", null);
        assertTrue(outdated(stamped("tpl-test", Map.of(Metadata.BUILD_VERSION, "")),
                imageDef), "Image with missing version should be outdated");
        // Never stamped at all: a build with no recorded version.
        assertTrue(outdated(stamped("tpl-test", Map.of()), imageDef));
    }

    @Test
    void isImageOutdatedSameVersionNoDefinitionChange() {
        var imageDef = def("tpl-test", null);
        assertFalse(outdated(stamped("tpl-test", Map.of(Metadata.BUILD_VERSION, dev.incusspawn.BuildInfo.instance().version(),
                        Metadata.DEFINITION_SHA, "")), imageDef),
                "Image with same version and no definition SHA should not be outdated");
    }

    @Test
    void isImageOutdatedDefinitionChanged() {
        var imageDef = def("tpl-test", null);
        assertTrue(outdated(stamped("tpl-test", Map.of(Metadata.BUILD_VERSION, dev.incusspawn.BuildInfo.instance().version(),
                        Metadata.DEFINITION_SHA, "old-sha-123")), imageDef),
                "Image with changed definition should be outdated");
    }

    @Test
    void isImageOutdatedWithAMatchingDefinitionIsCurrent() {
        var imageDef = def("tpl-test", null);
        assertFalse(outdated(stamped("tpl-test", Map.of(Metadata.BUILD_VERSION, dev.incusspawn.BuildInfo.instance().version(),
                        Metadata.DEFINITION_SHA, imageDef.contentFingerprint(Map.of()))),
                imageDef));
    }

    /**
     * {@code --out-of-sync} judges a template by {@link TemplateStaleness}'s rules: all its own
     * stamps in one read, and its parent's in a second, made only when the template is otherwise
     * current (#1115, #1130).
     */
    @Test
    void isImageOutdatedReadsItsStampsOnceAndItsParentOnlyWhenOtherwiseCurrent() {
        var parent = def("tpl-parent", null);
        var child = def("tpl-child", "tpl-parent");

        var old = stamped("tpl-child", Map.of(Metadata.BUILD_VERSION, "0.0.1", Metadata.CREATED, "2026-10-01T10:00:00"));
        assertTrue(outdated(old, child));
        verify(old).instanceMetadataOrThrow("tpl-child");
        verifyNoMoreInteractions(old);

        var current = stamped("tpl-child", Map.of(Metadata.BUILD_VERSION, dev.incusspawn.BuildInfo.instance().version(),
                Metadata.CREATED, "2026-10-01T10:00:00"));
        when(current.instanceMetadataOrThrow("tpl-parent"))
                .thenReturn(instance("container", Map.of(Metadata.CREATED, "2026-09-30T10:00:00")));
        assertFalse(outdated(current, child));
        verify(current).instanceMetadataOrThrow("tpl-child");
        verify(current).instanceMetadataOrThrow("tpl-parent");
        verifyNoMoreInteractions(current);
    }

    /**
     * A template whose parent was built after it is out of sync, as the TUI's {@code ↑} says:
     * it was copied from the parent's earlier build (#1130). Equal or unknown times are not, nor
     * is a parent that is not built.
     */
    @Test
    void isImageOutdatedWhenItsParentWasBuiltAfterIt() {
        var parent = def("tpl-parent", null);
        var child = def("tpl-child", "tpl-parent");
        var incus = stamped("tpl-child", Map.of(Metadata.BUILD_VERSION, dev.incusspawn.BuildInfo.instance().version(),
                Metadata.CREATED, "2026-10-01T10:00:00"));

        for (var parentBuilt : List.of("2026-10-01T11:00:00", "2026-10-01T10:00:00", "")) {
            when(incus.instanceMetadataOrThrow("tpl-parent"))
                    .thenReturn(instance("container", Map.of(Metadata.CREATED, parentBuilt)));
            assertEquals(parentBuilt.startsWith("2026-10-01T11"), outdated(incus, child), parentBuilt);
        }

        when(incus.instanceMetadataOrThrow("tpl-parent")).thenReturn(null);
        assertFalse(outdated(incus, child), "a parent that is not built was not rebuilt");
    }

    /**
     * A VM over a container parent ({@code tpl-isx-vm} over {@code tpl-isx}) is built from the
     * definitions, not copied, so rebuilding the parent leaves it current: {@code --out-of-sync}
     * must not rebuild it, for minutes, after every rebuild of the parent (#1130 review).
     */
    @Test
    void aTemplateOfAnotherMachineTypeThanItsParentIsNotOutdatedByItsRebuild() {
        var parent = def("tpl-isx", null);
        var child = def("tpl-isx-vm", "tpl-isx");
        child.setType("vm");
        var incus = mock(IncusClient.class);
        when(incus.instanceMetadataOrThrow("tpl-isx-vm")).thenReturn(instance("virtual-machine", Map.of(
                Metadata.BUILD_VERSION, dev.incusspawn.BuildInfo.instance().version(),
                Metadata.CREATED, "2026-10-01T10:00:00")));
        when(incus.instanceMetadataOrThrow("tpl-isx"))
                .thenReturn(instance("container", Map.of(Metadata.CREATED, "2026-10-07T10:00:00")));

        assertFalse(outdated(incus, child));
    }

    /** An unpinned root as loaded from its YAML: the built-in base image tag is only a fallback. */
    private static ImageDef trackedRoot() {
        var root = def("tpl-minimal", null);
        root.setImage("isx-base");
        root.setImageUrl("https://github.com/Sanne/incus-spawn-images/releases/download/{tag}/fedora-44-{arch}.tar.xz");
        root.setImageTag("fedora-44-20261009");
        root.setImageSha256(Map.of("x86_64", "builtin-sha"));
        return root;
    }

    /**
     * A build that tracks a base image newer than the built-in tag stamps the definition it was
     * given, so the next look at that same definition finds it unchanged; before, the stamp
     * folded in the tracked tag and no rebuild could clear the mark (#1244).
     */
    @Test
    void aRootBuiltOnANewerTrackedBaseImageIsCurrentAfterwards(@TempDir Path tmp) {
        InitCommandTest.withHome(tmp, () -> {
            var releases = mock(BaseImageReleases.class);
            var latest = new BaseImageReleases.Release("fedora-44-20261010", "2026-10-10", "sums");
            try {
                when(releases.fetchReleases()).thenReturn(List.of(latest));
                when(releases.fetchChecksums(latest)).thenReturn(new BaseImageReleases.Checksums(
                        Map.of("x86_64", "tracked-sha"), Map.of()));
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
            var incus = mock(IncusClient.class);
            var cmd = spy(new BuildCommand());
            cmd.incus = incus;
            cmd.toolDefLoader = mock(ToolDefLoader.class);
            doReturn(releases).when(cmd).baseImageReleases(anyString());

            var root = trackedRoot();
            var base = cmd.resolveTrackedBaseImage(root);
            assertEquals("fedora-44-20261010", base.tag(), "the build downloads the newest release");
            assertEquals(Map.of("x86_64", "tracked-sha"), base.sha256());
            cmd.stampBuildVersion("tpl-minimal", root, Map.of());

            var stamp = ArgumentCaptor.forClass(String.class);
            verify(incus).configSet(eq("tpl-minimal"), eq(Metadata.DEFINITION_SHA), stamp.capture());
            var reloaded = trackedRoot();
            assertFalse(TemplateStaleness.definitionChanged(stamp.getValue(), reloaded, Map.of()),
                    "the definition did not change, only a newer base image was published");
            assertFalse(outdated(stamped("tpl-minimal", Map.of(
                            Metadata.BUILD_VERSION, dev.incusspawn.BuildInfo.instance().version(),
                            Metadata.DEFINITION_SHA, stamp.getValue())),
                    reloaded), "--out-of-sync must not rebuild it again");
        });
    }

    /** Asking Incus failing is not "the parent is not built": it is thrown, never read as current. */
    @Test
    void aParentThatCannotBeReadFailsTheCheck() {
        var parent = def("tpl-parent", null);
        var child = def("tpl-child", "tpl-parent");
        var incus = stamped("tpl-child", Map.of(Metadata.BUILD_VERSION, dev.incusspawn.BuildInfo.instance().version(),
                Metadata.CREATED, "2026-10-01T10:00:00"));
        when(incus.instanceMetadataOrThrow("tpl-parent")).thenThrow(new IncusException("HTTP 500"));

        assertThrows(IncusException.class, () -> outdated(incus, child));
    }

    // --- default-action sync after every build (#284) ---

    private static Map<String, ImageDef> parentAndChild(String parentDefaultAction) throws Exception {
        var parent = ImageDef.parseYaml("name: tpl-parent\npackages: [git]\n"
                + (parentDefaultAction != null ? "default-action: " + parentDefaultAction + "\n" : ""));
        var child = ImageDef.parseYaml("name: tpl-child\nparent: tpl-parent\n");
        return Map.of("tpl-parent", parent, "tpl-child", child);
    }

    /**
     * {@code name} as {@code GET /1.0/instances?recursion=1} lists a template this isx built
     * from {@code defs}, stamped with {@code defaultAction}.
     */
    private static io.hyperfoil.tools.jjq.value.JqObject template(String name, Map<String, ImageDef> defs,
            String defaultAction) {
        return template(name, defs, defaultAction, Map.of());
    }

    private static io.hyperfoil.tools.jjq.value.JqObject template(String name, Map<String, ImageDef> defs,
            String defaultAction, Map<String, String> configOverrides) {
        var built = new LinkedHashMap<String, ImageDef>();
        BuildCommand.definitionChain(defs.get(name), defs).forEach(d -> built.put(d.getName(), d));
        var config = io.hyperfoil.tools.jjq.value.JqObject.builder(8)
                .put(Metadata.TYPE, Metadata.TYPE_BASE)
                .put(Metadata.BUILD_VERSION, dev.incusspawn.BuildInfo.instance().version())
                .put(Metadata.DEFINITION_SHA, defs.get(name).contentFingerprint(Map.of()))
                .put(Metadata.BUILD_SOURCE, new BuildSource(built, null, null, null).toJson());
        if (defaultAction != null) config.put(Metadata.DEFAULT_ACTION, defaultAction);
        configOverrides.forEach(config::put);
        return io.hyperfoil.tools.jjq.value.JqObject.builder(2)
                .put("name", name)
                .put("config", config.build())
                .build();
    }

    private static BuildCommand commandListing(IncusClient incus,
            io.hyperfoil.tools.jjq.value.JqValue... instances) {
        var list = io.hyperfoil.tools.jjq.value.JqArray.arrayBuilder();
        for (var instance : instances) {
            list.add(instance);
        }
        when(incus.listJsonConfig()).thenReturn(list.build());
        var cmd = new BuildCommand();
        cmd.incus = incus;
        cmd.toolDefLoader = mock(ToolDefLoader.class);
        return cmd;
    }

    /**
     * default-action is left out of the fingerprint, so editing it never rebuilds anything:
     * the build still has to bring the stamp of every template in line, inherited values included.
     */
    @Test
    void editedDefaultActionReachesTemplatesTheBuildDidNotRebuild() throws Exception {
        var defs = parentAndChild("claude");
        var incus = mock(IncusClient.class);
        var cmd = commandListing(incus,
                template("tpl-parent", defs, null),
                template("tpl-child", defs, "codex"));

        cmd.syncDefaultActions(defs);

        verify(incus).configSet("tpl-parent", Metadata.DEFAULT_ACTION, "claude");
        verify(incus).configSet("tpl-child", Metadata.DEFAULT_ACTION, "claude");
    }

    /** A listing Incus cannot give is a warning; a bug in the sync itself is not hidden as one. */
    @Test
    void onlyAnIncusFailureIsAWarningWhenTheListingCannotBeRead() throws Exception {
        var defs = parentAndChild("claude");
        var incus = mock(IncusClient.class);
        var cmd = commandListing(incus);
        doThrow(new dev.incusspawn.incus.IncusException("down")).when(incus).listJsonConfig();
        cmd.syncDefaultActions(defs);
        verify(incus, never()).configSet(anyString(), anyString(), anyString());

        doThrow(new NullPointerException("a bug")).when(incus).listJsonConfig();
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> cmd.syncDefaultActions(defs));
    }

    @Test
    void removedDefaultActionIsUnstamped() throws Exception {
        var defs = parentAndChild(null);
        var incus = mock(IncusClient.class);
        var cmd = commandListing(incus,
                template("tpl-parent", defs, "claude"),
                template("tpl-child", defs, null));

        cmd.syncDefaultActions(defs);

        verify(incus).configUnset("tpl-parent", Metadata.DEFAULT_ACTION);
        verify(incus, never()).configUnset(eq("tpl-child"), anyString());
    }

    /** One list request covers every template, and a current stamp costs no write. */
    @Test
    void currentStampsAndNonTemplatesAreLeftAlone() throws Exception {
        var defs = parentAndChild("claude");
        var incus = mock(IncusClient.class);
        var clone = template("tpl-child", defs, null, Map.of(Metadata.TYPE, Metadata.TYPE_CLONE));
        var cmd = commandListing(incus, template("tpl-parent", defs, "claude"), clone);

        cmd.syncDefaultActions(defs);

        verify(incus).listJsonConfig();
        verifyNoMoreInteractions(incus);
    }

    /** An outdated template keeps the stamp matching what it has installed until it is rebuilt. */
    @Test
    void outdatedTemplateKeepsItsStamp() throws Exception {
        var defs = parentAndChild("claude");
        var incus = mock(IncusClient.class);
        var outdated = template("tpl-parent", defs, null, Map.of(Metadata.DEFINITION_SHA, "old-sha"));
        var cmd = commandListing(incus, outdated);

        cmd.syncDefaultActions(defs);

        verify(incus, never()).configSet(anyString(), anyString(), anyString());
    }

    /** A project-local definition must not reach a same-named template built from elsewhere (#765). */
    @Test
    void templateBuiltFromAnotherProjectKeepsItsStamp() throws Exception {
        var defs = parentAndChild(null);
        var incus = mock(IncusClient.class);
        var cmd = commandListing(incus, template("tpl-parent", parentAndChild(null), "codex"));
        defs.get("tpl-parent").setProjectRoot(Path.of("/home/user/cloned-repo"));

        cmd.syncDefaultActions(defs);

        verify(incus, never()).configUnset(anyString(), anyString());
    }

    /**
     * A child inherits its default-action, so it must have been built from the parent as it
     * stands: one built before the parent added a tool would be stamped with a tool it lacks.
     */
    @Test
    void childBuiltFromAnOlderParentKeepsItsStamp() throws Exception {
        var builtFrom = parentAndChild(null);
        var defs = new LinkedHashMap<>(parentAndChild("codex"));
        defs.put("tpl-parent", ImageDef.parseYaml(
                "name: tpl-parent\npackages: [git, nodejs]\ndefault-action: codex\n"));
        var incus = mock(IncusClient.class);
        var cmd = commandListing(incus,
                template("tpl-parent", defs, null),
                template("tpl-child", builtFrom, null));

        cmd.syncDefaultActions(defs);

        verify(incus).configSet("tpl-parent", Metadata.DEFAULT_ACTION, "codex");
        verify(incus, never()).configSet(eq("tpl-child"), anyString(), anyString());
    }

    /** With a parent's definition gone, the inherited value is unknown: the stamp is the fallback. */
    @Test
    void childWhoseParentDefinitionIsGoneKeepsItsStamp() throws Exception {
        var builtFrom = parentAndChild("claude");
        var incus = mock(IncusClient.class);
        var cmd = commandListing(incus, template("tpl-child", builtFrom, "claude"));

        cmd.syncDefaultActions(Map.of("tpl-child", builtFrom.get("tpl-child")));

        verify(incus, never()).configUnset(anyString(), anyString());
    }

    /** A project-local parent override must not reach a child built from the user's definitions (#765). */
    @Test
    void projectLocalParentOverrideDoesNotReachTemplatesBuiltElsewhere() throws Exception {
        var builtFrom = parentAndChild(null);
        var defs = new LinkedHashMap<>(parentAndChild("codex"));
        defs.get("tpl-parent").setProjectRoot(Path.of("/home/user/cloned-repo"));
        var incus = mock(IncusClient.class);
        var cmd = commandListing(incus, template("tpl-child", builtFrom, null));

        cmd.syncDefaultActions(defs);

        verify(incus, never()).configSet(anyString(), anyString(), anyString());
    }

    @Test
    void parentCycleEndsTheWalk() throws Exception {
        var defs = Map.of(
                "tpl-a", ImageDef.parseYaml("name: tpl-a\nparent: tpl-b\n"),
                "tpl-b", ImageDef.parseYaml("name: tpl-b\nparent: tpl-a\ndefault-action: claude\n"));
        assertNull(BuildCommand.definitionChain(defs.get("tpl-a"), defs));
    }

    /** A template stamped by another isx build may differ in what it installed: it waits for its rebuild. */
    @Test
    void templateBuiltByAnotherIsxVersionKeepsItsStamp() throws Exception {
        var defs = parentAndChild("claude");
        var incus = mock(IncusClient.class);
        var older = template("tpl-parent", defs, null, Map.of(Metadata.BUILD_VERSION, "0.0.1-older"));
        var cmd = commandListing(incus, older);

        cmd.syncDefaultActions(defs);

        verify(incus, never()).configSet(anyString(), anyString(), anyString());
    }

    /**
     * An unrelated template whose tool parameters no longer validate must not fail the build
     * that triggered the sync, nor stop the other templates from being synced.
     */
    @Test
    void templateThatNoLongerValidatesDoesNotFailTheBuild() throws Exception {
        var defs = new LinkedHashMap<>(parentAndChild("claude"));
        var broken = ImageDef.parseYaml("name: tpl-broken\ndefault-action: maven-3\n");
        broken.setTools(List.of(new ToolDef.ToolRef("maven-3", Map.of("version", "9"))));
        defs.put("tpl-broken", broken);
        var incus = mock(IncusClient.class);
        var cmd = commandListing(incus,
                template("tpl-broken", defs, null),
                template("tpl-parent", defs, null));
        // The tool no longer declares the parameter the template still passes
        when(cmd.toolDefLoader.find("maven-3")).thenReturn(simpleToolSetup("maven-3"));

        assertDoesNotThrow(() -> cmd.syncDefaultActions(defs));

        verify(incus, never()).configSet(eq("tpl-broken"), anyString(), anyString());
        verify(incus).configSet("tpl-parent", Metadata.DEFAULT_ACTION, "claude");
    }

    /** The build has already succeeded or failed: a sync error must not replace that outcome. */
    @Test
    void syncErrorsAreOnlyWarnings() throws Exception {
        var defs = parentAndChild("claude");
        var incus = mock(IncusClient.class);
        var cmd = commandListing(incus,
                template("tpl-parent", defs, null),
                template("tpl-child", defs, null));
        doThrow(new IncusException("gone")).when(incus).configSet(eq("tpl-parent"), anyString(), anyString());

        assertDoesNotThrow(() -> cmd.syncDefaultActions(defs));
        verify(incus).configSet("tpl-child", Metadata.DEFAULT_ACTION, "claude");

        when(incus.listJsonConfig()).thenThrow(new IncusException("daemon gone"));
        assertDoesNotThrow(() -> cmd.syncDefaultActions(defs));
    }

    // --- collectDescendants ---

    @Test
    void collectDescendantsFindsDirectChildren() {
        var parent = new ImageDef();
        parent.setName("tpl-base");

        var child1 = new ImageDef();
        child1.setName("tpl-java");
        child1.setParent("tpl-base");

        var child2 = new ImageDef();
        child2.setName("tpl-python");
        child2.setParent("tpl-base");

        var defs = new java.util.LinkedHashMap<String, ImageDef>();
        defs.put("tpl-base", parent);
        defs.put("tpl-java", child1);
        defs.put("tpl-python", child2);

        var result = new java.util.ArrayList<String>();
        BuildCommand.collectDescendants("tpl-base", defs, result, new java.util.LinkedHashSet<>());

        assertEquals(2, result.size());
        assertTrue(result.contains("tpl-java"));
        assertTrue(result.contains("tpl-python"));
    }

    @Test
    void collectDescendantsFindsTransitiveDescendants() {
        var root = new ImageDef();
        root.setName("tpl-base");

        var mid = new ImageDef();
        mid.setName("tpl-dev");
        mid.setParent("tpl-base");

        var leaf = new ImageDef();
        leaf.setName("tpl-java");
        leaf.setParent("tpl-dev");

        var defs = new java.util.LinkedHashMap<String, ImageDef>();
        defs.put("tpl-base", root);
        defs.put("tpl-dev", mid);
        defs.put("tpl-java", leaf);

        var result = new java.util.ArrayList<String>();
        BuildCommand.collectDescendants("tpl-base", defs, result, new java.util.LinkedHashSet<>());

        assertEquals(List.of("tpl-dev", "tpl-java"), result,
                "Should find tpl-dev before tpl-java (parent before child)");
    }

    @Test
    void collectDescendantsEmptyForLeaf() {
        var leaf = new ImageDef();
        leaf.setName("tpl-leaf");

        var defs = java.util.Map.of("tpl-leaf", leaf);
        var result = new java.util.ArrayList<String>();
        BuildCommand.collectDescendants("tpl-leaf", defs, result, new java.util.LinkedHashSet<>());

        assertTrue(result.isEmpty(), "Leaf template should have no descendants");
    }

    @Test
    void collectDescendantsSkipsAlreadySeen() {
        var parent = new ImageDef();
        parent.setName("tpl-base");

        var child = new ImageDef();
        child.setName("tpl-java");
        child.setParent("tpl-base");

        var defs = new java.util.LinkedHashMap<String, ImageDef>();
        defs.put("tpl-base", parent);
        defs.put("tpl-java", child);

        var seen = new java.util.LinkedHashSet<String>();
        seen.add("tpl-java");

        var result = new java.util.ArrayList<String>();
        BuildCommand.collectDescendants("tpl-base", defs, result, seen);

        assertTrue(result.isEmpty(), "Already-seen descendant should be skipped");
    }

    // --- diagnoseCrashCause ---

    @Test
    void diagnoseCrashCauseDetectsOom() {
        var dmesg = "[0.000000] oom-kill:constraint=CONSTRAINT_NONE,nodemask=...\n"
                + "[0.000000] Out of memory: Killed process 1234";
        assertEquals("out of memory — the kernel killed the container because the VM ran out of RAM",
                BuildCommand.diagnoseCrashCause(dmesg));
    }

    @Test
    void diagnoseCrashCauseDetectsMemoryCgroupOom() {
        var dmesg = "[0.000000] Memory cgroup out of memory: Killed process 456";
        assertEquals("out of memory — the kernel killed the container because the VM ran out of RAM",
                BuildCommand.diagnoseCrashCause(dmesg));
    }

    @Test
    void diagnoseCrashCauseDetectsPidsLimit() {
        var dmesg = "[0.000000] cgroup: fork rejected by pids controller in /lxc.payload.tpl-build";
        assertEquals("process limit exceeded — the container hit the cgroup process (PID) limit",
                BuildCommand.diagnoseCrashCause(dmesg));
    }

    @Test
    void diagnoseCrashCauseReturnsNullForUnknown() {
        var dmesg = "[0.000000] some unrelated kernel message";
        assertNull(BuildCommand.diagnoseCrashCause(dmesg));
    }

    @Test
    void diagnoseCrashCauseOomTakesPriorityOverPids() {
        var dmesg = "[0.000000] oom-kill:constraint=CONSTRAINT_NONE\n"
                + "[0.000000] cgroup: fork rejected by pids controller in /lxc.payload.test";
        assertEquals("out of memory — the kernel killed the container because the VM ran out of RAM",
                BuildCommand.diagnoseCrashCause(dmesg));
    }

    @Test
    void projectLocalFileBaseImageMustStayInsideProject(@TempDir Path tmp) throws Exception {
        var project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        var inside = Files.writeString(project.resolve("fedora.tar.xz"), "image");
        var outside = Files.writeString(tmp.resolve("backup.tar.xz"), "private");

        var def = new ImageDef();
        def.setName("tpl-project");
        def.setSource(project.resolve(".incus-spawn/images/project.yaml").toString());
        def.setProjectRoot(project);
        var defs = Map.of("tpl-project", def);

        def.setImageUrl(inside.toUri().toString());
        BuildCommand.requireConfinedBaseImages(def, defs);

        def.setImageUrl(outside.toUri().toString());
        assertThrows(dev.incusspawn.config.HostResourceSetup.HostPathOutsideProjectException.class,
                () -> BuildCommand.requireConfinedBaseImages(def, defs));

        def.setImageUrl(inside.toUri().toString());
        def.setVmImageUrl("file://" + project + "/../backup.tar.xz");
        assertThrows(dev.incusspawn.config.HostResourceSetup.HostPathOutsideProjectException.class,
                () -> BuildCommand.requireConfinedBaseImages(def, defs));

        // The same URL is fine from a trusted layer: that is the local image-testing workflow.
        def.setProjectRoot(null);
        BuildCommand.requireConfinedBaseImages(def, defs);
    }

    // --- #765: project-local templates must not reach host state through images or repos ---

    private static ImageDef projectLocal(String name, Path root) {
        var def = new ImageDef();
        def.setName(name);
        def.setSource(root.resolve(".incus-spawn/images/x.yaml").toString());
        def.setProjectRoot(root);
        return def;
    }

    private static ImageDef trusted(String name) {
        var def = new ImageDef();
        def.setName(name);
        return def;
    }

    @Test
    void projectLocalTemplateCannotReplaceAnImageItDidNotImport() {
        var incus = mock(IncusClient.class);
        when(incus.imageAliasTargetOrThrow("fedora-44-base")).thenReturn("abc");
        when(incus.getImageProperty("abc", "incus-spawn.tag")).thenReturn("fedora-44-20260915");
        var cmd = spy(new BuildCommand());
        cmd.incus = incus;

        var e = assertThrows(IllegalStateException.class, () -> cmd.downloadAndAliasImage("fedora-44-base",
                "https://attacker.example/evil.tar.xz", null, "fedora-44-20260915",
                projectLocal("tpl-evil", Path.of("/work/repo"))));
        assertTrue(e.getMessage().contains("may only replace images it imported itself"), e.getMessage());
        verify(incus, never()).deleteImage(any());
        verify(incus, never()).deleteImageAlias(any());
        verify(cmd, never()).newDownloadCache();
    }

    @Test
    void projectLocalTemplateReusesItsOwnImport() {
        var incus = mock(IncusClient.class);
        when(incus.imageAliasTargetOrThrow("proj-base")).thenReturn("abc");
        when(incus.getImageProperty("abc", "incus-spawn.tag")).thenReturn("v1");
        when(incus.imagePropertyOrThrow("abc", BuildCommand.IMAGE_PROJECT_PROPERTY)).thenReturn("/work/repo");
        var cmd = spy(new BuildCommand());
        cmd.incus = incus;

        cmd.downloadAndAliasImage("proj-base", "https://example.com/img.tar.xz", null, "v1",
                projectLocal("tpl-proj", Path.of("/work/repo")));
        verify(incus, never()).deleteImage(any());
        verify(cmd, never()).newDownloadCache();
    }

    @Test
    void trustedTemplateReplacesAProjectImportEvenWithMatchingTag(@TempDir Path tmp) throws Exception {
        var incus = mock(IncusClient.class);
        when(incus.imageAliasTargetOrThrow("fedora-44-base")).thenReturn("evil");
        when(incus.getImageProperty("evil", "incus-spawn.tag")).thenReturn("fedora-44-20260915");
        when(incus.imagePropertyOrThrow("evil", BuildCommand.IMAGE_PROJECT_PROPERTY)).thenReturn("/work/repo");
        var tarball = Files.writeString(tmp.resolve("genuine.tar.xz"), "genuine");
        when(incus.importImage(tarball)).thenReturn("genuine");
        when(incus.imagePropertyOrThrow("genuine", BuildCommand.IMAGE_PROJECT_PROPERTY)).thenReturn("");
        var cache = mock(dev.incusspawn.tool.DownloadCache.class);
        when(cache.downloadAllowingLocalFile(anyString(), any(), any())).thenReturn(tarball);
        var cmd = spy(new BuildCommand());
        cmd.incus = incus;
        doReturn(cache).when(cmd).newDownloadCache();

        cmd.downloadAndAliasImage("fedora-44-base", "https://example.com/fedora.tar.xz", null,
                "fedora-44-20260915", trusted("tpl-minimal"));

        verify(incus).deleteImage("evil");
        // The stamp overwrites whatever the tarball shipped, before the alias makes it usable.
        var order = inOrder(incus);
        order.verify(incus).setImageProperty("genuine", BuildCommand.IMAGE_PROJECT_PROPERTY, "");
        order.verify(incus).createImageAlias("fedora-44-base", "genuine");
    }

    @Test
    void importWhoseOwnershipStampDidNotStickIsDeleted(@TempDir Path tmp) throws Exception {
        var incus = mock(IncusClient.class);
        var tarball = Files.writeString(tmp.resolve("img.tar.xz"), "img");
        when(incus.importImage(tarball)).thenReturn("fp");
        // setImageProperty fails silently; the read-back shows no stamp.
        when(incus.imagePropertyOrThrow("fp", BuildCommand.IMAGE_PROJECT_PROPERTY)).thenReturn(null);
        var cache = mock(dev.incusspawn.tool.DownloadCache.class);
        when(cache.downloadAllowingLocalFile(anyString(), any(), any())).thenReturn(tarball);
        var cmd = spy(new BuildCommand());
        cmd.incus = incus;
        doReturn(cache).when(cmd).newDownloadCache();

        assertThrows(IllegalStateException.class, () -> cmd.downloadAndAliasImage("proj-base",
                "https://example.com/img.tar.xz", null, "v1", projectLocal("tpl-proj", Path.of("/work/repo"))));
        verify(incus).deleteImage("fp");
        verify(incus, never()).createImageAlias(any(), any());
    }

    @Test
    void baseImageDownloadFailureNamesTheUrl() throws Exception {
        var incus = mock(IncusClient.class);
        var cache = mock(dev.incusspawn.tool.DownloadCache.class);
        when(cache.downloadAllowingLocalFile(anyString(), any(), any()))
                .thenThrow(new java.io.IOException("Connection reset"));
        var cmd = spy(new BuildCommand());
        cmd.incus = incus;
        doReturn(cache).when(cmd).newDownloadCache();

        var e = assertThrows(RuntimeException.class, () -> cmd.downloadAndAliasImage("fedora-44-base",
                "https://example.com/fedora.tar.xz", null, "v1", trusted("tpl-minimal")));
        assertTrue(e.getMessage().contains("https://example.com/fedora.tar.xz"), e.getMessage());
        assertTrue(e.getMessage().contains("Connection reset"), e.getMessage());
        verify(incus, never()).importImage(any());
    }

    @Test
    void buildsOnlyStartFromTrustedOrOwnProjectImages() {
        var incus = mock(IncusClient.class);
        when(incus.imageAliasTargetOrThrow("fedora-44-base")).thenReturn("abc");
        when(incus.imagePropertyOrThrow("abc", BuildCommand.IMAGE_PROJECT_PROPERTY)).thenReturn("/work/repo");
        when(incus.imageAliasTargetOrThrow("legacy")).thenReturn("old");
        var cmd = new BuildCommand();
        cmd.incus = incus;

        assertThrows(IllegalStateException.class,
                () -> cmd.requireImageTrustedFor("fedora-44-base", trusted("tpl-mine"), null));
        assertThrows(IllegalStateException.class,
                () -> cmd.requireImageTrustedFor("fedora-44-base", projectLocal("tpl-other", Path.of("/elsewhere")), null));
        cmd.requireImageTrustedFor("fedora-44-base", projectLocal("tpl-proj", Path.of("/work/repo")));
        cmd.requireImageTrustedFor("legacy", trusted("tpl-mine")); // imported before the stamp existed
        cmd.requireImageTrustedFor("images:fedora/44", trusted("tpl-mine"));
        // A fingerprint names the same local image, and gets the same gate (#1138)
        assertThrows(IllegalStateException.class,
                () -> cmd.requireImageTrustedFor("sha256:abc", trusted("tpl-mine"), null));
    }

    private static IncusClient projectImportedBaseImage() {
        var incus = mock(IncusClient.class);
        when(incus.imageAliasTargetOrThrow("fedora-44-base")).thenReturn("abc");
        when(incus.imagePropertyOrThrow("abc", BuildCommand.IMAGE_PROJECT_PROPERTY)).thenReturn("/work/repo");
        return incus;
    }

    @Test
    void refusedProjectImportIsDeletedOnlyWhenSomeoneAtATerminalSaysSo() {
        var incus = projectImportedBaseImage();
        var cmd = new BuildCommand();
        cmd.incus = incus;

        // No terminal: the image stays, and the message says how to remove it without naming incus.
        var e = assertThrows(IllegalStateException.class,
                () -> cmd.requireImageTrustedFor("fedora-44-base", trusted("tpl-mine"), null));
        assertTrue(e.getMessage().contains("in a terminal"), e.getMessage());
        assertFalse(e.getMessage().contains("incus "), e.getMessage());
        // Declined: kept.
        var no = ScriptedPrompts.lines("n");
        e = assertThrows(IllegalStateException.class,
                () -> cmd.requireImageTrustedFor("fedora-44-base", trusted("tpl-mine"), no));
        no.assertFullyConsumed();
        assertFalse(e.getMessage().contains("incus "), e.getMessage());
        verify(incus, never()).deleteImageOrThrow(any());
        // Accepted: deleted, and the build still stops, since its base image is gone.
        var yes = ScriptedPrompts.lines("y");
        e = assertThrows(IllegalStateException.class,
                () -> cmd.requireImageTrustedFor("fedora-44-base", trusted("tpl-mine"), yes));
        yes.assertFullyConsumed();
        assertTrue(e.getMessage().contains("re-run the build"), e.getMessage());
        verify(incus).deleteImageOrThrow("abc");
    }

    // ---- A cached image whose /sbin/init cannot exec (#986), in the order a real build meets it ----

    /** A build launch against {@code daemon}, started the way a build starts it. */
    private static BuildCommand.Launched launch(FakeIncusDaemon daemon, String image, boolean reimportable) {
        var cmd = new BuildCommand();
        cmd.incus = daemon.client();
        var def = trusted("tpl-x");
        return cmd.launchBuildInstance(image, "tpl-x-rebuilding", MachineType.CONTAINER, "tpl-x", reimportable,
                () -> new BuildCommand.Launched(cmd.startBuild("tpl-x-rebuilding", def, Map.of("tpl-x", def)), null));
    }

    @Test
    void aRemoteImageWhoseInitCannotExecIsDeletedAndFetchedAgain() {
        var daemon = new FakeIncusDaemon().containerSubvolumes("tpl-x-rebuilding").nextFetchBrokenInit("fedora/44");

        launch(daemon, "images:fedora/44", false);

        assertFalse(daemon.hasImage("fetched-1"), "the broken cached copy is deleted");
        var refetched = daemon.fetchedAs("fedora/44");
        assertEquals("fetched-2", refetched);
        assertEquals(refetched, daemon.instance("tpl-x-rebuilding").path("config").path("volatile.base_image").asText());
        assertEquals("Running", daemon.instance("tpl-x-rebuilding").path("status").asText());
    }

    @Test
    void aLocalImageWhoseInitCannotExecIsDeletedForTheNextBuildToReimport() {
        var daemon = new FakeIncusDaemon().containerSubvolumes("tpl-x-rebuilding")
                .imageAlias("fedora-44-base", "fp-broken").brokenInit("fp-broken");

        var e = assertThrows(RuntimeException.class, () -> launch(daemon, "fedora-44-base", true));

        assertTrue(e.getMessage().contains("re-run 'isx build tpl-x'"), e.getMessage());
        assertFalse(e.getMessage().contains("incus "), e.getMessage());
        assertFalse(daemon.hasImage("fp-broken"));
        assertFalse(daemon.client().exists("tpl-x-rebuilding"), "the failed build instance is removed");
    }

    @Test
    void aBrokenLocalImageIsxDidNotImportIsKept() {
        var daemon = new FakeIncusDaemon().containerSubvolumes("tpl-x-rebuilding")
                .imageAlias("hand-imported", "fp-only-copy").brokenInit("fp-only-copy");

        var e = assertThrows(RuntimeException.class, () -> launch(daemon, "hand-imported", false));

        // No image_url: nothing would import it again, so it is the only copy and stays.
        assertTrue(daemon.hasImage("fp-only-copy"));
        assertTrue(e.getMessage().contains("left in place"), e.getMessage());
        assertFalse(e.getMessage().contains("import it again"), e.getMessage());
        assertFalse(e.getMessage().contains("incus "), e.getMessage());
    }

    @Test
    void aLocalImageNamedByFingerprintIsNotTakenForARemoteOne() {
        var daemon = new FakeIncusDaemon().containerSubvolumes("tpl-x-rebuilding")
                .imageAlias("hand-imported", "fp-only-copy").brokenInit("fp-only-copy");

        assertThrows(RuntimeException.class, () -> launch(daemon, "sha256:fp-only-copy", false));

        assertTrue(daemon.hasImage("fp-only-copy"));
    }

    @Test
    void aRemoteImageBrokenAgainAfterTheFetchSaysSo() {
        var daemon = new FakeIncusDaemon().containerSubvolumes("tpl-x-rebuilding")
                .nextFetchBrokenInit("fedora/44").nextFetchBrokenInit("fedora/44");

        var e = assertThrows(RuntimeException.class, () -> launch(daemon, "images:fedora/44", false));

        assertTrue(e.getMessage().contains("broken /sbin/init too"), e.getMessage());
    }

    @Test
    void anotherStartFailureDeletesNothing() {
        var daemon = new FakeIncusDaemon().containerSubvolumes("tpl-x-rebuilding")
                .imageAlias("fedora-44-base", "fp");
        var cmd = new BuildCommand();
        cmd.incus = daemon.client();

        assertThrows(IncusException.class, () -> cmd.launchBuildInstance("fedora-44-base", "tpl-x-rebuilding",
                MachineType.CONTAINER, "tpl-x", true, () -> { throw new IncusException("Failed to start tpl-x-rebuilding"); }));

        assertTrue(daemon.hasImage("fp"));
        assertTrue(daemon.client().exists("tpl-x-rebuilding"));
    }

    @Test
    void imageProvenanceLookupsFailClosed() {
        var incus = mock(IncusClient.class);
        when(incus.imageAliasTargetOrThrow("fedora-44-base")).thenReturn("abc");
        when(incus.imagePropertyOrThrow("abc", BuildCommand.IMAGE_PROJECT_PROPERTY))
                .thenThrow(new IncusException("HTTP 500"));
        when(incus.imageAliasTargetOrThrow("flaky")).thenThrow(new IncusException("HTTP 503"));
        var cmd = spy(new BuildCommand());
        cmd.incus = incus;

        // A failed read is not "imported by a trusted definition"...
        assertThrows(IncusException.class, () -> cmd.requireImageTrustedFor("fedora-44-base", trusted("tpl-mine")));
        assertThrows(IncusException.class, () -> cmd.downloadAndAliasImage("fedora-44-base",
                "https://example.com/x.tar.xz", null, "v1", projectLocal("tpl-proj", Path.of("/work/repo"))));
        // ...and a failed alias lookup is not "no such alias".
        assertThrows(IncusException.class, () -> cmd.requireImageTrustedFor("flaky", trusted("tpl-mine")));
        verify(incus, never()).deleteImage(any());
        verify(cmd, never()).newDownloadCache();
    }

    @Test
    void agentHomeOwnershipFillsSkelAndSkipsMounts(@TempDir Path tempDir) throws Exception {
        // Host resources are attached before start, so a mount under the agent home can create it
        // as root before useradd runs: useradd -m then skips skel, and chown -R would hit the
        // read-only mount. Run the real script with chown and mountpoint stubbed.
        assumeTrue(Platform.isLinux(), "runs a guest script with the host's tools, and BSD cp -n exits 1"
                + " when it leaves a file alone");
        var home = Files.createDirectories(tempDir.resolve("home"));
        var skel = Files.createDirectories(tempDir.resolve("skel"));
        Files.writeString(skel.resolve(".bashrc"), "skel");
        Files.writeString(skel.resolve(".bash_profile"), "skel");
        Files.writeString(home.resolve(".bashrc"), "mine");
        var mount = Files.createDirectories(home.resolve(".m2/ro"));
        Files.writeString(mount.resolve("hostfile"), "x");
        var bin = Files.createDirectories(tempDir.resolve("bin"));
        var chownLog = tempDir.resolve("chown.log");
        writeStub(bin.resolve("mountpoint"), "[ \"$2\" = \"" + mount + "\" ]");
        writeStub(bin.resolve("chown"), "shift; [ \"$1\" = agentuser:agentuser ] && shift; echo \"$@\" >> " + chownLog);
        // GNU coreutils 9.2 to 9.4 and BSD cp exit 1 when -n leaves a file alone, as it does
        // .bashrc here (#981): stub that, so the result does not depend on the host's cp.
        writeStub(bin.resolve("cp"), "PATH=${PATH#*:} cp \"$@\" || exit; exit 1");

        var script = BuildCommand.AGENT_HOME_OWNERSHIP
                .replace("/etc/skel", skel.toString())
                .replace("/home/agentuser", home.toString());
        var pb = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var proc = pb.start();
        var out = new String(proc.getInputStream().readAllBytes());
        assertEquals(0, proc.waitFor(), out);

        assertEquals("mine", Files.readString(home.resolve(".bashrc")), "skel clobbered a file");
        assertEquals("skel", Files.readString(home.resolve(".bash_profile")), "skel not copied");
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(home)), "home should be 0700 as useradd -m makes it");
        var chowned = Files.readString(chownLog);
        assertTrue(chowned.contains(home.resolve(".m2").toString()), chowned);
        assertTrue(chowned.contains(home.resolve(".bashrc").toString()), chowned);
        assertFalse(chowned.contains(mount.toString()), "chowned into a mount: " + chowned);
    }

    /**
     * The root expansion a VM build runs, with the guest's tools stubbed: each reports a root at
     * {@code /dev/<partition>} of {@code disk}, and the resize commands log what they were asked to grow.
     */
    private static String expandVmRoot(Path tempDir, String fsType, String disk, String partition)
            throws Exception {
        assumeTrue(Platform.isLinux(), "runs a guest script with the host's sh and grep");
        var bin = Files.createDirectories(tempDir.resolve("bin"));
        var log = tempDir.resolve("resize.log");
        writeStub(bin.resolve("findmnt"), "case \"$*\" in *SOURCE*) echo /dev/" + partition + " ;; *FSTYPE*) echo " + fsType + " ;; esac");
        writeStub(bin.resolve("lsblk"), "[ \"$2\" = PKNAME ] && [ \"$3\" = /dev/" + partition + " ] && echo " + disk);
        for (var tool : List.of("growpart", "resize2fs", "xfs_growfs")) {
            writeStub(bin.resolve(tool), "echo " + tool + " \"$@\" >> " + log);
        }
        var pb = new ProcessBuilder("sh", "-c", BuildCommand.VM_ROOT_EXPANSION).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var proc = pb.start();
        var out = new String(proc.getInputStream().readAllBytes());
        assertEquals(0, proc.waitFor(), out);
        return Files.readString(log);
    }

    @Test
    void aVmRootOnVirtioBlkIsGrownWhereItIs(@TempDir Path tempDir) throws Exception {
        // VM builds put the root on virtio-blk (#1255)
        assertEquals("growpart /dev/vda 2\nresize2fs /dev/vda2\n",
                expandVmRoot(tempDir, "ext4", "vda", "vda2"));
    }

    @Test
    void aVmRootOnVirtioScsiIsStillGrown(@TempDir Path tempDir) throws Exception {
        // Templates built before #1255 keep their root on virtio-scsi, and so do their derivations' parents
        assertEquals("growpart /dev/sda 2\nresize2fs /dev/sda2\n",
                expandVmRoot(tempDir, "ext4", "sda", "sda2"));
    }

    @Test
    void anXfsVmRootOnNvmeIsGrownToo(@TempDir Path tempDir) throws Exception {
        assertEquals("growpart /dev/nvme0n1 2\nxfs_growfs /\n",
                expandVmRoot(tempDir, "xfs", "nvme0n1", "nvme0n1p2"));
    }

    private static void writeStub(Path path, String body) throws Exception {
        Files.writeString(path, "#!/bin/sh\n" + body + "\n");
        path.toFile().setExecutable(true);
    }

    @Test
    void aNameTooLongToBuildUnderItsTemporaryNameIsRefusedBeforeAnythingIsMade() {
        var incus = mock(IncusClient.class);
        var cmd = new BuildCommand();
        cmd.incus = incus;
        var def = new ImageDef();
        // Incus would refuse '<name>-rebuilding' only at the create, after the header and the checks
        def.setName("t".repeat(BuildCommand.MAX_TEMPLATE_NAME_LENGTH + 1));

        assertThrows(BuildCommand.BuildFailedException.class, () -> cmd.buildSingleImage(def, Map.of()));
        verifyNoInteractions(incus);
    }

    // ── a project-local template's text in build's refusals (#1228) ─────────────

    /**
     * Text a cloned repository controls: an OSC title change, an 8-bit CSI (U+009B), a line
     * break that would forge a line of isx's own, and a bidi override.
     */
    private static final String HOSTILE = "\u001b]0;pwned\u0007\u009b2J\nfake: all clear\u202Egnp";

    /** {@link #HOSTILE} as a local image alias, which has no colon (a colon names a remote image). */
    private static final String HOSTILE_ALIAS = "base\u001b]0;pwned\u0007\u009b2J\nfake\u202Egnp";

    /** A project-local template named, sourced and resourced with {@link #HOSTILE} text. */
    private static ImageDef hostileProjectTemplate(Path project, ImageDef.HostResource... resources) {
        var def = new ImageDef();
        def.setName("tpl-" + HOSTILE);
        def.setSource(project.resolve(".incus-spawn/images/evil" + HOSTILE + ".yaml").toString());
        def.setProjectRoot(project);
        def.setHostResources(List.of(resources));
        return def;
    }

    /** What {@code isx build} prints refusing {@code def}, before anything reaches Incus. */
    private static String refusal(ImageDef def) {
        var incus = mock(IncusClient.class);
        var cmd = new BuildCommand();
        cmd.incus = incus;
        var out = bothStreams(() -> assertThrows(BuildCommand.BuildFailedException.class,
                () -> cmd.buildSingleImage(def, Map.of(def.getName(), def))));
        verifyNoInteractions(incus);
        return out;
    }

    /** What {@code body} prints to stdout and stderr, in one, as a terminal shows them. */
    private static String bothStreams(Runnable body) {
        var originalOut = System.out;
        var originalErr = System.err;
        var out = new java.io.ByteArrayOutputStream();
        var sink = new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8);
        System.setOut(sink);
        System.setErr(sink);
        try {
            body.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Nothing a terminal acts on, and every line break isx's own. */
    private static void assertSafe(String out) {
        assertTrue(out.contains("pwned"), "the refusal should quote the template's text:\n" + out);
        out.codePoints().filter(c -> c != '\n').forEach(c -> assertFalse(
                Character.isISOControl(c) || c >= 0x2028 && c <= 0x202E || c >= 0x2066 && c <= 0x2069,
                () -> "U+%04X printed raw:%n%s".formatted(c, out)));
        assertTrue(out.lines().noneMatch(line -> line.startsWith("fake")), "a forged line:\n" + out);
    }

    @Test
    void aHostResourceOutsideTheProjectIsRefusedInOneSafeLinePerLine(@TempDir Path tmp) throws Exception {
        var project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        var outside = Files.createDirectories(tmp.resolve("secret" + HOSTILE)).toRealPath();
        var out = refusal(hostileProjectTemplate(project,
                new ImageDef.HostResource(outside.toString(), "/home/agentuser/data", "copy")));
        assertTrue(out.contains("is project-local"), out);
        assertSafe(out);
    }

    @Test
    void aForbiddenMountTargetIsRefusedInOneSafeLinePerLine(@TempDir Path tmp) throws Exception {
        var project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        Files.createDirectories(project.resolve("data" + HOSTILE));
        var out = refusal(hostileProjectTemplate(project,
                new ImageDef.HostResource("data" + HOSTILE, "/etc/x" + HOSTILE, "readonly")));
        assertTrue(out.contains("a system directory"), out);
        assertSafe(out);
    }

    @Test
    void aBaseImageOutsideTheProjectIsRefusedInOneSafeLinePerLine(@TempDir Path tmp) throws Exception {
        var project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        var outside = Files.writeString(tmp.resolve("backup" + HOSTILE + ".tar.xz"), "private");
        var def = hostileProjectTemplate(project);
        def.setImageUrl(outside.toUri().toString());
        var out = refusal(def);
        assertTrue(out.contains("base image"), out);
        assertSafe(out);
    }

    @Test
    void aNameTooLongIsRefusedInOneSafeLinePerLine(@TempDir Path tmp) throws Exception {
        var project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        var def = hostileProjectTemplate(project);
        def.setName(def.getName() + "-" + "t".repeat(BuildCommand.MAX_TEMPLATE_NAME_LENGTH));
        var out = refusal(def);
        assertTrue(out.contains("too long to build"), out);
        assertSafe(out);
    }

    @Test
    void theClosingListOfFailedTemplatesIsOneSafeLine() {
        var out = bothStreams(() -> assertThrows(BuildCommand.BuildFailedException.class,
                () -> BuildCommand.failIfAny(List.of("tpl-ok", "tpl-" + HOSTILE))));
        assertTrue(out.contains("Some templates failed to build"), out);
        assertSafe(out);
    }

    /**
     * What {@code isx build} prints when {@code refuse} -- a check {@code buildInto} makes once the
     * build has started -- refuses {@code def}: the refusal arrives through "Build failed for".
     */
    private static String refusalDuringBuild(Path home, ImageDef def, IncusClient incus,
                                             java.util.function.Consumer<BuildCommand> refuse) {
        var out = new String[1];
        InitCommandTest.withHome(home, () -> {
            var cmd = spy(new BuildCommand());
            cmd.incus = incus;
            cmd.yes = true;
            doAnswer(inv -> {
                refuse.accept(cmd);
                return null;
            }).when(cmd).buildInto(any(), any(), anyString());
            out[0] = bothStreams(() -> assertThrows(BuildCommand.BuildFailedException.class,
                    () -> cmd.buildSingleImage(def, Map.of(def.getName(), def))));
        });
        return out[0];
    }

    @Test
    void anImageReplacementIsRefusedInOneSafeLinePerLine(@TempDir Path tmp) throws Exception {
        var project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        var def = hostileProjectTemplate(project);
        var incus = mock(IncusClient.class);
        when(incus.imageAliasTargetOrThrow(HOSTILE_ALIAS)).thenReturn("abc");

        var out = refusalDuringBuild(tmp.resolve("home"), def, incus, cmd -> cmd.downloadAndAliasImage(
                HOSTILE_ALIAS, "https://example.com/evil.tar.xz", null, "v1", def));
        assertTrue(out.contains("may only replace images it imported itself"), out);
        assertSafe(out);
    }

    @Test
    void anImageAnotherProjectImportedIsRefusedInOneSafeLinePerLine(@TempDir Path tmp) throws Exception {
        var project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        var def = hostileProjectTemplate(project);
        var incus = mock(IncusClient.class);
        when(incus.imageAliasTargetOrThrow(HOSTILE_ALIAS)).thenReturn("abc");
        when(incus.imagePropertyOrThrow("abc", BuildCommand.IMAGE_PROJECT_PROPERTY))
                .thenReturn("/work/other" + HOSTILE);

        var out = refusalDuringBuild(tmp.resolve("home"), def, incus,
                cmd -> cmd.requireImageTrustedFor(HOSTILE_ALIAS, def, null));
        assertTrue(out.contains("was imported by a project-local template"), out);
        assertSafe(out);
    }

    /** What {@code requireValidAccounts} prints after "Cannot build: ". */
    @Test
    void anUnknownAccountIsRefusedInOneSafeLine(@TempDir Path tmp) throws Exception {
        var project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        var def = hostileProjectTemplate(project);
        def.setAccounts(Map.of("claude", "acct" + HOSTILE));
        var refusal = new String[1];
        InitCommandTest.withHome(tmp.resolve("home"), () ->
                refusal[0] = BuildCommand.validateTemplateAccounts(List.of(def.getName()), Map.of(def.getName(), def)));
        assertNotNull(refusal[0]);
        assertSafe(refusal[0]);
        assertFalse(refusal[0].contains("\n"), refusal[0]);
    }

    @Test
    void unparsableDefinitionFilesAreListedOneSafeLineEach(@TempDir Path tmp) {
        var refusal = BuildCommand.unparsableDefinitionsError(
                List.of(tmp.resolve(".incus-spawn/images/evil" + HOSTILE + ".yaml")));
        assertTrue(refusal.contains("could not be loaded"), refusal);
        assertSafe(refusal);
    }

    @Test
    void conflictingDefinitionFilesAreListedOneSafeLineEach(@TempDir Path tmp) {
        var conflict = new dev.incusspawn.config.LayeredDefinitions.NameConflict("image", "tpl-" + HOSTILE,
                List.of(tmp.resolve("a" + HOSTILE + ".yaml"), tmp.resolve("b" + HOSTILE + ".yaml")));
        assertSafe(conflict.message());
        assertSafe(conflict.shortMessage());
        assertFalse(conflict.shortMessage().contains("\n"), conflict.shortMessage());
    }

    /**
     * An error isx did not build itself -- Incus quoting the instance name, say -- is printed one
     * safe line at a time. Its line breaks stay, since a forged one cannot be told from the
     * message's own once it is assembled; nothing else reaches the terminal.
     */
    @Test
    void aMidBuildErrorQuotingTheNameIsPrintedSafely(@TempDir Path tmp) throws Exception {
        var project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        var def = hostileProjectTemplate(project);
        var out = refusalDuringBuild(tmp.resolve("home"), def, mock(IncusClient.class), cmd -> {
            throw new IncusException("Invalid instance name '" + def.getName() + "-rebuilding'");
        });
        assertTrue(out.contains("Invalid instance name"), out);
        out.codePoints().filter(c -> c != '\n').forEach(c -> assertFalse(
                Character.isISOControl(c) || c >= 0x2028 && c <= 0x202E || c >= 0x2066 && c <= 0x2069,
                () -> "U+%04X printed raw:%n%s".formatted(c, out)));
    }

    /** Ctrl-C mid-build: the shutdown hook's report names the template in one safe line. */
    @Test
    void anInterruptedBuildIsReportedInOneSafeLinePerLine(@TempDir Path tmp) throws Exception {
        var project = Files.createDirectories(tmp.resolve("project")).toRealPath();
        var def = hostileProjectTemplate(project);
        var out = refusalDuringBuild(tmp.resolve("home"), def, mock(IncusClient.class), cmd -> {
            cmd.reportInterruptedBuild();
            throw new BuildCommand.BuildFailedException(def.getName());
        });
        assertTrue(out.contains("Build interrupted for tpl- ]0;pwned  2J fake: all clear gnp"), out);
        assertSafe(out);
    }

    @Test
    void strandedStorageIsRefusedInOneSafeLinePerLine() {
        var name = "tpl-" + HOSTILE;
        var incus = mock(IncusClient.class);
        when(incus.scanSubvolumes()).thenReturn(Optional.of(scan(Set.of(name), Set.of(name + "-rebuilding"))));
        var out = bothStreams(() -> assertTrue(BuildCommand.reportStrandedStorage(incus, name, name + "-rebuilding")));
        assertTrue(out.contains("Cannot build tpl- ]0;pwned"), out);
        assertSafe(out);
    }
}
