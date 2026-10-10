package dev.incusspawn.proxy;

import dev.incusspawn.BuildInfo;
import dev.incusspawn.Platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.security.cert.X509Certificate;
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

    private static final ObjectMapper JSON = new ObjectMapper();

    // Top-level fields accepted by Vertex AI rawPredict. Anything else (beta features
    // like context_management, etc.) is stripped to avoid "Extra inputs" rejections.
    private static final Set<String> VERTEX_ALLOWED_FIELDS = Set.of(
            "anthropic_version", "messages", "system", "max_tokens",
            "temperature", "top_p", "top_k", "stop_sequences", "stream",
            "metadata", "tools", "tool_choice", "thinking", "output_config"
    );

    // Track which stripped fields have already been logged (avoid spam)
    private final Set<String> loggedStrippedFields = ConcurrentHashMap.newKeySet();

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
    private HttpServer mitmServer;
    private HttpServer healthHttpServer;
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
            Map<String, ResolvedToolProxy> exactDomain,
            List<Map.Entry<String, ResolvedToolProxy>> wildcardSuffixes,
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

    /** Each instance's model calls, for {@code /activity} (#898). */
    final ApiActivity apiActivity = new ApiActivity();

    /** {@code isx mcp} for coordinator instances, at {@link ProxyConfig#MCP_DOMAIN} (#915). */
    private McpBridge mcpBridge;
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
     */
    private record ConfigState(
            dev.incusspawn.config.SpawnConfig config,
            com.fasterxml.jackson.databind.JsonNode configTree,
            Map<String, dev.incusspawn.tool.ToolSetup> toolSetups,
            ProxyCredentials credentials,
            ToolProxyRouting routing,
            Map<String, dev.incusspawn.tool.ToolSetup> setupsByNamespace,
            Map<String, java.util.Set<String>> namespacesByDomain,
            Map<Map<String, String>, AccountBundle> credentialsBySelection,
            Map<InstanceRegistry.InstanceAccounts, Map<String, String>> mismatchesByInstance) {

        ConfigState(dev.incusspawn.config.SpawnConfig config,
                    com.fasterxml.jackson.databind.JsonNode configTree,
                    Map<String, dev.incusspawn.tool.ToolSetup> toolSetups,
                    ProxyCredentials credentials,
                    List<ResolvedToolProxy> proxiesAcrossAccounts) {
            this(config, configTree, toolSetups, credentials,
                    buildRouting(credentials.toolProxies(), proxiesAcrossAccounts, true),
                    dev.incusspawn.config.AccountSelection.byNamespace(toolSetups),
                    dev.incusspawn.config.AccountSelection.namespacesByDomain(toolSetups),
                    new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    private volatile ConfigState configState;

    private final java.util.concurrent.atomic.AtomicBoolean registryRefreshInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * What a single request needs to know about who asked and which credentials answer.
     *
     * @param caller the instance that asked, with its incarnation as the same listing has it
     *               (for {@link ApiActivity}); null for any other caller
     */
    record RequestContext(String domain, InstanceRegistry.Caller caller,
                          ProxyCredentials creds, ToolProxyRouting routing,
                          boolean usesDefaultCredentials) {}

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
        this.configState = new ConfigState(NO_CONFIG, NO_CONFIG.tree(), Map.of(), credentials,
                credentials.toolProxies());
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
        // Populate before serving: an empty snapshot would hand defaults to every pinned
        // instance until the first refresh landed. Off the event loop -- this runs during
        // startup, but keep the blocking call explicit so it stays that way.
        vertx.executeBlocking(() -> { registry.refresh(); return null; })
                .onFailure(e -> ProxyLog.warn("Initial instance registry load failed: " + e.getMessage()));
    }

    /**
     * Credentials for whoever sent this request.
     *
     * <p>An address that is not a known instance -- host-side traffic -- gets the configured
     * defaults, and so does an instance that pins nothing. A template build that pins an
     * account is a known instance: it holds a static IP while it builds (#903).
     * Only an explicit pin diverges, and a pin naming an account that is not configured
     * raises {@link dev.incusspawn.config.AccountResolver.UnknownAccountException} so the
     * caller can fail the request instead of spending the wrong credential (#351).
     *
     * <p>Never blocks: reads the registry snapshot and schedules a refresh when it is stale.
     */
    RequestContext contextFor(String domain, String sourceAddress) {
        var state = configState;
        var registry = instanceRegistry;
        if (registry == null) return new RequestContext(domain, null, state.credentials(), state.routing(), true);

        var caller = registry.resolve(sourceAddress);
        var instance = caller == null ? null : caller.accounts();
        // A miss is the case worth refreshing for: a branch that happened since the last
        // snapshot. isx signals the proxy on branch, so this is only the backstop -- and it is
        // rate-limited, because host-side traffic has no static IP and so misses every time.
        if (instance == null ? registry.wantsMissRefresh() : registry.isStale()) {
            scheduleRegistryRefresh(registry);
        }

        if (instance != null && !instance.bakedIdentities().isEmpty()) {
            refuseIfUnservable(state, instance, domain);
        }
        if (instance == null || instance.usesDefaults()) {
            return new RequestContext(domain, caller, state.credentials(), state.routing(), true);
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
        return new RequestContext(domain, caller, bundle.creds(), bundle.routing(), false);
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
        var setups = ToolProxyResolver.proxyToolSetups(config);
        var tree = config.tree();
        var previous = configState;
        // The default selection is resolved once, and seeds the across-accounts set.
        var credentials = ProxyCredentials.forAccounts(config, tree, Map.of(), setups);
        configState = new ConfigState(config, tree, setups, credentials,
                ToolProxyResolver.resolveAcrossAccounts(tree, setups, credentials.toolProxies()));
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
    com.fasterxml.jackson.databind.JsonNode configTree() {
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
        if (registry != null) registry.refresh();
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
        var exact = new java.util.LinkedHashMap<String, ResolvedToolProxy>();
        var wildcards = new ArrayList<Map.Entry<String, ResolvedToolProxy>>();
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
            if (domain.startsWith("*.")) {
                var suffix = domain.substring(1); // ".example.com"
                if (warn) {
                    // Only computed when it can be reported: the per-selection builds pass
                    // warn=false, and this scan's sole consumer is the message below.
                    var firstForSuffix = wildcards.stream()
                            .filter(e -> e.getKey().equals(suffix))
                            .findFirst().orElse(null);
                    if (firstForSuffix != null
                            && !firstForSuffix.getValue().toolName().equals(tp.toolName())) {
                        ProxyLog.warn("Tool '" + tp.toolName() + "' claims wildcard '" + domain
                                + "' already registered by tool '" + firstForSuffix.getValue().toolName()
                                + "' — first match wins");
                    }
                }
                wildcards.add(Map.entry(suffix, tp));
            } else {
                var existing = exact.putIfAbsent(domain, tp);
                if (existing != null) {
                    if (warn) {
                        ProxyLog.warn("Tool '" + tp.toolName() + "' claims domain '" + domain
                                + "' already registered by tool '" + existing.toolName() + "' — skipping");
                    }
                    continue;
                }
            }
        }

        wildcards.sort(Comparator.<Map.Entry<String, ResolvedToolProxy>, Integer>comparing(
                e -> e.getKey().length()).reversed());

        return new ToolProxyRouting(
                Map.copyOf(exact),
                List.copyOf(wildcards),
                ProxyConfig.interceptedDomains(extraDomains),
                List.copyOf(suffixSet));
    }

    ResolvedToolProxy findToolProxy(String domain) {
        return findToolProxy(configState.routing(), domain);
    }

    /**
     * The tool proxy serving a domain for one request. Routing is global (which domains are
     * intercepted never depends on the account) but the credential inside the entry is not,
     * so a pinned instance looks this up in its own routing.
     */
    private static ResolvedToolProxy findToolProxy(ToolProxyRouting routing, String domain) {
        var exact = routing.exactDomain().get(domain);
        if (exact != null) return exact;
        for (var entry : routing.wildcardSuffixes()) {
            if (domain.endsWith(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    public Set<String> allInterceptedDomains() {
        return configState.routing().allInterceptedDomains();
    }

    private boolean isInterceptedDomain(String domain) {
        var routing = configState.routing();
        return ProxyConfig.isInterceptedDomain(domain,
                routing.exactDomain().keySet(), routing.suffixes());
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
            if (mitmServer != null) {
                var sslOptions = new io.vertx.core.net.SSLOptions()
                        .setKeyCertOptions(keyCertOptions);
                mitmServer.updateSSLOptions(sslOptions)
                        .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
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
        if (stopLatch.getCount() == 0) return;

        // Per-domain certs chosen by SNI, with wildcards (*.domain) pre-minted so
        // subdomains resolved via dnsmasq address= overrides get a valid cert (e.g.
        // cdn01.quay.io); deeper names get a leaf minted on demand (#783). Reuses persisted leaf certs across restarts so their notBefore stays
        // stable (minted while clocks were in sync); only mint on miss/expiry/CA
        // rotation. See CertStore for why per-start re-minting broke validation
        // on hosts whose container clock lags (e.g. macOS VM after resume).
        var keyCertOptions = buildKeyCertOptions();

        // MITM TLS server with SNI
        var serverOptions = new HttpServerOptions()
                .setHost(bindAddress)
                .setPort(requestedMitmPort)
                .setSsl(true)
                .setSni(true)
                .setKeyCertOptions(keyCertOptions)
                .setIdleTimeout(MITM_IDLE_TIMEOUT_SECONDS)
                .setIdleTimeoutUnit(TimeUnit.SECONDS)
                .setAlpnVersions(List.of(HttpVersion.HTTP_1_1))
                .setMaxWebSocketFrameSize(1024 * 1024)
                .setMaxWebSocketMessageSize(16 * 1024 * 1024);

        upstream.createClients();

        int maxRetries = 30;
        for (int attempt = 1; ; attempt++) {
            mitmServer = vertx.createHttpServer(serverOptions);
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
            if (mcpBridge == null) {
                mcpBridge = new McpBridge(vertx, () -> instanceRegistry, mcpCommand);
                mcpBridge.start();
            }
            mitmServer.requestHandler(this::routeRequest);
            mitmServer.webSocketHandler(this::routeWebSocket);
            try {
                boundMitmPort = mitmServer.listen()
                        .toCompletionStage().toCompletableFuture().get().actualPort();
                break;
            } catch (Exception e) {
                // A port the kernel picks cannot be held by an earlier proxy: nothing to wait for.
                if (attempt >= maxRetries || requestedMitmPort == 0 || !isBindException(e)) throw e;
                if (!ProxyHealthCheck.isHealthy(healthBindAddress, requestedHealthPort)) throw e;
                ProxyLog.warn("Port " + requestedMitmPort + " in use, previous proxy still running (" + attempt + "/" + maxRetries + ")");
                Thread.sleep(200);
            }
        }

        // Health check HTTP server (plain, no TLS)
        healthHttpServer = vertx.createHttpServer()
                .requestHandler(req -> {
                    switch (req.path()) {
                        case "/health" -> handleHealthCheck(req);
                        case "/activity" -> sendActivity(req);
                        default -> req.response().setStatusCode(404).end();
                    }
                });
        boundHealthPort = healthHttpServer.listen(requestedHealthPort, healthBindAddress)
                .toCompletionStage().toCompletableFuture().get().actualPort();

        if (onReady != null) {
            onReady.run();
        }

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

    public void stop() {
        ProxyLog.info("Stopping proxy");
        try {
            artifactCache.logCacheStats();
            try {
                if (mitmServer != null) mitmServer.close().toCompletionStage().toCompletableFuture().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            upstream.closeClients();
            if (mcpBridge != null) mcpBridge.stop();
            try {
                if (healthHttpServer != null) healthHttpServer.close().toCompletionStage().toCompletableFuture().get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
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
            if (domain == null) {
                sendError(clientReq.response(), 502, "Unknown domain");
                return;
            }

            Consumer<HttpServerRequest> cache;
            if (ProxyConfig.MCP_DOMAIN.equals(domain)) {
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
                RequestContext ctx;
                try {
                    ctx = contextFor(domain, sourceAddressOf(clientReq));
                } catch (dev.incusspawn.config.AccountResolver.UnknownAccountException e) {
                    // Fail closed: this instance is pinned to an account that is gone.
                    // Falling back to the default would quietly spend another account.
                    ProxyLog.warn("Refusing " + domain + " for " + describeCaller(clientReq)
                            + ", pinned to missing " + e.namespace()
                            + " account '" + e.accountName() + "'");
                    sendError(clientReq.response(), 502, e.getMessage());
                    return;
                } catch (dev.incusspawn.config.AccountSelection.UnservableAccountException e) {
                    // Fail closed too: the account exists, but not of the kind this instance
                    // was built for, so serving it would hand it a mismatched credential.
                    ProxyLog.warn("Refusing " + domain + " for " + describeCaller(clientReq) + ": " + e.getMessage());
                    sendError(clientReq.response(), 502, e.getMessage());
                    return;
                }
                handleApiRequest(clientReq, ctx);
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
        if (host == null) {
            clientWs.reject(400);
            return;
        }
        var colon = host.indexOf(':');
        var domain = colon > 0 ? host.substring(0, colon) : host;
        if (ProxyConfig.MCP_DOMAIN.equals(domain)) {
            // MCP is plain HTTP here, and the name has no upstream to relay a socket to.
            clientWs.reject(404);
            return;
        }
        RequestContext ctx;
        try {
            ctx = contextFor(domain, sourceAddressOf(clientWs));
        } catch (dev.incusspawn.config.AccountResolver.UnknownAccountException e) {
            // Same fail-closed rule as the HTTP path: an instance pinned to an account
            // that is gone gets an error, never someone else's credential.
            ProxyLog.warn("Refusing WebSocket to " + domain + " for "
                    + describeCaller(sourceAddressOf(clientWs)) + ", pinned to missing "
                    + e.namespace() + " account '" + e.accountName() + "'");
            clientWs.reject(502);
            return;
        } catch (dev.incusspawn.config.AccountSelection.UnservableAccountException e) {
            ProxyLog.warn("Refusing WebSocket to " + domain + " for "
                    + describeCaller(sourceAddressOf(clientWs)) + ": " + e.getMessage());
            clientWs.reject(502);
            return;
        }
        if (!completeHandshake(clientWs, domain)) return;
        try {
            handleWebSocketUpgrade(clientWs, ctx);
        } catch (RuntimeException e) {
            // The 101 has gone out, so a refusal can only be a close: a paused socket with
            // no relay would otherwise hang until the idle timeout
            ProxyLog.warn("WebSocket relay setup failed (" + domain + "): " + e.getMessage());
            if (!clientWs.isClosed()) clientWs.close((short) 1011, "Proxy error");
        }
    }

    /**
     * Completes the client's handshake, or reports that Netty refused it. Vert.x 4 calls the
     * WebSocket handler before Netty has validated the request, and would only complete the
     * handshake once the handler returned; a request Netty then refused (no key, no
     * {@code Upgrade} token in {@code Connection}, ...) had by then been given a credential
     * and an upstream socket (#972). A valid client sees no difference: its 101 used to go
     * out as soon as the handler returned, before the upstream connection was up, too.
     * <p>
     * {@code accept()} runs Netty's handshake in this call and throws its refusal, after
     * Vert.x has answered it with a 400. It is deprecated and gone in Vert.x 5;
     * {@code WebSocketProxyTest#refusedUpgradeIsAnswered400AndNeverDialled} pins the
     * behaviour for whatever replaces it.
     */
    @SuppressWarnings("deprecation")
    private boolean completeHandshake(ServerWebSocket clientWs, String domain) {
        // Paused before the 101, so frames arriving before the upstream connection is
        // ready are buffered, not dropped
        clientWs.pause();
        try {
            clientWs.accept();
            return true;
        } catch (RuntimeException e) {
            ProxyLog.warn("Refusing WebSocket to " + domain + " for "
                    + describeCaller(sourceAddressOf(clientWs)) + ": " + e.getMessage());
            return false;
        }
    }

    private void handleWebSocketUpgrade(ServerWebSocket clientWs, RequestContext ctx) {
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
                    upstreamWs.writeFrame(frame);
                }
            });
            upstreamWs.frameHandler(frame -> {
                if ((frame.isText() || frame.isBinary() || frame.isContinuation())
                        && !clientWs.isClosed()) {
                    clientWs.writeFrame(frame);
                }
            });

            clientWs.closeHandler(v -> {
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
        var domain = ctx.domain();
        var credentials = ctx.creds();
        if (ANTHROPIC_DOMAINS.contains(domain)) {
            if (!credentials.oauthToken().isBlank()) {
                options.putHeader("Authorization", "Bearer " + credentials.oauthToken());
                options.removeHeader("x-api-key");
            } else if (!credentials.anthropicApiKey().isBlank()) {
                options.putHeader("x-api-key", credentials.anthropicApiKey());
            }
        } else {
            var tp = findToolProxy(ctx.routing(), domain);
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

    private void handleApiRequest(HttpServerRequest clientReq, RequestContext ctx) {
        clientReq.body().onSuccess(bodyBuffer -> {
            try {
                handleApiRequestWithBody(clientReq, ctx, bodyBuffer);
            } catch (Exception e) {
                System.err.println("API request error: " + e.getMessage());
                e.printStackTrace(System.err);
                sendError(clientReq.response(), 502, "Proxy error");
            }
        }).onFailure(err -> {
            System.err.println("Failed to read API request body: " + err.getMessage());
            sendError(clientReq.response(), 502, "Proxy error");
        });
    }

    private void handleApiRequestWithBody(HttpServerRequest clientReq, RequestContext ctx,
                                           Buffer bodyBuffer) throws Exception {
        var domain = ctx.domain();
        var credentials = ctx.creds();
        String upstreamHost;
        byte[] bodyBytes = bodyBuffer.getBytes();
        var exchange = beginModelCall(clientReq, ctx);
        boolean isVertexRequest = false;
        boolean bodyRewritten = false;
        String originalDump = null;
        byte[] originalBody = null;

        if (debugLog != null) {
            originalDump = dumpRequest(clientReq);
            originalBody = bodyBytes;
        }

        var path = clientReq.path();
        var uri = clientReq.uri();
        var requestOptions = new RequestOptions()
                .setMethod(clientReq.method())
                .setPort(443);

        if (credentials.useVertex() && ANTHROPIC_DOMAINS.contains(domain) && path != null) {
            if (path.startsWith("/v1/projects/")) {
                // Already Vertex-formatted (container running in Vertex mode with
                // ANTHROPIC_VERTEX_BASE_URL pointing here): forward to real Vertex.
                // The Vertex SDK uses @date suffixes (e.g. claude-haiku-4-5@20251001)
                // which the global endpoint rejects — strip them.
                upstreamHost = vertexHost(credentials);
                isVertexRequest = true;
                uri = path.replaceFirst("@\\d{8}(?=:)", "");
            } else if (path.startsWith("/v1/messages")) {
                // Standard API format: translate to Vertex AI rawPredict
                upstreamHost = vertexHost(credentials);
                isVertexRequest = true;
                var translated = translateToVertex(path, bodyBytes, upstreamHost, credentials);
                uri = translated.path;
                bodyBytes = translated.body;
                bodyRewritten = true;
            } else {
                // Non-messages endpoints (settings, bootstrap, feature flags, etc.)
                upstreamHost = domain;
            }
        } else {
            upstreamHost = domain;
        }
        requestOptions.setHost(upstreamHost).setURI(uri);

        sendApiRequest(clientReq, requestOptions, upstreamHost, ctx,
                bodyBytes, isVertexRequest, bodyRewritten, false,
                originalDump, originalBody, exchange);
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

    private void sendApiRequest(HttpServerRequest clientReq, RequestOptions requestOptions,
                                String upstreamHost, RequestContext ctx,
                                byte[] bodyBytes, boolean isVertexRequest,
                                boolean bodyRewritten, boolean isRetry,
                                String originalDump, byte[] originalBody, ApiActivity.Exchange exchange) {
        var domain = ctx.domain();
        var credentials = ctx.creds();
        requestWithAsyncDns(requestOptions).onSuccess(upReq -> {
            copyRequestHeaders(clientReq, upReq, domain);
            // A compressed answer would hide its token usage from apiActivity
            if (exchange != null) upReq.putHeader("Accept-Encoding", "identity");
            injectHeaders(upReq, ctx, upstreamHost, isVertexRequest).onSuccess(ok -> {
                if (!ok) {
                    var err = authError;
                    var detail = err != null ? err : "Failed to obtain upstream credentials";
                    sendError(clientReq.response(), 502, detail);
                    return;
                }
                upReq.putHeader("Content-Length", String.valueOf(bodyBytes.length));

                upReq.send(Buffer.buffer(bodyBytes)).onSuccess(upResp -> {
                    if (!isRetry && isVertexRequest && upResp.statusCode() == 401) {
                        System.err.println("Vertex 401: invalidating cached token and retrying");
                        invalidateVertexToken();
                        sendApiRequest(clientReq, requestOptions, upstreamHost, ctx,
                                bodyBytes, isVertexRequest, bodyRewritten, true,
                                originalDump, originalBody, exchange);
                        return;
                    }

                    // Only the default credentials drive the host's auth status. A pinned
                    // instance's broken token is reported to that instance (it sees the 401)
                    // but must not tell the user their own default credential has failed --
                    // nor clear a real default-account error by succeeding.
                    if (ctx.usesDefaultCredentials()
                            && !credentials.oauthToken().isBlank() && ANTHROPIC_DOMAINS.contains(domain)) {
                        if (upResp.statusCode() == 401) {
                            setAuthError("Claude OAuth token rejected (HTTP 401). "
                                    + "The token may have expired — run 'isx init' to refresh.",
                                    "isx init");
                        } else {
                            clearAuthError();
                        }
                    }

                    relayApiResponse(clientReq, upResp, upstreamHost, domain,
                            bodyBytes, bodyRewritten, originalDump, originalBody, exchange);
                }).onFailure(err -> {
                    System.err.println("Upstream send error (" + domain + "): " + err.getMessage());
                    sendError(clientReq.response(), 502, "Upstream error");
                });
            });
        }).onFailure(err -> {
            System.err.println("Upstream connect error (" + domain + "): " + err.getMessage());
            sendError(clientReq.response(), 502, "Upstream connection failed");
        });
    }

    private void relayApiResponse(HttpServerRequest clientReq, HttpClientResponse upResp,
                                   String upstreamHost, String domain,
                                   byte[] sentBody, boolean bodyRewritten,
                                   String originalDump, byte[] originalBody, ApiActivity.Exchange exchange) {
        var clientResp = clientReq.response();
        clientResp.setStatusCode(upResp.statusCode());
        clientResp.setStatusMessage(upResp.statusMessage());
        copyResponseHeaders(upResp, clientResp);
        if (exchange != null) {
            exchange.respond(upResp.statusCode(), upResp.getHeader("Content-Type"), upResp.getHeader("Content-Encoding"));
        }

        if (debugLog != null) {
            upResp.body().onSuccess(respBody -> {
                var respBytes = respBody.getBytes();
                if (exchange != null) exchange.accept(respBody);
                var responseDump = dumpResponse(upResp);
                debugLog.logExchange(
                        originalDump, originalBody,
                        null, bodyRewritten ? sentBody : null,
                        responseDump, respBytes.length > 0 ? respBytes : null);
                clientResp.putHeader("Content-Length", String.valueOf(respBytes.length));
                clientResp.end(Buffer.buffer(respBytes));
            }).onFailure(err -> {
                System.err.println("Failed to capture debug response: " + err.getMessage());
                sendError(clientResp, 502, "Debug capture error");
            });
        } else {
            pipeResponse(upResp, clientResp, null, exchange == null ? null : exchange::accept);
        }
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

    private void copyResponseHeaders(HttpClientResponse upResp, HttpServerResponse clientResp) {
        upstream.copyResponseHeaders(upResp, clientResp);
    }

    private void pipeResponse(HttpClientResponse upResp, HttpServerResponse clientResp,
                              Upstream.RelayWatchdog watchdog, java.util.function.Consumer<Buffer> tap) {
        upstream.pipeResponse(upResp, clientResp, watchdog, tap);
    }

    private void sendError(HttpServerResponse resp, int statusCode, String message) {
        upstream.sendError(resp, statusCode, message);
    }

    // --- Vertex AI translation ---

    private record VertexTranslation(String path, byte[] body) {}

    /**
     * Translate a standard Anthropic API request into a Vertex AI rawPredict request.
     * <p>
     * Differences between the two APIs:
     * <ul>
     *   <li>URL: /v1/messages → /v1/projects/{pid}/locations/{region}/publishers/anthropic/models/{model}:rawPredict</li>
     *   <li>Auth: x-api-key header → Authorization: Bearer (GCP token)</li>
     *   <li>Body: only {@link #VERTEX_ALLOWED_FIELDS} are kept; everything else is stripped</li>
     *   <li>Body: "model" replaced with "anthropic_version": "vertex-2023-10-16"</li>
     *   <li>Body: "scope" removed from nested cache_control objects (beta feature)</li>
     *   <li>Header: anthropic-beta removed (Vertex features are enabled via anthropic_version)</li>
     *   <li>Streaming: :rawPredict → :streamRawPredict when stream=true</li>
     * </ul>
     */
    private VertexTranslation translateToVertex(String originalPath, byte[] bodyBytes,
                                                 String upstreamHost, ProxyCredentials credentials) {
        try {
            var tree = bodyBytes.length > 0 ? JSON.readTree(bodyBytes) : null;

            // Non-JSON or non-object body (e.g. GET /v1/models): just forward as-is
            if (tree == null || !tree.isObject()) {
                return new VertexTranslation(originalPath, bodyBytes);
            }

            var root = (ObjectNode) tree;

            // Extract model (goes into URL, not body). The global Vertex endpoint
            // only accepts short aliases, so strip date suffixes like -20251001.
            var model = root.has("model") ? root.get("model").asText() : "claude-sonnet-4-6";
            model = model.replaceFirst("-\\d{8}$", "");
            var streaming = root.has("stream") && root.get("stream").asBoolean();

            // Strip all top-level fields Vertex doesn't support (beta features, etc.)
            root.remove("model");
            var fieldNames = new java.util.ArrayList<String>();
            root.fieldNames().forEachRemaining(fieldNames::add);
            var stripped = new java.util.ArrayList<String>();
            for (var field : fieldNames) {
                if (!VERTEX_ALLOWED_FIELDS.contains(field)) {
                    root.remove(field);
                    stripped.add(field);
                }
            }
            if (!stripped.isEmpty() && loggedStrippedFields.addAll(stripped)) {
                System.err.println("Vertex translation: stripped unsupported fields: " + stripped);
            }

            root.put("anthropic_version", "vertex-2023-10-16");
            // Strip "scope" from cache_control objects deep in the tree (beta feature)
            stripCacheControlScope(root);

            var rewrittenBytes = JSON.writeValueAsBytes(root);

            var endpoint = streaming ? ":streamRawPredict" : ":rawPredict";
            var vertexPath = "/v1/projects/" + credentials.vertexProjectId() + "/locations/" + credentials.vertexRegion() +
                    "/publishers/anthropic/models/" + model + endpoint;

            return new VertexTranslation(vertexPath, rewrittenBytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to translate request body to Vertex format", e);
        }
    }

    /** Recursively remove "scope" from any "cache_control" object in the JSON tree. */
    private void stripCacheControlScope(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var obj = (ObjectNode) node;
            if (obj.has("cache_control") && obj.get("cache_control").isObject()) {
                ((ObjectNode) obj.get("cache_control")).remove("scope");
            }
            for (var it = obj.elements(); it.hasNext(); ) {
                stripCacheControlScope(it.next());
            }
        } else if (node.isArray()) {
            for (var element : node) {
                stripCacheControlScope(element);
            }
        }
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
            } else {
                upReq.headers().remove("x-api-key");
            }
        } else {
            var tp = findToolProxy(ctx.routing(), domain);
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

    private String fetchGcloudToken() {
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

    /**
     * Each instance's model calls ({@link ApiActivity}), for the host only: a container gets
     * the 404 of an unknown path, since what its neighbours do is none of its business.
     */
    private void sendActivity(HttpServerRequest req) {
        if (!isHostCaller(req)) {
            req.response().setStatusCode(404).end();
            return;
        }
        var registry = instanceRegistry;
        var known = registry == null ? new InstanceRegistry.Incarnations(Map.of(), 0) : registry.incarnations();
        req.response()
                .putHeader("Content-Type", "application/json")
                .end(apiActivity.snapshot(known.byName(), known.view()).toJson());
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

    private String dumpRequest(HttpServerRequest req) {
        var sb = new StringBuilder();
        sb.append(req.method()).append(' ').append(req.uri())
                .append(' ').append(req.version() == HttpVersion.HTTP_1_1 ? "HTTP/1.1" : "HTTP/1.0")
                .append('\n');
        for (var entry : req.headers()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    private String dumpResponse(HttpClientResponse resp) {
        var sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(resp.statusCode())
                .append(' ').append(resp.statusMessage()).append('\n');
        for (var entry : resp.headers()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }
}
