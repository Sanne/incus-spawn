package dev.incusspawn.config;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.util.Map;
import dev.incusspawn.ClientLog;
import dev.incusspawn.Environment;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Global incus-spawn configuration stored in ~/.config/incus-spawn/config.yaml
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpawnConfig {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** For {@link #tree()}; separate from YAML so the view is a plain node, not YAML-flavoured. */
    private static final ObjectMapper TREE = new ObjectMapper();

    /**
     * This config as a navigable tree.
     *
     * <p>Credential accounts are read off the serialized shape rather than typed fields -- that
     * is what lets a namespace isx has no Java for have accounts at all -- so several callers
     * need this view. Built here so none of them constructs an {@code ObjectMapper} of its own,
     * which is one of Jackson's most expensive objects and was being created per lookup.
     *
     * <p>Deliberately not cached: this object is mutable, and a stale tree would answer for a
     * credential that had just been changed. Hold the result when doing several lookups.
     */
    public com.fasterxml.jackson.databind.JsonNode tree() {
        return TREE.valueToTree(this);
    }

    private ClaudeConfig claude = new ClaudeConfig();
    private GitHubConfig github = new GitHubConfig();
    private BobConfig bob = new BobConfig();
    private OpenaiConfig openai = new OpenaiConfig();
    private java.util.List<String> features = java.util.List.of();
    private java.util.List<String> searchPaths = java.util.List.of();
    @JsonProperty(value = "host-path", access = JsonProperty.Access.WRITE_ONLY)
    private String hostPath = "";
    @JsonProperty("host-paths")
    private java.util.List<String> hostPaths = java.util.List.of();
    @JsonProperty("repo-paths")
    private Map<String, String> repoPaths = Map.of();
    @JsonProperty("incus-bridge-gateway")
    private String incusBridgeGateway = "";
    @JsonProperty("auto-clone-repos")
    private String autoCloneRepos = "";
    // Escape hatch for the TUI's live refresh (Incus event subscription plus its polling
    // fallback). Null means the default, on, and is never written back, so saving a config
    // doesn't pin today's default into every user's file.
    @JsonProperty("tui-live-refresh")
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private Boolean tuiLiveRefresh;
    private Map<String, Object> extras = new java.util.LinkedHashMap<>();

    /** How a Claude account authenticates. Named for what the credential is, not what isx uses it for. */
    public enum ClaudeAccountType {
        API_KEY("api-key"),
        OAUTH("oauth"),
        VERTEX("vertex");

        private final String wireName;

        ClaudeAccountType(String wireName) { this.wireName = wireName; }

        @com.fasterxml.jackson.annotation.JsonValue
        public String wireName() { return wireName; }

        @com.fasterxml.jackson.annotation.JsonCreator
        public static ClaudeAccountType fromWire(String value) {
            if (value == null) return null;
            var normalized = value.strip().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            for (var type : values()) {
                if (type.wireName.equals(normalized)) return type;
            }
            // Lenient by design: load() answers a mapping error with a blank SpawnConfig, so a
            // single typo here would otherwise discard host paths, tokens and all -- and the next
            // save would persist those empty defaults over the real file. A null type falls back
            // to effectiveType(), which infers from the fields that are set; an account that still
            // cannot be identified is dropped by the isComplete() filter.
            return null;
        }
    }

    /**
     * One Claude credential. Several may be configured at once so that different instances can
     * run as different accounts; {@link ClaudeConfig#account()} picks the one to use.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY,
            getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public static class ClaudeAccount {
        private ClaudeAccountType type;
        private String apiKey = "";
        private String oauthToken = "";
        private String cloudMlRegion = "";
        private String vertexProjectId = "";

        public ClaudeAccount() {}

        public static ClaudeAccount ofApiKey(String apiKey) {
            var account = new ClaudeAccount();
            account.setType(ClaudeAccountType.API_KEY);
            account.setApiKey(apiKey);
            return account;
        }

        public static ClaudeAccount ofOauth(String oauthToken) {
            var account = new ClaudeAccount();
            account.setType(ClaudeAccountType.OAUTH);
            account.setOauthToken(oauthToken);
            return account;
        }

        public static ClaudeAccount ofVertex(String cloudMlRegion, String vertexProjectId) {
            var account = new ClaudeAccount();
            account.setType(ClaudeAccountType.VERTEX);
            account.setCloudMlRegion(cloudMlRegion);
            account.setVertexProjectId(vertexProjectId);
            return account;
        }

        public void setType(ClaudeAccountType type) { this.type = type; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.strip(); }
        public String getOauthToken() { return oauthToken; }
        public void setOauthToken(String oauthToken) { this.oauthToken = oauthToken == null ? "" : oauthToken.strip(); }
        public String getCloudMlRegion() { return cloudMlRegion; }
        public void setCloudMlRegion(String cloudMlRegion) { this.cloudMlRegion = cloudMlRegion == null ? "" : cloudMlRegion.strip(); }
        public String getVertexProjectId() { return vertexProjectId; }
        public void setVertexProjectId(String vertexProjectId) { this.vertexProjectId = vertexProjectId == null ? "" : vertexProjectId.strip(); }

        /**
         * Infers the type when a hand-written account omits it, so the field stays optional for
         * the unambiguous cases rather than forcing boilerplate.
         */
        public ClaudeAccountType effectiveType() {
            if (type != null) return type;
            if (!vertexProjectId.isBlank() || !cloudMlRegion.isBlank()) return ClaudeAccountType.VERTEX;
            if (!oauthToken.isBlank()) return ClaudeAccountType.OAUTH;
            if (!apiKey.isBlank()) return ClaudeAccountType.API_KEY;
            return null;
        }

        /** True when this account carries the credential its type needs. */
        public boolean isComplete() {
            var resolved = effectiveType();
            if (resolved == null) return false;
            return switch (resolved) {
                case API_KEY -> !apiKey.isBlank();
                case OAUTH -> !oauthToken.isBlank();
                case VERTEX -> !cloudMlRegion.isBlank() && !vertexProjectId.isBlank();
            };
        }

        /**
         * True when isx can call the Anthropic API with this account directly (`isx ask`).
         *
         * <p>An OAuth account cannot: a Claude Pro/Max token is only valid for Claude Code
         * itself, and the Messages API rejects it with an opaque HTTP 429 otherwise. That is a
         * property of the credential, not of any particular feature.
         */
        public boolean servesDirectApi() {
            return isComplete() && effectiveType() != ClaudeAccountType.OAUTH;
        }

        /** Human-readable description of what this account is, for init and doctor. */
        public String describe() {
            var resolved = effectiveType();
            if (resolved == null) return "incomplete account";
            return switch (resolved) {
                case API_KEY -> "Anthropic API key";
                case OAUTH -> "Claude Pro/Max OAuth token";
                case VERTEX -> "Google Cloud Vertex AI (region: " + orNotSet(cloudMlRegion)
                        + ", project: " + orNotSet(vertexProjectId) + ")";
            };
        }

        private static String orNotSet(String value) { return value.isBlank() ? "<not set>" : value; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(
            fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY,
            getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE,
            isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public static class ClaudeConfig {
        /** Prefix of tokens minted by 'claude setup-token'. */
        public static final String OAUTH_TOKEN_PREFIX = "sk-ant-oat01-";
        public static final String PLACEHOLDER_OAUTH_TOKEN = OAUTH_TOKEN_PREFIX + "placeholder";

        /** Name the flat credential of a pre-accounts config.yaml presents as. */
        public static final String LEGACY_ACCOUNT_NAME = AccountResolver.FLAT_ACCOUNT_NAME;

        /** Config namespace, matching the {@code config-namespace} ClaudeSetup declares. */
        public static final String NAMESPACE = "claude";

        // Pre-accounts layout. Still read, and still written until credentials next change,
        // so a config.yaml written by an older isx (or copied between machines) keeps working.
        // NON_DEFAULT rather than NON_EMPTY: a primitive false is not "empty", and a stray
        // 'useVertex: false' alongside an accounts block reads like a contradiction.
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
        private boolean useVertex;
        private String cloudMlRegion = "";
        private String vertexProjectId = "";
        private String apiKey = "";
        private String oauthToken = "";

        private Map<String, ClaudeAccount> accounts = new java.util.LinkedHashMap<>();
        @JsonProperty("default")
        private String defaultAccount = "";

        public void setUseVertex(boolean useVertex) { this.useVertex = useVertex; }
        public void setCloudMlRegion(String cloudMlRegion) { this.cloudMlRegion = cloudMlRegion == null ? "" : cloudMlRegion.strip(); }
        public void setVertexProjectId(String vertexProjectId) { this.vertexProjectId = vertexProjectId == null ? "" : vertexProjectId.strip(); }
        public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.strip(); }
        public void setOauthToken(String oauthToken) { this.oauthToken = oauthToken == null ? "" : oauthToken.strip(); }

        public Map<String, ClaudeAccount> getAccounts() { return accounts; }
        public void setAccounts(Map<String, ClaudeAccount> accounts) {
            this.accounts = accounts == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(accounts);
        }

        public String getDefaultAccount() { return defaultAccount; }
        public void setDefaultAccount(String defaultAccount) {
            this.defaultAccount = defaultAccount == null ? "" : defaultAccount.strip();
        }

        /**
         * Claude's accounts as {@link AccountResolver} sees them.
         *
         * <p>Two things differ from a shape derived from secret keys. The pre-accounts layout
         * holds a Vertex credential that is not a secret at all -- {@code useVertex: true} with a
         * region and project -- and it must present as the account {@code default} even when
         * the region or project is missing, so ProxyMain can report exactly that
         * misconfiguration rather than "no credentials". And whether an account is usable
         * depends on its type: a Vertex account needs a region and a project, the others a key.
         */
        public static final AccountShape ACCOUNT_SHAPE = new AccountShape() {
            @Override
            public boolean hasFlatCredential(JsonNode namespace) {
                var flat = bind(namespace, ClaudeConfig.class);
                return flat != null && flat.legacyAccount() != null;
            }

            @Override
            public com.fasterxml.jackson.databind.node.ObjectNode flatAccount(JsonNode namespace) {
                var flat = bind(namespace, ClaudeConfig.class);
                var legacy = flat == null ? null : flat.legacyAccount();
                return legacy == null ? TREE.createObjectNode() : TREE.valueToTree(legacy);
            }

            @Override
            public java.util.List<String> flatKeys() {
                return java.util.List.of("useVertex", "cloudMlRegion", "vertexProjectId", "apiKey", "oauthToken");
            }

            @Override
            public String problem(JsonNode account, JsonNode namespace) {
                var typed = bind(account, ClaudeAccount.class);
                if (typed == null || typed.effectiveType() == null) return "it holds no credential";
                if (typed.isComplete()) return "";
                return "a " + typed.effectiveType().wireName() + " account is missing the fields its type requires";
            }
        };

        private static <T> T bind(JsonNode node, Class<T> type) {
            if (node == null || !node.isObject()) return null;
            try {
                return TREE.treeToValue(node, type);
            } catch (Exception e) {
                return null;
            }
        }

        /** This namespace alone, placed where {@link AccountResolver} expects to find it. */
        private JsonNode asConfigTree() {
            var root = TREE.createObjectNode();
            root.set(NAMESPACE, TREE.valueToTree(this));
            return root;
        }

        /** The typed account a resolved name refers to -- the flat credential for {@code default} on a pre-accounts file. */
        private ClaudeAccount typedAccount(String name) {
            if (accounts.isEmpty()) return LEGACY_ACCOUNT_NAME.equals(name) ? legacyAccount() : null;
            return accounts.get(name);
        }

        /**
         * Every configured account including incomplete ones, in file order. A pre-accounts
         * config.yaml contributes the single account its flat credential presents as, so the rest
         * of isx only ever deals with accounts. Used by the init UI so incomplete accounts are
         * visible and removable; credential resolution should use {@link #effectiveAccounts()}.
         */
        public Map<String, ClaudeAccount> allAccounts() {
            return accountsNamed(AccountResolver.accountNames(asConfigTree(), NAMESPACE, ACCOUNT_SHAPE));
        }

        /**
         * Every usable account, in file order -- the subset of {@link #allAccounts()} that
         * {@link AccountResolver} would serve. The flat credential is deliberately never filtered:
         * a flat {@code useVertex: true} with no region or project must still present as a
         * Vertex account so ProxyMain can report exactly that misconfiguration rather than
         * silently turning it into "no credentials".
         */
        public Map<String, ClaudeAccount> effectiveAccounts() {
            return accountsNamed(AccountResolver.usableAccountNames(asConfigTree(), NAMESPACE, ACCOUNT_SHAPE));
        }

        private Map<String, ClaudeAccount> accountsNamed(java.util.List<String> names) {
            var result = new java.util.LinkedHashMap<String, ClaudeAccount>();
            for (var name : names) {
                var account = typedAccount(name);
                if (account != null) result.put(name, account);
            }
            return result;
        }

        private ClaudeAccount legacyAccount() {
            if (useVertex) return ClaudeAccount.ofVertex(cloudMlRegion, vertexProjectId);
            if (!oauthToken.isBlank()) return ClaudeAccount.ofOauth(oauthToken);
            if (!apiKey.isBlank()) return ClaudeAccount.ofApiKey(apiKey);
            return null;
        }

        /** The account to use when nothing narrower applies: no instance or template selection. */
        public ClaudeAccount account() {
            return accountFor(a -> true);
        }

        /**
         * The account an instance or template explicitly named, or {@link #account()} when
         * it named none.
         *
         * <p><strong>Fails closed</strong>, through the same {@link AccountResolver} rule every
         * namespace follows: a name that is not configured raises rather than falling back to
         * the default, because an instance pinned to one client's subscription must never quietly
         * start spending another's (#351). An incomplete account is reported as such rather than
         * as missing, because it is easy to overlook in {@code isx init} (#742).
         */
        public ClaudeAccount accountNamed(String name) {
            if (name == null || name.isBlank()) return account();
            var resolved = AccountResolver.effectiveAccount(asConfigTree(), NAMESPACE, ACCOUNT_SHAPE, name);
            return typedAccount(resolved);
        }

        /** Name of the account {@link #account()} resolves to, or "" when none is configured. */
        public String accountName() {
            return accountNameFor(a -> true);
        }

        /**
         * The configured default when it can do the job, otherwise the first account that can.
         * A Pro/Max default still serves instances while 'isx ask' quietly uses an API-key or
         * Vertex account, without either having to be designated for that purpose.
         */
        public ClaudeAccount accountFor(java.util.function.Predicate<ClaudeAccount> usable) {
            var available = effectiveAccounts();
            var name = accountNameIn(available, usable);
            return name.isEmpty() ? null : available.get(name);
        }

        /**
         * {@code effectiveAccounts()} rebuilds its map on every call, so resolve against a single
         * snapshot rather than deriving it once to pick a name and again to look that name up.
         */
        public String accountNameFor(java.util.function.Predicate<ClaudeAccount> usable) {
            return accountNameIn(effectiveAccounts(), usable);
        }

        private String accountNameIn(Map<String, ClaudeAccount> available,
                                     java.util.function.Predicate<ClaudeAccount> usable) {
            if (available.isEmpty()) return "";
            var preferred = available.get(defaultAccount);
            if (preferred != null && usable.test(preferred)) return defaultAccount;
            for (var entry : available.entrySet()) {
                if (usable.test(entry.getValue())) return entry.getKey();
            }
            return "";
        }

        /** Replaces every configured account with a single one, keeping the file in one shape. */
        public void setSingleAccount(String name, ClaudeAccount account) {
            clearAuth();
            accounts.put(name, account);
            defaultAccount = name;
        }

        public void putAccount(String name, ClaudeAccount account) {
            // Materialize any pre-accounts credential first: clearLegacyFields() below would
            // otherwise delete it outright, switching every instance to the newcomer -- the
            // opposite of what "add another account" promises.
            adoptLegacyAccount();
            // Adding an account must not silently re-point the default at it.
            if (accounts.isEmpty() && defaultAccount.isBlank()) defaultAccount = name;
            accounts.put(name, account);
            clearLegacyFields();
        }

        /**
         * Moves a pre-accounts credential into {@code accounts.default}, the account it already
         * presents as, before the first typed write: the equivalent of
         * {@link NamespaceAccounts#materialize} for writes made through this typed API.
         */
        private void adoptLegacyAccount() {
            if (!accounts.isEmpty()) return;
            var legacy = legacyAccount();
            if (legacy == null || !legacy.isComplete()) return;
            accounts.put(LEGACY_ACCOUNT_NAME, legacy);
            if (defaultAccount.isBlank()) defaultAccount = LEGACY_ACCOUNT_NAME;
            clearLegacyFields();
        }

        // Effective accessors. Kept so callers that only ever need "the" account -- container
        // env, the proxy, doctor -- read the resolved one without knowing about the map.
        public boolean isUseVertex() {
            var account = account();
            return account != null && account.effectiveType() == ClaudeAccountType.VERTEX;
        }

        public String getCloudMlRegion() {
            var account = account();
            return account == null ? "" : account.getCloudMlRegion();
        }

        public String getVertexProjectId() {
            var account = account();
            return account == null ? "" : account.getVertexProjectId();
        }

        public String getApiKey() {
            var account = account();
            return account == null || account.effectiveType() != ClaudeAccountType.API_KEY
                    ? "" : account.getApiKey();
        }

        public String getOauthToken() {
            var account = account();
            return account == null || account.effectiveType() != ClaudeAccountType.OAUTH
                    ? "" : account.getOauthToken();
        }

        public boolean hasAuth() { return account() != null; }

        /** True when the container's tools should authenticate via a Claude Pro/Max OAuth token rather than a direct API key. */
        public boolean isOauthMode() {
            var account = account();
            return account != null && account.effectiveType() == ClaudeAccountType.OAUTH;
        }

        public void clearAuth() {
            accounts.clear();
            defaultAccount = "";
            clearLegacyFields();
        }

        private void clearLegacyFields() {
            useVertex = false;
            apiKey = "";
            oauthToken = "";
            cloudMlRegion = "";
            vertexProjectId = "";
        }
    }

    /**
     * Base for the credential namespaces that have a typed class, preserving any key the class
     * does not declare -- notably an {@code accounts:} block.
     *
     * <p>Without this, a namespace's named accounts would deserialize to nothing and then be
     * <em>deleted</em> by the next save, destroying credentials exactly the way the
     * {@code putAccount} bug found in review did. Namespaces with no Java class at all (a tool
     * defined purely in YAML) are already safe: they land in {@link SpawnConfig#extras}.
     *
     * <p>Accounts are read generically off the serialized tree by {@link AccountResolver}, so
     * nothing here needs to know their shape.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public abstract static class NamespaceConfig {
        private final Map<String, Object> extras = new java.util.LinkedHashMap<>();

        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void setExtra(String key, Object value) { extras.put(key, value); }

        @com.fasterxml.jackson.annotation.JsonAnyGetter
        public Map<String, Object> getExtras() { return extras; }

        /** The config namespace this class holds, matching its tool's {@code config-namespace}. */
        protected abstract String namespace();

        /**
         * {@code key} for the account this namespace resolves to by default, whether the
         * credential is saved under {@code accounts:} or in the flat, pre-accounts layout.
         *
         * <p>The typed getters read only the flat field, which is empty once a credential lives in
         * an account; asking "is this configured" of them would say no for every file written
         * since. Resolved against the built-in tool's shape, which every typed namespace has, so
         * this never scans for tool definitions.
         */
        public String defaultValue(String key) {
            var namespace = namespace();
            var tree = TREE.createObjectNode();
            tree.set(namespace, TREE.valueToTree(this));
            var shape = AccountResolver.shapeOf(Map.of(), namespace);
            var account = AccountResolver.effectiveAccount(tree, namespace, shape, null);
            return AccountResolver.value(tree, namespace, account, key);
        }
    }

    // NON_EMPTY, like ClaudeConfig: once a credential moves into an account the flat fields
    // are blank, and writing them back as "" makes an older isx read the namespace as
    // configured-but-empty rather than simply absent.
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GitHubConfig extends NamespaceConfig {
        private String token = "";
        private String email = "";

        /** The flat, pre-accounts token; see {@link #defaultValue} for the one actually in use. */
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token == null ? "" : token.strip(); }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email == null ? "" : email; }
        @Override protected String namespace() { return "github"; }
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BobConfig extends NamespaceConfig {
        private String apiKey = "";
        private boolean licenseConsent;

        /** The flat, pre-accounts key; see {@link #effectiveApiKey} for the one actually in use. */
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.strip(); }
        /** The key of the account Bob resolves to by default, flat or under {@code accounts:}. */
        public String effectiveApiKey() { return defaultValue("apiKey"); }
        public boolean hasAuth() { return !effectiveApiKey().isBlank(); }
        /** Shared by every account: consenting to the licence is not a property of one key. */
        public boolean isLicenseConsent() { return licenseConsent; }
        public void setLicenseConsent(boolean licenseConsent) { this.licenseConsent = licenseConsent; }
        @Override protected String namespace() { return "bob"; }
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenaiConfig extends NamespaceConfig {
        private String apiKey = "";

        /** The flat, pre-accounts key; see {@link #effectiveApiKey} for the one actually in use. */
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.strip(); }
        /** The key of the account OpenAI resolves to by default, flat or under {@code accounts:}. */
        public String effectiveApiKey() { return defaultValue("apiKey"); }
        public boolean hasAuth() { return !effectiveApiKey().isBlank(); }
        @Override protected String namespace() { return "openai"; }
    }

    public java.util.List<String> getFeatures() { return features; }
    public void setFeatures(java.util.List<String> features) { this.features = features == null ? java.util.List.of() : features; }
    public boolean isFeatureEnabled(String feature) {
        if (features.contains(feature)) return true;
        // Implicitly enable features when the user already has credentials configured,
        // so upgrading doesn't silently break existing setups.
        if ("openai".equals(feature)) return openai.hasAuth();
        return false;
    }

    public ClaudeConfig getClaude() { return claude; }
    public void setClaude(ClaudeConfig claude) { this.claude = claude; }
    public GitHubConfig getGithub() { return github; }
    public void setGithub(GitHubConfig github) { this.github = github; }
    public BobConfig getBob() { return bob; }
    public void setBob(BobConfig bob) { this.bob = bob; }
    public OpenaiConfig getOpenai() { return openai; }
    public void setOpenai(OpenaiConfig openai) { this.openai = openai; }
    public java.util.List<String> getSearchPaths() { return searchPaths; }
    public void setSearchPaths(java.util.List<String> searchPaths) { this.searchPaths = searchPaths == null ? java.util.List.of() : searchPaths; }
    public String getHostPath() { return hostPath; }
    public void setHostPath(String hostPath) { this.hostPath = hostPath == null ? "" : hostPath; }
    public java.util.List<String> getHostPaths() {
        if (!hostPaths.isEmpty()) {
            return hostPaths;
        }
        if (!hostPath.isEmpty()) {
            return java.util.List.of(hostPath);
        }
        return java.util.List.of();
    }
    public void setHostPaths(java.util.List<String> hostPaths) { this.hostPaths = hostPaths == null ? java.util.List.of() : hostPaths; }
    public Map<String, String> getRepoPaths() { return repoPaths; }
    public void setRepoPaths(Map<String, String> repoPaths) { this.repoPaths = repoPaths == null ? Map.of() : repoPaths; }
    public String getIncusBridgeGateway() { return incusBridgeGateway; }
    public void setIncusBridgeGateway(String incusBridgeGateway) { this.incusBridgeGateway = incusBridgeGateway == null ? "" : incusBridgeGateway; }
    public String getAutoCloneRepos() { return autoCloneRepos; }

    // Deliberately not a bean getter: Jackson would serialize its boolean, writing the default out.
    public boolean tuiLiveRefreshEnabled() { return tuiLiveRefresh == null || tuiLiveRefresh; }
    public void setTuiLiveRefresh(Boolean enabled) { this.tuiLiveRefresh = enabled; }
    public void setAutoCloneRepos(String autoCloneRepos) { this.autoCloneRepos = autoCloneRepos == null ? "" : autoCloneRepos; }
    @JsonAnySetter
    public void setExtra(String key, Object value) { extras.put(key, value); }

    @JsonAnyGetter
    public Map<String, Object> getExtras() { return extras; }

    /**
     * Remove a dotted config path, and any ancestor left empty by the removal.
     *
     * <p>The counterpart to {@link #setConfigByPath}: blanking a key would leave an empty
     * string behind, which reads back as a configured-but-empty credential rather than as
     * absent. Pruning empty parents is what keeps a removed account from lingering as
     * {@code accounts: {acme: {}}}, which {@code AccountResolver} would still list.
     */
    public void removeConfigPath(String dotPath) {
        var segments = dotPath.split("\\.");
        if (segments.length == 0) return;
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) YAML.valueToTree(this);
        var chain = new java.util.ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
        var node = tree;
        for (int i = 0; i < segments.length - 1; i++) {
            chain.add(node);
            var child = node.get(segments[i]);
            if (!(child instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) return;
            node = object;
        }
        chain.add(node);
        node.remove(segments[segments.length - 1]);
        for (int i = chain.size() - 1; i > 0; i--) {
            if (!chain.get(i).isEmpty()) break;
            chain.get(i - 1).remove(segments[i - 1]);
        }
        try {
            // readerForUpdating merges rather than replaces, so a removed key would survive.
            // Re-read the pruned tree into a fresh instance and copy it over this one.
            var replacement = YAML.treeToValue(tree, SpawnConfig.class);
            copyFrom(replacement);
        } catch (Exception e) {
            throw new RuntimeException("Failed to remove config path " + dotPath, e);
        }
    }

    /** Overwrite every field from another instance, for a whole-tree replacement. */
    private void copyFrom(SpawnConfig other) {
        this.claude = other.claude;
        this.github = other.github;
        this.bob = other.bob;
        this.openai = other.openai;
        this.features = other.features;
        this.searchPaths = other.searchPaths;
        this.hostPath = other.hostPath;
        this.hostPaths = other.hostPaths;
        this.repoPaths = other.repoPaths;
        this.incusBridgeGateway = other.incusBridgeGateway;
        this.autoCloneRepos = other.autoCloneRepos;
        this.tuiLiveRefresh = other.tuiLiveRefresh;
        this.extras = other.extras;
    }

    public void setConfigByPath(String dotPath, String value) {
        var segments = dotPath.split("\\.");
        if (segments.length == 0) return;
        var tree = YAML.valueToTree(this);
        var node = tree;
        for (int i = 0; i < segments.length - 1; i++) {
            var child = node.get(segments[i]);
            if (child == null || !child.isObject()) {
                child = YAML.createObjectNode();
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).set(segments[i], child);
            }
            node = child;
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(segments[segments.length - 1], value);
        try {
            YAML.readerForUpdating(this).readValue(tree);
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply config path " + dotPath, e);
        }
    }

    public static Path configDir() {
        return Environment.configDir();
    }

    /**
     * Check whether the given image (or any unbuilt ancestor) requires auth credentials
     * that have not been configured. Returns a non-empty error message if credentials
     * are missing, or empty string if everything is configured.
     *
     * @param imageDef the image to check
     * @param allDefs  all known image definitions (for parent resolution)
     * @param existsCheck  predicate to test whether an image already exists (skip parent check if built)
     */
    public static String checkCredentials(ImageDef imageDef, java.util.Map<String, ImageDef> allDefs,
                                           java.util.function.Predicate<String> existsCheck) {
        var config = load();
        var missing = new java.util.LinkedHashSet<String>();

        // Collect tools from this image and any unbuilt ancestors (first occurrence wins,
        // so child overrides take precedence over ancestor tool refs).
        var toolRefs = new java.util.LinkedHashMap<String, dev.incusspawn.tool.ToolDef.ToolRef>();
        var current = imageDef;
        while (current != null) {
            for (var toolRef : current.getTools()) {
                toolRefs.putIfAbsent(toolRef.getName(), toolRef);
            }
            if (current.isRoot() || existsCheck.test(current.getParent())) break;
            current = allDefs.get(current.getParent());
        }
        var tools = toolRefs.keySet();

        if (tools.contains("claude")) {
            if (!config.getClaude().hasAuth()) {
                missing.add("Anthropic API key, OAuth token, or Vertex AI");
            }
        }
        if (tools.contains("pi")) {
            var piProvider = toolRefs.get("pi").getParams().getOrDefault("provider", "anthropic");
            if ("openai".equals(piProvider)) {
                if (!config.getOpenai().hasAuth()) {
                    missing.add("OpenAI API key");
                }
            } else if ("vertex".equals(piProvider) || "google".equals(piProvider)) {
                if (!config.getClaude().isUseVertex()) {
                    missing.add("Vertex AI configuration");
                }
            } else {
                if (!config.getClaude().hasAuth()) {
                    missing.add("Anthropic API key, OAuth token, or Vertex AI");
                }
            }
        }
        // Account-aware: a credential may live under the template's account rather than the flat
        // field, and AccountResolver.value falls back to the flat one either way. A pin to an
        // account that does not exist is reported below, so it does not also count as missing.
        var selection = ImageDef.resolveAccounts(imageDef, allDefs);
        java.util.function.BiPredicate<String, String> configured = (namespace, key) -> {
            try {
                var account = AccountResolver.effectiveAccount(config, namespace, selection.get(namespace));
                return !AccountResolver.value(config, namespace, account, key).isBlank();
            } catch (AccountResolver.UnknownAccountException e) {
                return true;
            }
        };
        if (tools.contains("gh") && !configured.test("github", "token")) {
            missing.add("GitHub token");
        }
        if (tools.contains("bob") && !configured.test("bob", "apiKey")) {
            missing.add("Bob API key");
        }
        if (tools.contains("codex") && config.isFeatureEnabled("openai")
                && !configured.test("openai", "apiKey")) {
            missing.add("OpenAI API key");
        }

        // A template naming an account that is not configured is a configuration problem and
        // should read as one here, rather than surfacing later as an exception mid-build.
        // Reported on its own because it already explains itself, and because the fix is to
        // correct the template or add the account, not simply to run 'isx init'.
        try {
            AccountSelection.validate(config, ImageDef.resolveAccounts(imageDef, allDefs));
        } catch (AccountResolver.UnknownAccountException e) {
            return e.getMessage();
        }

        if (missing.isEmpty()) return "";
        return "Missing credentials: " + String.join(", ", missing) + ". Run 'isx init' to configure.";
    }

    public static SpawnConfig load() {
        var configFile = configDir().resolve("config.yaml");
        if (!Files.exists(configFile)) {
            return new SpawnConfig();
        }
        try {
            var config = YAML.readValue(configFile.toFile(), SpawnConfig.class);
            config.migrateHostPath();
            return config;
        } catch (IOException e) {
            ClientLog.warn(YamlErrors.friendly("config.yaml", e));
            return new SpawnConfig();
        }
    }

    void migrateHostPath() {
        if (hostPath.isEmpty()) return;
        if (hostPaths.isEmpty()) {
            hostPaths = java.util.List.of(hostPath);
        } else if (!hostPaths.contains(hostPath)) {
            var merged = new java.util.ArrayList<>(hostPaths);
            merged.add(0, hostPath);
            hostPaths = java.util.List.copyOf(merged);
        }
        hostPath = "";
    }

    public void save() {
        try {
            migrateHostPath();
            var configFile = configDir().resolve("config.yaml");
            Files.createDirectories(configFile.getParent());
            YAML.writeValue(configFile.toFile(), this);
            // Restrict permissions - config contains tokens
            configFile.toFile().setReadable(false, false);
            configFile.toFile().setReadable(true, true);
            configFile.toFile().setWritable(false, false);
            configFile.toFile().setWritable(true, true);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config: " + e.getMessage(), e);
        }
    }
}
