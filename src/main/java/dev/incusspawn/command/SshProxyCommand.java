package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(
        name = "ssh-proxy",
        description = "SSH ProxyCommand that tunnels through the Incus exec API",
        mixinStandardHelpOptions = true
)
public class SshProxyCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Instance name")
    String instance;

    @Inject
    IncusClient incus;

    @Override
    public Integer call() {
        if (!checkInstanceRunning()) {
            return 1;
        }

        return incus.execBidirectional(instance, 0, 0, "/",
                new String[]{"nc", "localhost", "22"},
                System.in, System.out, System.err);
    }

    private boolean checkInstanceRunning() {
        try {
            var status = incus.getInstanceStatus(instance);
            if (status.isEmpty()) {
                System.err.println("Error: instance '" + instance + "' not found or not accessible.");
                return false;
            }
            if (!"Running".equalsIgnoreCase(status)) {
                System.err.println("Error: instance '" + instance + "' is not running (status: " + status + ").");
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("Error: could not check instance status: " + e.getMessage());
            return false;
        }
    }
}
