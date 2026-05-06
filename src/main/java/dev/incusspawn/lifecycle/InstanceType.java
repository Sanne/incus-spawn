package dev.incusspawn.lifecycle;

/**
 * Distinguishes between different types of instances being created,
 * which affects which lifecycle operations are performed.
 */
public enum InstanceType {
    /**
     * A running instance (branch/clone) that will be used interactively.
     * Gets full setup including SSH keys, git remotes, etc.
     */
    INSTANCE,

    /**
     * A stopped template (base or project) used as a source for branching.
     * Gets build-time setup but not runtime integration (no SSH, no remotes).
     */
    TEMPLATE
}
