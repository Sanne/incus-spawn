package dev.incusspawn.git;

import dev.incusspawn.config.SecretRedactor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A host Git command that failed, with isx's own explanation kept apart from what Git said, so a
 * user can tell which words are whose (#1343). The message is isx's and names only the command: Git's
 * output can hold a URL, so it is forwarded in {@link #details()}, scrubbed, never in the message.
 */
public class GitCommandException extends IOException {
    /** What {@link #details()} puts before each of Git's own lines. */
    static final String QUOTE = "  | ";
    private static final Pattern HTTP_USERINFO = Pattern.compile("(?i)\\b(https?://)[^/?#@\\s'\"]+@");
    private final List<String> details;

    GitCommandException(String message, String isxNote, String gitOutput) {
        super(message);
        details = details(isxNote, gitOutput);
    }

    /** isx's explanation, then Git's own lines quoted under a header; empty when there is neither. */
    public List<String> details() {
        return details;
    }

    static List<String> details(String isxNote, String gitOutput) {
        var lines = new ArrayList<String>();
        if (isxNote != null) lines.add(isxNote);
        if (gitOutput != null && !gitOutput.isBlank()) {
            // Bounded and cleaned like a transfer's tail, whichever command it came from.
            var said = gitOutput.lines().map(GitProcess.Tail::clean).filter(l -> !l.isEmpty()).toList();
            lines.add("Git's output:");
            said.subList(Math.max(0, said.size() - GitProcess.TAIL_LINES), said.size()).forEach(l -> lines.add(QUOTE + l));
        }
        return lines;
    }

    /**
     * {@code text} without URL credentials: the redactor's {@code user:password@} and, beyond it, the
     * whole userinfo of an HTTP(S) URL, where a token can stand alone.
     */
    static String scrub(String text) {
        return HTTP_USERINFO.matcher(SecretRedactor.scrubText(text, Map.of()).text())
                .replaceAll("$1" + SecretRedactor.marker("url-credentials") + "@");
    }

    /**
     * isx's explanation for a failure that a prompt would have answered, or null when isx changed
     * nothing that explains it. {@code ssh}: what isx did.
     */
    static String promptNote(String gitOutput, PreparationGit.SshSetup ssh) {
        return promptNote(gitOutput, ssh, GitCommandException::ghLogin);
    }

    /** {@code gh}: whether the GitHub CLI is logged in to a host, asked only for a credential that Git could not get. */
    static String promptNote(String gitOutput, PreparationGit.SshSetup ssh, Function<String, GhLogin> gh) {
        if (gitOutput == null) return null;
        if (gitOutput.contains("Permission denied (publickey")) {
            // Also what a server says when it accepts none of the keys: isx cannot tell which.
            return ssh.done() + ", so ssh could not ask for a key's passphrase. If your key has one, load it"
                    + " into ssh-agent; otherwise check that the server accepts the key, or choose another with "
                    + ssh.keySetting() + ".";
        }
        if (gitOutput.contains("Host key verification failed")) {
            return ssh.done() + ", so ssh could not ask about the host's key. If the host is new, check its"
                    + " fingerprint and add it to ~/.ssh/known_hosts; if its key changed, find out why before"
                    + " trusting it.";
        }
        if (gitOutput.contains("terminal prompts disabled") || gitOutput.contains("could not read Username")
                || gitOutput.contains("could not read Password")) {
            return "isx ran Git with GIT_TERMINAL_PROMPT=0 and no askpass program, so it could not ask for a"
                    + " username or password. " + credentialAdvice(credentialHost(gitOutput), gh);
        }
        return null;
    }

    /** Whether the GitHub CLI can serve a host's credentials: {@code UNKNOWN} when it is missing or did not answer. */
    enum GhLogin { UNKNOWN, LOGGED_OUT, LOGGED_IN }

    private static final Pattern CREDENTIAL_URL = Pattern.compile("could not read (?:Username|Password) for '([^']+)'");

    /** The host Git wanted a credential for, from its own message, or null. */
    static String credentialHost(String gitOutput) {
        var matcher = CREDENTIAL_URL.matcher(gitOutput);
        if (!matcher.find()) return null;
        try {
            var uri = new URI(matcher.group(1));
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * {@code gh auth setup-git} where the GitHub CLI already holds a login for {@code host}; for github.com,
     * its login first. Any other host gets the generic advice: gh may know nothing about it.
     */
    static String credentialAdvice(String host, Function<String, GhLogin> gh) {
        var generic = "Configure a Git credential helper.";
        if (host == null) return generic;
        var hostname = GITHUB.equals(host) ? "" : " --hostname " + host;
        return switch (gh.apply(host)) {
            case LOGGED_IN -> "The GitHub CLI is logged in to " + host + ": run `gh auth setup-git" + hostname
                    + "` to make it Git's credential helper there.";
            case LOGGED_OUT -> GITHUB.equals(host)
                    ? "Run `gh auth login`, then `gh auth setup-git`, to make the GitHub CLI Git's credential helper"
                    + " for github.com, or configure another Git credential helper."
                    : generic;
            case UNKNOWN -> generic;
        };
    }

    private static final String GITHUB = "github.com";
    private static final long GH_TIMEOUT_SECONDS = 10;

    /** {@code gh auth status} for {@code host}, which may check the token over the network: bounded. */
    static GhLogin ghLogin(String host) {
        Process process = null;
        try {
            var builder = new ProcessBuilder("gh", "auth", "status", "--hostname", host)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("GH_PROMPT_DISABLED", "1");
            process = builder.start();
            if (!process.waitFor(GH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return GhLogin.UNKNOWN;
            }
            return process.exitValue() == 0 ? GhLogin.LOGGED_IN : GhLogin.LOGGED_OUT;
        } catch (IOException e) {
            return GhLogin.UNKNOWN; // not installed
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return GhLogin.UNKNOWN;
        }
    }
}
