package dev.incusspawn.proxy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The account {@code inherit} (#1110): an isx running inside an isx instance shares that
 * instance's grant for one namespace with its own instances, on purpose.
 *
 * <p>A nested isx's instances present their own proofs ({@link ProofToken}), which the outer
 * proxy cannot verify, so by default they get none of its credentials (#1107). An instance whose
 * account for a namespace is {@code inherit} has every proof it presents for that namespace
 * replaced by the one the parent instance's own login environment holds -- derived from the
 * credential seed the outer isx gave it, at {@link CredentialProof#GUEST_PATH} -- under the same prefix and
 * in the same header. The outer proxy then sees the parent's proof from the parent's address, and
 * serves the parent's account. Nothing else is swapped: a static placeholder proves nothing, and
 * a token the caller supplied stays theirs.
 *
 * <p>The seed is refreshed off the event loop, so an explicit parent proof reset is observed.
 * A machine without one is not running nested, and such a request is refused, as for an account
 * that is not configured.
 */
public final class InheritedProof {

    /** Where tests point {@link #secretFile()}, so a test run inside an isx instance is not nested. */
    public static final String SECRET_FILE_PROPERTY = "isx.instance-secret-file";

    private InheritedProof() {}

    /** The parent instance's secret, as its isx delivered it. */
    static Path secretFile() {
        return Path.of(System.getProperty(SECRET_FILE_PROPERTY, CredentialProof.GUEST_PATH));
    }

    /** Whether this machine is an isx instance, holding a secret its isx gave it. */
    public static boolean nested() {
        return Files.exists(secretFile());
    }

    /** The parent instance's current secret, or null when there is none, or none this can read. */
    public static String parentSecret() {
        // Outside an instance, every second: no exception for the watcher to throw away
        if (!nested()) return null;
        try {
            var secret = Files.readString(secretFile(), StandardCharsets.US_ASCII).strip();
            return InstanceSecret.isWellFormed(secret) ? secret : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Why a request to inherit cannot be served here; refused with it. */
    public static String notNestedMessage(String namespace) {
        var file = secretFile();
        var why = Files.exists(file)
                ? "the instance secret at " + file + " cannot be read by this isx, or is not one isx wrote"
                : "there is no instance secret at " + file + ": this isx is not running in an isx instance,"
                        + " or that instance has not been started by isx";
        return "This instance takes its '" + namespace + "' credential from the isx instance this isx runs in"
                + " (account 'inherit'), but " + why + ".";
    }

    /**
     * The placeholders declared for each namespace, one per prefix: the proofs a request in it
     * may carry.
     */
    public static Map<String, List<ProofToken.Placeholder>> byNamespace(Collection<ProofToken.Placeholder> placeholders) {
        var byNamespace = new java.util.HashMap<String, Map<String, ProofToken.Placeholder>>();
        for (var placeholder : placeholders) {
            byNamespace.computeIfAbsent(placeholder.namespace(), ns -> new java.util.LinkedHashMap<>())
                    .putIfAbsent(placeholder.prefix(), placeholder);
        }
        var result = new java.util.HashMap<String, List<ProofToken.Placeholder>>();
        byNamespace.forEach((ns, byPrefix) -> result.put(ns, List.copyOf(byPrefix.values())));
        return Map.copyOf(result);
    }

    /**
     * Swaps the proofs of some namespaces for the parent's, in header values.
     *
     * @param tokens each proof pattern, with the token that replaces it
     */
    public record Swap(List<Map.Entry<Pattern, String>> tokens) {

        /**
         * The swap for {@code namespaces}, each proving with the parent's {@code secret} under
         * every placeholder declared for it ({@link #byNamespace}); a namespace that declares
         * none has no proof to swap.
         */
        public static Swap of(String secret, Collection<String> namespaces,
                              Map<String, List<ProofToken.Placeholder>> placeholders) {
            var byPrefix = new java.util.LinkedHashMap<String, ProofToken.Placeholder>();
            var ambiguous = new java.util.HashSet<String>();
            for (var namespace : namespaces) {
                for (var placeholder : placeholders.getOrDefault(namespace, List.of())) {
                    if (byPrefix.putIfAbsent(placeholder.prefix(), placeholder) != null) ambiguous.add(placeholder.prefix());
                }
            }
            // A prefix two of them declare could be either's proof: swapping it for one would
            // present the wrong namespace's, so it is left as it is, which proves nothing upstream
            var tokens = new java.util.ArrayList<Map.Entry<Pattern, String>>();
            byPrefix.forEach((prefix, placeholder) -> {
                if (!ambiguous.contains(prefix)) tokens.add(Map.entry(proofPattern(prefix), placeholder.tokenFor(secret)));
            });
            return new Swap(List.copyOf(tokens));
        }

        /**
         * {@code value} with every proof replaced. A {@code Basic} credential is decoded first:
         * a git client sends its token as the password.
         */
        public String apply(String value) {
            if (value == null || tokens.isEmpty()) return value;
            if (value.regionMatches(true, 0, BASIC, 0, BASIC.length())) {
                byte[] decoded;
                try {
                    decoded = Base64.getDecoder().decode(value.substring(BASIC.length()).strip());
                } catch (IllegalArgumentException e) {
                    return value;
                }
                var plain = new String(decoded, StandardCharsets.UTF_8);
                var swapped = replace(plain);
                return swapped.equals(plain) ? value
                        : BASIC + Base64.getEncoder().encodeToString(swapped.getBytes(StandardCharsets.UTF_8));
            }
            return replace(value);
        }

        private String replace(String value) {
            // Most headers hold no proof: one search rather than a pattern per prefix
            if (!value.contains(ProofToken.MARKER)) return value;
            var result = value;
            for (var token : tokens) {
                result = token.getKey().matcher(result).replaceAll(Matcher.quoteReplacement(token.getValue()));
            }
            return result;
        }
    }

    private static final String BASIC = "Basic ";

    /**
     * A proof under {@code prefix}, as a whole token: a character that could continue it on either
     * side makes it something else. The prefix holds only {@code [A-Za-z0-9._-]}.
     */
    private static Pattern proofPattern(String prefix) {
        return PATTERNS.computeIfAbsent(prefix, p -> Pattern.compile("(?<![A-Za-z0-9._-])" + Pattern.quote(p + ProofToken.MARKER)
                + "[0-9a-f]{" + ProofToken.DIGEST_HEX_CHARS + "}(?![A-Za-z0-9._-])"));
    }

    /** Compiled once per prefix: a swap is built for every request that inherits. */
    private static final Map<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();
}
