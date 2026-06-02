package dev.incusspawn.incus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the IncusClient high-level API.
 * Exercises the paths not covered by IncusApiLiveTest: pollUntilReady,
 * shellExecInteractiveAsUser, execBidirectional, and execPty.
 *
 * Run with socket access:
 *   sg incus-admin -c "mvn test -Dtest=IncusClientSmokeTest"
 *
 * Requires the test-alpine image to be cached:
 *   incus image copy images:alpine/edge local: --alias test-alpine
 */
class IncusClientSmokeTest {

    private static final String IMAGE = "test-alpine";
    // Container name shared across tests; created once in @BeforeAll, deleted in @AfterAll.
    private static final String CONTAINER = "isx-smoke-" + (System.currentTimeMillis() % 100000);

    private static IncusClient client;
    private static boolean available;

    @BeforeAll
    static void setUp() {
        // IncusClient can be instantiated directly; CDI annotations are irrelevant for unit/live tests.
        client = new IncusClient();

        // Skip cleanly if Incus socket isn't accessible or image isn't cached.
        if (client.checkConnectivity() != null) {
            System.out.println("Skipping smoke tests: Incus daemon not accessible.");
            available = false;
            return;
        }
        if (!imageAvailable()) {
            System.out.println("Skipping smoke tests: image '" + IMAGE + "' not cached. " +
                    "Run: incus image copy images:alpine/edge local: --alias " + IMAGE);
            available = false;
            return;
        }

        // Create and start a shared container for the session.
        // Use IncusApi directly so we can set security.privileged=true (required
        // for nested Incus environments that lack a functional idmap setup).
        System.out.println("Creating smoke test container: " + CONTAINER);
        var http = IncusApi.tryConnect();
        var imgsResp = http.get("/1.0/images/aliases/" + IMAGE);
        var fingerprint = imgsResp.body().path("metadata").path("target").asText();

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("name", CONTAINER);
        body.put("type", "container");
        body.put("source", java.util.Map.of("type", "image", "fingerprint", fingerprint));
        body.put("config", java.util.Map.of("security.privileged", "true"));
        http.requestAndWait("POST", "/1.0/instances", body);
        http.requestAndWait("PUT", "/1.0/instances/" + CONTAINER + "/state",
                java.util.Map.of("action", "start", "timeout", 30, "force", false));

        // waitForReady is itself under test — call it here to confirm it doesn't throw.
        client.waitForReady(CONTAINER);

        // Container.runAsUser hardcodes "bash --login -c"; production templates use Fedora
        // which always has bash. Alpine's busybox doesn't include bash, and symlinking
        // /bin/bash -> /bin/sh fails because busybox dispatches by argv[0] and rejects
        // "bash". Create a real wrapper script at /usr/local/bin/bash (highest priority
        // in Incus's default PATH) that execs /bin/sh, so argv[0] is the script path.
        client.shellExec(CONTAINER, "sh", "-c",
                "command -v bash >/dev/null 2>&1 || " +
                "{ printf '#!/bin/sh\\nexec /bin/sh \"$@\"\\n' > /usr/local/bin/bash " +
                "&& chmod +x /usr/local/bin/bash; }");
        // Create the agentuser that several tests depend on.
        var addUser = client.shellExec(CONTAINER, "adduser", "-D", "agentuser");
        if (!addUser.success()) {
            addUser = client.shellExec(CONTAINER, "useradd", "-m", "agentuser");
        }
        addUser.assertSuccess("Failed to create agentuser");
        available = true;
        System.out.println("Container ready.");
    }

    @AfterAll
    static void tearDown() {
        if (available) {
            try {
                client.delete(CONTAINER, true);
                System.out.println("Smoke test container deleted.");
            } catch (Exception e) {
                System.err.println("Warning: cleanup failed: " + e.getMessage());
            }
        }
    }

    private static boolean skip() {
        return !available;
    }

    private static boolean imageAvailable() {
        var resp = IncusApi.tryConnect();
        if (resp == null) return false;
        var imgs = resp.get("/1.0/images/aliases/" + IMAGE);
        return imgs.isSuccess();
    }

    // =========================================================================
    // waitForReady / pollUntilReady
    // =========================================================================

    @Test
    void waitForReadyDoesNotThrowOnRunningContainer() {
        if (skip()) return;
        // pollUntilReady fix: must not propagate IncusException when container IS running.
        assertDoesNotThrow(() -> client.waitForReady(CONTAINER),
                "waitForReady should not throw on a running container");
    }

    @Test
    void pollUntilReadyReturnsTrueWhenCommandSucceeds() {
        if (skip()) return;
        assertTrue(client.pollUntilReady(CONTAINER, 5, "true"),
                "pollUntilReady should return true when 'true' exits 0");
    }

    @Test
    void pollUntilReadyReturnsFalseWhenCommandAlwaysFails() {
        if (skip()) return;
        assertFalse(client.pollUntilReady(CONTAINER, 3, "false"),
                "pollUntilReady should return false when command always exits non-zero");
    }

    @Test
    void pollUntilReadyDoesNotThrowOnExecError() {
        if (skip()) return;
        // Regression test for the bug where an IncusException from execCapture would
        // propagate out of the retry loop instead of being treated as "not ready".
        // We can't easily simulate a 400 response, but we verify the try/catch is present
        // by ensuring a failing command causes a retry (returns false) rather than an exception.
        assertDoesNotThrow(() -> client.pollUntilReady(CONTAINER, 2, "sh", "-c", "exit 1"));
    }

    // =========================================================================
    // shellExec / shellExecInteractive
    // =========================================================================

    @Test
    void shellExecCapturesStdoutAndExitCode() {
        if (skip()) return;
        var result = client.shellExec(CONTAINER, "sh", "-c", "echo smoketest-out; exit 7");
        assertEquals(7, result.exitCode(), "Exit code should be 7");
        assertTrue(result.stdout().contains("smoketest-out"), "stdout: " + result.stdout());
    }

    @Test
    void shellExecCapturesStderr() {
        if (skip()) return;
        var result = client.shellExec(CONTAINER, "sh", "-c", "echo smoketest-err >&2; exit 0");
        assertTrue(result.stderr().contains("smoketest-err"), "stderr: " + result.stderr());
    }

    @Test
    void shellExecInteractiveStreamsOutput() throws Exception {
        if (skip()) return;
        // shellExecInteractive writes to System.out/err; redirect temporarily.
        var capture = new ByteArrayOutputStream();
        var savedOut = System.out;
        System.setOut(new PrintStream(capture));
        try {
            int exitCode = client.shellExecInteractive(CONTAINER, "sh", "-c", "echo hello-interactive");
            assertEquals(0, exitCode);
        } finally {
            System.setOut(savedOut);
        }
        assertTrue(capture.toString().contains("hello-interactive"),
                "shellExecInteractive output: " + capture);
    }

    // =========================================================================
    // shellExecInteractiveAsUser / execInContainer
    // =========================================================================

    @Test
    void shellExecInteractiveAsUserRunsWithCorrectUid() throws Exception {
        if (skip()) return;
        // Verify the command runs as agentuser, not root.
        // Side-effect approach: create a file with agentuser's uid, then shellExec to check.
        var result = client.shellExec(CONTAINER, "sh", "-c",
                "id -u agentuser > /tmp/expected-uid.txt");
        assertEquals(0, result.exitCode());
        var expectedUid = result.stdout().strip();
        if (expectedUid.isEmpty()) expectedUid = client.shellExec(CONTAINER, "cat",
                "/tmp/expected-uid.txt").stdout().strip();

        // Run as agentuser and write actual uid to a file.
        int exitCode = client.shellExecInteractiveAsUser(CONTAINER, "agentuser",
                "id -u > /tmp/actual-uid.txt");
        assertEquals(0, exitCode, "shellExecInteractiveAsUser should exit 0");

        var actualUid = client.shellExec(CONTAINER, "cat", "/tmp/actual-uid.txt").stdout().strip();
        assertEquals(expectedUid, actualUid,
                "Should run as agentuser (uid=" + expectedUid + "), got uid=" + actualUid);
        System.out.println("shellExecInteractiveAsUser: agentuser uid=" + actualUid + " confirmed");
    }

    @Test
    void execInContainerRunsWithUserEnvironment() {
        if (skip()) return;
        // execInContainer uses 'su - user -c "cmd"' — runs as the correct user
        // with a full login environment.
        var result = client.execInContainer(CONTAINER, "agentuser", "id", "-u");
        assertEquals(0, result.exitCode(), "execInContainer should succeed");
        var uid = result.stdout().strip();
        var expected = client.shellExec(CONTAINER, "id", "-u", "agentuser").stdout().strip();
        assertEquals(expected, uid, "Should run as agentuser uid=" + expected + ", got: " + uid);
        System.out.println("execInContainer: ran as uid=" + uid + " confirmed");
    }

    @Test
    void execInContainerUsesLoginShellPath() {
        if (skip()) return;
        // Regression: 'su - user -c cmd' establishes a login session so tools installed
        // to user-controlled PATH locations (SDKMAN, ~/.profile additions) are found.
        // Simulate by placing a fake 'mvn' in ~/.profile's PATH and verifying it runs.
        client.shellExec(CONTAINER, "sh", "-c",
                "mkdir -p /home/agentuser/.local/bin && " +
                "printf '#!/bin/sh\\necho fake-mvn-ran\\n' > /home/agentuser/.local/bin/mvn && " +
                "chmod +x /home/agentuser/.local/bin/mvn && " +
                "chown -R agentuser:agentuser /home/agentuser/.local");
        // Use ~/.profile (sourced by all POSIX login shells including ash/busybox)
        // rather than ~/.bash_profile (bash-specific, not available with ash shim).
        client.shellExec(CONTAINER, "sh", "-c",
                "printf 'export PATH=$HOME/.local/bin:$PATH\\n' >> /home/agentuser/.profile && " +
                "chown agentuser:agentuser /home/agentuser/.profile");

        var result = client.execInContainer(CONTAINER, "agentuser", "mvn", "--version");
        assertEquals(0, result.exitCode(),
                "execInContainer should find tools added via login shell PATH, got: "
                + result.stdout() + " / " + result.stderr());
        assertTrue(result.stdout().contains("fake-mvn-ran"),
                "Login shell PATH should include ~/.local/bin, got: " + result.stdout());
        System.out.println("execInContainer login PATH: su - sources ~/.profile, PATH confirmed");
    }

    @Test
    void execInContainerFindsUsrLocalBin() {
        if (skip()) return;
        // Regression: su - replaces PATH with login.defs default (PATH=/bin:/usr/bin
        // on Fedora), which excludes /usr/local/bin. Tools like mvn are symlinked
        // there. The LOGIN_PATH_PREFIX fix prepends /usr/local/bin to the script.
        client.shellExec(CONTAINER, "sh", "-c",
                "printf '#!/bin/sh\\necho usr-local-bin-ok\\n' > /usr/local/bin/isx-path-test " +
                "&& chmod +x /usr/local/bin/isx-path-test");

        var result = client.execInContainer(CONTAINER, "agentuser", "isx-path-test");
        assertEquals(0, result.exitCode(),
                "execInContainer should find tools in /usr/local/bin, got: "
                + result.stdout() + " / " + result.stderr());
        assertTrue(result.stdout().contains("usr-local-bin-ok"),
                "/usr/local/bin should be on PATH, got: " + result.stdout());

        client.shellExec(CONTAINER, "rm", "-f", "/usr/local/bin/isx-path-test");
        System.out.println("execInContainer: /usr/local/bin on PATH confirmed");
    }

    // =========================================================================
    // execBidirectional — simulated with 'cat' (echoes stdin to stdout)
    // =========================================================================

    @Test
    void execBidirectionalBridgesStdinToStdout() throws Exception {
        if (skip()) return;
        // 'cat' echoes stdin to stdout. When we close stdin, cat exits.
        // This exercises the join-order fix: stdout/stderr joined first,
        // then the 2 s timeout on the stdin thread.
        var stdinPipeOut = new PipedOutputStream();
        var stdinPipeIn  = new PipedInputStream(stdinPipeOut);
        var stdoutCapture = new ByteArrayOutputStream();
        var stderrCapture = new ByteArrayOutputStream();

        var exitCodeHolder = new AtomicInteger(-1);
        var execThread = Thread.ofVirtual().start(() ->
                exitCodeHolder.set(client.execBidirectional(
                        CONTAINER, 0, 0, "/root",
                        new String[]{"cat"},
                        stdinPipeIn, stdoutCapture, stderrCapture)));

        // Send data and close stdin; cat will echo then exit.
        stdinPipeOut.write("hello-bidirectional\n".getBytes());
        stdinPipeOut.close();

        execThread.join(10_000);
        assertFalse(execThread.isAlive(), "execBidirectional should complete within 10 s");
        assertEquals(0, exitCodeHolder.get(), "cat should exit 0");
        assertTrue(stdoutCapture.toString().contains("hello-bidirectional"),
                "stdout: " + stdoutCapture);
        System.out.println("execBidirectional: cat round-trip verified");
    }

    @Test
    void execBidirectionalCompletesAfterContainerExitsBeforeStdinEof() throws Exception {
        if (skip()) return;
        // Regression test for the hang scenario: container exits (stdout closes) while
        // stdin is still open. The fix: join stdout/stderr first, then wait max 2 s on stdin.
        // We simulate this with a command that exits immediately, while stdin is open.
        var stdinPipeOut = new PipedOutputStream();
        var stdinPipeIn  = new PipedInputStream(stdinPipeOut);
        var stdoutCapture = new ByteArrayOutputStream();

        var exitCodeHolder = new AtomicInteger(-1);
        var execThread = Thread.ofVirtual().start(() ->
                exitCodeHolder.set(client.execBidirectional(
                        CONTAINER, 0, 0, "/root",
                        new String[]{"sh", "-c", "echo done-early"},
                        stdinPipeIn, stdoutCapture, OutputStream.nullOutputStream())));

        // Container exits almost immediately; we deliberately do NOT close stdinPipeOut.
        // The fix should allow execBidirectional to return within ~2 s despite open stdin.
        execThread.join(8_000);
        assertFalse(execThread.isAlive(),
                "execBidirectional must not hang when container exits before stdin EOF");
        assertTrue(stdoutCapture.toString().contains("done-early"),
                "stdout: " + stdoutCapture);
        stdinPipeOut.close(); // cleanup
        System.out.println("execBidirectional: early-container-exit handled correctly");
    }

    // =========================================================================
    // execPty — tested with piped stdin/stdout to avoid terminal dependencies
    // =========================================================================

    @Test
    void execPtyRunsCommandAndReturnsExitCode() throws Exception {
        if (skip()) return;
        // execPty test: use InputStream.nullInputStream() for stdin (returns EOF immediately,
        // but as a virtual thread the stdinThread blocks harmlessly).
        // The watcher in execPty closes the fd0 channel when the control WebSocket closes
        // (command exited), unblocking the read loop without losing buffered output.
        var ptyCapture = new ByteArrayOutputStream();
        var savedIn  = System.in;
        var savedOut = System.out;
        System.setIn(InputStream.nullInputStream());
        System.setOut(new PrintStream(ptyCapture));
        int ptyExit;
        try {
            var http = IncusApi.tryConnect();
            assertNotNull(http, "IncusApi should be connectable");
            ptyExit = http.execPty(CONTAINER,
                    List.of("sh", "-c", "echo pty-output-ok; exit 5"),
                    0, 0, "/root", java.util.Map.of(), 80, 24);
        } finally {
            System.setIn(savedIn);
            System.setOut(savedOut);
        }
        assertEquals(5, ptyExit, "execPty exit code should be 5");
        assertTrue(ptyCapture.toString().contains("pty-output-ok"),
                "execPty output: " + ptyCapture);
        System.out.println("execPty: exit=" + ptyExit + " output confirmed");
    }

    // =========================================================================
    // Container.runAsUser — the build workhorse
    // =========================================================================

    @Test
    void containerRunAsUserExecutesScriptAsCorrectUser() throws Exception {
        if (skip()) return;
        // Container.runAsUser uses shellExecInteractiveAsUser → 'su - user -c script'.
        // This is the primary path for all tool-installation steps during isx build.
        var container = new Container(client, CONTAINER);

        // Run a script as agentuser that writes its uid to a file.
        container.runAsUser("agentuser",
                "id -u > /tmp/run-as-user-uid.txt",
                "runAsUser should succeed");

        // Verify the uid written matches agentuser's uid.
        var expectedUid = client.shellExec(CONTAINER, "id", "-u", "agentuser").stdout().strip();
        var actualUid   = client.shellExec(CONTAINER, "cat", "/tmp/run-as-user-uid.txt").stdout().strip();
        assertEquals(expectedUid, actualUid,
                "runAsUser should run as agentuser, expected uid=" + expectedUid
                + " but got uid=" + actualUid);
        System.out.println("Container.runAsUser: ran as uid=" + actualUid + " confirmed");
    }

    @Test
    void containerRunAsUserFailureThrowsIncusException() {
        if (skip()) return;
        // Verify that a non-zero exit from the script propagates as IncusException.
        var container = new Container(client, CONTAINER);
        assertThrows(IncusException.class,
                () -> container.runAsUser("agentuser", "exit 1", "expected failure"),
                "runAsUser should throw IncusException on non-zero exit");
    }

    // =========================================================================
    // copy + getInstanceStatus — the branch workflow
    // =========================================================================

    @Test
    void copyCreatesStoppedCloneWithSameConfig() throws Exception {
        if (skip()) return;
        // Set a config value on the source container, then copy it, and verify the clone
        // inherits the config. This exercises the full branch workflow path.
        var marker = "smoke-copy-marker";
        client.configSet(CONTAINER, "user.smoke-test", marker);

        String clone = CONTAINER + "-copy";
        try {
            client.copy(CONTAINER, clone);

            // Clone should exist and be Stopped (copy does not start the instance).
            assertTrue(client.exists(clone), "Cloned instance should exist");
            assertEquals("Stopped", client.getInstanceStatus(clone),
                    "Cloned instance should be Stopped after copy");

            // Config should be inherited from source.
            assertEquals(marker, client.configGet(clone, "user.smoke-test"),
                    "Cloned instance should inherit source config");

            System.out.println("copy: clone exists, Stopped, config inherited — confirmed");
        } finally {
            try { client.delete(clone, true); } catch (Exception ignored) {}
            client.configUnset(CONTAINER, "user.smoke-test");
        }
    }

    // =========================================================================
    // filePush + verify — config/artifact injection
    // =========================================================================

    @Test
    void filePushWritesContentReadableInsideContainer() throws Exception {
        if (skip()) return;
        // filePush is used during builds to inject config files and tool binaries.
        // Verify content survives the push and is readable inside the container.
        var content = "smoke-test-content-" + System.currentTimeMillis();
        var tmpFile = Files.createTempFile("isx-smoke", ".txt");
        try {
            Files.writeString(tmpFile, content);
            client.filePush(tmpFile.toString(), CONTAINER, "/tmp/smoke-push.txt");

            var result = client.shellExec(CONTAINER, "cat", "/tmp/smoke-push.txt");
            assertEquals(0, result.exitCode(), "cat of pushed file should succeed");
            assertEquals(content, result.stdout().strip(),
                    "Pushed file content should match original");
            System.out.println("filePush: content round-trip confirmed");
        } finally {
            Files.deleteIfExists(tmpFile);
            client.shellExec(CONTAINER, "rm", "-f", "/tmp/smoke-push.txt");
        }
    }

    @Test
    void filePushRecursiveWithSingleFilePushesAsDestFilename() throws Exception {
        if (skip()) return;
        // Regression test: YamlToolSetup.extractOnHostAndPush calls filePushRecursive
        // on each direct child of the extraction dir. For flat archives like Starship
        // (tar.gz containing a single binary), the child is a FILE not a directory.
        // Previously, Files.walk(file) returned the file with empty relative path,
        // which the isEmpty() guard skipped — so nothing was pushed.
        var tmpFile = Files.createTempFile("isx-smoke-binary", "");
        try {
            Files.writeString(tmpFile, "fake-binary-content");
            // Simulate: extractDir contains one file "mybinary", push to /tmp/tooldir
            client.filePushRecursive(tmpFile.toString(), CONTAINER, "/tmp/smoke-single-file-push");
            // The file should appear at /tmp/smoke-single-file-push/<filename>
            var filename = tmpFile.getFileName().toString();
            var result = client.shellExec(CONTAINER, "cat",
                    "/tmp/smoke-single-file-push/" + filename);
            assertEquals(0, result.exitCode(),
                    "Single-file push: file should exist at destPath/filename");
            assertEquals("fake-binary-content", result.stdout().strip());
            System.out.println("filePushRecursive(single file): placed at destPath/filename confirmed");
        } finally {
            Files.deleteIfExists(tmpFile);
            client.shellExec(CONTAINER, "rm", "-rf", "/tmp/smoke-single-file-push");
        }
    }

    @Test
    void filePushRecursivePushesDirectoryTree() throws Exception {
        if (skip()) return;
        // filePushRecursive mirrors 'incus file push -r': the source directory itself
        // is placed inside destPath, not just its contents. E.g. pushing 'apache-maven-3.9.15'
        // to '/opt' creates '/opt/apache-maven-3.9.15/bin/mvn'.
        var tmpDir = Files.createTempDirectory("isx-smoke-dir");
        var dirName = tmpDir.getFileName().toString();
        try {
            Files.writeString(tmpDir.resolve("a.txt"), "file-a");
            var subDir = Files.createDirectory(tmpDir.resolve("sub"));
            Files.writeString(subDir.resolve("b.txt"), "file-b");

            client.filePushRecursive(tmpDir.toString(), CONTAINER, "/tmp/smoke-push-dir");

            assertEquals("file-a",
                    client.shellExec(CONTAINER, "cat", "/tmp/smoke-push-dir/" + dirName + "/a.txt").stdout().strip(),
                    "top-level file should be at destPath/dirName/file");
            assertEquals("file-b",
                    client.shellExec(CONTAINER, "cat", "/tmp/smoke-push-dir/" + dirName + "/sub/b.txt").stdout().strip(),
                    "nested file should be at destPath/dirName/sub/file");
            System.out.println("filePushRecursive: directory tree round-trip confirmed");
        } finally {
            client.shellExec(CONTAINER, "rm", "-rf", "/tmp/smoke-push-dir");
            try (var s = Files.walk(tmpDir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    @Test
    void filePushRecursiveSymlinkTargetExists() throws Exception {
        if (skip()) return;
        // Regression: filePushRecursive previously pushed directory CONTENTS into destPath,
        // so pushing 'apache-maven-3.9.15/' to '/opt' put bin/mvn at /opt/bin/mvn instead
        // of /opt/apache-maven-3.9.15/bin/mvn. Symlinks pointing to the expected path broke.
        var tmpDir = Files.createTempDirectory("isx-maven-sim");
        var toolDir = Files.createDirectory(tmpDir.resolve("apache-maven-fake"));
        var binDir = Files.createDirectory(toolDir.resolve("bin"));
        var mvnFile = binDir.resolve("mvn");
        Files.writeString(mvnFile, "#!/bin/sh\necho fake-mvn\n");
        mvnFile.toFile().setExecutable(true);
        try {
            // filePush must preserve the executable bit — no manual chmod needed.
            client.filePushRecursive(toolDir.toString(), CONTAINER, "/opt");
            client.shellExec(CONTAINER, "ln", "-sf",
                    "/opt/apache-maven-fake/bin/mvn", "/usr/local/bin/mvn-test");

            var result = client.shellExec(CONTAINER, "/usr/local/bin/mvn-test");
            assertEquals(0, result.exitCode(),
                    "symlink target should exist at /opt/apache-maven-fake/bin/mvn: " + result.stderr());
            assertTrue(result.stdout().contains("fake-mvn"), "got: " + result.stdout());
            System.out.println("filePushRecursive: symlink-to-pushed-binary confirmed");
        } finally {
            client.shellExec(CONTAINER, "rm", "-rf", "/opt/apache-maven-fake", "/usr/local/bin/mvn-test");
            try (var s = Files.walk(tmpDir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    // =========================================================================
    // configSet / configGet round-trip through IncusClient
    // =========================================================================

    @Test
    void configSetAndGetRoundTrip() {
        if (skip()) return;
        // configSet/configGet are used to store all template metadata (type, parent,
        // build-source, etc.). Test through IncusClient (not just IncusApi directly).
        var key = "user.smoke-config-test";
        var value = "hello-from-smoke-test";
        try {
            client.configSet(CONTAINER, key, value);
            assertEquals(value, client.configGet(CONTAINER, key),
                    "configGet should return the value set by configSet");

            client.configUnset(CONTAINER, key);
            assertEquals("", client.configGet(CONTAINER, key),
                    "configGet should return empty string after configUnset");

            System.out.println("configSet/configGet/configUnset round-trip confirmed");
        } finally {
            try { client.configUnset(CONTAINER, key); } catch (Exception ignored) {}
        }
    }
}
