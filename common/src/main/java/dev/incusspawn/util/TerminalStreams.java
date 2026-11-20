package dev.incusspawn.util;

import java.io.Console;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * The process's own streams and console, for the code that hands the terminal over or draws on
 * it whole (#1703): an interactive guest command's inherited output ({@code IncusClient}), an
 * interactive shell's raw bytes ({@code IncusApi}) and its status bar ({@code InteractiveShell}),
 * the live renderer ({@code TerminalProgress}); a process whose stdin and stdout are another
 * program's protocol ({@code GitRemoteHelperCommand}, git's; {@code SshProxyCommand}, ssh's), or
 * a guest command's own ({@code ExecCommand}); and
 * the one check of whether a person is at a terminal ({@code Prompts}, and {@code TerminalOutput}
 * before a window title). A gate of
 * {@code RawOutputGateTest}, which also lets only those files call it: anything else reports
 * through {@link BuildOutput} or a caller's {@link Output}.
 */
public final class TerminalStreams {

    private TerminalStreams() {}

    /** The process's stdout, read when called: a step's guard or a test may have replaced it. */
    public static PrintStream out() {
        return System.out;
    }

    /** The process's stderr, read when called. */
    public static PrintStream err() {
        return System.err;
    }

    /** The process's stdin, for a process whose stdin is another program's protocol. */
    public static InputStream in() {
        return System.in;
    }

    /** Whether the JDK reports a console at all, as colour and animation ask. */
    public static boolean hasConsole() {
        return System.console() != null;
    }

    /**
     * The console, or null unless stdin and stdout are a terminal ({@link Console#isTerminal}). On
     * JDK 25 {@code System.console()} is already null when either is redirected; this guards
     * against a console that exists anyway (JDK 22 to 24, or {@code -Djdk.console=jdk.internal.le}).
     */
    public static Console terminal() {
        var console = System.console();
        return console != null && console.isTerminal() ? console : null;
    }
}
