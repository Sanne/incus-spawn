package dev.incusspawn.git;

import dev.incusspawn.config.GitTransferConfig;
import dev.incusspawn.util.SessionProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The bounds, progress and cleanup of a monitored Git process, driven by shell stand-ins (#1343). */
class GitProcessTest {

    private static final GitTransferConfig STALL_1S = new GitTransferConfig(Duration.ofSeconds(1), Duration.ofMinutes(1));

    private static GitProcess.Result sh(String script, GitTransferConfig limits, List<GitProcess.Progress> seen)
            throws Exception {
        return GitProcess.run(new ProcessBuilder("sh", "-c", script), null, PreparationGit.SshScope.CHECKOUT, limits, seen::add);
    }

    @Test
    void progressWrittenGraduallyIsReportedLiveAndKeepsTheTransferAlive() throws Exception {
        var seen = new CopyOnWriteArrayList<GitProcess.Progress>();
        // Three seconds in all, past the one-second stall budget, but never silent for a second.
        var result = sh("for p in 10 20 30 40 50 60 70 80 90; do printf 'Receiving objects: %s%%\\r' $p >&2; sleep 0.3; done;"
                + " echo 'Receiving objects: 100%, done.' >&2", STALL_1S, seen);
        assertEquals(GitProcess.Status.OK, result.status());
        var lines = seen.stream().map(GitProcess.Progress::line).filter(l -> l != null).distinct().toList();
        assertTrue(lines.size() >= 3, "progress seen as it happened: " + lines);
        assertTrue(lines.getFirst().startsWith("Receiving objects: "), lines.getFirst());
        assertEquals("Receiving objects: 100%, done.", result.diagnostics());
    }

    @Test
    void aSilentIdleHelperIsStoppedAsAStallAndKilled() throws Exception {
        var helper = new AtomicReference<Long>();
        long start = System.nanoTime();
        var result = GitProcess.run(new ProcessBuilder("sh", "-c", "echo started >&2; sleep 600 & wait"), null, PreparationGit.SshScope.CHECKOUT, STALL_1S, p -> {
            if (helper.get() == null) GitProcessTest.children().forEach(c -> helper.compareAndSet(null, c));
        });
        assertEquals(GitProcess.Status.STALLED, result.status());
        assertEquals("stalled: no output, I/O or CPU work for 1s", result.stopReason());
        assertEquals("started", result.diagnostics());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(10)) < 0);
        assertNoneAlive(helper.get());
    }

    @Test
    void quietButBusyLocalWorkIsNotAStall() throws Exception {
        // Like resolving deltas: no output for three times the stall budget, but using the CPU throughout.
        var result = sh("end=$(( $(date +%s) + 4 )); while [ $(date +%s) -lt $end ]; do i=0;"
                + " while [ $i -lt 2000 ]; do i=$((i+1)); done; done", STALL_1S, new CopyOnWriteArrayList<>());
        assertEquals(GitProcess.Status.OK, result.status());
    }

    @Test
    void aHelperThatWakesBrieflyButNeverProgressesIsStillAStall() throws Exception {
        // Like git-remote-http polling curl against a server that never answers: a clock tick every
        // couple of seconds (about 0.5% of a CPU), no output, no I/O and no new processes.
        var result = GitProcess.run(new ProcessBuilder("perl", "-e",
                        "while (1) { select(undef, undef, undef, 0.5); my $x = 0; $x++ for 1..200000; }"),
                null, PreparationGit.SshScope.CHECKOUT, new GitTransferConfig(Duration.ofSeconds(3), Duration.ofMinutes(1)), p -> {});
        assertEquals(GitProcess.Status.STALLED, result.status());
    }

    @Test
    void cpuCountsAsWorkOnlyAsAShareOfTheQuietSpell() {
        long minute = Duration.ofMinutes(1).toNanos();
        var before = activity(0, 1, 0, 100);
        // The measured idle git-remote-http: one 10ms tick a minute.
        assertFalse(activity(minute, 1, Duration.ofMillis(10).toNanos(), 100).busierThan(before));
        assertTrue(activity(minute, 1, Duration.ofSeconds(10).toNanos(), 100).busierThan(before));
        assertTrue(activity(minute, 1, 0, 101).busierThan(before), "any I/O is work");
    }

    @Test
    void aHelperThatExitsIsNeitherWorkNorHidesTheOthers() {
        long second = Duration.ofSeconds(1).toNanos();
        var before = new GitProcess.Activity(0, java.util.Map.of(
                1L, new GitProcess.Usage(0, 100), 2L, new GitProcess.Usage(5 * second, 500)));
        assertFalse(activity(second, 1, 0, 100).busierThan(before), "helper 2 exited");
        assertTrue(activity(second, 1, second / 2, 100).busierThan(before), "git itself is busy");
    }

    private static GitProcess.Activity activity(long at, long pid, long cpu, long io) {
        return new GitProcess.Activity(at, java.util.Map.of(pid, new GitProcess.Usage(cpu, io)));
    }

    @Test
    void psTimesParseInBothPlatformsFormats() {
        assertEquals(Duration.ofMillis(1_230).toNanos(), GitProcess.parsePsTime("0:01.23"));
        assertEquals(Duration.ofSeconds(3_723).toNanos(), GitProcess.parsePsTime("01:02:03"));
        assertEquals(Duration.ofDays(2).plusSeconds(5).toNanos(), GitProcess.parsePsTime("2-00:00:05"));
    }

    @Test
    void theTotalLimitStopsEvenABusyTransfer() throws Exception {
        var limits = new GitTransferConfig(null, Duration.ofSeconds(1));
        var result = sh("while :; do :; done", limits, new CopyOnWriteArrayList<>());
        assertEquals(GitProcess.Status.TIMED_OUT, result.status());
        assertEquals("timed out after 1s", result.stopReason());
    }

    @Test
    void interruptingTheWaitDuringABlockingReadKillsTheTreeAndReturnsPromptly() throws Exception {
        var outcome = new AtomicReference<GitProcess.Result>();
        var stillInterrupted = new AtomicReference<Boolean>();
        var helper = new AtomicReference<Long>();
        var started = new java.util.concurrent.CountDownLatch(1);
        var caller = Thread.ofPlatform().start(() -> {
            try {
                // The helper keeps the output pipe open, so a plain read would never return.
                outcome.set(GitProcess.run(new ProcessBuilder("sh", "-c", "sleep 600 & wait"), null, PreparationGit.SshScope.CHECKOUT,
                        GitTransferConfig.DEFAULT, p -> {
                            children().forEach(c -> helper.compareAndSet(null, c));
                            if (helper.get() != null) started.countDown();
                        }));
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        started.await();
        long start = System.nanoTime();
        caller.interrupt();
        caller.join(10_000);
        assertFalse(caller.isAlive(), "the wait was released");
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(8)) < 0);
        assertEquals(GitProcess.Status.INTERRUPTED, outcome.get().status());
        assertTrue(stillInterrupted.get(), "the interrupt is kept for the caller");
        assertNoneAlive(helper.get());
    }

    @Test
    void aSecondInterruptDuringTheTermWaitStillKillsAHelperThatIgnoresSigterm() throws Exception {
        var helper = new AtomicReference<Long>();
        var started = new java.util.concurrent.CountDownLatch(1);
        var caller = Thread.ofPlatform().start(() -> {
            try {
                GitProcess.run(new ProcessBuilder("sh", "-c", "trap '' TERM; sleep 600 & wait"), null, PreparationGit.SshScope.CHECKOUT,
                        GitTransferConfig.DEFAULT, p -> {
                            children().forEach(c -> helper.compareAndSet(null, c));
                            if (helper.get() != null) started.countDown();
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        started.await();
        caller.interrupt();
        Thread.sleep(500); // inside the two-second wait after SIGTERM
        caller.interrupt();
        caller.join(15_000);
        assertFalse(caller.isAlive(), "the wait was released");
        assertNoneAlive(helper.get());
    }

    @Test
    void theSshCommandCheckReadsGitConfigWithTheEnvironmentGitRunsWith(@TempDir Path repo) throws Exception {
        GitTestUtils.git(repo, "init", "-q");
        var injected = new java.util.HashMap<>(System.getenv());
        injected.keySet().removeIf(k -> k.startsWith("GIT_"));
        injected.putAll(java.util.Map.of("GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "core.sshCommand",
                "GIT_CONFIG_VALUE_0", "ssh -i work_key"));
        var plain = new java.util.HashMap<>(injected);
        plain.keySet().removeIf(k -> k.startsWith("GIT_CONFIG_"));
        PreparationGit.nonInteractiveSsh(injected, repo, PreparationGit.SshScope.CHECKOUT);
        assertEquals("ssh -o BatchMode=yes -i work_key", injected.get("GIT_SSH_COMMAND"), "the injected sshCommand, without prompts");

        PreparationGit.nonInteractiveSsh(plain, repo, PreparationGit.SshScope.CHECKOUT);
        assertEquals("ssh -o BatchMode=yes", plain.get("GIT_SSH_COMMAND"));
    }

    @Test
    void runsWithoutAnyWayToPrompt() throws Exception {
        var result = GitProcess.run(new ProcessBuilder("sh", "-c",
                        "read line; echo \"read=$? prompt=$GIT_TERMINAL_PROMPT ssh=$GIT_SSH_COMMAND\" >&2"),
                null, PreparationGit.SshScope.CHECKOUT, STALL_1S, p -> {});
        assertEquals(GitProcess.Status.OK, result.status());
        assertEquals("read=1 prompt=0 ssh=ssh -o BatchMode=yes", result.diagnostics());
    }

    @Test
    void psReportsTheCpuTimesOfAProcessTree() throws Exception {
        // The macOS path: there the JDK reports CPU time only for its own process.
        var helper = new ProcessBuilder("sleep", "30").start();
        try {
            var times = GitProcess.psCpuNanos(List.of(ProcessHandle.current(), helper.toHandle()));
            assertNotNull(times);
            assertEquals(java.util.Set.of(ProcessHandle.current().pid(), helper.pid()), times.keySet());
            assertTrue(times.get(ProcessHandle.current().pid()) > 0, "this JVM has used the CPU");
        } finally {
            helper.destroyForcibly();
        }
    }

    @Test
    void aRepositorysOwnSshCommandKeepsItsOptionsButCannotPrompt(@TempDir Path repo) throws Exception {
        GitTestUtils.git(repo, "init", "-q");
        GitTestUtils.git(repo, "config", "core.sshCommand", "ssh -i work_key");
        var result = GitProcess.run(new ProcessBuilder("sh", "-c", "echo \"ssh=${GIT_SSH_COMMAND-unset}\" >&2"),
                repo, PreparationGit.SshScope.CHECKOUT, STALL_1S, p -> {});
        assertEquals("ssh=ssh -o BatchMode=yes -i work_key", result.diagnostics());
    }

    @Test
    void crlfEndsALineWhileABareCrIsAProgressUpdate() throws Exception {
        var seen = new CopyOnWriteArrayList<GitProcess.Progress>();
        var result = sh("printf 'Receiving objects:  50%%\\rReceiving objects: 100%%, done.\\n"
                + "warning: one\\r\\nfatal: two\\r\\nReceiving objects:  9%%\\r' >&2", STALL_1S, seen);
        assertEquals(List.of("Receiving objects: 100%, done.", "warning: one", "fatal: two", "Receiving objects:  9%"),
                result.diagnostics().lines().toList());
    }

    @Test
    void diagnosticsAreBoundedAndFreeOfTerminalEscapes() throws Exception {
        var long_ = "x".repeat(5000);
        var result = sh("i=0; while [ $i -lt 5000 ]; do echo \"line $i\" >&2; i=$((i+1)); done;"
                + " printf '\\033[31mremote: red\\033[0m\\033[K\\n' >&2; echo '" + long_ + "' >&2; exit 3",
                STALL_1S, new CopyOnWriteArrayList<>());
        assertEquals(GitProcess.Status.FAILED, result.status());
        var lines = result.diagnostics().lines().toList();
        assertEquals(GitProcess.TAIL_LINES, lines.size());
        assertEquals("line 4982", lines.getFirst());
        assertEquals("remote: red", lines.get(lines.size() - 2));
        assertEquals(GitProcess.MAX_LINE, lines.getLast().length());
    }

    /**
     * A build's Git leads a session of its own: the terminal's Ctrl+C, which may be a copy, reaches isx
     * alone, and isx decides (#1425). A session leader leads its process group, which ps shows everywhere.
     */
    @Test
    void gitThatCannotPromptIsOutOfTheTerminalsProcessGroup() throws Exception {
        SessionProbe.assumeSession();
        var result = GitProcess.run(new ProcessBuilder(SessionProbe.SH).redirectErrorStream(true), null, true, STALL_1S, p -> {});
        assertEquals(GitProcess.Status.OK, result.status(), result.diagnostics());
        assertTrue(SessionProbe.leadsItsGroup(result.diagnostics()),
                "Git shares the process group of isx, so Ctrl+C reaches it: " + result.diagnostics());
    }

    /** update-all runs the user's own SSH command as it is, which may ask on the terminal: it stays there. */
    @Test
    void gitThatMayPromptKeepsTheTerminal(@TempDir Path repo) throws Exception {
        var result = GitProcess.run(new ProcessBuilder("sh", "-c", "ps -o pgid= -p $$").redirectErrorStream(true),
                repo, false, STALL_1S, p -> {});
        assertEquals(GitProcess.Status.OK, result.status(), result.diagnostics());
        assertEquals(SessionProbe.look(ProcessHandle.current().pid()).processGroup(), result.diagnostics().strip());
    }

    private static java.util.stream.Stream<Long> children() {
        return ProcessHandle.current().descendants()
                .filter(p -> p.info().command().map(c -> c.endsWith("sleep")).orElse(false))
                .map(ProcessHandle::pid);
    }

    private static void assertNoneAlive(Long pid) throws InterruptedException {
        assertNotNull(pid, "the helper was seen");
        for (int i = 0; i < 50 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(100);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "helper " + pid + " survived");
    }
}
