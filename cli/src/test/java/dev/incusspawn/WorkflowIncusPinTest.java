package dev.incusspawn;

import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI installed whatever Incus Zabbly's stable repository served on the day, so Incus 7.7 first
 * showed up as a red release run with no change in isx (#2720, #2733).
 * So every job installs Incus through {@code .github/scripts/install-incus.sh}, which installs the
 * version {@code scripts/zabbly-incus.env} pins, the one the upstream watch's {@code zabbly-incus}
 * item moves, and trusts the committed key the watch checks the repository against.
 */
class WorkflowIncusPinTest {

    private static final Path WORKFLOWS = Path.of("../.github/workflows");
    private static final Path SCRIPT = Path.of("../.github/scripts/install-incus.sh");
    private static final Path ACTIONS = Path.of("../.github/actions");
    private static final Path SCRIPTS = Path.of("../.github/scripts");
    private static final String INVOCATION = "sudo .github/scripts/install-incus.sh";
    private static final Path MANIFEST = Path.of("../upstream/manifest.yaml");
    private static final Pattern PINNED = Pattern.compile("(?m)^PINNED=\\(([^)]*)\\)$");
    private static final Pattern WORKFLOW_FILE = Pattern.compile(".*\\.ya?ml");

    @Test
    void everyWorkflowInstallsThePinnedIncus() throws IOException {
        var offenders = new ArrayList<String>();
        int pinned = 0;
        // A composite action or another script naming the repository would install unpinned too.
        for (var dir : List.of(ACTIONS, SCRIPTS)) {
            if (!Files.isDirectory(dir)) continue;
            try (var all = Files.walk(dir)) {
                for (var f : all.filter(Files::isRegularFile).filter(f -> !f.equals(SCRIPT)).sorted().toList()) {
                    if (Files.readString(f).contains("pkgs.zabbly.com")) offenders.add(f + " names Zabbly's repository itself");
                }
            }
        }
        try (var files = Files.list(WORKFLOWS)) {
            for (var file : files.filter(f -> WORKFLOW_FILE.matcher(f.getFileName().toString()).matches()).sorted().toList()) {
                if (Files.readString(file).contains("pkgs.zabbly.com")) {
                    offenders.add(file.getFileName() + " names Zabbly's repository itself");
                }
                var jobs = JqYaml.parse(Files.readString(file)).path("jobs");
                for (var job : jobs.asMap().entrySet()) {
                    boolean checkedOut = false;
                    for (var step : job.getValue().path("steps").asList()) {
                        checkedOut |= WorkflowCheckout.checksOut(step);
                        if (step.pathText("run", "").contains(INVOCATION)) {
                            pinned++;
                            if (!checkedOut) offenders.add(file.getFileName() + " " + job.getKey() + " installs Incus before the checkout");
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "install Incus with `" + INVOCATION + " [<package>...]` after the checkout (#2733):\n"
                + String.join("\n", offenders));
        assertTrue(pinned > 0, "no workflow runs " + INVOCATION);
    }

    @Test
    void theScriptInstallsThePinWithTheCommittedKey() throws IOException {
        var script = Files.readString(SCRIPT);
        assertTrue(script.contains("scripts/zabbly-incus.env"), "the script does not read the pin");
        // incus needs incus-base and incus-base needs incus-client at its own exact version, and apt
        // takes a dependency's newest build: the script pins what the watch proves, the manifest's
        // package and the packages it is installed with.
        var source = JqYaml.parse(Files.readString(MANIFEST)).path("items").path("zabbly-incus").path("source");
        var proven = new ArrayList<String>();
        proven.add(source.pathText("package", ""));
        source.path("with").asList().forEach(p -> proven.add(p.asText()));
        var pinned = PINNED.matcher(script);
        assertTrue(pinned.find(), "the script sets no PINNED=(...)");
        assertEquals(proven, List.of(pinned.group(1).trim().split("\\s+")),
                "the script's PINNED is not the zabbly-incus item's source.package and source.with");
        assertTrue(script.contains("\"${PINNED[@]/%/=$ZABBLY_INCUS_VERSION}\""), "the script does not install what it pins at the pinned version");
        assertTrue(script.contains("apt-install.sh"), "the script does not install through apt-install.sh");
        assertTrue(script.contains("upstream/keys/zabbly.asc"), "the script does not trust the committed key");
        assertFalse(script.contains("curl"), "the script downloads something, such as a key, at run time");
    }
}
