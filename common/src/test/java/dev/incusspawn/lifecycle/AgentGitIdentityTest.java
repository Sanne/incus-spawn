package dev.incusspawn.lifecycle;

import dev.incusspawn.config.GitIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fallback Git identity of a build (#2410): written to {@code /etc/gitconfig}, so the GitHub
 * account's identity in {@code ~/.gitconfig} wins wherever there is one.
 */
class AgentGitIdentityTest {

    private static final GitIdentity JANE = new GitIdentity("Jane Doe", "jane@example.org");

    @TempDir
    Path dir;

    @Test
    void theFallbackIsUsedWhereNoAccountGaveAnIdentity() throws Exception {
        var effective = run(JANE);
        assertEquals(new AgentGitIdentity.Effective(JANE, AgentGitIdentity.Source.CONFIG), effective);
        assertEquals("Agents commit as Jane Doe <jane@example.org>, from the git-identity in config.yaml",
                AgentGitIdentity.report(effective));
    }

    @Test
    void theAccountsIdentityWinsOverTheFallback() throws Exception {
        Files.writeString(home().resolve(".gitconfig"), "[user]\n\tname = agent-bot\n\temail = bot@example.com\n");
        var effective = run(JANE);
        assertEquals(new AgentGitIdentity.Effective(new GitIdentity("agent-bot", "bot@example.com"),
                AgentGitIdentity.Source.USER), effective);
        // Still written, for when the account's identity goes
        assertTrue(Files.readString(system()).contains("Jane Doe"));
    }

    @Test
    void aRemovedKeyTakesIsxsFallbackOutButNotATemplatesOwn() throws Exception {
        run(JANE);
        assertNull(run(null));
        assertFalse(Files.readString(system()).contains("Jane"));

        Files.writeString(system(), "[user]\n\tname = Template\n\temail = t@example.org\n");
        var effective = run(null);
        // A template's own, never named as config.yaml's
        assertEquals(new AgentGitIdentity.Effective(new GitIdentity("Template", "t@example.org"),
                AgentGitIdentity.Source.OTHER), effective);
        assertTrue(Files.readString(system()).contains("Template"));

        // Nor overwritten while the key is set
        assertEquals(new AgentGitIdentity.Effective(new GitIdentity("Template", "t@example.org"),
                AgentGitIdentity.Source.OTHER), run(JANE));
        assertFalse(Files.readString(system()).contains("Jane"));
    }

    @Test
    void aNameIsWrittenAsItIsNeverRunByTheShell() throws Exception {
        var hostile = new GitIdentity("$(touch pwned) 'x' \"y\"", "a;b@example.org");
        assertEquals(hostile, run(hostile).identity());
        assertFalse(Files.exists(dir.resolve("pwned")));
    }

    @Test
    void aGitHubAccountTheTemplateDoesNotUseIsNotSaidToBeMissing() {
        var without = AgentGitIdentity.report(null, null, false, false);
        assertTrue(without.contains("there is no GitHub account"), without);
        var with = AgentGitIdentity.report(null, null, false, true);
        assertTrue(with.contains("your GitHub account gave it none"), with);
        assertFalse(with.contains("no GitHub account"), with);
    }

    @Test
    void noIdentityIsSaidAsNone() {
        assertNull(AgentGitIdentity.parse("git\n"));
        assertNull(AgentGitIdentity.parse("git\nfile:/etc/gitconfig\tJane\n"));
        assertTrue(AgentGitIdentity.report(null).startsWith("Agents have no Git identity"));
    }

    @Test
    void theOriginNamesTheSource() {
        assertEquals(AgentGitIdentity.Source.CONFIG, AgentGitIdentity.sourceOf("file:/etc/gitconfig"));
        assertEquals(AgentGitIdentity.Source.USER, AgentGitIdentity.sourceOf("file:/home/agentuser/.gitconfig"));
        assertEquals(AgentGitIdentity.Source.OTHER, AgentGitIdentity.sourceOf("file:/home/agentuser/.config/git/config"));
    }

    /**
     * A failed write fails the script, so the build fails rather than shipping a template with no
     * author: a stub git refuses the email, and the exit is not 0 (review of #2430).
     */
    @Test
    void aFailedWriteFailsTheScript() throws Exception {
        var bin = Files.createDirectories(dir.resolve("bin"));
        var git = bin.resolve("git");
        // Unset for every read; the email write fails; every other write succeeds
        Files.writeString(git, """
                #!/bin/sh
                for a in "$@"; do [ "$a" = --get ] && exit 1; done
                for a in "$@"; do [ "$a" = user.email ] && exit 1; done
                exit 0
                """);
        assertTrue(git.toFile().setExecutable(true));
        var process = new ProcessBuilder("sh", "-c", AgentGitIdentity.script(JANE)).directory(dir.toFile())
                .redirectErrorStream(true);
        process.environment().put("PATH", bin + ":/usr/bin:/bin");
        var started = process.start();
        var out = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertNotEquals(0, started.waitFor(), out);
        assertFalse(out.contains("git\n"), "nothing is reported after a failed write: " + out);
    }

    /** A template's own email alone is kept, never overwritten and later removed, and the report says why. */
    @Test
    void aTemplatesOwnEmailIsLeftAlone() throws Exception {
        Files.writeString(system(), "[user]\n\temail = t@example.org\n");
        assertNull(run(JANE));
        assertFalse(Files.readString(system()).contains("Jane"));
        assertNull(run(null));
        assertTrue(Files.readString(system()).contains("t@example.org"));
        var script = AgentGitIdentity.script(JANE).replace("'" + AgentGitIdentity.SYSTEM_FILE + "'", "'" + system() + "'");
        var process = new ProcessBuilder("sh", "-c", script).directory(dir.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), out);
        assertTrue(AgentGitIdentity.keptTemplatesOwn(out), out);
        var report = AgentGitIdentity.report(null, JANE, true, false);
        assertTrue(report.contains("was not written"), report);
        // Written, but emptied by another file: not said to be the template's
        assertFalse(AgentGitIdentity.report(null, JANE, false, false).contains("was not written"));
    }

    /** The identity is the guest's text, and reaches the host's terminal on one line. */
    @Test
    void theGuestsIdentityIsReportedOnOneLine() {
        var effective = AgentGitIdentity.parse("git\nfile:/etc/gitconfig\tEvil\u001b[2J\rName\nfile:/etc/gitconfig\te@x.org\nisx\n");
        var line = AgentGitIdentity.report(effective);
        assertTrue(line.chars().noneMatch(Character::isISOControl), line);
    }

    private Path home() throws Exception {
        return Files.createDirectories(dir.resolve("home"));
    }

    private Path system() {
        return dir.resolve("gitconfig");
    }

    /**
     * The guest script, as root would run it, with its two paths moved under {@link #dir}: the
     * script's own text names them only as the constants it is built from.
     */
    private AgentGitIdentity.Effective run(GitIdentity identity) throws Exception {
        var script = AgentGitIdentity.script(identity)
                .replace("'" + AgentGitIdentity.SYSTEM_FILE + "'", "'" + system() + "'")
                .replace("HOME=" + AgentGitIdentity.AGENT_HOME, "HOME=" + home() + " XDG_CONFIG_HOME=" + home() + "/.config GIT_CONFIG_SYSTEM=" + system());
        var process = new ProcessBuilder("sh", "-c", script).directory(dir.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), out);
        // The real file's origin, as the guest reports it
        return AgentGitIdentity.parse(out.replace("file:" + system(), "file:" + AgentGitIdentity.SYSTEM_FILE)
                .replace("file:" + home() + "/.gitconfig", "file:" + AgentGitIdentity.AGENT_HOME + "/.gitconfig"));
    }
}
