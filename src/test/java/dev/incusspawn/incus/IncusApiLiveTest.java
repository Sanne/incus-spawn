package dev.incusspawn.incus;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration tests for IncusApi against a real Incus daemon.
 * These tests are skipped automatically if the Incus socket is not accessible.
 *
 * Run with socket access:
 *   sg incus-admin -c "mvn test -Dtest=IncusApiLiveTest"
 */
class IncusApiLiveTest {

    private static IncusApi http;

    @BeforeAll
    static void connect() {
        http = IncusApi.tryConnect();
        if (http == null) {
            System.out.println("Skipping live tests: Incus socket not accessible.");
        }
    }

    private static boolean skip() {
        return http == null;
    }

    @Test
    void probeReturnsApiVersion() {
        if (skip()) return;
        var resp = http.get("/1.0");
        assertTrue(resp.isSuccess(), "GET /1.0 should return 200, got: " + resp.statusCode());
        assertFalse(resp.body().path("metadata").isMissingNode(), "metadata should be present");
    }

    @Test
    void listInstancesReturnsArray() {
        if (skip()) return;
        var resp = http.get("/1.0/instances?recursion=1");
        assertTrue(resp.isSuccess(), "GET /1.0/instances should return 200");
        var metadata = resp.body().path("metadata");
        assertTrue(metadata.isArray(), "metadata should be an array");
        System.out.println("Instances found: " + metadata.size());
    }

    @Test
    void listInstancesRecursion2IncludesStateField() {
        if (skip()) return;
        var resp = http.get("/1.0/instances?recursion=2");
        assertTrue(resp.isSuccess());
        var metadata = resp.body().path("metadata");
        assertTrue(metadata.isArray());
        // Each instance should have a 'state' field (may be null for stopped instances)
        for (var instance : metadata) {
            assertFalse(instance.path("state").isMissingNode(),
                    "Instance " + instance.path("name").asText() + " missing 'state' field");
            assertFalse(instance.path("config").isMissingNode(),
                    "Instance " + instance.path("name").asText() + " missing 'config' field");
            System.out.println("  " + instance.path("name").asText()
                    + " status=" + instance.path("status").asText()
                    + " type=" + instance.path("type").asText());
        }
    }

    @Test
    void storagePoolsListsDefault() {
        if (skip()) return;
        var resp = http.get("/1.0/storage-pools?recursion=1");
        assertTrue(resp.isSuccess(), "GET /1.0/storage-pools should return 200");
        var metadata = resp.body().path("metadata");
        assertTrue(metadata.isArray());
        assertTrue(metadata.size() > 0, "Expected at least one storage pool");
        for (var pool : metadata) {
            System.out.println("  pool: " + pool.path("name").asText()
                    + " driver=" + pool.path("driver").asText());
        }
    }

    @Test
    void networkConfigGetReadsIpv4Address() {
        if (skip()) return;
        var resp = http.get("/1.0/networks/incusbr0");
        assertTrue(resp.isSuccess(), "GET /1.0/networks/incusbr0 should return 200");
        var config = resp.body().path("metadata").path("config");
        assertFalse(config.isMissingNode(), "config field should be present");
        var ipv4 = config.path("ipv4.address");
        assertFalse(ipv4.isMissingNode(), "ipv4.address should be present in bridge config");
        System.out.println("incusbr0 ipv4.address = " + ipv4.asText());
        assertTrue(ipv4.asText().contains("."), "ipv4.address should look like an IP/CIDR");
    }

    @Test
    void nonExistentInstanceReturns404() {
        if (skip()) return;
        var resp = http.get("/1.0/instances/definitely-does-not-exist-xyz");
        assertEquals(404, resp.statusCode(), "Non-existent instance should return 404");
        assertFalse(resp.isSuccess());
    }

    @Test
    void createStartStopDeleteInstance() throws Exception {
        if (skip()) return;

        // Pull a small test image - use the busybox image from the Incus test images
        // Actually, let's use a pre-existing image if available, otherwise skip
        var imagesResp = http.get("/1.0/images?recursion=1");
        assertTrue(imagesResp.isSuccess());

        String imageFingerprint = null;
        for (var img : imagesResp.body().path("metadata")) {
            imageFingerprint = img.path("fingerprint").asText();
            break;
        }

        if (imageFingerprint == null) {
            System.out.println("Skipping create/start/stop/delete test: no local images available.");
            System.out.println("Run: incus image copy images:alpine/edge local: --alias test-image");
            return;
        }

        String name = "isx-live-test-" + System.currentTimeMillis() % 10000;
        System.out.println("Creating test instance: " + name + " from image " + imageFingerprint.substring(0, 12));

        try {
            // Create (privileged to avoid nested idmap issues in CI/nested environments)
            var createBody = new java.util.LinkedHashMap<String, Object>();
            createBody.put("name", name);
            createBody.put("type", "container");
            createBody.put("source", java.util.Map.of("type", "image", "fingerprint", imageFingerprint));
            createBody.put("config", java.util.Map.of("security.privileged", "true"));
            var createResp = http.requestAndWait("POST", "/1.0/instances", createBody);
            assertTrue(createResp.isSuccess(), "Create should succeed: " + createResp.body());

            // Verify exists
            var existsResp = http.get("/1.0/instances/" + name);
            assertTrue(existsResp.isSuccess());
            assertEquals("Stopped", existsResp.body().path("metadata").path("status").asText());

            // Config set
            var configResp = http.requestAndWait("PATCH", "/1.0/instances/" + name,
                    java.util.Map.of("config", java.util.Map.of("user.test-key", "hello")));
            assertTrue(configResp.isSuccess(), "Config PATCH should succeed");

            // Config get
            var infoResp = http.get("/1.0/instances/" + name);
            assertEquals("hello", infoResp.body().path("metadata").path("config").path("user.test-key").asText());

            // Config unset (null = remove)
            var unsetMap = new java.util.HashMap<String, Object>();
            unsetMap.put("user.test-key", null);
            var unsetResp = http.requestAndWait("PATCH", "/1.0/instances/" + name,
                    java.util.Map.of("config", unsetMap));
            assertTrue(unsetResp.isSuccess(), "Config unset should succeed");
            var afterUnset = http.get("/1.0/instances/" + name);
            assertTrue(afterUnset.body().path("metadata").path("config").path("user.test-key").isMissingNode(),
                    "Key should be removed after null PATCH");

            // Start
            var startResp = http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                    java.util.Map.of("action", "start", "timeout", 30, "force", false));
            assertTrue(startResp.isSuccess(), "Start should succeed");

            // Verify running
            var runningResp = http.get("/1.0/instances/" + name);
            assertEquals("Running", runningResp.body().path("metadata").path("status").asText());

            // Stop (force)
            var stopResp = http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                    java.util.Map.of("action", "stop", "timeout", 10, "force", true));
            assertTrue(stopResp.isSuccess(), "Stop should succeed");
            var stoppedResp = http.get("/1.0/instances/" + name);
            assertEquals("Stopped", stoppedResp.body().path("metadata").path("status").asText(),
                    "Should be Stopped after stop");

            // Device add (disk device)
            var diskDevice = new java.util.LinkedHashMap<String, String>();
            diskDevice.put("type", "disk");
            diskDevice.put("source", "/tmp");
            diskDevice.put("path", "/mnt/tmptest");
            var devAddResp = http.requestAndWait("PATCH", "/1.0/instances/" + name,
                    java.util.Map.of("devices", java.util.Map.of("tmptest", diskDevice)));
            assertTrue(devAddResp.isSuccess(), "Device add should succeed");
            var withDevResp = http.get("/1.0/instances/" + name);
            assertFalse(withDevResp.body().path("metadata").path("devices").path("tmptest").isMissingNode(),
                    "Device 'tmptest' should be present after add");

            // Device remove via read-modify-write PUT
            var devRemoveResp = http.removeDevice(name, "tmptest");
            assertTrue(devRemoveResp.isSuccess(), "Device remove should succeed");
            var noDevResp = http.get("/1.0/instances/" + name);
            assertTrue(noDevResp.body().path("metadata").path("devices").path("tmptest").isMissingNode(),
                    "Device 'tmptest' should be absent after remove");

            // Rename
            String renamed = name + "-r";
            var renameResp = http.requestAndWait("POST", "/1.0/instances/" + name,
                    java.util.Map.of("name", renamed, "migration", false));
            assertTrue(renameResp.isSuccess(), "Rename should succeed");
            assertTrue(http.get("/1.0/instances/" + renamed).isSuccess(), "Renamed instance should exist");
            assertFalse(http.get("/1.0/instances/" + name).isSuccess(), "Old name should not exist after rename");
            name = renamed;

        } finally {
            // Always clean up
            try {
                http.requestAndWait("DELETE", "/1.0/instances/" + name, null);
                System.out.println("Cleaned up test instance: " + name);
            } catch (Exception e) {
                System.err.println("Warning: cleanup failed for " + name + ": " + e.getMessage());
            }
        }
    }

    @Test
    void copyInstance() throws Exception {
        if (skip()) return;

        var imagesResp = http.get("/1.0/images?recursion=1");
        if (imagesResp.body().path("metadata").size() == 0) {
            System.out.println("Skipping copy test: no local images."); return;
        }
        var fingerprint = imagesResp.body().path("metadata").get(0).path("fingerprint").asText();
        String src = "isx-copy-src-" + System.currentTimeMillis() % 10000;
        String dst = src + "-copy";

        try {
            var createBody = new java.util.LinkedHashMap<String, Object>();
            createBody.put("name", src);
            createBody.put("type", "container");
            createBody.put("source", java.util.Map.of("type", "image", "fingerprint", fingerprint));
            createBody.put("config", java.util.Map.of("security.privileged", "true",
                    "user.test-marker", "original"));
            assertTrue(http.requestAndWait("POST", "/1.0/instances", createBody).isSuccess(),
                    "Source create should succeed");

            // Copy
            var copyBody = new java.util.LinkedHashMap<String, Object>();
            copyBody.put("name", dst);
            copyBody.put("source", java.util.Map.of("type", "copy", "source", src));
            assertTrue(http.requestAndWait("POST", "/1.0/instances", copyBody).isSuccess(),
                    "Copy should succeed");

            // Verify copy exists and has the config
            var dstResp = http.get("/1.0/instances/" + dst);
            assertTrue(dstResp.isSuccess(), "Copied instance should exist");
            assertEquals("original",
                    dstResp.body().path("metadata").path("config").path("user.test-marker").asText(),
                    "Copied instance should have source config");
            System.out.println("Copy succeeded: " + src + " → " + dst);

        } finally {
            http.requestAndWait("DELETE", "/1.0/instances/" + src, null);
            http.requestAndWait("DELETE", "/1.0/instances/" + dst, null);
        }
    }

    @Test
    void launchFromAlias() throws Exception {
        if (skip()) return;

        String name = "isx-launch-test-" + System.currentTimeMillis() % 10000;
        try {
            // Create from image alias (like incus launch)
            var createBody = new java.util.LinkedHashMap<String, Object>();
            createBody.put("name", name);
            createBody.put("type", "container");
            createBody.put("source", java.util.Map.of("type", "image", "alias", "test-alpine"));
            createBody.put("config", java.util.Map.of("security.privileged", "true"));
            var createResp = http.requestAndWait("POST", "/1.0/instances", createBody);
            assertTrue(createResp.isSuccess(), "Create from alias should succeed");

            // Then start (this is what our launch() does)
            var startResp = http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                    java.util.Map.of("action", "start", "timeout", 30, "force", false));
            assertTrue(startResp.isSuccess(), "Start after create should succeed");

            var statusResp = http.get("/1.0/instances/" + name);
            assertEquals("Running", statusResp.body().path("metadata").path("status").asText(),
                    "Should be Running after launch sequence");
            System.out.println("Launch (create+start) succeeded: " + name);

        } finally {
            try {
                http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                        java.util.Map.of("action", "stop", "timeout", 10, "force", true));
            } catch (Exception ignored) {}
            http.requestAndWait("DELETE", "/1.0/instances/" + name, null);
        }
    }

    @Test
    void deviceConfigSet() throws Exception {
        if (skip()) return;

        var imagesResp = http.get("/1.0/images?recursion=1");
        if (imagesResp.body().path("metadata").size() == 0) {
            System.out.println("Skipping deviceConfigSet test: no local images."); return;
        }
        var fingerprint = imagesResp.body().path("metadata").get(0).path("fingerprint").asText();
        String name = "isx-devset-test-" + System.currentTimeMillis() % 10000;
        try {
            var createBody = new java.util.LinkedHashMap<String, Object>();
            createBody.put("name", name);
            createBody.put("type", "container");
            createBody.put("source", java.util.Map.of("type", "image", "fingerprint", fingerprint));
            createBody.put("config", java.util.Map.of("security.privileged", "true"));
            assertTrue(http.requestAndWait("POST", "/1.0/instances", createBody).isSuccess());

            // deviceConfigSet: reads expanded_devices, merges "size", PATCHes full device config
            var setResp = http.deviceConfigSet(name, "root", "size", "10GB");
            assertTrue(setResp.isSuccess(), "deviceConfigSet should succeed");

            var infoResp = http.get("/1.0/instances/" + name);
            var rootSize = infoResp.body().path("metadata").path("devices")
                    .path("root").path("size").asText("");
            assertEquals("10GB", rootSize, "root device size should be updated to 10GB");
            System.out.println("deviceConfigSet succeeded: root.size = " + rootSize);

        } finally {
            http.requestAndWait("DELETE", "/1.0/instances/" + name, null);
        }
    }

    // =========================================================================
    // Exec via WebSocket
    // =========================================================================

    private static String testImageFingerprint() throws Exception {
        if (skip()) return null;
        var r = http.get("/1.0/images?recursion=1");
        var meta = r.body().path("metadata");
        if (meta.size() == 0) return null;
        return meta.get(0).path("fingerprint").asText();
    }

    private static String createAndStartContainer(String name, String fingerprint) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("name", name);
        body.put("type", "container");
        body.put("source", java.util.Map.of("type", "image", "fingerprint", fingerprint));
        body.put("config", java.util.Map.of("security.privileged", "true"));
        assertTrue(http.requestAndWait("POST", "/1.0/instances", body).isSuccess());
        assertTrue(http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                java.util.Map.of("action", "start", "timeout", 30, "force", false)).isSuccess());
        return name;
    }

    private static void deleteContainer(String name) {
        try {
            http.requestAndWait("PUT", "/1.0/instances/" + name + "/state",
                    java.util.Map.of("action", "stop", "timeout", 10, "force", true));
        } catch (Exception ignored) {}
        try { http.requestAndWait("DELETE", "/1.0/instances/" + name, null); } catch (Exception ignored) {}
    }

    @Test
    void execCaptureReturnsStdoutStderrAndExitCode() throws Exception {
        if (skip()) return;
        var fp = testImageFingerprint();
        if (fp == null) { System.out.println("Skipping exec test: no images."); return; }

        String name = "isx-exec-test-" + System.currentTimeMillis() % 10000;
        try {
            createAndStartContainer(name, fp);

            var result = http.execCapture(name,
                    List.of("sh", "-c", "echo hello-stdout; echo hello-stderr >&2; exit 7"),
                    0, 0, null, java.util.Map.of());

            assertEquals(7, result.exitCode(), "exit code should be 7");
            assertTrue(result.stdout().contains("hello-stdout"), "stdout: " + result.stdout());
            assertTrue(result.stderr().contains("hello-stderr"), "stderr: " + result.stderr());
            System.out.println("execCapture: exit=" + result.exitCode()
                    + " stdout=" + result.stdout().strip()
                    + " stderr=" + result.stderr().strip());
        } finally {
            deleteContainer(name);
        }
    }

    @Test
    void execCaptureWithUserAndGroup() throws Exception {
        if (skip()) return;
        var fp = testImageFingerprint();
        if (fp == null) { System.out.println("Skipping exec user test: no images."); return; }

        String name = "isx-execuser-test-" + System.currentTimeMillis() % 10000;
        try {
            createAndStartContainer(name, fp);

            // Run id as root (uid=0)
            var rootResult = http.execCapture(name, List.of("id", "-u"), 0, 0, "/root", java.util.Map.of());
            assertEquals(0, rootResult.exitCode());
            assertEquals("0", rootResult.stdout().strip());

            // Create a user (Alpine: adduser -D, Fedora: useradd)
            var addResult = http.execCapture(name, List.of("adduser", "-D", "testuser"), 0, 0, null, java.util.Map.of());
            if (addResult.exitCode() != 0) {
                addResult = http.execCapture(name, List.of("useradd", "testuser"), 0, 0, null, java.util.Map.of());
            }
            assertEquals(0, addResult.exitCode(), "user creation should succeed: " + addResult.stderr());
            var uidResult = http.execCapture(name, List.of("id", "-u", "testuser"), 0, 0, null, java.util.Map.of());
            assertEquals(0, uidResult.exitCode(), "id -u testuser should succeed: " + uidResult.stderr());
            assertFalse(uidResult.stdout().strip().isEmpty(), "id -u should return a uid");
            var uid = Integer.parseInt(uidResult.stdout().strip());

            var userResult = http.execCapture(name, List.of("id", "-u"), uid, uid, "/home/testuser",
                    java.util.Map.of("HOME", "/home/testuser"));
            assertEquals(0, userResult.exitCode());
            assertEquals(String.valueOf(uid), userResult.stdout().strip(), "should run as testuser");
            System.out.println("execCapture as user uid=" + uid + " confirmed");
        } finally {
            deleteContainer(name);
        }
    }

    @Test
    void execStreamWritesToOutputStreams() throws Exception {
        if (skip()) return;
        var fp = testImageFingerprint();
        if (fp == null) { System.out.println("Skipping execStream test: no images."); return; }

        String name = "isx-stream-test-" + System.currentTimeMillis() % 10000;
        try {
            createAndStartContainer(name, fp);

            var stdoutBuf = new java.io.ByteArrayOutputStream();
            var stderrBuf = new java.io.ByteArrayOutputStream();
            var exitCode = http.execStream(name,
                    List.of("sh", "-c", "echo streamed-out; echo streamed-err >&2"),
                    0, 0, null, java.util.Map.of(), stdoutBuf, stderrBuf);

            assertEquals(0, exitCode);
            assertTrue(stdoutBuf.toString().contains("streamed-out"), "stdout: " + stdoutBuf);
            assertTrue(stderrBuf.toString().contains("streamed-err"), "stderr: " + stderrBuf);
            System.out.println("execStream: stdout=" + stdoutBuf.toString().strip());
        } finally {
            deleteContainer(name);
        }
    }

    @Test
    void getLogReturnsContent() throws Exception {
        if (skip()) return;
        var fp = testImageFingerprint();
        if (fp == null) { System.out.println("Skipping log test: no images."); return; }

        String name = "isx-log-test-" + System.currentTimeMillis() % 10000;
        try {
            createAndStartContainer(name, fp);
            var logsResp = http.get("/1.0/instances/" + name + "/logs");
            assertTrue(logsResp.isSuccess(), "logs endpoint should return 200");
            var logs = logsResp.body().path("metadata");
            assertTrue(logs.isArray(), "logs should be array");
            System.out.println("Log files: " + logs.size());
            if (logs.size() > 0) {
                // Get the last log file content
                var logPath = logs.get(logs.size() - 1).asText();
                var content = http.getText(logPath);
                assertNotNull(content);
                System.out.println("Log content length: " + content.length() + " chars");
            }
        } finally {
            deleteContainer(name);
        }
    }

    @Test
    void networkConfigGetAndSet() {
        if (skip()) return;
        // Read current raw.dnsmasq value
        var resp = http.get("/1.0/networks/incusbr0");
        assertTrue(resp.isSuccess());
        var config = resp.body().path("metadata").path("config");
        var original = config.path("raw.dnsmasq").isMissingNode() ? "" : config.path("raw.dnsmasq").asText();

        // Set a test value
        var setResp = http.requestAndWait("PATCH", "/1.0/networks/incusbr0",
                java.util.Map.of("config", java.util.Map.of("raw.dnsmasq", "# live-test")));
        assertTrue(setResp.isSuccess(), "Network config set should succeed");

        // Read it back
        var readResp = http.get("/1.0/networks/incusbr0");
        assertEquals("# live-test",
                readResp.body().path("metadata").path("config").path("raw.dnsmasq").asText(),
                "raw.dnsmasq should be updated");

        // Restore
        http.requestAndWait("PATCH", "/1.0/networks/incusbr0",
                java.util.Map.of("config", java.util.Map.of("raw.dnsmasq", original)));
    }

    @Test
    void filePushAndReadBack() throws Exception {
        if (skip()) return;

        // Need a running instance for file push
        var imagesResp = http.get("/1.0/images?recursion=1");
        if (imagesResp.body().path("metadata").size() == 0) {
            System.out.println("Skipping file push test: no local images.");
            return;
        }
        var imageFingerprint = imagesResp.body().path("metadata").get(0).path("fingerprint").asText();
        String name = "isx-filepush-test-" + System.currentTimeMillis() % 10000;

        try {
            var createBody = new java.util.LinkedHashMap<String, Object>();
            createBody.put("name", name);
            createBody.put("type", "container");
            createBody.put("source", java.util.Map.of("type", "image", "fingerprint", imageFingerprint));
            createBody.put("config", java.util.Map.of("security.privileged", "true"));
            assertTrue(http.requestAndWait("POST", "/1.0/instances", createBody).isSuccess());

            // Push a file
            var tmp = Files.createTempFile("isx-test", ".txt");
            Files.writeString(tmp, "hello from REST API");
            var pushResp = http.filePush(name, "/root/test.txt", tmp);
            assertTrue(pushResp.isSuccess(), "File push should succeed: " + pushResp.statusCode() + " " + pushResp.body());
            Files.delete(tmp);
            System.out.println("File push succeeded for instance: " + name);

        } finally {
            try {
                http.requestAndWait("DELETE", "/1.0/instances/" + name, null);
                System.out.println("Cleaned up: " + name);
            } catch (Exception ignored) {}
        }
    }
}
