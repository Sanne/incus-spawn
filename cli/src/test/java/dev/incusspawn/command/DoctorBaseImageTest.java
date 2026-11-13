package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx doctor} names the templates and instances whose base image predates the one a rebuild
 * would use (#1657): a fix in a base image reaches only instances built after it, and the network
 * watchdog that restarted networkd every 30 s was found only by measuring the host's disk writes.
 */
class DoctorBaseImageTest {

    private static final String CURRENT_SHA = "aa".repeat(32);

    private static ImageDef def(String yaml) {
        try {
            return ImageDef.parseYaml(yaml);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static final Map<String, ImageDef> DEFS = Map.of(
            "tpl-minimal", def("""
                    name: tpl-minimal
                    image: fedora-44-base
                    image_url: https://example.org/{tag}/fedora-44-{arch}.tar.xz
                    image_tag: fedora-44-20261021
                    image_sha256: {x86_64: %s, aarch64: %s}
                    """.formatted(CURRENT_SHA, "cc".repeat(32))),
            "tpl-java", def("""
                    name: tpl-java
                    parent: tpl-minimal
                    """),
            "tpl-quarkus", def("""
                    name: tpl-quarkus
                    parent: tpl-java
                    """));

    private static Map<String, String> stamps(String type, String profile, String tag, String fingerprint) {
        var config = new HashMap<String, String>();
        config.put(Metadata.TYPE, type);
        config.put(Metadata.PROFILE, profile);
        if (tag != null) config.put(BaseImageAge.TAG, tag);
        config.put(BaseImageAge.FINGERPRINT, fingerprint);
        return config;
    }

    private static List<DoctorCommand.Finding> findings(FakeIncusDaemon daemon) {
        return DoctorCommand.baseImageFindings(daemon.client().listJsonConfig(), DEFS);
    }

    @Test
    void namesTheTemplatesOnAnOlderReleaseAndTheInstancesBranchedFromThem() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-minimal", stamps(Metadata.TYPE_BASE, "tpl-minimal", "fedora-44-20261021", CURRENT_SHA))
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)))
                .instance("quarkus-1", "container", "Running",
                        stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)))
                .container("fresh", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261021", CURRENT_SHA));
        var found = findings(daemon);
        assertEquals(1, found.size(), found.toString());
        var f = found.getFirst();
        // A routine newer release: a note, which does not fail doctor
        assertEquals(DoctorCommand.Status.NOTE, f.status());
        assertEquals("Base image older than fedora-44-20261021", f.label());
        assertEquals("(templates: tpl-java on fedora-44-20261015; instances: quarkus-1)", f.detail());
        assertNull(f.remediation().action(), "a rebuild is the user's to start");
        assertEquals("Rebuild to pick up image fixes: isx build tpl-java, then branch quarkus-1 again",
                f.remediation().description());
    }

    /**
     * A template on another line, another Fedora or channel, is a finding of its own (#1994): a
     * rebuild moves it to the built-in's line, but it is neither older nor newer than that.
     */
    @Test
    void aTemplateOnAnotherLineIsNamedApartFromTheOlderOnes() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-minimal", stamps(Metadata.TYPE_BASE, "tpl-minimal", "fedora-44-20261021", CURRENT_SHA))
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-next-20261101", "ee".repeat(32)))
                .container("old", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)));
        var found = findings(daemon);
        assertEquals(List.of("Base image older than fedora-44-20261021", "Base image on another line than fedora-44-20261021"),
                found.stream().map(DoctorCommand.Finding::label).toList(), found.toString());
        var other = found.get(1);
        assertEquals(DoctorCommand.Status.NOTE, other.status());
        assertEquals("(templates: tpl-java on fedora-44-next-20261101)", other.detail());
        assertEquals("Rebuild to pick up image fixes: isx build tpl-java", other.remediation().description());
    }

    @Test
    void aReleaseBeforeAKnownFixIsAWarningThatNamesTheFix() {
        // Releases before fedora-44-20261009 restart systemd-networkd every 30 seconds
        var daemon = new FakeIncusDaemon()
                .container("tpl-minimal", stamps(Metadata.TYPE_BASE, "tpl-minimal", "fedora-44-20261021", CURRENT_SHA))
                .container("quarkus-1", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261001", "ee".repeat(32)))
                .container("later", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)));
        var found = findings(daemon);
        // One finding for the release, so the rebuild is planned once: a WARN for what lacks the fix
        assertEquals(1, found.size(), found.toString());
        var warn = found.getFirst();
        assertEquals(DoctorCommand.Status.WARN, warn.status());
        assertEquals("Base image older than fedora-44-20261021", warn.label());
        assertEquals("(instances: quarkus-1, later; before fedora-44-20261009 its network watchdog restarts"
                + " systemd-networkd every 30 seconds: quarkus-1)", warn.detail());
        assertEquals("Rebuild to pick up image fixes: isx build tpl-java, then branch quarkus-1, later again",
                warn.remediation().description());
    }

    @Test
    void aNotesRemediationIsPrintedUnderItSinceOnlyAnIssuesIsOffered() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-20261021", CURRENT_SHA))
                .container("old", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)));
        assertEquals("""
                · Base image older than fedora-44-20261021 (instances: old)
                    Branch old again to pick up image fixes
                """, DoctorCommand.formatFindings(findings(daemon)));
    }

    @Test
    void aParentIsBuiltBeforeItsChildWhateverTheListingOrder() {
        // isx build builds in the order given, and tpl-java is a copy of tpl-minimal as built:
        // child first would copy the old root and leave tpl-java on the old image
        var daemon = new FakeIncusDaemon()
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)))
                .container("tpl-minimal", stamps(Metadata.TYPE_BASE, "tpl-minimal", "fedora-44-20261015", "ee".repeat(32)))
                .container("dev-1", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)));
        var f = findings(daemon).getFirst();
        assertEquals("(templates: tpl-minimal on fedora-44-20261015, tpl-java on fedora-44-20261015; instances: dev-1)",
                f.detail());
        assertEquals("Rebuild to pick up image fixes: isx build tpl-minimal tpl-java, then branch dev-1 again",
                f.remediation().description());
    }

    @Test
    void aLayerUnderAnOlderTemplateThatIsNotKnownCurrentIsBuiltFirst() {
        // tpl-minimal's release was not recorded: building tpl-java alone would copy it as it is
        var daemon = new FakeIncusDaemon()
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)))
                .container("tpl-minimal", stamps(Metadata.TYPE_BASE, "tpl-minimal", null, "ee".repeat(32)));
        var found = findings(daemon);
        assertEquals("Rebuild to pick up image fixes: isx build tpl-minimal tpl-java",
                found.getFirst().remediation().description());
        assertEquals(DoctorCommand.Finding.note("Base images", "(release not recorded: tpl-minimal)"), found.get(1));
    }

    @Test
    void aDeepChainIsBuiltRootFirstSkippingTheLayersAlreadyCurrent() {
        var deleted = new FakeIncusDaemon()
                .container("tpl-quarkus", stamps(Metadata.TYPE_BASE, "tpl-quarkus", "fedora-44-20261015", "ee".repeat(32)))
                .container("q-1", stamps(Metadata.TYPE_CLONE, "tpl-quarkus", "fedora-44-20261015", "ee".repeat(32)));
        // Neither tpl-java nor tpl-minimal is built: every layer is
        assertEquals("Rebuild to pick up image fixes: isx build tpl-minimal tpl-java tpl-quarkus, then branch q-1 again",
                findings(deleted).getFirst().remediation().description());
        var rootCurrent = new FakeIncusDaemon()
                .container("tpl-minimal", stamps(Metadata.TYPE_BASE, "tpl-minimal", "fedora-44-20261021", CURRENT_SHA))
                .container("tpl-quarkus", stamps(Metadata.TYPE_BASE, "tpl-quarkus", "fedora-44-20261015", "ee".repeat(32)))
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)));
        assertEquals("Rebuild to pick up image fixes: isx build tpl-java tpl-quarkus",
                findings(rootCurrent).getFirst().remediation().description());
    }

    @Test
    void anInstanceWhoseTemplateWasRebuiltIsBranchedAgain() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-20261021", CURRENT_SHA))
                .container("old", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261012", "ee".repeat(32)));
        var f = findings(daemon).getFirst();
        assertEquals("(instances: old)", f.detail());
        // tpl-java is current already: no rebuild to send the user after
        assertEquals("Branch old again to pick up image fixes", f.remediation().description());
    }

    @Test
    void aProjectTemplateIsCreatedAgainRatherThanBranched() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)))
                .container("myapp", stamps(Metadata.TYPE_PROJECT, "tpl-java", "fedora-44-20261015", "ee".repeat(32)))
                .container("other", stamps(Metadata.TYPE_PROJECT, "tpl-java", "fedora-44-20261015", "ee".repeat(32)))
                .container("myapp-1", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)));
        var f = findings(daemon).getFirst();
        assertEquals("(templates: tpl-java on fedora-44-20261015; projects: myapp, other; instances: myapp-1)", f.detail());
        // project create takes one name, read with that project's incus-spawn.yaml
        // tpl-minimal is not built: it is built first, from the release a rebuild would use
        assertEquals("Rebuild to pick up image fixes: isx build tpl-minimal tpl-java,"
                + " then run 'isx project create myapp' from its project's directory,"
                + " then run 'isx project create other' from its project's directory,"
                + " then branch myapp-1 again", f.remediation().description());
    }

    @Test
    void aProjectOnAnOldImageOverACurrentTemplateIsCreatedAgainBeforeItsBranches() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-20261021", CURRENT_SHA))
                .container("myapp-1", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)))
                .container("myapp", stamps(Metadata.TYPE_PROJECT, "tpl-java", "fedora-44-20261015", "ee".repeat(32)));
        assertEquals("Run 'isx project create myapp' from its project's directory, then branch myapp-1 again"
                        + " to pick up image fixes",
                findings(daemon).getFirst().remediation().description());
    }

    @Test
    void anInstanceWhoseTemplateIsGoneOrUnknownHasItBuiltFirst() {
        // tpl-java deleted: branching again from it would fail
        var gone = new FakeIncusDaemon()
                .container("old", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)));
        assertEquals("Rebuild to pick up image fixes: isx build tpl-minimal tpl-java, then branch old again",
                findings(gone).getFirst().remediation().description());
        // Built before isx recorded the release: not known to be current
        var unknown = new FakeIncusDaemon()
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", null, "ee".repeat(32)))
                .container("old", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261015", "ee".repeat(32)));
        assertEquals("Rebuild to pick up image fixes: isx build tpl-minimal tpl-java, then branch old again",
                findings(unknown).getFirst().remediation().description());
    }

    @Test
    void allCurrentIsOneOkAndUnrecordedOnesAreANote() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-java", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-20261021", CURRENT_SHA))
                .container("a", stamps(Metadata.TYPE_CLONE, "tpl-java", "fedora-44-20261030", "ff".repeat(32)));
        assertEquals(List.of(DoctorCommand.Finding.ok("Base images", "(2 checked, none older than a rebuild would use)")),
                findings(daemon));

        daemon.container("ancient", stamps(Metadata.TYPE_CLONE, "tpl-java", null, "ee".repeat(32)));
        assertEquals(List.of(DoctorCommand.Finding.note("Base images", "(release not recorded: ancient)")),
                findings(daemon));

        // A tag isx list shows is never called unrecorded
        daemon.container("odd", stamps(Metadata.TYPE_CLONE, "tpl-java", "nightly", "ee".repeat(32)));
        assertEquals(List.of(DoctorCommand.Finding.note("Base images",
                        "(release not recorded: ancient; release not comparable: odd on nightly (against fedora-44-20261021))")),
                findings(daemon));
    }

    @Test
    void failedBuildsRebuildCopiesAndUnknownTemplatesAreNotChecked() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-java-rebuilding", stamps(Metadata.TYPE_BASE, "tpl-java", "fedora-44-20261001", "ee".repeat(32)))
                .container("broken", stamps(Metadata.TYPE_FAILED_BUILD, "tpl-java", "fedora-44-20261001", "ee".repeat(32)))
                .container("elsewhere", stamps(Metadata.TYPE_CLONE, "tpl-project", "fedora-44-20261001", "ee".repeat(32)))
                .container("not-ours", Map.of(BaseImageAge.TAG, "fedora-44-20261001"));
        assertEquals(List.of(), findings(daemon));
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(IsolatedHome.class)
    void theSharedChecksReadTheListingThroughTheDoctorsIncus() {
        // The built-in tpl-minimal, on a release before the one it names
        var expected = ImageDef.loadBuiltinByName("tpl-minimal").getImageTag();
        var older = BaseImageAge.ReleaseTag.parse(expected).line() + "-20250101";
        var daemon = new FakeIncusDaemon().pool("cow", "btrfs")
                .container("tpl-minimal", stamps(Metadata.TYPE_BASE, "tpl-minimal", older, "ee".repeat(32)));
        var incus = daemon.client();
        var doctor = new DoctorCommand() {
            @Override
            dev.incusspawn.incus.IncusClient incus() {
                return incus;
            }
        };
        var findings = new java.util.ArrayList<DoctorCommand.Finding>();
        doctor.runSharedChecks(findings);
        var base = findings.stream().filter(f -> f.label().startsWith("Base image")).toList();
        assertEquals(List.of("Base image older than " + expected), base.stream().map(DoctorCommand.Finding::label).toList(),
                findings.toString());
        assertEquals("(templates: tpl-minimal on " + older + ")", base.getFirst().detail());
        assertFalse(base.getFirst().status().isProblem(), "a routine release is not an issue to address");
    }
}
