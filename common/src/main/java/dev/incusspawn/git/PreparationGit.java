package dev.incusspawn.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Git in an owned repository. Output goes to files: object enumerations can exceed a pipe or heap. */
final class PreparationGit {
    private static final Set<String> TRANSPORT_ENV = Set.of("GIT_SSH", "GIT_SSH_COMMAND", "GIT_SSH_VARIANT",
            "GIT_ASKPASS", "GIT_SSL_CAINFO", "GIT_SSL_CAPATH", "GIT_SSL_NO_VERIFY", "GIT_PROXY_COMMAND",
            "GIT_HTTP_PROXY_AUTHMETHOD", "GIT_HTTP_USER_AGENT", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM");
    private PreparationGit() {}

    static String git(Path repo, String... args) throws IOException {
        return run(repo, null, null, List.of(args));
    }

    static String run(Path repo, Path input, Path output, List<String> args) throws IOException {
        var command = new ArrayList<>(List.of("git", "--no-replace-objects", "-c", "gc.auto=0",
                "-c", "maintenance.auto=false", "-c", "core.hooksPath=/dev/null",
                "-c", "pack.writeReverseIndex=false",
                "-c", "fetch.writeCommitGraph=false", "-C", repo.toString()));
        command.addAll(args);
        var stdout = output == null ? Files.createTempFile("isx-git-", ".out") : output;
        var stderr = Files.createTempFile("isx-git-", ".err");
        Process process = null;
        try {
            var builder = new ProcessBuilder(command);
            // In particular, do not inherit an alternate, a worktree, replace refs or injected -c options.
            // Keep the normal host config/credential helpers for authenticating the real remote.
            builder.environment().keySet().removeIf(k -> k.startsWith("GIT_") && !TRANSPORT_ENV.contains(k));
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            builder.environment().put("LC_ALL", "C");
            builder.redirectInput(input == null ? Path.of("/dev/null").toFile() : input.toFile());
            builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            process = builder.start();
            if (!process.waitFor(Duration.ofMinutes(15).toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Git preparation timed out: " + args.getFirst());
            }
            if (process.exitValue() != 0) {
                // Never include the command/URL: it may contain a credential.
                throw new IOException("Git preparation failed: " + args.getFirst());
            }
            return output == null ? Files.readString(stdout).strip() : "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git preparation interrupted", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (output == null) Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    static String hash(Path file) throws IOException {
        var digest = digest();
        try (var in = Files.newInputStream(file)) {
            var buffer = new byte[128 * 1024];
            for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String hash(String value) {
        return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static List<Path> children(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream.sorted().toList();
        }
    }

    static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            for (var p : stream.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
