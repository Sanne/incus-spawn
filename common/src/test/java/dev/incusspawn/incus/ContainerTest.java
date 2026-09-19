package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerTest {

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");

    /** Return the `sh -c` script Container handed to the client. */
    private static String scriptFor(IncusClient incus) {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(incus).shellExec(eq("test"), eq("sh"), eq("-c"), captor.capture());
        return captor.getValue();
    }

    private static IncusClient mockClient() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq("test"), eq("sh"), eq("-c"), anyString())).thenReturn(OK);
        return incus;
    }

    /** The body sits between the first newline and the final delimiter line. */
    private static String bodyOf(String script) {
        var start = script.indexOf('\n') + 1;
        var lastNewline = script.lastIndexOf('\n');
        return script.substring(start, lastNewline);
    }

    @Test
    void ordinaryContentUsesThePlainMarker() {
        var incus = mockClient();
        new Container(incus, "test").writeFile("/etc/thing.conf", "key = value");

        var script = scriptFor(incus);
        assertTrue(script.contains("<< 'INCUS_EOF'\n"));
        assertTrue(script.endsWith("\nINCUS_EOF"));
        assertEquals("key = value", bodyOf(script));
    }

    @Test
    void contentContainingTheMarkerIsWrittenVerbatim() {
        // Content reaching writeFile is often user-authored (tool files:, template env,
        // skills, agent notes). A fixed delimiter would let the content close the
        // heredoc early, truncating the file and running the rest as shell.
        var incus = mockClient();
        var content = "before\nINCUS_EOF\nafter";
        new Container(incus, "test").writeFile("/etc/thing.conf", content);

        var script = scriptFor(incus);
        assertTrue(script.contains("<< 'INCUS_EOF_'\n"), "delimiter should be extended");
        assertEquals(content, bodyOf(script), "content must survive byte-for-byte");
    }

    @Test
    void markerIsExtendedUntilItIsUnambiguous() {
        var incus = mockClient();
        var content = "a\nINCUS_EOF\nb\nINCUS_EOF_\nc";
        new Container(incus, "test").writeFile("/etc/thing.conf", content);

        var script = scriptFor(incus);
        assertTrue(script.contains("<< 'INCUS_EOF__'\n"));
        assertEquals(content, bodyOf(script));
    }

    @Test
    void markerMentionedInlineDoesNotTriggerExtension() {
        // Only a line equal to the delimiter closes a heredoc.
        var incus = mockClient();
        new Container(incus, "test").writeFile("/etc/thing.conf", "the marker is INCUS_EOF here");

        var script = scriptFor(incus);
        assertTrue(script.contains("<< 'INCUS_EOF'\n"));
        assertEquals("the marker is INCUS_EOF here", bodyOf(script));
    }
}
