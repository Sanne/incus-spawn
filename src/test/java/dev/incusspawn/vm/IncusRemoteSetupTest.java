package dev.incusspawn.vm;

import dev.incusspawn.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IncusRemoteSetupTest {

    @TempDir Path tempHome;
    private String originalHome;

    @BeforeEach
    void isolateEnvironment() {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void restoreEnvironment() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void isConfiguredReturnsFalseWhenNoConfigExists() {
        assertFalse(IncusRemoteSetup.isConfigured());
    }

    @Test
    void ensureCertExistsGeneratesCertAndKey() throws Exception {
        assertFalse(Files.exists(Environment.incusClientCert()));
        assertFalse(Files.exists(Environment.incusClientKey()));

        IncusRemoteSetup.ensureCertExists();

        assertTrue(Files.exists(Environment.incusClientCert()));
        assertTrue(Files.exists(Environment.incusClientKey()));
        var certContent = Files.readString(Environment.incusClientCert());
        assertTrue(certContent.contains("BEGIN CERTIFICATE"));
    }

    @Test
    void ensureCertExistsIsIdempotent() throws Exception {
        IncusRemoteSetup.ensureCertExists();
        var firstCert = Files.readString(Environment.incusClientCert());

        IncusRemoteSetup.ensureCertExists();
        var secondCert = Files.readString(Environment.incusClientCert());

        assertEquals(firstCert, secondCert);
    }
}
