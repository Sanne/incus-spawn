package dev.incusspawn;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The commit {@code isx --version} and {@code isx proxy status} report is the one the build's
 * working tree has checked out, also when that tree is a {@code git worktree} (#1169).
 * <p>
 * Agents and the Mac runner build from worktrees and quote this commit in "Verified on" lines;
 * {@code git-commit-id-maven-plugin} stamped the main clone's HEAD there instead. The test runs
 * each module's stamping plugin, exactly as its pom declares it, in a throwaway pom inside a
 * worktree whose HEAD differs from its main clone's.
 */
class GitCommitStampTest {

    private static final String DESCRIBE_FORMAT = "%(describe:tags=true,match=v[0-9]*)";

    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"pom.xml", "../proxy/pom.xml"})
    void stampsTheWorktreeHeadNotTheMainClones(String modulePom) throws Exception {
        var clone = Files.createDirectories(tmp.resolve("clone"));
        git(clone, "init", "-q");
        git(clone, "commit", "-q", "--allow-empty", "-m", "worktree's commit");
        var worktreeHead = git(clone, "rev-parse", "HEAD");
        git(clone, "commit", "-q", "--allow-empty", "-m", "main clone's commit");
        var worktree = tmp.resolve("worktree");
        git(clone, "worktree", "add", "-q", worktree.toString(), worktreeHead);

        assertEquals("0.0.0-SNAPSHOT", stamp(worktree, modulePom), "a checkout without tags still builds");

        var props = new Properties();
        try (var in = Files.newInputStream(worktree.resolve("target/classes/git.properties"))) {
            props.load(in);
        }
        assertEquals(worktreeHead, props.getProperty("git.commit.id"),
                modulePom + " stamps the worktree's HEAD, not the main clone's");
        assertTrue(List.of("", DESCRIBE_FORMAT).contains(props.getProperty("git.commit.id.describe")),
                "no reachable tag yields an empty description, or an unexpanded placeholder on older Git");

        git(worktree, "tag", "nightly");
        assertEquals("0.0.0-SNAPSHOT", stamp(worktree, modulePom), "an unrelated tag is not a release version");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pom.xml", "../proxy/pom.xml"})
    void stampsTheWorktreesTagAndDistance(String modulePom) throws Exception {
        var clone = Files.createDirectories(tmp.resolve("clone"));
        git(clone, "init", "-q");
        Files.writeString(clone.resolve("tracked.txt"), "original\n");
        git(clone, "add", "tracked.txt");
        git(clone, "commit", "-q", "-m", "release");
        git(clone, "tag", "v1.2.3");
        var releaseHead = git(clone, "rev-parse", "HEAD");
        for (int i = 0; i < 5; i++) git(clone, "commit", "-q", "--allow-empty", "-m", "change " + i);
        var worktreeHead = git(clone, "rev-parse", "HEAD");
        git(clone, "tag", "nightly");
        git(clone, "tag", "vnightly");
        git(clone, "commit", "-q", "--allow-empty", "-m", "main clone's release");
        git(clone, "tag", "v9.9.9");
        var worktree = tmp.resolve("worktree");
        git(clone, "worktree", "add", "-q", worktree.toString(), releaseHead);

        // Older Git leaves the formatter unexpanded; source builds must still pass their tests.
        var supportsDescribe = !git(worktree, "log", "-1", "--format=" + DESCRIBE_FORMAT).equals(DESCRIBE_FORMAT);
        var atTag = supportsDescribe ? "1.2.3+0" : "0.0.0-SNAPSHOT";
        var afterTag = supportsDescribe ? "1.2.3+5" : "0.0.0-SNAPSHOT";
        assertEquals(atTag, stamp(worktree, modulePom), "an exact tag retains a snapshot marker");
        Files.writeString(worktree.resolve("tracked.txt"), "changed at the tag\n");
        assertEquals(atTag, stamp(worktree, modulePom), "a dirty exact tag is still a snapshot build");
        git(worktree, "restore", "--", "tracked.txt");
        git(worktree, "checkout", "-q", "--detach", worktreeHead);
        assertEquals(afterTag, stamp(worktree, modulePom), "only tags reachable from the worktree count");
        Files.writeString(worktree.resolve("tracked.txt"), "changed\n");
        assertEquals(afterTag, stamp(worktree, modulePom), "unstaged changes do not affect the committed version");
        git(worktree, "add", "tracked.txt");
        assertEquals(afterTag, stamp(worktree, modulePom), "staged changes do not affect the committed version");
    }

    private static String stamp(Path worktree, String modulePom) throws Exception {
        Files.writeString(worktree.resolve("pom.xml"), stampingPom(Path.of(modulePom)));
        var mvn = new ArrayList<>(List.of(mvn(), "-o", "-q", "-B", "generate-resources"));
        // Offline, so the nested build must look where the outer one resolved the plugin.
        var localRepository = System.getProperty("isx.test.localRepository");
        if (localRepository != null && !localRepository.isBlank()) mvn.add("-Dmaven.repo.local=" + localRepository);
        run(worktree, mvn.toArray(String[]::new));
        var props = new Properties();
        try (var in = Files.newInputStream(worktree.resolve("target/classes/git.properties"))) {
            props.load(in);
        }
        var description = props.getProperty("git.commit.id.describe");
        assertNotNull(description, "git.properties includes the description alongside the commit hash");
        var buildProps = new Properties();
        buildProps.setProperty("app.version", "0.0.0-SNAPSHOT");
        var build = BuildInfo.createInstance(buildProps, props);
        assertEquals("0.0.0-SNAPSHOT", build.version(), "Git descriptions do not change template stamps");
        assertTrue(build.isDev(), "snapshot builds remain development builds, even at a tag with local edits");
        return build.displayVersion();
    }

    /** A minimal pom holding only the plugins of {@code modulePom} that stamp {@code git.commit.id}. */
    private static String stampingPom(Path modulePom) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(modulePom.toFile());
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        var plugins = new StringBuilder();
        var build = (Element) doc.getDocumentElement().getElementsByTagName("build").item(0);
        var nodes = build.getElementsByTagName("plugin");
        for (int i = 0; i < nodes.getLength(); i++) {
            var plugin = (Element) nodes.item(i);
            if (!plugin.getTextContent().contains("git.commit.id")) continue;
            var out = new StringWriter();
            transformer.transform(new DOMSource(plugin), new StreamResult(out));
            plugins.append(out);
        }
        assertFalse(plugins.isEmpty(), modulePom + " declares no plugin stamping git.commit.id");
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId>
                  <artifactId>git-commit-stamp</artifactId>
                  <version>1</version>
                  <packaging>jar</packaging>
                  <build><plugins>%s</plugins></build>
                </project>
                """.formatted(plugins);
    }

    private static String mvn() {
        var home = System.getProperty("maven.home");
        if (home != null && !home.isBlank()) {
            var mvn = Path.of(home, "bin", "mvn");
            if (Files.isExecutable(mvn)) return mvn.toString();
        }
        return "mvn";
    }

    private static String git(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-c", "user.name=test", "-c", "user.email=test@example.com",
                "-c", "commit.gpgsign=false", "-c", "tag.gpgSign=false", "-c", "init.defaultBranch=main",
                "-c", "gc.auto=0", "-c", "maintenance.auto=false")); // no detached maintenance racing @TempDir cleanup (#1473)
        cmd.addAll(List.of(args));
        return run(dir, cmd.toArray(String[]::new));
    }

    private static String run(Path dir, String... cmd) throws IOException, InterruptedException {
        var log = Files.createTempFile("git-commit-stamp", ".log");
        try {
            var builder = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            // A hook or `git rebase --exec` exports GIT_DIR and friends: they would point every
            // command here at the real repository instead of the throwaway one.
            builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
            var process = builder.start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail(String.join(" ", cmd) + " timed out:\n" + Files.readString(log));
            }
            var output = Files.readString(log).strip();
            assertEquals(0, process.exitValue(), String.join(" ", cmd) + " failed:\n" + output);
            return output;
        } finally {
            Files.delete(log);
        }
    }
}
