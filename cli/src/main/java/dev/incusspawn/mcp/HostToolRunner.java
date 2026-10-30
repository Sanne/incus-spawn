package dev.incusspawn.mcp;

import dev.incusspawn.config.McpConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Runs user-defined host tools from {@code host-tools:} in {@code mcp.yaml}. Each tool's command
 * is fixed by the user's configuration; the agent can only trigger it, never influence the command
 * string. Only this class may use {@code ProcessBuilder} in the {@code mcp} package
 * ({@code McpNoWritePathTest}).
 */
final class HostToolRunner {

    static final int DEFAULT_MAX_OUTPUT = 64 * 1024;

    record Result(int exitCode, String stdout, String stderr,
                  long stdoutBytes, long stderrBytes,
                  long durationMs, boolean timedOut, boolean truncated) {}

    private final Map<String, Semaphore> slots = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFinished = new ConcurrentHashMap<>();

    HostToolRunner() {}

    /**
     * Run a host tool. When {@code min-interval > 0}, concurrent invocations and calls that come
     * too soon are refused. When {@code min-interval == 0}, a concurrent call blocks until the
     * running one finishes.
     */
    Result run(McpConfig.HostTool tool) {
        checkInterval(tool);
        acquireSlot(tool);
        try {
            return execute(tool);
        } finally {
            slotFor(tool.name()).release();
            lastFinished.put(tool.name(), System.currentTimeMillis());
        }
    }

    private void checkInterval(McpConfig.HostTool tool) {
        if (tool.minInterval() <= 0) return;
        var last = lastFinished.get(tool.name());
        if (last == null) return;
        var elapsed = System.currentTimeMillis() - last;
        var required = tool.minInterval() * 1000L;
        if (elapsed < required) {
            var wait = (required - elapsed + 999) / 1000;
            throw new ToolError(ToolError.Code.REFUSED, "host tool '" + tool.toolName()
                    + "' was invoked less than " + tool.minInterval() + "s ago; wait "
                    + wait + "s before calling it again.");
        }
    }

    private void acquireSlot(McpConfig.HostTool tool) {
        var slot = slotFor(tool.name());
        if (tool.minInterval() > 0) {
            if (!slot.tryAcquire()) {
                throw new ToolError(ToolError.Code.REFUSED, "host tool '" + tool.toolName()
                        + "' is already running; wait for it to finish before calling it again.");
            }
        } else {
            slot.acquireUninterruptibly();
        }
    }

    private Semaphore slotFor(String name) {
        return slots.computeIfAbsent(name, k -> new Semaphore(1));
    }

    private static Result execute(McpConfig.HostTool tool) {
        var out = new TailBuffer(DEFAULT_MAX_OUTPUT);
        var err = new TailBuffer(DEFAULT_MAX_OUTPUT);
        var start = System.nanoTime();
        boolean timedOut = false;
        int exitCode;
        try {
            var pb = new ProcessBuilder("bash", "-c", tool.command());
            pb.environment().put("ISX_HOST_TOOL", tool.name());
            pb.redirectErrorStream(false);
            var process = pb.start();
            process.getOutputStream().close();
            var stdoutReader = Thread.startVirtualThread(() -> {
                try (var is = process.getInputStream()) {
                    is.transferTo(out);
                } catch (Exception ignored) {}
            });
            var stderrReader = Thread.startVirtualThread(() -> {
                try (var is = process.getErrorStream()) {
                    is.transferTo(err);
                } catch (Exception ignored) {}
            });
            if (tool.timeout() > 0) {
                if (!process.waitFor(tool.timeout(), TimeUnit.SECONDS)) {
                    timedOut = true;
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } else {
                process.waitFor();
            }
            exitCode = process.exitValue();
            stdoutReader.join(2000);
            stderrReader.join(2000);
        } catch (Exception e) {
            throw new ToolError(ToolError.Code.UNAVAILABLE,
                    "host tool '" + tool.toolName() + "' failed to run: " + e.getMessage());
        }
        var durationMs = (System.nanoTime() - start) / 1_000_000;
        return new Result(exitCode, out.text(), err.text(),
                out.total(), err.total(), durationMs, timedOut,
                out.truncated() || err.truncated());
    }
}
