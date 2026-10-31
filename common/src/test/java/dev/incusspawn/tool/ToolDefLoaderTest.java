package dev.incusspawn.tool;

import dev.incusspawn.util.Output;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.config.SpawnConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolDefLoaderTest {

    @Test
    void findsBuiltinPodman() {
        var loader = new ToolDefLoader();
        var tool = loader.find("podman");
        assertNotNull(tool, "podman should be found as a built-in YAML tool");
        assertEquals("podman", tool.name());
    }

    @Test
    void onlyChecksThatNeedRootVerifyAsRoot() {
        // A verify runs as the image's user unless it says otherwise: run as root, zmx left a
        // root-owned ~/.zmx/logs behind and every later zmx run failed with "error: AccessDenied".
        var loader = new ToolDefLoader();
        assertTrue(loader.find("sshd").verifyAsRoot(), "sshd -t reads root-only host keys");
        assertFalse(loader.find("zmx").verifyAsRoot());
        assertFalse(loader.find("maven-3").verifyAsRoot());
    }

    @Test
    void findsBuiltinMaven() {
        var loader = new ToolDefLoader();
        var tool = loader.find("maven-3");
        assertNotNull(tool, "maven-3 should be found as a built-in YAML tool");
        assertEquals("maven-3", tool.name());
    }

    @Test
    void findsBuiltinMvnd() {
        var loader = new ToolDefLoader();
        var tool = loader.find("mvnd");
        assertNotNull(tool, "mvnd should be found as a built-in YAML tool");
        assertEquals("mvnd", tool.name());
    }

    @Test
    void findsBuiltinSshd() {
        var loader = new ToolDefLoader();
        var tool = loader.find("sshd");
        assertNotNull(tool, "sshd should be found as a built-in YAML tool");
        assertEquals("sshd", tool.name());
    }

    @Test
    void findsBuiltinIdeaBackend() {
        var loader = new ToolDefLoader();
        var tool = loader.find("idea-backend");
        assertNotNull(tool, "idea-backend should be found as a built-in YAML tool");
        assertEquals("idea-backend", tool.name());
    }

    @Test
    void ideaBackendRequiresSshd() {
        var loader = new ToolDefLoader();
        var tool = loader.find("idea-backend");
        assertNotNull(tool);
        assertTrue(tool.requires().contains("sshd"),
                "idea-backend should declare sshd as a dependency");
    }

    @Test
    void transitiveDependencies(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("tool-a.yaml"), """
                name: tool-a
                requires:
                  - tool-b
                run:
                  - echo a
                """);
        Files.writeString(tempDir.resolve("tool-b.yaml"), """
                name: tool-b
                requires:
                  - tool-c
                run:
                  - echo b
                """);
        Files.writeString(tempDir.resolve("tool-c.yaml"), """
                name: tool-c
                run:
                  - echo c
                """);

        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);

        var a = loader.find("tool-a");
        assertNotNull(a);
        assertEquals(java.util.List.of("tool-b"), a.requires());

        var b = loader.find("tool-b");
        assertNotNull(b);
        assertEquals(java.util.List.of("tool-c"), b.requires());

        var c = loader.find("tool-c");
        assertNotNull(c);
        assertTrue(c.requires().isEmpty());
    }

    @Test
    void unknownToolReturnsNull() {
        var loader = new ToolDefLoader();
        assertNull(loader.find("nonexistent-tool"));
    }

    @Test
    void aProjectToolAlsoReachedThroughASymlinkedSearchPathStaysProjectLocal(@TempDir Path tempDir) throws Exception {
        // The project's .incus-spawn is also a search path, spelled through a symlink: the tool is
        // the project's whichever spelling is scanned first, so its proxy: stays refused
        var project = Files.createDirectories(tempDir.resolve("proj/.incus-spawn/tools"));
        Files.writeString(project.resolve("evil.yaml"), """
                name: evil
                run:
                  - echo evil
                """);
        var link = Files.createSymbolicLink(tempDir.resolve("link"), project.getParent());

        var loader = new ToolDefLoader();
        loader.setSearchPaths(java.util.List.of(link.toString()));
        loader.setProjectToolsDir(project);

        assertEquals(java.util.Set.of("evil"), loader.projectLocalToolNames());
        assertEquals(project.resolve("evil.yaml").toString(), loader.getSource("evil"));
    }

    @Test
    void userDefinedToolOverridesBuiltin(@TempDir Path tempDir) throws Exception {
        // Create a user-defined podman.yaml that overrides the built-in
        var userYaml = """
                name: podman
                description: Custom podman override
                run:
                  - echo custom-podman
                """;
        Files.writeString(tempDir.resolve("podman.yaml"), userYaml);

        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);

        var tool = loader.find("podman");
        assertNotNull(tool);
        // The user tool should have taken priority — verify via the adapter
        // by checking it's a YamlToolSetup (which it always is from the loader)
        assertEquals("podman", tool.name());
    }

    @Test
    void userDefinedCustomTool(@TempDir Path tempDir) throws Exception {
        var userYaml = """
                name: my-custom-tool
                description: A project-specific tool
                run:
                  - echo installed
                """;
        Files.writeString(tempDir.resolve("my-custom-tool.yaml"), userYaml);

        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);

        var tool = loader.find("my-custom-tool");
        assertNotNull(tool, "user-defined custom tool should be discovered");
        assertEquals("my-custom-tool", tool.name());
    }

    @Test
    void nonexistentUserDirIsIgnored(@TempDir Path tempDir) {
        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir.resolve("does-not-exist"));
        // Should still find builtins without error
        assertNotNull(loader.find("podman"));
    }

    @Test
    void userAndSearchPathToolDirsAreEveryLayerButTheProjectInLoadOrder(@TempDir Path tempDir) {
        // HelpContext reads these instead of keeping its own copy of the layers (#1173).
        assertEquals(List.of(SpawnConfig.configDir().resolve("tools"), tempDir.resolve("tools")),
                ToolDefLoader.userAndSearchPathToolDirs(List.of(tempDir.toString())));
    }

    @Test
    void searchPathLoadsTool(@TempDir Path tempDir) throws Exception {
        var toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("gradle.yaml"), """
                name: gradle
                description: Gradle build tool
                run:
                  - echo installing gradle
                verify: gradle --version
                """);

        var loader = new ToolDefLoader();
        loader.setSearchPaths(java.util.List.of(tempDir.toString()));

        var tool = loader.find("gradle");
        assertNotNull(tool, "gradle should be found from search path");
        assertEquals("gradle", tool.name());
    }

    @Test
    void searchPathOverridesBuiltin(@TempDir Path tempDir) throws Exception {
        var toolsDir = tempDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("podman.yaml"), """
                name: podman
                description: Custom podman from search path
                run:
                  - echo custom-search-path-podman
                """);

        var loader = new ToolDefLoader();
        loader.setSearchPaths(java.util.List.of(tempDir.toString()));

        var tool = loader.find("podman");
        assertNotNull(tool);
        assertEquals("podman", tool.name());
    }

    @Test
    void projectLocalOverridesSearchPath(@TempDir Path tempDir) throws Exception {
        var searchDir = tempDir.resolve("search");
        var projectDir = tempDir.resolve("project");
        Files.createDirectories(searchDir.resolve("tools"));
        Files.createDirectories(projectDir);

        Files.writeString(searchDir.resolve("tools/my-tool.yaml"), """
                name: my-tool
                description: From search path
                run:
                  - echo search
                """);
        Files.writeString(projectDir.resolve("my-tool.yaml"), """
                name: my-tool
                description: From project
                run:
                  - echo project
                """);

        var loader = new ToolDefLoader();
        loader.setSearchPaths(java.util.List.of(searchDir.toString()));
        loader.setProjectToolsDir(projectDir);

        var tool = loader.find("my-tool");
        assertNotNull(tool, "my-tool should be found");
        // Project-local should win over search path
        assertEquals("my-tool", tool.name());
    }

    @Test
    void nonexistentSearchPathIsIgnored(@TempDir Path tempDir) {
        var loader = new ToolDefLoader();
        loader.setSearchPaths(java.util.List.of("/nonexistent/path"));
        // Should still find builtins without error
        assertNotNull(loader.find("podman"));
    }

    @Test
    void addFallbacksDoesNotOverrideExisting() {
        var loader = new ToolDefLoader();
        assertNotNull(loader.find("podman"));

        var fallbackDef = new ToolDef();
        fallbackDef.setName("podman");
        fallbackDef.setDescription("should not replace built-in");

        loader.addFallbacks(Map.of("podman", fallbackDef));

        var tool = loader.find("podman");
        assertNotNull(tool);
        assertNotEquals("should not replace built-in",
                ((YamlToolSetup) tool).toolDef().getDescription());
    }

    /** A template built before #1266 may have stored a tool the loader now refuses. */
    @Test
    void addFallbacksSkipsAnInvalidNamespace() {
        var proxy = new ToolDef.ProxyDef();
        proxy.setConfigNamespace("evil\u001b]0;x\u0007");
        var fallbackDef = new ToolDef();
        fallbackDef.setName("evil-fallback");
        fallbackDef.setProxy(proxy);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.find("podman");
            loader.addFallbacks(Map.of("evil-fallback", fallbackDef));

            assertNull(loader.find("evil-fallback"));
            assertTrue(reported.stream().anyMatch(w -> w.contains("config-namespace")), reported.toString());
        }
    }

    @Test
    void addFallbacksAddsNewTool() {
        var loader = new ToolDefLoader();
        assertNull(loader.find("my-fallback"));

        var fallbackDef = new ToolDef();
        fallbackDef.setName("my-fallback");
        fallbackDef.setDescription("A fallback tool");

        loader.addFallbacks(Map.of("my-fallback", fallbackDef));

        var tool = loader.find("my-fallback");
        assertNotNull(tool);
        assertEquals("my-fallback", tool.name());
    }

    @Test
    void addFallbacksHandlesNull() {
        var loader = new ToolDefLoader();
        assertDoesNotThrow(() -> loader.addFallbacks(null));
        assertNotNull(loader.find("podman"));
    }

    @Test
    void sameDirectoryDuplicateNameIsConflict(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("gradle.yaml"), """
                name: gradle
                description: Current
                run:
                  - echo a
                """);
        Files.writeString(tempDir.resolve("gradle-old.yaml"), """
                name: gradle
                description: Stale copy
                run:
                  - echo b
                """);

        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);

        var conflicts = loader.conflicts();
        assertEquals(1, conflicts.size(), "same-directory duplicate should be one conflict");
        assertEquals("gradle", conflicts.get(0).name());
        assertEquals(2, conflicts.get(0).files().size());
        assertTrue(loader.overrides().stream().noneMatch(o -> o.name().equals("gradle")));
    }

    @Test
    void unparsableFileIsRecordedAsParseFailure(@TempDir Path tempDir) throws Exception {
        var broken = tempDir.resolve("podman.yaml");
        Files.writeString(broken, """
                name: podman
                description:
                  - not a string
                """);

        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);

        assertEquals(List.of(broken.toAbsolutePath().normalize()), loader.parseFailures());
        assertEquals("built-in", loader.getSource("podman"));
    }

    /** A bare key under parameters: or proxy.configuration: is refused like an invalid namespace (#1475). */
    @Test
    void aToolWithABareKeyIsRefusedAtLoad(@TempDir Path tempDir) throws Exception {
        var bare = tempDir.resolve("empty-key.yaml");
        Files.writeString(bare, """
                name: empty-key
                parameters:
                  flavour:
                proxy:
                  configuration:
                    token:
                """);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);

            assertNull(loader.find("empty-key"));
            assertEquals(List.of(bare.toAbsolutePath().normalize()), loader.parseFailures());
            assertEquals(1, reported.size(), reported.toString());
            assertTrue(reported.get(0).startsWith("empty-key.yaml: parameter 'flavour' in 'empty-key' has no definition"),
                    reported.get(0));
        }
    }

    /**
     * A tool whose config-namespace is not an identifier is refused where definitions load
     * (#1266), so its namespace never reaches isx init's prompts, isx account or config.yaml.
     * Like an unparsable file it may have meant to replace a lower layer's, so isx build refuses.
     */
    @Test
    void aToolWithAnInvalidNamespaceIsRefusedAtLoad(@TempDir Path tempDir) throws Exception {
        var evil = tempDir.resolve("evil.yaml");
        Files.writeString(evil, """
                name: evil
                proxy:
                  config-namespace: "\\e]0;x\\a"
                  configuration:
                    token:
                      config-path: token
                      secret: true
                      description: Token
                """);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);

            assertNull(loader.find("evil"));
            assertEquals(List.of(evil.toAbsolutePath().normalize()), loader.parseFailures());
            assertEquals(1, reported.size(), reported.toString());
            assertTrue(reported.get(0).contains("config-namespace"), reported.get(0));
            assertTrue(reported.get(0).chars().noneMatch(Character::isISOControl), "printed raw: " + reported.get(0));
        }
    }

    /**
     * A tool whose name is not an identifier is refused at load like an invalid namespace, so
     * no listing prints it: bash completion ran {@code isx tools list}'s names through compgen
     * -W, which runs a command substitution in one (#1778).
     */
    @Test
    void aToolWhoseNameIsNotAnIdentifierIsRefusedAtLoad(@TempDir Path tempDir) throws Exception {
        var evil = tempDir.resolve("evil.yaml");
        Files.writeString(evil, """
                name: "$(touch pwned)\\e]0;x\\a"
                description: x
                """);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);

            assertTrue(loader.allToolSetups().keySet().stream().noneMatch(n -> n.contains("touch")),
                    loader.allToolSetups().keySet().toString());
            assertEquals(List.of(evil.toAbsolutePath().normalize()), loader.parseFailures());
            assertEquals(1, reported.size(), reported.toString());
            assertTrue(reported.get(0).contains("tool name"), reported.get(0));
            assertTrue(reported.get(0).chars().noneMatch(Character::isISOControl), "printed raw: " + reported.get(0));
        }
    }

    /** A template built before #1778 may have stored a tool by a name the loader now refuses. */
    @Test
    void addFallbacksSkipsANameThatIsNotAnIdentifier() {
        var hostile = new ToolDef();
        hostile.setName("$(touch pwned)");
        var renamed = new ToolDef();
        renamed.setName("fine");
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.addFallbacks(Map.of("$(touch pwned)", hostile, "`id`", renamed));

            assertTrue(loader.allToolSetups().keySet().stream().noneMatch(n -> n.contains("touch") || n.contains("id`")),
                    loader.allToolSetups().keySet().toString());
            assertEquals(2, reported.size(), reported.toString());
        }
    }

    /** A path scope that is not a '/'-rooted literal is refused like an invalid namespace (#1423). */
    @Test
    void aToolWithAnInvalidPathScopeIsRefusedAtLoad(@TempDir Path tempDir) throws Exception {
        var scoped = tempDir.resolve("scoped.yaml");
        Files.writeString(scoped, """
                name: scoped
                proxy:
                  config-namespace: scoped
                  configuration:
                    token:
                      config-path: token
                      secret: true
                      description: Token
                  auth:
                    - domains: [scoped.example]
                      paths: [""]
                      type: bearer
                      token: "${token}"
                """);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);

            assertNull(loader.find("scoped"));
            assertEquals(List.of(scoped.toAbsolutePath().normalize()), loader.parseFailures());
            assertEquals(1, reported.size(), reported.toString());
            assertTrue(reported.get(0).contains("path '' is empty"), reported.get(0));
        }
    }

    /** Unlike other lists, an empty 'paths' item is kept and refused: dropping it would scope the entry to every path. */
    @Test
    void anEmptyPathItemIsRefusedNotDropped(@TempDir Path tempDir) throws Exception {
        var scoped = tempDir.resolve("scoped.yaml");
        Files.writeString(scoped, """
                name: scoped
                proxy:
                  config-namespace: scoped
                  configuration:
                    token:
                      config-path: token
                      secret: true
                      description: Token
                  auth:
                    - domains: [scoped.example]
                      paths:
                        -
                      type: bearer
                      token: "${token}"
                """);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);

            assertNull(loader.find("scoped"));
            assertEquals(List.of(scoped.toAbsolutePath().normalize()), loader.parseFailures());
            assertEquals(1, reported.size(), reported.toString());
            assertTrue(reported.get(0).contains("path '' is empty"), reported.get(0));
        }
    }

    /** An empty auth item ('auth:' followed by a bare '-') declares nothing: the tool loads without it. */
    @Test
    void aToolWithAnEmptyAuthItemStillLoads(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("empty-auth.yaml"), """
                name: empty-auth
                proxy:
                  config-namespace: emptyauth
                  auth:
                    -
                """);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);

            assertEquals(List.of(), loader.find("empty-auth").proxy().getAuth());
            assertEquals(List.of(), loader.parseFailures());
            assertEquals(List.of(), reported);
        }
    }

    /** A user tool reaches no other check, so the loader warns that this step fails the build (#1520). */
    @Test
    void aToolLingeringThroughLoginctlIsWarned(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("lingers.yaml"), """
                name: lingers
                run:
                  - loginctl enable-linger agentuser
                """);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);
            assertNotNull(loader.find("lingers"), "a warning, not a refusal");

            assertEquals(1, loader.warnings().size(), loader.warnings().toString());
            assertTrue(loader.warnings().get(0).contains("/var/lib/systemd/linger/"), loader.warnings().get(0));
            assertEquals(loader.warnings(), reported);
        }
    }

    /**
     * The token a tool presents to the proxy changes on every start (#1108): a tool that copies
     * its placeholder into a file at build time presents a stale one after the next start.
     */
    @Test
    void aToolBakingItsTokenIntoAFileIsWarned(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("mytool.yaml"), """
                name: mytool
                env:
                  - name: MYTOOL_TOKEN
                    value: mytool-placeholder
                  - name: MYTOOL_COLOR
                    value: "1"
                files:
                  - path: ~/.mytool/config
                    content: |
                      token = mytool-placeholder
                      color = 1
                proxy:
                  config-namespace: mytool
                  configuration:
                    token:
                      config-path: token
                      secret: true
                      description: MyTool token
                  auth:
                    - domains: [api.mytool.example]
                      type: bearer
                      token: "${token}"
                """);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);
            assertNotNull(loader.find("mytool"), "a warning, not a refusal");

            assertEquals(1, loader.warnings().size(), loader.warnings().toString());
            var warning = loader.warnings().get(0);
            assertTrue(warning.contains("'~/.mytool/config'") && warning.contains("$MYTOOL_TOKEN"), warning);
            assertEquals(List.of(warning), reported);
        }
    }

    /**
     * A tool that declares its placeholders (#1106) is checked on exactly those: no guessing
     * from a variable's name, which would miss one named like a setting and flag a copied
     * setting named like a credential.
     */
    @Test
    void aToolsDeclaredPlaceholdersAreWhatCounts(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("mytool.yaml"), """
                name: mytool
                env:
                  - name: MYTOOL_ID
                    value: mytool_placeholder
                  - name: MYTOOL_SECRET_SALT
                    value: notatoken42
                files:
                  - path: ~/.mytool/config
                    content: |
                      id = mytool_placeholder
                      salt = notatoken42
                proxy:
                  config-namespace: mytool
                  placeholders:
                    - env: MYTOOL_ID
                      prefix: mytool_
                  configuration:
                    token:
                      config-path: token
                      secret: true
                      description: MyTool token
                  auth:
                    - domains: [api.mytool.example]
                      type: bearer
                      token: "${token}"
                """);
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(msg -> {}))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);
            loader.find("mytool");
            var warnings = loader.warnings();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("$MYTOOL_ID"), warnings.get(0));
        }
    }

    @Test
    void onlyACredentialShapedVariableCountsAsAToken(@TempDir Path tempDir) throws Exception {
        // Paths, endpoints and model names are reused in files all the time, and are no token
        Files.writeString(tempDir.resolve("mytool.yaml"), """
                name: mytool
                env:
                  - name: MYTOOL_HOME
                    value: /opt/mytool
                  - name: MYTOOL_ENDPOINT
                    value: https://api.mytool.example
                  - name: MYTOOL_MODEL
                    value: mytool-large-2
                  - name: MYTOOL_API_KEY
                    value: https://not-a-key.example/x
                  - name: MYTOOL_KEY_FILE
                    value: ~/.mytool/key.pem
                files:
                  - path: ~/.mytool/config
                    content: |
                      home = /opt/mytool
                      endpoint = https://api.mytool.example
                      model = mytool-large-2
                      key_url = https://not-a-key.example/x
                      key_file = ~/.mytool/key.pem
                run:
                  - ln -s /opt/mytool/bin/mytool /usr/local/bin/mytool
                proxy:
                  config-namespace: mytool
                  configuration:
                    token:
                      config-path: token
                      secret: true
                      description: MyTool token
                  auth:
                    - domains: [api.mytool.example]
                      type: bearer
                      token: "${token}"
                """);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);
            assertNotNull(loader.find("mytool"));
            assertEquals(List.of(), loader.warnings());
            assertEquals(List.of(), reported);
        }
    }

    @Test
    void aBase64OrUserColonTokenStillCounts(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("mytool.yaml"), """
                name: mytool
                env:
                  - name: MYTOOL_TOKEN
                    value: mytool/placeholder==
                  - name: MYTOOL_AUTH
                    value: user:placeholder
                files:
                  - path: ~/.mytool/config
                    content: |
                      token = mytool/placeholder==
                      auth = user:placeholder
                proxy:
                  config-namespace: mytool
                  configuration:
                    token:
                      config-path: token
                      secret: true
                      description: MyTool token
                  auth:
                    - domains: [api.mytool.example]
                      type: bearer
                      token: "${token}"
                """);
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(w -> {}))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);
            assertEquals(2, loader.warnings().size(), loader.warnings().toString());
        }
    }

    @Test
    void anEmptyKeyInAProxyToolStillLoads(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("mytool.yaml"), """
                name: mytool
                env:
                  - name: MYTOOL_TOKEN
                    value: mytool-placeholder
                run:
                run_as_user:
                  -
                files:
                proxy:
                  config-namespace: mytool
                  configuration:
                    token:
                      config-path: token
                      secret: true
                      description: MyTool token
                  auth:
                    - domains: [api.mytool.example]
                      type: bearer
                      token: "${token}"
                """);
        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);
        assertNotNull(loader.find("mytool"));
    }

    @Test
    void aToolReadingItsTokenWhenItRunsIsNotWarned(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("mytool.yaml"), """
                name: mytool
                env:
                  - name: MYTOOL_TOKEN
                    value: mytool-placeholder
                files:
                  - path: ~/.mytool/config
                    content: |
                      token_env = MYTOOL_TOKEN
                proxy:
                  config-namespace: mytool
                  configuration:
                    token:
                      config-path: token
                      secret: true
                      description: MyTool token
                  auth:
                    - domains: [api.mytool.example]
                      type: bearer
                      token: "${token}"
                """);
        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);
        assertNotNull(loader.find("mytool"));
        assertEquals(List.of(), loader.warnings());
    }

    /** Loaders are built deep in code the TUI reaches, so they must never print (#872). */
    @Test
    void unreadableFilesAreWarningsNotStderr(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("podman.yaml"), """
                name: podman
                env:
                  - export DOCKER_HOST=unix:///var/run/docker.sock
                """);
        var reported = new ArrayList<String>();
        // That none reaches the terminal is the binding's, pinned by OutputBindingTest
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);

            assertEquals(1, loader.warnings().size());
            var warning = loader.warnings().get(0);
            assertTrue(warning.startsWith("podman.yaml:2: "), warning);
            assertTrue(warning.contains("is a shell string"), warning);

            // A second loader finds the same problem; it is still reported only once.
            var again = new ToolDefLoader();
            again.setProjectToolsDir(tempDir);
            assertEquals(List.of(warning), again.warnings());
            assertEquals(List.of(warning), reported);
        }
    }

    @Test
    void crossLayerOverrideIsNotConflict(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("podman.yaml"), """
                name: podman
                description: Custom podman override
                run:
                  - echo custom
                """);

        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);

        assertTrue(loader.conflicts().isEmpty(), "cross-layer override must not be a conflict");
        assertTrue(loader.overrides().stream().anyMatch(o -> o.name().equals("podman")),
                "override of built-in podman should be recorded");
    }

    @Test
    void searchPathExpandsTilde() {
        var originalHome = System.getProperty("user.home");
        try {
            var testResources = Path.of("src/test/resources").toAbsolutePath();
            System.setProperty("user.home", testResources.toString());

            var loader = new ToolDefLoader();
            loader.setSearchPaths(java.util.List.of("~/searchpaths-test"));

            var tool = loader.find("tilde-tool");
            assertNotNull(tool, "Should load tool from ~/searchpaths-test");
            assertEquals("tilde-tool", tool.name());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void projectLocalToolNamesReturnsProjectLocalTools(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("local-tool.yaml"), """
                name: local-tool
                description: A project-local tool
                run:
                  - echo local
                """);

        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);

        var projectLocal = loader.projectLocalToolNames();
        assertTrue(projectLocal.contains("local-tool"),
                "local-tool should be project-local");
        assertFalse(projectLocal.contains("podman"),
                "podman is built-in, not project-local");
    }

    @Test
    void projectLocalToolNamesExcludesSearchPathTools(@TempDir Path tempDir) throws Exception {
        var searchDir = tempDir.resolve("search");
        var projectDir = tempDir.resolve("project");
        Files.createDirectories(searchDir.resolve("tools"));
        Files.createDirectories(projectDir);

        Files.writeString(searchDir.resolve("tools/search-tool.yaml"), """
                name: search-tool
                description: From search path
                run:
                  - echo search
                """);

        var loader = new ToolDefLoader();
        loader.setSearchPaths(java.util.List.of(searchDir.toString()));
        loader.setProjectToolsDir(projectDir);

        var projectLocal = loader.projectLocalToolNames();
        assertFalse(projectLocal.contains("search-tool"),
                "search-tool is from search path, not project-local");
    }

    @Test
    void reloadPicksUpOnDiskEdits(@TempDir Path tempDir) throws Exception {
        var toolFile = tempDir.resolve("edit-me.yaml");
        Files.writeString(toolFile, """
                name: edit-me
                packages:
                  - old-package
                """);
        var loader = new ToolDefLoader();
        loader.setProjectToolsDir(tempDir);

        // First access caches the definition (and memoizes its fingerprint).
        var fpBefore = ((YamlToolSetup) loader.find("edit-me")).toolDef().contentFingerprint();

        // Edit the file on disk in a way that changes the fingerprint.
        Files.writeString(toolFile, """
                name: edit-me
                packages:
                  - new-package
                """);

        // After reload the on-disk edit is visible in the recomputed fingerprint.
        loader.reload();
        var fpAfter = ((YamlToolSetup) loader.find("edit-me")).toolDef().contentFingerprint();
        assertNotEquals(fpBefore, fpAfter,
                "reload() should surface the edited tool content in the fingerprint");
    }

    /** #1546: a misspelled key anywhere in a tool was dropped without a word. */
    @Test
    void everyKeyIsxIgnoresInAToolIsWarnedAboutWithWhereItIs(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("typos.yaml"), """
                name: typos
                require: [maven-3]
                downloads:
                  - url: https://example.com/t.tar.gz
                    sha265: abc
                files:
                  - path: ~/.t
                    contents: x
                env:
                  - name: T
                    value: x
                    seperator: ":"
                skills:
                  repo: a/skills
                  lists: [x]
                actions:
                  - label: Run
                    type: command
                    command: t
                    lable: Run t
                parameters:
                  memory:
                    type: string
                    defualt: 4g
                proxy:
                  config-namespace: typos
                  configuration:
                    token:
                      config-path: token
                      secert: true
                  placeholders:
                    - env: T_TOKEN
                      prefx: t-
                  auth:
                    - domain: [api.example.com]
                      domains: [api.example.com]
                      type: bearer
                      token: "${token}"
                """);
        var reported = new ArrayList<String>();
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(reported::add))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);
            assertNotNull(loader.find("typos"), "a warning, not a refusal");

            assertEquals(List.of(
                    "typos.yaml: unknown key 'require', ignored; did you mean 'requires'?",
                    "typos.yaml: unknown key 'downloads[0].sha265', ignored; did you mean 'sha256'?",
                    "typos.yaml: unknown key 'files[0].contents', ignored; did you mean 'content'?",
                    "typos.yaml: unknown key 'env[0].seperator', ignored; did you mean 'separator'?",
                    "typos.yaml: unknown key 'actions[0].lable', ignored; did you mean 'label'?",
                    "typos.yaml: unknown key 'parameters.memory.defualt', ignored; did you mean 'default'?",
                    "typos.yaml: unknown key 'skills.lists', ignored; did you mean 'list'?",
                    "typos.yaml: unknown key 'proxy.configuration.token.secert', ignored; did you mean 'secret'?",
                    "typos.yaml: unknown key 'proxy.auth[0].domain', ignored; did you mean 'domains'?",
                    "typos.yaml: unknown key 'proxy.placeholders[0].prefx', ignored; did you mean 'prefix'?"),
                    loader.warnings());
            assertEquals(loader.warnings(), reported);
        }
    }

    @Test
    void aToolWithAMisspelledNameIsNotSkippedWithoutAWord(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("nameless.yaml"), "nmae: nameless\npackages: [git]\n");
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(w -> {}))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);

            assertEquals(List.of("nameless.yaml: unknown key 'nmae', ignored; did you mean 'name'?",
                    "nameless.yaml: no 'name:'; it is skipped, and isx build refuses until it is fixed"),
                    loader.warnings());
        }
    }

    @Test
    void aToolFileWithoutANameIsNotSkippedWithoutAWord(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("nameless.yaml"), "packages: [git]\n");
        Files.writeString(tempDir.resolve("empty.yaml"), "~\n");
        Files.writeString(tempDir.resolve("blank.yaml"), "name: ''\npackages: [git]\n");
        try (var ignored = BuildOutput.bind(Output.diagnosticsTo(w -> {}))) {
            var loader = new ToolDefLoader();
            loader.setProjectToolsDir(tempDir);

            var refused = "; it is skipped, and isx build refuses until it is fixed";
            assertEquals(List.of("blank.yaml: a blank 'name:'" + refused, "empty.yaml: no 'name:'" + refused,
                    "nameless.yaml: no 'name:'" + refused), loader.warnings());
            assertEquals(3, loader.parseFailures().size(), loader.parseFailures().toString());
        }
    }

    /** A key named after the fingerprint cache is reported like any other, never dropped (#1889). */
    @Test
    void aKeyNamedAfterTheFingerprintCacheIsReported() throws Exception {
        var def = dev.incusspawn.config.Jjq.toolDef("name: t\ncachedFingerprint: x\n");
        assertEquals(List.of("unknown key 'cachedFingerprint', ignored"), def.unknownKeyWarnings());
    }

    @Test
    void theBuiltInToolsHoldNoKeyIsxIgnores() throws Exception {
        for (var file : ToolDefLoader.BUILTIN_TOOLS) {
            try (var is = getClass().getClassLoader().getResourceAsStream("tools/" + file)) {
                assertEquals(List.of(), ToolDef.loadFromStream(is).unknownKeyWarnings(), file);
            }
        }
    }
}
