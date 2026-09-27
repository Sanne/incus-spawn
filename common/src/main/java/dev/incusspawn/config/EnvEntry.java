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
import java.util.regex.Pattern;

/**
 * A declarative environment variable entry. Supports four strategies:
 * <ul>
 *   <li>{@code set} — unconditional assignment (default)</li>
 *   <li>{@code set-if-unset} — only assign if the variable is not already defined</li>
 *   <li>{@code prepend} — prepend to the existing value (with a separator)</li>
 *   <li>{@code append} — append to the existing value (with a separator)</li>
 * </ul>
 *
 * Values are written literally: {@code $} and friends are escaped. Built-in Java code
 * that needs a value the shell fills in at login ({@code $HOME}, {@code $HOSTNAME}) marks
 * the entry {@link #expandingAtLogin()}; it keeps its name and strategy, so it is still
 * subject to conflict detection. YAML definitions cannot set that flag.
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
    private boolean expandAtLogin;

    public EnvEntry() {}

    private EnvEntry(String name, String value, Strategy strategy, String separator) {
        this(name, value, strategy, separator, false);
    }

    private EnvEntry(String name, String value, Strategy strategy, String separator,
                     boolean expandAtLogin) {
        this.name = name;
        this.value = value;
        this.strategy = strategy;
        this.separator = separator;
        this.expandAtLogin = expandAtLogin;
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

    /**
     * A copy of this entry whose value the shell expands at login ({@code $VAR},
     * {@code ${VAR}}) rather than taking literally. Code-only: there is deliberately no
     * setter, so YAML definitions cannot reach it.
     */
    public EnvEntry expandingAtLogin() {
        return new EnvEntry(name, value, strategy, separator, true);
    }

    public boolean expandsAtLogin() { return expandAtLogin; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Strategy getStrategy() { return strategy; }
    public void setStrategy(Strategy strategy) { this.strategy = strategy; }
    public String getSeparator() { return separator; }
    public void setSeparator(String separator) { this.separator = separator; }

    /**
     * Return a copy of this entry with parameter substitution applied.
     */
    public EnvEntry withSubstitution(java.util.function.UnaryOperator<String> substitutor) {
        return new EnvEntry(name, substitutor.apply(value), strategy, separator, expandAtLogin);
    }

    public String fingerprintString() {
        return "env=" + name + "," + value + "," + strategy + "," + separator
                + (expandAtLogin ? ",expand" : "");
    }

    /**
     * Deserializes a YAML/JSON list of structured env entries (maps with
     * name/value/strategy/separator fields). A plain string such as
     * {@code export FOO=bar} is rejected, naming the structured equivalent.
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
                    throw new IOException(shellStringError(p.getText()));
                } else if (p.currentToken() == JsonToken.START_OBJECT) {
                    result.add(parseStructuredEntry(p));
                } else {
                    throw new IOException("Unexpected token in env array: " + p.currentToken());
                }
            }
            return result;
        }

        private static final String NAME_REGEX = "[a-zA-Z_][a-zA-Z0-9_]*";
        private static final Pattern VALID_NAME = Pattern.compile(NAME_REGEX);
        private static final Pattern EXPORT_LINE =
                Pattern.compile("\\s*(?:export\\s+)?(" + NAME_REGEX + ")=(.*)");

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
            if (!VALID_NAME.matcher(entry.getName()).matches()) {
                throw new IOException("Invalid env var name '" + entry.getName()
                        + "'; must match " + NAME_REGEX);
            }
            if (entry.getValue() == null) {
                throw new IOException("Structured env entry '" + entry.getName() + "' requires a 'value' field");
            }
            if (entry.getValue().indexOf('\n') >= 0 || entry.getValue().indexOf('\r') >= 0) {
                throw new IOException("Env var '" + entry.getName()
                        + "' value must not contain newline or carriage return characters");
            }
            return entry;
        }
    }
}
