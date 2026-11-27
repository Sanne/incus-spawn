package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test waits for another thread or process through {@code Eventually} (common test-jar), never through a
 * loop of its own that sleeps, parks or spins (#2504, #2686).
 * A hand-rolled loop picks its own pause and step count, so it either wastes time on every run or gives up too
 * soon on a loaded CI host, and its failure rarely says what it was waiting for.
 * A loop whose pause is the test (it paces a producer, spaces the retries of an action run again, or leaves time
 * for something that must not happen) says so with a {@value #MARKER} comment on the pause's line.
 */
class PollingLoopGateTest {

    static final String MARKER = "pause is the test:";

    /** The test sources of every module, from the {@code cli} module's directory. */
    private static final String[] ROOTS = {"src/test/java", "../common/src/test/java", "../proxy/src/test/java"};
    /** {@code Eventually} is the one loop allowed to pause, since it is the way every other test waits. */
    private static final String HELPER = "dev/incusspawn/util/Eventually.java";

    /** A sleep, a park or a spin: {@code Thread.sleep}, a {@code TimeUnit}'s {@code sleep}, {@code LockSupport.park*}, {@code Thread.onSpinWait}, {@code Thread.yield}. */
    private static final Pattern PAUSE = Pattern.compile(
            "\\bThread\\s*\\.\\s*(?:onSpinWait|yield)\\s*\\(|\\b\\w+\\s*\\.\\s*sleep\\s*\\("
                    + "|\\bLockSupport\\s*\\.\\s*park\\w*\\s*\\(");
    /** A loop statement, or a {@code forEach} whose argument is the loop's body. */
    private static final Pattern LOOP = Pattern.compile("\\b(for|while|do)\\b|\\.\\s*(forEach(?:Ordered)?)\\s*(?=\\()");
    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "//[^\\n]*|/\\*(?s:.*?)\\*/|\"\"\"(?s:.*?)\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)+'");

    @Test
    void noTestPollsInALoopOfItsOwn() throws IOException {
        var offenders = new StringBuilder();
        for (var root : ROOTS) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    if (file.toString().replace('\\', '/').endsWith(HELPER)) continue;
                    for (var line : offenders(Files.readString(file))) {
                        offenders.append(file).append(':').append(line).append('\n');
                    }
                }
            }
        }
        assertEquals("", offenders.toString(),
                "wait through Eventually.await, poll or holdsWithin; a loop whose pause is the test says so"
                        + " with a '// " + MARKER + "' comment on the pause's line");
    }

    @Test
    void theGateSeesAPauseInALoop() {
        assertEquals(List.of(1), offenders("for (int i = 0; i < 500 && !done(); i++) Thread.sleep(10);"));
        assertEquals(List.of(3), offenders("while (true) {\n if (done()) return;\n Thread.sleep(100);\n}"));
        assertEquals(List.of(2), offenders("do {\n TimeUnit.MILLISECONDS.sleep(5);\n} while (!done());"));
        assertEquals(List.of(1), offenders("while (!done()) LockSupport.parkNanos(1_000);"));
        assertEquals(List.of(1), offenders("while (!flag.get()) Thread.onSpinWait();"));
        assertEquals(List.of(3), offenders("for (var f : files) {\n if (f.exists()) {\n  Thread.sleep(1);\n }\n}"),
                "a pause in a block nested in the loop");
        assertEquals(List.of(2), offenders("for (;;)\n if (!done()) Thread.sleep(1); else break;"),
                "a loop's body that is one statement, not a block");
        assertEquals(List.of(2), offenders("while (!done())\n try { check(); } catch (Exception e) { Thread.sleep(1); }"),
                "a try statement goes on through its catch");
        assertEquals(List.of(3), offenders("IntStream.range(0, 50).forEach(i -> {\n if (done()) return;\n Thread.sleep(10);\n});"),
                "a forEach's lambda is a loop's body");
        assertEquals(List.of(2), offenders("while (!done()) {\n unit.sleep(1);\n}"), "a TimeUnit held in a variable");
    }

    @Test
    void theGateLetsThroughWhatIsNotAPollingLoop() {
        assertTrue(offenders("Thread.sleep(200);\nassertFalse(happened());").isEmpty(), "a pause outside a loop");
        assertTrue(offenders("for (int i = 0; i < 3; i++) send(i);\nThread.sleep(50);").isEmpty(),
                "a pause after a loop with a one-statement body");
        assertTrue(offenders("while (it.hasNext()) {\n it.next();\n}\nThread.sleep(50);").isEmpty(),
                "a pause after a loop's block");
        assertTrue(offenders("do {\n x++;\n} while (x < 3);\nThread.sleep(1);").isEmpty(), "a pause after a do loop");
        assertTrue(offenders("for (int i = 0; i < 3; i++) {\n send(i);\n Thread.sleep(5); // " + MARKER + " paces the producer\n}")
                .isEmpty());
        assertTrue(offenders("for (int i = 0; i < 3; i++) {\n // Thread.sleep(5);\n log(\"while Thread.sleep(1)\");\n}")
                .isEmpty(), "a pause in a comment or a string");
        assertTrue(offenders("String format = \"do {\"; Thread.sleep(1);").isEmpty(), "a loop keyword in a string");
        assertTrue(offenders("Eventually.await(\"done\", () -> done());").isEmpty());
    }

    /** The 1-based lines of {@code source} that pause inside a loop without the marker. */
    static List<Integer> offenders(String source) {
        var lines = source.split("\n", -1);
        var code = blank(source);
        var bodies = new ArrayList<int[]>();
        var loop = LOOP.matcher(code);
        while (loop.find()) {
            var keyword = loop.group(1) != null ? loop.group(1) : loop.group(2);
            var body = body(code, keyword, loop.end());
            if (body != null) bodies.add(body);
        }
        var result = new ArrayList<Integer>();
        var pause = PAUSE.matcher(code);
        while (pause.find()) {
            int at = pause.start();
            if (bodies.stream().noneMatch(b -> b[0] <= at && at < b[1])) continue;
            int line = (int) code.substring(0, at).chars().filter(c -> c == '\n').count();
            if (!lines[line].contains(MARKER)) result.add(line + 1);
        }
        return result.stream().distinct().sorted().toList();
    }

    /** The source with every comment and string literal replaced by spaces, newlines kept, so offsets stay. */
    private static String blank(String source) {
        var out = new StringBuilder(source);
        var m = COMMENT_OR_STRING.matcher(source);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                if (out.charAt(i) != '\n') out.setCharAt(i, ' ');
            }
        }
        return out.toString();
    }

    /**
     * The range of the body of the loop whose keyword ends at {@code from}, or null when there is none.
     * A {@code while} whose condition is followed by {@code ;} is a do loop's tail or has an empty body.
     */
    private static int[] body(String code, String keyword, int from) {
        int at = skipSpace(code, from);
        if (keyword.startsWith("forEach")) return new int[] {at, close(code, at)};
        if (!keyword.equals("do")) {
            if (at >= code.length() || code.charAt(at) != '(') return null;
            at = skipSpace(code, close(code, at));
        }
        if (at >= code.length() || code.charAt(at) == ';') return null;
        return new int[] {at, statementEnd(code, at)};
    }

    /** The offset just past the bracket that closes the one at {@code open}. */
    private static int close(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{' || c == '[') depth++;
            else if ((c == ')' || c == '}' || c == ']') && --depth == 0) return i + 1;
        }
        return code.length();
    }

    /**
     * The offset just past the statement starting at {@code at}: a block ends at its closing brace, any other
     * statement at its {@code ;} outside brackets, so a nested block or an {@code else} stays inside it.
     */
    private static int statementEnd(String code, int at) {
        if (code.charAt(at) == '{') return close(code, at);
        int depth = 0;
        for (int i = at; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{' || c == '[') depth++;
            else if (c == ')' || c == '}' || c == ']') {
                if (--depth < 0) return i;
                if (depth == 0 && c == '}' && !continues(code, i + 1)) return i + 1;
            } else if (c == ';' && depth == 0) {
                if (!continues(code, i + 1)) return i + 1;
            }
        }
        return code.length();
    }

    /** Whether the statement goes on after {@code at}, with an {@code else}, {@code catch} or {@code finally}. */
    private static boolean continues(String code, int at) {
        int next = skipSpace(code, at);
        return startsWith(code, next, "else") || startsWith(code, next, "catch") || startsWith(code, next, "finally");
    }

    private static boolean startsWith(String code, int at, String word) {
        return code.startsWith(word, at) && (at + word.length() == code.length()
                || !Character.isJavaIdentifierPart(code.charAt(at + word.length())));
    }

    private static int skipSpace(String code, int at) {
        while (at < code.length() && Character.isWhitespace(code.charAt(at))) at++;
        return at;
    }
}
