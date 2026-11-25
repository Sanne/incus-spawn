package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.Environment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** The banner {@code isx init} opens with says whether it is setting up or revisiting a host (#1119). */
@ExtendWith(IsolatedHome.class)
class InitBannerTest {

    /** The banner, a frame init draws as given. */
    private static String banner() {
        return String.join("\n", RecordingOutput.during(() -> InitCommand.printSetupBanner("About 3 minutes.", null)).frameLines());
    }

    @Test
    void aFreshHostGetsFirstTimeSetup() {
        var banner = banner();
        assertTrue(banner.contains("Sets up this"), banner);
        assertTrue(banner.contains("About 3 minutes."), banner);
    }

    @Test
    void aHostThatCompletedInitBeforeIsNotFirstTime() throws Exception {
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.initCompleteMarker(), "1");
        var banner = banner();
        assertFalse(banner.contains("Sets up"), banner);
        assertTrue(banner.contains("Checks the setup of"), banner);
        // How long a first setup takes says nothing about a re-run
        assertFalse(banner.contains("minutes"), banner);
    }

    @Test
    void anExistingConfigIsNotFirstTimeEither() throws Exception {
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.configDir().resolve("config.yaml"), "{}\n");
        var banner = banner();
        assertFalse(banner.contains("Sets up"), banner);
        assertTrue(banner.contains("Checks the setup of"), banner);
        // How long a first setup takes says nothing about a re-run
        assertFalse(banner.contains("minutes"), banner);
    }
}
