package dev.incusspawn.mcp;

import dev.incusspawn.util.Headless;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Hands the real stdin and stdout to the protocol, and everything else to stderr.
 *
 * <p>Much of isx prints progress to {@code System.out} ({@code BuildOutput}, lifecycle steps,
 * proxy warnings). Under {@code isx mcp} stdout is the protocol channel, so one stray line would
 * corrupt it. Rather than teach every helper about MCP, {@code System.out} becomes stderr -- which
 * the MCP client logs -- and {@code System.in} becomes empty, so nothing but the transport can
 * consume the client's messages. {@link Headless} stops animations and prompts.
 */
final class StdioGuard {

    final InputStream protocolIn;
    final OutputStream protocolOut;

    private StdioGuard(InputStream in, OutputStream out) {
        this.protocolIn = in;
        this.protocolOut = out;
    }

    static StdioGuard install() {
        var guard = new StdioGuard(new FileInputStream(FileDescriptor.in),
                new FileOutputStream(FileDescriptor.out));
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
        System.setIn(InputStream.nullInputStream());
        Headless.enable();
        return guard;
    }
}
