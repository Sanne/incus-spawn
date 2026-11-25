package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.mapper.JqIgnore;

/**
 * Why the config.yaml a {@link SpawnConfig} stands for could not be read, or null when it was.
 * {@link SpawnConfig#load} reads such a file as empty, and {@link SpawnConfig#save} refuses to
 * write that empty configuration over it, which would drop every account the file holds (#2523).
 *
 * <p>A superclass for the reason {@link UndeclaredKeys} is one: the generated mapping counts every
 * field the class itself declares as a known key, so a field declared in {@link SpawnConfig} would
 * make a key of that name in the file neither bound nor kept.
 */
abstract class UnreadableFile extends UndeclaredKeys {
    @JqIgnore
    String unreadable;

    /** Why config.yaml could not be read, or null when it was: then this configuration is an empty stand-in. */
    public String unreadableReason() {
        return unreadable;
    }
}
