package dev.incusspawn.command;

import dev.incusspawn.incus.Container;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A session that started before {@code isx init}'s root script added the user to
 * {@code incus-admin} is refused by the socket until it logs in again, which an agent cannot do:
 * every isx command but init, which continues by itself, runs again under {@code sg} (#2167).
 */
class GroupContinuationTest {

    /** A host whose socket, group database, {@code sg} and own command line are given. */
    static class FakeHost extends GroupContinuation {
        boolean continued;
        boolean denied = true;
        String groups = "agent wheel incus-admin";
        String primary = "agent";
        boolean sg = true;
        List<String> commandLine = List.of("/usr/bin/isx", "mcp");
        int probes;
        final List<List<String>> ran = new ArrayList<>();
        int childExit = 0;

        @Override boolean continued() { return continued; }
        @Override boolean socketDenied() { return denied; }
        @Override List<String> configuredGroups() { probes++; return List.of(groups.split(" ")); }
        @Override String primaryGroup() { probes++; return primary; }
        @Override boolean hasSg() { return sg; }
        @Override List<String> ownCommandLine() { return commandLine; }

        @Override
        int run(List<String> command) {
            ran.add(command);
            return childExit;
        }
    }

    @Test
    void aCommandTheSocketRefusesRunsAgainUnderSgAndReturnsItsCode() {
        var host = new FakeHost();
        host.childExit = 3;
        assertEquals(3, host.continueIfNeeded(new String[]{"mcp"}));
        assertEquals(List.of(List.of("sg", "incus-admin", "-c",
                "exec sg 'agent' -c " + Container.shellQuote("exec '/usr/bin/isx' 'mcp'"))), host.ran);
    }

    @Test
    void theTuiContinuesToo() {
        var host = new FakeHost();
        host.commandLine = List.of("/usr/bin/isx");
        assertEquals(0, host.continueIfNeeded(new String[0]));
        assertEquals(1, host.ran.size());
    }

    /** Only the socket's permission is read on the way to a command that reaches Incus. */
    @Test
    void aSocketThatLetsThisProcessInCostsNoProcess() {
        var host = new FakeHost();
        host.denied = false;
        assertEquals(-1, host.continueIfNeeded(new String[]{"list"}));
        assertEquals(0, host.probes);
        assertTrue(host.ran.isEmpty());
    }

    @Test
    void nothingContinuesWhenItCannotOrNeedNotHelp() {
        var init = new FakeHost();
        var initArgs = new String[]{"init", "--no-sudo"};
        var notAMember = new FakeHost();
        notAMember.groups = "agent wheel incus-admins";
        var noSg = new FakeHost();
        noSg.sg = false;
        var alreadyContinued = new FakeHost();
        alreadyContinued.continued = true;
        var noCommandLine = new FakeHost();
        noCommandLine.commandLine = List.of();
        var unnamedPrimary = new FakeHost();
        unnamedPrimary.primary = "id: cannot find name for group ID 1000\n1000";
        assertEquals(-1, init.continueIfNeeded(initArgs));
        assertTrue(init.ran.isEmpty());
        for (var host : List.of(notAMember, noSg, alreadyContinued, noCommandLine, unnamedPrimary)) {
            assertEquals(-1, host.continueIfNeeded(new String[]{"build", "tpl-minimal"}));
            assertTrue(host.ran.isEmpty());
        }
    }

    /**
     * Where {@code sg} forks to log the session's end (Ubuntu's {@code SYSLOG_SG_ENAB}), the
     * continued isx is a grandchild: stopping the continuation stops it too, never only the
     * {@code sg} above it, and leaves what that isx started to the isx's own shutdown.
     */
    @Test
    void stoppingTheContinuationStopsWhatItsSgForkedButNotItsChildren() throws Exception {
        // The outer sh forks an inner one and waits, as a logging sg waits for its shell; the
        // inner one stands for the continued isx, and its sleep for what that isx started
        var forking = new ProcessBuilder("sh", "-c", "sh -c 'sleep 60; :'; :").start();
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        var tree = forking.descendants().toList();
        while (tree.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
            tree = forking.descendants().toList();
        }
        var descendants = tree;
        assertEquals(2, descendants.size(), "sh started no inner sh and sleep");
        var isx = forking.children().findFirst().orElseThrow();
        var started = descendants.stream().filter(p -> !p.equals(isx)).findFirst().orElseThrow();
        try {
            GroupContinuation.stopAndWait(forking.toHandle(), isx::equals, java.time.Duration.ofSeconds(10));
            assertFalse(forking.isAlive());
            assertFalse(isx.isAlive(), "left the continued isx running");
            assertTrue(started.isAlive(), "stopped what the continued isx started");
        } finally {
            descendants.forEach(ProcessHandle::destroyForcibly);
            forking.destroyForcibly();
        }
    }
}
