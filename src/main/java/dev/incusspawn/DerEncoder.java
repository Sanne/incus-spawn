package dev.incusspawn;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.TimeZone;

public final class DerEncoder {

    private DerEncoder() {}

    // --- Algorithm Identifiers (private, exposed via clone methods) ---

    // SHA256withRSA: SEQUENCE { OID 1.2.840.113549.1.1.11, NULL }
    private static final byte[] SHA256_WITH_RSA_AID = derSequence(concat(
            new byte[]{0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                    (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b},
            new byte[]{0x05, 0x00}));

    // SHA384withECDSA: SEQUENCE { OID 1.2.840.10045.4.3.3 }
    private static final byte[] SHA384_WITH_ECDSA_AID = derSequence(
            new byte[]{0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x04, 0x03, 0x03});

    // --- Extension OIDs ---

    private static final byte[] OID_BASIC_CONSTRAINTS = {0x06, 0x03, 0x55, 0x1d, 0x13}; // 2.5.29.19
    private static final byte[] OID_SUBJECT_ALT_NAME  = {0x06, 0x03, 0x55, 0x1d, 0x11}; // 2.5.29.17
    private static final byte[] OID_KEY_USAGE          = {0x06, 0x03, 0x55, 0x1d, 0x0f}; // 2.5.29.15

    public static byte[] sha256WithRsaAid()    { return SHA256_WITH_RSA_AID.clone(); }
    public static byte[] sha384WithEcdsaAid()  { return SHA384_WITH_ECDSA_AID.clone(); }
    public static byte[] oidBasicConstraints() { return OID_BASIC_CONSTRAINTS.clone(); }
    public static byte[] oidSubjectAltName()   { return OID_SUBJECT_ALT_NAME.clone(); }
    public static byte[] oidKeyUsage()         { return OID_KEY_USAGE.clone(); }

    // --- Core DER primitives ---

    public static byte[] derSequence(byte[] content) {
        return concat(new byte[]{0x30}, derLength(content.length), content);
    }

    public static byte[] derSet(byte[] content) {
        return concat(new byte[]{0x31}, derLength(content.length), content);
    }

    public static byte[] derInteger(BigInteger val) {
        byte[] bytes = val.toByteArray();
        return concat(new byte[]{0x02}, derLength(bytes.length), bytes);
    }

    public static byte[] derBitString(byte[] content) {
        return concat(new byte[]{0x03}, derLength(content.length + 1),
                new byte[]{0x00}, content);
    }

    public static byte[] derOctetString(byte[] content) {
        return concat(new byte[]{0x04}, derLength(content.length), content);
    }

    public static byte[] derExplicit(int tag, byte[] content) {
        return concat(new byte[]{(byte) (0xa0 | tag)}, derLength(content.length), content);
    }

    public static byte[] derLength(int length) {
        if (length < 128) return new byte[]{(byte) length};
        if (length < 256) return new byte[]{(byte) 0x81, (byte) length};
        if (length < 65536) return new byte[]{(byte) 0x82, (byte) (length >> 8), (byte) length};
        throw new IllegalArgumentException("DER length too large: " + length);
    }

    public static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (var a : arrays) len += a.length;
        var result = new byte[len];
        int pos = 0;
        for (var a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    // --- X.509 helpers ---

    public static byte[] derUtcTime(Date date) {
        var sdf = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        byte[] ascii = sdf.format(date).getBytes(StandardCharsets.US_ASCII);
        return concat(new byte[]{0x17}, derLength(ascii.length), ascii);
    }

    public static byte[] derDistinguishedName(String cn) {
        byte[] cnOid = {0x06, 0x03, 0x55, 0x04, 0x03};
        byte[] cnValue = cn.getBytes(StandardCharsets.UTF_8);
        byte[] cnUtf8 = concat(new byte[]{0x0c}, derLength(cnValue.length), cnValue);
        return derSequence(derSet(derSequence(concat(cnOid, cnUtf8))));
    }

    public static byte[] derExtension(byte[] oid, boolean critical, byte[] value) {
        var parts = critical
                ? concat(oid, new byte[]{0x01, 0x01, (byte) 0xff}, derOctetString(value))
                : concat(oid, derOctetString(value));
        return derSequence(parts);
    }

    public static byte[] derDnsName(String name) {
        byte[] ascii = name.getBytes(StandardCharsets.US_ASCII);
        return concat(new byte[]{(byte) 0x82}, derLength(ascii.length), ascii);
    }

    // --- PEM encoding ---

    public static String toPem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
