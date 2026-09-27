"""Shared helpers for the bench/ scripts that drive the isx CLI."""
import fcntl, os, re, select, signal, struct, subprocess, termios, time


class NoPrompt(Exception):
    """The command never reached a usable shell prompt."""


# Time to a usable prompt, which is what a user actually waits for. The command runs on a
# pseudo-terminal (isx reads /dev/tty and puts it in raw mode, so it needs a controlling one),
# and this line is typed at once. It sits in the terminal's input queue until the shell inside
# the instance reads it, so the marker prints exactly when the shell is ready. The typed text
# is echoed back as "$((6*7))", and only a shell that ran it prints 42 -- so the echo of the
# input cannot be mistaken for the output.
PROMPT_INPUT = b"echo ISXBENCH_$((6*7))_READY; exit\r"
PROMPT_MARKER = b"ISXBENCH_42_READY"
# Terminal escapes (CSI, OSC, two-byte), stripped only to make a failure's output readable.
ANSI = re.compile(rb"\x1b(\[[0-9;?]*[ -/]*[@-~]|\][^\x07\x1b]*(\x07|\x1b\\)|[@-Z\\-_])")

def run_to_prompt(cmd, timeout_s):
    master, slave = os.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
    child_env = dict(os.environ, TERM="xterm-256color")
    # Inside tmux, isx renames the window and the instance may attach tmux itself; neither
    # belongs in a measurement, and the user's own tmux must not be touched.
    child_env.pop("TMUX", None)
    start = time.perf_counter_ns()
    proc = subprocess.Popen(cmd, stdin=slave, stdout=slave, stderr=slave, env=child_env,
                            start_new_session=True,
                            preexec_fn=lambda: fcntl.ioctl(0, termios.TIOCSCTTY, 0))
    os.close(slave)
    os.write(master, PROMPT_INPUT)
    output, elapsed_ms = b"", None
    deadline = time.monotonic() + timeout_s
    try:
        while elapsed_ms is None and time.monotonic() < deadline:
            ready, _, _ = select.select([master], [], [], 0.1)
            if not ready:
                if proc.poll() is not None:
                    break
                continue
            try:
                chunk = os.read(master, 65536)
            except OSError:  # EIO: the child closed the terminal
                break
            output += chunk
            if PROMPT_MARKER in output:
                elapsed_ms = (time.perf_counter_ns() - start) / 1e6
        if elapsed_ms is not None:
            # The typed `exit` ends the shell; keep draining so isx can restore the terminal.
            end = time.monotonic() + 30
            while proc.poll() is None and time.monotonic() < end:
                if select.select([master], [], [], 0.1)[0]:
                    try:
                        os.read(master, 65536)
                    except OSError:
                        break
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGKILL)
        proc.wait()
        os.close(master)
    if elapsed_ms is None:
        tail = ANSI.sub(b"", output).decode("utf-8", "replace").strip()[-2000:]
        why = (f"no shell prompt within {timeout_s}s" if proc.returncode == -signal.SIGKILL
               else f"exited {proc.returncode} without a shell prompt")
        raise NoPrompt(f"{' '.join(cmd)}: {why}. Last output:\n{tail}")
    return elapsed_ms
