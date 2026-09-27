package dev.incusspawn.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * Runs the task scripts in a real local bash, with stand-ins for what the instance provides:
 * {@code sudo}, {@code systemd-run}/{@code systemctl} (a unit is a background process group),
 * {@code su} and a {@code claude} that speaks stream-json.
 */
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
            sid=sess-1
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
                p=$(cat "$HOME/units/$2" 2>/dev/null)
                case "$1" in
                  is-active) if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then echo active; else echo inactive; exit 3; fi;;
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
        var pb = new ProcessBuilder("bash", "-c", script).directory(home.toFile());
        pb.environment().put("HOME", home.toString());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var p = pb.start();
        p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
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
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(), 30), true),
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
        sh(TaskScripts.launch(id, 2, Tasks.AGENT, TaskScripts.agentRun(id, 2, work.toString(), null), false),
                "now open a PR");
        var second = awaitFinished(id);
        assertEquals(2, second.run());
        assertEquals("Done: now open a PR", StreamJsonEvents.summarize(second.output(), 1).resultText());
        assertTrue(Files.readString(home.resolve("claude-args")).contains("--resume sess-1"));
    }

    @Test
    void aPathLimitsTheDiffAndATooLargeOneReturnsOnlyTheSummary() throws Exception {
        var id = "t2-abc";
        sh(TaskScripts.launch(id, 1, Tasks.AGENT, TaskScripts.agentRun(id, 1, work.toString(), null), true), "go");
        awaitFinished(id);
        var only = sh(TaskScripts.diff(id, "tracked.txt", 100_000), "");
        assertTrue(only.contains("+more") && !only.contains("NEW_FILE"), only);
        var small = sh(TaskScripts.diff(id, null, 10), "");
        assertTrue(small.contains("(too large"), small);
        assertTrue(small.contains("tracked.txt"), "the stat summary is still there: " + small);
    }

    @Test
    void aBackgroundCommandRecordsItsOutputAndExitCode() throws Exception {
        var id = "t3-abc";
        sh(TaskScripts.launch(id, 1, Tasks.COMMAND,
                TaskScripts.commandRun(id, work.toString(), Map.of("GREETING", "it's me"),
                        "echo \"$GREETING\"; echo oops >&2; exit 4"), false), "");
        var status = awaitFinished(id);
        assertEquals(4, status.exit());
        assertEquals("command", status.kind());
        assertTrue(status.output().startsWith("it's me"), status.output());
        assertTrue(status.stderr().startsWith("oops"), status.stderr());
    }

    @Test
    void cancellingStopsARunningTask() throws Exception {
        var id = "t4-abc";
        sh(TaskScripts.launch(id, 1, Tasks.COMMAND,
                TaskScripts.commandRun(id, work.toString(), Map.of(), "sleep 300 & sleep 300"), false), "");
        assertEquals("running", Tasks.parse(sh(TaskScripts.status(id, 1000), "")).state());
        sh(TaskScripts.cancel(id), "");
        var status = Tasks.parse(sh(TaskScripts.status(id, 1000), ""));
        assertEquals("finished", status.state());
        assertEquals(143, status.exit());
    }

    @Test
    void aUnitStateNobodyCouldReadIsUnknownNotLost() {
        assertEquals("unknown", Tasks.parse("run=1\nkind=command\nunit=\n---\n\n---stderr\n").state());
        assertEquals("lost", Tasks.parse("run=1\nkind=command\nunit=inactive\n---\n\n---stderr\n").state());
    }

    @Test
    void aTaskThatHasWrittenNothingYetStillHasAStatus() throws Exception {
        var d = Files.createDirectories(home.resolve(".isx-mcp/tasks/t5-abc"));
        Files.writeString(d.resolve("current"), "1\n");
        Files.writeString(d.resolve("kind"), "agent\n");
        assertEquals(1, Tasks.parse(sh(TaskScripts.status("t5-abc", 1000), "")).run());
    }

    @Test
    void anUnknownTaskIsLost() throws Exception {
        assertEquals("lost", Tasks.parse(sh(TaskScripts.status("t9-zzz", 1000), "")).state());
    }
}
