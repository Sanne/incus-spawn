package dev.incusspawn.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            # A resume Claude Code cannot find: no init, and a made-up ID in the result.
            case $prompt in *RESUME-FAILS*) echo '{"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":0,"session_id":"made-up-1"}'; exit 1;; esac
            echo '{"type":"system","subtype":"init","session_id":"'$sid'","model":"stub"}'
            # Cut off before Claude Code wrote the session's transcript.
            case $prompt in *NO-TRANSCRIPT*) exec sleep 300;; esac
            mkdir -p "$HOME/.claude/projects/-work" && : >> "$HOME/.claude/projects/-work/$sid.jsonl"
            case $prompt in *KEEP-WORKING*) exec sleep 300;; esac
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
                  is-active) shift; q=; [ "$1" = -q ] && { q=1; shift; }; rc=0
                    for u in "$@"; do p=$(cat "$HOME/units/$u" 2>/dev/null)
                      if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then [ -n "$q" ] || echo active; else [ -n "$q" ] || echo inactive; rc=3; fi
                    done; exit $rc;;
                  list-units) for f in "$HOME"/units/*; do [ -f "$f" ] || continue; u=${f##*/}
                      case $u in ${@: -1}) kill -0 "$(cat "$f")" 2>/dev/null && echo "$u.service loaded active running";; esac; done; true;;
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
        // No detached auto maintenance racing @TempDir cleanup (#1473).
        cmd.addAll(java.util.List.of("git", "-c", "gc.auto=0", "-c", "maintenance.auto=false"));
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
        // Pi's session locations: the default one under this test's home.
        pb.environment().remove("PI_CODING_AGENT_DIR");
        pb.environment().remove("PI_CODING_AGENT_SESSION_DIR");
        var outFile = Files.createTempFile(home, "script-out-", ".txt");
        var errFile = Files.createTempFile(home, "script-err-", ".txt");
        pb.redirectOutput(outFile.toFile()).redirectError(errFile.toFile());
        var p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            throw new AssertionError("still waiting after " + seconds + " s: " + script);
        }
        var out = Files.readString(outFile);
        var err = Files.readString(errFile);
        Files.delete(outFile);
        Files.delete(errFile);
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
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(), Tasks.Profile.NONE)),
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
        assertFalse(args.contains("--max-turns"), "no turn limit: " + args);
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
        sh(TaskScripts.launch(id, 2, Tasks.AGENT, TaskScripts.agentRun(id, 2, work.toString(), Tasks.Profile.NONE)),
                "now open a PR");
        var second = awaitFinished(id);
        assertEquals(2, second.run());
        assertEquals("Done: now open a PR", StreamJsonEvents.summarize(second.output(), 1).resultText());
        assertTrue(Files.readString(home.resolve("claude-args")).contains("--resume " + sessionId()));
    }

    /** Waits until run {@code run} of task {@code id} has written {@code text} to its events. */
    private void awaitEvents(String id, int run, String text) throws Exception {
        var events = home.resolve(".isx-mcp/tasks/" + id + "/events-" + run + ".jsonl");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!(Files.exists(events) && Files.readString(events).contains(text))) {
            if (System.nanoTime() > deadline) throw new AssertionError("no " + text + " in " + events);
            Thread.sleep(50);
        }
    }

    @Test
    void aCancelledRunKeepsItsSessionSoTheNextMessageResumesIt() throws Exception {
        var id = "t1-abc";
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(), Tasks.Profile.NONE)), "KEEP-WORKING");
        awaitEvents(id, 1, "\"subtype\":\"init\"");
        // The transcript is written just after the init event.
        var transcript = home.resolve(".claude/projects/-work/" + sessionId() + ".jsonl");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(transcript)) {
            if (System.nanoTime() > deadline) throw new AssertionError("no transcript at " + transcript);
            Thread.sleep(50);
        }
        sh(TaskScripts.cancel(id), "");
        var cancelled = Tasks.parse(sh(TaskScripts.status(id, 1000), ""));
        assertEquals(143, cancelled.exit());
        assertEquals(sessionId(), cancelled.sessionId(), "the cancel saved the session the run script could not");

        sh(TaskScripts.launch(id, 2, Tasks.AGENT, TaskScripts.agentRun(id, 2, work.toString(), Tasks.Profile.NONE)), "carry on");
        var second = awaitFinished(id);
        assertEquals(0, second.exit());
        assertTrue(Files.readString(home.resolve("claude-args")).contains("--resume " + sessionId()));
    }

    @Test
    void aRunCancelledBeforeItsTranscriptSavesNoSessionAndTheNextIsRefusedWithoutAResume() throws Exception {
        var id = "t1-abc";
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(), Tasks.Profile.NONE)), "NO-TRANSCRIPT");
        awaitEvents(id, 1, "\"subtype\":\"init\"");
        sh(TaskScripts.cancel(id), "");
        assertNull(Tasks.parse(sh(TaskScripts.status(id, 1000), "")).sessionId(), "Claude Code could not resume it");

        // Claude Code fails --resume "" at once, and reports a made-up session ID that would be saved.
        Files.delete(home.resolve("claude-args"));
        sh(TaskScripts.launch(id, 2, Tasks.AGENT, TaskScripts.agentRun(id, 2, work.toString(), Tasks.Profile.NONE)), "carry on");
        var second = awaitFinished(id);
        assertEquals(125, second.exit());
        assertTrue(second.stderr().contains("never saved a Claude Code session"), second.stderr());
        assertFalse(Files.exists(home.resolve("claude-args")), "Claude Code was not started");
    }

    @Test
    void aResumeClaudeCodeRefusedLeavesTheSavedSession() throws Exception {
        var id = "t1-abc";
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(), Tasks.Profile.NONE)), "go");
        assertEquals(sessionId(), awaitFinished(id).sessionId());
        sh(TaskScripts.launch(id, 2, Tasks.AGENT, TaskScripts.agentRun(id, 2, work.toString(), Tasks.Profile.NONE)), "RESUME-FAILS");
        var failed = awaitFinished(id);
        assertEquals(1, failed.exit());
        assertEquals(sessionId(), failed.sessionId(), "not the made-up ID of the refused resume");
    }

    @Test
    void aSessionWhoseTranscriptClaudeCodeDeletedIsGoneAndItsResumeIsRefused() throws Exception {
        var id = "t1-abc";
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(), Tasks.Profile.NONE)), "go");
        var finished = awaitFinished(id);
        assertEquals(sessionId(), finished.sessionId());
        assertFalse(finished.sessionGone());

        // What Claude Code's cleanupPeriodDays does to an old conversation.
        Files.delete(home.resolve(".claude/projects/-work/" + sessionId() + ".jsonl"));
        var gone = Tasks.parse(sh(TaskScripts.status(id, 1000), ""));
        assertEquals(sessionId(), gone.sessionId(), "still the conversation it had");
        assertTrue(gone.sessionGone(), "but Claude Code can no longer resume it");

        Files.delete(home.resolve("claude-args"));
        sh(TaskScripts.launch(id, 2, Tasks.AGENT, TaskScripts.agentRun(id, 2, work.toString(), Tasks.Profile.NONE)), "carry on");
        var refused = awaitFinished(id);
        assertEquals(125, refused.exit());
        assertTrue(refused.stderr().contains("its Claude Code conversation is gone"), refused.stderr());
        assertFalse(Files.exists(home.resolve("claude-args")), "Claude Code was not started");
    }

    @Test
    void aCodexOrPiTaskIsNeverJudgedByClaudeCodesTranscripts() throws Exception {
        for (var agent : List.of("codex", "pi")) {
            var d = finishedTaskFiles("t5-" + agent, Tasks.AGENT);
            Files.writeString(d.resolve("identity"), "1 " + agent + "\n");
            Files.writeString(d.resolve("session_id"), sessionId());
            assertFalse(Tasks.parse(sh(TaskScripts.status("t5-" + agent, 1000), "")).sessionGone(), agent);
        }
    }

    @Test
    void aDelegateRunsUnderItsProfileAndRecordsIt() throws Exception {
        var id = "t1-abc";
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(),
                new Tasks.Profile("claude-haiku-4-5"))), "rebase");
        awaitFinished(id);
        var args = Files.readString(home.resolve("claude-args"));
        assertTrue(args.contains("--model claude-haiku-4-5 "), args);
        // An adopting session learns the profile with the task.
        assertTrue(sh(TaskScripts.list(), "").contains("t1-abc agent 1 done claude-haiku-4-5 - "));

        // Without one, the template's model (no --model).
        sh(TaskScripts.launch("t2-abc", 1, Tasks.AGENT, TaskScripts.agentRun("t2-abc", 1, work.toString(),
                Tasks.Profile.NONE)), "design");
        awaitFinished("t2-abc");
        var second = Files.readString(home.resolve("claude-args")).lines().reduce((a, b) -> b).orElseThrow();
        assertFalse(second.contains("--model"), second);
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
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(), Tasks.Profile.NONE)), "go");
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
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, wt.toString(), Tasks.Profile.NONE)), "in a worktree");
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
                TaskScripts.agentRun("t24-abc", 1, work.toString(), Tasks.Profile.NONE)), "go");
        awaitFinished("t24-abc");
        var resumed = claude(home, "bash -c 'sleep 30; :' --resume " + sessionId()).start();
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
                TaskScripts.agentRun("t1-abc", 1, work.toString(), Tasks.Profile.NONE)), "go");
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
                TaskScripts.agentRun("t1-abc", 1, work.toString(), Tasks.Profile.NONE)), "go");
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
        var files = diff.structured().path("repos").getElement(0).path("files");
        assertEquals(java.util.List.of("NEW_FILE", odd, "tracked.txt"),
                files.asList().stream().map(f -> f.path("path").asText()).sorted().toList(), files.toString());
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
                TaskScripts.agentRun("t1-abc", 1, work.toString(), Tasks.Profile.NONE)), "go");
        var agent = awaitFinished("t1-abc");
        assertTrue(agent.startedAt() != null && agent.lastActivity() != null, agent.toString());
    }

    @Test
    void aPersonsClaudeOnTheConversationMakesTheTaskAttached() throws Exception {
        sh(TaskScripts.launch("t1-abc", 1, Tasks.AGENT,
                TaskScripts.agentRun("t1-abc", 1, work.toString(), Tasks.Profile.NONE)), "go");
        assertEquals("finished", awaitFinished("t1-abc").state());

        // A person resumes the session from elsewhere: argv[0] is what Claude Code's is.
        var resumed = claude(home, "bash -c 'sleep 30; :' --resume " + sessionId()).start();
        try {
            var status = awaitAttached("t1-abc");
            assertEquals(resumed.pid(), status.attachedPid());
        } finally {
            resumed.destroy();
            resumed.waitFor(5, TimeUnit.SECONDS);
        }
        // ... or works in the task's directory, where --continue would pick the session up.
        var inPlace = claude(work, "sleep 30").start();
        try {
            assertEquals(inPlace.pid(), awaitAttached("t1-abc").attachedPid());
        } finally {
            inPlace.destroy();
            inPlace.waitFor(5, TimeUnit.SECONDS);
        }
        assertEquals("finished", Tasks.parse(sh(TaskScripts.status("t1-abc", 0), "")).state());

        // A task's own Claude Code is not a person, even in the same directory.
        var pb = claude(work, "sleep 30");
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

    /**
     * A stand-in for a Claude Code: argv[0] is what Claude Code's is. It does not inherit
     * {@link TaskScripts#TASK_ENV} from the JVM, which has it when the suite runs in a task (#1246).
     */
    private static ProcessBuilder claude(Path dir, String command) {
        var pb = new ProcessBuilder("bash", "-c", "exec -a claude " + command).directory(dir.toFile());
        pb.environment().remove(TaskScripts.TASK_ENV);
        return pb;
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
                TaskScripts.agentRun("t12-abc", 1, work.toString(), Tasks.Profile.NONE)), "go");
        assertEquals("finished", awaitFinished("t12-abc").state());
        stub("systemd-run", "exit 1");
        var pb = new ProcessBuilder("bash", "-c", TaskScripts.launch("t12-abc", 2, Tasks.AGENT,
                TaskScripts.agentRun("t12-abc", 2, work.toString(), Tasks.Profile.NONE)))
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
    void theSweepSeesHowLongAgoADelegateLastWroteAnything() throws Exception {
        assertEquals("", sh("HOME=" + home.resolve("empty") + "; " + TaskScripts.agentIdle(), ""),
                "nothing to report before a run has written anything, and no failure");
        recordedTask("t21-abc", Tasks.AGENT, false);
        recordedTask("t22-abc", Tasks.COMMAND, false); // a command's output is not a delegate's progress
        var twoHoursAgo = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofHours(2)));
        try (var files = Files.list(home.resolve(".isx-mcp/tasks/t21-abc"))) {
            for (var f : files.toList()) Files.setLastModifiedTime(f, twoHoursAgo);
        }
        Files.writeString(home.resolve(".isx-mcp/tasks/t22-abc/stdout"), "fresh\n");
        var idle = TaskScripts.agentIdle(sh(TaskScripts.agentIdle(), "").lines().toList());
        assertTrue(idle != null && idle >= 7200 && idle < 7300, "idle " + idle);

        Files.writeString(home.resolve(".isx-mcp/tasks/t21-abc/events-1.jsonl"), "{}\n");
        idle = TaskScripts.agentIdle(sh(TaskScripts.agentIdle() + "; " + TaskScripts.unfinished(), "").lines().toList());
        assertTrue(idle != null && idle < 60, "the newest file counts: " + idle);
        assertEquals(null, TaskScripts.agentIdle(List.of("task t1-abc running", "idle soon")), "only a number is read");
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
    @Timeout(30)
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
        for (var f : List.of("cwd", "model", "key", "session_id", "events-1.jsonl", "stderr-1.log")) {
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

    /**
     * A {@code [} that, having found the task file {@code $SWAP} to be a regular (or non-empty) file, puts a FIFO
     * in its place: the race of #1080 (something in the instance swapping the two between check
     * and open), made to happen every time.
     */
    private static final String SWAP_AFTER_CHECK = """
            [() {
              builtin [ "$@"; local r=$?
              if builtin [ $r = 0 ] && { builtin [ "$1" = -f ] || builtin [ "$1" = -s ]; } && builtin [ "$2" = "$SWAP" ]; then
                rm -f -- "$2"; mkfifo -- "$2"
              fi
              return $r
            }
            """;

    @Test
    @Timeout(120)
    void aFifoSwappedInAfterTheCheckHoldsNoRead() throws Exception {
        var reads = List.of(TaskScripts.list(), TaskScripts.status("t32-abc", 1000), TaskScripts.status("t33-abc", 1000),
                TaskScripts.status("t34-abc", 1000), TaskScripts.output("t33-abc"),
                TaskScripts.watch(List.of("t32-abc", "t33-abc", "t34-abc")), TaskScripts.busy(), TaskScripts.unfinished(),
                TaskScripts.agentIdle());
        var files = swappableTaskFiles();
        for (var script : reads) {
            for (var file : files) {
                swappableTaskFiles();
                sh("SWAP=" + file + "\n" + SWAP_AFTER_CHECK + script, "", 5);
            }
        }
        // The kill sweep waits between TERM and KILL: cancel reads one file, its current run.
        swappableTaskFiles();
        sh("SWAP=" + home.resolve(".isx-mcp/tasks/t33-abc/current") + "\n" + SWAP_AFTER_CHECK + TaskScripts.cancel("t33-abc"), "", 10);
    }

    @Test
    @Timeout(60)
    void aFifoInTheTaskDirOrTheRepositoryHoldsNoDiff() throws Exception {
        sh(TaskScripts.launch("t51-abc", 1, Tasks.AGENT,
                TaskScripts.agentRun("t51-abc", 1, work.toString(), Tasks.Profile.NONE)), "go");
        awaitFinished("t51-abc");
        var base = home.resolve(".isx-mcp/tasks/t51-abc/base.txt");
        var recorded = Files.readString(base);
        for (var script : List.of(TaskScripts.diff("t51-abc", null, 100_000), TaskScripts.diff("t51-abc", null, 100_000, true),
                TaskScripts.diffForReading("t51-abc", null))) {
            // Exported: the diff runs in a bash of its own, under timeout.
            sh("export SWAP=" + base + "\n" + SWAP_AFTER_CHECK + "export -f [\n" + script, "", 5);
            Files.delete(base);
            Files.writeString(base, recorded);
        }

        // The diff starts from a copy of the repository's index.
        var index = work.resolve(".git/index");
        Files.move(index, work.resolve(".git/index.kept"));
        sh("mkfifo " + index, "");
        assertTrue(sh(TaskScripts.diff("t51-abc", null, 100_000), "", 5).contains("NEW_FILE"), "a diff all the same");
        Files.delete(index);
        Files.move(work.resolve(".git/index.kept"), index);

        // Whatever git itself opens is the repository's: no script can check it, so the diff is bounded.
        var head = work.resolve(".git/HEAD");
        Files.delete(head);
        sh("mkfifo " + head, "");
        var pb = new ProcessBuilder("bash", "-c", TaskScripts.diff("t51-abc", null, 100_000, false, 2)).directory(home.toFile());
        pb.environment().put("HOME", home.toString());
        var p = pb.start();
        try {
            assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the diff waited on a FIFO");
            assertTrue(p.exitValue() != 0, "a diff that did not finish is no diff");
            assertTrue(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).contains("did not finish within 2 s"));
        } finally {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    /** A finished agent, a finished command and an agent being cancelled, from scratch; every file of theirs. */
    private List<Path> swappableTaskFiles() throws Exception {
        var agent = finishedTaskFiles("t32-abc", Tasks.AGENT);
        for (var f : List.of("cwd", "model", "key", "session_id", "events-1.jsonl", "stderr-1.log")) {
            Files.writeString(agent.resolve(f), "x\n");
        }
        var command = finishedTaskFiles("t33-abc", Tasks.COMMAND);
        Files.writeString(command.resolve("stdout"), "out\n");
        Files.writeString(command.resolve("stderr"), "err\n");
        // No exit yet and no unit: a state read reads its stamp.
        var cancelling = finishedTaskFiles("t34-abc", Tasks.AGENT);
        Files.delete(cancelling.resolve("exit-1"));
        Files.writeString(cancelling.resolve("cancelling-1"), System.currentTimeMillis() / 1000 + "\n");
        try (var all = Files.walk(home.resolve(".isx-mcp/tasks"))) {
            return all.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** A finished run 1 of task {@code id} ({@link #recordedTask}), recreated from scratch. */
    private Path finishedTaskFiles(String id, String kind) throws Exception {
        var d = home.resolve(".isx-mcp/tasks/" + id);
        if (Files.exists(d)) {
            try (var files = Files.list(d)) {
                for (var f : files.toList()) Files.delete(f);
            }
        }
        recordedTask(id, kind, false);
        return d;
    }

    @Test
    @Timeout(60)
    void aFifoWhereATaskFileIsWrittenHoldsNeitherCancelNorTheNextRun() throws Exception {
        // Opening a FIFO for writing waits for a reader: cancel_task and send_message write task files.
        var d = home.resolve(".isx-mcp/tasks/t41-abc");
        sh(TaskScripts.launch("t41-abc", 1, Tasks.COMMAND, TaskScripts.commandRun("t41-abc", work.toString(), Map.of(), "sleep 300")), "");
        // exit-1.tmp: where the exit record used to be written aside.
        sh("mkfifo " + d.resolve("cancelling-1") + " " + d.resolve("exit-1.tmp"), "");
        sh(TaskScripts.cancel("t41-abc"), "", 15);
        assertEquals(143, awaitFinished("t41-abc").exit());

        for (var f : List.of("prompt-2.md", "run-2.sh", "kind", "current")) {
            Files.deleteIfExists(d.resolve(f));
            sh("mkfifo " + d.resolve(f), "");
        }
        sh(TaskScripts.launch("t41-abc", 2, Tasks.COMMAND, "true"), "the next prompt", 5);
        assertEquals("the next prompt", Files.readString(d.resolve("prompt-2.md")), "the FIFO is replaced, never written through");
        assertEquals("true", Files.readString(d.resolve("run-2.sh")));
        assertEquals("2\n", Files.readString(d.resolve("current")));
        assertEquals("command\n", Files.readString(d.resolve("kind")));
        // A directory in its place is not written into, which would leave run 2 current unseen.
        Files.createDirectories(d.resolve("prompt-3.md"));
        assertThrows(AssertionError.class, () -> sh(TaskScripts.launch("t41-abc", 3, Tasks.COMMAND, "true"), "", 5));
        assertEquals("2\n", Files.readString(d.resolve("current")));
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
                TaskScripts.agentRun("t13-abc", 1, work.toString(), Tasks.Profile.NONE),
                TaskScripts.agentRun("t13-abc", 2, work.toString(), Tasks.Profile.NONE),
                TaskScripts.cancel("t13-abc"));
        var direct = java.util.regex.Pattern.compile(">\\s*\"\\$D/exit-[^\".]*\"");
        for (var script : scripts) {
            assertFalse(direct.matcher(script).find(), "an exit file written in place:\n" + script);
        }
        sh(TaskScripts.launch("t13-abc", 1, Tasks.COMMAND, scripts.get(0)), "");
        assertEquals(0, awaitFinished("t13-abc").exit());
        try (var files = Files.list(home.resolve(".isx-mcp/tasks/t13-abc"))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith(".tmp.")), "nothing left aside");
        }
    }

    @Test
    @Timeout(30)
    void anExitRecordThatCouldNotBeWrittenIsNeverPutInPlace() throws Exception {
        // A full disk, say: the record is written aside, and an empty one must not become the exit.
        sh(TaskScripts.launch("t61-abc", 1, Tasks.COMMAND, TaskScripts.commandRun("t61-abc", work.toString(), Map.of(), "sleep 300")), "");
        var pb = new ProcessBuilder("bash", "-c", "echo() { return 1; }\n" + TaskScripts.cancel("t61-abc")).directory(home.toFile());
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var p = pb.start();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        var d = home.resolve(".isx-mcp/tasks/t61-abc");
        assertFalse(Files.exists(d.resolve("exit-1")), "no exit was recorded, so none is in place");
        assertTrue(p.exitValue() != 0, "and the cancel says it failed");
        try (var files = Files.list(d)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().startsWith(".tmp.")), "nothing left aside");
        }
    }

    @Test
    void aTaskStartedUnderAKeyListsItForAnAdoptingSession() throws Exception {
        sh(TaskScripts.launch("t1-abc", 1, Tasks.COMMAND,
                TaskScripts.commandRun("t1-abc", work.toString(), Map.of(), "true"), "issue-1011:impl.v2_x"), "");
        awaitFinished("t1-abc");
        var physical = work.toRealPath().toString();
        assertTrue(sh(TaskScripts.list(), "").contains("t1-abc command 1 done - =issue-1011:impl.v2_x " + physical + "\n"));
        // A later run does not write it again; the first one's stays.
        sh(TaskScripts.launch("t1-abc", 2, Tasks.COMMAND, "true", "other"), "");
        assertEquals("issue-1011:impl.v2_x", Files.readString(home.resolve(".isx-mcp/tasks/t1-abc/key")));
        assertThrows(ToolError.class, () -> TaskScripts.launch("t2-abc", 1, Tasks.COMMAND, "true", "a'b"),
                "nothing that is not a key reaches the script");
    }

    @Test
    void aKeyFileAnyoneInTheInstanceWroteIsReadAsOneField() throws Exception {
        recordedTask("t1-abc", Tasks.AGENT, false);
        var planted = "a key\nwith  spaces $(touch pwned)" + "x".repeat(100);
        Files.writeString(home.resolve(".isx-mcp/tasks/t1-abc/key"), planted);
        recordedTask("t2-abc", Tasks.AGENT, false);
        var fifo = home.resolve(".isx-mcp/tasks/t2-abc/key");
        new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor();
        var pb = new ProcessBuilder("bash", "-c", TaskScripts.list()).directory(home.toFile());
        pb.environment().put("HOME", home.toString());
        var p = pb.start();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "a FIFO in place of the key holds nothing up");
        var lines = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        assertFalse(Files.exists(home.resolve("pwned")));
        var hostile = lines.stream().filter(l -> l.startsWith("t1-abc ")).findFirst().orElseThrow();
        var fields = hostile.split(" ", 7);
        assertEquals(7, fields.length, hostile);
        assertEquals("=" + planted.substring(0, 64).replaceAll("[ \n]", ""), fields[5], "at most 64 bytes, no space or newline");
        assertNull(McpSession.keyOf(fields[5].substring(1)), "and not a key");
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("t2-abc agent 1 done - - ")), lines.toString());
    }

    /** Codex ships as a Node script, so its stand-ins are too; a box without Node skips them (#1363). */
    private static void assumeNode() {
        var path = System.getenv("PATH");
        assumeTrue(path != null && Arrays.stream(path.split(File.pathSeparator)).map(dir -> Path.of(dir, "node"))
                .anyMatch(node -> Files.isRegularFile(node) && Files.isExecutable(node)), "node is not on PATH");
    }

    private void codexStub(String events) throws Exception {
        assumeNode();
        var script = home.resolve("codex-stub.cjs");
        Files.writeString(script, """
                const fs = require('node:fs');
                const args = process.argv.slice(2);
                fs.writeFileSync(process.env.HOME + '/codex-args', JSON.stringify(args));
                const prompt = fs.readFileSync(0, 'utf8');
                fs.writeFileSync(process.env.HOME + '/codex-prompt', prompt);
                const report = args[args.indexOf('--output-last-message') + 1];
                """ + events);
        stub("codex", "exec node " + ExecScript.quote(script.toString()) + " \"$@\"");
    }

    private void launchCodex(String id, int run, String prompt) throws Exception {
        sh(TaskScripts.launch(id, run, Tasks.AGENT,
                TaskScripts.agentRun(id, run, work.toString(), Tasks.Profile.NONE, DelegateAgent.CODEX),
                null, DelegateAgent.CODEX), prompt);
    }

    @Test
    void codexRunsAndResumesTheExactConversationWithPromptsOnStdin() throws Exception {
        codexStub("""
                console.log(JSON.stringify({type:'thread.started', thread_id:'codex-session-123'}));
                fs.writeFileSync(report, 'The report: ' + prompt);
                console.log(JSON.stringify({type:'turn.completed', usage:{input_tokens:10, output_tokens:3}}));
                """);
        launchCodex("t1-codex", 1, "fix 'quotes'; $(touch SHOULD_NOT_EXIST) — safely");
        var first = awaitFinished("t1-codex");
        assertEquals(0, first.exit(), first.stderr());
        assertEquals("codex-session-123", first.sessionId());
        assertFalse(Files.exists(work.resolve("SHOULD_NOT_EXIST")));
        assertTrue(Files.readString(home.resolve("codex-prompt")).contains("You are a delegate"));
        assertTrue(sh(TaskScripts.list(), "").contains("identity t1-codex 1 codex"));
        // Recover from the durable opening event if publishing its separate ID was interrupted.
        Files.delete(home.resolve(".isx-mcp/tasks/t1-codex/session_id"));
        launchCodex("t1-codex", 2, "follow up with $HOME and `literal`");
        var resumed = awaitFinished("t1-codex");
        assertEquals(0, resumed.exit(), resumed.stderr());
        var argv = io.hyperfoil.tools.jjq.value.JqValues.parseStrict(Files.readString(home.resolve("codex-args")));
        assertEquals("resume", argv.getElement(1).asText());
        assertEquals("codex-session-123", argv.getElement(argv.size() - 2).asText());
        assertEquals("-", argv.getElement(argv.size() - 1).asText());
        assertEquals("follow up with $HOME and `literal`", Files.readString(home.resolve("codex-prompt")));
        assertTrue(CodexEvents.summarize(resumed.output(), 8).finished());
    }

    @Test
    void codexKeepsLargeReportsOutsideTheEventTailAndReplacesHostileTargets() throws Exception {
        codexStub("""
                console.log(JSON.stringify({type:'thread.started', thread_id:'codex-session-123'}));
                console.log(JSON.stringify({type:'future.event',data:'x'.repeat(2 * 1024 * 1024)}));
                console.log(JSON.stringify({type:'item.completed', item:{type:'agent_message',text:'a'.repeat(300000)}}));
                fs.writeFileSync(report, 'a'.repeat(300000));
                console.log(JSON.stringify({type:'turn.completed'}));
                """);
        var d = Files.createDirectories(home.resolve(".isx-mcp/tasks/t2-codex"));
        for (var file : List.of("session_id", "runner-1.log", "stderr-1.log"))
            sh("mkfifo " + ExecScript.quote(d.resolve(file).toString()), "");
        Files.createSymbolicLink(d.resolve("outcome-1.json"), home.resolve("must-not-write"));
        launchCodex("t2-codex", 1, "large report");
        var status = awaitFinished("t2-codex");
        assertEquals(0, status.exit(), status.stderr());
        assertEquals(300000, Files.size(d.resolve("result-1.txt")));
        assertFalse(Files.exists(home.resolve("must-not-write")));
        assertEquals(300000, CodexEvents.summarize(status.output(), 0).result().path("report_bytes").asInt(0));
    }

    @Test
    void aReadableButUnwritableFileCountsAsReadable() throws Exception {
        var file = Files.writeString(home.resolve("SKILL.md"), "x");
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
        assertEquals("ok", sh(TaskScripts.isReadableFile(file.toString()) + " && echo ok", "").strip());
        assertEquals("no", sh("{ " + TaskScripts.isReadableFile(home.resolve("missing").toString()) + "; } || echo no", "").strip());
        // What ifFile would have said: it opens read-write, so this file would read as missing.
        assertEquals("no", sh("{ " + TaskScripts.ifFile(file.toString(), "cat") + "; } || echo no", "").strip());
    }

    @Test
    void codexCountsAFallbackReportBeforeCuttingIt() throws Exception {
        // No --output-last-message file: the last agent message is the report. Two bytes a
        // character, so it is over the 1 MiB report limit while its event line is not, and the
        // leading byte puts the limit inside a character.
        codexStub("""
                console.log(JSON.stringify({type:'thread.started', thread_id:'codex-session-123'}));
                console.log(JSON.stringify({type:'item.completed', item:{type:'agent_message',text:'a' + '\u00e9'.repeat(600000)}}));
                console.log(JSON.stringify({type:'turn.completed'}));
                """);
        launchCodex("t3-codex", 1, "large fallback report");
        var status = awaitFinished("t3-codex");
        assertEquals(0, status.exit(), status.stderr());
        var result = CodexEvents.summarize(status.output(), 0).result();
        assertEquals(1200001, result.path("report_bytes").asInt(0));
        assertTrue(result.path("report_truncated").asBoolean(false));
        assertEquals(1024 * 1024 - 1, Files.size(home.resolve(".isx-mcp/tasks/t3-codex/result-1.txt")), "cut before the split character");
    }

    @Test
    void codexRequiresTerminalSuccessAndZeroExit() throws Exception {
        int trial = 0;
        for (var ending : List.of("", "console.log(JSON.stringify({type:'turn.failed',error:{message:'denied'}}));",
                "console.log('invalid-json'); console.log(JSON.stringify({type:'turn.completed'}));",
                "console.log(JSON.stringify({type:'turn.completed'})); process.exitCode = 7;")) {
            codexStub("console.log(JSON.stringify({type:'thread.started', thread_id:'codex-session-123'})); fs.writeFileSync(report, 'partial'); " + ending);
            var id = "t3-codex-" + trial++;
            launchCodex(id, 1, "fail");
            var status = awaitFinished(id);
            assertTrue(status.exit() != 0, status.toString());
            assertTrue(CodexEvents.summarize(status.output(), 0).isError());
        }
    }

    @Test
    void codexSessionIsDurableBeforeItFinishesAndCancelKillsItsChildren() throws Exception {
        codexStub("""
                console.log(JSON.stringify({type:'thread.started', thread_id:'codex-session-123'}));
                const child = require('node:child_process').spawn('sleep', ['120'], {stdio:'ignore'});
                fs.writeFileSync(process.env.HOME + '/codex-child', String(child.pid));
                setTimeout(() => {}, 120000);
                """);
        launchCodex("t4-codex", 1, "wait");
        var d = home.resolve(".isx-mcp/tasks/t4-codex");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((!Files.exists(d.resolve("session_id")) || !Files.exists(home.resolve("codex-child"))) && System.nanoTime() < deadline) Thread.sleep(50);
        try {
            assertEquals("codex-session-123", Files.readString(d.resolve("session_id")));
            assertTrue(Tasks.parse(sh(TaskScripts.status("t4-codex", 1000), "")).running());
        } finally {
            sh(TaskScripts.cancel("t4-codex"), "");
        }
        assertFalse(Tasks.parse(sh(TaskScripts.status("t4-codex", 1000), "")).running());
        var pid = Files.readString(home.resolve("codex-child"));
        assertEquals("", sh("p=" + pid + "; [ ! -f /proc/$p/stat ] || { s=$(cat /proc/$p/stat); [[ $s == *') Z '* ]] || echo alive; }; true", ""));
    }

    /**
     * A stand-in for Pi's {@code -p --mode json} that keeps Pi's session rule: {@code --session-id}
     * opens the session it names, or creates it, and the header it prints first carries the
     * session's creation time, unchanged on a resume. The session is a file where Pi 1.4.0 keeps it,
     * {@code ~/.pi/agent/sessions/--<cwd>--/<timestamp>_<id>.jsonl}; {@code $HOME/pi-fake-header}
     * makes the header claim another creation time, as a session Pi re-created would.
     */
    private void piStub(String events) throws Exception {
        assumeNode();
        var script = home.resolve("pi-stub.cjs");
        Files.writeString(script, """
                const fs = require('node:fs');
                const args = process.argv.slice(2);
                fs.writeFileSync(process.env.HOME + '/pi-args', JSON.stringify(args));
                const prompt = fs.readFileSync(0, 'utf8');
                fs.writeFileSync(process.env.HOME + '/pi-prompt', prompt);
                const id = args[args.indexOf('--session-id') + 1];
                const sessions = process.env.HOME + '/.pi/agent/sessions/--'
                    + process.cwd().replace(/^[/\\\\]/, '').replace(/[/\\\\:]/g, '-') + '--';
                fs.mkdirSync(sessions, {recursive: true});
                const existing = fs.readdirSync(sessions).find(f => f.endsWith('_' + id + '.jsonl'));
                let timestamp = existing && JSON.parse(fs.readFileSync(sessions + '/' + existing, 'utf8').split('\\n')[0]).timestamp;
                if (!timestamp) {
                    timestamp = new Date(Date.now() + Math.random() * 1e6).toISOString();
                    fs.writeFileSync(sessions + '/' + timestamp.replace(/[:.]/g, '-') + '_' + id + '.jsonl',
                        JSON.stringify({type: 'session', id, timestamp}) + '\\n');
                }
                if (fs.existsSync(process.env.HOME + '/pi-fake-header')) timestamp = fs.readFileSync(process.env.HOME + '/pi-fake-header', 'utf8');
                const emit = e => console.log(JSON.stringify(e));
                emit({type: 'session', version: 3, id, timestamp, cwd: process.cwd()});
                const say = (text, extra) => emit({type: 'message_end', message: Object.assign({role: 'assistant',
                    content: [{type: 'text', text}], stopReason: 'stop',
                    usage: {input: 10, output: 3, cacheRead: 5, cacheWrite: 7, cost: {total: 0.125}}}, extra)});
                // What Pi says when it creates a session; the stand-in says it on every run, to show which runs drop it.
                process.stderr.write("Warning: No project session found with id '" + id + "'; creating a new session with that id.\\n"
                    + "a real diagnostic\\n");
                """ + events);
        stub("pi", "exec node " + ExecScript.quote(script.toString()) + " \"$@\"");
    }

    private void launchPi(String id, int run, String prompt) throws Exception {
        sh(TaskScripts.launch(id, run, Tasks.AGENT,
                TaskScripts.agentRun(id, run, work.toString(), Tasks.Profile.NONE, DelegateAgent.PI),
                null, DelegateAgent.PI), prompt);
    }

    private static final String PI_SUCCESS = """
            emit({type: 'agent_start'});
            emit({type: 'tool_execution_start', toolCallId: 'c1', toolName: 'bash', args: {command: 'make test'}});
            say('The report: ' + prompt);
            emit({type: 'turn_end'});
            emit({type: 'agent_end', messages: []});
            emit({type: 'agent_settled', aborted: false});
            """;

    @Test
    void piRunsAndResumesTheSessionIsxChoseWithPromptsOnStdin() throws Exception {
        piStub(PI_SUCCESS);
        launchPi("t1-pi", 1, "fix 'quotes'; $(touch SHOULD_NOT_EXIST) — safely");
        var first = awaitFinished("t1-pi");
        assertEquals(0, first.exit(), first.stderr());
        var d = home.resolve(".isx-mcp/tasks/t1-pi");
        var session = first.sessionId();
        assertTrue(session.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), session);
        var argv = io.hyperfoil.tools.jjq.value.JqValues.parseStrict(Files.readString(home.resolve("pi-args")));
        assertEquals(List.of("-p", "--mode", "json", "--session-id", session, "--append-system-prompt"),
                argv.asList().subList(0, 6).stream().map(v -> v.asText()).toList());
        assertTrue(argv.getElement(6).asText().startsWith("You are a delegate"), "the brief is appended to the system prompt");
        assertEquals("fix 'quotes'; $(touch SHOULD_NOT_EXIST) — safely", Files.readString(home.resolve("pi-prompt")));
        assertFalse(Files.exists(work.resolve("SHOULD_NOT_EXIST")));
        assertEquals("The report: fix 'quotes'; $(touch SHOULD_NOT_EXIST) — safely", Files.readString(d.resolve("result-1.txt")));
        assertTrue(sh(TaskScripts.list(), "").contains("identity t1-pi 1 pi"));
        var summary = PiEvents.summarize(first.output(), 8);
        assertFalse(summary.isError(), first.toString());
        assertEquals(1, summary.result().path("turns").asInt(0));
        assertEquals(0.125, summary.result().path("cost_usd").asDouble(0), 1e-9);
        assertEquals(5, summary.result().path("usage").path("cached_input_tokens").asInt(0));
        assertEquals(7, summary.result().path("usage").path("cache_creation_input_tokens").asInt(0));
        // Run 1 drops Pi's warning that it is creating the session it was asked to create, and nothing else.
        var stderr1 = Files.readString(d.resolve("stderr-1.log"));
        assertFalse(stderr1.contains("No project session found"), stderr1);
        assertTrue(stderr1.contains("a real diagnostic"), stderr1);
        assertEquals(List.of("ran bash: make test", "said: The report: fix 'quotes'; $(touch SHOULD_NOT_EXIST) — safely"), summary.recent());

        launchPi("t1-pi", 2, "follow up with $HOME and `literal`");
        var resumed = awaitFinished("t1-pi");
        assertEquals(0, resumed.exit(), resumed.stderr());
        assertEquals(session, resumed.sessionId());
        argv = io.hyperfoil.tools.jjq.value.JqValues.parseStrict(Files.readString(home.resolve("pi-args")));
        assertEquals(session, argv.getElement(4).asText(), "the same session, by its exact id");
        assertEquals("follow up with $HOME and `literal`", Files.readString(home.resolve("pi-prompt")));
        assertEquals("The report: follow up with $HOME and `literal`", Files.readString(d.resolve("result-2.txt")));
        assertTrue(Files.readString(d.resolve("stderr-2.log")).contains("No project session found"),
                "a resume keeps it: there it would mean the session was lost");
    }

    @Test
    void piNeverContinuesInANewConversationInPlaceOfALostOne() throws Exception {
        piStub(PI_SUCCESS);
        launchPi("t2-pi", 1, "first");
        var first = awaitFinished("t2-pi");
        assertEquals(0, first.exit(), first.stderr());
        var d = home.resolve(".isx-mcp/tasks/t2-pi");
        assertEquals("ok", sh(TaskScripts.piSessionStatus("t2-pi"), "").strip(), "what send_message asks first");

        // Pi's session file is gone: --session-id would make a new one under the same id and write the
        // message into it, so neither send_message's check nor the run lets Pi start at all.
        var sessions = home.resolve(".pi/agent/sessions").resolve(piProjectSessions(work.toRealPath().toString()));
        try (var files = Files.list(sessions)) {
            for (var f : files.toList()) Files.delete(f);
        }
        assertTrue(sh(TaskScripts.piSessionStatus("t2-pi"), "").contains("its Pi session file is gone"));
        Files.delete(home.resolve("pi-args"));
        launchPi("t2-pi", 2, "second");
        var refusedRun = awaitFinished("t2-pi");
        assertEquals(125, refusedRun.exit(), refusedRun.toString());
        assertTrue(refusedRun.stderr().contains("its Pi session file is gone"), refusedRun.stderr());
        assertFalse(Files.exists(home.resolve("pi-args")), "Pi never started");
        try (var files = Files.list(sessions)) {
            assertEquals(0, files.count(), "and wrote no replacement session");
        }

        // A session Pi re-created behind the check (its header is not run 1's) is stopped by the runner.
        launchPi("t2-pi-b", 1, "first");
        assertEquals(0, awaitFinished("t2-pi-b").exit());
        Files.writeString(home.resolve("pi-fake-header"), "2030-01-01T00:00:00.000Z");
        launchPi("t2-pi-b", 2, "second");
        var stopped = awaitFinished("t2-pi-b");
        Files.delete(home.resolve("pi-fake-header"));
        assertTrue(stopped.exit() != 0, stopped.toString());
        var summary = PiEvents.summarize(stopped.output(), 0);
        assertTrue(summary.resultText().contains("no longer has session"), summary.resultText());
        assertEquals("", Files.readString(home.resolve(".isx-mcp/tasks/t2-pi-b/result-2.txt")), "no report from the new conversation");

        // A first run that never opened its session left nothing to continue either.
        Files.delete(d.resolve("session_started"));
        assertTrue(sh(TaskScripts.piSessionStatus("t2-pi"), "").contains("never recorded"));
        launchPi("t2-pi", 3, "third");
        var refused = awaitFinished("t2-pi");
        assertEquals(125, refused.exit(), refused.toString());
        assertTrue(refused.stderr().contains("its Pi session was never recorded"), refused.stderr());

        // Pi that fails before opening its session (an unknown model, say) leaves no ID to resume.
        stub("pi", "echo 'Error: Model not found' >&2; exit 1");
        launchPi("t3-pi-none", 1, "first");
        var never = awaitFinished("t3-pi-none");
        assertEquals(1, never.exit(), never.toString());
        assertNull(never.sessionId());
        assertTrue(PiEvents.summarize(never.output(), 0).resultText().contains("before opening its session"));
    }

    @Test
    void piRequiresASettledRunAZeroExitAndNoFailedMessage() throws Exception {
        int trial = 0;
        for (var ending : List.of(
                "say('partial'); emit({type: 'turn_end'});",
                "say('', {stopReason: 'error', errorMessage: 'overloaded'}); emit({type: 'agent_settled', aborted: false});",
                "say('partial'); emit({type: 'agent_settled', aborted: true});",
                "say('done'); emit({type: 'agent_settled', aborted: false}); process.exitCode = 7;",
                "console.log('invalid-json'); say('done'); emit({type: 'agent_settled', aborted: false});")) {
            piStub(ending);
            var id = "t3-pi-" + trial++;
            launchPi(id, 1, "fail");
            var status = awaitFinished(id);
            assertTrue(status.exit() != 0, ending + " " + status);
            assertTrue(PiEvents.summarize(status.output(), 0).isError(), ending);
        }
        piStub("say('', {stopReason: 'error', errorMessage: 'overloaded'}); emit({type: 'agent_settled', aborted: false});");
        launchPi("t3-pi-err", 1, "fail");
        assertEquals("overloaded", PiEvents.summarize(awaitFinished("t3-pi-err").output(), 0).resultText());
    }

    @Test
    void piKeepsItsReportOutsideTheEventTailAndNeverAnEarlierOneForALostFinalMessage() throws Exception {
        piStub("""
                say('an earlier message');
                emit({type: 'agent_end', messages: [{role: 'user', content: 'x'.repeat(2 * 1024 * 1024)}]});
                say('a'.repeat(300000));
                emit({type: 'agent_end', messages: [{role: 'user', content: 'x'.repeat(2 * 1024 * 1024)}]});
                emit({type: 'agent_settled', aborted: false});
                """);
        launchPi("t4-pi", 1, "large report");
        var status = awaitFinished("t4-pi");
        assertEquals(0, status.exit(), status.stderr());
        assertEquals(300000, Files.size(home.resolve(".isx-mcp/tasks/t4-pi/result-1.txt")));
        assertEquals(300000, PiEvents.summarize(status.output(), 0).result().path("report_bytes").asInt(0));

        piStub("""
                say('an earlier message');
                say('b'.repeat(9 * 1024 * 1024));
                emit({type: 'agent_settled', aborted: false});
                """);
        launchPi("t5-pi", 1, "huge report");
        var lost = awaitFinished("t5-pi");
        assertTrue(lost.exit() != 0, lost.toString());
        assertEquals("", Files.readString(home.resolve(".isx-mcp/tasks/t5-pi/result-1.txt")));
        assertTrue(PiEvents.summarize(lost.output(), 0).resultText().contains("too large"));
    }

    @Test
    void cancellingAPiTaskKillsWhatItStarted() throws Exception {
        piStub("""
                emit({type: 'message_end', message: {role: 'user', content: [{type: 'text', text: prompt}]}});
                emit({type: 'message_start', message: {role: 'assistant', content: []}});
                const child = require('node:child_process').spawn('sleep', ['120'], {stdio:'ignore'});
                fs.writeFileSync(process.env.HOME + '/pi-child', String(child.pid));
                setTimeout(() => {}, 120000);
                """);
        launchPi("t6-pi", 1, "wait");
        var d = home.resolve(".isx-mcp/tasks/t6-pi");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((!Files.exists(d.resolve("session_id")) || !Files.exists(home.resolve("pi-child"))) && System.nanoTime() < deadline) Thread.sleep(50);
        try {
            assertTrue(Files.exists(d.resolve("session_id")), "saved once Pi has written its session, before the run ends");
            assertTrue(Tasks.parse(sh(TaskScripts.status("t6-pi", 1000), "")).running());
        } finally {
            sh(TaskScripts.cancel("t6-pi"), "");
        }
        assertFalse(Tasks.parse(sh(TaskScripts.status("t6-pi", 1000), "")).running());
        var pid = Files.readString(home.resolve("pi-child"));
        assertEquals("", sh("p=" + pid + "; [ ! -f /proc/$p/stat ] || { s=$(cat /proc/$p/stat); [[ $s == *') Z '* ]] || echo alive; }; true", ""));
    }

    @Test
    void aPiTaskCancelledBeforePiWroteItsSessionIsNotResumable() throws Exception {
        // The header is out, but Pi writes the file only with the first message: nothing to continue yet.
        piStub("setTimeout(() => {}, 120000);");
        launchPi("t7-pi", 1, "wait");
        var d = home.resolve(".isx-mcp/tasks/t7-pi");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(d.resolve("session_started")) && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(Files.exists(d.resolve("session_started")));
        sh(TaskScripts.cancel("t7-pi"), "");
        var status = Tasks.parse(sh(TaskScripts.status("t7-pi", 1000), ""));
        assertFalse(status.running());
        assertNull(status.sessionId(), "no session_id, so task_status is not resumable and send_message refuses");
    }

    @Test
    void aPersonsPiIsFoundByTheDirectoryItWorksIn() throws Exception {
        var cwd = work.toRealPath().toString();
        // Pi replaces its command line with its title: nothing but "pi" is left to read.
        var pb = new ProcessBuilder("bash", "-c", "exec -a pi bash -c ': > \"$HOME/pi-ready\"; while read -r line; do :; done'")
                .directory(work.toFile());
        pb.environment().put("HOME", home.toString());
        pb.environment().remove(TaskScripts.TASK_ENV);
        var person = pb.start();
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(home.resolve("pi-ready")) && System.nanoTime() < deadline) Thread.sleep(10);
            var who = Presence.parse(sh(Presence.script(""), "").lines().toList());
            assertTrue(who.attended());
            assertEquals(person.pid(), who.holderOf(DelegateAgent.PI, "any-session", cwd));
            assertEquals(person.pid(), who.holderOf(DelegateAgent.CLAUDE, null, cwd), "the worktree stays protected across agents");
            assertNull(who.holderOf(DelegateAgent.PI, "any-session", "/elsewhere"));
        } finally {
            person.destroy();
            assertTrue(person.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void presenceRecognizesBothAgentsAndQualifiesSessionIds() {
        var presence = Presence.parse(List.of("codex 71 /codex", "resume 71 same-id", "claude 72 /claude", "resume 72 same-id"));
        assertTrue(presence.attended());
        assertEquals(71L, presence.holderOf(DelegateAgent.CODEX, "same-id", "/other"));
        assertEquals(72L, presence.holderOf(DelegateAgent.CLAUDE, "same-id", "/other"));
        assertEquals(71L, presence.holderOf(DelegateAgent.CLAUDE, null, "/codex"), "same worktree stays protected across agents");
    }

    @Test
    void codexResumeOptionsStillAttachFromAnotherDirectory() throws Exception {
        var id = "t5-codex";
        var d = finishedTaskFiles(id, Tasks.AGENT);
        Files.writeString(d.resolve("identity"), "1 codex\n");
        Files.writeString(d.resolve("session_id"), sessionId());
        Files.writeString(d.resolve("cwd"), work.toString());
        // Native argv and the Node launcher both occur in supported Codex installs; native first, so a
        // box without Node still runs every native case before the launcher skips.
        for (var launcher : List.of(false, true)) {
            for (var args : List.of(
                    List.of("exec", "resume", "--json", sessionId(), "follow up"),
                    List.of("resume", "--all", "--model", "model-value", "-c", "key=value", sessionId()),
                    List.of("exec", "resume", "--output-last-message", "report-value", "--image", "image-value", sessionId(), "-"),
                    List.of("resume", "-mattached-value", "--config=key=value", "--", sessionId(), "prompt-value"),
                    List.of("--profile", "profile-value", "exec", "--color", "never", "resume", "--enable", "feature-value", sessionId()),
                    List.of("e", "resume", "-o", "report-value", "-m", "model-value", sessionId()))) {
                var resumed = codexPresence(args, launcher, false);
                try {
                    var status = Tasks.parse(sh(TaskScripts.status(id, 0), ""));
                    assertEquals("attached", status.state(), args.toString());
                    assertEquals(resumed.pid(), status.attachedPid());
                    var watched = sh(TaskScripts.watch(List.of(id)), "");
                    assertTrue(watched.contains("presence resume " + resumed.pid() + " " + sessionId() + "\n"), watched);
                    var who = Presence.parse(sh(Presence.script(""), "").lines().toList());
                    for (var value : List.of("model-value", "report-value", "image-value", "attached-value", "prompt-value", "follow up"))
                        assertNull(who.holderOf(DelegateAgent.CODEX, value, work.toString()), value);
                } finally {
                    resumed.destroy();
                    assertTrue(resumed.waitFor(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void codexPromptsAndManagedProcessesAreNotHumanResumes() throws Exception {
        for (var args : List.of(
                List.of("exec", "--model", "resume", sessionId()),
                List.of("exec", "a prompt", "resume", sessionId()),
                List.of("--", "resume", sessionId()),
                List.of("exec", "resume", "--last", "--json"))) {
            var proc = codexPresence(args, false, false);
            try {
                var who = Presence.parse(sh(Presence.script(""), "").lines().toList());
                assertNull(who.holderOf(DelegateAgent.CODEX, sessionId(), work.toString()), args.toString());
            } finally {
                proc.destroy();
                assertTrue(proc.waitFor(5, TimeUnit.SECONDS));
            }
        }
        var managed = codexPresence(List.of("exec", "resume", "--json", sessionId()), true, true);
        try {
            var who = Presence.parse(sh(Presence.script(""), "").lines().toList());
            assertNull(who.holderOf(DelegateAgent.CODEX, sessionId(), work.toString()));
        } finally {
            managed.destroy();
            assertTrue(managed.waitFor(5, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(60)
    void theJoinCommandIsWhatPresenceTakesForAPersonInTheConversation() throws Exception {
        var cwd = work.toRealPath().toString();
        for (var agent : List.of(DelegateAgent.CLAUDE, DelegateAgent.CODEX, DelegateAgent.PI)) {
            var id = "t9-" + agent.id;
            var d = finishedTaskFiles(id, Tasks.AGENT);
            if (agent != DelegateAgent.CLAUDE) Files.writeString(d.resolve("identity"), "1 " + agent.id + "\n");
            if (agent == DelegateAgent.PI) piSessionFile(d, cwd);
            if (agent == DelegateAgent.CLAUDE) claudeTranscript();
            Files.writeString(d.resolve("session_id"), sessionId() + "\n");
            Files.writeString(d.resolve("cwd"), cwd + "\n");
            Files.writeString(d.resolve("prompt-1.md"), "\n  Fix the flaky test\nand say why\n");
            recordedTask("t10-cmd", Tasks.COMMAND, false);
            var entries = TaskJoin.parse(sh(TaskScripts.joinable(), ""));
            assertEquals(List.of(id), entries.stream().map(TaskJoin.Entry::id).toList(), "only agent tasks, and the other agent's was removed");
            var join = (TaskJoin.Outcome.Join) TaskJoin.resolve(entries, null);
            assertEquals(agent.id, join.entry().agent());
            assertEquals("Fix the flaky test", join.entry().instruction());

            // The agent as a person's shell runs it: its argv is the agent's own, from wherever the shell starts.
            // (bash -c's script is argv[2], and the first argument after it is still in argv: no $0 of its own.)
            var ready = home.resolve("join-ready");
            Files.deleteIfExists(ready);
            stub(agent.id, "exec -a " + agent.id + " bash -c " + JOINED_AGENT + " \"$@\"");
            var person = start(join.entry().guestCommand());
            try {
                var agentPid = joinedAgent(ready, person);
                var status = Tasks.parse(sh(TaskScripts.status(id, 0), ""));
                assertEquals("attached", status.state(), agent.id);
                assertEquals(agentPid, status.attachedPid());
                var who = Presence.parse(sh(Presence.script(""), "").lines().toList());
                // Pi's sessions belong to their directory, and its title hides the session it opened.
                if (agent != DelegateAgent.PI) assertEquals(agentPid, who.holderOf(agent, sessionId(), "/elsewhere"), "found by the session it resumes");
                assertEquals(agentPid, who.holderOf(agent, null, cwd), "and by the directory it works in");
                var again = TaskJoin.resolve(TaskJoin.parse(sh(TaskScripts.joinable(), "")), null);
                var busy = assertInstanceOf(TaskJoin.Outcome.Busy.class, again, "a second person is not let in");
                assertEquals(agentPid, busy.entry().attachedPid());
            } finally {
                stopAll(person);
            }
            Files.walk(d).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** The folder Pi keeps {@code cwd}'s sessions in, as the guest derives it. */
    private String piProjectSessions(String cwd) throws Exception {
        return sh("c=" + ExecScript.quote(cwd) + "; " + TaskScripts.PI_PROJECT_SESSIONS, "");
    }

    /** What Claude Code leaves for the session {@link #sessionId()}: its transcript, where it keeps them. */
    private Path claudeTranscript() throws IOException {
        var project = Files.createDirectories(home.resolve(".claude/projects/-work"));
        return Files.writeString(project.resolve(sessionId() + ".jsonl"), "{}\n");
    }

    @Test
    @Timeout(60)
    void joiningAClaudeTaskWhoseTranscriptIsGoneIsRefused() throws Exception {
        var id = "t14-abc";
        var d = finishedTaskFiles(id, Tasks.AGENT);
        Files.writeString(d.resolve("session_id"), sessionId() + "\n");
        Files.writeString(d.resolve("cwd"), work.toRealPath() + "\n");
        var transcript = claudeTranscript();
        var ready = home.resolve("join-ready");
        stub("claude", "exec -a claude bash -c " + JOINED_AGENT + " \"$@\"");
        var join = (TaskJoin.Outcome.Join) TaskJoin.resolve(TaskJoin.parse(sh(TaskScripts.joinable(), "")), id);
        Files.delete(transcript);
        var refused = joinAs(join.entry().guestCommand());
        assertEquals(1, refused.exit(), refused.stderr());
        assertTrue(refused.stderr().contains("its Claude Code conversation is gone"), refused.stderr());
        assertFalse(Files.exists(ready), "Claude Code never started");
    }

    /** What Pi leaves for the session run 1 opened in {@code cwd}: its start in {@code d}, its file where Pi keeps it. */
    private Path piSessionFile(Path d, String cwd) throws IOException {
        Files.writeString(d.resolve("session_started"), "2026-10-30T12:19:25.667Z");
        var project = Files.createDirectories(home.resolve(".pi/agent/sessions/--" + cwd.substring(1).replace('/', '-') + "--"));
        return Files.writeString(project.resolve("2026-10-30T12-19-25-667Z_" + sessionId() + ".jsonl"), "{}\n");
    }

    @Test
    @Timeout(60)
    void joiningAPiTaskWhoseSessionIsGoneIsRefusedNotStartedEmpty() throws Exception {
        var cwd = work.toRealPath().toString();
        var id = "t13-pi";
        var d = finishedTaskFiles(id, Tasks.AGENT);
        Files.writeString(d.resolve("identity"), "1 pi\n");
        Files.writeString(d.resolve("session_id"), sessionId() + "\n");
        Files.writeString(d.resolve("cwd"), cwd + "\n");
        var file = piSessionFile(d, cwd);
        var ready = home.resolve("join-ready");
        stub("pi", "exec -a pi bash -c " + JOINED_AGENT + " \"$@\"");
        var join = (TaskJoin.Outcome.Join) TaskJoin.resolve(TaskJoin.parse(sh(TaskScripts.joinable(), "")), id);

        // Pi looks only in the cwd's own folder: the same session filed under another project is not this one.
        var elsewhere = Files.createDirectories(home.resolve(".pi/agent/sessions/--home-someone-other-project--"));
        Files.move(file, elsewhere.resolve(file.getFileName()));
        var misplaced = joinAs(join.entry().guestCommand());
        assertEquals(1, misplaced.exit(), misplaced.stderr());
        assertTrue(misplaced.stderr().contains("its Pi session file is gone"), misplaced.stderr());
        assertFalse(Files.exists(ready), "Pi never started");
        Files.move(elsewhere.resolve(file.getFileName()), file);
        // Pi's own derivation of that folder (getDefaultSessionDirPath in Pi 1.4.0).
        assertEquals("--home-a-b-c-d--", piProjectSessions("/home/a/b:c\\d"));

        // Gone, or replaced by a session Pi created under the same id since: either way not this conversation.
        Files.delete(file);
        for (var replacement : List.of("", "2026-10-31T08-00-00-000Z_" + sessionId() + ".jsonl")) {
            if (!replacement.isEmpty()) Files.writeString(file.resolveSibling(replacement), "{}\n");
            var refused = joinAs(join.entry().guestCommand());
            assertEquals(1, refused.exit(), refused.stderr());
            assertTrue(refused.stderr().contains("its Pi session file is gone"), refused.stderr());
            assertFalse(Files.exists(ready), "Pi never started");
        }
        Files.delete(d.resolve("session_started"));
        var unrecorded = joinAs(join.entry().guestCommand());
        assertEquals(1, unrecorded.exit());
        assertTrue(unrecorded.stderr().contains("its Pi session was never recorded"), unrecorded.stderr());
        assertFalse(Files.exists(ready));
    }

    @Test
    @Timeout(60)
    void thePiJoinGuardExpandsALeadingTildeInPisDirectoriesAsPiDoes() throws Exception {
        var cwd = work.toRealPath().toString();
        var id = "t14-pi";
        var d = finishedTaskFiles(id, Tasks.AGENT);
        Files.writeString(d.resolve("identity"), "1 pi\n");
        Files.writeString(d.resolve("session_id"), sessionId() + "\n");
        Files.writeString(d.resolve("cwd"), cwd + "\n");
        Files.writeString(d.resolve("session_started"), "2026-10-30T12:19:25.667Z");
        var name = "2026-10-30T12-19-25-667Z_" + sessionId() + ".jsonl";
        var project = piProjectSessions(cwd);
        var ready = home.resolve("join-ready");
        stub("pi", "exec -a pi bash -c " + JOINED_AGENT + " \"$@\"");
        var join = (TaskJoin.Outcome.Join) TaskJoin.resolve(TaskJoin.parse(sh(TaskScripts.joinable(), "")), id);
        // Each value, where Pi 1.4.0 (normalizePath) then keeps the session: ~ alone or a leading ~/ is the home.
        var cases = new java.util.LinkedHashMap<Map<String, String>, Path>();
        cases.put(Map.of("PI_CODING_AGENT_DIR", "~/my agent"), home.resolve("my agent/sessions").resolve(project));
        cases.put(Map.of("PI_CODING_AGENT_DIR", "~"), home.resolve("sessions").resolve(project));
        cases.put(Map.of("PI_CODING_AGENT_SESSION_DIR", "~/flat"), home.resolve("flat"));
        cases.put(Map.of("PI_CODING_AGENT_SESSION_DIR", "~/flat", "PI_CODING_AGENT_DIR", "~/unused"), home.resolve("flat"));
        cases.put(Map.of("PI_CODING_AGENT_SESSION_DIR", "", "PI_CODING_AGENT_DIR", ""), home.resolve(".pi/agent/sessions").resolve(project));
        for (var c : cases.entrySet()) {
            var file = Files.writeString(Files.createDirectories(c.getValue()).resolve(name), "{}\n");
            Files.deleteIfExists(ready);
            var joined = joinAs(join.entry().guestCommand(), c.getKey());
            assertEquals(0, joined.exit(), c.getKey() + ": " + joined.stderr());
            assertTrue(Files.exists(ready), c.getKey() + ": Pi started");
            Files.delete(file);
        }
        // ~user is not the home to Pi: the same file under the home does not count for it.
        Files.writeString(Files.createDirectories(home.resolve("x/sessions").resolve(project)).resolve(name), "{}\n");
        Files.deleteIfExists(ready);
        var refused = joinAs(join.entry().guestCommand(), Map.of("PI_CODING_AGENT_DIR", "~nobody/x"));
        assertEquals(1, refused.exit(), refused.stderr());
        assertFalse(Files.exists(ready));
    }

    @Test
    @Timeout(60)
    void theJoinChecksAgainInTheShellThatStartsTheAgent() throws Exception {
        var cwd = work.toRealPath().toString();
        var id = "t11-join";
        var d = finishedTaskFiles(id, Tasks.AGENT);
        Files.writeString(d.resolve("session_id"), sessionId() + "\n");
        claudeTranscript();
        Files.writeString(d.resolve("cwd"), cwd + "\n");
        var join = (TaskJoin.Outcome.Join) TaskJoin.resolve(TaskJoin.parse(sh(TaskScripts.joinable(), "")), id);
        var ready = home.resolve("join-ready");
        stub("claude", "exec -a claude bash -c " + JOINED_AGENT + " \"$@\"");

        // A send_message turn launched after the read: its unit runs, and current still names the finished run,
        // as between systemd-run and current being written. Any run of the task counts, not only the next.
        for (var run : List.of("2", "4")) {
            sh("systemd-run --unit=" + TaskScripts.unit(id, run) + " -- sleep 300", "");
            var refused = joinAs(join.entry().guestCommand());
            assertEquals(1, refused.exit(), refused.stderr());
            assertTrue(refused.stderr().contains("isx: a run of it is going, so not joining t11-join."), refused.stderr());
            assertFalse(Files.exists(ready), "the agent never started");
            sh("kill -TERM -- -" + Files.readString(home.resolve("units/" + TaskScripts.unit(id, run))).strip(), "");
        }
        sh("systemd-run --unit=" + TaskScripts.unit("t12-other", "1") + " -- sleep 300", "");
        // Another task's run is not this one's; the person who resumed this session from elsewhere is.
        var elsewhere = new ProcessBuilder("bash", "-c", "exec -a claude bash -c 'while read -r line; do :; done' --resume " + sessionId())
                .directory(home.toFile());
        elsewhere.environment().remove(TaskScripts.TASK_ENV);
        var resumer = elsewhere.start();
        try {
            var seen = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Presence.parse(sh(Presence.script(""), "").lines().toList()).holderOf(DelegateAgent.CLAUDE, sessionId(), "/x") == null
                    && System.nanoTime() < seen) Thread.sleep(10);
            var held = joinAs(join.entry().guestCommand());
            assertEquals(1, held.exit(), held.stderr());
            assertTrue(held.stderr().contains("a person is already in its conversation (agent pid " + resumer.pid() + ")"), held.stderr());
        } finally {
            resumer.destroy();
            assertTrue(resumer.waitFor(5, TimeUnit.SECONDS));
        }

        // Nothing in the way: the first person gets in; a second one, who read before the first started, does not.
        var first = start(join.entry().guestCommand());
        try {
            var agentPid = joinedAgent(ready, first);
            Files.delete(ready);
            var second = joinAs(join.entry().guestCommand());
            assertEquals(1, second.exit(), second.stderr());
            assertTrue(second.stderr().contains("a person is already in its conversation (agent pid " + agentPid + ")"), second.stderr());
            assertFalse(Files.exists(ready));
        } finally {
            stopAll(first);
        }

        sh("kill -TERM -- -" + Files.readString(home.resolve("units/" + TaskScripts.unit("t12-other", "1"))).strip(), "");
        stub("systemctl", "exit 1"); // systemd cannot be asked: that is not "nothing is running"
        var unknown = joinAs(join.entry().guestCommand());
        assertEquals(1, unknown.exit(), unknown.stderr());
        assertTrue(unknown.stderr().contains("systemd cannot say whether a run is going"), unknown.stderr());
        assertFalse(Files.exists(ready));
    }

    @Test
    @Timeout(60)
    void aRunAndAJoinedAgentNeverBothHoldTheConversation() throws Exception {
        var cwd = work.toRealPath().toString();
        var id = "t13-lock";
        var d = finishedTaskFiles(id, Tasks.AGENT);
        Files.writeString(d.resolve("session_id"), sessionId() + "\n");
        claudeTranscript();
        Files.writeString(d.resolve("cwd"), cwd + "\n");
        var join = (TaskJoin.Outcome.Join) TaskJoin.resolve(TaskJoin.parse(sh(TaskScripts.joinable(), "")), id);
        var ready = home.resolve("join-ready");

        // The join first: a turn send_message starts after its checks, but before the person's agent is
        // there to be seen, does not run beside it.
        stub("claude", "exec -a claude bash -c " + JOINED_AGENT + " \"$@\"");
        var person = start(join.entry().guestCommand());
        try {
            joinedAgent(ready, person);
            Files.delete(ready);
            Files.writeString(d.resolve("current"), "2\n");
            sh(TaskScripts.launch(id, 2, Tasks.AGENT, TaskScripts.agentRun(id, 2, cwd, Tasks.Profile.NONE)), "go on");
            var status = awaitFinished(id);
            assertEquals(TaskScripts.CONVERSATION_TAKEN, status.exit());
            assertTrue(Files.readString(d.resolve("stderr-2.log")).contains("a person is in this conversation"), "the run says why");
            assertFalse(Files.exists(ready), "the turn's agent never started");
        } finally {
            stopAll(person);
        }

        // The run first: a join whose checks passed before it, refused when it comes to start, and the
        // host learns of the refusal through the nonce.
        var locked = home.resolve("run-locked");
        var holder = start("D=" + TaskScripts.dir(id) + "; " + TaskScripts.CONVERSATION_LOCK + "bash -c ': > \"$HOME/run-locked\"; sleep 300'");
        try {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(locked) && holder.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(locked), "the run did not take the lock");
            var nonce = TaskJoin.nonce();
            var refused = joinAs(join.entry().guestCommand(nonce));
            assertEquals(1, refused.exit(), refused.stderr());
            assertTrue(refused.stderr().contains("a run of it, or another person, took its conversation first"), refused.stderr());
            assertFalse(Files.exists(ready));
            assertEquals("a run of it, or another person, took its conversation first\n", sh(TaskScripts.joinRefusal(id, nonce), ""));
            assertEquals("", sh(TaskScripts.joinRefusal(id, nonce), ""), "read once");
        } finally {
            stopAll(holder);
        }
        assertEquals("", sh(TaskScripts.joinRefusal(id, TaskJoin.nonce()), ""), "a join that was not refused leaves nothing");
    }

    /**
     * A joined agent's stand-in: writes its pid where the test waits for it, then waits on stdin. Its
     * argv is the agent's own (bash -c's script is argv[2]; the first argument after it is still in argv).
     */
    private static final String JOINED_AGENT = "'echo $$ > \"$HOME/join-ready\"; while read -r line; do :; done'";

    /** The pid the {@link #JOINED_AGENT} {@code person} started reported, once it has. */
    private static long joinedAgent(Path ready, Process person) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((!Files.exists(ready) || Files.readString(ready).isBlank()) && person.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(ready) && !Files.readString(ready).isBlank(), "the joined agent did not start");
        return Long.parseLong(Files.readString(ready).strip());
    }

    /** Stop {@code p} and what it started (a join's flock and agent are its children). */
    private static void stopAll(Process p) throws InterruptedException {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        assertTrue(p.waitFor(5, TimeUnit.SECONDS));
    }

    private record Joined(int exit, String stderr) {}

    /** {@code command} as a person's {@code isx shell} runs it, with no terminal and nothing on stdin. */
    private Process start(String command) throws IOException {
        return start(command, Map.of());
    }

    /** {@link #start}, with {@code env} set after the defaults are cleared. */
    private Process start(String command, Map<String, String> env) throws IOException {
        var pb = new ProcessBuilder("bash", "-c", command).directory(home.toFile());
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().remove(TaskScripts.TASK_ENV);
        // Pi's session locations: the default one under this test's home.
        pb.environment().remove("PI_CODING_AGENT_DIR");
        pb.environment().remove("PI_CODING_AGENT_SESSION_DIR");
        pb.environment().putAll(env);
        return pb.start();
    }

    /** {@link #start}, waited for: how a refused join ends. */
    private Joined joinAs(String command) throws Exception {
        return joinAs(command, Map.of());
    }

    private Joined joinAs(String command, Map<String, String> env) throws Exception {
        var p = start(command, env);
        p.getOutputStream().close();
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new AssertionError("the join neither refused nor started");
        }
        return new Joined(p.exitValue(), new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** A sleeping process with Codex's argv, ready before the probe scans /proc. */
    private Process codexPresence(List<String> args, boolean launcher, boolean managed) throws Exception {
        var ready = home.resolve("presence-ready");
        Files.deleteIfExists(ready);
        var command = new java.util.ArrayList<String>();
        if (launcher) {
            assumeNode();
            var script = home.resolve("codex-presence.cjs");
            Files.writeString(script, "require('node:fs').writeFileSync(process.env.CODEX_PRESENCE_READY, 'ready'); setTimeout(() => {}, 30000);");
            command.addAll(List.of("node", script.toString()));
        } else {
            // bash's -c is also a Codex option taking one value; the rest of argv is exact.
            command.addAll(List.of("bash", "-c", "exec -a codex bash -c ': > \"$CODEX_PRESENCE_READY\"; while read -r line; do :; done' \"$@\"", "presence"));
        }
        command.addAll(args);
        var pb = new ProcessBuilder(command).directory(home.toFile());
        pb.environment().put("CODEX_PRESENCE_READY", ready.toString());
        if (managed) pb.environment().put(TaskScripts.TASK_ENV, "another-task");
        else pb.environment().remove(TaskScripts.TASK_ENV);
        var proc = pb.start();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(ready) && proc.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        if (!Files.exists(ready)) {
            proc.destroyForcibly();
            throw new AssertionError("Codex presence stand-in did not start: " + args);
        }
        return proc;
    }
}
