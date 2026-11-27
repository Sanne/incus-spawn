package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The last step of {@code isx init}, which installs the proxy service. {@code isx-proxy} refuses
 * to start until init is marked complete, so the service must never be installed, upgraded or
 * restarted ahead of the marker (#938).
 */
@ExtendWith(IsolatedHome.class)
class InitServiceStepTest {

    /** Records, for each thing done to the service, whether init was already marked complete. */
    static final class FakeInit extends InitCommand {
        boolean serviceActive;
        boolean macOsServicesInstalled;
        boolean upgradeRestarts;
        boolean upgradeLeavesItDown;
        boolean installFails;
        boolean restartFails;
        /** The restarted proxy exits at once: systemd still reads the unit active, nothing answers. */
        boolean restartedProxyExits;
        /** Another proxy holds the ports, so what answers is not the restarted service. */
        boolean anotherProxyAnswers;
        /** systemd waits out RestartSec to start a proxy that exited: the unit reads activating, not active. */
        boolean unitActivating;
        /** systemd stopped the restarted proxy for a configuration error (exit 78). */
        boolean configError;
        int answerWaits;
        final List<String> serviceActions = new ArrayList<>();

        private void record(String action) {
            serviceActions.add(action + (Environment.hasBeenInitialized() ? "" : " before the init marker"));
        }

        @Override
        boolean proxyServiceActive() {
            return serviceActive;
        }

        @Override
        boolean macOsServicesInstalled() {
            return macOsServicesInstalled;
        }

        @Override
        boolean upgradeProxyService() {
            record("upgrade");
            if (upgradeLeavesItDown) serviceActive = false;
            return upgradeRestarts;
        }

        @Override
        boolean restartProxyService(java.util.function.Supplier<String[]> advice) {
            record("restart");
            // As ProxyService.restart: the advice is asked only for a restart that failed
            if (restartFails) ProxyService.warnNotRestarted(BuildOutput.bound(), advice.get());
            return !restartFails;
        }

        @Override
        String[] notRestartedAdvice() {
            return ProxyService.notRestartedAdvice(false);
        }

        @Override
        boolean proxyServiceStarting() {
            return serviceActive || unitActivating;
        }

        @Override
        boolean proxyServiceAnswers() {
            answerWaits++;
            return (serviceActive || unitActivating) && !restartedProxyExits;
        }

        @Override
        ProxyService.NotAnswering proxyServiceNotAnswering() {
            return ProxyService.notAnsweringAfterRestart(configError);
        }

        @Override
        boolean answeringIsTheProxyService() {
            return !anotherProxyAnswers;
        }

        @Override
        boolean installProxyService() {
            record("install");
            return !installFails;
        }
    }

    private final FakeInit init = new FakeInit();

    /** Earlier steps of init have saved the configuration by the time this one runs. */
    @BeforeEach
    void configDirExists() throws IOException {
        Files.createDirectories(Environment.configDir());
    }

    /** The services are optional: without them init is complete all the same. */
    private void assertCompleteWithoutTouchingTheService() {
        assertEquals(List.of(), init.serviceActions);
        assertTrue(Environment.hasBeenInitialized());
    }

    /** A service that did not start is remembered, so the closing box does not tick the setup (#2597). */
    @Test
    void aLinuxProxyServiceThatDidNotStartIsRecordedAsFailed() throws IOException {
        init.installFails = true;
        assertFalse(init.completeWithProxyService(ScriptedPrompts.lines("y")));
        assertEquals(Set.of("proxy-service"), init.failed);
        assertEquals("the proxy service", init.failedService());
        var box = BuildOutput.stripAnsi(String.join("\n", init.linuxClosingSteps(false, false)));
        assertFalse(box.contains("is set up for isx"), box);
        assertTrue(box.contains("Setup finished without the proxy service."), box);
    }

    @Test
    void macOsServicesThatDidNotStartAreRecordedAsFailed() throws IOException {
        init.installFails = true;
        assertFalse(init.completeWithMacOsServices(ScriptedPrompts.lines("y")));
        assertEquals(Set.of("macos-services"), init.failed);
        assertEquals("the VM and proxy services", init.failedService());
        var box = BuildOutput.stripAnsi(String.join("\n", init.macOsClosingSteps(false)));
        assertFalse(box.contains("is set up for isx"), box);
        assertTrue(box.contains("Setup finished without the VM and proxy services."), box);
        assertTrue(box.contains("1. Start VM, proxy:"), box);
    }

    /** A running service whose owed restart failed is down, so it is not reported as running (#2597). */
    @Test
    void aRunningLinuxProxyServiceWhoseOwedRestartFailedIsRecordedAsFailed() throws IOException {
        Files.writeString(Environment.initCompleteMarker(), String.valueOf(Environment.INIT_VERSION - 1));
        init.serviceActive = true;
        init.restartFails = true;
        var output = RecordingOutput.during(() -> assertFalse(init.completeWithProxyService(null)));
        assertEquals(List.of("upgrade", "restart"), init.serviceActions);
        assertEquals("the proxy service", init.failedService());
        // The warning names where to see why and how to start it again (#2620)
        var warns = output.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warns.size());
        assertEquals(ProxyService.NOT_RESTARTED, warns.get(0).msg());
        assertEquals(List.of(ProxyService.CHECK_LOGS, ProxyService.START_AGAIN), warns.get(0).details());
        var box = BuildOutput.stripAnsi(String.join("\n", init.linuxClosingSteps(false, false)));
        assertFalse(box.contains("is set up for isx"), box);
    }

    /** An upgrade whose restart left the service down is not reported as running either (#2604). */
    @Test
    void aRunningLinuxProxyServiceThatTheUpgradeRestartLeftDownIsRecordedAsFailed() throws IOException {
        Environment.markInitComplete();
        init.serviceActive = true;
        init.upgradeRestarts = true;
        init.upgradeLeavesItDown = true;
        var output = RecordingOutput.during(() -> assertFalse(init.completeWithProxyService(null)));
        assertEquals(List.of("upgrade"), init.serviceActions);
        assertEquals("the proxy service", init.failedService());
        assertEquals(List.of(), output.events(RecordingOutput.Kind.STEP));
        var warns = output.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warns.size());
        var warn = warns.get(0);
        assertEquals(ProxyService.NOT_RESTARTED, warn.msg());
        // The warning names where to see why and how to start it again (#2620)
        assertEquals(List.of(ProxyService.CHECK_LOGS, ProxyService.START_AGAIN), warn.details());
        var box = BuildOutput.stripAnsi(String.join("\n", init.linuxClosingSteps(false, false)));
        assertFalse(box.contains("is set up for isx"), box);
    }

    /**
     * The unit reads active straight after its restart even when the new proxy exits at once, so
     * init waits for the proxy to answer before it reports the service running (#2619).
     */
    @Test
    void anUpgradeRestartWhoseProxyDoesNotAnswerIsRecordedAsFailed() throws IOException {
        Environment.markInitComplete();
        init.serviceActive = true;
        init.upgradeRestarts = true;
        init.restartedProxyExits = true;
        var output = RecordingOutput.during(() -> assertFalse(init.completeWithProxyService(null)));
        assertEquals(1, init.answerWaits);
        assertEquals("the proxy service", init.failedService());
        assertEquals(List.of(), output.events(RecordingOutput.Kind.STEP));
        assertEquals(1, output.events(RecordingOutput.Kind.WARN).size());
    }

    /** The owed restart is waited on the same way (#2619). */
    @Test
    void anOwedRestartWhoseProxyDoesNotAnswerIsRecordedAsFailed() throws IOException {
        Files.writeString(Environment.initCompleteMarker(), String.valueOf(Environment.INIT_VERSION - 1));
        init.serviceActive = true;
        init.restartedProxyExits = true;
        var output = RecordingOutput.during(() -> assertFalse(init.completeWithProxyService(null)));
        assertEquals(List.of("upgrade", "restart"), init.serviceActions);
        assertEquals(1, init.answerWaits);
        assertEquals("the proxy service", init.failedService());
        assertEquals(List.of(), output.events(RecordingOutput.Kind.STEP));
        assertEquals(1, output.events(RecordingOutput.Kind.WARN).size());
        assertEquals(1, output.events(RecordingOutput.Kind.STEP_BREAK).size());
    }

    /** A restarted proxy that stopped on a configuration error says so, not only that nothing answers (#2619). */
    @Test
    void aRestartThatStoppedOnAConfigErrorNamesIt() throws IOException {
        Environment.markInitComplete();
        init.serviceActive = true;
        init.upgradeRestarts = true;
        init.restartedProxyExits = true;
        init.configError = true;
        var output = RecordingOutput.during(() -> assertFalse(init.completeWithProxyService(null)));
        var warns = output.events(RecordingOutput.Kind.WARN);
        assertEquals(1, warns.size());
        assertTrue(warns.getFirst().msg().contains("configuration problem (exit 78)"), warns.toString());
        assertEquals("the proxy service", init.failedService());
    }

    /** The owed restart's answer is checked for another proxy too (#1570). */
    @Test
    void anOwedRestartAnsweredByAnotherProxyIsRecordedAsFailed() throws IOException {
        Files.writeString(Environment.initCompleteMarker(), String.valueOf(Environment.INIT_VERSION - 1));
        init.serviceActive = true;
        init.anotherProxyAnswers = true;
        var output = RecordingOutput.during(() -> assertFalse(init.completeWithProxyService(null)));
        assertEquals(List.of("upgrade", "restart"), init.serviceActions);
        assertEquals("the proxy service", init.failedService());
        assertEquals(List.of(), output.events(RecordingOutput.Kind.STEP));
    }

    /**
     * Read during systemd's RestartSec wait, the upgraded unit is activating, not active. That is
     * not a failure: init waits, and the proxy systemd starts again answers (#2619).
     */
    @Test
    void anUpgradedUnitThatSystemdIsStillStartingIsWaitedOn() throws IOException {
        Environment.markInitComplete();
        init.serviceActive = true;
        init.upgradeRestarts = true;
        init.upgradeLeavesItDown = true;
        init.unitActivating = true;
        var output = RecordingOutput.during(() -> assertTrue(init.completeWithProxyService(null)));
        assertEquals(1, init.answerWaits);
        assertNull(init.failedService());
        assertEquals(List.of(), output.events(RecordingOutput.Kind.WARN));
        assertEquals(1, output.events(RecordingOutput.Kind.STEP_START).size());
        assertEquals(1, output.events(RecordingOutput.Kind.STEP_DONE).size());
    }

    /** A unit the upgrade left down fails at once, without the wait for an answer. */
    @Test
    void anUpgradeRestartThatLeftTheUnitDownIsNotWaitedOn() throws IOException {
        Environment.markInitComplete();
        init.serviceActive = true;
        init.upgradeRestarts = true;
        init.upgradeLeavesItDown = true;
        assertFalse(init.completeWithProxyService(null));
        assertEquals(0, init.answerWaits);
    }

    /** An answer from another proxy on the ports is not the restarted service's (#1570). */
    @Test
    void aRestartAnsweredByAnotherProxyIsRecordedAsFailed() throws IOException {
        Environment.markInitComplete();
        init.serviceActive = true;
        init.upgradeRestarts = true;
        init.anotherProxyAnswers = true;
        var output = RecordingOutput.during(() -> assertFalse(init.completeWithProxyService(null)));
        assertEquals("the proxy service", init.failedService());
        assertEquals(List.of(), output.events(RecordingOutput.Kind.STEP));
    }

    /** The firewall step's restart is waited on too, since it makes the same unit read active (#2619). */
    @Test
    void anEarlierStepsRestartWhoseProxyDoesNotAnswerIsRecordedAsFailed() throws IOException {
        Environment.markInitComplete();
        init.serviceActive = true;
        init.proxyRestartedEarlier = true;
        init.restartedProxyExits = true;
        RecordingOutput.during(() -> assertFalse(init.completeWithProxyService(null)));
        assertEquals(1, init.answerWaits);
        assertEquals("the proxy service", init.failedService());
    }

    /** A running service that nothing restarted is not waited on: its re-run costs no wait. */
    @Test
    void aRunningServiceThatWasNotRestartedIsNotWaitedOn() throws IOException {
        Environment.markInitComplete();
        init.serviceActive = true;
        assertTrue(init.completeWithProxyService(ScriptedPrompts.lines()));
        assertEquals(0, init.answerWaits);
    }

    /** A re-run reads what is built, so a host with a template is not led to a first one (#2598). */
    @Test
    void aReRunOnAHostWithATemplateLeavesOutTheQuickStart() {
        var daemon = new FakeIncusDaemon().container("tpl-mine", Map.of(Metadata.TYPE, Metadata.TYPE_BASE));
        init.incus = daemon.client();
        var box = BuildOutput.stripAnsi(String.join("\n", init.linuxClosingSteps(false, false)));
        assertFalse(box.contains("tpl-agent"), box);
        assertTrue(box.contains("Branch a template:"), box);
        init.incus = new FakeIncusDaemon().container("my-box", Map.of(Metadata.TYPE, "")).client();
        box = BuildOutput.stripAnsi(String.join("\n", init.linuxClosingSteps(false, false)));
        assertTrue(box.contains("Define tpl-agent:"), box);
    }

    /** A project's template is one to branch too (#2598). */
    @Test
    void aReRunOnAHostWithOnlyAProjectTemplateLeavesOutTheQuickStart() {
        init.incus = new FakeIncusDaemon().container("my-project", Map.of(Metadata.TYPE, Metadata.TYPE_PROJECT)).client();
        var box = BuildOutput.stripAnsi(String.join("\n", init.linuxClosingSteps(false, false)));
        assertFalse(box.contains("tpl-agent"), box);
        assertTrue(box.contains("Branch a template:"), box);
    }

    /** A listing that cannot be read shows the Quick Start, the longer box that is never wrong (#2598). */
    @Test
    void aReRunWhoseListingFailsKeepsTheQuickStart() {
        init.incus = new FakeIncusDaemon().container("tpl-mine", Map.of(Metadata.TYPE, Metadata.TYPE_BASE))
                .failInstanceListing().client();
        var box = BuildOutput.stripAnsi(String.join("\n", init.linuxClosingSteps(false, false)));
        assertTrue(box.contains("Define tpl-agent:"), box);
    }

    @Test
    void aMacOsReRunOnAHostWithATemplateLeavesOutTheQuickStart() {
        init.incus = new FakeIncusDaemon().container("tpl-mine", Map.of(Metadata.TYPE, Metadata.TYPE_BASE)).client();
        var box = BuildOutput.stripAnsi(String.join("\n", init.macOsClosingSteps(false)));
        assertFalse(box.contains("tpl-agent"), box);
        assertTrue(box.contains("1. Start VM, proxy:"), box);
        assertTrue(box.contains("2. Branch a template:"), box);
    }

    @Test
    void anInstalledServiceIsNotRecordedAsFailed() throws IOException {
        assertTrue(init.completeWithProxyService(ScriptedPrompts.lines("y")));
        assertNull(init.failedService());
    }

    @Test
    void macOsServicesAreInstalledOnlyOnceInitIsMarkedComplete() throws IOException {
        var prompts = ScriptedPrompts.lines("y");
        assertTrue(init.completeWithMacOsServices(prompts));
        prompts.assertFullyConsumed();
        assertEquals(List.of("install"), init.serviceActions);
    }

    @Test
    void theLinuxProxyServiceIsInstalledOnlyOnceInitIsMarkedComplete() throws IOException {
        var prompts = ScriptedPrompts.lines("y");
        assertTrue(init.completeWithProxyService(prompts));
        prompts.assertFullyConsumed();
        assertEquals(List.of("install"), init.serviceActions);
    }

    /**
     * A re-run after an {@code INIT_VERSION} bump. The proxy running is the old build, and nothing
     * could restart it while the marker was stale, so it is restarted once the marker is written:
     * the restart the firewall step deferred (#1048). Read from the marker, so this holds as well
     * when the run that deferred it stopped before reaching this step.
     */
    @Test
    void aRunningLinuxProxyServiceFoundWithAStaleMarkerIsRestartedOnceInitIsMarkedComplete() throws IOException {
        Files.writeString(Environment.initCompleteMarker(), String.valueOf(Environment.INIT_VERSION - 1));
        init.serviceActive = true;
        var prompts = ScriptedPrompts.lines();
        assertTrue(init.completeWithProxyService(prompts));
        prompts.assertFullyConsumed();
        assertEquals(List.of("upgrade", "restart"), init.serviceActions);
    }

    /** One restart is enough: a second would cut every instance's connection again. */
    @Test
    void theRestartOwedIsNotRepeatedWhenTheUpgradeRestarted() throws IOException {
        Files.writeString(Environment.initCompleteMarker(), String.valueOf(Environment.INIT_VERSION - 1));
        init.serviceActive = true;
        init.upgradeRestarts = true;
        assertTrue(init.completeWithProxyService(ScriptedPrompts.lines()));
        assertEquals(List.of("upgrade"), init.serviceActions);
    }

    /** A re-run with nothing new: the firewall step restarted the proxy itself, if it had to. */
    @Test
    void aRunningLinuxProxyServiceWithACurrentMarkerIsOnlyUpgraded() throws IOException {
        Environment.markInitComplete();
        init.serviceActive = true;
        assertTrue(init.completeWithProxyService(ScriptedPrompts.lines()));
        assertEquals(List.of("upgrade"), init.serviceActions);
    }

    @Test
    void macOsServicesAlreadyInstalledAreLeftAloneAndInitCompletes() throws IOException {
        init.macOsServicesInstalled = true;
        var prompts = ScriptedPrompts.lines();
        assertTrue(init.completeWithMacOsServices(prompts));
        prompts.assertFullyConsumed();
        assertCompleteWithoutTouchingTheService();
    }

    @Test
    void decliningTheMacOsServicesStillCompletesInit() throws IOException {
        var prompts = ScriptedPrompts.lines("n");
        assertFalse(init.completeWithMacOsServices(prompts));
        prompts.assertFullyConsumed();
        assertCompleteWithoutTouchingTheService();
    }

    @Test
    void decliningTheLinuxProxyServiceStillCompletesInit() throws IOException {
        var prompts = ScriptedPrompts.lines("n");
        assertFalse(init.completeWithProxyService(prompts));
        prompts.assertFullyConsumed();
        assertCompleteWithoutTouchingTheService();
    }

    /** {@code isx init </dev/null}, as CI runs it. */
    @Test
    void initCompletesWithoutATerminal() throws IOException {
        assertFalse(init.completeWithMacOsServices(null));
        assertCompleteWithoutTouchingTheService();
    }

    @Test
    void initCompletesWithoutATerminalOnLinux() throws IOException {
        assertFalse(init.completeWithProxyService(null));
        assertCompleteWithoutTouchingTheService();
    }

    /**
     * The regression (review on #967): a marker that cannot be written must abort before the
     * service is touched, not warn and continue into a proxy that refuses to start.
     */
    @Test
    void aMarkerThatCannotBeWrittenAbortsBeforeTheServiceIsTouched() throws IOException {
        var configDir = Environment.configDir();
        Files.setPosixFilePermissions(configDir, Set.of());
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(configDir), "running as root");
            var prompts = ScriptedPrompts.lines("y");
            assertThrows(IOException.class, () -> init.completeWithMacOsServices(prompts));
            assertEquals(List.of(), init.serviceActions);
        } finally {
            Files.setPosixFilePermissions(configDir,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }
}
