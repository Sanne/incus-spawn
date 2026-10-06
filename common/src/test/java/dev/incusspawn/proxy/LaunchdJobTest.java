package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Restarting the proxy's launchd job against a launchd that behaves like the real one where it
 * matters (#916): {@code bootout} returns before the job is gone, a {@code bootstrap} in that
 * window fails with 5, and {@code print} goes on finding the job until the teardown is over.
 * The exit codes and messages are the ones a Mac gave.
 */
class LaunchdJobTest {

    private static final String DOMAIN = "gui/501";
    private static final String TARGET = DOMAIN + "/dev.incusspawn.proxy";
    private static final Path PLIST = Path.of("/Users/someone/Library/LaunchAgents/dev.incusspawn.proxy.plist");

    private static final class FakeLaunchd implements LaunchdJob.Launchctl {
        boolean loaded;
        boolean running;
        /** How many more {@code print}s find a job that has been booted out; negative: for ever. */
        int printsUntilUnloaded = 3;
        /** A plist launchd will not load, whatever the state of the job. */
        boolean plistRejected;
        /** The program exits right after it was spawned, as the proxy does without its VM. */
        boolean exitsAtOnce;
        /** What {@code kickstart} answers for a job that is loaded and not being unloaded. */
        int kickstartExit = 0;

        private int pid = 100;
        private boolean seenRunning;

        private boolean unloading;
        private int teardownPrintsLeft;
        final List<String> calls = new ArrayList<>();

        @Override
        public LaunchdJob.Result run(String... args) {
            calls.add(String.join(" ", args));
            return switch (args[0]) {
                case "print" -> print();
                case "bootout" -> bootout();
                case "bootstrap" -> bootstrap();
                case "kickstart" -> kickstart(args[1].equals("-k"));
                default -> throw new AssertionError("unexpected launchctl " + args[0]);
            };
        }

        private LaunchdJob.Result print() {
            if (unloading) {
                if (teardownPrintsLeft == 0) {
                    unloading = false;
                    loaded = false;
                } else {
                    if (teardownPrintsLeft > 0) teardownPrintsLeft--;
                    return new LaunchdJob.Result(0, TARGET + " = {\n\tstate = SIGTERMed\n}\n");
                }
            }
            if (!loaded) {
                return new LaunchdJob.Result(113,
                        "Could not find service \"dev.incusspawn.proxy\" in domain for user gui: 501\n");
            }
            // launchd shows a program that is about to exit as running, until it has exited.
            if (running && exitsAtOnce && seenRunning) running = false;
            seenRunning = running;
            return new LaunchdJob.Result(0, TARGET + " = {\n\tstate = "
                    + (running ? "running\n\tpid = " + pid : "spawn scheduled")
                    + "\n\tendpoints = {\n\t\tstate = active\n\t}\n}\n");
        }

        private void spawn() {
            pid++;
            running = true;
            seenRunning = false;
        }

        private LaunchdJob.Result bootout() {
            if (!loaded) return new LaunchdJob.Result(5, "Boot-out failed: 5: Input/output error\n");
            if (!unloading) teardownPrintsLeft = printsUntilUnloaded;
            unloading = true;
            running = false;
            return new LaunchdJob.Result(0, "");
        }

        private LaunchdJob.Result bootstrap() {
            // Both a job that is still there and a plist launchd rejects answer with the same 5.
            if (loaded || plistRejected) {
                return new LaunchdJob.Result(5, "Bootstrap failed: 5: Input/output error\n");
            }
            loaded = true;
            spawn();
            return new LaunchdJob.Result(0, "");
        }

        private LaunchdJob.Result kickstart(boolean kill) {
            if (!loaded) return new LaunchdJob.Result(113, "");
            if (unloading) return new LaunchdJob.Result(37, "");
            if (kickstartExit != 0) return new LaunchdJob.Result(kickstartExit, "");
            if (kill || !running) spawn();
            return new LaunchdJob.Result(0, "");
        }

        long count(String verb) {
            return calls.stream().filter(c -> c.startsWith(verb + " ")).count();
        }
    }

    private final FakeLaunchd launchd = new FakeLaunchd();
    private final List<String> log = new ArrayList<>();
    private int pauses;
    private final LaunchdJob job =
            new LaunchdJob(launchd, () -> pauses++, DOMAIN, "dev.incusspawn.proxy", PLIST);

    private void aRunningJob() {
        launchd.loaded = true;
        launchd.running = true;
        launchd.seenRunning = true;
    }

    @Test
    void restartReloadsAJobWhoseBootoutIsStillInProgress() {
        aRunningJob();

        assertEquals(LaunchdJob.Outcome.RUNNING, job.restart(true, log::add), log.toString());

        assertTrue(launchd.loaded && launchd.running, "the job must end up loaded and running");
        assertEquals(1, launchd.count("bootstrap"), "bootstrap is tried once, after the teardown: " + launchd.calls);
        assertEquals(List.of(), log);
    }

    @Test
    void restartDoesNotReportSuccessWhenBootstrapFailed() {
        aRunningJob();
        launchd.plistRejected = true;

        assertEquals(LaunchdJob.Outcome.FAILED, job.restart(true, log::add));

        assertFalse(launchd.loaded);
        assertEquals(List.of("launchctl bootstrap failed (exit 5): Bootstrap failed: 5: Input/output error"), log);
    }

    @Test
    void restartingAJobThatIsNotLoadedLoadsAndStartsIt() {
        assertEquals(LaunchdJob.Outcome.RUNNING, job.restart(false, log::add), log.toString());

        assertTrue(launchd.loaded && launchd.running);
        assertEquals(0, launchd.count("bootout"));
        assertEquals(List.of("bootstrap " + DOMAIN + " " + PLIST, "kickstart " + TARGET),
                launchd.calls.stream().filter(c -> !c.startsWith("print ")).toList());
    }

    /** No unload, so nothing to race: launchd's own restart verb. */
    @Test
    void aLoadedJobWithAnUnchangedPlistIsRestartedInPlace() {
        aRunningJob();

        assertEquals(LaunchdJob.Outcome.RUNNING, job.restart(false, log::add), log.toString());

        assertEquals(List.of("kickstart -k " + TARGET),
                launchd.calls.stream().filter(c -> !c.startsWith("print ")).toList());
    }

    /** `isx proxy stop` on a proxy that will not exit, then a restart before launchd has killed it. */
    @Test
    void aJobSomeoneElseIsUnloadingIsLoadedAgainOnceItIsGone() {
        aRunningJob();
        launchd.run("bootout", TARGET);

        assertEquals(LaunchdJob.Outcome.RUNNING, job.restart(false, log::add), log.toString());

        assertTrue(launchd.loaded && launchd.running);
        assertEquals(1, launchd.count("bootout"), "it is going already: no second bootout");
        assertEquals(List.of(), log);
    }

    /** Only "being unloaded" is answered by loading afresh; anything else must not unload a loaded job. */
    @Test
    void aRefusedRestartLeavesTheJobLoaded() {
        aRunningJob();
        launchd.kickstartExit = 1;

        assertEquals(LaunchdJob.Outcome.FAILED, job.restart(false, log::add));

        assertTrue(launchd.loaded, "the job was loaded and still is");
        assertEquals(0, launchd.count("bootout"));
        assertEquals(List.of("launchctl kickstart failed (exit 1)."), log);
    }

    /** #969: a timed-out wait for health says whether launchd is still retrying or the job exited. */
    @Test
    void lastRunReportsTheJobsStateRunsAndLastExitCode() {
        var waiting = new LaunchdJob((String... args) -> new LaunchdJob.Result(0, TARGET + """
                 = {
                \tactive count = 0
                \tpath = /Users/someone/Library/LaunchAgents/dev.incusspawn.proxy.plist
                \tstate = spawn scheduled
                \truns = 1
                \tlast exit code = 1
                \tendpoints = {
                \t\tstate = active
                \t}
                }
                """), () -> {}, DOMAIN, "dev.incusspawn.proxy", PLIST);

        assertEquals("launchd: state = spawn scheduled, runs = 1, last exit code = 1", waiting.lastRun());
        assertNull(job.lastRun(), "a job launchd does not know has nothing to report");
    }

    /** Pinned separately from FAILED (review on #916): installMacOs reports this one differently. */
    @Test
    void restartGivesUpOnAJobThatNeverFinishesUnloading() {
        aRunningJob();
        launchd.printsUntilUnloaded = -1;

        assertEquals(LaunchdJob.Outcome.STILL_UNLOADING, job.restart(true, log::add));

        assertEquals(0, launchd.count("bootstrap"), "loading it again could only fail");
        assertEquals(LaunchdJob.UNLOAD_POLLS, pauses);
        assertEquals(List.of("launchd is still unloading the proxy service; not loading it again."), log);
    }

    /**
     * {@code launchctl} succeeding is not enough, and neither is one look at the job: launchd
     * shows a proxy that is about to exit as running. It has to still be there a moment later.
     */
    @Test
    void aJobThatDoesNotStayUpIsNotReportedAsRestarted() {
        aRunningJob();
        launchd.exitsAtOnce = true;

        assertEquals(LaunchdJob.Outcome.FAILED, job.restart(false, log::add));
        assertTrue(job.isLoaded(), "it is loaded all the same, so launchd will try again");
    }

    /** start() only ever reaches a job that is not loaded (review on #916: see LaunchdJob#start). */
    @Test
    void startLoadsAJobThatIsNotLoaded() {
        assertEquals(LaunchdJob.Outcome.RUNNING, job.start(log::add), log.toString());

        assertEquals(1, launchd.count("bootstrap"));
        assertTrue(launchd.loaded && launchd.running);
    }
}
