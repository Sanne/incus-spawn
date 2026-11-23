package dev.incusspawn.command;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.TerminalStreams;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code claude setup-token} under {@code script(1)} and reads the token it prints, so the
 * person need not copy a wrapped token back into the terminal (#2543).
 *
 * <p>Claude Code draws a full-screen program and needs a terminal. {@code script} gives it a
 * pseudo-terminal with the keyboard's settings and window size, writes its log to
 * {@code /dev/null}, and passes the program's output through its own stdout, which this class
 * reads and copies to the terminal byte for byte. Nothing is written to disk: the token is held
 * only in this process's memory, in {@link Screen}, until the caller verifies it.
 *
 * <p>Claude Code 2.1.330 prints the token as one {@code <Text>} in a column, hard-wrapped at the
 * window's width, with a blank line after it, and places words with cursor moves rather than
 * spaces. Its renderer may also redraw only the cells that changed. So the output is read through
 * a small screen model rather than a pattern over the bytes, and each reading is only a candidate:
 * {@code verifyOauthToken} decides, and the paste stays the fallback.
 */
final class SetupTokenCapture {

    private SetupTokenCapture() {}

    /** How long the person has to sign in and approve, as the attached run allowed. */
    static final Duration TIMEOUT = Duration.ofMinutes(2);

    /** At most this many readings are verified, longest first. */
    static final int MAX_CANDIDATES = 3;

    /**
     * {@code script} running {@code claude setup-token} with its log in {@code /dev/null}, in the
     * syntax of the platform's {@code script}: BSD's on macOS, util-linux's elsewhere.
     */
    static List<String> command(boolean macOS) {
        return macOS
                ? List.of("script", "-q", "/dev/null", "claude", "setup-token")
                : List.of("script", "-q", "-e", "-f", "-c", "claude setup-token", "/dev/null");
    }

    /**
     * How a run ended: the exit code, or null when it timed out and was ended; the tokens read
     * from the screen, longest first, at most {@link #MAX_CANDIDATES}; and whether anything at all
     * reached the screen.
     */
    record Run(Integer exitCode, List<String> tokens, boolean drew) {
        /**
         * {@code script} failed before the command drew anything, as one that does not take
         * util-linux's options (BusyBox's has no {@code -e}) does: its usage goes to stderr.
         */
        boolean scriptFailed() {
            return exitCode != null && exitCode != 0 && !drew;
        }
    }

    /** Runs {@code command} on this process's terminal, its output relayed to the screen. */
    static Run run(List<String> command) throws IOException, InterruptedException {
        return run(command, ProcessBuilder.Redirect.INHERIT, TerminalStreams.out(), null, TIMEOUT);
    }

    /**
     * Runs {@code command} with {@code stdin}, copying its output to {@code screen} as it arrives
     * and reading it for a token. A run past {@code timeout} is ended, gently first, so
     * {@code script} restores the terminal's settings.
     */
    static Run run(List<String> command, ProcessBuilder.Redirect stdin, OutputStream screen, File directory,
                   Duration timeout) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(command);
        pb.redirectInput(stdin);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        if (directory != null) pb.directory(directory);
        // script sets the keyboard's terminal raw; BSD's may die on SIGTERM without restoring it
        var terminal = stdin == ProcessBuilder.Redirect.INHERIT ? sttySettings() : null;
        var process = pb.start();
        var model = new Screen();
        var relay = new Thread(() -> relay(process.getInputStream(), screen, model), "setup-token-relay");
        relay.setDaemon(true);
        relay.start();
        Integer exitCode = null;
        try {
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                exitCode = process.exitValue();
                // A script killed from outside (SIGTERM, SIGHUP) may not have restored the terminal
                if (exitCode != 0) restore(terminal);
            } else {
                end(process, terminal);
            }
        } catch (InterruptedException e) {
            // Never leave script holding the terminal raw while isx goes on to prompt
            end(process, terminal);
            process.getInputStream().close();
            relay.join(TimeUnit.SECONDS.toMillis(1));
            model.clear();
            throw e;
        }
        relay.join(TimeUnit.SECONDS.toMillis(5));
        if (relay.isAlive()) {
            process.getInputStream().close();
            relay.join(TimeUnit.SECONDS.toMillis(1));
        }
        var tokens = model.tokens();
        model.clear();
        return new Run(exitCode, tokens, model.drew());
    }

    private static void relay(InputStream from, OutputStream screen, Screen model) {
        var buffer = new byte[8192];
        try (from) {
            int n;
            while ((n = from.read(buffer)) != -1) {
                screen.write(buffer, 0, n);
                screen.flush();
                model.feed(buffer, 0, n);
            }
        } catch (IOException e) {
            // The process ended, or its output was closed after a timeout: what was read stays
        } finally {
            java.util.Arrays.fill(buffer, (byte) 0);
        }
    }

    /**
     * Ends the run: SIGTERM first, then force, descendants included. Then the keyboard's terminal
     * gets back the settings it had before ({@code terminal}, from {@code stty -g}), since a
     * {@code script} that died, or was killed, may have left it raw and without echo.
     */
    private static void end(Process process, String terminal) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
        restore(terminal);
    }

    /** Gives the keyboard's terminal back the settings {@code stty -g} read before the run, when it read them. */
    private static void restore(String terminal) throws InterruptedException {
        if (terminal == null) return;
        try {
            new ProcessBuilder("stty", terminal).redirectInput(ProcessBuilder.Redirect.INHERIT).start()
                    .waitFor(2, TimeUnit.SECONDS);
        } catch (IOException ignored) {
            // No stty: the person's terminal needs a reset by hand
        }
    }

    /** The keyboard's terminal settings as {@code stty -g} prints them, or null when they do not read. */
    private static String sttySettings() {
        try {
            var stty = new ProcessBuilder("stty", "-g").redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var settings = new String(stty.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).strip();
            if (!stty.waitFor(2, TimeUnit.SECONDS) || stty.exitValue() != 0) return null;
            // Only what stty -g writes: colon-separated hex and the like, never an option
            return !settings.isEmpty() && settings.chars().allMatch(c -> c == ':' || c == '=' || Character.isLetterOrDigit(c))
                    && !settings.startsWith("-") ? settings : null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * What the terminal shows, as far as a token needs: rows of cells, a cursor, and the moves and
     * erases a full-screen program sends. Colour and modes are dropped; widths are not known, so a
     * row never wraps by itself, and every code point past ASCII takes one cell.
     *
     * <p>Tokens are read from the rows before each erase and at the end, so one drawn and then
     * cleared is still found.
     */
    static final class Screen {
        private static final String PREFIX = SpawnConfig.ClaudeConfig.OAUTH_TOKEN_PREFIX;
        /** A reading shorter than this is not a token: real ones run to about 108 characters. */
        private static final int MIN_LENGTH = PREFIX.length() + 20;
        /** A token wraps over at most this many more rows (108 characters at 40 columns). */
        private static final int MAX_CONTINUATION_ROWS = 3;
        private static final int MAX_ROWS = 2000;
        /** Rows assumed visible, for absolute moves and how far up the cursor can go. */
        private static final int HEIGHT = 200;
        private static final int MAX_PARAMS = 64;
        private static final int MAX_STRING = 8192;

        private enum State { TEXT, ESC, ESC_INTERMEDIATE, CSI, STRING, STRING_ESC }

        private final List<StringBuilder> rows = new ArrayList<>();
        private final LinkedHashSet<String> found = new LinkedHashSet<>();
        private int row, col, savedRow, savedCol, bottom;
        private State state = State.TEXT;
        private final StringBuilder params = new StringBuilder();
        private int stringLength;
        private int utf8Remaining;

        /** Whether any byte arrived; {@link #clear()} keeps it. */
        private boolean drew;

        synchronized void feed(byte[] bytes, int offset, int length) {
            if (length > 0) drew = true;
            for (int i = offset; i < offset + length; i++) accept(bytes[i] & 0xff);
        }

        synchronized boolean drew() {
            return drew;
        }

        /** The tokens read, longest first, at most {@link #MAX_CANDIDATES}. */
        synchronized List<String> tokens() {
            harvest();
            return found.stream().sorted(Comparator.comparingInt(String::length).reversed())
                    .limit(MAX_CANDIDATES).toList();
        }

        /** Drops what was read, the token's cells included. */
        synchronized void clear() {
            rows.forEach(Screen::wipe);
            rows.clear();
            found.clear();
            params.setLength(0);
        }

        /** Overwrites a row's characters before dropping them, so the token's do not linger. */
        private static void wipe(StringBuilder row) {
            for (int i = 0; i < row.length(); i++) row.setCharAt(i, '\0');
            row.setLength(0);
        }

        private void accept(int b) {
            switch (state) {
                case TEXT -> text(b);
                case ESC -> escape(b);
                case ESC_INTERMEDIATE -> {
                    if (b < 0x20 || b > 0x2f) state = State.TEXT;
                }
                case CSI -> {
                    if (b >= 0x40 && b <= 0x7e) {
                        csi((char) b);
                        state = State.TEXT;
                    } else if (b >= 0x20 && b <= 0x3f) {
                        if (params.length() < MAX_PARAMS) params.append((char) b);
                    } else {
                        state = State.TEXT;
                    }
                }
                case STRING -> {
                    // OSC, DCS and the like end at BEL or ESC \; a runaway one is given up on
                    if (b == 0x07 || ++stringLength > MAX_STRING) state = State.TEXT;
                    else if (b == 0x1b) state = State.STRING_ESC;
                }
                case STRING_ESC -> state = b == '\\' ? State.TEXT : State.STRING;
            }
        }

        private void text(int b) {
            if (utf8Remaining > 0 && (b & 0xc0) == 0x80) {
                if (--utf8Remaining == 0) put('�');
                return;
            }
            utf8Remaining = 0;
            if (b == 0x1b) {
                state = State.ESC;
            } else if (b == '\r') {
                col = 0;
            } else if (b == '\n' || b == 0x0b || b == 0x0c) {
                moveTo(row + 1);
            } else if (b == '\b') {
                col = Math.max(0, col - 1);
            } else if (b == '\t') {
                col = (col / 8 + 1) * 8;
            } else if (b >= 0x20 && b < 0x7f) {
                put((char) b);
            } else if (b >= 0xc0 && b < 0xf8) {
                utf8Remaining = b >= 0xf0 ? 3 : b >= 0xe0 ? 2 : 1;
            } else if (b >= 0x80) {
                put('�');
            }
        }

        private void escape(int b) {
            state = State.TEXT;
            switch (b) {
                case '[' -> {
                    params.setLength(0);
                    state = State.CSI;
                }
                case ']', 'P', 'X', '^', '_' -> {
                    stringLength = 0;
                    state = State.STRING;
                }
                case '7' -> {
                    savedRow = row;
                    savedCol = col;
                }
                case '8' -> {
                    moveTo(savedRow);
                    col = savedCol;
                }
                case 'M' -> moveTo(Math.max(screenTop(), row - 1));
                case 'D' -> moveTo(row + 1);
                case 'E' -> {
                    moveTo(row + 1);
                    col = 0;
                }
                case 'c' -> {
                    harvest();
                    rows.clear();
                    row = col = bottom = 0;
                }
                default -> {
                    if (b >= 0x20 && b <= 0x2f) state = State.ESC_INTERMEDIATE;
                }
            }
        }

        private void csi(char command) {
            // A private sequence (?25h, >0q, =...) moves nothing
            if (!params.isEmpty() && "?<=>".indexOf(params.charAt(0)) >= 0) return;
            int n = param(0, 1);
            switch (command) {
                case 'A' -> moveTo(Math.max(screenTop(), row - Math.max(1, n)));
                case 'B', 'e' -> moveTo(row + Math.max(1, n));
                case 'C', 'a' -> col += Math.max(1, n);
                case 'D' -> col = Math.max(0, col - Math.max(1, n));
                case 'E' -> {
                    moveTo(row + Math.max(1, n));
                    col = 0;
                }
                case 'F' -> {
                    moveTo(Math.max(screenTop(), row - Math.max(1, n)));
                    col = 0;
                }
                case 'G', '`' -> col = Math.max(1, n) - 1;
                case 'd' -> moveTo(screenTop() + Math.max(1, n) - 1);
                case 'H', 'f' -> {
                    moveTo(screenTop() + Math.max(1, n) - 1);
                    col = Math.max(1, param(1, 1)) - 1;
                }
                case 'K' -> eraseInLine(param(0, 0));
                case 'J' -> eraseInDisplay(param(0, 0));
                case 's' -> {
                    savedRow = row;
                    savedCol = col;
                }
                case 'u' -> {
                    moveTo(savedRow);
                    col = savedCol;
                }
                default -> {
                    // colour, modes and queries leave the cells as they are
                }
            }
        }

        /** The {@code index}th parameter, {@code otherwise} when absent or unreadable. */
        private int param(int index, int otherwise) {
            int value = -1, at = 0;
            for (int i = 0; i < params.length(); i++) {
                char c = params.charAt(i);
                if (c == ';') {
                    if (at == index) break;
                    at++;
                    value = -1;
                } else if (at == index) {
                    if (c < '0' || c > '9') return otherwise;
                    value = Math.min((value < 0 ? 0 : value) * 10 + (c - '0'), 10_000);
                }
            }
            return at == index && value >= 0 ? value : otherwise;
        }

        private int screenTop() {
            return Math.max(0, bottom - HEIGHT + 1);
        }

        private void moveTo(int target) {
            row = Math.max(0, target);
            bottom = Math.max(bottom, row);
            if (row >= MAX_ROWS) {
                // Keep the newest rows: read the oldest before they go
                harvest();
                int drop = row - MAX_ROWS + 1;
                for (int i = 0; i < drop && !rows.isEmpty(); i++) rows.removeFirst();
                row -= drop;
                bottom -= drop;
                savedRow = Math.max(0, savedRow - drop);
            }
        }

        private StringBuilder current() {
            while (rows.size() <= row) rows.add(new StringBuilder());
            return rows.get(row);
        }

        private void put(char c) {
            var line = current();
            if (col > 4096) return;
            while (line.length() < col) line.append(' ');
            if (col < line.length()) line.setCharAt(col, c);
            else line.append(c);
            col++;
        }

        private void eraseInLine(int mode) {
            if (row >= rows.size()) return;
            var line = rows.get(row);
            // A renderer erases a frame bottom-up, so a wrapped token's last rows go before its first
            if (holdsTokenStart(row)) harvest();
            switch (mode) {
                case 0 -> {
                    if (col < line.length()) line.setLength(col);
                }
                case 1 -> {
                    for (int i = 0; i <= col && i < line.length(); i++) line.setCharAt(i, ' ');
                }
                default -> line.setLength(0);
            }
        }

        /** Whether row {@code i} or one of the rows a token wrapped from holds a token's start. */
        private boolean holdsTokenStart(int i) {
            for (int j = Math.max(0, i - MAX_CONTINUATION_ROWS); j <= i && j < rows.size(); j++) {
                if (rows.get(j).indexOf(PREFIX) >= 0) return true;
            }
            return false;
        }

        private void eraseInDisplay(int mode) {
            harvest();
            switch (mode) {
                case 0 -> {
                    eraseInLine(0);
                    for (int i = row + 1; i < rows.size(); i++) rows.get(i).setLength(0);
                }
                case 1 -> {
                    for (int i = screenTop(); i < row && i < rows.size(); i++) rows.get(i).setLength(0);
                    eraseInLine(1);
                }
                case 3 -> {
                    // Erases the scrollback alone: the rows on the screen stay
                }
                default -> {
                    for (int i = screenTop(); i < rows.size(); i++) rows.get(i).setLength(0);
                }
            }
        }

        /** Reads every token now on the rows into {@link #found}. */
        private void harvest() {
            for (int i = 0; i < rows.size(); i++) {
                var line = rows.get(i);
                for (int at = line.indexOf(PREFIX); at >= 0; at = line.indexOf(PREFIX, at + 1)) {
                    read(i, at);
                }
            }
        }

        /**
         * The token starting at {@code at} on row {@code i}, and, when it fills its row to the
         * end, that reading joined with each next row that holds token characters alone: the
         * rest of a wrapped token. Each reading is a candidate, so a wrong join still leaves the
         * shorter one to verify.
         */
        private void read(int i, int at) {
            var line = rows.get(i);
            int end = at;
            while (end < line.length() && isTokenChar(line.charAt(end))) end++;
            var token = new StringBuilder(line.substring(at, end));
            add(token);
            if (!line.substring(end).isBlank()) return;
            for (int j = i + 1; j < rows.size() && j <= i + MAX_CONTINUATION_ROWS; j++) {
                var next = rows.get(j).toString().strip();
                if (next.isEmpty() || !next.chars().allMatch(c -> isTokenChar((char) c))) return;
                token.append(next);
                add(token);
            }
        }

        private void add(CharSequence token) {
            if (token.length() >= MIN_LENGTH && found.size() < 16) found.add(token.toString());
        }

        private static boolean isTokenChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
        }
    }
}
