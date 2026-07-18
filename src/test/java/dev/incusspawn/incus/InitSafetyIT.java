package dev.incusspawn.incus;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the init-related IncusClient methods: hasStoragePool()
 * and createBridgeIfMissing(). Exercises the queries and idempotent create path
 * against a real Incus daemon.
 *
 * Run with:
 *   mvn verify -DskipITs=false -Dit.test=InitSafetyIT
 *
 * Skips gracefully if Incus is not reachable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InitSafetyIT {

    private static final String TEST_BRIDGE = "isx-it-testbr";
    private static final String TEST_GATEWAY = "10.254.254.1";

    private static IncusClient client;

    @BeforeAll
    static void setUp() {
        client = new IncusClient();
        var error = client.checkConnectivity();
        Assumptions.assumeTrue(error == null,
                "Incus not reachable — skipping: " + error);
    }

    @AfterAll
    static void tearDown() {
        if (client == null) return;
        try { client.deleteNetwork(TEST_BRIDGE); } catch (Exception ignored) {}
    }

    @Test @Order(1)
    void hasStoragePoolTrueOnInitializedDaemon() {
        assertTrue(client.hasStoragePool(),
                "An initialized daemon should have at least one storage pool");
    }

    @Test @Order(2)
    void createBridgeIfMissingCreatesNewBridge() {
        assertTrue(client.createBridgeIfMissing(TEST_BRIDGE, TEST_GATEWAY),
                "Should return true when creating a new bridge");
    }

    @Test @Order(3)
    void createBridgeIfMissingIdempotent() {
        assertFalse(client.createBridgeIfMissing(TEST_BRIDGE, TEST_GATEWAY),
                "Should return false on second call — bridge already exists");
    }

    @Test @Order(4)
    void deleteAndRecreate() {
        client.deleteNetwork(TEST_BRIDGE);
        assertTrue(client.createBridgeIfMissing(TEST_BRIDGE, TEST_GATEWAY),
                "After deletion, creating again should return true");
    }
}
