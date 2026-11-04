package dev.incusspawn.ssh;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Well-formed SSH public key blobs for tests: the type, then key material derived from {@code seed}. */
public final class TestHostKeys {

    private TestHostKeys() {}

    public static String blob(String type, int seed) {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            var name = type.getBytes(StandardCharsets.US_ASCII);
            out.writeInt(name.length);
            out.write(name);
            out.writeInt(32);
            for (int i = 0; i < 32; i++) out.writeByte(seed + i);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static HostKeys.HostKey key(String type, int seed) {
        return new HostKeys.HostKey(type, blob(type, seed));
    }

    /** What a guest's probe prints for {@code key}. */
    public static String printed(HostKeys.HostKey key) {
        return HostKeys.PREFIX + key.type() + " " + key.blob();
    }
}
