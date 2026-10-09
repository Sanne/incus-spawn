package dev.incusspawn.tool;

import io.quarkus.runtime.annotations.RegisterForReflection;
import dev.incusspawn.tool.ToolDef.ToolRef;
import io.hyperfoil.tools.jjq.mapper.ValueConverter;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 (incus-spawn-jjq-migration): {@code ValueConverter} for a YAML/JSON
 * array of tool references, mirroring {@link ToolRef#deserializeList} exactly
 * (same accepted shapes, same error strings). Each entry is either a plain
 * string ({@code - maven-3}) or a single-key map
 * ({@code - idea-backend: {memory: "8g"}}).
 *
 * <p>Wired via {@code @JqConverter} on the owning fields; the union shapes live
 * here, in one place, for both reads and writes.
 */
@RegisterForReflection
public class ToolRefListConverter implements ValueConverter<List<ToolRef>> {

    private final String fieldName;

    /** Default constructor for {@code @JqConverter} instantiation. */
    public ToolRefListConverter() {
        this("tools");
    }

    public ToolRefListConverter(String fieldName) {
        this.fieldName = fieldName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ToolRef> fromJqValue(JqValue value) {
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) {
            throw new JqMapperException(
                    "Expected array for " + fieldName + " field, got " + describe(value));
        }
        var result = new ArrayList<ToolRef>();
        for (var entry : value.asList()) {
            if (entry.isString()) {
                result.add(new ToolRef(entry.asText()));
            } else if (entry.isObject()) {
                result.add(parseSingleKeyMap(entry));
            } else {
                throw new JqMapperException("Unexpected entry in " + fieldName
                        + " array: expected a string or object, got " + describe(entry));
            }
        }
        return result;
    }

    private ToolRef parseSingleKeyMap(JqValue entry) {
        var map = entry.asMap();
        if (map.isEmpty()) {
            throw new JqMapperException("Empty object in " + fieldName
                    + " — use a plain string for tools without parameters");
        }
        if (map.size() != 1) {
            var name = map.keySet().iterator().next();
            throw new JqMapperException("Tool entry in " + fieldName
                    + " must be a single-key map like '- " + name + ": {params}', got multiple keys");
        }
        var name = map.keySet().iterator().next();
        var paramsValue = map.get(name);
        Map<String, String> params = new LinkedHashMap<>();
        if (paramsValue == null || paramsValue.isNull()) {
            // '- name:' with no params: same as the plain string form.
        } else if (paramsValue.isObject()) {
            for (var param : paramsValue.asMap().entrySet()) {
                if (!param.getValue().isScalar()) {
                    throw new JqMapperException("Parameter '" + param.getKey() + "' for tool '" + name
                            + "' in " + fieldName + " must be a scalar value (string, number, boolean)");
                }
                params.put(param.getKey(), param.getValue().asText());
            }
        } else {
            throw new JqMapperException("Parameters for '" + name + "' in " + fieldName
                    + " must be a map, e.g. '- " + name + ": {memory: \"8g\"}'");
        }
        return new ToolRef(name, params);
    }

    @Override
    public JqValue toJqValue(List<ToolRef> value) {
        if (value == null) return JqArray.arrayBuilder().build();
        var builder = JqArray.arrayBuilder();
        for (var ref : value) {
            if (ref.getParams().isEmpty()) {
                builder.add(ref.getName());
            } else {
                var params = JqObject.builder(ref.getParams().size());
                for (var e : ref.getParams().entrySet()) {
                    params.put(e.getKey(), JqString.of(e.getValue()));
                }
                builder.add(JqObject.ofEntries(ref.getName(), params.build()));
            }
        }
        return builder.build();
    }

    private static String describe(JqValue value) {
        if (value.isArray()) return "a list";
        if (value.isObject()) return "a mapping";
        if (value.isNull()) return "an empty value";
        return "a single value";
    }

    /** Variant reporting {@code requires} as the field name, for {@code ToolDef.requires}. */
    @RegisterForReflection
    public static class ForRequires extends ToolRefListConverter {
        public ForRequires() {
            super("requires");
        }
    }
}
