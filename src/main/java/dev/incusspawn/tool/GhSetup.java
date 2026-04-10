package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import jakarta.enterprise.context.Dependent;

@Dependent
public class GhSetup implements ToolSetup {

    @Override
    public String name() {
        return "gh";
    }

    @Override
    public void install(Container c) {
        System.out.println("Installing GitHub CLI...");
        c.dnfInstall("Failed to install GitHub CLI", "gh");
        configureAuth(c);
    }

    /**
     * Set GH_TOKEN so the CLI believes it's authenticated and proceeds to make
     * network requests. The placeholder is not a real credential — the MITM proxy
     * replaces the Authorization header with the real token before it reaches GitHub.
     * <p>
     * We use GH_TOKEN instead of hosts.yml because newer gh versions store tokens
     * in the system keyring (via D-Bus), which doesn't exist in containers. Any
     * hosts.yml triggers a migration that fails without D-Bus.
     */
    private void configureAuth(Container c) {
        c.appendToProfile("export GH_TOKEN=gho_placeholder");
    }
}
