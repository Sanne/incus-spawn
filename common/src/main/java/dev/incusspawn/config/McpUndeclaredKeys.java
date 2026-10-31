package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.mapper.JqIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * The keys of {@code mcp.yaml} isx does not know, only to refuse them, and the retired ones, only
 * to name them; never written back.
 *
 * <p>A superclass because the generated mapping counts every field the class itself declares as
 * a known key, {@code @JqIgnore} or not: declared in {@link McpConfig}, a key named
 * {@code unknown} or {@code retired} would be neither bound nor refused (#1889). An inherited
 * field is not counted.
 */
abstract class McpUndeclaredKeys {
    @JqIgnore
    final List<String> unknown = new ArrayList<>();
    @JqIgnore
    final List<String> retired = new ArrayList<>();
}
