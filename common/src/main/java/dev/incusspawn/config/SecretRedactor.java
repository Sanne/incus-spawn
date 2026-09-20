package dev.incusspawn.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes credentials from anything destined for a support bundle, and owns the marker
 * format that says so.
 *
 * <p>Two mechanisms, deliberately unequal in standing:
 *
 * <ul>
 *   <li><b>Structural redaction</b> ({@link #redactConfig}) is <em>the</em> mechanism for
 *       config.yaml: the config is serialized to a tree and the values at the secret
 *       locations {@link SecretRegistry} knows about are replaced before anything is
 *       written. Nothing is pattern-matched, so nothing depends on a secret looking the way
 *       we expected it to look.
 *   <li><b>Text scrubbing</b> ({@link #scrubText}) is a net, not a mechanism, for artifacts
 *       that have no model to scrub -- log files, {@code instances.json}. It replaces the
 *       exact secret values taken from the config, then known token shapes. A net can miss;
 *       that is why the bundle says so and why the config never relies on it.
 * </ul>
 *
 * <p>A redacted value carries a marker naming the key it replaced
 * ({@code <isx:redacted:github.token>}) rather than becoming an empty string, because empty
 * is itself a legitimate value: without the marker a reader cannot tell a credential that
 * was removed from one that was never configured, and those two describe opposite bugs.
 */
public final class SecretRedactor {

    private SecretRedactor() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public static final String MARKER_PREFIX = "<isx:redacted:";

    /** Exact-match scrubbing is skipped below this length, so a short value cannot mass-replace unrelated text. */
    private static final int MIN_SCRUBBABLE_LENGTH = 8;

    /** Key-name words that mark a value as sensitive when nothing declared it. */
    private static final Set<String> SECRET_NAME_HINTS = Set.of(
            "token", "tokens", "key", "keys", "apikey", "secret", "secrets",
            "password", "passwd", "credential", "credentials", "auth");

    /** Splits camelCase, kebab-case and snake_case keys into words. */
    private static final Pattern WORD_SPLIT =
            Pattern.compile("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    public static String marker(String label) {
        return MARKER_PREFIX + label + ">";
    }

    /**
     * Whether a config key name reads as a credential. The backstop for keys no tool
     * declared -- notably anything hand-added under {@link SpawnConfig}'s catch-all
     * {@code extras} -- so an unknown key errs towards being withheld.
     *
     * <p>Matching is on whole words, not substrings: {@code apiKey}, {@code api-key} and
     * {@code AUTH_TOKEN} are credentials, while {@code monkey} and {@code keyboardLayout}
     * are not. Substring matching would quietly blank out unrelated diagnostics.
     */
    public static boolean looksSecret(String key) {
        if (key == null || key.isBlank()) return false;
        for (var word : WORD_SPLIT.split(key)) {
            if (SECRET_NAME_HINTS.contains(word.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * A redacted config: the YAML to write, the paths that were redacted, and the raw values
     * that were removed (mapped to the path they came from) so the same values can be
     * scrubbed out of unstructured artifacts.
     */
    public record Redaction(String yaml, List<String> redactedPaths, Map<String, String> secretValues) {}

    /** Scrubbed text plus a per-class tally of what was removed. */
    public record Scrubbed(String text, Map<String, Integer> hits) {}

    // ---- Structural config redaction ----

    public static Redaction redactConfig(SpawnConfig config) {
        return redactConfig(config, SecretRegistry.locations());
    }

    public static Redaction redactConfig(SpawnConfig config, List<SecretRegistry.SecretLocation> locations) {
        var tree = JSON.valueToTree(config);
        if (!(tree instanceof ObjectNode root)) {
            return new Redaction("# could not serialize config\n", List.of(), Map.of());
        }
        var redacted = new ArrayList<String>();
        var values = new LinkedHashMap<String, String>();

        for (var location : locations) {
            redactPath(root, location.path(), redacted, values);
        }
        // Backstop: credential-shaped keys nobody declared. Scoped to the catch-all extras,
        // which is where an undeclared tool's config lands — the typed fields are covered by
        // declarations (SecretRedactorTest pins that), and some of them, like repo-paths, are
        // maps whose *keys are user data*. Running a key-name heuristic over those would
        // redact a repository called "secret-sauce" and then scrub its path out of every log
        // line, destroying the diagnostics this bundle exists to carry.
        for (var extra : config.getExtras().keySet()) {
            redactUndeclared(root, extra, extra, redacted, values);
        }

        try {
            return new Redaction(YAML.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    List.copyOf(redacted), Map.copyOf(values));
        } catch (Exception e) {
            return new Redaction("# could not serialize config: " + e.getMessage() + "\n",
                    List.copyOf(redacted), Map.copyOf(values));
        }
    }

    /**
     * Redact a declared secret: the exact path, and the same leaf name anywhere else under
     * the tool's namespace.
     *
     * <p>The deep pass is what keeps a declaration true as the config grows a dimension.
     * {@code claude.accounts.<name>.apiKey} arrived when credentials became a named map, and
     * an exact-path-only redactor would have written every account's token into the bundle
     * while reporting that it found no credentials to redact. The tool said "apiKey under
     * claude is a credential"; honouring that wherever it appears under {@code claude} is
     * faithful to the declaration rather than a second list to maintain. It stays narrow
     * because only a declared namespace/leaf pair earns the walk -- a map whose keys are user
     * data, like {@code repo-paths}, is neither.
     */
    private static void redactPath(ObjectNode root, String path,
                                   List<String> redacted, Map<String, String> values) {
        var segments = path.split("\\.");
        if (segments.length == 0) return;
        JsonNode node = root;
        for (int i = 0; i < segments.length - 1; i++) {
            node = node.get(segments[i]);
            if (!(node instanceof ObjectNode)) break;
        }
        var leaf = segments[segments.length - 1];
        if (node instanceof ObjectNode parent) {
            redactDeclared(parent, leaf, path, redacted, values);
        }
        if (segments.length > 1) {
            redactNested(root.get(segments[0]), segments[0], leaf, redacted, values);
        }
    }

    /** Redact every occurrence of a declared leaf name below its namespace, at any depth. */
    private static void redactNested(JsonNode node, String path, String leaf,
                                     List<String> redacted, Map<String, String> values) {
        if (node instanceof ObjectNode object) {
            for (var field : fieldNames(object)) {
                var childPath = path + "." + field;
                if (field.equals(leaf)) {
                    redactDeclared(object, field, childPath, redacted, values);
                } else {
                    redactNested(object.get(field), childPath, leaf, redacted, values);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                redactNested(array.get(i), path + "[" + i + "]", leaf, redacted, values);
            }
        }
    }

    /**
     * Redact one key that is known to be a secret -- because a tool declared it, or because
     * its name says so. A structured value is redacted leaf by leaf rather than skipped: a
     * credential stored as a list or an object is still a credential, and skipping it would
     * leave it in the bundle *and* out of the manifest, so nobody would know.
     */
    private static void redactDeclared(ObjectNode parent, String field, String path,
                                       List<String> redacted, Map<String, String> values) {
        var child = parent.get(field);
        if (child == null) return;
        if (child.isContainerNode()) {
            redactSubtree(child, path, redacted, values);
        } else if (replaceIfSecret(parent, field, path, values)) {
            // A path the declared pass already handled holds a marker by now, and
            // replaceIfSecret declines those — so the backstop cannot double-report.
            redacted.add(path);
        }
    }

    /** Redact every text leaf below a node whose key already marked it secret. */
    private static void redactSubtree(JsonNode node, String path,
                                      List<String> redacted, Map<String, String> values) {
        if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                var element = array.get(i);
                var elementPath = path + "[" + i + "]";
                if (element.isContainerNode()) {
                    redactSubtree(element, elementPath, redacted, values);
                } else if (element.isTextual() && replaceIfSecret(array, i, elementPath, values)) {
                    redacted.add(elementPath);
                }
            }
        } else if (node instanceof ObjectNode object) {
            for (var field : fieldNames(object)) {
                redactDeclared(object, field, path + "." + field, redacted, values);
            }
        }
    }

    /**
     * Walk an undeclared subtree, redacting values whose key name reads as a credential.
     *
     * <p>How far a name's authority reaches depends on what it covers, because <b>the nearest
     * key name governs</b>. A string is redacted. A <em>list</em> inherits the name, since its
     * elements have no names of their own to judge by -- {@code tokens: [...]} is a list of
     * tokens. An <em>object</em> does not: its fields carry their own names, and those are
     * better evidence than the parent's. Blanket-redacting an object cost far more than it
     * protected -- an ordinary {@code auth: {enabled: true, endpoint: ..., timeoutMs: ...}}
     * block lost every field, and the endpoint, now treated as a secret value, was scrubbed
     * out of every log in the archive. That is the {@code repo-paths} failure in another
     * shape: a name-matching false positive destroying the diagnostics the bundle carries.
     *
     * <p>A tool that stores a structured credential should declare it — a declared path is
     * taken at its word and redacted whole, whatever shape it holds.
     */
    private static void redactUndeclared(ObjectNode parent, String field, String path,
                                         List<String> redacted, Map<String, String> values) {
        var child = parent.get(field);
        if (child == null) return;
        if (looksSecret(field) && (child.isTextual() || child.isArray())) {
            redactDeclared(parent, field, path, redacted, values);
            return;
        }
        if (child instanceof ObjectNode object) {
            for (var nested : fieldNames(object)) {
                redactUndeclared(object, nested, path + "." + nested, redacted, values);
            }
        } else if (child instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i) instanceof ObjectNode element) {
                    for (var nested : fieldNames(element)) {
                        redactUndeclared(element, nested,
                                path + "[" + i + "]." + nested, redacted, values);
                    }
                }
            }
        }
    }

    private static List<String> fieldNames(ObjectNode object) {
        var fields = new ArrayList<String>();
        object.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    /**
     * Replace one field with its marker when it holds a non-blank value. Blank stays blank:
     * "configured but removed" and "never configured" must stay distinguishable.
     */
    private static boolean replaceIfSecret(ObjectNode parent, String field, String path,
                                           Map<String, String> values) {
        var value = parent.get(field);
        if (!isRedactableValue(value)) return false;
        // Two keys can hold the same literal secret. Keep the first path — declarations are
        // processed before the name backstop — so the log scrubber labels it with the key a
        // reader can actually look up, rather than whichever happened to be visited last.
        values.putIfAbsent(value.asText(), path);
        parent.put(field, marker(path));
        return true;
    }

    private static boolean replaceIfSecret(ArrayNode array, int index, String path,
                                           Map<String, String> values) {
        var value = array.get(index);
        if (!isRedactableValue(value)) return false;
        values.putIfAbsent(value.asText(), path);
        array.set(index, marker(path));
        return true;
    }

    private static boolean isRedactableValue(JsonNode value) {
        if (value == null || !value.isValueNode() || value.isNull()) return false;
        var text = value.asText();
        return !text.isBlank() && !text.startsWith(MARKER_PREFIX);
    }

    // ---- Text scrubbing (logs, instances.json) ----

    /**
     * A credential shape worth catching in text that has no model behind it, and what to
     * leave in its place -- either the marker alone, or the marker with the surrounding
     * syntax kept via {@code $n} group references (an {@code Authorization:} header is far
     * more useful in a log when it still reads as one).
     */
    private record ScrubPattern(String label, Pattern pattern, String replacement) {
        static ScrubPattern whole(String label, String regex) {
            return new ScrubPattern(label, Pattern.compile(regex), marker(label));
        }
        static ScrubPattern keeping(String label, String regex, String replacement) {
            return new ScrubPattern(label, Pattern.compile(regex), replacement);
        }
    }

    private static final List<ScrubPattern> PATTERNS = List.of(
            ScrubPattern.whole("private-key",
                    "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"),
            ScrubPattern.whole("anthropic-key", "sk-ant-[A-Za-z0-9_-]{8,}"),
            ScrubPattern.whole("openai-key", "sk-(?!ant-)[A-Za-z0-9_-]{20,}"),
            ScrubPattern.whole("github-token", "github_pat_[A-Za-z0-9_]{20,}"),
            ScrubPattern.whole("github-token", "gh[pousr]_[A-Za-z0-9]{20,}"),
            ScrubPattern.whole("google-key", "AIza[A-Za-z0-9_-]{30,}"),
            ScrubPattern.keeping("bearer-token",
                    "(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/-]{8,}=*",
                    "$1" + marker("bearer-token")),
            ScrubPattern.keeping("basic-auth",
                    "(?i)(authorization\\s*[:=]\\s*basic\\s+)[A-Za-z0-9+/]{8,}=*",
                    "$1" + marker("basic-auth")),
            // The value scrub runs first, so skip anything it already marked: re-matching a
            // marker would relabel it and lose the name of the key it came from.
            ScrubPattern.keeping("url-credentials",
                    "(?i)(https?://[^/\\s:@]+:)(?!" + Pattern.quote(MARKER_PREFIX) + ")[^/\\s@]+(@)",
                    "$1" + marker("url-credentials") + "$2")
    );

    /** Scrub known secret values and credential shapes out of unstructured text. */
    public static Scrubbed scrubText(String text, Map<String, String> secretValues) {
        if (text == null || text.isEmpty()) return new Scrubbed(text == null ? "" : text, Map.of());
        var hits = new LinkedHashMap<String, Integer>();
        var result = text;

        // Longest first: a secret that contains another must not be half-replaced.
        var byLength = new ArrayList<>(secretValues.keySet());
        byLength.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (var value : byLength) {
            if (value.length() < MIN_SCRUBBABLE_LENGTH) continue;
            result = replaceLiteral(result, value, hits, secretValues.get(value));
        }

        for (var scrub : PATTERNS) {
            result = applyPattern(result, scrub, hits);
        }
        return new Scrubbed(result, Map.copyOf(hits));
    }

    /** Replace every occurrence of a known secret with its marker, counting as it goes. */
    private static String replaceLiteral(String text, String value,
                                         Map<String, Integer> hits, String label) {
        int count = 0;
        for (int i = text.indexOf(value); i >= 0; i = text.indexOf(value, i + value.length())) {
            count++;
        }
        if (count == 0) return text;
        hits.merge(label, count, Integer::sum);
        return text.replace(value, marker(label));
    }

    private static String applyPattern(String text, ScrubPattern scrub, Map<String, Integer> hits) {
        var matcher = scrub.pattern().matcher(text);
        var sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            matcher.appendReplacement(sb, scrub.replacement());
            count++;
        }
        if (count == 0) return text;
        matcher.appendTail(sb);
        hits.merge(scrub.label(), count, Integer::sum);
        return sb.toString();
    }
}
