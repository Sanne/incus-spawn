package dev.incusspawn.config;

import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.mapper.JqAnySetter;
import io.hyperfoil.tools.jjq.mapper.JqIgnore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A template or tool definition, or an entry in one, as mapped from YAML. It keeps each key isx
 * does not read, so loading can warn about it rather than drop it without a word (#1543, #1546):
 * a misspelled key changes what a build does as surely as a wrong value, since the build goes
 * ahead without what it asked for. The keys are never written back, so a stored definition holds
 * only what isx read.
 * <p>
 * A warning, not a refusal: every definition that loaded before still loads.
 */
public abstract class MappedDefinition {

    @JqIgnore
    private List<String> unknownKeys = List.of();

    @JqAnySetter
    public void setUnknown(String key, Object value) {
        addUnknownKey(key);
    }

    /** For a converter that reads the tree itself and builds the entry. */
    public void addUnknownKey(String key) {
        if (unknownKeys.isEmpty()) unknownKeys = new ArrayList<>();
        unknownKeys.add(key);
    }

    /** The keys this part's YAML held that isx ignored, in file order. */
    public List<String> unknownKeys() {
        return unknownKeys;
    }

    /**
     * The keys this part takes, in declaration order: those its mapping writes. Only asked for when
     * the part has an unknown key, so the serialization costs nothing on a clean load.
     */
    protected Collection<String> knownKeys() {
        return Jjq.mapper().toJqValue(this).asMap().keySet();
    }

    /** What to say about {@code key} beyond the close known key {@link Walk} looks for, or null. */
    protected String hint(String key) {
        return null;
    }

    /**
     * Walks a definition's parts and collects one warning per ignored key, naming where it is:
     * {@code host-resource} at the top level, {@code repos[0].branch-name} in an entry.
     */
    public static final class Walk {
        private final List<String> warnings = new ArrayList<>();

        /** The ignored keys of {@code part}, found at {@code where} ("" for the top level). */
        public Walk part(String where, MappedDefinition part) {
            if (part == null || part.unknownKeys().isEmpty()) return this;
            var known = part.knownKeys();
            for (var key : part.unknownKeys()) {
                var warning = "unknown key '" + OutputFormat.oneLine(where.isEmpty() ? key : where + "." + key)
                        + "', ignored";
                var hint = part.hint(key);
                var meant = hint == null ? closest(key, known) : null;
                if (hint != null) {
                    warning += "; " + hint;
                } else if (meant != null) {
                    warning += didYouMean(meant);
                } else if (!where.isEmpty()) {
                    warning += " (" + OutputFormat.oneLine(where) + " takes " + OutputFormat.andList(List.copyOf(known)) + ")";
                }
                warnings.add(warning);
            }
            return this;
        }

        /** Each entry of a list, as {@code where[i]}. */
        public Walk each(String where, List<? extends MappedDefinition> parts) {
            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i) != null && !parts.get(i).unknownKeys().isEmpty()) part(where + "[" + i + "]", parts.get(i));
            }
            return this;
        }

        /** Each value of a map, as {@code where.key}. */
        public Walk values(String where, Map<String, ? extends MappedDefinition> parts) {
            parts.forEach((key, part) -> part(where + "." + key, part));
            return this;
        }

        public Walk add(String warning) {
            warnings.add(warning);
            return this;
        }

        public List<String> warnings() {
            return warnings;
        }
    }

    /**
     * {@code unknown key 'key'} for a section read by hand, whose message names the place before
     * it: with the known key it most likely meant ({@code ; did you mean 'x'?}) when there is one,
     * else followed by {@code otherwise} (#2463).
     */
    static String unknownKey(Object key, Collection<String> known, String otherwise) {
        var typed = String.valueOf(key);
        // Sorted, so a tie between two keys of a Set.of names the same one on every run
        var meant = closest(typed, known.stream().sorted().toList());
        return "unknown key '" + OutputFormat.oneLine(typed) + "'"
                + (meant != null ? didYouMean(meant) : otherwise);
    }

    /** {@code ; did you mean 'meant'?}, as every unknown-key message words it. */
    static String didYouMean(String meant) {
        return "; did you mean '" + meant + "'?";
    }

    /**
     * The known key {@code key} most likely meant, or null: the same key in another case or with
     * {@code _} for {@code -} (both are in use), one or two typed letters off, or a word of it
     * ({@code repo} for {@code repos}, {@code branch-name} for {@code branch}).
     */
    static String closest(String key, Collection<String> known) {
        return closest(key, known, true);
    }

    /**
     * As {@link #closest(String, Collection)}, with {@code words} false for an open level, where a
     * key that is a known one plus a word ({@code github-enterprise}) or a short key is as likely
     * another tool's own: only a key of five letters or more, one or two letters off, is matched.
     */
    static String closest(String key, Collection<String> known, boolean words) {
        var typed = normalized(key);
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (var candidate : known) {
            var name = normalized(candidate);
            int score = distance(typed, name);
            int shorter = Math.min(typed.length(), name.length());
            if (score > (shorter >= 5 ? 2 : shorter >= 3 && words ? 1 : 0)) {
                score = words && shorter >= 3 && (aWordOf(typed, name) || aWordOf(name, typed)) ? 3 : Integer.MAX_VALUE;
            }
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    protected static String normalized(String key) {
        return key.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Whether {@code longer} is {@code shorter} with a plural {@code s} or more words after it. */
    private static boolean aWordOf(String shorter, String longer) {
        if (!longer.startsWith(shorter) || longer.length() == shorter.length()) return false;
        var rest = longer.substring(shorter.length());
        return rest.equals("s") || rest.startsWith("-");
    }

    /** Edits from {@code a} to {@code b}, a swap of two adjacent letters counting as one. */
    private static int distance(String a, String b) {
        var d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
