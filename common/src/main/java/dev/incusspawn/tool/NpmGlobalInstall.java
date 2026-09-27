package dev.incusspawn.tool;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.util.BuildOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Installs npm-distributed CLIs globally.
 * <p>
 * Some CLIs ({@code @openai/codex}, {@code @github/copilot}) publish a thin JS launcher whose
 * real binary lives in a per-platform <em>optional</em> dependency. npm treats a failed optional
 * dependency as skippable, so a transient download failure leaves {@code npm install -g} (and
 * {@code npm update -g}) exiting 0 with only the launcher installed -- and the template would be
 * stamped as built and CoW-copied into every branch (#808). For those CLIs the platform package
 * is checked after every install or update, and a skipped one is reinstalled once before failing.
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
        var npm = npmInstall(c, npmPackage);
        if (!npm.success()) {
            throw npmFailed(displayName, npm);
        }
    }

    /**
     * Installs a platform-split CLI, reinstalling once if npm skipped the platform package,
     * then checks that agentuser's login PATH finds it.
     */
    static void install(Container c, PlatformSplitCli cli) {
        var npm = npmInstall(c, cli.npmPackage());
        if (!npm.success()) {
            throw npmFailed(cli.displayName(), npm);
        }
        var check = checkPlatformPackage(c, cli);
        if (check.missing() != null) {
            npm = npmInstall(c, cli.npmPackage());
            check = npm.success() ? checkPlatformPackage(c, cli) : check;
        }
        if (!npm.success()) {
            throw npmFailed(cli.displayName(), npm);
        }
        if (check.notInstalled()) {
            BuildOutput.stepBreak();
            throw new IncusException("npm reported installing " + cli.npmPackage()
                    + ", but it is not in npm's global root");
        }
        if (check.missing() != null) {
            BuildOutput.stepBreak();
            printSkipReasons(npm);
            throw skipped(cli, check.missing());
        }
        if (!c.shAsUser("agentuser", "command -v " + cli.binary()).success()) {
            BuildOutput.stepBreak();
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
        for (var cli : PLATFORM_SPLIT_CLIS) {
            var check = checkPlatformPackage(c, cli);
            if (check.missing() == null) continue;
            var npm = npmInstall(c, cli.npmPackage());
            var recheck = npm.success() ? checkPlatformPackage(c, cli) : check;
            if (recheck.missing() != null) {
                problems.add(skipped(cli, recheck.missing()).getMessage()
                        + skipReasons(npm).stream().map(l -> "\n  " + l).collect(Collectors.joining()));
            }
        }
        return problems;
    }

    private record Check(boolean notInstalled, String missing) {}

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
                BuildOutput.stepBreak();
                if (!result.stderr().isBlank()) System.err.print(result.stderr());
                throw new IncusException("Could not check " + cli.displayName() + "'s platform package"
                        + " (exit code " + result.exitCode() + ")");
            }
        };
    }

    private static IncusClient.ExecResult npmInstall(Container c, String npmPackage) {
        var command = new ArrayList<>(NPM_INSTALL);
        command.add(npmPackage);
        return c.exec(command.toArray(String[]::new));
    }

    private static IncusException npmFailed(String displayName, IncusClient.ExecResult npm) {
        BuildOutput.stepBreak();
        if (!npm.stdout().isBlank()) System.err.print(npm.stdout());
        if (!npm.stderr().isBlank()) System.err.print(npm.stderr());
        return new IncusException("Failed to install " + displayName + " (exit code " + npm.exitCode() + ")");
    }

    private static IncusException skipped(PlatformSplitCli cli, String platformPackage) {
        return new IncusException("npm skipped " + cli.displayName() + "'s platform package "
                + platformPackage + " twice without failing, so '" + cli.binary() + "' cannot run."
                + " It is an optional dependency, which npm drops when its download fails");
    }

    private static void printSkipReasons(IncusClient.ExecResult npm) {
        skipReasons(npm).forEach(System.err::println);
    }

    /** The lines of npm's verbose log that say why it dropped something. */
    static List<String> skipReasons(IncusClient.ExecResult npm) {
        return (npm.stdout() + "\n" + npm.stderr()).lines()
                .filter(l -> {
                    var lower = l.toLowerCase();
                    return lower.contains("fail") || lower.contains("error");
                })
                .toList();
    }
}
