package dev.incusspawn;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildInfoTest {

    @ParameterizedTest
    @CsvSource({
            "v1.2.3-5-gd4d0313, 1.2.3+5",
            "1.2.3-5-gd4d0313, 1.2.3+5",
            "v1.2.3-dev.1-15-gd4d0313, 1.2.3-dev.1+15",
            "' v1.2.3-5-gd4d0313 ', 1.2.3+5"
    })
    void snapshotsUseTheTagAndCommitCount(String description, String expected) {
        var build = buildInfo("0.0.0-SNAPSHOT", description);
        assertEquals(expected, build.displayVersion());
        assertEquals("0.0.0-SNAPSHOT", build.version(), "template stamps must stay stable between commits");
        assertTrue(build.isDev(),
                "snapshots between tags must still resolve a released appliance");
    }

    @ParameterizedTest
    @CsvSource({
            "v1.2.3, 1.2.3+0",
            "1.2.3, 1.2.3+0",
            "v1.2.3-0-gd4d0313, 1.2.3+0",
            "v1.2.3-dev.1, 1.2.3-dev.1+0",
            "1.2.3-dev.1, 1.2.3-dev.1+0"
    })
    void snapshotsAtExactTagsKeepASnapshotMarker(String description, String expected) {
        var build = buildInfo("1.2.4-SNAPSHOT", description);
        assertEquals(expected, build.displayVersion());
        assertEquals("1.2.4-SNAPSHOT", build.version());
        assertTrue(build.isDev(), "a snapshot at an exact tag is still a development build");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "%(describe:tags=true,match=v[0-9]*)", "nightly", "vnightly",
            "v", "v1.2.3 invalid"})
    void snapshotsWithoutAValidDescriptionKeepThePomVersion(String description) {
        var build = buildInfo("0.0.0-SNAPSHOT", description);
        assertEquals("0.0.0-SNAPSHOT", build.displayVersion());
        assertEquals("0.0.0-SNAPSHOT", build.version());
        assertTrue(build.isDev());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2.3", "1.2.3-dev.1"})
    void releasesKeepThePomVersion(String version) {
        var build = buildInfo(version, "v1.2.2-5-gd4d0313");
        assertEquals(version, build.displayVersion());
        assertEquals(version, build.version());
        assertFalse(build.isDev());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "0.0.0", "0.0.0-SNAPSHOT"})
    void existingDevelopmentVersionsRemainDevelopmentBuilds(String version) {
        assertTrue(new BuildInfo(version, "sha", "runtime").isDev());
    }

    @Test
    void missingBuildMetadataKeepsDevelopmentDefaults() {
        var build = BuildInfo.createInstance(new Properties(), new Properties());
        assertEquals("dev", build.version());
        assertEquals("dev", build.displayVersion());
        assertEquals("unknown", build.gitSha());
        assertTrue(build.isDev());
    }

    private static BuildInfo buildInfo(String version, String description) {
        var buildProps = new Properties();
        buildProps.setProperty("app.version", version);
        var gitProps = new Properties();
        gitProps.setProperty("git.commit.id", "d4d0313");
        gitProps.setProperty("git.commit.id.describe", description);
        var build = BuildInfo.createInstance(buildProps, gitProps);
        assertEquals("d4d0313", build.gitSha());
        return build;
    }
}
