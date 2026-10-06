package dev.incusspawn.proxy;

import dev.incusspawn.Warnings;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.Container;
import dev.incusspawn.tool.ToolDefLoader;
import dev.incusspawn.tool.ToolSetup;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * What an instance presents where a tool expects its credential: a proof, derived from the
 * instance's per-start secret ({@link InstanceSecret}) and one credential namespace, that the
 * request comes from a process given this start's login environment (#1106, plan in #1100).
 *
 * <p>The value keeps the shape the tool checks -- a prefix its namespace declares, such as
 * {@code gho_} -- and says whose it is to anyone debugging: {@code gho_isx_<digest>}. The digest
 * is an HMAC-SHA256 keyed with the secret, so it changes on every start, differs per namespace,
 * and gives away neither the secret nor another namespace's proof. The raw secret is what the MCP
 * bridge accepts, so it never sits in the environment; a proof does, in every login shell.
 *
 * <h3>How the guest gets them</h3>
 * Each tool declares which variables carry its placeholder ({@link ToolSetup#placeholders()}). The
 * exec that delivers the secret carries the exports too ({@link InstanceSecret#guestEnv}), and
 * writes them beside it in {@code /run} -- a tmpfs, so they never reach an image or a copy, and
 * readable by the instance user's group only. The login profile sources them after
 * {@code isx-env.sh}, replacing only the variables the build exported: an instance whose Claude
 * is OAuth-configured never grows an {@code ANTHROPIC_API_KEY}. A build, or an instance never
 * started by an isx that knows proofs, keeps the static placeholder {@code isx-env.sh} holds.
 */
public final class ProofToken {

    /** Between a placeholder's prefix and its digest, so a proof is recognisably isx's. */
    public static final String MARKER = "isx_";
    /** 128 bits: as long as a guess has to be right, and short enough for any token field. */
    static final int DIGEST_HEX_CHARS = 32;
    /** Separates this use of the secret from any other keyed with it. */
    private static final String CONTEXT = "isx-proof-token:";

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9._-]*");
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private ProofToken() {}

    /**
     * An environment variable that carries {@code namespace}'s credential, and the prefix its
     * tool expects the value to start with.
     */
    public record Placeholder(String env, String prefix, String namespace) {

        /** This start's value for the variable, from the instance's {@code secret}. */
        public String tokenFor(String secret) {
            return prefix + MARKER + derive(secret, namespace);
        }

        /** Why this declaration cannot be written into a login profile, or {@code ""}. */
        public String problem() {
            if (!EnvEntry.isValidName(env)) return "'" + env + "' is not a variable name";
            if (prefix == null || !PREFIX.matcher(prefix).matches()) {
                return "the prefix of " + env + " may only hold letters, digits, '.', '_' and '-'";
            }
            if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
                return env + " belongs to no single credential namespace";
            }
            return "";
        }
    }

    /**
     * The digest proving an instance holds {@code secret}, for {@code namespace}: hex, the first
     * {@value #DIGEST_HEX_CHARS} characters of HMAC-SHA256 keyed with the secret.
     *
     * @param secret the per-start secret as {@link InstanceSecret#generate} made it -- the guest's
     *               file content, stripped
     */
    public static String derive(String secret, String namespace) {
        InstanceSecret.requireHex(secret);
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Not a credential namespace: " + namespace);
        }
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(secret), "HmacSHA256"));
            var digest = mac.doFinal((CONTEXT + namespace).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, DIGEST_HEX_CHARS);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    /**
     * Every placeholder {@code tools} declare, once per variable. A declaration that could not be
     * written safely is dropped, and so is a variable two tools declare differently: guessing
     * which one the image's tool reads would hand it a proof for the wrong namespace. Dropping
     * leaves the build's static placeholder, which proves nothing -- the safe way to be wrong.
     */
    public static List<Placeholder> declaredBy(Collection<? extends ToolSetup> tools) {
        var byEnv = new LinkedHashMap<String, Placeholder>();
        var conflicting = new HashSet<String>();
        for (var tool : tools) {
            for (var placeholder : tool.placeholders()) {
                var problem = placeholder.problem();
                if (!problem.isEmpty()) {
                    Warnings.warn("tool '" + tool.name() + "' declares a placeholder isx cannot fill: " + problem);
                    continue;
                }
                var earlier = byEnv.putIfAbsent(placeholder.env(), placeholder);
                if (earlier != null && !earlier.equals(placeholder)) conflicting.add(placeholder.env());
            }
        }
        for (var env : conflicting) {
            Warnings.warn("tools declare " + env + " as the placeholder of different credentials;"
                    + " no instance gets a proof token in it");
            byEnv.remove(env);
        }
        return List.copyOf(byEnv.values());
    }

    /**
     * The placeholders of the tools the proxy serves ({@link ToolProxyResolver#proxyToolSetups}):
     * feature-gated tools only when enabled, and never a project-local tool's proxy entry.
     * Reads tool definitions from disk.
     */
    public static List<Placeholder> declared() {
        var config = SpawnConfig.load();
        return declaredBy(ToolProxyResolver.proxyToolSetups(config, new ToolDefLoader(config.getSearchPaths())).values());
    }

    /**
     * {@link #declared()}, read while the caller starts the instance: it reads files, and the
     * start waits on the guest far longer, so the start path pays nothing for it. A failed read
     * gives no placeholders -- static ones, which prove nothing -- and a warning.
     */
    public static CompletableFuture<List<Placeholder>> declaredInBackground() {
        return CompletableFuture.supplyAsync(ProofToken::declared).exceptionally(e -> {
            Warnings.warn("could not read the tools' credential placeholders, so this start"
                    + " gives none of them a proof token: " + e.getMessage());
            return List.of();
        });
    }

    /**
     * The shell the login profile sources to export this start's proofs: for each placeholder,
     * its token -- only when the variable is already set, by {@code isx-env.sh}, so a variable
     * the build did not give this instance stays unset.
     */
    static String profile(String secret, List<Placeholder> placeholders) {
        var sb = new StringBuilder();
        for (var placeholder : placeholders) {
            if (!placeholder.problem().isEmpty()) continue;
            var env = placeholder.env();
            sb.append("if [ -n \"${").append(env).append("+x}\" ]; then export ").append(env).append('=')
              .append(Container.shellQuote(placeholder.tokenFor(secret))).append("; fi\n");
        }
        return sb.toString();
    }
}
