package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No test touches the developer's own {@code ~/.config/incus-spawn} (#2426, #2433): surefire
 * gives every test JVM a {@code user.home} under {@code target/}, so a test outside
 * {@code IsolatedHome} or {@code TempHome} reads and writes there instead.
 */
class TestHomeGateTest {

    @Test
    void testJvmHomeIsUnderTarget() {
        var home = Environment.home().toAbsolutePath().normalize();
        var target = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize().resolve("target");
        assertTrue(home.startsWith(target), "user.home is " + home + ", not under " + target);
        assertEquals(home.resolve(".config/incus-spawn"), Environment.configDir());
    }
}
