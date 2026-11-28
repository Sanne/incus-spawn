package dev.incusspawn.command;

import dev.incusspawn.ai.AiHelpClient;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The model's merge of several agent sessions (#2241): made on the instance's own Claude account
 * or not at all, cached against what the model was given, and shown only in the shape asked for.
 */
class SessionMergeTest {

    @TempDir
    Path dir;

    private static final String ANSWER = """
            Headline: Fixed the parser and opened PR #12
            Done: The parser handles nested quotes; PR #12 is open.
            State: One commit is not pushed.
            Pending: Review comments on PR #12.
            """;

    /** Each question the model was asked, with the account it was asked on. */
    private final List<String> asked = new ArrayList<>();

    private final FakeIncusDaemon daemon = new FakeIncusDaemon()
            .instance("dev-1", "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                    Metadata.PARENT, "tpl", Metadata.accountKey("claude"), "personal"))
            .instance("dev-2", "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                    Metadata.PARENT, "tpl", Metadata.accountKey("claude"), "personal"))
            .instance("sub", "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                    Metadata.PARENT, "tpl", Metadata.accountKey("claude"), "max"))
            .instance("gone", "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                    Metadata.PARENT, "tpl", Metadata.accountKey("claude"), "missing"))
            .instance("child", "container", "Running", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                    Metadata.PARENT, "tpl", Metadata.accountKey("claude"), "inherit"))
            .createdAt("dev-1", "2026-11-01T08:00:00Z")
            .createdAt("dev-2", "2026-11-02T08:00:00Z")
            .createdAt("sub", "2026-11-02T08:00:00Z")
            .createdAt("gone", "2026-11-02T08:00:00Z")
            .createdAt("child", "2026-11-02T08:00:00Z");

    private static SpawnConfig config() {
        var config = new SpawnConfig();
        var claude = config.getClaude();
        // The AI Help account and the namespace's default: never used for an instance pinned elsewhere
        claude.putAccount("work", SpawnConfig.ClaudeAccount.ofApiKey("work-key"));
        claude.putAccount("personal", SpawnConfig.ClaudeAccount.ofApiKey("personal-key"));
        claude.putAccount("max", SpawnConfig.ClaudeAccount.ofOauth("max-token"));
        claude.setDefaultAccount("work");
        return config;
    }

    private InstanceInfo instance(String name) {
        return InstanceListing.collectEntries(daemon.client().listJsonConfig()).stream()
                .filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    private static AgentSessions.Session session(String closing, long at) {
        return new AgentSessions.Session("claude", "p/" + at + ".jsonl", Instant.ofEpochSecond(at), "/home/agentuser/app",
                "Fix the parser", closing, true);
    }

    private static GuestRead guest(String... closings) {
        var sessions = new ArrayList<AgentSessions.Session>();
        for (int i = 0; i < closings.length; i++) sessions.add(session(closings[i], 1_793_700_000L - i));
        var work = WorkState.parse(0, WorkState.HEADER + "\nrepo 0 1 0 app\nend\n", false);
        return new GuestRead(work, new AgentSessions(AgentSessions.State.READ, closings.length + 1, sessions, List.of(), null));
    }

    private SessionMerge.Model model(String answer) {
        return (config, target, system, question) -> {
            asked.add(target.accountName() + " " + target.provider() + "\n" + question);
            return answer;
        };
    }

    private Map<String, SessionMerge.Merged> merge(List<String> names, Map<String, GuestRead> guests, SessionMerge.Model model,
                                                   List<String> failed) {
        var instances = names.stream().map(this::instance).toList();
        return SessionMerge.mergeAll(instances, guests, config(), model, new SessionMerge.Cache(dir), failed::add);
    }

    @Test
    void theCallIsMadeOnTheInstancesOwnAccountNeverTheDefault() {
        var target = SessionMerge.target(config(), instance("dev-1"));
        assertEquals("personal", target.accountName(), "the pinned account, not the default nor the AI Help one");
        assertEquals(AiHelpClient.Provider.ANTHROPIC, target.provider());
        assertNull(SessionMerge.target(config(), instance("sub")), "a Pro/Max subscription cannot answer the Messages API");
        assertNull(SessionMerge.target(config(), instance("gone")), "a pin to a missing account fails closed");
        assertNull(SessionMerge.target(config(), instance("child")), "inherit is the parent's grant, not the host's to spend");
    }

    @Test
    void severalSessionsAreMergedOnceAndThenReadFromTheCache() {
        var failed = new ArrayList<String>();
        var guests = Map.of("dev-1", guest("Fixed the parser.", "Started on the parser."));
        var merged = merge(List.of("dev-1"), guests, model(ANSWER), failed).get("dev-1");
        assertEquals(List.of(), failed);
        assertEquals("Fixed the parser and opened PR #12", merged.headline());
        assertEquals("Review comments on PR #12.", merged.pending());
        assertEquals(2, merged.sessions());
        assertEquals(3, merged.total());
        assertEquals(1, asked.size());
        assertTrue(asked.getFirst().startsWith("personal ANTHROPIC\n"), asked.getFirst());
        assertTrue(asked.getFirst().contains("Closing report:\nFixed the parser.\n</session>"), asked.getFirst());
        assertTrue(asked.getFirst().contains("app: 0 uncommitted changes, 1 unpushed commit, 0 stashes"), asked.getFirst());

        var again = merge(List.of("dev-1"), guests, model("never asked"), failed).get("dev-1");
        assertEquals(merged.headline(), again.headline(), "unchanged sessions and clones are read from the cache");
        assertEquals(1, asked.size(), "no second call");

        merge(List.of("dev-1"), Map.of("dev-1", guest("Merged PR #12.", "Fixed the parser.")), model(ANSWER), failed);
        assertEquals(2, asked.size(), "a new closing message asks again");
    }

    @Test
    void oneSessionOrAnAccountThatCannotAnswerMakesNoCall() {
        var failed = new ArrayList<String>();
        var merged = merge(List.of("dev-1", "sub", "gone", "child"), Map.of(
                "dev-1", guest("Only one."),
                "sub", guest("a", "b"),
                "gone", guest("a", "b"),
                "child", guest("a", "b")), model(ANSWER), failed);
        assertEquals(Map.of(), merged);
        assertEquals(List.of(), asked);
        assertEquals(List.of(), failed, "no account to use is no failure: the newest session is shown");
    }

    @Test
    void aStoppedInstanceShowsItsLastSummaryAndIsNeverAsked() {
        var failed = new ArrayList<String>();
        var stopped = instance("dev-2");
        new SessionMerge.Cache(dir).put("dev-2", "2026-11-02T08:00:00Z", "fp",
                new SessionMerge.Merged("Wrote the docs", "d", "s", "p", 2, 2, 1, Instant.parse("2026-11-02T10:00:00Z")), failed::add);
        var merged = SessionMerge.mergeAll(List.of(stopped), Map.of(), config(), model(ANSWER), new SessionMerge.Cache(dir), failed::add);
        assertEquals("Wrote the docs", merged.get("dev-2").headline());
        assertEquals(List.of(), asked);
        assertNull(new SessionMerge.Cache(dir).get("dev-2", "2026-10-01T00:00:00Z"), "another incarnation of the name has none");
    }

    @Test
    void aStoppedInstancesKeptReadingShowsOnlyTheMergeOfItsOwnSessions() {
        var failed = new ArrayList<String>();
        // Merged while it ran, of the sessions it had then
        var running = merge(List.of("dev-1"), Map.of("dev-1", guest("Fixed the parser.", "Started on the parser.")),
                model(ANSWER), failed).get("dev-1");
        daemon.instance("dev-1", "container", "Stopped", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.PARENT, "tpl", Metadata.accountKey("claude"), "personal")).createdAt("dev-1", "2026-11-01T08:00:00Z");
        var stopped = instance("dev-1");
        var merger = new SessionMerge.Merger(config(), model("never asked"), new SessionMerge.Cache(dir), failed::add);

        var same = merger.step(stopped, guest("Fixed the parser.", "Started on the parser."));
        assertNull(same.call(), "a stopped instance makes no model call");
        assertEquals(running.headline(), same.known().headline(), "the merge of the sessions its kept reading holds");

        // Stopped after more work: the kept reading holds a session the cached merge never saw
        var newer = merger.step(stopped, guest("Merged PR #12.", "Fixed the parser.", "Started on the parser."));
        assertNull(newer.call(), "still no model call");
        assertNull(newer.known(), "an older merge is not shown for newer sessions: the newest session speaks");
        assertEquals(1, asked.size());

        // Its account is gone since: the merge of the same sessions is still its own
        var noAccount = new SessionMerge.Merger(new SpawnConfig(), model("never asked"), new SessionMerge.Cache(dir), failed::add);
        assertEquals(running.headline(), noAccount.step(stopped, guest("Fixed the parser.", "Started on the parser.")).known().headline(),
                "matched on the sessions, whatever model or account merged them");
    }

    @Test
    void anAnswerOutOfShapeIsNotShownAndNotKept() {
        var failed = new ArrayList<String>();
        var guests = Map.of("dev-1", guest("a", "b"));
        assertEquals(Map.of(), merge(List.of("dev-1"), guests, model("Sure! Here is a summary:\n" + ANSWER), failed));
        assertEquals(1, failed.size(), failed.toString());
        assertNull(new SessionMerge.Cache(dir).get("dev-1", "2026-11-01T08:00:00Z"));
    }

    @Test
    void aFailedCallIsReportedAndTheNewestSessionStays() {
        var failed = new ArrayList<String>();
        SessionMerge.Model down = (config, target, system, question) -> {
            throw new IOException("API error (529, overloaded_error): Overloaded");
        };
        assertEquals(Map.of(), merge(List.of("dev-1"), Map.of("dev-1", guest("a", "b")), down, failed));
        assertEquals(List.of("Could not merge dev-1's sessions: API error (529, overloaded_error): Overloaded"), failed);
    }

    @Test
    void theAnswerIsParsedStrictlyAndScrubbed() {
        var at = Instant.parse("2026-11-03T10:00:00Z");
        assertNull(SessionMerge.parse("Headline: x\nDone: y\nState: z", 2, 2, 0, at), "a missing line");
        assertNull(SessionMerge.parse("Done: y\nHeadline: x\nState: z\nPending: w", 2, 2, 0, at), "out of order");
        assertNull(SessionMerge.parse("Headline: x\nDone: y\nState: z\nPending: w\nPending: v", 2, 2, 0, at), "a line too many");
        assertNull(SessionMerge.parse("Headline:\nDone: y\nState: z\nPending: w", 2, 2, 0, at), "an empty value");
        var token = "gh" + "p_" + "a".repeat(36);
        var merged = SessionMerge.parse("**Headline:** x\n\nDone: pushed with " + token + "\nState: z\nPending: w", 2, 2, 0, at);
        assertEquals("x", merged.headline(), "bold labels are read");
        assertFalse(merged.done().contains(token), merged.done());
    }

    @Test
    void itsSourcesSayHowManySessionsOfHowManyAndWhen() {
        var made = LocalDateTime.of(2026, 11, 3, 10, 0).atZone(ZoneId.systemDefault()).toInstant();
        var now = LocalDateTime.of(2026, 11, 3, 12, 0);
        assertEquals("from 3 sessions, 1 repository, 2h ago",
                new SessionMerge.Merged("h", "d", "s", "p", 3, 3, 1, made).sources(now));
        assertEquals("from 8 of 12 sessions, 2 repositories, 2h ago",
                new SessionMerge.Merged("h", "d", "s", "p", 8, 12, 2, made).sources(now));
    }

    @Test
    void theSummariesOfDestroyedInstancesArePruned() throws Exception {
        var cache = new SessionMerge.Cache(dir);
        var merged = new SessionMerge.Merged("h", "d", "s", "p", 2, 2, 0, Instant.now());
        cache.put("dev-1", "2026-11-01T08:00:00Z", "fp", merged, f -> fail(f));
        cache.put("old", "2026-01-01T00:00:00Z", "fp", merged, f -> fail(f));
        cache.prune(List.of(instance("dev-1")));
        assertNotNull(cache.get("dev-1", "2026-11-01T08:00:00Z"));
        assertNull(cache.get("old", "2026-01-01T00:00:00Z"));
        try (var files = Files.list(dir)) {
            var file = files.findFirst().orElseThrow();
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }
}
