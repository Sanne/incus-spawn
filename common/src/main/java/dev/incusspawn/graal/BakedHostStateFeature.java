package dev.incusspawn.graal;

import dev.incusspawn.config.SecretRedactor;
import org.graalvm.nativeimage.hosted.Feature;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Build-time guard that fails the native-image build if state belonging to the <em>build
 * machine</em> is baked into the image heap — the regression detector for the rule in
 * {@code .claude/rules/native-image.md}: host state may only be captured by the classes on the
 * {@code --initialize-at-run-time} list ({@code RuntimeConstants}, {@code RuntimeServices}).
 * Anything else constructed by a static initializer is built by the <em>builder</em>, which on
 * Linux is root inside the GraalVM builder container, so its fields freeze {@code /root/...} and
 * every user's run then fails on a directory only root can read.
 *
 * <h2>How it works</h2>
 * Registers an object replacer — the analysis calls it for every object scanned into the image
 * heap — and records any string-like constant that trips one of these checks. Findings are
 * reported together in {@code afterAnalysis}, so one build names every leak rather than the first.
 * <ul>
 *   <li><b>Canaries.</b> Before analysis starts — and with it build-time class initialization —
 *       the builder's {@code user.home} and {@code user.name} are replaced by values carrying a
 *       random name, and any constant containing one is a leak. This is exact: no literal can
 *       contain a random name, so it holds whoever builds, and wherever. It is the check that
 *       covers the regression that actually shipped (an {@code Environment} path held by a
 *       build-time-initialized class).</li>
 *   <li><b>The builder's real home</b> ({@code user.home} before the swap, and {@code $HOME}), and
 *       anything under it. This catches what reaches the heap without reading the property during
 *       analysis: values Quarkus recorded in the Maven JVM before this feature ran, code reading
 *       {@code $HOME}, and JDK internals that cached the property at startup. It is switched off,
 *       loudly, when the builder's home is {@link #GUEST_HOME}: building inside an isx instance
 *       runs as {@code agentuser}, and every {@code "/home/agentuser/..."} literal isx writes
 *       <em>into instances</em> would otherwise look like a leak (issue #708). The canaries still
 *       run in that build.</li>
 *   <li><b>The builder's {@code user.dir}</b>, and anything under it. It keeps its real value —
 *       the builder resolves relative paths against it — and it cannot collide with a literal: it
 *       is the build's own source-jar directory under {@code target/}.</li>
 *   <li><b>Credentials in the builder's environment</b> — by name
 *       ({@link SecretRedactor#looksSecret}) or by shape ({@link SecretRedactor#hasSecretShape}).
 *       {@code native-image} sanitizes the builder's environment (only {@code HOME}, {@code LANG},
 *       {@code PATH} and {@code PWD} pass by default), so a build-time {@code getenv} of a token
 *       returns null; that protection is the one {@code NativeImageInitializationTest} keeps by
 *       rejecting {@code -E} pass-throughs. This check is the backstop if one gets through anyway.
 *       The report names the variable and withholds the value, since build logs are often
 *       public.</li>
 * </ul>
 *
 * <p>A {@link Path} is checked as well as its string form because {@code sun.nio.fs.UnixPath}
 * stores the path as a byte array and computes its {@code String} lazily — a folded path can reach
 * the heap with no matching {@code String} object at all (the regression that prompted this guard
 * produced both).
 */
public class BakedHostStateFeature implements Feature {

    /** The home directory isx creates inside instances, which its sources name in many literals. */
    static final String GUEST_HOME = "/home/agentuser";

    /** A credential shorter than this is not one, and matching it would hit unrelated strings. */
    private static final int MIN_SECRET_LENGTH = 8;

    private final String nonce = UUID.randomUUID().toString();

    /** Stands in for {@code user.home} for the rest of the build. */
    private final String homeCanary = "/isx-home-canary-" + nonce;

    /** Stands in for {@code user.name} for the rest of the build. */
    private final String userCanary = "isx-user-canary-" + nonce;

    /** Builder directory → where it came from, for the report. */
    private final Map<String, String> hostDirectories = new LinkedHashMap<>();

    /** Credential value → the variable that holds it. Never printed. */
    private final Map<String, String> secretValues = new LinkedHashMap<>();

    /** Concurrent (the replacer runs on analysis threads), deduplicating, and ordered for a stable report. */
    private final Set<String> leaks = new ConcurrentSkipListSet<>();

    @Override
    public String getDescription() {
        return "Fails the build if build-machine state leaks into the image heap";
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        var realHomes = new LinkedHashMap<String, String>();
        addDirectory(realHomes, System.getProperty("user.home"), "user.home");
        addDirectory(realHomes, System.getenv("HOME"), "$HOME");
        System.setProperty("user.home", homeCanary);
        System.setProperty("user.name", userCanary);

        if (realHomes.containsKey(GUEST_HOME)) {
            System.err.println("[isx-hoststate-guard] the builder's home is the guest home " + GUEST_HOME
                    + ": real-home checking DISABLED for this build (the user.home and user.name"
                    + " canaries still run)");
        } else {
            hostDirectories.putAll(realHomes);
        }
        addDirectory(hostDirectories, System.getProperty("user.dir"), "user.dir");

        // Sorted, so which variable a shared value is attributed to does not depend on hash order.
        for (var env : new TreeMap<>(System.getenv()).entrySet()) {
            var value = env.getValue().strip();
            if (value.length() < MIN_SECRET_LENGTH) continue;
            if (SecretRedactor.looksSecret(env.getKey()) || SecretRedactor.hasSecretShape(value)) {
                secretValues.putIfAbsent(value, env.getKey());
            }
        }

        System.err.println("[isx-hoststate-guard] user.home=" + homeCanary + ", user.name=" + userCanary
                + "; watching the image heap for them, for paths under " + hostDirectories
                + " and for " + secretValues.size() + " credential(s) " + secretValues.values());
        access.registerObjectReplacer(this::check);
    }

    private Object check(Object obj) {
        String value = switch (obj) {
            case String s -> s;
            case Path p -> p.toString();
            case File f -> f.getPath();
            default -> null;
        };
        if (value == null) return obj;
        var kind = obj.getClass().getSimpleName();
        if (value.contains(homeCanary)) {
            leaks.add(kind + " \"" + value + "\" (derived from user.home)");
            return obj;
        }
        if (value.contains(userCanary)) {
            leaks.add(kind + " \"" + value + "\" (derived from user.name)");
            return obj;
        }
        for (var dir : hostDirectories.entrySet()) {
            if (value.equals(dir.getKey()) || value.startsWith(dir.getKey() + File.separator)) {
                leaks.add(kind + " \"" + value + "\" (build " + dir.getValue() + "=" + dir.getKey() + ")");
                return obj;
            }
        }
        for (var secret : secretValues.entrySet()) {
            if (value.contains(secret.getKey())) {
                leaks.add(kind + " containing the value of $" + secret.getValue() + " (withheld)");
                return obj;
            }
        }
        return obj;
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        if (leaks.isEmpty()) {
            System.err.println("[isx-hoststate-guard]   ok — no build-host state in the image heap");
            return;
        }
        throw new Error("[isx-hoststate-guard] Build aborted: " + leaks.size()
                + " value(s) belonging to the build machine were captured into the image heap and"
                + " would be used verbatim on every user's machine:\n  "
                + String.join("\n  ", leaks)
                + "\n(user.home and user.name were " + homeCanary + " and " + userCanary + " for this"
                + " build, so a value derived from them shows that canary where a normal build would"
                + " bake the builder's own.)"
                + "\nThe holder is built at image-build time. Resolve host state in the method that"
                + " uses it, or move the holder into RuntimeConstants/RuntimeServices and keep it on"
                + " --initialize-at-run-time (see " + BakedHostStateFeature.class.getSimpleName() + ").");
    }

    /**
     * Records a builder directory worth watching. "/" is what GraalVM reports when a directory
     * cannot be resolved; as a prefix it would match every absolute path in the heap, so there is
     * nothing useful to check against.
     */
    private static void addDirectory(Map<String, String> into, String dir, String source) {
        if (dir == null) return;
        var stripped = dir.endsWith(File.separator) ? dir.substring(0, dir.length() - 1) : dir;
        if (stripped.length() < 2) return;
        into.putIfAbsent(stripped, source);
    }
}
