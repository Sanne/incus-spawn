package dev.incusspawn;

import dev.incusspawn.tool.BobSetup;
import dev.incusspawn.tool.ClaudeSetup;
import dev.incusspawn.tool.CodexSetup;
import dev.incusspawn.tool.GhSetup;
import dev.incusspawn.tool.PiSetup;
import dev.incusspawn.tool.ToolSetup;

import java.nio.file.Path;
import java.util.List;

/**
 * Host-derived constants resolved once per process. All fields are initialized eagerly at
 * class-load time from {@link Environment}, which means this class MUST be listed in the
 * native-image {@code --initialize-at-run-time} flag (see each module's
 * {@code resources-filtered/application.properties}, plus {@code cli/pom.xml}'s
 * {@code macos-native} profile) — the {@code common} counterpart of {@code RuntimeServices}
 * in the CLI.
 *
 * <p>Without that flag GraalVM resolves these paths while <em>building</em> the image and bakes
 * the result into the image heap — on Linux that is the builder container's {@code /root}, which
 * no user can read. {@link dev.incusspawn.graal.BakedHostPathFeature} fails the build if it
 * happens, and {@code .claude/rules/native-image.md} has the full rationale.
 *
 * <p>This is therefore the one place in {@code common} that may capture a host path, and also
 * where anything <em>holding</em> one is constructed — hence {@link #CDI_TOOLS} living here rather
 * than in {@code ToolDefLoader}. Deferring only the class that resolves the path is not enough:
 * GraalVM does not reject a build-time initializer that touches a run-time-initialized class, it
 * initializes it early and folds the value. Everywhere else, call the {@link Environment} method
 * instead of storing its result.
 */
public final class RuntimeConstants {

    /** Host-side tool/image download cache; see {@link dev.incusspawn.tool.DownloadCache}. */
    public static final Path DOWNLOAD_CACHE_DIR = Environment.downloadCacheDir();

    /** Host-side Claude skill cache; see {@link dev.incusspawn.tool.SkillsCache}. */
    public static final Path SKILLS_CACHE_DIR = Environment.skillsCacheDir();

    /**
     * Tools declared in Java rather than YAML: proxy entries, build steps, and actions are defined
     * programmatically. Resolved by {@code ToolDefLoader} as a fallback when no YAML tool matches.
     * They hold a {@link dev.incusspawn.tool.DownloadCache}, so their holder has to be deferred
     * too — which is why they are declared here, after the paths they capture.
     */
    public static final List<ToolSetup> CDI_TOOLS = List.of(
            new ClaudeSetup(), new GhSetup(), new PiSetup(), new BobSetup(), new CodexSetup()
    );

    private RuntimeConstants() {}
}
