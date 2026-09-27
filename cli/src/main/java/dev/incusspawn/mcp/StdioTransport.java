package dev.incusspawn.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** The stdio transport: one JSON-RPC message per line, UTF-8, in both directions. */
final class StdioTransport implements McpTransport {

    private final BufferedReader in;
    private final OutputStream out;

    StdioTransport(InputStream in, OutputStream out) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
    }

    @Override
    public String read() throws IOException {
        String line;
        do {
            line = in.readLine();
        } while (line != null && line.isBlank());
        return line;
    }

    @Override
    public void send(String json) throws IOException {
        // Messages must not contain embedded newlines; Jackson's compact output never does.
        var bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
    }
}
