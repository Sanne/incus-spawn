package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A declarative environment variable entry. Supports four strategies:
 * <ul>
 *   <li>{@code set} — unconditional assignment (default)</li>
 *   <li>{@code set-if-unset} — only assign if the variable is not already defined</li>
 *   <li>{@code prepend} — prepend to the existing value (with a separator)</li>
 *   <li>{@code append} — append to the existing value (with a separator)</li>
 * </ul>
 *
 * Also supports a "raw" mode for backward compatibility: a plain string like
 * {@code export FOO=bar} is written verbatim with no conflict detection.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnvEntry {

    public enum Strategy {
        @JsonProperty("set") SET,
        @JsonProperty("set-if-unset") SET_IF_UNSET,
        @JsonProperty("prepend") PREPEND,
        @JsonProperty("append") APPEND
    }

    private String name;
    private String value;
    private Strategy strategy = Strategy.SET;
    private String separator = " ";
    private String raw;

    public EnvEntry() {}

    private EnvEntry(String name, String value, Strategy strategy, String separator) {
        this.name = name;
        this.value = value;
        this.strategy = strategy;
        this.separator = separator;
    }

    private EnvEntry(String raw) {
        this.raw = raw;
    }

    public static EnvEntry set(String name, String value) {
        return new EnvEntry(name, value, Strategy.SET, " ");
    }

    public static EnvEntry setIfUnset(String name, String value) {
        return new EnvEntry(name, value, Strategy.SET_IF_UNSET, " ");
    }

    public static EnvEntry prepend(String name, String value, String separator) {
        return new EnvEntry(name, value, Strategy.PREPEND, separator);
    }

    public static EnvEntry append(String name, String value, String separator) {
        return new EnvEntry(name, value, Strategy.APPEND, separator);
    }

    public static EnvEntry raw(String line) {
        return new EnvEntry(line);
    }

    public boolean isRaw() {
        return raw != null;
    }

    public boolean isStructured() {
        return raw == null;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Strategy getStrategy() { return strategy; }
    public void setStrategy(Strategy strategy) { this.strategy = strategy; }
    public String getSeparator() { return separator; }
    public void setSeparator(String separator) { this.separator = separator; }
    public String getRaw() { return raw; }
    public void setRaw(String raw) { this.raw = raw; }

    /**
     * Return a copy of this entry with parameter substitution applied.
     */
    public EnvEntry withSubstitution(java.util.function.UnaryOperator<String> substitutor) {
        if (isRaw()) {
            return EnvEntry.raw(substitutor.apply(raw));
        }
        var copy = new EnvEntry(name, substitutor.apply(value), strategy, separator);
        return copy;
    }

    public String fingerprintString() {
        if (isRaw()) {
            return "raw=" + raw;
        }
        return "env=" + name + "," + value + "," + strategy + "," + separator;
    }

    /**
     * Deserializes a YAML/JSON list of env entries. Each element can be:
     * <ul>
     *   <li>A plain string: backward-compatible raw shell line</li>
     *   <li>A map with name/value/strategy/separator fields: structured entry</li>
     * </ul>
     */
    public static class ListDeserializer extends StdDeserializer<List<EnvEntry>> {
        public ListDeserializer() { super(List.class); }

        @Override
        public List<EnvEntry> deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            return deserializeList(p);
        }

        static List<EnvEntry> deserializeList(JsonParser p) throws IOException {
            var result = new ArrayList<EnvEntry>();
            if (p.currentToken() != JsonToken.START_ARRAY) {
                throw new IOException("Expected array for env field");
            }

            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (p.currentToken() == JsonToken.VALUE_STRING) {
                    result.add(EnvEntry.raw(p.getText()));
                } else if (p.currentToken() == JsonToken.START_OBJECT) {
                    result.add(parseStructuredEntry(p));
                } else {
                    throw new IOException("Unexpected token in env array: " + p.currentToken());
                }
            }
            return result;
        }

        private static final Map<String, Strategy> STRATEGY_MAP = Map.of(
                "set", Strategy.SET,
                "set-if-unset", Strategy.SET_IF_UNSET,
                "prepend", Strategy.PREPEND,
                "append", Strategy.APPEND
        );

        private static EnvEntry parseStructuredEntry(JsonParser p) throws IOException {
            var entry = new EnvEntry();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                var field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "name" -> entry.setName(p.getText());
                    case "value" -> entry.setValue(p.getText());
                    case "strategy" -> {
                        var strategyStr = p.getText();
                        var strategy = STRATEGY_MAP.get(strategyStr);
                        if (strategy == null) {
                            throw new IOException("Unknown env strategy '" + strategyStr
                                    + "'; expected one of: set, set-if-unset, prepend, append");
                        }
                        entry.setStrategy(strategy);
                    }
                    case "separator" -> entry.setSeparator(p.getText());
                    default -> p.skipChildren();
                }
            }
            if (entry.getName() == null || entry.getName().isBlank()) {
                throw new IOException("Structured env entry requires a 'name' field");
            }
            if (entry.getValue() == null) {
                throw new IOException("Structured env entry '" + entry.getName() + "' requires a 'value' field");
            }
            return entry;
        }
    }
}
