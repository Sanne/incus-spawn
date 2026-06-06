package dev.incusspawn.vm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
