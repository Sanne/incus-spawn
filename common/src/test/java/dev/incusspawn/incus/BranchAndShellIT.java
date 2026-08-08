package dev.incusspawn.incus;

import org.junit.jupiter.api.*;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for container branching (copy) and exec/shell operations.
 * Exercises the full lifecycle: launch → exec → copy → start → exec → cleanup.
 * Works on both Linux (Unix socket) and macOS (HTTPS via VM) transports.
 *
 * Run with:
 *   mvn verify -DskipITs=false -Dit.test=BranchAndShellIT
 *
 * On macOS, the VM must be running (isx vm start).
 * Skips gracefully if Incus is not reachable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BranchAndShellIT {

    private static final String CONTAINER = "isx-it-" + Integer.toHexString(ThreadLocalRandom.current().nextInt() >>> 1);
    private static final String BRANCH = CONTAINER + "-branch";
    private static final String IMAGE = "images:alpine/edge";

    private static IncusClient client;

    @BeforeAll
    static void setUp() {
        client = new IncusClient();
        var error = client.checkConnectivity();
        Assumptions.assumeTrue(error == null,
                "Incus not reachable — skipping: " + error);
    }

    @AfterAll
    static void tearDown() {
        if (client == null) return;
        for (var name : new String[]{CONTAINER, BRANCH}) {
            try { client.delete(name, true); } catch (Exception ignored) {}
        }
    }

    @Test @Order(1)
    void launchContainer() {
        client.launch(IMAGE, CONTAINER, false);
        assertTrue(client.exists(CONTAINER));
        client.waitForReady(CONTAINER);
    }

    @Test @Order(2)
    void execCaptureStdout() {
        Assumptions.assumeTrue(client.exists(CONTAINER));
        var result = client.shellExec(CONTAINER, "echo", "hello-from-incus");
        assertTrue(result.success(), "exit code should be 0");
        assertEquals("hello-from-incus", result.stdout().strip());
    }

    @Test @Order(3)
    void execCaptureStderr() {
        Assumptions.assumeTrue(client.exists(CONTAINER));
        var result = client.shellExec(CONTAINER, "sh", "-c", "echo oops >&2; exit 0");
        assertTrue(result.success());
        assertTrue(result.stderr().strip().contains("oops"),
                "stderr should contain 'oops', got: " + result.stderr());
    }

    @Test @Order(4)
    void execNonZeroExitCode() {
        Assumptions.assumeTrue(client.exists(CONTAINER));
        var result = client.shellExec(CONTAINER, "sh", "-c", "exit 42");
        assertFalse(result.success());
        assertEquals(42, result.exitCode());
    }

    @Test @Order(5)
    void execRunsAsRoot() {
        Assumptions.assumeTrue(client.exists(CONTAINER));
        var result = client.shellExec(CONTAINER, "id", "-u");
        assertTrue(result.success());
        assertEquals("0", result.stdout().strip());
    }

    @Test @Order(6)
    void copyInstance() {
        Assumptions.assumeTrue(client.exists(CONTAINER));
        client.stop(CONTAINER);
        client.copy(CONTAINER, BRANCH);
        assertTrue(client.exists(BRANCH));
    }

    @Test @Order(7)
    void startBranch() {
        Assumptions.assumeTrue(client.exists(BRANCH));
        client.start(BRANCH);
        assertTrue(client.pollUntilReady(BRANCH, 30, "true"),
                "Branch should become ready within 30s");
    }

    @Test @Order(8)
    void execInBranch() {
        Assumptions.assumeTrue(client.exists(BRANCH));
        var result = client.shellExec(BRANCH, "echo", "hello-from-branch");
        assertTrue(result.success());
        assertEquals("hello-from-branch", result.stdout().strip());
    }

    @Test @Order(9)
    void configRoundtrip() {
        Assumptions.assumeTrue(client.exists(BRANCH));
        client.configSet(BRANCH, "user.test-key", "test-value-42");
        var value = client.configGet(BRANCH, "user.test-key");
        assertEquals("test-value-42", value);
    }

    @Test @Order(10)
    void stopAndDelete() {
        for (var name : new String[]{BRANCH, CONTAINER}) {
            if (!client.exists(name)) continue;
            client.delete(name, true);
            assertFalse(client.exists(name), name + " should not exist after delete");
        }
    }
}
