package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProofToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GitHubGitCredentialTest {
    @TempDir Path root;

    private Path config() { return root.resolve("gitconfig"); }
    private Path proofs() { return root.resolve("proof-tokens"); }

    private String helper() {
        return GitHubGitCredential.HELPER.replace(InstanceSecret.PROOFS_PATH, proofs().toString());
    }

    private void install() throws Exception {
        var script = GitHubGitCredential.INSTALL_SCRIPT
                .replace("git config --system", "git config --file " + Container.shellQuote(config().toString()))
                .replace(InstanceSecret.PROOFS_PATH, proofs().toString());
        assertEquals(0, run(List.of("sh", "-c", script), "", Map.of()).exit());
    }

    private String deliver(String seed) throws Exception {
        var placeholder = new ProofToken.Placeholder("GH_TOKEN", "gho_", "github");
        var profile = InstanceSecret.guestEnv(new InstanceSecret.Delivery(seed, seed), false, List.of(placeholder))
                .get(InstanceSecret.PROOFS_DELIVERY_ENV);
        Files.writeString(proofs(), profile);
        return placeholder.tokenFor(seed);
    }

    @Test
    void gitReadsTheCurrentProofWithoutALoginShellAndAfterReset() throws Exception {
        install();
        var first = deliver("11".repeat(32));
        var credential = fill("https://github.com/owner/private.git", Map.of());
        assertEquals(0, credential.exit(), credential.error());
        assertTrue(credential.output().contains("username=x-access-token\npassword=" + first + "\n"));

        var second = deliver("22".repeat(32));
        credential = fill("https://github.com/owner/private.git", Map.of("GH_TOKEN", first));
        assertEquals(0, credential.exit(), credential.error());
        assertTrue(credential.output().contains("password=" + second + "\n"));
        assertFalse(Files.readString(config()).contains(first));
        assertFalse(Files.readString(config()).contains(second));
    }

    @Test
    void installationIsIdempotentAndPreservesOtherConfiguration() throws Exception {
        Files.writeString(config(), "[user]\n name = Existing Author\n"
                + "[credential \"https://example.com\"]\n helper = other-service\n"
                + "[credential \"https://github.com\"]\n helper = !isx_github_credential_old\n helper = existing-helper\n");
        install();
        var installed = Files.readString(config());
        install();
        assertEquals(installed, Files.readString(config()));
        assertTrue(installed.contains("Existing Author"));
        assertTrue(installed.contains("other-service"));
        assertTrue(installed.contains("existing-helper"));
        var helpers = run(List.of("git", "config", "--get-all", "credential.https://github.com.helper"), "", Map.of());
        assertEquals(helper() + "\nexisting-helper\n", helpers.output());
    }

    @Test
    void noProofDoesNotInventACredential() throws Exception {
        install();
        for (var env : List.of(Map.<String, String>of(), Map.of("GH_TOKEN", "gho_placeholder"))) {
            var credential = fill("https://github.com/owner/private.git", env);
            assertNotEquals(0, credential.exit());
            assertFalse(credential.output().contains("password="));
        }
        Files.writeString(proofs(), "");
        assertNotEquals(0, fill("https://github.com/owner/private.git", Map.of()).exit());
    }

    @Test
    void theHelperIsScopedToGitHubHttpsAndDeclinesStoreAndErase() throws Exception {
        install();
        deliver("11".repeat(32));
        for (var url : List.of("http://github.com/repo", "https://example.com/repo",
                "https://api.github.com/repo", "https://github.com.evil.example/repo", "https://github.com:444/repo")) {
            assertNotEquals(0, fill(url, Map.of()).exit(), url);
        }
        // Check the helper's own guard too, even when called without Git's URL scoping.
        for (var input : List.of("protocol=http\nhost=github.com\n\n",
                "protocol=https\nhost=example.com\n\n", "protocol=https\n\n")) {
            assertEquals("", invoke("get", input).output());
        }
        var before = Files.readString(proofs());
        for (var action : List.of("store", "erase")) {
            var result = invoke(action, "protocol=https\nhost=github.com\npassword=unused\n\n");
            assertEquals(0, result.exit());
            assertEquals("", result.output());
            assertEquals(before, Files.readString(proofs()));
        }
    }

    @Test
    void anExplicitTokenAndUserHelperOverrideAreRespected() throws Exception {
        install();
        deliver("11".repeat(32));
        var credential = fill("https://github.com/owner/private.git", Map.of("GH_TOKEN", "ghp_explicit-test-token"));
        assertTrue(credential.output().contains("password=ghp_explicit-test-token\n"));
        var overridden = run(List.of("git", "-c", "credential.helper=", "credential", "fill"),
                "url=https://github.com/owner/private.git\n\n", Map.of());
        assertNotEquals(0, overridden.exit(), "an explicit reset of the helper list takes precedence");
    }

    private Result fill(String url, Map<String, String> env) throws Exception {
        return run(List.of("git", "credential", "fill"), "url=" + url + "\n\n", env);
    }

    private Result invoke(String action, String input) throws Exception {
        return run(List.of("sh", "-c", helper().substring(1) + " " + action), input, Map.of());
    }

    private record Result(int exit, String output, String error) {}

    private Result run(List<String> command, String input, Map<String, String> env) throws Exception {
        var pb = new ProcessBuilder(command).directory(root.toFile());
        pb.environment().keySet().removeIf(k -> k.startsWith("GIT_") || k.equals("GH_TOKEN"));
        pb.environment().putAll(Map.of("GIT_CONFIG_SYSTEM", config().toString(),
                "GIT_CONFIG_GLOBAL", "/dev/null", "GIT_TERMINAL_PROMPT", "0", "GIT_ASKPASS", "/bin/false"));
        pb.environment().putAll(env);
        var process = pb.start();
        try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Git must never wait for a terminal prompt");
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
