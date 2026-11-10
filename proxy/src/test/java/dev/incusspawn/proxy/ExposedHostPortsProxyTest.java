package dev.incusspawn.proxy;

import dev.incusspawn.config.ExposedHostPorts;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The proxy serves {@code exposed-host-ports:} (#2117) from config.yaml alone: a listed port of the
 * loopback answers on the address the proxy listens on once it starts, an unlisted one never does,
 * and a reload follows the list.
 * <p>
 * The proxy listens on 127.0.0.2, standing in for the bridge gateway, so the service it reaches on
 * 127.0.0.1 can hold the same port. Linux routes all of 127.0.0.0/8 to loopback; macOS does not.
 */
@EnabledOnOs(OS.LINUX)
@Timeout(60)
class ExposedHostPortsProxyTest {

    private static final String GATEWAY = "127.0.0.2";

    @TempDir
    Path home;
    private String origHome;
    private Vertx vertx;
    private MitmProxy proxy;
    private final List<ServerSocket> services = new java.util.ArrayList<>();

    @AfterEach
    void stop() throws Exception {
        try {
            if (proxy != null) proxy.stop();
            if (vertx != null) vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            for (var service : services) service.close();
        } finally {
            if (origHome != null) System.setProperty("user.home", origHome);
        }
    }

    /** A loopback-only service that greets every connection with {@code greeting}. */
    private int service(String greeting) throws IOException {
        var server = new ServerSocket(0, 50, InetAddress.ofLiteral("127.0.0.1"));
        services.add(server);
        Thread.ofVirtual().start(() -> {
            while (true) {
                try (var socket = server.accept()) {
                    socket.getOutputStream().write(greeting.getBytes(StandardCharsets.UTF_8));
                } catch (IOException closed) {
                    return;
                }
            }
        });
        return server.getLocalPort();
    }

    private Path startProxy(String configYaml) throws Exception {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        var configDir = Files.createDirectories(home.resolve(".config/incus-spawn"));
        CertificateAuthority.loadOrCreate();
        var config = configDir.resolve("config.yaml");
        Files.writeString(config, configYaml);
        vertx = Vertx.vertx();
        proxy = new MitmProxy(vertx, GATEWAY, 0, 0, GATEWAY, ConfigFingerprint.load());
        ContainerTls.startInBackground(proxy);
        return config;
    }

    /** What an instance reads from {@code port} at the proxy's address, or null when nothing answers there. */
    private static String readAtGateway(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(GATEWAY, port), 2_000);
            socket.setSoTimeout(5_000);
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException refused) {
            return null;
        }
    }

    @Test
    void aListedPortAnswersAtTheProxysAddressAndAnUnlistedOneDoesNot() throws Exception {
        var listed = service("ollama");
        var unlisted = service("private");
        startProxy("exposed-host-ports: [" + listed + "]\n");

        assertEquals("ollama", readAtGateway(listed));
        assertNull(readAtGateway(unlisted), "a loopback port nobody listed stays out of reach");
        assertEquals(List.of(listed), proxy.exposedHostPorts());
        assertFalse(proxy.allInterceptedDomains().contains(ExposedHostPorts.HOST_NAME),
                "the name is never intercepted: no certificate is minted for it and 443 does not serve it");
    }

    @Test
    void aReloadFollowsTheList() throws Exception {
        var first = service("first");
        var second = service("second");
        var config = startProxy("exposed-host-ports: [" + first + "]\n");
        assertEquals("first", readAtGateway(first));
        assertNull(readAtGateway(second));

        Files.writeString(config, "exposed-host-ports: [" + second + "]\n");
        proxy.reload();
        assertNull(readAtGateway(first), "an unlisted port is closed on reload");
        assertEquals("second", readAtGateway(second));

        Files.writeString(config, "{}\n");
        proxy.reload();
        assertNull(readAtGateway(second));
        assertEquals(List.of(), proxy.exposedHostPorts());
    }

    /**
     * Something else on the proxy's address holds a listed port: the proxy serves the others and
     * says which on {@code /health}, to the host alone, for {@code isx doctor}.
     */
    @Test
    void aPortHeldOnTheProxysAddressIsSkippedAndReportedToTheHost() throws Exception {
        var listed = service("served");
        var held = new ServerSocket(0, 50, InetAddress.ofLiteral(GATEWAY));
        services.add(held);
        startProxy("exposed-host-ports: [" + held.getLocalPort() + ", " + listed + "]\n");

        assertEquals("served", readAtGateway(listed));
        var info = ProxyHealthCheck.fetchProxyInfo(GATEWAY, proxy.healthPort(), 5_000);
        assertEquals(List.of(held.getLocalPort()), info.unboundHostPorts());
    }

    @Test
    void noKeyExposesNothing() throws Exception {
        var service = service("nothing");
        startProxy("{}\n");
        assertNull(readAtGateway(service));
        assertEquals(List.of(), proxy.exposedHostPorts());
    }
}
