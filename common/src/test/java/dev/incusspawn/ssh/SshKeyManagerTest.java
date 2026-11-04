package dev.incusspawn.ssh;

import dev.incusspawn.util.Output;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.HostLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        SshKeyManager.addHostEntry("test-instance");

        var configFile = tempDir.resolve(".config/incus-spawn/ssh/config");
        assertTrue(Files.exists(configFile));
        var content = assertDoesNotThrow(() -> Files.readString(configFile));
        assertTrue(content.contains("Host test-instance"));
        assertTrue(content.contains("ProxyCommand"));
        assertTrue(content.contains("ssh-proxy test-instance"));
        assertTrue(content.contains("User agentuser"));
        assertTrue(content.contains("IdentityFile ~/.config/incus-spawn/ssh/id_ed25519"));
        assertTrue(content.contains("IdentitiesOnly yes"));
        assertTrue(content.contains("StrictHostKeyChecking no"));
        // The key a strict client checks is found by the instance's name, in isx's pins (#1863)
        assertTrue(content.contains("    HostKeyAlias test-instance\n"), content);
        assertTrue(content.contains("    HashKnownHosts no\n"), content);
        assertTrue(content.contains("    UserKnownHostsFile \"" + knownHosts() + "\"\n"), content);
        assertFalse(content.contains("/dev/null"), content);
    }

    private Path knownHosts() {
        return tempDir.resolve(".config/incus-spawn/ssh/known_hosts").toAbsolutePath();
    }

    private static final HostKeys.HostKey ED25519 = TestHostKeys.key("ssh-ed25519", 1);
    private static final HostKeys.HostKey ECDSA = TestHostKeys.key("ecdsa-sha2-nistp256", 2);
    private static final HostKeys.HostKey OTHER = TestHostKeys.key("ssh-ed25519", 3);

    private static String pin(String name, String ip, HostKeys.HostKey key) {
        return name + (ip.isEmpty() ? "" : "," + ip) + " " + key.type() + " " + key.blob() + " isx:" + name;
    }

    @Test
    void aBlockWrittenBeforeHostKeysWerePinnedIsRewritten() throws IOException {
        // Every block isx wrote before #1863 sends the key to /dev/null: a start must replace it
        var config = tempDir.resolve(".config/incus-spawn/ssh/config");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "Host dev\n    Hostname 10.0.0.5\n    StrictHostKeyChecking no\n"
                + "    UserKnownHostsFile /dev/null\n");

        assertFalse(SshKeyManager.hasHostEntry("dev", "10.0.0.5"));
        SshKeyManager.addHostEntry("dev", "10.0.0.5");
        assertTrue(SshKeyManager.hasHostEntry("dev", "10.0.0.5"));
    }

    @Test
    void hostKeysArePinnedUnderTheNameAndTheAddress() throws IOException {
        assertTrue(SshKeyManager.pinHostKeys("dev", "10.0.0.5", List.of(ED25519, ECDSA)));

        assertEquals(List.of(pin("dev", "10.0.0.5", ED25519), pin("dev", "10.0.0.5", ECDSA)),
                Files.readAllLines(knownHosts()));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(knownHosts())));
        assertTrue(SshKeyManager.hasHostKeys("dev"));
        assertFalse(SshKeyManager.hasHostKeys("de"));
    }

    @Test
    void withoutAnAddressTheKeyIsPinnedUnderTheNameAlone() throws IOException {
        SshKeyManager.pinHostKeys("dev", "", List.of(ED25519));
        SshKeyManager.pinHostKeys("box", "not-an-address", List.of(ECDSA));

        assertEquals(List.of(pin("dev", "", ED25519), pin("box", "", ECDSA)), Files.readAllLines(knownHosts()));
    }

    @Test
    void noKeysPinNothing() {
        assertFalse(SshKeyManager.pinHostKeys("dev", "10.0.0.5", List.of()));
        assertFalse(Files.exists(knownHosts()));
        assertFalse(SshKeyManager.hasHostKeys("dev"));
    }

    @Test
    void aChangedKeyReplacesTheInstancesLinesWhereTheyStood() throws IOException {
        // In place, so two instances' starts never move each other's lines and rewrite the file
        SshKeyManager.pinHostKeys("a", "10.0.0.1", List.of(ED25519, ECDSA));
        SshKeyManager.pinHostKeys("b", "10.0.0.2", List.of(ED25519));

        SshKeyManager.pinHostKeys("a", "10.0.0.1", List.of(OTHER));

        assertEquals(List.of(pin("a", "10.0.0.1", OTHER), pin("b", "10.0.0.2", ED25519)),
                Files.readAllLines(knownHosts()));
    }

    @Test
    void aCurrentPinIsNotRewritten() throws IOException {
        SshKeyManager.pinHostKeys("a", "10.0.0.1", List.of(ED25519));
        SshKeyManager.pinHostKeys("b", "10.0.0.2", List.of(ECDSA));
        var before = FileTime.fromMillis(0);
        Files.setLastModifiedTime(knownHosts(), before);

        assertTrue(SshKeyManager.pinHostKeys("a", "10.0.0.1", List.of(ED25519)));

        assertEquals(before, Files.getLastModifiedTime(knownHosts()), "the pins must not be rewritten");
    }

    @Test
    void aRecycledAddressLeavesItsEarlierTenantPinnedByName() throws IOException {
        // The address is the instance's only while it holds it: a stopped instance whose address
        // went to another keeps its pin by name, and the address now names the new holder's key
        SshKeyManager.pinHostKeys("old", "10.0.0.7", List.of(ED25519));

        SshKeyManager.pinHostKeys("new", "10.0.0.7", List.of(OTHER));

        assertEquals(List.of(pin("old", "", ED25519), pin("new", "10.0.0.7", OTHER)),
                Files.readAllLines(knownHosts()));
    }

    @Test
    void linesIsxDoesNotManageAreLeftAsTheyAre() throws IOException {
        Files.createDirectories(knownHosts().getParent());
        var foreign = List.of("# a comment naming dev", "@revoked dev " + OTHER.type() + " " + OTHER.blob(),
                "|1|c2FsdA==|aGFzaA== " + OTHER.type() + " " + OTHER.blob(), "");
        Files.write(knownHosts(), foreign);

        SshKeyManager.pinHostKeys("dev", "10.0.0.5", List.of(ED25519));
        SshKeyManager.cleanupInstance("dev");

        assertEquals(foreign, Files.readAllLines(knownHosts()));
    }

    @Test
    void aLineSshAddedByTheAliasIsReplacedByThePin() throws IOException {
        // Under StrictHostKeyChecking no, ssh records a key it did not know, by the alias
        Files.createDirectories(knownHosts().getParent());
        Files.writeString(knownHosts(), "dev " + OTHER.type() + " " + OTHER.blob() + "\n");

        SshKeyManager.pinHostKeys("dev", "10.0.0.5", List.of(ED25519));

        assertEquals(List.of(pin("dev", "10.0.0.5", ED25519)), Files.readAllLines(knownHosts()));
    }

    @Test
    void destroyingAnInstanceRemovesItsPinsOnly() throws IOException {
        SshKeyManager.addHostEntry("gone", "10.0.0.1");
        SshKeyManager.pinHostKeys("gone", "10.0.0.1", List.of(ED25519));
        SshKeyManager.pinHostKeys("kept", "10.0.0.2", List.of(ECDSA));

        SshKeyManager.cleanupInstance("gone");

        assertFalse(SshKeyManager.hasHostKeys("gone"));
        assertEquals(List.of(pin("kept", "10.0.0.2", ECDSA)), Files.readAllLines(knownHosts()));
    }

    @Test
    void concurrentPinsKeepEachOthersLines() throws Exception {
        whileFirstWriterHoldsConfig(() -> SshKeyManager.pinHostKeys("first", "10.0.0.1", List.of(ED25519)),
                () -> SshKeyManager.pinHostKeys("second", "10.0.0.2", List.of(ECDSA)));

        assertTrue(SshKeyManager.hasHostKeys("first"));
        assertTrue(SshKeyManager.hasHostKeys("second"));
    }

    @Test
    void onlyTheProxyCommandHandedToProgramsStartsAStoppedInstance() {
        assertEquals("\"/usr/bin/isx\" ssh-proxy dev", SshKeyManager.proxyCommand("/usr/bin/isx", "dev", false));
        assertEquals("\"/usr/bin/isx\" ssh-proxy --start dev", SshKeyManager.proxyCommand("/usr/bin/isx", "dev", true));
        SshKeyManager.addHostEntry("test-instance");
        var content = assertDoesNotThrow(() -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("ssh-proxy test-instance"), content);
        assertFalse(content.contains("--start"), "a plain ssh never starts an instance");
    }

    @Test
    void aHostnameAfterTheBlockEndsIsNotTheBlocks() throws IOException {
        // An unindented line ends a Host block, as removing one reads it: a later top-level
        // Hostname (or anything under a Match) is not this instance's address
        SshKeyManager.addHostEntry("test-instance");
        var config = tempDir.resolve(".config/incus-spawn/ssh/config");
        Files.writeString(config, Files.readString(config) + "Match all\nHostname 10.0.0.42\n");

        assertTrue(SshKeyManager.hasHostEntry("test-instance", ""));
        assertFalse(SshKeyManager.hasHostEntry("test-instance", "10.0.0.42"));

        SshKeyManager.addHostEntry("test-instance", "10.0.0.42");
        assertTrue(SshKeyManager.hasHostEntry("test-instance", "10.0.0.42"));
    }

    @Test
    void addHostEntryWithHostname() {
        SshKeyManager.addHostEntry("test-instance", "10.0.0.42");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host test-instance"));
        assertTrue(content.contains("Hostname 10.0.0.42"));
        assertTrue(content.contains("ProxyCommand"));
    }

    @Test
    void addHostEntryWithNullHostnameOmitsDirective() {
        SshKeyManager.addHostEntry("test-instance", null);

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host test-instance"));
        assertFalse(content.contains("Hostname"));
    }

    @Test
    void addHostEntryReplacesExistingEntry() {
        SshKeyManager.addHostEntry("test-instance");
        SshKeyManager.addHostEntry("test-instance");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertEquals(1, content.lines().filter(l -> l.strip().startsWith("Host ")).count(),
                "Should have exactly one Host block");
    }

    @Test
    void addMultipleHostEntries() {
        SshKeyManager.addHostEntry("instance-a");
        SshKeyManager.addHostEntry("instance-b");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host instance-a"));
        assertTrue(content.contains("Host instance-b"));
        assertEquals(2, content.lines().filter(l -> l.strip().startsWith("Host ")).count());
    }

    @Test
    void removeHostEntry() {
        SshKeyManager.addHostEntry("keep-me");
        SshKeyManager.addHostEntry("remove-me");
        SshKeyManager.removeHostEntry("remove-me");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host keep-me"));
        assertFalse(content.contains("Host remove-me"));
    }

    @Test
    void removeHostEntryNonexistent() {
        SshKeyManager.addHostEntry("keep-me");
        SshKeyManager.removeHostEntry("nonexistent");

        var content = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertTrue(content.contains("Host keep-me"));
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
        SshKeyManager.addHostEntry("my-instance");
        SshKeyManager.addHostEntry("other-instance");

        SshKeyManager.cleanupInstance("my-instance");

        var config = assertDoesNotThrow(
                () -> Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config")));
        assertFalse(config.contains("Host my-instance"), "Host block should be removed");
        assertTrue(config.contains("Host other-instance"), "Other host should remain");
    }

    @Test
    void cleanupNonexistentInstanceIsNoOp() {
        assertDoesNotThrow(() -> SshKeyManager.cleanupInstance("nonexistent"));
    }

    @Test
    void concurrentWritersKeepEachOthersHostBlock() throws Exception {
        whileFirstWriterHoldsConfig(() -> SshKeyManager.addHostEntry("first"),
                () -> SshKeyManager.addHostEntry("second"));

        var config = Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config"));
        assertTrue(config.contains("Host first"), config);
        assertTrue(config.contains("Host second"), config);
    }

    @Test
    void concurrentRemoveKeepsAnotherWritersHostBlock() throws Exception {
        SshKeyManager.addHostEntry("gone");
        whileFirstWriterHoldsConfig(() -> SshKeyManager.removeHostEntry("gone"),
                () -> SshKeyManager.addHostEntry("kept"));

        var config = Files.readString(tempDir.resolve(".config/incus-spawn/ssh/config"));
        assertFalse(config.contains("Host gone"), config);
        assertTrue(config.contains("Host kept"), config);
    }

    /** Runs {@code second} once {@code first} has read the config, before it writes it back. */
    private static void whileFirstWriterHoldsConfig(Runnable first, Runnable second) throws Exception {
        var secondThread = new Thread(second);
        // The second writer now writes, or waits for the first one to finish
        SshKeyManager.afterConfigRead = () -> {
            SshKeyManager.afterConfigRead = () -> {};
            secondThread.start();
            while (secondThread.isAlive() && !waitingForHostLock(secondThread)) {
                Thread.onSpinWait();
            }
        };
        try {
            first.run();
            secondThread.join();
        } finally {
            SshKeyManager.afterConfigRead = () -> {};
        }
    }

    private static boolean waitingForHostLock(Thread thread) {
        var state = thread.getState();
        return (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING)
                && Arrays.stream(thread.getStackTrace())
                        .anyMatch(frame -> frame.getClassName().equals(HostLock.class.getName()));
    }

    @Test
    void removingAnInstanceWithoutAHostBlockLeavesTheConfigAlone() throws IOException {
        SshKeyManager.addHostEntry("with-ssh");
        var config = tempDir.resolve(".config/incus-spawn/ssh/config");
        var before = FileTime.fromMillis(0);
        Files.setLastModifiedTime(config, before);

        SshKeyManager.cleanupInstance("without-ssh");

        assertEquals(before, Files.getLastModifiedTime(config), "the config must not be rewritten");
    }

    @Test
    void reAddingTheLastHostBlockUnchangedLeavesTheConfigAlone() throws IOException {
        SshKeyManager.addHostEntry("first", "10.0.0.1");
        SshKeyManager.addHostEntry("last", "10.0.0.2");
        var config = tempDir.resolve(".config/incus-spawn/ssh/config");
        var before = FileTime.fromMillis(0);
        Files.setLastModifiedTime(config, before);

        assertTrue(SshKeyManager.addHostEntry("last", "10.0.0.2"));

        assertEquals(before, Files.getLastModifiedTime(config), "the config must not be rewritten");
    }

    @Test
    void aHostBlockThatCannotBeWrittenIsReportedAsAWarning() throws IOException {
        // A file where the managed directory should be: the config cannot be created.
        Files.createDirectories(tempDir.resolve(".config/incus-spawn"));
        Files.writeString(tempDir.resolve(".config/incus-spawn/ssh"), "");
        var warnings = new ArrayList<String>();

        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(warnings::add))) {
            assertFalse(SshKeyManager.addHostEntry("test-instance"));
        }

        assertEquals(1, warnings.size(), "the TUI's warning log, not stderr, must get it: " + warnings);
        assertTrue(warnings.getFirst().startsWith("Failed to update SSH config"), warnings.getFirst());
    }

    @Test
    void anIncludeThatCannotBeWrittenIsReportedAsAWarning() throws IOException {
        // A file where ~/.ssh should be: ~/.ssh/config cannot be written.
        Files.writeString(tempDir.resolve(".ssh"), "");
        var warnings = new ArrayList<String>();

        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(warnings::add))) {
            assertFalse(SshKeyManager.ensureSshConfigInclude());
        }

        assertEquals(1, warnings.size(), "the TUI's warning log, not stderr, must get it: " + warnings);
        assertTrue(warnings.getFirst().startsWith("Failed to update ~/.ssh/config"), warnings.getFirst());
    }

    /** Read only: whether ~/.ssh/config includes isx's managed config before any Host block (#1720). */
    @Test
    void theIncludeIsCheckedWithoutWritingIt() throws IOException {
        var config = tempDir.resolve(".ssh/config");
        assertFalse(SshKeyManager.hasSshConfigInclude());
        assertFalse(Files.exists(config), "nothing written");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "Host other\n    User x\nInclude ~/.config/incus-spawn/ssh/config\n");
        assertFalse(SshKeyManager.hasSshConfigInclude(), "after a Host block it does not apply");
        Files.writeString(config, "# mine\n\nInclude ~/.config/incus-spawn/ssh/config\n\nHost other\n");
        assertTrue(SshKeyManager.hasSshConfigInclude());
    }
}
