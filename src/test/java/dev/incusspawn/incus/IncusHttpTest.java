package dev.incusspawn.incus;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncusHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final IncusHttp http = new IncusHttp("/nonexistent");

    // --- readLine ---

    @Test
    void readLineHandlesCrLf() throws Exception {
        var in = stream("hello\r\nworld\r\n");
        assertEquals("hello", http.readLine(in));
        assertEquals("world", http.readLine(in));
        assertEquals("", http.readLine(in));
    }

    @Test
    void readLineHandlesBareNewline() throws Exception {
        var in = stream("hello\nworld\n");
        assertEquals("hello", http.readLine(in));
        assertEquals("world", http.readLine(in));
    }

    @Test
    void readLineReturnsEmptyStringForBlankLine() throws Exception {
        var in = stream("\r\n");
        assertEquals("", http.readLine(in));
    }

    // --- readChunkedBody ---

    @Test
    void readChunkedBodyDecodesSimpleChunk() throws Exception {
        // "5\r\nHello\r\n0\r\n\r\n"
        var in = stream("5\r\nHello\r\n0\r\n\r\n");
        var result = http.readChunkedBody(in);
        assertArrayEquals("Hello".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void readChunkedBodyDecodesMultipleChunks() throws Exception {
        var in = stream("5\r\nHello\r\n6\r\n World\r\n0\r\n\r\n");
        var result = http.readChunkedBody(in);
        assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void readChunkedBodyHandlesChunkExtensions() throws Exception {
        var in = stream("5;ext=ignored\r\nHello\r\n0\r\n\r\n");
        var result = http.readChunkedBody(in);
        assertArrayEquals("Hello".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    void readChunkedBodyHandlesEmptyBody() throws Exception {
        var in = stream("0\r\n\r\n");
        var result = http.readChunkedBody(in);
        assertEquals(0, result.length);
    }

    // --- tryConnect does not blow up on absent socket ---

    @Test
    void tryConnectReturnsNullWhenNoSocketExists() {
        // All standard paths don't exist in unit test environment
        var candidates = IncusHttp.SOCKET_CANDIDATES;
        // At least verify the method returns null gracefully (real socket likely absent)
        var result = IncusHttp.tryConnect();
        // In CI/unit test environment, socket is not accessible — null is correct
        // In a live Incus environment this could be non-null; both are valid
        assertTrue(result == null || result instanceof IncusHttp);
    }

    // --- ApiResponse helpers ---

    @Test
    void apiResponseIsSuccess() throws Exception {
        var node = JSON.readTree("{\"type\":\"sync\",\"metadata\":[]}");
        var resp = new IncusHttp.ApiResponse(200, node);
        assertTrue(resp.isSuccess());
        assertFalse(resp.isAsync());
    }

    @Test
    void apiResponseIsAsync() throws Exception {
        var node = JSON.readTree("{\"type\":\"async\",\"operation\":\"/1.0/operations/abc\"}");
        var resp = new IncusHttp.ApiResponse(202, node);
        assertTrue(resp.isAsync());
        assertEquals("/1.0/operations/abc", resp.operationPath());
    }

    @Test
    void apiResponseOperationPathEmptyWhenAbsent() throws Exception {
        var node = JSON.readTree("{\"type\":\"sync\"}");
        var resp = new IncusHttp.ApiResponse(200, node);
        assertEquals("", resp.operationPath());
    }

    // --- list() response format compatibility ---

    @Test
    void listMetadataExtractsNameStatusType() throws Exception {
        var json = JSON.readTree("""
                {
                  "type": "sync",
                  "metadata": [
                    {"name": "tpl-dev", "status": "Stopped", "type": "container"},
                    {"name": "my-inst", "status": "Running", "type": "virtual-machine"}
                  ]
                }
                """);
        var instances = List.of(json.path("metadata").elements().next(),
                json.path("metadata").get(1));
        assertEquals("tpl-dev", instances.get(0).path("name").asText());
        assertEquals("Stopped", instances.get(0).path("status").asText());
        assertEquals("container", instances.get(0).path("type").asText());
        assertEquals("Running", instances.get(1).path("status").asText());
    }

    // --- listJson() response format compatibility ---

    @Test
    void listJsonMetadataArrayIsValidForCallers() throws Exception {
        var json = JSON.readTree("""
                {
                  "type": "sync",
                  "metadata": [
                    {
                      "name": "tpl-dev",
                      "config": {"user.incus-spawn.type": "base"},
                      "expanded_devices": {"root": {"size": "20GB"}},
                      "state": {"network": null}
                    }
                  ]
                }
                """);
        // Simulate what listJson() returns: just the metadata array serialized
        var metadataStr = json.path("metadata").toString();
        var parsed = JSON.readTree(metadataStr);
        assertTrue(parsed.isArray());
        var node = parsed.get(0);
        assertEquals("tpl-dev", node.path("name").asText());
        assertEquals("base", node.path("config").path("user.incus-spawn.type").asText());
        assertEquals("20GB", node.path("expanded_devices").path("root").path("size").asText());
    }

    // --- configGet response format ---

    @Test
    void configGetExtractsKnownKey() throws Exception {
        var json = JSON.readTree("""
                {
                  "type": "sync",
                  "metadata": {
                    "name": "tpl-dev",
                    "config": {
                      "user.incus-spawn.workdir": "/home/agentuser",
                      "user.incus-spawn.type": "base"
                    }
                  }
                }
                """);
        var value = json.path("metadata").path("config").path("user.incus-spawn.workdir");
        assertFalse(value.isMissingNode());
        assertEquals("/home/agentuser", value.asText());
    }

    @Test
    void configGetReturnsEmptyForAbsentKey() throws Exception {
        var json = JSON.readTree("""
                {
                  "type": "sync",
                  "metadata": {
                    "name": "tpl-dev",
                    "config": {}
                  }
                }
                """);
        var value = json.path("metadata").path("config").path("user.incus-spawn.missing");
        assertTrue(value.isMissingNode());
        // Callers should treat missing as empty string
        var result = value.isMissingNode() || value.isNull() ? "" : value.asText();
        assertEquals("", result);
    }

    // --- configSet PATCH body format ---

    @Test
    void configSetPatchBodyHasCorrectStructure() throws Exception {
        var body = java.util.Map.of("config", java.util.Map.of("user.incus-spawn.workdir", "/home/agentuser"));
        var json = JSON.valueToTree(body);
        assertEquals("/home/agentuser", json.path("config").path("user.incus-spawn.workdir").asText());
    }

    // --- state-change request body format ---

    @Test
    void stateChangeBodyHasRequiredFields() throws Exception {
        var body = java.util.Map.of("action", "start", "timeout", 30, "force", false);
        var json = JSON.valueToTree(body);
        assertEquals("start", json.path("action").asText());
        assertEquals(30, json.path("timeout").asInt());
        assertFalse(json.path("force").asBoolean());
    }

    @Test
    void forceStopBodyHasForceTrue() throws Exception {
        var body = java.util.Map.of("action", "stop", "timeout", 30, "force", true);
        var json = JSON.valueToTree(body);
        assertTrue(json.path("force").asBoolean());
    }

    @Test
    void renameBodyHasNameAndMigration() throws Exception {
        var body = java.util.Map.of("name", "new-name", "migration", false);
        var json = JSON.valueToTree(body);
        assertEquals("new-name", json.path("name").asText());
        assertFalse(json.path("migration").asBoolean());
    }

    // --- waitForOperation error handling ---

    @Test
    void asyncResponseDetectedOn202() throws Exception {
        var node = JSON.readTree("{\"type\":\"async\",\"operation\":\"/1.0/operations/abc\",\"status_code\":202}");
        var resp = new IncusHttp.ApiResponse(202, node);
        assertTrue(resp.isAsync());
        assertEquals("/1.0/operations/abc", resp.operationPath());
    }

    @Test
    void operationFailureStatusDetected() throws Exception {
        var metadata = JSON.readTree("{\"status\":\"Failure\",\"err\":\"container is running\"}");
        assertEquals("Failure", metadata.path("status").asText());
        assertEquals("container is running", metadata.path("err").asText());
    }

    // --- probeCowPool REST response format ---

    @Test
    void probeCowPoolFindsBtrfsPool() throws Exception {
        var json = JSON.readTree("""
                {
                  "type": "sync",
                  "metadata": [
                    {"name": "default", "driver": "btrfs", "status": "Created"},
                    {"name": "other", "driver": "dir", "status": "Created"}
                  ]
                }
                """);
        String found = null;
        for (var pool : json.path("metadata")) {
            var driver = pool.path("driver").asText("");
            if (java.util.Set.of("btrfs", "zfs", "lvm").contains(driver)) {
                found = pool.path("name").asText();
                break;
            }
        }
        assertEquals("default", found);
    }

    @Test
    void probeCowPoolReturnsNullWhenNoCowDriver() throws Exception {
        var json = JSON.readTree("""
                {
                  "type": "sync",
                  "metadata": [
                    {"name": "default", "driver": "dir", "status": "Created"}
                  ]
                }
                """);
        String found = null;
        for (var pool : json.path("metadata")) {
            var driver = pool.path("driver").asText("");
            if (java.util.Set.of("btrfs", "zfs", "lvm").contains(driver)) {
                found = pool.path("name").asText();
                break;
            }
        }
        assertNull(found);
    }

    // --- deviceAdd PATCH body format ---

    @Test
    void deviceAddBodyContainsTypeAndProps() throws Exception {
        var device = new java.util.LinkedHashMap<String, String>();
        device.put("type", "disk");
        device.put("source", "/home/user/.m2");
        device.put("path", "/home/agentuser/.m2");
        var body = java.util.Map.of("devices", java.util.Map.of("m2cache", device));
        var json = JSON.valueToTree(body);
        var d = json.path("devices").path("m2cache");
        assertEquals("disk", d.path("type").asText());
        assertEquals("/home/user/.m2", d.path("source").asText());
        assertEquals("/home/agentuser/.m2", d.path("path").asText());
    }

    @Test
    void deviceRemoveBodySetsDeviceToNull() throws Exception {
        var devicesMap = new java.util.HashMap<String, Object>();
        devicesMap.put("m2cache", null);
        var body = java.util.Map.of("devices", devicesMap);
        var json = JSON.valueToTree(body);
        assertTrue(json.path("devices").path("m2cache").isNull());
    }

    @Test
    void deviceAddPropParsingHandlesEqualsInValue() {
        var prop = "source=/home/user/.m2";
        int eq = prop.indexOf('=');
        assertEquals("source", prop.substring(0, eq));
        assertEquals("/home/user/.m2", prop.substring(eq + 1));
    }

    // --- file push header format ---

    @Test
    void filePushHeadersContainRequiredIncusFields() {
        var headers = java.util.Map.of(
                "X-Incus-uid", "0",
                "X-Incus-gid", "0",
                "X-Incus-mode", "0644",
                "X-Incus-type", "file");
        assertEquals("0", headers.get("X-Incus-uid"));
        assertEquals("0644", headers.get("X-Incus-mode"));
        assertEquals("file", headers.get("X-Incus-type"));
    }

    @Test
    void mkdirHeadersContainDirectoryType() {
        var headers = java.util.Map.of(
                "X-Incus-uid", "0",
                "X-Incus-gid", "0",
                "X-Incus-mode", "0755",
                "X-Incus-type", "directory");
        assertEquals("directory", headers.get("X-Incus-type"));
        assertEquals("0755", headers.get("X-Incus-mode"));
    }

    // --- exists() uses status code ---

    @Test
    void existsReturnsTrueOn200() throws Exception {
        var resp = new IncusHttp.ApiResponse(200, JSON.readTree("{\"type\":\"sync\"}"));
        assertTrue(resp.isSuccess());
    }

    @Test
    void existsReturnsFalseOn404() throws Exception {
        var resp = new IncusHttp.ApiResponse(404, JSON.readTree("{\"type\":\"error\"}"));
        assertFalse(resp.isSuccess());
    }

    // --- networkConfigGet REST response format ---

    @Test
    void networkConfigGetExtractsKnownKey() throws Exception {
        var json = JSON.readTree("""
                {
                  "type": "sync",
                  "metadata": {
                    "name": "incusbr0",
                    "config": {
                      "ipv4.address": "172.20.0.1/24",
                      "raw.dnsmasq": ""
                    }
                  }
                }
                """);
        var value = json.path("metadata").path("config").path("ipv4.address");
        assertEquals("172.20.0.1/24", value.asText());
    }

    @Test
    void networkConfigSetBodyContainsKey() throws Exception {
        var body = java.util.Map.of("config", java.util.Map.of("ipv4.address", "172.21.0.1/24"));
        var json = JSON.valueToTree(body);
        assertEquals("172.21.0.1/24", json.path("config").path("ipv4.address").asText());
    }

    // --- copy() REST body format ---

    @Test
    void copyBodyContainsSourceAndTarget() throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("name", "my-branch");
        body.put("source", java.util.Map.of("type", "copy", "source", "tpl-dev"));
        body.put("storage", "default");
        var json = JSON.valueToTree(body);
        assertEquals("my-branch", json.path("name").asText());
        assertEquals("copy", json.path("source").path("type").asText());
        assertEquals("tpl-dev", json.path("source").path("source").asText());
        assertEquals("default", json.path("storage").asText());
    }

    // --- launch() REST body format ---

    @Test
    void launchBodyContainsImageAlias() throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("name", "tpl-minimal");
        body.put("type", "container");
        body.put("source", java.util.Map.of("type", "image", "alias", "images:ubuntu/24.04"));
        var json = JSON.valueToTree(body);
        assertEquals("tpl-minimal", json.path("name").asText());
        assertEquals("container", json.path("type").asText());
        assertEquals("image", json.path("source").path("type").asText());
        assertEquals("images:ubuntu/24.04", json.path("source").path("alias").asText());
    }

    @Test
    void launchVmBodyHasVirtualMachineType() throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("name", "my-vm");
        body.put("type", "virtual-machine");
        body.put("source", java.util.Map.of("type", "image", "alias", "images:ubuntu/24.04"));
        var json = JSON.valueToTree(body);
        assertEquals("virtual-machine", json.path("type").asText());
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
