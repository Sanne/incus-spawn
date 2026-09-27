package dev.incusspawn.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Config drift is "differs from what was loaded", never "newer than when we loaded" (#818). */
class ConfigFingerprintTest {

    @TempDir
    Path dir;

    @Test
    void aFutureMtimeIsNotDriftOnceLoaded() throws Exception {
        var config = Files.writeString(dir.resolve("config.yaml"), "a: 1\n");
        Files.createDirectory(dir.resolve("tools"));
        var tool = Files.writeString(dir.resolve("tools/t.yaml"), "name: t\n");
        var future = FileTime.from(Instant.now().plus(Duration.ofHours(1)));
        Files.setLastModifiedTime(config, future);
        Files.setLastModifiedTime(tool, future);

        assertEquals(ConfigFingerprint.capture(dir), ConfigFingerprint.capture(dir));
    }

    @Test
    void anEditIsDriftEvenWhenItsMtimeIsOlder() throws Exception {
        var config = Files.writeString(dir.resolve("config.yaml"), "a: 1\n");
        var loaded = ConfigFingerprint.capture(dir);

        // A restored backup, or a clock stepped back: the new mtime precedes the load.
        Files.setLastModifiedTime(config, FileTime.from(Instant.now().minus(Duration.ofDays(1))));

        assertNotEquals(loaded, ConfigFingerprint.capture(dir));
    }

    @Test
    void anEditWithinTheSameTickIsDrift() throws Exception {
        var config = Files.writeString(dir.resolve("config.yaml"), "a: 1\n");
        var mtime = Files.getLastModifiedTime(config);
        var loaded = ConfigFingerprint.capture(dir);

        Files.writeString(config, "a: 12\n");
        Files.setLastModifiedTime(config, mtime);

        assertNotEquals(loaded, ConfigFingerprint.capture(dir));
    }

    @Test
    void anEditRacingTheReadIsDrift() throws Exception {
        var config = Files.writeString(dir.resolve("config.yaml"), "a: 1\n");

        var loaded = ConfigFingerprint.load(dir, () -> {
            try {
                Files.writeString(config, "a: 12\n");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return null;
        });

        assertNotEquals(loaded.fingerprint(), ConfigFingerprint.capture(dir),
                "a fingerprint taken after the read would record the racing edit as already loaded");
    }

    @Test
    void aSaveByRenameIsDriftEvenWithTheSameMtimeAndSize() throws Exception {
        var config = Files.writeString(dir.resolve("config.yaml"), "a: 1\n");
        var mtime = Files.getLastModifiedTime(config);
        var loaded = ConfigFingerprint.capture(dir);

        var replacement = Files.writeString(dir.resolve("config.yaml.tmp"), "a: 2\n");
        Files.setLastModifiedTime(replacement, mtime);
        Files.move(replacement, config, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);

        assertNotEquals(loaded, ConfigFingerprint.capture(dir));
    }

    @Test
    void configAppearingOrDisappearingIsDrift() throws Exception {
        var empty = ConfigFingerprint.capture(dir);
        Files.writeString(dir.resolve("config.yaml"), "a: 1\n");
        var present = ConfigFingerprint.capture(dir);

        assertNotEquals(empty, present);
        Files.delete(dir.resolve("config.yaml"));
        assertEquals(empty, ConfigFingerprint.capture(dir));
    }

    @Test
    void addingOrRemovingAToolIsDrift() throws Exception {
        var tools = Files.createDirectory(dir.resolve("tools"));
        var loaded = ConfigFingerprint.capture(dir);

        var tool = Files.writeString(tools.resolve("t.yaml"), "name: t\n");
        assertNotEquals(loaded, ConfigFingerprint.capture(dir));

        Files.delete(tool);
        assertEquals(loaded, ConfigFingerprint.capture(dir));
    }

    @Test
    void filesOtherThanToolYamlAreIgnored() throws Exception {
        var tools = Files.createDirectory(dir.resolve("tools"));
        var loaded = ConfigFingerprint.capture(dir);

        Files.writeString(tools.resolve("notes.txt"), "x");

        assertEquals(loaded, ConfigFingerprint.capture(dir));
    }
}
