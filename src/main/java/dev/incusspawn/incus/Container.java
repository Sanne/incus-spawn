package dev.incusspawn.incus;

/**
 * A handle to a specific Incus container, providing a convenient API
 * for running commands and managing files inside it.
 */
public class Container {

    private final IncusClient incus;
    private final String name;

    public Container(IncusClient incus, String name) {
        this.incus = incus;
        this.name = name;
    }

    public String name() {
        return name;
    }

    /** Run a command as root. Returns the result for inspection. */
    public IncusClient.ExecResult exec(String... command) {
        return incus.shellExec(name, command);
    }

    /** Run a shell snippet (sh -c) as root. */
    public IncusClient.ExecResult sh(String script) {
        return incus.shellExec(name, "sh", "-c", script);
    }

    /** Run a command as root with inherited IO (visible output). Fails on non-zero exit. */
    public void runInteractive(String failureMessage, String... command) {
        int exitCode = incus.shellExecInteractive(name, command);
        if (exitCode != 0) {
            throw new IncusException(failureMessage + " (exit code " + exitCode + ")");
        }
    }

    /** Run a shell snippet as a specific user with a login shell. Fails on non-zero exit. */
    public void runAsUser(String user, String script, String failureMessage) {
        int exitCode = incus.shellExecInteractive(name, "su", "-l", user, "-c", script);
        if (exitCode != 0) {
            throw new IncusException(failureMessage + " (exit code " + exitCode + ")");
        }
    }

    /** Install packages via dnf. */
    public void dnfInstall(String failureMessage, String... packages) {
        var command = new String[packages.length + 3];
        command[0] = "dnf";
        command[1] = "install";
        command[2] = "-y";
        System.arraycopy(packages, 0, command, 3, packages.length);
        runInteractive(failureMessage, command);
    }

    /** Write content to a file inside the container. */
    public void writeFile(String path, String content) {
        // Use a heredoc with a unique delimiter to avoid escaping issues
        sh("cat > " + path + " << 'INCUS_EOF'\n" + content.strip() + "\nINCUS_EOF");
    }

    /** Set ownership recursively. */
    public void chown(String path, String owner) {
        exec("chown", "-R", owner, path);
    }

    /** Push a directory recursively into the container. */
    public void filePushRecursive(String sourceDir, String destPath) {
        incus.filePushRecursive(sourceDir, name, destPath);
    }

    /** Append a line to agentuser's .bashrc. */
    public void appendToProfile(String line) {
        sh("echo '" + line + "' >> /home/agentuser/.bashrc");
    }
}
