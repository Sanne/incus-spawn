package dev.incusspawn.proxy;

import io.vertx.core.buffer.Buffer;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Admits only the body of a Gemini call that Pi's google-vertex provider sends (#1925), as its bytes
 * arrive, and passes each chunk on unchanged once it has been read. The call is signed with the
 * host's gcloud token, whose reach is every project its user can touch, and a body can name such a
 * project's data: a {@code cachedContent}, a RAG corpus or a Search datastore in a tool, a
 * {@code gs://} object in a part's {@code fileData}. So the check is an allowlist:
 * <ul>
 *   <li>the top object holds only {@code contents}, {@code systemInstruction},
 *       {@code generationConfig}, {@code tools} and {@code toolConfig};</li>
 *   <li>a tool holds only {@code functionDeclarations};</li>
 *   <li>a part holds no {@code fileData}, in either spelling: the parts of {@code contents[]}, of
 *       {@code systemInstruction} and of a part's {@code functionResponse}.</li>
 * </ul>
 * A field outside the list is refused, never dropped, so the client never gets an answer to a
 * question it did not ask. Names are compared as Vertex reads them, escapes decoded, and every
 * position the check relies on must have the shape it expects ({@code tools} an array of objects,
 * say) or be {@code null}, so no other spelling or shape slips past it. What lies below those
 * positions, a function's arguments or schema included, is not read: a field there may be named
 * {@code fileData}.
 * <p>
 * The body must be strict JSON, a complete object, with well-formed UTF-8 in every string, and a name the
 * check reads holds no control character. Its memory is bounded whatever the body: a byte per
 * nesting level up to {@link #MAX_DEPTH} and a field name up to {@link #MAX_NAME_CHARS} characters.
 */
final class GeminiBodyCheck {

    /** As {@link VertexBodyRewriter#MAX_DEPTH}. */
    static final int MAX_DEPTH = VertexBodyRewriter.MAX_DEPTH;
    /** How much of a refused field's name is kept, to name it. */
    static final int MAX_NAME_CHARS = 64;

    static final Set<String> TOP_FIELDS = Set.of(
            "contents", "systemInstruction", "generationConfig", "tools", "toolConfig");
    static final Set<String> TOOL_FIELDS = Set.of("functionDeclarations");

    /** A body the route does not serve; {@link #status} says how it is answered. */
    static final class Refused extends Exception {
        private final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        /** 400 for a body that is not JSON, 403 for one the route does not serve. */
        int status() {
            return status;
        }
    }

    // What a container is for: an object's names are read at every role but OPAQUE
    private static final byte OPAQUE = 0, TOP = 1, CONTENTS = 2, CONTENT = 3, PARTS = 4, PART = 5,
            RESPONSE = 6, TOOLS = 7, TOOL = 8;
    /** Set on a depth's role when the container there is an object. */
    private static final byte OBJECT = 0x10;
    private static final String[] SHAPES = {
            null, "the body must be a JSON object", "contents must be an array", "a content must be an object",
            "parts must be an array", "a part must be an object", "a functionResponse must be an object",
            "tools must be an array", "a tool must be an object"};

    // Lexer states
    private static final int START = 0, VALUE = 1, OBJ_FIRST = 2, OBJ_NEXT = 3, COLON = 4, AFTER = 5,
            ARR_FIRST = 6, STRING = 7, NUMBER = 8, LITERAL = 9, TRAIL = 10;

    private final Consumer<Buffer> output;
    private int state = START;

    /** The container at depth d (1-based) is roles[d]: its role, with {@link #OBJECT} for an object. */
    private int depth;
    private final byte[] roles = new byte[MAX_DEPTH + 1];
    /** The role of the value starting next. */
    private byte next = TOP;

    // Key being read
    private boolean inKey;
    private boolean keyRead;
    private final StringBuilder keyName = new StringBuilder();
    private boolean keyTruncated;

    // String state
    private int escape;          // 0 none, 1 after '\', 2..5 reading \\u hex digits
    private int unicodeValue;
    private int utf8Remaining;
    private int utf8Codepoint;
    private int utf8Min;
    private int pendingHighSurrogate = -1;

    // Number and literal state
    private int numberState;
    private String literal;
    private int literalIndex;

    GeminiBodyCheck(Consumer<Buffer> output) {
        this.output = output;
    }

    /** Reads {@code chunk} and, unless it refuses the body, passes it on as it is. */
    void accept(Buffer chunk) throws Refused {
        for (int i = 0, n = chunk.length(); i < n; i++) dispatch(chunk.getByte(i) & 0xff);
        output.accept(chunk);
    }

    /** Refuses a body that ended before a complete JSON object did. */
    void end() throws Refused {
        // A number is never last: the body is an object, so a closing brace follows it
        if (state == START) throw new Refused(403, SHAPES[TOP]);
        if (state != TRAIL) throw invalid("the body ends before its JSON does");
    }

    // --- Lexer ---

    private void dispatch(int b) throws Refused {
        switch (state) {
            case START -> {
                if (isWhitespace(b)) return;
                if (b != '{') throw new Refused(403, SHAPES[TOP]);
                beginValue(b);
            }
            case VALUE, ARR_FIRST -> {
                if (isWhitespace(b)) return;
                if (state == ARR_FIRST && b == ']') {
                    closeContainer();
                    return;
                }
                if (state == ARR_FIRST) next = elementRole();
                beginValue(b);
            }
            case OBJ_FIRST, OBJ_NEXT -> {
                if (isWhitespace(b)) return;
                if (state == OBJ_FIRST && b == '}') {
                    closeContainer();
                    return;
                }
                if (b != '"') throw invalid("expected a field name");
                beginKey();
            }
            case COLON -> {
                if (isWhitespace(b)) return;
                if (b != ':') throw invalid("expected ':' after a field name");
                state = VALUE;
            }
            case AFTER -> after(b);
            case STRING -> string(b);
            case NUMBER -> number(b);
            case LITERAL -> literal(b);
            case TRAIL -> {
                if (!isWhitespace(b)) throw invalid("content after the JSON value");
            }
            default -> throw new IllegalStateException();
        }
    }

    private void after(int b) throws Refused {
        if (isWhitespace(b)) return;
        var object = isObject(depth);
        if (b == ',') {
            state = object ? OBJ_NEXT : VALUE;
            if (!object) next = elementRole();
            return;
        }
        if ((object && b == '}') || (!object && b == ']')) {
            closeContainer();
            return;
        }
        throw invalid(object ? "expected ',' or '}' in an object" : "expected ',' or ']' in an array");
    }

    /** The role of an element of the array at the current depth. */
    private byte elementRole() {
        return switch (roleAt(depth)) {
            case CONTENTS -> CONTENT;
            case PARTS -> PART;
            case TOOLS -> TOOL;
            default -> OPAQUE;
        };
    }

    private void beginValue(int b) throws Refused {
        var role = next;
        next = OPAQUE;
        // A position the check relies on has its shape, or is null and carries nothing
        if (role != OPAQUE && b != 'n' && b != (isArrayRole(role) ? '[' : '{')) throw new Refused(403, SHAPES[role]);
        switch (b) {
            case '{', '[' -> openContainer(b == '{', role);
            case '"' -> beginString(false);
            case 't', 'f', 'n' -> {
                literal = b == 't' ? "true" : b == 'f' ? "false" : "null";
                literalIndex = 1;
                state = LITERAL;
            }
            default -> {
                if (b == '-' || (b >= '0' && b <= '9')) {
                    numberState = b == '-' ? 1 : b == '0' ? 2 : 3;
                    state = NUMBER;
                } else {
                    throw invalid("expected a JSON value");
                }
            }
        }
    }

    private static boolean isArrayRole(byte role) {
        return role == CONTENTS || role == PARTS || role == TOOLS;
    }

    private void openContainer(boolean object, byte role) throws Refused {
        if (depth == MAX_DEPTH) throw invalid("nested deeper than " + MAX_DEPTH + " levels");
        depth++;
        roles[depth] = (byte) (role | (object ? OBJECT : 0));
        state = object ? OBJ_FIRST : ARR_FIRST;
    }

    private void closeContainer() {
        depth--;
        afterValue();
    }

    private void afterValue() {
        state = depth == 0 ? TRAIL : AFTER;
    }

    private byte roleAt(int d) {
        return (byte) (roles[d] & ~OBJECT);
    }

    private boolean isObject(int d) {
        return (roles[d] & OBJECT) != 0;
    }

    // --- Keys ---

    private void beginKey() {
        inKey = true;
        keyRead = roleAt(depth) != OPAQUE;
        keyName.setLength(0);
        keyTruncated = false;
        beginString(true);
    }

    private void endKey() throws Refused {
        inKey = false;
        var role = roleAt(depth);
        state = COLON;
        if (role == OPAQUE) {
            next = OPAQUE;
            return;
        }
        var name = keyTruncated ? null : keyName.toString();
        next = switch (role) {
            case TOP -> {
                if (name == null || !TOP_FIELDS.contains(name)) throw refusedField(shownName());
                yield switch (name) {
                    case "contents" -> CONTENTS;
                    case "systemInstruction" -> CONTENT;
                    case "tools" -> TOOLS;
                    default -> OPAQUE;
                };
            }
            case CONTENT, RESPONSE -> "parts".equals(name) ? PARTS : OPAQUE;
            case PART -> {
                if ("fileData".equals(name) || "file_data".equals(name)) {
                    throw new Refused(403, "a part's " + name + " is not served with the Vertex AI account");
                }
                yield "functionResponse".equals(name) || "function_response".equals(name) ? RESPONSE : OPAQUE;
            }
            case TOOL -> {
                if (name == null || !TOOL_FIELDS.contains(name)) throw refusedField("tools[]." + shownName());
                yield OPAQUE;
            }
            default -> throw new IllegalStateException();
        };
    }

    private static Refused refusedField(String name) {
        return new Refused(403, "the field " + name + " is not served with the Vertex AI account");
    }

    /** The key as read, quoted, with what could start or style a line of a log escaped. */
    private String shownName() {
        var shown = new StringBuilder("\"");
        keyName.codePoints().forEach(c -> {
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || c == 0x2028 || c == 0x2029 || c == '"' || c == '\\') shown.append(String.format("\\u%04x", c));
            else shown.appendCodePoint(c);
        });
        return shown.append(keyTruncated ? "…\"" : "\"").toString();
    }

    private void nameChar(int c) throws Refused {
        // A reader that ends a name at a NUL, say, would read another name than the check did
        if (Character.isISOControl(c)) throw new Refused(403, "a field name holds a control character");
        if (keyTruncated) return;
        if (keyName.length() >= MAX_NAME_CHARS) {
            keyTruncated = true;
            return;
        }
        keyName.appendCodePoint(c);
    }

    // --- Strings ---

    private void beginString(boolean key) {
        state = STRING;
        escape = 0;
        utf8Remaining = 0;
        pendingHighSurrogate = -1;
        if (!key) inKey = false;
    }

    private void string(int b) throws Refused {
        if (escape == 1) {
            escape = 0;
            switch (b) {
                case '"', '\\', '/' -> decoded(b);
                case 'b' -> decoded('\b');
                case 'f' -> decoded('\f');
                case 'n' -> decoded('\n');
                case 'r' -> decoded('\r');
                case 't' -> decoded('\t');
                case 'u' -> {
                    escape = 2;
                    unicodeValue = 0;
                }
                default -> throw invalid("an invalid escape in a string");
            }
            return;
        }
        if (escape >= 2) {
            var digit = Character.digit(b, 16);
            if (digit < 0) throw invalid("an invalid \\u escape in a string");
            unicodeValue = unicodeValue * 16 + digit;
            if (++escape == 6) {
                escape = 0;
                escapedChar(unicodeValue);
            }
            return;
        }
        if (b < 0x20) throw invalid("a control character in a string");
        if (b == '\\') {
            if (utf8Remaining != 0) malformed();
            escape = 1;
            return;
        }
        if (pendingHighSurrogate >= 0 && reading()) malformed();
        if (b == '"') {
            if (utf8Remaining != 0) malformed();
            if (inKey) endKey();
            else afterValue();
            return;
        }
        rawByte(b);
    }

    /** Whether the string's text is read: a name the check compares. */
    private boolean reading() {
        return inKey && keyRead;
    }

    private void escapedChar(int c) throws Refused {
        if (!reading()) return;
        if (pendingHighSurrogate >= 0) {
            if (c < 0xDC00 || c > 0xDFFF) malformed();
            var high = pendingHighSurrogate;
            pendingHighSurrogate = -1;
            nameChar(Character.toCodePoint((char) high, (char) c));
            return;
        }
        if (c >= 0xD800 && c <= 0xDBFF) {
            pendingHighSurrogate = c;
            return;
        }
        if (c >= 0xDC00 && c <= 0xDFFF) malformed();
        nameChar(c);
    }

    private void decoded(int c) throws Refused {
        if (!reading()) return;
        if (pendingHighSurrogate >= 0) malformed();
        nameChar(c);
    }

    /**
     * A byte of a string as it is (not an escape): well-formed UTF-8 in every string, or refused, so a
     * lenient decoder cannot end a string where the check did not; a name's characters are kept.
     */
    private void rawByte(int b) throws Refused {
        if (utf8Remaining == 0) {
            if (b < 0x80) {
                if (reading()) nameChar(b);
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
                malformed();
            }
            return;
        }
        if ((b & 0xC0) != 0x80) malformed();
        utf8Codepoint = (utf8Codepoint << 6) | (b & 0x3F);
        if (--utf8Remaining == 0) {
            if (utf8Codepoint < utf8Min || utf8Codepoint > 0x10FFFF
                    || (utf8Codepoint >= 0xD800 && utf8Codepoint <= 0xDFFF)) {
                malformed();
            }
            if (reading()) nameChar(utf8Codepoint);
        }
    }

    private void malformed() throws Refused {
        if (!reading()) throw invalid("a string is not well-formed UTF-8");
        throw new Refused(403, "a field name is not well-formed Unicode");
    }

    // --- Numbers and literals ---

    // 1 after '-', 2 after a leading 0, 3 in integer digits, 4 after '.', 5 in fraction digits,
    // 6 after 'e', 7 after the exponent's sign, 8 in exponent digits
    private void number(int b) throws Refused {
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
        if (accepted < 0) throw invalid("an invalid number");
        if (accepted == 1) return;
        // The number ended at a byte that belongs to what follows
        endNumber();
        afterValue();
        dispatch(b);
    }

    private int next(int s) {
        numberState = s;
        return 1;
    }

    private void endNumber() throws Refused {
        if (numberState != 2 && numberState != 3 && numberState != 5 && numberState != 8) {
            throw invalid("an invalid number");
        }
    }

    private void literal(int b) throws Refused {
        if (b != literal.charAt(literalIndex)) throw invalid("an invalid literal");
        if (++literalIndex == literal.length()) afterValue();
    }

    private static Refused invalid(String reason) {
        return new Refused(400, "the body is not valid JSON: " + reason);
    }

    private static boolean isWhitespace(int b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }
}
