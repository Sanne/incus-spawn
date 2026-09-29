package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.util.BuildOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolVerifierTest {

    private static final String CONTAINER = "test-container";

    @Test
    void verifiesInTheImageEnvironmentAndReportsTheVersion() {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), anyString()))
                .thenReturn(new IncusClient.ExecResult(0, "Apache Maven 3.9.16\nMaven home: /opt\n", ""));

        var out = capture(() -> ToolVerifier.verifyAll(new Container(incus, CONTAINER),
                List.of(new ToolVerifier.Check("maven-3", "mvn --version"))));

        // Sourced, so JAVA_HOME from another tool's env entry is set -- and the file exists by now.
        verify(incus).shellExec(CONTAINER, "sh", "-c", ". /etc/profile.d/isx-env.sh && mvn --version");
        // The blank line before a group depends on what the previous output ended with.
        assertEquals("""
                    ▸ Verifying  maven-3
                      maven-3... done.
                        Apache Maven 3.9.16

                """, BuildOutput.stripAnsi(out).replaceFirst("^\n", ""));
    }

    @Test
    void stepIsNotDoneUntilVerifyReturnsAndAFailureSaysAllOfWhy() {
        var incus = mock(IncusClient.class);
        var printedWhenVerifying = new AtomicReference<String>();
        var out = new java.io.ByteArrayOutputStream();
        when(incus.shellExec(eq(CONTAINER), eq("sh"), eq("-c"), anyString())).thenAnswer(inv -> {
            printedWhenVerifying.set(out.toString());
            return new IncusClient.ExecResult(1, "",
                    "The JAVA_HOME environment variable is not defined correctly,\n"
                            + "this environment variable is needed to run this program.\n");
        });

        capture(out, () -> ToolVerifier.verifyAll(new Container(incus, CONTAINER),
                List.of(new ToolVerifier.Check("maven-3", "mvn --version"))));

        assertFalse(printedWhenVerifying.get().contains("done"),
                "a slow verify must not run after the step already claimed to be done");
        assertTrue(BuildOutput.stripAnsi(out.toString()).contains(
                "⚠ Verification failed (mvn --version): The JAVA_HOME environment variable is not defined"
                        + " correctly, this environment variable is needed to run this program.\n\n"),
                out.toString());
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
    void nothingToVerifyPrintsNothing() {
        var incus = mock(IncusClient.class);
        assertEquals("", capture(() -> ToolVerifier.verifyAll(new Container(incus, CONTAINER), List.of())));
        verifyNoInteractions(incus);
    }

    private static String capture(Runnable body) {
        var out = new java.io.ByteArrayOutputStream();
        capture(out, body);
        return out.toString();
    }

    private static void capture(java.io.ByteArrayOutputStream out, Runnable body) {
        var original = System.out;
        System.setOut(new java.io.PrintStream(out, true));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
    }
}
