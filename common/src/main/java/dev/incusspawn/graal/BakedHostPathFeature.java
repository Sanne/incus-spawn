package dev.incusspawn.graal;

import org.graalvm.nativeimage.hosted.Feature;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Build-time guard that fails the native-image build if a path belonging to the <em>build
 * machine</em> is baked into the image heap — the regression detector for the rule in
 * {@code .claude/rules/native-image.md}: host state may only be captured by the classes on the
 * {@code --initialize-at-run-time} list ({@code RuntimeConstants}, {@code RuntimeServices}).
 * Anything else constructed by a static initializer is built by the <em>builder</em>, which on
 * Linux is root inside the GraalVM builder container, so its fields freeze {@code /root/...} and
 * every user's run then fails on a directory only root can read.
 *
 * <h2>How it works</h2>
 * Registers an object replacer — the analysis calls it for every object scanned into the image
 * heap — and records any string-like constant that is, or lives under, one of the builder's own
 * directories ({@code user.home}, {@code user.dir}). Findings are reported together in
 * {@code afterAnalysis}, so one build names every leak rather than the first.
 *
 * <p>A {@link Path} is checked as well as its string form because {@code sun.nio.fs.UnixPath}
 * stores the path as a byte array and computes its {@code String} lazily — a folded path can reach
 * the heap with no matching {@code String} object at all (the regression that prompted this guard
 * produced both).
 *
 * <p>Precision comes from {@link #ALLOWED}, not from guessing which paths matter: an earlier
 * version only flagged builder paths containing {@code "incus-spawn"}, which silently tolerated
 * {@code ~/.m2/repository}, {@code ~/.config/incus/}, {@code ~/.local/bin/isx} and a bare
 * {@code $HOME} — the same bug with a different directory. Build-host values that legitimately
 * belong in the heap go in the allowed list with a reason, where they are reviewable.
 */
public class BakedHostPathFeature implements Feature {

    /** System properties whose values name a directory private to the build machine. */
    private static final List<String> HOST_DIRECTORY_PROPERTIES = List.of("user.home", "user.dir");

    /**
     * Prefixes under a build-host directory that are legitimately part of the image. Empty, and
     * expected to stay that way: add an entry only with a comment saying why that path is correct
     * to ship. ({@code user.name} is deliberately not checked — its value is typically
     * {@code "root"}, too short and too common a substring to match without false positives.)
     */
    private static final List<String> ALLOWED = List.of();

    /** Builder directory → the property it came from, for the failure message. */
    private final Map<String, String> hostDirectories = new LinkedHashMap<>();

    /** Concurrent (the replacer runs on analysis threads), deduplicating, and ordered for a stable report. */
    private final Set<String> leaks = new ConcurrentSkipListSet<>();

    @Override
    public String getDescription() {
        return "Fails the build if a build-machine directory leaks into the image heap";
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        for (var property : HOST_DIRECTORY_PROPERTIES) {
            var value = System.getProperty(property);
            // "/" is what GraalVM reports when a directory cannot be resolved; as a prefix it would
            // match every absolute path in the heap, so there is nothing useful to check against.
            if (value == null || value.length() < 2 || "/".equals(value)) continue;
            hostDirectories.putIfAbsent(stripTrailingSeparator(value), property);
        }
        if (hostDirectories.isEmpty()) {
            System.err.println("[isx-hostpath-guard] no build-host directories resolved — skipping");
            return;
        }
        System.err.println("[isx-hostpath-guard] watching the image heap for paths under "
                + hostDirectories);
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
        for (var entry : hostDirectories.entrySet()) {
            var dir = entry.getKey();
            if (!value.equals(dir) && !value.startsWith(dir + File.separator)) continue;
            if (ALLOWED.stream().anyMatch(value::startsWith)) continue;
            leaks.add(obj.getClass().getSimpleName() + " \"" + value
                    + "\" (build " + entry.getValue() + "=" + dir + ")");
            break;
        }
        return obj;
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        if (hostDirectories.isEmpty()) {
            return;
        }
        if (leaks.isEmpty()) {
            System.err.println("[isx-hostpath-guard]   ok — no build-host paths in the image heap");
            return;
        }
        throw new Error("[isx-hostpath-guard] Build aborted: " + leaks.size()
                + " path(s) belonging to the build machine were captured into the image heap and"
                + " would be used verbatim on every user's machine:\n  "
                + String.join("\n  ", leaks)
                + "\nThe holder is built at image-build time. Resolve Environment paths in the"
                + " method that uses them, or move the holder into RuntimeConstants/RuntimeServices"
                + " and keep it on --initialize-at-run-time (see "
                + BakedHostPathFeature.class.getSimpleName() + ").");
    }

    private static String stripTrailingSeparator(String dir) {
        return dir.endsWith(File.separator) ? dir.substring(0, dir.length() - 1) : dir;
    }
}
