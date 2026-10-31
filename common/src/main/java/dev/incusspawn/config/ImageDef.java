package dev.incusspawn.config;

import dev.incusspawn.Warnings;
import dev.incusspawn.incus.InstanceNames;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.tool.ToolDef;
import dev.incusspawn.tool.ToolRefListConverter;
import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.mapper.JqConverter;
import io.hyperfoil.tools.jjq.mapper.JqInclude;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Definition of a template image, loaded from YAML.
 * <p>
 * Resolution order: built-in (classpath) → user ({@code ~/.config/incus-spawn/images/})
 * → project-local ({@code .incus-spawn/images/}). Later definitions with the
 * same name override earlier ones.
 * <p>
 * Overriding across layers is intentional and supported. Two files that declare
 * the same {@code name:} <em>within a single directory</em>, however, are always a
 * mistake (usually a copy-paste that forgot to update {@code name:}); those are
 * reported as {@link NameConflict}s by {@link #loadAllWithConflicts()} so callers
 * can refuse to build and insist the user disambiguate.
 */
@JqMapped
public class ImageDef extends DefinitionOrigin {

    private static final String RESOURCE_DIR = "images/";

    // Hardcoded list of built-in image filenames. Classpath directory scanning
    // is unreliable in GraalVM native image, so we enumerate explicitly.
    // Update this list when adding a new built-in image definition.
    public static final List<String> BUILTIN_FILES = List.of(
            "minimal.yaml", "dev.yaml", "java.yaml"
    );

    private static final Path PROJECT_IMAGES_DIR = Path.of(".incus-spawn/images");

    public static Path userImagesDir() { return SpawnConfig.configDir().resolve("images"); }

    public static Path projectImagesDir() { return PROJECT_IMAGES_DIR; }

    public static ImageDef loadBuiltinByName(String name) {
        for (var filename : BUILTIN_FILES) {
            var def = loadResource(RESOURCE_DIR + filename, w -> {});
            if (def != null && name.equals(def.getName())) return def;
        }
        return null;
    }

    /**
     * Parse an image definition from a YAML file on disk (via jjq).
     */
    public static ImageDef parseFile(Path file) throws IOException {
        return Jjq.imageDef(file);
    }

    public static ImageDef parseYaml(String yaml) throws IOException {
        return Jjq.imageDef(yaml);
    }

    /**
     * Derive the YAML filename from a template name (e.g. {@code tpl-java} -> {@code java.yaml}).
     */
    public static String filenameForName(String name) {
        return (name.startsWith("tpl-") ? name.substring(4) : name) + ".yaml";
    }

    private String name;
    private String description = "";
    private String image = "images:fedora/44";
    @JqName("image_url")
    private String imageUrl;
    @JqName("image_tag")
    private String imageTag;
    @JqName("image_sha256")
    private Map<String, String> imageSha256;
    @JqName("vm_image_url")
    private String vmImageUrl;
    @JqName("vm_image_sha256")
    private Map<String, String> vmImageSha256;
    private String parent;
    private List<String> packages = List.of();
    @JqName("remove_packages")
    private List<String> removePackages = List.of();
    @io.hyperfoil.tools.jjq.mapper.JqConverter(ToolRefListConverter.class)
    private List<ToolDef.ToolRef> tools = List.of();
    private List<RepoEntry> repos = List.of();
    @io.hyperfoil.tools.jjq.mapper.JqConverter(SkillsDefConverter.class)
    private SkillsDef skills = SkillsDef.EMPTY;
    @JqName("package_repos")
    private List<PackageRepo> packageRepos = List.of();
    @JqName("host-resources")
    private List<HostResource> hostResources = List.of();
    @JqName("mask_services")
    private List<String> maskServices = List.of();
    private boolean gui;
    private boolean pinned;
    private String type;
    private String workdir;
    @JqName("shell-command")
    private String shellCommand;
    @io.hyperfoil.tools.jjq.mapper.JqConverter(EnvEntryListConverter.class)
    private List<EnvEntry> env = List.of();
    @JqName("default-action")
    private String defaultAction;
    @JqName("agent_note")
    private String agentNote;
    /**
     * Credential account per config namespace, e.g. {@code {claude: work, github: acme-bot}}.
     * Overrides the namespace's global {@code default}; overridden in turn by an instance's
     * own selection. Merged key-by-key down the inheritance chain, so a child template
     * re-points one namespace without restating the rest.
     */
    private Map<String, String> accounts = Map.of();
    /**
     * Set when the template is on its way out: {@code true}, or what to use instead. It still
     * builds, but every build of it or of a template naming it as parent warns
     * ({@link #deprecationWarning}). Left out of {@link #contentFingerprint}: marking a template
     * deprecated, or moving to an identical copy that is not, must not rebuild anything.
     */
    @JqInclude(JqInclude.Include.NON_NULL)
    private String deprecated;

    /**
     * Whether a branch of this template is a coordinator that may drive isx over MCP, and what it
     * may control ({@code coordinator:}, #1812). Never baked into the image, so left out of
     * {@link #contentFingerprint}: editing it rebuilds nothing.
     */
    @JqName("coordinator")
    @JqInclude(JqInclude.Include.NON_NULL)
    private Coordinator coordinator;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getImageTag() { return imageTag; }
    public void setImageTag(String imageTag) { this.imageTag = imageTag; }
    public Map<String, String> getImageSha256() { return imageSha256; }
    public void setImageSha256(Map<String, String> imageSha256) { this.imageSha256 = imageSha256; }
    public String getVmImageUrl() { return vmImageUrl; }
    public void setVmImageUrl(String vmImageUrl) { this.vmImageUrl = vmImageUrl; }
    public Map<String, String> getVmImageSha256() { return vmImageSha256; }
    public void setVmImageSha256(Map<String, String> vmImageSha256) { this.vmImageSha256 = vmImageSha256; }
    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }
    public List<String> getPackages() { return packages; }
    public void setPackages(List<String> packages) { this.packages = items(packages); }
    public List<String> getRemovePackages() { return removePackages; }
    public void setRemovePackages(List<String> removePackages) { this.removePackages = items(removePackages); }
    public List<ToolDef.ToolRef> getTools() { return tools; }
    public void setTools(List<ToolDef.ToolRef> tools) { this.tools = items(tools); }
    public List<RepoEntry> getRepos() { return repos; }
    public void setRepos(List<RepoEntry> repos) { this.repos = items(repos); }
    public SkillsDef getSkills() { return skills; }
    public void setSkills(SkillsDef skills) { this.skills = skills; }
    public List<PackageRepo> getPackageRepos() { return packageRepos; }
    public void setPackageRepos(List<PackageRepo> packageRepos) { this.packageRepos = items(packageRepos); }
    public List<HostResource> getHostResources() { return hostResources; }
    public void setHostResources(List<HostResource> hostResources) { this.hostResources = items(hostResources); }
    public List<String> getMaskServices() { return maskServices; }
    public void setMaskServices(List<String> maskServices) { this.maskServices = items(maskServices); }
    public boolean isGui() { return gui; }
    public void setGui(boolean gui) { this.gui = gui; }
    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public MachineType machineType() { return "vm".equals(type) ? MachineType.VM : MachineType.CONTAINER; }
    public boolean isVm() { return "vm".equals(type); }
    public boolean isKvm() { return "kvm".equals(type); }
    public String getWorkdir() { return workdir; }
    public void setWorkdir(String workdir) { this.workdir = workdir; }
    public String getShellCommand() { return shellCommand; }
    public void setShellCommand(String shellCommand) { this.shellCommand = shellCommand; }
    public List<EnvEntry> getEnv() { return env; }
    public void setEnv(List<EnvEntry> env) { this.env = items(env); }
    public String getDefaultAction() { return defaultAction; }
    public void setDefaultAction(String defaultAction) { this.defaultAction = defaultAction; }
    public String getAgentNote() { return agentNote; }
    public void setAgentNote(String agentNote) { this.agentNote = agentNote; }
    public Map<String, String> getAccounts() { return accounts; }
    public void setAccounts(Map<String, String> accounts) {
        this.accounts = accounts == null ? Map.of() : Map.copyOf(accounts);
    }
    public Coordinator getCoordinator() { return coordinator; }
    public void setCoordinator(Coordinator coordinator) { this.coordinator = coordinator; }
    public String getDeprecated() { return deprecated; }
    public void setDeprecated(String deprecated) { this.deprecated = deprecated; }
    /** Whether the definition says {@code deprecated:} with anything but {@code false}. */
    public boolean isDeprecated() {
        return deprecated != null && !deprecated.isBlank() && !"false".equalsIgnoreCase(deprecated.strip());
    }
    /** Whether this definition ships with isx itself, as opposed to one the user wrote. */
    public boolean isBuiltIn() { return "built-in".equals(getSource()); }

    /**
     * A list as mapped from YAML, without its empty items: a bare '-' (or '~') arrives as null
     * and declares nothing, so no reader has to skip it. An absent or empty key is no list.
     */
    static <T> List<T> items(List<T> list) {
        return list == null ? List.of() : list.stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * Groups the skills catalog repo and skill list under a single {@code skills} key.
     * <p>
     * Supports two YAML forms:
     * <pre>
     * # Object form (with optional repo):
     * skills:
     *   repo: myorg/claude-skills
     *   list:
     *     - security-review
     *     - xixu-me/skills@xget
     *
     * # List shorthand (no repo):
     * skills:
     *   - xixu-me/skills@xget
     *   - myorg/catalog
     * </pre>
     */
    public static class SkillsDef extends MappedDefinition {
        public static final SkillsDef EMPTY = new SkillsDef(null, List.of());

        private final String repo;
        private final List<String> list;

        public SkillsDef(String repo, List<String> list) {
            this.repo = repo;
            this.list = items(list);
        }

        /** Default skills catalog, used to resolve bare skill names (e.g. {@code security-review}). */
        public String getRepo() { return repo; }

        /** Skill sources declared in this image. */
        public List<String> getList() { return list; }

        @Override
        protected List<String> knownKeys() { return List.of("repo", "list"); }
    }

    @JqMapped
    public static class RepoEntry extends MappedDefinition {
        private String url;
        @JqName("host-source")
        private String hostSource;
        private String path;
        private String branch;
        @JqConverter(IntegerConverter.class)
        private Integer depth;
        @JqConverter(CommandListConverter.class)
        private List<String> prime = List.of();

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getHostSource() { return hostSource; }
        public void setHostSource(String hostSource) { this.hostSource = hostSource; }
        public boolean isHostSource() { return hostSource != null; }
        public String sourceDescription() { return isHostSource() ? hostSource : url; }
        public String sourceName() {
            if (!isHostSource()) return dev.incusspawn.git.GitRemoteUtils.repoNameFromUrl(url);
            var filename = Path.of(hostSource).getFileName();
            var name = filename == null ? "" : filename.toString();
            return name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
        }
        /** Resolve only explicit, unambiguous host paths; file:// remains a guest URL. */
        public Path hostSourcePath() {
            var resolved = Path.of(HostResourceSetup.expandHostTilde(hostSource));
            if (!resolved.isAbsolute()) throw new IllegalArgumentException("host-source requires an absolute or ~/ path");
            return resolved.normalize();
        }
        public String sourceProblem() {
            if (!isHostSource()) return null;
            if (hostSource.isBlank()) return "host-source must not be blank";
            if (url != null) return "use either url or host-source, not both";
            if (depth != null) return "host-source exports full branch and tag history; depth is not supported";
            try { hostSourcePath(); }
            catch (IllegalArgumentException e) { return e.getMessage(); }
            return null;
        }
        public String getPath() {
            if (path != null) return path;
            if (sourceDescription() == null) return null;
            var name = sourceName();
            return name.isEmpty() ? null : "~/" + name;
        }
        public void setPath(String path) { this.path = path; }
        public String getBranch() { return branch; }
        public void setBranch(String branch) { this.branch = branch; }
        public boolean hasBranch() { return branch != null && !branch.isBlank(); }
        /** How many commits of history a clone keeps, or null for all of it (#1344). */
        public Integer getDepth() { return depth; }
        public void setDepth(Integer depth) { this.depth = depth; }
        /** The commands run in turn in the clone, each stopping the rest when it fails (#1745). */
        public List<String> getPrime() { return prime; }
        public void setPrime(List<String> prime) { this.prime = items(prime).stream().filter(c -> !c.isBlank()).toList(); }
        public boolean hasPrime() { return !prime.isEmpty(); }
        /** The commands as one line for people, joined as {@code &&} would run them, or null for none. */
        public String primeText() { return hasPrime() ? String.join(" && ", prime) : null; }
        /**
         * One command as written, so a template that has one keeps the fingerprint it was built with.
         * Several are joined with NUL, which no command can hold, so no two lists read the same.
         */
        String primeFingerprint() {
            return prime.isEmpty() ? "null" : String.join("\0", prime);
        }
    }

    /**
     * The {@code coordinator:} block (#1812). {@code control-over} lists the templates a branch of
     * this template may control over MCP, of those {@code mcp.yaml} approves. Every branch is such
     * a coordinator, unless {@code --no-mcp-client} says otherwise, when
     * {@code isx-control-capability} is true, which it is by default exactly when that list is not
     * empty. An object, so per-coordinator limits can join it as keys that only narrow
     * {@code mcp.yaml}'s.
     */
    @JqInclude(JqInclude.Include.NON_NULL)
    @JqMapped
    public static class Coordinator extends MappedDefinition {
        @JqName("isx-control-capability")
        private Boolean isxControlCapability;
        /** Null when this layer does not say, so a parent's list applies. */
        @JqName("control-over")
        private List<String> controlOver;

        public Boolean getIsxControlCapability() { return isxControlCapability; }
        public void setIsxControlCapability(Boolean isxControlCapability) { this.isxControlCapability = isxControlCapability; }
        public List<String> getControlOver() { return controlOver; }
        public void setControlOver(List<String> controlOver) { this.controlOver = controlOver == null ? null : items(controlOver); }

        /** Every key, though the mapping writes only those set. */
        @Override
        protected List<String> knownKeys() { return List.of("isx-control-capability", "control-over"); }
    }

    @JqMapped
    public static class PackageRepo extends MappedDefinition {
        private String type;
        private String name;

        public PackageRepo() {}

        public PackageRepo(String type, String name) {
            this.type = type;
            this.name = name;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @JqMapped
    public static class HostResource extends MappedDefinition {
        private String source;
        private String path;
        private String mode = "readonly";
        /**
         * Set by {@link HostResourceSetup#collectEffective} (never by the user: any value in
         * YAML is discarded there) when the declaring template is project-local. The source is
         * then an absolute real path that must stay inside this directory every time it is used.
         */
        @JqName("confined-to")
        @JqInclude(JqInclude.Include.NON_NULL)
        private String confinedTo;
        /** What a warning about a file's {@code confined-to} ends with. */
        static final String SET_BY_ISX = ", a file's value is ignored (#765)";
        private static final String CONFINED_TO_IS_ISXS = "'confined-to' is set by isx" + SET_BY_ISX;

        /** Keys that name where a resource goes, which is {@code path:}; lower case, '-' for '_'. */
        private static final Set<String> PATH_LIKE = Set.of("path", "target", "target-path", "dest", "dst",
                "destination", "to", "mount", "mountpoint", "mount-point", "container", "container-path");

        public HostResource() {}

        public HostResource(String source, String path, String mode) {
            this.source = source;
            this.path = path;
            this.mode = mode != null ? mode : "readonly";
        }

        public HostResource(String source, String path, String mode, String confinedTo) {
            this(source, path, mode);
            this.confinedTo = confinedTo;
        }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getConfinedTo() { return confinedTo; }
        public void setConfinedTo(String confinedTo) { this.confinedTo = confinedTo; }

        /** Not {@code confined-to}, which is isx's own. */
        @Override
        protected List<String> knownKeys() { return List.of("source", "path", "mode"); }

        /**
         * A key naming where the resource goes is {@code path:}, unless the entry already has one
         * (#1543). Another spelling of {@code confined-to} is isx's own key too (#765).
         */
        @Override
        protected String hint(String key) {
            var normalized = normalized(key);
            if (normalized.equals("confined-to")) return CONFINED_TO_IS_ISXS;
            return source != null && path == null && PATH_LIKE.contains(normalized)
                    ? "use 'path:' to set where it goes in the instance" : null;
        }
    }

    /**
     * {@code vm:} is the old spelling of {@code type: vm}, which no mapping reads. Only a root
     * template with no {@code type:} is sure to build a container: a child's type may come from
     * its parent, which this one file does not know. The keys isx sets are {@link DefinitionOrigin}'s.
     */
    @Override
    protected String hint(String key) {
        if (!"vm".equalsIgnoreCase(key)) return super.hint(key);
        var hint = "a virtual machine is 'type: vm'";
        return type == null && isRoot() ? hint + "; without it this builds a container" : hint;
    }

    /** With {@code deprecated}, which the mapping writes only when it is set. */
    @Override
    protected List<String> knownKeys() {
        var keys = new ArrayList<>(super.knownKeys());
        if (!keys.contains("deprecated")) keys.add("deprecated");
        if (!keys.contains("coordinator")) keys.add("coordinator");
        return keys;
    }

    /**
     * One warning per key isx ignores in this definition as read from its file, each named by where
     * it is (#1546). {@code confined-to:} in a host resource is isx's own, so a file's value is
     * discarded too (#765).
     */
    public List<String> unknownKeyWarnings() {
        var walk = new Walk().part("", this)
                .each("repos", repos)
                .each("package_repos", packageRepos)
                .each("host-resources", hostResources)
                .each("env", env)
                .part("skills", skills)
                .part("coordinator", coordinator);
        for (int i = 0; i < hostResources.size(); i++) {
            if (hostResources.get(i).getConfinedTo() != null) {
                walk.add("'host-resources[" + i + "].confined-to' is set by isx" + HostResource.SET_BY_ISX);
            }
        }
        return walk.warnings();
    }

    public String contentFingerprint(Map<String, String> toolFingerprints) {
        var sb = new StringBuilder();
        sb.append("image=").append(image).append('\n');
        if (imageUrl != null) sb.append("image_url=").append(imageUrl).append('\n');
        if (imageTag != null) sb.append("image_tag=").append(imageTag).append('\n');
        if (imageSha256 != null) {
            imageSha256.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("image_sha256.").append(e.getKey())
                            .append('=').append(e.getValue()).append('\n'));
        }
        if (vmImageUrl != null) sb.append("vm_image_url=").append(vmImageUrl).append('\n');
        if (vmImageSha256 != null) {
            vmImageSha256.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append("vm_image_sha256.").append(e.getKey())
                            .append('=').append(e.getValue()).append('\n'));
        }
        sb.append("parent=").append(parent != null ? parent : "").append('\n');
        // The selected account decides what gets baked into the image: which auth mode
        // ClaudeSetup writes into isx-env.sh, and which GitHub identity GhSetup resolves
        // into .gitconfig. Re-pointing an account must therefore rebuild, not reuse.
        accounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("account.").append(e.getKey())
                        .append('=').append(e.getValue()).append('\n'));
        packages.stream().sorted().forEach(p -> sb.append("pkg=").append(p).append('\n'));
        // Removed before installing, so baked in too; likewise no line when there is none.
        removePackages.stream().distinct().sorted().forEach(p -> sb.append("rmpkg=").append(p).append('\n'));
        // Baked into the image; no line when none is masked, so such a template keeps its fingerprint.
        maskServices.stream().distinct().sorted().forEach(m -> sb.append("mask=").append(m).append('\n'));
        for (var t : tools.stream().sorted(java.util.Comparator.comparing(ToolDef.ToolRef::getName)).toList()) {
            sb.append("tool=").append(t.getName());
            var fp = toolFingerprints.get(t.getName());
            if (fp != null && !fp.isEmpty()) sb.append(':').append(fp);
            // Include parameter values in fingerprint
            if (!t.getParams().isEmpty()) {
                t.getParams().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(',').append(e.getKey()).append('=').append(e.getValue()));
            }
            sb.append('\n');
        }
        packageRepos.stream()
                .sorted(java.util.Comparator.<PackageRepo, String>comparing(r -> String.valueOf(r.getType()))
                        .thenComparing(r -> String.valueOf(r.getName())))
                .forEach(r -> sb.append("pkgrepo=").append(r.getType()).append(',').append(r.getName()).append('\n'));
        repos.stream()
                .sorted(java.util.Comparator.<RepoEntry, String>comparing(r -> String.valueOf(r.getUrl()))
                        .thenComparing(r -> String.valueOf(r.getPath())))
                .forEach(r -> sb.append("repo=").append(r.getUrl()).append(',').append(r.getPath())
                        .append(',').append(r.getBranch()).append(',').append(r.primeFingerprint())
                        // Only when set, so a template without one keeps the fingerprint it was built with.
                        .append(r.getDepth() != null ? ",depth=" + r.getDepth() : "")
                        .append(r.isHostSource() ? ",host-source=" + r.getHostSource() : "").append('\n'));
        if (skills.getRepo() != null) sb.append("skills-repo=").append(skills.getRepo()).append('\n');
        skills.getList().stream().sorted().forEach(s -> sb.append("skill=").append(s).append('\n'));
        for (var hr : hostResources) {
            sb.append("hr=").append(hr.getSource()).append(',').append(hr.getPath())
                    .append(',').append(hr.getMode());
            if ("copy".equals(hr.getMode())
                    && !hr.getSource().startsWith("http://") && !hr.getSource().startsWith("https://")) {
                try {
                    var resolved = Path.of(HostResourceSetup.expandHostTilde(hr.getSource()));
                    if (Files.isRegularFile(resolved)) {
                        sb.append(',').append(sha256hexFile(resolved));
                    }
                } catch (IOException ignored) {
                }
            }
            sb.append('\n');
        }
        env.stream()
                .map(EnvEntry::fingerprintString)
                .sorted()
                .forEach(e -> sb.append(e).append('\n'));
        if (gui) sb.append("gui=true\n");
        // Included (unlike description) because the note is baked into the image:
        // a stale footgun warning is worse than an extra rebuild.
        if (agentNote != null && !agentNote.isBlank()) {
            sb.append("agent_note=").append(agentNote).append('\n');
        }
        if (type != null) sb.append("type=").append(type).append('\n');
        if (workdir != null && !workdir.isEmpty()) sb.append("workdir=").append(workdir).append('\n');
        if (shellCommand != null && !shellCommand.isEmpty()) sb.append("shell-command=").append(shellCommand).append('\n');
        return sha256hex(sb.toString());
    }

    private static String sha256hexFile(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = new java.security.DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Return the ordered list of ancestor images, starting from the immediate
     * parent and walking up to the root. Stops if a name is missing from
     * {@code defs}, or before a layer it has already seen: loading refuses a parent cycle, but a
     * build-source snapshot merged in afterwards can still close one (#1443).
     */
    public static List<ImageDef> ancestors(ImageDef start, Map<String, ImageDef> defs) {
        var result = new ArrayList<ImageDef>();
        var cycle = walkParents(start, defs, result);
        if (cycle != null) Warnings.warn(cycle + "; every chain through it stops before repeating a layer");
        return result;
    }

    /**
     * The parent cycle the chain of {@code start} runs into, as "parent cycle a → b → a", or null
     * when it ends at a root or a missing parent. Only a build-source snapshot merged in after
     * load can close one (#1443); a build must refuse it, as it would have no root to start from.
     */
    public static String parentCycle(ImageDef start, Map<String, ImageDef> defs) {
        return walkParents(start, defs, new ArrayList<>());
    }

    /** Add the ancestors of {@code start} to {@code result}; the cycle it stopped at, or null. */
    private static String walkParents(ImageDef start, Map<String, ImageDef> defs, List<ImageDef> result) {
        var seen = new LinkedHashSet<String>();
        seen.add(start.getName());
        var parentName = start.getParent();
        while (parentName != null && !parentName.isBlank()) {
            var parent = defs.get(parentName);
            if (parent == null) break;
            if (!seen.add(parentName)) {
                var path = new ArrayList<>(seen);
                return cycleText(path.subList(path.indexOf(parentName), path.size()));
            }
            result.add(parent);
            parentName = parent.getParent();
        }
        return null;
    }

    /**
     * "parent cycle a → b → a", starting at the least name, so a cycle reads the same from
     * wherever a walk entered it and {@link Warnings} reports it once.
     */
    private static String cycleText(List<String> cycle) {
        var rotated = new ArrayList<>(cycle);
        Collections.rotate(rotated, -rotated.indexOf(Collections.min(cycle)));
        rotated.add(rotated.get(0));
        return "parent cycle " + String.join(" → ", rotated);
    }

    /**
     * Return the full inheritance chain root-first, ending with {@code start} itself.
     * This is the order layers are applied during a build, so it is also the order
     * their contributions (env, notes, repos) should be collected in. For a root
     * image the list contains only {@code start}.
     */
    public static List<ImageDef> chain(ImageDef start, Map<String, ImageDef> defs) {
        var chain = new ArrayList<>(ancestors(start, defs).reversed());
        chain.add(start);
        return chain;
    }

    /** A repo the chain clones, with the template whose declaration of its destination came first. */
    public record ChainRepo(RepoEntry repo, String template) {}

    /**
     * The repos the chain of {@code start} clones, root-first, one per destination (#1394): a
     * redeclaration of a destination, in a descendant or in the same template, reuses the first
     * checkout. Entries with no path are skipped: nothing is cloned for them.
     */
    public static List<ChainRepo> chainRepos(ImageDef start, Map<String, ImageDef> defs) {
        var seen = new java.util.HashSet<String>();
        var result = new ArrayList<ChainRepo>();
        for (var layer : chain(start, defs)) {
            for (var repo : layer.getRepos()) {
                var destination = repoDestination(repo);
                if (destination != null && seen.add(destination)) result.add(new ChainRepo(repo, layer.getName()));
            }
        }
        return result;
    }

    /**
     * The first redeclaration in the chain of {@code start} that disagrees with the first
     * declaration of its destination, as a message naming the destination, both templates and the
     * differing fields; null when every redeclaration is identical. Overriding an inherited
     * repo is not supported, so such a chain cannot be built.
     */
    public static String repoConflict(ImageDef start, Map<String, ImageDef> defs) {
        var first = new java.util.HashMap<String, ChainRepo>();
        for (var layer : chain(start, defs)) {
            for (var repo : layer.getRepos()) {
                var destination = repoDestination(repo);
                if (destination == null) continue;
                var earlier = first.putIfAbsent(destination, new ChainRepo(repo, layer.getName()));
                if (earlier == null) continue;
                var differences = repoDifferences(earlier.repo(), repo);
                if (differences.isEmpty()) continue;
                var where = earlier.template().equals(layer.getName())
                        ? "template '" + layer.getName() + "' declares it twice"
                        : "template '" + layer.getName() + "' redeclares it from '" + earlier.template() + "'";
                return "repo " + shown(repo.getPath()) + ": " + where + " with a different "
                        + String.join(", ", differences)
                        + "; remove one declaration or make them identical (an inherited repo cannot be overridden)";
            }
        }
        return null;
    }

    /** Where a repo is cloned to: {@code ~/x} and {@code /home/agentuser/x} are one destination. */
    public static String repoDestination(RepoEntry repo) {
        var path = repo.getPath();
        if (path == null || path.isBlank()) return null;
        path = path.strip();
        if (path.equals("~") || path.startsWith("~/")) path = "/home/agentuser" + path.substring(1);
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    private static List<String> repoDifferences(RepoEntry a, RepoEntry b) {
        var differences = new ArrayList<String>();
        if (!java.util.Objects.equals(a.getHostSource(), b.getHostSource())) {
            differences.add("host-source (" + shown(a.getHostSource()) + " vs " + shown(b.getHostSource()) + ")");
        }
        if (!dev.incusspawn.git.GitRemoteUtils.urlsMatch(a.getUrl(), b.getUrl())) {
            differences.add("url (" + shown(a.getUrl()) + " vs " + shown(b.getUrl()) + ")");
        }
        if (!java.util.Objects.equals(blankToNull(a.getBranch()), blankToNull(b.getBranch()))) {
            differences.add("branch (" + shown(a.getBranch()) + " vs " + shown(b.getBranch()) + ")");
        }
        if (!java.util.Objects.equals(a.getDepth(), b.getDepth())) {
            differences.add("depth (" + shown(a.getDepth()) + " vs " + shown(b.getDepth()) + ")");
        }
        if (!stripped(a.getPrime()).equals(stripped(b.getPrime()))) {
            differences.add("prime (" + shown(a.primeText()) + " vs " + shown(b.primeText()) + ")");
        }
        return differences;
    }

    private static String shown(Object value) {
        return value == null ? "none" : OutputFormat.oneLine(String.valueOf(value));
    }

    private static List<String> stripped(List<String> commands) {
        return commands.stream().map(String::strip).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /**
     * Resolve the instance type by walking up the parent chain.
     * Returns the first non-null {@code type} found, or null if none is set.
     */
    public static String resolveType(ImageDef start, Map<String, ImageDef> defs) {
        if (start.getType() != null) return start.getType();
        for (var ancestor : ancestors(start, defs)) {
            if (ancestor.getType() != null) return ancestor.getType();
        }
        return null;
    }

    public static boolean resolveVm(ImageDef start, Map<String, ImageDef> defs) {
        return "vm".equals(resolveType(start, defs));
    }

    /** What loading says of a project-local definition's {@code coordinator:}, which is never read. */
    public static final String PROJECT_LOCAL_COORDINATOR = "'coordinator' is ignored in a project-local template:"
            + " a cloned repository cannot make its branches drive isx. Declare it in a template in "
            + "~/.config/incus-spawn/images, or branch with --mcp-client";

    /**
     * The nearest layer of the chain of {@code start} whose {@code coordinator:} says anything: it
     * decides alone, so a child never mixes its parent's list with its own switch. Null when none
     * does. Whether the chain may say so at all, which a project-local one may not (#765), is the
     * caller's to check: see {@link #anyProjectLocal}.
     */
    private static Coordinator nearestCoordinator(ImageDef start, Map<String, ImageDef> defs) {
        var layer = coordinatorLayer(start, defs);
        return layer == null ? null : layer.getCoordinator();
    }

    /** The layer whose {@code coordinator:} decides for {@code start} ({@link #nearestCoordinator}), or null. */
    public static ImageDef coordinatorLayer(ImageDef start, Map<String, ImageDef> defs) {
        for (var layer : chain(start, defs).reversed()) {
            var said = layer.getCoordinator();
            if (said != null && (said.getIsxControlCapability() != null || said.getControlOver() != null)) return layer;
        }
        return null;
    }

    /**
     * Whether a branch of {@code start} is a coordinator by default: the nearest layer that says,
     * by its {@code isx-control-capability}, else by its {@code control-over} not being empty. So
     * a child of a coordinator template is one unless it says otherwise.
     */
    public static boolean resolveCoordinatorDefault(ImageDef start, Map<String, ImageDef> defs) {
        var nearest = nearestCoordinator(start, defs);
        if (nearest == null) return false;
        if (nearest.getIsxControlCapability() != null) return nearest.getIsxControlCapability();
        return !nearest.getControlOver().isEmpty();
    }

    /**
     * What a coordinator of {@code start} may control, before {@code mcp.yaml} narrows it: the
     * {@code control-over} of the layer {@link #nearestCoordinator} picks, which decides alone, so a
     * child that says only {@code isx-control-capability} never takes its parent's list; null when
     * that layer lists none.
     */
    public static List<String> resolveControlOver(ImageDef start, Map<String, ImageDef> defs) {
        var nearest = nearestCoordinator(start, defs);
        return nearest == null ? null : nearest.getControlOver();
    }

    /**
     * One warning per trusted definition that turns {@code isx-control-capability} off beside a
     * non-empty {@code control-over} of its own: its branches are no coordinators unless chosen,
     * which the list suggests was not meant. A child that says only {@code false} opts out of its
     * parent's list, which is no mistake, since the layer that says decides alone.
     */
    public static List<String> coordinatorProblems(Map<String, ImageDef> defs) {
        return defs.values().stream().sorted(java.util.Comparator.comparing(ImageDef::getName))
                .flatMap(def -> coordinatorProblems(def, defs).stream()).toList();
    }

    /** {@link #coordinatorProblems(Map)} for one definition, whose chain {@code defs} holds. */
    public static List<String> coordinatorProblems(ImageDef def, Map<String, ImageDef> defs) {
        var said = def.getCoordinator();
        if (said == null || def.getProjectRoot() != null || !Boolean.FALSE.equals(said.getIsxControlCapability())) {
            return List.of();
        }
        var controlOver = resolveControlOver(def, defs);
        if (controlOver == null || controlOver.isEmpty()) return List.of();
        return List.of(OutputFormat.oneLine(String.valueOf(def.getName())) + ": coordinator.isx-control-capability is false,"
                + " although coordinator.control-over lists " + OutputFormat.oneLine(String.join(", ", controlOver))
                + ", so its branches are coordinators only with --mcp-client or the branch dialog's box."
                + " Remove the line to make them coordinators by default, or empty control-over if none should be");
    }

    /** Whether any layer of the chain of {@code start} is project-local. */
    public static boolean anyProjectLocal(ImageDef start, Map<String, ImageDef> defs) {
        return chain(start, defs).stream().anyMatch(layer -> layer.getProjectRoot() != null);
    }

    /**
     * Collapse the {@code accounts:} maps of the whole inheritance chain into one.
     *
     * <p>Merged per key rather than replaced wholesale, and walked root-first via
     * {@link #chain}, so the nearest declaration of a namespace wins while namespaces a
     * child says nothing about keep the parent's choice. A template that re-points only
     * {@code claude} therefore keeps its parent's {@code github} account.
     *
     * <p>Unlike {@link #resolveType}, which takes the first non-null it finds, every
     * layer contributes here -- the value is a map, not a scalar.
     */
    public static Map<String, String> resolveAccounts(ImageDef start, Map<String, ImageDef> defs) {
        var merged = new java.util.LinkedHashMap<String, String>();
        for (var layer : chain(start, defs)) {
            layer.getAccounts().forEach((namespace, account) -> {
                if (namespace != null && !namespace.isBlank()
                        && account != null && !account.isBlank()) {
                    merged.put(namespace.strip(), account.strip());
                }
            });
        }
        return merged;
    }

    /**
     * The warning for building {@code start}: that it is deprecated itself, or else that the
     * parent it names is; one deprecated in a deprecated parent says both, in one line. A
     * template further down the chain is not warned about (#1353): the one that names the
     * deprecated template is where it gets replaced, and repeating the warning for every
     * descendant, one per deprecated ancestor, buried which one that was.
     */
    public static Optional<String> deprecationWarning(ImageDef start, Map<String, ImageDef> defs) {
        var parent = start.isRoot() ? null : defs.get(start.getParent());
        var parentDeprecated = parent != null && parent.isDeprecated();
        var deprecated = start.isDeprecated() ? start : parentDeprecated ? parent : null;
        if (deprecated == null) return Optional.empty();
        // A deprecated parent of a deprecated template is named, but its note is left to its own
        // build: one long note per line is enough
        var what = deprecated != start
                ? start.getName() + " derives from " + deprecated.getName() + ", which is deprecated"
                : parentDeprecated
                ? start.getName() + " is deprecated, and so is its parent " + parent.getName()
                : start.getName() + " is deprecated";
        var note = deprecated.getDeprecated().strip();
        return Optional.of("true".equalsIgnoreCase(note) ? what + "." : what + ": " + note);
    }

    /** Whether this image is built from scratch (no parent). */
    public boolean isRoot() {
        return parent == null || parent.isBlank();
    }

    /**
     * Load all image definitions: built-in first, then user-defined overrides.
     * Returns a map keyed by image name (e.g. "tpl-minimal").
     */
    public static Map<String, ImageDef> loadAll() {
        return loadAll(SpawnConfig.load().getSearchPaths());
    }

    /**
     * Load all image definitions, collecting parse warnings via the given consumer
     * instead of printing to stderr. Use this from the TUI to avoid corrupting
     * the terminal with raw error output.
     */
    public static Map<String, ImageDef> loadAll(Consumer<String> warnings) {
        return loadAll(SpawnConfig.load().getSearchPaths(), warnings);
    }

    /**
     * Load all image definitions with explicit search paths.
     */
    static Map<String, ImageDef> loadAll(List<String> searchPaths) {
        return loadAll(searchPaths, Warnings::warn);
    }

    static Map<String, ImageDef> loadAll(List<String> searchPaths, Consumer<String> warnings) {
        return loadLayers(searchPaths, warnings).defs();
    }

    /**
     * Like {@link #loadAll(Consumer)}, conflicts reported through {@code warnings}, but keeping
     * where each definition came from and what it overrides.
     */
    public static LayeredDefinitions<ImageDef> loadLayers(Consumer<String> warnings) {
        return loadLayers(SpawnConfig.load().getSearchPaths(), warnings);
    }

    private static LayeredDefinitions<ImageDef> loadLayers(List<String> searchPaths, Consumer<String> warnings) {
        var result = loadAllWithConflicts(searchPaths, warnings);
        for (var conflict : result.conflicts()) {
            warnings.accept(conflict.shortMessage());
        }
        return result;
    }

    /**
     * Load all image definitions, additionally reporting same-directory name
     * collisions and cross-layer overrides (see {@link LayeredDefinitions}).
     * Unlike {@link #loadAll()}, this does <em>not</em> emit conflict messages via the
     * warnings consumer — the caller decides how to react (e.g. {@code isx build}
     * aborts on a conflict; the TUI shows a warning but still lists templates).
     */
    public static LayeredDefinitions<ImageDef> loadAllWithConflicts() {
        return loadAllWithConflicts(SpawnConfig.load().getSearchPaths(), Warnings::warn);
    }

    /**
     * The definitions from trusted layers only -- built-in, user and search paths -- leaving out
     * the working directory's {@code .incus-spawn/}. For a caller acting for someone other than
     * the user in that directory ({@code isx mcp}, whose working directory is whatever repository
     * the agent was started in), a project-local definition must not so much as override a
     * parent of an approved template.
     */
    public static Map<String, ImageDef> loadTrusted() {
        // Warnings.warn: a long-lived isx mcp loads these on every call, and a conflict is news once.
        return loadAllWithConflicts(SpawnConfig.load().getSearchPaths(), Warnings::warn, false, true).defs();
    }

    /**
     * The trusted definitions as {@link #loadTrusted()}, but without the base-image pin applied:
     * what {@code isx update-base} pins a release of.
     */
    public static Map<String, ImageDef> loadTrustedUnpinned() {
        return loadAllWithConflicts(SpawnConfig.load().getSearchPaths(), Warnings::warn, false, false).defs();
    }

    static LayeredDefinitions<ImageDef> loadAllWithConflicts(List<String> searchPaths, Consumer<String> warnings) {
        return loadAllWithConflicts(searchPaths, warnings, true, true);
    }

    private static LayeredDefinitions<ImageDef> loadAllWithConflicts(List<String> searchPaths,
                                                                     Consumer<String> warnings,
                                                                     boolean includeProject,
                                                                     boolean applyPin) {
        var defs = new LayeredDefinitions<ImageDef>("image");
        loadBuiltins(defs, warnings);
        loadFromDirectory(userImagesDir(), defs, warnings, null);
        for (var searchPath : searchPaths) {
            var expandedPath = HostResourceSetup.expandHostTilde(searchPath);
            loadFromDirectory(Path.of(expandedPath).resolve("images"), defs, warnings, null);
        }
        if (applyPin) applyBaseImagePin(defs, warnings);
        if (includeProject) loadFromDirectory(PROJECT_IMAGES_DIR, defs, warnings, projectRoot());
        refuseParentCycles(defs, warnings);
        inheritTypes(defs.defs());
        coordinatorProblems(defs.defs()).forEach(warnings);
        controlOverProblems(defs.defs()).forEach(warnings);
        return defs;
    }

    /**
     * One warning per {@code coordinator.control-over} entry that names no defined template (#1812):
     * likely a typo, and a coordinator could never control it. Only once every layer is loaded,
     * since another layer may define it.
     */
    public static List<String> controlOverProblems(Map<String, ImageDef> defs) {
        return defs.values().stream().sorted(java.util.Comparator.comparing(ImageDef::getName))
                .flatMap(def -> controlOverProblems(def, defs::containsKey).stream()).toList();
    }

    /** {@link #controlOverProblems(Map)} for one definition, against what {@code defined} knows. */
    public static List<String> controlOverProblems(ImageDef def, java.util.function.Predicate<String> defined) {
        if (def.getCoordinator() == null || def.getCoordinator().getControlOver() == null || def.getProjectRoot() != null) {
            return List.of();
        }
        return def.getCoordinator().getControlOver().stream().filter(named -> !defined.test(named))
                .map(named -> OutputFormat.oneLine(String.valueOf(def.getName())) + ": coordinator.control-over names '"
                        + OutputFormat.oneLine(named) + "', which no template defines, so its coordinators cannot control it")
                .toList();
    }

    /**
     * Put the release {@code isx update-base <tag>} pinned into the trusted {@code tpl-minimal}
     * (#1563), before a project-local one could replace it: only its base image changes, so every
     * other field still comes from whichever definition is in effect. A pin that cannot be read
     * counts as a file that failed to parse, so {@code isx build} refuses rather than track the
     * latest release in its place.
     */
    private static void applyBaseImagePin(LayeredDefinitions<ImageDef> defs, Consumer<String> warnings) {
        var file = BaseImagePin.file();
        if (!Files.exists(file)) return;
        var name = OutputFormat.oneLine(file.toString());
        BaseImagePin pin;
        try {
            pin = BaseImagePin.read(file);
        } catch (IOException e) {
            warnings.accept(YamlErrors.friendly(name, e));
            defs.parseFailed(file.toAbsolutePath().normalize());
            return;
        }
        pin.unknownKeyWarnings().forEach(w -> warnings.accept(name + ": " + w));
        var problem = pin.problem();
        if (problem != null) {
            warnings.accept(name + ": " + problem + "; the base image pin is ignored, and isx build refuses until it is fixed"
                    + " (or removed with 'isx update-base --latest')");
            defs.parseFailed(file.toAbsolutePath().normalize());
            return;
        }
        var def = defs.defs().get(BaseImagePin.TEMPLATE);
        if (def == null) return;
        if (!pin.appliesTo(def)) {
            warnings.accept(name + ": pins a release of " + OutputFormat.oneLine(pin.getImageUrl())
                    + ", but " + BaseImagePin.TEMPLATE + " no longer downloads from there; the pin is ignored."
                    + " Run 'isx update-base <tag>' to pin again, or 'isx update-base --latest'");
            return;
        }
        pin.applyTo(def);
    }

    /**
     * Refuse every template on a parent cycle, naming the layers (#1443): walking its chain would
     * never end. A cycle is only visible once every layer is loaded, since an override can close it.
     */
    private static void refuseParentCycles(LayeredDefinitions<ImageDef> defs, Consumer<String> warnings) {
        var map = defs.defs();
        var walked = new HashSet<String>();
        var cycles = new ArrayList<List<String>>();
        for (var start : map.keySet()) {
            var path = new ArrayList<String>();
            var name = start;
            while (name != null && map.containsKey(name) && walked.add(name)) {
                path.add(name);
                var def = map.get(name);
                name = def.isRoot() ? null : def.getParent();
            }
            // Stopped at a template walked before: a cycle only if this walk reached it
            int loopStart = path.indexOf(name);
            if (loopStart >= 0) cycles.add(List.copyOf(path.subList(loopStart, path.size())));
        }
        for (var cycle : cycles) {
            var files = cycle.stream().map(defs::getSource).distinct()
                    .map(OutputFormat::oneLine).toList();
            warnings.accept(cycleText(cycle) + " (" + String.join(", ", files) + ")" + LayeredDefinitions.REFUSED);
            cycle.forEach(defs::refuse);
        }
    }

    /**
     * Propagate {@code type} from parent to child when the child doesn't set its own.
     */
    private static void inheritTypes(Map<String, ImageDef> defs) {
        for (var def : defs.values()) {
            if (def.type == null && !def.isRoot()) {
                var resolved = resolveType(def, defs);
                if (resolved != null) {
                    def.type = resolved;
                }
            }
        }
    }

    /**
     * @deprecated Use {@link #loadAll()} instead.
     */
    @Deprecated
    public static Map<String, ImageDef> loadBuiltins() {
        return loadAll();
    }

    /**
     * Find an image definition by name.
     */
    public static ImageDef findByName(String name, Map<String, ImageDef> defs) {
        return defs.get(name);
    }

    private static void loadBuiltins(LayeredDefinitions<ImageDef> defs, Consumer<String> warnings) {
        // Built-ins are the first layer and their names are unique by construction
        // (hardcoded BUILTIN_FILES), so no conflict/override tracking is needed here.
        for (var filename : BUILTIN_FILES) {
            var def = loadResource(RESOURCE_DIR + filename, warnings);
            if (def != null) {
                def.setSource("built-in");
                if (validate(def, filename, warnings)) defs.putBuiltin(def.getName(), def);
            }
        }
    }

    /** The directory whose {@code .incus-spawn/images/} supplies the project-local layer. */
    static Path projectRoot() {
        return PROJECT_IMAGES_DIR.toAbsolutePath().getParent().getParent();
    }

    private static void loadFromDirectory(Path dir, LayeredDefinitions<ImageDef> defs, Consumer<String> warnings,
                                          Path projectRoot) {
        if (!Files.isDirectory(dir)) return;
        defs.beginDirectory();
        try (var stream = Files.list(dir)) {
            var paths = stream
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
            for (var path : paths) {
                try (var is = Files.newInputStream(path)) {
                    var def = Jjq.imageDef(is);
                    var source = path.toAbsolutePath().normalize();
                    if (def == null) def = new ImageDef(); // a document of just `~`: no name either
                    def.setSource(source.toString());
                    def.setProjectRoot(projectRoot);
                    if (validate(def, path.getFileName().toString(), warnings)) {
                        defs.put(def.getName(), def, source);
                    } else {
                        // Like an unparsable file, it may have meant to replace a lower layer's.
                        defs.parseFailed(source);
                    }
                } catch (IOException e) {
                    warnings.accept(YamlErrors.friendly(path.getFileName().toString(), e));
                    defs.parseFailed(path.toAbsolutePath().normalize());
                }
            }
        } catch (IOException e) {
            warnings.accept("failed to scan " + dir + ": " + e.getMessage());
        }
        defs.endDirectory();
    }

    /** False when {@code def} cannot be a template at all, which it has then warned about. */
    private static boolean validate(ImageDef def, String source, Consumer<String> warnings) {
        var file = OutputFormat.oneLine(source);
        // First: a misspelled key may be why the definition is refused below ('nmae:').
        def.unknownKeyWarnings().forEach(w -> warnings.accept(file + ": " + w));
        if (def.getProjectRoot() != null && def.getCoordinator() != null) warnings.accept(file + ": " + PROJECT_LOCAL_COORDINATOR);
        if (def.getName() == null) {
            warnings.accept(file + ": no 'name:'; it is skipped, and isx build refuses until it is fixed");
            return false;
        }
        var invalidName = notAnInstanceName("template name", def.getName());
        if (invalidName == null && !def.isRoot()) invalidName = notAnInstanceName("parent", def.getParent());
        if (invalidName != null) {
            warnings.accept(file + ": " + invalidName + LayeredDefinitions.REFUSED);
            return false;
        }
        for (var repo : def.getRepos()) {
            var problem = repo.sourceProblem();
            if (repo.isHostSource() && def.getProjectRoot() != null) {
                problem = "project-local templates cannot prepare host repositories; declare host-source in a trusted template";
            }
            if (problem != null) {
                warnings.accept(file + ": " + problem + LayeredDefinitions.REFUSED);
                return false;
            }
            if (repo.getDepth() != null && repo.getDepth() < 1) {
                warnings.accept(file + ": repo " + OutputFormat.oneLine(String.valueOf(repo.getUrl()))
                        + ": 'depth' " + repo.getDepth() + " is not a positive number of commits"
                        + LayeredDefinitions.REFUSED);
                return false;
            }
        }
        if (def.getDefaultAction() != null && def.getDefaultAction().isBlank()) {
            warnings.accept(file + ": 'default-action' is blank; remove it or set a valid tool reference");
            def.setDefaultAction(null);
        }
        return true;
    }

    /**
     * Why {@code name}, a template's {@code what}, can never be built, or null: a template is an
     * instance, so its name and its parent's must be names Incus accepts for one. Checked at load,
     * this also keeps a cloned repository's name from reaching the terminal raw wherever isx shows it.
     */
    static String notAnInstanceName(String what, String name) {
        if (InstanceNames.isValid(name)) return null;
        return what + " '" + OutputFormat.oneLine(String.valueOf(name)) + "' is not a valid Incus instance name ("
                + InstanceNames.RULE + ")";
    }

    private static ImageDef loadResource(String path, Consumer<String> warnings) {
        try (InputStream is = ImageDef.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) return null;
            return Jjq.imageDef(is);
        } catch (IOException e) {
            warnings.accept(YamlErrors.friendly(path, e));
            return null;
        }
    }
}
