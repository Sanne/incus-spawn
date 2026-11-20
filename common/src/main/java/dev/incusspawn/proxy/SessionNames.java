package dev.incusspawn.proxy;

import java.util.regex.Pattern;

/** What an account name or a session id of a {@link SessionStore} may hold: they name files and are joined into contexts. */
public final class SessionNames {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private SessionNames() {}

    public static boolean isValid(String name) {
        return name != null && NAME.matcher(name).matches();
    }
}
