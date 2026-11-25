package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
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
        boolean restartProxyService() {
            record("restart");
            return !restartFails;
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
        assertFalse(box.contains("Setup complete"), box);
        assertTrue(box.contains("Setup finished without the proxy service."), box);
    }

    @Test
    void macOsServicesThatDidNotStartAreRecordedAsFailed() throws IOException {
        init.installFails = true;
        assertFalse(init.completeWithMacOsServices(ScriptedPrompts.lines("y")));
        assertEquals(Set.of("macos-services"), init.failed);
        assertEquals("the VM and proxy services", init.failedService());
        var box = BuildOutput.stripAnsi(String.join("\n", init.macOsClosingSteps(false)));
        assertFalse(box.contains("Setup complete"), box);
        assertTrue(box.contains("Setup finished without the VM and proxy services."), box);
        assertTrue(box.contains("1. Start VM, proxy:"), box);
    }

    /** A running service whose owed restart failed is down, so it is not reported as running (#2597). */
    @Test
    void aRunningLinuxProxyServiceWhoseOwedRestartFailedIsRecordedAsFailed() throws IOException {
        Files.writeString(Environment.initCompleteMarker(), String.valueOf(Environment.INIT_VERSION - 1));
        init.serviceActive = true;
        init.restartFails = true;
        assertFalse(init.completeWithProxyService(null));
        assertEquals(List.of("upgrade", "restart"), init.serviceActions);
        assertEquals("the proxy service", init.failedService());
        var box = BuildOutput.stripAnsi(String.join("\n", init.linuxClosingSteps(false, false)));
        assertFalse(box.contains("Setup complete"), box);
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
        assertEquals(1, output.events(RecordingOutput.Kind.WARN).size());
        var box = BuildOutput.stripAnsi(String.join("\n", init.linuxClosingSteps(false, false)));
        assertFalse(box.contains("Setup complete"), box);
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
