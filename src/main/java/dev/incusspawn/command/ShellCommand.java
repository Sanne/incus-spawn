package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(
        name = "shell",
        description = "Open a shell in an existing clone",
        mixinStandardHelpOptions = true
)
public class ShellCommand implements Runnable {

    @Parameters(index = "0", description = "Name of the clone to connect to")
    String name;

    @Inject
    IncusClient incus;

    @Override
    public void run() {
        if (!incus.exists(name)) {
            System.err.println("Error: no instance named '" + name + "' found.");
            System.err.println("Run 'incus-spawn list' to see available environments.");
            return;
        }

        // Start if stopped
        var info = incus.exec("list", name, "--format=csv", "--columns=s");
        if (info.success() && info.stdout().strip().equalsIgnoreCase("STOPPED")) {
            System.out.println("Starting " + name + "...");
            incus.start(name);
            waitForReady(name);
        }

        System.out.println("Connecting to " + name + "...\n");
        incus.interactiveShell(name, "agentuser");
    }

    private void waitForReady(String container) {
        for (int i = 0; i < 30; i++) {
            var result = incus.shellExec(container, "true");
            if (result.success()) return;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }
}
