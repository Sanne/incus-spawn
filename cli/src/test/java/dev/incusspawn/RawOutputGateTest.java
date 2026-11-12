package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Output reaches the terminal only through the gate classes below (#1703).
 * A message printed straight to {@code System.out} or {@code System.err} from code the TUI can
 * reach is drawn over and lost (#872, #1154, #1646, #1689), and a line indented by hand drifts
 * from the group it sits in, so main source of all three modules may not use or replace the raw
 * streams, use the console or {@code printStackTrace()}, nor print a string literal that starts
 * with spaces, through a stream or the proxy's {@code ProxyLog.stdout} alike.
 * A logger, {@code Thread.dumpStack()} and reflection on {@code System} write to the same streams,
 * and only named files may hand the terminal to a child process or open a terminal device.
 * Comments and the contents of string literals are not scanned, and Unicode escapes are decoded
 * first, as the compiler does, so none can spell a stream or end a comment.
 */
class RawOutputGateTest {

    private static final Pattern RAW_STREAM = Pattern.compile(
            "\\bSystem\\s*\\.\\s*(?:(?:out|err)\\b|(?:console|setOut|setErr)\\s*\\()"
                    + "|\\bSystem\\s*::\\s*(?:console|setOut|setErr)\\b"
                    + "|\\bFileDescriptor\\s*\\.\\s*(?:out|err)\\b"
                    + "|\\.\\s*printStackTrace\\s*\\(\\s*\\)"
                    + "|\\bThread\\s*\\.\\s*dumpStack\\s*\\("
                    // A logger prints on stderr, and reflection reaches the streams by name
                    + "|\\bSystem\\s*(?:\\.\\s*(?:getLogger|Logger(?:Finder)?|class)|::\\s*getLogger)\\b"
                    + "|\\b(?:" + String.join("|", Stream.of("org.jboss.logging", "org.jboss.logmanager",
                            "io.quarkus.logging", "java.util.logging", "org.apache.logging", "org.apache.commons.logging",
                            "org.apache.log4j", "org.slf4j", "ch.qos.logback").map(RawOutputGateTest::qualified).toList()) + ")\\b");

    /** A pattern for a qualified name, spaced around its dots as the compiler allows. */
    private static String qualified(String name) {
        return name.replace(".", "\\s*\\.\\s*");
    }
    private static final Pattern INDENTED_PRINT = Pattern.compile(
            "\\b(?:print|println|printf|stdout)\\s*\\(\\s*\"(?:\\\\n|%n)*(?: |\\\\t)");
    /** The pieces of a string literal's start that {@link #blank} keeps, so {@link #INDENTED_PRINT} can see them. */
    private static final Set<String> LEADING_INDENT = Set.of(" ", "\\n", "\\t", "%", "n");
    /** Refused in every file, gate classes included: it would hide every bare {@code out.println} that follows it. */
    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "\\bimport\\s+static\\s+java\\.lang\\.System\\.(?:out|err|console|setOut|setErr|\\*)\\s*;");

    /** Repository-relative paths of the gate classes, each with why it may write. */
    private static final Map<String, String> EXEMPT = Map.of(
            "common/src/main/java/dev/incusspawn/util/TerminalOutput.java", "the terminal printer",
            "common/src/main/java/dev/incusspawn/util/TerminalStreams.java", "the process's streams, for what hands the terminal over or draws on it whole",
            "cli/src/main/java/dev/incusspawn/command/StdoutOnStderr.java", "points stdout at stderr while a machine format gets its answer",
            "cli/src/main/java/dev/incusspawn/mcp/StdioGuard.java", "moves stdout off the MCP protocol stream",
            "cli/src/main/java/dev/incusspawn/command/Prompts.java", "owns the console",
            "common/src/main/java/dev/incusspawn/proxy/ProxyLog.java", "the proxy's log, silent while the TUI draws",
            "cli/src/main/java/dev/incusspawn/mcp/McpLog.java", "isx mcp's log on stderr, its client's log, beside the protocol on stdout",
            "cli/src/main/java/dev/incusspawn/tui/StreamWatch.java", "notes what anything prints before the TUI opens, so the hand-over waits for it to be read");

    /** {@code graal/*Feature} classes run inside native-image at build time, so they print to its log. */
    private static final Pattern BUILD_TIME_FEATURE =
            Pattern.compile("common/src/main/java/dev/incusspawn/graal/[^/]*Feature\\.java");

    private static boolean exempt(String path) {
        return EXEMPT.containsKey(path) || BUILD_TIME_FEATURE.matcher(path).matches();
    }

    /** What a file holds: the one-based lines of each kind of offence. */
    record Found(List<Integer> raw, List<Integer> indented) {}

    @Test
    void outputStaysBehindTheGate() throws IOException {
        var failures = new StringBuilder();
        for (var source : sources("src/main/java").entrySet()) {
            var path = source.getKey();
            var code = blank(source.getValue());
            var imports = lines(STATIC_IMPORT, code);
            if (!imports.isEmpty()) {
                failures.append(path).append(": static import of a System stream at lines ")
                        .append(imports).append('\n');
            }
            if (exempt(path)) continue;
            var found = find(code);
            report(failures, path, "raw output uses", found.raw());
            report(failures, path, "indented print literals", found.indented());
        }
        assertEquals("", failures.toString(),
                "write through BuildOutput or a sink the caller hands in (CLAUDE.md, \"Never print straight to"
                        + " stdout/stderr\"), and indent with BuildOutput's groups, not leading spaces");
    }

    private static void report(StringBuilder failures, String path, String kind, List<Integer> lines) {
        if (lines.isEmpty()) return;
        failures.append(path).append(": ").append(lines.size()).append(' ').append(kind)
                .append(" at lines ").append(lines).append('\n');
    }

    private static final Pattern TERMINAL_STREAMS = Pattern.compile("\\bTerminalStreams\\s*(?:\\.|::)");

    /**
     * Who may take the terminal's own streams from {@code TerminalStreams}, each with why: the gate
     * is exempt, so without this list any code could print raw through it.
     */
    private static final Map<String, String> MAY_USE_TERMINAL_STREAMS = Map.of(
            "common/src/main/java/dev/incusspawn/incus/IncusClient.java", "hands the terminal to an interactive guest command",
            "common/src/main/java/dev/incusspawn/incus/IncusApi.java", "writes an interactive shell's raw bytes",
            "common/src/main/java/dev/incusspawn/incus/InteractiveShell.java", "draws the shell's status bar on the terminal it hands the guest",
            "common/src/main/java/dev/incusspawn/util/TerminalOutput.java", "writes a window title only where TerminalStreams.terminal() finds one",
            "common/src/main/java/dev/incusspawn/util/TerminalProgress.java", "the live renderer, which draws on the terminal whole",
            "cli/src/main/java/dev/incusspawn/command/Prompts.java", "its terminal() asks only when stdin and stdout are a terminal",
            "cli/src/main/java/dev/incusspawn/command/GitRemoteHelperCommand.java", "a git remote helper: stdin and stdout are git's protocol, stderr what git shows",
            "cli/src/main/java/dev/incusspawn/command/SshProxyCommand.java", "an ssh ProxyCommand: stdin and stdout are the connection, stderr what ssh shows");

    @Test
    void onlyTheNamedPassthroughsTakeTheTerminalsStreams() throws IOException {
        var gate = "common/src/main/java/dev/incusspawn/util/TerminalStreams.java";
        assertEquals("", strays(TERMINAL_STREAMS, path -> path.equals(gate) || MAY_USE_TERMINAL_STREAMS.containsKey(path)),
                "report through BuildOutput or a caller's Output; TerminalStreams is for"
                        + " what hands the terminal over or draws on it whole (MAY_USE_TERMINAL_STREAMS)");
    }

    @Test
    void everyTerminalStreamsCallerStillCallsIt() throws IOException {
        assertEquals("", unneeded(MAY_USE_TERMINAL_STREAMS, TERMINAL_STREAMS),
                "drop these from MAY_USE_TERMINAL_STREAMS: they no longer call TerminalStreams");
    }

    /** A child process that inherits stdout or stderr prints past the bound output, as a raw print does. */
    private static final Pattern INHERITS_TERMINAL = Pattern.compile("(?:\\.\\s*inheritIO\\s*\\(|::\\s*inheritIO\\b)|\\bRedirect\\s*\\.\\s*INHERIT\\b"
            + "|\\bimport\\s+static\\s+java\\.lang\\.ProcessBuilder\\s*\\.\\s*Redirect\\s*\\.\\s*\\*\\s*;");

    /** Who may hand a child process the terminal, each with why. */
    private static final Map<String, String> MAY_INHERIT_TERMINAL = Map.ofEntries(
            Map.entry("common/src/main/java/dev/incusspawn/incus/IncusApi.java", "sets the terminal raw for an interactive shell"),
            Map.entry("common/src/main/java/dev/incusspawn/util/TerminalProgress.java", "asks stty for the terminal's size, on stdin alone"),
            Map.entry("common/src/main/java/dev/incusspawn/tool/YamlToolAction.java", "runs a tool's command once BuildOutput.releaseTerminal() hands it over"),
            Map.entry("cli/src/main/java/dev/incusspawn/command/ProxyStartCommand.java", "the proxy in the foreground, its log on the terminal"),
            Map.entry("cli/src/main/java/dev/incusspawn/command/CredentialSetup.java", "'claude setup-token', whose prompts reach the user"),
            Map.entry("cli/src/main/java/dev/incusspawn/command/DoctorCommand.java", "an interactive remediation the user chose"),
            Map.entry("cli/src/main/java/dev/incusspawn/command/InitCommand.java", "host setup commands, shown as they run, and stdin for a sudo prompt"),
            Map.entry("cli/src/main/java/dev/incusspawn/command/GroupContinuation.java", "the whole command run again under sg, on this process's streams (#2167)"),
            Map.entry("cli/src/main/java/dev/incusspawn/command/TemplatesCommand.java", "the user's editor"),
            Map.entry("cli/src/main/java/dev/incusspawn/command/LogFollower.java", "follows a log on the terminal, from the CLI alone"),
            Map.entry("cli/src/main/java/dev/incusspawn/command/CowPoolBacking.java", "host pool commands, shown as they run"),
            Map.entry("proxy/src/main/java/dev/incusspawn/proxy/McpBridge.java", "the bridged isx mcp's log on the proxy's stderr"));

    @Test
    void onlyTheNamedFilesHandAChildTheTerminal() throws IOException {
        assertEquals("", strays(INHERITS_TERMINAL, MAY_INHERIT_TERMINAL::containsKey),
                "capture the child's output and report it through BuildOutput or a caller's Output,"
                        + " or name the file in MAY_INHERIT_TERMINAL with why it owns the terminal");
    }

    @Test
    void everyChildThatInheritsTheTerminalStillDoes() throws IOException {
        assertEquals("", unneeded(MAY_INHERIT_TERMINAL, INHERITS_TERMINAL),
                "drop these from MAY_INHERIT_TERMINAL: they no longer hand a child the terminal");
    }

    /** A path separator as the kernel reads it: doubled slashes and {@code /./} too. */
    private static final String SEPARATOR = "(?:/+\\.(?=/))*/+";

    /**
     * A path that opens the terminal past the process's streams, anywhere in a string literal or
     * text block, a shell command's included.
     */
    private static final Pattern TERMINAL_DEVICE = Pattern.compile(("/dev/(?:tty\\w*|stdout\\b|stderr\\b|console\\b|fd/[0-2]\\b|pts/\\d*)"
            + "|/proc/(?:self|thread-self|\\d+)(?:/task/\\d+)?/fd/[0-2]\\b").replace("/", SEPARATOR));

    /** The one terminal device a file may name, and why. */
    record DeviceUse(String device, String why) {}

    /** Who may name a terminal device, each only the one it names, with why. */
    private static final Map<String, DeviceUse> MAY_OPEN_TERMINAL_DEVICE = Map.of(
            "common/src/main/java/dev/incusspawn/incus/IncusApi.java",
            new DeviceUse("/dev/tty", "reads an interactive shell's keys"),
            "cli/src/main/java/dev/incusspawn/mcp/Presence.java",
            new DeviceUse("/dev/console", "a guest script that stats the guest's console"));

    @Test
    void onlyTheNamedFilesOpenATerminalDevice() throws IOException {
        var stray = new StringBuilder();
        for (var source : sources("src/main/java").entrySet()) {
            var use = MAY_OPEN_TERMINAL_DEVICE.get(source.getKey());
            var others = devices(source.getValue()).stream()
                    .filter(found -> use == null || !found.getValue().equals(use.device()))
                    .map(Map.Entry::getKey).toList();
            if (!others.isEmpty()) stray.append(source.getKey()).append(" at lines ").append(others).append('\n');
        }
        assertEquals("", stray.toString(), "write through BuildOutput or a caller's Output, not to a terminal device;"
                + " a file in MAY_OPEN_TERMINAL_DEVICE may name only the one device listed for it");
    }

    @Test
    void everyTerminalDeviceOpenerStillOpensOne() throws IOException {
        var unneeded = new StringBuilder();
        for (var listed : new TreeMap<>(MAY_OPEN_TERMINAL_DEVICE).entrySet()) {
            var file = repo().resolve(listed.getKey());
            if (!Files.exists(file) || devices(Files.readString(file)).stream().noneMatch(found -> found.getValue().equals(listed.getValue().device()))) {
                unneeded.append(listed.getKey()).append('\n');
            }
        }
        assertEquals("", unneeded.toString(), "drop these from MAY_OPEN_TERMINAL_DEVICE: they no longer name their device");
    }

    /**
     * Each terminal device {@code source} names in a string literal or text block: its one-based
     * line and the path, each separator made one slash.
     */
    private static List<Map.Entry<Integer, String>> devices(String source) {
        return matches(TERMINAL_DEVICE, blank(source, true),
                (line, match) -> Map.entry(line, match.group().replaceAll(SEPARATOR, "/")));
    }

    /**
     * The main sources not {@code allowed} that match {@code pattern}, one per line.
     */
    private static String strays(Pattern pattern, Predicate<String> allowed) throws IOException {
        var stray = new StringBuilder();
        for (var source : sources("src/main/java").entrySet()) {
            var path = source.getKey();
            if (allowed.test(path)) continue;
            var uses = lines(pattern, blank(source.getValue()));
            if (!uses.isEmpty()) stray.append(path).append(" at lines ").append(uses).append('\n');
        }
        return stray.toString();
    }

    @Test
    void findsChildrenThatInheritTheTerminalAndTerminalDevices() {
        var source = """
                class A {
                    // pb.inheritIO();
                    String s = "Redirect.INHERIT";
                    void f() {
                        new ProcessBuilder(c).inheritIO().start(); Optional.of(pb).map(ProcessBuilder::inheritIO);
                        pb.redirectError(ProcessBuilder.Redirect . INHERIT);
                        pb.redirectOutput(Redirect.PIPE);
                        var t = Path.of("/dev/tty"); var o = new FileOutputStream("/dev/stderr");
                        var p = Path.of("/proc/self/fd/1"); var q = Path.of("/dev/fd/10"); var d = Path.of("/dev/null");
                        var sh = new ProcessBuilder("sh", "-c", "echo hi > /dev/tty");
                        var script = \"""
                            echo hi > /dev/stderr; stat /dev/console
                            echo hi > /dev/\\
                            tty
                            \""";
                        var o = Path.of("/proc/thread-self/fd/2"); var t = Path.of("/dev//tty");
                        var u = Path.of("/dev/./ttyS0"); var v = Path.of("/proc/self/task/12/fd/1"); var w = Path.of("/dev/ttyx/../null");
                        // "/dev/tty" in a comment
                    }
                }
                import static java.lang.ProcessBuilder.Redirect.*;
                """;
        assertEquals(List.of(5, 5, 6, 21), lines(INHERITS_TERMINAL, blank(source)));
        assertEquals(List.of(Map.entry(8, "/dev/tty"), Map.entry(8, "/dev/stderr"), Map.entry(9, "/proc/self/fd/1"),
                Map.entry(10, "/dev/tty"), Map.entry(12, "/dev/stderr"), Map.entry(12, "/dev/console"),
                Map.entry(13, "/dev/tty"), Map.entry(16, "/proc/thread-self/fd/2"), Map.entry(16, "/dev/tty"),
                Map.entry(17, "/dev/ttyS0"), Map.entry(17, "/proc/self/task/12/fd/1"), Map.entry(17, "/dev/ttyx")), devices(source));
        assertEquals(blank(source).lines().count(), blank(source, true).lines().count());
    }

    @Test
    void findsTerminalStreamsCallsAndMethodReferences() {
        var source = """
                class A {
                    // TerminalStreams.out().println("comment");
                    String s = "TerminalStreams.err()";
                    var o = TerminalStreams.out();
                    Supplier<PrintStream> e = TerminalStreams::err;
                    Supplier<PrintStream> f = dev.incusspawn.util.TerminalStreams :: out;
                    var c = TerminalStreams . terminal();
                }
                import static dev.incusspawn.util.TerminalStreams.out;
                import dev.incusspawn.util.TerminalStreams;
                """;
        assertEquals(List.of(4, 5, 6, 7, 9), lines(TERMINAL_STREAMS, blank(source)));
    }

    @Test
    void everyExemptionIsStillNeeded() throws IOException {
        assertEquals("", unneeded(EXEMPT, RAW_STREAM),
                "drop these from EXEMPT: they write no raw output, and the gate should say so");
    }

    /** The paths in {@code listed} that are gone or no longer match {@code pattern}, one per line. */
    private static String unneeded(Map<String, String> listed, Pattern pattern) throws IOException {
        var repo = repo();
        var unneeded = new StringBuilder();
        for (var path : new TreeMap<>(listed).keySet()) {
            var file = repo.resolve(path);
            if (!Files.exists(file) || lines(pattern, blank(Files.readString(file))).isEmpty()) {
                unneeded.append(path).append('\n');
            }
        }
        return unneeded.toString();
    }

    static Path repo() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    /** What {@link #sources} read, by directory: every gate test reads the same tree. */
    private static final Map<String, Map<String, String>> SOURCES = new HashMap<>();

    /**
     * Every Java source under {@code dir} ({@code src/main/java}, {@code src/test/java}) of the
     * three modules, by repository path: the one walk every source gate makes, so a module added
     * is seen by all of them.
     */
    static synchronized Map<String, String> sources(String dir) throws IOException {
        var read = SOURCES.get(dir);
        if (read != null) return read;
        var repo = repo();
        var sources = new TreeMap<String, String>();
        for (var module : List.of("cli", "common", "proxy")) {
            try (Stream<Path> files = Files.walk(repo.resolve(module).resolve(dir))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    sources.put(repo.relativize(file).toString().replace('\\', '/'), Files.readString(file));
                }
            }
        }
        SOURCES.put(dir, Collections.unmodifiableMap(sources));
        return SOURCES.get(dir);
    }

    /** {@code code} is already {@link #blank blanked}. */
    private static Found find(String code) {
        return new Found(lines(RAW_STREAM, code), lines(INDENTED_PRINT, code));
    }

    @Test
    void findsUsesInCodeOnly() {
        var source = """
                class A {
                    // System.out.println("comment");
                    /* System.err */ String s = "System.out.println(x)";
                    String t = \"""
                        System.err.println("in a text block");
                        \""";
                    char q = '"'; char b = '\\''; String e = "\\" System.out \\\\";
                    void f(Exception e) {
                        System.out.println("  indented");
                        java.lang.System . err.print("x");
                        var c = System.console(); System.setErr(c); System . setOut (c); System.setIn(c);
                        e.printStackTrace();
                        e.printStackTrace(writer);
                        out.printf( " %s", s);
                        System.out.println("\\n  after a newline");
                        System.out.printf("%n\\tafter a tab%n");
                        System.out.println("no indent  "); Supplier<?> s = System::console;
                        BuildOutput.note("  not a print");
                    }
                }
                import static java.lang.System.out;
                import static java.lang.System.setErr;
                import static java.lang.System.setIn;
                """;
        assertEquals(new Found(List.of(9, 10, 11, 11, 11, 12, 15, 16, 17, 17, 21), List.of(9, 14, 15, 16)), find(blank(source)));
        assertEquals(List.of(21, 22), lines(STATIC_IMPORT, blank(source)));
    }

    @Test
    void blanksOnlyCommentsToSpacesAndKeepsALineCommentsSlashes() {
        var source = "a(\"\"\"\n  // x\n  \"\"\"); /* // y */ b(\"// z\"); // raw\n";
        assertEquals("a(___\n______\n_____);            b(\"____\"); //    \n", blank(source));
    }

    @Test
    void findsStreamsSpelledWithUnicodeEscapesLoggersAndReflection() {
        var source = """
                class A {
                    void f() {
                        System.\\u006fut.println("x"); System.\\uu0065rr.print("x");
                        // \\u000a System.err.println("ends the comment");
                        String s = "\\\\u006f"; char c = '\\u0007';
                        Thread.dumpStack(); System.getLogger("a"); var o = System.class.getField("out");
                        io.quarkus.logging.Log.warn("x"); java.util.logging.Logger.getLogger("a"); System.LoggerFinder.getLoggerFinder();
                    }
                }
                import org.jboss.logging.Logger;
                import org.slf4j.LoggerFactory;
                import org.apache.commons.logging.LogFactory;
                """;
        assertEquals(new Found(List.of(3, 3, 4, 6, 6, 6, 7, 7, 7, 10, 11, 12), List.of()), find(blank(source)));
    }

    /** One-based line numbers of each match. */
    static List<Integer> lines(Pattern pattern, String code) {
        return matches(pattern, code, (line, match) -> line);
    }

    /** What {@code each} makes of every match and its one-based line. */
    private static <T> List<T> matches(Pattern pattern, String code, BiFunction<Integer, MatchResult, T> each) {
        var found = new ArrayList<T>();
        var matcher = pattern.matcher(code);
        int line = 1;
        int counted = 0;
        while (matcher.find()) {
            for (; counted < matcher.start(); counted++) {
                if (code.charAt(counted) == '\n') line++;
            }
            found.add(each.apply(line, matcher.toMatchResult()));
        }
        return found;
    }

    /**
     * The source with every comment blanked to spaces but a line comment's opening {@code //},
     * every text block (its delimiters included) and character literal to underscores, and every
     * string literal's contents to underscores except its leading run of spaces, {@code \n},
     * {@code \t} and {@code %n}: enough to see an indentation, never enough to spell a stream, and
     * only a comment is left as spaces.
     * A string's quotes and every newline stay, so offsets still map to the same lines.
     */
    static String blank(String source) {
        return blank(source, false);
    }

    /** As {@link #blank(String)}, keeping string and character literals and text blocks whole when {@code keepLiterals}. */
    private static String blank(String source, boolean keepLiterals) {
        return blankDecoded(unicodeDecoded(source), keepLiterals);
    }

    /** As {@link #blank(String, boolean)}, for a source already {@link #unicodeDecoded decoded}: each character stays one. */
    static String blankDecoded(String source, boolean keepLiterals) {
        var out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            int end;
            if (source.startsWith("//", i)) {
                // Its opening slashes stay, so a reader can tell a line comment from a block comment
                out.append("//");
                i += 2;
                end = i;
                while (end < n && source.charAt(end) != '\n' && source.charAt(end) != '\r') end++;
            } else if (source.startsWith("/*", i)) {
                end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                end = source.indexOf("\"\"\"", i + 3);
                while (end > 0 && escaped(source, end)) end = source.indexOf("\"\"\"", end + 1);
                end = end < 0 ? n : end + 3;
                if (keepLiterals) {
                    // A line continuation joins the lines, as the compiler does once it strips the
                    // indentation, which this approximates as all of it; its newlines follow the block
                    int joined = 0;
                    for (; i < end; i++) {
                        if (source.charAt(i) == '\\' && i + 1 < end && source.charAt(i + 1) == '\n' && !escaped(source, i)) {
                            joined++;
                            i++;
                            while (i + 1 < end && (source.charAt(i + 1) == ' ' || source.charAt(i + 1) == '\t')) i++;
                        } else {
                            out.append(source.charAt(i));
                        }
                    }
                    out.append("\n".repeat(joined));
                    continue;
                }
                for (; i < end; i++) out.append(lineEnd(source.charAt(i)) ? source.charAt(i) : '_');
                continue;
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                boolean leading = c == '"';
                while (i < n && source.charAt(i) != c && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                    int next = source.charAt(i) == '\\' ? Math.min(i + 2, n) : i + 1;
                    leading &= LEADING_INDENT.contains(source.substring(i, next));
                    for (; i < next; i++) out.append(leading || keepLiterals ? source.charAt(i) : '_');
                }
                if (i < n) out.append(source.charAt(i++));
                continue;
            } else {
                out.append(c);
                i++;
                continue;
            }
            for (; i < end; i++) out.append(lineEnd(source.charAt(i)) ? source.charAt(i) : ' ');
        }
        return out.toString();
    }

    /**
     * The source with its Unicode escapes ({@code \\u0041}, {@code \\uu0041}) translated, as the
     * compiler does before it reads a token: a backslash starts one only after an even run of
     * backslashes. A translated escape is one character, so a line holding one is shorter, and a
     * line terminator it spells becomes a {@code \\r}, which ends a comment or literal as the
     * compiler's does while {@link #lines} still counts the source's own lines.
     */
    static String unicodeDecoded(String source) {
        if (!source.contains("\\u")) return source;
        var out = new StringBuilder(source.length());
        int n = source.length();
        for (int i = 0; i < n; i++) {
            char c = source.charAt(i);
            int u = i + 1;
            if (c == '\\' && u < n && source.charAt(u) == 'u' && !escaped(source, i)) {
                while (u < n && source.charAt(u) == 'u') u++;
                if (u + 4 <= n && hex(source, u)) {
                    char decoded = (char) Integer.parseInt(source, u, u + 4, 16);
                    out.append(decoded == '\n' ? '\r' : decoded);
                    i = u + 3;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** A newline, or the {@code \\r} a decoded line terminator becomes: blanking keeps both. */
    private static boolean lineEnd(char c) {
        return c == '\n' || c == '\r';
    }

    /** Whether the four characters at {@code at} are ASCII hex digits, the only ones the compiler takes. */
    private static boolean hex(String source, int at) {
        for (int k = at; k < at + 4; k++) {
            char h = source.charAt(k);
            if (h >= 128 || Character.digit(h, 16) < 0) return false;
        }
        return true;
    }

    private static boolean escaped(String source, int at) {
        int backslashes = 0;
        for (int k = at - 1; k >= 0 && source.charAt(k) == '\\'; k--) backslashes++;
        return backslashes % 2 == 1;
    }
}
