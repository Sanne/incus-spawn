package dev.incusspawn.proxy;

import dev.incusspawn.tool.ToolDef;

import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * What a guest presents in place of a ChatGPT session's tokens (#1332): proofs derived from the
 * instance's per-start secret, bound to one account and one host session.
 *
 * <h3>Derivation</h3>
 * As #1107 requires, every proof is keyed with the <em>raw</em> per-start secret
 * ({@link InstanceSecret}), never with its SHA-256: the guest can read that hash through
 * {@code /dev/incus}, and anyone holding it could then mint proofs.
 * <ul>
 *   <li>The <b>refresh proof</b> is HMAC-SHA256(secret, {@code isx-chatgpt:<account>:<session>:refresh}).</li>
 *   <li>The <b>access proof</b> is HMAC-SHA256(refresh proof, {@code isx-chatgpt-access}).</li>
 * </ul>
 * So the refresh proof implies the access proof, and not the other way round. That is what lets
 * the proxy answer a refresh with the caller's own access proof without holding the secret: the
 * refresh request carries the refresh proof, and the access proof follows from it. Holding only
 * an access placeholder yields no refresh.
 *
 * <h3>Verification</h3>
 * The host records only {@link #verifier SHA-256 verifiers} of the two proofs, in instance
 * metadata next to the secret's own hash, together with the account and session they were
 * derived for ({@code Metadata.CHATGPT_SESSION}). The proxy hashes what a request presents and
 * compares in constant time. A new session, a new start or another instance means another proof.
 *
 * <h3>Forms</h3>
 * The refresh placeholder is {@code rt.isx_<refresh proof>}. The access placeholder is a JWT whose
 * signature segment is {@code isx_<access proof>}: Codex decodes the payload (its {@code exp}) and
 * never checks the signature.
 */
public final class ChatGptProof {

    /** Marks a value as an isx proof, after a prefix the client expects. */
    public static final String MARKER = "isx_";
    /** The refresh placeholder's prefix: Codex's refresh tokens start {@code rt.}. */
    public static final String REFRESH_PREFIX = "rt." + MARKER;
    private static final String REFRESH_CONTEXT = "isx-chatgpt:";
    private static final String ACCESS_CONTEXT = "isx-chatgpt-access";
    private static final int DIGEST_HEX_CHARS = 64;
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{" + DIGEST_HEX_CHARS + "}");
    /** What an account name or a session id may hold: they are joined with ':' into the context. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private ChatGptProof() {}

    /** One start's proofs for one account and session, as hex digests. */
    public record Proofs(String access, String refresh) {
        public String refreshToken() { return REFRESH_PREFIX + refresh; }
    }

    /**
     * The proofs {@code secret} gives for {@code account}'s session {@code sessionId}.
     *
     * @param secret the per-start secret as {@link InstanceSecret#generate} made it
     */
    public static Proofs derive(String secret, String account, String sessionId) {
        InstanceSecret.requireHex(secret);
        requireName(account, "account");
        requireName(sessionId, "session id");
        var refresh = ProofToken.hmacSha256Hex(HexFormat.of().parseHex(secret), REFRESH_CONTEXT + account + ":" + sessionId + ":refresh");
        return new Proofs(accessFromRefresh(refresh), refresh);
    }

    /** The access proof a refresh proof implies. */
    public static String accessFromRefresh(String refreshDigest) {
        if (!isDigest(refreshDigest)) throw new IllegalArgumentException("Not a proof digest");
        return ProofToken.hmacSha256Hex(HexFormat.of().parseHex(refreshDigest), ACCESS_CONTEXT);
    }

    /** What the host records for a proof: never the proof itself. */
    public static String verifier(String digest) {
        return ToolDef.sha256hex(digest);
    }

    /**
     * Whether {@code digest} is the proof {@code recordedSha256} was made from. Constant time in
     * the content; false for anything missing, so an instance without a verifier matches nothing.
     */
    public static boolean matches(String digest, String recordedSha256) {
        return isDigest(digest) && InstanceSecret.matches(digest, recordedSha256);
    }

    /** The refresh proof in a refresh placeholder, or null when {@code token} is not one. */
    public static String refreshDigestOf(String token) {
        if (token == null || !token.startsWith(REFRESH_PREFIX)) return null;
        var digest = token.substring(REFRESH_PREFIX.length());
        return isDigest(digest) ? digest : null;
    }

    /**
     * Whether a bearer token claims to be an isx access placeholder: a JWT whose signature segment
     * starts with {@link #MARKER}. A real token's base64url signature starts so with odds of one in
     * 64^4, and such a token is refused rather than spent, so a false claim fails closed.
     */
    public static boolean claimsAccess(String token) {
        if (token == null) return false;
        var lastDot = token.lastIndexOf('.');
        return lastDot > 0 && token.startsWith(MARKER, lastDot + 1);
    }

    /** The access proof in an access placeholder, or null when {@code token} is not one. */
    public static String accessDigestOf(String token) {
        if (!claimsAccess(token) || token.chars().filter(c -> c == '.').count() != 2) return null;
        var digest = token.substring(token.lastIndexOf('.') + 1 + MARKER.length());
        return isDigest(digest) ? digest : null;
    }

    /** Whether {@code name} may be used as an account name or a session id here. */
    public static boolean isValidName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    private static void requireName(String name, String what) {
        if (!isValidName(name)) throw new IllegalArgumentException("Not a valid " + what + ": " + name);
    }

    private static boolean isDigest(String value) {
        return value != null && DIGEST.matcher(value).matches();
    }

}
