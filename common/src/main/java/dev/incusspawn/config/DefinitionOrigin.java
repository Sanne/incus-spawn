package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.mapper.JqIgnore;

import java.nio.file.Path;

/**
 * Where an {@link ImageDef} came from, which isx sets as it loads one and never reads from YAML.
 *
 * <p>A superclass because the generated mapping counts every field the class itself declares as
 * a known key, {@code @JqIgnore} or not: declared in {@link ImageDef}, a key named {@code source}
 * or {@code projectRoot} in a template would be dropped without the unknown-key warning (#1889).
 * An inherited field is not counted.
 */
abstract class DefinitionOrigin extends MappedDefinition {

    @JqIgnore
    private String source = "unknown";

    /**
     * The project directory of a project-local definition ({@code .incus-spawn/images/} in the
     * working directory), null for every trusted layer. Such a definition arrives with whatever
     * repository was cloned, so its host-resources are confined to this directory (#765).
     * Never read from YAML: a definition must not be able to vouch for itself.
     */
    @JqIgnore
    private Path projectRoot;

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Path getProjectRoot() { return projectRoot; }
    public void setProjectRoot(Path projectRoot) { this.projectRoot = projectRoot; }

    /** A template that sets either key itself is told why its value is not used. */
    @Override
    protected String hint(String key) {
        return switch (normalized(key)) {
            case "source", "projectroot", "project-root" -> "'" + key + "' is set by isx" + ImageDef.HostResource.SET_BY_ISX;
            default -> null;
        };
    }
}
