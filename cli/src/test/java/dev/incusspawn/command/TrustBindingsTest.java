package dev.incusspawn.command;

import dev.incusspawn.config.EnvEntry;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** #713: runtimes that carry their own root bundle trust the system store, and so the isx CA. */
class TrustBindingsTest {

    /** What {@code update-ca-trust} extracts; spelled out so the tests pin the paths themselves. */
    private static final String PEM = "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem";
    private static final String HASH_DIR = "/etc/pki/ca-trust/extracted/pem/directory-hash";
    private static final String JAVA_STORE = "/etc/pki/ca-trust/extracted/java/cacerts";

    private static final IncusClient.ExecResult OK = new IncusClient.ExecResult(0, "", "");

    private static Container recordingContainer(IncusClient incus) {
        when(incus.shellExec(anyString(), any(String[].class))).thenReturn(OK);
        return new Container(incus, "test");
    }

    private static List<String> writtenScripts(IncusClient incus) {
        var captor = ArgumentCaptor.forClass(String[].class);
        verify(incus, atLeast(0)).shellExec(eq("test"), captor.capture());
        return captor.getAllValues().stream().map(a -> String.join(" ", a)).toList();
    }

    @Test
    void envFileExportsTheTrustVariablesOnlyWhenUnset() {
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);
        var imageDef = new ImageDef();
        imageDef.setName("tpl-x");

        GuestProvisioning.writeEnvFile(container, imageDef, Map.of("tpl-x", imageDef), List.of(), "tpl-x");

        var all = String.join("\n", writtenScripts(incus));
        assertTrue(all.contains("export SSL_CERT_FILE=\"${SSL_CERT_FILE:-" + PEM + "}\""), all);
        assertTrue(all.contains("export REQUESTS_CA_BUNDLE=\"${REQUESTS_CA_BUNDLE:-"), all);
        assertTrue(all.contains("export NODE_EXTRA_CA_CERTS=\"${NODE_EXTRA_CA_CERTS:-"), all);
        assertTrue(all.contains("export DENO_TLS_CA_STORE=\"${DENO_TLS_CA_STORE:-system}\""), all);
    }

    @Test
    void aTemplatesOwnSetIfUnsetValueBeatsTheDefault() {
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);
        var imageDef = new ImageDef();
        imageDef.setName("tpl-x");
        imageDef.setEnv(List.of(EnvEntry.setIfUnset("SSL_CERT_FILE", "/corp/ca.pem")));

        GuestProvisioning.writeEnvFile(container, imageDef, Map.of("tpl-x", imageDef), List.of(), "tpl-x");

        var all = String.join("\n", writtenScripts(incus));
        assertTrue(all.contains("${SSL_CERT_FILE:-/corp/ca.pem}"), all);
        assertFalse(all.contains("${SSL_CERT_FILE:-/etc/pki"), all);
    }

    @Test
    void theEnvironmentDropInSortsFirstSoAnyOtherFileOverridesIt() {
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);

        GuestProvisioning.writeTrustEnvironmentD(container);

        var all = String.join("\n", writtenScripts(incus));
        // First in filename order, so a user's own file wins; plain values, because the
        // ${NAME:-value} form broke rootless podman's registry pulls in CI.
        assertTrue(all.contains("cat > '/etc/environment.d/00-isx-trust.conf'"), all);
        assertTrue(all.contains("\nSSL_CERT_FILE=" + PEM + "\n"), all);
        assertFalse(all.contains("${"), all);
    }

    @Test
    void systemServicesGetTheVariablesFromTheSystemManager() {
        // #2748: a system service reads neither isx-env.sh nor environment.d, so Node.js in a
        // GitHub Actions runner installed as a service rejected the proxy's certificates.
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);

        GuestProvisioning.writeTrustEnvironmentD(container);

        var all = String.join("\n", writtenScripts(incus));
        assertTrue(all.contains("cat > '/etc/systemd/system.conf.d/00-isx-trust.conf'"), all);
        var line = all.lines().filter(l -> l.startsWith("DefaultEnvironment=")).findFirst().orElseThrow();
        var assignments = List.of(line.substring("DefaultEnvironment=".length()).split(" "));
        GuestProvisioning.TRUST_ENV.forEach((name, value) ->
                assertTrue(assignments.contains(name + "=" + value), name + " in " + line));
        assertEquals(GuestProvisioning.TRUST_ENV.size(), assignments.size(), line);
        assertTrue(all.contains("[Manager]\nDefaultEnvironment="), all);
    }

    @Test
    void theTrustVariablesNameTheStoreUpdateCaTrustExtracts() {
        // ca-certificates 2026.2.90 (Fedora 44) no longer ships /etc/pki/tls/certs/ca-bundle.crt;
        // curl then fails with "(77) error adding trust anchors" in every login shell (#713).
        GuestProvisioning.TRUST_ENV.forEach((name, value) -> {
            if (value.startsWith("/") && !name.equals("SSL_CERT_DIR")) assertEquals(PEM, value, name);
        });
        assertEquals(HASH_DIR, GuestProvisioning.TRUST_ENV.get("SSL_CERT_DIR"));
    }

    @Test
    void theHelperBindsCertifiToTheSameBundleAsTheVariables(@TempDir Path tmp) throws Exception {
        var script = Files.readString(helper(tmp));
        assertTrue(script.contains("bundle=${ISX_TRUST_PEM_BUNDLE:-" + PEM + "}"), script);
    }

    @Test
    void theHelperBindsJdksToTheJavaStoreUpdateCaTrustExtracts(@TempDir Path tmp) throws Exception {
        // /etc/pki/java/cacerts is a ca-certificates compat link, as /etc/pki/tls/certs/ca-bundle.crt
        // was until 2026.2.90 dropped it (#713); the extracted file is what update-ca-trust writes.
        var script = Files.readString(helper(tmp));
        assertTrue(script.contains("store=${ISX_TRUST_JAVA_STORE:-" + JAVA_STORE + "}"), script);
    }

    @ParameterizedTest
    @ValueSource(strings = {PEM, JAVA_STORE})
    void buildFailsBeforeWritingTheVariablesWhenTheBaseImageLacksAStore(String store) {
        var incus = mock(IncusClient.class);
        when(incus.shellExec(anyString(), any(String[].class))).thenAnswer(inv ->
                List.of(inv.getArguments()).contains(store) ? new IncusClient.ExecResult(1, "", "") : OK);
        var container = new Container(incus, "test");
        var imageDef = new ImageDef();
        imageDef.setName("tpl-x");

        var e = assertThrows(RuntimeException.class, () ->
                GuestProvisioning.writeEnvFile(container, imageDef, Map.of("tpl-x", imageDef), List.of(), "tpl-x"));
        assertTrue(e.getMessage().contains(store), e.getMessage());
        var all = String.join("\n", writtenScripts(incus));
        assertFalse(all.contains("isx-env.sh"), all);
    }

    @Test
    void javaIsNeverBoundThroughAVariable() {
        // JAVA_TOOL_OPTIONS and JDK_JAVA_OPTIONS print "Picked up ..." and break OpenJDK's
        // configure (#464); a Java store is bound by symlink only.
        assertFalse(GuestProvisioning.TRUST_ENV.containsKey("JAVA_TOOL_OPTIONS"));
        assertFalse(GuestProvisioning.TRUST_ENV.containsKey("JDK_JAVA_OPTIONS"));
    }

    @Test
    void buildInstallsTheHelperTheDropInAndRunsTheHelperOnTheImageJdks() {
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);

        GuestProvisioning.bindTrustStores(container);

        var all = String.join("\n", writtenScripts(incus));
        assertTrue(all.contains("cat > '/usr/local/bin/isx-trust'"), all);
        assertTrue(all.contains("cat > '/etc/environment.d/00-isx-trust.conf'"), all);
        assertTrue(all.contains("SSL_CERT_FILE=" + PEM), all);
        assertTrue(all.contains("/usr/local/bin/isx-trust /usr/lib/jvm /opt"), all);
    }

    @Test
    void buildWatchesWhereRuntimesLandWithOnePathUnitPerOwner() {
        // #515: a JDK that lands after the build (sdk install, a Gradle toolchain, a GraalVM
        // unpacked into ~/opt) is bound without anyone running a command.
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);

        GuestProvisioning.bindTrustStores(container);

        var all = String.join("\n", writtenScripts(incus));
        assertTrue(all.contains("cat > '/etc/systemd/system/isx-trust-system.path'"), all);
        assertTrue(all.contains("PathChanged=/usr/lib/jvm\nPathChanged=/opt\nPathChanged=/usr/local\n"), all);
        assertTrue(all.contains("cat > '/etc/systemd/system/isx-trust-home.path'"), all);
        for (var dir : List.of("opt", ".jdks", ".sdkman/candidates/java", ".gradle/jdks",
                ".jbang/cache/jdks", ".local/share/mise/installs/java", ".asdf/installs/java")) {
            assertTrue(all.contains("PathChanged=/home/agentuser/" + dir + "\n"), dir + "\n" + all);
        }
        assertTrue(all.contains("WantedBy=paths.target"), all);
        assertTrue(all.contains("Type=oneshot\nExecStart=/usr/local/bin/isx-trust --settle /usr/lib/jvm /opt /usr/local\n"), all);
        assertTrue(all.contains("Type=oneshot\nUser=agentuser\nExecStart=/usr/local/bin/isx-trust --settle /home/agentuser/opt "), all);
        assertTrue(all.contains("systemctl enable isx-trust-system.path isx-trust-home.path"), all);
    }

    @Test
    void bothBuildPathsBindAfterTheReposAreClonedAndTheirPrimesHaveRun() throws Exception {
        // A prime may download a runtime into a watched directory (./gradlew build provisions a
        // toolchain into ~/.gradle/jdks). Nothing else binds it in a build: a path unit does not
        // fire at boot for content already there. The build has no fake guest to run (see
        // BuildCommandAccountsWiringTest), so this pins the order in the source.
        var src = Files.readString(Path.of("src/main/java/dev/incusspawn/command/BuildCommand.java"));
        for (var method : List.of("void buildFromParent(", "void buildFromScratch(")) {
            int start = src.indexOf(method);
            assertTrue(start >= 0, method);
            int next = src.indexOf("\n    }\n", start);
            var body = src.substring(start, next < 0 ? src.length() : next);
            int clone = body.lastIndexOf("cloneRepos(container");
            int bind = body.indexOf("bindTrustStores(container)");
            assertTrue(clone >= 0 && bind > clone, method + ": bindTrustStores must follow cloneRepos");
        }
    }

    @Test
    void bothBuildPathsBindBeforeTheReposPrimesToo() throws Exception {
        // A layer that adds a vendor JDK (labsjdk, JAVA_HOME=/opt/jdks/...) and primes with it
        // needs the isx CA before the prime runs, and a prime may run isx-trust itself; the bind
        // after cloneRepos covers what a prime downloads. Both are in the source order because
        // the build has no fake guest to run.
        var src = Files.readString(Path.of("src/main/java/dev/incusspawn/command/BuildCommand.java"));
        for (var method : List.of("void buildFromParent(", "void buildFromScratch(")) {
            int start = src.indexOf(method);
            var body = src.substring(start, src.indexOf("\n    }\n", start));
            int early = body.indexOf("bindInstalledRuntimes(container)");
            assertTrue(early >= 0 && early < body.indexOf("cloneRepos(container"), method + ": bind before cloneRepos");
        }
    }

    @Test
    void aPrimeIsRetriedOnTheSymptomsTheAgentContextNames() {
        // #1746: the build's retry and the agent's advice recognise the same failures.
        var context = dev.incusspawn.config.AgentContextGenerator
                .generate("tpl-x", List.of(), List.of(), List.of()).replaceAll("\\s+", " ");
        for (var symptom : GuestProvisioning.UNTRUSTED_CERTIFICATE) {
            assertTrue(context.contains("`" + symptom + "`"), symptom + "\n" + context);
        }
    }

    @Test
    void helperWithoutArgumentsBindsAgentusersDirectoriesWhateverHomeIs(@TempDir Path tmp) throws Exception {
        // `sudo isx-trust` has HOME=/root; the table is agentuser's.
        var script = Files.readString(helper(tmp));
        assertFalse(script.contains("$HOME"), script);
        assertTrue(script.contains("home=/home/agentuser\n"), script);
        assertTrue(script.contains("\"$home/.gradle/jdks\""), script);
    }

    @Test
    void helperDoesNotLinkInsideADirectoryThatSitsWhereTheFileShouldBe(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var elsewhere = Files.createDirectories(tmp.resolve("elsewhere"));
        var security = Files.createDirectories(tmp.resolve("opt/jdk/lib/security"));
        Files.createSymbolicLink(security.resolve("cacerts"), elsewhere);

        run(helper(tmp), store, tmp.resolve("opt"));

        try (var inside = Files.list(elsewhere)) {
            assertEquals(0, inside.count(), "ln -sf would have created elsewhere/system-cacerts");
        }
    }

    @Test
    void theInotifyEstimateCountsOneInstancePerWatchedDirectory() {
        // systemd opens one inotify instance per PathChanged=; doctor's estimate has to follow.
        assertEquals(10 + GuestProvisioning.TRUST_SYSTEM_DIRS.size() + GuestProvisioning.TRUST_HOME_DIRS.size(),
                dev.incusspawn.incus.IncusDiagnostics.INOTIFY_INSTANCES_PER_CONTAINER);
    }

    @Test
    void theLandingDirectoriesNeedNoQuotingInAUnitFile() {
        // They go unquoted into PathChanged= and ExecStart=, where a space splits and % expands.
        for (var dir : GuestProvisioning.TRUST_SYSTEM_DIRS) assertTrue(dir.matches("/[\\w./-]+"), dir);
        for (var dir : GuestProvisioning.TRUST_HOME_DIRS) assertTrue(dir.matches("/[\\w./-]+"), dir);
    }

    @Test
    void buildBindsTheHomeDirectoriesAsTheirOwner() {
        var incus = mock(IncusClient.class);
        var container = recordingContainer(incus);

        GuestProvisioning.bindTrustStores(container);

        verify(incus).execInContainer(eq("test"), eq("agentuser"),
                startsWith("/usr/local/bin/isx-trust '/home/agentuser/opt' '/home/agentuser/.jdks' "));
    }

    // --- the helper itself, run for real against fake JDK trees ---

    private static Path helper(Path dir) throws Exception {
        var script = dir.resolve("isx-trust");
        try (var is = TrustBindingsTest.class.getClassLoader().getResourceAsStream("isx-trust")) {
            assertNotNull(is, "isx-trust resource");
            Files.write(script, is.readAllBytes());
        }
        script.toFile().setExecutable(true);
        return script;
    }

    private static String run(Path script, Path store, Path... dirs) throws Exception {
        var args = new String[dirs.length];
        for (int i = 0; i < dirs.length; i++) args[i] = dirs[i].toString();
        return run(script, Map.of("ISX_TRUST_JAVA_STORE", store.toString()), args);
    }

    private static String run(Path script, Map<String, String> env, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add(script.toString());
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd).redirectErrorStream(true).directory(script.getParent().toFile());
        pb.environment().putAll(env);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    @Test
    void helperLinksABundledCacertsAndLeavesTheRestAlone(@TempDir Path tmp) throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var bundled = Files.createDirectories(tmp.resolve("opt/graalvm/lib/security"));
        var cacerts = Files.writeString(bundled.resolve("cacerts"), "vendor");
        var jsse = Files.writeString(bundled.resolve("jssecacerts"), "keep");
        var unrelated = Files.createDirectories(tmp.resolve("opt/other"));
        var other = Files.writeString(unrelated.resolve("cacerts"), "keep");

        var out = run(helper(tmp), store, tmp.resolve("opt"));

        assertTrue(Files.isSymbolicLink(cacerts), out);
        assertEquals("system", Files.readString(cacerts));
        assertEquals("keep", Files.readString(jsse));
        assertFalse(Files.isSymbolicLink(other));
        assertTrue(out.contains("now trusts the system store"), out);
    }

    @Test
    void helperIsSilentOnASecondRun(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var bundled = Files.createDirectories(tmp.resolve("opt/jdk/lib/security"));
        Files.writeString(bundled.resolve("cacerts"), "vendor");
        var script = helper(tmp);

        assertFalse(run(script, store, tmp.resolve("opt")).isEmpty());
        assertEquals("", run(script, store, tmp.resolve("opt")));
    }

    @Test
    void helperFollowsADirectoryThatIsASymlink(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var real = Files.createDirectories(tmp.resolve("graalvm-25/lib/security"));
        var cacerts = Files.writeString(real.resolve("cacerts"), "vendor");
        var link = Files.createSymbolicLink(tmp.resolve("graalvm"), tmp.resolve("graalvm-25"));

        run(helper(tmp), store, link);

        assertTrue(Files.isSymbolicLink(cacerts));
        assertEquals("system", Files.readString(cacerts));
    }

    @Test
    void helperLinksCertifisBundleToTheSystemPemBundle(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var pem = Files.writeString(tmp.resolve("ca-bundle.crt"), "system pem");
        var certifi = Files.createDirectories(tmp.resolve("venvs/tool/lib/python3.13/site-packages/certifi"));
        var cacert = Files.writeString(certifi.resolve("cacert.pem"), "vendor");
        var other = Files.writeString(Files.createDirectories(tmp.resolve("venvs/tool/share")).resolve("cacert.pem"), "keep");
        // A source checkout of certifi is somebody's working tree, not an installed package.
        var lookalike = Files.writeString(Files.createDirectories(
                tmp.resolve("venvs/tool/lib/python3.13/site-packages/notcertifi")).resolve("cacert.pem"), "keep");
        var checkout = Files.writeString(Files.createDirectories(tmp.resolve("venvs/python-certifi/certifi"))
                .resolve("cacert.pem"), "keep");

        var out = run(helper(tmp), Map.of("ISX_TRUST_JAVA_STORE", store.toString(),
                "ISX_TRUST_PEM_BUNDLE", pem.toString()), "venvs");

        assertTrue(Files.isSymbolicLink(cacert), out);
        assertEquals("system pem", Files.readString(cacert));
        assertFalse(Files.isSymbolicLink(other));
        assertFalse(Files.isSymbolicLink(checkout));
        assertFalse(Files.isSymbolicLink(lookalike));
    }

    @Test
    void helperLeavesCertifiAloneWhenTheSystemBundleIsMissing(@TempDir Path tmp) throws Exception {
        // A link to a missing bundle would leave Python trusting nothing at all (#713).
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var cacert = Files.writeString(Files.createDirectories(
                tmp.resolve("v/lib/python3.14/site-packages/certifi")).resolve("cacert.pem"), "vendor");

        var out = run(helper(tmp), Map.of("ISX_TRUST_JAVA_STORE", store.toString(),
                "ISX_TRUST_PEM_BUNDLE", tmp.resolve("ca-bundle.crt").toString()), "v");

        assertFalse(Files.isSymbolicLink(cacert), out);
        assertEquals("vendor", Files.readString(cacert));
        assertTrue(out.contains("ca-bundle.crt is missing or empty"), out);
    }

    @Test
    void helperSettlingBindsARuntimeThatFinishesUnpackingAfterTheFirstPass(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        Files.writeString(Files.createDirectories(tmp.resolve("opt/first/lib/security")).resolve("cacerts"), "vendor");
        var late = Files.createDirectories(tmp.resolve("opt/late/lib/security"));

        var pb = new ProcessBuilder(helper(tmp).toString(), "--settle", tmp.resolve("opt").toString())
                .redirectErrorStream(true);
        pb.environment().put("ISX_TRUST_JAVA_STORE", store.toString());
        pb.environment().put("ISX_TRUST_SETTLE", "5");
        var p = pb.start();
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        // The first pass has bound "first"; "late" finishes unpacking only now.
        assertTrue(reader.readLine().contains("opt/first/lib/security/cacerts"));
        var cacerts = Files.writeString(late.resolve("cacerts"), "vendor");
        var rest = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(0, p.waitFor(), rest);

        assertTrue(Files.isSymbolicLink(cacerts), rest);
        assertEquals("system", Files.readString(cacerts));
    }

    @Test
    void helperLinksPipsVendoredCertifiToo(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var pem = Files.writeString(tmp.resolve("ca-bundle.crt"), "system pem");
        var vendored = Files.writeString(Files.createDirectories(
                tmp.resolve("v/lib/python3.13/site-packages/pip/_vendor/certifi")).resolve("cacert.pem"), "vendor");

        run(helper(tmp), Map.of("ISX_TRUST_JAVA_STORE", store.toString(),
                "ISX_TRUST_PEM_BUNDLE", pem.toString()), "v");

        assertTrue(Files.isSymbolicLink(vendored));
    }

    @Test
    void helperSettlingStopsAfterFiveRoundsEvenIfTheDirectoryKeepsChanging(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        var opt = Files.createDirectories(tmp.resolve("opt"));
        var pb = new ProcessBuilder(helper(tmp).toString(), "--settle", opt.toString()).redirectErrorStream(true);
        pb.environment().put("ISX_TRUST_JAVA_STORE", store.toString());
        pb.environment().put("ISX_TRUST_SETTLE", "0.1");
        var p = pb.start();
        int n = 0;
        while (p.isAlive() && n < 600) { // churn: a new entry every 50 ms
            Files.createDirectories(opt.resolve("e" + n++));
            Thread.sleep(50); // pause is the test: the churn the settle loop has to outlast
        }
        assertFalse(p.isAlive(), "the settle loop must end although the directory keeps changing");
        assertEquals(0, p.waitFor());
    }

    @Test
    void helperIgnoresAMissingDirectory(@TempDir Path tmp) throws Exception {
        var store = Files.writeString(tmp.resolve("system-cacerts"), "system");
        assertEquals("", run(helper(tmp), store, tmp.resolve("nowhere")));
    }
}
