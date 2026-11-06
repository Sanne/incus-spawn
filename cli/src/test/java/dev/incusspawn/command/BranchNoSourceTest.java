package dev.incusspawn.command;

import dev.incusspawn.util.RecordingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code isx branch <name>} with nothing to branch from says what there is to branch from (#1441). */
class BranchNoSourceTest {

    private static RecordingOutput.Event reported(List<String> built) {
        var errors = RecordingOutput.during(() -> BranchCommand.reportNoSource(built)).events(RecordingOutput.Kind.ERROR);
        assertEquals(1, errors.size(), errors.toString());
        return errors.getFirst();
    }

    @Test
    void itListsTheBuiltTemplates() {
        var error = reported(List.of("tpl-minimal", "tpl-agent"));
        assertTrue(error.msg().contains("incus-spawn.yaml (a project's definition)"), error.msg());
        assertEquals(List.of("Usage: isx branch <name> --from <template>", "Built templates: tpl-minimal, tpl-agent."),
                error.details());
    }

    @Test
    void withNoneBuiltItSaysHowToBuildOne() {
        var error = reported(List.of());
        assertTrue(error.details().getLast().startsWith("No template is built yet: 'isx build tpl-minimal'"), error.details().toString());
    }

    @Test
    void whenIncusCannotSayItStillGivesTheUsage() {
        assertEquals(List.of("Usage: isx branch <name> --from <template>"), reported(null).details());
    }
}
