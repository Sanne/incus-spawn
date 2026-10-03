package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BuildAccounts;
import dev.incusspawn.proxy.ProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How {@link BuildCommand} wires in a build's template accounts (#903): the pins and the proxy
 * signal before the first start, the address check after it, and the address given back after
 * the stop. {@code BuildAccountsTest} pins what each step does; this pins that the build's
 * helpers for them ask for it, with the template's resolved accounts (#932). That
 * {@code buildFromParent} and {@code buildFromScratch} call the helpers is not pinned: the steps
 * between them exec in the guest, which {@link FakeIncusDaemon} does not serve.
 */
@ExtendWith(IsolatedHome.class)
class BuildCommandAccountsWiringTest {

    private static final String BUILD = "tpl-child-rebuilding";

    private final FakeIncusDaemon daemon = new FakeIncusDaemon().container(BUILD, Map.of());
    private final List<String> seenByTheProxy = new ArrayList<>();
    private final BuildCommand cmd = new BuildCommand();

    @BeforeEach
    void watchTheProxySignal() {
        cmd.incus = daemon.client();
        // IsolatedHome swapped the real signal for a counter and puts the real one back after
        ProxyService.replaceAccountRefreshSignal(() -> {
            var instance = daemon.instance(BUILD);
            var config = instance.path("config");
            seenByTheProxy.add(instance.path("status").asText() + " "
                    + config.path(Metadata.STATIC_IP).asText() + " "
                    + config.path(Metadata.accountKey("github")).asText() + " "
                    + config.path(Metadata.accountKey("claude")).asText());
        });
    }

    /** Start the build of a child that pins claude itself and inherits its parent's github pin. */
    private BuildAccounts.Started startPinned() {
        var parent = new ImageDef();
        parent.setName("tpl-parent");
        parent.setAccounts(Map.of("github", "bot"));
        var child = new ImageDef();
        child.setName("tpl-child");
        child.setParent("tpl-parent");
        child.setAccounts(Map.of("claude", "work"));
        return cmd.startBuild(BUILD, child, Map.of("tpl-parent", parent, "tpl-child", child));
    }

    private static boolean isInstanceWrite(String request) {
        return request.equals("PUT /1.0/instances/" + BUILD) || request.equals("PATCH /1.0/instances/" + BUILD);
    }

    @Test
    void thePinnedBuildIsKnownToTheProxyBeforeItsFirstStart() {
        var started = startPinned();

        assertNotNull(started.address(), "a build whose template pins accounts gets a static address");
        assertEquals(List.of("Stopped " + started.address() + " bot work"), seenByTheProxy,
                "the proxy is signalled once, before the start, with the address and every resolved pin");
        assertEquals(List.of(BUILD + " start"), daemon.stateActions());
        var config = daemon.instance(BUILD).path("config");
        assertEquals("template:tpl-child", config.path(Metadata.accountOriginKey("github")).asText(),
                "the pins name the template being built, not the temporary build container");
    }

    @Test
    void theAddressIsCheckedAfterTheStart() {
        var started = startPinned();
        daemon.clearRequests();

        cmd.requireBuildAddress(BUILD, started);

        assertTrue(daemon.requests().contains("GET /1.0/instances/" + BUILD + "/state"),
                "the running guest's addresses are read to check it took " + started.address()
                        + ": " + daemon.requests());
    }

    @Test
    void theAddressIsGivenBackOnceTheBuildHasStopped() {
        var started = startPinned();
        daemon.clearRequests();

        cmd.stopBuild(BUILD, started);

        assertEquals(List.of(BUILD + " start", BUILD + " stop"), daemon.stateActions());
        var instance = daemon.instance(BUILD);
        assertEquals("Stopped", instance.path("status").asText());
        assertFalse(instance.path("config").has(Metadata.STATIC_IP),
                "the finished template does not keep a bridge address: " + instance.path("config"));
        assertEquals("bot", instance.path("config").path(Metadata.accountKey("github")).asText(),
                "the pins stay, for every branch of the template");
        var requests = daemon.requests();
        var stop = requests.indexOf("PUT /1.0/instances/" + BUILD + "/state");
        assertTrue(stop >= 0 && requests.subList(stop + 1, requests.size()).stream().anyMatch(BuildCommandAccountsWiringTest::isInstanceWrite),
                "the address is released after the stop: " + requests);
    }

    @Test
    void aBuildPinningNothingKeepsDhcpAndNeverSignalsTheProxy() {
        var root = new ImageDef();
        root.setName("tpl-child");
        var defs = Map.of("tpl-child", root);

        var started = cmd.startBuild(BUILD, root, defs);
        cmd.requireBuildAddress(BUILD, started);
        cmd.stopBuild(BUILD, started);

        assertNull(started.address());
        assertEquals(List.of(), seenByTheProxy);
        assertEquals(List.of(BUILD + " start", BUILD + " stop"), daemon.stateActions());
        assertFalse(daemon.requests().contains("GET /1.0/instances/" + BUILD + "/state"),
                "no address to check, so no wait for one: " + daemon.requests());
        assertFalse(daemon.requests().stream().anyMatch(BuildCommandAccountsWiringTest::isInstanceWrite),
                "served the defaults either way, it pays for no address writes: " + daemon.requests());
    }
}
