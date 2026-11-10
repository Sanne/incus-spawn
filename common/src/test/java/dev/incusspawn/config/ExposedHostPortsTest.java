package dev.incusspawn.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code exposed-host-ports:} (#2117): only a plain list of port numbers exposes anything, and each
 * port is checked on its own, so a typo drops itself and never another port's check.
 */
class ExposedHostPortsTest {

    private final List<String> warnings = new ArrayList<>();

    private List<Integer> ports(String yaml) throws Exception {
        return Jjq.spawnConfig(yaml).exposedHostPorts().ports(warnings::add);
    }

    @Test
    void noKeyExposesNothingAndIsNotWritten() throws Exception {
        assertEquals(List.of(), ports("{}"));
        assertFalse(Jjq.toYaml(new SpawnConfig()).contains("exposed-host-ports"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void listedPortsAreKeptInOrder() throws Exception {
        assertEquals(List.of(11434, 1, 65535), ports("exposed-host-ports: [11434, 1, 65535]"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anEmptyListExposesNothing() throws Exception {
        assertEquals(List.of(), ports("exposed-host-ports: []"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aValueThatIsNotAPlainListOfIntegersDropsTheWholeKey() throws Exception {
        for (var value : List.of("11434", "{11434: true}", "[11434, \"8000-8010\"]", "[\"127.0.0.1:11434\"]",
                "[11434, \"8080\"]", "[11434, 8080.5]", "[11434, [8080]]", "[11434, null]", "[true]")) {
            warnings.clear();
            assertEquals(List.of(), ports("exposed-host-ports: " + value), value);
            assertEquals(1, warnings.size(), value + ": " + warnings);
            assertTrue(warnings.getFirst().startsWith("Ignoring exposed-host-ports:"), warnings::toString);
        }
    }

    @Test
    void aPortOutOfRangeIsDroppedAlone() throws Exception {
        assertEquals(List.of(11434), ports("exposed-host-ports: [0, 11434, 65536, -1, 99999999999999999999]"));
        assertEquals(4, warnings.size(), warnings::toString);
        assertTrue(warnings.stream().allMatch(w -> w.contains("from 1 to 65535")), warnings::toString);
    }

    @Test
    void isxsOwnPortsAreRefused() throws Exception {
        assertEquals(List.of(8080), ports("exposed-host-ports: [443, 18443, 18080, 53, 8080]"));
        assertEquals(4, warnings.size(), warnings::toString);
        assertTrue(warnings.getFirst().contains("443"), warnings::toString);
    }

    @Test
    void aPortListedTwiceIsKeptOnceAndSaidSo() throws Exception {
        assertEquals(List.of(11434, 8080), ports("exposed-host-ports: [11434, 8080, 11434]"));
        assertEquals(List.of("Ignoring exposed host port 11434 in config.yaml: it is listed more than once"), warnings);
    }

    @Test
    void theKeyIsWrittenBackAsItWasRead() throws Exception {
        var yaml = Jjq.toYaml(Jjq.spawnConfig("exposed-host-ports: [11434, \"oops\"]"));
        assertTrue(yaml.contains("exposed-host-ports"), yaml);
        assertTrue(yaml.contains("oops"), yaml);
    }

    @Test
    void theHostNameAndEveryNameUnderItAreReserved() {
        assertTrue(ExposedHostPorts.isReserved("host.isx.internal"));
        assertTrue(ExposedHostPorts.isReserved("a.host.isx.internal"));
        assertFalse(ExposedHostPorts.isReserved("otherhost.isx.internal"));
        assertFalse(ExposedHostPorts.isReserved("work.isx.internal"));
    }
}
