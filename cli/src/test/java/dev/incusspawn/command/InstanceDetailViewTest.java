package dev.incusspawn.command;

import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.config.AccountUsage;
import dev.incusspawn.tui.TuiSnapshot;
import dev.incusspawn.tui.TuiTheme;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The F3 instance details on their own: which keys they keep, which they hand back, and how they look. */
class InstanceDetailViewTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    private static InstanceDetailView view() {
        return new InstanceDetailView(new ModalRenderer(THEME), THEME);
    }

    // No creation time: the dialog would show its age, which moves with the clock.
    private static InstanceInfo instance() {
        return new InstanceInfo("dev-1", "Running", "default", "default", "",
                "container", "tpl-dev", "4", "8GiB", "20GiB", "10.0.0.5", "", "x86_64",
                "", "", "instance", "", "", "", -1, -1, "", false, null, false);
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
}
