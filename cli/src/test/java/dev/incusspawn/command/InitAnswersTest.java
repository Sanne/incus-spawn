package dev.incusspawn.command;

import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.RecordingOutput;
import org.aesh.command.option.Option;
import org.aesh.command.option.OptionList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx init} without a terminal (#2075): flags answer the questions that are not secrets,
 * a credential is only ever typed by a person, and the summary says what is left and for whom.
 */
@ExtendWith(IsolatedHome.class)
class InitAnswersTest {

    /** Init with no terminal, its service install faked. */
    static final class NoTerminal extends InitCommand {
        final List<String> installed = new ArrayList<>();

        @Override Prompts prompts() { return null; }
        @Override boolean proxyServiceActive() { return false; }

        @Override
        boolean installProxyService() {
            installed.add("proxy");
            return true;
        }
    }

    /**
     * No option of init carries a secret: each one is named here, so a new option is weighed
     * before it lands. A credential is typed at a terminal, never passed as a flag.
     */
    @Test
    void noOptionOfInitTakesASecret() {
        var names = new TreeSet<String>();
        for (var field : InitCommand.class.getDeclaredFields()) {
            var option = field.getAnnotation(Option.class);
            if (option != null) names.add(option.name());
            var list = field.getAnnotation(OptionList.class);
            if (list != null) names.add(list.name());
        }
        assertEquals(new TreeSet<>(Set.of("plan", "format", "no-sudo", "search-path", "code-dir", "fork-templates",
                "service", "no-service")), names);
    }

    /** Without a terminal no credential is chosen, none is read from anywhere, and the step is reported skipped. */
    @Test
    void withoutATerminalNoCredentialIsTaken() throws Exception {
        var init = new NoTerminal();
        RecordingOutput.during(() -> init.credentialSetup().run().run());
        assertEquals(Set.of("credentials"), init.skipped);
        IsolatedHome.assertNothingSaved();
    }

    /**
     * Init's questions, credentials and approvals included, come only through {@code prompts()},
     * which is {@code Prompts.terminal()}: a console whose stdin is piped never answers them
     * (#2120 review). JDK 25 itself gives no console off a terminal, so a JVM under
     * -Djdk.console=jdk.internal.le cannot be staged here; this pins that no step asks around the gate.
     */
    @Test
    void initAsksOnlyThroughTheTerminalGate() throws Exception {
        for (var file : List.of("InitCommand.java", "HostSetup.java", "CredentialSetup.java")) {
            var source = java.nio.file.Files.readString(Path.of("src/main/java/dev/incusspawn/command", file));
            assertFalse(source.contains("Prompts.console()") || source.contains("System.console()"),
                    file + " asks through a console, not the terminal gate");
        }
        var gate = java.nio.file.Files.readString(Path.of("src/main/java/dev/incusspawn/command/InitCommand.java"));
        assertTrue(gate.matches("(?s).*Prompts prompts\\(\\) \\{[^}]*return Prompts\\.terminal\\(\\);.*"), "prompts() is Prompts.terminal()");
    }

    @Test
    void aFlagAnswersTheDirectoriesWithoutATerminal(@TempDir Path dir) throws Exception {
        var init = new NoTerminal();
        init.searchPathAnswers = List.of(dir.toString());
        init.codeDirAnswers = List.of(dir.toString(), dir.toString());
        init.checkAnswers();
        var steps = init.finalSteps(init.proxyServiceStep(new java.util.concurrent.atomic.AtomicBoolean()));
        RecordingOutput.during(() -> {
            steps.get(0).run().run();
            steps.get(1).run().run();
        });
        var config = SpawnConfig.load();
        assertEquals(List.of(dir.toString()), config.getSearchPaths());
        assertEquals(List.of(dir.toString()), config.getHostPaths());
        assertEquals(Set.of(), init.skipped);
    }

    /** --fork-templates answers Template Search Paths: with its templates among the paths, the step is not skipped. */
    @Test
    void forkTemplatesAnswersTheSearchPaths(@TempDir Path dir) throws Exception {
        var templates = java.nio.file.Files.createDirectories(dir.resolve("incus-spawn-templates"));
        IsolatedHome.seed("searchPaths:\n  - " + templates + "\n");
        var init = new NoTerminal();
        init.forkTemplates = true;
        var steps = init.finalSteps(init.proxyServiceStep(new java.util.concurrent.atomic.AtomicBoolean()));
        RecordingOutput.during(() -> steps.get(0).run().run());
        assertEquals(Set.of(), init.skipped);
    }

    @Test
    void withNoFlagTheDirectoriesAreSkippedAndSaySo() throws Exception {
        var init = new NoTerminal();
        var steps = init.finalSteps(init.proxyServiceStep(new java.util.concurrent.atomic.AtomicBoolean()));
        RecordingOutput.during(() -> steps.get(1).run().run());
        assertEquals(Set.of("code-directories"), init.skipped);
        IsolatedHome.assertNothingSaved();
    }

    @Test
    void theServiceFlagsAnswerItsQuestion() throws Exception {
        java.nio.file.Files.createDirectories(dev.incusspawn.Environment.configDir());
        var yes = new NoTerminal();
        yes.service = true;
        RecordingOutput.during(() -> assertTrue(yes.completeWithProxyService(null)));
        assertEquals(List.of("proxy"), yes.installed);
        var no = new NoTerminal();
        no.noService = true;
        RecordingOutput.during(() -> assertFalse(no.completeWithProxyService(null)));
        assertEquals(List.of(), no.installed);
        assertEquals(Set.of(), no.skipped);
        // Declined, not skipped: the summary says the person's answer was no, so nothing is left there
        assertEquals(Set.of("proxy-service"), no.declined);
        var unanswered = new NoTerminal();
        var said = RecordingOutput.during(() -> unanswered.completeWithProxyService(null));
        assertEquals(Set.of("proxy-service"), unanswered.skipped);
        // The skipped step names the flag, never only 'isx proxy start', which holds an agent's turn (#2081)
        assertTrue(said.lines().stream().anyMatch(l -> l.contains("isx init --service")), said.lines().toString());
        assertEquals(InitCommand.Services.INSTALL_PROXY, unanswered.linuxServices(false));
        assertEquals(InitCommand.Services.START_PROXY, no.linuxServices(false));
        assertEquals(InitCommand.Services.INSTALLED, unanswered.linuxServices(true));
        assertTrue(String.join("\n", InitCommand.nextSteps(InitCommand.Services.INSTALL_PROXY)).contains("isx init --service"));
        // A --no-sudo run names a re-run that asks sudo for nothing
        var withoutSudo = new NoTerminal();
        withoutSudo.noSudo = true;
        var saidWithoutSudo = RecordingOutput.during(() -> withoutSudo.completeWithProxyService(null));
        assertTrue(saidWithoutSudo.lines().stream().anyMatch(l -> l.contains("isx init --no-sudo --service")), saidWithoutSudo.lines().toString());
        assertEquals(InitCommand.Services.INSTALL_PROXY_NO_SUDO, withoutSudo.linuxServices(false));
    }

    /** What is left for the person is what needs sudo, a secret or an approval; the summary adds skipped to the plan's fields. */
    @Test
    void theSummaryNamesWhatIsLeftForThePerson() {
        var credential = new InitPlan.Entry("credentials", "Credential Setup", "", InitPlan.Assessment.of(List.of(
                InitPlan.Change.run("GitHub token", InitPlan.Who.SECRET, "isx account add github"))));
        var paths = new InitPlan.Entry("search-paths", "Template Search Paths", "", new InitPlan.Assessment(
                InitPlan.State.OPTIONAL, true, List.of(InitPlan.Change.write("Add", InitPlan.Who.NONE, "c")), null));
        assertTrue(InitCommand.leftForThePerson(List.of(credential, paths), Set.of(), false));
        assertFalse(InitCommand.leftForThePerson(List.of(paths), Set.of(), false));
        // A root change only root can read: written by a run with sudo, the person's under --no-sudo
        var unread = new InitPlan.Entry("firewall", "Firewall Configuration", "", InitPlan.Assessment.notChecked("Not checked",
                InitPlan.Change.sudo("Add the rule", null).scripted(List.of("firewall-cmd --reload")), List.of()));
        assertFalse(InitCommand.leftForThePerson(List.of(unread), Set.of(), false));
        assertTrue(InitCommand.leftForThePerson(List.of(unread), Set.of(), true));
        var record = InitCommand.summaryRecord(credential, true, false);
        assertEquals(List.copyOf(InitPlan.record(credential).keySet()),
                List.copyOf(record.keySet()).subList(0, record.size() - 2), "the plan's fields, in order, first");
        assertEquals(List.of("skipped", "declined"), List.copyOf(record.keySet()).subList(record.size() - 2, record.size()));
        assertEquals(true, record.get("skipped"));
        assertEquals(List.of("isx account add github"), record.get("commands"));
    }
}
