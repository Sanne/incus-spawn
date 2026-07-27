package dev.incusspawn.incus;

import org.junit.jupiter.api.*;

import java.util.List;

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
    private static final String TEST_PROFILE = "isx-it-testprofile";

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
        // Profile first: its NIC references TEST_BRIDGE, and Incus refuses to delete a network
        // that is still in use — which would leak the bridge and fail the next run.
        try { client.deleteProfile(TEST_PROFILE); } catch (Exception ignored) {}
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

    /**
     * Regression: a daemon with a storage pool but an unpopulated default profile made every
     * instance creation fail with "Failed getting root disk: No root device could be found".
     * 'incus admin init --minimal' is skipped once any pool exists, so init has to repair this
     * itself. Runs against a scratch profile — breaking the real default profile would leave a
     * developer's machine unable to create instances if the test aborted mid-run.
     */
    @Test @Order(5)
    void ensureProfileDevicesRepairsEmptyProfile() {
        // Delete first: a profile left behind by an aborted run would already have devices.
        client.deleteProfile(TEST_PROFILE);
        client.createProfile(TEST_PROFILE);
        var pool = client.findCowPool();
        Assumptions.assumeTrue(pool != null, "No CoW pool — skipping");

        var added = client.ensureProfileDevices(TEST_PROFILE, pool, TEST_BRIDGE);
        assertEquals(List.of("root", "eth0"), added,
                "An empty profile should get both a root disk and a NIC");
    }

    @Test @Order(6)
    void ensureProfileDevicesIsIdempotent() {
        var pool = client.findCowPool();
        Assumptions.assumeTrue(pool != null, "No CoW pool — skipping");
        assertEquals(List.of(), client.ensureProfileDevices(TEST_PROFILE, pool, TEST_BRIDGE),
                "A complete profile should be left untouched");
    }

    /**
     * The repaired profile must actually satisfy Incus, not merely look right — this is the
     * assertion that would have caught the original bug.
     */
    @Test @Order(7)
    void repairedProfileHasUsableRootDisk() {
        var devices = client.profileDevices(TEST_PROFILE);
        assertEquals("disk", devices.path("root").path("type").asText());
        assertEquals("/", devices.path("root").path("path").asText());
        assertFalse(devices.path("root").path("pool").asText().isEmpty(),
                "The root disk must name a storage pool");
    }

    /** A pre-existing device must never be rewritten — only genuinely missing ones are added. */
    @Test @Order(8)
    void ensureProfileDevicesPreservesExistingDevices() {
        var pool = client.findCowPool();
        Assumptions.assumeTrue(pool != null, "No CoW pool — skipping");
        var before = client.profileDevices(TEST_PROFILE).path("root").deepCopy();

        client.ensureProfileDevices(TEST_PROFILE, "some-other-pool", "some-other-bridge");

        assertEquals(before, client.profileDevices(TEST_PROFILE).path("root"),
                "An existing root disk must not be repointed at another pool");
    }
}
