package dev.incusspawn.command;

import dev.incusspawn.config.BuildSource;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code isx doctor} names each built template with no loaded definition (#2057), which
 * {@code isx build} refuses: a rename or a gone file is a warning, and a branch is no template.
 */
class DoctorMissingDefinitionTest {

    private static final String FILE = "/nonexistent/images/presentation.yaml";

    private static ImageDef def(String name) {
        var def = new ImageDef();
        def.setName(name);
        def.setSource(FILE);
        return def;
    }

    private static String stamp(String name, String file) {
        return new BuildSource(Map.of(name, def(name)), Map.of(), Map.of(), Map.of(name, file)).toJson();
    }

    @Test
    void aRenamedTemplateIsAWarningNamingTheNewOne() {
        var daemon = new FakeIncusDaemon()
                .container("tpl-new", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_SOURCE, stamp("tpl-new", FILE)))
                .container("tpl-old", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.BUILD_SOURCE, stamp("tpl-old", FILE)))
                .container("tpl-gone", Map.of(Metadata.TYPE, Metadata.TYPE_BASE,
                        Metadata.BUILD_SOURCE, stamp("tpl-gone", "/nonexistent/images/gone.yaml")))
                // A branch carries its template's stamp, which does not define the branch
                .container("my-branch", Map.of(Metadata.TYPE, Metadata.TYPE_CLONE, Metadata.BUILD_SOURCE, stamp("tpl-new", FILE)));

        var findings = DoctorCommand.missingDefinitionFindings(daemon.client().listJsonConfig(),
                Map.of("tpl-new", def("tpl-new")));

        assertEquals(2, findings.size(), findings.toString());
        var byLabel = new java.util.HashMap<String, DoctorCommand.Finding>();
        findings.forEach(f -> byLabel.put(f.label(), f));
        var renamed = byLabel.get("Template tpl-old has no definition here");
        assertEquals(DoctorCommand.Status.WARN, renamed.status());
        assertTrue(renamed.detail().contains(FILE + " now defines tpl-new. Build tpl-new instead"), renamed.detail());
        var gone = byLabel.get("Template tpl-gone has no definition here");
        assertEquals(DoctorCommand.Status.WARN, gone.status());
        assertTrue(gone.detail().contains("/nonexistent/images/gone.yaml no longer exists"), gone.detail());
    }
}
