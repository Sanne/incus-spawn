package dev.incusspawn.incus;

import java.util.regex.Pattern;

/**
 * Which terminal-multiplexer session a shell enters (#2059): the instance's default {@code isx}
 * session, one the user names, a new one whose name the guest picks, or none at all. Two
 * terminals mirror each other exactly when they enter the same session, so naming it is what
 * lets a second terminal be independent.
 *
 * <p>A name is held to {@link #NAME}: it goes into the guest's script unquoted, and the host
 * links it as {@code isx-<instance>.<name>}, which a dot or a slash would make ambiguous.</p>
 */
public record ShellSession(Kind kind, String name) {

    public enum Kind { DEFAULT, NAMED, NEW, NONE }

    /**
     * What a session name may be: no dot (the host link's separator), nothing that needs quoting,
     * and no leading {@code -}, which zmx and tmux would read as an option.
     */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]{0,31}");

    /** The instance's own session, as every shell entered it before #2059. */
    public static final ShellSession DEFAULT = new ShellSession(Kind.DEFAULT, null);
    /** A new session, named in the guest by the first free number from 2. */
    public static final ShellSession NEW = new ShellSession(Kind.NEW, null);
    /** A plain login shell, outside any session. */
    public static final ShellSession NONE = new ShellSession(Kind.NONE, null);

    public ShellSession {
        if ((kind == Kind.NAMED) != (name != null)) {
            throw new IllegalArgumentException("only a named session has a name");
        }
        if (name != null && !isValidName(name)) {
            throw new IllegalArgumentException("not a session name: " + name);
        }
    }

    /** The session called {@code name}, which {@link #isValidName} must accept. */
    public static ShellSession named(String name) {
        return new ShellSession(Kind.NAMED, name);
    }

    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }
}
