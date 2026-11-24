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
        out.ok("SSH access to isx machines ready");
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
}
