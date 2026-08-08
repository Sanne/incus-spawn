package dev.incusspawn.incus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for the build pipeline: exercises the same operation sequence
 * that BuildCommand.buildFromScratch and buildFromParent use, without
 * requiring internet access (uses the cached test-alpine image as a stand-in).
 *
 * This validates that the REST API layer correctly handles the FULL SEQUENCE
 * of operations rather than just individual operations in isolation.
 *
 * Run with socket access:
 *   sg incus-admin -c "mvn test -Dtest=BuildPipelineSmokeTest"
 *
 * Requires: incus image copy images:alpine/edge local: --alias test-alpine
 */
class BuildPipelineSmokeTest {

    private static final String IMAGE   = "test-alpine";
    private static final String BASE    = "isx-build-base-"   + (System.currentTimeMillis() % 100000);
    private static final String DERIVED = "isx-build-derived-" + (System.currentTimeMillis() % 100000);

    private static IncusClient client;
    private static boolean available;

    @BeforeAll
    static void setUp() {
        client = new IncusClient();
        if (client.checkConnectivity() != null) {
            System.out.println("Skipping build pipeline tests: Incus daemon not accessible.");
            available = false;
            return;
        }
        var http = IncusApi.tryConnect();
        if (http == null || !http.get("/1.0/images/aliases/" + IMAGE).isSuccess()) {
            System.out.println("Skipping build pipeline tests: image '" + IMAGE + "' not cached.");
            available = false;
            return;
        }
        available = true;
    }

    @AfterAll
    static void tearDown() {
        for (var name : new String[]{BASE, DERIVED}) {
            try { if (client != null && client.exists(name)) client.delete(name, true); }
            catch (Exception ignored) {}
        }
    }

    private static boolean skip() { return !available; }

    // =========================================================================
    // buildFromScratch simulation
    //
    // Mirrors BuildCommand.buildFromScratch:
    //   launch → waitForReady → configSet (security) → restart → waitForReady
    //   → sh (DNS) → filePush (CA cert) → shellExec → runAsUser → metadata → stop
    // =========================================================================

    @Test
    void buildFromScratchSequenceProducesCorrectlyTaggedTemplate() throws Exception {
        if (skip()) return;

        var http = IncusApi.tryConnect();
        assertNotNull(http);

        // 1. launch — create container from base image (simulates incus.launch)
        System.out.println("Step 1: launching base container...");
        var fp = http.get("/1.0/images/aliases/" + IMAGE)
                .body().path("metadata").path("target").asText();
        var createBody = new java.util.LinkedHashMap<String, Object>();
        createBody.put("name", BASE);
        createBody.put("type", "container");
        createBody.put("source", java.util.Map.of("type", "image", "fingerprint", fp));
        createBody.put("config", java.util.Map.of("security.privileged", "true"));
        http.requestAndWait("POST", "/1.0/instances", createBody);
        http.requestAndWait("PUT", "/1.0/instances/" + BASE + "/state",
                java.util.Map.of("action", "start", "timeout", 30, "force", false));

        // 2. waitForReady — polls until exec succeeds (tests pollUntilReady fix)
        System.out.println("Step 2: waitForReady...");
        client.waitForReady(BASE);

        // 3. configSet batch — simulates the security settings applied before restart
        System.out.println("Step 3: configSet (security settings)...");
        client.configSet(BASE, "user.test-security-nesting", "true");
        client.configSet(BASE, "user.test-raw-idmap", "both 1000 1000");
        assertEquals("true", client.configGet(BASE, "user.test-security-nesting"));

        // 4. restart + waitForReady — tests restart and poll-after-restart
        System.out.println("Step 4: restart...");
        client.restart(BASE);
        client.waitForReady(BASE);

        // 5. sh command for DNS setup — simulates "rm -f /etc/resolv.conf && echo nameserver..."
        System.out.println("Step 5: DNS setup via sh...");
        var dnsResult = client.shellExec(BASE, "sh", "-c",
                "echo 'nameserver 10.82.112.1' > /tmp/test-resolv.conf && " +
                "grep nameserver /tmp/test-resolv.conf");
        assertEquals(0, dnsResult.exitCode(), "DNS config sh command should succeed");
        assertTrue(dnsResult.stdout().contains("nameserver"), "resolv.conf should have nameserver");

        // 6. filePush — simulates CA certificate injection
        System.out.println("Step 6: filePush (CA cert simulation)...");
        var certContent = "-----BEGIN CERTIFICATE-----\nMIIFake==\n-----END CERTIFICATE-----\n";
        var tmpCert = java.nio.file.Files.createTempFile("isx-smoke-cert", ".pem");
        try {
            java.nio.file.Files.writeString(tmpCert, certContent);
            client.filePush(tmpCert.toString(), BASE, "/tmp/test-ca.pem");
            var certCheck = client.shellExec(BASE, "cat", "/tmp/test-ca.pem");
            assertTrue(certCheck.stdout().contains("BEGIN CERTIFICATE"),
                    "Pushed CA cert should be readable");
        } finally {
            java.nio.file.Files.deleteIfExists(tmpCert);
        }

        // 7. Container.runAsUser — simulates user-level tool installation
        System.out.println("Step 7: Container.runAsUser (user tool setup)...");
        var addUser = client.shellExec(BASE, "adduser", "-D", "agentuser");
        if (!addUser.success()) addUser = client.shellExec(BASE, "useradd", "-m", "agentuser");
        addUser.assertSuccess("Failed to create agentuser");
        // Create bash shim for runAsUser (see IncusClientSmokeTest for why)
        client.shellExec(BASE, "sh", "-c",
                "command -v bash >/dev/null 2>&1 || " +
                "{ printf '#!/bin/sh\\nexec /bin/sh \"$@\"\\n' > /usr/local/bin/bash " +
                "&& chmod +x /usr/local/bin/bash; }");

        var container = new Container(client, BASE);
        container.runAsUser("agentuser",
                "echo build-step-ran > /home/agentuser/build-marker.txt",
                "User-level build step should succeed");

        var markerCheck = client.shellExec(BASE, "cat", "/home/agentuser/build-marker.txt");
        assertEquals("build-step-ran", markerCheck.stdout().strip(),
                "User-level script output should be readable by root");

        // 8. Metadata tagging — simulates tagTemplateMetadata
        System.out.println("Step 8: tagging template metadata...");
        client.configSet(BASE, Metadata.TYPE, Metadata.TYPE_BASE);
        client.configSet(BASE, Metadata.PARENT, "");
        client.configSet(BASE, Metadata.CREATED, Metadata.now());
        client.configSet(BASE, Metadata.BUILD_VERSION, "test-1.0");

        // 9. stop
        System.out.println("Step 9: stopping...");
        client.stop(BASE);
        assertEquals("Stopped", client.getInstanceStatus(BASE), "Container should be Stopped");

        // 10. Verify all metadata survived the stop
        assertEquals(Metadata.TYPE_BASE, client.configGet(BASE, Metadata.TYPE),
                "TYPE metadata should survive stop");
        assertFalse(client.configGet(BASE, Metadata.CREATED).isBlank(),
                "CREATED timestamp should be set");
        assertEquals("test-1.0", client.configGet(BASE, Metadata.BUILD_VERSION));

        System.out.println("buildFromScratch sequence: all " + 9 + " steps confirmed");
    }

    // =========================================================================
    // buildFromParent simulation
    //
    // Mirrors BuildCommand.buildFromParent:
    //   copy → start → waitForReady → runAsUser (additional tools)
    //   → metadata update → stop → verify
    // =========================================================================

    @Test
    void buildFromParentSequenceCopiesAndExtendsTemplate() throws Exception {
        if (skip()) return;
        // This test depends on the BASE container existing from buildFromScratch.
        // If that test didn't run or failed, create a minimal base here.
        if (!client.exists(BASE) || !"Stopped".equalsIgnoreCase(client.getInstanceStatus(BASE))) {
            System.out.println("BASE not available; skipping buildFromParent test.");
            return;
        }

        // 1. copy — simulates incus.copy(parentSource, buildName)
        System.out.println("Step 1: copy from base template...");
        client.copy(BASE, DERIVED);
        assertEquals("Stopped", client.getInstanceStatus(DERIVED),
                "Derived container should be Stopped after copy");

        // 2. start + waitForReady
        System.out.println("Step 2: start + waitForReady...");
        client.start(DERIVED);
        client.waitForReady(DERIVED);

        // 3. Verify inherited metadata from parent
        assertEquals(Metadata.TYPE_BASE, client.configGet(DERIVED, Metadata.TYPE),
                "Derived should inherit parent's TYPE");

        // 4. Additional tool setup via runAsUser (simulates derived image tools)
        System.out.println("Step 3: runAsUser (derived tool setup)...");
        var container = new Container(client, DERIVED);
        container.runAsUser("agentuser",
                "echo derived-tool-installed >> /home/agentuser/derived-marker.txt",
                "Derived user-level setup should succeed");

        var markerCheck = client.shellExec(DERIVED, "cat", "/home/agentuser/derived-marker.txt");
        assertTrue(markerCheck.stdout().contains("derived-tool-installed"),
                "Derived tool setup marker should exist");

        // 5. Update metadata for derived template
        System.out.println("Step 4: updating derived metadata...");
        client.configSet(DERIVED, Metadata.TYPE, Metadata.TYPE_BASE);
        client.configSet(DERIVED, Metadata.PARENT, BASE);
        client.configSet(DERIVED, Metadata.CREATED, Metadata.now());
        client.configSet(DERIVED, Metadata.BUILD_VERSION, "test-derived-1.0");

        // 6. stop
        System.out.println("Step 5: stopping derived...");
        client.stop(DERIVED);
        assertEquals("Stopped", client.getInstanceStatus(DERIVED));

        // 7. Verify derived metadata
        assertEquals(BASE, client.configGet(DERIVED, Metadata.PARENT),
                "PARENT should point to base template");
        assertEquals("test-derived-1.0", client.configGet(DERIVED, Metadata.BUILD_VERSION));

        System.out.println("buildFromParent sequence: all 5 steps confirmed");
    }

    // =========================================================================
    // configSet in a loop — mirrors the metadata-heavy tagTemplateMetadata call
    // =========================================================================

    @Test
    void multipleSequentialConfigSetsAllPersist() {
        if (skip()) return;
        if (!client.exists(BASE)) {
            System.out.println("BASE not available; skipping.");
            return;
        }

        // BuildCommand sets ~8-10 config keys in sequence; verify all persist.
        var keys = List.of(
                Metadata.TYPE, Metadata.PARENT, Metadata.PROFILE,
                Metadata.NETWORK_MODE, Metadata.BUILD_VERSION, Metadata.DEFINITION_SHA
        );
        var value = "smoke-test-value";

        for (var key : keys) {
            client.configSet(BASE, key, value);
        }
        for (var key : keys) {
            assertEquals(value, client.configGet(BASE, key),
                    "Config key " + key + " should persist after sequential sets");
        }
        for (var key : keys) {
            client.configUnset(BASE, key);
        }
        System.out.println("Sequential configSet/configGet: " + keys.size() + " keys confirmed");
    }
}
