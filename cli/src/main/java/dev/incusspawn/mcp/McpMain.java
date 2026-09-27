package dev.incusspawn.mcp;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.SpawnConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@code isx mcp}: serve this session over stdio until the client goes away, then destroy the
 * instances it created and did not keep.
 */
public final class McpMain {

    static final String INSTRUCTIONS = """
            isx gives you disposable Linux instances (full systems, not Docker containers) \
            branched copy-on-write from templates the user approved. Use them to run builds and \
            tests, or to try risky changes, away from the user's machine. Start with \
            list_templates, then create_instance, then exec. Instances you create are destroyed \
            when this session ends unless you keep_instance them. Credentials are injected by a \
            proxy on the host and never enter an instance.""";

    private McpMain() {}

    /** Serve until end of input; returns the process exit code. */
    public static int run(BooleanSupplier isInitialized) throws Exception {
        var guard = StdioGuard.install();
        // Re-checked per call so `isx init` can run mid-session; once true it stays true.
        var initDone = new AtomicBoolean();
        BooleanSupplier initialized = () -> {
            if (!initDone.get() && isInitialized.getAsBoolean()) initDone.set(true);
            return initDone.get();
        };

        var self = SessionId.current();
        var owner = System.getProperty("user.name", "");
        var clientPid = ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L);
        var cwd = Path.of("").toAbsolutePath().toString();
        var backend = new IncusInstanceBackend(RuntimeServices.incus(), RuntimeServices.lockManager());
        // Read on every use: a person narrowing the config affects a running session at once.
        Supplier<McpConfig> config = () -> SpawnConfig.load().mcp();
        var session = new McpSession(self, owner, clientPid, cwd, backend, config);
        var tasks = new Tasks(session, backend, config);
        var tools = new McpTools(session, backend, new TemplatePolicy(backend, config), tasks);

        var reaped = new AtomicBoolean();
        Runnable reap = () -> {
            if (!reaped.compareAndSet(false, true)) return;
            var names = session.reap();
            if (!names.isEmpty()) System.err.println("isx mcp: destroyed " + String.join(", ", names));
        };
        // A client that is killed rather than closing stdin still gets its instances reaped
        // when it can deliver SIGTERM; SIGKILL is left to the next session's orphan reaper.
        Runtime.getRuntime().addShutdownHook(new Thread(reap, "isx-mcp-reaper"));

        var server = new McpServer(new StdioTransport(guard.protocolIn, guard.protocolOut),
                requireInit(tools.all(), initialized), BuildInfo.instance().version(), INSTRUCTIONS,
                clientInfo -> {
                    session.clientName(clientInfo.path("name").asText(""));
                    if (initialized.getAsBoolean()) {
                        Thread.startVirtualThread(() -> reapOrphans(backend, self, owner));
                    }
                });
        server.run();
        reap.run();
        return 0;
    }

    private static void reapOrphans(InstanceBackend backend, SessionId self, String owner) {
        try {
            var names = OrphanReaper.reap(backend, self, owner, SessionId::isAlive);
            if (!names.isEmpty()) {
                System.err.println("isx mcp: destroyed instances of ended sessions: " + String.join(", ", names));
                McpAuditLog.record(self, "reap-orphans", null, String.join(",", names), 0, "destroyed");
            }
        } catch (RuntimeException e) {
            System.err.println("isx mcp: could not check for orphaned instances: " + e.getMessage());
        }
    }

    /** Before {@code isx init} has run, every tool explains that instead of failing obscurely. */
    private static List<McpTool> requireInit(List<McpTool> tools, BooleanSupplier initialized) {
        return tools.stream().map(t -> new McpTool(t.name(), t.description(), t.inputSchema(),
                t.annotations(), (args, ctx) -> {
                    if (!initialized.getAsBoolean()) {
                        throw new ToolError("isx is not set up on this machine yet. "
                                + "Ask the user to run: isx init");
                    }
                    return t.handler().call(args, ctx);
                })).toList();
    }
}
