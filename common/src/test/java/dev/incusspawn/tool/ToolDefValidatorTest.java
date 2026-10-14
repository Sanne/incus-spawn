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
    void downloadWithoutUrlWarns(@TempDir Path dir) throws Exception {
        var file = dir.resolve("test.yaml");
        Files.writeString(file, """
                name: my-tool
                downloads:
                  - sha256: abc123
                    extract: /opt
                """);
        var result = ToolDefValidator.validate(file);
        assertFalse(result.hasErrors());
        assertTrue(result.hasWarnings());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("url")));
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
        ToolDefValidator.validateDef(proxyTool("", "gh.\u001b[2Jx"), new ArrayList<>(), warnings);
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
}
