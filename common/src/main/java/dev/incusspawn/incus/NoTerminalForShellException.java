package dev.incusspawn.incus;

/**
 * An interactive shell was asked for with no terminal to run it in (stdin or stdout redirected,
 * or no controlling terminal). Never a lost connection: retrying cannot help (#1027).
 */
public class NoTerminalForShellException extends IncusException {
    public NoTerminalForShellException(String instance) {
        super("no terminal: a shell on " + instance + " needs stdin and stdout to be a terminal.");
    }
}
