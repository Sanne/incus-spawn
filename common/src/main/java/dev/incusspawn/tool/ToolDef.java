package dev.incusspawn.tool;

import dev.incusspawn.config.IntegerConverter;
import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.Jjq;
import dev.incusspawn.config.MappedDefinition;
import io.hyperfoil.tools.jjq.mapper.JqConverter;
import io.hyperfoil.tools.jjq.mapper.JqMapped;
import io.hyperfoil.tools.jjq.mapper.JqName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A tool installation defined in YAML. Each definition declares
 * packages to install, shell commands to run, files to write, and
 * environment variables to export.
 */
@JqMapped
public class ToolDef extends FingerprintCache {

    private String name;
    private String description = "";
    private List<DownloadEntry> downloads = List.of();
    private List<String> packages = List.of();
    private List<String> run = List.of();
    @JqName("run_as_user")
    private List<String> runAsUser = List.of();
    private List<FileEntry> files = List.of();
    @io.hyperfoil.tools.jjq.mapper.JqConverter(dev.incusspawn.config.EnvEntryListConverter.class)
    private List<EnvEntry> env = List.of();
    @io.hyperfoil.tools.jjq.mapper.JqConverter(ToolRefListConverter.ForRequires.class)
    private List<ToolRef> requires = List.of();
    private String verify;
    @JqName("verify_as_root")
    private boolean verifyAsRoot;
    private String ready;
    private List<ActionEntry> actions = List.of();
    @JqName("package_repos")
    private List<ImageDef.PackageRepo> packageRepos = List.of();
    private Map<String, ParameterDef> parameters = Map.of();
    private String feature;
    private List<String> architectures = List.of();
    private ProxyDef proxy;
    @JqName("agent_note")
    private String agentNote;
    @io.hyperfoil.tools.jjq.mapper.JqConverter(dev.incusspawn.config.SkillsDefConverter.class)
    private ImageDef.SkillsDef skills = ImageDef.SkillsDef.EMPTY;
    private List<McpEntry> mcp = List.of();
    @io.hyperfoil.tools.jjq.mapper.JqConverter(ResourcesConverter.class)
    private Resources resources = Resources.NONE;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<DownloadEntry> getDownloads() { return downloads; }
    public void setDownloads(List<DownloadEntry> downloads) { this.downloads = items(downloads); }
    public List<String> getPackages() { return packages; }
    public void setPackages(List<String> packages) { this.packages = items(packages); }
    public List<String> getRun() { return run; }
    public void setRun(List<String> run) { this.run = items(run); }
    public List<String> getRunAsUser() { return runAsUser; }
    public void setRunAsUser(List<String> runAsUser) { this.runAsUser = items(runAsUser); }
    public List<FileEntry> getFiles() { return files; }
    public void setFiles(List<FileEntry> files) { this.files = items(files); }
    public List<EnvEntry> getEnv() { return env; }
    public void setEnv(List<EnvEntry> env) { this.env = items(env); }
    public List<ToolRef> getRequires() { return requires; }
    public void setRequires(List<ToolRef> requires) { this.requires = items(requires); }
    public String getVerify() { return verify; }
    public void setVerify(String verify) { this.verify = verify; }
    public boolean isVerifyAsRoot() { return verifyAsRoot; }
    public void setVerifyAsRoot(boolean verifyAsRoot) { this.verifyAsRoot = verifyAsRoot; }
    public String getReady() { return ready; }
    public void setReady(String ready) { this.ready = ready; }
    public List<ActionEntry> getActions() { return actions; }
    public void setActions(List<ActionEntry> actions) { this.actions = items(actions); }
    public List<ImageDef.PackageRepo> getPackageRepos() { return packageRepos; }
    public void setPackageRepos(List<ImageDef.PackageRepo> packageRepos) { this.packageRepos = items(packageRepos); }
    public Map<String, ParameterDef> getParameters() { return parameters; }
    public void setParameters(Map<String, ParameterDef> parameters) {
        this.parameters = parameters != null ? parameters : Map.of();
    }
    public String getFeature() { return feature; }
    public void setFeature(String feature) { this.feature = feature != null && feature.isBlank() ? null : feature; }
    /** The architectures the tool can be installed on, as download entries spell them; empty means every one. */
    public List<String> getArchitectures() { return architectures; }
    public void setArchitectures(List<String> architectures) { this.architectures = items(architectures); }
    public ProxyDef getProxy() { return proxy; }
    public void setProxy(ProxyDef proxy) { this.proxy = proxy; }
    public String getAgentNote() { return agentNote; }
    public void setAgentNote(String agentNote) { this.agentNote = agentNote; }
    public ImageDef.SkillsDef getSkills() { return skills; }
    public void setSkills(ImageDef.SkillsDef skills) { this.skills = skills != null ? skills : ImageDef.SkillsDef.EMPTY; }
    public List<McpEntry> getMcp() { return mcp; }
    public void setMcp(List<McpEntry> mcp) { this.mcp = items(mcp); }
    public Resources getResources() { return resources; }
    public void setResources(Resources resources) { this.resources = resources != null ? resources : Resources.NONE; }

    /**
     * A list as mapped from YAML, without its empty items: a bare '-' (or '~') arrives as null
     * and declares nothing, so no reader has to skip it. An absent or empty key is no list.
     */
    static <T> List<T> items(List<T> list) {
        return list == null ? List.of() : list.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** One warning per key isx ignores in this definition as read from its file, each named by where it is (#1546). */
    public List<String> unknownKeyWarnings() {
        var walk = new MappedDefinition.Walk().part("", this)
                .each("downloads", downloads)
                .each("files", files)
                .each("env", env)
                .each("actions", actions)
                .each("package_repos", packageRepos)
                .each("mcp", mcp)
                .values("parameters", parameters)
                .part("proxy", proxy)
                .part("skills", skills)
                .part("resources", resources);
        if (proxy != null) {
            walk.values("proxy.configuration", proxy.getConfiguration());
            proxy.getConfiguration().forEach((key, entry) -> {
                if (entry != null) walk.values("proxy.configuration." + key + ".for-each", entry.getForEach());
            });
            walk.each("proxy.auth", proxy.getAuth())
                    .each("proxy.placeholders", proxy.getPlaceholders());
        }
        return walk.warnings();
    }

    @JqMapped
    public static class DownloadEntry extends MappedDefinition {
        private String url;
        private String sha256;
        private String extract;
        @JqName("destination_file")
        private String destinationFile;
        private Map<String, String> links = Map.of();
        @JqName("extract_in_container")
        private boolean extractInContainer = false;
        private String arch;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getSha256() { return sha256; }
        public void setSha256(String sha256) { this.sha256 = sha256; }
        public String getExtract() { return extract; }
        public void setExtract(String extract) { this.extract = extract; }
        public String getDestinationFile() { return destinationFile; }
        public void setDestinationFile(String destinationFile) { this.destinationFile = destinationFile; }
        public Map<String, String> getLinks() { return links; }
        public void setLinks(Map<String, String> links) { this.links = links; }
        public boolean isExtractInContainer() { return extractInContainer; }
        public void setExtractInContainer(boolean extractInContainer) { this.extractInContainer = extractInContainer; }
        public String getArch() { return arch; }
        public void setArch(String arch) { this.arch = arch; }
    }

    /**
     * An MCP server the tool provides, registered with every agent in the image that supports one
     * ({@link ToolSetup#mcpClient}): a stdio {@code command} with its {@code env}, or an HTTPS
     * {@code url} whose bearer token an agent reads from {@code bearer-token-env} when it connects.
     * Checked by {@link ToolDefValidator#invalidMcpServers}, which refuses a tool whose entry is not
     * one of the two.
     */
    @JqMapped
    public static class McpEntry extends MappedDefinition {
        private String name;
        private String description;
        private List<String> command = List.of();
        private Map<String, String> env = Map.of();
        private String url;
        @JqName("bearer-token-env")
        private String bearerTokenEnv;
        /** A {@code proxy.configuration} list this entry is registered once for each item of ({@link ConfigEntry#getForEach}). */
        @JqName("for-each")
        private String forEach;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getCommand() { return command; }
        public void setCommand(List<String> command) { this.command = command != null ? command : List.of(); }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env != null ? env : Map.of(); }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getBearerTokenEnv() { return bearerTokenEnv; }
        public void setBearerTokenEnv(String bearerTokenEnv) { this.bearerTokenEnv = bearerTokenEnv; }
        public String getForEach() { return forEach; }
        public void setForEach(String forEach) { this.forEach = forEach == null || forEach.isBlank() ? null : forEach; }
    }

    /**
     * What a tool needs of the instance it runs in ({@code resources:}), memory alone so far
     * (#2355): {@code memory.resident}, held for as long as the tool runs, and
     * {@code memory.peak}, needed on top of it for a short while (an index, a warm-up). Each
     * comes from a measurement recorded in docs/dev/PERFORMANCE-NOTES.md. Read by
     * {@link MemoryNeed}, which the build stamps on the template.
     */
    public static final class Resources extends MappedDefinition {
        public static final Resources NONE = new Resources(null, null);

        private final MemoryAmount resident;
        private final MemoryAmount peak;

        public Resources(MemoryAmount resident, MemoryAmount peak) {
            this.resident = resident;
            this.peak = peak;
        }

        /** The memory the tool holds while it runs, or null. */
        public MemoryAmount resident() { return resident; }

        /** The memory the tool needs on top of {@link #resident()} for a short while, or null. */
        public MemoryAmount peak() { return peak; }

        public boolean declaresMemory() { return resident != null || peak != null; }

        String fingerprint() {
            return "resident:" + (resident == null ? "" : resident.fingerprint())
                    + ",peak:" + (peak == null ? "" : peak.fingerprint());
        }

        @Override
        protected java.util.Collection<String> knownKeys() {
            return List.of("memory");
        }

        @Override
        protected String hint(String key) {
            return key.startsWith("memory.") ? "resources.memory takes 'resident' and 'peak'" : null;
        }
    }

    /**
     * An amount of memory a tool declares: {@code bytes} alone, or the size its {@code parameter}
     * holds plus {@code bytes} ({@code {parameter: memory, plus: 768MiB}}), for a tool whose need
     * is one of its settings, such as a JVM heap.
     */
    public record MemoryAmount(long bytes, String parameter) {
        /** The bytes this comes to with {@code params}, or -1 when the parameter does not hold a size. */
        public long resolve(Map<String, String> params) {
            if (parameter == null) return bytes;
            var size = dev.incusspawn.util.MemorySize.parse(params.get(parameter));
            return size < 0 ? -1 : size + bytes;
        }

        String fingerprint() {
            return parameter == null ? String.valueOf(bytes) : parameter + "+" + bytes;
        }
    }

    @JqMapped
    public static class FileEntry extends MappedDefinition {
        private String path;
        private String content;
        private String owner;
        private String merge;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getOwner() { return owner; }
        public void setOwner(String owner) { this.owner = owner; }
        /** {@code json} to deep-merge the content into the file already there, else null to replace it. */
        public String getMerge() { return merge; }
        public void setMerge(String merge) { this.merge = merge; }
        public boolean mergesJson() { return MERGE_JSON.equals(merge); }

        /** The one {@code merge:} value there is. */
        public static final String MERGE_JSON = "json";
    }

    @JqMapped
    public static class ActionEntry extends MappedDefinition {
        private String label;
        private String type;
        @JqName("requires_running")
        private boolean requiresRunning = true;
        private String expand;
        private String url;
        private String command;
        private String text;
        @JqName("auto_return")
        private boolean autoReturn = false;
        private String id;
        @JqName("shell_menu")
        private boolean shellMenu = false;
        private String shortcut;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public boolean isRequiresRunning() { return requiresRunning; }
        public void setRequiresRunning(boolean requiresRunning) { this.requiresRunning = requiresRunning; }
        public String getExpand() { return expand; }
        public void setExpand(String expand) { this.expand = expand; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public boolean isAutoReturn() { return autoReturn; }
        public void setAutoReturn(boolean autoReturn) { this.autoReturn = autoReturn; }
        public boolean isShellMenu() { return shellMenu; }
        public void setShellMenu(boolean shellMenu) { this.shellMenu = shellMenu; }
        public String getShortcut() { return shortcut; }
        public void setShortcut(String shortcut) { this.shortcut = shortcut; }
    }

    public static class ToolRef {
        private String name;
        @JqName("params")
        private Map<String, String> params = Map.of();

        public ToolRef() {}

        public ToolRef(String name) {
            this.name = name;
            this.params = Map.of();
        }

        public ToolRef(String name, Map<String, String> params) {
            this.name = name;
            this.params = params != null ? params : Map.of();
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Map<String, String> getParams() { return params; }
        public void setParams(Map<String, String> params) {
            this.params = params != null ? params : Map.of();
        }

        // Union shapes (string-or-map) live in ToolRefListConverter.
    }

    @JqMapped
    public static class ParameterDef extends MappedDefinition {
        private String type;  // "string", "integer", "boolean", "enum"
        @JqName("default")
        private String defaultValue;
        private String description;
        private String pattern;  // regex for string validation
        @JqConverter(IntegerConverter.class)
        private Integer min;     // for integer type
        @JqConverter(IntegerConverter.class)
        private Integer max;     // for integer type
        private List<String> options = List.of();  // for enum type
        private boolean optional = false;
        private boolean reconfigurable = false;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDefault() { return defaultValue; }
        public void setDefault(String defaultValue) { this.defaultValue = defaultValue; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public Integer getMin() { return min; }
        public void setMin(Integer min) { this.min = min; }
        public Integer getMax() { return max; }
        public void setMax(Integer max) { this.max = max; }
        public List<String> getOptions() { return options; }
        public void setOptions(List<String> options) { this.options = items(options); }
        public boolean isOptional() { return optional; }
        public void setOptional(boolean optional) { this.optional = optional; }
        public boolean isReconfigurable() { return reconfigurable; }
        public void setReconfigurable(boolean reconfigurable) { this.reconfigurable = reconfigurable; }
    }

    @JqMapped
    public static class ProxyDef extends MappedDefinition {
        @JqName("config-namespace")
        private String configNamespace = "";
        private Map<String, ConfigEntry> configuration = Map.of();
        private List<AuthDef> auth = List.of();
        private List<PlaceholderDef> placeholders = List.of();

        public String getConfigNamespace() { return configNamespace; }
        public void setConfigNamespace(String configNamespace) {
            this.configNamespace = configNamespace != null ? configNamespace : "";
        }
        public Map<String, ConfigEntry> getConfiguration() { return configuration; }
        public void setConfiguration(Map<String, ConfigEntry> configuration) {
            this.configuration = configuration != null ? configuration : Map.of();
        }
        public List<AuthDef> getAuth() { return auth; }
        public void setAuth(List<AuthDef> auth) { this.auth = items(auth); }
        public List<PlaceholderDef> getPlaceholders() { return placeholders; }
        public void setPlaceholders(List<PlaceholderDef> placeholders) {
            this.placeholders = items(placeholders);
        }

        public String fullConfigPath(ConfigEntry entry) {
            var path = entry.getConfigPath();
            if (path.isBlank() || configNamespace.isBlank()) return path;
            return configNamespace + "." + path;
        }

        /**
         * Path to this entry inside a named account of the tool's config namespace --
         * {@code github.accounts.acme.token} for {@code config-path: token}.
         *
         * <p>This is the whole of what makes named accounts generic: a tool declares
         * {@code config-namespace} and gets per-account credentials without any code,
         * including a tool defined purely in YAML. Blank when the tool has no namespace
         * or no account was selected, in which case callers fall back to
         * {@link #fullConfigPath(ConfigEntry)} -- the flat, pre-accounts layout.
         */
        public String accountConfigPath(ConfigEntry entry, String accountName) {
            if (accountName == null || accountName.isBlank()) return "";
            var namespace = namespaceOf(entry);
            if (namespace.isBlank()) return "";
            var path = fullConfigPath(entry);
            var leaf = path.substring(namespace.length() + 1);
            return namespace + "." + dev.incusspawn.config.AccountResolver.ACCOUNTS_KEY
                    + "." + accountName + "." + leaf;
        }

        /**
         * The config namespace a single entry belongs to -- normally the tool's own, but a tool
         * may instead <em>borrow</em> another namespace's credential by naming it fully in
         * {@code config-path} and declaring no {@code config-namespace} at all. {@code
         * CopilotSetup} does exactly that, pointing at {@code github.token} to share the
         * {@code gh} tool's PAT rather than prompting for a second one.
         *
         * <p>Deriving the namespace per entry rather than per tool is what makes such a tool
         * follow the same account selection as the tool it borrows from. Keying off the tool's
         * own namespace would leave {@code gh} authenticating as the pinned account while
         * copilot kept using the flat token -- two GitHub identities inside one container.
         */
        public String namespaceOf(ConfigEntry entry) {
            if (!configNamespace.isBlank()) return configNamespace;
            var path = entry.getConfigPath();
            var dot = path.indexOf('.');
            // A single-segment path with no namespace names a top-level key, which no
            // namespace owns and so no account can vary.
            return dot <= 0 || dot == path.length() - 1 ? "" : path.substring(0, dot);
        }
    }

    @JqMapped
    public static class ConfigEntry extends MappedDefinition {
        private String type = "string"; // "string" or "confirm"
        private String description = "";
        private boolean secret = false;
        @JqName("config-path")
        private String configPath = "";
        private String value = "";
        private List<String> help = List.of();
        /**
         * The fields of each item when this entry is a list: a map in {@code config.yaml} from an
         * item's label to its fields, each read at the field's {@code config-path} (its key when
         * unset). An auth or {@code mcp:} entry that names this entry in {@code for-each} is
         * repeated once per item, its {@code ${<entry>.<field>}} and {@code ${<entry>.label}}
         * references taking that item's values.
         */
        @JqName("for-each")
        private Map<String, ListField> forEach = Map.of();
        /**
         * How {@code isx init} checks a secret pasted for this entry, or why it cannot
         * ({@link SecretCheck}): null when the definition says neither.
         */
        private CheckDef check;

        public String getType() { return type; }
        public void setType(String type) { this.type = type != null ? type : "string"; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description != null ? description : ""; }
        public boolean isSecret() { return secret; }
        public void setSecret(boolean secret) { this.secret = secret; }
        public String getConfigPath() { return configPath; }
        public void setConfigPath(String configPath) { this.configPath = configPath != null ? configPath : ""; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value != null ? value : ""; }
        public List<String> getHelp() { return help; }
        public void setHelp(List<String> help) { this.help = items(help); }
        public Map<String, ListField> getForEach() { return forEach; }
        public void setForEach(Map<String, ListField> forEach) { this.forEach = forEach != null ? forEach : Map.of(); }
        public CheckDef getCheck() { return check; }
        public void setCheck(CheckDef check) { this.check = check; }
        /** Whether this entry is a list of items, each with the fields of {@link #getForEach}. */
        public boolean isList() { return !forEach.isEmpty(); }
        public boolean isSelfResolving() { return !configPath.isBlank() || !this.value.isBlank(); }
        public boolean isConfirm() { return "confirm".equals(type); }
        /** Whether a field of this list's items is a secret. */
        public boolean hasSecretField() {
            return forEach.values().stream().anyMatch(field -> field != null && field.isSecret());
        }
    }

    /**
     * How a pasted secret is checked ({@link ConfigEntry#getCheck}): a {@code url} isx sends a
     * {@code GET} to, authenticated as the tool's auth entry for that host injects the secret, or
     * {@code none}, which says why nothing can check it.
     */
    @JqMapped
    public static class CheckDef extends MappedDefinition {
        private String url = "";
        private String none = "";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url != null ? url.strip() : ""; }
        public String getNone() { return none; }
        public void setNone(String none) { this.none = none != null ? none.strip() : ""; }
    }

    /** One field of each item of a list entry ({@link ConfigEntry#getForEach}): one value an item holds. */
    @JqMapped
    public static class ListField extends MappedDefinition {
        private String description = "";
        private boolean secret = false;
        @JqName("config-path")
        private String configPath = "";

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description != null ? description : ""; }
        public boolean isSecret() { return secret; }
        public void setSecret(boolean secret) { this.secret = secret; }
        public String getConfigPath() { return configPath; }
        public void setConfigPath(String configPath) { this.configPath = configPath != null ? configPath : ""; }
        /** Where an item holds this field: its {@code config-path}, else {@code key}. */
        public String fieldPath(String key) { return configPath.isBlank() ? key : configPath; }
    }

    /**
     * An environment variable the tool reads its credential from, which an instance's start fills
     * with a proof token for the proxy's namespace ({@code dev.incusspawn.proxy.ProofToken}, #1106):
     * {@code prefix} plus {@code isx_} plus a digest of the instance's per-start secret. The prefix
     * is whatever the tool checks a credential's shape by, e.g. {@code gho_}.
     */
    @JqMapped
    public static class PlaceholderDef extends MappedDefinition {
        private String env = "";
        private String prefix = "";

        public PlaceholderDef() {}
        public PlaceholderDef(String env, String prefix) {
            setEnv(env);
            setPrefix(prefix);
        }

        public String getEnv() { return env; }
        public void setEnv(String env) { this.env = env != null ? env : ""; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix != null ? prefix : ""; }
    }

    @JqMapped
    public static class AuthDef extends MappedDefinition {
        private List<String> domains = List.of();
        private List<String> paths = List.of();
        private String type;       // "basic", "bearer", "header", "anthropic"
        private String username;   // for basic: literal or ${configKey} template
        private String password;   // for basic: literal or ${configKey} template
        private String token;      // for bearer: literal or ${configKey} template
        private String name;       // for header: the header name
        private String value;      // for header: template with ${configKey} substitution
        /** A {@code proxy.configuration} list this entry is repeated for, once per item ({@link ConfigEntry#getForEach}). */
        @JqName("for-each")
        private String forEach;

        public List<String> getDomains() { return domains; }
        public void setDomains(List<String> domains) { this.domains = items(domains); }
        public List<String> getPaths() { return paths; }
        // Not items(): dropping an empty path could leave none, which scopes the entry to every path;
        // ToolDefValidator.invalidPathScopes refuses the tool instead
        public void setPaths(List<String> paths) { this.paths = paths != null ? paths : List.of(); }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getForEach() { return forEach; }
        public void setForEach(String forEach) { this.forEach = forEach == null || forEach.isBlank() ? null : forEach; }
    }

    public boolean hasBuildSteps() {
        return !packages.isEmpty() || !downloads.isEmpty() || !run.isEmpty()
            || !runAsUser.isEmpty() || !files.isEmpty() || !packageRepos.isEmpty();
    }

    public String contentFingerprint() {
        var result = cachedFingerprint();
        if (result != null) return result;
        var sb = new StringBuilder();
        for (var d : downloads) {
            sb.append("dl=").append(d.url).append(',').append(d.sha256)
                    .append(',').append(d.extract)
                    .append(',').append(d.destinationFile)
                    .append(',').append(d.extractInContainer)
                    .append(',').append(d.arch);
            new TreeMap<>(d.links).forEach((k, v) -> sb.append(',').append(k).append('=').append(v));
            sb.append('\n');
        }
        packages.stream().sorted().forEach(p -> sb.append("pkg=").append(p).append('\n'));
        packageRepos.stream()
                .sorted(java.util.Comparator.<ImageDef.PackageRepo, String>comparing(r -> String.valueOf(r.getType()))
                        .thenComparing(r -> String.valueOf(r.getName())))
                .forEach(r -> sb.append("pkgrepo=").append(r.getType()).append(',').append(r.getName()).append('\n'));
        run.forEach(r -> sb.append("run=").append(r).append('\n'));
        runAsUser.forEach(r -> sb.append("run_as_user=").append(r).append('\n'));
        for (var f : files) {
            sb.append("file=").append(f.path).append(',').append(f.content)
                    .append(',').append(f.owner);
            // Only when set, so a tool without it keeps the fingerprint it had before merge: existed
            if (f.merge != null) sb.append(",merge=").append(f.merge);
            sb.append('\n');
        }
        env.stream()
                .map(EnvEntry::fingerprintString)
                .sorted()
                .forEach(e -> sb.append(e).append('\n'));
        for (var r : requires.stream().sorted(Comparator.comparing(ToolRef::getName)).toList()) {
            sb.append("requires=").append(r.getName());
            if (!r.getParams().isEmpty()) {
                r.getParams().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(',').append(e.getKey()).append('=').append(e.getValue()));
            }
            sb.append('\n');
        }
        if (proxy != null) {
            proxy.configuration.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        sb.append("config=").append(e.getKey())
                                .append(':').append(e.getValue().configPath)
                                .append(':').append(e.getValue().value);
                        // A list's fields decide what each repeated entry reads
                        new TreeMap<>(e.getValue().forEach).forEach((k, f) -> sb.append(":field=").append(k)
                                .append(':').append(f == null ? "" : f.configPath).append(':').append(f != null && f.secret));
                        sb.append('\n');
                    });
            for (var ae : proxy.auth) {
                sb.append("auth=");
                ae.domains.stream().sorted().forEach(d -> sb.append(d).append(','));
                if (!ae.paths.isEmpty()) {
                    sb.append("paths=");
                    ae.paths.stream().sorted().forEach(p -> sb.append(p).append(','));
                }
                sb.append(ae.type != null ? ae.type : "");
                if (ae.token != null) sb.append(",token=").append(ae.token);
                if (ae.username != null) sb.append(",user=").append(ae.username);
                if (ae.password != null) sb.append(",pass=").append(ae.password);
                if (ae.name != null) sb.append(",name=").append(ae.name);
                if (ae.value != null) sb.append(",value=").append(ae.value);
                if (ae.forEach != null) sb.append(",for-each=").append(ae.forEach);
                sb.append('\n');
            }
        }
        if (verify != null) sb.append("verify=").append(verify).append('\n');
        if (verifyAsRoot) sb.append("verify_as_root=true\n");
        // Included (unlike description) because the note is baked into the image.
        if (agentNote != null && !agentNote.isBlank()) {
            sb.append("agent_note=").append(agentNote).append('\n');
        }
        if (skills.getRepo() != null) sb.append("skills-repo=").append(skills.getRepo()).append('\n');
        skills.getList().stream().sorted().forEach(s -> sb.append("skill=").append(s).append('\n'));
        skills.appendPin(sb);
        // Registered with every agent in the image, so a changed entry is a changed image
        for (var m : mcp) {
            sb.append("mcp=").append(m.name).append(',').append(m.description).append(',')
                    .append(String.join("\u0000", m.command)).append(',').append(m.url).append(',')
                    .append(m.bearerTokenEnv).append(',');
            if (m.forEach != null) sb.append("for-each=").append(m.forEach).append(',');
            new TreeMap<>(m.env).forEach((k, v) -> sb.append(k).append('=').append(v).append(';'));
            sb.append('\n');
        }
        // Only when declared, so a tool without it keeps its fingerprint. Stamped on the template
        // at build, so a changed figure asks for the rebuild that stamps it
        if (resources.declaresMemory()) sb.append("memory=").append(resources.fingerprint()).append('\n');
        parameters.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> {
                    var p = e.getValue();
                    sb.append("param=").append(e.getKey()).append(',')
                      .append(p.getType()).append(',')
                      .append(p.getDefault()).append(',')
                      .append(p.getPattern()).append(',')
                      .append(p.getMin()).append(',')
                      .append(p.getMax()).append(',');
                    if (!p.getOptions().isEmpty()) {
                        p.getOptions().stream().sorted().forEach(opt -> sb.append(opt).append(';'));
                    }
                    sb.append('\n');
                });
        result = sha256hex(sb.toString());
        cacheFingerprint(result);
        return result;
    }

    /**
     * Compute composite fingerprints that incorporate transitive deps.
     * Each tool's entry in the result includes the fingerprints of all its
     * transitive dependencies, so a change in any dep propagates upward.
     * Each tool is resolved on its own, so a {@code requires} cycle is entered at that tool
     * whatever else is fingerprinted with it (#1142).
     *
     * @param rawFingerprints  tool name → own contentFingerprint()
     * @param depMap           tool name → list of required tool names
     * @return tool name → composite fingerprint (includes dep tree)
     */
    public static Map<String, String> compositeFingerprints(
            Map<String, String> rawFingerprints, Map<String, java.util.List<String>> depMap) {
        var result = new TreeMap<String, String>();
        for (var name : rawFingerprints.keySet()) {
            result.put(name, resolveComposite(name, rawFingerprints, depMap, new TreeMap<>(),
                    new java.util.LinkedHashSet<>()));
        }
        return result;
    }

    private static String resolveComposite(String name, Map<String, String> rawFps,
                                            Map<String, java.util.List<String>> depMap,
                                            Map<String, String> cache,
                                            java.util.Set<String> resolving) {
        if (cache.containsKey(name)) return cache.get(name);
        if (!resolving.add(name)) return rawFps.getOrDefault(name, "");
        var sb = new StringBuilder(rawFps.getOrDefault(name, ""));
        var deps = depMap.getOrDefault(name, java.util.List.of());
        for (var dep : deps.stream().sorted().toList()) {
            sb.append("+dep=").append(dep).append(':')
                    .append(resolveComposite(dep, rawFps, depMap, cache, resolving));
        }
        var result = deps.isEmpty() ? sb.toString() : sha256hex(sb.toString());
        cache.put(name, result);
        resolving.remove(name);
        return result;
    }

    public static String sha256hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static ToolDef loadFromStream(InputStream is) throws IOException {
        return Jjq.toolDef(is);
    }
}
