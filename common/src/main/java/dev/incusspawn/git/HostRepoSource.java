package dev.incusspawn.git;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static dev.incusspawn.incus.Container.shellQuote;

/** A file URL names a required host repository, never a remote to fetch. */
public final class HostRepoSource {
    public static final String CONFIG_KEY = "isx.hostOnly";

    private HostRepoSource() {}

    public static boolean isHostOnly(String url) {
        return url != null && url.regionMatches(true, 0, "file:", 0, 5);
    }

    public static Path path(String url) {
        try {
            var uri = URI.create(url);
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                    || uri.getRawAuthority() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPath() == null || !uri.getPath().startsWith("/")) {
                throw new IllegalArgumentException();
            }
            return Path.of(uri.getPath()).normalize();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Expected an absolute host file URL without an authority, query or fragment: " + url, e);
        }
    }

    public static String repoName(String url) {
        var source = path(url);
        if (source.getFileName() == null) return "";
        if (source.getFileName().toString().equals(".git")) source = source.getParent();
        var name = source.getFileName() == null ? "" : source.getFileName().toString();
        return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
    }

    /** Initial support is deliberately limited to self-contained Git directories. */
    public static Path gitDirectory(String url) {
        var source = path(url);
        if (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("HEAD"))
                || !Files.isDirectory(source.resolve("objects")) || Files.exists(source.resolve("commondir"))) {
            throw new IllegalArgumentException("Host repository must be a standalone .git directory (or bare repository): " + source);
        }
        if (Files.exists(source.resolve("objects/info/alternates"))
                || Files.exists(source.resolve("objects/info/http-alternates"))
                || Files.isSymbolicLink(source.resolve("objects")) || Files.exists(source.resolve("shallow"))) {
            throw new IllegalArgumentException("Host repository must be complete and have no external object dependencies: " + source);
        }
        return source;
    }

    public record ConfigEntry(String key, String value) {}
    public record Snapshot(Path gitDirectory, String head, String branch, String refs, List<ConfigEntry> config) {
        public String fingerprint() {
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                for (var value : List.of(head, branch, refs)) {
                    digest.update(value.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
                for (var entry : config) {
                    digest.update(entry.key().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(entry.value().getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * A local mirror preserves all ref namespaces. Convert it to a normal working
         * repository, replacing only the clone-generated config, then check out HEAD.
         * No host config, hooks, index or working tree is copied wholesale.
         */
        public String cloneScript(String mountedGitDir, String destination) {
            var git = "git -C " + shellQuote(destination);
            var script = new StringBuilder("set -eu\n")
                    .append("export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_NO_LAZY_FETCH=1 GIT_ALLOW_PROTOCOL=file GIT_LFS_SKIP_SMUDGE=1\n")
                    .append("test ! -e ").append(shellQuote(destination)).append("\n")
                    .append("git -c core.hooksPath=/dev/null clone --local --mirror --no-hardlinks --origin isx-source -- ")
                    .append(shellQuote(mountedGitDir)).append(' ').append(shellQuote(destination + "/.git")).append("\n")
                    .append("git --git-dir=").append(shellQuote(destination + "/.git"))
                    .append(" config core.bare false\n")
                    .append(git).append(" config --remove-section remote.isx-source\n");
            // Install the captured refs explicitly: uploadpack.hideRefs may hide some
            // from clone's advertisement, although local cloning copies their objects.
            script.append(git).append(" update-ref --no-deref --stdin <<'ISX_HOST_REFS'\n");
            for (var line : refs.lines().toList()) {
                var fields = line.split(" ", 3);
                script.append("update ").append(fields[0]).append(' ').append(fields[1]).append('\n');
            }
            script.append("ISX_HOST_REFS\n");
            // Mirror cloning resolves symbolic refs other than HEAD. Restore those
            // explicitly (e.g. upstream/HEAD).
            for (var line : refs.lines().toList()) {
                var fields = line.split(" ", 3);
                if (fields.length == 3 && !fields[2].isEmpty()) {
                    script.append(git).append(" symbolic-ref ").append(shellQuote(fields[0]))
                            .append(' ').append(shellQuote(fields[2])).append("\n");
                }
            }
            for (var entry : config) {
                script.append(git).append(" config --add ").append(shellQuote(entry.key()))
                        .append(' ').append(shellQuote(entry.value())).append("\n");
            }
            script.append(git).append(" config ").append(CONFIG_KEY).append(" true\n");
            if (branch.isEmpty()) {
                script.append(git).append(" update-ref --no-deref HEAD ").append(shellQuote(head)).append("\n");
            } else {
                if (!head.isEmpty()) {
                    script.append(git).append(" update-ref ").append(shellQuote(branch)).append(' ').append(shellQuote(head)).append("\n");
                }
                script.append(git).append(" symbolic-ref HEAD ").append(shellQuote(branch)).append("\n");
            }
            // read-tree populates the index/worktree without checkout hooks or smudge
            // filters inherited from the host. An empty repository has no tree yet.
            if (!head.isEmpty()) {
                script.append(git).append(" -c core.hooksPath=/dev/null read-tree --reset -u HEAD\n");
            }
            script.append(git).append(" fsck --connectivity-only --no-dangling\n");
            return script.toString();
        }
    }

    public static Snapshot capture(String url) {
        var source = gitDirectory(url);
        var config = new ArrayList<ConfigEntry>();
        var rawConfig = git(source, true, "config", "--includes", "--null", "--get-regexp", "^(remote\\.|branch\\.)");
        for (var item : rawConfig.split("\u0000")) {
            if (item.isEmpty()) continue;
            int split = item.indexOf('\n');
            var key = split < 0 ? item : item.substring(0, split);
            // A valueless config entry is an implicit boolean true, not an empty value.
            var value = split < 0 ? "true" : item.substring(split + 1);
            if (key.endsWith(".promisor") || key.endsWith(".partialclonefilter")) {
                throw new IllegalArgumentException("Partial host repositories are not supported: " + source);
            }
            config.add(new ConfigEntry(key, value));
        }
        var branch = git(source, true, "symbolic-ref", "--quiet", "HEAD").strip();
        var head = git(source, true, "rev-parse", "--verify", "--quiet", "HEAD^{commit}").strip();
        if (head.isEmpty() && branch.isEmpty()) throw new IllegalArgumentException("Invalid host HEAD: " + source);
        var refs = git(source, false, "for-each-ref", "--format=%(refname) %(objectname) %(symref)");
        if (!branch.isEmpty() && !head.isEmpty()
                && refs.lines().noneMatch(line -> line.startsWith(branch + " " + head + " "))) {
            throw new IllegalStateException("Host HEAD changed while reading repository; retry: " + source);
        }
        return new Snapshot(source, head, branch, refs, List.copyOf(config));
    }

    public static String fingerprint(String url) {
        try {
            return capture(url).fingerprint();
        } catch (IllegalArgumentException | IllegalStateException e) {
            // An unavailable source makes a previously built template outdated, but
            // must not prevent the TUI from displaying it. Building reports the error.
            return "unavailable";
        }
    }

    private static String git(Path source, boolean allowMissing, String... args) {
        var command = new ArrayList<>(List.of("git", "--git-dir=" + source));
        command.addAll(List.of(args));
        Process process = null;
        try {
            var builder = new ProcessBuilder(command);
            // A caller's GIT_DIR, worktree or injected config must not change the source.
            builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
            builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
            builder.environment().put("GIT_NO_LAZY_FETCH", "1");
            builder.environment().put("GIT_ALLOW_PROTOCOL", "file");
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            if (code != 0 && !(allowMissing && code == 1)) {
                throw new IllegalStateException("Cannot read host repository " + source + " (git " + args[0] + " exited " + code + ")");
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read host repository " + source, e);
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading host repository " + source, e);
        }
    }
}
