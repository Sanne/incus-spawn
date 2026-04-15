package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;

/**
 * A tool that can be installed into a template image during build.
 * Implementations are discovered automatically via CDI.
 */
public interface ToolSetup {

    /** Short name for display during build (e.g. "podman", "gh", "claude"). */
    String name();

    /** Packages this tool needs installed via dnf. Used to batch all installs into one call. */
    default java.util.List<String> packages() { return java.util.List.of(); }

    /** Install and configure this tool inside the given container. Packages are already installed. */
    void install(Container container);
}
