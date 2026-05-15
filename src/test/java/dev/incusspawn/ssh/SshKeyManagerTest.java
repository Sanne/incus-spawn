package dev.incusspawn.ssh;

import dev.incusspawn.incus.IncusClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SshKeyManagerTest {

    @TempDir
    Path tempDir;

    private String originalHome;

    @BeforeEach
    void setUp() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void existsReturnsFalseWhenNoKeys() {
        assertFalse(SshKeyManager.exists());
    }

    @Test
    void ensureKeyPairCreatesFiles() {
        SshKeyManager.ensureKeyPairExists();

        var keyFile = tempDir.resolve(".config/incus-spawn/ssh/id_ed25519");
        var pubFile = tempDir.resolve(".config/incus-spawn/ssh/id_ed25519.pub");
        assertTrue(Files.exists(keyFile), "Private key should be created");
        assertTrue(Files.exists(pubFile), "Public key should be created");
        assertTrue(SshKeyManager.exists());
    }

    @Test
    void ensureKeyPairIsIdempotent() throws IOException {
        SshKeyManager.ensureKeyPairExists();
        var keyFile = tempDir.resolve(".config/incus-spawn/ssh/id_ed25519");
        var firstContent = Files.readString(keyFile);

        SshKeyManager.ensureKeyPairExists();
        var secondContent = Files.readString(keyFile);

        assertEquals(firstContent, secondContent, "Key should not be regenerated");
    }

    @Test
    void publicKeyContentReturnsKeyData() {
        SshKeyManager.ensureKeyPairExists();
        var content = SshKeyManager.publicKeyContent();
        assertTrue(content.startsWith("ssh-ed25519 "), "Should be an ed25519 public key");
        assertTrue(content.contains("incus-spawn managed key"), "Should contain the comment");
    }

    @Test
    void addHostEntryCreatesConfigFile() {
        SshKeyManager.addHostEntry("test-instance", "10.0.0.1");

        var configFile = tempDir.resolve(".config/incus-spawn/ssh/config");
        assertTrue(Files.exists(configFile));
        var content = assertDoesNotThrow(() -> Files.readString(configFile));
        assertTrue(content.contains("Host test-instance"));
        assertTrue(content.contains("HostName 10.0.0.1"));
        assertTrue(content.contains("User agentuser"));
        assertTrue(content.contains("IdentityFile ~/.config/incus-spawn/ssh/id_ed25519"));
        assertTrue(content.contains("IdentitiesOnly yes"));
        assertTrue(content.contains("UserKnownHostsFile ~/.config/incus-spawn/ssh/known_hosts"));
        assertTrue(content.contains("StrictHostKeyChecking yes"));
    }

    @Test
    void addHostEntryReplacesExistingEntry() {
        SshKeyManager.addHostEntry("test-instance", "10.0.0.1");
        SshKeyManager.addHostEntry("test-instance", "10.0.0.2");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertFalse(content.contains("10.0.0.1"), "Old IP should be removed");
        assertTrue(content.contains("10.0.0.2"), "New IP should be present");
        assertEquals(1, content.lines().filter(l -> l.strip().startsWith("Host ")).count(),
                "Should have exactly one Host block");
    }

    @Test
    void addMultipleHostEntries() {
        SshKeyManager.addHostEntry("instance-a", "10.0.0.1");
        SshKeyManager.addHostEntry("instance-b", "10.0.0.2");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host instance-a"));
        assertTrue(content.contains("Host instance-b"));
        assertEquals(2, content.lines().filter(l -> l.strip().startsWith("Host ")).count());
    }

    @Test
    void removeHostEntry() {
        SshKeyManager.addHostEntry("keep-me", "10.0.0.1");
        SshKeyManager.addHostEntry("remove-me", "10.0.0.2");
        SshKeyManager.removeHostEntry("remove-me");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host keep-me"));
        assertFalse(content.contains("Host remove-me"));
    }

    @Test
    void removeHostEntryNonexistent() {
        SshKeyManager.addHostEntry("keep-me", "10.0.0.1");
        SshKeyManager.removeHostEntry("nonexistent");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host keep-me"));
    }

    @Test
    void findIpForInstance() {
        SshKeyManager.addHostEntry("my-instance", "10.0.0.42");
        assertEquals("10.0.0.42", SshKeyManager.findIpForInstance("my-instance"));
        assertNull(SshKeyManager.findIpForInstance("nonexistent"));
    }

    @Test
    void addKnownHostCreatesFile() {
        SshKeyManager.addKnownHost("my-instance", "10.0.0.1", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA...");

        var knownHosts = tempDir.resolve(".config/incus-spawn/ssh/known_hosts");
        assertTrue(Files.exists(knownHosts));
        var content = assertDoesNotThrow(() -> Files.readString(knownHosts));
        assertTrue(content.contains("my-instance,10.0.0.1 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA..."));

        var stdKnownHosts = tempDir.resolve(".ssh/known_hosts");
        assertTrue(Files.exists(stdKnownHosts), "Standard known_hosts should also be written");
        var stdContent = assertDoesNotThrow(() -> Files.readString(stdKnownHosts));
        assertTrue(stdContent.contains("my-instance,10.0.0.1 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA..."));
        assertTrue(stdContent.contains("# incus-spawn"), "Entry should be tagged");
    }

    @Test
    void addKnownHostReplacesExistingIp() {
        SshKeyManager.addKnownHost("my-instance", "10.0.0.1", "ssh-ed25519 OLD_KEY");
        SshKeyManager.addKnownHost("my-instance", "10.0.0.1", "ssh-ed25519 NEW_KEY");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/known_hosts")));
        assertFalse(content.contains("OLD_KEY"));
        assertTrue(content.contains("NEW_KEY"));
        assertEquals(1, content.lines().filter(l -> l.contains("10.0.0.1")).count());
    }

    @Test
    void addKnownHostMultipleIps() {
        SshKeyManager.addKnownHost("instance-a", "10.0.0.1", "ssh-ed25519 KEY_A");
        SshKeyManager.addKnownHost("instance-b", "10.0.0.2", "ssh-ed25519 KEY_B");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/known_hosts")));
        assertTrue(content.contains("instance-a,10.0.0.1 ssh-ed25519 KEY_A"));
        assertTrue(content.contains("instance-b,10.0.0.2 ssh-ed25519 KEY_B"));
    }

    @Test
    void removeKnownHost() {
        SshKeyManager.addKnownHost("instance-a", "10.0.0.1", "ssh-ed25519 KEY_A");
        SshKeyManager.addKnownHost("instance-b", "10.0.0.2", "ssh-ed25519 KEY_B");
        SshKeyManager.removeKnownHost("10.0.0.1");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/known_hosts")));
        assertFalse(content.contains("10.0.0.1"));
        assertTrue(content.contains("10.0.0.2"));

        var stdContent = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".ssh/known_hosts")));
        assertFalse(stdContent.contains("10.0.0.1"), "Standard known_hosts should also be cleaned");
        assertTrue(stdContent.contains("10.0.0.2"));
    }

    @Test
    void removeKnownHostNonexistentFile() {
        assertDoesNotThrow(() -> SshKeyManager.removeKnownHost("10.0.0.1"));
    }

    @Test
    void addKnownHostSkipsStandardFileWhenForeignEntryExists() throws IOException {
        var stdKnownHosts = tempDir.resolve(".ssh/known_hosts");
        Files.createDirectories(stdKnownHosts.getParent());
        Files.writeString(stdKnownHosts, "10.0.0.1 ssh-ed25519 REAL_SERVER_KEY\n");

        SshKeyManager.addKnownHost("my-instance", "10.0.0.1", "ssh-ed25519 CONTAINER_KEY");

        var managed = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/known_hosts")));
        assertTrue(managed.contains("CONTAINER_KEY"), "Managed file should be written");

        var std = Files.readString(stdKnownHosts);
        assertTrue(std.contains("REAL_SERVER_KEY"), "Foreign entry must not be removed");
        assertFalse(std.contains("CONTAINER_KEY"), "Should not write when foreign entry exists");
    }

    @Test
    void addKnownHostReplacesOurEntryInStandardFile() throws IOException {
        var stdKnownHosts = tempDir.resolve(".ssh/known_hosts");
        Files.createDirectories(stdKnownHosts.getParent());
        Files.writeString(stdKnownHosts, "old-instance,10.0.0.1 ssh-ed25519 OLD_KEY # incus-spawn\n");

        SshKeyManager.addKnownHost("new-instance", "10.0.0.1", "ssh-ed25519 NEW_KEY");

        var std = Files.readString(stdKnownHosts);
        assertFalse(std.contains("OLD_KEY"), "Our old entry should be replaced");
        assertTrue(std.contains("new-instance,10.0.0.1 ssh-ed25519 NEW_KEY"));
    }

    @Test
    void addKnownHostPreservesCommaFormatForeignEntry() throws IOException {
        var stdKnownHosts = tempDir.resolve(".ssh/known_hosts");
        Files.createDirectories(stdKnownHosts.getParent());
        Files.writeString(stdKnownHosts, "myserver.example.com,10.0.0.1 ssh-ed25519 REAL_KEY\n");

        SshKeyManager.addKnownHost("my-instance", "10.0.0.1", "ssh-ed25519 CONTAINER_KEY");

        var std = Files.readString(stdKnownHosts);
        assertTrue(std.contains("REAL_KEY"), "Comma-format foreign entry must not be removed");
        assertFalse(std.contains("CONTAINER_KEY"), "Should not write when foreign entry exists");
    }

    @Test
    void removeKnownHostPreservesForeignEntriesInStandardFile() throws IOException {
        var stdKnownHosts = tempDir.resolve(".ssh/known_hosts");
        Files.createDirectories(stdKnownHosts.getParent());
        Files.writeString(stdKnownHosts, "10.0.0.1 ssh-ed25519 REAL_SERVER_KEY\n");

        SshKeyManager.removeKnownHost("10.0.0.1");

        var std = Files.readString(stdKnownHosts);
        assertTrue(std.contains("REAL_SERVER_KEY"), "Foreign entry must not be removed");
    }

    @Test
    void ensureSshConfigIncludeCreatesConfigIfMissing() {
        assertTrue(SshKeyManager.ensureSshConfigInclude());

        var sshConfig = tempDir.resolve(".ssh/config");
        assertTrue(Files.exists(sshConfig));
        var content = assertDoesNotThrow(() -> Files.readString(sshConfig));
        assertTrue(content.contains("Include ~/.config/incus-spawn/ssh/config"));
    }

    @Test
    void ensureSshConfigIncludePrependsToExistingConfig() throws IOException {
        var sshDir = tempDir.resolve(".ssh");
        Files.createDirectories(sshDir);
        var sshConfig = sshDir.resolve("config");
        Files.writeString(sshConfig, "Host existing\n    HostName 1.2.3.4\n");

        assertTrue(SshKeyManager.ensureSshConfigInclude());

        var content = Files.readString(sshConfig);
        var lines = content.lines().toList();
        assertEquals("Include ~/.config/incus-spawn/ssh/config", lines.get(0),
                "Include should be the first line");
        assertTrue(content.contains("Host existing"), "Existing content should be preserved");
    }

    @Test
    void ensureSshConfigIncludeIsIdempotent() throws IOException {
        SshKeyManager.ensureSshConfigInclude();
        var sshConfig = tempDir.resolve(".ssh/config");
        var firstContent = Files.readString(sshConfig);

        SshKeyManager.ensureSshConfigInclude();
        var secondContent = Files.readString(sshConfig);

        assertEquals(firstContent, secondContent, "Should not duplicate the Include line");
    }

    @Test
    void fullCleanupFlow() {
        SshKeyManager.addHostEntry("my-instance", "10.0.0.5");
        SshKeyManager.addKnownHost("my-instance", "10.0.0.5", "ssh-ed25519 SOME_KEY");
        SshKeyManager.addHostEntry("other-instance", "10.0.0.6");
        SshKeyManager.addKnownHost("other-instance", "10.0.0.6", "ssh-ed25519 OTHER_KEY");

        SshKeyManager.cleanupInstance("my-instance");

        var config = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertFalse(config.contains("Host my-instance"), "Host block should be removed");
        assertTrue(config.contains("Host other-instance"), "Other host should remain");

        var knownHosts = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/known_hosts")));
        assertFalse(knownHosts.contains("10.0.0.5"), "Known host entry should be removed");
        assertTrue(knownHosts.contains("10.0.0.6"), "Other known host should remain");

        var stdKnownHosts = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".ssh/known_hosts")));
        assertFalse(stdKnownHosts.contains("10.0.0.5"), "Standard known_hosts entry should be removed");
        assertTrue(stdKnownHosts.contains("10.0.0.6"), "Other standard known_hosts entry should remain");
    }

    @Test
    void cleanupNonexistentInstanceIsNoOp() {
        assertDoesNotThrow(() -> SshKeyManager.cleanupInstance("nonexistent"));
    }

    // --- harvestHostKey tests using a stub IncusClient ---

    /**
     * Minimal IncusClient stub that returns canned responses for shellExec calls.
     */
    static class StubIncusClient extends IncusClient {
        private final Map<String, IncusClient.ExecResult> responses = new HashMap<>();

        void stubShellExec(String key, int exitCode, String stdout) {
            responses.put(key, new IncusClient.ExecResult(exitCode, stdout, ""));
        }

        @Override
        public IncusClient.ExecResult shellExec(String container, String... command) {
            var key = container + ":" + String.join(" ", command);
            var result = responses.get(key);
            if (result != null) return result;
            return new IncusClient.ExecResult(1, "", "not stubbed: " + key);
        }
    }

    @Test
    void harvestHostKeyWritesConfigAndKnownHosts() {
        var incus = new StubIncusClient();
        incus.stubShellExec("test-vm:hostname -I", 0, "10.0.0.99 ");
        incus.stubShellExec("test-vm:sh -c rm -f /etc/ssh/ssh_host_*_key /etc/ssh/ssh_host_*_key.pub && ssh-keygen -A", 0, "");
        incus.stubShellExec("test-vm:systemctl restart sshd", 0, "");
        incus.stubShellExec("test-vm:cat /etc/ssh/ssh_host_ed25519_key.pub", 0,
                "ssh-ed25519 AAAAC3NzTestKey root@test-vm\n");

        boolean result = SshKeyManager.harvestHostKey(incus, "test-vm");

        assertTrue(result, "Should succeed");
        assertEquals("10.0.0.99", SshKeyManager.findIpForInstance("test-vm"));
        var knownHosts = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/known_hosts")));
        assertTrue(knownHosts.contains("test-vm,10.0.0.99 ssh-ed25519 AAAAC3NzTestKey"));
    }

    @Test
    void harvestHostKeyFallsBackToEcdsaKey() {
        var incus = new StubIncusClient();
        incus.stubShellExec("test-vm:hostname -I", 0, "10.0.0.50 ");
        incus.stubShellExec("test-vm:sh -c rm -f /etc/ssh/ssh_host_*_key /etc/ssh/ssh_host_*_key.pub && ssh-keygen -A", 0, "");
        incus.stubShellExec("test-vm:systemctl restart sshd", 0, "");
        // ed25519 not available, ecdsa is
        incus.stubShellExec("test-vm:cat /etc/ssh/ssh_host_ecdsa_key.pub", 0,
                "ecdsa-sha2-nistp256 AAAAE2VjZHNhEcdsaKey root@test-vm\n");

        boolean result = SshKeyManager.harvestHostKey(incus, "test-vm");

        assertTrue(result);
        var knownHosts = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/known_hosts")));
        assertTrue(knownHosts.contains("test-vm,10.0.0.50 ecdsa-sha2-nistp256 AAAAE2VjZHNhEcdsaKey"));
    }

    @Test
    void harvestHostKeyReturnsFalseWhenNoIp() {
        var incus = new StubIncusClient();
        incus.stubShellExec("test-vm:hostname -I", 1, "");

        assertFalse(SshKeyManager.harvestHostKey(incus, "test-vm"));
    }

    @Test
    void harvestHostKeyReturnsFalseWhenNoHostKeys() {
        var incus = new StubIncusClient();
        incus.stubShellExec("test-vm:hostname -I", 0, "10.0.0.1 ");
        incus.stubShellExec("test-vm:sh -c rm -f /etc/ssh/ssh_host_*_key /etc/ssh/ssh_host_*_key.pub && ssh-keygen -A", 0, "");
        incus.stubShellExec("test-vm:systemctl restart sshd", 0, "");

        assertFalse(SshKeyManager.harvestHostKey(incus, "test-vm"));
    }

    @Test
    void harvestHostKeyReturnsFalseWhenRegenFails() {
        var incus = new StubIncusClient();
        incus.stubShellExec("test-vm:hostname -I", 0, "10.0.0.1 ");
        incus.stubShellExec("test-vm:sh -c rm -f /etc/ssh/ssh_host_*_key /etc/ssh/ssh_host_*_key.pub && ssh-keygen -A", 1, "");

        assertFalse(SshKeyManager.harvestHostKey(incus, "test-vm"));
    }
}
