package dev.incusspawn.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.mapper.ValueConverter;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Phase 1 (incus-spawn-jjq-migration): {@code ValueConverter} for a list of
 * structured env entries, mirroring {@code EnvEntry.ListDeserializer} exactly
 * (same accepted shape, same error strings). A plain shell string such as
 * {@code export FOO=bar} is rejected with the structured equivalent.
 */
@RegisterForReflection
public class EnvEntryListConverter implements ValueConverter<List<EnvEntry>> {

    private static final String NAME_REGEX = "[a-zA-Z_][a-zA-Z0-9_]*";
    static final Pattern VALID_NAME = Pattern.compile(NAME_REGEX);
    private static final Pattern EXPORT_LINE =
            Pattern.compile("\\s*(?:export\\s+)?(" + NAME_REGEX + ")=(.*)");

    private static final Map<String, EnvEntry.Strategy> STRATEGY_MAP = Map.of(
            "set", EnvEntry.Strategy.SET,
            "set-if-unset", EnvEntry.Strategy.SET_IF_UNSET,
            "prepend", EnvEntry.Strategy.PREPEND,
            "append", EnvEntry.Strategy.APPEND);

    @Override
    @SuppressWarnings("unchecked")
    public List<EnvEntry> fromJqValue(JqValue value) {
        if (value == null || value.isNull()) return List.of();
        if (!value.isArray()) {
            throw new JqMapperException("Expected array for env field");
        }
        var result = new ArrayList<EnvEntry>();
        for (var entry : value.asList()) {
            if (entry.isString()) {
                throw new JqMapperException(shellStringError(entry.asText()));
            }
            if (!entry.isObject()) {
                throw new JqMapperException("Unexpected token in env array");
            }
            result.add(parseStructuredEntry(entry));
        }
        return result;
    }

    private static EnvEntry parseStructuredEntry(JqValue entry) {
        String name = null;
        String entryValue = null;
        EnvEntry.Strategy strategy = EnvEntry.Strategy.SET;
        String separator = " ";
        for (var field : entry.asMap().entrySet()) {
            switch (field.getKey()) {
                case "name" -> name = field.getValue().asText();
                case "value" -> entryValue = field.getValue().asText();
                case "strategy" -> {
                    var strategyStr = field.getValue().asText();
                    var resolved = STRATEGY_MAP.get(strategyStr);
                    if (resolved == null) {
                        throw new JqMapperException("Unknown env strategy '" + strategyStr
                                + "'; expected one of: set, set-if-unset, prepend, append");
                    }
                    strategy = resolved;
                }
                case "separator" -> separator = field.getValue().asText();
                default -> { /* ignoreUnknown, like Jackson */ }
            }
        }
        if (name == null || name.isBlank()) {
            throw new JqMapperException("Structured env entry requires a 'name' field");
        }
        if (!VALID_NAME.matcher(name).matches()) {
            throw new JqMapperException("Invalid env var name '" + name + "'; must match " + NAME_REGEX);
        }
        if (entryValue == null) {
            throw new JqMapperException("Structured env entry '" + name + "' requires a 'value' field");
        }
        if (entryValue.indexOf('\n') >= 0 || entryValue.indexOf('\r') >= 0) {
            throw new JqMapperException("Env var '" + name
                    + "' value must not contain newline or carriage return characters");
        }
        var result = switch (strategy) {
            case SET -> EnvEntry.set(name, entryValue);
            case SET_IF_UNSET -> EnvEntry.setIfUnset(name, entryValue);
            case PREPEND -> EnvEntry.prepend(name, entryValue, separator);
            case APPEND -> EnvEntry.append(name, entryValue, separator);
        };
        return result;
    }

    static String shellStringError(String line) {
        var msg = new StringBuilder("Env entry '").append(line)
                .append("' is a shell string, which is not supported; use a structured entry");
        var m = EXPORT_LINE.matcher(line);
        if (m.matches()) {
            msg.append(":\n  - name: ").append(m.group(1))
                    .append("\n    value: ").append(m.group(2));
        } else {
            msg.append(" with 'name' and 'value'");
        }
        return msg.toString();
    }

    @Override
    public JqValue toJqValue(List<EnvEntry> value) {
        if (value == null) return JqArray.arrayBuilder().build();
        var builder = JqArray.arrayBuilder();
        for (var entry : value) {
            var obj = JqObject.builder(4);
            obj.put("name", entry.getName());
            obj.put("value", entry.getValue());
            obj.put("strategy", switch (entry.getStrategy()) {
                case SET -> "set";
                case SET_IF_UNSET -> "set-if-unset";
                case PREPEND -> "prepend";
                case APPEND -> "append";
            });
            obj.put("separator", entry.getSeparator());
            builder.add(obj.build());
        }
        return builder.build();
    }
}
