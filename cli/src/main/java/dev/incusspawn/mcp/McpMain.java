package dev.incusspawn.mcp;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.SpawnConfig;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@code isx mcp}: serve this session over stdio until the client goes away, then release the
 * instances it holds: they become orphans a later session may adopt, destroyed only once
 * {@code mcp.orphan-grace-hours} have passed with nobody working in them.
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
        var session = new McpSession(self, owner, clientPid, cwd, backend, config, SessionId::isAlive);
        var tasks = new Tasks(session, backend, config);
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

        var server = new McpServer(new StdioTransport(guard.protocolIn, guard.protocolOut),
                requireInit(tools.all(), initialized), BuildInfo.instance().version(), INSTRUCTIONS,
                clientInfo -> {
                    session.clientName(clientInfo.path("name").asText(""));
                    if (initialized.getAsBoolean()) {
                        Thread.startVirtualThread(() -> sweepOrphans(backend, self, owner, config));
                    }
                });
        server.run();
        release.run();
        return 0;
    }

    private static void sweepOrphans(InstanceBackend backend, SessionId self, String owner,
                                     Supplier<McpConfig> config) {
        try {
            var grace = Duration.ofHours(config.get().orphanGraceHours());
            var names = Orphans.sweep(backend, self, owner, SessionId::isAlive, grace, Instant.now(),
                    name -> attended(backend, name));
            if (!names.isEmpty()) {
                System.err.println("isx mcp: destroyed orphaned instances past their grace period: "
                        + String.join(", ", names));
                McpAuditLog.record(self, "reap-orphans", null, String.join(",", names), 0, "destroyed");
            }
        } catch (RuntimeException e) {
            System.err.println("isx mcp: could not check for orphaned instances: " + e.getMessage());
        }
    }

    /** Whether a person works in the instance; true when it cannot be looked into (e.g. stopped). */
    private static boolean attended(InstanceBackend backend, String name) {
        try {
            var out = new ByteArrayOutputStream();
            if (backend.exec(name, Presence.script(""), null, out, null) != 0) return true;
            return Presence.parse(out.toString(StandardCharsets.UTF_8).lines().toList()).attended();
        } catch (RuntimeException e) {
            return true;
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
