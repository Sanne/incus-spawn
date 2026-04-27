package dev.incusspawn.git;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.incusspawn.git.GitTestUtils.runGit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoRemoteServiceTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @BeforeEach
    void setup() {
        // Set user.home to tempDir so SpawnConfig.load() (called by AutoRemoteService)
        // finds our test config at tempDir/.config/incus-spawn/config.yaml
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void addRemotesChecksAllRemotesNotJustOrigin() throws IOException, InterruptedException {
        // Setup: create host repo with fork as origin, upstream as canonical
        var repoDir = tempDir.resolve("incus-spawn");
        repoDir.toFile().mkdirs();
        runGit(repoDir, "init");
        runGit(repoDir, "config", "user.name", "Test User");
        runGit(repoDir, "config", "user.email", "test@example.com");
        runGit(repoDir, "commit", "--allow-empty", "-m", "Initial commit");
        runGit(repoDir, "remote", "add", "origin", "git@github.com:yrodiere/incus-spawn.git");
        runGit(repoDir, "remote", "add", "upstream", "https://github.com/Sanne/incus-spawn.git");

        // Create config directory and files (user.home is set to tempDir in @BeforeEach)
        var configDir = tempDir.resolve(".config/incus-spawn");
        configDir.toFile().mkdirs();
        Files.writeString(configDir.resolve("config.yaml"), "host-path: \"" + tempDir + "\"");

        // Create image definition that declares the upstream repo
        var imagesDir = configDir.resolve("images");
        imagesDir.toFile().mkdirs();
        var imageDef = """
                name: tpl-incus-spawn
                parent: tpl-minimal
                repos:
                  - url: https://github.com/Sanne/incus-spawn/
                    path: /home/agentuser/incus-spawn
                """;
        Files.writeString(imagesDir.resolve("incus-spawn.yaml"), imageDef);

        // Mock IncusClient to return parent template
        var incus = mock(IncusClient.class);
        when(incus.configGet(eq("test-instance"), eq(Metadata.PARENT)))
                .thenReturn("tpl-incus-spawn");

        // Call AutoRemoteService.addRemotes - this should add the remote despite origin not matching
        AutoRemoteService.addRemotes(incus, "test-instance");

        // Verify: remote was added because upstream (not origin) matches the template repo
        var remotes = runGit(repoDir, "remote", "-v");
        assertThat(remotes)
                .as("Remote 'test-instance' should have been added")
                .contains("test-instance")
                .contains("isx://test-instance/home/agentuser/incus-spawn");
    }
}