package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import io.hyperfoil.tools.jjq.yaml.YamlMapper;
import io.hyperfoil.tools.jjq.yaml.YamlOptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

/**
 * Shared jjq entry points for YAML definitions (Phase 1 of
 * incus-spawn-jjq-migration).
 *
 * <p>One process-wide {@link JqMapper} (thread-safe, with the generated
 * mappings registered once, so it is not built per parse). All
 * methods take {@link YamlOptions#STRICT} (duplicate-key detection, matching
 * the old Jackson {@code STRICT_DUPLICATE_DETECTION}) and translate jjq's
 * unchecked failures to {@link IOException}, preserving every caller's
 * contract (warnings, {@code parseFailed}, {@code throws} signatures).
 * {@link YamlErrors#friendly} unwraps the cause again for rendering.
 */
public final class Jjq {

    /**
     * Explicitly wired mapper: generated mappings pre-registered. No Jackson
     * bridge (main is annotation-free since the removal slices) and no
     * {@code ServiceLoader} scan or {@code Class.forName()} discovery at
     * runtime — the form native-image needs (see Phase 4).
     * Behavior-identical to {@code JqMapper.create()} on the JVM; the suites
     * pin that.
     */
    private static final JqMapper MAPPER = buildMapper();

    /**
     * Checked-exception databind over {@link #mapper()} with process-wide
     * {@link YamlOptions#STRICT}. Replaces eight hand-rolled overloads that
     * each repeated the same try/catch (jjq#117); cause chains are preserved
     * for {@link YamlErrors#friendly}.
     */
    private static final YamlMapper YAML = new YamlMapper(MAPPER, YamlOptions.STRICT);

    /**
     * A DTO whose generated mapping is not registered here binds reflectively, which the native
     * image cannot do; {@code JjqGeneratedMappingTest} reads what this registers to catch that.
     */
    static JqMapper buildMapper() {
        var builder = JqMapper.builder();
        dev.incusspawn.config.JqMappingRegistry.registerAll(builder);
        dev.incusspawn.tool.JqMappingRegistry.registerAll(builder);
        return builder.build();
    }

    private Jjq() {}

    public static JqMapper mapper() {
        return MAPPER;
    }

    public static ImageDef imageDef(String yaml) throws IOException {
        return YAML.readValue(yaml, ImageDef.class);
    }

    public static ImageDef imageDef(InputStream in) throws IOException {
        return YAML.readValue(in, ImageDef.class);
    }

    public static ImageDef imageDef(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, ImageDef.class);
    }

    /** {@code base-image-pin.yaml}; null for an empty document. */
    public static BaseImagePin baseImagePin(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, BaseImagePin.class);
    }

    public static ToolDef toolDef(String yaml) throws IOException {
        return YAML.readValue(yaml, ToolDef.class);
    }

    public static ToolDef toolDef(InputStream in) throws IOException {
        return YAML.readValue(in, ToolDef.class);
    }

    public static ProjectConfig projectConfig(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, ProjectConfig.class);
    }

    public static SpawnConfig spawnConfig(String yaml) throws IOException {
        return YAML.readValue(yaml, SpawnConfig.class);
    }

    public static SpawnConfig spawnConfig(java.nio.file.Path file) throws IOException {
        return YAML.readValue(file, SpawnConfig.class);
    }

    /** {@code mcp.yaml}; null for an empty document. */
    public static McpConfig mcpConfig(String yaml) throws IOException {
        return YAML.readValue(yaml, McpConfig.class);
    }

    /** Serialize a config for {@code save()}: block-style YAML, same shape Jackson wrote. */
    public static String toYaml(Object config) {
        return YAML.writeValueAsString(config);
    }

    /** Write a config file straight from the tree: no intermediate String. */
    public static void writeYaml(java.nio.file.Path file, Object config) throws IOException {
        YAML.writeValue(file, config);
    }

    /** As {@link #writeYaml(java.nio.file.Path, Object)}, opening with the schema modeline editors read. */
    public static void writeYaml(java.nio.file.Path file, Object config, SchemaModeline modeline) throws IOException {
        java.nio.file.Files.writeString(file, modeline.line() + "\n" + YAML.writeValueAsString(config));
    }

    /**
     * A scalar as written in the file: {@code 0022}, {@code 3.10} and {@code yes} stay as typed
     * (jjq's {@code asText()} renders the resolved number or boolean, Hyperfoil/jjq#126). For the
     * converters that read the tree themselves; binding to a {@code String} field already does this.
     */
    public static String text(JqValue value) {
        var written = value.sourceText();
        return written != null ? written : value.asText();
    }

    /**
     * Why a parse failed, for one line of output: jjq's strict parse quotes the whole document
     * after the reason ({@code ... (while parsing '...')}), which can be megabytes.
     */
    public static String brief(Throwable e) {
        var message = e.getMessage();
        if (message == null) return "";
        var first = message.lines().findFirst().orElse("");
        var quoted = first.indexOf(" (while parsing '");
        if (quoted >= 0) first = first.substring(0, quoted);
        return first.length() > 200 ? first.substring(0, 200) + "..." : first;
    }

    // ---- JSON another program wrote ----

    /**
     * JSON another program wrote -- a tool's own file, a peer's message, an API's answer -- read as
     * strictly as {@link JqValues#parseStrict} but on one point. A lone surrogate escape
     * ({@code "\\ud83d"}), which RFC 8259 allows and JavaScript's {@code JSON.stringify} writes
     * for a string cut inside a pair, reads as U+FFFD. Since jjq 0.2.4 the strict parse refuses
     * the whole document for one (Hyperfoil/jjq#146), and a user cannot fix a file another tool
     * keeps rewriting. isx's own files and state stay strict: they go through parseStrict.
     * <p>
     * Costs nothing on a clean document: the escapes are replaced, length for length, and the
     * text parsed again only when the strict parse refused one, so positions in a later error hold.
     *
     * @param source what the JSON is, for the message: {@code "~/.claude.json"}
     * @throws IllegalArgumentException for anything else malformed, as
     *     {@code <source> is not valid JSON: <why> at line L, column C}
     */
    public static JqValue parseForeign(String json, String source) {
        return parseForeign(() -> JqValues.parseStrict(json),
                () -> JqValues.parseStrict(replaceLoneSurrogateEscapes(json)), source);
    }

    /** {@link #parseForeign(String, String)} of UTF-8 bytes, which are checked as strictly. */
    public static JqValue parseForeign(byte[] json, String source) {
        // Every byte of a multi-byte UTF-8 character is 0x80 or above, never a quote, a backslash
        // or a hex digit: the escapes can be rewritten on the bytes, read one char per byte.
        return parseForeign(() -> JqValues.parseStrict(json), () -> JqValues.parseStrict(
                replaceLoneSurrogateEscapes(new String(json, ISO_8859_1)).getBytes(ISO_8859_1)), source);
    }

    private static JqValue parseForeign(Supplier<JqValue> strict, Supplier<JqValue> repaired, String source) {
        try {
            return strict.get();
        } catch (IllegalArgumentException refused) {
            if (!isLoneSurrogate(refused)) throw notJson(source, refused);
        }
        try {
            return repaired.get();
        } catch (IllegalArgumentException refused) {
            throw notJson(source, refused);
        }
    }

    /** jjq's refusal of a lone surrogate escape; JjqForeignJsonTest fails if its wording changes. */
    private static boolean isLoneSurrogate(IllegalArgumentException refused) {
        return String.valueOf(refused.getMessage()).startsWith("Unpaired surrogate escape");
    }

    /**
     * Without {@code refused} as its cause, whose message may quote the whole document after the
     * reason; its stack trace is kept, so a trace still shows where jjq refused.
     */
    private static IllegalArgumentException notJson(String source, IllegalArgumentException refused) {
        var e = new IllegalArgumentException(source + " is not valid JSON: " + brief(refused));
        e.setStackTrace(refused.getStackTrace());
        return e;
    }

    /**
     * {@code json} with every {@code \\u} escape of an unpaired surrogate inside a string written
     * as {@code \\ufffd}, the same length; pairs, other escapes and everything outside strings are
     * left for the parser to judge.
     */
    public static String replaceLoneSurrogateEscapes(String json) {
        StringBuilder out = null;
        int copied = 0;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!inString) {
                inString = c == '"';
                continue;
            }
            if (c == '"') {
                inString = false;
            } else if (c == '\\' && i + 1 < json.length()) {
                int unit = surrogateEscape(json, i);
                if (unit < 0) {
                    i++; // an escape that is not a surrogate's: skip what it escapes
                } else if (Character.isHighSurrogate((char) unit)
                        && Character.isLowSurrogate((char) Math.max(surrogateEscape(json, i + 6), 0))) {
                    i += 11; // a pair, kept as written
                } else {
                    if (out == null) out = new StringBuilder(json.length());
                    out.append(json, copied, i).append("\\ufffd");
                    copied = i + 6;
                    i += 5;
                }
            }
        }
        return out == null ? json : out.append(json, copied, json.length()).toString();
    }

    /** The surrogate a {@code \\uXXXX} escape at {@code at} writes, or -1 for anything else. */
    private static int surrogateEscape(String json, int at) {
        if (at + 6 > json.length() || json.charAt(at) != '\\' || json.charAt(at + 1) != 'u') return -1;
        for (int k = at + 2; k < at + 6; k++) {
            if (!HexFormat.isHexDigit(json.charAt(k))) return -1; // ASCII only, unlike Character.digit
        }
        int unit = HexFormat.fromHexDigits(json, at + 2, at + 6);
        return Character.isSurrogate((char) unit) ? unit : -1;
    }

    // ---- JSON reads (Incus API responses) ----
    //
    // Jackson parity notes: daemon responses are machine-generated and never carry
    // explicit nulls (Incus omits rather than nulls), so unlike
    // {@link AccountResolver#navigate} — which distinguishes missing ("") from
    // explicit null ("null") because user files can mean both — these treat a null
    // like a missing node. Containers read as "" (Jackson's asText on a container).

    private static final java.math.BigDecimal INT_MIN = java.math.BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final java.math.BigDecimal INT_MAX = java.math.BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final java.math.BigDecimal LONG_MIN = java.math.BigDecimal.valueOf(Long.MIN_VALUE);
    private static final java.math.BigDecimal LONG_MAX = java.math.BigDecimal.valueOf(Long.MAX_VALUE);

    /**
     * A whole number that fits an int, or null: jjq's {@code intValue()}/{@code asInt()} wrap
     * beyond the range and truncate a fraction. Bounds are compared first, so a huge literal is
     * refused before its digits are looked at.
     */
    public static Integer exactInt(java.math.BigDecimal number) {
        if (number == null || number.compareTo(INT_MIN) < 0 || number.compareTo(INT_MAX) > 0) return null;
        try {
            return number.intValueExact();
        } catch (ArithmeticException fractional) {
            return null;
        }
    }

    /** {@link #exactInt} of a JSON or YAML number; null for anything else. */
    public static Integer exactInt(JqValue value) {
        var number = literalNumber(value);
        return number == null ? null : exactInt(number);
    }

    /**
     * The value of a number, or null when it is not a number or cannot be trusted. The YAML parser
     * reads {@code .inf} and {@code .nan} as non-finite numbers, whose decimal value throws (and
     * was 0 before jjq 0.2.4), so they are refused first. A number whose source text is not a
     * decimal equal to its value ({@code 0x10}, {@code 1_000}) is refused too. JSON numbers carry
     * no source text and keep their exact value.
     */
    private static java.math.BigDecimal literalNumber(JqValue value) {
        if (value == null || !value.isNumber()) return null;
        if (value instanceof JqNumber n && (n.isNaN() || n.isInfinite())) return null;
        var number = value.decimalValue();
        var written = value.sourceText();
        if (written == null) return number;
        try {
            return new java.math.BigDecimal(written.strip()).compareTo(number) == 0 ? number : null;
        } catch (NumberFormatException notDecimal) {
            return null;
        }
    }

    /** As {@link #exactInt}, for a long. */
    public static Long exactLong(JqValue value) {
        var number = literalNumber(value);
        if (number == null || number.compareTo(LONG_MIN) < 0 || number.compareTo(LONG_MAX) > 0) return null;
        try {
            return number.longValueExact();
        } catch (ArithmeticException fractional) {
            return null;
        }
    }

    /** Jackson's {@code asLong(dflt)}. */
    public static long longOr(JqValue value, long dflt) {
        var exact = exactLong(value); // longValue() would wrap beyond the long range
        return exact != null ? exact : dflt;
    }

    /** Jackson's {@code isMissingNode()}: absent (or, for responses, null). */
    public static boolean isMissing(JqValue value) {
        return value == null || value.isNull();
    }
}
