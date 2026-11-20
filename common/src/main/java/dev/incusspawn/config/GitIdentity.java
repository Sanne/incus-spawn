package dev.incusspawn.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code git-identity:} in config.yaml (#2410): the name and email an agent commits under when no
 * GitHub account gives it one.
 *
 * <pre>
 * git-identity:
 *   name: Jane Doe
 *   email: jane@example.org
 * </pre>
 *
 * <p>Committing under a person's own name is acceptable only as a choice they made and saw, so
 * isx writes this key only after an explicit yes ({@code isx init}'s question, or its
 * {@code --git-identity-from-host} flag); a person may also write it by hand. Kept as the raw YAML
 * value, like {@code drivers:}: a mistyped section must not fail the whole config.yaml. One that
 * is wrong is ignored with a warning, and an ignored one sets no identity.
 *
 * @param name  the {@code user.name} commits get
 * @param email the {@code user.email} commits get
 */
public record GitIdentity(String name, String email) {

    /** The key in config.yaml. */
    public static final String KEY = "git-identity";

    /**
     * The identity the section names, or null when there is none or it is wrong (said through
     * {@code warn}).
     */
    static GitIdentity of(Object raw, Consumer<String> warn) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> map)) {
            warn.accept("Ignoring " + KEY + ": in config.yaml (expected name: and email: under it)");
            return null;
        }
        for (var key : map.keySet()) {
            if (!"name".equals(key) && !"email".equals(key)) {
                warn.accept("Ignoring " + KEY + ": in config.yaml: unknown key '" + key + "' (expected name: and email:)");
                return null;
            }
        }
        var name = map.get("name") instanceof String s ? s.strip() : "";
        var email = map.get("email") instanceof String s ? s.strip() : "";
        var problem = problem(name, email);
        if (problem != null) {
            warn.accept("Ignoring " + KEY + ": in config.yaml: " + problem);
            return null;
        }
        return new GitIdentity(name, email);
    }

    /**
     * Why {@code name} and {@code email} cannot be an identity, or null when they can: both set,
     * on one line each, and an email Git accepts between its angle brackets.
     */
    public static String problem(String name, String email) {
        if (name == null || name.isBlank()) return "name: is missing";
        if (email == null || email.isBlank()) return "email: is missing";
        if (name.chars().anyMatch(Character::isISOControl)) return "name: holds a control character";
        if (email.chars().anyMatch(Character::isISOControl)) return "email: holds a control character";
        if (email.contains("<") || email.contains(">") || name.contains("<") || name.contains(">")) {
            return "name: and email: may not hold '<' or '>'";
        }
        return null;
    }

    /** The section as config.yaml holds it. */
    Map<String, Object> toYaml() {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", name);
        map.put("email", email);
        return map;
    }

    /** As Git shows an author: {@code Jane Doe <jane@example.org>}. */
    @Override
    public String toString() {
        return name + " <" + email + ">";
    }
}
