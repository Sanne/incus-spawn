package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;

/**
 * Adapts a {@link ToolDef} (parsed from YAML) into a {@link ToolSetup}
 * that can be executed by the build system.
 */
public class YamlToolSetup implements ToolSetup {

    private final ToolDef def;

    public YamlToolSetup(ToolDef def) {
        this.def = def;
    }

    @Override
    public String name() {
        return def.getName();
    }

    @Override
    public java.util.List<String> packages() {
        return def.getPackages();
    }

    @Override
    public void install(Container container) {
        var label = def.getDescription().isEmpty() ? def.getName() : def.getDescription();
        System.out.println("Installing " + label + "...");

        // Packages are installed in bulk by BuildCommand before tool.install() is called.

        // 2. Shell commands as root
        for (var script : def.getRun()) {
            container.runInteractive("Failed to run setup for " + def.getName(),
                    "sh", "-c", script);
        }

        // 3. Shell commands as agentuser
        for (var script : def.getRunAsUser()) {
            container.runAsUser("agentuser", script,
                    "Failed to run user setup for " + def.getName());
        }

        // 4. Files
        for (var file : def.getFiles()) {
            container.writeFile(file.getPath(), file.getContent());
            if (file.getOwner() != null && !file.getOwner().isEmpty()) {
                container.chown(file.getPath(), file.getOwner());
            }
        }

        // 5. Environment variables
        for (var line : def.getEnv()) {
            container.appendToProfile(line);
        }

        // 6. Verification
        if (def.getVerify() != null && !def.getVerify().isBlank()) {
            var result = container.exec(def.getVerify().split("\\s+"));
            if (result.success()) {
                System.out.println("  " + result.stdout().lines().findFirst().orElse(""));
            } else {
                System.err.println("  Warning: verification failed for " + def.getName());
            }
        }
    }
}
