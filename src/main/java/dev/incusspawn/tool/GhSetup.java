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
        // Auth is handled transparently by the host MITM proxy — no container-side
        // configuration needed. The proxy intercepts TLS to github.com and injects
        // the Authorization header server-side. Credentials never enter containers.
    }
}
