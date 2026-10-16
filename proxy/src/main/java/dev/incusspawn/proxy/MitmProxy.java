package dev.incusspawn.proxy;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Environment;
import dev.incusspawn.Platform;

import dev.incusspawn.config.Jjq;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;

import java.net.InetAddress;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * TLS-terminating MITM proxy for transparent credential injection.
 * <p>
 * Containers resolve intercepted domains (api.anthropic.com, github.com, etc.)
 * to the Incus bridge gateway IP via bridge-level dnsmasq. This proxy listens on port 443
 * on the gateway IP, terminates TLS using per-domain certificates signed by a
 * custom CA, injects authentication headers, and forwards to the real upstream.
 * <p>
 * Credentials never enter containers in any form. Tools (curl, git, gh, claude)
 * work completely unmodified inside containers.
 * <p>
 * Internally uses Vert.x for non-blocking I/O, connection pooling, and
 * zero-copy file serving.
 */
public class MitmProxy {

    private static final Set<String> ANTHROPIC_DOMAINS = ProxyConfig.ANTHROPIC_DOMAINS;

    private final String bindAddress;
    private final int requestedMitmPort;
    private final int requestedHealthPort;

    // The Vertex SDK's @date model suffix in a URL, which the global endpoint rejects
    private static final java.util.regex.Pattern VERTEX_DATE_SUFFIX = java.util.regex.Pattern.compile("@\\d{8}(?=:)");

    // Dropped top-level fields already logged, so each is logged once; bounded, since an instance names them
    private static final int MAX_LOGGED_DROPPED_FIELDS = 256;
    private final Set<String> loggedStrippedFields = ConcurrentHashMap.newKeySet();

    // What Vertex model calls' bodies may hold, in heap and on disk (#1315). Package-private for tests.
    SpoolBudget spoolBudget = new SpoolBudget(SpoolBudget.DEFAULT_LIMIT);
    SpoolStore spoolStore;

    // Cached GCP access token for Vertex AI (tokens last ~60 min, refresh at ~50 min).
    // Single-flight: concurrent callers share one in-flight gcloud invocation via the
    // resolving entry, mirroring the DnsEntry pattern used by resolveHost().
    private static final long VERTEX_TOKEN_TTL_MS = 50 * 60 * 1000L;
    record VertexTokenEntry(String token, long expiresAt, Future<String> inflight) {
        static VertexTokenEntry resolving(Future<String> f) { return new VertexTokenEntry(null, 0, f); }
        static VertexTokenEntry resolved(String token) {
            return new VertexTokenEntry(token, System.currentTimeMillis() + VERTEX_TOKEN_TTL_MS, null);
        }
        boolean isValid() { return token != null && System.currentTimeMillis() < expiresAt; }
        boolean isResolving() { return inflight != null; }
    }
    final AtomicReference<VertexTokenEntry> vertexToken = new AtomicReference<>();

    private final Vertx vertx;
    // Volatile: replaced on each bind attempt while stop() may read them from a shutdown hook.
    private volatile HttpServer mitmServer;
    private volatile HttpServer healthHttpServer;
    /** The certificates the MITM server serves; guarded by {@code this}, as reload() is. */
    private InterceptedCertOptions mitmCertOptions;
    // Set once each listen has succeeded: Vert.x's actualPort() reports a fixed port before the
    // bind and after a failed one, so it cannot say whether a server is listening.
    private volatile int boundMitmPort;
    private volatile int boundHealthPort;
    /** The upstream side: clients, DNS, backoff, relay, and the helpers the caches share. */
    final Upstream upstream;
    /** Registry, npm and Maven/Gradle caching; built from {@link #upstream}, never given credentials. */
    final ArtifactCacheHandler artifactCache;
    /** Created once, so a {@link #stop()} that comes before {@link #start} is not lost (#966). */
    private final CountDownLatch stopLatch = new CountDownLatch(1);
    // stopLatch drops only once stop() has closed everything, too late for a start() still
    // binding to see it. So stop() sets stopping first, and start() gets ready only while it is
    // unset, both under lifecycleLock: a start that a stop overlaps never runs onReady (#1219).
    private final Object lifecycleLock = new Object();
    private volatile boolean stopping;

    private ApiTrafficLog debugLog;
    // CA fingerprint computed at startup for the health endpoint
    private String caFingerprint = "";
    private volatile boolean dnsConfigured;
    private final Object authLock = new Object();
    volatile String authError;
    // Remediation hint for the current authError, so the health endpoint knows
    // whether the failure is one it can re-check on its own (gcloud) or one that
    // needs the user to re-run 'isx init' (OAuth).
    private String authErrorHint;
    private long authNotificationSentMs;
    private long authRevalidatedMs;
    private boolean authRevalidateInFlight;

    private final String healthBindAddress;

    private record ToolProxyRouting(
            Map<String, List<ResolvedToolProxy>> exactDomain,
            List<Map.Entry<String, List<ResolvedToolProxy>>> wildcardSuffixes,
            Set<String> allInterceptedDomains,
            List<String> suffixes
    ) {}
    /** What {@code config.yaml} and the tool definitions looked like when the running config was read. */
    private volatile ConfigFingerprint configFingerprint;
    private java.util.function.Consumer<java.util.Set<String>> bridgeDnsWriter;

    /**
     * Source address → instance → pinned accounts. Null until {@link #setIncusClient} runs,
     * in which case every caller gets the configured defaults -- which is exactly the
     * behaviour before per-instance selection existed.
     */
    private volatile InstanceRegistry instanceRegistry;

    /**
     * Each instance's model calls and all its traffic, for {@code /activity} (#898, #935).
     * Replaced only by a test, before any request, to give it a clock of its own.
     */
    ApiActivity apiActivity = new ApiActivity();

    /** {@code isx mcp} for coordinator instances, at {@link ProxyConfig#MCP_DOMAIN} (#915). */
    private volatile McpBridge mcpBridge;
    /** The names of {@code host-routes:}, forwarded to controllers on this host (#936). */
    private final HostRouteRelay hostRouteRelay;
    /** What serves one instance's MCP session; tests replace the {@code isx} it runs. */
    private java.util.function.Function<String, List<String>> mcpCommand = McpBridge::isxMcp;

    /** Tests only: serve MCP sessions with {@code command} instead of {@code isx mcp}. */
    void useMcpCommand(java.util.function.Function<String, List<String>> command) {
        this.mcpCommand = command;
    }

    /**
     * Everything derived from one read of the config: the default account's credentials and
     * routing, and what a pinned instance's request needs. Published whole, in one write, and
     * replaced -- never mutated -- by a reload, so a request never mixes two configs. The caches live inside it: a request that read the old state and finishes
     * after a reload writes into the old state's caches, which nothing reads any more, so a
     * rotated token or a changed default is never put back (#837).
     *
     * <p>Built eagerly, off the event loop, from the config ProxyMain or reload() already
     * read, never from a later read: discovering tool setups scans the filesystem, and a
     * later read of config.yaml would escape the fingerprint taken ahead of that one. (Tool
     * YAMLs under configured search paths are not fingerprinted yet -- see #890.)
     *
     * @param routing                credentials from the default account, but the intercepted
     *                               domain set from every configured account. The two differ on
     *                               purpose: which domains are intercepted drives certificate
     *                               minting and bridge DNS, which cannot vary per caller, so a
     *                               tool whose credential exists only under a named account must
     *                               still have its domains intercepted -- otherwise a pinned
     *                               instance's request is relayed with nothing injected. A pinned
     *                               caller gets its own routing from {@link #contextFor}
     * @param configTree             {@code config} serialized, once per config read: every
     *                               selection resolves against it
     * @param credentialsBySelection keyed by the selection itself, so two instances pinned the
     *                               same way share one entry, and holding a selection's refusal
     *                               as well as its credentials, so a dangling pin is not
     *                               resolved again on every request it sends
     * @param mismatchesByInstance   per instance, the credentials it cannot be served because
     *                               its build does not match the account it would get (a Claude
     *                               auth mode) -- see {@code AccountSelection.servingMismatches}.
     *                               Keyed by the registry record, so a re-pinned or rebuilt
     *                               instance is looked up afresh
     * @param hostRoutes             {@code host-routes:} as served (#936), by name, each with the
     *                               secret its controller checks; their names
     *                               are in the routing's intercepted domains, for certificates
     *                               and bridge DNS, and nowhere in its credential lookups
     * @param placeholders           each namespace's placeholders, one per prefix: the proofs
     *                               an instance inheriting it has swapped (#1110)
     * @param nested                 whether this machine was an isx instance when the domains
     *                               were decided: inside one, they include every domain whose
     *                               credential can be inherited ({@link #refreshNesting})
     */
    private record ConfigState(
            dev.incusspawn.config.SpawnConfig config,
            io.hyperfoil.tools.jjq.value.JqValue configTree,
            Map<String, dev.incusspawn.tool.ToolSetup> toolSetups,
            ProxyCredentials credentials,
            ToolProxyRouting routing,
            Map<String, dev.incusspawn.tool.ToolSetup> setupsByNamespace,
            Map<String, java.util.Set<String>> namespacesByDomain,
            Map<Map<String, String>, AccountBundle> credentialsBySelection,
            Map<InstanceRegistry.InstanceAccounts, Map<String, String>> mismatchesByInstance,
            Map<String, HostRouteRelay.Served> hostRoutes,
            Map<String, List<ProofToken.Placeholder>> placeholders,
            ProofCarriers proofCarriers, boolean nested) {

        ConfigState(dev.incusspawn.config.SpawnConfig config,
                    io.hyperfoil.tools.jjq.value.JqValue configTree,
                    Map<String, dev.incusspawn.tool.ToolSetup> toolSetups,
                    ProxyCredentials credentials,
                    List<ResolvedToolProxy> proxiesAcrossAccounts,
                    Map<String, HostRouteRelay.Served> hostRoutes,
                    boolean nested) {
            this(config, configTree, toolSetups, credentials,
                    withHostRoutes(buildRouting(credentials.toolProxies(), proxiesAcrossAccounts, true),
                            hostRoutes.keySet()),
                    dev.incusspawn.config.AccountSelection.byNamespace(toolSetups),
                    dev.incusspawn.config.AccountSelection.namespacesByDomain(toolSetups),
                    new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), hostRoutes,
                    InheritedProof.byNamespace(ProofToken.declaredBy(toolSetups.values())),
                    new ProofCarriers(toolSetups.values()), nested);
            proofCarriers.warnings().forEach(ProxyLog::warn);
        }
    }

    /** {@code routing}, intercepting the host routes' names as well. */
    private static ToolProxyRouting withHostRoutes(ToolProxyRouting routing, Set<String> names) {
        if (names.isEmpty()) return routing;
        var all = new HashSet<>(routing.allInterceptedDomains());
        all.addAll(names);
        return new ToolProxyRouting(routing.exactDomain(), routing.wildcardSuffixes(), Set.copyOf(all),
                routing.suffixes());
    }

    private volatile ConfigState configState;

    private final java.util.concurrent.atomic.AtomicBoolean registryRefreshInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * What a single request needs to know about who asked and which credentials answer.
     *
     * @param caller the instance that asked, with its incarnation as the same listing has it
     *               (for {@link ApiActivity}); null for any other caller
     * @param flow   the request's traffic in {@link ApiActivity}, which a model call ends with;
     *               null where nothing counts it
     */
    record RequestContext(String domain, InstanceRegistry.Caller caller,
                          ProxyCredentials creds, ToolProxyRouting routing,
                          boolean usesDefaultCredentials, ApiActivity.Flow flow,
                          InheritedProof.Swap inherited, boolean authorized) {}

    /**
     * Credentials plus the routing that indexes them, cached together per selection; or, for
     * a selection naming an account that is not configured, the refusal to raise instead.
     */
    private record AccountBundle(ProxyCredentials creds, ToolProxyRouting routing,
                                 dev.incusspawn.config.AccountResolver.UnknownAccountException unknown) {
        static AccountBundle of(ProxyCredentials creds) {
            return new AccountBundle(creds, buildRouting(creds.toolProxies(), creds.toolProxies(), false), null);
        }

        static AccountBundle refused(dev.incusspawn.config.AccountResolver.UnknownAccountException unknown) {
            return new AccountBundle(null, null, unknown);
        }
    }

    // Overridable for tests: upstream WebSocket connections default to port 443 + TLS
    int upstreamWsPort = 443;
    boolean upstreamWsSsl = true;
    static final int MITM_IDLE_TIMEOUT_SECONDS = 120;

    void overrideUpstream(String host, String ip, int port) {
        upstream.overrideUpstream(host, ip, port);
    }

    /** Trust a stub's certificate for upstream connections, alongside the system CAs. Set before start(). */
    void trustUpstreamCertificate(String pemPath) {
        upstream.trustUpstreamCertificate(pemPath);
    }

    /**
     * The proxy as {@code isx-proxy} runs it: credentials, routing and the per-account state
     * are all derived from {@code loaded}, the same way {@link #reload()} derives them.
     */
    public MitmProxy(Vertx vertx, String bindAddress, int mitmPort, int healthPort,
                     String healthBindAddress, ConfigFingerprint.Loaded loaded) {
        this(vertx, bindAddress, mitmPort, healthPort, healthBindAddress, loaded.fingerprint());
        useConfig(loaded.config());
    }

    /**
     * Tests only: a proxy that serves {@code credentials} with no config behind them. The
     * default account's entries stand in for the across-accounts domain set, and there is no
     * config read to capture ahead of. Per-account selection is not modelled: there are no
     * accounts and no tool setups, so a pinned Claude account fails closed but a pin in any
     * other namespace gets no credential at all. Tests of pinning build a config and use the
     * {@link ConfigFingerprint.Loaded} constructor.
     */
    public MitmProxy(Vertx vertx, String bindAddress, int mitmPort, int healthPort,
                     String healthBindAddress, ProxyCredentials credentials) {
        this(vertx, bindAddress, mitmPort, healthPort, healthBindAddress, ConfigFingerprint.capture());
        this.configState = new ConfigState(NO_CONFIG, NO_CONFIG.tree(),
                ToolProxyResolver.proxyToolSetups(NO_CONFIG, dev.incusspawn.tool.ToolDefLoader.withoutProjectTools(NO_CONFIG.getSearchPaths())), credentials,
                credentials.toolProxies(), Map.of(), false);
    }

    /** The config of a proxy built from credentials alone: empty, and never compared on reload. */
    private static final dev.incusspawn.config.SpawnConfig NO_CONFIG = new dev.incusspawn.config.SpawnConfig();

    private MitmProxy(Vertx vertx, String bindAddress, int mitmPort, int healthPort,
                      String healthBindAddress, ConfigFingerprint configFingerprint) {
        this.vertx = vertx;
        this.bindAddress = bindAddress;
        this.healthBindAddress = healthBindAddress;
        this.requestedMitmPort = mitmPort;
        this.requestedHealthPort = healthPort;
        // Null would compare unequal to every capture: permanent drift, a restart per command.
        this.configFingerprint = java.util.Objects.requireNonNull(configFingerprint, "configFingerprint");
        this.upstream = new Upstream(vertx, this::lookupHost);
        this.artifactCache = new ArtifactCacheHandler(vertx, upstream);
        this.hostRouteRelay = new HostRouteRelay(vertx, () -> instanceRegistry, upstream);
    }

    public void setDnsConfigured(boolean configured) {
        this.dnsConfigured = configured;
    }

    /** Tests only: serve callers from {@code registry}, populated by the caller, without Vert.x. */
    void useInstanceRegistry(InstanceRegistry registry) {
        this.instanceRegistry = registry;
    }

    /**
     * What rewrites the bridge DNS block for a set of intercepted domains on reload, pointing it
     * wherever the proxy was started to serve; none by default.
     */
    public void setBridgeDnsWriter(java.util.function.Consumer<java.util.Set<String>> bridgeDnsWriter) {
        this.bridgeDnsWriter = bridgeDnsWriter;
    }

    public void setIncusClient(dev.incusspawn.incus.IncusClient incusClient) {
        var registry = new InstanceRegistry(incusClient);
        this.instanceRegistry = registry;
        // Populate before serving: an empty snapshot cannot authorize any instance.
        // Off the event loop -- this runs during
        // startup, but keep the blocking call explicit so it stays that way.
        vertx.executeBlocking(() -> { registry.refresh(); return null; })
                .onFailure(e -> ProxyLog.warn("Initial instance registry load failed: " + e.getMessage()));
    }

    /**
     * Account resolution in isolation, for tests. A null caller selects the configured defaults
     * here; live request handlers use {@link #authorize} before calling the private overload,
     * and never reach account resolution for an unknown address or an unproven request.
     *
     * <p>Never blocks: reads the registry snapshot and schedules a refresh when it is stale.
     */
    RequestContext contextFor(String domain, String sourceAddress) {
        return contextFor(configState, domain, callerAt(sourceAddress), null);
    }

    /**
     * The instance at {@code sourceAddress} as the registry has it, null for none (or no
     * registry). Used for traffic attribution. Credential authorization obtains its own caller
     * from the same immutable listing that verified its proofs.
     */
    private InstanceRegistry.Caller callerAt(String sourceAddress) {
        var registry = instanceRegistry;
        if (registry == null) return null;
        var caller = registry.resolve(sourceAddress);
        // A miss is the case worth refreshing for: a branch that happened since the last
        // snapshot. isx signals the proxy on branch, so this is only the backstop -- and it is
        // rate-limited, because host-side traffic has no static IP and so misses every time.
        if (caller == null ? registry.wantsMissRefresh() : registry.isStale()) {
            scheduleRegistryRefresh(registry);
        }
        return caller;
    }

    private RequestContext contextFor(ConfigState state, String domain, InstanceRegistry.Caller caller, ApiActivity.Flow flow) {
        var instance = caller == null ? null : caller.accounts();

        if (instance != null && !instance.bakedIdentities().isEmpty()) {
            refuseIfUnservable(state, instance, domain);
        }
        if (instance == null || instance.usesDefaults()) {
            return new RequestContext(domain, caller, state.credentials(), state.routing(), true, flow,
                    inheritedProofs(state, state.credentials(), domain), true);
        }
        var selection = instance.accountsByNamespace();
        // Plain get() first: this runs on the event loop for every intercepted request, and
        // computeIfAbsent would allocate a capturing lambda even on a hit. The map is an
        // immutable copy, so it is a sound key -- content-based and order-independent.
        var bundle = state.credentialsBySelection().get(selection);
        if (bundle == null) {
            try {
                bundle = AccountBundle.of(ProxyCredentials.forAccounts(state.config(), state.configTree(),
                        selection, state.toolSetups()));
            } catch (dev.incusspawn.config.AccountResolver.UnknownAccountException e) {
                bundle = AccountBundle.refused(e);
            }
            state.credentialsBySelection().putIfAbsent(selection, bundle);
        }
        if (bundle.unknown() != null) throw bundle.unknown();
        return new RequestContext(domain, caller, bundle.creds(), bundle.routing(), false, flow,
                inheritedProofs(state, bundle.creds(), domain), true);
    }

    /**
     * The swap that presents the parent instance's proofs for the namespaces {@code creds}
     * inherit and a request to {@code domain} spends (#1110), or null for none. The parent's
     * secret is the one read last, off the event loop ({@link #parentSecret}). Without one this
     * isx is not running nested, and the request is refused as for a missing account.
     */
    private InheritedProof.Swap inheritedProofs(ConfigState state, ProxyCredentials creds, String domain) {
        var inherited = creds.inheritedNamespaces();
        if (inherited.isEmpty()) return null;
        var namespaces = dev.incusspawn.config.AccountSelection.namespacesForDomain(state.namespacesByDomain(), domain)
                .stream().filter(inherited::contains).toList();
        if (namespaces.isEmpty()) return null;
        var secret = parentSecret();
        if (secret == null) {
            throw new dev.incusspawn.config.AccountResolver.UnknownAccountException(namespaces.getFirst(),
                    dev.incusspawn.config.AccountResolver.INHERIT, InheritedProof.notNestedMessage(namespaces.getFirst()));
        }
        // Reached only after the caller has proved every namespace this destination can spend.
        return InheritedProof.Swap.of(secret, namespaces, state.placeholders());
    }

    /** Why a request was refused for {@code e}, for the log: inheriting is never a missing account. */
    static String refusal(RuntimeException e) {
        if (e instanceof dev.incusspawn.config.AccountResolver.UnknownAccountException unknown
                && !dev.incusspawn.config.AccountResolver.INHERIT.equals(unknown.accountName())) {
            return ", pinned to missing " + unknown.namespace() + " account '" + unknown.accountName() + "'";
        }
        if (e instanceof dev.incusspawn.config.AccountResolver.UnknownAccountException
                || e instanceof dev.incusspawn.config.AccountSelection.UnservableAccountException) {
            return ": " + e.getMessage();
        }
        // Header-validation exceptions can include authentication values in their messages.
        return ": " + e.getClass().getSimpleName();
    }

    /**
     * The parent instance's secret as last read: with every config read, by the config watcher
     * every second, and by a request that finds it older than {@link #PARENT_SECRET_MAX_AGE_NANOS}
     * -- never on the event loop. It changes on every start of the parent, which restarts this
     * proxy too, and when {@code isx shell} gives a running VM a new one (#1024).
     */
    private volatile String parentSecret;
    private volatile long parentSecretReadAt;
    private final java.util.concurrent.atomic.AtomicBoolean parentSecretReadInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final long PARENT_SECRET_MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** Set when the nesting changed, cleared once a reload has completed: what the watcher retries. */
    private volatile boolean nestingReloadDue;

    /** Blocking: the parent's secret, read again. */
    private void readParentSecret() {
        parentSecret = InheritedProof.parentSecret();
        parentSecretReadAt = System.nanoTime();
    }

    /**
     * {@link #parentSecret}, reading it again in the background when it is old: the request that
     * finds it so uses it as it is, the next ones the new one. Not the watcher's alone, which a
     * proxy started some other way, or a watcher that ended, would leave never refreshed.
     */
    private String parentSecret() {
        if (System.nanoTime() - parentSecretReadAt > PARENT_SECRET_MAX_AGE_NANOS
                && vertx != null && parentSecretReadInFlight.compareAndSet(false, true)) {
            vertx.executeBlocking(() -> { readParentSecret(); return null; })
                    .onComplete(ar -> parentSecretReadInFlight.set(false));
        }
        return parentSecret;
    }

    /**
     * Reads the parent instance's secret again, and says whether a reload is due because this
     * machine became, or stopped being, an isx instance since the domains were decided -- until
     * one completes, so a reload that fails is retried. A proxy started at boot, before the
     * parent's isx delivered its secret, would otherwise never intercept what an instance can
     * inherit. Blocking: the config watcher calls it.
     */
    boolean refreshNesting() {
        readParentSecret();
        if (configState.nested() != InheritedProof.nested()) nestingReloadDue = true;
        return nestingReloadDue;
    }

    /**
     * Fail a request closed when the credential it spends is one the instance was not built
     * for -- typically because the global default it follows moved to another Claude auth mode.
     * Scoped to that credential's domains: the instance's other services keep working.
     */
    private void refuseIfUnservable(ConfigState state, InstanceRegistry.InstanceAccounts instance,
                                    String domain) {
        var mismatches = state.mismatchesByInstance().get(instance);
        if (mismatches == null) {
            mismatches = dev.incusspawn.config.AccountSelection.servingMismatches(state.config(),
                    state.setupsByNamespace(), instance.instanceName(),
                    instance.accountsByNamespace(), instance.bakedIdentities());
            state.mismatchesByInstance().put(instance, mismatches);
        }
        if (mismatches.isEmpty()) return;
        for (var namespace : dev.incusspawn.config.AccountSelection.namespacesForDomain(
                state.namespacesByDomain(), domain)) {
            var message = mismatches.get(namespace);
            if (message != null) {
                throw new dev.incusspawn.config.AccountSelection.UnservableAccountException(namespace, message);
            }
        }
    }

    /**
     * Take on config.yaml and the tool definitions as a new {@link ConfigState}: the one path
     * construction and every reload go through. Returns the state it replaced.
     */
    private ConfigState useConfig(dev.incusspawn.config.SpawnConfig config) {
        var setups = ToolProxyResolver.proxyToolSetups(config, dev.incusspawn.tool.ToolDefLoader.withoutProjectTools(config.getSearchPaths()));
        var tree = config.tree();
        var previous = configState;
        var nested = InheritedProof.nested();
        readParentSecret();
        // The default selection is resolved once, and seeds the across-accounts set.
        var credentials = ProxyCredentials.forAccounts(config, tree, Map.of(), setups);
        var acrossAccounts = ToolProxyResolver.resolveAcrossAccounts(tree, setups, credentials.toolProxies(), nested);
        configState = new ConfigState(config, tree, setups, credentials, acrossAccounts,
                HostRouteRelay.served(ToolProxyResolver.hostRoutes(config, acrossAccounts, ProxyLog::warn),
                        name -> HostRouteSecret.loadOrCreate(name, ProxyLog::warn)), nested);
        artifactCache.artifactCacheTiers = ArtifactCacheTiers.from(config);
        return previous;
    }

    /** The default account's credentials, as of the last config read. */
    ProxyCredentials credentials() {
        return configState.credentials();
    }

    /** The tool setups resolved from the last config read. */
    Map<String, dev.incusspawn.tool.ToolSetup> toolSetups() {
        return configState.toolSetups();
    }

    /** The last config read, serialized: read it rather than serializing the config again. */
    io.hyperfoil.tools.jjq.value.JqValue configTree() {
        return configState.configTree();
    }

    private void scheduleRegistryRefresh(InstanceRegistry registry) {
        if (registryRefreshInFlight.compareAndSet(false, true)) {
            vertx.executeBlocking(() -> { registry.refresh(); return null; })
                    .onComplete(r -> registryRefreshInFlight.set(false));
        }
    }

    /** Re-read instance pinning now, e.g. after {@code isx account set} signals the proxy. */
    public void refreshInstanceRegistry() {
        var registry = instanceRegistry;
        if (registry != null) registry.refreshAfterNow(CallerCheck.REFRESH_WAIT_MILLIS);
    }

    /** Vertex endpoint for one request's account -- the region is part of the account. */
    private static String vertexHost(ProxyCredentials creds) {
        return ProxyConfig.vertexHost(creds.vertexRegion());
    }

    public void setDebugLog(ApiTrafficLog debugLog) {
        this.debugLog = debugLog;
    }

    /**
     * Index a resolved tool proxy list for lookup: exact domains, then wildcard suffixes
     * longest-first.
     *
     * <p>Pure, so the same construction serves both the global routing table and the
     * per-account-selection one. Domains never vary by account -- only the credential does --
     * so {@code warn} is set only for the global build; repeating collision warnings once per
     * selection would say nothing new.
     */
    private static ToolProxyRouting buildRouting(List<ResolvedToolProxy> proxies,
                                                 List<ResolvedToolProxy> domainProxies,
                                                 boolean warn) {
        var exact = new java.util.LinkedHashMap<String, List<ResolvedToolProxy>>();
        var wildcardsBySuffix = new java.util.LinkedHashMap<String, List<ResolvedToolProxy>>();
        var extraDomains = new HashSet<String>();
        var suffixSet = new java.util.LinkedHashSet<String>();

        for (var tp : domainProxies) {
            if (tp.auth() != null && "anthropic".equals(tp.auth().getType())) continue;
            var domain = tp.domain();
            if (domain.startsWith("*.")) {
                suffixSet.add(domain.substring(1));
                extraDomains.add(domain.substring(2));
            } else {
                extraDomains.add(domain);
            }
        }

        for (var tp : proxies) {
            if (tp.auth() != null && "anthropic".equals(tp.auth().getType())) continue;

            var domain = tp.domain();
            boolean isPathScoped = tp.auth() != null && !tp.auth().getPaths().isEmpty();
            if (domain.startsWith("*.")) {
                var suffix = domain.substring(1); // ".example.com"
                var candidates = wildcardsBySuffix.computeIfAbsent(suffix, k -> new ArrayList<>());
                if (!isPathScoped) {
                    var existingUnscoped = candidates.stream()
                            .filter(c -> c.auth() == null || c.auth().getPaths().isEmpty())
                            .findFirst().orElse(null);
                    if (existingUnscoped != null) {
                        if (warn && !existingUnscoped.toolName().equals(tp.toolName())) {
                            ProxyLog.warn("Tool '" + tp.toolName() + "' claims wildcard '" + domain
                                    + "' already registered by tool '" + existingUnscoped.toolName()
                                    + "' — first match wins");
                        }
                        continue;
                    }
                }
                candidates.add(tp);
            } else {
                var candidates = exact.computeIfAbsent(domain, k -> new ArrayList<>());
                if (!isPathScoped) {
                    var existingUnscoped = candidates.stream()
                            .filter(c -> c.auth() == null || c.auth().getPaths().isEmpty())
                            .findFirst().orElse(null);
                    if (existingUnscoped != null) {
                        if (warn && !existingUnscoped.toolName().equals(tp.toolName())) {
                            ProxyLog.warn("Tool '" + tp.toolName() + "' claims domain '" + domain
                                    + "' already registered by tool '" + existingUnscoped.toolName()
                                    + "' — skipping");
                        }
                        continue;
                    }
                }
                candidates.add(tp);
            }
        }

        var wildcards = new ArrayList<Map.Entry<String, List<ResolvedToolProxy>>>();
        for (var e : wildcardsBySuffix.entrySet()) {
            wildcards.add(Map.entry(e.getKey(), List.copyOf(e.getValue())));
        }
        wildcards.sort(Comparator.<Map.Entry<String, List<ResolvedToolProxy>>, Integer>comparing(
                e -> e.getKey().length()).reversed());

        var immutableExact = new java.util.LinkedHashMap<String, List<ResolvedToolProxy>>();
        for (var e : exact.entrySet()) {
            immutableExact.put(e.getKey(), List.copyOf(e.getValue()));
        }

        return new ToolProxyRouting(
                Map.copyOf(immutableExact),
                List.copyOf(wildcards),
                ProxyConfig.interceptedDomains(extraDomains),
                List.copyOf(suffixSet));
    }

    ResolvedToolProxy findToolProxy(String domain) {
        return findAnyToolProxy(configState.routing(), domain);
    }

    ResolvedToolProxy findToolProxy(String domain, String requestUri) {
        return findToolProxy(configState.routing(), domain, requestUri);
    }

    /**
     * The tool proxy serving a domain and request path. Path-scoped entries are tried first
     * (they are more specific), then unscoped entries. Routing is global (which domains are
     * intercepted never depends on the account) but the credential inside the entry is not,
     * so a pinned instance looks this up in its own routing.
     */
    private static ResolvedToolProxy findToolProxy(ToolProxyRouting routing, String domain,
                                                   String requestUri) {
        var candidates = routing.exactDomain().get(domain);
        if (candidates != null) {
            var match = selectByPath(candidates, requestUri);
            if (match != null) return match;
        }
        for (var entry : routing.wildcardSuffixes()) {
            if (domain.endsWith(entry.getKey())) {
                var match = selectByPath(entry.getValue(), requestUri);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static ResolvedToolProxy findAnyToolProxy(ToolProxyRouting routing, String domain) {
        var candidates = routing.exactDomain().get(domain);
        if (candidates != null && !candidates.isEmpty()) return candidates.get(0);
        for (var entry : routing.wildcardSuffixes()) {
            if (domain.endsWith(entry.getKey()) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static ResolvedToolProxy selectByPath(List<ResolvedToolProxy> candidates,
                                                  String requestUri) {
        // Path-scoped entries first: they are more specific than unscoped ones.
        for (var tp : candidates) {
            if (tp.auth() != null && !tp.auth().getPaths().isEmpty() && tp.matchesPath(requestUri)) {
                return tp;
            }
        }
        for (var tp : candidates) {
            if (tp.auth() == null || tp.auth().getPaths().isEmpty()) {
                return tp;
            }
        }
        return null;
    }

    public Set<String> allInterceptedDomains() {
        return configState.routing().allInterceptedDomains();
    }

    /**
     * Whether a request to {@code domain} is served as a credential domain: every domain the
     * proxy intercepts, as bridge DNS and certificates have it, not only those the default
     * account has a credential for -- an instance pinned to an account whose entry the default
     * lacks would otherwise be relayed as a stray subdomain, with nothing injected. What is
     * injected still comes from the caller's own routing.
     */
    private boolean isInterceptedDomain(String domain) {
        var routing = configState.routing();
        return ProxyConfig.isInterceptedDomain(domain,
                routing.allInterceptedDomains(), routing.suffixes());
    }

    // --- Lifecycle ---

    /**
     * The MITM server's certificates for the current intercepted domains, signed by the
     * current CA. Reuses persisted certs when they are still valid; re-mints against the
     * new CA on rotation. Also updates {@link #caFingerprint}.
     */
    private InterceptedCertOptions buildKeyCertOptions() throws Exception {
        var ca = CertificateAuthority.loadOrCreate();
        caFingerprint = ca.caFingerprint();
        return new InterceptedCertOptions(ca, configState.routing().allInterceptedDomains());
    }

    /**
     * Say which instances a change of a global default moves. An edit of config.yaml cannot
     * ask first the way {@code isx init} does, so this is where it becomes visible: every
     * instance not pinned for that credential switches on its next request -- and one that was
     * built for another auth mode is refused instead, which is said here too.
     */
    private void logDefaultChanges(ConfigState oldState, ConfigState newState) {
        var registry = instanceRegistry;
        var oldTree = oldState.config().tree();
        var newTree = newState.config().tree();
        newState.setupsByNamespace().forEach((namespace, setup) -> {
            var shape = setup.accountShape();
            String before;
            String after;
            try {
                before = dev.incusspawn.config.AccountResolver.effectiveAccount(oldTree, namespace, shape, null);
                after = dev.incusspawn.config.AccountResolver.effectiveAccount(newTree, namespace, shape, null);
            } catch (RuntimeException e) {
                return;
            }
            if (before.equals(after)) return;
            var followers = new java.util.ArrayList<String>();
            if (registry != null) {
                for (var instance : registry.instances()) {
                    if (instance.accountsByNamespace().containsKey(namespace)) continue;
                    followers.add(instance.instanceName());
                    var refused = dev.incusspawn.config.AccountSelection.servingMismatches(newState.config(), newState.setupsByNamespace(),
                            instance.instanceName(), instance.accountsByNamespace(), instance.bakedIdentities())
                            .get(namespace);
                    if (refused != null) ProxyLog.warn("Refusing " + namespace + " requests: " + refused);
                }
            }
            ProxyLog.info("Global " + namespace + " default changed from '" + before + "' to '" + after + "'"
                    + (followers.isEmpty() ? "; no instance follows it"
                            : "; instances following it switch on their next request: " + String.join(", ", followers)));
        });
    }

    /**
     * Reload configuration and certificates from disk. Re-reads {@code config.yaml}
     * for credential changes and re-loads the CA (re-minting leaf certs if the CA key
     * changed). Thread-safe: in-flight requests complete with the old state; new
     * connections pick up the new certs and credentials.
     */
    public synchronized void reload() {
        ProxyLog.info("Reloading configuration and certificates");
        System.out.println("Reloading configuration...");
        try {
            var loaded = ConfigFingerprint.load();
            // Warnings from re-reading the definitions (a project-local proxy tool, a broken
            // file) apply to this load too: report them again rather than once per process.
            dev.incusspawn.Warnings.forgetReported();
            var oldState = useConfig(loaded.config());
            // A proxy built from credentials alone had no config to compare against.
            if (oldState.config() != NO_CONFIG) logDefaultChanges(oldState, configState);
            invalidateVertexToken();
            // Account pinning is instance state, not config state, but a reload is the one
            // moment isx reliably signals -- so take the opportunity to re-read it too.
            refreshInstanceRegistry();
            var keyCertOptions = buildKeyCertOptions();
            mitmCertOptions = keyCertOptions;
            if (mitmServer != null) {
                var sslOptions = new io.vertx.core.net.SSLOptions()
                        .setKeyCertOptions(keyCertOptions);
                mitmServer.updateSSLOptions(sslOptions)
                        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            // Before start() is ready too: the writer is also what notices the bridge moved (#966).
            if (bridgeDnsWriter != null) {
                try {
                    bridgeDnsWriter.accept(configState.routing().allInterceptedDomains());
                } catch (Exception dnsEx) {
                    ProxyLog.warn("DNS override update failed during reload: " + dnsEx.getMessage());
                }
            }
            // The writer stops the proxy when the bridge has moved (#966): this reload ends with it.
            if (stopLatch.getCount() == 0) return;
            // Last, not in useConfig(): a reload that fails part-way keeps the old fingerprint,
            // so /health reports drift and the next command restarts the proxy.
            configFingerprint = loaded.fingerprint();
            nestingReloadDue = false;
            System.out.println("Configuration reloaded successfully.");
            ProxyLog.info("Configuration reloaded (CA fingerprint: " + caFingerprint + ")");
        } catch (Exception e) {
            System.err.println("Configuration reload failed: " + e.getMessage());
            e.printStackTrace(System.err);
            ProxyLog.error("Configuration reload failed: " + e.getMessage());
        }
    }

    /**
     * The port the MITM server listens on: the one it was given, or the one the kernel picked
     * when that was 0. Read it once {@code onReady} has run.
     *
     * @throws IllegalStateException if the server is not listening yet
     */
    public int mitmPort() {
        return boundPort(boundMitmPort, "MITM");
    }

    /** The health server's port, as {@link #mitmPort()} is the MITM server's. */
    public int healthPort() {
        return boundPort(boundHealthPort, "health");
    }

    private static int boundPort(int port, String name) {
        if (port == 0) {
            throw new IllegalStateException("The " + name + " server is not listening yet:"
                    + " read its port once start() has called onReady");
        }
        return port;
    }

    /**
     * Start the MITM proxy and health server. Blocks until {@link #stop()} is called.
     *
     * @param onReady called after both servers are listening, before blocking on the stop latch.
     *                Use this to enable DNS overrides so they are never visible without a healthy proxy.
     */
    public void start(Runnable onReady) throws Exception {
        // A reload may already have stopped it, e.g. on finding the bridge moved.
        if (stopping) {
            stopLatch.await();
            return;
        }

        // Per-domain certs chosen by SNI, with wildcards (*.domain) pre-minted so
        // subdomains resolved via dnsmasq address= overrides get a valid cert (e.g.
        // cdn01.quay.io); deeper names get a leaf minted on demand (#783). Reuses persisted leaf certs across restarts so their notBefore stays
        // stable (minted while clocks were in sync); only mint on miss/expiry/CA
        // rotation. See CertStore for why per-start re-minting broke validation
        // on hosts whose container clock lags (e.g. macOS VM after resume).
        synchronized (this) {
            mitmCertOptions = buildKeyCertOptions();
        }
        if (spoolStore == null) {
            try {
                spoolStore = new SpoolStore.OnDisk(vertx, Environment.proxySpoolDir());
            } catch (IOException | RuntimeException e) {
                ProxyLog.warn("No spool for Vertex request bodies (" + Environment.proxySpoolDir() + "): " + e.getMessage());
                spoolStore = new SpoolStore.Unavailable(e.getMessage());
            }
        }

        // MITM TLS server with SNI
        var serverOptions = new HttpServerOptions()
                .setHost(bindAddress)
                .setPort(requestedMitmPort)
                .setSsl(true)
                .setSni(true)
                .setIdleTimeout(MITM_IDLE_TIMEOUT_SECONDS)
                .setIdleTimeoutUnit(TimeUnit.SECONDS)
                .setAlpnVersions(List.of(HttpVersion.HTTP_1_1))
                .setMaxWebSocketFrameSize(1024 * 1024)
                .setMaxWebSocketMessageSize(16 * 1024 * 1024);

        synchronized (lifecycleLock) {
            // Under the lock, so a stop() that reads none of these has made start() return here.
            if (!stopping) {
                upstream.createClients();
                if (mcpBridge == null) {
                    mcpBridge = new McpBridge(vertx, () -> instanceRegistry, mcpCommand);
                    mcpBridge.start();
                }
            }
        }

        try {
            if (!stopping) boundMitmPort = bindWaitingForOldProxy(() -> bindMitmServer(serverOptions), requestedMitmPort, "Port");
            if (!stopping) {
                boundHealthPort = bindWaitingForOldProxy(this::bindHealthServer, requestedHealthPort, "Health port");
            }
        } catch (Exception e) {
            // Nothing serves without a health endpoint: free the MITM port for whoever comes next.
            closeListening();
            throw e;
        }

        boolean ready;
        synchronized (lifecycleLock) {
            ready = !stopping;
        }
        if (!ready) {
            // A stop() that began while a bind waited may have read a server before it was set.
            // Return as a stopped proxy does, once stop() is done.
            closeListening();
            stopLatch.await();
            return;
        }
        // Outside the lock, so a stop() is never held up by its DNS write; one from here on
        // stops a ready proxy.
        if (onReady != null) onReady.run();

        ProxyLog.info("Listening on " + bindAddress + ":" + mitmPort());
        ProxyLog.info("Health endpoint on " + healthBindAddress + ":" + healthPort());
        System.out.println("MITM proxy listening on " + bindAddress + ":" + mitmPort());
        System.out.println("Health endpoint on " + healthBindAddress + ":" + healthPort() + "/health");
        System.out.println("Intercepted domains: " + configState.routing().allInterceptedDomains());
        artifactCache.start();
        var credentials = configState.credentials();
        if (credentials.useVertex()) {
            System.out.println("Vertex AI mode: translating api.anthropic.com requests" +
                    " to " + vertexHost(credentials) +
                    " (region: " + credentials.vertexRegion() + ", project: " + credentials.vertexProjectId() + ")");
        } else if (!credentials.oauthToken().isBlank()) {
            System.out.println("OAuth mode: injecting Bearer token for api.anthropic.com");
        }
        System.out.println();
        System.out.println("Press Ctrl+C to stop.");

        stopLatch.await();
    }

    private static final int BIND_RETRIES = 30;

    /**
     * Bind a server to {@code port}, waiting while an older proxy that holds it still answers
     * on this proxy's health port. A stopping proxy frees its MITM port first and its health
     * port last (#1219): that order is what makes the probe meaningful for the MITM port, and
     * what leaves the health port to be waited on once the MITM port is free. Returns 0 when
     * {@link #stop()} ends the wait.
     */
    private int bindWaitingForOldProxy(java.util.concurrent.Callable<Integer> bind, int port, String what)
            throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return bind.call();
            } catch (Exception e) {
                // A stop() may have closed the server mid-listen: no failure, the start just ends.
                if (stopping) return 0;
                // A port the kernel picks cannot be held by an earlier proxy: nothing to wait for.
                if (attempt >= BIND_RETRIES || port == 0 || !isBindException(e)) throw e;
                // Freed between the bind and the probe, or held by something that is not a
                // proxy: one more bind tells them apart.
                if (!ProxyHealthCheck.isHealthy(healthBindAddress, requestedHealthPort)) {
                    try {
                        return bind.call();
                    } catch (Exception again) {
                        ProxyLog.warn(what + " " + port + " is held by something that does not answer"
                                + " as an isx proxy on port " + requestedHealthPort + ": not waiting for it");
                        throw again;
                    }
                }
                ProxyLog.warn(what + " " + port + " in use, previous proxy still running (" + attempt + "/" + BIND_RETRIES + ")");
                Thread.sleep(200);
            }
        }
    }

    private void closeListening() {
        closeQuietly(mitmServer, 2);
        closeQuietly(healthHttpServer, 2);
        boundMitmPort = 0;
        boundHealthPort = 0;
    }

    private static void closeQuietly(HttpServer server, int timeoutSeconds) {
        if (server == null) return;
        try {
            server.close().toCompletionStage().toCompletableFuture().get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    /**
     * A fresh server on each bind: one whose bind failed is not bound again. Synchronized with
     * {@link #reload()}, so a reload during the wait reaches the server that binds: it updates
     * this one, or this one is made with the certificates it installed.
     */
    private synchronized int bindMitmServer(HttpServerOptions serverOptions) throws Exception {
        mitmServer = vertx.createHttpServer(new HttpServerOptions(serverOptions).setKeyCertOptions(mitmCertOptions));
        mitmServer.exceptionHandler(err -> {
            if (isBenignConnectionError(err)) {
                // Clients (containers) drop connections abruptly all the time —
                // process exit, timeouts, TLS aborts. A full stack trace per RST
                // is pure noise, so log concisely without one.
                ProxyLog.info("MITM connection closed: " + err.getMessage());
                return;
            }
            var rejection = certificateRejection(err);
            if (rejection != null) {
                // The client's own verdict on our leaf, not a proxy fault. Vert.x does
                // not say which connection failed, so the instance and domain are unknown.
                ProxyLog.warn("A client rejected the proxy's certificate (" + rejection
                        + "): something in an instance does not trust the isx CA");
                return;
            }
            System.err.println("MITM server error: " + err.getMessage());
            err.printStackTrace(System.err);
        });
        mitmServer.requestHandler(this::routeRequest);
        mitmServer.webSocketHandler(this::routeWebSocket);
        return listenOrClose(mitmServer, mitmServer.listen());
    }

    /** The plain-HTTP health server, fresh on each bind as {@link #bindMitmServer} is. */
    private int bindHealthServer() throws Exception {
        healthHttpServer = vertx.createHttpServer().requestHandler(this::routeHealth);
        return listenOrClose(healthHttpServer, healthHttpServer.listen(requestedHealthPort, healthBindAddress));
    }

    /** The bound port; a server whose listen failed is closed, since each attempt makes a new one. */
    private static int listenOrClose(HttpServer server, io.vertx.core.Future<HttpServer> listening) throws Exception {
        try {
            return listening.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).actualPort();
        } catch (Exception e) {
            closeQuietly(server, 2);
            throw e;
        }
    }

    public void stop() {
        // First, so a start() still binding never gets ready (see lifecycleLock).
        synchronized (lifecycleLock) {
            stopping = true;
        }
        ProxyLog.info("Stopping proxy");
        try {
            artifactCache.logCacheStats();
            closeQuietly(mitmServer, 2);
            upstream.closeClients();
            if (mcpBridge != null) mcpBridge.stop();
            hostRouteRelay.stop();
            closeQuietly(healthHttpServer, 1);
        } finally {
            // Guarantee stopLatch is always counted down, even if an unexpected exception occurs
            stopLatch.countDown();
        }
    }

    private static boolean isBindException(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.net.BindException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Whether a server-level exception is an expected connection teardown rather
     * than a real fault. Containers close connections abruptly (RST, half-close,
     * TLS abort) on process exit or timeout, which Netty surfaces here as
     * {@link java.net.SocketException} ("Connection reset", "Broken pipe") or a
     * closed-channel error. These carry no useful stack trace.
     */
    private static boolean isBenignConnectionError(Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof java.nio.channels.ClosedChannelException) return true;
            var msg = cause.getMessage();
            if (msg != null) {
                var m = msg.toLowerCase(java.util.Locale.ROOT);
                if (cause instanceof java.io.IOException) {
                    if (m.contains("connection reset") || m.contains("broken pipe")) {
                        return true;
                    }
                }
                // Vert.x wraps transport errors in VertxException (not IOException)
                // when a WebSocket operation hits a closed connection.
                if (m.contains("connection was closed")
                        || m.contains("connection or outbound has closed")) {
                    return true;
                }
                // InterceptedCertOptions refusing a handshake (off-domain or missing SNI):
                // it has already logged the name and why.
                if (cause instanceof javax.net.ssl.SSLHandshakeException
                        && m.contains("no available authentication scheme")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** TLS alerts a client sends when it refuses the certificate it was shown. */
    private static final Set<String> CERTIFICATE_REJECTION_ALERTS = Set.of(
            "bad_certificate", "unknown_ca", "certificate_unknown", "certificate_expired",
            "unsupported_certificate", "certificate_revoked");

    private static final Pattern RECEIVED_ALERT = Pattern.compile("Received fatal alert: (\\w+)");

    /**
     * The alert name when {@code err} is a client refusing the proxy's certificate
     * (e.g. {@code bad_certificate}, which Go sends for an unknown CA), otherwise null.
     */
    static String certificateRejection(Throwable err) {
        for (var cause = err; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLHandshakeException && cause.getMessage() != null) {
                var m = RECEIVED_ALERT.matcher(cause.getMessage());
                if (m.find() && CERTIFICATE_REJECTION_ALERTS.contains(m.group(1))) return m.group(1);
            }
        }
        return null;
    }

    // --- Request routing ---

    /** The handler of a domain served from a cache, or null: it sees only requests without a body. */
    private Consumer<HttpServerRequest> cacheHandler(String domain, long started) {
        return artifactCache.cacheHandler(domain, started);
    }

    private void routeRequest(HttpServerRequest clientReq) {
        // When the client last heard from us, for waits that must end before its idle timeout
        var started = System.nanoTime();
        try {
            var domain = extractDomain(clientReq);
            var caller = callerAt(sourceAddressOf(clientReq));
            var flow = countTraffic(clientReq, caller, domain);
            if (domain == null) {
                sendError(clientReq.response(), 502, "Unknown domain");
                return;
            }

            Consumer<HttpServerRequest> cache;
            var routes = configState.hostRoutes();
            if (HostRouteRelay.claims(routes, clientReq)) {
                // First, by Host or SNI: neither half of a route's connection reaches credentials
                hostRouteRelay.handle(clientReq, sourceAddressOf(clientReq), routes);
            } else if (ProxyConfig.MCP_DOMAIN.equals(domain)) {
                // Answered here, never relayed: it names no host outside this machine.
                mcpBridge.handle(clientReq, sourceAddressOf(clientReq));
            } else if ((cache = cacheHandler(domain, started)) != null) {
                if (hasBody(clientReq)) {
                    // An upload or a query (npm audit, a registry push): never from a cache, keyed by
                    // path alone, and relayed now, since a cache handler waits before it relays (#1164)
                    relayRequest(clientReq, domain);
                } else {
                    cache.accept(clientReq);
                }
            } else if (isInterceptedDomain(domain)) {
                clientReq.pause();
                var state = configState;
                authorize(state, domain, sourceAddressOf(clientReq), clientReq.headers()).onComplete(result -> {
                    var proven = result.succeeded() ? result.result() : null;
                    if (proven == null) {
                        handleApiRequest(clientReq, passthroughContext(domain, caller, flow));
                    } else {
                        serveProvenRequest(state, clientReq, domain, proven, flow);
                    }
                });
            } else {
                // Subdomain of an intercepted domain (e.g. cdn01.quay.io) reached us
                // via dnsmasq wildcard — relay transparently without auth injection.
                relayRequest(clientReq, domain);
            }
        } catch (Exception e) {
            var domain = extractDomain(clientReq);
            var path = clientReq.path();
            System.err.println("Unexpected error handling request to " + domain + path + ": " + e.getMessage());
            e.printStackTrace(System.err);
            sendError(clientReq.response(), 502, "Internal proxy error");
        }
    }

    private void serveProvenRequest(ConfigState state, HttpServerRequest clientReq, String domain,
                                     InstanceRegistry.Caller caller, ApiActivity.Flow flow) {
        try {
            handleApiRequest(clientReq, contextFor(state, domain, caller, flow));
        } catch (dev.incusspawn.config.AccountResolver.UnknownAccountException
                 | dev.incusspawn.config.AccountSelection.UnservableAccountException e) {
            ProxyLog.warn("Refusing " + domain + " for " + describeCaller(clientReq) + refusal(e));
            CallerCheck.refuse(clientReq, 502, e.getMessage());
        } catch (RuntimeException e) {
            ProxyLog.error("Refusing " + domain + " for " + describeCaller(clientReq) + refusal(e));
            CallerCheck.refuse(clientReq, 502, "Internal proxy error");
        }
    }

    /** A burst can suppress warnings for five minutes, never for the proxy's whole lifetime. */
    static final class ProofWarningWindow {
        static final int LIMIT = 256;
        static final long WINDOW_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
        private final Set<String> warned = new HashSet<>();
        private long startedAt;

        synchronized boolean shouldLog(String key, long now) {
            if (warned.isEmpty() || now - startedAt >= WINDOW_NANOS) {
                warned.clear();
                startedAt = now;
            }
            return warned.size() < LIMIT && warned.add(key);
        }
    }

    private final ProofWarningWindow refusedProofWarnings = new ProofWarningWindow();
    private Future<Void> proofRefresh;
    private final java.util.concurrent.atomic.AtomicLong refusedInjections = new java.util.concurrent.atomic.AtomicLong();

    /** The only entry to account-dependent handling, shared by HTTP and WebSockets. */
    private Future<InstanceRegistry.Caller> authorize(ConfigState state, String domain, String address, io.vertx.core.MultiMap headers) {
        var registry = instanceRegistry;
        var presented = state.proofCarriers().read(domain, headers::getAll);
        var digests = presented.digests();
        if (digests.isEmpty()) return Future.succeededFuture(null);
        if (registry == null || digests.values().stream().anyMatch(List::isEmpty)) {
            refusedProof(address, presented);
            return Future.succeededFuture(null);
        }
        var caller = registry.prove(address, digests);
        if (caller != null && !registry.isStale()) return Future.succeededFuture(caller);
        var refreshed = refreshProofs(registry);
        return refreshed.map(done -> provenOrWarn(registry, address, presented))
                .recover(error -> {
                    refusedProof(address, presented);
                    return Future.succeededFuture(null);
                });
    }

    /** Coalesce worker jobs too, including requests arriving on different event loops. */
    private synchronized Future<Void> refreshProofs(InstanceRegistry registry) {
        if (proofRefresh != null) return proofRefresh;
        var promise = io.vertx.core.Promise.<Void>promise();
        var refreshed = promise.future();
        proofRefresh = refreshed;
        // One timer and one worker for every waiter, with a bounded total wait even if other
        // registry consumers keep refreshing. Ordinary independent credentials never get here.
        refreshProofsWhenDue(registry, promise, System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(InstanceRegistry.MISS_REFRESH_INTERVAL_MS
                        + CallerCheck.REFRESH_WAIT_MILLIS));
        return refreshed;
    }

    private void refreshProofsWhenDue(InstanceRegistry registry, io.vertx.core.Promise<Void> promise, long deadline) {
        var delay = registry.missRefreshDelayMillis();
        if (delay > 0) {
            if (System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(delay) >= deadline) {
                promise.tryComplete();
                releaseProofRefresh(promise);
            } else {
                vertx.setTimer(delay, ignored -> refreshProofsWhenDue(registry, promise, deadline));
            }
            return;
        }
        var remaining = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        var timeout = vertx.setTimer(remaining, ignored -> promise.tryFail("Credential registry refresh timed out"));
        vertx.<Void>executeBlocking(() -> {
            registry.refreshAfterNow(CallerCheck.REFRESH_WAIT_MILLIS);
            return null;
        }, false).onComplete(result -> {
            vertx.cancelTimer(timeout);
            // Keep coalescing onto even a timed-out waiter while its Incus job still runs.
            // Otherwise a stalled daemon could accumulate one worker job per request.
            if (result.succeeded()) promise.tryComplete();
            else promise.tryFail(result.cause());
            releaseProofRefresh(promise);
        });
    }

    private synchronized void releaseProofRefresh(io.vertx.core.Promise<Void> promise) {
        if (proofRefresh == promise.future()) proofRefresh = null;
    }

    private InstanceRegistry.Caller provenOrWarn(InstanceRegistry registry, String address,
                                                  ProofCarriers.Presented presented) {
        var caller = registry.isStale() ? null : registry.prove(address, presented.digests());
        if (caller == null) refusedProof(address, presented);
        return caller;
    }

    private void refusedProof(String address, ProofCarriers.Presented presented) {
        refusedInjections.incrementAndGet();
        var namespaces = presented.digests().keySet();
        if (presented.claimsProof()
                && refusedProofWarnings.shouldLog(address + ":" + namespaces, System.nanoTime())) {
            ProxyLog.warn("Credential proof refused for " + describeCaller(address) + " ("
                    + String.join(", ", namespaces) + "); relaying unchanged. Open a new login shell;"
                    + " if needed, run isx shell on the host, then isx doctor inside the instance."
                    + " Old API-key Claude images may need rebuilding.");
        }
    }

    /**
     * Counts a request in {@link #apiActivity}'s traffic (#935), ending it however its response
     * finishes. Not by the response's end and close handlers: the relay, the cache and
     * {@link #beginModelCall} each set their own, and a handler set later replaces the earlier.
     * An HTTP/1.1 connection answers one request at a time, so its close ends this one.
     */
    private ApiActivity.Flow countTraffic(HttpServerRequest clientReq, InstanceRegistry.Caller caller, String domain) {
        var flow = trafficFlow(caller, domain);
        var resp = clientReq.response();
        io.vertx.core.Handler<Void> ended = v -> {
            flow.addRequestBytes(clientReq.bytesRead());
            flow.addResponseBytes(resp.bytesWritten());
            flow.end();
        };
        resp.bodyEndHandler(ended);
        clientReq.connection().closeHandler(ended);
        return flow;
    }

    /** A request or WebSocket to {@code domain} begins, from {@code caller} (null: no known instance). */
    private ApiActivity.Flow trafficFlow(InstanceRegistry.Caller caller, String domain) {
        var counted = ApiActivity.trafficDomain(domain, configState.routing().allInterceptedDomains());
        return caller == null ? apiActivity.flow(null, null, 0, counted)
                : apiActivity.flow(caller.accounts().instanceName(), caller.incarnation(), caller.view(), counted);
    }

    /**
     * The address the request came from. iptables REDIRECT preserves the container's source
     * address, so on the bridge this is the instance's own static IP.
     */
    private static String sourceAddressOf(HttpServerRequest req) {
        var remote = req.remoteAddress();
        return remote == null ? null : remote.hostAddress();
    }

    private static String sourceAddressOf(ServerWebSocket ws) {
        var remote = ws.remoteAddress();
        return remote == null ? null : remote.hostAddress();
    }

    /** Name the caller for a log line: the instance if the registry knows it, else its address. */
    private String describeCaller(HttpServerRequest req) {
        return describeCaller(sourceAddressOf(req));
    }

    private String describeCaller(String sourceAddress) {
        var registry = instanceRegistry;
        var instance = registry == null ? null : registry.lookup(sourceAddress);
        if (instance != null) return "instance '" + instance.instanceName() + "'";
        return sourceAddress == null ? "an unknown caller" : "caller " + sourceAddress;
    }

    private String extractDomain(HttpServerRequest req) {
        var host = req.getHeader("Host");
        if (host != null) {
            var colon = host.indexOf(':');
            return colon > 0 ? host.substring(0, colon) : host;
        }
        var sni = req.connection().indicatedServerName();
        return (sni != null && !sni.isEmpty()) ? sni : null;
    }

    // --- WebSocket passthrough ---

    private void routeWebSocket(ServerWebSocket clientWs) {
        var host = clientWs.headers().get("Host");
        var colon = host == null ? -1 : host.indexOf(':');
        var domain = colon > 0 ? host.substring(0, colon) : host;
        var address = sourceAddressOf(clientWs);
        var flow = trafficFlow(callerAt(address), domain);
        clientWs.pause();
        var status = io.vertx.core.Promise.<Integer>promise();
        var context = new java.util.concurrent.atomic.AtomicReference<RequestContext>();
        // Defer Netty's handshake while a stale registry is refreshed. Dial only after it validates.
        clientWs.setHandshake(status.future()).onComplete(handshake -> {
            if (handshake.failed() || handshake.result() != 101) {
                flow.end();
                return;
            }
            clientWs.closeHandler(v -> flow.end());
            try {
                handleWebSocketUpgrade(clientWs, context.get(), flow);
            } catch (RuntimeException e) {
                ProxyLog.error("WebSocket relay setup failed for " + domain + " for " + describeCaller(address) + refusal(e));
                if (!clientWs.isClosed()) clientWs.close((short) 1011, "Proxy error");
                flow.end();
            }
        });
        if (domain == null) {
            status.complete(400);
        } else if (ProxyConfig.MCP_DOMAIN.equals(domain) || HostRouteRelay.claims(configState.hostRoutes(), clientWs)) {
            status.complete(404);
        } else {
            var state = configState;
            authorize(state, domain, address, clientWs.headers()).onComplete(result -> {
                try {
                    var proven = result.succeeded() ? result.result() : null;
                    context.set(proven == null ? passthroughContext(domain, callerAt(address), flow) : contextFor(state, domain, proven, flow));
                    status.complete(101);
                } catch (dev.incusspawn.config.AccountResolver.UnknownAccountException
                         | dev.incusspawn.config.AccountSelection.UnservableAccountException e) {
                    ProxyLog.warn("Refusing WebSocket to " + domain + " for " + describeCaller(address) + refusal(e));
                    status.complete(502);
                } catch (RuntimeException e) {
                    ProxyLog.error("Refusing WebSocket to " + domain + " for " + describeCaller(address) + refusal(e));
                    status.tryComplete(502);
                }
            });
        }
    }

    private RequestContext passthroughContext(String domain, InstanceRegistry.Caller caller, ApiActivity.Flow flow) {
        return new RequestContext(domain, caller, new ProxyCredentials("", "", false, "", "", List.of()),
                buildRouting(List.of(), List.of(), false), false, flow, null, false);
    }

    private void handleWebSocketUpgrade(ServerWebSocket clientWs, RequestContext ctx, ApiActivity.Flow flow) {
        var domain = ctx.domain();
        var wsOptions = new WebSocketConnectOptions()
                .setHost(domain)
                .setPort(upstreamWsPort)
                .setSsl(upstreamWsSsl)
                .setURI(clientWs.uri());

        for (var entry : clientWs.headers()) {
            var key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!key.startsWith("sec-websocket") && !key.equals("connection")
                    && !key.equals("upgrade") && !key.equals("host")) {
                wsOptions.addHeader(entry.getKey(), entry.getValue());
            }
        }

        var protocols = clientWs.headers().get("Sec-WebSocket-Protocol");
        if (protocols != null && !protocols.isBlank()) {
            for (var p : protocols.split(",")) {
                wsOptions.addSubProtocol(p.trim());
            }
        }

        injectWebSocketAuth(wsOptions, ctx);
        presentInheritedProofs(wsOptions.getHeaders(), ctx);

        // Let Vert.x resolve DNS via its built-in resolver (configured on the
        // Vertx instance).  Unlike HTTP requests, WebSocket ignores setServer(),
        // so manual resolveHost() + setHost(ip) would break TLS SNI.
        // Uses wsUpstreamClient which has no read-idle timeout (WebSocket
        // sessions can be idle between prompts for minutes).
        connectUpstreamWebSocket(wsOptions).onSuccess(upstreamWs -> {
            if (clientWs.isClosed()) {
                upstreamWs.close();
                return;
            }

            if (ProxyLog.isDebugEnabled()) {
                ProxyLog.debug("WebSocket connected: " + domain + clientWs.uri());
            }

            // Periodic pings on both legs to prevent idle timeouts.
            // Upstream pings prevent NAT/firewall timeouts during long AI
            // thinking phases; client pings prevent the MITM server's own
            // idle timeout (120s) from killing the connection when no data
            // flows on the client leg (e.g. while the model is reasoning).
            var upstreamPingTimer = vertx.setPeriodic(30_000, id ->  {
                if (!upstreamWs.isClosed()) {
                    upstreamWs.writePing(Buffer.buffer("keepalive"));
                }
            });
            var clientPingTimer = vertx.setPeriodic(30_000, id -> {
                if (!clientWs.isClosed()) {
                    clientWs.writePing(Buffer.buffer("keepalive"));
                }
            });

            clientWs.frameHandler(frame -> {
                if ((frame.isText() || frame.isBinary() || frame.isContinuation())
                        && !upstreamWs.isClosed()) {
                    flow.addRequestBytes(frame.binaryData().length());
                    upstreamWs.writeFrame(frame);
                }
            });
            upstreamWs.frameHandler(frame -> {
                if ((frame.isText() || frame.isBinary() || frame.isContinuation())
                        && !clientWs.isClosed()) {
                    flow.addResponseBytes(frame.binaryData().length());
                    clientWs.writeFrame(frame);
                }
            });

            clientWs.closeHandler(v -> {
                flow.end();
                vertx.cancelTimer(upstreamPingTimer);
                vertx.cancelTimer(clientPingTimer);
                if (!upstreamWs.isClosed()) {
                    var code = clientWs.closeStatusCode();
                    if (code != null) {
                        upstreamWs.close(code, clientWs.closeReason() != null ? clientWs.closeReason() : "");
                    } else {
                        upstreamWs.close();
                    }
                }
            });
            upstreamWs.closeHandler(v -> {
                vertx.cancelTimer(upstreamPingTimer);
                vertx.cancelTimer(clientPingTimer);
                if (!clientWs.isClosed()) {
                    var code = upstreamWs.closeStatusCode();
                    if (code != null) {
                        clientWs.close(code, upstreamWs.closeReason() != null ? upstreamWs.closeReason() : "");
                    } else {
                        clientWs.close();
                    }
                }
            });

            clientWs.exceptionHandler(err -> {
                vertx.cancelTimer(upstreamPingTimer);
                vertx.cancelTimer(clientPingTimer);
                if (!isBenignConnectionError(err)) {
                    System.err.println("WebSocket client error (" + domain + "): " + err.getMessage());
                }
                if (!upstreamWs.isClosed()) upstreamWs.close();
            });
            upstreamWs.exceptionHandler(err -> {
                vertx.cancelTimer(upstreamPingTimer);
                vertx.cancelTimer(clientPingTimer);
                if (!isBenignConnectionError(err)) {
                    System.err.println("WebSocket upstream error (" + domain + "): " + err.getMessage());
                }
                if (!clientWs.isClosed()) clientWs.close();
            });

            clientWs.resume();
        }).onFailure(err -> {
            System.err.println("WebSocket upstream connect failed (" + domain + "): " + err.getMessage());
            if (!clientWs.isClosed()) clientWs.close((short) 1011, "Upstream connection failed");
        });
    }

    /** Opens the upstream leg of a relayed WebSocket; overridable so tests can see every dial. */
    Future<WebSocket> connectUpstreamWebSocket(WebSocketConnectOptions options) {
        return upstream.wsUpstreamClient.webSocket(options);
    }

    private void injectWebSocketAuth(WebSocketConnectOptions options, RequestContext ctx) {
        if (!ctx.authorized()) return;
        var domain = ctx.domain();
        var credentials = ctx.creds();
        if (ANTHROPIC_DOMAINS.contains(domain)) {
            if (!credentials.oauthToken().isBlank()) {
                options.putHeader("Authorization", "Bearer " + credentials.oauthToken());
                options.removeHeader("x-api-key");
            } else if (!credentials.anthropicApiKey().isBlank()) {
                options.putHeader("x-api-key", credentials.anthropicApiKey());
                options.removeHeader("Authorization");
            }
        } else {
            var tp = findToolProxy(ctx.routing(), domain, options.getURI());
            if (tp != null) {
                var headerName = tp.headerName();
                var headerValue = tp.computeHeaderValue();
                if (headerName != null && headerValue != null) {
                    options.putHeader(headerName, headerValue);
                }
            }
        }
    }

    // --- API requests (Anthropic, GitHub) ---

    /**
     * A request to a credential-injecting domain. Streamed upstream as it arrives, unless it is a
     * Vertex model call: its URL depends on its body, which may be translated, and it is sent again on
     * a 401, so its body is held in a {@link BodySpool} until it has been sent for good (#1315).
     */
    private void handleApiRequest(HttpServerRequest clientReq, RequestContext ctx) {
        if (!isVertexModelRequest(clientReq, ctx)) {
            streamApiRequest(clientReq, ctx);
            return;
        }
        retainVertexRequest(clientReq, ctx);
    }

    private static boolean isVertexModelRequest(HttpServerRequest clientReq, RequestContext ctx) {
        var path = clientReq.path();
        return ctx.creds().useVertex() && ANTHROPIC_DOMAINS.contains(ctx.domain()) && path != null
                && (path.startsWith("/v1/projects/") || path.startsWith("/v1/messages"));
    }

    /**
     * Relays the request as {@link Upstream#relayRequest} relays any other, with the credential set
     * on its head before any of it leaves: the body is never held, nor needed, since every credential
     * is a header.
     */
    private void streamApiRequest(HttpServerRequest clientReq, RequestContext ctx) {
        var domain = ctx.domain();
        var exchange = beginModelCall(clientReq, ctx);
        var debug = debugExchange(clientReq, null);
        upstream.relayRequest(clientReq, domain, new Upstream.RelayHooks(
                upReq -> prepareApiHead(clientReq, upReq, ctx, domain, false, exchange),
                upResp -> onApiResponse(ctx, upResp, exchange, debug),
                debug == null ? null : chunk -> capture(debug.original, chunk),
                responseTap(exchange, debug)));
    }

    /**
     * Sets the upstream head: the client's, with the credential for {@code upstreamHost} put in.
     * Completes false, having answered the client, when the credential could not be had.
     */
    private Future<Boolean> prepareApiHead(HttpServerRequest clientReq, HttpClientRequest upReq, RequestContext ctx,
                                           String upstreamHost, boolean isVertexRequest, ApiActivity.Exchange exchange) {
        copyRequestHeaders(clientReq, upReq, ctx.domain());
        // A compressed answer would hide its token usage from apiActivity
        if (exchange != null) upReq.putHeader("Accept-Encoding", "identity");
        return injectHeaders(upReq, ctx, upstreamHost, isVertexRequest).map(ok -> {
            if (ok) {
                presentInheritedProofs(upReq.headers(), ctx);
            } else {
                var err = authError;
                sendError(clientReq.response(), 502, err != null ? err : "Failed to obtain upstream credentials");
            }
            return ok;
        });
    }

    /** What the proxy notes of an upstream answer's head, before relaying it. */
    private void onApiResponse(RequestContext ctx, HttpClientResponse upResp, ApiActivity.Exchange exchange,
                               DebugExchange debug) {
        noteAuthStatus(ctx, upResp);
        if (exchange != null) {
            exchange.respond(upResp.statusCode(), upResp.getHeader("Content-Type"), upResp.getHeader("Content-Encoding"));
        }
        if (debug != null) debug.logAtEnd(upResp);
    }

    /**
     * A Vertex model call: its body is translated as it arrives if it is in the standard API's format,
     * and held in a {@link BodySpool} until it has been sent for good, since the URL is known only at its end
     * and a 401 sends it again. Paused here, before anything waits (#1164).
     */
    private void retainVertexRequest(HttpServerRequest clientReq, RequestContext ctx) {
        clientReq.pause();
        var credentials = ctx.creds();
        var upstreamHost = vertexHost(credentials);
        var clientResp = clientReq.response();
        var exchange = beginModelCall(clientReq, ctx);
        var debug = debugExchange(clientReq, null);
        var store = spoolStore;
        var spool = new BodySpool(spoolBudget, store, vertx.getOrCreateContext());
        // The exchange ends however the client's response does; so do its count and its spool
        io.vertx.core.Handler<Void> ended = v -> {
            if (exchange != null) exchange.end();
            spool.close();
        };
        clientResp.endHandler(ended);
        clientResp.closeHandler(ended);

        var path = clientReq.path();
        VertexBodyRewriter rewriter = null;
        if (!path.startsWith("/v1/projects/")) {
            if (debug != null) debug.forwarded = new ApiTrafficLog.Capture();
            rewriter = vertexRewriter(path, credentials,
                    debug == null ? spool::write : chunk -> {
                        capture(debug.forwarded, chunk);
                        spool.write(chunk);
                    });
        }
        var translator = rewriter;
        var refused = new boolean[1];
        java.util.function.BiConsumer<Integer, String> refuse = (status, message) -> {
            if (refused[0]) return;
            refused[0] = true;
            if (!clientReq.isEnded()) clientReq.handler(ignored -> {});
            refuseUnread(clientReq, status, message);
        };
        // Checked before the client is told to send a body that may not fit in memory: one that does
        // never reaches the disk, so the disk's state is no reason to refuse it
        if (mayNeedDisk(clientReq) && !store.hasRoom()) {
            ProxyLog.warn("Refusing a Vertex request (" + path + "): " + store.refusal());
            refuse.accept(507, "isx-proxy: " + store.refusal() + "; this request could not be held");
            return;
        }
        spool.failureHandler(err -> {
            var full = err instanceof BodySpool.SpoolFull;
            ProxyLog.warn("Could not hold a Vertex request (" + path + "): " + err.getMessage());
            refuse.accept(full ? 507 : 502, full ? "isx-proxy: " + err.getMessage() + "; this request could not be held"
                    : "Proxy error");
        });
        // Upstream cannot be asked before the body is in: the proxy answers for it (RFC 9110 10.1.1)
        if ("100-continue".equalsIgnoreCase(clientReq.getHeader("Expect"))) clientResp.writeContinue();

        clientReq.handler(chunk -> {
            if (debug != null) capture(debug.original, chunk);
            try {
                if (translator != null) translator.accept(chunk);
                else spool.write(chunk);
            } catch (VertexBodyRewriter.InvalidBody | VertexBodyRewriter.RefusedBody | RuntimeException e) {
                // Anything unexpected too: the client gets an answer, and the spool is closed with it
                refuseTranslation(path, e, refuse);
                return;
            }
            if (spool.writeQueueFull()) {
                clientReq.pause();
                spool.drainHandler(clientReq::resume);
            }
        });
        clientReq.exceptionHandler(err -> spool.close());
        clientReq.endHandler(v -> {
            if (refused[0]) return;
            String uri;
            try {
                if (translator == null) {
                    // Already Vertex-formatted (container running in Vertex mode with
                    // ANTHROPIC_VERTEX_BASE_URL pointing here): forward to real Vertex.
                    // The Vertex SDK uses @date suffixes (e.g. claude-haiku-4-5@20251001)
                    // which the global endpoint rejects — strip them.
                    uri = VERTEX_DATE_SUFFIX.matcher(path).replaceFirst("");
                } else {
                    var result = translator.end();
                    uri = result.path();
                    logDroppedFields(result.droppedFields());
                }
            } catch (VertexBodyRewriter.InvalidBody | VertexBodyRewriter.RefusedBody | RuntimeException e) {
                refuseTranslation(path, e, refuse);
                return;
            }
            var requestOptions = new RequestOptions()
                    .setMethod(clientReq.method())
                    .setPort(443)
                    .setHost(upstreamHost)
                    .setURI(uri);
            spool.end().onSuccess(done -> sendVertexRequest(clientReq, requestOptions, ctx, spool, false, exchange, debug));
        });
        clientReq.resume();
    }

    /** Answers a request whose body was not all read, and closes its connection, which still holds the rest. */
    private void refuseUnread(HttpServerRequest clientReq, int status, String message) {
        var resp = clientReq.response();
        if (resp.ended() || resp.closed()) return;
        if (resp.headWritten()) {
            resp.reset();
            return;
        }
        resp.headers().clear();
        resp.putHeader("Connection", "close");
        resp.setStatusCode(status).end(message).onComplete(v -> clientReq.connection().close());
    }

    /**
     * Answers a body the rewriter would not translate: invalid JSON as before, with a 502; a refused
     * one with a 400; and anything unexpected with a 502, as the handler's catch-all always did.
     */
    private static void refuseTranslation(String path, Exception e, java.util.function.BiConsumer<Integer, String> answer) {
        if (e instanceof RuntimeException) {
            System.err.println("Vertex request error (" + path + "): " + e);
            e.printStackTrace(System.err);
            answer.accept(502, "Proxy error");
        } else if (e instanceof VertexBodyRewriter.InvalidBody) {
            System.err.println("Failed to translate request body to Vertex format: " + e.getMessage());
            answer.accept(502, "Proxy error");
        } else {
            ProxyLog.warn("Refusing a Vertex request (" + path + "): " + e.getMessage());
            answer.accept(400, "isx-proxy: " + e.getMessage());
        }
    }

    /** Whether a body may outgrow what the spool keeps in memory: unknown length, or more than half the budget. */
    private boolean mayNeedDisk(HttpServerRequest clientReq) {
        var length = clientReq.getHeader("Content-Length");
        if (length == null) return true;
        try {
            return Long.parseLong(length.strip()) > spoolBudget.limit() / 2;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** The translation of a Vertex request's body; overridable so tests can make it fail. */
    VertexBodyRewriter vertexRewriter(String path, ProxyCredentials credentials, Consumer<Buffer> output) {
        return new VertexBodyRewriter(path, credentials.vertexProjectId(), credentials.vertexRegion(), output);
    }

    private void logDroppedFields(List<String> dropped) {
        if (dropped.isEmpty() || loggedStrippedFields.size() >= MAX_LOGGED_DROPPED_FIELDS) return;
        var fresh = dropped.stream().filter(loggedStrippedFields::add).toList();
        if (!fresh.isEmpty()) System.err.println("Vertex translation: stripped unsupported fields: " + fresh);
        if (loggedStrippedFields.size() >= MAX_LOGGED_DROPPED_FIELDS) {
            System.err.println("Vertex translation: " + MAX_LOGGED_DROPPED_FIELDS + " field names logged; no more will be");
        }
    }

    /**
     * Counts a model call from a known instance in {@link #apiActivity}, ending it however the
     * client's response finishes; null for any other request.
     */
    private ApiActivity.Exchange beginModelCall(HttpServerRequest clientReq, RequestContext ctx) {
        var caller = ctx.caller();
        if (caller == null || !ANTHROPIC_DOMAINS.contains(ctx.domain())
                || !ApiActivity.isModelCall(clientReq.path())) {
            return null;
        }
        var exchange = apiActivity.begin(caller.accounts().instanceName(), caller.incarnation(), caller.view());
        if (ctx.flow() != null) ctx.flow().carries(exchange);
        // The flow ends it first; these stay as the backstop where nothing replaces them (on a
        // streamed request, the relay's watchdog sets the response's own end and close handlers)
        clientReq.response().endHandler(v -> exchange.end());
        clientReq.response().closeHandler(v -> exchange.end());
        return exchange;
    }

    private Future<HttpClientRequest> requestWithAsyncDns(RequestOptions options) {
        return upstream.requestWithAsyncDns(options);
    }

    /** The blocking lookup behind {@link Upstream#resolveHost}; overridable so tests can hold it open. */
    String lookupHost(String host) throws Exception {
        return InetAddress.getByName(host).getHostAddress();
    }

    /**
     * Sends the held body upstream with the GCP token; a 401 invalidates the token and sends it once
     * more, replayed from the same spool, which is released once an answer other than a 401 has come and
     * the body has all gone, or the exchange ends.
     */
    private void sendVertexRequest(HttpServerRequest clientReq, RequestOptions requestOptions, RequestContext ctx,
                                   BodySpool spool, boolean isRetry, ApiActivity.Exchange exchange, DebugExchange debug) {
        var domain = ctx.domain();
        var clientResp = clientReq.response();
        if (clientResp.closed()) return;
        requestWithAsyncDns(requestOptions).onSuccess(upReq -> {
            Future<Boolean> head;
            try {
                head = prepareApiHead(clientReq, upReq, ctx, requestOptions.getHost(), true, exchange);
            } catch (RuntimeException e) {
                // A header Vert.x refuses: answer now, as the relay does, not at the idle timeout
                upReq.exceptionHandler(ignored -> {}).reset();
                System.err.println("Vertex request error (" + domain + "): " + e.getMessage());
                sendError(clientResp, 502, "Proxy error");
                return;
            }
            head.onSuccess(ok -> {
                // The token may take seconds: the client may have left, and its spool with it
                if (!ok || clientResp.closed()) {
                    upReq.exceptionHandler(ignored -> {}).reset();
                    return;
                }
                // The proxy answered the client's expectation itself; upstream gets the whole body at once
                upReq.headers().remove("Expect");
                upReq.putHeader("Content-Length", String.valueOf(spool.size()));
                upReq.exceptionHandler(ignored -> {});
                upReq.response().onSuccess(upResp -> {
                    if (!isRetry && upResp.statusCode() == 401) {
                        System.err.println("Vertex 401: invalidating cached token and retrying");
                        invalidateVertexToken();
                        spool.stopReplay();
                        upReq.reset();
                        sendVertexRequest(clientReq, requestOptions, ctx, spool, true, exchange, debug);
                        return;
                    }
                    // No send follows this answer: the body goes as soon as it has all gone upstream
                    spool.closeOnceSent();
                    onApiResponse(ctx, upResp, exchange, debug);
                    upstream.copyResponseHead(upResp, clientResp);
                    upstream.pipeResponse(upResp, clientResp, null, responseTap(exchange, debug));
                }).onFailure(err -> {
                    System.err.println("Upstream send error (" + domain + "): " + err.getMessage());
                    sendError(clientResp, 502, "Upstream error");
                });
                spool.replay(upReq).onComplete(sent -> {
                    if (sent.succeeded()) upReq.end();
                    else upReq.reset();
                });
            });
        }).onFailure(err -> {
            System.err.println("Upstream connect error (" + domain + "): " + err.getMessage());
            sendError(clientResp, 502, "Upstream connection failed");
        });
    }

    /**
     * Only the default credentials drive the host's auth status. A pinned instance's broken token is
     * reported to that instance (it sees the 401) but must not tell the user their own default
     * credential has failed -- nor clear a real default-account error by succeeding.
     */
    private void noteAuthStatus(RequestContext ctx, HttpClientResponse upResp) {
        if (ctx.usesDefaultCredentials()
                && !ctx.creds().oauthToken().isBlank() && ANTHROPIC_DOMAINS.contains(ctx.domain())) {
            if (upResp.statusCode() == 401) {
                setAuthError("Claude OAuth token rejected (HTTP 401). "
                        + "The token may have expired — run 'isx init' to refresh.",
                        "isx init");
            } else {
                clearAuthError();
            }
        }
    }

    // --- Debug log (--debug) ---

    /**
     * What {@code --debug} logs of one exchange: the client's head, and a prefix of each body taken as
     * it passed, so logging never holds a body back nor whole (#1315).
     */
    private static final class DebugExchange {
        private final ApiTrafficLog log;
        private final String originalDump;
        final ApiTrafficLog.Capture original;
        /** The body as translated, when it was. */
        ApiTrafficLog.Capture forwarded;
        final ApiTrafficLog.Capture response = new ApiTrafficLog.Capture();

        DebugExchange(ApiTrafficLog log, String originalDump, ApiTrafficLog.Capture original) {
            this.log = log;
            this.originalDump = originalDump;
            this.original = original;
        }

        /** Logs the exchange once the response has ended, or failed. */
        void logAtEnd(HttpClientResponse upResp) {
            var responseDump = dumpResponse(upResp);
            upResp.end().onComplete(ignored ->
                    log.logExchange(originalDump, original, null, forwarded, responseDump, response));
        }
    }

    /**
     * The exchange's debug record, or null when {@code --debug} is off; {@code body} is the request's
     * when it was read whole, else null and it is captured as it passes.
     */
    private DebugExchange debugExchange(HttpServerRequest clientReq, byte[] body) {
        var log = debugLog;
        if (log == null) return null;
        return new DebugExchange(log, dumpRequest(clientReq),
                body == null ? new ApiTrafficLog.Capture() : ApiTrafficLog.Capture.of(body));
    }

    private static void capture(ApiTrafficLog.Capture capture, Buffer chunk) {
        capture.accept(chunk.length(), chunk::getBytes);
    }

    /** What sees each chunk of the response: the model-call count and the debug log, or null for neither. */
    private static java.util.function.Consumer<Buffer> responseTap(ApiActivity.Exchange exchange, DebugExchange debug) {
        if (debug == null) return exchange == null ? null : exchange::accept;
        return chunk -> {
            if (exchange != null) exchange.accept(chunk);
            capture(debug.response, chunk);
        };
    }

    // --- Upstream helpers the credential path uses ---

    private void relayRequest(HttpServerRequest clientReq, String domain) {
        upstream.relayRequest(clientReq, domain);
    }

    private static boolean hasBody(HttpServerRequest clientReq) {
        return Upstream.hasBody(clientReq);
    }

    private void copyRequestHeaders(HttpServerRequest clientReq, HttpClientRequest upReq,
                                    String domain) {
        upstream.copyRequestHeaders(clientReq, upReq, domain);
    }

    private void sendError(HttpServerResponse resp, int statusCode, String message) {
        upstream.sendError(resp, statusCode, message);
    }

    // --- Header injection ---

    /**
     * Inject real credentials into the upstream request.
     * Returns a future resolving to false if a required token could not be obtained
     * (caller should 502). The Vertex path is async — token acquisition runs on a
     * worker thread via {@link #acquireVertexAccessToken()}, so the event loop is
     * never blocked by a {@code gcloud} fork.
     */
    private Future<Boolean> injectHeaders(HttpClientRequest upReq, RequestContext ctx,
                               String upstreamHost, boolean isVertexRequest) {
        if (!ctx.authorized()) return Future.succeededFuture(true);
        var domain = ctx.domain();
        var credentials = ctx.creds();
        upReq.putHeader("Host", upstreamHost);

        if (isVertexRequest) {
            // The token itself is host-wide: 'gcloud auth print-access-token' returns the
            // gcloud *user* credential, independent of project, so one cache serves every
            // Vertex account. Only the project and region differ, and those come off
            // ctx.creds() above. See the Vertex notes in .claude/rules/proxy.md.
            return acquireVertexAccessToken()
                    .map(token -> {
                        if (ctx.usesDefaultCredentials()) clearAuthError();
                        upReq.putHeader("Authorization", "Bearer " + token);
                        upReq.headers().remove("x-api-key");
                        upReq.headers().remove("anthropic-beta");
                        upReq.headers().remove("anthropic-version");
                        upReq.headers().remove("anthropic-dangerous-direct-browser-access");
                        return true;
                    })
                    .recover(e -> {
                        if (ctx.usesDefaultCredentials()) setAuthError(e.getMessage(), VERTEX_AUTH_HINT);
                        return Future.succeededFuture(false);
                    });
        } else if (ANTHROPIC_DOMAINS.contains(domain)) {
            if (!credentials.oauthToken().isBlank()) {
                upReq.putHeader("Authorization", "Bearer " + credentials.oauthToken());
                upReq.headers().remove("x-api-key");
            } else if (!credentials.anthropicApiKey().isBlank()) {
                upReq.putHeader("x-api-key", credentials.anthropicApiKey());
                upReq.headers().remove("Authorization");
            } else if (!credentials.inheritedNamespaces().contains(dev.incusspawn.config.SpawnConfig.ClaudeConfig.NAMESPACE)) {
                // Inherited, it carries the proof presentInheritedProofs swaps
                upReq.headers().remove("x-api-key");
            }
        } else {
            var tp = findToolProxy(ctx.routing(), domain, upReq.getURI());
            if (tp != null) {
                var headerName = tp.headerName();
                var headerValue = tp.computeHeaderValue();
                if (headerName != null && headerValue != null) {
                    upReq.putHeader(headerName, headerValue);
                }
            }
        }
        return Future.succeededFuture(true);
    }

    /**
     * Every proof the request carries for a namespace it inherits, swapped for the parent
     * instance's (#1110), in place: the same header and prefix, so it reaches the outer proxy as
     * the parent's own tool would send it. Anything else -- a static placeholder, a caller's own
     * token -- is left as it is.
     */
    private static void presentInheritedProofs(io.vertx.core.MultiMap headers, RequestContext ctx) {
        var swap = ctx.inherited();
        if (!ctx.authorized() || swap == null || headers == null) return;
        for (var name : Set.copyOf(headers.names())) {
            var values = headers.getAll(name);
            var swapped = values.stream().map(swap::apply).toList();
            if (!swapped.equals(values)) headers.set(name, swapped);
        }
    }

    // --- GCP access token ---

    private static final long GCLOUD_TIMEOUT_SECONDS = 15;

    /**
     * Acquire a GCP access token asynchronously, returning a cached value when valid.
     * Single-flight: concurrent callers share one in-flight {@code gcloud} invocation
     * via a CAS loop on {@link #vertexToken}, mirroring the {@link Upstream#resolveHost} pattern.
     * The {@code gcloud} fork runs on a Vert.x worker thread, so this never blocks
     * the event loop.
     */
    private Future<String> acquireVertexAccessToken() {
        while (true) {
            var existing = vertexToken.get();
            if (existing != null && existing.isValid()) {
                return Future.succeededFuture(existing.token());
            }
            if (existing != null && existing.isResolving()) {
                return existing.inflight();
            }
            var promise = Promise.<String>promise();
            var entry = VertexTokenEntry.resolving(promise.future());
            if (vertexToken.compareAndSet(existing, entry)) {
                vertx.<String>executeBlocking(() -> fetchGcloudToken(), false)
                        .onComplete(ar -> {
                            if (ar.succeeded()) {
                                vertexToken.compareAndSet(entry, VertexTokenEntry.resolved(ar.result()));
                                promise.complete(ar.result());
                            } else {
                                vertexToken.compareAndSet(entry, null);
                                promise.fail(ar.cause());
                            }
                        });
                return promise.future();
            }
        }
    }

    /** The token from {@code gcloud}; overridable so tests need none. */
    String fetchGcloudToken() {
        try {
            var pb = new ProcessBuilder("gcloud", "auth", "print-access-token");
            var process = pb.start();
            if (!process.waitFor(GCLOUD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new RuntimeException("gcloud auth print-access-token timed out after "
                        + GCLOUD_TIMEOUT_SECONDS + "s");
            }
            var stdout = new String(process.getInputStream().readAllBytes()).strip();
            var stderr = new String(process.getErrorStream().readAllBytes()).strip();
            var exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("gcloud auth print-access-token failed (exit " + exitCode + "): " + stderr);
            }
            if (stdout.isBlank()) {
                throw new RuntimeException("gcloud auth print-access-token returned an empty token");
            }
            return stdout;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to obtain GCP access token: " + e.getMessage() +
                    ". Ensure 'gcloud' is installed and 'gcloud auth login' has been run.", e);
        }
    }

    boolean hasFreshVertexToken() {
        var entry = vertexToken.get();
        return entry != null && entry.isValid();
    }

    private void invalidateVertexToken() {
        vertexToken.set(null);
    }

    private static final long AUTH_NOTIFICATION_COOLDOWN_MS = 5 * 60 * 1000L;

    /** Remediation for a Vertex token failure: the proxy shells out to
     *  {@code gcloud auth print-access-token}, which uses the gcloud user
     *  credential — not application-default credentials. */
    static final String VERTEX_AUTH_HINT = "gcloud auth login";

    /**
     * An auth failure with no usable message must never be stored as null or blank:
     * {@code /health} omits the field entirely in that case, so a real failure would
     * render as a healthy proxy.
     */
    private static String authDetail(String msg, String fallback) {
        return (msg == null || msg.isBlank()) ? fallback : msg;
    }

    void setAuthError(String msg, String hint) {
        var detail = authDetail(msg, "Credential injection failed");
        synchronized (authLock) {
            if (authError == null) {
                System.err.println(VERTEX_AUTH_HINT.equals(hint)
                        ? "Failed to get Vertex token: " + detail : detail);
            }
            authError = detail;
            authErrorHint = hint;
            long now = System.currentTimeMillis();
            if (now - authNotificationSentMs >= AUTH_NOTIFICATION_COOLDOWN_MS) {
                authNotificationSentMs = now;
                Platform.sendNotification("isx: Authentication expired",
                        "Run '" + hint + "' to re-authenticate.");
            }
        }
    }

    void clearAuthError() {
        if (authError == null) return;
        synchronized (authLock) {
            authError = null;
            authErrorHint = null;
            authNotificationSentMs = 0;
        }
    }

    /**
     * Record a failure found by the health-endpoint re-check.
     * <p>
     * Unlike {@link #setAuthError} this never notifies: the re-check runs on every
     * status poll, and a desktop notification per poll would be noise. The
     * notification belongs to the traffic path, where a failure actually blocks the
     * user. The console line is still printed on the first transition, so the log
     * shows when the proxy noticed.
     */
    void recordProbeAuthError(String msg) {
        var detail = authDetail(msg, "Vertex authentication check failed");
        synchronized (authLock) {
            if (authError == null) {
                System.err.println("Failed to get Vertex token: " + detail);
            }
            authError = detail;
            authErrorHint = VERTEX_AUTH_HINT;
        }
    }

    // --- Health check ---

    /**
     * How often a health check may re-run gcloud while an auth error stands.
     * The TUI and 'isx doctor' poll this endpoint, so an unthrottled re-check
     * would fork a gcloud process per poll.
     */
    // Overridable for tests
    long authRevalidateIntervalMs = 10_000;

    private void handleHealthCheck(HttpServerRequest req) {
        // Credential state is otherwise only learned from real Vertex traffic, so a
        // status view can be wrong in both directions: reporting a failure the user
        // has already fixed, or reporting nothing at all because no request has been
        // made yet. Verify the token here so the answer reflects the present.
        if (!claimAuthRevalidation()) {
            sendHealthResponse(req);
            return;
        }
        acquireVertexAccessToken()
                .onSuccess(token -> clearAuthError())
                .onFailure(e -> recordProbeAuthError(e.getMessage()))
                .onComplete(ar -> {
                    releaseAuthRevalidation();
                    sendHealthResponse(req);
                });
    }

    /**
     * Returns true if this health check should verify the Vertex token, claiming the
     * right to do so. Declines when:
     * <ul>
     *   <li>Vertex is not in use — a non-Vertex setup must never fork gcloud at all;</li>
     *   <li>the standing error is an OAuth rejection (hint {@code isx init}), which
     *       only the user can resolve — running gcloud would prove nothing;</li>
     *   <li>nothing is wrong and the cached token is still valid, so a check would
     *       learn nothing (this is the steady state: it keeps the healthy path free,
     *       leaving roughly one gcloud call per token lifetime);</li>
     *   <li>a check ran recently or is in flight — status views poll this endpoint,
     *       and a failing credential caches nothing, so every poll would otherwise
     *       fork a fresh gcloud.</li>
     * </ul>
     */
    boolean claimAuthRevalidation() {
        if (!configState.credentials().useVertex()) return false;
        // Lock-free: hasFreshVertexToken() reads an AtomicReference, no monitor.
        var tokenFresh = hasFreshVertexToken();
        synchronized (authLock) {
            if (authError != null && !VERTEX_AUTH_HINT.equals(authErrorHint)) return false;
            if (authError == null && tokenFresh) return false;
            var now = System.currentTimeMillis();
            if (authRevalidateInFlight || now - authRevalidatedMs < authRevalidateIntervalMs) {
                return false;
            }
            authRevalidatedMs = now;
            authRevalidateInFlight = true;
            return true;
        }
    }

    void releaseAuthRevalidation() {
        synchronized (authLock) {
            authRevalidateInFlight = false;
        }
    }

    private void sendHealthResponse(HttpServerRequest req) {
        var info = BuildInfo.instance();
        var err = authError;
        var configDrifted = hasConfigChangedSinceLoad();
        var body = "{\"status\":\"ok\""
                + ",\"version\":\"" + info.version() + "\""
                + ",\"gitSha\":\"" + info.gitSha() + "\""
                + ",\"runtime\":\"" + escapeJson(info.runtime()) + "\""
                + ",\"caFingerprint\":\"" + caFingerprint + "\""
                + ",\"configDrifted\":" + configDrifted
                + ",\"dnsConfigured\":" + dnsConfigured
                + (err != null ? ",\"authError\":\"" + escapeJson(err) + "\"" : "")
                // Lets the CLI signal this process (SIGUSR1: re-read the instance list)
                // without fuser scanning every process on the host to find it.
                + (isHostCaller(req) ? ",\"pid\":" + ProcessHandle.current().pid() : "")
                + "}";
        req.response()
                .putHeader("Content-Type", "application/json")
                .end(body);
    }

    /** What the health server answers a request with. */
    enum HealthRoute { HEALTH, ACTIVITY, CREDENTIAL_PROOFS, NOT_FOUND, METHOD_NOT_ALLOWED }

    /**
     * The health server's routing. It only reads: a write to the proxy's state is never one
     * address check away from the bridge, which every container reaches (DESIGN.md "The proxy
     * holds no configuration of its own", #935). {@code /activity} is the host's only: a
     * container gets the 404 of an unknown path, since what its neighbours do is none of its
     * business, whatever the method.
     */
    static HealthRoute healthRoute(io.vertx.core.http.HttpMethod method, String path,
                                   java.util.function.BooleanSupplier hostCaller) {
        var route = switch (path) {
            case "/health" -> HealthRoute.HEALTH;
            case "/activity" -> hostCaller.getAsBoolean() ? HealthRoute.ACTIVITY : HealthRoute.NOT_FOUND;
            case "/credential-proofs" -> hostCaller.getAsBoolean() ? HealthRoute.CREDENTIAL_PROOFS : HealthRoute.NOT_FOUND;
            case null, default -> HealthRoute.NOT_FOUND;
        };
        return route == HealthRoute.NOT_FOUND || method == io.vertx.core.http.HttpMethod.GET
                || method == io.vertx.core.http.HttpMethod.HEAD ? route : HealthRoute.METHOD_NOT_ALLOWED;
    }

    private void routeHealth(HttpServerRequest req) {
        switch (healthRoute(req.method(), req.path(), () -> isHostCaller(req))) {
            case HEALTH -> handleHealthCheck(req);
            case ACTIVITY -> sendActivity(req);
            case CREDENTIAL_PROOFS -> {
                var registry = instanceRegistry;
                var name = req.getParam("instance");
                var state = registry == null || name == null ? null : registry.proofState(name);
                if (state == null) req.response().setStatusCode(404).end();
                else req.response().putHeader("Content-Type", "application/json").end(
                        "{\"generation\":\"" + escapeJson(state.generation()) + "\",\"verifierHash\":\""
                                + escapeJson(state.verifierHash()) + "\"}");
            }
            case NOT_FOUND -> req.response().setStatusCode(404).end();
            case METHOD_NOT_ALLOWED -> req.response().setStatusCode(405).putHeader("Allow", "GET, HEAD").end();
        }
    }

    /** Each instance's model calls and traffic ({@link ApiActivity}); {@link #healthRoute} keeps it the host's. */
    private void sendActivity(HttpServerRequest req) {
        var registry = instanceRegistry;
        var known = registry == null ? new InstanceRegistry.Incarnations(Map.of(), 0) : registry.incarnations();
        req.response()
                .putHeader("Content-Type", "application/json")
                .end(apiActivity.snapshot(known.byName(), known.view()).toJson(refusedInjections.get()));
    }

    private static boolean isHostCaller(HttpServerRequest req) {
        return isHostCaller(req.remoteAddress() == null ? null : req.remoteAddress().hostAddress(),
                req.localAddress() == null ? null : req.localAddress().hostAddress());
    }

    /**
     * Whether a health request comes from the host rather than a container. Containers reach
     * this endpoint too (it listens on the bridge address), so host-only details such as the
     * PID are withheld from them. The host reaches it over loopback (macOS) or from the bridge
     * address itself; a container's source address is its own, which
     * {@code security.ipv4_filtering} stops it from spoofing.
     */
    static boolean isHostCaller(String remote, String local) {
        if (remote == null || remote.isEmpty()) return false;
        if (remote.equals(local)) return true;
        try {
            return java.net.InetAddress.getByName(remote).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    boolean hasConfigChangedSinceLoad() {
        return !configFingerprint.isCurrent();
    }

    /** The directories of tool definitions the running config was read from, for the watcher. */
    java.util.List<java.nio.file.Path> toolDirs() {
        return configFingerprint.toolDirs();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }


    // --- Debug logging helpers ---

    private static String dumpRequest(HttpServerRequest req) {
        var sb = new StringBuilder();
        sb.append(req.method()).append(' ').append(req.uri())
                .append(' ').append(req.version() == HttpVersion.HTTP_1_1 ? "HTTP/1.1" : "HTTP/1.0")
                .append('\n');
        for (var entry : req.headers()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static String dumpResponse(HttpClientResponse resp) {
        var sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(resp.statusCode())
                .append(' ').append(resp.statusMessage()).append('\n');
        for (var entry : resp.headers()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }
}
