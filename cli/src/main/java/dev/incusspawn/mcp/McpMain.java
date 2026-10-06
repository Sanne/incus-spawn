package dev.incusspawn.mcp;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Metadata;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code isx mcp}: serve this session over stdio until the client goes away, then release the
 * instances it holds: they become orphans a later session may adopt, destroyed only once
 * {@code mcp.orphan-grace-hours} have passed with nobody working in them.
 *
 * <p>{@code isx mcp --caller-instance <name>} serves an isx instance instead (#915): the proxy
 * runs it for each MCP connection the instance makes to {@code mcp.isx.internal}, its stdio
 * bridged to that connection. The session is the instance, so it takes back what it held when
 * it starts and releases nothing when it ends.
 */
public final class McpMain {

    static final String INSTRUCTIONS = """
            isx gives you disposable Linux instances (full systems, not Docker containers) \
            branched copy-on-write from templates the user approved. Use them to run builds and \
            tests, to try risky changes away from the user's machine, or to hand whole tasks to \
            a Claude Code inside one (delegate). Start with list_templates, then create_instance, \
            then exec. Instances outlive this session: if you restart, list_instances shows the \
            ones you left and adopt_instance takes them back; orphans nobody adopts are destroyed \
            after a grace period. Destroy instances you are done with. Credentials are injected by \
            a proxy on the host and never enter an instance.""";

    private McpMain() {}

    /** Serve until end of input; returns the process exit code. */
    public static int run(BooleanSupplier isInitialized, String callerInstance) throws Exception {
        var guard = StdioGuard.install();
        // Re-checked per call so `isx init` can run mid-session; once true it stays true.
        var initDone = new AtomicBoolean();
        BooleanSupplier initialized = () -> {
            if (!initDone.get() && isInitialized.getAsBoolean()) initDone.set(true);
            return initDone.get();
        };

        var backend = new IncusInstanceBackend(RuntimeServices.incus(), RuntimeServices.lockManager());
        SessionId self;
        long clientPid;
        String cwd;
        if (callerInstance != null) {
            // The proxy checked the stamp; a session is never served on that word alone. The
            // grant it carries is part of the session: a successor under the name is another.
            var metadata = backend.metadata(callerInstance);
            if (!Metadata.isMcpCaller(metadata)) {
                System.err.println("isx mcp: instance '" + callerInstance + "' may not call isx mcp "
                        + "(it was not branched with --mcp-client)");
                return 78;
            }
            try {
                self = SessionId.ofInstance(callerInstance, metadata.get(Metadata.MCP_CALLER));
            } catch (IllegalArgumentException e) {
                System.err.println("isx mcp: " + e.getMessage());
                return 78;
            }
            // Its client is in the instance: no host process, no host directory to stamp.
            clientPid = -1;
            cwd = null;
        } else {
            self = SessionId.current();
            clientPid = ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L);
            cwd = Path.of("").toAbsolutePath().toString();
        }
        var owner = System.getProperty("user.name", "");
        // Read on every use: a person narrowing the config affects a running session at once.
        Supplier<McpConfig> config = () -> SpawnConfig.load().mcp();
        var alive = new CallerLiveness(backend);
        var session = new McpSession(self, owner, clientPid, cwd, backend, config, alive);
        var tasks = new Tasks(session, backend, config);
        if (initialized.getAsBoolean()) resume(session, tasks);
        var tools = new McpTools(session, backend, new TemplatePolicy(backend, config), tasks);

        var released = new AtomicBoolean();
        Runnable release = () -> {
            if (!released.compareAndSet(false, true)) return;
            var names = session.release();
            if (!names.isEmpty()) System.err.println("isx mcp: released " + String.join(", ", names));
        };
        // A client that is killed rather than closing stdin still gets its instances released
        // when it can deliver SIGTERM; after a SIGKILL, the next session notices the orphans.
        Runtime.getRuntime().addShutdownHook(new Thread(release, "isx-mcp-release"));

        var watcher = tasks.watcher();
        var server = new McpServer(new StdioTransport(guard.protocolIn, guard.protocolOut),
                requireInit(tools.all(), initialized), BuildInfo.instance().version(), INSTRUCTIONS,
                clientInfo -> {
                    session.clientName(clientInfo.path("name").asText(""));
                    if (initialized.getAsBoolean()) {
                        Thread.startVirtualThread(() -> sweepOrphans(backend, self, owner, config, alive));
                    }
                }, Map.of(TaskWatcher.CAPABILITY, watcher::start));
        watcher.sendTo(server::notify);
        server.run();
        watcher.stop();
        release.run();
        return 0;
    }

    /**
     * Take back what this instance session held before, then the tasks of those running, in the
     * background: their ids work again once read, and a stopped one's are read by start_instance.
     */
    private static void resume(McpSession session, Tasks tasks) {
        Map<String, Map<String, String>> resumed;
        try {
            resumed = session.resume();
        } catch (RuntimeException e) {
            System.err.println("isx mcp: could not list the instances this session held: " + e.getMessage());
            return;
        }
        if (resumed.isEmpty()) return;
        System.err.println("isx mcp: holding again " + String.join(", ", resumed.keySet()));
        // One exec each, independent of one another.
        resumed.forEach((name, metadata) -> {
            if (!InstanceBackend.running(metadata)) return;
            Thread.startVirtualThread(() -> {
                try {
                    tasks.adopt(name);
                } catch (RuntimeException e) {
                    System.err.println("isx mcp: could not read the tasks of " + name + ": " + e.getMessage());
                }
            });
        });
    }

    private static void sweepOrphans(InstanceBackend backend, SessionId self, String owner,
                                     Supplier<McpConfig> config, Predicate<SessionId> alive) {
        try {
            var grace = Duration.ofHours(config.get().orphanGraceHours());
            var names = Orphans.sweep(backend, self, owner, alive, grace, Instant.now(),
                    name -> Orphans.inUse(backend, name));
            if (!names.isEmpty()) {
                System.err.println("isx mcp: destroyed orphaned instances past their grace period: "
                        + String.join(", ", names));
                McpAuditLog.record(self, "reap-orphans", null, String.join(",", names), 0, "destroyed");
            }
        } catch (RuntimeException e) {
            System.err.println("isx mcp: could not check for orphaned instances: " + e.getMessage());
        }
    }

    /** Before {@code isx init} has run, every tool explains that instead of failing obscurely. */
    private static List<McpTool> requireInit(List<McpTool> tools, BooleanSupplier initialized) {
        return tools.stream().map(t -> new McpTool(t.name(), t.description(), t.inputSchema(), t.outputSchema(),
                t.annotations(), (args, ctx) -> {
                    if (!initialized.getAsBoolean()) {
                        throw new ToolError(ToolError.Code.REFUSED, "isx is not set up on this machine yet. "
                                + "Ask the user to run: isx init");
                    }
                    return t.handler().call(args, ctx);
                })).toList();
    }
}
