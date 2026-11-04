package dev.incusspawn.ssh;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An instance's SSH host keys, as its readiness probe reports them (#1863): what isx pins in its
 * managed {@code known_hosts}, so a client that forces {@code StrictHostKeyChecking=yes}, such as
 * herdr, finds the key it checks.
 *
 * <p>The probe runs as root in the guest, and the guest is not trusted with the host's files: a
 * line is never copied from it. Only a key type and its base64 blob are taken, each checked for
 * shape, and the blob must name the same type as the line does; isx writes the host field itself.
 * So a guest can pin only its own name to some key, which is no more than its sshd could present.
 */
public final class HostKeys {

    private HostKeys() {}

    /** One host key: its type ({@code ssh-ed25519}) and its base64 blob. */
    public record HostKey(String type, String blob) {}

    /** What the guest prints before each key, so nothing else it prints is taken for one. */
    static final String PREFIX = "isx-host-key ";

    /**
     * Shell, run as root in the guest by a readiness probe, that prints each of sshd's public host
     * keys as {@code isx-host-key <type> <blob>}. Only the first two fields of each file are read,
     * never its comment, and a symlink is skipped; a file without a final newline still counts.
     * Never fails and prints nothing else.
     */
    public static final String GUEST_SCRIPT = "for isx_k in /etc/ssh/ssh_host_*_key.pub; do"
            + " [ -f \"$isx_k\" ] && [ ! -L \"$isx_k\" ]"
            + " && { read -r isx_t isx_b _ || [ -n \"$isx_b\" ]; } < \"$isx_k\""
            + " && printf '" + PREFIX + "%s %s\\n' \"$isx_t\" \"$isx_b\"; done 2>/dev/null; true";

    /** More than sshd generates (ed25519, ecdsa, rsa): a guest printing more is not believed. */
    static final int MAX_KEYS = 8;
    private static final int MAX_BLOB = 4096;
    private static final Pattern TYPE = Pattern.compile("[a-z0-9@.-]{1,64}");
    private static final Pattern BLOB = Pattern.compile("[A-Za-z0-9+/]+={0,2}");
    private static final Pattern FIELDS = Pattern.compile(" ");

    /**
     * The keys in a probe's output, in the order printed, each once. A line without the prefix, or
     * whose key fails a check, is skipped; more than {@link #MAX_KEYS} keys is none at all.
     */
    public static List<HostKey> parse(String probeOutput) {
        var keys = new ArrayList<HostKey>();
        if (probeOutput == null) return keys;
        for (var line : probeOutput.lines().toList()) {
            if (!line.startsWith(PREFIX)) continue;
            var fields = FIELDS.split(line.substring(PREFIX.length()).strip(), -1);
            if (fields.length != 2) continue;
            var key = new HostKey(fields[0], fields[1]);
            if (!valid(key) || keys.contains(key)) continue;
            if (keys.size() == MAX_KEYS) return List.of();
            keys.add(key);
        }
        return keys;
    }

    /** Whether the blob decodes as an SSH public key of the type the line names. */
    static boolean valid(HostKey key) {
        if (!TYPE.matcher(key.type()).matches()) return false;
        if (key.blob().length() > MAX_BLOB || !BLOB.matcher(key.blob()).matches()) return false;
        try {
            var buffer = ByteBuffer.wrap(Base64.getDecoder().decode(key.blob()));
            int length = buffer.getInt();
            if (length <= 0 || length > buffer.remaining()) return false;
            var type = new byte[length];
            buffer.get(type);
            return key.type().equals(new String(type, StandardCharsets.US_ASCII)) && buffer.hasRemaining();
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException e) {
            return false;
        }
    }
}
