package dev.incusspawn.command;

import dev.incusspawn.Environment;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.EventLogReader;
import dev.incusspawn.proxy.EventLogReader.PullRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** What GitHub is asked for the overview (#2625): each account at once, and a merged pull request once a session. */
@ExtendWith(IsolatedHome.class)
class PullRequestStatesTest {

    @TempDir
    Path dir;

    private static final String ONE = "2026-11-01T08:00:00Z";
    private static final String TWO = "2026-11-02T08:00:00Z";

    private List<InstanceListing.InstanceInfo> instances() {
        var daemon = new FakeIncusDaemon()
                .instance("one", "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                        Metadata.accountKey("github"), "alice"))
                .instance("two", "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                        Metadata.accountKey("github"), "bob"))
                .createdAt("one", ONE)
                .createdAt("two", TWO);
        return InstanceListing.collectEntries(daemon.client().listJsonConfig());
    }

    private SpawnConfig config() throws Exception {
        Files.createDirectories(Environment.configDir());
        Files.writeString(Environment.configDir().resolve("config.yaml"), """
                github:
                  default: alice
                  accounts:
                    alice:
                      token: alice-token
                    bob:
                      token: bob-token
                """);
        return SpawnConfig.load();
    }

    private EventLogReader.Log log() throws Exception {
        var file = dir.resolve("events.jsonl");
        Files.writeString(file, String.join("\n",
                "{\"t\":\"2026-11-03T10:01:00Z\",\"kind\":\"github\",\"op\":\"pull_request\",\"instance\":\"one\",\"incarnation\":\"" + ONE + "\","
                        + "\"repo\":\"o/r\",\"number\":12}",
                "{\"t\":\"2026-11-03T10:02:00Z\",\"kind\":\"github\",\"op\":\"pull_request\",\"instance\":\"one\",\"incarnation\":\"" + ONE + "\","
                        + "\"repo\":\"o/r\",\"number\":13}",
                "{\"t\":\"2026-11-03T10:03:00Z\",\"kind\":\"github\",\"op\":\"pull_request\",\"instance\":\"two\",\"incarnation\":\"" + TWO + "\","
                        + "\"repo\":\"o/r\",\"number\":12}") + "\n");
        return EventLogReader.read(file);
    }

    @Test
    void eachAccountIsAskedAtOnce() throws Exception {
        var both = new CyclicBarrier(2);
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var states = PullRequestStates.read(instances(), log(), config(), (token, pulls) -> {
            try {
                // Asked one after the other, the first would wait here alone and time out
                both.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new java.io.IOException("not asked at once: " + e);
            }
            return Map.of(new PullRequest("o/r", 12), PullRequestStates.State.OPEN);
        }, failures::add);
        // Heard on the accounts' threads, where a fail() would be lost
        assertEquals(List.of(), List.copyOf(failures));
        assertEquals(PullRequestStates.State.OPEN, states.get("one").get(new PullRequest("o/r", 12)));
        assertEquals(PullRequestStates.State.OPEN, states.get("two").get(new PullRequest("o/r", 12)));
    }

    @Test
    void aMergedPullRequestIsNotAskedAboutAgainForTheSameAccount() throws Exception {
        var settled = new PullRequestStates.Settled();
        var asked = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        PullRequestStates.GitHub github = (token, pulls) -> {
            asked.add(token + " " + pulls);
            return token.equals("alice-token")
                    ? Map.of(new PullRequest("o/r", 12), PullRequestStates.State.MERGED,
                            new PullRequest("o/r", 13), PullRequestStates.State.OPEN)
                    : Map.of(new PullRequest("o/r", 12), PullRequestStates.State.MERGED);
        };
        var instances = instances().stream().filter(i -> i.name().equals("one")).toList();
        var config = config();
        var log = log();
        var failures = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        PullRequestStates.read(instances, log, config, github, failures::add, settled);
        assertEquals(List.of("alice-token [o/r#12, o/r#13]"), List.copyOf(asked));

        asked.clear();
        var again = PullRequestStates.read(instances(), log, config, github, failures::add, settled);
        // Heard on the accounts' threads, where a fail() would be lost
        assertEquals(List.of(), List.copyOf(failures));
        assertEquals(PullRequestStates.State.MERGED, again.get("one").get(new PullRequest("o/r", 12)), "still merged");
        assertEquals(PullRequestStates.State.OPEN, again.get("one").get(new PullRequest("o/r", 13)));
        assertEquals(java.util.Set.of("alice-token [o/r#13]", "bob-token [o/r#12]"), java.util.Set.copyOf(asked),
                "only the open one again, and another account is asked for itself");
    }
}
