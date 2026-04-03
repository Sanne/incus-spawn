package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;

/**
 * A tool that can be installed into a golden image during build.
 * Implementations are discovered automatically via CDI.
 */
public interface ToolSetup {

    /** Short name for display during build (e.g. "podman", "gh", "claude"). */
    String name();

    /** Install and configure this tool inside the given container. */
    void install(Container container);
}
