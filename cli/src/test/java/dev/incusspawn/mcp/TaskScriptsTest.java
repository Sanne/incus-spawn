package dev.incusspawn.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the task scripts in a real local bash, with stand-ins for what the instance provides:
 * {@code sudo}, {@code systemd-run}/{@code systemctl} (a unit is a background process group),
 * {@code su} and a {@code claude} that speaks stream-json. Linux only: the scripts are written for
 * the guest, which has {@code setsid}, {@code /proc} and GNU {@code grep -z}, and a macOS host has
 * none of them (#1016).
 */
@EnabledOnOs(OS.LINUX)
class TaskScriptsTest {

    @TempDir
    Path home;
    private Path bin;
    private Path work;

    private static final String CLAUDE_STUB = """
            #!/bin/bash
            # Records its arguments, answers in stream-json, and edits the repository.
            prompt=$(cat)
            printf '%s\\n' "$*" >> "$HOME/claude-args"
            printf '%s\\n' "$ISX_MCP_TASK" > "$HOME/task-env"
            sid=sess-$(basename "$HOME")
            echo '{"type":"system","subtype":"init","session_id":"'$sid'","model":"stub"}'
            echo '{"type":"assistant","message":{"content":[{"type":"text","text":"Looking at it"},{"type":"tool_use","name":"Bash","input":{"command":"make test"}}]},"session_id":"'$sid'"}'
            echo "changed by: $prompt" > NEW_FILE
            echo more >> tracked.txt
            printf '{"type":"result","subtype":"success","is_error":false,"result":"Done: %s","num_turns":2,"total_cost_usd":0.01,"session_id":"%s"}\\n' "$prompt" "$sid"
            """;

    @BeforeEach
    void stubs() throws Exception {
        bin = Files.createDirectories(home.resolve("bin"));
        stub("sudo", "[ \"$1\" = -n ] && shift; exec \"$@\"");
        stub("su", "exec bash -c \"$4\"");
        stub("systemd-run", """
                unit=; while [ "$1" != -- ]; do case "$1" in --unit=*) unit=${1#--unit=};; esac; shift; done; shift
                mkdir -p "$HOME/units"; setsid "$@" < /dev/null > /dev/null 2>&1 & echo $! > "$HOME/units/$unit"
                """);
        stub("systemctl", """
                p=$(cat "$HOME/units/${@: -1}" 2>/dev/null)
                case "$1" in
                  is-active) if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then [ "$2" = -q ] || echo active; else [ "$2" = -q ] || echo inactive; exit 3; fi;;
                  stop) [ -n "$p" ] && kill -TERM -- -"$p" 2>/dev/null; sleep 0.5;;
                esac
                """);
        stub("claude", CLAUDE_STUB.substring(CLAUDE_STUB.indexOf('\n') + 1));
        work = Files.createDirectories(home.resolve("work"));
        git("init", "-q");
        git("-c", "user.email=a@b", "-c", "user.name=a", "commit", "-q", "--allow-empty", "-m", "base");
        Files.writeString(work.resolve("tracked.txt"), "one\n");
        git("add", "tracked.txt");
        git("-c", "user.email=a@b", "-c", "user.name=a", "commit", "-q", "-m", "tracked");
    }

    /** The session id the stub reports: unique per test, so a stray process of another cannot match. */
    private String sessionId() {
        return "sess-" + home.getFileName();
    }

    private void stub(String name, String body) throws IOException {
        var f = bin.resolve(name);
        Files.writeString(f, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private void git(String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        var p = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true).start();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
    }

    private String sh(String script, String stdin) throws Exception {
        return sh(script, stdin, 30);
    }

    /** {@link #sh}, failing (and killing the script) unless it ends within {@code seconds}. */
    private String sh(String script, String stdin, int seconds) throws Exception {
        var pb = new ProcessBuilder("bash", "-c", script).directory(home.toFile());
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            throw new AssertionError("still waiting after " + seconds + " s: " + script);
        }
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.exitValue(), "script failed: " + err + "\n" + script);
        return out;
    }

    private Tasks.Status awaitFinished(String id) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Tasks.Status status;
        do {
            status = Tasks.parse(sh(TaskScripts.status(id, 65536), ""));
            if (!status.running()) return status;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("task did not finish: " + status);
    }

    @Test
    void aDelegatedAgentRunsReportsAndCanBeResumed() throws Exception {
        var id = "t1-abc";
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(), Tasks.Profile.NONE, 30, "bypassPermissions")),
                "fix the 'flaky' test; don't push");
        var status = awaitFinished(id);
        assertEquals("finished", status.state());
        assertEquals(0, status.exit());
        var summary = StreamJsonEvents.summarize(status.output(), 5);
        assertTrue(summary.finished());
        assertEquals("Done: fix the 'flaky' test; don't push", summary.resultText());
        assertEquals(java.util.List.of("said: Looking at it", "ran Bash: make test"), summary.recent());

        var args = Files.readString(home.resolve("claude-args"));
        assertTrue(args.contains("--append-system-prompt You are a delegate"), args);
        assertTrue(args.contains("--max-turns 30"), args);
        assertTrue(args.contains("--permission-mode bypassPermissions"), args);
        assertEquals("t1-abc", Files.readString(home.resolve("task-env")).strip(), "the run marks what it starts");
        assertFalse(args.contains("--resume"), args);

        // The diff covers the untracked file and the uncommitted edit, against the recorded base.
        var diff = sh(TaskScripts.diff(id, null, 100_000), "");
        assertTrue(diff.contains("NEW_FILE"), diff);
        assertTrue(diff.contains("+changed by: fix the 'flaky' test"), diff);
        assertTrue(diff.contains("+more"), diff);
        var index = work.resolve(".git/index");
        assertTrue(sh("cd " + work + " && git status --porcelain", "").contains("?? NEW_FILE"),
                "the instance's own index is untouched");
        assertTrue(Files.exists(index));

        // A second turn resumes the recorded session.
        sh(TaskScripts.launch(id, 2, Tasks.AGENT, TaskScripts.agentRun(id, 2, work.toString(), Tasks.Profile.NONE, null, "bypassPermissions")),
                "now open a PR");
        var second = awaitFinished(id);
        assertEquals(2, second.run());
        assertEquals("Done: now open a PR", StreamJsonEvents.summarize(second.output(), 1).resultText());
        assertTrue(Files.readString(home.resolve("claude-args")).contains("--resume " + sessionId()));
    }

    @Test
    void aDelegateRunsUnderItsProfileAndRecordsIt() throws Exception {
        var id = "t1-abc";
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(),
                new Tasks.Profile("claude-haiku-4-5", 5), 5, "bypassPermissions")), "rebase");
        awaitFinished(id);
        var args = Files.readString(home.resolve("claude-args"));
        assertTrue(args.contains("--max-turns 5 "), args);
        assertTrue(args.contains("--model claude-haiku-4-5 "), args);
        // An adopting session learns the profile with the task.
        assertTrue(sh(TaskScripts.list(), "").startsWith("t1-abc agent 1 done claude-haiku-4-5 5 "));

        // Without one, the template's model (no --model) and the budget the session gives.
        sh(TaskScripts.launch("t2-abc", 1, Tasks.AGENT, TaskScripts.agentRun("t2-abc", 1, work.toString(),
                Tasks.Profile.NONE, 200, "bypassPermissions")), "design");
        awaitFinished("t2-abc");
        var second = Files.readString(home.resolve("claude-args")).lines().reduce((a, b) -> b).orElseThrow();
        assertFalse(second.contains("--model"), second);
        assertTrue(second.contains("--max-turns 200 "), second);
        assertTrue(sh(TaskScripts.list(), "").contains("t2-abc agent 1 done - - "));
    }

    @Test
    void aModelCheckPassesOnlyWhenTheModelAnswers() throws Exception {
        stub("claude", """
                printf '%s\\n' "$*" >> "$HOME/check-args"
                case "$*" in
                  *"--model good"*) echo '{"type":"result","subtype":"success","is_error":false,"result":"OK"}';;
                  *"--model refused"*) echo '{"type":"result","subtype":"success","is_error":true,"result":"API Error: 404 model: refused"}';;
                  *) echo 'There is an issue with the selected model (broken).' >&2; exit 1;;
                esac
                """);
        assertEquals(null, check("good"));
        var args = Files.readString(home.resolve("check-args"));
        assertTrue(args.contains("--model good") && args.contains("--max-turns 1") && args.contains("--tools "), args);
        assertTrue(check("refused").contains("API Error: 404 model: refused"));
        assertTrue(check("broken").contains("issue with the selected model"));
        Files.delete(bin.resolve("claude"));
        assertTrue(check("good").contains("no Claude Code"));
        assertTrue(ModelCheck.refusal(124, "", "").contains("no answer within"), "timeout's exit says what happened");
        assertTrue(ModelCheck.script("good").contains("timeout " + ModelCheck.TIMEOUT_SECONDS + " claude -p"),
                "a stalled API cannot hold the call");
    }

    /** {@link ModelCheck}'s verdict on a model, its script run here; without a claude, none is found. */
    private String check(String model) throws Exception {
        var pb = new ProcessBuilder("bash", "-c", ModelCheck.script(model)).directory(home.toFile());
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", bin + ":/usr/bin:/bin");
        var p = pb.start();
        p.getOutputStream().close();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        return ModelCheck.refusal(p.exitValue(), new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aPathLimitsTheDiffAndATooLargeOneReturnsOnlyTheSummary() throws Exception {
        var id = "t2-abc";
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(), Tasks.Profile.NONE, null, "bypassPermissions")), "go");
        awaitFinished(id);
        var only = sh(TaskScripts.diff(id, "tracked.txt", 100_000), "");
        assertTrue(only.contains("+more") && !only.contains("NEW_FILE"), only);
        var small = sh(TaskScripts.diff(id, null, 10), "");
        assertTrue(small.contains("(too large"), small);
        assertTrue(small.contains("tracked.txt"), "the stat summary is still there: " + small);
    }

    @Test
    void aLinkedWorktreesChangesAreInTheDiff() throws Exception {
        // A worktree's .git is a file, not a directory: .git/index does not exist there.
        git("worktree", "add", "-q", home.resolve("wt").toString());
        var wt = home.resolve("wt");
        assertTrue(Files.isRegularFile(wt.resolve(".git")));
        var id = "t9-wt";
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, wt.toString(), Tasks.Profile.NONE, null, "bypassPermissions")), "in a worktree");
        awaitFinished(id);
        var diff = sh(TaskScripts.diff(id, null, 100_000), "");
        assertTrue(diff.contains("+changed by: in a worktree"), diff);
        assertTrue(diff.contains("+more"), diff);
        assertTrue(sh("cd " + wt + " && git status --porcelain", "").contains("?? NEW_FILE"),
                "the worktree's own index is untouched");
    }

    @Test
    void aBackgroundCommandRecordsItsOutputAndExitCode() throws Exception {
        var id = "t3-abc";
        sh(TaskScripts.launch(id, 1, Tasks.COMMAND,
                TaskScripts.commandRun(id, work.toString(), Map.of("GREETING", "it's me"),
                        "echo \"$GREETING\"; echo oops >&2; exit 4")), "");
        var status = awaitFinished(id);
        assertEquals(4, status.exit());
        assertTrue(status.output().startsWith("it's me"), status.output());
        assertTrue(status.stderr().startsWith("oops"), status.stderr());
    }

    @Test
    void cancellingStopsARunningTask() throws Exception {
        var id = "t4-abc";
        sh(TaskScripts.launch(id, 1, Tasks.COMMAND,
                TaskScripts.commandRun(id, work.toString(), Map.of(), "sleep 300 & sleep 300")), "");
        assertEquals("running", Tasks.parse(sh(TaskScripts.status(id, 1000), "")).state());
        sh(TaskScripts.cancel(id), "");
        var status = Tasks.parse(sh(TaskScripts.status(id, 1000), ""));
        assertEquals("finished", status.state());
        assertEquals(143, status.exit());
    }

    @Test
    void cancellingSeveralTasksAtOnceStopsEachInOneWait() throws Exception {
        var ids = java.util.List.of("t6-abc", "t7-abc", "t8-abc");
        for (var id : ids) {
            sh(TaskScripts.launch(id, 1, Tasks.COMMAND, TaskScripts.commandRun(id, work.toString(), Map.of(), "sleep 300")), "");
        }
        var start = System.nanoTime();
        sh(TaskScripts.cancelAll(ids), "");
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "side by side, not one after another");
        for (var id : ids) assertEquals(143, Tasks.parse(sh(TaskScripts.status(id, 1000), "")).exit(), id);
    }

    @Test
    void aCancelThatFailsFailsTheBatch() throws Exception {
        recordedTask("t9-abc", Tasks.COMMAND, false);
        Files.writeString(home.resolve(".isx-mcp/tasks/t9-abc/current"), "2\n"); // run 2 never recorded an exit
        home.resolve(".isx-mcp/tasks/t9-abc").toFile().setWritable(false); // and none can be written now
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root writes anywhere");
        try {
            var p = new ProcessBuilder("bash", "-c", TaskScripts.cancelAll(java.util.List.of("t9-abc", "t10-abc")));
            p.environment().put("HOME", home.toString());
            p.environment().put("PATH", bin + ":" + System.getenv("PATH"));
            var proc = p.directory(home.toFile()).start();
            assertTrue(proc.waitFor(20, TimeUnit.SECONDS));
            assertEquals(1, proc.exitValue(), "cancel_task must not report a cancel whose exit was not recorded");
        } finally {
            home.resolve(".isx-mcp/tasks/t9-abc").toFile().setWritable(true);
        }
    }

    @Test
    void aTaskThatHasWrittenNothingYetStillHasAStatus() throws Exception {
        var d = Files.createDirectories(home.resolve(".isx-mcp/tasks/t5-abc"));
        Files.writeString(d.resolve("current"), "1\n");
        Files.writeString(d.resolve("kind"), "agent\n");
        assertEquals(1, Tasks.parse(sh(TaskScripts.status("t5-abc", 1000), "")).run());
    }

    @Test
    void theStateProbeTellsRunningFinishedAndLost() throws Exception {
        sh(TaskScripts.launch("t6-abc", 1, Tasks.COMMAND,
                TaskScripts.commandRun("t6-abc", work.toString(), Map.of(), "sleep 300")), "");
        sh(TaskScripts.launch("t7-abc", 1, Tasks.COMMAND,
                TaskScripts.commandRun("t7-abc", work.toString(), Map.of(), "true")), "");
        awaitFinished("t7-abc");
        assertEquals("t6-abc running\nt7-abc finished\nt8-abc lost\n",
                sh(TaskScripts.states(java.util.List.of("t6-abc", "t7-abc", "t8-abc")), ""));
        sh(TaskScripts.cancel("t6-abc"), "");
    }

    @Test
    void theStateProbeSaysUnknownWhenSystemdCannotBeAsked() throws Exception {
        sh(TaskScripts.launch("t10-abc", 1, Tasks.COMMAND,
                TaskScripts.commandRun("t10-abc", work.toString(), Map.of(), "sleep 300")), "");
        // As in a container where the session cannot reach systemd: no answer at all.
        stub("systemctl", "exit 1");
        assertEquals("t10-abc unknown\n", sh(TaskScripts.states(java.util.List.of("t10-abc")), ""));
        assertEquals("unknown", Tasks.parse(sh(TaskScripts.status("t10-abc", 0), "")).state(), "not lost");
        var pid = Files.readString(home.resolve("units/isx-task-t10-abc-1")).strip();
        sh("kill -TERM -- -" + pid, "");
    }

    @Test
    void theWatchProbeTellsEachStateAndWhoIsInAFinishedConversation() throws Exception {
        sh(TaskScripts.launch("t20-abc", 1, Tasks.COMMAND,
                TaskScripts.commandRun("t20-abc", work.toString(), Map.of(), "sleep 300")), "");
        sh(TaskScripts.launch("t21-abc", 1, Tasks.COMMAND,
                TaskScripts.commandRun("t21-abc", work.toString(), Map.of(), "exit 5")), "");
        awaitFinished("t21-abc");
        // Its unit ended without recording an exit: killed, or the instance restarted.
        var dead = Files.createDirectories(home.resolve(".isx-mcp/tasks/t22-abc"));
        Files.writeString(dead.resolve("kind"), "command\n");
        Files.writeString(dead.resolve("current"), "1\n");
        var ids = java.util.List.of("t20-abc", "t21-abc", "t22-abc", "t23-abc");
        assertEquals("task t20-abc 1 running\ntask t21-abc 1 finished 5\ntask t22-abc 1 lost\ntask t23-abc 0 lost\n",
                sh(TaskScripts.watch(ids), ""), "no finished agent: no presence probe");

        sh(TaskScripts.launch("t24-abc", 1, Tasks.AGENT,
                TaskScripts.agentRun("t24-abc", 1, work.toString(), Tasks.Profile.NONE, null, "plan")), "go");
        awaitFinished("t24-abc");
        var resumed = new ProcessBuilder("bash", "-c", "exec -a claude bash -c 'sleep 30; :' --resume " + sessionId())
                .directory(home.toFile()).start();
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            String watched;
            do {
                watched = sh(TaskScripts.watch(java.util.List.of("t24-abc")), "");
            } while (!watched.contains("presence resume " + resumed.pid() + " ") && System.nanoTime() < deadline);
            assertTrue(watched.startsWith("task t24-abc 1 finished 0\nsid t24-abc " + sessionId()
                    + "\ncwd t24-abc " + work.toRealPath() + "\n"), watched);
            assertTrue(watched.contains("presence resume " + resumed.pid() + " " + sessionId() + "\n"), watched);
        } finally {
            resumed.destroy();
            resumed.waitFor(5, TimeUnit.SECONDS);
        }

        // A task whose kind cannot be read after an agent is not taken for one.
        var kindless = Files.createDirectories(home.resolve(".isx-mcp/tasks/t25-abc"));
        Files.writeString(kindless.resolve("current"), "1\n");
        Files.writeString(kindless.resolve("exit-1"), "0\n");
        var both = sh(TaskScripts.watch(java.util.List.of("t24-abc", "t25-abc")), "");
        assertTrue(both.contains("task t25-abc 1 finished 0\n"), both);
        assertFalse(both.contains("sid t25-abc"), both);

        // Cancelling and then watching, in one exec: the cancel's own exit code is kept.
        sh(TaskScripts.launch("t26-abc", 1, Tasks.COMMAND,
                TaskScripts.commandRun("t26-abc", work.toString(), Map.of(), "sleep 300")), "");
        var ids26 = java.util.List.of("t26-abc");
        assertEquals("task t26-abc 1 finished 143\n", sh(TaskScripts.cancelAll(ids26, TaskScripts.watchBody(ids26)), ""));

        stub("systemctl", "exit 1"); // systemd cannot be asked: not lost, unknown
        assertEquals("task t20-abc 1 unknown\n", sh(TaskScripts.watch(java.util.List.of("t20-abc")), ""));
        var pid = Files.readString(home.resolve("units/isx-task-t20-abc-1")).strip();
        sh("kill -TERM -- -" + pid, "");
    }

    @Test
    void aTaskBeingCancelledReadsRunningUntilItsExitIsRecorded() throws Exception {
        var sleep = new ProcessBuilder("bash", "-c", "command -v sleep").start();
        var realSleep = new String(sleep.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        // The kill sweep's pause between TERM and KILL holds until the test releases it: the
        // window in which the unit is stopped and the exit is not recorded yet.
        stub("sleep", "if [ \"$1\" = 2 ]; then while [ ! -f \"$HOME/release\" ]; do " + realSleep
                + " 0.05; done; else exec " + realSleep + " \"$@\"; fi");
        var id = "t27-abc";
        var ids = java.util.List.of(id);
        sh(TaskScripts.launch(id, 1, Tasks.COMMAND, TaskScripts.commandRun(id, work.toString(), Map.of(), "sleep 300")), "");
        var pb = new ProcessBuilder("bash", "-c", TaskScripts.cancel(id)).directory(home.toFile());
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var cancel = pb.start();
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!sh("systemctl is-active isx-task-" + id + "-1 || true", "").contains("inactive")) {
                assertTrue(System.nanoTime() < deadline, "the cancel never stopped the unit");
                Thread.sleep(50);
            }
            assertFalse(Files.exists(home.resolve(".isx-mcp/tasks/" + id + "/exit-1")), "still in the window");
            assertEquals("task " + id + " 1 running\n", sh(TaskScripts.watch(ids), ""), "not lost: being cancelled");
            assertEquals("running", Tasks.parse(sh(TaskScripts.status(id, 0), "")).state());
            assertEquals(id + " running\n", sh(TaskScripts.states(ids), ""));
        } finally {
            Files.writeString(home.resolve("release"), "");
            assertTrue(cancel.waitFor(20, TimeUnit.SECONDS));
        }
        assertEquals("task " + id + " 1 finished 143\n", sh(TaskScripts.watch(ids), ""));

        assertFalse(Files.exists(home.resolve(".isx-mcp/tasks/" + id + "/cancelling-1")), "the stamp is gone");

        // A stamp a cancel that died midway left long ago, or one from the future, counts for
        // nothing: the run is lost after all. Nor is a stamp that is not a number evaluated.
        var d = Files.createDirectories(home.resolve(".isx-mcp/tasks/t28-abc"));
        Files.writeString(d.resolve("kind"), "command\n");
        Files.writeString(d.resolve("current"), "1\n");
        var now = System.currentTimeMillis() / 1000;
        var watch28 = TaskScripts.watch(java.util.List.of("t28-abc"));
        for (var stamp : java.util.List.of(String.valueOf(now - 600), String.valueOf(now + 600), "x[$(touch " + home + "/pwned)]")) {
            Files.writeString(d.resolve("cancelling-1"), stamp + "\n");
            assertEquals("task t28-abc 1 lost\n", sh(watch28, ""), stamp);
        }
        assertFalse(Files.exists(home.resolve("pwned")), "a stamp is data, never evaluated");
        Files.writeString(d.resolve("cancelling-1"), now + "\n");
        assertEquals("task t28-abc 1 running\n", sh(watch28, ""), "a fresh one counts");
    }

    @Test
    void aCancelThatFinishesBetweenTheChecksReadsFinishedNotLost() throws Exception {
        var d = Files.createDirectories(home.resolve(".isx-mcp/tasks/t29-abc"));
        Files.writeString(d.resolve("kind"), "command\n");
        Files.writeString(d.resolve("current"), "1\n"); // no unit: systemd says inactive
        Files.writeString(d.resolve("cancelling-1"), System.currentTimeMillis() / 1000 + "\n");
        // Pins the exit file being looked at once more when the stamp check fails. In the real
        // race the cancel records its exit and removes its stamp before the check's [ -f ], with
        // no command in between to hook; here the hook is date, the check's only command: it
        // records the exit and removes the stamp, and answers a time that makes the stamp it
        // already read stale, so the check fails as it would on a stamp already gone.
        stub("date", "D=$HOME/.isx-mcp/tasks/t29-abc; echo 143 > \"$D/exit-1\"; rm -f \"$D/cancelling-1\"; "
                + "echo " + (System.currentTimeMillis() / 1000 + 3600));
        assertEquals("task t29-abc 1 finished 143\n", sh(TaskScripts.watch(java.util.List.of("t29-abc")), ""));

        Files.delete(d.resolve("exit-1"));
        Files.writeString(d.resolve("cancelling-1"), System.currentTimeMillis() / 1000 + "\n");
        var status = Tasks.parse(sh(TaskScripts.status("t29-abc", 0), ""));
        assertEquals("finished", status.state(), "status() reads it the same way");
        assertEquals(143, status.exit());
    }

    @Test
    void anUnknownTaskIsLost() throws Exception {
        assertEquals("lost", Tasks.parse(sh(TaskScripts.status("t9-zzz", 1000), "")).state());
    }

    @Test
    void anAdoptingSessionFindsTheTasksAndWhereTheyWorked() throws Exception {
        sh(TaskScripts.launch("t1-abc", 1, Tasks.AGENT,
                TaskScripts.agentRun("t1-abc", 1, work.toString(), Tasks.Profile.NONE, null, "plan")), "go");
        awaitFinished("t1-abc");
        sh(TaskScripts.launch("t2-abc", 1, Tasks.COMMAND,
                TaskScripts.commandRun("t2-abc", work.toString(), Map.of(), "sleep 300")), "");
        var listing = sh(TaskScripts.list(), "");
        var physical = work.toRealPath().toString();
        assertTrue(listing.contains("t1-abc agent 1 done - - " + physical + "\n"), listing);
        assertTrue(listing.contains("t2-abc command 1 running - - " + physical + "\n"), listing);
        sh(TaskScripts.cancel("t2-abc"), "");
        assertEquals("", sh("HOME=" + home.resolve("empty") + "; " + TaskScripts.list(), ""), "no tasks, no output");
    }

    @Test
    void aStatDiffNamesTheFilesAndCountsTheirLines() throws Exception {
        sh(TaskScripts.launch("t1-abc", 1, Tasks.AGENT,
                TaskScripts.agentRun("t1-abc", 1, work.toString(), Tasks.Profile.NONE, null, "bypassPermissions")), "go");
        awaitFinished("t1-abc");
        // A name no line-based format can carry: the stat is NUL-separated, so it is read whole.
        var odd = "a\tb\n1\t0\tforged";
        Files.writeString(work.resolve(odd), "x\n");
        var diff = Diff.parse(sh(TaskScripts.diff("t1-abc", null, 100_000, true), ""), true);
        var stat = diff.text();
        assertTrue(stat.contains("1\t0\tNEW_FILE"), stat);
        assertTrue(stat.contains("1\t0\ttracked.txt"), stat);
        assertTrue(stat.contains("3 files changed, 3 insertions(+)"), stat);
        assertFalse(stat.contains("deletion"), "git leaves a zero count out: " + stat);
        assertFalse(stat.contains("+++"), "no patch: " + stat);
        // Nor can the name forge a line in the text: control characters are C-quoted there, as git does.
        assertTrue(stat.contains("1\t0\t\"a\\tb\\n1\\t0\\tforged\"\n"), stat);
        assertFalse(stat.contains("\n1\t0\tforged"), stat);
        var read = sh(TaskScripts.diffForReading("t1-abc", null), "");
        assertFalse(read.contains("\0"), "what a model reads has no NULs");
        assertTrue(read.contains("1\t0\t\"a\\tb\\n1\\t0\\tforged\"\n"), read);
        assertFalse(read.contains("\n1\t0\tforged"), read);
        assertTrue(read.contains("\n1\t0\tNEW_FILE\n") && read.contains("+more"), read);
        var files = diff.structured().path("repos").get(0).path("files");
        assertEquals(java.util.List.of("NEW_FILE", odd, "tracked.txt"),
                files.findValuesAsText("path").stream().sorted().toList(), files.toString());
    }

    @Test
    void aStatusSaysWhenTheRunStartedAndLastWroteOutput() throws Exception {
        var before = java.time.Instant.now().minusSeconds(2);
        sh(TaskScripts.launch("t2-abc", 1, Tasks.COMMAND,
                TaskScripts.commandRun("t2-abc", work.toString(), Map.of(), "echo hi")), "");
        var command = awaitFinished("t2-abc");
        assertTrue(command.startedAt() != null && !command.startedAt().isBefore(before), command.toString());
        assertTrue(command.lastActivity() != null && !command.lastActivity().isBefore(command.startedAt().minusSeconds(1)),
                command.toString());
        sh(TaskScripts.launch("t1-abc", 1, Tasks.AGENT,
                TaskScripts.agentRun("t1-abc", 1, work.toString(), Tasks.Profile.NONE, null, "bypassPermissions")), "go");
        var agent = awaitFinished("t1-abc");
        assertTrue(agent.startedAt() != null && agent.lastActivity() != null, agent.toString());
    }

    @Test
    void aPersonsClaudeOnTheConversationMakesTheTaskAttached() throws Exception {
        sh(TaskScripts.launch("t1-abc", 1, Tasks.AGENT,
                TaskScripts.agentRun("t1-abc", 1, work.toString(), Tasks.Profile.NONE, null, "bypassPermissions")), "go");
        assertEquals("finished", awaitFinished("t1-abc").state());

        // A person resumes the session from elsewhere: argv[0] is what Claude Code's is.
        var resumed = new ProcessBuilder("bash", "-c", "exec -a claude bash -c 'sleep 30; :' --resume " + sessionId())
                .directory(home.toFile()).start();
        try {
            var status = awaitAttached("t1-abc");
            assertEquals(resumed.pid(), status.attachedPid());
        } finally {
            resumed.destroy();
            resumed.waitFor(5, TimeUnit.SECONDS);
        }
        // ... or works in the task's directory, where --continue would pick the session up.
        var inPlace = new ProcessBuilder("bash", "-c", "exec -a claude sleep 30").directory(work.toFile()).start();
        try {
            assertEquals(inPlace.pid(), awaitAttached("t1-abc").attachedPid());
        } finally {
            inPlace.destroy();
            inPlace.waitFor(5, TimeUnit.SECONDS);
        }
        assertEquals("finished", Tasks.parse(sh(TaskScripts.status("t1-abc", 0), "")).state());

        // A task's own Claude Code is not a person, even in the same directory.
        var pb = new ProcessBuilder("bash", "-c", "exec -a claude sleep 30").directory(work.toFile());
        pb.environment().put(TaskScripts.TASK_ENV, "t2-abc");
        var own = pb.start();
        try {
            Thread.sleep(300);
            assertEquals("finished", Tasks.parse(sh(TaskScripts.status("t1-abc", 0), "")).state());
        } finally {
            own.destroy();
            own.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private Tasks.Status awaitAttached(String id) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Tasks.Status status;
        do {
            status = Tasks.parse(sh(TaskScripts.status(id, 0), ""));
            if (status.state().equals("attached")) return status;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("never attached: " + status);
    }

    @Test
    void askFeedsTheTextAndTheQuestionToAOneShotModel() throws Exception {
        stub("claude", """
                printf 'ARGS %s\\n' "$*"
                input=$(cat)
                printf 'READ %s BYTES\\n' "${#input}"
                case "$input" in *'Question: which tests fail?'*'<data>'*'FooTest failed'*'</data>'*) echo QUESTION_AND_DATA_OK;; esac
                """);
        var producer = ExecScript.build("exec-1", work.toString(), Map.of(), "echo 'FooTest failed' >&2; exit 3", null);
        var answer = AskScript.parse(sh(AskScript.build(producer, true, "which tests fail?", "haiku"), ""));
        assertEquals(3, answer.exit(), "the producer's exit code");
        assertEquals("1 lines, 15 bytes", answer.summarised());
        assertTrue(answer.text().contains("QUESTION_AND_DATA_OK"), answer.text());
        assertTrue(answer.text().contains("--model haiku --tools  --no-session-persistence"), answer.text());

        var empty = AskScript.parse(sh(AskScript.build("true", false, "q", "haiku"), ""));
        assertEquals("(there was nothing to summarise)", empty.text());

        stub("claude", "echo 'API error' >&2; exit 1");
        var failed = AskScript.parse(sh(AskScript.build("echo data", false, "q", "haiku"), ""));
        assertTrue(failed.text().contains("(the summary failed, exit 1: API error"), failed.text());

        var fromStdin = AskScript.parse(sh(AskScript.build("cat", false, "q", "haiku"), "a report"));
        assertEquals("0 lines, 8 bytes", fromStdin.summarised());
    }

    @Test
    void askWithoutClaudeSaysSo() throws Exception {
        Files.delete(bin.resolve("claude"));
        var script = "PATH=" + bin + ":/usr/bin:/bin; " + AskScript.build("echo data", false, "q", "haiku");
        assertTrue(AskScript.parse(sh(script, "")).text().contains("no Claude Code in this instance"));
    }

    @Test
    void cancellingReachesProcessesThatLeftTheUnit() throws Exception {
        // As PAM does to su - in a real unit: the run is no longer where stopping the unit reaches.
        sh(TaskScripts.launch("t11-abc", 1, Tasks.COMMAND, TaskScripts.commandRun("t11-abc", work.toString(), Map.of(),
                "setsid sleep 300 & echo $! > \"$HOME/escaped\"; wait")), "");
        var escaped = Path.of(home.toString(), "escaped");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(escaped) || Files.readString(escaped).isBlank()) {
            assertTrue(System.nanoTime() < deadline, "the task never started");
            Thread.sleep(50);
        }
        var pid = Long.parseLong(Files.readString(escaped).strip());
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        sh(TaskScripts.cancel("t11-abc"), "");
        var gone = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            assertTrue(System.nanoTime() < gone, "the escaped process survived the cancel");
            Thread.sleep(50);
        }
    }

    @Test
    void aRunThatFailsToStartLeavesThePreviousOneCurrent() throws Exception {
        sh(TaskScripts.launch("t12-abc", 1, Tasks.AGENT,
                TaskScripts.agentRun("t12-abc", 1, work.toString(), Tasks.Profile.NONE, null, "bypassPermissions")), "go");
        assertEquals("finished", awaitFinished("t12-abc").state());
        stub("systemd-run", "exit 1");
        var pb = new ProcessBuilder("bash", "-c", TaskScripts.launch("t12-abc", 2, Tasks.AGENT,
                TaskScripts.agentRun("t12-abc", 2, work.toString(), Tasks.Profile.NONE, null, "bypassPermissions")))
                .directory(home.toFile());
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var p = pb.start();
        p.getOutputStream().close();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertTrue(p.exitValue() != 0, "the launch reports the failure");
        var status = Tasks.parse(sh(TaskScripts.status("t12-abc", 65536), ""));
        assertEquals(1, status.run());
        assertEquals("finished", status.state(), "the first run's result is still there");
        assertTrue(StreamJsonEvents.summarize(status.output(), 0).finished());
    }

    /** A task whose current run is {@code run}, as launch leaves it, with a unit running while {@code running}. */
    private void recordedTask(String dirName, String kind, boolean running) throws Exception {
        var d = Files.createDirectories(home.resolve(".isx-mcp/tasks/" + dirName));
        Files.writeString(d.resolve("kind"), kind + "\n");
        Files.writeString(d.resolve("current"), "1\n");
        if (running) sh(TaskScripts.launch(dirName, 2, kind, "sleep 300"), "");
        Files.writeString(d.resolve("current"), running ? "2\n" : "1\n");
        if (!running) Files.writeString(d.resolve("exit-1"), "0\n");
    }

    @Test
    void theSweepSeesADelegateThatHasNotFinished() throws Exception {
        recordedTask("t11-abc", Tasks.AGENT, true);
        recordedTask("t12-abc", Tasks.AGENT, false);
        recordedTask("t14-abc", Tasks.COMMAND, true); // a dev server never finishes: not a reason to keep it
        assertEquals("task t11-abc running\n", sh(TaskScripts.unfinished(), ""));
        Files.createDirectories(home.resolve(".isx-mcp/tasks/a b*"));
        Files.writeString(home.resolve(".isx-mcp/tasks/a b*/kind"), "agent\n");
        Files.writeString(home.resolve(".isx-mcp/tasks/a b*/current"), "1\n"); // unfinished, but no task id
        stub("systemctl", "exit 1"); // systemd cannot be asked: that is not "finished"
        assertEquals("task t11-abc unknown\n", sh(TaskScripts.unfinished(), ""), "only task ids are asked about");
        for (var id : java.util.List.of("t11-abc", "t14-abc")) {
            var pid = Files.readString(home.resolve("units/isx-task-" + id + "-2")).strip();
            sh("kill -TERM -- -" + pid, "");
        }
        assertEquals("", sh("HOME=" + home.resolve("empty") + "; " + TaskScripts.unfinished(), ""), "no tasks");
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void aFifoInPlaceOfATaskFileHoldsNoProbe() throws Exception {
        // Anyone in the instance can put a FIFO there; a read of it would wait for a writer.
        recordedTask("t11-abc", Tasks.AGENT, true);
        var current = Files.createDirectories(home.resolve(".isx-mcp/tasks/t18-abc"));
        Files.writeString(current.resolve("kind"), "command\n");
        sh("mkfifo " + current.resolve("current"), "");
        var kind = Files.createDirectories(home.resolve(".isx-mcp/tasks/t19-abc"));
        Files.writeString(kind.resolve("current"), "1\n");
        sh("mkfifo " + kind.resolve("kind"), "");
        long start = System.nanoTime();
        assertEquals("task t11-abc running\n", sh(TaskScripts.busy(), ""));
        assertEquals("task t11-abc running\n", sh(TaskScripts.unfinished(), ""));
        // The watch poller reads more of a finished task: its kind, and an agent's session and cwd.
        var finishedKind = Files.createDirectories(home.resolve(".isx-mcp/tasks/t21-abc"));
        Files.writeString(finishedKind.resolve("current"), "1\n");
        Files.writeString(finishedKind.resolve("exit-1"), "0\n");
        sh("mkfifo " + finishedKind.resolve("kind"), "");
        var agent = Files.createDirectories(home.resolve(".isx-mcp/tasks/t22-abc"));
        Files.writeString(agent.resolve("current"), "1\n");
        Files.writeString(agent.resolve("exit-1"), "0\n");
        Files.writeString(agent.resolve("kind"), "agent\n");
        sh("mkfifo " + agent.resolve("session_id") + " " + agent.resolve("cwd"), "");
        var watched = sh(TaskScripts.watch(java.util.List.of("t18-abc", "t19-abc", "t21-abc", "t22-abc")), "");
        assertTrue(watched.startsWith("task t18-abc 0 lost\ntask t19-abc 1 lost\ntask t21-abc 1 finished 0\n"
                + "task t22-abc 1 finished 0\nsid t22-abc \ncwd t22-abc \n"), watched);
        assertTrue(System.nanoTime() - start < 10_000_000_000L, "no probe waited on a FIFO");
        var pid = Files.readString(home.resolve("units/isx-task-t11-abc-2")).strip();
        sh("kill -TERM -- -" + pid, "");
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void aFifoInPlaceOfATaskFileHoldsNeitherAdoptionNorStatus() throws Exception {
        // adopt_instance lists the tasks another session left; task_status reads one. Either reads
        // files anyone in the instance can replace with a FIFO, which would wait for a writer.
        var current = Files.createDirectories(home.resolve(".isx-mcp/tasks/t31-abc"));
        Files.writeString(current.resolve("kind"), "agent\n");
        sh("mkfifo " + current.resolve("current"), "");
        var agent = Files.createDirectories(home.resolve(".isx-mcp/tasks/t32-abc"));
        Files.writeString(agent.resolve("kind"), "agent\n");
        Files.writeString(agent.resolve("current"), "1\n");
        Files.writeString(agent.resolve("exit-1"), "0\n");
        for (var f : java.util.List.of("cwd", "model", "max-turns", "session_id", "events-1.jsonl", "stderr-1.log")) {
            sh("mkfifo " + agent.resolve(f), "");
        }
        var command = Files.createDirectories(home.resolve(".isx-mcp/tasks/t33-abc"));
        Files.writeString(command.resolve("kind"), "command\n");
        Files.writeString(command.resolve("current"), "1\n");
        Files.writeString(command.resolve("exit-1"), "0\n");
        sh("mkfifo " + command.resolve("stdout") + " " + command.resolve("stderr"), "");

        assertEquals("t32-abc agent 1 done - - \nt33-abc command 1 done - - \n", sh(TaskScripts.list(), "", 5));
        var status = Tasks.parse(sh(TaskScripts.status("t32-abc", 1000), "", 5));
        assertEquals("finished", status.state());
        assertEquals(0, status.exit());
        assertEquals("finished", Tasks.parse(sh(TaskScripts.status("t33-abc", 1000), "", 5)).state());
        assertEquals("lost", Tasks.parse(sh(TaskScripts.status("t31-abc", 1000), "", 5)).state());
        assertEquals("--- stdout\n\n--- stderr\n", sh(TaskScripts.output("t33-abc"), "", 5));
    }

    @Test
    void taskIdsAreReadFromTheTaskLinesAloneAndEachOnce() {
        // The output is the guest's: anything its profile prints must neither count nor fail the read.
        var lines = java.util.stream.Stream.of("task t1-a running", "task ", "task  x", "tasks t2-a running",
                "welcome!", "task t1-a unknown", "task t3-a unknown");
        assertEquals(java.util.Set.of("t1-a", "t3-a"), TaskScripts.taskIds(lines));
    }

    @Test
    void theUsersTaskCountSeesEveryRunThatHasNotFinished() throws Exception {
        recordedTask("t11-abc", Tasks.AGENT, true);
        recordedTask("t12-abc", Tasks.AGENT, false);
        recordedTask("t14-abc", Tasks.COMMAND, true); // a dev server uses the machine as much as an agent
        recordedTask("t16-abc", Tasks.COMMAND, false);
        assertEquals("task t11-abc running\ntask t14-abc running\n", sh(TaskScripts.busy(), ""));
        Files.createDirectories(home.resolve(".isx-mcp/tasks/a b*"));
        Files.writeString(home.resolve(".isx-mcp/tasks/a b*/kind"), "command\n");
        Files.writeString(home.resolve(".isx-mcp/tasks/a b*/current"), "1\n"); // unfinished, but no task id
        Files.createDirectories(home.resolve(".isx-mcp/tasks/t17-abc")); // no kind: not a task isx started
        Files.writeString(home.resolve(".isx-mcp/tasks/t17-abc/current"), "1\n");
        stub("systemctl", "exit 1"); // systemd cannot be asked: counted, as this session keeps such a slot
        assertEquals("task t11-abc unknown\ntask t14-abc unknown\n", sh(TaskScripts.busy(), ""),
                "only task ids with a kind are counted");
        for (var id : java.util.List.of("t11-abc", "t14-abc")) {
            var pid = Files.readString(home.resolve("units/isx-task-" + id + "-2")).strip();
            sh("kill -TERM -- -" + pid, "");
        }
        assertEquals("", sh("HOME=" + home.resolve("empty") + "; " + TaskScripts.busy(), ""), "no tasks");
    }

    @Test
    void clearingRemovesEveryTaskAndNothingElse() throws Exception {
        recordedTask("t11-abc", Tasks.AGENT, false);
        recordedTask("t12-abc", Tasks.COMMAND, false);
        var odd = Files.createDirectories(home.resolve(".isx-mcp/tasks/a b*"));
        var notes = Files.writeString(home.resolve(".isx-mcp/tasks/notes"), "kept\n");
        sh(TaskScripts.clear(), "");
        assertEquals("", sh(TaskScripts.list(), ""), "a fork finds no task to adopt");
        assertTrue(Files.isDirectory(odd) && Files.exists(notes), "only task ids are removed");
        sh("HOME=" + home.resolve("empty") + "; " + TaskScripts.clear(), ""); // no tasks dir at all
    }

    @Test
    void clearingFailsWhenATaskCannotBeRemoved() throws Exception {
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root removes anything");
        recordedTask("t15-abc", Tasks.AGENT, false);
        var tasks = home.resolve(".isx-mcp/tasks/t15-abc");
        tasks.toFile().setWritable(false); // a directory whose entries cannot be removed
        try {
            var p = new ProcessBuilder("bash", "-c", TaskScripts.clear()).directory(home.toFile());
            p.environment().put("HOME", home.toString());
            var proc = p.start();
            assertTrue(proc.waitFor(10, TimeUnit.SECONDS));
            assertEquals(1, proc.exitValue(), "a fork that keeps its source's task must not pass as cleared");
        } finally {
            tasks.toFile().setWritable(true);
        }
    }

    @Test
    void anExitCodeIsNeverSeenHalfWritten() throws Exception {
        // Whether a run finished is whether its exit file exists: each is written aside, then renamed.
        var scripts = java.util.List.of(
                TaskScripts.commandRun("t13-abc", work.toString(), Map.of(), "true"),
                TaskScripts.agentRun("t13-abc", 1, work.toString(), Tasks.Profile.NONE, null, "plan"),
                TaskScripts.agentRun("t13-abc", 2, work.toString(), Tasks.Profile.NONE, null, "plan"),
                TaskScripts.cancel("t13-abc"));
        var direct = java.util.regex.Pattern.compile(">\\s*\"\\$D/exit-[^\".]*\"");
        for (var script : scripts) {
            assertFalse(direct.matcher(script).find(), "an exit file written in place:\n" + script);
        }
        sh(TaskScripts.launch("t13-abc", 1, Tasks.COMMAND, scripts.get(0)), "");
        assertEquals(0, awaitFinished("t13-abc").exit());
        try (var files = Files.list(home.resolve(".isx-mcp/tasks/t13-abc"))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")), "nothing left aside");
        }
    }
}
