package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that build actual golden images using a real Incus daemon.
 * Run with: mvn verify -DskipITs=false
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoldenImageBuildIT {

    private static final String TEST_MINIMAL = "test-golden-minimal";

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

        // Start the image temporarily to check inside it
        incus.start(TEST_MINIMAL);
        try {
            // Wait for ready
            for (int i = 0; i < 30; i++) {
                if (incus.shellExec(TEST_MINIMAL, "true").success()) break;
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }

            var result = incus.shellExec(TEST_MINIMAL, "id", "agentuser");
            assertTrue(result.success(), "agentuser should exist");
            assertTrue(result.stdout().contains("uid=1000"),
                    "agentuser should have UID 1000");
        } finally {
            incus.stop(TEST_MINIMAL);
        }
    }
}
