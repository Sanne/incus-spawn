package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.RecordingOutput;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static dev.incusspawn.git.GitTestUtils.git;
import static dev.incusspawn.git.GitTestUtils.releasesFixture;
import static org.junit.jupiter.api.Assertions.*;

/** A host refresh whose remote accepts the connection and then never answers (#1343). */
class HostRepoRefreshStallTest {

    @TempDir Path tmp;

    @Test
    void aStalledFetchIsKilledAndReportedAsANonFatalWarning() throws Exception {
        var host = tmp.resolve("host").resolve("releases");
        git(tmp, "clone", "-q", releasesFixture(tmp).toString(), host.toString());
        var head = git(host, "rev-parse", "origin/main");

        try (var silent = new SilentRemote()) {
            var url = silent.url("http", "releases.git");
            git(host, "remote", "set-url", "origin", url);

            var config = new SpawnConfig();
            config.setRepoPaths(Map.of("releases", host.toString()));
            config.setConfigByPath("git-transfer.stall", "2s");
            var repo = new ImageDef.RepoEntry();
            repo.setUrl(url);
            var said = assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                    RecordingOutput.during(() -> HostRepoRefresh.refresh(List.of(repo), Map.of(), config)));

            var warnings = said.events(RecordingOutput.Kind.WARN).stream().map(RecordingOutput.Event::msg).toList();
            assertTrue(warnings.stream().anyMatch(w -> w.contains("Fetch failed for releases") && w.contains("stalled")),
                    "warning names the stall: " + said.events());
            assertEquals(head, git(host, "rev-parse", "origin/main"), "the existing checkout is untouched");
            assertEquals(List.of(), gitDescendants(), "no Git process survives the stall");
        }
    }

    /** Git processes still running under this JVM, after giving killed ones a moment to be reaped. */
    private static List<Long> gitDescendants() throws Exception {
        java.util.function.Supplier<List<Long>> live = () -> ProcessHandle.current().descendants()
                .filter(p -> p.info().command().map(c -> c.contains("git")).orElse(false))
                .map(ProcessHandle::pid).toList();
        dev.incusspawn.util.Eventually.holdsWithin(java.time.Duration.ofSeconds(5), () -> live.get().isEmpty());
        return live.get();
    }
}
