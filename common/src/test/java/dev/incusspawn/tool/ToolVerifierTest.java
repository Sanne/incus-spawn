package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.RecordingOutput;
import dev.incusspawn.util.RecordingOutput.Event;
import dev.incusspawn.util.RecordingOutput.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolVerifierTest {

    private static final String CONTAINER = "test-container";

    @Test
    void eachVerifyIsRecordedInTheBuildsCommandLog() {
        // #1742: a verify is a command the definition wrote, as root or as the user
        var incus = mock(IncusClient.class);
        when(incus.execInContainer(CONTAINER, "agentuser", "mvn --version"))
                .thenReturn(new IncusClient.ExecResult(0, "Apache Maven 3.10.1\n", ""));
        when(incus.shellExec(eq(CONTAINER), any(String[].class))).thenReturn(new IncusClient.ExecResult(1, "", "no zmx\n"));
        var recorded = new java.util.ArrayList<String>();
        var container = new Container(incus, CONTAINER, RecordingOutput.QUIET,
                (step, command, result, took) -> recorded.add(step + " | " + command + " | " + result.exitCode()));

        RecordingOutput.during(() -> ToolVerifier.verifyAll(container, List.of(
                new ToolVerifier.Check("maven-3", "mvn --version"),
                new ToolVerifier.Check("zmx", "zmx version", true))));

        assertEquals(List.of("tool 'maven-3' verify | mvn --version | 0", "tool 'zmx' verify | zmx version | 1"), recorded);
    }

    @Test
    void verifiesInTheImageEnvironmentAndReportsTheVersion() throws Exception {
        var incus = mock(IncusClient.class);
        when(incus.execInContainer(CONTAINER, "agentuser", "mvn --version"))
                .thenReturn(new IncusClient.ExecResult(0, "Apache Maven 3.10.1\nMaven home: /opt\n", ""));

        var out = RecordingOutput.during(() -> ToolVerifier.verifyAll(new Container(incus, CONTAINER),
                List.of(new ToolVerifier.Check("maven-3", "mvn --version"))));

        // As the image's user in a login shell, which sources isx-env.sh (JAVA_HOME from another
        // tool's env entry) -- and never as root, which could leave root-owned state in their home.
        verify(incus).execInContainer(CONTAINER, "agentuser", "mvn --version");
        verifyNoMoreInteractions(incus);
        // One group, its step done before the version under it; how that looks is BuildOutputTest's
        assertEquals(List.of(
                new Event(Kind.GROUP, "Verifying", List.of("maven-3")),
                new Event(Kind.STEP_START, "maven-3", List.of()),
                new Event(Kind.STEP_PROGRESS, "mvn --version", List.of()),
                new Event(Kind.STEP_DONE, null, List.of()),
                new Event(Kind.STEP_NOTE, "Apache Maven 3.10.1", List.of()),
                new Event(Kind.GROUP_END, null, List.of())), out.events());
    }

    @Test
    void aRootCheckRunsInRootsOwnEnvironmentAndNothingElseRunsAsRoot() throws Exception {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq(CONTAINER), any(String[].class)))
                .thenReturn(new IncusClient.ExecResult(0, "v1\n", ""));
        when(incus.execInContainer(eq(CONTAINER), eq("agentuser"), anyString()))
                .thenReturn(new IncusClient.ExecResult(0, "v1\n", ""));

        RecordingOutput.during(() -> ToolVerifier.verifyAll(new Container(incus, CONTAINER),
                List.of(new ToolVerifier.Check("a", "a --version"),
                        new ToolVerifier.Check("b", "b --version", true))));

        // Root's own environment, not isx-env.sh, which points into agentuser's home (#931) --
        // and no other root exec: nothing is left to hand back afterwards.
        verify(incus).execInContainer(CONTAINER, "agentuser", "a --version");
        verify(incus).shellExec(CONTAINER, "env", "-i", "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "sh", "-c", "b --version");
        verifyNoMoreInteractions(incus);
    }

    @Test
    void stepIsNotDoneUntilVerifyReturnsAndAFailureSaysAllOfWhy() throws Exception {
        var incus = mock(IncusClient.class);
        var recorder = new RecordingOutput();
        var reportedWhenVerifying = new AtomicReference<List<Event>>();
        when(incus.execInContainer(eq(CONTAINER), eq("agentuser"), anyString())).thenAnswer(inv -> {
            reportedWhenVerifying.set(recorder.events());
            return new IncusClient.ExecResult(1, "",
                    "The JAVA_HOME environment variable is not defined correctly,\n"
                            + "this environment variable is needed to run this program.\n");
        });

        try (var bound = BuildOutput.bind(recorder)) {
            ToolVerifier.verifyAll(new Container(incus, CONTAINER), List.of(new ToolVerifier.Check("maven-3", "mvn --version")));
        }

        assertTrue(reportedWhenVerifying.get().stream().noneMatch(e -> e.kind() == Kind.STEP_DONE),
                "a slow verify must not run after the step already claimed to be done");
        assertEquals(List.of("Verification failed (mvn --version): The JAVA_HOME environment variable is not defined"
                        + " correctly, this environment variable is needed to run this program."),
                recorder.events(Kind.STEP_WARN).stream().map(Event::msg).toList());
    }

    @Test
    void failureReasonFallsBackAndStaysBounded() {
        assertEquals("Verification failed (x): from stdout",
                ToolVerifier.failure("x", new IncusClient.ExecResult(1, "\n from stdout \n", "  \n")));
        assertEquals("Verification failed (x): exit code 127",
                ToolVerifier.failure("x", new IncusClient.ExecResult(127, "", "")));

        var trace = "java.lang.Error: boom\n" + "\tat Some.frame(Some.java:1)\n".repeat(50);
        var reason = ToolVerifier.failure("x", new IncusClient.ExecResult(1, "", trace))
                .substring("Verification failed (x): ".length());
        assertEquals(ToolVerifier.MAX_REASON, reason.length());
        assertTrue(reason.endsWith("…"));
    }

    @Test
    void nothingToVerifyReportsNothing() throws Exception {
        var incus = mock(IncusClient.class);
        assertEquals(List.of(), RecordingOutput.during(() ->
                ToolVerifier.verifyAll(new Container(incus, CONTAINER), List.of())).events());
        verifyNoInteractions(incus);
    }
}
