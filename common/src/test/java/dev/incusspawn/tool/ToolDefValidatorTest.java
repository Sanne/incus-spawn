package dev.incusspawn.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolDefValidatorTest {

    @Test void anArchitectureIsXOrAarch64(@TempDir Path dir) throws Exception {
        var file = dir.resolve("arch.yaml");
        Files.writeString(file, """
                name: arch
                architectures: [x86_64, arm64]
                """);
        var result = ToolDefValidator.validate(file);
        assertEquals(List.of("architectures in 'arch' names 'arm64'; use x86_64 or aarch64"), result.errors());
        assertEquals(List.of(), result.warnings());
    }

    @Test void aDownloadsArchIsX86OrAarch64(@TempDir Path dir) throws Exception {
        var file = dir.resolve("dl.yaml");
        Files.writeString(file, """
                name: dl
                downloads:
                  - url: https://example.com/dl
                    destination_file: /usr/local/bin/dl
                    arch: arm64
                """);
        assertEquals(List.of("download entry in 'dl' has arch 'arm64'; use x86_64 or aarch64"),
                ToolDefValidator.validate(file).errors());
    }

    @Test void lingeringThroughLoginctlWarnsAndTheLingerFileDoesNot(@TempDir Path dir) throws Exception {
        var file = dir.resolve("lingers.yaml");
        Files.writeString(file, """
                name: lingers
                packages: [dbus-broker, systemd-pam]
                run_as_user:
                  - sudo loginctl --no-ask-password enable-linger agentuser
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().getFirst().contains("/var/lib/systemd/linger/"), result.warnings().getFirst());

        Files.writeString(file, """
                name: lingers
                run:
                  - |
                    loginctl --no-ask-password \\
                      enable-linger agentuser
                """);
        assertEquals(1, ToolDefValidator.validate(file).warnings().size(), "a continued line is the same command");

        Files.writeString(file, """
                name: lingers
                parameters:
                  flavour:
                run:
                  - loginctl enable-linger agentuser
                """);
        var withBareKey = ToolDefValidator.validate(file);
        assertTrue(withBareKey.hasErrors(), "the bare key is refused");
        assertEquals(1, withBareKey.warnings().size(), "a bare key does not hide it");

        Files.writeString(file, """
                name: lingers
                packages: [dbus-broker, systemd-pam]
                run:
                  - |
                    # not 'loginctl enable-linger', which needs logind running
                    mkdir -p /var/lib/systemd/linger && touch /var/lib/systemd/linger/agentuser
                """);
        assertFalse(ToolDefValidator.validate(file).hasWarnings());
    }

    @Test void borrowedNamespaceDoesNotNeedItsOwnPlaceholderDeclaration(@TempDir Path dir) throws Exception {
        var file = dir.resolve("borrowed.yaml");
        Files.writeString(file, """
                name: borrowed
                requires: [gh]
                proxy:
                  configuration:
                    token:
                      config-path: github.token
                      description: Shared GitHub token
                      secret: true
                  auth:
                    - domains: [custom.example]
                      type: bearer
                      token: '${token}'
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors(), result.errors().toString());
        assertFalse(result.hasWarnings(), result.warnings().toString());
    }

    @Test void authWithoutPlaceholdersWarnsAndDeclaringTheVariableFixesIt(@TempDir Path dir) throws Exception {
        var file = dir.resolve("custom.yaml");
        var yaml = """
                name: custom
                env:
                  - name: CUSTOM_TOKEN
                    value: custom_placeholder
                proxy:
                  config-namespace: custom
                  configuration:
                    token:
                      config-path: token
                      description: Custom token
                      secret: true
                  auth:
                    - domains: [custom.example]
                      type: bearer
                      token: '${token}'
                """;
        Files.writeString(file, yaml);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().getFirst().contains("namespace 'custom' has no declared placeholders"));
        Files.writeString(file, yaml.replace("  config-namespace: custom",
                "  placeholders:\n    - env: CUSTOM_TOKEN\n      prefix: custom_\n  config-namespace: custom"));
        result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors(), result.errors().toString());
        assertFalse(result.hasWarnings(), result.warnings().toString());
    }

    @Test void invalidPathScopesAreRejected(@TempDir Path dir) throws Exception {
        var file = dir.resolve("scoped.yaml");
        var yaml = """
                name: scoped
                env:
                  - name: SCOPED_TOKEN
                    value: scoped_placeholder
                proxy:
                  config-namespace: scoped
                  placeholders:
                    - env: SCOPED_TOKEN
                      prefix: scoped_
                  configuration:
                    token:
                      config-path: token
                      description: Scoped token
                      secret: true
                  auth:
                    - domains: [scoped.example]
                      paths: [PATHS]
                      type: bearer
                      token: '${token}'
                """;
        Files.writeString(file, yaml.replace("PATHS", "/, /mcp, /mcp/, /api/v1"));
        var valid = ToolDefValidator.validate(file);
        assertFalse(valid.hasErrors(), valid.errors().toString());

        for (var invalid : Map.of(
                "''", "is empty", "mcp", "must start with '/' (for example '/mcp')",
                "'/mcp '", "whitespace", "' /api'", "whitespace", "'/mcp?x=1'", "query", "'/mcp/*'", "wildcards",
                "'/a//b'", "empty segment", "/a/../b", "'..' segment",
                "'/a;b'", "must not contain ';'", "'/a%2Fb'", "no upstream may read").entrySet()) {
            Files.writeString(file, yaml.replace("PATHS", "/ok, " + invalid.getKey()));
            var result = ToolDefValidator.validate(file);
            assertEquals(1, result.errors().size(), invalid.getKey() + ": " + result.errors());
            assertTrue(result.errors().getFirst().contains(invalid.getValue()), result.errors().getFirst());
            assertTrue(result.errors().getFirst().contains("[scoped.example]"), result.errors().getFirst());
        }
    }

    @Test
    void validToolPasses(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                description: A test tool
                packages:
                  - htop
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void missingNameIsError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                description: No name tool
                packages:
                  - htop
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("'name'")));
    }

    @Test
    void duplicateKeyIsError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                packages:
                  - foo
                packages:
                  - bar
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("duplicate key")));
    }

    @Test
    void downloadWithoutUrlIsAnError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                downloads:
                  - sha256: abc123
                    extract: /opt
                """);
        var result = ToolDefValidator.validate(file);
        assertEquals(List.of("download entry in 'my-tool' is missing a 'url'"), result.errors());
    }

    @Test
    void downloadWithDestinationFileDoesNotRequireExtract(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                downloads:
                  - url: https://example.com/completion.sh
                    destination_file: ~/.bashrc.d/completion.sh
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
    }

    @Test
    void downloadRequiresExtractOrDestinationFile(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                downloads:
                  - url: https://example.com/tool.tar.gz
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("extract")
                && e.contains("destination_file")));
    }

    @Test
    void invalidParameterTypeWarns(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                parameters:
                  size:
                    type: float
                    default: "1.5"
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("float") && w.contains("type")));
    }

    @Test
    void enumWithoutOptionsWarns(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                parameters:
                  color:
                    type: enum
                    default: red
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("enum") && w.contains("options")));
    }

    @Test
    void integerMinGreaterThanMaxWarns(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                parameters:
                  count:
                    type: integer
                    min: 100
                    max: 10
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("min") && w.contains("max")));
    }

    @Test
    void validParametersPasses(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                parameters:
                  memory:
                    type: string
                    default: "2g"
                    pattern: "^[0-9]+[gGmM]$"
                  count:
                    type: integer
                    min: 1
                    max: 100
                  color:
                    type: enum
                    default: red
                    options:
                      - red
                      - blue
                  debug:
                    type: boolean
                    default: "false"
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertFalse(result.hasWarnings());
    }

    @Test
    void builtinToolsPassValidation() throws Exception {
        var builtins = new String[]{
                "tools/maven-3.yaml", "tools/mvnd.yaml", "tools/idea-backend.yaml",
                "tools/starship.yaml", "tools/podman.yaml", "tools/sshd.yaml", "tools/tmux.yaml",
                "tools/typesafe.yaml"
        };
        for (var resource : builtins) {
            try (var is = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (is == null) continue;
                var def = ToolDef.loadFromStream(is);
                var errors = new java.util.ArrayList<String>();
                var warnings = new java.util.ArrayList<String>();
                ToolDefValidator.validateDef(def, errors, warnings);
                assertTrue(errors.isEmpty(), resource + " has validation errors: " + errors);
            }
        }
    }

    @Test
    void proxyMissingDomainsIsError() throws Exception {
        var file = writeTemp("""
                name: bad-proxy
                proxy:
                  auth:
                    - type: bearer
                      token: "${t}"
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("missing 'domains'")));
    }

    @Test
    void proxyDomainsOfOnlyEmptyItemsIsMissingDomains() throws Exception {
        var file = writeTemp("""
                name: blank-domain
                proxy:
                  auth:
                    - domains:
                        -
                      type: bearer
                      token: "${t}"
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("missing 'domains'")), result.errors().toString());
    }

    @Test
    void proxyInvalidAuthTypeIsError() throws Exception {
        var file = writeTemp("""
                name: bad-auth
                proxy:
                  auth:
                    - domains:
                        - api.example.com
                      type: invalid
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("invalid auth type")));
    }

    @Test
    void proxyConfigPathAndValueMutualExclusion() throws Exception {
        var file = writeTemp("""
                name: both-set
                proxy:
                  config-namespace: bothset
                  configuration:
                    token:
                      config-path: "path"
                      value: "literal"
                  auth:
                    - domains:
                        - api.example.com
                      type: bearer
                      token: "${token}"
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("both 'config-path' and 'value'")));
    }

    @Test
    void proxyConfigWithoutDescriptionWarns() throws Exception {
        var file = writeTemp("""
                name: no-desc
                proxy:
                  config-namespace: nodesc
                  configuration:
                    token:
                      secret: true
                  auth:
                    - domains:
                        - api.example.com
                      type: bearer
                      token: "${token}"
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("no description")));
    }

    @Test
    void aDomainReferenceMustBeADefinedEntryAndNoSecret() throws Exception {
        var file = writeTemp("""
                name: configured
                proxy:
                  config-namespace: configured
                  configuration:
                    instances:
                      config-path: instances
                      description: "servers"
                    key:
                      config-path: key
                      description: "access key"
                      secret: true
                  auth:
                    - domains:
                        - "${instances}"
                        - "${key}.example.com"
                        - "${nowhere}"
                        - "*.${instances}"
                      type: bearer
                      token: "${key}"
                """);
        var errors = ToolDefValidator.validate(file).errors();
        assertTrue(errors.stream().anyMatch(e -> e.contains("${nowhere}") && e.contains("not defined")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("secret '${key}'")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("'*.${instances}' is a wildcard")), errors.toString());
        assertEquals(3, errors.size(), "a defined, non-secret reference is fine: " + errors);
    }

    /** Yoann's Develocity tool in the for-each form, with {@code %s} where a case changes it. */
    private static final String DEVELOCITY = """
            name: develocity
            env:
              - name: DEVELOCITY_TOKEN
                value: develocity-placeholder
            proxy:
              config-namespace: develocity
              placeholders:
                - env: DEVELOCITY_TOKEN
                  prefix: develocity-
              configuration:
                servers:
                  config-path: servers
                  description: "Develocity servers"
                  for-each:
                    host:
                      description: "Server host"
                    access-key:
                      config-path: accessKey
                      description: "Access key"
                      secret: true
              auth:
                - for-each: servers
                  domains: ["${servers.host}"]
                  paths: [/mcp]
                  type: bearer
                  token: "${servers.access-key}"
            mcp:
              - for-each: servers
                name: "%s"
                url: "%s"
                bearer-token-env: DEVELOCITY_TOKEN
            """;

    private static List<String> develocityErrors(String name, String url) {
        return ToolDefValidator.validate(DEVELOCITY.formatted(name, url), "develocity").errors();
    }

    @Test
    void aServerRepeatedForEachItemIsValid() {
        var result = ToolDefValidator.validate(DEVELOCITY.formatted("develocity-${servers.label}",
                "https://${servers.host}/mcp"), "develocity");
        assertEquals(List.of(), result.errors());
        assertTrue(result.warnings().stream().noneMatch(w -> w.contains("unknown") || w.contains("ignore")), result.warnings().toString());
    }

    @Test
    void aRepeatedServerMayReadAParameterInItsUrl() {
        // The sampled url keeps ${param_...} as written, never read as a group reference
        assertEquals(List.of(), develocityErrors("develocity-${servers.label}", "https://${servers.host}/${param_path}"));
    }

    @Test
    void aRepeatedServerIsNamedForItsItemAndReachesOnlyAServedHost() {
        assertTrue(develocityErrors("develocity", "https://${servers.host}/mcp").stream()
                .anyMatch(e -> e.contains("must hold '${servers.label}'")));
        assertTrue(develocityErrors("develocity-${servers.label}", "https://ge.example.test/mcp").stream()
                .anyMatch(e -> e.contains("url must be 'https://${servers.<field>}/")));
        assertTrue(develocityErrors("develocity-${servers.label}", "https://${servers.host}.evil.test/mcp").stream()
                .anyMatch(e -> e.contains("url must be 'https://${servers.<field>}/")));
        assertTrue(develocityErrors("develocity-${servers.label}", "https://${servers.host}/${servers.access-key}").stream()
                .anyMatch(e -> e.contains("is a secret")));
        assertTrue(develocityErrors("develocity-${servers.label}", "https://${servers.host}/${other.thing}").stream()
                .anyMatch(e -> e.contains("is not the label or a field")));
    }

    @Test
    void aListsFieldsAreReadOnlyWhereTheyRepeat() {
        var yaml = """
                name: lists
                proxy:
                  config-namespace: lists
                  configuration:
                    servers:
                      config-path: servers
                      description: "servers"
                      secret: true
                      for-each:
                        host: { description: "host" }
                        key: { config-path: a.b, secret: true }
                        label: { description: "clashes with the item's name" }
                  auth:
                    - for-each: servers
                      domains: ["${servers.key}", "x.${servers.host}"]
                      type: bearer
                      token: "${servers.key}"
                    - domains: [static.example.test]
                      type: bearer
                      token: "${servers.key}"
                    - for-each: nowhere
                      domains: ["${servers.host}"]
                      type: bearer
                      token: "${servers}"
                """;
        var errors = ToolDefValidator.validate(yaml, "lists").errors();
        for (var expected : List.of("a list is not a secret itself", "its config-path is one key of the item",
                "'label' is the item's own name", "references secret '${servers.key}' in domains",
                "each domain must be one field of a servers item alone", "which only an entry with for-each: servers reads",
                "for-each 'nowhere' is not a configuration entry", "references the list '${servers}'")) {
            assertTrue(errors.stream().anyMatch(e -> e.contains(expected)), expected + " in " + errors);
        }
    }

    @Test
    void proxyMissingConfigRefIsError() throws Exception {
        var file = writeTemp("""
                name: bad-ref
                proxy:
                  config-namespace: badref
                  configuration:
                    user:
                      value: "admin"
                  auth:
                    - domains:
                        - api.example.com
                      type: basic
                      username: "${user}"
                      password: "${pass}"
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("${pass}") && e.contains("not defined")));
    }

    @Test
    void proxyMissingNamespaceIsError() throws Exception {
        var file = writeTemp("""
                name: no-ns
                proxy:
                  configuration:
                    token:
                      config-path: "token"
                  auth:
                    - domains:
                        - api.example.com
                      type: bearer
                      token: "${token}"
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("no 'config-namespace'")));
    }

    /**
     * A namespace is an identifier: it is printed in isx init's account prompts and in isx
     * account, and is a key of config.yaml. A cloned repository's tool must not carry terminal
     * control sequences or line breaks there (#1266).
     */
    @Test
    void configNamespaceMustBeAnIdentifier() throws Exception {
        for (var bad : List.of("\u001b]0;x\u0007", "a\u009bb", "two\nlines", "rtl\u202eok", "-lead", "has space")) {
            var def = proxyTool(bad, "token");
            var errors = new ArrayList<String>();
            ToolDefValidator.validateDef(def, errors, new ArrayList<>());
            assertTrue(errors.stream().anyMatch(e -> e.contains("config-namespace")), bad + ": " + errors);
            assertTrue(errors.stream().allMatch(e -> e.chars().noneMatch(c -> Character.isISOControl(c) || c == 0x202e)),
                    "printed raw: " + errors);
        }
    }

    /** A config.yaml a previous release wrote may hold camelCase or dotted namespaces; they still load. */
    @Test
    void configNamespaceAcceptsExistingIdentifiers() {
        for (var good : List.of("github", "testAccountTool", "my-service", "my_service", "corp.artifactory", "s3")) {
            var errors = new ArrayList<String>();
            ToolDefValidator.validateDef(proxyTool(good, "token"), errors, new ArrayList<>());
            assertEquals(List.of(), errors, good);
        }
    }

    /**
     * A tool's name is an identifier too: bash completion feeds the names to compgen -W, which
     * runs a command substitution in one, and a cloned repository's tool named {@code $(...)}
     * ran it on TAB (#1778).
     */
    @Test
    void toolNameMustBeAnIdentifier() {
        for (var bad : List.of("$(touch pwned)", "`id`", "${HOME}", "\u001b]0;x\u0007", "two\nlines", "rtl\u202eok",
                "-lead", ".hidden", "has space", "a;b", "*")) {
            var def = new ToolDef();
            def.setName(bad);
            var errors = new ArrayList<String>();
            ToolDefValidator.validateDef(def, errors, new ArrayList<>());
            assertTrue(errors.stream().anyMatch(e -> e.contains("tool name")), bad + ": " + errors);
            assertTrue(errors.stream().allMatch(e -> e.chars().noneMatch(c -> Character.isISOControl(c) || c == 0x202e)),
                    "printed raw: " + errors);
        }
    }

    /**
     * A refused name is the only error: every later message names the tool, which would print
     * a cloned repository's escape sequence raw.
     */
    @Test
    void anInvalidNameIsTheOnlyErrorReported() {
        var def = new ToolDef();
        def.setName("evil\u001b]0;x\u0007");
        def.setDownloads(List.of(new ToolDef.DownloadEntry()));
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        ToolDefValidator.validateDef(def, errors, warnings);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().contains("tool name"), errors.toString());
        assertTrue(java.util.stream.Stream.concat(errors.stream(), warnings.stream())
                .allMatch(e -> e.chars().noneMatch(Character::isISOControl)), "printed raw: " + errors + warnings);
    }

    /** Every tool isx ships, YAML or Java, already has such a name, as do the names other tools use. */
    @Test
    void toolNameAcceptsEveryBuiltInName(@TempDir Path home) {
        // Only the built-ins: an empty home and no search paths, so no tool of the developer's own is read
        var previous = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        java.util.Set<String> builtIns;
        try {
            builtIns = new ToolDefLoader(List.of()).allToolSetups().keySet();
        } finally {
            System.setProperty("user.home", previous);
        }
        var names = new java.util.TreeSet<>(builtIns);
        names.addAll(List.of("maven-3", "gemini-cli", "idea-backend", "openjdk-primer", "my_tool", "corp.tool", "Tool2"));
        for (var name : names) {
            var def = new ToolDef();
            def.setName(name);
            var errors = new ArrayList<String>();
            ToolDefValidator.validateDef(def, errors, new ArrayList<>());
            assertTrue(errors.stream().noneMatch(e -> e.contains("tool name")), name + ": " + errors);
        }
    }

    /** A tool borrowing another namespace names it in config-path, which is checked the same way. */
    @Test
    void borrowedNamespaceMustBeAnIdentifier() {
        var errors = new ArrayList<String>();
        ToolDefValidator.validateDef(proxyTool("", "\u001b[2Jgh.token"), errors, new ArrayList<>());
        assertTrue(errors.stream().anyMatch(e -> e.contains("namespace")), errors.toString());
    }

    /** The dotted-path warning shows the config-path, which a cloned repository wrote. */
    @Test
    void dottedConfigPathWarningIsOneLine() {
        var warnings = new ArrayList<String>();
        ToolDefValidator.validateDef(proxyTool("custom", "gh.\u001b[2Jx"), new ArrayList<>(), warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("contains a dot")), warnings.toString());
        assertTrue(warnings.stream().allMatch(w -> w.chars().noneMatch(Character::isISOControl)), warnings.toString());
    }

    private static ToolDef proxyTool(String namespace, String configPath) {
        var entry = new ToolDef.ConfigEntry();
        entry.setConfigPath(configPath);
        entry.setSecret(true);
        entry.setDescription("Token");
        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace(namespace);
        proxy.setConfiguration(Map.of("token", entry));
        var def = new ToolDef();
        def.setName("ns-tool");
        def.setProxy(proxy);
        return def;
    }

    @Test
    void proxyDottedConfigPathWarns() throws Exception {
        var file = writeTemp("""
                name: dotted
                proxy:
                  config-namespace: dotted
                  configuration:
                    token:
                      config-path: "some.nested.path"
                  auth:
                    - domains:
                        - api.example.com
                      type: bearer
                      token: "${token}"
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("contains a dot")));
    }

    @Test
    void bearerAuthRequiresToken() throws Exception {
        var file = writeTemp("""
                name: no-token
                proxy:
                  config-namespace: notoken
                  configuration:
                    key:
                      value: "val"
                  auth:
                    - domains:
                        - api.example.com
                      type: bearer
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("bearer auth requires 'token'")));
    }

    @Test
    void basicAuthRequiresUsernameAndPassword() throws Exception {
        var file = writeTemp("""
                name: no-creds
                proxy:
                  config-namespace: nocreds
                  configuration:
                    key:
                      value: "val"
                  auth:
                    - domains:
                        - api.example.com
                      type: basic
                """);
        var result = ToolDefValidator.validate(file);
        assertTrue(result.hasErrors());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("basic auth requires 'username'")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("basic auth requires 'password'")));
    }

    private static Path writeTemp(String content) throws Exception {
        var file = Files.createTempFile("tool-test-", ".yaml");
        file.toFile().deleteOnExit();
        Files.writeString(file, content);
        return file;
    }

    @Test
    void aPlaceholderTheStartCannotFillIsAnError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                proxy:
                  config-namespace: acme
                  placeholders:
                    - env: ACME_TOKEN
                      prefix: "acme'$(id)"
                    - env: "ACME TOKEN"
                  configuration:
                    token:
                      config-path: token
                      description: Acme token
                      secret: true
                  auth:
                    - domains: [api.acme.example]
                      type: bearer
                      token: "${token}"
                """);
        var errors = ToolDefValidator.validate(file).errors();
        assertTrue(errors.stream().anyMatch(e -> e.contains("prefix of ACME_TOKEN")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("'ACME TOKEN' is not a variable name")), errors.toString());
    }

    @Test
    void aPlaceholderTheToolDoesNotExportIsAnError(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                env:
                  - name: ACME_TOKEN
                    value: acme_placeholder
                proxy:
                  config-namespace: acme
                  placeholders:
                    - env: ACME_TOKN
                      prefix: acme_
                  configuration:
                    token:
                      config-path: token
                      description: Acme token
                      secret: true
                  auth:
                    - domains: [api.acme.example]
                      type: bearer
                      token: "${token}"
                """);
        var errors = ToolDefValidator.validate(file).errors();
        assertTrue(errors.stream().anyMatch(e -> e.contains("ACME_TOKN") && e.contains("env:")), errors.toString());
    }

    @Test
    void aPlaceholderWhoseStaticValueIsNotOneIsAnError(@TempDir Path dir) throws Exception {
        // A start replaces only a value starting with the prefix and "placeholder": any other
        // static value would never get a proof
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                env:
                  - name: ACME_TOKEN
                    value: acme_dummy
                proxy:
                  config-namespace: acme
                  placeholders:
                    - env: ACME_TOKEN
                      prefix: acme_
                  configuration:
                    token:
                      config-path: token
                      description: Acme token
                      secret: true
                  auth:
                    - domains: [api.acme.example]
                      type: bearer
                      token: "${token}"
                """);
        var errors = ToolDefValidator.validate(file).errors();
        assertTrue(errors.stream().anyMatch(e -> e.contains("acme_placeholder")), errors.toString());
    }

    /** A bare key ('flavour:' with nothing under it) has no definition: an error, never an NPE (#1475). */
    @Test void aBareKeyIsAnErrorNamingIt(@TempDir Path dir) throws Exception {
        for (var entry : Map.of(
                "name: a\nparameters:\n  flavour:\n", "parameter 'flavour' in 'a' has no definition",
                "name: b\nproxy:\n  configuration:\n    key:\n", "configuration 'key' in proxy for 'b' has no definition")
                .entrySet()) {
            var file = dir.resolve("bare.yaml");
            Files.writeString(file, entry.getKey());
            var result = ToolDefValidator.validate(file);
            assertEquals(List.of(entry.getValue()), result.errors());
            try (var is = Files.newInputStream(file)) {
                assertEquals(entry.getValue(), ToolDefValidator.refusal(ToolDef.loadFromStream(is)));
            }
        }
    }

    /** A bare key does not hide the file's other refusals; the loader names the bare key first. */
    @Test void aBareKeyIsReportedWithTheOtherRefusals(@TempDir Path dir) throws Exception {
        var file = dir.resolve("bare.yaml");
        Files.writeString(file, """
                name: c
                parameters:
                  flavour:
                proxy:
                  config-namespace: 'bad ns'
                  auth:
                    - domains: [c.example]
                      paths: [""]
                      type: bearer
                      token: x
                """);
        var errors = ToolDefValidator.validate(file).errors();
        assertEquals(3, errors.size(), errors.toString());
        assertEquals("parameter 'flavour' in 'c' has no definition", errors.get(0));
        assertTrue(errors.get(1).contains("config-namespace 'bad ns'"), errors.toString());
        assertTrue(errors.get(2).contains("path '' is empty"), errors.toString());
        try (var is = Files.newInputStream(file)) {
            assertEquals(errors.get(0), ToolDefValidator.refusal(ToolDef.loadFromStream(is)));
        }
    }
}
