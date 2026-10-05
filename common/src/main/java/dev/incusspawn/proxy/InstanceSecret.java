package dev.incusspawn.proxy;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.tool.ToolDef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;

/**
 * A random secret each instance is given at every start, so that a service on the host can
 * identify a caller by <em>address and secret together</em> (#934).
 *
 * <p>The source address stays the identity the proxy relies on, made trustworthy by
 * {@code security.ipv4_filtering}; the secret is a second layer on top of it, never an
 * alternative. It keeps an identity from passing on address reuse or on a misconfigured filter,
 * and gives a host-side service something another instance on the bridge cannot present. Since
 * it is only ever accepted from its bound address, a leaked secret is useless on its own: it is a
 * plain random string with no rotation protocol beyond "every start makes a new one".
 *
 * <h3>Where it lives</h3>
 * <ul>
 *   <li>Host side, only its SHA-256 is kept, as {@link Metadata#INSTANCE_SECRET_SHA256} -- read
 *       by {@link InstanceRegistry} in the listing it already makes. The guest can read its own
 *       {@code user.*} keys through {@code /dev/incus}, so the secret itself never goes there.</li>
 *   <li>In the guest, at {@link #GUEST_PATH}, owned by root and readable by the instance user's
 *       group only. {@code /run} is a tmpfs: any reboot, including one isx did not do, leaves
 *       the box without a secret, which fails closed, until isx starts it again or the next
 *       {@code isx shell} notices the reboot and gives it one (#1024). The login
 *       profile exports {@link #FILE_ENV_VAR} pointing at it; the value is not exported, so it
 *       does not travel in the environment of every process or show up in an {@code env} dump.</li>
 *   <li>Never in an image: builds do not get one, and a branch is given its own before its
 *       first start, replacing whatever hash its source carried.</li>
 * </ul>
 *
 * <p>A caller presents it in {@link #HEADER}; {@link InstanceRegistry#identify} checks it.
 */
public final class InstanceSecret {

    private static final String GUEST_DIR = "/run/isx";
    /** Where the guest finds its secret. */
    public static final String GUEST_PATH = GUEST_DIR + "/instance-secret";
    /** Exported by the guest's login profile, naming {@link #GUEST_PATH}. */
    public static final String FILE_ENV_VAR = "ISX_INSTANCE_SECRET_FILE";
    /** The request header a caller presents the secret in. */
    public static final String HEADER = "X-Isx-Instance-Secret";

    /** The exec environment variable {@link #GUEST_SCRIPT} takes the secret from. */
    public static final String DELIVERY_ENV = "ISX_INSTANCE_SECRET";
    static final String PROFILE_PATH = "/etc/profile.d/isx-instance-secret.sh";
    static final String RUN_IS_TMPFS = "grep -qs '^[^ ]* /run tmpfs ' /proc/mounts";
    private static final int BYTES = 32;

    private InstanceSecret() {}

    /** A new secret: 256 random bits, hex-encoded. */
    public static String generate() {
        var bytes = new byte[BYTES];
        // Not a static field: build-time initialization would put it in the native image heap
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * A new secret, its hash put in {@code config} under {@link Metadata#INSTANCE_SECRET_SHA256}
     * for the caller to write with whatever else it records -- the one way a start gets its secret.
     */
    public static String stampInto(Map<String, String> config) {
        var secret = generate();
        config.put(Metadata.INSTANCE_SECRET_SHA256, sha256(secret));
        return secret;
    }

    /** What the host records for {@code secret}. */
    public static String sha256(String secret) {
        return ToolDef.sha256hex(secret);
    }

    /**
     * Whether {@code presented} is the secret {@code recordedSha256} was made from. Constant
     * time in the content, and false for anything missing: an instance with no recorded secret
     * never matches, whatever is presented.
     */
    public static boolean matches(String presented, String recordedSha256) {
        if (presented == null || presented.isEmpty()
                || recordedSha256 == null || recordedSha256.isEmpty()) return false;
        return MessageDigest.isEqual(sha256(presented.strip()).getBytes(StandardCharsets.US_ASCII),
                recordedSha256.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Shell that puts the secret in {@link #guestEnv} in place in a running guest, and the
     * profile line that names it. Idempotent, since it rides in scripts that are retried until
     * they answer. Every step is best-effort: a box left without its secret is refused by
     * whatever checks it, which is the safe way for this to fail, and must not stop the start it
     * rides on.
     *
     * <p>The secret arrives in the exec's environment, never on its command line: any user in
     * the guest can read a process's {@code /proc/<pid>/cmdline}, only its own uid and root its
     * {@code environ}. The script moves it into an unexported variable at once, so nothing it
     * runs inherits it, and writes it with the shell's builtin {@code printf}.
     *
     * <p>A container answers exec as soon as its init runs, possibly before systemd has mounted
     * the tmpfs on /run: a secret written then would land on the rootfs -- hidden by the mount,
     * and carried into copies. The script gives the mount a moment, and skips the write without it.
     */
    public static final String GUEST_SCRIPT = "isx_secret=$" + DELIVERY_ENV + "; unset " + DELIVERY_ENV + "; "
            + "[ -n \"$isx_secret\" ] && { i=0; until " + RUN_IS_TMPFS + "; do i=$((i+1)); [ $i -ge 60 ] && break; sleep 0.05; done; "
            + RUN_IS_TMPFS + " && install -d -m 755 " + GUEST_DIR
            + " && (umask 077 && printf '%s\\n' \"$isx_secret\" > " + GUEST_PATH + ".new)"
            + " && chgrp 1000 " + GUEST_PATH + ".new && chmod 440 " + GUEST_PATH + ".new"
            + " && mv -f " + GUEST_PATH + ".new " + GUEST_PATH
            + " && printf '%s\\n' " + Container.shellQuote("export " + FILE_ENV_VAR + "=" + GUEST_PATH) + " > " + PROFILE_PATH
            + "; } >/dev/null 2>&1; unset isx_secret; true";

    /** What {@link #GUEST_CHECK} prints for a guest that holds no secret. */
    public static final String MISSING = "isx-instance-secret-missing";

    /**
     * Shell that prints {@link #MISSING} when the guest holds no secret, as after a reboot isx
     * did not do, for a probe that already runs in the guest to ask on the way. Silent when
     * {@code /run} is no tmpfs: {@link #GUEST_SCRIPT} would not write one there, so asking for
     * a new secret on every probe could not help.
     */
    public static final String GUEST_CHECK = RUN_IS_TMPFS + " && ! [ -s " + GUEST_PATH + " ] && echo " + MISSING + "; true";

    /** Whether the output of a probe running {@link #GUEST_CHECK} says the guest holds no secret. */
    public static boolean missingIn(String stdout) {
        return stdout != null && stdout.lines().anyMatch(MISSING::equals);
    }

    /** The exec environment that hands {@code secret} to {@link #GUEST_SCRIPT}. */
    public static Map<String, String> guestEnv(String secret) {
        return Map.of(DELIVERY_ENV, requireHex(secret));
    }

    /** Only what {@link #generate} makes is ever handed to the guest as a secret. */
    private static String requireHex(String secret) {
        if (secret == null || secret.isEmpty() || !secret.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            throw new IllegalArgumentException("Not an instance secret");
        }
        return secret;
    }
}
