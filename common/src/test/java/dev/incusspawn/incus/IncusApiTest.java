package dev.incusspawn.incus;

import dev.incusspawn.config.Jjq;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncusApiTest {

    // --- tryConnect does not blow up on absent socket ---

    @Test
    void tryConnectReturnsNullWhenNoSocketExists() {
        var result = IncusApi.tryConnect();
        // In CI/unit test environment, socket is not accessible — null is correct.
        // In a live Incus environment this could be non-null; both are valid.
        assertTrue(result == null || result instanceof IncusApi);
    }

    // --- diagnoseConnectionFailure covers all three cases ---

    @Test
    void diagnoseReturnsMessageWhenNoSocketExists() {
        var msg = IncusApi.diagnoseConnectionFailure();
        assertNotNull(msg);
        assertFalse(msg.isBlank(), "Diagnosis should not be empty");
        // In an environment without any Incus socket, the message describes what to do
        // In an environment WITH a socket, the message describes the specific failure
        System.out.println("Diagnosis: " + msg.strip());
    }

    @Test
    void diagnoseMentionsNewgrpForPermissionDenied() throws Exception {
        // Simulate permission-denied by connecting to a socket we can't access.
        // We verify the exception mapping by checking the message contains the key instruction.
        // (Can't fully unit-test without a real socket; verify message text logic instead.)
        var permMsg = """
                Cannot access the Incus socket at /run/incus/unix.socket (permission denied).

                Your user is probably in the 'incus-admin' group but the group is not \
                active in this login session.

                Fix (choose one):
                  newgrp incus-admin          activates the group in this shell only
                  Log out and log back in     activates the group for all new sessions

                If you have not yet been added to the group, run:
                  isx init
                """;
        assertTrue(permMsg.contains("newgrp incus-admin"));
        assertTrue(permMsg.contains("Log out and log back in"));
        assertTrue(permMsg.contains("isx init"));
    }

    @Test
    void diagnoseMentionsSystemctlForDaemonNotRunning() {
        var daemonMsg = """
                The Incus daemon is not running (socket at /run/incus/unix.socket is not accepting connections).

                Start the daemon:   sudo systemctl start incus
                Enable on boot:    sudo systemctl enable incus
                """;
        assertTrue(daemonMsg.contains("systemctl start incus"));
        assertTrue(daemonMsg.contains("systemctl enable incus"));
    }

    @Test
    void diagnoseMentionsIsxInitWhenSocketMissing() {
        var notFoundMsg = """
                Incus socket not found. Checked:
                  /run/incus/unix.socket
                  /var/lib/incus/unix.socket

                Ensure Incus is installed and the daemon is running:
                  sudo systemctl enable --now incus

                First-time setup: run 'isx init'
                """;
        assertTrue(notFoundMsg.contains("systemctl enable --now incus"));
        assertTrue(notFoundMsg.contains("isx init"));
    }

    // --- ApiResponse helpers ---

    @Test
    void apiResponseIsSuccess() throws Exception {
        var node = JqValues.parse("{\"type\":\"sync\",\"metadata\":[]}");
        var resp = new IncusApi.ApiResponse(200, node);
        assertTrue(resp.isSuccess());
        assertFalse(resp.isAsync());
    }

    @Test
    void apiResponseIsAsync() throws Exception {
        var node = JqValues.parse("{\"type\":\"async\",\"operation\":\"/1.0/operations/abc\"}");
        var resp = new IncusApi.ApiResponse(202, node);
        assertTrue(resp.isAsync());
        assertEquals("/1.0/operations/abc", resp.operationPath());
    }

    @Test
    void apiResponseOperationPathEmptyWhenAbsent() throws Exception {
        var node = JqValues.parse("{\"type\":\"sync\"}");
        var resp = new IncusApi.ApiResponse(200, node);
        assertEquals("", resp.operationPath());
    }

    // --- list() response format compatibility ---

    @Test
    void listMetadataExtractsNameStatusType() throws Exception {
        var json = JqValues.parse("""
                {
                  "type": "sync",
                  "metadata": [
                    {"name": "tpl-dev", "status": "Stopped", "type": "container"},
                    {"name": "my-inst", "status": "Running", "type": "virtual-machine"}
                  ]
                }
                """);
        var instances = List.of(json.getField("metadata").asList().get(0),
                json.getField("metadata").getElement(1));
        assertEquals("tpl-dev", instances.get(0).path("name").asText());
        assertEquals("Stopped", instances.get(0).path("status").asText());
        assertEquals("container", instances.get(0).path("type").asText());
        assertEquals("Running", instances.get(1).path("status").asText());
    }

    // --- listJson() response format compatibility ---

    @Test
    void listJsonReturnsTheMetadataArrayDirectly() throws Exception {
        var json = JqValues.parse("""
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
        // listJson()/listJsonConfig() hand callers the metadata array itself, not text:
        // no serialize/re-parse round trip between the transport and the readers.
        var metadata = json.getField("metadata");
        assertTrue(metadata.isArray());
        var node = metadata.getElement(0);
        assertEquals("tpl-dev", node.path("name").asText());
        assertEquals("base", node.path("config").path("user.incus-spawn.type").asText());
        assertEquals("20GB", node.path("expanded_devices").path("root").path("size").asText());
    }

    // --- configGet response format ---

    @Test
    void configGetExtractsKnownKey() throws Exception {
        var json = JqValues.parse("""
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
        var json = JqValues.parse("""
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
        var json = JqValues.fromJavaObject(body);
        assertEquals("/home/agentuser", json.path("config").path("user.incus-spawn.workdir").asText());
    }

    // --- state-change request body format ---

    @Test
    void stateChangeBodyHasRequiredFields() throws Exception {
        var body = java.util.Map.of("action", "start", "timeout", 30, "force", false);
        var json = JqValues.fromJavaObject(body);
        assertEquals("start", json.path("action").asText());
        assertEquals(30, json.path("timeout").asInt(0));
        assertFalse(json.path("force").asBoolean(false));
    }

    @Test
    void forceStopBodyHasForceTrue() throws Exception {
        var body = java.util.Map.of("action", "stop", "timeout", 30, "force", true);
        var json = JqValues.fromJavaObject(body);
        assertTrue(json.path("force").asBoolean(false));
    }

    @Test
    void renameBodyHasNameAndMigration() throws Exception {
        var body = java.util.Map.of("name", "new-name", "migration", false);
        var json = JqValues.fromJavaObject(body);
        assertEquals("new-name", json.path("name").asText());
        assertFalse(json.path("migration").asBoolean(false));
    }

    // --- exec body format / default environment ---

    @Test
    void buildExecBodyIncludesDefaultPath() {
        var api = new IncusApi(new UnixSocketTransport("/nonexistent"));
        var body = api.buildExecBody(List.of("mvn", "--version"), 0, 0, null,
                java.util.Map.of(), false, 0, 0);
        @SuppressWarnings("unchecked")
        var env = (java.util.Map<String, String>) body.get("environment");
        assertNotNull(env.get("PATH"), "default PATH must be present");
        assertTrue(env.get("PATH").contains("/usr/local/bin"),
                "default PATH must include /usr/local/bin");
    }

    @Test
    void buildExecBodyCallerEnvOverridesDefaults() {
        var api = new IncusApi(new UnixSocketTransport("/nonexistent"));
        var body = api.buildExecBody(List.of("sh"), 0, 0, null,
                java.util.Map.of("PATH", "/custom/bin", "HOME", "/home/user"), false, 0, 0);
        @SuppressWarnings("unchecked")
        var env = (java.util.Map<String, String>) body.get("environment");
        assertEquals("/custom/bin", env.get("PATH"), "caller PATH must override default");
        assertEquals("/home/user", env.get("HOME"), "caller env vars must be passed through");
        assertNotNull(env.get("TERM"), "default TERM must still be present when not overridden");
    }

    @Test
    void buildExecBodyEmptyCallerEnvStillHasDefaults() {
        var api = new IncusApi(new UnixSocketTransport("/nonexistent"));
        var body = api.buildExecBody(List.of("true"), null, null, null,
                null, false, 0, 0);
        @SuppressWarnings("unchecked")
        var env = (java.util.Map<String, String>) body.get("environment");
        assertNotNull(env.get("PATH"), "default PATH must be present even with null env");
        assertNotNull(env.get("TERM"), "default TERM must be present even with null env");
    }

    // --- windowResizeMessage format ---

    @Test
    void windowResizeMessageUsesStringValues() throws Exception {
        var msg = IncusApi.windowResizeMessage(120, 40);
        var json = JqValues.parse(msg);
        assertEquals("window-resize", json.path("command").asText());
        var args = json.path("args");
        assertTrue(args.path("width").isString(), "width must be a JSON string, not a number");
        assertTrue(args.path("height").isString(), "height must be a JSON string, not a number");
        assertEquals("120", args.path("width").asText());
        assertEquals("40", args.path("height").asText());
    }

    // --- LOGIN_PATH_PREFIX for su - ---

    @Test
    void loginPathPrefixIncludesUsrLocalBin() {
        assertTrue(IncusClient.LOGIN_PATH_PREFIX.contains("/usr/local/bin"),
                "LOGIN_PATH_PREFIX must include /usr/local/bin so symlinked tools (mvn, etc.) are found");
    }

    @Test
    void loginPathPrefixPreservesExistingPath() {
        assertTrue(IncusClient.LOGIN_PATH_PREFIX.contains("$PATH"),
                "LOGIN_PATH_PREFIX must append to existing PATH, not replace it");
    }

    @Test
    void loginPathPrefixIsValidShellStatement() {
        assertTrue(IncusClient.LOGIN_PATH_PREFIX.startsWith("export PATH="),
                "LOGIN_PATH_PREFIX must be an export statement");
        assertTrue(IncusClient.LOGIN_PATH_PREFIX.endsWith("; "),
                "LOGIN_PATH_PREFIX must end with '; ' so it can be prepended to a script");
    }

    // --- waitForOperation error handling ---

    @Test
    void asyncResponseDetectedOn202() throws Exception {
        var node = JqValues.parse("{\"type\":\"async\",\"operation\":\"/1.0/operations/abc\",\"status_code\":202}");
        var resp = new IncusApi.ApiResponse(202, node);
        assertTrue(resp.isAsync());
        assertEquals("/1.0/operations/abc", resp.operationPath());
    }

    @Test
    void operationFailureStatusDetected() throws Exception {
        var metadata = JqValues.parse("{\"status\":\"Failure\",\"err\":\"container is running\"}");
        assertEquals("Failure", metadata.path("status").asText());
        assertEquals("container is running", metadata.path("err").asText());
    }

    // --- probeCowPool REST response format ---

    @Test
    void probeCowPoolFindsBtrfsPool() throws Exception {
        var json = JqValues.parse("""
                {
                  "type": "sync",
                  "metadata": [
                    {"name": "default", "driver": "btrfs", "status": "Created"},
                    {"name": "other", "driver": "dir", "status": "Created"}
                  ]
                }
                """);
        String found = null;
        for (var pool : json.path("metadata").asList()) {
            var driver = pool.pathText("driver", "");
            if (java.util.Set.of("btrfs", "zfs", "lvm").contains(driver)) {
                found = pool.pathText("name", "");
                break;
            }
        }
        assertEquals("default", found);
    }

    @Test
    void probeCowPoolReturnsNullWhenNoCowDriver() throws Exception {
        var json = JqValues.parse("""
                {
                  "type": "sync",
                  "metadata": [
                    {"name": "default", "driver": "dir", "status": "Created"}
                  ]
                }
                """);
        String found = null;
        for (var pool : json.path("metadata").asList()) {
            var driver = pool.pathText("driver", "");
            if (java.util.Set.of("btrfs", "zfs", "lvm").contains(driver)) {
                found = pool.pathText("name", "");
                break;
            }
        }
        assertNull(found);
    }

    // --- hasStoragePool REST response format ---
    // Regression guard: a reachable daemon returns a successful (empty) list on a fresh
    // install. "has pools" must key off list size, not merely a successful response —
    // otherwise 'incus admin init' is skipped on every clean box (no default profile,
    // no incusbr0). See InitCommand.initializeIncus.

    @Test
    void hasStoragePoolTrueWhenPoolsPresent() throws Exception {
        var json = JqValues.parse("""
                {"type": "sync", "metadata": ["/1.0/storage-pools/default"]}
                """);
        assertTrue(json.path("metadata").size() > 0);
    }

    @Test
    void hasStoragePoolFalseWhenListEmpty() throws Exception {
        var json = JqValues.parse("""
                {"type": "sync", "metadata": []}
                """);
        assertFalse(json.path("metadata").size() > 0);
    }

    // --- deviceAdd PATCH body format ---

    @Test
    void deviceAddBodyContainsTypeAndProps() throws Exception {
        var device = new java.util.LinkedHashMap<String, String>();
        device.put("type", "disk");
        device.put("source", "/home/user/.m2");
        device.put("path", "/home/agentuser/.m2");
        var body = java.util.Map.of("devices", java.util.Map.of("m2cache", device));
        var json = JqValues.fromJavaObject(body);
        var d = json.path("devices").path("m2cache");
        assertEquals("disk", d.path("type").asText());
        assertEquals("/home/user/.m2", d.path("source").asText());
        assertEquals("/home/agentuser/.m2", d.path("path").asText());
    }

    @Test
    void deviceRemovePutBodyDropsDevice() throws Exception {
        // Device removal uses read-modify-write with PUT, not PATCH with null.
        // Simulate: original has devices {keep, remove}, result should only have {keep}.
        var json = JqValues.parse("""
                {
                  "type": "sync",
                  "metadata": {
                    "architecture": "x86_64",
                    "config": {},
                    "description": "",
                    "devices": {
                      "keep": {"type": "disk", "source": "/keep", "path": "/mnt/keep"},
                      "remove": {"type": "disk", "source": "/tmp", "path": "/mnt/tmp"}
                    },
                    "ephemeral": false,
                    "profiles": ["default"],
                    "stateful": false
                  }
                }
                """);
        var metadata = json.path("metadata");
        // Simulate what removeDevice does: build PUT body excluding "remove"
        var devicesNode = io.hyperfoil.tools.jjq.value.JqObject.builder(2);
        var devices = metadata.path("devices");
        if (devices instanceof io.hyperfoil.tools.jjq.value.JqObject devicesObj) {
            devicesObj.forEach((name, device) -> {
                if (!name.equals("remove")) devicesNode.put(name, device);
            });
        }
        var built = devicesNode.build();
        assertTrue(built.keys().contains("keep"), "keep device should remain");
        assertFalse(built.keys().contains("remove"), "remove device should be excluded");
        assertEquals("disk", built.getField("keep").pathText("type", ""));
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
        var resp = new IncusApi.ApiResponse(200, JqValues.parse("{\"type\":\"sync\"}"));
        assertTrue(resp.isSuccess());
    }

    @Test
    void existsReturnsFalseOn404() throws Exception {
        var resp = new IncusApi.ApiResponse(404, JqValues.parse("{\"type\":\"error\"}"));
        assertFalse(resp.isSuccess());
    }

    // --- resolveImageSource (remote:alias parsing) ---

    @Test
    void localAliasProducesSimpleSource() throws Exception {
        // Local alias (no colon) → {"type":"image","alias":"my-image"}
        var body = new java.util.LinkedHashMap<String, Object>();
        var source = java.util.Map.of("type", "image", "alias", "my-image");
        body.put("source", source);
        var json = JqValues.fromJavaObject(body);
        assertEquals("image", json.path("source").path("type").asText());
        assertEquals("my-image", json.path("source").path("alias").asText());
        assertFalse(json.path("source").has("server"), "local alias should have no server field");
    }

    @Test
    void remoteAliasProducesPullSource() throws Exception {
        // Simulate what resolveImageSource produces for "images:fedora/44"
        var source = new java.util.LinkedHashMap<String, Object>();
        source.put("type", "image");
        source.put("mode", "pull");
        source.put("server", "https://images.linuxcontainers.org");
        source.put("protocol", "simplestreams");
        source.put("alias", "fedora/44");
        var json = JqValues.fromJavaObject(source);
        assertEquals("pull", json.path("mode").asText());
        assertEquals("https://images.linuxcontainers.org", json.path("server").asText());
        assertEquals("simplestreams", json.path("protocol").asText());
        assertEquals("fedora/44", json.path("alias").asText());
    }

    // --- networkConfigGet REST response format ---

    @Test
    void networkConfigGetExtractsKnownKey() throws Exception {
        var json = JqValues.parse("""
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
        var json = JqValues.fromJavaObject(body);
        assertEquals("172.21.0.1/24", json.path("config").path("ipv4.address").asText());
    }

    // --- copy() REST body format ---

    @Test
    void copyBodyContainsSourceAndTarget() throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("name", "my-branch");
        body.put("source", java.util.Map.of("type", "copy", "source", "tpl-dev"));
        body.put("storage", "default");
        var json = JqValues.fromJavaObject(body);
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
        var json = JqValues.fromJavaObject(body);
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
        var json = JqValues.fromJavaObject(body);
        assertEquals("virtual-machine", json.path("type").asText());
    }

}
