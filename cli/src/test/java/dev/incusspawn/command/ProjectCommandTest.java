package dev.incusspawn.command;

import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.lifecycle.TemplateLockProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// The template swap takes a lock under the home directory
@ExtendWith(IsolatedHome.class)
class ProjectCommandTest {

    private static final String NAME = "proj";
    /** What a create builds under, swapped in as {@link #NAME} once it succeeds (#1212). */
    private static final String BUILD = NAME + "-rebuilding";
    private static final String PARENT = "tpl-java";
    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");
    private static final IncusClient.ExecResult FAILED = new IncusClient.ExecResult(1, "", "");

    /** Runs what reaches the guest the way {@code su - agentuser -c} does: one bash command line in HOME. */
    private static IncusClient incus(Path home) {
        var incus = mock(IncusClient.class);
        when(incus.execInContainer(eq(NAME), eq("agentuser"), anyString())).thenAnswer(inv -> {
            String script = inv.getArgument(2);
            var process = new ProcessBuilder("bash", "-c", script).directory(home.toFile())
                    .redirectErrorStream(true).start();
            var output = new String(process.getInputStream().readAllBytes());
            return new IncusClient.ExecResult(process.waitFor(), "", output);
        });
        return incus;
    }

    @Test
    void aMultiWordPreBuildRunsWhole(@TempDir Path home) {
        assertTrue(ProjectCommand.preBuild(incus(home), NAME, "touch built 'with space'"));

        assertTrue(Files.exists(home.resolve("built")), "the pre-build ran at all");
        assertTrue(Files.exists(home.resolve("with space")), "the pre-build ran with all its arguments");
    }

    @Test
    void aFailedPreBuildIsReportedAsFailed(@TempDir Path home) {
        assertFalse(ProjectCommand.preBuild(incus(home), NAME, "echo starting && exit 3"));
    }

    /** A parent to create from, with every command run as agentuser answered by {@code guest}. */
    private static IncusClient parentWhoseGuestAnswers(IncusClient.ExecResult guest) {
        var incus = preparedClient(false);
        doAnswer(inv -> ((String) inv.getArgument(2)).startsWith("export ANTHROPIC_API_KEY=") ? OK : guest)
                .when(incus).execInContainer(eq(BUILD), eq("agentuser"), anyString());
        return incus;
    }

    private static IncusClient preparedClient(boolean previous) {
        var daemon = new FakeIncusDaemon().container(PARENT, Map.of(Metadata.TYPE, Metadata.TYPE_BASE));
        if (previous) daemon.container(NAME, Map.of(Metadata.TYPE, Metadata.TYPE_PROJECT));
        var incus = spy(daemon.client());
        doNothing().when(incus).waitForReady(anyString(), any(), anyString(), anyMap());
        doReturn(OK).when(incus).shellExec(anyString(), any(String[].class));
        doReturn(OK).when(incus).execInContainer(anyString(), eq("agentuser"), anyString());
        doAnswer(inv -> List.of(daemon.instance(inv.getArgument(0)).path("config").path(Metadata.STATIC_IP).asText()))
                .when(incus).ipv4Addresses(anyString());
        return incus;
    }

    private static ProjectConfig config(String preBuild, String... repos) {
        var config = new ProjectConfig();
        config.setName(NAME);
        config.setParent(PARENT);
        config.setRepos(List.of(repos));
        config.setPreBuild(preBuild);
        return config;
    }

    private static int create(IncusClient incus, ProjectConfig config) {
        var create = new ProjectCommand.Create();
        create.name = NAME;
        return create.create(incus, config);
    }

    @Test
    void createSucceedsAndTagsTheTemplate() {
        var incus = parentWhoseGuestAnswers(OK);

        assertEquals(0, create(incus, config("mvn install", "https://example.com/repo.git")));
        // Stamped with the name it will have, not the one it is built under
        verify(incus).configSet(BUILD, Metadata.PROJECT, NAME);
        verify(incus).stop(BUILD);
        verify(incus).rename(BUILD, NAME);
        verify(incus, never()).delete(eq(NAME), anyBoolean());
    }

    @Test
    void projectCreateAndUpdateAuthenticateThenRevokeWithoutLosingAccountRestrictions() {
        var incus = preparedClient(false);
        incus.configSetAll(PARENT, Map.of(Metadata.accountKey("github"), "project-bot",
                Metadata.accountIdentityKey("github"), "project-user", Metadata.MCP_CALLER, "true"));
        var seeds = new java.util.ArrayList<String>();
        doAnswer(inv -> {
            String instance = inv.getArgument(0);
            Map<String, String> env = inv.getArgument(3);
            var seed = env.get(dev.incusspawn.proxy.CredentialProof.DELIVERY_ENV);
            seeds.add(seed);
            var config = incus.instanceMetadata(instance).path("config");
            assertEquals(dev.incusspawn.proxy.ProofVerifier.stamp(seed, dev.incusspawn.proxy.ProofToken.declared()),
                    config.pathText(Metadata.INSTANCE_PROOF_SHA256, ""));
            assertFalse(config.pathText(Metadata.STATIC_IP, "").isBlank());
            assertEquals("project-bot", config.pathText(Metadata.accountKey("github"), ""));
            assertEquals("project-user", config.pathText(Metadata.accountIdentityKey("github"), ""));
            assertEquals(Metadata.TYPE_PROJECT, config.pathText(Metadata.UNTRUSTED_LINEAGE, ""));
            assertEquals("", config.pathText(Metadata.MCP_CALLER, ""));
            return null;
        }).when(incus).waitForReady(anyString(), any(), anyString(), anyMap());
        doAnswer(inv -> {
            String instance = inv.getArgument(0);
            assertFalse(seeds.isEmpty(), "proof delivery precedes authenticated steps");
            assertFalse(incus.instanceMetadata(instance).path("config").pathText(Metadata.INSTANCE_PROOF_ID, "").isBlank());
            return OK;
        }).when(incus).execInContainer(anyString(), eq("agentuser"), eq("check-private-registry"));
        assertEquals(0, create(incus, config("check-private-registry", "https://github.com/example/private.git")));
        assertRevoked(incus, NAME);
        var update = new ProjectCommand.Update();
        update.name = NAME;
        assertEquals(0, update.update(incus, config("check-private-registry")));
        assertRevoked(incus, NAME);
        assertEquals(2, seeds.size());
        assertNotEquals(seeds.get(0), seeds.get(1), "each operation gets a fresh temporary grant");
        var published = incus.instanceMetadata(NAME).path("config");
        assertEquals("project-bot", published.pathText(Metadata.accountKey("github"), ""));
        assertEquals("project-user", published.pathText(Metadata.accountIdentityKey("github"), ""));
        assertEquals(Metadata.TYPE_PROJECT, published.pathText(Metadata.UNTRUSTED_LINEAGE, ""));
    }

    private static void assertRevoked(IncusClient incus, String name) {
        var instance = incus.instanceMetadata(name);
        assertEquals("Stopped", instance.pathText("status", ""));
        for (var key : List.of(Metadata.INSTANCE_PROOF_ID, Metadata.INSTANCE_PROOF_SHA256,
                Metadata.INSTANCE_SECRET_SHA256, Metadata.INSTANCE_SECRET_BOOT, Metadata.STATIC_IP)) {
            assertEquals("", instance.path("config").pathText(key, ""), key);
        }
    }

    @Test
    void failedUpdateRevokesItsProofsAndStopsTheRetainedProject() {
        var incus = preparedClient(true);
        doReturn(FAILED).when(incus).execInContainer(NAME, "agentuser", "make");
        var update = new ProjectCommand.Update();
        update.name = NAME;
        assertEquals(1, update.update(incus, config("make")));
        assertRevoked(incus, NAME);
    }

    @Test
    void aCleanupFailureStillRevokesAndStopsWithoutPublishing() {
        var incus = preparedClient(false);
        doReturn(FAILED).when(incus).execInContainer(eq(BUILD), eq("agentuser"), startsWith("export ANTHROPIC_API_KEY="));
        assertThrows(IncusException.class, () -> create(incus, config(null)));
        verify(incus).stop(BUILD);
        verify(incus, never()).rename(anyString(), anyString());
        assertFalse(incus.exists(BUILD));
    }

    @Test
    void failedReadinessDuringUpdateStillRevokesAndStops() {
        var incus = preparedClient(true);
        doThrow(new IncusException("agent not ready")).when(incus).waitForReady(eq(NAME), any(), anyString(), anyMap());
        var update = new ProjectCommand.Update();
        update.name = NAME;
        assertEquals("agent not ready", assertThrows(IncusException.class,
                () -> update.update(incus, config(null))).getMessage());
        assertRevoked(incus, NAME);
    }

    /** Created on the built-in tpl-java, deprecated by #1195: it still works, and says so. */
    @Test
    void createOnADeprecatedParentWarns() {
        var incus = parentWhoseGuestAnswers(OK);
        var config = config(null);
        config.setParent("tpl-java");
        var originalErr = System.err;
        var err = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(err, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            assertEquals(0, create(incus, config));
        } finally {
            System.setErr(originalErr);
        }
        var printed = err.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(printed.contains(NAME + " derives from tpl-java, which is deprecated"), printed);
    }

    @Test
    void aFailedCloneFailsCreateAndLeavesNoTemplate() {
        var incus = parentWhoseGuestAnswers(FAILED);

        assertEquals(1, create(incus, config(null, "https://example.com/repo.git")));
        verify(incus).delete(BUILD, true);
        verify(incus, never()).configSet(BUILD, Metadata.PROJECT, NAME);
        verify(incus, never()).rename(anyString(), anyString());
    }

    @Test
    void aFailedPreBuildFailsCreateAndLeavesNoTemplate() {
        var incus = parentWhoseGuestAnswers(FAILED);

        assertEquals(1, create(incus, config("mvn install")));
        verify(incus).delete(BUILD, true);
        verify(incus, never()).configSet(BUILD, Metadata.PROJECT, NAME);
        verify(incus, never()).rename(anyString(), anyString());
    }

    @Test
    void updateRefusesAConfigFoundForAnotherProject() {
        var incus = mock(IncusClient.class);
        when(incus.exists(NAME)).thenReturn(true);
        var other = config("rm -rf ~/proj");
        other.setName("other");
        var update = new ProjectCommand.Update();
        update.name = NAME;

        assertEquals(1, update.update(incus, other));
        verify(incus, never()).start(anyString());
        verifyNoMoreInteractions(ignoreStubs(incus));
    }

    @Test
    void anExceptionDuringCreateLeavesNoTemplate() {
        var incus = parentWhoseGuestAnswers(OK);
        doThrow(new IllegalStateException("timed out")).when(incus).waitForReady(eq(BUILD), any(), anyString(), anyMap());

        assertThrows(IllegalStateException.class, () -> create(incus, config(null)));
        // The stale one before the build, and this one after it
        verify(incus, times(2)).deleteIfExists(BUILD);
    }

    @Test
    void updateUsesAConfigNamedWithConfigWhateverItsName(@TempDir Path dir) {
        var incus = preparedClient(true);
        doReturn(FAILED).when(incus).shellExec(NAME, "which", "npm");
        var other = config("make");
        other.setName("other");
        var update = new ProjectCommand.Update();
        update.name = NAME;
        update.configPath = dir.resolve("incus-spawn.yaml");

        assertEquals(0, update.update(incus, other));
        verify(incus).execInContainer(NAME, "agentuser", "make");
    }

    @Test
    void aFailedStepShowsWhatItPrintedOnStdout() throws Exception {
        var output = InitStepOutputTest.capture(() -> assertFalse(GuestUpdate.finish(
                new IncusClient.ExecResult(1, "[ERROR] COMPILATION ERROR\n", ""), "pre-build")));

        assertTrue(output.err().contains("[ERROR] COMPILATION ERROR"), output.err());
    }

    /**
     * A parent and a {@link #NAME} built before, with every command run as agentuser answered by
     * {@code guest} whatever the instance is called: where the create builds is what is under test.
     */
    private static IncusClient previousAndGuestAnswering(IncusClient.ExecResult guest) {
        var incus = preparedClient(true);
        doAnswer(inv -> ((String) inv.getArgument(2)).startsWith("export ANTHROPIC_API_KEY=") ? OK : guest)
                .when(incus).execInContainer(anyString(), eq("agentuser"), anyString());
        return incus;
    }

    @Test
    void aFailedReCreateKeepsThePreviousTemplate() {
        var incus = previousAndGuestAnswering(FAILED);

        assertEquals(1, create(incus, config("mvn install")));
        verify(incus, never()).delete(eq(NAME), anyBoolean());
        verify(incus, never()).deleteIfExists(NAME);
        verify(incus, never()).rename(anyString(), anyString());
        verify(incus).copy(PARENT, BUILD, ProjectCommand.PROJECT_BUILD);
        verify(incus).delete(BUILD, true);
    }

    @Test
    void anExceptionDuringAReCreateKeepsThePreviousTemplate() {
        var incus = previousAndGuestAnswering(OK);
        doThrow(new IncusException("not ready")).when(incus).waitForReady(anyString(), any(), anyString(), anyMap());

        assertThrows(IncusException.class, () -> create(incus, config(null)));
        verify(incus, never()).delete(eq(NAME), anyBoolean());
        verify(incus, never()).deleteIfExists(NAME);
    }

    @Test
    void aReCreateIsSwappedInOnlyOnceBuilt() {
        var incus = previousAndGuestAnswering(OK);

        assertEquals(0, create(incus, config("make")));

        var order = inOrder(incus);
        order.verify(incus).copy(PARENT, BUILD, ProjectCommand.PROJECT_BUILD);
        order.verify(incus).execInContainer(BUILD, "agentuser", "make");
        order.verify(incus).stop(BUILD);
        order.verify(incus).deleteIfExists(NAME);
        order.verify(incus).rename(BUILD, NAME);
    }

    @Test
    void aFailedSwapKeepsTheBuild() {
        var incus = previousAndGuestAnswering(OK);
        doThrow(new IncusException("rename failed")).when(incus).rename(BUILD, NAME);

        assertThrows(IncusException.class, () -> create(incus, config(null)));
        // Only the stale one before the build: past the swap's delete, it may be the only template there is
        verify(incus, times(1)).deleteIfExists(BUILD);
    }

    @Test
    void theParentIsHeldFromItsLookupUntilItIsCopied() {
        var incus = previousAndGuestAnswering(OK);
        var heldAt = new LinkedHashMap<String, Boolean>();
        doAnswer(inv -> {
            heldAt.put("lookup", TemplateLockProbe.held(PARENT));
            return true;
        }).when(incus).exists(PARENT);
        doAnswer(inv -> {
            heldAt.put("copy", TemplateLockProbe.held(PARENT));
            return inv.callRealMethod();
        }).when(incus).copy(PARENT, BUILD, ProjectCommand.PROJECT_BUILD);
        doAnswer(inv -> {
            heldAt.put("start", TemplateLockProbe.held(PARENT));
            return inv.callRealMethod();
        }).when(incus).start(BUILD);

        assertEquals(0, create(incus, config(null)));
        // A rebuild of the parent waits from the lookup through the copy, and no longer
        assertEquals(Map.of("lookup", true, "copy", true, "start", false), heldAt);
        assertFalse(TemplateLockProbe.held(PARENT));
    }

    @Test
    void aNameTooLongToBuildAsideIsRefusedBeforeAnythingIsMade() {
        var incus = mock(IncusClient.class);
        var create = new ProjectCommand.Create();
        create.name = "p".repeat(BuildCommand.MAX_TEMPLATE_NAME_LENGTH + 1);

        assertEquals(1, create.create(incus, config(null)));
        verifyNoInteractions(incus);
    }

    @Test
    void aMissingParentFailsBeforeAnythingIsTouched() {
        var incus = mock(IncusClient.class);

        assertEquals(1, create(incus, config(null)));
        verify(incus).exists(PARENT);
        verifyNoMoreInteractions(incus);
    }
}
