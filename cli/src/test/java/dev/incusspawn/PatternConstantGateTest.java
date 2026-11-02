package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A regular expression is compiled once, into a {@code static final Pattern}, never on every call
 * (#1929). {@code Pattern.compile} is expensive, and so is every {@code String.matches},
 * {@code replaceAll}, {@code replaceFirst} and regex {@code split}, which compile their pattern
 * each time they run. A constant is also built into the native image heap at build time, so it
 * costs nothing at startup either. Where the pattern is only known at run time (a user's pattern,
 * one built from configuration), the line says so with a {@value #MARKER} comment.
 */
class PatternConstantGateTest {

    static final String MARKER = "regex at run time:";

    /** {@code Pattern.compile(} or {@code Pattern.matches(}. */
    private static final Pattern COMPILE = Pattern.compile("\\bPattern\\s*\\.\\s*(compile|matches)\\s*\\(");
    /** A {@code String} method that compiles its first argument, given a literal or a constant. */
    private static final Pattern STRING_REGEX = Pattern.compile(
            "\\.\\s*(?:(matches|split)\\s*\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[A-Z][A-Z0-9_]*\\b)"
                    + "|(?:replaceAll|replaceFirst)\\s*\\(\\s*(?:\"(?:[^\"\\\\]|\\\\.)*\"|[A-Z][A-Z0-9_]*\\b)\\s*(?:,|\\+[^;]*?,))");
    private static final Pattern STATEMENT_START = Pattern.compile("[;{}]");
    private static final Pattern COMMENT_OR_STRING =
            Pattern.compile("//[^\\n]*|/\\*(?s:.*?)\\*/|\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)+'");
    /** A {@code static final Pattern} field declaration up to its initializer. */
    private static final Pattern CONSTANT = Pattern.compile("^(?:@\\w+\\s+)*(?:(?:private|protected|public)\\s+)?"
            + "static\\s+final\\s+(?:java\\.util\\.regex\\.)?Pattern\\s+\\w+\\s*=\\s*(?:java\\.util\\.regex\\.)?$");

    @Test
    void everyPatternIsAConstant() throws IOException {
        var offenders = new StringBuilder();
        for (var root : new String[] {"src/main/java", "../common/src/main/java", "../proxy/src/main/java"}) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    for (var line : offenders(Files.readString(file))) {
                        offenders.append(file).append(':').append(line).append('\n');
                    }
                }
            }
        }
        assertEquals("", offenders.toString(),
                "compile each regex once into a static final Pattern; one only known at run time says so"
                        + " with a '// " + MARKER + "' comment on its line");
    }

    @Test
    void theGateSeesWhatCompilesPerCall() {
        assertEquals(1, offenders("class A { boolean f(String s) { return s.matches(\"[a-z]+\"); } }").size());
        assertEquals(1, offenders("class A { String f(String s) { return s.replaceAll(\"\\\\s+\", \" \"); } }").size());
        assertEquals(1, offenders("class A { String f(String s) { return s.replaceFirst(ID, \"\"); } }").size());
        assertEquals(1, offenders("class A { String[] f(String s) { return s.split(\"\\\\s+\"); } }").size());
        assertEquals(1, offenders("class A { String f(String s, String o) { return s.replaceAll(\"^\" + o, \"\"); } }").size(),
                "a pattern built by concatenation still compiles per call");
        assertEquals(1, offenders("class A { String[] f(String s) { return s.split(\" +\"); } }").size());
        assertEquals(1, offenders("class A { void f(String r) { var p = Pattern.compile(r); } }").size());
        assertEquals(1, offenders("class A { static final Pattern P;\n static { P = Pattern.compile(\"x\"); } }").size(),
                "only a field initializer is a constant");
        assertEquals(1, offenders("class A { final Pattern p = Pattern.compile(\"x\"); }").size());
    }

    @Test
    void theGateLetsThroughWhatIsCompiledOnce() {
        assertTrue(offenders("class A { private static final Pattern P =\n    Pattern.compile(\"x\" + Y,\n Pattern.MULTILINE); }").isEmpty());
        assertTrue(offenders("class A { static final Pattern P = java.util.regex.Pattern.compile(\"x\"); }").isEmpty());
        assertTrue(offenders("class A { boolean f(String s) { return P.matcher(s).matches(); } }").isEmpty());
        assertTrue(offenders("class A { String f(Matcher m) { return m.replaceAll(\"$1\"); } }").isEmpty());
        assertTrue(offenders("class A { String f(Matcher m) { return m.replaceAll(\"$1\" + x(\"y\") + \"@\"); } }").isEmpty());
        assertTrue(offenders("class A { String[] f(String s) { return s.split(\"\\n\", -1); } }").isEmpty(),
                "a one-character split takes String's fast path and compiles nothing");
        assertTrue(offenders("class A { String[] f(String s) { return s.split(\"\\t\"); } }").isEmpty());
        assertTrue(offenders("class A { String[] f(String s) { return s.split(\"\\\\.\"); } }").isEmpty());
        assertTrue(offenders("class A { String[] f(String s) { return s.split(\":\"); } }").isEmpty());
        assertTrue(offenders("class A { void f(String r) { Pattern.compile(r); // " + MARKER + " a user's\n } }").isEmpty());
        assertTrue(offenders("class A { int x; /** {@code y}; */\n static final Pattern P = Pattern.compile(\"x\"); }").isEmpty(),
                "a comment before the field is not a statement boundary");
        assertTrue(offenders("class A { String s = \"a;\" + ';' + \"//\";\n static final Pattern P = Pattern.compile(\"x\"); }").isEmpty(),
                "nor is a semicolon in a string");
        assertTrue(offenders("class A {\n // s.matches(\"x\")\n }").isEmpty());
        assertFalse(offenders("class A { String[] f(String s) { return s.split(\".\"); } }").isEmpty(),
                "a regex metacharacter alone is still a regex");
    }

    /** The 1-based lines of {@code source} that compile a regex outside a constant. */
    static java.util.List<Integer> offenders(String source) {
        var result = new java.util.ArrayList<Integer>();
        var lines = source.split("\n", -1);
        var compile = COMPILE.matcher(source);
        while (compile.find()) {
            if (!isConstant(source, compile.start())) report(source, lines, compile.start(), result);
        }
        var call = STRING_REGEX.matcher(source);
        while (call.find()) {
            if ("split".equals(call.group(1)) && call.group(2).startsWith("\"") && isFastPath(unescape(call.group(2)))) continue;
            report(source, lines, call.start(), result);
        }
        return result.stream().distinct().sorted().toList();
    }

    private static void report(String source, String[] lines, int offset, java.util.List<Integer> result) {
        int line = (int) source.substring(0, offset).chars().filter(c -> c == '\n').count();
        var text = lines[line].strip();
        if (text.startsWith("//") || text.startsWith("*") || text.startsWith("/*") || text.contains(MARKER)) return;
        result.add(line + 1);
    }

    /** Whether the {@code Pattern.compile} at {@code offset} initializes a {@code static final} field. */
    private static boolean isConstant(String source, int offset) {
        var before = COMMENT_OR_STRING.matcher(source.substring(0, offset)).replaceAll(" ");
        var start = STATEMENT_START.matcher(before);
        int from = 0;
        while (start.find()) from = start.end();
        var statement = before.substring(from).strip();
        return CONSTANT.matcher(statement).find();
    }

    /** {@code String.split}'s fast path: one character that is not a metacharacter, or one escaped. */
    private static boolean isFastPath(String regex) {
        if (regex.length() == 1) return ".$|()[{^?*+\\".indexOf(regex.charAt(0)) < 0;
        return regex.length() == 2 && regex.charAt(0) == '\\' && !Character.isLetterOrDigit(regex.charAt(1));
    }

    private static String unescape(String literal) {
        var body = literal.substring(1, literal.length() - 1);
        var out = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\' || i + 1 == body.length()) {
                out.append(c);
                continue;
            }
            char next = body.charAt(++i);
            out.append(switch (next) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case '0' -> '\0';
                default -> next;
            });
        }
        return out.toString();
    }
}
