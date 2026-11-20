package dev.incusspawn.proxy;

import dev.incusspawn.config.AccountResolver;
import dev.incusspawn.config.AccountShape;
import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Antigravity CLI's Google sign-in for an instance whose {@code gemini} account is
 * {@code type: google} (#2443): the session stays on the host ({@link GoogleSessionStore}), the
 * guest holds look-alikes carrying a digest the proxy can verify ({@link GooglePlaceholders}),
 * and this route is where a look-alike becomes the session's token, as {@link ChatGptRoute} is
 * for Codex.
 *
 * <h3>Which requests</h3>
 * Only requests that <em>claim</em> to be isx's -- an isx access placeholder as the bearer, or
 * an isx refresh placeholder in a token request -- are served, plus the one request that creates
 * a session: agy's exchange of the code the user pasted after signing in, from an instance on
 * such an account. Everything else to these hosts is relayed untouched, so a sign-in in an
 * instance whose account is an API key sees a transparent proxy. A claimed request has to pass,
 * each failure answered here and never relayed: its shape ({@link ChatGptRoute#shapeProblem},
 * 400); its path, {@code /v1internal...} on the Code Assist hosts and {@code /oauth2/v2/userinfo}
 * on {@code www.googleapis.com} (403); and its caller, the instance the digest proves
 * ({@link InstanceRegistry#prove}) or the run the proxy minted it for, whose {@code gemini}
 * account is a Google one with a session (401).
 *
 * <h3>The host is the boundary at the backend, not a method list</h3>
 * agy calls dozens of {@code /v1internal} methods, and a release adds more; every one reaches the
 * user's own Antigravity data, at the two hosts whose TLS name the request must match. The
 * session's token has the {@code cloud-platform} scope, so what matters is that the proxy sends
 * it to those hosts and nowhere else.
 *
 * <h3>Sign-in</h3>
 * agy exchanges the pasted code at {@code oauth2.googleapis.com/token} with its own client id and
 * secret, so isx holds neither. The exchange is relayed as sent when the caller is an instance on
 * a Google account, and Google's answer becomes that account's session, replacing an earlier one.
 * On macOS the address names no instance, so the sign-in is held to the one instance that proved
 * itself and was told to sign in moments before ({@link #signInAccount}); an exchange the proxy
 * cannot attribute is refused, never relayed. So is an oversized token request.
 * The guest gets placeholders carrying a digest minted for this run: the proxy holds no
 * credential seed, so it cannot mint the instance's own proof, and the next login's script
 * ({@code AntigravitySetup.LOGIN_SCRIPT}) replaces it with that proof. A minted digest lives in
 * memory for {@link #PENDING_DIGEST_TTL_MILLIS}, bound to the caller's address where there is one.
 *
 * <h3>Refresh</h3>
 * agy refreshes when its token has expired, which the look-alikes' times make it do when the real
 * one has. The guest's request is relayed with the real refresh token in place of the placeholder
 * only when the real token needs it, within {@link #REFRESH_WINDOW_SECONDS} of expiry or after a
 * 401 from the backend, and otherwise answered from the session. Google does not rotate the
 * refresh token, so a lost answer costs nothing, but one refresh per session goes upstream at a
 * time and at most one per {@link #MIN_UPSTREAM_INTERVAL_MILLIS}, as for ChatGPT.
 *
 * <p>Built from a probe of agy 1.5.12 against a stub of Google's endpoints: it shows that isx
 * works with the tested client, not that Google supports the integration.
 */
final class GoogleAuthRoute {

    static final String TOKEN = GoogleAccounts.TOKEN;
    static final String USERINFO = GoogleAccounts.USERINFO;
    /** Antigravity CLI's OAuth client id, public in every sign-in URL it prints: a request naming another is not agy's. */
    static final String AGY_CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";
    /** Where agy's sign-in sends the browser; the code shown there is what the user pastes. */
    static final String AGY_REDIRECT_URI = "https://antigravity.google/oauth-callback";
    static final long REFRESH_WINDOW_SECONDS = 300;
    static final long MIN_UPSTREAM_INTERVAL_MILLIS = 30_000;
    /** How long a digest minted at sign-in is honoured: the run that signed in, until a login replaces it. */
    static final long PENDING_DIGEST_TTL_MILLIS = 24L * 60 * 60 * 1000;
    /** How many minted digests the proxy keeps at once. */
    static final int MAX_PENDING = 64;
    private static final int MAX_TOKEN_BODY = 64 * 1024;
    private static final String HINT = " (isx, #2443)";
    /** The characters a target may hold here: {@link ChatGptRoute}'s, and ':' for {@code /v1internal:method}. */
    private static final Pattern SAFE_TARGET = Pattern.compile("[A-Za-z0-9/._~+=&?:-]{1,512}");
    /** The Code Assist API: one of its methods, or a resource under it. */
    private static final Pattern BACKEND_PATH = Pattern.compile("/v1internal(:[A-Za-z0-9]+|/[A-Za-z0-9/._-]+)");
    private static final Set<String> USERINFO_PATHS = Set.of("/oauth2/v1/userinfo", "/oauth2/v2/userinfo", "/oauth2/v3/userinfo");
    private static final Set<HttpMethod> BACKEND_METHODS = Set.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
            HttpMethod.PATCH, HttpMethod.DELETE);
    /** The form agy's code exchange and refresh send, key for key. */
    private static final Set<String> EXCHANGE_KEYS = Set.of("client_id", "client_secret", "code", "code_verifier",
            "grant_type", "redirect_uri");
    private static final Set<String> REFRESH_KEYS = Set.of("client_id", "client_secret", "grant_type", "refresh_token");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** How long an instance that was told to sign in may complete the sign-in: the browser step takes a while. */
    static final long SIGN_IN_WINDOW_MILLIS = 15L * 60 * 1000;

    /**
     * A digest minted at sign-in: whose account and instance it stands for, from which address
     * (null where the address named no instance), until when.
     */
    private record Pending(String account, String instance, String address, long expiresAt) {}

    /** The caller a claimed request was verified as: its instance and account, and the digest it presented. */
    private record Binding(String account, String digest, String instance) {}

    /** An instance that proved itself and found its Google account without a session: the one a sign-in may be for. */
    private record SigningIn(String account, long expiresAt) {}

    private final MitmProxy proxy;
    private final io.vertx.core.Vertx vertx;
    private final GoogleSessionStore store;
    /** How many refreshes joined one already in flight; read by tests. */
    final java.util.concurrent.atomic.AtomicInteger joined = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, Future<Refreshed>> refreshes = new ConcurrentHashMap<>();
    /** The sessions whose token the backend refused, by {@link #sessionKey} and generation. */
    private final Map<String, Set<Long>> refused = new ConcurrentHashMap<>();
    private final Map<String, Long> lastUpstream = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** By instance name: those told to sign in ({@link #NOT_SIGNED_IN}) within {@link #SIGN_IN_WINDOW_MILLIS}. */
    private final Map<String, SigningIn> signingIn = new ConcurrentHashMap<>();
    private final Set<String> loggedRefusals = ConcurrentHashMap.newKeySet();

    private volatile JqValue configTree = JqValues.parse("{}");
    private volatile AccountShape shape = AccountShape.UNDECLARED;
    private volatile boolean enabled;

    GoogleAuthRoute(MitmProxy proxy, io.vertx.core.Vertx vertx, GoogleSessionStore store) {
        this.proxy = proxy;
        this.vertx = vertx;
        this.store = store;
    }

    /** Takes on a config: the route is active only while some {@code gemini} account is a Google one. */
    void useConfig(JqValue tree, AccountShape geminiShape) {
        configTree = tree;
        shape = geminiShape == null ? AccountShape.UNDECLARED : geminiShape;
        enabled = GoogleAccounts.enabledBy(tree);
    }

    boolean handles(String domain) {
        return enabled && GoogleAccounts.DOMAINS.contains(domain);
    }

    /**
     * @param byProofAlone whether a caller at this address may be identified by what it presents
     *                     alone (macOS, #1582), as {@code MitmProxy.routeRequest} decides it
     * @param started      when the client last heard from the proxy, as {@code Upstream.relayRequest} counts it
     */
    void handle(HttpServerRequest req, String domain, String sourceAddress, boolean byProofAlone, long started) {
        if (domain.equals(TOKEN) && req.method() == HttpMethod.POST
                && (req.path().equals("/token") || req.path().equals("/revoke"))) {
            // A token request is never this large; one relayed unread would be the one way an
            // instance on a Google account could get Google's real tokens into its own files
            if (ChatGptRoute.declaredLength(req) > MAX_TOKEN_BODY) {
                warnOnce("Refusing an oversized Google token request from " + describe(sourceAddress) + " (#2443)");
                ChatGptRoute.tooLarge(req);
                return;
            }
            ChatGptRoute.readTokenRequest(req).onSuccess(body -> handleTokenRequest(req, body, sourceAddress, byProofAlone));
            return;
        }
        var digest = GooglePlaceholders.digestOf(ChatGptRoute.bearer(req.getHeader("Authorization")));
        if (digest == null && !claimsBearer(req)) {
            proxy.upstream.relayRequest(req, domain, started);
            return;
        }
        // Everything a claimed request is judged on is in its head: its body is not read until
        // the caller has proved it may send one, and then it streams, never held whole
        if (!allowed(req, domain, sourceAddress)) {
            ChatGptRoute.stopReading(req);
            return;
        }
        if (Upstream.hasBody(req)) req.pause();
        proven(req, sourceAddress, byProofAlone, digest, binding -> forward(req, domain, binding, sourceAddress, started));
    }

    /** Whether any {@code Authorization} header claims an isx placeholder, well-formed or not. */
    private static boolean claimsBearer(HttpServerRequest req) {
        for (var value : req.headers().getAll("Authorization")) {
            if (GooglePlaceholders.claims(ChatGptRoute.bearer(value))) return true;
        }
        return false;
    }

    /** Checks a claimed request's shape (400) and its path (403), both read off its head. */
    private boolean allowed(HttpServerRequest req, String domain, String sourceAddress) {
        var resp = req.response();
        if (!req.isEnded()) resp.putHeader("Connection", "close");
        var problem = ChatGptRoute.shapeProblem(req.headers(), domain, req.uri(), ChatGptRoute.sni(req), SAFE_TARGET);
        if (problem != null) {
            refuse(resp, 400, "invalid_request", problem);
            return false;
        }
        var ok = domain.equals(USERINFO)
                ? req.method() == HttpMethod.GET && USERINFO_PATHS.contains(req.path())
                : BACKEND_METHODS.contains(req.method()) && BACKEND_PATH.matcher(req.path()).matches();
        if (!ok) {
            var what = req.method() + " " + req.path();
            warnOnce("Refusing " + domain + " " + what + " for " + describe(sourceAddress)
                    + ": not a request Google sign-ins allow (#2443)");
            refuse(resp, 403, "forbidden", what + " is not allowed for a Google sign-in");
        }
        return ok;
    }

    // --- The caller ---

    /**
     * Hands {@code then} the caller's account, if {@code digest} is a proof of the instance at
     * {@code sourceAddress} or a digest minted for a sign-in from there, and that instance's
     * {@code gemini} account is a Google one; otherwise answers 401. A refusal right after a start
     * may come from a registry listing that predates it, so it gets {@link CallerCheck}'s one
     * throttled refresh and one more try.
     */
    private void proven(HttpServerRequest req, String sourceAddress, boolean byProofAlone, String digest,
                        java.util.function.Consumer<Binding> then) {
        var resp = req.response();
        if (digest == null) {
            refuseProof(resp, sourceAddress, "the placeholder is malformed");
            return;
        }
        var minted = pending.get(digest);
        if (minted != null) {
            if (minted.expiresAt() < System.currentTimeMillis()) {
                pending.remove(digest);
            } else if (minted.address() == null || minted.address().equals(sourceAddress)) {
                // Held to the instance that signed in: at an address the registry names, that
                // instance on that account (a later instance given the address is not the run that
                // signed in); at an address it does not name (macOS), that instance still running
                var registry = proxy.instanceRegistry();
                var known = registry == null ? null : registry.lookup(sourceAddress);
                var still = known != null
                        ? known.instanceName().equals(minted.instance()) && minted.account().equals(googleAccountOf(known))
                        : registry != null && registry.incarnations().running().contains(minted.instance());
                if (still) {
                    then.accept(new Binding(minted.account(), digest, minted.instance()));
                    return;
                }
            }
        }
        var registry = proxy.instanceRegistry();
        if (registry == null) {
            refuseProof(resp, sourceAddress, "no instance is known to the proxy");
            return;
        }
        CallerCheck.identify(vertx, req, registry, r -> provenBinding(r, sourceAddress, digest, byProofAlone), binding -> {
            if (binding != null) {
                then.accept(binding);
            } else {
                refuseProof(resp, sourceAddress, "the proof is not this instance's current one, or its gemini account is not a Google one");
            }
        });
    }

    /** The instance {@code digest} proves at {@code sourceAddress} and its Google account, or null. Non-blocking. */
    private Binding provenBinding(InstanceRegistry registry, String sourceAddress, String digest, boolean byProofAlone) {
        var caller = registry.prove(sourceAddress, Map.of(GoogleAccounts.NAMESPACE, List.of(digest)), byProofAlone);
        var account = caller == null ? null : googleAccountOf(caller.accounts());
        return account == null ? null : new Binding(account, digest, caller.accounts().instanceName());
    }

    /** {@code instance}'s effective {@code gemini} account when it is a Google one, else null. */
    private String googleAccountOf(InstanceRegistry.InstanceAccounts instance) {
        if (instance == null) return null;
        var tree = configTree;
        String account;
        try {
            account = AccountResolver.effectiveAccount(tree, GoogleAccounts.NAMESPACE, shape,
                    instance.accountsByNamespace().get(GoogleAccounts.NAMESPACE));
        } catch (AccountResolver.UnknownAccountException e) {
            return null;
        }
        return GoogleAccounts.isGoogleAccount(tree, account) ? account : null;
    }

    // --- Backend and userinfo ---

    /** Relays a proven request, streaming its body, with the session's token in place of the placeholder. */
    private void forward(HttpServerRequest req, String domain, Binding binding, String sourceAddress, long started) {
        currentSession(binding.account()).otherwise((SessionStore.Session<GoogleSessionStore.Tokens>) null).onSuccess(session -> {
            if (session == null) {
                toldToSignIn(binding);
                refuseProof(req.response(), sourceAddress, NOT_SIGNED_IN);
                return;
            }
            var backend = GoogleAccounts.BACKENDS.contains(domain);
            proxy.upstream.relayRequest(req, domain, new Upstream.RelayHooks(upReq -> {
                proxy.upstream.copyRequestHeaders(req, upReq, domain);
                upReq.putHeader("Authorization", "Bearer " + session.tokens().accessToken());
                return Future.succeededFuture(true);
            }, upResp -> { if (backend && upResp.statusCode() == 401) markRefused(session); }, null, null), started);
        });
    }

    // --- Token requests ---

    /** A token request whose body is read: agy's code exchange, a claimed refresh or revocation, or somebody else's, relayed. */
    private void handleTokenRequest(HttpServerRequest req, Buffer body, String sourceAddress, boolean byProofAlone) {
        var form = parseForm(body);
        if (req.path().equals("/revoke")) {
            var token = form.getOrDefault("token", queryValue(req, "token"));
            if (!GooglePlaceholders.claims(token)) {
                proxy.upstream.relayBuffered(req, TOKEN, body);
                return;
            }
            // A guest's sign-out must never end the host's session: answered here, whatever it names
            ProxyLog.info("Answering a Google token revocation from " + describe(sourceAddress)
                    + " locally; the host session is untouched");
            req.response().putHeader("Content-Type", "application/json").end("{}");
            return;
        }
        var grant = form.getOrDefault("grant_type", "");
        if (grant.equals("authorization_code")) {
            signIn(req, body, form, sourceAddress, byProofAlone);
            return;
        }
        if (!GooglePlaceholders.claims(form.get("refresh_token"))) {
            proxy.upstream.relayBuffered(req, TOKEN, body);
            return;
        }
        var resp = req.response();
        if (!grant.equals("refresh_token") || !form.keySet().equals(REFRESH_KEYS)
                || !AGY_CLIENT_ID.equals(form.get("client_id"))) {
            warnOnce("Refusing a Google token request from " + describe(sourceAddress)
                    + ": not the refresh request isx recognises (#2443)");
            refuse(resp, 400, "invalid_request", "unrecognised token request");
            return;
        }
        var problem = ChatGptRoute.shapeProblem(req.headers(), TOKEN, req.uri(), ChatGptRoute.sni(req), SAFE_TARGET);
        if (problem != null) {
            refuse(resp, 400, "invalid_request", problem);
            return;
        }
        var digest = GooglePlaceholders.digestOf(form.get("refresh_token"));
        proven(req, sourceAddress, byProofAlone, digest, binding -> refresh(req, form, binding));
    }

    /**
     * agy's exchange of the code the user pasted: relayed as sent for an instance on a Google
     * account, whose session Google's answer becomes; relayed untouched for any other caller, whose
     * own sign-in this is.
     */
    private void signIn(HttpServerRequest req, Buffer body, Map<String, String> form, String sourceAddress,
                        boolean byProofAlone) {
        if (!AGY_CLIENT_ID.equals(form.get("client_id"))) {
            proxy.upstream.relayBuffered(req, TOKEN, body);
            return;
        }
        var registry = proxy.instanceRegistry();
        if (registry == null) {
            // As proven() does: with nobody to tell a Google-account instance from another, a
            // relayed exchange could hand one real tokens
            refuse(req.response(), 403, "forbidden", "no instance is known to the proxy yet; try again shortly");
            return;
        }
        CallerCheck.identify(vertx, req, registry, r -> signInAccount(r, sourceAddress, byProofAlone), signIn -> {
            if (signIn == null || signIn.account() == null) {
                proxy.upstream.relayBuffered(req, TOKEN, body);
                return;
            }
            var account = signIn.account();
            if (account.isEmpty()) {
                warnOnce("Refusing a Google sign-in from " + describe(sourceAddress) + ": " + signIn.why() + " (#2443)");
                refuse(req.response(), 403, "forbidden", signIn.why());
                return;
            }
            // agy's exchange from an instance on a Google account is kept here or refused, never
            // relayed: relayed, Google's real tokens would land in the guest, which is what the
            // account exists to prevent. A shape this release of isx does not know is refused.
            if (!form.keySet().equals(EXCHANGE_KEYS) || !AGY_REDIRECT_URI.equals(form.get("redirect_uri"))) {
                warnOnce("Refusing a Google sign-in from " + describe(sourceAddress)
                        + ": not the code exchange isx recognises, and the instance's gemini account is a Google one (#2443)");
                refuse(req.response(), 400, "invalid_request", "unrecognised code exchange");
                return;
            }
            var problem = ChatGptRoute.shapeProblem(req.headers(), TOKEN, req.uri(), ChatGptRoute.sni(req), SAFE_TARGET);
            if (problem != null) {
                refuse(req.response(), 400, "invalid_request", problem);
                return;
            }
            exchange(req, body, account, signIn.instance(), registry.lookup(sourceAddress) == null ? null : sourceAddress);
        });
    }

    /**
     * What a sign-in is to the proxy: whose session it becomes and which instance signs in, "" with
     * {@code why} when it is refused, or {@link #RELAY nobody's}. Null, which
     * {@link CallerCheck#identify} takes as a caller the registry may not have listed yet, is kept
     * for an address it does not know on Linux: a known instance on a key account is relayed at
     * once, not after a registry refresh.
     */
    private record SignIn(String account, String instance, String why) {
        static SignIn refused(String why) { return new SignIn("", null, why); }
    }

    private static final SignIn RELAY = new SignIn(null, null, null);

    /**
     * The account a sign-in from {@code sourceAddress} is for. At an address the registry names:
     * that instance's Google account, or {@link #RELAY} for an instance on a key account, whose
     * sign-in is its own. At an address it does not name, where a proof alone may identify (macOS):
     * the one instance that proved itself and was told to sign in within
     * {@link #SIGN_IN_WINDOW_MILLIS} (agy's first request on a seeded token file is a refresh with
     * the proof, refused with {@link #NOT_SIGNED_IN} when the account has no session). No such
     * instance, or several, is a refusal, never a relay: a relayed exchange would put Google's real
     * tokens into whichever instance sent it, and on macOS the proxy cannot tell which one that was.
     */
    private SignIn signInAccount(InstanceRegistry registry, String sourceAddress, boolean byProofAlone) {
        var instance = registry.lookup(sourceAddress);
        if (instance != null) {
            var account = googleAccountOf(instance);
            return account == null ? RELAY : new SignIn(account, instance.instanceName(), null);
        }
        if (!byProofAlone) return null;
        var now = System.currentTimeMillis();
        signingIn.values().removeIf(s -> s.expiresAt() < now);
        var running = registry.incarnations().running();
        var asked = signingIn.entrySet().stream().filter(e -> running.contains(e.getKey())).toList();
        if (asked.isEmpty()) {
            return SignIn.refused("no instance on a Google account asked to sign in; open a new login shell in the"
                    + " instance and start agy again, so its request shows the proxy which account signs in");
        }
        if (asked.size() > 1) {
            return SignIn.refused("several instances asked to sign in at once (" + asked.stream().map(Map.Entry::getKey)
                    .sorted().collect(java.util.stream.Collectors.joining(", ")) + "); sign in from one at a time");
        }
        var only = asked.getFirst();
        return new SignIn(only.getValue().account(), only.getKey(), null);
    }

    /** {@code binding}'s instance proved itself and its account has no session: a sign-in from it may follow. */
    private void toldToSignIn(Binding binding) {
        signingIn.put(binding.instance(), new SigningIn(binding.account(), System.currentTimeMillis() + SIGN_IN_WINDOW_MILLIS));
    }

    /** Sends agy's exchange to Google as it came, keeps the answer as {@code account}'s session, and answers with placeholders. */
    private void exchange(HttpServerRequest req, Buffer body, String account, String instance, String address) {
        var resp = req.response();
        send(req, body).compose(answer -> vertx.executeBlocking(() -> {
            if (answer.status() != 200) return answer;
            var tokens = Jjq.parseForeign(answer.body().getBytes(), "Google's token answer");
            var issued = sessionTokens(tokens, null);
            if (issued == null) {
                ProxyLog.warn("Google answered a sign-in for gemini account '" + account + "' without tokens");
                return new Answer(502, Buffer.buffer(errorJson("server_error", "Google's answer held no tokens" + HINT)), List.of());
            }
            if (issued.refreshToken().isEmpty()) {
                ProxyLog.warn("Google's sign-in for gemini account '" + account + "' came with no refresh token:"
                        + " the session ends when its access token expires");
            }
            var previous = store.read(account);
            store.create(account, issued);
            if (previous != null) forgetSession(previous);
            ProxyLog.info("Kept a Google sign-in" + (issued.email().isEmpty() ? "" : " as " + issued.email())
                    + " as gemini account '" + account + "'" + (previous == null ? "" : ", replacing its earlier session"));
            proxy.clearAuthErrorIf(standing -> standing.startsWith(authErrorPrefix(account)));
            signingIn.remove(instance);
            var digest = mint(account, instance, address);
            return new Answer(200, Buffer.buffer(GooglePlaceholders.tokenResponse(issued, digest,
                    tokens.pathText("scope", ""))), List.of());
        }, false)).onComplete(ar -> {
            if (ar.failed()) {
                ProxyLog.warn("Google sign-in failed: " + ar.cause().getMessage());
                refuse(resp, 502, "server_error", "the sign-in could not be completed");
                return;
            }
            var answer = ar.result();
            answer.retryHeaders().forEach(h -> resp.headers().add(h.getKey(), h.getValue()));
            resp.setStatusCode(answer.status()).putHeader("Content-Type", "application/json").end(answer.body());
        });
    }

    /** A digest for the run that signed in, honoured until a login replaces it or it expires. */
    private String mint(String account, String instance, String address) {
        var now = System.currentTimeMillis();
        pending.values().removeIf(p -> p.expiresAt() < now);
        while (pending.size() >= MAX_PENDING) {
            var oldest = pending.entrySet().stream().min(Map.Entry.comparingByValue(
                    java.util.Comparator.comparingLong(Pending::expiresAt))).map(Map.Entry::getKey).orElse(null);
            if (oldest == null) break;
            pending.remove(oldest);
        }
        var bytes = new byte[ProofToken.DIGEST_HEX_CHARS / 2];
        // Not a static field: build-time initialization would put it in the native image heap
        new SecureRandom().nextBytes(bytes);
        var digest = HexFormat.of().formatHex(bytes);
        pending.put(digest, new Pending(account, instance, address, now + PENDING_DIGEST_TTL_MILLIS));
        return digest;
    }

    /** Tests only: the digests minted at sign-in that are still honoured. */
    Set<String> mintedDigests() {
        return Set.copyOf(pending.keySet());
    }

    /** Tests only: forgets every minted digest and every instance told to sign in, as a proxy restart does. */
    void forgetMinted() {
        pending.clear();
        signingIn.clear();
    }

    /**
     * The session tokens Google's token answer {@code tokens} names, on top of {@code current}
     * for a refresh (which keeps its refresh token and email where the answer names none); null
     * when the answer holds no access token.
     */
    static GoogleSessionStore.Tokens sessionTokens(JqValue tokens, GoogleSessionStore.Tokens current) {
        var access = tokens.pathText("access_token", "");
        if (access.isEmpty()) return null;
        var expiresIn = tokens.path("expires_in").asLong(0);
        var expiry = System.currentTimeMillis() / 1000 + Math.max(0, expiresIn);
        var email = GooglePlaceholders.emailOf(tokens.pathText("id_token", ""));
        var refresh = tokens.pathText("refresh_token", "");
        return current == null ? new GoogleSessionStore.Tokens(access, refresh, expiry, email)
                : current.refreshed(access, refresh, expiry, email);
    }

    /** Google's answer to a token request the proxy sent: its status, body and the headers that say when to try again. */
    private record Answer(int status, Buffer body, List<Map.Entry<String, String>> retryHeaders) {}

    /** Sends {@code body} to Google's token endpoint as the guest's request, less its credential headers. */
    private Future<Answer> send(HttpServerRequest req, Buffer body) {
        var options = new RequestOptions().setMethod(HttpMethod.POST).setHost(TOKEN).setPort(443).setURI("/token");
        return proxy.upstream.requestWithAsyncDns(options).compose(upReq -> {
            proxy.upstream.copyBufferedRequestHeaders(req, upReq, TOKEN);
            upReq.headers().remove("Authorization");
            upReq.putHeader("Accept-Encoding", "identity");
            upReq.putHeader("Content-Type", "application/x-www-form-urlencoded");
            upReq.putHeader("Content-Length", String.valueOf(body.length()));
            return upReq.send(body).compose(upResp -> {
                proxy.upstream.throttles.answered(TOKEN, upResp.statusCode());
                return upResp.body().map(b -> new Answer(upResp.statusCode(), b,
                        upResp.statusCode() == 429 ? Upstream.retryHeaders(upResp.headers()) : List.of()));
            });
        });
    }

    // --- Refresh ---

    /** A refresh's outcome: the session's tokens after it, or why the caller gets none. */
    record Refreshed(GoogleSessionStore.Tokens tokens, int status, String code, String message,
                     List<Map.Entry<String, String>> retryHeaders) {
        static Refreshed ok(SessionStore.Session<GoogleSessionStore.Tokens> session) {
            return new Refreshed(session.tokens(), 200, null, null, List.of());
        }

        static Refreshed refused(int status, String code, String message) {
            return new Refreshed(null, status, code, message, List.of());
        }
    }

    private static final String NOT_SIGNED_IN =
            "this gemini account has no Google session yet; run agy in the instance and sign in";
    private static final String NOT_NOW = "Google could not refresh the session just now; try again shortly";

    /** A recognised refresh from a caller that proved it may make it. */
    private void refresh(HttpServerRequest req, Map<String, String> form, Binding binding) {
        var resp = req.response();
        currentSession(binding.account()).compose(session -> {
            if (session == null) {
                toldToSignIn(binding);
                return Future.succeededFuture(Refreshed.refused(401, "invalid_grant", NOT_SIGNED_IN));
            }
            var key = sessionKey(session);
            var secondsLeft = session.tokens().expiry() - System.currentTimeMillis() / 1000;
            var tokenRefused = isRefused(session);
            if (secondsLeft > REFRESH_WINDOW_SECONDS && !tokenRefused) return Future.succeededFuture(Refreshed.ok(session));
            var usable = secondsLeft > 0 && !tokenRefused;
            var inFlight = refreshes.get(tokenKey(session));
            if (inFlight != null) {
                if (usable) return Future.succeededFuture(Refreshed.ok(session));
                joined.incrementAndGet();
                return inFlight;
            }
            var last = lastUpstream.get(key);
            var waitMillis = last == null ? 0 : MIN_UPSTREAM_INTERVAL_MILLIS - (System.currentTimeMillis() - last);
            if (waitMillis > 0) {
                return usable ? Future.succeededFuture(Refreshed.ok(session))
                        : vertx.executeBlocking(() -> newerOrLater(session, waitMillis), false);
            }
            return refreshes.computeIfAbsent(tokenKey(session), k -> {
                var started = upstreamRefresh(req, session, form);
                started.onComplete(ar -> refreshes.remove(k));
                return started;
            });
        }).onComplete(ar -> {
            if (ar.failed()) {
                ProxyLog.warn("Google refresh failed: " + ar.cause().getMessage());
                refuse(resp, 502, "server_error", "the refresh could not be completed");
                return;
            }
            var outcome = ar.result();
            if (outcome.status() != 200) {
                refuse(resp, outcome.status(), outcome.code() == null ? "invalid_grant" : outcome.code(),
                        outcome.message() == null ? NOT_SIGNED_IN : outcome.message(), outcome.retryHeaders());
                return;
            }
            resp.putHeader("Content-Type", "application/json")
                    .end(GooglePlaceholders.tokenResponse(outcome.tokens(), binding.digest(), ""));
        });
    }

    /** One refresh of {@code session} upstream, with the guest's form as its template and the real refresh token in it. */
    private Future<Refreshed> upstreamRefresh(HttpServerRequest req, SessionStore.Session<GoogleSessionStore.Tokens> session,
                                              Map<String, String> guestForm) {
        var key = sessionKey(session);
        return vertx.executeBlocking(() -> store.beginRefresh(session), false).compose(begun -> {
            if (!begun) return vertx.executeBlocking(() -> current(session), false);
            lastUpstream.put(key, System.currentTimeMillis());
            var substituted = new LinkedHashMap<>(guestForm);
            substituted.put("refresh_token", session.tokens().refreshToken());
            var body = Buffer.buffer(encodeForm(substituted));
            return send(req, body)
                    .compose(answer -> vertx.executeBlocking(() -> settle(session, answer), false),
                            e -> {
                                ProxyLog.warn("Google refresh for gemini account '" + session.account()
                                        + "' got no answer: " + e.getMessage());
                                return Future.failedFuture(e);
                            });
        });
    }

    /** Commits or discards what Google answered a refresh of {@code session} with. Blocks. */
    private Refreshed settle(SessionStore.Session<GoogleSessionStore.Tokens> session, Answer answer) {
        var status = answer.status();
        if (status != 200) {
            store.abandonRefresh(session);
            var code = errorCode(answer.body());
            ProxyLog.warn("Google refresh for gemini account '" + session.account() + "' refused upstream: HTTP "
                    + status + (code == null ? "" : " " + code));
            if (status != 400 && status != 401) {
                return status == 429
                        ? new Refreshed(null, 429, "server_error", NOT_NOW, answer.retryHeaders())
                        : Refreshed.refused(502, "server_error", NOT_NOW);
            }
            if (isDefaultAccount(session.account())) {
                proxy.setAuthError(authErrorPrefix(session.account()) + "can no longer be refreshed"
                        + (code == null ? "" : " (" + code + ")") + ". Run agy in an instance and sign in again.",
                        "agy");
            }
            return Refreshed.refused(401, code == null ? "invalid_grant" : code,
                    "the Google session can no longer be refreshed; run agy in the instance and sign in again");
        }
        JqValue tokens;
        try {
            tokens = Jjq.parseForeign(answer.body().getBytes(), "Google's refresh answer");
        } catch (RuntimeException e) {
            tokens = JqValues.parse("{}");
        }
        var refreshed = sessionTokens(tokens, session.tokens());
        if (refreshed == null) {
            store.abandonRefresh(session);
            ProxyLog.warn("Google refresh for gemini account '" + session.account() + "' answered without tokens");
            return Refreshed.refused(502, "server_error", NOT_NOW);
        }
        var commit = store.commit(session, refreshed);
        if (commit != SessionStore.Commit.COMMITTED) {
            ProxyLog.warn("Discarding a Google refresh for gemini account '" + session.account() + "': the session "
                    + (commit == SessionStore.Commit.GONE ? "was removed" : "changed") + " while it ran");
            return current(session);
        }
        refused.remove(key(session));
        proxy.clearAuthErrorIf(standing -> standing.startsWith(authErrorPrefix(session.account())));
        return new Refreshed(refreshed, 200, null, null, List.of());
    }

    /** Package-private for tests: what a throttled refresh whose token no longer works answers with. Blocks. */
    Refreshed newerOrLater(SessionStore.Session<GoogleSessionStore.Tokens> session, long waitMillis) {
        var now = store.read(session.account());
        if (now == null || !now.sessionId().equals(session.sessionId())) return forget(session);
        if (now.generation() > session.generation() && works(now)) return Refreshed.ok(now);
        var retryAfter = String.valueOf(Math.max(1, (waitMillis + 999) / 1000));
        return new Refreshed(null, 503, "temporarily_unavailable",
                "a refresh of the session went upstream moments ago and left no token that works yet; try again shortly",
                List.of(Map.entry("Retry-After", retryAfter)));
    }

    /** The account's current tokens if it is still the same session, else a refusal. Blocks. */
    private Refreshed current(SessionStore.Session<GoogleSessionStore.Tokens> session) {
        var now = store.read(session.account());
        if (now != null && now.sessionId().equals(session.sessionId())) return Refreshed.ok(now);
        return forget(session);
    }

    private Refreshed forget(SessionStore.Session<GoogleSessionStore.Tokens> session) {
        forgetSession(session);
        return Refreshed.refused(401, "invalid_grant", NOT_SIGNED_IN);
    }

    /** Drops what is kept about {@code session}: it is no longer the account's, so nothing will ask after it. */
    private void forgetSession(SessionStore.Session<GoogleSessionStore.Tokens> session) {
        refused.remove(key(session));
        lastUpstream.remove(key(session));
    }

    private boolean works(SessionStore.Session<GoogleSessionStore.Tokens> session) {
        return session.tokens().expiry() > System.currentTimeMillis() / 1000 && !isRefused(session);
    }

    private boolean isRefused(SessionStore.Session<GoogleSessionStore.Tokens> session) {
        return refused.getOrDefault(key(session), Set.of()).contains(session.generation());
    }

    private void markRefused(SessionStore.Session<GoogleSessionStore.Tokens> session) {
        refused.compute(key(session), (k, generations) -> {
            var marked = new java.util.HashSet<>(generations == null ? Set.of() : generations);
            marked.add(session.generation());
            return Set.copyOf(marked);
        });
    }

    /** Tests only: whether any token of {@code account}'s session is recorded as refused. */
    boolean refuses(String account, String sessionId) {
        return refused.containsKey(account + ":" + sessionId);
    }

    // --- Shared ---

    /** {@code account}'s session, read now, or null when it has none or cannot be read. */
    private Future<SessionStore.Session<GoogleSessionStore.Tokens>> currentSession(String account) {
        return vertx.executeBlocking(() -> {
            try {
                return store.read(account);
            } catch (RuntimeException e) {
                ProxyLog.warn("Cannot read the Google session of gemini account '" + account + "': " + e.getMessage());
                return null;
            }
        }, false);
    }

    private static String sessionKey(SessionStore.Session<GoogleSessionStore.Tokens> session) {
        return key(session);
    }

    private static String key(SessionStore.Session<GoogleSessionStore.Tokens> session) {
        return session.account() + ":" + session.sessionId();
    }

    private static String tokenKey(SessionStore.Session<GoogleSessionStore.Tokens> session) {
        return key(session) + "@" + session.generation();
    }

    private boolean isDefaultAccount(String account) {
        try {
            return account.equals(AccountResolver.effectiveAccount(configTree, GoogleAccounts.NAMESPACE, shape, null));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String authErrorPrefix(String account) {
        return "The Google sign-in of gemini account '" + account + "' ";
    }

    /** The {@code application/x-www-form-urlencoded} pairs of {@code body}; a repeated key keeps its last value. */
    static Map<String, String> parseForm(Buffer body) {
        var form = new LinkedHashMap<String, String>();
        if (body == null || body.length() == 0) return form;
        for (var pair : body.toString(StandardCharsets.UTF_8).split("&")) {
            if (pair.isEmpty()) continue;
            var eq = pair.indexOf('=');
            try {
                var key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                var value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                form.put(key, value);
            } catch (IllegalArgumentException malformed) {
                return new LinkedHashMap<>();
            }
        }
        return form;
    }

    static String encodeForm(Map<String, String> form) {
        var out = new StringBuilder();
        form.forEach((key, value) -> {
            if (!out.isEmpty()) out.append('&');
            out.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    private static String queryValue(HttpServerRequest req, String name) {
        var value = req.getParam(name);
        return value == null ? "" : value;
    }

    /** The {@code error} code of a Google token error, or null. */
    private static String errorCode(Buffer body) {
        try {
            var code = Jjq.parseForeign(body.getBytes(), "Google's error").pathText("error", "");
            return ERROR_CODE.matcher(code).matches() ? code : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void warnOnce(String message) {
        if (loggedRefusals.size() > 1024) loggedRefusals.clear();
        if (loggedRefusals.add(message)) ProxyLog.warn(message);
    }

    private void refuseProof(HttpServerResponse resp, String sourceAddress, String why) {
        warnOnce("Refusing a Google sign-in request from " + describe(sourceAddress) + ": " + why);
        refuse(resp, 401, "invalid_grant", why);
    }

    private static void refuse(HttpServerResponse resp, int status, String code, String message) {
        refuse(resp, status, code, message, List.of());
    }

    /** An answer in the shape Google's token errors take ({@code error}, {@code error_description}). */
    private static void refuse(HttpServerResponse resp, int status, String code, String message,
                               List<Map.Entry<String, String>> retryHeaders) {
        if (resp.ended() || resp.closed()) return;
        retryHeaders.forEach(header -> resp.headers().add(header.getKey(), header.getValue()));
        resp.setStatusCode(status).putHeader("Content-Type", "application/json").end(errorJson(code, message + HINT));
    }

    private static String errorJson(String code, String description) {
        return JqObject.builder(2).put("error", JqString.of(code))
                .put("error_description", JqString.of(description)).build().toJsonString();
    }

    private String describe(String sourceAddress) {
        return proxy.describeCaller(sourceAddress);
    }
}
