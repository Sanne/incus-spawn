package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.IncusClient;
import io.quarkus.test.junit.QuarkusTest;
import org.aesh.AeshRuntimeRunner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Build a project-local template against real Incus: mvn verify -DskipITs=false. */
@QuarkusTest
class HostRepoBuildIT {
    @Test
    void buildsUnpushedHostCommitIntoContainer() throws Exception {
        // BuildCommand auto-runs isx init otherwise, which must never be a test side effect.
        assertTrue(InitCommand.hasBeenInitialized(), "Requires an initialized isx host (run isx init before integration tests)");
        assertTrue(IncusClient.isReachable(), "Requires a running, accessible Incus daemon");
        var incus = RuntimeServices.incus();
        var name = "tpl-it-host-" + UUID.randomUUID().toString().substring(0, 8);
        var images = ImageDef.projectImagesDir().toAbsolutePath();
        boolean hadImages = Files.exists(images);
        boolean hadProjectConfig = Files.exists(images.getParent());
        // Project-local host Git sources may use repositories anywhere inside the project.
        var fixture = Files.createTempDirectory(images.getParent().getParent(), ".isx-host-repo-it-");
        var definition = images.resolve(name + ".yaml");
        try {
            var origin = Files.createDirectory(fixture.resolve("origin.git"));
            git(origin, "init", "--bare", "-b", "main");
            var source = Files.createDirectory(fixture.resolve("source"));
            git(source, "init", "-b", "main");
            git(source, "config", "user.name", "Integration Test");
            git(source, "config", "user.email", "test@example.com");
            Files.writeString(source.resolve("tracked"), "pushed\n");
            git(source, "add", "tracked");
            git(source, "commit", "-m", "Pushed baseline");
            git(source, "remote", "add", "origin", origin.toString());
            git(source, "push", "-u", "origin", "main");
            Files.writeString(source.resolve("tracked"), "unpushed\n");
            git(source, "commit", "-am", "Only on the host");
            var expectedHead = git(source, "rev-parse", "HEAD").strip();
            assertNotEquals(expectedHead, git(origin, "rev-parse", "refs/heads/main").strip());
            Files.writeString(source.resolve("tracked"), "dirty host worktree\n");
            Files.writeString(source.resolve("untracked"), "private\n");

            Files.createDirectories(images);
            Files.writeString(definition, """
                    name: %s
                    parent: tpl-minimal
                    type: container
                    repos:
                      - url: '%s'
                        path: ~/local
                        prime: test "$(cat tracked)" = unpushed
                    """.formatted(name, images.relativize(source)));

            var result = AeshRuntimeRunner.builder().command(BuildCommand.class)
                    .args(new String[]{name, "--yes"}).execute();
            assertNotNull(result);
            assertEquals(0, result.getResultValue(), "Template build should succeed");
            assertTrue(incus.exists(name));
            incus.start(name);
            var head = incus.execInContainer(name, "agentuser", "git -C ~/local rev-parse HEAD");
            assertTrue(head.success(), head.stderr());
            assertEquals(expectedHead, head.stdout().strip());
            var content = incus.execInContainer(name, "agentuser", "cat ~/local/tracked");
            assertTrue(content.success(), content.stderr());
            assertEquals("unpushed\n", content.stdout());
            assertTrue(incus.execInContainer(name, "agentuser",
                    "test ! -e ~/local/untracked && test ! -e ~/local/.git/FETCH_HEAD"
                            + " && git -C ~/local fsck --full").success());
            assertEquals("dirty host worktree\n", Files.readString(source.resolve("tracked")));
        } finally {
            try {
                for (var instance : List.of(name, name + "-rebuilding", name + "-failed-build")) {
                    if (incus.exists(instance)) incus.delete(instance, true);
                }
            } finally {
                Files.deleteIfExists(definition);
                try (var paths = Files.walk(fixture)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
                if (!hadImages) Files.deleteIfExists(images);
                if (!hadProjectConfig) Files.deleteIfExists(images.getParent());
            }
        }
    }

    private static String git(Path directory, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgSign=false"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
