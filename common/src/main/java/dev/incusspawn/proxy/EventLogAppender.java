package dev.incusspawn.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Appends a line to the event log from a process other than the proxy: {@code isx mcp}, for the
 * runs of its tasks (#2428). The proxy's health server only reads, so a line cannot be sent to it
 * to write, and the line goes into the proxy's own file instead, rolled with the calls it covers.
 *
 * <p>The file is opened for append, so each line lands at its end whatever the proxy wrote
 * meanwhile, and the line goes out as one write: a local file system does not interleave it with
 * the proxy's. The proxy's rollover renames the file: a line written meanwhile lands in the old
 * file or the new one, and the reader reads both. Owner-only, as the proxy makes them.
 */
public final class EventLogAppender {

    private EventLogAppender() {}

    /** Whether the line was written. Never throws: the log loses the line, never the work it records. */
    public static boolean append(Path file, String line) {
        var bytes = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
        try {
            var dir = file.getParent();
            if (!Files.isDirectory(dir)) Files.createDirectories(dir,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            try (var channel = FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                while (bytes.hasRemaining()) channel.write(bytes);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
