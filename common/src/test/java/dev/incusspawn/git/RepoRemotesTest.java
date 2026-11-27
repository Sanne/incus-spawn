package dev.incusspawn.git;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.Jjq;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A repo's {@code remote:} and {@code remotes:} (#343): their validation, and the guest scripts,
 * run in a real shell against real Git repositories.
 */
class RepoRemotesTest {

    private static ImageDef.RepoEntry repo(String yaml) throws Exception {
        return Jjq.imageDef("name: tpl-a\nrepos:\n  - " + yaml.replace("\n", "\n    ") + "\n").getRepos().getFirst();
    }

    @Test
    void anEntryNamesItsRemoteAndOthersBesideIt() throws Exception {
        var repo = repo("""
                url: https://git.example.com/example-org/example-repo.git
                remote: upstream
                remotes:
                  origin: https://git.example.com/{github.login}/example-repo.git
                  colleague: https://git.example.org/example-user/example-repo.git""");

        assertEquals("upstream", repo.remoteName());
        assertEquals(Map.of("origin", "https://git.example.com/{github.login}/example-repo.git",
                "colleague", "https://git.example.org/example-user/example-repo.git"), repo.getRemotes());
        assertNull(ImageDef.repoProblem(repo, false));
        assertTrue(repo.unknownKeys().isEmpty(), repo.unknownKeys().toString());
    }

    @Test
    void anEntryWithoutThemKeepsOrigin() throws Exception {
        var repo = repo("url: https://git.example.com/example-org/example-repo.git");

        assertEquals("origin", repo.remoteName());
        assertNull(RepoRemotes.cloneScript(repo, "/home/agentuser/quarkus"));
    }

    @Test
    void whatCannotBeSetUpIsRefused() throws Exception {
        var refusals = Map.of(
                "url: https://example.com/a.git\nremote: -x", "'remote'",
                "url: https://example.com/a.git\nremote: a/b", "'remote'",
                "url: https://example.com/a.git\nremotes:\n  origin: https://example.com/b.git", "clone's own remote",
                "url: https://example.com/a.git\nremote: up\nremotes:\n  up: https://example.com/b.git", "clone's own remote",
                "url: https://example.com/a.git\nremotes:\n  f: https://example.com/{repo}.git", "only placeholder",
                "url: https://example.com/a.git\nremotes:\n  f: ext::sh -c touch% /tmp/x", "network URL",
                "url: https://example.com/a.git\nremotes:\n  f: /srv/git/a.git", "network URL",
                "url: https://example.com/a.git\nremotes:\n  f: ''", "no URL",
                "host-source: /srv/a\nremote: upstream", "host-source");
        for (var refusal : refusals.entrySet()) {
            var problem = ImageDef.repoProblem(repo(refusal.getKey()), false);
            assertNotNull(problem, refusal.getKey());
            assertTrue(problem.contains(refusal.getValue()), refusal.getKey() + " -> " + problem);
        }
    }

    @Test
    void aHostSourceMayAddRemotes() throws Exception {
        var repo = repo("host-source: /srv/a\nremotes:\n  origin: https://example.com/a.git");

        assertNull(ImageDef.repoProblem(repo, false));
        assertEquals("git -C '/home/agentuser/a' remote add 'origin' 'https://example.com/a.git'"
                        + " && git -C '/home/agentuser/a' config 'remote.origin.skipFetchAll' true",
                RepoRemotes.cloneScript(repo, "/home/agentuser/a"));
    }

    @Test
    void aGitHubLoginIsOnlyWhatGitHubAllows() {
        // Every login here is fictional: only its shape is under test
        assertTrue(RepoRemotes.validLogin("example-user-bot"));
        assertTrue(RepoRemotes.validLogin("example_acme"), "an Enterprise Managed User's handle_shortcode");
        assertTrue(RepoRemotes.validLogin("a".repeat(39)));
        assertFalse(RepoRemotes.validLogin("a".repeat(40)), "GitHub allows at most 39 characters");
        assertFalse(RepoRemotes.validLogin("a-".repeat(20) + "a"));
        assertTrue(RepoRemotes.validLogin("old--login-"), "older accounts have doubled and trailing hyphens");
        assertFalse(RepoRemotes.validLogin("-a"));
        assertFalse(RepoRemotes.validLogin("a'b"));
        assertFalse(RepoRemotes.validLogin(""));
        assertThrows(IllegalArgumentException.class, () -> RepoRemotes.fillScript("", "a;b"));
    }

    @Test
    void theCloneIsRenamedAndGetsTheFixedRemotesNeverFetched(@TempDir Path dir) throws Exception {
        var clone = dir.resolve("quarkus");
        git(dir, "init", "-q", clone.toString());
        git(clone, "remote", "add", "origin", "https://git.example.com/example-org/example-repo.git");
        var repo = repo("""
                url: https://git.example.com/example-org/example-repo.git
                remote: upstream
                remotes:
                  origin: https://git.example.com/{github.login}/example-repo.git
                  colleague: https://git.example.org/example-user/example-repo.git""");

        sh(RepoRemotes.cloneScript(repo, clone.toString()));

        assertEquals("colleague\nupstream", git(clone, "remote").strip());
        assertEquals("https://git.example.com/example-org/example-repo.git", git(clone, "remote", "get-url", "upstream").strip());
        assertEquals("true", git(clone, "config", "remote.colleague.skipFetchAll").strip());
    }

    @Test
    void theLoginRemotesFollowTheAccountAndGoWithoutOne(@TempDir Path dir) throws Exception {
        var clone = dir.resolve("home/quarkus");
        git(dir, "init", "-q", clone.toString());
        var remotes = RepoRemotes.loginRemotes(List.of(repo("""
                url: https://git.example.com/example-org/example-repo.git
                path: ~/quarkus
                remotes:
                  fork: https://git.example.com/{github.login}/{github.login}-example-repo.git""")),
                path -> path.replace("~", dir.resolve("home").toString()));
        var root = dir.resolve("root").toString();
        sh(RepoRemotes.recordScript(root, remotes));

        var first = sh(RepoRemotes.fillScript(root, "example-bot"));
        assertEquals("https://git.example.com/example-bot/example-bot-example-repo.git", git(clone, "remote", "get-url", "fork").strip());
        assertEquals("true", git(clone, "config", "remote.fork.skipFetchAll").strip());
        assertTrue(first.contains(RepoRemotes.CHANGED + clone + "\tfork"), first);

        assertEquals("", sh(RepoRemotes.fillScript(root, "example-bot")), "nothing changes for the same login");

        sh(RepoRemotes.fillScript(root, "other"));
        assertEquals("https://git.example.com/other/other-example-repo.git", git(clone, "remote", "get-url", "fork").strip());

        git(clone, "config", "branch.feature.remote", "fork");
        sh(RepoRemotes.fillScript(root, null));
        assertEquals("https://git.example.com/{github.login}/{github.login}-example-repo.git", git(clone, "remote", "get-url", "fork").strip(),
                "without a login, the remote names no other account's fork");
        assertEquals("fork", git(clone, "config", "branch.feature.remote").strip(), "and branches keep tracking it");

        sh(RepoRemotes.fillScript(root, "example-bot"));
        assertEquals("https://git.example.com/example-bot/example-bot-example-repo.git", git(clone, "remote", "get-url", "fork").strip());

        sh(RepoRemotes.recordScript(root, List.of()));
        assertFalse(Files.exists(Path.of(root + RepoRemotes.RECORD)));
        assertEquals("", sh(RepoRemotes.fillScript(root, "example-bot")), "no record, nothing to fill");
    }

    @Test
    void aGoneCloneIsSkipped(@TempDir Path dir) throws Exception {
        var root = dir.resolve("root").toString();
        sh(RepoRemotes.recordScript(root, List.of(
                new RepoRemotes.LoginRemote(dir.resolve("gone").toString(), "fork", "https://github.com/{github.login}/a.git"))));

        assertEquals("", sh(RepoRemotes.fillScript(root, "someone")));
    }

    private static String git(Path dir, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        return run(command);
    }

    private static String sh(String script) throws Exception {
        return run(List.of("sh", "-c", script));
    }

    private static String run(List<String> command) throws Exception {
        var pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        var process = pb.start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", command) + "\n" + out);
        return out;
    }
}
