package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code drivers:} (#1720): what an external tool may do to its instances, granted only by the
 * user. A wrong entry grants nothing and says why; it never falls back to something wider.
 */
class DriversTest {

    private final List<String> warnings = new ArrayList<>();

    private Drivers.Driver find(String yaml, String name) throws Exception {
        return Jjq.spawnConfig(yaml).drivers().find(name, warnings::add);
    }

    @Test
    void noSectionGrantsNothing() throws Exception {
        assertNull(find("{}", "orca"));
        assertFalse(Jjq.toYaml(new SpawnConfig()).contains("drivers"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aToolGrantedDestroyMayDestroyAndOnlyIt() throws Exception {
        var yaml = """
                drivers:
                  orca:
                    destroy: true
                  herdr:
                    destroy: false
                  quiet:
                """;
        assertEquals(new Drivers.Driver("orca", true), find(yaml, "orca"));
        assertEquals(new Drivers.Driver("herdr", false), find(yaml, "herdr"));
        assertEquals(new Drivers.Driver("quiet", false), find(yaml, "quiet"), "listed with nothing is granted nothing");
        assertNull(find(yaml, "other"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aWrongEntryGrantsNothingAndSaysWhy() throws Exception {
        assertNull(find("drivers:\n  orca:\n    destory: true\n", "orca"));
        assertNull(find("drivers:\n  orca:\n    destroy: \"yes\"\n", "orca"));
        assertNull(find("drivers:\n  orca: true\n", "orca"));
        assertNull(find("drivers:\n  mcp:\n    destroy: true\n", "mcp"));
        assertNull(find("drivers: [orca]\n", "orca"));
        assertEquals(List.of(
                "Ignoring drivers: orca in config.yaml: unknown key 'destory'; did you mean 'destroy'?",
                "Ignoring drivers: orca in config.yaml: destroy: is true or false",
                "Ignoring drivers: orca in config.yaml: expected destroy: under it",
                "Ignoring drivers: mcp in config.yaml: the owner name mcp is reserved for instances isx mcp makes",
                "Ignoring drivers: in config.yaml (expected a tool name per entry, each with destroy:)"), warnings);
    }

    @Test
    void aMisspeltKeyIsNamedEvenWhenAnotherIsLookedUp() throws Exception {
        assertNull(find("drivers:\n  Orca:\n    destroy: true\n", "orca"));
        assertEquals(List.of("Ignoring drivers: Orca in config.yaml: an owner is a lowercase name of letters,"
                + " digits and '-', at most 32 long"), warnings);
    }

    @Test
    void theSectionIsWrittenBackAsItWas() throws Exception {
        var config = Jjq.spawnConfig("drivers:\n  orca:\n    destroy: true\n");
        assertTrue(Jjq.toYaml(config).contains("drivers:"), Jjq.toYaml(config));
        assertEquals(new Drivers.Driver("orca", true),
                Jjq.spawnConfig(Jjq.toYaml(config)).drivers().find("orca", warnings::add));
    }
}
