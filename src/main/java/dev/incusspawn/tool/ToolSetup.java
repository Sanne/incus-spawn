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

    /** Other tools that must be installed before this one. */
    default java.util.List<String> requires() { return java.util.List.of(); }

    /**
     * Parameter definitions for this tool. Returns an empty map by default.
     * Tools can override this to declare parameters with validation rules.
     */
    default java.util.Map<String, ToolDef.ParameterDef> parameters() {
        return java.util.Map.of();
    }

    /**
     * Install and configure this tool inside the given container. Packages are already installed.
     *
     * @param container the container to install into
     * @param resolvedParams parameter values (already validated and with defaults applied)
     */
    void install(Container container, java.util.Map<String, String> resolvedParams);
}
