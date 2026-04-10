package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that build actual templates using a real Incus daemon.
 * Run with: mvn verify -DskipITs=false
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TemplateBuildIT {

    private static final String TEST_MINIMAL = "test-tpl-minimal";

    @Inject
    IncusClient incus;

    @Inject
    picocli.CommandLine.IFactory factory;

    @AfterAll
    static void cleanup() {
        // Best-effort cleanup of test containers
        var client = new IncusClient();
        for (var name : new String[]{TEST_MINIMAL}) {
            if (client.exists(name)) {
                client.delete(name, true);
            }
        }
    }

    @Test
    @Order(1)
    void buildMinimalImage() {
        // Clean up if left over from a previous run
        if (incus.exists(TEST_MINIMAL)) {
            incus.delete(TEST_MINIMAL, true);
        }

        var exitCode = new picocli.CommandLine(BuildCommand.class, factory)
                .execute(TEST_MINIMAL);
        assertEquals(0, exitCode, "Build command should succeed");
        assertTrue(incus.exists(TEST_MINIMAL), "Image should exist after build");
    }

    @Test
    @Order(2)
    void minimalImageHasMetadata() {
        Assumptions.assumeTrue(incus.exists(TEST_MINIMAL),
                "Skipping: " + TEST_MINIMAL + " was not built");

        var type = incus.configGet(TEST_MINIMAL, Metadata.TYPE);
        assertEquals(Metadata.TYPE_BASE, type, "Should be tagged as base image");

        var created = incus.configGet(TEST_MINIMAL, Metadata.CREATED);
        assertNotNull(created);
        assertFalse(created.isBlank(), "Created date should be set");
    }

    @Test
    @Order(3)
    void minimalImageHasAgentuser() {
        Assumptions.assumeTrue(incus.exists(TEST_MINIMAL),
                "Skipping: " + TEST_MINIMAL + " was not built");

        startAndWait(TEST_MINIMAL);
        try {
            var result = incus.shellExec(TEST_MINIMAL, "id", "agentuser");
            assertTrue(result.success(), "agentuser should exist");
            assertTrue(result.stdout().contains("uid=1000"),
                    "agentuser should have UID 1000");
        } finally {
            incus.stop(TEST_MINIMAL);
        }
    }

    @Test
    @Order(4)
    void minimalImageHasMitmCaCert() {
        Assumptions.assumeTrue(incus.exists(TEST_MINIMAL),
                "Skipping: " + TEST_MINIMAL + " was not built");

        startAndWait(TEST_MINIMAL);
        try {
            // CA cert should be installed in Fedora's trust anchors
            var result = incus.shellExec(TEST_MINIMAL,
                    "test", "-f", "/etc/pki/ca-trust/source/anchors/incus-spawn-mitm.crt");
            assertTrue(result.success(), "MITM CA certificate should be installed");

            // Verify it's a PEM certificate
            var content = incus.shellExec(TEST_MINIMAL,
                    "head", "-1", "/etc/pki/ca-trust/source/anchors/incus-spawn-mitm.crt");
            assertTrue(content.stdout().contains("BEGIN CERTIFICATE"),
                    "CA cert should be a PEM certificate");
        } finally {
            incus.stop(TEST_MINIMAL);
        }
    }

    @Test
    @Order(5)
    void minimalImageHasDnsConfig() {
        Assumptions.assumeTrue(incus.exists(TEST_MINIMAL),
                "Skipping: " + TEST_MINIMAL + " was not built");

        startAndWait(TEST_MINIMAL);
        try {
            // DNS should point at the gateway, not systemd-resolved.
            // Domain interception is handled at the bridge level via dnsmasq
            // (configured by isx proxy), not via /etc/hosts.
            var resolv = incus.shellExec(TEST_MINIMAL, "cat", "/etc/resolv.conf");
            assertTrue(resolv.success());
            assertFalse(resolv.stdout().contains("127.0.0.53"),
                    "resolv.conf should not point at systemd-resolved");
            assertTrue(resolv.stdout().contains("nameserver"),
                    "resolv.conf should have a nameserver entry");
        } finally {
            incus.stop(TEST_MINIMAL);
        }
    }

    private void startAndWait(String container) {
        incus.start(container);
        for (int i = 0; i < 30; i++) {
            if (incus.shellExec(container, "true").success()) return;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }
}
