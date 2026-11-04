package dev.incusspawn.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dev.incusspawn.ssh.TestHostKeys.blob;
import static dev.incusspawn.ssh.TestHostKeys.key;
import static dev.incusspawn.ssh.TestHostKeys.printed;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The guest reports its SSH host keys on the readiness probe's answer (#1863), and the guest is
 * not trusted with the host's files: only a checked type and blob are ever taken from it.
 */
class HostKeysTest {

    private static final HostKeys.HostKey ED25519 = key("ssh-ed25519", 1);
    private static final HostKeys.HostKey ECDSA = key("ecdsa-sha2-nistp256", 2);

    @Test
    void onlyThePrefixedLinesAreKeys() {
        var answer = "Welcome!\n" + printed(ED25519) + "\nssh-ed25519 " + ED25519.blob() + " unprefixed\n" + printed(ECDSA);

        assertEquals(List.of(ED25519, ECDSA), HostKeys.parse(answer));
        assertEquals(List.of(), HostKeys.parse(null));
        assertEquals(List.of(), HostKeys.parse(""));
    }

    @Test
    void aBlobMustBeTheKeyItsLineNames() {
        // An ecdsa blob passed off as ed25519, and base64 that is no key at all
        assertEquals(List.of(), HostKeys.parse(HostKeys.PREFIX + "ssh-ed25519 " + ECDSA.blob()));
        assertEquals(List.of(), HostKeys.parse(HostKeys.PREFIX + "ssh-ed25519 AAAA"));
        assertEquals(List.of(), HostKeys.parse(HostKeys.PREFIX + "ssh-ed25519 not*base64"));
    }

    @Test
    void aGuestCannotWriteAnotherHostsLine() {
        // A third field, or a host list in the type, would become another host's entry if copied
        assertEquals(List.of(), HostKeys.parse(printed(ED25519) + " other-instance"));
        assertEquals(List.of(), HostKeys.parse(HostKeys.PREFIX + "other,ssh-ed25519 " + blob("other,ssh-ed25519", 1)));
        assertEquals(List.of(), HostKeys.parse(HostKeys.PREFIX + "ssh-ed25519\t" + ED25519.blob()));
    }

    @Test
    void aKeyPrintedTwiceIsOneKey() {
        assertEquals(List.of(ED25519), HostKeys.parse(printed(ED25519) + "\n" + printed(ED25519)));
    }

    @Test
    void aGuestPrintingMoreKeysThanSshdHasIsNotBelieved() {
        var answer = new ArrayList<String>();
        for (int i = 0; i <= HostKeys.MAX_KEYS; i++) answer.add(printed(key("ssh-ed25519", i * 40)));
        assertEquals(List.of(), HostKeys.parse(String.join("\n", answer)));
        assertEquals(HostKeys.MAX_KEYS, HostKeys.parse(String.join("\n", answer.subList(0, HostKeys.MAX_KEYS))).size());
    }

    @Test
    void theGuestScriptPrintsEachKeyWithoutItsComment(@TempDir Path etc) throws Exception {
        Files.writeString(etc.resolve("ssh_host_ed25519_key.pub"), "ssh-ed25519 " + ED25519.blob() + " root@box\n");
        Files.writeString(etc.resolve("ssh_host_ecdsa_key.pub"), "ecdsa-sha2-nistp256 " + ECDSA.blob() + "\n");
        Files.writeString(etc.resolve("elsewhere.pub"), "ssh-rsa " + blob("ssh-rsa", 3) + "\n");
        Files.createSymbolicLink(etc.resolve("ssh_host_rsa_key.pub"), etc.resolve("elsewhere.pub"));

        var answer = run(HostKeys.GUEST_SCRIPT.replace("/etc/ssh/", etc + "/"));

        assertEquals(List.of(ECDSA, ED25519), HostKeys.parse(answer), answer);
        assertFalse(answer.contains("root@box"), answer);
        assertFalse(answer.contains("ssh-rsa"), "a symlinked key file is skipped: " + answer);
    }

    /** A hand-written key file may lack its final newline, and an empty one must not repeat the last key. */
    @Test
    void theGuestScriptReadsAKeyWithoutAFinalNewline(@TempDir Path etc) throws Exception {
        Files.writeString(etc.resolve("ssh_host_ecdsa_key.pub"), "ecdsa-sha2-nistp256 " + ECDSA.blob());
        Files.writeString(etc.resolve("ssh_host_ed25519_key.pub"), "ssh-ed25519 " + ED25519.blob() + " root@box");
        Files.writeString(etc.resolve("ssh_host_rsa_key.pub"), "");

        var answer = run(HostKeys.GUEST_SCRIPT.replace("/etc/ssh/", etc + "/"));

        assertEquals(List.of(ECDSA, ED25519), HostKeys.parse(answer), answer);
        assertEquals(2, answer.lines().count(), answer);
    }

    @Test
    void theGuestScriptSucceedsAndPrintsNothingWithoutKeys(@TempDir Path etc) throws Exception {
        assertEquals("", run(HostKeys.GUEST_SCRIPT.replace("/etc/ssh/", etc + "/")));
    }

    private static String run(String script) throws Exception {
        var sh = new ProcessBuilder("sh", "-c", script).start();
        assertTrue(sh.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, sh.exitValue(), "a readiness probe's script must not fail");
        return new String(sh.getInputStream().readAllBytes());
    }
}
