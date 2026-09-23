package dev.incusspawn.tool;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;

/**
 * A tool that can be installed into a template image during build.
 * Implementations are discovered automatically via CDI.
 */
public interface ToolSetup {

    /** Short name for display during build (e.g. "podman", "gh", "claude"). */
    String name();

    /** Human-readable description shown in menus (e.g. "GitHub — PAT for git operations"). */
    default String description() { return ""; }

    /**
     * Always-true fact about this tool that an agent must know <em>before</em> acting,
     * rendered into {@code /etc/claude-code/CLAUDE.md} by the build. Null for most tools.
     * Reserve it for things that cause a wrong action if unknown; procedural how-to
     * content belongs in a skill, which loads on demand instead of every session.
     */
    default String agentNote() { return null; }

    /**
     * Skills this tool ships, installed when the tool is. Use these for the procedures
     * that drive the tool — they load on demand, unlike {@link #agentNote()}, which is
     * in context for every session. Bare skill names resolve against this definition's
     * own {@code skills.repo}, not the image's.
     */
    default ImageDef.SkillsDef skills() { return ImageDef.SkillsDef.EMPTY; }

    /** Proxy definition for credential injection by the MITM proxy. Null if this tool has no proxy config. */
    default ToolDef.ProxyDef proxy() { return null; }

    /**
     * Whether this tool's {@link #proxy()} configuration is its own credential, rather than
     * borrowing another tool's (e.g. {@code CopilotSetup} reuses the {@code gh} tool's PAT via
     * {@code github.token}). {@code isx init}'s credential menu uses this to avoid listing a
     * "configure X" entry that silently edits some other tool's secret.
     */
    default boolean hasOwnCredentials() { return true; }

    /** Feature flag that must be enabled for this tool to be available. Null means always available. */
    default String feature() { return null; }

    /** Packages this tool needs installed via dnf. Used to batch all installs into one call. */
    default java.util.List<String> packages() { return java.util.List.of(); }

    /** Package repositories this tool needs enabled before packages are installed. */
    default java.util.List<ImageDef.PackageRepo> packageRepos() { return java.util.List.of(); }

    /** Other tools that must be installed before this one. */
    default java.util.List<String> requires() { return java.util.List.of(); }

    /**
     * Parameter definitions for this tool. Returns an empty map by default.
     * Tools can override this to declare parameters with validation rules.
     */
    default java.util.Map<String, ToolDef.ParameterDef> parameters() {
        return java.util.Map.of();
    }

    /** Runtime actions this tool contributes to the TUI actions menu. */
    default java.util.List<ToolDef.ActionEntry> actions() { return java.util.List.of(); }

    /**
     * Environment variable declarations contributed by this tool.
     * Returned entries are collected by the build system, merged with template
     * env entries, validated for conflicts, and written to
     * {@code /etc/profile.d/isx-env.sh}.
     */
    default java.util.List<EnvEntry> envEntries(java.util.Map<String, String> resolvedParams) {
        return java.util.List.of();
    }

    /**
     * Install and configure this tool inside the given container. Packages are already installed.
     *
     * @param container the container to install into
     * @param resolvedParams parameter values (already validated and with defaults applied)
     */
    void install(Container container, java.util.Map<String, String> resolvedParams);

    /**
     * Apply only reconfigurable parameter changes without a full reinstall.
     * Called when a child template overrides reconfigurable parameters of a
     * tool already installed by an ancestor. Defaults to {@link #install}.
     */
    default void reconfigure(Container container, java.util.Map<String, String> resolvedParams) {
        install(container, resolvedParams);
    }
}
