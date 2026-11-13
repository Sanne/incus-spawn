package dev.incusspawn.mcp;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.RuntimeServices;
import dev.incusspawn.command.BaseCommand;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.McpConfig;
import dev.incusspawn.config.McpScope;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Metadata;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@code isx mcp}: serve this session over stdio until the client goes away, then release the
 * instances it holds: they become orphans a later session may adopt, destroyed only once
 * {@code orphan-grace-hours} in {@code mcp.yaml} have passed with nobody working in them.
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
            Claude Code, Codex, Pi or opencode inside one (delegate). Start with list_templates, then create_instance, \
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
        final Function<McpConfig, McpScope> scope;
        if (callerInstance != null) {
            // The proxy checked the stamp; a session is never served on that word alone. The
            // grant it carries is part of the session: a successor under the name is another.
            var metadata = backend.metadata(callerInstance);
            if (!Metadata.isMcpCaller(metadata)) {
                McpLog.log("instance '" + callerInstance + "' may not call isx mcp "
                        + "(it was not branched with --mcp-client)");
                return 78;
            }
            try {
                self = SessionId.ofInstance(callerInstance, metadata.get(Metadata.MCP_CALLER));
            } catch (IllegalArgumentException e) {
                McpLog.log(BaseCommand.failure(e));
                return 78;
            }
            // Its client is in the instance: no host process, no host directory to stamp.
            clientPid = -1;
            cwd = null;
            scope = scopeFor(backend, callerInstance, ImageDef::loadTrusted);
        } else {
            self = SessionId.current();
            scope = scopeFor(backend, null, ImageDef::loadTrusted);
            clientPid = ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L);
            cwd = Path.of("").toAbsolutePath().toString();
        }
        var owner = System.getProperty("user.name", "");
        // Read on every use: a person narrowing the policy affects a running session at once.
        Supplier<McpConfig> config = McpMain::policy;
        warnAboutPolicy(scope);
        var alive = new CallerLiveness(backend);
        var session = new McpSession(self, owner, clientPid, cwd, backend, config, scope, alive);
        var tasks = new Tasks(session, backend, config);
        if (initialized.getAsBoolean()) resume(session, tasks);
        var tools = new McpTools(session, backend, new TemplatePolicy(backend, config, scope), tasks);

        var released = new AtomicBoolean();
        Runnable release = () -> {
            if (!released.compareAndSet(false, true)) return;
            var names = session.release();
            if (!names.isEmpty()) McpLog.log("released " + String.join(", ", names));
        };
        // A client that is killed rather than closing stdin still gets its instances released
        // when it can deliver SIGTERM; after a SIGKILL, the next session notices the orphans.
        Runtime.getRuntime().addShutdownHook(new Thread(release, "isx-mcp-release"));

        var watcher = tasks.watcher();
        var server = new McpServer(new StdioTransport(guard.protocolIn, guard.protocolOut),
                requireInitAndTakeBack(tools.all(), initialized, () -> takeBack(session, tasks)), McpPrompt.ALL, BuildInfo.instance().version(), INSTRUCTIONS,
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
     * What the session of {@code callerInstance} may use of a config (#1812): {@code mcp.yaml}'s
     * list for the host's own session (null), else only what the caller's template also lists.
     * Its lineage once, by the proxy's own judgement; its template's definition on every call,
     * from {@code definitions} (the trusted layers), so an edit reaches a running coordinator.
     */
    static Function<McpConfig, McpScope> scopeFor(IncusInstanceBackend backend, String callerInstance,
                                                  Supplier<Map<String, ImageDef>> definitions) {
        if (callerInstance == null) return c -> McpScope.host(c.templates());
        var lineage = backend.lineage(callerInstance);
        return c -> McpScope.coordinator(lineage, definitions.get(), c.templates());
    }

    /**
     * The host user's {@code mcp.yaml} as it is now. One that cannot be used refuses the call that
     * needed it: never the policy it held before, never an {@code mcp:} section in config.yaml.
     */
    static McpConfig policy() {
        try {
            return McpConfig.load();
        } catch (McpConfig.Invalid e) {
            throw new ToolError(ToolError.Code.REFUSED, e.getMessage() + ". Ask the user to fix it.");
        }
    }

    /** Say at start what the user has to fix, before an agent meets it as a refusal. */
    private static void warnAboutPolicy(Function<McpConfig, McpScope> scope) {
        try {
            var policy = McpConfig.load();
            var retired = policy.retiredKeysNotice();
            if (retired != null) McpLog.log(retired);
            var scoped = scope.apply(policy);
            if (scoped.isCoordinator()) {
                McpLog.log("this coordinator may control: " + scoped.summary());
                scoped.mismatches().forEach(McpLog::log);
            }
            var legacy = McpConfig.legacySectionNotice(SpawnConfig.load());
            if (legacy != null) McpLog.log(legacy);
        } catch (McpConfig.Invalid e) {
            McpLog.log(BaseCommand.failure(e));
        }
    }

    /** {@link #takeBack} as the connection opens, logging a failure: the first tool call meets it again. */
    private static void resume(McpSession session, Tasks tasks) {
        try {
            takeBack(session, tasks);
        } catch (ToolError e) {
            McpLog.log("not taking back the instances this session held yet: " + e.getMessage());
        }
    }

    /**
     * Take back what this instance session held before, then the tasks of those running, in the
     * background: their ids work again once read, and a stopped one's are read by start_instance.
     * Throws while it cannot (#1514): a tool call that went ahead without them would neither list
     * them nor count them against {@code max-instances}.
     */
    static void takeBack(McpSession session, Tasks tasks) {
        Map<String, Map<String, String>> resumed;
        try {
            resumed = session.resumeIfPending();
        } catch (RuntimeException e) {
            // A ToolError says what to do (mcp.yaml refused: ask the user); anything else may not pass.
            if (e instanceof ToolError) throw e;
            throw new ToolError(ToolError.Code.UNAVAILABLE, "could not take back the instances this session held ("
                    + e.getMessage() + "); every call tries again until it can. If this persists, ask the user to look at it.");
        }
        if (resumed.isEmpty()) return;
        McpLog.log("holding again " + String.join(", ", resumed.keySet()));
        // One exec each, independent of one another.
        resumed.forEach((name, metadata) -> {
            if (!InstanceBackend.running(metadata)) return;
            Thread.startVirtualThread(() -> {
                try {
                    tasks.adopt(name);
                } catch (RuntimeException e) {
                    McpLog.log("could not read the tasks of " + name + ": " + e.getMessage());
                }
            });
        });
    }

    private static void sweepOrphans(InstanceBackend backend, SessionId self, String owner,
                                     Supplier<McpConfig> config, Predicate<SessionId> alive) {
        try {
            var swept = Orphans.sweep(backend, self, owner, alive, Orphans.Windows.of(config.get()), Instant.now(),
                    name -> Orphans.probe(backend, name));
            var names = swept.destroyed();
            if (!names.isEmpty()) {
                McpLog.log("destroyed orphaned instances past their grace period: "
                        + String.join(", ", names));
                McpAuditLog.record(self, "reap-orphans", null, String.join(",", names), 0, "destroyed");
            }
            if (!swept.stopped().isEmpty()) {
                McpAuditLog.record(self, "reap-orphans", null, String.join(",", swept.stopped()), 0, "stopped(dormant)");
            }
        } catch (RuntimeException e) {
            McpLog.log("could not check for orphaned instances: " + e.getMessage());
        }
    }

    /**
     * Before {@code isx init} has run, every tool explains that instead of failing obscurely.
     * After, each call first runs {@code takeBack}, failing with its error: what a session could
     * not take back when it connected, it takes back on its next call (#1514).
     */
    static List<McpTool> requireInitAndTakeBack(List<McpTool> tools, BooleanSupplier initialized, Runnable takeBack) {
        return tools.stream().map(t -> new McpTool(t.name(), t.description(), t.inputSchema(), t.outputSchema(),
                t.annotations(), (args, ctx) -> {
                    if (!initialized.getAsBoolean()) {
                        throw new ToolError(ToolError.Code.REFUSED, "isx is not set up on this machine yet. "
                                + "Ask the user to run: isx init");
                    }
                    takeBack.run();
                    return t.handler().call(args, ctx);
                })).toList();
    }
}
