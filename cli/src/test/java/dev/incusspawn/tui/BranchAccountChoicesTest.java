package dev.incusspawn.tui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.incusspawn.config.AccountOrigin;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.lifecycle.BranchFlow;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.tool.ToolSetup;
import dev.tamboui.layout.Rect;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

/** The branch dialog's credential account dropdowns. */
class BranchAccountChoicesTest {

    private static final TuiTheme THEME = TuiTheme.dark();

    private static final String CONFIG = """
            claude:
              accounts:
                personal:
                  type: oauth
                  oauthToken: "sk-ant-oat01-x"
                work-sub:
                  type: oauth
                  oauthToken: "sk-ant-oat01-y"
                acme:
                  type: vertex
                  cloudMlRegion: europe-west1
                  vertexProjectId: acme-prod
              default: personal
            github:
              accounts:
                me:
                  token: "ghp_me"
                acme-bot:
                  token: "ghp_bot"
                  email: "bot@acme.example"
              default: me
            bob:
              accounts:
                only:
                  apiKey: "bob-key"
            """;

    private static Map<String, ToolSetup> setups() {
        var setups = new LinkedHashMap<String, ToolSetup>();
        setups.put("claude", new ClaudeSetup());
        setups.put("github", new GhSetup());
        return setups;
    }

    /** Branched from tpl-acme, which pins github to acme-bot; the source was built for Pro/Max. */
    private static List<BranchAccountChoices.Row> rows() throws Exception {
        var config = new ObjectMapper(new YAMLFactory()).readValue(CONFIG, SpawnConfig.class);
        var inherited = new BranchFlow.Inherited("tpl-acme", Map.of("github", "acme-bot"),
                Map.of("github", AccountOrigin.template("tpl-acme")));
        return BranchAccountChoices.rowsFor(config, setups(), new java.util.LinkedHashSet<>(List.of("claude", "github", "bob")),
                inherited, Map.of("claude", "oauth"));
    }

    private static BranchAccountChoices choices() throws Exception {
        return new BranchAccountChoices(new ModalRenderer(THEME), THEME, rows());
    }

    private static KeyEvent key(KeyCode code) {
        return KeyEvent.ofKey(code);
    }

    @Test
    void offersCredentialsWithAChoiceOnly() throws Exception {
        var rows = rows();
        assertEquals(List.of("claude", "github"), rows.stream().map(BranchAccountChoices.Row::namespace).toList(),
                "bob has a single account: nothing to decide, so no row");
    }

    @Test
    void theFirstChoiceIsWhatTheBranchWouldInherit() throws Exception {
        var rows = rows();
        assertEquals("personal", rows.get(0).inheritedAccount());
        assertEquals("global default, follows it", rows.get(0).inheritedSource());
        assertFalse(rows.get(0).inheritedPinned());
        assertEquals("acme-bot", rows.get(1).inheritedAccount());
        assertEquals("as tpl-acme chooses", rows.get(1).inheritedSource());
        assertTrue(choices().overrides().isEmpty(), "untouched, the branch gets exactly what 'isx branch' would");
    }

    /** A Claude auth mode the template was not built for is shown, with why it cannot be chosen. */
    @Test
    void anAccountTheTemplateCannotHonourIsListedButNotSelectable() throws Exception {
        var acme = rows().get(0).options().stream().filter(o -> o.account().equals("acme")).findFirst().orElseThrow();
        assertEquals("vertex", acme.requiredBuild());

        var choices = choices();
        choices.cycle(0, 1);   // personal, pinned
        choices.cycle(0, 1);   // work-sub
        choices.cycle(0, 1);   // skips acme, wraps to inherit
        assertTrue(choices.overrides().isEmpty(), choices.overrides().toString());

        choices.open(0);
        choices.handleOpenKey(key(KeyCode.END));        // acme
        choices.handleOpenKey(key(KeyCode.ENTER));
        assertTrue(choices.isOpen(), "choosing it is refused while the reason is on screen");
    }

    /** Pinning the account that is the default is its own choice: the pin stays if the default moves. */
    @Test
    void theInheritedDefaultCanBePinnedExplicitly() throws Exception {
        var choices = choices();
        choices.open(0);
        choices.handleOpenKey(key(KeyCode.DOWN));       // personal, as an explicit pin
        choices.handleOpenKey(key(KeyCode.ENTER));
        assertFalse(choices.isOpen());
        assertEquals(List.of("claude=personal"), choices.overrides());
    }

    @Test
    void escapeClosesTheDropdownWithoutChanging() throws Exception {
        var choices = choices();
        choices.open(1);
        choices.handleOpenKey(key(KeyCode.DOWN));
        choices.handleOpenKey(key(KeyCode.ESCAPE));
        assertFalse(choices.isOpen());
        assertTrue(choices.overrides().isEmpty());
    }

    @Test
    void anInheritedPinToAMissingAccountGetsARowToFixItIn() throws Exception {
        var config = new ObjectMapper(new YAMLFactory()).readValue(CONFIG, SpawnConfig.class);
        var inherited = new BranchFlow.Inherited("tpl-dev", Map.of("bob", "gone"),
                Map.of("bob", AccountOrigin.EXPLICIT));
        var setups = setups();
        setups.put("bob", new dev.incusspawn.tool.BobSetup());
        var rows = BranchAccountChoices.rowsFor(config, setups, Set.of("bob"), inherited, Map.of());
        assertEquals(1, rows.size(), "a single account, but the inherited one is broken");
        assertFalse(rows.get(0).inheritedProblem().isEmpty());
    }

    // ── rendering ────────────────────────────────────────────────────────────

    private static dev.tamboui.buffer.Buffer render(BranchAccountChoices choices, int width, int height,
                                                     int focused) {
        return TuiSnapshot.render(width, height, f -> {
            var screen = f.area();
            for (int i = 0; i < choices.size(); i++) {
                var area = new Rect(screen.x() + 2, screen.y() + 1 + i, 60, 1);
                choices.renderRow(f, area, i, i == focused);
            }
            if (choices.isOpen()) {
                choices.renderOpen(f, screen, new Rect(screen.x() + 2, screen.y() + 1 + focused, 60, 1));
            }
        });
    }

    @Test
    void rowsShowInheritedAndPinnedDifferently() throws Exception {
        var choices = choices();
        choices.cycle(1, 1);   // github: pin 'me'
        var screen = TuiSnapshot.toText(render(choices, 80, 6, 0));
        assertTrue(screen.contains("claude  personal · global default, follows it ▾"), screen);
        assertTrue(screen.contains("github  me · pinned ▾"), screen);
        TuiSnapshot.assertMatches("branch-accounts-rows-80x6", render(choices, 80, 6, 0));
    }

    @Test
    void openDropdownAt80x24() throws Exception {
        var choices = choices();
        choices.open(0);
        var buffer = render(choices, 80, 24, 0);
        var screen = TuiSnapshot.toText(buffer);
        assertTrue(screen.contains("inherit"), screen);
        assertTrue(screen.contains("or pin for this branch:"), screen);
        assertTrue(screen.contains("Claude Pro/Max OAuth token · stays if the default changes"), screen);
        assertTrue(screen.contains("acme      not usable: this template was built for another auth mode"), screen);
        TuiSnapshot.assertMatches("branch-accounts-open-80x24", buffer);
    }

    @Test
    void openDropdownAt120x40() throws Exception {
        var choices = choices();
        choices.open(1);
        TuiSnapshot.assertMatches("branch-accounts-open-120x40", render(choices, 120, 40, 1));
    }
}
