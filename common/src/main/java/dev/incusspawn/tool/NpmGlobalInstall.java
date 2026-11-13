package dev.incusspawn.tool;

import dev.incusspawn.config.SecretRedactor;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.util.BuildOutput;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Installs npm-distributed CLIs globally.
 * <p>
 * Some CLIs ({@code @openai/codex}, {@code @github/copilot}) publish a thin JS launcher whose
 * real binary lives in a per-platform <em>optional</em> dependency. npm treats a failed optional
 * dependency as skippable, so a transient download failure leaves {@code npm install -g} (and
 * {@code npm update -g}) exiting 0 with only the launcher installed -- and the template would be
 * stamped as built and CoW-copied into every branch (#808). For those CLIs the platform package
 * is checked after every install or update, and a skipped one is reinstalled before failing.
 * <p>
 * A just-published release takes minutes to reach the registry whole: npm publishes the
 * launcher before its platform builds, and the CDN can answer 404 for a tarball the metadata
 * already names. So an install that the registry cannot serve yet is retried with a bounded
 * backoff ({@link #REGISTRY_RETRY_DELAYS}) rather than failed at once (#1757).
 * <p>
 * The check resolves the package with node as root rather than running the CLI: running it would
 * write into the template ({@code copilot --version} unpacks ~165 MB into agentuser's cache).
 */
public final class NpmGlobalInstall {

    /**
     * A CLI whose launcher, {@code npmPackage}, loads its binary from the optional package
     * {@code platformPackagePrefix + process.platform + "-" + process.arch}, the name both
     * launchers look up on glibc Linux.
     */
    public record PlatformSplitCli(String displayName, String npmPackage, String binary,
                                   String platformPackagePrefix) {}

    /** Every built-in CLI with a platform-split package, checked after {@code npm update -g}. */
    public static final List<PlatformSplitCli> PLATFORM_SPLIT_CLIS =
            List.of(CodexSetup.NPM_CLI, CopilotSetup.NPM_CLI);

    // Verbose, because npm reports a skipped optional dependency at no lower level: the output
    // is captured, and only shown (filtered) when the install fails.
    private static final List<String> NPM_INSTALL =
            List.of("npm", "install", "-g", "--ignore-scripts", "--loglevel=verbose");

    /**
     * The waits before each retry while a release is still reaching the registry, shared by
     * every retry of one npm command or repair pass: 6m45s in all. Codex's Linux builds have
     * landed up to six minutes after its launcher.
     */
    static final List<Duration> REGISTRY_RETRY_DELAYS = List.of(Duration.ofSeconds(15),
            Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(180));

    // The code npm fails with when the registry does not serve a tarball (E404) or version
    // (ETARGET): its own line, since the verbose log can mention either on the way.
    private static final Pattern REGISTRY_MISS =
            Pattern.compile("^npm (?:error|ERR!) code E(?:404|TARGET)$", Pattern.MULTILINE);

    /** How an install waits before a retry; tests record the waits instead. */
    static DownloadCache.Pause pause = Thread::sleep;

    private static final int NOT_INSTALLED = 2;
    private static final int PLATFORM_PACKAGE_MISSING = 3;

    // Mirrors node's own lookup from the launcher's directory. Checking for package.json in each
    // candidate node_modules avoids require.resolve(), which a package's "exports" can refuse.
    private static final String PLATFORM_CHECK_JS = String.join("",
            "const path=require(\"path\"),fs=require(\"fs\"),{createRequire}=require(\"module\");",
            "const [dir,prefix]=process.argv.slice(1);",
            "const want=prefix+process.platform+\"-\"+process.arch;",
            "const paths=createRequire(path.join(fs.realpathSync(dir),\"package.json\")).resolve.paths(want)||[];",
            "console.log(want);",
            "process.exit(paths.some(p=>fs.existsSync(path.join(p,want,\"package.json\")))?0:"
                    + PLATFORM_PACKAGE_MISSING + ");");

    private NpmGlobalInstall() {
    }

    /** Installs a self-contained npm CLI, failing the build step if npm fails. */
    static void install(Container c, String displayName, String npmPackage) {
        var npm = npmInstall(c, npmPackage, new Retries());
        if (!npm.success()) {
            throw npmFailed(c, displayName, npm);
        }
    }

    /**
     * Installs a platform-split CLI, reinstalling while npm skips the platform package,
     * then checks that agentuser's login PATH finds it.
     */
    static void install(Container c, PlatformSplitCli cli) {
        var retries = new Retries();
        var npm = npmInstall(c, cli.npmPackage(), retries);
        if (!npm.success()) {
            throw npmFailed(c, cli.displayName(), npm);
        }
        var check = checkPlatformPackage(c, cli);
        if (check.missing() != null) {
            var reinstalled = reinstallSkipped(c, cli, check, retries);
            npm = reinstalled.npm();
            check = reinstalled.check();
        }
        if (!npm.success()) {
            throw npmFailed(c, cli.displayName(), npm);
        }
        if (check.notInstalled()) {
            c.out().stepBreak();
            throw new IncusException("npm reported installing " + cli.npmPackage()
                    + ", but it is not in npm's global root");
        }
        if (check.missing() != null) {
            c.out().stepBreak();
            throw withSkipReasons(c, skipped(cli, check.missing()), npm);
        }
        if (!c.shAsUser("agentuser", "command -v " + cli.binary()).success()) {
            c.out().stepBreak();
            throw new IncusException(cli.displayName() + " was installed, but '" + cli.binary()
                    + "' is not on agentuser's PATH");
        }
    }

    /**
     * After {@code npm update -g}: reinstalls any platform-split CLI whose update skipped its
     * platform package. Returns a message per CLI still left without one; CLIs the instance
     * does not have are ignored.
     */
    public static List<String> repairAfterUpdate(Container c) {
        var problems = new ArrayList<String>();
        var retries = new Retries();
        for (var cli : PLATFORM_SPLIT_CLIS) {
            var check = checkPlatformPackage(c, cli);
            if (check.missing() == null) continue;
            var reinstalled = reinstallSkipped(c, cli, check, retries);
            if (reinstalled.check().missing() != null) {
                problems.add(skipped(cli, reinstalled.check().missing()).getMessage()
                        + skipReasons(reinstalled.npm()).stream().map(l -> "\n  " + l).collect(Collectors.joining()));
            }
        }
        return problems;
    }

    private record Check(boolean notInstalled, String missing) {}

    private record Reinstall(IncusClient.ExecResult npm, Check check) {}

    /**
     * Reinstalls a CLI whose platform package npm skipped: at once, then after each wait
     * {@code retries} has left, until npm fails or the package is there. npm logs nothing when
     * it skips a platform build that is not published yet, so every skip may be one.
     */
    private static Reinstall reinstallSkipped(Container c, PlatformSplitCli cli, Check check, Retries retries) {
        var npm = npmInstall(c, cli.npmPackage(), retries);
        while (npm.success()) {
            check = checkPlatformPackage(c, cli);
            if (check.missing() == null || !retries.waitBefore("npm skipped " + check.missing())) break;
            npm = npmInstall(c, cli.npmPackage(), retries);
        }
        return new Reinstall(npm, check);
    }

    /** The waits one install, or one repair pass, has left for the registry to catch up with a release. */
    private static final class Retries {
        private final Iterator<Duration> delays = REGISTRY_RETRY_DELAYS.iterator();

        /** Waits the next delay, showing why on the live step; false when none is left. */
        boolean waitBefore(String why) {
            if (!delays.hasNext()) return false;
            var delay = delays.next();
            BuildOutput.stepProgress(why + ", retrying in " + delay.toSeconds() + "s");
            try {
                pause.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IncusException("Interrupted while waiting for the npm registry");
            }
            return true;
        }
    }

    private static Check checkPlatformPackage(Container c, PlatformSplitCli cli) {
        var result = c.sh("root=$(npm root -g) || exit 1\n"
                + "[ -f \"$root/" + cli.npmPackage() + "/package.json\" ] || exit " + NOT_INSTALLED + "\n"
                + "exec node -e " + Container.shellQuote(PLATFORM_CHECK_JS)
                + " \"$root/" + cli.npmPackage() + "\" " + Container.shellQuote(cli.platformPackagePrefix()));
        return switch (result.exitCode()) {
            case 0 -> new Check(false, null);
            case NOT_INSTALLED -> new Check(true, null);
            case PLATFORM_PACKAGE_MISSING -> new Check(false, result.stdout().strip());
            default -> {
                c.out().stepBreak();
                // Its stdout is the check's answer, so only what went wrong is shown
                throw Container.failed(c.out(), "The check printed:", "Could not check " + cli.displayName()
                        + "'s platform package (exit code " + result.exitCode() + ")",
                        new IncusClient.ExecResult(result.exitCode(), "", result.stderr()));
            }
        };
    }

    /**
     * Runs {@code npm update -g}, retrying while the registry does not serve a release it
     * resolved yet. {@link #repairAfterUpdate} checks what it dropped without failing.
     */
    public static IncusClient.ExecResult update(Container c) {
        return npm(c, new Retries(), "what npm update -g resolved", "sh", "-c", UPDATE_SCRIPT);
    }

    /**
     * {@code npm update -g}, unlike the build's {@code npm install -g --ignore-scripts}, runs
     * install scripts, and opencode's runs its binary, which writes directories into {@code HOME}.
     * So it gets a scratch {@code HOME} and {@code TMPDIR} with root's real npm cache and npmrc, and leaves
     * nothing in root's home for every branch to inherit (#2221).
     */
    public static final String UPDATE_SCRIPT = """
            cache=$(npm config get cache) || exit 1
            rcfile=$(npm config get userconfig) || exit 1
            scratch=$(mktemp -d) || exit 1
            trap 'rm -rf "$scratch"' EXIT
            HOME="$scratch" TMPDIR="$scratch" npm_config_cache="$cache" npm_config_userconfig="$rcfile" npm update -g""";

    private static IncusClient.ExecResult npmInstall(Container c, String npmPackage, Retries retries) {
        var command = new ArrayList<>(NPM_INSTALL);
        command.add(npmPackage);
        return npm(c, retries, npmPackage, command.toArray(String[]::new));
    }

    /** Runs npm, retrying while the registry answers that it has no such version or tarball. */
    private static IncusClient.ExecResult npm(Container c, Retries retries, String what, String... command) {
        while (true) {
            var npm = c.exec(command);
            if (npm.success() || !registryMiss(npm)
                    || !retries.waitBefore("The npm registry does not serve " + what + " yet")) {
                return npm;
            }
        }
    }

    private static boolean registryMiss(IncusClient.ExecResult npm) {
        return REGISTRY_MISS.matcher(npm.stdout()).find() || REGISTRY_MISS.matcher(npm.stderr()).find();
    }

    private static IncusException npmFailed(Container c, String displayName, IncusClient.ExecResult npm) {
        c.out().stepBreak();
        return Container.failed(c.out(), "npm printed:",
                "Failed to install " + displayName + " (exit code " + npm.exitCode() + ")", npm);
    }

    private static IncusException skipped(PlatformSplitCli cli, String platformPackage) {
        return new IncusException("npm skipped " + cli.displayName() + "'s platform package "
                + platformPackage + " on every install without failing, so '" + cli.binary() + "' cannot run."
                + " It is an optional dependency, which npm drops when its download fails"
                + " or the registry does not serve it yet");
    }

    /**
     * {@code skipped}, after warning with the end of why npm dropped the platform package, from its
     * log; the exception carries all of it for the failure report.
     */
    private static IncusException withSkipReasons(Container c, IncusException skipped, IncusClient.ExecResult npm) {
        var reasons = skipReasons(npm);
        if (reasons.isEmpty()) return skipped;
        c.out().warn("npm's log says why:", BuildOutput.lastOf(reasons.toArray(String[]::new)));
        return skipped.withStepOutput("npm's log says why:\n" + String.join("\n", reasons) + "\n\n");
    }

    /**
     * The lines of npm's verbose log that say why it dropped something, as text: an install
     * script decides what they hold (#1588). Each is scrubbed as npm printed it and as it is shown
     * ({@link SecretRedactor#scrubGuest}, #1878), since the failure report keeps them.
     */
    static List<String> skipReasons(IncusClient.ExecResult npm) {
        var raw = npm.stdout() + "\n" + npm.stderr();
        // A control string can run across raw lines (#1920), so a shown line is the raw text
        // between the line breaks it shows, found through where each shown character came from
        var shown = BuildOutput.shownGuestText(raw);
        var text = shown.text();
        var reasons = new ArrayList<String>();
        for (int line = 0, rawLine = 0; line <= text.length(); ) {
            int end = text.indexOf('\n', line);
            if (end < 0) end = text.length();
            int rawEnd = end < text.length() ? shown.source()[end] : raw.length();
            var lower = text.substring(line, end).toLowerCase();
            if (lower.contains("fail") || lower.contains("error")) {
                reasons.add(SecretRedactor.scrubGuest(raw.substring(rawLine, rawEnd)));
            }
            line = end + 1;
            rawLine = rawEnd + 1;
        }
        return List.copyOf(reasons);
    }
}
