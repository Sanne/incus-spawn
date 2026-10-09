package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqName;
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
@JqMapped
public class EnvEntry {

    public enum Strategy {
        @JqName("set") SET,
        @JqName("set-if-unset") SET_IF_UNSET,
        @JqName("prepend") PREPEND,
        @JqName("append") APPEND
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

    /** Whether {@code name} is a variable name an env entry may set. */
    public static boolean isValidName(String name) {
        return name != null && EnvEntryListConverter.VALID_NAME.matcher(name).matches();
    }

    public String fingerprintString() {
        return "env=" + name + "," + value + "," + strategy + "," + separator
                + (expandAtLogin ? ",expand" : "");
    }

}
