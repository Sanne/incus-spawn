package dev.incusspawn.mcp;

import dev.incusspawn.Environment;
import dev.incusspawn.config.SecretRedactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * What agents did through {@code isx mcp}, one line per tool call, in
 * {@code ~/.local/state/incus-spawn/mcp.log}. Commands and instructions are scrubbed of
 * anything shaped like a credential before they are written.
 */
final class McpAuditLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final int MAX_DETAIL = 500;

    private McpAuditLog() {}

    static void record(SessionId session, String tool, String instance, String detail,
                       long millis, String outcome) {
        var text = detail == null ? "" : SecretRedactor.scrubText(detail, Map.of()).text().replace('\n', ' ');
        if (text.length() > MAX_DETAIL) text = text.substring(0, MAX_DETAIL) + "...";
        var line = LocalDateTime.now().format(FMT) + " session=" + session + " tool=" + tool
                + (instance != null ? " instance=" + instance : "")
                + " outcome=" + outcome + " ms=" + millis
                + (text.isEmpty() ? "" : " detail=" + text);
        try {
            // Resolved per call: a path held in a static would be baked into the native image.
            var path = Environment.mcpLogFile();
            Files.createDirectories(path.getParent());
            Files.writeString(path, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("isx mcp: could not write the audit log: " + e.getMessage());
        }
    }
}
