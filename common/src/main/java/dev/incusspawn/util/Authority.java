package dev.incusspawn.util;

/**
 * A host and its port as {@code Host} names them over https: the port only when it is not 443.
 * The one formula behind a mirror's name, the proxy's {@code Host} on a redirect and its 429
 * tally, so two servers on one host are told apart the same way everywhere (#2681).
 */
public final class Authority {

    private Authority() {}

    /** {@code host}, with {@code :port} when the port is neither 443 nor unset (negative). */
    public static String of(String host, int port) {
        return port == 443 || port < 0 ? host : host + ":" + port;
    }
}
