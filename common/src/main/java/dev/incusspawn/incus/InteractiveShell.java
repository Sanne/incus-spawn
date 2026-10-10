package dev.incusspawn.incus;

import dev.incusspawn.incus.IncusClient.ShellPrep;
import dev.incusspawn.tool.ShellMenu;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The interactive shell into an instance: the PTY session and its reconnects, the host terminal's
 * title, tmux window and terminfo. Split out of {@link IncusClient}, whose
 * {@code interactiveShell} methods open it.
 */
final class InteractiveShell {

    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    private final IncusClient incus;

    InteractiveShell(IncusClient incus) {
        this.incus = incus;
    }

    private static final java.util.regex.Pattern SIMPLE_COMMAND =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_./ -]+$");

    static String zmxCommand(String shellCommand) {
        if (SIMPLE_COMMAND.matcher(shellCommand).matches()) {
            return shellCommand;
        }
        var escaped = shellCommand.replace("'", "'\\''");
        return "bash -c '" + escaped + "'";
    }

    public void interactiveShell(String container, String user, ShellPrep prep, ShellMenu menu) {
        if (!IncusClient.hasTerminal()) throw new NoTerminalForShellException(container);
        System.out.print("\033]0;isx:" + container + "\007"); // raw ANSI: window title, behind hasTerminal()
        System.out.flush();

        String savedWindowName = null;
        String savedStatusRight = null;
        boolean inTmux = System.getenv("TMUX") != null;
        if (inTmux) {
            savedWindowName = hostExecCapture("tmux", "display-message", "-p", "#W");
            hostExecQuiet("tmux", "rename-window", "isx:" + container);
            if (prep.subnetDiagnostic() != null) {
                savedStatusRight = hostExecCapture("tmux", "show-option", "-v", "status-right");
                if (savedStatusRight == null) savedStatusRight = "";
                hostExecQuiet("tmux", "set-option", "status-right",
                        "#[bg=yellow,fg=black,bold] ⚠ Bridge subnet conflict — run 'isx init' #[default]");
            }
        }

        if (!prep.terminfoHandled()) {
            propagateTerminfo(container);
        }

        try {
            var homeDir = "/home/" + user;
            var targetCwd = prep.workdir() != null ? prep.workdir() : homeDir;

            String innerScript;
            if (prep.shellCommand() != null && prep.autoAttachZmx()) {
                var zmxCmd = zmxCommand(prep.shellCommand());
                innerScript =
                        "if command -v zmx >/dev/null 2>&1; then "
                        + "exec zmx attach isx " + zmxCmd
                        + "; fi; " + prep.shellCommand() + " || exec bash --login";
            } else if (prep.shellCommand() != null) {
                innerScript = prep.shellCommand() + " || exec bash --login";
            } else if (prep.autoAttachTmux() && !inTmux) {
                innerScript =
                        "if command -v tmux >/dev/null 2>&1; then "
                        + "infocmp \"$TERM\" >/dev/null 2>&1 || export TERM=xterm-256color; "
                        + "exec tmux new-session -A -s isx; fi; exec bash --login";
            } else if (prep.autoAttachZmx() && !inTmux) {
                innerScript =
                        "if command -v zmx >/dev/null 2>&1; then "
                        + "exec zmx attach isx bash --login; fi; exec bash --login";
            } else {
                innerScript = "exec bash --login";
            }

            // Wrap in su -l so the session goes through PAM, which calls
            // initgroups() (activating supplementary groups like incus-admin)
            // and starts the systemd user instance (needed by rootless podman).
            // -P (--pty) makes su allocate its own PTY with a proper setsid +
            // TIOCSCTTY, giving the inner bash a controlling terminal — without
            // it, tcgetpgrp() returns -1 and bash prints "cannot set terminal
            // process group / no job control in this shell".
            // su -l does cd $HOME, so we prepend cd to restore targetCwd.
            var script = "cd " + Container.shellQuote(targetCwd) + " && "
                    + IncusClient.LOGIN_PATH_PREFIX + innerScript;
            var shellArgs = List.of("su", "-l", "-P", user, "-c", script);

            var env = Map.of("HOME", homeDir);

            ShellStatusBar statusBar = null;
            if (ShellMenu.enabled()) {
                statusBar = new ShellStatusBar(container, System.out);
                statusBar.setMenuActions(menu.actions(), menu.context());
            }

            for (int reconnectAttempt = 0; ; reconnectAttempt++) {
                try {
                    var size = IncusApi.terminalSize();
                    boolean connectionLost = incus.http().execPty(container, shellArgs, 0, 0,
                            null, env, size[0], size[1], statusBar);
                    if (!connectionLost) return;
                } catch (IncusException e) {
                    if (!hasIOExceptionCause(e) || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) throw e;
                }
                long delay = Math.min(1000L * (1 << reconnectAttempt), 10_000L);
                System.err.println("\n" + dev.incusspawn.util.BuildOutput.styled(
                        dev.incusspawn.util.BuildOutput.BOLD + dev.incusspawn.util.BuildOutput.YELLOW,
                        "Connection lost — reconnecting..."));
                try { Thread.sleep(delay); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            if (inTmux && savedWindowName != null) {
                hostExecQuiet("tmux", "rename-window", savedWindowName);
            }
            if (savedStatusRight != null) {
                if (savedStatusRight.isEmpty()) {
                    hostExecQuiet("tmux", "set-option", "-u", "status-right");
                } else {
                    hostExecQuiet("tmux", "set-option", "status-right", savedStatusRight);
                }
            }
            System.out.print("\033]0;\007"); // raw ANSI: window title, behind hasTerminal()
            System.out.flush();
        }
    }

    private static boolean hasIOExceptionCause(Throwable t) {
        while (t != null) {
            if (t instanceof java.io.IOException) return true;
            t = t.getCause();
        }
        return false;
    }

    private static String hostExecCapture(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            return process.exitValue() == 0 ? output : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void hostExecQuiet(String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.start().waitFor();
        } catch (IOException e) {
            // best-effort
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void propagateTerminfo(String container) {
        String term = System.getenv("TERM");
        if (term == null || term.isEmpty()) return;
        var check = incus.shellExec(container, "infocmp", term);
        if (check.exitCode() == 0) return;
        String terminfo = hostExecCapture("infocmp", "-x", term);
        if (terminfo == null) return;
        incus.shellExec(container, "sh", "-c",
                "cat <<'TERMINFO_EOF' | tic -x -\n" + terminfo + "\nTERMINFO_EOF");
    }
}
