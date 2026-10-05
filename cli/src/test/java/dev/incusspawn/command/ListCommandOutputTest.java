package dev.incusspawn.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx list} from the CLI (#1036): what each format prints, which instances it lists, and
 * what it costs. {@code plain} and {@code json} are a contract with scripts, so these are golden
 * outputs: a field may be added, never renamed, removed or reordered.
 */
class ListCommandOutputTest {

    private static final ZoneOffset ZONE = ZoneOffset.ofHours(2);

    /**
     * A template, two instances branched from it, a branch whose name only looks like a template,
     * and an Incus instance isx did not create.
     */
    private final FakeIncusDaemon daemon = new FakeIncusDaemon()
            .container("tpl-java", Map.of(
                    Metadata.TYPE, Metadata.TYPE_BASE,
                    Metadata.CREATED, "2026-10-01T09:00:00"))
            .instance("dev-1", "container", "Running", Map.of(
                    Metadata.TYPE, Metadata.TYPE_CLONE,
                    Metadata.PARENT, "tpl-java",
                    Metadata.STATIC_IP, "10.166.11.20",
                    Metadata.CREATED, "2026-10-05T10:15:30"))
            .instance("dev-2", "virtual-machine", "Stopped", Map.of(
                    Metadata.TYPE, Metadata.TYPE_CLONE,
                    Metadata.PARENT, "tpl-java",
                    Metadata.CREATED, "2026-09-01"))
            .container("tpl-scratch", Map.of(
                    Metadata.TYPE, Metadata.TYPE_CLONE,
                    Metadata.PARENT, "dev-1",
                    Metadata.CREATED, "2026-10-05T11:00:00"))
            .container("not-ours", Map.of());

    private String list(ListCommand cmd) {
        return list(cmd, daemon);
    }

    private static String list(ListCommand cmd, FakeIncusDaemon incus) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            cmd.printListing(incus.client(), out, ZONE);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static ListCommand cmd(String format) {
        var cmd = new ListCommand();
        cmd.format = format;
        return cmd;
    }

    private static ListCommand quiet() {
        var cmd = new ListCommand();
        cmd.quiet = true;
        return cmd;
    }

    @Test
    void plainIsOneTabSeparatedRecordPerInstance() {
        assertEquals("""
                dev-1\trunning\t10.166.11.20\ttpl-java\tcontainer\t2026-10-05T10:15:30+02:00
                dev-2\tstopped\t-\ttpl-java\tvirtual-machine\t2026-09-01
                tpl-scratch\tstopped\t-\tdev-1\tcontainer\t2026-10-05T11:00:00+02:00
                """, list(cmd("plain")));
    }

    @Test
    void thePlainFlagIsPlainFormat() {
        var cmd = new ListCommand();
        cmd.plain = true;
        assertEquals(list(cmd("plain")), list(cmd));
    }

    @Test
    void jsonIsAnArrayOfObjectsWithStableFields() throws Exception {
        List<LinkedHashMap<String, Object>> json = new ObjectMapper().readValue(list(cmd("json")),
                new TypeReference<>() {});
        assertEquals(3, json.size());
        var first = json.get(0);
        assertEquals(List.of("name", "status", "ipv4", "parent", "runtime", "created"),
                List.copyOf(first.keySet()));
        assertEquals(List.of("dev-1", "running", "10.166.11.20", "tpl-java", "container",
                "2026-10-05T10:15:30+02:00"), List.copyOf(first.values()));
        assertTrue(json.get(1).containsKey("ipv4"));
        assertNull(json.get(1).get("ipv4"), "an absent value is null, never \"-\"");
    }

    @Test
    void quietPrintsNamesOnly() {
        assertEquals("dev-1\ndev-2\ntpl-scratch\n", list(quiet()));
    }

    @Test
    void templatesAreExcludedByTypeNeverByName() {
        var names = list(quiet()).lines().toList();
        assertFalse(names.contains("tpl-java"), "a template is not an instance");
        assertTrue(names.contains("tpl-scratch"), "a branch named tpl-* is still an instance");
        assertFalse(names.contains("not-ours"), "an instance isx did not create is not listed");
    }

    @Test
    void statusFiltersByState() {
        var cmd = quiet();
        cmd.status = "running";
        assertEquals("dev-1\n", list(cmd));
        cmd.status = "Stopped";
        assertEquals("dev-2\ntpl-scratch\n", list(cmd));
    }

    @Test
    void anUnknownStatusOrFormatIsAnError() {
        var badStatus = new ListCommand();
        badStatus.status = "frozen";
        assertThrows(IllegalArgumentException.class, () -> list(badStatus));
        assertThrows(IllegalArgumentException.class, () -> list(cmd("yaml")));
    }

    @Test
    void quietAndPlainOrJsonDoNotCombine() {
        var cmd = cmd("json");
        cmd.quiet = true;
        assertThrows(IllegalArgumentException.class, () -> list(cmd));
        var both = cmd("json");
        both.plain = true;
        assertThrows(IllegalArgumentException.class, () -> list(both));
    }

    @Test
    void noInstancesIsEmptyOutputInPlainAndAnEmptyArrayInJson() {
        var empty = new FakeIncusDaemon();
        assertEquals("", list(cmd("plain"), empty));
        assertEquals("[ ]\n", list(cmd("json"), empty));
        assertEquals("", list(quiet(), empty));
    }

    @Test
    void aListingThatCannotBeReadIsAnErrorNeverAnEmptyList() {
        // [] with exit 0 would tell a script there are no instances. The CLI reports an
        // IncusException as "Error: ..." on stderr with exit 1, and the TUI shows it.
        for (var listing : new String[] {"<html>bad gateway</html>", "{\"instances\": []}", ""}) {
            var e = assertThrows(IncusException.class,
                    () -> ListCommand.collectEntries(listing), listing);
            assertTrue(e.getMessage().startsWith("Cannot read the instance listing"), e.getMessage());
        }
        assertEquals(List.of(), ListCommand.collectEntries("[]"), "an empty array is no instances");
    }

    @Test
    void aListingAnswerWithoutAnArrayFailsEveryFormat() {
        var broken = new FakeIncusDaemon().listingAnswers(new ObjectMapper().createObjectNode().put("oops", 1));
        for (var cmd : List.of(cmd(null), cmd("plain"), cmd("json"), quiet())) {
            var e = assertThrows(IncusException.class, () -> list(cmd, broken));
            assertTrue(e.getMessage().startsWith("Cannot read the instance listing"), e.getMessage());
        }
    }

    @Test
    void aCreatedStampThatCannotBeReadIsNull() throws Exception {
        var daemon = new FakeIncusDaemon().container("odd", Map.of(
                Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.CREATED, "sometime"));
        assertEquals("odd\tstopped\t-\t-\tcontainer\t-\n", list(cmd("plain"), daemon));
        List<LinkedHashMap<String, Object>> json = new ObjectMapper().readValue(list(cmd("json"), daemon),
                new TypeReference<>() {});
        assertNull(json.get(0).get("created"));
    }

    @Test
    void tableKeepsTheHumanListing() {
        var table = list(cmd(null));
        assertTrue(table.contains("NAME"), table);
        assertTrue(table.contains("dev-1"), table);
        assertTrue(table.lines().noneMatch(l -> l.strip().startsWith("tpl-java ")), "no row for the template:\n" + table);
    }

    @Test
    void listingFromTheCliReadsIncusOnce() {
        // The listing a script may poll: one read, no pool probes, no per-instance reads. Names
        // need no live state, so -q (which completion runs on every TAB) skips recursion=2.
        // More is a latency regression; fewer is an improvement -- lower the budget.
        for (var format : new String[] {null, "plain", "json"}) {
            daemon.clearRequests();
            list(cmd(format));
            assertEquals(List.of("GET /1.0/instances?recursion=2"), daemon.requests(), String.valueOf(format));
        }
        daemon.clearRequests();
        list(quiet());
        assertEquals(List.of("GET /1.0/instances?recursion=1"), daemon.requests());
    }
}
