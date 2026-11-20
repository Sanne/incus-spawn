package dev.incusspawn.tool;

import dev.incusspawn.config.Jjq;
import dev.incusspawn.tool.ToolDef.MemoryAmount;
import dev.incusspawn.tool.ToolDef.Resources;
import dev.incusspawn.util.MemorySize;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.mapper.ValueConverter;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;

/**
 * Reads a tool's {@code resources:} (#2355):
 * <pre>
 * resources:
 *   memory:
 *     resident: 768MiB                          # a size
 *     peak: { parameter: memory, plus: 512MiB } # or a parameter's size plus one
 * </pre>
 * A key isx does not read is kept for the unknown-key warning; a size it cannot read refuses the
 * definition, since a figure that is silently zero gives no guidance at all.
 */
public class ResourcesConverter implements ValueConverter<Resources> {

    @Override
    public Resources fromJqValue(JqValue value) {
        if (value == null || value.isNull()) return Resources.NONE;
        if (!value.isObject()) throw new JqMapperException("resources: expects a mapping with 'memory'");
        MemoryAmount resident = null;
        MemoryAmount peak = null;
        var unknown = new ArrayList<String>();
        for (var field : value.asMap().entrySet()) {
            if (!"memory".equals(field.getKey())) {
                unknown.add(field.getKey());
                continue;
            }
            var memory = field.getValue();
            if (memory.isNull()) continue;
            if (!memory.isObject()) {
                throw new JqMapperException("resources.memory: expects a mapping with 'resident' and 'peak'");
            }
            for (var entry : memory.asMap().entrySet()) {
                switch (entry.getKey()) {
                    case "resident" -> resident = amount("resources.memory.resident", entry.getValue());
                    case "peak" -> peak = amount("resources.memory.peak", entry.getValue());
                    default -> unknown.add("memory." + entry.getKey());
                }
            }
        }
        var resources = new Resources(resident, peak);
        unknown.forEach(resources::addUnknownKey);
        return resources;
    }

    private static MemoryAmount amount(String where, JqValue value) {
        if (value.isNull()) return null;
        if (value.isObject()) {
            String parameter = null;
            long plus = 0;
            for (var field : value.asMap().entrySet()) {
                switch (field.getKey()) {
                    case "parameter" -> parameter = field.getValue().isNull() ? null : Jjq.text(field.getValue()).strip();
                    case "plus" -> plus = size(where + ".plus", field.getValue());
                    default -> throw new JqMapperException(where + ": takes 'parameter' and 'plus', not '" + field.getKey() + "'");
                }
            }
            if (parameter == null || parameter.isEmpty()) {
                throw new JqMapperException(where + ": a mapping names the tool's 'parameter' that holds a size");
            }
            return new MemoryAmount(plus, parameter);
        }
        return new MemoryAmount(size(where, value), null);
    }

    private static long size(String where, JqValue value) {
        var text = value.isNull() ? "" : Jjq.text(value);
        var bytes = MemorySize.parse(text);
        if (bytes < 0) {
            throw new JqMapperException(where + ": expects a size such as 768MiB or 2GiB, but got '"
                    + (text.length() > 40 ? text.substring(0, 40) + "..." : text) + "'");
        }
        return bytes;
    }

    @Override
    public JqValue toJqValue(Resources value) {
        // Nothing for a tool that declares nothing, so a stored definition (BUILD_SOURCE) does not grow by it
        if (value == null || !value.declaresMemory()) return JqNull.NULL;
        var memory = JqObject.builder(2);
        if (value.resident() != null) memory.put("resident", shown(value.resident()));
        if (value.peak() != null) memory.put("peak", shown(value.peak()));
        var obj = JqObject.builder(1);
        obj.put("memory", memory.build());
        return obj.build();
    }

    private static JqValue shown(MemoryAmount amount) {
        if (amount.parameter() == null) return JqString.of(String.valueOf(amount.bytes()));
        var obj = JqObject.builder(2);
        obj.put("parameter", amount.parameter());
        obj.put("plus", String.valueOf(amount.bytes()));
        return obj.build();
    }
}
