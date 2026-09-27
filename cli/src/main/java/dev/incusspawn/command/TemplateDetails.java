package dev.incusspawn.command;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.ImageDef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The effective settings of a template, resolved across its inheritance chain the way
 * {@link BuildCommand} applies them -- so the TUI detail view shows what a build produces,
 * not just what the leaf file declares. Where the build has a resolver, this calls it.
 *
 * @param type          container, vm or kvm (the nearest declaration wins; container if none)
 * @param gui           whether branches get GUI passthrough by default (the template's own flag)
 * @param workdir       where a shell starts, or null for the home directory
 * @param shellCommand  what a shell runs instead of the login shell, or null
 * @param defaultAction what Enter runs on a branch, or null for a shell
 * @param env           every layer's env entries, root first, with the layer that set each
 * @param accounts      credential account per config namespace, merged down the chain
 * @param skills        skill sources, deduplicated across the chain
 * @param skillRepos    skills catalogs that resolve bare skill names
 * @param packageRepos  extra package repositories, as {@code type:name}
 * @param removePackages packages removed by some layer (a child starts from its parent's image)
 * @param maskServices  systemd units masked by some layer
 * @param agentNotes    notes added to the agent's managed CLAUDE.md, root first
 * @param root          the root definition, which owns the base image
 */
record TemplateDetails(String type, boolean gui, String workdir, String shellCommand,
                       String defaultAction, List<LayerEnv> env, Map<String, String> accounts,
                       List<String> skills, List<String> skillRepos, List<String> packageRepos,
                       List<String> removePackages, List<String> maskServices,
                       List<String> agentNotes, ImageDef root) {

    record LayerEnv(String layer, EnvEntry entry) {}

    static TemplateDetails resolve(ImageDef def, Map<String, ImageDef> defs) {
        var chain = ImageDef.chain(def, defs);

        var type = ImageDef.resolveType(def, defs);
        String shellCommand = null;
        var env = new ArrayList<LayerEnv>();
        var skills = new LinkedHashSet<String>();
        var skillRepos = new LinkedHashSet<String>();
        var packageRepos = new LinkedHashSet<String>();
        var removePackages = new LinkedHashSet<String>();
        var maskServices = new LinkedHashSet<String>();
        var agentNotes = new ArrayList<String>();
        for (var layer : chain) {
            // A child is copied from its parent's instance, so the parent's stamped
            // shell-command carries over until a nearer layer sets its own.
            if (layer.getShellCommand() != null && !layer.getShellCommand().isBlank()) {
                shellCommand = layer.getShellCommand();
            }
            for (var entry : layer.getEnv()) env.add(new LayerEnv(layer.getName(), entry));
            skills.addAll(layer.getSkills().getList());
            if (layer.getSkills().getRepo() != null) skillRepos.add(layer.getSkills().getRepo());
            for (var repo : layer.getPackageRepos()) packageRepos.add(repo.getType() + ":" + repo.getName());
            removePackages.addAll(layer.getRemovePackages());
            maskServices.addAll(layer.getMaskServices());
            var note = layer.getAgentNote();
            if (note != null && !note.isBlank() && !agentNotes.contains(note.strip())) {
                agentNotes.add(note.strip());
            }
        }

        return new TemplateDetails(
                type != null ? type : "container",
                def.isGui(),
                resolveWorkdir(def, defs),
                shellCommand,
                BuildCommand.resolveEffectiveDefaultAction(def, defs),
                List.copyOf(env),
                ImageDef.resolveAccounts(def, defs),
                List.copyOf(skills), List.copyOf(skillRepos), List.copyOf(packageRepos),
                List.copyOf(removePackages), List.copyOf(maskServices),
                List.copyOf(agentNotes), chain.get(0));
    }

    /**
     * The workdir a build stamps, or -- when this layer resolves none -- the nearest ancestor's:
     * a child is copied from its parent's instance, and a null resolution leaves that stamp alone.
     */
    private static String resolveWorkdir(ImageDef def, Map<String, ImageDef> defs) {
        var own = BuildCommand.resolveEffectiveWorkdir(def, defs);
        if (own != null) return own;
        for (var ancestor : ImageDef.ancestors(def, defs)) {
            var inherited = BuildCommand.resolveEffectiveWorkdir(ancestor, defs);
            if (inherited != null) return inherited;
        }
        return null;
    }

    /** A human label for an instance type, as declared ({@code type:}) or stamped at build. */
    static String typeLabel(String type) {
        if (type == null || type.isBlank()) return "container";
        return switch (type) {
            case "vm" -> "virtual machine";
            case "kvm" -> "container with KVM passthrough";
            default -> type;
        };
    }

    /**
     * The mode a built template carries, from its stamp or, for builds older than the stamp,
     * from the Incus instance type. Null when unknown.
     */
    static String builtType(String instanceMode, String runtime) {
        if (instanceMode != null && !instanceMode.isBlank()) return instanceMode;
        if ("virtual-machine".equals(runtime)) return "vm";
        if ("container".equals(runtime)) return "container";
        return null;
    }

    /**
     * The type a built template carries when it differs from what its definition now resolves
     * to (so a rebuild would change it), or null when they agree or the built type is unknown.
     */
    static String staleBuiltType(String resolvedType, String instanceMode, String runtime) {
        var built = builtType(instanceMode, runtime);
        return built != null && !built.equals(resolvedType) ? built : null;
    }
}
