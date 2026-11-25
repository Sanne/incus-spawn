package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How {@code isx doctor} offers a remediation under the issue it fixes (#1703). */
class DoctorRemediationOutputTest {

    /** Advice with nothing to run is a note under the issue, not a line indented by hand. */
    @Test
    void adviceIsANoteUnderTheIssue() {
        var recorded = RecordingOutput.during(() -> DoctorCommand.applyOrSuggest(
                new DoctorCommand.Remediation("Run 'isx init' to configure credentials", false, null), null));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.STEP_NOTE,
                "Run 'isx init' to configure credentials")), recorded.events());
    }

    /** Without a terminal to ask on, a fix that could run says how to apply it, and runs nothing. */
    @Test
    void aFixIsNotAppliedWithoutATerminal() {
        var ran = new boolean[1];
        var recorded = RecordingOutput.during(() -> DoctorCommand.applyOrSuggest(
                new DoctorCommand.Remediation("Restart the proxy", false, () -> ran[0] = true), null));
        assertEquals(List.of(new RecordingOutput.Event(RecordingOutput.Kind.STEP_NOTE,
                "Restart the proxy: isx offers to do this when you run 'isx doctor' in a terminal")), recorded.events());
        assertEquals(false, ran[0]);
    }
}
