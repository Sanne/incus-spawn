package dev.incusspawn.tui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.tool.ToolSetup;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

/** The TUI's credential account dialog: what it offers, and what applying it would change. */
class AccountsModalTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    private static final AccountsModal.Row CLAUDE = new AccountsModal.Row("claude",
            List.of("personal", "acme"), "personal", "",
            Map.of("personal", "Claude Pro/Max OAuth token", "acme", "Anthropic API key"));
    private static final AccountsModal.Row GITHUB = new AccountsModal.Row("github",
            List.of("me", "bot"), "me", "bot", Map.of("me", "", "bot", "commits as bot@acme.example"));

    private static AccountsModal modal(AccountsModal.Row... rows) {
        return new AccountsModal(new ModalRenderer(THEME), THEME, "review-1", List.of(rows));
    }

    private static AccountsModal.Outcome press(AccountsModal modal, KeyEvent... keys) {
        var outcome = AccountsModal.Outcome.STAY;
        for (var key : keys) outcome = modal.handleKey(key);
        return outcome;
    }

    private static final KeyEvent DOWN = KeyEvent.ofKey(KeyCode.DOWN);
    private static final KeyEvent RIGHT = KeyEvent.ofKey(KeyCode.RIGHT);
    private static final KeyEvent LEFT = KeyEvent.ofKey(KeyCode.LEFT);
    private static final KeyEvent ENTER = KeyEvent.ofKey(KeyCode.ENTER);

    @Test
    void opensOnWhatTheInstanceUsesAndChangesNothing() {
        var modal = modal(CLAUDE, GITHUB);
        assertTrue(modal.changes().isEmpty());
        assertEquals(AccountsModal.Outcome.CLOSE, press(modal, ENTER), "nothing to apply just closes");
    }

    @Test
    void pinningAnotherAccount() {
        var modal = modal(CLAUDE, GITHUB);
        assertEquals(AccountsModal.Outcome.APPLY, press(modal, RIGHT, RIGHT, ENTER));
        assertEquals(Map.of("claude", "acme"), modal.changes(), "only the row that changed");
    }

    @Test
    void choosingTheDefaultOptionUnpins() {
        var modal = modal(CLAUDE, GITHUB);
        // github is pinned to 'bot' (option 2); two steps left reach "default (me)".
        press(modal, DOWN, LEFT, LEFT);
        var expected = new HashMap<String, String>();
        expected.put("github", null);
        assertEquals(expected, modal.changes());
    }

    /** Pinning the account that is the default is a different choice from following it. */
    @Test
    void pinningTheDefaultAccountIsAChange() {
        var modal = modal(CLAUDE);
        press(modal, RIGHT);
        assertEquals(Map.of("claude", "personal"), modal.changes());
    }

    /** A pin to an account that is gone is shown as it is, and leaving it alone changes nothing. */
    @Test
    void aDanglingPinIsKeptSelectable() {
        var modal = modal(new AccountsModal.Row("github", List.of("me"), "me", "ghost", Map.of("me", "")));
        assertTrue(modal.changes().isEmpty());
        var screen = TuiSnapshot.toText(TuiSnapshot.render(80, 16, f -> modal.render(f, f.area())));
        assertTrue(screen.contains("ghost (not configured)"), screen);
        assertTrue(screen.contains("not configured: requests fail until it is"), screen);
    }

    @Test
    void aRefusalIsShownInTheDialog() {
        var modal = modal(CLAUDE);
        press(modal, RIGHT, RIGHT);
        modal.setError("Instance 'review-1' was built for claude 'oauth', but account 'acme' is 'api-key'."
                + " That is baked into the container at build time and cannot be changed on a built"
                + " instance -- branch from a template configured for 'api-key' instead.");
        var screen = TuiSnapshot.toText(TuiSnapshot.render(90, 20, f -> modal.render(f, f.area())));
        assertTrue(screen.contains("was built for claude 'oauth'"), screen);
        assertTrue(screen.contains("'api-key' instead."), "wrapped, not clipped:\n" + screen);
        press(modal, LEFT);
        screen = TuiSnapshot.toText(TuiSnapshot.render(90, 20, f -> modal.render(f, f.area())));
        assertFalse(screen.contains("was built for"), "changing the choice clears it:\n" + screen);
    }

    @Test
    void rendersAt80x24() {
        var modal = modal(CLAUDE, GITHUB);
        TuiSnapshot.assertMatches("accounts-80x24",
                TuiSnapshot.render(80, 24, f -> modal.render(f, f.area())));
    }

    @Test
    void rendersAt120x40() {
        var modal = modal(CLAUDE, GITHUB);
        press(modal, DOWN);
        TuiSnapshot.assertMatches("accounts-120x40",
                TuiSnapshot.render(120, 40, f -> modal.render(f, f.area())));
    }

    @Test
    void rowsComeFromTheConfigAndThePins() throws Exception {
        var config = new ObjectMapper(new YAMLFactory()).readValue("""
                claude:
                  accounts:
                    personal:
                      type: oauth
                      oauthToken: "sk-ant-oat01-x"
                    broken:
                      type: vertex
                  default: personal
                github:
                  token: "ghp_flat"
                """, SpawnConfig.class);
        var setups = new java.util.LinkedHashMap<String, ToolSetup>();
        setups.put("claude", new ClaudeSetup());
        setups.put("github", new GhSetup());
        var rows = AccountsModal.rowsFor(config, setups, Map.of("github", "default"));

        assertEquals(2, rows.size());
        assertEquals(List.of("personal"), rows.get(0).accounts(), "an incomplete account cannot be pinned");
        assertEquals("", rows.get(0).pinned());
        assertEquals(List.of("default"), rows.get(1).accounts(), "a flat credential is the account 'default'");
        assertEquals("default", rows.get(1).pinned());
    }
}
