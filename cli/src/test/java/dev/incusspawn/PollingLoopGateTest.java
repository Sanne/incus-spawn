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
 * A test waits for another thread or process through {@code Eventually} (common test-jar), never
 * through a loop of its own around a sleep (#2504, design/testing.md "Waiting for another thread or
 * process"). Its own loop samples at whatever pace it picked, often 10 to 100 ms, so the suite waits
 * longer than the condition takes, and its timeout says nothing of what was awaited.
 * A sleep, park or spin inside a loop of a test source is that loop.
 * Where the sleep is the test (it paces a producer, spaces the retries of an action run again, or gives
 * something time to happen that must not), its line says so with a {@value #MARKER} comment and why.
 */
class PollingLoopGateTest {

    static final String MARKER = "not a wait:";

    private static final Pattern PAUSE = Pattern.compile(
            "\\bThread\\s*\\.\\s*(?:sleep|onSpinWait|yield)\\s*\\("
                    + "|\\b(?:NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)\\s*\\.\\s*sleep\\s*\\("
                    + "|\\bLockSupport\\s*\\.\\s*park(?:Nanos|Until)?\\s*\\(");
    private static final Pattern LOOP = Pattern.compile("\\b(?:for|while)\\s*\\(|\\bdo\\s*\\{");
    private static final Pattern CONTINUED = Pattern.compile("\\s*(?:else|catch|finally)\\b");
    private static final Pattern COMMENT_OR_STRING = Pattern.compile(
            "//[^\\n]*|/\\*(?s:.*?)\\*/|\"\"\"(?s:.*?)\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)+'");

    /** The helper itself is the one loop allowed to sleep. */
    private static final String HELPER = "Eventually.java";

    @Test
    void noTestWaitsInALoopOfItsOwn() throws IOException {
        var offenders = new StringBuilder();
        for (var root : new String[] {"src/test/java", "../common/src/test/java", "../proxy/src/test/java"}) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    if (file.getFileName().toString().equals(HELPER)) continue;
                    for (var line : offenders(Files.readString(file))) {
                        offenders.append(file).append(':').append(line).append('\n');
                    }
                }
            }
        }
        assertEquals("", offenders.toString(),
                "wait through Eventually.await, poll or holdsWithin, not a loop around a sleep;"
                        + " a sleep that is not a wait says so with a '// " + MARKER + "' comment on its line");
    }

    @Test
    void theGateSeesALoopAroundASleep() {
        assertEquals(List.of(1), offenders("class A { void f() { for (int i = 0; i < 500 && !done(); i++) Thread.sleep(10); } }"),
                "a loop whose body is the sleep alone");
        assertEquals(List.of(3), offenders("class A { void f() {\n String s = screen();\n for (int i = 0; i < 500 && !s.contains(\"x\"); i++) { Thread.sleep(10);\n s = screen(); } } }"));
        assertEquals(List.of(2), offenders("class A { void f() { while (!done())\n Thread.sleep(10); } }"),
                "the body on the next line");
        assertEquals(List.of(2), offenders("class A { void f() { do {\n TimeUnit.MILLISECONDS.sleep(5); } while (!done()); } }"));
        assertEquals(List.of(2), offenders("class A { void f() { while (true) { if (done()) break;\n try { Thread.sleep(100); } catch (InterruptedException e) { return; } } } }"),
                "a sleep nested deeper in the body");
        assertEquals(List.of(1), offenders("class A { void f() { while (!done()) Thread.onSpinWait(); } }"), "a spin");
        assertEquals(List.of(1), offenders("class A { void f() { while (!done()) LockSupport.parkNanos(1000); } }"));
        assertEquals(List.of(1), offenders("class A { void f() { for (var x : xs) { if (x.ok()) Thread.sleep(5); } } }"));
        assertEquals(List.of(1), offenders("class A { void f() { while (!done()) if (a()) { g(); } else Thread.sleep(5); } }"),
                "an unbraced body's else");
        assertEquals(List.of(1), offenders("class A { void f() { while (!done()) try { g(); } finally { Thread.sleep(5); } } }"),
                "an unbraced body's finally");
        assertEquals(List.of(1), offenders("class A { void f() { while (!done()) TimeUnit.HOURS.sleep(1); } }"));
    }

    @Test
    void theGateLetsThroughWhatIsNotAWait() {
        assertTrue(offenders("class A { void f() { Thread.sleep(300); } }").isEmpty(),
                "a sleep outside a loop holds a stall open or asserts an absence");
        assertTrue(offenders("class A { void f() { for (int i = 0; i < 3; i++) press();\n Thread.sleep(300); } }").isEmpty(),
                "a sleep after a one-statement loop is not in it");
        assertTrue(offenders("class A { void f() { while (more()) { write();\n Thread.sleep(50); // " + MARKER + " a new entry every 50 ms\n } } }").isEmpty());
        assertTrue(offenders("class A { void f() {\n // while (x) Thread.sleep(10);\n } }").isEmpty(), "a comment");
        assertTrue(offenders("class A { String s = \"for (;;) Thread.sleep(1)\"; }").isEmpty(), "a string");
        assertTrue(offenders("class A { void f() { for (var x : xs) { g(x); }\n Thread.sleep(5); } }").isEmpty(),
                "a sleep after a loop's braces");
        assertTrue(offenders("class A { void f() { while (more()) if (a()) { g(); }\n Thread.sleep(5); } }").isEmpty(),
                "a sleep after an unbraced body's block");
        assertTrue(offenders("class A { void f() { char c = '{'; for (var x : xs) g(x);\n Thread.sleep(5); } }").isEmpty(),
                "a brace in a character literal");
    }

    /** The 1-based lines of {@code source} that pause inside a loop with no {@value #MARKER} comment. */
    static List<Integer> offenders(String source) {
        var code = blank(source);
        var loops = loopBodies(code);
        var lines = source.split("\n", -1);
        var result = new ArrayList<Integer>();
        var pause = PAUSE.matcher(code);
        while (pause.find()) {
            int at = pause.start();
            if (loops.stream().noneMatch(body -> body[0] <= at && at < body[1])) continue;
            int line = (int) code.substring(0, at).chars().filter(c -> c == '\n').count();
            if (!lines[line].contains(MARKER)) result.add(line + 1);
        }
        return result.stream().distinct().sorted().toList();
    }

    /** {@code source} with every comment and literal blanked, its offsets and line breaks kept. */
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

    /** The {@code [start, end)} spans of every loop's body (and a {@code for}'s or {@code while}'s header). */
    private static List<int[]> loopBodies(String code) {
        var bodies = new ArrayList<int[]>();
        var loop = LOOP.matcher(code);
        while (loop.find()) {
            int at = loop.end() - 1;
            if (code.charAt(at) == '(') {
                int close = matching(code, at, '(', ')');
                if (close < 0) continue;
                at = close + 1;
                while (at < code.length() && Character.isWhitespace(code.charAt(at))) at++;
                if (at < code.length() && code.charAt(at) == ';') continue; // the while of a do, or an empty loop
            }
            int end = at < code.length() && code.charAt(at) == '{' ? matching(code, at, '{', '}') : statementEnd(code, at);
            if (end >= 0) bodies.add(new int[] {loop.start(), end + 1});
        }
        return bodies;
    }

    private static int matching(String code, int open, char opening, char closing) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == opening) depth++;
            else if (c == closing && --depth == 0) return i;
        }
        return -1;
    }

    /**
     * The {@code ;} ending the statement at {@code from}, or the brace closing a block it opens, read on
     * through an {@code else}, {@code catch} or {@code finally} that continues it.
     */
    private static int statementEnd(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{') depth++;
            else if (c == ')' && --depth < 0) return i;
            else if (c == '}' && --depth < 0) return i;
            else if (c == '}' && depth == 0 && !CONTINUED.matcher(code).region(i + 1, code.length()).lookingAt()) return i;
            else if (c == ';' && depth == 0) return i;
        }
        return -1;
    }
}
