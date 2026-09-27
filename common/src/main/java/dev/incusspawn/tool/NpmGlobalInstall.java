package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;

/**
 * Installs an npm-distributed CLI globally and proves it runs.
 * <p>
 * Some CLIs ({@code @openai/codex}, {@code @github/copilot}) publish a thin JS launcher whose
 * real binary lives in a per-platform <em>optional</em> dependency. npm treats a failed optional
 * dependency as skippable, so a transient download failure leaves {@code npm install -g} exiting
 * 0 with only the launcher installed -- and the template would be stamped as built and CoW-copied
 * into every branch (#808). Running the binary as agentuser, through the same login PATH an
 * instance uses, turns that into a failed build step instead.
 */
final class NpmGlobalInstall {

    private NpmGlobalInstall() {
    }

    static void install(Container c, String displayName, String npmPackage, String binary) {
        c.runQuiet("Failed to install " + displayName,
                "npm", "install", "-g", "--ignore-scripts", "--loglevel=error", npmPackage);
        c.runAsUserQuiet("agentuser", binary + " --version",
                displayName + " was installed but '" + binary + " --version' fails; npm may have"
                        + " skipped its platform-specific package after a failed download");
    }
}
