package dev.incusspawn.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.incusspawn.util.RecordingOutput.Kind.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The first run's block of setup: outcomes on the screen, details in the log (#2528). */
class DetailsLoggedOutputTest {

    private final RecordingOutput terminal = new RecordingOutput();
    private final List<String> log = new ArrayList<>();
    private final Output out = Output.detailsLogged(terminal, log::add);

    private List<RecordingOutput.Event> shown() {
        return terminal.events();
    }

    @Test
    void aStepBeforeItsOutcomeIsLoggedAndNotShown() {
        out.step("subuid/subgid configured.");
        out.ok("The isx proxy is set up");

        assertEquals(List.of(new RecordingOutput.Event(OK, "The isx proxy is set up")), shown());
        assertEquals(List.of("subuid/subgid configured."), log);
    }

    @Test
    void theDetailsAreShownBeforeAWarningThatFollowsThem() {
        out.step("Incus already initialized.");
        out.warn("Storage & Network failed");

        assertEquals(List.of(new RecordingOutput.Event(STEP, "Incus already initialized."),
                new RecordingOutput.Event(WARN, "Storage & Network failed")), shown());
    }

    @Test
    void theDetailsAreShownBeforeAQuestion() {
        out.step("The bridge's subnet is also a VPN's.");
        out.prompt("Move the bridge? (Y/n): ");

        assertEquals(List.of(STEP, PROMPT), shown().stream().map(RecordingOutput.Event::kind).toList());
    }

    @Test
    void anOutcomeForgetsTheDetailsBeforeIt() {
        out.step("Incus is installed.");
        out.ok("SSH access to instances ready");
        out.warn("Proxy service did not start");

        assertEquals(List.of(OK, WARN), shown().stream().map(RecordingOutput.Event::kind).toList());
    }

    @Test
    void aNoteRightUnderAnOutcomeIsShownWithIt() {
        out.ok("Certificate created for the isx proxy");
        out.stepNote("The proxy uses it to open the HTTPS requests.");
        out.step("Installed git remote helper: /usr/local/bin/git-remote-isx");
        out.stepNote("a detail of the step above");

        assertEquals(List.of(OK, STEP_NOTE), shown().stream().map(RecordingOutput.Event::kind).toList());
    }

    @Test
    void aFailedLiveStepShowsWhatItsCommandPrinted() {
        out.step("Incus already initialized.");
        out.stepStart("Starting Incus");
        out.stepNote("Job for incus.service failed");
        out.abandonStep();

        assertEquals(List.of(STEP_START, STEP_NOTE), shown().stream()
                .map(RecordingOutput.Event::kind).filter(k -> k != ABANDON_STEP).toList());
    }

    @Test
    void aQuestionKeepsTheBlankLinesAndTheGapBeforeIt() {
        out.step("The bridge's subnet is also a VPN's.");
        out.gap();
        out.step("");
        out.prompt("Choice: ");

        assertEquals(List.of(STEP, GAP, STEP, PROMPT), shown().stream().map(RecordingOutput.Event::kind).toList());
        assertEquals(List.of("The bridge's subnet is also a VPN's."), log);
    }

    /**
     * A failure's diagnostics go to the log and are not replayed (#2528): a failed build's
     * container status and LXC log, a note anywhere else, came back before the warning that
     * names the saved report.
     */
    @Test
    void aFailuresDetailsAreLoggedAndNeverReplayedBeforeAWarning() {
        out.failureDetail("  Container status: Running");
        out.failureDetail("  LXC log (last 8 lines):");
        out.error("Build failed for tpl-minimal: Operation failed", "exit status 1");
        out.warn("The failure report is in build-failures/tpl-minimal.log.");

        assertEquals(List.of(new RecordingOutput.Event(ERROR, "Build failed for tpl-minimal: Operation failed", List.of("exit status 1")),
                new RecordingOutput.Event(WARN, "The failure report is in build-failures/tpl-minimal.log.")), shown());
        assertEquals(List.of("Container status: Running", "LXC log (last 8 lines):"), log);
    }

    /** An output that shows everything draws a failure's detail as the note it always was. */
    @Test
    void aFailureDetailIsANoteWhereThePersonReadsTheWholeReport() {
        var notes = new ArrayList<String>();
        new Output() {
            @Override public void warn(String msg, String... details) { }
            @Override public void note(String msg) { notes.add(msg); }
        }.failureDetail("  Container status: Running");
        assertEquals(List.of("  Container status: Running"), notes);
    }
}
