package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;

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
        var tree = Jjq.mapper().toJqValue(config);
        if (!(tree instanceof JqObject)) {
            return new Redaction("# could not serialize config\n", List.of(), Map.of());
        }
        // Collect replacements while walking, then fold them in: JqValue trees are
        // immutable, so unlike the old ObjectNode walk nothing is replaced in place.
        // A path recorded by an earlier pass is not recorded again (putIfAbsent below),
        // which is what keeps the name backstop from double-reporting a declared secret.
        var replacements = new LinkedHashMap<String, String>();
        var values = new LinkedHashMap<String, String>();

        for (var location : locations) {
            redactPath(tree, location.path(), replacements, values);
        }
        // Backstop: credential-shaped keys nobody declared. Scoped to the catch-all extras,
        // which is where an undeclared tool's config lands — the typed fields are covered by
        // declarations (SecretRedactorTest pins that), and some of them, like repo-paths, are
        // maps whose *keys are user data*. Running a key-name heuristic over those would
        // redact a repository called "secret-sauce" and then scrub its path out of every log
        // line, destroying the diagnostics this bundle exists to carry.
        for (var extra : config.getExtras().keySet()) {
            if (tree instanceof JqObject root) redactUndeclared(root, extra, extra, replacements, values);
        }

        var redacted = new ArrayList<>(replacements.keySet());
        var folded = tree;
        for (var entry : replacements.entrySet()) {
            folded = setPath(folded, entry.getKey(), io.hyperfoil.tools.jjq.value.JqString.of(entry.getValue()));
        }
        try {
            return new Redaction(JqYaml.toYaml(folded), List.copyOf(redacted), Map.copyOf(values));
        } catch (Exception e) {
            return new Redaction("# could not serialize config: " + e.getMessage() + "\n",
                    List.copyOf(redacted), Map.copyOf(values));
        }
    }

    /** Rebuild the tree with the dotted/indexed path set to a value, creating nothing. */
    static JqValue setPath(JqValue node, String path, JqValue leaf) {
        return setSegments(node, segments(path), 0, leaf);
    }

    private record Segment(String field, int index) {
        static Segment field(String name) { return new Segment(name, -1); }
        static Segment index(int i) { return new Segment(null, i); }
        boolean isIndex() { return field == null; }
    }

    static List<Segment> segments(String path) {
        var out = new ArrayList<Segment>();
        var current = new StringBuilder();
        Runnable flush = () -> {
            if (!current.isEmpty()) { out.add(Segment.field(current.toString())); current.setLength(0); }
        };
        for (int i = 0; i < path.length(); i++) {
            var c = path.charAt(i);
            if (c == '.') flush.run();
            else if (c == '[') {
                flush.run();
                var end = path.indexOf(']', i);
                out.add(Segment.index(Integer.parseInt(path.substring(i + 1, end))));
                i = end;
            } else current.append(c);
        }
        flush.run();
        return out;
    }

    private static JqValue setSegments(JqValue node, List<Segment> segments, int i, JqValue leaf) {
        if (i >= segments.size()) return leaf;
        var segment = segments.get(i);
        if (segment.isIndex()) {
            if (!(node instanceof JqArray array) || segment.index() < 0 || segment.index() >= array.size()) {
                return node;
            }
            var builder = JqArray.arrayBuilder();
            for (int j = 0; j < array.size(); j++) {
                builder.add(j == segment.index() ? setSegments(array.getElement(j), segments, i + 1, leaf)
                        : array.getElement(j));
            }
            return builder.build();
        }
        if (!(node instanceof JqObject object) || !object.keys().contains(segment.field())) return node;
        var builder = JqObject.builder(object.size());
        object.forEach((key, value) -> builder.put(key,
                key.equals(segment.field()) ? setSegments(value, segments, i + 1, leaf) : value));
        return builder.build();
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
    private static void redactPath(JqValue root, String path,
                               Map<String, String> replacements, Map<String, String> values) {
        var segments = path.split("\\.");
        if (segments.length == 0) return;
        JqValue node = root;
        for (int i = 0; i < segments.length - 1; i++) {
            if (!(node instanceof JqObject object)) break;
            node = object.getField(segments[i]);
            if (!(node instanceof JqObject)) break;
        }
        var leaf = segments[segments.length - 1];
        if (node instanceof JqObject parent) {
            redactDeclared(parent, leaf, path, replacements, values);
        }
        if (segments.length > 1 && root instanceof JqObject rootObj) {
            redactNested(rootObj.getField(segments[0]), segments[0], leaf, replacements, values);
        }
    }

    /** Redact every occurrence of a declared leaf name below its namespace, at any depth. */
    private static void redactNested(JqValue node, String path, String leaf,
                                 Map<String, String> replacements, Map<String, String> values) {
        if (node instanceof JqObject object) {
            for (var field : object.keys()) {
                var childPath = path + "." + field;
                if (field.equals(leaf)) {
                    redactDeclared(object, field, childPath, replacements, values);
                } else {
                    redactNested(object.getField(field), childPath, leaf, replacements, values);
                }
            }
        } else if (node instanceof JqArray array) {
            for (int i = 0; i < array.size(); i++) {
                redactNested(array.getElement(i), path + "[" + i + "]", leaf, replacements, values);
            }
        }
    }

    /**
     * Redact one key that is known to be a secret -- because a tool declared it, or because
     * its name says so. A structured value is redacted leaf by leaf rather than skipped: a
     * credential stored as a list or an object is still a credential, and skipping it would
     * leave it in the bundle *and* out of the manifest, so nobody would know.
     */
    private static void redactDeclared(JqObject parent, String field, String path,
                                   Map<String, String> replacements, Map<String, String> values) {
        if (!parent.keys().contains(field)) return;
        var child = parent.getField(field);
        if (child.isContainer()) {
            redactSubtree(child, path, replacements, values);
        } else {
            replaceIfSecret(child, path, replacements, values);
        }
    }

    /** Redact every text leaf below a node whose key already marked it secret. */
    private static void redactSubtree(JqValue node, String path,
                                  Map<String, String> replacements, Map<String, String> values) {
        if (node instanceof JqArray array) {
            for (int i = 0; i < array.size(); i++) {
                var element = array.getElement(i);
                var elementPath = path + "[" + i + "]";
                if (element.isContainer()) {
                    redactSubtree(element, elementPath, replacements, values);
                } else if (element.isString()) {
                    replaceIfSecret(element, elementPath, replacements, values);
                }
            }
        } else if (node instanceof JqObject object) {
            for (var field : object.keys()) {
                redactDeclared(object, field, path + "." + field, replacements, values);
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
    private static void redactUndeclared(JqObject parent, String field, String path,
                                     Map<String, String> replacements, Map<String, String> values) {
        if (!parent.keys().contains(field)) return;
        var child = parent.getField(field);
        if (looksSecret(field) && (child.isString() || child.isArray())) {
            redactDeclared(parent, field, path, replacements, values);
            return;
        }
        if (child instanceof JqObject object) {
            for (var nested : object.keys()) {
                redactUndeclared(object, nested, path + "." + nested, replacements, values);
            }
        } else if (child instanceof JqArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.getElement(i) instanceof JqObject element) {
                    for (var nested : element.keys()) {
                        redactUndeclared(element, nested,
                                path + "[" + i + "]." + nested, replacements, values);
                    }
                }
            }
        }
    }

    /**
     * Replace one field with its marker when it holds a non-blank value. Blank stays blank:
     * "configured but removed" and "never configured" must stay distinguishable.
     */
    /**
     * Record one value's replacement when it holds a non-blank value. Blank stays blank:
     * "configured but removed" and "never configured" must stay distinguishable.
     * An already-recorded path is left alone: the declared pass runs before the name
     * backstop, so the first recording wins and a secret is never double-reported.
     */
    private static void replaceIfSecret(JqValue value, String path,
                                        Map<String, String> replacements, Map<String, String> values) {
        if (!isRedactableValue(value)) return;
        // Two keys can hold the same literal secret. Keep the first path — declarations are
        // processed before the name backstop — so the log scrubber labels it with the key a
        // reader can actually look up, rather than whichever happened to be visited last.
        values.putIfAbsent(value.asText(), path);
        replacements.putIfAbsent(path, marker(path));
    }

    private static boolean isRedactableValue(JqValue value) {
        if (value == null || value.isContainer() || value.isNull()) return false;
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

    /** Whether the text contains anything shaped like a credential (the patterns {@link #scrubText} removes). */
    public static boolean hasSecretShape(String text) {
        if (text == null || text.isEmpty()) return false;
        return PATTERNS.stream().anyMatch(scrub -> scrub.pattern().matcher(text).find());
    }

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
