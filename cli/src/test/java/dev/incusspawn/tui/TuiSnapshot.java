package dev.incusspawn.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;

/**
 * Headless rendering for TUI tests: draws into an in-memory {@link Buffer} instead of a terminal,
 * and compares the result against golden text files.
 *
 * <p>Golden files live in {@code src/test/resources/tui-snapshots/<name>.txt}. Every run also writes
 * the actual rendering to {@code target/tui-snapshots/} as {@code .txt} (plain) and {@code .ansi}
 * (with colours; view with {@code cat} or {@code less -R}). To accept a changed rendering, run the
 * tests with {@code -Dtui.snapshots.update=true} and review the diff of the golden files.
 */
public final class TuiSnapshot {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/tui-snapshots");
    private static final Path OUTPUT_DIR = Path.of("target/tui-snapshots");

    private TuiSnapshot() {}

    /** Renders into a fresh {@code width}×{@code height} buffer. */
    public static Buffer render(int width, int height, Consumer<Frame> renderer) {
        var buffer = Buffer.empty(new Rect(0, 0, width, height));
        renderer.accept(Frame.forTesting(buffer));
        return buffer;
    }

    /** The buffer as plain text, one line per row, trailing spaces trimmed. */
    public static String toText(Buffer buffer) {
        var sb = new StringBuilder();
        var area = buffer.area();
        for (int y = area.y(); y < area.bottom(); y++) {
            var row = new StringBuilder();
            for (int x = area.x(); x < area.right(); x++) {
                var cell = buffer.get(x, y);
                if (!cell.isContinuation()) row.append(cell.symbol());
            }
            sb.append(row.toString().stripTrailing()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Asserts the buffer's plain-text rendering matches the golden file {@code name}.txt, writing
     * the actual rendering to {@code target/tui-snapshots/} either way.
     */
    public static void assertMatches(String name, Buffer buffer) {
        var actual = toText(buffer);
        try {
            Files.createDirectories(OUTPUT_DIR);
            Files.writeString(OUTPUT_DIR.resolve(name + ".txt"), actual);
            Files.writeString(OUTPUT_DIR.resolve(name + ".ansi"), buffer.toAnsiString());

            var golden = GOLDEN_DIR.resolve(name + ".txt");
            if (Boolean.getBoolean("tui.snapshots.update")) {
                Files.createDirectories(GOLDEN_DIR);
                Files.writeString(golden, actual);
                return;
            }
            if (!Files.exists(golden)) {
                fail("No golden snapshot " + golden + "; review target/tui-snapshots/" + name
                        + ".txt and rerun with -Dtui.snapshots.update=true to create it");
            }
            assertEquals(Files.readString(golden), actual,
                    "Rendering of '" + name + "' changed; see target/tui-snapshots/" + name
                            + ".txt, and rerun with -Dtui.snapshots.update=true to accept it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
