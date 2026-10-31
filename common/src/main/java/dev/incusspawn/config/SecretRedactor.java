package dev.incusspawn.config;

import dev.incusspawn.util.BuildOutput;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
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
            redactDeclared(parent, leaf, Arrays.<Object>asList(segments), replacements, values);
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
        static int[] secret(Matcher match) {
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
     * each run of what either redacts becomes one marker. A lone control or a character that
     * draws no cell splits a token in both, so the shown text is also scrubbed with those dropped
     * (#1888, #1900), and the raw text as a terminal's screen shows it ({@link Screen}).
     */
    public static String scrubGuest(String... raw) {
        // Shown as printed, the common case: a raw scrub would find the same spans, so neither
        // the second scrub nor the map from shown to raw characters is needed
        boolean asPrinted = true;
        boolean drawn = true;
        for (var piece : raw) {
            if (piece == null) continue;
            if (!BuildOutput.showsAsPrinted(piece)) {
                asPrinted = false;
                break;
            }
            if (drawn && piece.codePoints().anyMatch(SecretRedactor::mayDrawNoCell)) drawn = false;
        }
        if (asPrinted && drawn) {
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
        // A screen shows what the shown text does until the cursor moves back: most output never does
        Screen screen = null;
        if (!asPrinted) {
            for (var piece : raw) {
                if (piece != null && Screen.mayMoveBack(piece)) {
                    screen = new Screen(rawLength, true);
                    break;
                }
            }
        }
        for (var piece : raw) {
            if (piece == null) continue;
            var shown = BuildOutput.shownGuestText(piece);
            var from = shown.source();
            for (int i = 0; i < from.length; i++) source[shownText.length() + i] = rawText.length() + from[i];
            shownText.append(shown.text());
            if (screen != null) screen.print(piece, rawText.length());
            rawText.append(piece);
        }
        var text = shownText.toString();
        if (text.isEmpty()) return text;
        source = Arrays.copyOf(source, text.length());
        var seen = scrub(text, Map.of());
        var spans = new ArrayList<>(seen.spans);
        boolean wider = false;
        // Shown as printed, the raw text is the shown one
        if (!asPrinted) wider = addSpans(rawText, null, null, source, seen.spans, spans);
        if (screen != null && screen.moved()) {
            var view = screen.view();
            wider |= addSpans(view.text, view.start, view.end, source, seen.spans, spans);
        }
        if (screen != null && screen.absoluteRowsMayDiffer()) {
            // Absolute rows counted from the first one used instead of the top of the screen
            var other = new Screen(rawLength, false);
            int offset = 0;
            for (var piece : raw) {
                if (piece == null) continue;
                other.print(piece, offset);
                offset += piece.length();
            }
            if (other.moved()) {
                var view = other.view();
                wider |= addSpans(view.text, view.start, view.end, source, seen.spans, spans);
            }
        }
        // A lone control the shown text spaces ends a token there, as the control does in the raw
        // text, and a character that draws no cell ends it in both: with them dropped it is whole,
        // as it is on the screen. Showing changes a character only by spacing a control, so a
        // shown character unlike its raw one is such a control
        wider |= addDroppedSpans(text, rawText, source, seen.spans, spans);
        if (!wider) return seen.text;
        // Earliest first, so a run is named by what starts it, and the shown scrub's when both do
        spans.sort(Comparator.comparingInt(Span::start));
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
     * Adds to {@code spans} what a scrub of the shown {@code text} with its lone controls and the
     * characters that may draw no cell ({@link #mayDrawNoCell}) dropped redacts. Whether it redacts a
     * character the shown scrub's {@code seen} spans leave.
     */
    private static boolean addDroppedSpans(String text, CharSequence rawText, int[] source,
                                           List<Span> seen, List<Span> spans) {
        // Most text that is not shown as printed only loses escapes: nothing to build a view for
        int kept = 0;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            int width = Character.charCount(c);
            if (c == rawText.charAt(source[i]) || width == 2) {
                if (!mayDrawNoCell(c)) kept += width;
            }
            i += width;
        }
        if (kept == text.length()) return false;
        var dropped = new StringBuilder(kept);
        var droppedFrom = new int[kept];
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            int width = Character.charCount(c);
            if ((c == rawText.charAt(source[i]) || width == 2) && !mayDrawNoCell(c)) {
                for (int j = i; j < i + width; j++) {
                    droppedFrom[dropped.length()] = source[j];
                    dropped.append(text.charAt(j));
                }
            }
            i += width;
        }
        return addSpans(dropped, droppedFrom, droppedFrom, source, seen, spans);
    }

    /**
     * How many cells a terminal draws {@code c} in: none for a combining mark, a format
     * character (U+200B, a joiner, a byte order mark) or a Hangul medial vowel, two for an East
     * Asian wide or fullwidth character and most emoji, one for every other (#1900). A soft
     * hyphen is drawn, one cell wide, on most terminals. Ranges follow {@code wcwidth}; a
     * terminal that disagrees on one shows it a cell off from the {@link Screen}.
     */
    static int cellWidth(int c) {
        if (c < 0x300) return 1;
        int type = Character.getType(c);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.FORMAT
                || c >= 0x1160 && c <= 0x11FF || c >= 0xD7B0 && c <= 0xD7FF) {
            return 0;
        }
        for (int i = 0; i < WIDE.length; i += 2) {
            if (c < WIDE[i]) return 1;
            if (c <= WIDE[i + 1]) return 2;
        }
        return 1;
    }

    /**
     * Whether some terminal draws {@code c} in no cell: what {@link #cellWidth} gives none, and a
     * soft hyphen, which most draw one cell wide and some hide. Dropping it can only widen a span.
     */
    static boolean mayDrawNoCell(int c) {
        return c == 0x00AD || cellWidth(c) == 0;
    }

    /** The code points {@link #cellWidth} draws in two cells, in ascending ranges of first and last. */
    private static final int[] WIDE = {
            0x1100, 0x115F, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC, 0x23F0, 0x23F0,
            0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615, 0x2648, 0x2653, 0x267F, 0x267F,
            0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5,
            0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5,
            0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728,
            0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797,
            0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55,
            0x2E80, 0x303E, 0x3041, 0x33FF, 0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xA000, 0xA4CF,
            0xA960, 0xA97F, 0xAC00, 0xD7A3, 0xF900, 0xFAFF, 0xFE10, 0xFE19, 0xFE30, 0xFE6F,
            0xFF00, 0xFF60, 0xFFE0, 0xFFE6, 0x16FE0, 0x16FE4, 0x17000, 0x18AFF, 0x1B000, 0x1B2FF,
            0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F200, 0x1F251,
            0x1F300, 0x1F64F, 0x1F680, 0x1F6FF, 0x1F7E0, 0x1F7EB, 0x1F90C, 0x1F9FF, 0x1FA70, 0x1FAFF,
            0x20000, 0x2FFFD, 0x30000, 0x3FFFD,
    };

    /**
     * The raw text as a terminal's screen shows it once its cursor has moved (#1888, #1900): what
     * a character draws overwrites the cells under it and leaves those to its right. A backspace
     * moves one cell left and a carriage return to the start of the line, never past it; a tab
     * moves to the next stop of eight, a line feed to the next line, a vertical tab, form feed
     * or 8-bit IND down a line, an 8-bit NEL to the next line and RI up one. Of the control
     * sequences, those that move the cursor along a line or up or down it (CUF, HPR, CUB, CHA,
     * HPA, CHT, CBT, CUU, CUD, VPR, CNL, CPL, CUP, VPA) and the escapes DECBI and DECFI do so
     * here, a saved position is restored (DECSC and DECRC, SCOSC and SCORC, xterm's 1048 and
     * 1049) and REP repeats the character last drawn; the rest move nothing. Rows are counted from
     * where each piece starts. An absolute row (CUP, VPA) counts from there too, as if the piece
     * started at the top of the screen, and on a second screen from where the first absolute row
     * is, as output that has scrolled addresses it ({@link #absoluteRow}, #1916 review). The
     * alternate screen (xterm's 47, 1047 and 1049) is a region of rows of its own, with its own
     * saved cursor, so what a program draws there neither overwrites the main screen nor is lost.
     * <p>
     * Each cell redacts every raw character written to it, from its {@code start} to its
     * {@code end}, so an overwritten half of a token is never kept beside a marker, nor one a
     * token drawn whole there once left. A row repainted from further down then also redacts
     * what lies between, which fails closed (#1916). A character
     * that draws no cell ({@link #cellWidth}) joins the cell before the cursor and the one last
     * written, and is left out of the view, so it splits no token. A wide character takes two
     * cells, and overwriting either clears the other. Erasing is not modelled: a cell the screen
     * shows blank never holds a token's character, so what this view keeps there only widens a
     * span. Only written cells are kept, by row and column, so what a scrub holds grows with what
     * the guest printed and never with how far it moved the cursor; REP draws at most
     * {@link #MAX_REPEATS_PER_CHAR} cells for each raw character, the rest no more.
     */
    private static final class Screen {
        /** Wider than any terminal: a real one stops its cursor at its last column. */
        static final int MAX_COLUMN = 4096;
        private static final char ESC = '\u001b'; // raw ANSI: read from guest output, never written
        private static final int COLUMN_BITS = 13;
        /**
         * Rows past this are where the cursor stays, so no key overflows. A raw character moves the
         * cursor down at most {@link #MAX_COLUMN} rows, so no text a String holds comes near it: a
         * guest cannot pile its lines onto one row, where a line feed would overwrite the last.
         */
        static final long MAX_ROW = (1L << 48) - 1;
        /** The first row of the alternate screen's region, past every row of the main one. */
        static final long ALTERNATE = MAX_ROW + 1;
        static final int MAX_REPEATS_PER_CHAR = 1;
        private static final byte NARROW = 1, WIDE_HEAD = 2, WIDE_TAIL = 3, CLEARED = 4;

        private boolean moved;
        /** Whether absolute rows count from the top of the screen, else from the first one used. */
        private final boolean fromTop;
        /** Whether a first absolute row was not on the piece's first row, so counting the other way differs. */
        private boolean absoluteRowsDiffer;
        /** Where the first absolute row of each screen is, counted from the first one used, or unset. */
        private final long[] firstAbsolute = new long[2];
        private long row;
        private int column;
        /** The saved row of each screen, main then alternate, from the base of its region: xterm keeps one per screen. */
        private final long[] savedRow = new long[2];
        private final int[] savedColumn = new int[2];
        /** The first row of the piece in its region: where an absolute row counts from. */
        private long rowBase;
        private long lastRow = -1;
        private long lastAlternateRow = ALTERNATE - 1;
        private boolean alternate;
        private long mainBase;
        /** The alternate screen's base while what was drawn there stays shown (47 never clears), or -1. */
        private long alternateBase = -1;
        /** The cell last written, where some terminals put a mark that follows a cursor move. */
        private int lastSlot = -1;
        private int lastCodePoint = -1;
        private long repeats;

        // The written cells, each a slot: what it shows and the raw characters written to it
        private int[] codePoint = new int[64];
        private int[] slotStart = new int[64];
        private int[] slotEnd = new int[64];
        private byte[] kind = new byte[64];
        private int slots;

        // Row and column to slot, open addressing: keys are row << COLUMN_BITS | column
        private long[] keys = new long[128];
        private int[] values = new int[128];
        private int used;
        /** Per scrub, so a guest cannot choose cells that all land on one probe chain. */
        private final long seed = ThreadLocalRandom.current().nextLong();

        Screen(int rawLength, boolean fromTop) {
            this.fromTop = fromTop;
            repeats = (long) MAX_REPEATS_PER_CHAR * rawLength + MAX_COLUMN;
        }

        /**
         * Whether {@code piece} holds what can move a cursor back or draw a character twice, a
         * screen's only way to differ from the shown text but for blanks: a carriage return not
         * ending a line, a backspace, an 8-bit RI, or a sequence that moves back or repeats. It
         * only looks, so what it misses a screen would miss as well.
         */
        static boolean mayMoveBack(String piece) {
            for (int i = 0; i < piece.length(); i++) {
                char c = piece.charAt(i);
                if (c == '\b' || c == '\u008d' || c == '\r' && (i + 1 == piece.length() || piece.charAt(i + 1) != '\n')) return true;
            }
            if (piece.indexOf(ESC) < 0 && piece.indexOf('\u009b') < 0) return false;
            var controls = BuildOutput.guestControls(piece);
            while (controls.find()) {
                char last = piece.charAt(controls.end() - 1);
                if (controls.end() - controls.start() == 2 && piece.charAt(controls.start()) == ESC) {
                    if ("86M".indexOf(last) >= 0) return true;
                } else if ("ADFG`HfZubd".indexOf(last) >= 0) {
                    return true;
                } else if (last == 'l' && restoresOrLeaves(piece, controls.start(), controls.end())) {
                    return true;
                }
            }
            return false;
        }

        /** Whether a sequence is a private mode reset that leaves the alternate screen or restores a cursor xterm saved. */
        private static boolean restoresOrLeaves(String piece, int start, int end) {
            int from = start + (piece.charAt(start) == ESC ? 2 : 1);
            if (from >= end - 1 || piece.charAt(from) != '?') return false;
            for (var mode : piece.substring(from + 1, end - 1).split(";", -1)) {
                int m = mode(mode);
                if (m == 47 || m == 1047 || m == 1048 || m == 1049) return true;
            }
            return false;
        }

        /** A private mode's number as a terminal reads it, leading zeros and all, or -1. */
        private static int mode(String digits) {
            int first = 0;
            while (first < digits.length() && digits.charAt(first) == '0') first++;
            if (digits.length() - first > 6) return -1;
            int value = 0;
            for (int i = first; i < digits.length(); i++) {
                char d = digits.charAt(i);
                if (d < '0' || d > '9') return -1;
                value = value * 10 + d - '0';
            }
            return value;
        }

        /** Whether the cursor ever moved back, or REP drew what the shown text does not show. */
        boolean moved() {
            return moved;
        }

        /** Prints {@code piece}, which starts at {@code offset} in the raw text, on its own rows. */
        void print(String piece, int offset) {
            // A piece starts on a line of its own, below the last
            alternate = false;
            rowBase = Math.min(lastRow + 1, MAX_ROW);
            row = rowBase;
            column = 0;
            Arrays.fill(savedRow, 0);
            Arrays.fill(firstAbsolute, Long.MIN_VALUE);
            Arrays.fill(savedColumn, 0);
            lastSlot = -1;
            lastCodePoint = -1;
            var controls = BuildOutput.guestControls(piece);
            int pos = 0;
            while (pos < piece.length()) {
                int next = controls.find(pos) ? controls.start() : piece.length();
                for (int i = pos; i < next; ) {
                    int c = piece.codePointAt(i);
                    int width = Character.charCount(c);
                    put(c, offset + i, offset + i + width - 1);
                    i += width;
                }
                if (next == piece.length()) break;
                sequence(piece, controls.start(), controls.end(), offset);
                pos = controls.end();
            }
        }

        private void put(int c, int from, int to) {
            switch (c) {
                case '\n' -> {
                    nextLine();
                    return;
                }
                case '\r' -> {
                    move(row, 0);
                    return;
                }
                case '\b' -> {
                    move(row, column - 1);
                    return;
                }
                case '\t' -> {
                    move(row, (column / 8 + 1) * 8);
                    return;
                }
                case 0x0B, 0x0C, 0x84 -> {
                    // A vertical tab, a form feed or an 8-bit IND
                    move(row + 1, column);
                    return;
                }
                case 0x85 -> {
                    nextLine();
                    return;
                }
                case 0x8D -> {
                    move(row - 1, column);
                    return;
                }
                default -> {
                }
            }
            if (BuildOutput.actsOnTerminal(c)) return;
            int width = cellWidth(c);
            if (width == 0) {
                // On the cell before the cursor, and on the one last written, which some terminals pick
                if (column > 0) widen(slot(row, column - 1), from, to);
                widen(lastSlot, from, to);
                return;
            }
            if (column + width > MAX_COLUMN) return;
            clear(row, column);
            if (width == 2) clear(row, column + 1);
            lastSlot = write(row, column, c, width == 2 ? WIDE_HEAD : NARROW, from, to);
            if (width == 2) write(row, column + 1, c, WIDE_TAIL, from, to);
            lastCodePoint = c;
            if (alternate) lastAlternateRow = Math.max(lastAlternateRow, row);
            else lastRow = Math.max(lastRow, row);
            column += width;
        }

        /** A control sequence or escape from {@code start} to {@code end} of {@code piece}. */
        private void sequence(String piece, int start, int end, int offset) {
            char first = piece.charAt(start);
            char last = piece.charAt(end - 1);
            if (first == ESC && end - start == 2) {
                switch (last) {
                    case '7' -> save();
                    case '8' -> restore();
                    case 'M' -> move(row - 1, column);
                    case 'D' -> move(row + 1, column);
                    case 'E' -> nextLine();
                    // DECBI and DECFI: back and forward a column, as xterm moves them
                    case '6' -> move(row, column - 1);
                    case '9' -> move(row, column + 1);
                    default -> {
                    }
                }
                return;
            }
            boolean csi = first == '\u009b' || first == ESC && piece.charAt(start + 1) == '[';
            if (!csi) return;
            int from = start + (first == '\u009b' ? 1 : 2);
            if (from < end - 1 && piece.charAt(from) == '?' && (last == 'h' || last == 'l')) {
                for (var mode : piece.substring(from + 1, end - 1).split(";", -1)) privateMode(mode, last == 'h');
                return;
            }
            // Private (?, >, =, <) and intermediate bytes make another sequence of the same letter
            for (int i = from; i < end - 1; i++) {
                char p = piece.charAt(i);
                if (p != ';' && (p < '0' || p > '9')) return;
            }
            var numbers = piece.substring(from, end - 1).split(";", -1);
            int n = number(numbers, 0);
            switch (last) {
                case 'A' -> move(row - n, column);
                case 'B', 'e' -> move(row + n, column);
                case 'C', 'a' -> move(row, column + n);
                case 'I' -> move(row, (column / 8 + n) * 8);
                case 'Z' -> move(row, ((column + 7) / 8 - n) * 8);
                case 'D' -> move(row, column - n);
                case 'E' -> {
                    move(row + n, column);
                    column = 0;
                }
                case 'F' -> move(row - n, 0);
                case 'G', '`' -> move(row, n - 1);
                case 'H', 'f' -> move(absoluteRow(n), number(numbers, 1) - 1);
                case 'd' -> move(absoluteRow(n), column);
                case 's' -> save();
                case 'u' -> restore();
                case 'b' -> repeat(n, offset + start, offset + end - 1);
                default -> {
                }
            }
        }

        /**
         * Where absolute row {@code n} is. Whether the piece started at the top of the screen is
         * not known: a program that addresses rows usually clears it first, and one whose output
         * has scrolled addresses rows from a top long gone. So one screen counts from the piece's
         * first row and another from where its first absolute row is, and both are scrubbed.
         */
        private long absoluteRow(int n) {
            int screen = alternate ? 1 : 0;
            if (firstAbsolute[screen] == Long.MIN_VALUE) {
                firstAbsolute[screen] = row - (n - 1);
                // Where both count from the same row, the two screens are one
                if (firstAbsolute[screen] != rowBase) absoluteRowsDiffer = true;
            }
            return (fromTop ? rowBase : firstAbsolute[screen]) + n - 1;
        }

        /** Whether a screen counting absolute rows the other way can differ from this one. */
        boolean absoluteRowsMayDiffer() {
            return absoluteRowsDiffer;
        }

        /** An xterm private mode set or reset that saves or restores the cursor or swaps the screen. */
        private void privateMode(String mode, boolean set) {
            switch (mode(mode)) {
                case 1048 -> {
                    if (set) save(); else restore();
                }
                case 1049 -> {
                    if (set) {
                        save();
                        // xterm clears the alternate screen as it enters it
                        if (!alternate) alternateBase = -1;
                        switchScreen(true);
                    } else {
                        switchScreen(false);
                        restore();
                    }
                }
                case 1047 -> {
                    switchScreen(set);
                    // xterm clears the alternate screen as it leaves it
                    if (!set) alternateBase = -1;
                }
                case 47 -> switchScreen(set);
                default -> {
                }
            }
        }

        /**
         * Onto the alternate screen's region, past every row of its own drawn before unless what
         * was drawn there is still shown, or back to the main one. The cursor keeps its place on
         * the screen, and going back may overwrite.
         */
        private void switchScreen(boolean toAlternate) {
            if (alternate == toAlternate) return;
            long fromBase = row - rowBase;
            alternate = toAlternate;
            if (alternate) {
                mainBase = rowBase;
                if (alternateBase < 0) {
                    // A cleared alternate screen: its absolute rows count afresh, never from rows it left
                    alternateBase = Math.min(lastAlternateRow + 1, lastRegionRow());
                    firstAbsolute[1] = Long.MIN_VALUE;
                } else {
                    moved = true;
                }
                rowBase = alternateBase;
            } else {
                rowBase = mainBase;
                moved = true;
            }
            row = Math.min(rowBase + fromBase, lastRegionRow());
        }

        private long lastRegionRow() {
            return alternate ? ALTERNATE + MAX_ROW : MAX_ROW;
        }

        /** REP: the character last drawn, {@code n} times more, as drawn by the sequence itself. */
        private void repeat(int n, int from, int to) {
            moved = true;
            if (lastCodePoint < 0) return;
            for (int i = 0; i < n && repeats > 0; i++, repeats--) put(lastCodePoint, from, to);
        }

        /** Parameter {@code i}, 1 when missing or 0, as a terminal reads it, at most a column's worth. */
        private static int number(String[] numbers, int i) {
            if (i >= numbers.length || numbers[i].isEmpty()) return 1;
            int first = 0;
            while (first < numbers[i].length() && numbers[i].charAt(first) == '0') first++;
            var digits = numbers[i].substring(first);
            if (digits.isEmpty()) return 1;
            return digits.length() > 6 ? MAX_COLUMN : Math.min(Integer.parseInt(digits), MAX_COLUMN);
        }

        /**
         * Moving back, up or left, is what lets a character overwrite another: until something
         * does, the screen shows what the shown text does, with blanks where it moved ahead.
         */
        private void move(long toRow, int toColumn) {
            long newRow = Math.max(rowBase, Math.min(toRow, lastRegionRow()));
            int newColumn = Math.max(0, Math.min(toColumn, MAX_COLUMN));
            if (newRow < row || newRow == row && newColumn < column) moved = true;
            row = newRow;
            column = newColumn;
        }

        /** A line feed, NEL or CNL: down a line, to its start, overwriting only after a move back up. */
        private void nextLine() {
            row = Math.min(row + 1, lastRegionRow());
            column = 0;
        }

        private void save() {
            int screen = alternate ? 1 : 0;
            savedRow[screen] = row - rowBase;
            savedColumn[screen] = column;
        }

        private void restore() {
            int screen = alternate ? 1 : 0;
            move(rowBase + savedRow[screen], savedColumn[screen]);
        }

        /** Overwriting either cell of a wide character clears the other. */
        private void clear(long atRow, int at) {
            int slot = slot(atRow, at);
            if (slot < 0) return;
            if (kind[slot] == WIDE_HEAD) {
                int tail = slot(atRow, at + 1);
                if (tail >= 0 && kind[tail] == WIDE_TAIL) kind[tail] = CLEARED;
            } else if (kind[slot] == WIDE_TAIL && at > 0) {
                int head = slot(atRow, at - 1);
                if (head >= 0 && kind[head] == WIDE_HEAD) kind[head] = CLEARED;
            }
        }

        /** Writes {@code c} at a cell, which keeps every raw character written to it: its slot. */
        private int write(long atRow, int at, int c, byte cellKind, int from, int to) {
            long key = atRow << COLUMN_BITS | at;
            int index = index(key);
            int slot;
            if (values[index] == 0) {
                if (slots == codePoint.length) {
                    int size = slots * 2;
                    codePoint = Arrays.copyOf(codePoint, size);
                    slotStart = Arrays.copyOf(slotStart, size);
                    slotEnd = Arrays.copyOf(slotEnd, size);
                    kind = Arrays.copyOf(kind, size);
                }
                slot = slots++;
                slotStart[slot] = from;
                slotEnd[slot] = to;
                keys[index] = key;
                values[index] = slot + 1;
                if (++used * 4 > keys.length * 3) grow();
            } else {
                slot = values[index] - 1;
                // Everything it held, from any row: what a token once drawn whole there left is
                // redacted with it, though a row repainted from further down then takes what lies between
                slotStart[slot] = Math.min(slotStart[slot], from);
                slotEnd[slot] = Math.max(slotEnd[slot], to);
            }
            codePoint[slot] = c;
            kind[slot] = cellKind;
            return slot;
        }

        private void widen(int slot, int from, int to) {
            if (slot < 0) return;
            slotStart[slot] = Math.min(slotStart[slot], from);
            slotEnd[slot] = Math.max(slotEnd[slot], to);
        }

        /** The slot written at a cell, or -1. */
        private int slot(long atRow, int at) {
            return values[index(atRow << COLUMN_BITS | at)] - 1;
        }

        /** Where {@code key} is, or the empty place it would go. */
        private int index(long key) {
            int mask = keys.length - 1;
            int index = (int) mix(key ^ seed) & mask;
            while (values[index] != 0 && keys[index] != key) index = (index + 1) & mask;
            return index;
        }

        private void grow() {
            var oldKeys = keys;
            var oldValues = values;
            keys = new long[oldKeys.length * 2];
            values = new int[oldKeys.length * 2];
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldValues[i] == 0) continue;
                int index = index(oldKeys[i]);
                keys[index] = oldKeys[i];
                values[index] = oldValues[i];
            }
        }

        /** The murmur3 finalizer: every key bit reaches every index bit. */
        private static long mix(long h) {
            h ^= h >>> 33;
            h *= 0xff51afd7ed558ccdL;
            h ^= h >>> 33;
            h *= 0xc4ceb9fe1a85ec53L;
            return h ^ h >>> 33;
        }

        /** What the screen shows, row by row, and the raw characters each of its characters redacts. */
        View view() {
            var cells = new long[used];
            int n = 0;
            for (int i = 0; i < keys.length; i++) if (values[i] != 0) cells[n++] = keys[i];
            Arrays.sort(cells);
            var view = new View(n + n / 8 + 16);
            long previous = -1;
            for (long key : cells) {
                long atRow = key >>> COLUMN_BITS;
                int at = (int) (key & ((1 << COLUMN_BITS) - 1));
                int slot = slot(atRow, at);
                long previousRow = previous < 0 ? -1 : previous >>> COLUMN_BITS;
                int previousAt = previous < 0 ? -1 : (int) (previous & ((1 << COLUMN_BITS) - 1));
                // Rows and cells nothing was written to show blank, which ends a token as a line break does
                if (previous >= 0 && atRow != previousRow) {
                    view.append('\n', -1, -1);
                    previousAt = -1;
                }
                if (at > previousAt + 1) view.append(' ', -1, -1);
                switch (kind[slot]) {
                    case NARROW, WIDE_HEAD -> {
                        for (char half : Character.toChars(codePoint[slot])) view.append(half, slotStart[slot], slotEnd[slot]);
                    }
                    case CLEARED -> view.append(' ', slotStart[slot], slotEnd[slot]);
                    default -> {
                    }
                }
                previous = key;
            }
            return view;
        }
    }

    /** A view of the raw text, and for each character the raw ones it redacts, -1 for none. */
    private static final class View {
        final StringBuilder text;
        int[] start;
        int[] end;

        View(int capacity) {
            text = new StringBuilder(capacity);
            start = new int[capacity];
            end = new int[capacity];
        }

        void append(char c, int from, int to) {
            int i = text.length();
            if (i == start.length) {
                start = Arrays.copyOf(start, i * 2 + 1);
                end = Arrays.copyOf(end, i * 2 + 1);
            }
            start[i] = from;
            end[i] = to;
            text.append(c);
        }
    }

    /**
     * Adds to {@code spans}, in the shown text, what a scrub of {@code view} redacts. Its character
     * {@code i} redacts the raw text from {@code startIn[i]} to {@code endIn[i]}, inclusive, both
     * null for the raw text itself; {@code source} maps the shown text to it. Whether any redacts a
     * character the shown scrub's {@code seen} spans leave.
     */
    private static boolean addSpans(CharSequence view, int[] startIn, int[] endIn, int[] source,
                                    List<Span> seen, List<Span> spans) {
        boolean wider = false;
        // Both earliest first, the shown ones swept once with a cursor as these advance
        int cursor = 0;
        for (var span : scrub(view.toString(), Map.of()).spans) {
            if (span.start() >= span.end()) continue;
            int rawStart = span.start();
            int rawEnd = span.end();
            if (startIn != null) {
                // A view's characters need not be in raw order: a span takes all its cells' sources
                rawStart = Integer.MAX_VALUE;
                rawEnd = 0;
                for (int i = span.start(); i < span.end(); i++) {
                    if (startIn[i] < 0) continue;
                    rawStart = Math.min(rawStart, startIn[i]);
                    rawEnd = Math.max(rawEnd, endIn[i] + 1);
                }
                if (rawStart > rawEnd) continue;
            }
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
