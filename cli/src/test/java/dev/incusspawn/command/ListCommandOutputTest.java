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
import java.util.ArrayList;
import java.util.Arrays;
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
                dev-1\trunning\t10.166.11.20\ttpl-java\tcontainer\t2026-10-05T10:15:30+02:00\t-\t-
                dev-2\tstopped\t-\ttpl-java\tvirtual-machine\t2026-09-01\t-\t-
                tpl-scratch\tstopped\t-\tdev-1\tcontainer\t2026-10-05T11:00:00+02:00\t-\t-
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
        assertEquals(List.of("name", "status", "ipv4", "parent", "runtime", "created", "mcp_state", "mcp_purpose"),
                List.copyOf(first.keySet()));
        assertEquals(Arrays.asList("dev-1", "running", "10.166.11.20", "tpl-java", "container",
                "2026-10-05T10:15:30+02:00", null, null), new ArrayList<>(first.values()));
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
        assertEquals("odd\tstopped\t-\t-\tcontainer\t-\t-\t-\n", list(cmd("plain"), daemon));
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

    /**
     * Instances an {@code isx mcp} session made (#1053), held by a coordinator instance's session
     * so that liveness is decided from the listing alone: one held (its coordinator exists with
     * the grant), one whose coordinator is gone, one its holder released, and one kept.
     */
    private static FakeIncusDaemon mcpDaemon() {
        var grant = "0123456789abcdef0123456789abcdef";
        var held = "instance:coord:" + grant;
        var gone = "instance:old-coord:" + grant;
        return new FakeIncusDaemon()
                .container("coord", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "tpl-java",
                        Metadata.MCP_CALLER, grant))
                .container("worker", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "tpl-java",
                        Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, held,
                        Metadata.MCP_PURPOSE, "#870 implement"))
                .container("stray", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "tpl-java",
                        Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, gone))
                .container("released", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "tpl-java",
                        Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, held,
                        Metadata.MCP_ORPHANED, "2026-10-05T08:00:00Z " + held, Metadata.MCP_PURPOSE, "review\t#12"))
                .container("mine-now", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.PARENT, "tpl-java",
                        Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, held,
                        Metadata.MCP_KEPT, "2026-10-05T09:00:00Z", Metadata.MCP_PURPOSE, "spike"));
    }

    @Test
    void plainAndJsonSayWhichInstancesAnMcpSessionMadeAndWhatFor() throws Exception {
        var mcp = mcpDaemon();
        // Fields at the end only: what a script reads by position stays where it was.
        assertEquals("""
                coord\tstopped\t-\ttpl-java\tcontainer\t-\t-\t-
                worker\tstopped\t-\ttpl-java\tcontainer\t-\theld\t#870 implement
                stray\tstopped\t-\ttpl-java\tcontainer\t-\torphaned\t-
                released\tstopped\t-\ttpl-java\tcontainer\t-\torphaned\treview #12
                mine-now\tstopped\t-\ttpl-java\tcontainer\t-\tkept\tspike
                """, list(cmd("plain"), mcp));
        List<LinkedHashMap<String, Object>> json = new ObjectMapper().readValue(list(cmd("json"), mcp),
                new TypeReference<>() {});
        assertNull(json.get(0).get("mcp_state"), "an instance no isx mcp session made");
        assertEquals("held", json.get(1).get("mcp_state"));
        assertEquals("#870 implement", json.get(1).get("mcp_purpose"));
        assertTrue(json.get(2).containsKey("mcp_purpose"));
        assertNull(json.get(2).get("mcp_purpose"), "no purpose given is null, never \"-\"");
        assertEquals("orphaned", json.get(3).get("mcp_state"));
        assertEquals("review\t#12", json.get(3).get("mcp_purpose"), "json keeps the value as stamped");
        assertEquals("kept", json.get(4).get("mcp_state"));
    }

    @Test
    void theTableShowsAnMcpColumnOnlyWhenAnInstanceHasOne() {
        var table = list(cmd(null), mcpDaemon());
        var header = table.lines().filter(l -> l.contains("NAME")).findFirst().orElseThrow();
        assertTrue(header.strip().endsWith("MCP"), table);
        assertTrue(table.lines().anyMatch(l -> l.contains("worker") && l.endsWith("held: #870 implement")), table);
        assertTrue(table.lines().anyMatch(l -> l.contains("stray") && l.endsWith("orphaned")), table);
        assertTrue(table.lines().anyMatch(l -> l.contains("mine-now") && l.endsWith("kept: spike")), table);
        assertFalse(list(cmd(null)).contains("MCP"), "nobody using isx mcp sees no column for it");
    }

    @Test
    void theTableShowsAHandSetParentWithoutItsEscapeSequence() {
        // #1118: parent is a stamp too, and the table's PARENT column printed it raw.
        var daemon = new FakeIncusDaemon().container("w", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.PARENT, "tpl\u001b[2J\u009b2J"));
        var table = list(cmd(null), daemon);
        assertFalse(table.contains("\u001b") || table.contains("\u009b"), table);
        assertTrue(table.contains("tpl [2J 2J"), table);
    }

    @Test
    void noFormatPassesAnEscapeSequenceInAStampToTheTerminal() throws Exception {
        // #1118: 7-bit (ESC [) and 8-bit (U+009B) CSI alike, in plain, the table and the details.
        var purpose = "\u001b[2J\u009b2Jhi";
        var daemon = new FakeIncusDaemon().container("w", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, "garbled", Metadata.MCP_PURPOSE, purpose));
        assertEquals("w\tstopped\t-\t-\tcontainer\t-\theld\t [2J 2Jhi\n", list(cmd("plain"), daemon));
        var table = list(cmd(null), daemon);
        assertFalse(table.contains("\u009b"), table);
        assertTrue(table.contains("held:  [2J 2Jhi"), table);
        var entry = ListCommand.collectEntries(daemon.client().listJson()).getFirst();
        assertEquals("  Purpose:| [2J 2Jhi", rows(ListCommand.mcpDetailRows(entry.mcp())).getLast());
        var json = list(cmd("json"), daemon);
        assertFalse(json.contains("\u001b") || json.contains("\u009b"), json);
        List<LinkedHashMap<String, Object>> parsed = new ObjectMapper().readValue(json, new TypeReference<>() {});
        assertEquals(purpose, parsed.getFirst().get("mcp_purpose"), "json keeps the value as stamped");
    }

    @Test
    void theDetailPaneSaysWhoHoldsItWhatForAndWhatHappensToAnOrphan() {
        assertEquals(List.of(), ListCommand.mcpDetailRows(null));
        var held = ListCommand.collectEntries(mcpDaemon().client().listJson());
        var rows = held.stream().filter(i -> i.name().equals("worker")).findFirst().orElseThrow();
        assertEquals(List.of("MCP:|held by isx instance coord", "  Purpose:|#870 implement"),
                rows(ListCommand.mcpDetailRows(rows.mcp())));
        var released = held.stream().filter(i -> i.name().equals("released")).findFirst().orElseThrow();
        var text = rows(ListCommand.mcpDetailRows(released.mcp()));
        assertEquals("MCP:|orphaned since 2026-10-05T08:00:00Z", text.get(0));
        assertTrue(text.stream().anyMatch(r -> r.contains("mcp.orphan-grace-hours")), text.toString());
        assertEquals("  Purpose:|review #12", text.getLast());
    }

    @Test
    void theDetailPaneSaysWhenAnOrphanWasStoppedAsDormantAndWhatComesNext() {
        // #1028: past its grace an orphan kept only for an idle delegate is stopped, not destroyed.
        var dead = "instance:gone:0123456789abcdef0123456789abcdef";
        var daemon = new FakeIncusDaemon().container("idle", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE,
                Metadata.MCP_OWNER, "alice", Metadata.MCP_SESSION, dead,
                Metadata.MCP_ORPHANED, "2026-10-04T08:00:00Z " + dead,
                Metadata.MCP_DORMANT, "2026-10-05T09:00:00Z " + dead));
        var idle = ListCommand.collectEntries(daemon.client().listJson()).getFirst();
        var text = rows(ListCommand.mcpDetailRows(idle.mcp()));
        assertEquals("MCP:|orphaned since 2026-10-04T08:00:00Z, stopped as dormant since 2026-10-05T09:00:00Z", text.get(0));
        assertTrue(text.stream().anyMatch(r -> r.contains("mcp.dormant-grace-hours")), text.toString());
        assertTrue(text.stream().noneMatch(r -> r.contains("mcp.orphan-grace-hours")), "its orphan grace is over: " + text);
    }

    private static List<String> rows(List<ListCommand.DetailRow> rows) {
        return rows.stream().map(r -> r.label() + "|" + r.value()).toList();
    }

    @Test
    void listingMcpInstancesStillReadsIncusOnce() {
        // Whether a coordinator instance still holds its workers is read from the same listing.
        var mcp = mcpDaemon();
        list(cmd("json"), mcp);
        assertEquals(List.of("GET /1.0/instances?recursion=2"), mcp.requests());
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
