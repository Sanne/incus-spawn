package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The per-start instance secret (#934): what it is, how it is checked, and how the guest gets it. */
class InstanceSecretTest {

    private static final List<ProofToken.Placeholder> PLACEHOLDERS =
            List.of(new ProofToken.Placeholder("GH_TOKEN", "gho_", "github"));

    @Test
    void everySecretIsNewAndOnlyItsHashIsRecorded() {
        var first = InstanceSecret.generate();
        var second = InstanceSecret.generate();
        assertNotEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{64}"), first);

        var recorded = InstanceSecret.sha256(first);
        assertNotEquals(first, recorded, "the host records a hash, never the secret");
        assertTrue(InstanceSecret.matches(first, recorded));
        assertFalse(InstanceSecret.matches(second, recorded));
    }

    @Test
    void nothingMissingEverMatches() {
        var recorded = InstanceSecret.sha256(InstanceSecret.generate());
        assertFalse(InstanceSecret.matches(null, recorded));
        assertFalse(InstanceSecret.matches("", recorded));
        assertFalse(InstanceSecret.matches(recorded, recorded), "the hash is not the secret");
        // An instance started before #934, or never by isx, has no secret: nothing passes
        assertFalse(InstanceSecret.matches(InstanceSecret.generate(), null));
        assertFalse(InstanceSecret.matches(InstanceSecret.generate(), ""));
        assertFalse(InstanceSecret.matches("", ""));
    }

    @Test
    void theGuestScriptOnlyEverCarriesASecret() {
        assertThrows(IllegalArgumentException.class, () -> InstanceSecret.guestEnv("x'; rm -rf / #", false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> InstanceSecret.guestEnv("", false, List.of()));
    }

    /**
     * Runs the script against a scratch directory standing in for the guest's root. Only the
     * group differs: the guest runs it as root, which may hand the file to the instance user's
     * group, and this test is not root.
     */
    @Test
    void theGuestScriptPutsTheSecretInPlaceForTheInstanceUser(@TempDir Path root) throws Exception {
        var secret = InstanceSecret.generate();
        var gid = new String(new ProcessBuilder("id", "-g").start().getInputStream().readAllBytes()).strip();
        var script = guestScriptUnder(root, "tmpfs /run tmpfs rw,nosuid,nodev 0 0\n")
                .replace("chgrp 1000 ", "chgrp " + gid + " ");

        // Twice: it rides in probes and setup scripts that are retried until one answers
        for (int i = 0; i < 2; i++) {
            var run = run(script + "\necho \"ready${ISX_INSTANCE_SECRET}${isx_secret}${ISX_PROOF_TOKENS}${isx_proofs}\"", secret);
            assertEquals("ready", new String(run.getInputStream().readAllBytes()).strip(),
                    "the secret does not outlive the script's own use of it");
            assertEquals(0, run.waitFor());
        }

        var file = root.resolve("run/isx/instance-secret");
        assertEquals(secret + "\n", Files.readString(file));
        assertEquals("r--r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                "readable by the instance user's group, by nobody else");
        assertFalse(Files.exists(root.resolve("run/isx/instance-secret.new")));
        var proofs = root.resolve("run/isx/proof-tokens");
        assertEquals("export " + InstanceSecret.FILE_ENV_VAR + "=" + file + "\n"
                        + "if [ -r " + proofs + " ]; then . " + proofs + "; fi\n",
                Files.readString(root.resolve("etc/profile.d/isx-instance-secret.sh")),
                "the profile names the file; the secret stays out of every process's environment");
        assertEquals(ProofToken.profile(secret, PLACEHOLDERS), Files.readString(proofs));
        assertEquals("r--r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(proofs)),
                "a proof opens a credential: the instance user's group only, like the secret");
    }

    /**
     * A login shell sources {@code isx-env.sh} first, then the secret's profile: this start's
     * proofs replace the build's static placeholders, and only the variables the build exported.
     */
    @Test
    void aLoginShellSeesThisStartsProofsInPlaceOfTheStaticPlaceholders(@TempDir Path root) throws Exception {
        var gid = new String(new ProcessBuilder("id", "-g").start().getInputStream().readAllBytes()).strip();
        var script = guestScriptUnder(root, "tmpfs /run tmpfs rw 0 0\n").replace("chgrp 1000 ", "chgrp " + gid + " ");
        var placeholders = List.of(
                new ProofToken.Placeholder("GH_TOKEN", "gho_", "github"),
                new ProofToken.Placeholder("ANTHROPIC_API_KEY", "sk-ant-", "claude"),
                new ProofToken.Placeholder("CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat01-", "claude"));
        var envFile = root.resolve("etc/profile.d/isx-env.sh");
        Files.writeString(envFile, "export GH_TOKEN=gho_placeholder\nexport CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-placeholder\n");

        for (var secret : List.of(InstanceSecret.generate(), InstanceSecret.generate())) {
            var start = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
            start.environment().putAll(InstanceSecret.guestEnv(secret, false, placeholders));
            assertEquals(0, start.start().waitFor());

            var login = new ProcessBuilder("sh", "-c", ". " + envFile + "; . " + root.resolve("etc/profile.d/isx-instance-secret.sh")
                    + "; echo \"$GH_TOKEN\"; echo \"$CLAUDE_CODE_OAUTH_TOKEN\"; echo \"${ANTHROPIC_API_KEY-unset}\"; env")
                    .redirectErrorStream(true);
            login.environment().clear();
            var lines = new String(login.start().getInputStream().readAllBytes()).lines().toList();
            assertEquals("gho_isx_" + ProofToken.derive(secret, "github"), lines.get(0));
            assertEquals("sk-ant-oat01-isx_" + ProofToken.derive(secret, "claude"), lines.get(1));
            assertEquals("unset", lines.get(2), "an OAuth-configured instance never grows an API key");
            assertTrue(lines.stream().noneMatch(l -> l.contains(secret)),
                    "the environment carries proofs, never the secret itself");
        }
    }

    /** The script with its guest paths moved under {@code root}, as if {@code mounts} were the guest's. */
    static String guestScriptUnder(Path root, String mounts) throws Exception {
        Files.createDirectories(root.resolve("etc/profile.d"));
        Files.writeString(root.resolve("mounts"), mounts);
        return InstanceSecret.GUEST_SCRIPT
                .replace("/run/isx", root + "/run/isx")
                .replace("/etc/profile.d", root + "/etc/profile.d")
                .replace("/proc/mounts", root + "/mounts");
    }

    @Test
    void nothingIsWrittenBeforeRunIsMounted(@TempDir Path root) throws Exception {
        // Written then, the secret would land on the rootfs under the tmpfs, and travel in copies
        var script = guestScriptUnder(root, "/dev/sda1 / ext4 rw 0 0\n");
        var run = run(script + "\necho ready", InstanceSecret.generate());
        assertEquals("ready", new String(run.getInputStream().readAllBytes()).strip());
        assertFalse(Files.exists(root.resolve("run/isx")));
        assertFalse(Files.exists(root.resolve("etc/profile.d/isx-instance-secret.sh")));
    }

    @Test
    void aGuestThatCannotTakeTheSecretStillAnswers(@TempDir Path root) throws Exception {
        // Best-effort: a box without its secret is refused by whatever checks it, but the start
        // it rides on must not hang on it
        var script = guestScriptUnder(root, "tmpfs /run tmpfs rw 0 0\n")
                .replace(root + "/run/isx", "/proc/no-such-dir/isx");
        var run = run(script + "\necho ready", InstanceSecret.generate());
        assertEquals("ready", new String(run.getInputStream().readAllBytes()).strip());
    }

    /** {@code script} run as the guest would, handed {@code secret} the way the exec hands it. */
    private static Process run(String script, String secret) throws Exception {
        var pb = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        pb.environment().putAll(InstanceSecret.guestEnv(secret, false, PLACEHOLDERS));
        return pb.start();
    }
}
