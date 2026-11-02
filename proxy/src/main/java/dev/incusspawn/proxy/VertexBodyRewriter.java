package dev.incusspawn.proxy;

import io.vertx.core.buffer.Buffer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Translates a standard Messages request body into Vertex's rawPredict as its bytes arrive, so a
 * body is never held whole to be translated (#1315). It validates the JSON as strictly as
 * {@code JqValues.parseStrict} did, which is what the translation used before, and copies every
 * value it keeps byte for byte:
 * <ul>
 *   <li>top-level fields Vertex does not take are dropped, the client's own {@code anthropic_version}
 *       included, and {@code "anthropic_version":"vertex-2023-10-16"} is appended;</li>
 *   <li>{@code model} is dropped and goes into the URL, with its date suffix stripped; {@code stream}
 *       is kept, and only a literal {@code true} asks for {@code :streamRawPredict};</li>
 *   <li>{@code scope} is dropped from every {@code cache_control} object below the top level, and
 *       the rest of that object is copied as it is;</li>
 *   <li>{@code strict} is dropped from every tool definition, an object in the top-level
 *       {@code tools} array (#1556): Vertex treats it as the structured_outputs partner-model
 *       feature, which an organization policy may disallow, and then refuses every request with
 *       tools.</li>
 * </ul>
 * {@code output_config}, a JSON-schema {@code format} included, is copied as it is, by the
 * translation and for a body already in Vertex's format (#1569). Vertex treats that format as the
 * same feature. Unlike a tool's {@code strict}, dropping it would answer with something other than
 * the JSON the client asked for. The rewriter therefore keeps it, and under such a policy the
 * client gets Vertex's own 400.
 * <p>
 * Its memory is bounded whatever the body: two bits per nesting level up to {@link #MAX_DEPTH}, a
 * field name up to {@link #MAX_NAME_CHARS} characters, and {@code model} up to the URL ceiling
 * ({@link #MAX_URL_PATH}), and {@link #MAX_DROPPED_NAMES} names to log. Strings and numbers pass
 * through in chunks.
 * <p>
 * Where it parts from the tree translation it replaces (decided on #1315): a duplicate key is
 * forwarded as sent unless it is a top-level {@code model} or {@code stream}, which are refused;
 * malformed Unicode and number spelling in values are forwarded as sent, not re-encoded; and a name
 * the rewriter compares, or the {@code model} that goes into the URL, must be well-formed, or the
 * request is refused.
 * <p>
 * A body already in Vertex's format ({@link #vertexFormat}) is rewritten by the same rule for tool
 * definitions and nothing else (#1568): every other member, {@code model}, {@code anthropic_version}
 * and {@code cache_control}'s {@code scope} included, is copied as it is, and the path is left as it
 * is. Only the names it compares, the top object's and a tool definition's, must be well-formed.
 */
class VertexBodyRewriter {
    private static final Pattern DATE_SUFFIX = Pattern.compile("-\\d{8}$");

    /** {@code parseStrict}'s own limit: 10,000 levels parse, 10,001 do not. */
    static final int MAX_DEPTH = 10_000;
    /** Longer than any name the rewriter compares ({@code anthropic_version} is 17). */
    static final int MAX_NAME_CHARS = 32;
    /** The Vertex front end answers a longer path with its own error page, before the API sees it. */
    static final int MAX_URL_PATH = 16_384;
    static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    static final String ANTHROPIC_VERSION = "vertex-2023-10-16";
    /** How much of a dropped field's name is logged. */
    static final int LOGGED_NAME_BYTES = 64;
    /** How many distinct dropped names one body reports: a body may drop any number of fields. */
    static final int MAX_DROPPED_NAMES = 16;
    /** How a dropped tool {@code strict} is named among the dropped fields. */
    static final String TOOL_STRICT = "tools[].strict";

    static final Set<String> ALLOWED_FIELDS = Set.of(
            "anthropic_version", "messages", "system", "max_tokens",
            "temperature", "top_p", "top_k", "stop_sequences", "stream",
            "metadata", "tools", "tool_choice", "thinking", "output_config"
    );

    /** Not JSON the translation takes: answered as before, with a 502. */
    static final class InvalidBody extends Exception {
        InvalidBody(String message) {
            super(message);
        }
    }

    /** JSON whose names or routing the proxy cannot read unambiguously: answered with a 400. */
    static final class RefusedBody extends Exception {
        RefusedBody(String message) {
            super(message);
        }
    }

    /** Where the request goes, once the body has ended. */
    record Result(String path, List<String> droppedFields) {}

    // What the value being parsed is for
    private static final int EMIT = 0;      // copied to the output
    private static final int SKIP = 1;      // validated and dropped
    private static final int MODEL = 2;     // the top-level model: captured, not copied
    private static final int PASSTHROUGH = 3; // a body that is not an object: everything copied as it is

    // Lexer states
    private static final int START = 0, VALUE = 1, OBJ_FIRST = 2, OBJ_NEXT = 3, COLON = 4, AFTER = 5,
            ARR_FIRST = 6, STRING = 7, NUMBER = 8, LITERAL = 9, TRAIL = 10;

    private final String originalPath;
    /** Where a translated body goes; null for a body already in Vertex's format, which is not translated. */
    private final String pathPrefix;
    private final Consumer<Buffer> output;

    private int state = START;
    private int bomSeen;
    private final byte[] held = new byte[3];
    private boolean passthrough;

    // Containers: bit d of isObject/emittedAny is depth d (1-based); the top object is depth 1
    private int depth;
    private final long[] isObject = new long[MAX_DEPTH / 64 + 2];
    private final long[] emittedAny = new long[MAX_DEPTH / 64 + 2];
    /** The depth from which everything is skipped (0: none). */
    private int skipFrom;
    /** The depth of the cache_control object being copied, whose members' values are copied as they are. */
    private int ccDepth;
    /** The depth from which everything is copied as it is, below a cache_control member (0: none). */
    private int rawFrom;
    /** The array at depth 2 is the top-level tools, so an object at depth 3 is a tool definition. */
    private boolean inTools;

    // The value starting next
    private int valueMode;
    private boolean nextIsCacheControl;
    private boolean nextIsTools;
    private boolean valueIsStream;

    // Key being read
    private boolean inKey;
    private boolean keyInterpreted;
    private final byte[] keyRaw = new byte[MAX_NAME_CHARS * 6 + 2];
    private int keyRawLen;
    private boolean keyRawOverflow;
    private final StringBuilder keyName = new StringBuilder();
    private boolean keyNameMatchable;
    /** The key's bytes are being written as they come (it overflowed {@link #keyRaw} in an emitted object). */
    private boolean keyStreaming;

    // String state
    private int escape;          // 0 none, 1 after '\', 2..5 reading \\u hex digits
    private int unicodeValue;
    private boolean validateUnicode;
    private int utf8Remaining;
    private int utf8Codepoint;
    private int utf8Min;
    private int pendingHighSurrogate = -1;
    private boolean expectLowSurrogate;
    private StringBuilder modelText;
    private int modelBytes;

    // Number and literal state
    private int numberState;
    private String literal;
    private int literalIndex;
    private boolean literalIsTrue;

    // Top-level fields
    private int modelSeen;
    private int streamSeen;
    private String model;
    private boolean stream;
    private final Set<String> dropped = new java.util.LinkedHashSet<>();

    // Output of the chunk being read
    private byte[] out = new byte[8192];
    private int outLen;

    VertexBodyRewriter(String originalPath, String projectId, String region, Consumer<Buffer> output) {
        this.originalPath = originalPath;
        this.pathPrefix = "/v1/projects/" + projectId + "/locations/" + region + "/publishers/anthropic/models/";
        this.output = output;
    }

    /** A rewriter of a body already in Vertex's format, for {@code path}: only a tool's {@code strict} goes. */
    static VertexBodyRewriter vertexFormat(String path, Consumer<Buffer> output) {
        return new VertexBodyRewriter(path, output);
    }

    private VertexBodyRewriter(String path, Consumer<Buffer> output) {
        this.originalPath = path;
        this.pathPrefix = null;
        this.output = output;
    }

    private boolean translating() {
        return pathPrefix != null;
    }

    void accept(Buffer chunk) throws InvalidBody, RefusedBody {
        var bytes = chunk.getBytes();
        try {
            for (var b : bytes) step(b & 0xff);
        } finally {
            flush();
        }
    }

    Result end() throws InvalidBody, RefusedBody {
        try {
            if (state == NUMBER) {
                endNumber();
                afterValue();
            }
            if (state == START) {
                // No value at all, as parseStrict reads it: forwarded, as it was, but for whitespace
                if (bomSeen > 0 && bomSeen < 3) throw new InvalidBody("a broken byte order mark");
                for (int i = 0; i < bomSeen; i++) emit(held[i]);
                return new Result(originalPath, List.of());
            }
            if (state != TRAIL) throw new InvalidBody("the body ends before its JSON does");
            if (passthrough) return new Result(originalPath, List.of());
            if (!translating()) return new Result(originalPath, List.copyOf(dropped));
            var name = model == null ? DEFAULT_MODEL : DATE_SUFFIX.matcher(model).replaceFirst("");
            var path = pathPrefix + name + (stream ? ":streamRawPredict" : ":rawPredict");
            if (path.getBytes(StandardCharsets.UTF_8).length > MAX_URL_PATH) {
                throw new RefusedBody("model is too long for a Vertex URL (" + MAX_URL_PATH + " bytes)");
            }
            return new Result(path, List.copyOf(dropped));
        } finally {
            flush();
        }
    }

    // --- Lexer ---

    private void step(int b) throws InvalidBody, RefusedBody {
        if (passthrough) emit(b);
        dispatch(b);
    }

    private void dispatch(int b) throws InvalidBody, RefusedBody {
        switch (state) {
            case START -> start(b);
            case VALUE, ARR_FIRST -> {
                if (isWhitespace(b)) return;
                if (state == ARR_FIRST && b == ']') {
                    closeContainer(b);
                    return;
                }
                beginValue(b);
            }
            case OBJ_FIRST, OBJ_NEXT -> {
                if (isWhitespace(b)) return;
                if (state == OBJ_FIRST && b == '}') {
                    closeContainer(b);
                    return;
                }
                if (b != '"') throw new InvalidBody("expected a field name");
                beginKey();
            }
            case COLON -> {
                if (isWhitespace(b)) return;
                if (b != ':') throw new InvalidBody("expected ':' after a field name");
                state = VALUE;
            }
            case AFTER -> after(b);
            case STRING -> string(b);
            case NUMBER -> number(b);
            case LITERAL -> literal(b);
            case TRAIL -> {
                if (!isWhitespace(b)) throw new InvalidBody("content after the JSON value");
            }
            default -> throw new IllegalStateException();
        }
    }

    private void start(int b) throws InvalidBody, RefusedBody {
        // One byte order mark among the whitespace before the value, as parseStrict takes: dropped
        // from an object, kept otherwise
        if (bomSeen > 0 && bomSeen < 3) {
            if (b != BOM[bomSeen]) throw new InvalidBody("a broken byte order mark");
            held[bomSeen++] = (byte) b;
            return;
        }
        if (bomSeen == 0 && b == BOM[0]) {
            held[bomSeen++] = (byte) b;
            return;
        }
        // Insignificant, and not forwarded
        if (isWhitespace(b)) return;
        if (b != '{') {
            // Not an object (an array, a string...): validated, and forwarded as it is
            passthrough = true;
            for (int i = 0; i < bomSeen; i++) emit(held[i]);
            emit(b);
            valueMode = PASSTHROUGH;
            beginValueToken(b);
            return;
        }
        valueMode = EMIT;
        beginValueToken(b);
    }

    private static final int[] BOM = {0xEF, 0xBB, 0xBF};

    private void after(int b) throws InvalidBody {
        if (isWhitespace(b)) return;
        if (depth == 0) throw new InvalidBody("content after the JSON value");
        var object = bit(isObject, depth);
        if (b == ',') {
            state = object ? OBJ_NEXT : VALUE;
            if (!object) prepareElement();
            return;
        }
        if ((object && b == '}') || (!object && b == ']')) {
            closeContainer(b);
            return;
        }
        throw new InvalidBody(object ? "expected ',' or '}' in an object" : "expected ',' or ']' in an array");
    }

    /** Begins a value whose mode is set: an object or array opens a container. */
    private void beginValue(int b) throws InvalidBody, RefusedBody {
        if (state == ARR_FIRST) prepareElement();
        beginValueToken(b);
    }

    private void beginValueToken(int b) throws InvalidBody, RefusedBody {
        var emitting = valueMode == EMIT;
        if (valueMode == MODEL && b != '"') {
            // Not a string: what pathText made of it, a scalar the default, a container ""
            model = (b == '{' || b == '[') ? "" : null;
            valueMode = SKIP;
        }
        switch (b) {
            case '{', '[' -> {
                if (emitting) emit(b);
                openContainer(b == '{');
            }
            case '"' -> {
                if (emitting) emit(b);
                beginString(false);
            }
            case 't', 'f', 'n' -> {
                if (emitting) emit(b);
                literal = b == 't' ? "true" : b == 'f' ? "false" : "null";
                literalIsTrue = b == 't';
                literalIndex = 1;
                state = LITERAL;
            }
            default -> {
                if (b == '-' || (b >= '0' && b <= '9')) {
                    if (emitting) emit(b);
                    numberState = b == '-' ? 1 : b == '0' ? 2 : 3;
                    state = NUMBER;
                } else {
                    throw new InvalidBody("expected a JSON value");
                }
            }
        }
    }

    private void openContainer(boolean object) throws InvalidBody {
        if (depth == MAX_DEPTH) throw new InvalidBody("nested deeper than " + MAX_DEPTH + " levels");
        depth++;
        set(isObject, depth, object);
        set(emittedAny, depth, false);
        if (valueMode == SKIP && skipFrom == 0) skipFrom = depth;
        if (valueMode == EMIT && rawFrom == 0 && ccDepth != 0 && depth > ccDepth) rawFrom = depth;
        if (valueMode == EMIT && nextIsCacheControl && object && depth > 1) ccDepth = depth;
        if (valueMode == EMIT && nextIsTools && !object) inTools = true;
        nextIsCacheControl = false;
        nextIsTools = false;
        state = object ? OBJ_FIRST : ARR_FIRST;
        valueIsStream = false;
    }

    private void closeContainer(int b) throws InvalidBody {
        var mode = modeAt(depth);
        if (depth == 1 && !passthrough && translating()) {
            if (bit(emittedAny, 1)) emit(',');
            emitAscii("\"anthropic_version\":\"" + ANTHROPIC_VERSION + "\"");
        }
        if (mode == EMIT) emit(b);
        if (skipFrom == depth) skipFrom = 0;
        if (rawFrom == depth) rawFrom = 0;
        if (ccDepth == depth) ccDepth = 0;
        if (depth == 2) inTools = false;
        depth--;
        afterValue();
    }

    /** The mode of what is written inside the container at {@code d}. */
    private int modeAt(int d) {
        if (passthrough) return PASSTHROUGH;
        if (skipFrom != 0 && d >= skipFrom) return SKIP;
        return EMIT;
    }

    private void afterValue() {
        valueIsStream = false;
        nextIsCacheControl = false;
        nextIsTools = false;
        state = depth == 0 ? TRAIL : AFTER;
    }

    /** An array element is about to begin: it is written in its array's mode, after a comma. */
    private void prepareElement() {
        valueMode = modeAt(depth);
        if (valueMode == EMIT) {
            if (bit(emittedAny, depth)) emit(',');
            set(emittedAny, depth, true);
        }
    }

    // --- Keys ---

    private void beginKey() {
        var mode = modeAt(depth);
        inKey = true;
        // Names the rewriter compares: in the top object, in the objects it copies and transforms,
        // and in a cache_control object; never below a skipped field or inside a copied value. A body
        // in Vertex's format has its top object's names compared, and a tool definition's
        keyInterpreted = mode == EMIT && (translating() ? rawFrom == 0 || depth < rawFrom
                : depth == 1 || isToolDefinition());
        keyRawLen = 0;
        keyRawOverflow = false;
        keyStreaming = false;
        keyName.setLength(0);
        keyNameMatchable = true;
        beginString(true);
        keyByte('"');
    }

    private void keyByte(int b) {
        if (keyStreaming) {
            emit(b);
            return;
        }
        if (keyRawLen < keyRaw.length) {
            keyRaw[keyRawLen++] = (byte) b;
            return;
        }
        keyRawOverflow = true;
        keyNameMatchable = false;
        // Too long to be a name the rewriter acts on: written as it comes, where it is written at all
        if (memberIsWritten(null)) {
            writeMemberStart();
            keyStreaming = true;
            emit(b);
        }
    }

    /** Whether the member whose name is {@code name} (null: matches nothing) is written. */
    private boolean memberIsWritten(String name) {
        if (modeAt(depth) != EMIT) return false;
        if (depth == 1 && !translating()) return true;
        if (depth == 1) return name != null && ALLOWED_FIELDS.contains(name) && !name.equals("anthropic_version");
        if (depth == ccDepth) return !"scope".equals(name);
        return true;
    }

    /** Whether the object whose members are being read is a tool definition. */
    private boolean isToolDefinition() {
        return inTools && depth == 3;
    }

    private void writeMemberStart() {
        if (bit(emittedAny, depth)) emit(',');
        set(emittedAny, depth, true);
        for (int i = 0; i < keyRawLen; i++) emit(keyRaw[i]);
    }

    private void endKey() throws RefusedBody {
        inKey = false;
        var name = keyNameMatchable ? keyName.toString() : null;
        var mode = modeAt(depth);
        nextIsCacheControl = false;
        nextIsTools = false;
        valueIsStream = false;
        if (mode != EMIT) {
            valueMode = mode;
        } else if (depth == 1) {
            if (translating() && "model".equals(name)) {
                if (++modelSeen > 1) throw new RefusedBody("the body names its model twice");
                valueMode = MODEL;
            } else if (translating() && "stream".equals(name)) {
                if (++streamSeen > 1) throw new RefusedBody("the body sets stream twice");
                valueMode = EMIT;
                valueIsStream = true;
            } else if (memberIsWritten(name)) {
                valueMode = EMIT;
                nextIsTools = "tools".equals(name);
            } else {
                valueMode = SKIP;
                if (dropped.size() < MAX_DROPPED_NAMES && !"anthropic_version".equals(name)) dropped.add(loggedName());
            }
        } else if (depth == ccDepth) {
            // Everything below a cache_control member is copied as it is (openContainer)
            valueMode = "scope".equals(name) ? SKIP : EMIT;
        } else if (isToolDefinition() && "strict".equals(name)) {
            valueMode = SKIP;
            if (dropped.size() < MAX_DROPPED_NAMES) dropped.add(TOOL_STRICT);
        } else {
            valueMode = EMIT;
            nextIsCacheControl = translating() && "cache_control".equals(name) && (rawFrom == 0);
        }
        if (valueMode == EMIT && !keyStreaming) writeMemberStart();
        if (valueMode == EMIT) emit(':');
        state = COLON;
    }

    private String loggedName() {
        var n = Math.min(keyRawLen, LOGGED_NAME_BYTES + 1);
        var s = new String(keyRaw, 0, n, StandardCharsets.UTF_8);
        if (s.startsWith("\"")) s = s.substring(1);
        if (s.endsWith("\"")) s = s.substring(0, s.length() - 1);
        // An instance chose the name: nothing in it may start a line of the proxy's log, or style it
        var shown = new StringBuilder();
        s.codePoints().forEach(c -> {
            if (Character.isISOControl(c)) shown.append(String.format("\\u%04x", c));
            else shown.appendCodePoint(c);
        });
        return keyRawOverflow || keyRawLen > LOGGED_NAME_BYTES ? shown + "…" : shown.toString();
    }

    // --- Strings ---

    private void beginString(boolean key) {
        state = STRING;
        escape = 0;
        utf8Remaining = 0;
        pendingHighSurrogate = -1;
        expectLowSurrogate = false;
        validateUnicode = key ? keyInterpreted : valueMode == MODEL;
        if (!key && valueMode == MODEL) {
            modelText = new StringBuilder();
            modelBytes = 0;
        }
    }

    private void string(int b) throws InvalidBody, RefusedBody {
        var key = inKey;
        if (key) keyByte(b);
        else if (valueMode == EMIT) emit(b);
        if (escape == 1) {
            escape = 0;
            switch (b) {
                case '"', '\\', '/' -> decoded(b, key);
                case 'b' -> decoded('\b', key);
                case 'f' -> decoded('\f', key);
                case 'n' -> decoded('\n', key);
                case 'r' -> decoded('\r', key);
                case 't' -> decoded('\t', key);
                case 'u' -> {
                    escape = 2;
                    unicodeValue = 0;
                }
                default -> throw new InvalidBody("an invalid escape in a string");
            }
            return;
        }
        if (escape >= 2) {
            var digit = Character.digit(b, 16);
            if (digit < 0) throw new InvalidBody("an invalid \\u escape in a string");
            unicodeValue = unicodeValue * 16 + digit;
            if (++escape == 6) {
                escape = 0;
                escapedChar(unicodeValue, key);
            }
            return;
        }
        if (b == '\\') {
            if (utf8Remaining != 0) malformed(key);
            escape = 1;
            return;
        }
        if (expectLowSurrogate && validateUnicode) malformed(key);
        if (b == '"') {
            if (utf8Remaining != 0) malformed(key);
            if (key) {
                endKey();
            } else {
                endStringValue();
            }
            return;
        }
        rawByte(b, key);
    }

    /** A character written as an escape. */
    private void escapedChar(int c, boolean key) throws RefusedBody {
        if (validateUnicode) {
            if (expectLowSurrogate) {
                if (c < 0xDC00 || c > 0xDFFF) malformed(key);
                expectLowSurrogate = false;
                decodedCodepoint(Character.toCodePoint((char) pendingHighSurrogate, (char) c), key);
                return;
            }
            if (c >= 0xD800 && c <= 0xDBFF) {
                pendingHighSurrogate = c;
                expectLowSurrogate = true;
                return;
            }
            if (c >= 0xDC00 && c <= 0xDFFF) malformed(key);
        }
        decodedCodepoint(c, key);
    }

    private void decoded(int c, boolean key) throws RefusedBody {
        if (expectLowSurrogate && validateUnicode) malformed(key);
        decodedCodepoint(c, key);
    }

    /** A byte of the string as it is (not an escape): UTF-8, checked where the text is read. */
    private void rawByte(int b, boolean key) throws RefusedBody {
        if (!validateUnicode) {
            if (key) nameChar(b < 0x80 ? b : -1);
            return;
        }
        if (utf8Remaining == 0) {
            if (b < 0x80) {
                decodedCodepoint(b, key);
            } else if (b >= 0xC2 && b <= 0xDF) {
                utf8Remaining = 1;
                utf8Codepoint = b & 0x1F;
                utf8Min = 0x80;
            } else if (b >= 0xE0 && b <= 0xEF) {
                utf8Remaining = 2;
                utf8Codepoint = b & 0x0F;
                utf8Min = 0x800;
            } else if (b >= 0xF0 && b <= 0xF4) {
                utf8Remaining = 3;
                utf8Codepoint = b & 0x07;
                utf8Min = 0x10000;
            } else {
                malformed(key);
            }
            return;
        }
        if ((b & 0xC0) != 0x80) malformed(key);
        utf8Codepoint = (utf8Codepoint << 6) | (b & 0x3F);
        if (--utf8Remaining == 0) {
            if (utf8Codepoint < utf8Min || utf8Codepoint > 0x10FFFF
                    || (utf8Codepoint >= 0xD800 && utf8Codepoint <= 0xDFFF)) {
                malformed(key);
            }
            decodedCodepoint(utf8Codepoint, key);
        }
    }

    private void decodedCodepoint(int c, boolean key) throws RefusedBody {
        if (key) {
            nameChar(c);
            return;
        }
        if (valueMode == MODEL) modelChar(c);
    }

    private void nameChar(int c) {
        if (!keyNameMatchable) return;
        if (c < 0 || c >= 0x80 || keyName.length() == MAX_NAME_CHARS) {
            keyNameMatchable = false;
            return;
        }
        keyName.append((char) c);
    }

    private void modelChar(int c) throws RefusedBody {
        // Anything a URL parser or normaliser might read as structure, or that is invisible
        if (Character.isISOControl(c) || c == '/' || c == '\\' || c == '?' || c == '#' || c == '%'
                || Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT) {
            throw new RefusedBody("model holds a character that would change the Vertex URL");
        }
        modelBytes += c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4;
        if (modelBytes > MAX_URL_PATH) {
            throw new RefusedBody("model is too long for a Vertex URL (" + MAX_URL_PATH + " bytes)");
        }
        modelText.appendCodePoint(c);
    }

    private void malformed(boolean key) throws RefusedBody {
        throw new RefusedBody(key ? "a field name is not well-formed Unicode" : "model is not well-formed Unicode");
    }

    private void endStringValue() {
        if (valueMode == MODEL) {
            model = modelText.toString();
            modelText = null;
        }
        afterValue();
    }

    // --- Numbers and literals ---

    // 1 after '-', 2 after a leading 0, 3 in integer digits, 4 after '.', 5 in fraction digits,
    // 6 after 'e', 7 after the exponent's sign, 8 in exponent digits
    private void number(int b) throws InvalidBody, RefusedBody {
        var accepted = switch (numberState) {
            case 1 -> b == '0' ? next(2) : (b >= '1' && b <= '9') ? next(3) : -1;
            case 2 -> b == '.' ? next(4) : (b == 'e' || b == 'E') ? next(6) : 0;
            case 3 -> (b >= '0' && b <= '9') ? next(3) : b == '.' ? next(4) : (b == 'e' || b == 'E') ? next(6) : 0;
            case 4 -> (b >= '0' && b <= '9') ? next(5) : -1;
            case 5 -> (b >= '0' && b <= '9') ? next(5) : (b == 'e' || b == 'E') ? next(6) : 0;
            case 6 -> (b == '+' || b == '-') ? next(7) : (b >= '0' && b <= '9') ? next(8) : -1;
            case 7 -> (b >= '0' && b <= '9') ? next(8) : -1;
            case 8 -> (b >= '0' && b <= '9') ? next(8) : 0;
            default -> -1;
        };
        if (accepted < 0) throw new InvalidBody("an invalid number");
        if (accepted == 1) {
            if (valueMode == EMIT) emit(b);
            return;
        }
        // The number ended at a byte that belongs to what follows
        endNumber();
        afterValue();
        dispatch(b);
    }

    private int next(int s) {
        numberState = s;
        return 1;
    }

    private void endNumber() throws InvalidBody {
        if (numberState != 2 && numberState != 3 && numberState != 5 && numberState != 8) {
            throw new InvalidBody("an invalid number");
        }
    }

    private void literal(int b) throws InvalidBody {
        if (b != literal.charAt(literalIndex)) throw new InvalidBody("an invalid literal");
        if (valueMode == EMIT) emit(b);
        if (++literalIndex == literal.length()) {
            if (valueIsStream) stream = literalIsTrue;
            afterValue();
        }
    }

    // --- Output ---

    private void emit(int b) {
        if (outLen == out.length) out = Arrays.copyOf(out, out.length * 2);
        out[outLen++] = (byte) b;
    }

    private void emitAscii(String s) {
        for (int i = 0; i < s.length(); i++) emit(s.charAt(i));
    }

    private void flush() {
        if (outLen == 0) return;
        output.accept(Buffer.buffer(Arrays.copyOf(out, outLen)));
        outLen = 0;
        if (out.length > 65536) out = new byte[8192];
    }

    private static boolean isWhitespace(int b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    private static boolean bit(long[] bits, int i) {
        return (bits[i >> 6] & (1L << i)) != 0;
    }

    private static void set(long[] bits, int i, boolean value) {
        if (value) bits[i >> 6] |= 1L << i;
        else bits[i >> 6] &= ~(1L << i);
    }
}
