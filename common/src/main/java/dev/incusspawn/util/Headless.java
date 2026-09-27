package dev.incusspawn.util;

/**
 * Whether isx is serving a machine rather than a person -- {@code isx mcp}, whose stdin and
 * stdout carry the protocol. Headless code must never read the console (it would consume
 * protocol bytes from stdin) or draw terminal animations.
 *
 * <p>A system property rather than a static field set at startup, so it cannot be baked into
 * the native image at build time.
 */
public final class Headless {

    public static final String PROPERTY = "isx.headless";

    private Headless() {}

    public static boolean active() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static void enable() {
        System.setProperty(PROPERTY, "true");
    }
}
