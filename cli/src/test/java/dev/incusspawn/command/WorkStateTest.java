package dev.incusspawn.command;

import dev.incusspawn.command.WorkState.State;
import dev.incusspawn.proxy.EventLogReader.PullRequest;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What an instance's clones hold (#2241). The overview tells a person whether destroying an
 * instance loses work, so every answer that is not exactly the script's is unknown, never clean.
 */
class WorkStateTest {

    private static final String H = WorkState.HEADER + "\n";

    @Test
    void cleanOnlyWhenEveryCloneWasReadAndHoldsNothing() {
        var clean = WorkState.parse(0, H + "repo 0 0 0 app\nrepo 0 0 0 src/lib\nend\n", false);
        assertEquals(State.CLEAN, clean.state());
        assertEquals("clean", clean.cell());
        assertEquals(List.of("app", "src/lib"), clean.repos().stream().map(WorkState.Repo::path).toList());
        assertEquals(State.NO_REPOS, WorkState.parse(0, H + "end\n", false).state());
    }

    @Test
    void workInAnyCloneIsUnsavedAndCounted() {
        var held = WorkState.parse(0, H + "repo 2 0 1 app\nrepo 0 5 0 lib\nend\n", false);
        assertEquals(State.UNSAVED, held.state());
        assertEquals("2 uncommitted, 5 unpushed, 1 stash", held.cell());
        assertEquals(List.of("app", "lib"), held.unsavedRepos());
    }

    @Test
    void whatTheReadCouldNotSeeIsNeverClean() {
        assertEquals(State.UNKNOWN, WorkState.parse(0, H + "repo 0 x 0 app\nend\n", false).state(), "Git refused a count");
        assertEquals(State.UNKNOWN, WorkState.parse(0, H + "repo 0 0 0 app\nmore\nend\n", false).state(), "past the cap");
        assertEquals(State.UNKNOWN, WorkState.parse(0, H + "repo 0 0 0 app\n", false).state(), "cut short");
        assertEquals(State.UNKNOWN, WorkState.parse(0, "repo 0 0 0 app\nend\n", false).state(), "no header");
        assertEquals(State.UNKNOWN, WorkState.parse(0, H + "repo 0 0 0 app\nall pushed\nend\n", false).state(), "a stray line");
        assertEquals(State.UNKNOWN, WorkState.parse(0, H + "repo -1 0 0 app\nend\n", false).state(), "not a count");
        assertEquals(State.UNKNOWN, WorkState.parse(0, H + "repo 99999999999 0 0 app\nend\n", false).state(), "too large");
        assertEquals(State.UNKNOWN, WorkState.parse(1, H + "end\n", false).state(), "a failed exit");
        assertEquals(State.UNKNOWN, WorkState.parse(0, H + "end\n", true).state(), "too long");
        assertNull(WorkState.parse(1, "", false).uncommitted(), "no count when nothing was read");
        var partly = WorkState.parse(0, H + "repo 1 0 0 app\nrepo x x x lib\nend\n", false);
        assertEquals(State.UNSAVED, partly.state(), "work found is work, whatever else could not be read");
        assertEquals("1 uncommitted, more unknown", partly.cell());
        var oneCountRefused = WorkState.parse(0, H + "repo 3 x 0 app\nend\n", false);
        assertEquals(State.UNSAVED, oneCountRefused.state());
        assertEquals(3, oneCountRefused.uncommitted(), "a refused count does not drop the clone's others");
        assertEquals("3 uncommitted, more unknown", oneCountRefused.cell());
    }

    @Test
    void aStoppedInstanceIsNotRead() {
        assertEquals("not read", WorkState.NOT_READ.cell());
        assertNull(WorkState.NOT_READ.unpushed());
    }

    @Test
    void theOutputKeptIsBounded() {
        var out = new WorkState.Bounded(4);
        out.write("abcdef".getBytes(StandardCharsets.UTF_8), 0, 6);
        assertTrue(out.cut);
        assertEquals("abcd", out.text());
    }

    /** The script itself, against real clones: what it counts and that it changes nothing. */
    @Test
    void theScriptCountsUncommittedUnpushedAndStashedWork(@TempDir Path home) throws Exception {
        assumeTrue(run(home, "git", "--version") == 0, "needs git");
        Files.createDirectories(home.resolve("remote.git"));
        git(home.resolve("remote.git"), "init", "-q", "--bare");
        var clean = Files.createDirectories(home.resolve("clean"));
        git(clean, "init", "-q");
        git(clean, "commit", "-q", "--allow-empty", "-m", "a");
        git(clean, "remote", "add", "origin", home.resolve("remote.git").toString());
        git(clean, "push", "-q", "origin", "HEAD:refs/heads/main");
        git(clean, "fetch", "-q", "origin");
        var dirty = Files.createDirectories(home.resolve("src/dirty"));
        git(dirty, "init", "-q");
        Files.writeString(dirty.resolve("f"), "x");
        git(dirty, "add", "f");
        git(dirty, "commit", "-q", "-m", "a");
        Files.writeString(dirty.resolve("f"), "y");
        git(dirty, "stash", "-q");
        Files.writeString(dirty.resolve("g"), "z");
        git(dirty, "checkout", "-q", "--detach");
        git(dirty, "commit", "-q", "--allow-empty", "-m", "detached");
        var pinned = Files.createDirectories(home.resolve("pinned"));
        git(pinned, "init", "-q");
        git(pinned, "commit", "-q", "--allow-empty", "-m", "release");
        git(pinned, "tag", "v1");
        git(pinned, "checkout", "-q", "--detach", "v1");
        git(pinned, "branch", "-q", "-D", "main");
        // As RepoCloner leaves a shallow tag pin: no remote-tracking branch, only the tag's own refspec
        git(pinned, "remote", "add", "origin", home.resolve("remote.git").toString());
        git(pinned, "config", "remote.origin.fetch", "+refs/tags/v1:refs/tags/v1");
        // The same pin whose tag the agent deleted: the refspec names a ref the clone no longer has
        var untagged = Files.createDirectories(home.resolve("untagged"));
        git(untagged, "init", "-q");
        git(untagged, "remote", "add", "origin", home.resolve("remote.git").toString());
        git(untagged, "config", "remote.origin.fetch", "+refs/tags/v1:refs/tags/v1");
        git(untagged, "commit", "-q", "--allow-empty", "-m", "unpushed");
        // A full tag pin: cloned from a snapshot that holds the tag alone, broad refspec, no remote-tracking branch
        var source = Files.createDirectories(home.resolve(".snapshot-src"));
        git(source, "init", "-q");
        git(source, "commit", "-q", "--allow-empty", "-m", "release");
        git(source, "tag", "v2");
        git(home, "clone", "-q", "--bare", source.toString(), home.resolve(".snapshot.git").toString());
        git(home.resolve(".snapshot.git"), "update-ref", "-d", "refs/heads/main");
        git(home, "clone", "-q", "--branch", "v2", home.resolve(".snapshot.git").toString(), home.resolve("fullpin").toString());
        var tagged = Files.createDirectories(home.resolve("tagged"));
        git(tagged, "init", "-q");
        git(tagged, "commit", "-q", "--allow-empty", "-m", "a");
        git(tagged, "remote", "add", "origin", home.resolve("remote.git").toString());
        git(tagged, "push", "-q", "origin", "HEAD:refs/heads/other");
        git(tagged, "fetch", "-q", "origin");
        git(tagged, "commit", "-q", "--allow-empty", "-m", "unpushed");
        git(tagged, "tag", "local-only");
        // A host-source clone: no remote, the exported tip kept under refs/isx/host (#2273)
        var hosted = Files.createDirectories(home.resolve("hosted"));
        git(hosted, "init", "-q");
        git(hosted, "commit", "-q", "--allow-empty", "-m", "from the host");
        git(hosted, "update-ref", "refs/isx/host/main", "HEAD");
        var index = Files.getLastModifiedTime(dirty.resolve(".git/index"));

        var out = new ByteArrayOutputStream();
        var process = new ProcessBuilder("sh", "-c", WorkState.SCRIPT).directory(home.toFile());
        process.environment().put("HOME", home.toString());
        var p = process.redirectErrorStream(true).start();
        p.getInputStream().transferTo(out);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        var state = WorkState.parse(p.exitValue(), out.toString(StandardCharsets.UTF_8), false);

        assertEquals(State.UNSAVED, state.state(), out.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(new WorkState.Repo("clean", 0, 0, 0), new WorkState.Repo("fullpin", 0, 0, 0), new WorkState.Repo("hosted", 0, 0, 0),
                        new WorkState.Repo("pinned", 0, 0, 0),
                        new WorkState.Repo("tagged", 0, 1, 0), new WorkState.Repo("untagged", 0, 1, 0),
                        new WorkState.Repo("src/dirty", 1, 2, 1)), state.repos(),
                "the detached commit and the branch's are both unpushed; a clone pinned to a tag, shallow or full, is not;"
                        + " a local tag on an unpushed commit does not save it; a named tag the clone lost is skipped,"
                        + " not a failed count; the bare remote is no clone");
        assertEquals(index, Files.getLastModifiedTime(dirty.resolve(".git/index")), "the read wrote nothing");
    }

    @Test
    void gitHubIsAskedWithVariablesNeverWithTheGuestsTextInTheQuery() throws Exception {
        var body = JqValues.parseStrict(PullRequestStates.body(List.of(
                new PullRequest("o/r", 12), new PullRequest("x/y\") { viewer { login } } #", 3))));
        var query = body.pathText("query", "");
        assertFalse(query.contains("viewer"), query);
        assertEquals("o", body.path("variables").pathText("o0", ""));
        assertEquals(12, body.path("variables").path("p0").asLong(0));
        var answer = JqValues.parseStrict("""
                {"data":{"p0":{"pullRequest":{"state":"MERGED"}},"p1":null}}""");
        var states = PullRequestStates.parse(List.of(new PullRequest("o/r", 12), new PullRequest("o/r", 13)), answer);
        assertEquals(java.util.Map.of(new PullRequest("o/r", 12), PullRequestStates.State.MERGED), states,
                "one GitHub could not see is left out, so it shows as unknown");
        var refused = assertThrows(java.io.IOException.class, () -> PullRequestStates.parse(List.of(new PullRequest("o/r", 12)),
                JqValues.parseStrict("{\"errors\":[{\"message\":\"API rate limit exceeded\"}]}")));
        assertEquals("GitHub refused: API rate limit exceeded", refused.getMessage(), "a refused request is said, not shown as unseen");
    }

    private static void git(Path dir, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t",
                "-c", "init.defaultBranch=main", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        assertEquals(0, run(dir, command.toArray(String[]::new)), String.join(" ", args));
    }

    private static int run(Path dir, String... command) throws Exception {
        var p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        return p.waitFor(30, TimeUnit.SECONDS) ? p.exitValue() : -1;
    }
}
