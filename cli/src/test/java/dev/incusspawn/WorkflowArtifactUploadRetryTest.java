package dev.incusspawn;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A runner that cannot reach GitHub's artifact service fails its upload with {@code CreateArtifact:
 * ETIMEDOUT} after the job's build already passed (#1672), and with it every job that downloads the
 * artifact. So an upload that a later job or the release needs goes through the local
 * {@code upload-artifact-retry} action, which tries once more when the first attempt failed.
 * Diagnostic uploads, run on {@code always()} or {@code failure()}, keep a single attempt.
 */
class WorkflowArtifactUploadRetryTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");
    private static final Path ACTION = Path.of("../.github/actions/upload-artifact-retry/action.yml");
    private static final String RETRYING = "./.github/actions/upload-artifact-retry";
    /** A macOS job the Mac runner gave up runs none of its steps, a diagnostic upload included (#2298). */
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "\\s*(?:\\$\\{\\{\\s*)?(?:always|failure)\\(\\)(?: && steps\\.gate\\.outputs\\.run == 'true')?(?:\\s*}})?\\s*");

    private static boolean isUpload(JqValue step) {
        return step.pathText("uses", "").matches("actions/upload-artifact[@/].*");
    }

    /** Only a step that runs on nothing but {@code always()} or {@code failure()} counts as diagnostic. */
    static boolean isDiagnostic(JqValue step) {
        return DIAGNOSTIC.matcher(step.pathText("if", "")).matches();
    }

    @Test
    void everyDeliverableUploadIsRetried() throws IOException {
        var offenders = new ArrayList<String>();
        int retried = 0;
        try (var files = Files.list(WORKFLOWS)) {
            for (var file : files.filter(f -> f.toString().matches(".*\\.ya?ml")).sorted().toList()) {
                var jobs = JqYaml.parse(Files.readString(file)).path("jobs");
                for (var job : jobs.asMap().entrySet()) {
                    for (var step : job.getValue().path("steps").asList()) {
                        if (step.pathText("uses", "").equals(RETRYING)) {
                            retried++;
                        } else if (isUpload(step) && !isDiagnostic(step)) {
                            offenders.add(file.getFileName() + " " + job.getKey() + ": " + step.pathText("name", ""));
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "upload what a later job or the release downloads with uses: "
                + RETRYING + " (#1672), or run it only on always() or failure():\n" + String.join("\n", offenders));
        assertTrue(retried > 0, "no workflow uses " + RETRYING);
    }

    /** The first attempt may fail; the retry runs only then, after a pause, overwrites it, and fails the job if it fails too. */
    @Test
    void theActionRetriesAFailedUploadOnce() throws IOException {
        var steps = JqYaml.parse(Files.readString(ACTION)).path("runs").path("steps").asList();
        assertEquals(3, steps.size(), "the action is an upload, a pause and a retry");
        var upload = steps.get(0);
        var retry = steps.get(2);
        assertTrue(isUpload(upload) && isUpload(retry), "both steps must be actions/upload-artifact");
        assertEquals(upload.pathText("uses", ""), retry.pathText("uses", ""), "both attempts use the same version");
        assertTrue(upload.pathBoolean("continue-on-error", false),
                "without continue-on-error the first failure ends the job before the retry");
        assertEquals("steps." + upload.pathText("id", "") + ".outcome=='failure'",
                retry.pathText("if", "").replace(" ", ""), "the retry runs only when the first attempt failed");
        assertEquals(retry.pathText("if", ""), steps.get(1).pathText("if", ""), "the pause runs only before a retry");
        assertFalse(retry.pathBoolean("continue-on-error", false), "two failed attempts must fail the job");
        assertTrue(retry.path("with").pathBoolean("overwrite", false),
                "the retry overwrites a partial artifact the first attempt left under its name");
        for (var input : upload.path("with").asMap().keySet()) {
            assertEquals(upload.path("with").pathText(input, ""), retry.path("with").pathText(input, ""),
                    "the retry must pass the same " + input);
        }
    }
}
