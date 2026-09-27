package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.*;

class CertificateRejectionTest {

    @Test
    void clientRejectingTheCertificateIsRecognised() {
        // The exact message the JDK engine produces when a Go client refuses the leaf.
        var err = new SSLHandshakeException("(bad_certificate) Received fatal alert: bad_certificate");
        assertEquals("bad_certificate", MitmProxy.certificateRejection(err));
    }

    @Test
    void recognisedWhenWrapped() {
        // Netty surfaces handshake failures wrapped (e.g. in a DecoderException).
        var err = new RuntimeException("decoder",
                new SSLHandshakeException("(unknown_ca) Received fatal alert: unknown_ca"));
        assertEquals("unknown_ca", MitmProxy.certificateRejection(err));
    }

    @Test
    void otherAlertsAreNotCertificateRejections() {
        // A protocol or cipher mismatch is not about trusting the CA, so it keeps the
        // full error rather than a message pointing at the CA.
        assertNull(MitmProxy.certificateRejection(
                new SSLHandshakeException("(protocol_version) Received fatal alert: protocol_version")));
        assertNull(MitmProxy.certificateRejection(
                new SSLHandshakeException("No available authentication scheme")));
        assertNull(MitmProxy.certificateRejection(new java.io.IOException("Connection reset")));
    }
}
