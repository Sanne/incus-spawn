package dev.incusspawn.command;

import dev.incusspawn.incus.Container;
import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * On a fresh host init adds the user to {@code incus-admin}, which this process does not get
 * until the next login: the rest of init runs under {@code sg} instead of stopping at Storage &
 * Network to ask for one (#1436).
 */
class InitGroupContinuationTest {

    private static final String DENIED = "Cannot access the Incus socket at /var/lib/incus/unix.socket (permission denied).";

    /** Init on a host whose group database, socket, {@code sg} and own command line are given. */
    static class GroupInit extends InitCommand {
        boolean continued;
        String connectivity = DENIED;
        String configuredGroups = "fresh incus-admin";
        String primaryGroup = "fresh";
        boolean sg = true;
        List<String> commandLine = List.of("/usr/bin/isx", "init");
        final List<List<String>> ran = new ArrayList<>();
        int childExit;
        Integer exited;

        @Override boolean continuedInGroup() { return continued; }
        @Override String incusConnectivity() { return connectivity; }
        @Override boolean hasSg() { return sg; }
        @Override List<String> ownCommandLine() { return commandLine; }

        @Override
        String captureOutput(String... command) {
            if (List.of(command).equals(List.of("id", "-gn"))) return primaryGroup;
            return command[0].equals("id") ? configuredGroups : "";
        }

        @Override
        int runContinuation(List<String> command) {
            ran.add(command);
            return childExit;
        }

        @Override
        void exit(int exitCode) {
            exited = exitCode;
        }

        @Override
        List<InitStep> credentialSteps() {
            startStep("Credential Setup");
            return List.of();
        }

        @Override
        void installGitRemoteShim() {
        }

        @Override
        void setupSshKeyPair() {
            hostSteps.add("setupSshKeyPair");
        }

        /** The host steps that ran, in order; each touches nothing, and the run shows its header. */
        final List<String> hostSteps = new ArrayList<>();
        private final HostSetup fakeHost = new HostSetup(this) {
            private void step(String name) {
                hostSteps.add(name);
            }
            @Override void checkIncusInstalled() { step("checkIncusInstalled"); }
            @Override void configureSubuidSubgid() { step("configureSubuidSubgid"); }
            @Override void initializeIncus() { step("initializeIncus"); }
            @Override void configureFirewall() { step("configureFirewall"); }
            @Override void configureMitmProxy() { step("configureMitmProxy"); }
        };

        @Override
        HostSetup host() {
            return fakeHost;
        }
    }

    /** Stands for {@code System.exit}: nothing of this process runs after it. */
    static final class Exited extends RuntimeException {}

    /** The steps init runs on Linux, through the same seams, with five finishing steps. */
    private static String runLinuxSteps(GroupInit init) {
        var finish = Collections.nCopies(5, InitStepOutputTest.step(() -> {}));
        return String.join("\n", RecordingOutput.during(() -> init.runLinuxSteps(finish)).frameLines());
    }

    /** Incus Installation hands the rest of init over, and nothing after it runs here. */
    @Test
    void incusInstallationContinuesUnderSgAndRunsNoLaterStep() {
        var init = new GroupInit() {
            @Override
            void exit(int exitCode) {
                super.exit(exitCode);
                throw new Exited();
            }
        };
        assertThrows(Exited.class, () -> runLinuxSteps(init));
        assertEquals(List.of("checkIncusInstalled"), init.hostSteps);
        assertEquals(1, init.ran.size());
        assertEquals(0, init.exited);
    }

    /** The continuation starts at User Namespace Mappings, numbered on from its parent's first step. */
    @Test
    void theContinuationStartsAfterIncusInstallation() {
        var init = new GroupInit();
        init.continued = true;
        var frames = runLinuxSteps(init);
        assertEquals(List.of("configureSubuidSubgid", "initializeIncus", "configureFirewall",
                "configureMitmProxy", "setupSshKeyPair"), init.hostSteps);
        assertTrue(init.ran.isEmpty());
        assertFalse(frames.contains("[1/12]"));
        assertTrue(frames.contains("2  User Namespace Mappings"));
        assertTrue(frames.contains("[12/12]"));
    }

    @Test
    void aGroupThisProcessLacksContinuesTheSameCommandUnderSg() {
        var init = new GroupInit();
        init.commandLine = List.of("/usr/bin/java", "-jar", "/opt/isx it's/quarkus-run.jar", "build", "tpl-dev");
        var continuation = init.groupContinuation();
        assertEquals(List.of("sg", "incus-admin", "-c"), continuation.subList(0, 3));
        // Each shell takes one quoting off: the outer sg's runs the inner sg, whose shell runs isx
        assertEquals("exec sg 'fresh' -c " + Container.shellQuote(
                        "exec '/usr/bin/java' '-jar' '/opt/isx it'\"'\"'s/quarkus-run.jar' 'build' 'tpl-dev'"),
                continuation.get(3));
    }

    @Test
    void theParentExitsWithTheContinuationsCode() {
        var init = new GroupInit();
        init.childExit = 3;
        RecordingOutput.during(init::continueInGroup);
        assertEquals(1, init.ran.size());
        assertEquals(3, init.exited);
    }

    @Test
    void aContinuationThatCannotStartLeavesInitToThisProcess() {
        var init = new GroupInit();
        init.childExit = -1;
        RecordingOutput.during(init::continueInGroup);
        assertNull(init.exited);
    }

    @Test
    void nothingContinuesWhenItCannotOrNeedNotHelp() {
        var reachable = new GroupInit();
        // What the fresh probe reports for a socket that answers: never null
        reachable.connectivity = "Incus socket at /var/lib/incus/unix.socket is now accessible — please retry.";
        var notRunning = new GroupInit();
        notRunning.connectivity = "The Incus daemon is not running (socket at /var/lib/incus/unix.socket is not accepting connections).";
        var notAMember = new GroupInit();
        notAMember.configuredGroups = "fresh wheel incus-admins";
        var noSg = new GroupInit();
        noSg.sg = false;
        var alreadyContinued = new GroupInit();
        alreadyContinued.continued = true;
        var noCommandLine = new GroupInit();
        noCommandLine.commandLine = List.of();
        var unnamedPrimary = new GroupInit();
        unnamedPrimary.primaryGroup = "id: cannot find name for group ID 1000\n1000";
        for (var init : List.of(reachable, notRunning, notAMember, noSg, alreadyContinued, noCommandLine, unnamedPrimary)) {
            assertNull(init.groupContinuation());
            RecordingOutput.during(init::continueInGroup);
            assertTrue(init.ran.isEmpty());
            assertNull(init.exited);
        }
    }

    /** The continuation picks up at the second step, with the same total: the counter reads on from the parent's. */
    @Test
    void theContinuationNumbersOnFromTheParentsStep() throws Exception {
        var init = new GroupInit();
        var ran = new ArrayList<Integer>();
        var setup = new ArrayList<InitCommand.InitStep>();
        for (int i = 1; i <= 6; i++) {
            var index = i;
            setup.add(InitStepOutputTest.step(() -> ran.add(index)));
        }
        var frames = String.join("\n", RecordingOutput.during(() ->
                init.runSteps(setup, Collections.nCopies(5, InitStepOutputTest.step(() -> {})), 1)).frameLines());
        assertEquals(List.of(2, 3, 4, 5, 6), ran);
        assertFalse(frames.contains("[1/12]"));
        assertTrue(frames.contains("[2/12+]"));
        assertTrue(frames.contains("[12/12]"));
    }
}
