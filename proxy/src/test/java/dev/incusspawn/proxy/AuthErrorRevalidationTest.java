package dev.incusspawn.proxy;

import io.vertx.core.Vertx;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The auth error latch and the health endpoint's on-demand check of it.
 *
 * <p>Credential state is otherwise only learned from real container traffic, so a
 * status view can be wrong in both directions — reporting a failure the user has
 * already fixed, or reporting nothing because no request has been made yet. These
 * tests pin the gate deciding when {@code /health} verifies the token itself, and
 * in particular the cases where it must <em>not</em> fork gcloud.
 */
class AuthErrorRevalidationTest {

    static Vertx vertex;
    MitmProxy proxy;

    @BeforeAll
    static void startVertx() {
        vertex = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        // Await it: an unawaited close leaves event-loop and worker threads
        // running past the class, leaking into the rest of the test JVM.
        if (vertex != null) {
            vertex.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void newProxy() {
        proxy = vertexProxy();
    }

    private static MitmProxy vertexProxy() {
        var credentials = new ProxyCredentials("", "", true, "us-east5", "proj", List.of());
        return new MitmProxy(vertex, "127.0.0.1", 0, 0, "127.0.0.1", credentials);
    }

    /** Pretend a live token was fetched, so the cache looks warm. */
    private static void warmToken(MitmProxy p) {
        p.cachedVertexToken = "ya29.test";
        p.vertexTokenExpiryMs = System.currentTimeMillis() + 60_000;
    }

    @Test
    void checksWhenNothingIsKnownYet() {
        assertTrue(proxy.claimAuthRevalidation(),
                "A cold cache and no error means the status is a guess — verify it");
    }

    @Test
    void neverForksGcloudWithoutVertex() {
        var credentials = new ProxyCredentials("", "sk-oauth", false, "", "", List.of());
        var nonVertex = new MitmProxy(vertex, "127.0.0.1", 0, 0, "127.0.0.1", credentials);

        assertFalse(nonVertex.claimAuthRevalidation(),
                "A non-Vertex setup has no reason to invoke gcloud at all");

        nonVertex.setAuthError("gcloud failed somehow", MitmProxy.VERTEX_AUTH_HINT);
        assertFalse(nonVertex.claimAuthRevalidation(),
                "Not even a gcloud-shaped error justifies it when Vertex is off");
    }

    @Test
    void skipsCheckWhileCachedTokenIsStillValid() {
        warmToken(proxy);
        assertFalse(proxy.claimAuthRevalidation(),
                "The healthy steady state must stay free — the cached token already answers");
    }

    @Test
    void checksDespiteWarmTokenWhenErrorStands() {
        warmToken(proxy);
        proxy.setAuthError("gcloud failed", MitmProxy.VERTEX_AUTH_HINT);

        assertTrue(proxy.claimAuthRevalidation(),
                "A standing error must be re-tested even if a token is cached");
    }

    @Test
    void revalidatesGcloudSourcedError() {
        proxy.setAuthError("gcloud auth print-access-token failed (exit 1)", MitmProxy.VERTEX_AUTH_HINT);
        assertTrue(proxy.claimAuthRevalidation(), "A gcloud failure is re-checkable");
    }

    @Test
    void doesNotRevalidateOauthError() {
        proxy.setAuthError("Claude OAuth token rejected (HTTP 401).", "isx init");
        assertFalse(proxy.claimAuthRevalidation(),
                "Only the user can resolve an OAuth failure — re-running gcloud proves nothing");
    }

    @Test
    void secondClaimBlockedWhileFirstInFlight() {
        proxy.authRevalidateIntervalMs = 0;
        proxy.setAuthError("gcloud failed", MitmProxy.VERTEX_AUTH_HINT);

        assertTrue(proxy.claimAuthRevalidation());
        assertFalse(proxy.claimAuthRevalidation(),
                "Concurrent polls must not fork a second gcloud process");

        proxy.releaseAuthRevalidation();
        assertTrue(proxy.claimAuthRevalidation(), "Claimable again once the attempt completes");
    }

    @Test
    void throttledUntilIntervalElapses() {
        proxy.setAuthError("gcloud failed", MitmProxy.VERTEX_AUTH_HINT);

        assertTrue(proxy.claimAuthRevalidation());
        proxy.releaseAuthRevalidation();
        assertFalse(proxy.claimAuthRevalidation(),
                "The TUI polls /health continuously; re-checks are rate-limited");

        proxy.authRevalidateIntervalMs = 0;
        assertTrue(proxy.claimAuthRevalidation(), "Allowed once the interval has passed");
    }

    @Test
    void clearingErrorSettlesOnceTokenIsCached() {
        proxy.setAuthError("gcloud failed", MitmProxy.VERTEX_AUTH_HINT);
        proxy.clearAuthError();
        assertNull(proxy.authError);

        proxy.authRevalidateIntervalMs = 0;
        assertTrue(proxy.claimAuthRevalidation(),
                "Cleared but with a cold cache, the healthy claim is still unverified");

        proxy.releaseAuthRevalidation();
        warmToken(proxy);
        assertFalse(proxy.claimAuthRevalidation(),
                "Once a token is cached and no error stands, checks stop");
    }

    @Test
    void probeErrorReplacesMessageWithoutLosingReCheckability() {
        proxy.setAuthError("first failure", MitmProxy.VERTEX_AUTH_HINT);
        proxy.recordProbeAuthError("second failure");

        assertEquals("second failure", proxy.authError,
                "A still-failing re-check refreshes the reported reason");
        proxy.authRevalidateIntervalMs = 0;
        assertTrue(proxy.claimAuthRevalidation(),
                "The hint survives, so the error stays re-checkable");
    }

    @Test
    void probeErrorLatchesFailureFoundWithoutTraffic() {
        proxy.recordProbeAuthError("gcloud auth print-access-token failed (exit 1)");

        assertEquals("gcloud auth print-access-token failed (exit 1)", proxy.authError,
                "A check that finds broken credentials must report them, traffic or not");
        proxy.authRevalidateIntervalMs = 0;
        assertTrue(proxy.claimAuthRevalidation(),
                "A probe-discovered error is tagged as gcloud-sourced, so it self-clears later");
    }

    @Test
    void setAuthErrorSubstitutesMessageForExceptionWithoutOne() {
        proxy.setAuthError(null, MitmProxy.VERTEX_AUTH_HINT);

        assertNotNull(proxy.authError,
                "A null message would omit authError from /health and read as healthy");
        assertFalse(proxy.authError.isBlank());
    }

    @Test
    void probeErrorSubstitutesMessageForExceptionWithoutOne() {
        proxy.recordProbeAuthError(null);

        assertNotNull(proxy.authError,
                "An exception with no message must not read as healthy");
        assertFalse(proxy.authError.isBlank());
    }
}
