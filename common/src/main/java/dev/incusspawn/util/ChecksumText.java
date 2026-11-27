package dev.incusspawn.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The checksum a sidecar's body states, read one way by the proxy's cache and by {@code isx
 * doctor}'s check of a Maven mirror (#2681), so the two never disagree on what a usable
 * {@code .sha1} is.
 */
public final class ChecksumText {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ChecksumText() {}

    /**
     * The checksum {@code body} states, as lowercase hex matching {@code hexPattern}, or null if
     * none is found. Repositories publish the bare hex, the {@code sha1sum} form
     * {@code "<hex>  <filename>"} (hex first) and the BSD/OpenSSL forms
     * {@code "SHA1(<filename>)= <hex>"} and {@code "MD5 (<filename>) = <hex>"} (hex last), all of
     * which Maven accepts.
     */
    public static String hex(byte[] body, Pattern hexPattern) {
        if (body == null) return null;
        var tokens = WHITESPACE.split(new String(body, StandardCharsets.US_ASCII).trim());
        for (var token : new String[] {tokens[0], tokens[tokens.length - 1]}) {
            var hex = token.toLowerCase(Locale.ROOT);
            if (hexPattern.matcher(hex).matches()) return hex;
        }
        return null;
    }
}
