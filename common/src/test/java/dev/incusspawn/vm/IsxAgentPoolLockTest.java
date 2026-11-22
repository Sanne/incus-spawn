package dev.incusspawn.vm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The appliance agent's {@code btrfs-settle} runs detached, and the next delete's
 * {@code btrfs-skip-drop-accounting} must wait for it until its rescan has started, since that
 * delete's drop would cancel it (#2436). Runs the agent itself, with a stand-in {@code btrfs} whose
 * cleaner wait takes 3 s, its pool mount and lock directory moved into a temporary directory, so
 * no real pool is written: it never runs as root. Needs util-linux's {@code flock}, which the appliance installs;
 * skipped where there is none, such as on a Mac.
 */
class IsxAgentPoolLockTest {

    private static final Path AGENT = Path.of("../appliance/root/usr/local/sbin/isx-agent");

    @TempDir
    Path dir;

    @Test
    void aDeleteWaitsForTheDetachedSettleUntilItsRescanHasStarted() throws Exception {
        assumeTrue(hasUtilLinuxFlock(), "needs util-linux flock");
        // As root, the agent could write a real btrfs filesystem's threshold: its lookup takes the
        // btrfs mount holding the temporary directory, which on a btrfs root is the host's own
        assumeFalse("root".equals(System.getProperty("user.name")), "not as root");
        var agent = dir.resolve("isx-agent");
        var original = Files.readString(AGENT);
        var script = original
                .replace("POOL_MOUNT_PREFIX=/var/lib/incus/storage-pools\n", "POOL_MOUNT_PREFIX=" + dir.resolve("pools") + "\n")
                .replace("LOCK_DIR=/run/isx-agent\n", "LOCK_DIR=" + dir.resolve("locks") + "\n");
        assertTrue(script.contains("POOL_MOUNT_PREFIX=" + dir.resolve("pools")), "the agent's POOL_MOUNT_PREFIX line moved");
        // An agent with no lock at all fails below, on what it answers
        if (original.contains("LOCK_DIR=")) {
            assertTrue(script.contains("LOCK_DIR=" + dir.resolve("locks")), "the agent's LOCK_DIR line moved");
        }
        Files.writeString(agent, script);
        var bin = Files.createDirectories(dir.resolve("bin"));
        var log = dir.resolve("btrfs.log");
        Files.writeString(bin.resolve("btrfs"), """
                #!/bin/sh
                echo "$*" >> '%s'
                case "$1 $2" in "subvolume sync") sleep 3 ;; esac
                exit 0
                """.formatted(log));
        Files.setPosixFilePermissions(bin.resolve("btrfs"), PosixFilePermissions.fromString("rwxr-xr-x"));

        assertEquals("started", ask(agent, bin, "btrfs-settle isx-test-pool 3"));
        long start = System.nanoTime();
        // The settle is in its cleaner wait: no rescan runs yet, and the lock says so
        assertEquals("error: settle running", ask(agent, bin, "btrfs-skip-drop-accounting isx-test-pool previous"));
        long waited = System.nanoTime() - start;
        assertTrue(waited >= Duration.ofMillis(1500).toNanos() && waited < Duration.ofMillis(4500).toNanos(),
                "waited within the host's 5 s reply timeout: " + Duration.ofNanos(waited));
        // Once the settle is through its last step, the lock is free and the verb goes on to the pool,
        // which has no qgroups (whether the settle rescans depends on the filesystem its directory is on)
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(log) || Files.readString(log).split("filesystem sync", -1).length < 3) {
            assertTrue(System.nanoTime() < deadline, "the settle did not finish");
            Thread.sleep(50);
        }
        Thread.sleep(300);
        assertEquals("error: no qgroups", ask(agent, bin, "btrfs-skip-drop-accounting isx-test-pool previous"));
    }

    private String ask(Path agent, Path bin, String verb) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("sh", agent.toString()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        var p = pb.start();
        p.getOutputStream().write((verb + "\n").getBytes(StandardCharsets.US_ASCII));
        p.getOutputStream().close();
        // The detached settle keeps no pipe of the agent's open, so the reply ends with the agent
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "the agent answered");
        return out;
    }

    private static boolean hasUtilLinuxFlock() {
        try {
            var p = new ProcessBuilder("sh", "-c", "command -v setsid >/dev/null && flock -w 0.1 9 9>/dev/null")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
