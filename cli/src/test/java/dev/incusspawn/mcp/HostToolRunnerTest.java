package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostToolRunnerTest {

    private final HostToolRunner runner = new HostToolRunner();

    private static McpConfig.HostTool tool(String command) {
        return new McpConfig.HostTool("test-tool", command, "A test tool",
                60, 0);
    }

    private static McpConfig.HostTool tool(String command, int timeout) {
        return new McpConfig.HostTool("test-tool", command, "A test tool",
                timeout, 0);
    }

    @Test
    void exitZeroWithStdout() {
        var result = runner.run(tool("echo hello"));
        assertEquals(0, result.exitCode());
        assertEquals("hello\n", result.stdout());
        assertEquals("", result.stderr());
        assertFalse(result.timedOut());
        assertFalse(result.truncated());
        assertTrue(result.durationMs() >= 0);
    }

    @Test
    void exitNonZero() {
        var result = runner.run(tool("exit 42"));
        assertEquals(42, result.exitCode());
    }

    @Test
    void stderrIsCaptured() {
        var result = runner.run(tool("echo err >&2"));
        assertEquals("err\n", result.stderr());
        assertEquals("", result.stdout());
    }

    @Test
    void timeout() {
        var result = runner.run(tool("sleep 60", 1));
        assertTrue(result.timedOut());
    }

    @Test
    void concurrencyGuardRefusesOverlapWhenRateLimited() throws Exception {
        var slow = new McpConfig.HostTool("slow", "sleep 10", "Slow",
                60, 10);
        var started = new java.util.concurrent.CountDownLatch(1);
        var thread = Thread.startVirtualThread(() -> {
            started.countDown();
            runner.run(slow);
        });
        started.await();
        Thread.sleep(200);
        var error = assertThrows(ToolError.class, () -> runner.run(slow));
        assertTrue(error.getMessage().contains("already running"), error.getMessage());
        thread.interrupt();
    }

    @Test
    void concurrencyBlocksWhenNotRateLimited() throws Exception {
        var fast = new McpConfig.HostTool("fast", "sleep 0.3", "Fast",
                60, 0);
        var started = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread.startVirtualThread(() -> {
            started.countDown();
            runner.run(fast);
            done.set(true);
        });
        started.await();
        Thread.sleep(100);
        assertFalse(done.get(), "first call should still be running");
        var result = runner.run(fast);
        assertEquals(0, result.exitCode(), "second call should succeed after first finishes");
    }

    @Test
    void minIntervalRefusesTooSoon() {
        var limited = new McpConfig.HostTool("limited", "echo ok", "Limited",
                60, 10);
        runner.run(limited);
        var error = assertThrows(ToolError.class, () -> runner.run(limited));
        assertTrue(error.getMessage().contains("wait"), error.getMessage());
    }

    @Test
    void tildeExpansionWorksThroughBash() {
        var result = runner.run(tool("echo ~"));
        assertFalse(result.stdout().strip().equals("~"), "bash should expand ~");
    }

    @Test
    void isxHostToolEnvIsSet() {
        var result = runner.run(tool("echo $ISX_HOST_TOOL"));
        assertEquals("test-tool\n", result.stdout());
    }
}
