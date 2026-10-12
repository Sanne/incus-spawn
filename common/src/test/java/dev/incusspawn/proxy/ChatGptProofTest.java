package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGptProofTest {

    private static final String SECRET = "11".repeat(32);

    /**
     * Pinned: the IT's fixture (.github/scripts/test-chatgpt-session.sh) derives the same values in Python,
     * and checks this vector before it derives anything.
     */
    @Test
    void derivationIsPinned() {
        var proofs = ChatGptProof.derive(SECRET, "sso", "session-1");
        assertEquals(VECTOR_REFRESH, proofs.refresh());
        assertEquals(VECTOR_ACCESS, proofs.access());
    }

    static final String VECTOR_REFRESH = "ad11f9e55facd04df98d86f8b541d815b6a70c02e3f86eccc2207180b5271082";
    static final String VECTOR_ACCESS = "1f9f4bb27ccf73b6b3b1043fa1381ca38631be1068353fe4b30e89e7929b0eb1";

    @Test
    void proofsDifferBySecretAccountSessionAndKind() {
        var base = ChatGptProof.derive(SECRET, "sso", "s1");
        assertNotEquals(base.refresh(), ChatGptProof.derive("22".repeat(32), "sso", "s1").refresh());
        assertNotEquals(base.refresh(), ChatGptProof.derive(SECRET, "other", "s1").refresh());
        assertNotEquals(base.refresh(), ChatGptProof.derive(SECRET, "sso", "s2").refresh());
        assertNotEquals(base.refresh(), base.access());
        assertEquals(base.access(), ChatGptProof.accessFromRefresh(base.refresh()));
    }

    @Test
    void theMetadataHashMintsNothingUsable() {
        // #1107: the guest can read sha256(secret) through /dev/incus; keyed with it, a proof fails
        var real = ChatGptProof.derive(SECRET, "sso", "s1");
        var forged = ChatGptProof.derive(InstanceSecret.sha256(SECRET), "sso", "s1");
        assertFalse(ChatGptProof.matches(forged.access(), ChatGptProof.verifier(real.access())));
        assertFalse(ChatGptProof.matches(forged.refresh(), ChatGptProof.verifier(real.refresh())));
        // Nor does the recorded verifier itself, presented as a proof
        assertFalse(ChatGptProof.matches(ChatGptProof.verifier(real.access()), ChatGptProof.verifier(real.access())));
        assertTrue(ChatGptProof.matches(real.access(), ChatGptProof.verifier(real.access())));
    }

    @Test
    void placeholdersAreRecognisedOnlyInTheirOwnForm() {
        var proofs = ChatGptProof.derive(SECRET, "sso", "s1");
        assertEquals(proofs.refresh(), ChatGptProof.refreshDigestOf(proofs.refreshToken()));
        assertNull(ChatGptProof.refreshDigestOf("rt.isx_placeholder"));
        assertNull(ChatGptProof.refreshDigestOf("rt.real-token"));
        assertEquals(proofs.access(), ChatGptProof.accessDigestOf("a.b.isx_" + proofs.access()));
        assertNull(ChatGptProof.accessDigestOf("a.b.c.isx_" + proofs.access()));
        assertNull(ChatGptProof.accessDigestOf("a.b.isx_placeholder"));
        assertTrue(ChatGptProof.claimsAccess("a.b.isx_anything"));
        assertFalse(ChatGptProof.claimsAccess("a.b.realsignature"));
        assertFalse(ChatGptProof.matches(null, ChatGptProof.verifier(proofs.access())));
        assertFalse(ChatGptProof.matches(proofs.access(), ""));
    }

    @Test
    void namesThatCouldBreakTheContextAreRefused() {
        for (var bad : new String[]{"a:b", "", "../x", "a b"}) {
            assertFalse(ChatGptProof.isValidName(bad), bad);
        }
    }
}
