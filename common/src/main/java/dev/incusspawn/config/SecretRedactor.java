package dev.incusspawn.config;

import dev.incusspawn.util.BuildOutput;
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
        // A location recorded by an earlier pass is not recorded again (putIfAbsent below),
        // which is what keeps the name backstop from double-reporting a declared secret.
        // Locations are kept as key/index segments, never re-split from the dotted path they
        // are reported as: a key may itself contain '.' or '[' (#1263 review).
        var replacements = new LinkedHashMap<List<Object>, String>();
        var values = new LinkedHashMap<String, String>();

        for (var location : locations) {
            redactPath(tree, location.path(), replacements, values);
        }
        // Backstop: credential-shaped keys nobody declared. Scoped to the undeclared keys the
        // config keeps: the catch-all extras, where an undeclared tool's config lands, those of
        // each typed namespace and those of each of its accounts — the typed fields are covered by
        // declarations (SecretRedactorTest pins that), and some of them, like repo-paths, are
        // maps whose *keys are user data*. Running a key-name heuristic over those would
        // redact a repository called "secret-sauce" and then scrub its path out of every log
        // line, destroying the diagnostics this bundle exists to carry.
        var root = (JqObject) tree;
        for (var extra : config.getExtras().keySet()) {
            redactUndeclared(root, extra, List.of(extra), replacements, values);
        }
        for (var namespace : config.namespaceConfigs()) {
            if (!(root.getField(namespace.namespace()) instanceof JqObject node)) continue;
            var accountsNode = node.getField(AccountResolver.ACCOUNTS_KEY);
            for (var extra : namespace.getExtras().keySet()) {
                // An accounts block kept as an extra is walked once, by the loop below
                if (AccountResolver.ACCOUNTS_KEY.equals(extra) && accountsNode instanceof JqObject) continue;
                redactUndeclared(node, extra, List.of(namespace.namespace(), extra), replacements, values);
            }
            // Whether a namespace types its accounts (Claude) or keeps them as extras, every key
            // of each account is walked; one the declared pass recorded is not recorded again
            if (!(accountsNode instanceof JqObject accounts)) continue;
            for (var name : accounts.keys()) {
                redactUndeclared(accounts, name, List.of(namespace.namespace(),
                        AccountResolver.ACCOUNTS_KEY, name), replacements, values);
            }
        }

        var redacted = new ArrayList<>(replacements.values());
        var folded = tree;
        for (var entry : replacements.entrySet()) {
            folded = setSegments(folded, entry.getKey(), 0,
                    io.hyperfoil.tools.jjq.value.JqString.of(marker(entry.getValue())));
        }
        try {
            return new Redaction(JqYaml.toYaml(folded), List.copyOf(redacted), Map.copyOf(values));
        } catch (Exception e) {
            return new Redaction("# could not serialize config: " + e.getMessage() + "\n",
                    List.copyOf(redacted), Map.copyOf(values));
        }
    }

    /** Rebuild the tree with the value at {@code at} (keys and indexes) replaced, creating nothing. */
    private static JqValue setSegments(JqValue node, List<Object> at, int i, JqValue leaf) {
        if (i >= at.size()) return leaf;
        if (at.get(i) instanceof Integer index) {
            if (!(node instanceof JqArray array) || index < 0 || index >= array.size()) return node;
            var builder = JqArray.arrayBuilder();
            for (int j = 0; j < array.size(); j++) {
                builder.add(j == index ? setSegments(array.getElement(j), at, i + 1, leaf) : array.getElement(j));
            }
            return builder.build();
        }
        var field = (String) at.get(i);
        if (!(node instanceof JqObject object) || !object.keys().contains(field)) return node;
        var builder = JqObject.builder(object.size());
        object.forEach((key, value) -> builder.put(key,
                key.equals(field) ? setSegments(value, at, i + 1, leaf) : value));
        return builder.build();
    }

    /**
     * The label a location is reported as: dotted, as a reader looks it up, with an index as
     * {@code [i]} and a key that holds '.', '[' or ']' itself written {@code ["..."]}, so two
     * locations never read the same ({@code a.token} the nested key, {@code ["a.token"]} the one
     * key).
     */
    static String label(List<Object> at) {
        var label = new StringBuilder();
        for (var segment : at) {
            if (segment instanceof Integer index) {
                label.append('[').append(index).append(']');
            } else {
                var key = String.valueOf(segment);
                if (key.indexOf('.') >= 0 || key.indexOf('[') >= 0 || key.indexOf(']') >= 0) {
                    label.append("[\"").append(key.replace("\"", "\\\"")).append("\"]");
                } else {
                    if (!label.isEmpty()) label.append('.');
                    label.append(key);
                }
            }
        }
        return label.toString();
    }

    private static List<Object> with(List<Object> at, Object segment) {
        var next = new ArrayList<Object>(at.size() + 1);
        next.addAll(at);
        next.add(segment);
        return next;
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
                               Map<List<Object>, String> replacements, Map<String, String> values) {
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
            redactDeclared(parent, leaf, java.util.Arrays.<Object>asList(segments), replacements, values);
        }
        if (segments.length > 1 && root instanceof JqObject rootObj) {
            redactNested(rootObj.getField(segments[0]), List.of(segments[0]), leaf, replacements, values);
        }
    }

    /** Redact every occurrence of a declared leaf name below its namespace, at any depth. */
    private static void redactNested(JqValue node, List<Object> at, String leaf,
                                 Map<List<Object>, String> replacements, Map<String, String> values) {
        if (node instanceof JqObject object) {
            for (var field : object.keys()) {
                if (field.equals(leaf)) {
                    redactDeclared(object, field, with(at, field), replacements, values);
                } else {
                    redactNested(object.getField(field), with(at, field), leaf, replacements, values);
                }
            }
        } else if (node instanceof JqArray array) {
            for (int i = 0; i < array.size(); i++) {
                redactNested(array.getElement(i), with(at, i), leaf, replacements, values);
            }
        }
    }

    /**
     * Redact one key that is known to be a secret -- because a tool declared it, or because
     * its name says so. A structured value is redacted leaf by leaf rather than skipped: a
     * credential stored as a list or an object is still a credential, and skipping it would
     * leave it in the bundle *and* out of the manifest, so nobody would know.
     */
    private static void redactDeclared(JqObject parent, String field, List<Object> at,
                                   Map<List<Object>, String> replacements, Map<String, String> values) {
        if (!parent.keys().contains(field)) return;
        var child = parent.getField(field);
        if (child.isContainer()) {
            redactSubtree(child, at, replacements, values);
        } else {
            replaceIfSecret(child, at, replacements, values);
        }
    }

    /** Redact every text leaf below a node whose key already marked it secret. */
    private static void redactSubtree(JqValue node, List<Object> at,
                                  Map<List<Object>, String> replacements, Map<String, String> values) {
        if (node instanceof JqArray array) {
            for (int i = 0; i < array.size(); i++) {
                var element = array.getElement(i);
                if (element.isContainer()) {
                    redactSubtree(element, with(at, i), replacements, values);
                } else if (element.isString()) {
                    replaceIfSecret(element, with(at, i), replacements, values);
                }
            }
        } else if (node instanceof JqObject object) {
            for (var field : object.keys()) {
                redactDeclared(object, field, with(at, field), replacements, values);
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
    private static void redactUndeclared(JqObject parent, String field, List<Object> at,
                                     Map<List<Object>, String> replacements, Map<String, String> values) {
        if (!parent.keys().contains(field)) return;
        var child = parent.getField(field);
        if (looksSecret(field) && (child.isString() || child.isArray())) {
            redactDeclared(parent, field, at, replacements, values);
            return;
        }
        if (child instanceof JqObject object) {
            for (var nested : object.keys()) {
                redactUndeclared(object, nested, with(at, nested), replacements, values);
            }
        } else if (child instanceof JqArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.getElement(i) instanceof JqObject element) {
                    for (var nested : element.keys()) {
                        redactUndeclared(element, nested, with(with(at, i), nested), replacements, values);
                    }
                }
            }
        }
    }

    /**
     * Record one value's replacement when it holds a non-blank value. Blank stays blank:
     * "configured but removed" and "never configured" must stay distinguishable.
     * An already-recorded path is left alone: the declared pass runs before the name
     * backstop, so the first recording wins and a secret is never double-reported.
     */
    private static void replaceIfSecret(JqValue value, List<Object> at,
                                        Map<List<Object>, String> replacements, Map<String, String> values) {
        if (!isRedactableValue(value)) return;
        // Two keys can hold the same literal secret. Keep the first path — declarations are
        // processed before the name backstop — so the log scrubber labels it with the key a
        // reader can actually look up, rather than whichever happened to be visited last.
        var path = label(at);
        values.putIfAbsent(value.asText(), path);
        replacements.putIfAbsent(List.copyOf(at), path);
    }

    private static boolean isRedactableValue(JqValue value) {
        if (value == null || value.isContainer() || value.isNull()) return false;
        var text = value.asText();
        return !text.isBlank() && !text.startsWith(MARKER_PREFIX);
    }

    // ---- Text scrubbing (logs, instances.json) ----

    /**
     * A credential shape worth catching in text that has no model behind it. Its match is
     * replaced by the marker, but for the surrounding syntax a {@link #keeping} pattern holds in
     * its groups: the text before the secret as group 1, and any after it as group 2 (an
     * {@code Authorization:} header is far more useful in a log when it still reads as one).
     * A match whose groups do not frame it so is redacted whole ({@link #secret}).
     */
    record ScrubPattern(String label, Pattern pattern) {
        ScrubPattern {
            int groups = pattern.matcher("").groupCount();
            if (groups > 2) throw new IllegalArgumentException(label + ": only the text around the secret is a group");
        }
        static ScrubPattern whole(String label, String regex) {
            return whole(label, Pattern.compile(regex));
        }
        static ScrubPattern whole(String label, Pattern pattern) {
            if (pattern.matcher("").groupCount() != 0) throw new IllegalArgumentException(label + ": the whole match is the secret");
            return new ScrubPattern(label, pattern);
        }
        static ScrubPattern keeping(String label, String regex) {
            var pattern = Pattern.compile(regex);
            if (pattern.matcher("").groupCount() == 0) throw new IllegalArgumentException(label + ": keeps nothing");
            return new ScrubPattern(label, pattern);
        }

        /**
         * The secret in a match, start and end: between what group 1 keeps before it and group 2
         * after it. It fails closed (#1882 review): unless group 1 took part and opens the match,
         * group 2, when there is one, took part and closes it, and something lies between them,
         * the whole match is the secret.
         */
        static int[] secret(java.util.regex.Matcher match) {
            int groups = match.groupCount();
            if (groups == 0) return new int[] {match.start(), match.end()};
            boolean framed = match.start(1) == match.start()
                    && (groups < 2 || match.start(2) >= 0 && match.end(2) == match.end());
            int start = framed ? match.end(1) : -1;
            int end = framed ? (groups >= 2 ? match.start(2) : match.end()) : -1;
            return framed && start < end ? new int[] {start, end} : new int[] {match.start(), match.end()};
        }
    }

    private static final String KEY_HEADER = "-----BEGIN [A-Z ]*PRIVATE KEY-----";

    private static final List<ScrubPattern> PATTERNS = List.of(
            // First, so an isx proof is named as one whatever the token shape its prefix borrows
            ScrubPattern.whole("isx-proof", dev.incusspawn.proxy.ProofToken.ANY_PROOF),
            // A key runs to its END line, or, cut short, to the next key's header or the end of the
            // text: all of it is secret. No match scans past another key's header, so the search is
            // linear; a lazy .*? to an END that never came was quadratic over guest output. A
            // header's words need no bound: their run ends at the next '-' at the latest, and every
            // header starts with one, so no two attempts read the same run
            ScrubPattern.whole("private-key",
                    "(?s)" + KEY_HEADER + "(?:(?!" + KEY_HEADER + ").)*?"
                            + "(?:-----END [A-Z ]*PRIVATE KEY-----|(?=" + KEY_HEADER + ")|\\z)"),
            ScrubPattern.whole("anthropic-key", "sk-ant-[A-Za-z0-9_-]{8,}"),
            ScrubPattern.whole("openai-key", "sk-(?!ant-)[A-Za-z0-9_-]{20,}"),
            ScrubPattern.whole("github-token", "github_pat_[A-Za-z0-9_]{20,}"),
            ScrubPattern.whole("github-token", "gh[pousr]_[A-Za-z0-9]{20,}"),
            ScrubPattern.whole("google-key", "AIza[A-Za-z0-9_-]{30,}"),
            ScrubPattern.keeping("bearer-token",
                    "(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/-]{8,}=*"),
            ScrubPattern.keeping("basic-auth",
                    "(?i)(authorization\\s*[:=]\\s*basic\\s+)[A-Za-z0-9+/]{8,}=*"),
            // The value scrub runs first, so skip anything it already marked: re-matching a
            // marker would relabel it and lose the name of the key it came from.
            ScrubPattern.keeping("url-credentials",
                    "(?i)(https?://[^/\\s:@]+:)(?!" + Pattern.quote(MARKER_PREFIX) + ")[^/\\s@]+(@)")
    );

    /** Whether the text contains anything shaped like a credential (the patterns {@link #scrubText} removes). */
    public static boolean hasSecretShape(String text) {
        if (text == null || text.isEmpty()) return false;
        return PATTERNS.stream().anyMatch(scrub -> scrub.pattern().matcher(text).find());
    }

    /** Scrub known secret values and credential shapes out of unstructured text. */
    public static Scrubbed scrubText(String text, Map<String, String> secretValues) {
        if (text == null || text.isEmpty()) return new Scrubbed(text == null ? "" : text, Map.of());
        var scrub = scrub(text, secretValues);
        return new Scrubbed(scrub.text, Map.copyOf(scrub.hits));
    }

    /**
     * What a guest printed, in {@code raw} pieces, as it is shown ({@link BuildOutput#guestText},
     * each piece on its own) and saved: with every credential shape redacted that a scrub of the
     * raw text or a scrub of the shown text finds (#1878). Neither alone is enough. Colour codes
     * can split a token that only matches once they are gone, while a control byte turned into a
     * space, or a carriage return dropping a key's BEGIN line, hides a shape the raw text shows.
     * What the raw scrub redacts is found in the shown text through {@link BuildOutput.Shown}, and
     * each run of what either redacts becomes one marker. A lone control splits a token in both,
     * so the shown text is also scrubbed with its controls dropped and its backspaces applied (#1888).
     */
    public static String scrubGuest(String... raw) {
        // Shown as printed, the common case: a raw scrub would find the same spans, so neither
        // the second scrub nor the map from shown to raw characters is needed
        boolean asPrinted = true;
        for (var piece : raw) {
            if (piece != null && !BuildOutput.showsAsPrinted(piece)) {
                asPrinted = false;
                break;
            }
        }
        if (asPrinted) {
            var printed = new StringBuilder();
            for (var piece : raw) if (piece != null) printed.append(piece);
            return scrubText(printed.toString(), Map.of()).text();
        }
        var rawText = new StringBuilder();
        var shownText = new StringBuilder();
        // Unboxed: guest output can run to megabytes, and showing it never adds a character
        int rawLength = 0;
        for (var piece : raw) rawLength += piece == null ? 0 : piece.length();
        var source = new int[rawLength];
        for (var piece : raw) {
            if (piece == null) continue;
            var shown = BuildOutput.shownGuestText(piece);
            var from = shown.source();
            for (int i = 0; i < from.length; i++) source[shownText.length() + i] = rawText.length() + from[i];
            shownText.append(shown.text());
            rawText.append(piece);
        }
        var text = shownText.toString();
        if (text.isEmpty()) return text;
        source = java.util.Arrays.copyOf(source, text.length());
        var seen = scrub(text, Map.of());
        var spans = new ArrayList<>(seen.spans);
        boolean wider = addSpans(rawText, null, null, source, seen.spans, spans);
        // A lone control the shown text spaces ends a token there, as the control does in the raw
        // text: with the controls dropped it is whole, and with a backspace's overstrike applied a
        // token man prints bold or underlined is (#1888). Showing changes a character only by
        // spacing a control, so a shown character unlike its raw one is such a control
        wider |= addControlFreeSpans(text, rawText, source, seen.spans, spans);
        if (!wider) return seen.text;
        // Earliest first, so a run is named by what starts it, and the shown scrub's when both do
        spans.sort(java.util.Comparator.comparingInt(Span::start));
        var out = new StringBuilder(text.length());
        int pos = 0;
        for (int i = 0; i < spans.size(); ) {
            var first = spans.get(i);
            int end = first.end();
            for (i++; i < spans.size() && spans.get(i).start() < end; i++) end = Math.max(end, spans.get(i).end());
            out.append(text, pos, first.start()).append(marker(first.label()));
            pos = end;
        }
        out.append(text, pos, text.length());
        // Once more as one text, as a scrub of the shown text would have seen what the raw one marked
        return scrubText(out.toString(), Map.of()).text();
    }

    /**
     * Adds to {@code spans} what a scrub of the shown {@code text} with its lone controls dropped,
     * and with its backspaces applied as overstrikes, redacts. Whether either redacts a character
     * the shown scrub's {@code seen} spans leave.
     */
    private static boolean addControlFreeSpans(String text, CharSequence rawText, int[] source,
                                               List<Span> seen, List<Span> spans) {
        // Most text that is not shown as printed only loses escapes: nothing to build a view for
        int spaced = 0;
        boolean backspace = false;
        for (int i = 0; i < text.length(); i++) {
            char raw = rawText.charAt(source[i]);
            if (text.charAt(i) != raw) {
                spaced++;
                backspace |= raw == '\b';
            }
        }
        if (spaced == 0) return false;
        var dropped = new StringBuilder(text.length() - spaced);
        var droppedFrom = new int[text.length() - spaced];
        for (int i = 0; i < text.length(); i++) {
            char raw = rawText.charAt(source[i]);
            if (text.charAt(i) == raw) {
                droppedFrom[dropped.length()] = source[i];
                dropped.append(raw);
            }
        }
        boolean wider = addSpans(dropped, droppedFrom, droppedFrom, source, seen, spans);
        if (!backspace) return wider;
        // A backspace strikes the character before it out, never past the start of its line. What
        // strikes it out redacts it with itself, so no struck half of a token is kept beside a marker
        var struck = new StringBuilder(text.length());
        var struckFrom = new int[text.length()];
        var struckStart = new int[text.length()];
        int pendingStart = -1;
        for (int i = 0; i < text.length(); i++) {
            char raw = rawText.charAt(source[i]);
            if (text.charAt(i) == raw) {
                int at = struck.length();
                struckFrom[at] = source[i];
                struckStart[at] = pendingStart >= 0 ? pendingStart : source[i];
                pendingStart = -1;
                struck.append(raw);
            } else if (raw == '\b' && !struck.isEmpty() && struck.charAt(struck.length() - 1) != '\n') {
                int last = struck.length() - 1;
                // A whole code point, as a terminal strikes it
                if (last > 0 && Character.isSurrogatePair(struck.charAt(last - 1), struck.charAt(last))) last--;
                pendingStart = struckStart[last];
                struck.setLength(last);
            }
        }
        return addSpans(struck, struckStart, struckFrom, source, seen, spans) | wider;
    }

    /**
     * Adds to {@code spans}, in the shown text, what a scrub of {@code view} redacts. Its character
     * {@code i} redacts the raw text from {@code startIn[i]} to {@code endIn[i]}, both null for the
     * raw text itself; {@code source} maps the shown text to it. Whether any redacts a character the
     * shown scrub's {@code seen} spans leave.
     */
    private static boolean addSpans(CharSequence view, int[] startIn, int[] endIn, int[] source,
                                    List<Span> seen, List<Span> spans) {
        boolean wider = false;
        // Both earliest first, the shown ones swept once with a cursor as these advance
        int cursor = 0;
        for (var span : scrub(view.toString(), Map.of()).spans) {
            if (span.start() >= span.end()) continue;
            int rawStart = startIn == null ? span.start() : startIn[span.start()];
            int rawEnd = endIn == null ? span.end() : endIn[span.end() - 1] + 1;
            int start = firstShownFrom(source, rawStart);
            int end = firstShownFrom(source, rawEnd);
            if (start >= end) continue;
            while (cursor < seen.size() && seen.get(cursor).end() <= start) cursor++;
            if (!covered(seen, cursor, start, end)) wider = true;
            spans.add(new Span(start, end, span.label()));
        }
        return wider;
    }

    /** The first shown character at or after {@code rawIndex} in the raw text, or past the last. */
    private static int firstShownFrom(int[] source, int rawIndex) {
        int low = 0, high = source.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (source[mid] < rawIndex) low = mid + 1; else high = mid;
        }
        return low;
    }

    /**
     * Whether {@code sorted}, earliest first, redact every character from {@code start} to
     * {@code end}, looking from {@code from}: none before it ends after {@code start}.
     */
    private static boolean covered(List<Span> sorted, int from, int start, int end) {
        int at = start;
        for (int i = from; i < sorted.size() && sorted.get(i).start() <= at; i++) {
            at = Math.max(at, sorted.get(i).end());
            if (at >= end) return true;
        }
        return at >= end;
    }

    /** A redaction: what it replaced, from {@code start} to {@code end} of the text first given. */
    private record Span(int start, int end, String label) {}

    /**
     * Text being scrubbed, the known values first and then each pattern in turn over what the last
     * left, and what each step redacted of the text first given. Each character keeps where it came
     * from; a marker's span all that it replaced, so a later match that takes in an earlier
     * marker redacts the original text under both.
     */
    private static final class Scrub {
        String text;
        /** Null while nothing is replaced: each character is then its own origin. */
        int[] from;
        int[] to;
        /**
         * Earliest first. Each claims only redacted characters; two regions of one pass that take in
         * the same marker can overlap, which every reader accepts.
         */
        List<Span> spans = new ArrayList<>();
        final Map<String, Integer> hits = new LinkedHashMap<>();
        /** Spans visited keeping {@link #spans}: linear in the matches, which a test pins. */
        long work;

        Scrub(String text) {
            this.text = text;
        }

        private int from(int i) {
            return from == null ? i : from[i];
        }

        private int to(int i) {
            return to == null ? i + 1 : to[i];
        }

        /** Replace each of {@code regions}, start and end in the current text, in order and apart, by {@code label}'s marker. */
        void replace(List<int[]> regions, String label) {
            if (regions.isEmpty()) return;
            hits.merge(label, regions.size(), Integer::sum);
            var marker = marker(label);
            var next = new StringBuilder(text.length());
            var nextFrom = new int[text.length() + regions.size() * marker.length()];
            var nextTo = new int[nextFrom.length];
            int pos = 0;
            var added = new ArrayList<Span>(regions.size());
            for (var region : regions) {
                for (int i = pos; i < region[0]; i++) {
                    nextFrom[next.length()] = from(i);
                    nextTo[next.length()] = to(i);
                    next.append(text.charAt(i));
                }
                int start = from(region[0]);
                int end = to(region[1] - 1);
                added.add(new Span(start, end, label));
                for (int i = 0; i < marker.length(); i++) {
                    nextFrom[next.length()] = start;
                    nextTo[next.length()] = end;
                    next.append(marker.charAt(i));
                }
                pos = region[1];
            }
            for (int i = pos; i < text.length(); i++) {
                nextFrom[next.length()] = from(i);
                nextTo[next.length()] = to(i);
                next.append(text.charAt(i));
            }
            text = next.toString();
            from = nextFrom;
            to = nextTo;
            spans = merged(spans, added);
        }

        /**
         * {@code old} and {@code added}, both earliest first, in one pass: an old span inside an
         * added one was swallowed by its match, and every other is kept. A match takes in a
         * marker whole, so none overlaps one in part, but one that did would be kept too.
         */
        private List<Span> merged(List<Span> old, List<Span> added) {
            var result = new ArrayList<Span>(old.size() + added.size());
            int i = 0;
            for (var span : added) {
                for (; i < old.size() && old.get(i).start() < span.start(); i++, work++) result.add(old.get(i));
                result.add(span);
                work++;
                for (; i < old.size() && old.get(i).start() < span.end(); i++, work++) {
                    if (old.get(i).end() > span.end()) result.add(old.get(i));
                }
            }
            for (; i < old.size(); i++, work++) result.add(old.get(i));
            return result;
        }
    }

    /** How many spans {@code text}'s scrub visited, for the test that keeps it linear. */
    static long scrubWork(String text) {
        return scrub(text, Map.of()).work;
    }

    private static Scrub scrub(String text, Map<String, String> secretValues) {
        var scrub = new Scrub(text);
        // Longest first: a secret that contains another must not be half-replaced.
        var byLength = new ArrayList<>(secretValues.keySet());
        byLength.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (var value : byLength) {
            if (value.length() < MIN_SCRUBBABLE_LENGTH) continue;
            var regions = new ArrayList<int[]>();
            for (int i = scrub.text.indexOf(value); i >= 0; i = scrub.text.indexOf(value, i + value.length())) {
                regions.add(new int[] {i, i + value.length()});
            }
            scrub.replace(regions, secretValues.get(value));
        }
        for (var pattern : PATTERNS) {
            var regions = new ArrayList<int[]>();
            var matcher = pattern.pattern().matcher(scrub.text);
            while (matcher.find()) {
                // An empty match has nothing to redact, and no character to say where it came from
                if (matcher.end() > matcher.start()) regions.add(ScrubPattern.secret(matcher));
            }
            scrub.replace(regions, pattern.label());
        }
        return scrub;
    }
}
