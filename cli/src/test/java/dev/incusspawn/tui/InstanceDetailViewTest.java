package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.config.AccountUsage;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The F3 instance details on their own: which keys they keep, which they hand back, and how they look. */
class InstanceDetailViewTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    /** The clock the details read the "Created:" age against. */
    private static final java.time.LocalDateTime NOW = java.time.LocalDateTime.of(2026, 10, 11, 12, 0);

    private static InstanceDetailView view() {
        return new InstanceDetailView(new ModalRenderer(THEME), THEME, () -> NOW);
    }

    // No creation time here; the VM test pins the "Created:" row against a fixed clock.
    private static InstanceInfo instance() {
        return new InstanceInfo("dev-1", "Running", "default", "default", "",
                "container", "tpl-dev", "4", "8GiB", "20GiB", "10.0.0.5", "", "x86_64",
                "", "", "instance", "", "", "", -1, -1, "", false, null, false, null);
    }

    @Test
    void scrollKeysAreItsOwnAndTheRestAreTheTuis() {
        var view = view();
        assertEquals(InstanceDetailView.Outcome.HANDLED, view.handleKey(KeyEvent.ofKey(KeyCode.DOWN)));
        assertEquals(InstanceDetailView.Outcome.HANDLED, view.handleKey(KeyEvent.ofChar('G')));
        assertEquals(InstanceDetailView.Outcome.CLOSE, view.handleKey(KeyEvent.ofKey(KeyCode.ESCAPE)));
        assertEquals(InstanceDetailView.Outcome.CLOSE, view.handleKey(KeyEvent.ofKey(KeyCode.F3)));
        assertEquals(InstanceDetailView.Outcome.ACCOUNTS, view.handleKey(KeyEvent.ofChar('a')));
        assertEquals(InstanceDetailView.Outcome.SHELL, view.handleKey(KeyEvent.ofKey(KeyCode.F2)));
        assertEquals(InstanceDetailView.Outcome.DEFAULT_ACTION, view.handleKey(KeyEvent.ofKey(KeyCode.ENTER)));
        assertEquals(InstanceDetailView.Outcome.UNHANDLED, view.handleKey(KeyEvent.ofChar('x')));
    }

    @Test
    void rendersAnInstanceWithItsAccounts() {
        var view = view();
        var uses = List.of(new AccountUsage.Use("github", "work", null, "", "", "", ""));
        TuiSnapshot.assertMatches("instance-detail-view-80x24",
                TuiSnapshot.render(80, 24, f -> view.render(f, f.area(), instance(), uses)));
    }

    @Test
    void rendersAVmWithoutTheKvmRowAndWithItsAge() {
        // Created three hours before the pinned clock, so the age reads "3h ago" on any day.
        var vm = new InstanceInfo("vm-1", "Stopped", "default", "default", "2026-10-11T09:00:00",
                "virtual-machine", "tpl-dev", "4", "8GiB", "50GiB", "", "", "x86_64",
                "", "", "instance", "", "", "", -1, -1, "", false, null, false, null);
        TuiSnapshot.assertMatches("instance-detail-view-vm-80x24",
                TuiSnapshot.render(80, 24, f -> view().render(f, f.area(), vm, List.of())));
    }
}
