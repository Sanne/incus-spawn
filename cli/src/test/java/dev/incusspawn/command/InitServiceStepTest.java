package dev.incusspawn.command;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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
            return upgradeRestarts;
        }

        @Override
        void restartProxyService() {
            record("restart");
        }

        @Override
        boolean installProxyService() {
            record("install");
            return true;
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

    @Test
    void macOsServicesAreInstalledOnlyOnceInitIsMarkedComplete() throws IOException {
        var prompts = ScriptedPrompts.lines("y");
        init.completeWithMacOsServices(prompts);
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
        init.completeWithMacOsServices(prompts);
        prompts.assertFullyConsumed();
        assertCompleteWithoutTouchingTheService();
    }

    @Test
    void decliningTheMacOsServicesStillCompletesInit() throws IOException {
        var prompts = ScriptedPrompts.lines("n");
        init.completeWithMacOsServices(prompts);
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
        init.completeWithMacOsServices(null);
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
        Files.setPosixFilePermissions(configDir, java.util.Set.of());
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
