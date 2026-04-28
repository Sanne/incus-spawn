package dev.incusspawn.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Utility for deep-merging JSON trees.
 */
public final class JsonMergeUtil {

    private JsonMergeUtil() {}

    /**
     * Deep merge two JSON trees. Overlay values take precedence over base values.
     * <p>
     * Merge rules:
     * <ul>
     *   <li>Objects: recursively merge keys from both, overlay wins on conflicts</li>
     *   <li>Arrays: overlay completely replaces base (REPLACE strategy)</li>
     *   <li>Primitives: overlay value wins</li>
     *   <li>Null overlay: return base unchanged</li>
     *   <li>Null base: return overlay unchanged</li>
     * </ul>
     *
     * @param base the base JSON tree (lower precedence)
     * @param overlay the overlay JSON tree (higher precedence)
     * @return the merged JSON tree
     */
    public static JsonNode deepMerge(JsonNode base, JsonNode overlay) {
        // Null or missing handling
        if (overlay == null || overlay.isNull() || overlay.isMissingNode()) {
            return base;
        }
        if (base == null || base.isNull() || base.isMissingNode()) {
            return overlay;
        }

        // Both are objects - merge recursively
        if (base.isObject() && overlay.isObject()) {
            ObjectNode result = ((ObjectNode) base).deepCopy();
            ObjectNode overlayObj = (ObjectNode) overlay;

            // Iterate through overlay fields
            overlayObj.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                JsonNode overlayValue = entry.getValue();

                if (result.has(fieldName)) {
                    // Field exists in both - recursively merge if both are objects
                    JsonNode baseValue = result.get(fieldName);
                    if (baseValue.isObject() && overlayValue.isObject()) {
                        result.set(fieldName, deepMerge(baseValue, overlayValue));
                    } else {
                        // Not both objects - overlay wins
                        result.set(fieldName, overlayValue.deepCopy());
                    }
                } else {
                    // Field only in overlay - add it
                    result.set(fieldName, overlayValue.deepCopy());
                }
            });

            return result;
        }

        // Arrays - REPLACE strategy (overlay replaces base entirely)
        // TODO: Future enhancement - support append, union, or by-key merge strategies
        if (overlay.isArray()) {
            return overlay.deepCopy();
        }

        // Primitives (strings, numbers, booleans, null) - overlay wins
        return overlay.deepCopy();
    }
}
