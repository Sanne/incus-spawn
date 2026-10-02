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
        void upgradeProxyService() {
            record("upgrade");
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
    void macOsServicesAreInstalledOnlyOnceInitIsMarkedComplete() {
        var prompts = ScriptedPrompts.lines("y");
        init.completeWithMacOsServices(prompts);
        prompts.assertFullyConsumed();
        assertEquals(List.of("install"), init.serviceActions);
    }

    @Test
    void theLinuxProxyServiceIsInstalledOnlyOnceInitIsMarkedComplete() {
        var prompts = ScriptedPrompts.lines("y");
        assertTrue(init.completeWithProxyService(prompts));
        prompts.assertFullyConsumed();
        assertEquals(List.of("install"), init.serviceActions);
    }

    /** A re-run after an {@code INIT_VERSION} bump: the upgrade may restart the proxy. */
    @Test
    void aRunningLinuxProxyServiceIsUpgradedOnlyOnceInitIsMarkedComplete() {
        init.serviceActive = true;
        var prompts = ScriptedPrompts.lines();
        assertTrue(init.completeWithProxyService(prompts));
        prompts.assertFullyConsumed();
        assertEquals(List.of("upgrade"), init.serviceActions);
    }

    @Test
    void macOsServicesAlreadyInstalledAreLeftAloneAndInitCompletes() {
        init.macOsServicesInstalled = true;
        var prompts = ScriptedPrompts.lines();
        init.completeWithMacOsServices(prompts);
        prompts.assertFullyConsumed();
        assertCompleteWithoutTouchingTheService();
    }

    @Test
    void decliningTheMacOsServicesStillCompletesInit() {
        var prompts = ScriptedPrompts.lines("n");
        init.completeWithMacOsServices(prompts);
        prompts.assertFullyConsumed();
        assertCompleteWithoutTouchingTheService();
    }

    @Test
    void decliningTheLinuxProxyServiceStillCompletesInit() {
        var prompts = ScriptedPrompts.lines("n");
        assertFalse(init.completeWithProxyService(prompts));
        prompts.assertFullyConsumed();
        assertCompleteWithoutTouchingTheService();
    }

    /** {@code isx init </dev/null}, as CI runs it. */
    @Test
    void initCompletesWithoutATerminal() {
        init.completeWithMacOsServices(null);
        assertCompleteWithoutTouchingTheService();
    }

    @Test
    void initCompletesWithoutATerminalOnLinux() {
        assertFalse(init.completeWithProxyService(null));
        assertCompleteWithoutTouchingTheService();
    }
}
