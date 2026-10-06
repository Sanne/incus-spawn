package dev.incusspawn.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runs a guest shell snippet in a local {@code sh}, with nothing of the test's environment but {@code PATH}. */
final class GuestShell {

    private GuestShell() {}

    /** {@code command}'s output, with {@code HOME} at {@code home} and {@code env} set; fails unless it exits 0. */
    static String run(Path home, Map<String, String> env, String... command) throws Exception {
        var pb = new ProcessBuilder(command).redirectErrorStream(true);
        var path = pb.environment().get("PATH");
        pb.environment().clear();
        pb.environment().put("PATH", path);
        pb.environment().put("HOME", home.toString());
        pb.environment().putAll(env);
        var process = pb.start();
        var out = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), out);
        return out;
    }

    /** Sources {@code script} as a login shell would, then runs {@code then}. */
    static String login(Path home, String script, Map<String, String> env, String then) throws Exception {
        var file = Files.createTempFile(home, "profile", ".sh");
        Files.writeString(file, script);
        return run(home, env, "sh", "-c", ". \"$1\"; " + then, "sh", file.toString());
    }
}
