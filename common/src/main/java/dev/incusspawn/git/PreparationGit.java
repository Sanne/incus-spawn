package dev.incusspawn.git;

import dev.incusspawn.config.GitTransferConfig;

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
import java.util.function.Consumer;

/** Git in an owned repository. Output goes to files: object enumerations can exceed a pipe or heap. */
final class PreparationGit {
    private static final Set<String> TRANSPORT_ENV = Set.of("GIT_SSH", "GIT_SSH_COMMAND", "GIT_SSH_VARIANT",
            "GIT_ASKPASS", "GIT_SSL_CAINFO", "GIT_SSL_CAPATH", "GIT_SSL_NO_VERIFY", "GIT_PROXY_COMMAND",
            "GIT_HTTP_PROXY_AUTHMETHOD", "GIT_HTTP_USER_AGENT", "GIT_CONFIG_GLOBAL", "GIT_CONFIG_SYSTEM");
    /** Local work on owned repositories: no stall budget, since an fsck or repack can be quiet for long. */
    static final GitTransferConfig LOCAL = new GitTransferConfig(null, Duration.ofMinutes(15));

    private PreparationGit() {}

    /** A transfer {@link GitProcess} stopped for stalling or overrunning: retrying it would only wait again. */
    static final class TransferStopped extends GitCommandException {
        TransferStopped(String message, String gitOutput) { super(message, null, gitOutput); }
    }

    static String git(Path repo, String... args) throws IOException {
        return run(repo, null, null, List.of(args));
    }

    static String run(Path repo, Path input, Path output, List<String> args) throws IOException {
        return run(repo, input, output, args, LOCAL, progress -> {});
    }

    /** {@code progress} sees Git's progress only when {@code args} ask for it ({@code --progress}). */
    static String run(Path repo, Path input, Path output, List<String> args, GitTransferConfig limits,
                      Consumer<GitProcess.Progress> progress) throws IOException {
        var command = new ArrayList<>(List.of("git", "--no-replace-objects", "-c", "gc.auto=0",
                "-c", "maintenance.auto=false", "-c", "core.hooksPath=/dev/null",
                "-c", "pack.writeReverseIndex=false",
                "-c", "fetch.writeCommitGraph=false",
                "-c", "protocol.allow=never", "-c", "protocol.https.allow=always",
                "-c", "protocol.ssh.allow=always", "-c", "protocol.git.allow=always",
                "-c", "protocol.file.allow=never", "-C", repo.toString()));
        command.addAll(args);
        var stdout = output == null ? Files.createTempFile("isx-git-", ".out") : output;
        try {
            var builder = new ProcessBuilder(command);
            // In particular, do not inherit an alternate, a worktree, replace refs or injected -c options.
            // Keep the normal host config/credential helpers for authenticating the real remote.
            builder.environment().keySet().removeIf(k -> k.startsWith("GIT_") && !TRANSPORT_ENV.contains(k));
            // GitProcess makes it non-interactive (prompts, SSH) and kills the whole tree on a stop.
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            if (limits == LOCAL) builder.environment().put("GIT_NO_LAZY_FETCH", "1");
            builder.environment().put("LC_ALL", "C");
            builder.redirectInput(input == null ? Path.of("/dev/null").toFile() : input.toFile());
            builder.redirectOutput(stdout.toFile());
            // Local steps use no SSH, so they skip reading core.sshCommand.
            var result = GitProcess.run(builder, limits == LOCAL ? null : repo, SshScope.BUILD, limits, progress);
            // The message never holds the command/URL: it may contain a credential. Git's own words go in
            // GitCommandException.details(), scrubbed, apart from isx's.
            return switch (result.status()) {
                case OK -> output == null ? Files.readString(stdout).strip() : "";
                case FAILED -> throw new GitCommandException("Git preparation failed: " + verb(args),
                        GitCommandException.promptNote(result.diagnostics(), result.ssh()), result.diagnostics());
                case INTERRUPTED -> throw new IOException("Git preparation interrupted");
                // A slow local step is not a stalled remote: the usual fallbacks still apply.
                case STALLED, TIMED_OUT -> throw limits == LOCAL
                        ? new IOException("Git preparation timed out: " + verb(args)) : new TransferStopped(
                        "Git preparation " + verb(args) + " " + result.stopReason(), result.diagnostics());
            };
        } finally {
            if (output == null) Files.deleteIfExists(stdout);
        }
    }

    /** The Git command {@code args} run, past any {@code -c} options. */
    static String verb(List<String> args) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("-c")) i++;
            else return args.get(i);
        }
        return "git";
    }

    /**
     * What isx did to make SSH and Git unable to prompt: {@code done} in words for a failure
     * ({@link GitCommandException#promptNote}); {@code keySetting}: where to choose another SSH key, given
     * the command Git ran.
     */
    record SshSetup(String done, String keySetting) {}

    /** Where Git runs, which decides whose {@code core.sshCommand} applies and how a failure names it. */
    enum SshScope {
        /** A build: Git in isx's own repository, where only a global {@code core.sshCommand} applies. */
        BUILD("your global core.sshCommand", "a global core.sshCommand (a repository's own does not apply to builds)"),
        /** {@code update-all} fetching: Git in the user's checkout, where its own applies. */
        CHECKOUT("your core.sshCommand", "core.sshCommand"),
        /**
         * {@code update-all} cloning: a global {@code includeIf "gitdir:"} matching the new checkout may choose
         * the command, and no probe sees it before the clone. isx never sets one from config, since any it set would
         * win, and reads no config ({@code repo} is unused).
         */
        CLONE("your core.sshCommand", "a global core.sshCommand, or one an includeIf \"gitdir:\" sets for the new checkout");

        private final String source, keySetting;

        SshScope(String source, String keySetting) {
            this.source = source;
            this.keySetting = keySetting;
        }
    }

    /**
     * Git and SSH without passphrase, host-key or credential prompts, for a build and for {@code update-all}
     * alike (#1403, #1410), with Git run in {@code repo} as {@code scope} says. Git's config is read with
     * {@code environment}, the one the real command runs with. Returns what isx did.
     * <p>
     * {@code -o BatchMode=yes} goes into the SSH command Git runs where it can
     * ({@link #withBatchMode}), keeping the user's options. For every case, those the option cannot reach
     * included ({@code GIT_SSH}, a wrapper, plink, a command isx could not read), OpenSSH 8.4 and later asks
     * {@code SSH_ASKPASS} instead of the terminal, and {@code false} answers nothing. An empty
     * {@code GIT_ASKPASS} ends Git's own askpass chain ({@code GIT_ASKPASS}, {@code core.askPass}, then
     * {@code SSH_ASKPASS}) before any dialog, whatever the Git version does with {@code GIT_TERMINAL_PROMPT=0}.
     */
    static SshSetup nonInteractiveSsh(java.util.Map<String, String> environment, Path repo, SshScope scope) {
        environment.put("SSH_ASKPASS", "false");
        environment.put("SSH_ASKPASS_REQUIRE", "force");
        environment.put("GIT_ASKPASS", "");
        // Git's own order: GIT_SSH_COMMAND, then core.sshCommand, then GIT_SSH, then plain ssh.
        var command = environment.get("GIT_SSH_COMMAND");
        if (command != null) return insertBatchMode(environment, command, "your GIT_SSH_COMMAND", "your GIT_SSH_COMMAND");
        if (scope == SshScope.CLONE) return environment.containsKey("GIT_SSH")
                ? new SshSetup(ASKPASS_FOR + "your GIT_SSH program", "your GIT_SSH program")
                : new SshSetup(ASKPASS_FOR + "ssh", scope.keySetting);
        var configured = repo == null ? java.util.Optional.of("") : configuredSshCommand(environment, repo);
        if (configured.isEmpty()) return new SshSetup(ASKPASS_FOR + "ssh", scope.keySetting);
        if (!configured.get().isBlank()) {
            return insertBatchMode(environment, configured.get(), scope.source, scope.keySetting);
        }
        // GIT_SSH_COMMAND set by isx would override it: the askpass setting covers it.
        if (environment.containsKey("GIT_SSH")) return new SshSetup(ASKPASS_FOR + "your GIT_SSH program", "your GIT_SSH program");
        environment.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes");
        return new SshSetup("isx ran ssh with -o BatchMode=yes", scope.keySetting);
    }

    private static final String ASKPASS_FOR = "isx set SSH_ASKPASS_REQUIRE=force with SSH_ASKPASS=false for ";

    private static SshSetup insertBatchMode(java.util.Map<String, String> environment, String command, String source,
                                           String keySetting) {
        var withBatchMode = withBatchMode(command);
        if (withBatchMode == null) return new SshSetup(ASKPASS_FOR + source, keySetting);
        environment.put("GIT_SSH_COMMAND", withBatchMode);
        return new SshSetup("isx added -o BatchMode=yes to " + source, keySetting);
    }

    /**
     * {@code command} with {@code -o BatchMode=yes} right after its {@code ssh}, where it wins over a later
     * {@code -o BatchMode=no} and no trailing comment can swallow it; null when the command is not plainly
     * ssh (a wrapper, plink, a quoted path), which might reject the option: the askpass setting covers it.
     */
    static String withBatchMode(String command) {
        var trimmed = command.strip();
        var program = trimmed.split("\\s", 2)[0];
        if (!program.equals("ssh") && !program.endsWith("/ssh")) return null;
        return program + " -o BatchMode=yes" + trimmed.substring(program.length());
    }

    /**
     * {@code core.sshCommand} as Git sees it with {@code environment} in {@code repo}: the value, {@code ""}
     * when unset, or empty when the read failed.
     */
    private static java.util.Optional<String> configuredSshCommand(java.util.Map<String, String> environment, Path repo) {
        var probe = new ProcessBuilder("git", "-C", repo.toString(), "config", "--get", "core.sshCommand")
                .redirectInput(Path.of("/dev/null").toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        probe.environment().clear();
        probe.environment().putAll(environment);
        Process process = null;
        try {
            // A build's first Ctrl+C may be a copy: it must not turn this read into "unknown".
            process = dev.incusspawn.util.OwnSession.start(probe);
            // Wait before reading: one config value fits the pipe.
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) return java.util.Optional.empty();
            return switch (process.exitValue()) {
                case 0 -> java.util.Optional.of(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
                case 1 -> java.util.Optional.of("");
                default -> java.util.Optional.empty();
            };
        } catch (IOException e) {
            return java.util.Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
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
