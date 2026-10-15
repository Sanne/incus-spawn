package dev.incusspawn.git;

import dev.incusspawn.FileTrees;
import dev.incusspawn.config.GitTransferConfig;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.TerminalProgress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

public final class HostRepoRefresh {

    private HostRepoRefresh() {}

    enum FetchState { FETCHING, DONE, FAILED }
    /**
     * {@code detail}: live progress while fetching, the time taken when done, isx's one-line reason when
     * failed; {@code details}: a failure's explanation and Git's quoted output, printed under it.
     */
    record TaskProgress(FetchState state, String detail, List<String> details) {
        TaskProgress(FetchState state, String detail) { this(state, detail, List.of()); }
    }

    /** How often a fetch off a terminal says it is still running. */
    static final long HEARTBEAT_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** {@code depths} is {@link #hostDepths(Map)} of every definition, not only of those refreshed. */
    public static void refresh(List<ImageDef.RepoEntry> repos, Map<String, Integer> depths, SpawnConfig config,
                               boolean cloneMissing, Consumer<String> output) {
        if (repos.isEmpty()) return;
        if (config.getHostPaths().isEmpty() && config.getRepoPaths().isEmpty()) return;

        var classified = classify(repos, depths, config, cloneMissing);
        if (classified.toFetch.isEmpty() && classified.toClone.isEmpty()) return;

        if (!classified.toFetch.isEmpty()) {
            BuildOutput.section("Refreshing " + classified.toFetch.size() + " host " + (classified.toFetch.size() == 1 ? "repo:" : "repos:"));
            fetchInParallel(classified.toFetch, config.gitTransfer(), output);
        }

        if (!classified.toClone.isEmpty()) {
            cloneSequentially(classified.toClone, config);
        }
    }

    private record ClassifiedRepos(List<FetchTask> toFetch, List<CloneTask> toClone) {}

    private static ClassifiedRepos classify(List<ImageDef.RepoEntry> repos, Map<String, Integer> depths,
                                            SpawnConfig config, boolean cloneMissing) {
        var deduplicated = deduplicateByUrl(repos);
        var toFetch = new ArrayList<FetchTask>();
        var toClone = new ArrayList<CloneTask>();

        for (var repo : deduplicated) {
            var repoName = GitRemoteUtils.repoNameFromUrl(repo.getUrl());
            if (repoName.isEmpty()) continue;
            var depth = depths.get(GitRemoteUtils.normalizeGitUrl(repo.getUrl()));

            Path hostPath;
            try {
                hostPath = GitRemoteUtils.resolveHostRepoPath(repoName, config);
            } catch (IllegalStateException e) {
                System.err.println(BuildOutput.STEP_INDENT + "Warning: " + e.getMessage());
                continue;
            }

            if (hostPath != null && Files.isDirectory(hostPath) && GitRemoteUtils.isGitRepo(hostPath)) {
                var remoteName = GitRemoteUtils.matchingRemoteName(hostPath, repo.getUrl());
                if (remoteName != null) {
                    toFetch.add(new FetchTask(repoName, hostPath, remoteName, managedDepth(hostPath), depth));
                } else {
                    BuildOutput.note("Host repo " + hostPath + " has no remote matching " + repo.getUrl() + ", skipping.");
                }
            } else if (cloneMissing) {
                var targetDir = resolveCloneTarget(repoName, config);
                if (targetDir != null) {
                    toClone.add(new CloneTask(repoName, repo.getUrl(), targetDir, depth,
                            depth != null && repo.hasBranch() ? repo.getBranch() : null));
                }
            }
        }

        return new ClassifiedRepos(toFetch, toClone);
    }

    private static void fetchInParallel(List<FetchTask> tasks, GitTransferConfig limits, Consumer<String> output) {
        var states = new AtomicReferenceArray<TaskProgress>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            states.set(i, new TaskProgress(FetchState.FETCHING, null));
        }

        // A terminal shows each fetch's progress live; a log gets a bounded line now and then instead.
        boolean live = TerminalProgress.isAnsiTerminal();
        // Fetches are lightweight; run them all at once (no concurrency cap).
        TerminalProgress.run(tasks.size(), tasks.size(),
                idx -> {
                    var task = tasks.get(idx);
                    long start = System.nanoTime();
                    long[] nextHeartbeat = {start + HEARTBEAT_NANOS};
                    try {
                        var result = task.fetch(limits, progress -> {
                            // The task's time, not the current Git process's: an unshallow runs several.
                            long now = System.nanoTime();
                            var shown = new GitProcess.Progress(Duration.ofNanos(now - start), progress.line()).summary();
                            states.set(idx, new TaskProgress(FetchState.FETCHING, shown));
                            if (!live && now >= nextHeartbeat[0]) {
                                nextHeartbeat[0] = now + HEARTBEAT_NANOS; // after a gap, one line rather than a burst
                                output.accept(BuildOutput.STEP_INDENT + "Still fetching " + task.repoName() + " (" + shown + ")");
                            }
                        });
                        if (result.success()) {
                            long millis = (System.nanoTime() - start) / 1_000_000;
                            states.set(idx, new TaskProgress(FetchState.DONE,
                                    millis >= 2_000 ? BuildOutput.formatElapsed(millis) : null));
                        } else {
                            // isx's reason on the line; Git's own words only quoted under it.
                            states.set(idx, new TaskProgress(FetchState.FAILED,
                                    result.reason(), result.details()));
                        }
                    } catch (Exception e) {
                        states.set(idx, new TaskProgress(FetchState.FAILED, e.getMessage()));
                    }
                },
                (idx, frame) -> formatFetchLine(tasks.get(idx), states.get(idx), frame),
                idx -> plainFetchLine(tasks.get(idx), states.get(idx)),
                output);
        // The animated lines hold one line each: the explanations and Git's words follow them.
        if (live) {
            for (int i = 0; i < tasks.size(); i++) {
                var failed = states.get(i);
                if (failed.state() == FetchState.FAILED && !failed.details().isEmpty()) output.accept(plainFetchLine(tasks.get(i), failed));
            }
        }
    }

    static String formatFetchLine(FetchTask task, TaskProgress progress, int frame) {
        var sb = new StringBuilder(BuildOutput.STEP_INDENT);
        switch (progress.state()) {
            case FETCHING -> sb.append(TerminalProgress.SPINNER[frame % TerminalProgress.SPINNER.length])
                    .append(" \033[2mFetching\033[0m "); // raw ANSI: animated line
            case DONE     -> sb.append("\033[32m✓\033[0m Fetched  "); // raw ANSI: animated line
            case FAILED   -> sb.append("\033[31m✗\033[0m \033[31mFailed\033[0m   "); // raw ANSI: animated line
        }
        sb.append(' ');
        sb.append(task.repoName());
        sb.append(" \033[2m(").append(task.hostPath()).append(")\033[0m"); // raw ANSI: animated line
        var detail = progress.detail();
        if (progress.state() == FetchState.FAILED && (detail == null || detail.isEmpty()) && !progress.details().isEmpty()) {
            detail = "details below";
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(progress.state() == FetchState.FAILED ? "  \033[31m" : "  \033[2m") // raw ANSI: animated line
                    .append(detail).append("\033[0m"); // raw ANSI: animated line
        }
        return sb.toString();
    }

    static String plainFetchLine(FetchTask task, TaskProgress progress) {
        if (progress.state() == FetchState.DONE) {
            return BuildOutput.STEP_INDENT + "Fetched " + task.repoName() + " (" + task.hostPath() + ")"
                    + (progress.detail() == null ? "" : " in " + progress.detail());
        }
        var msg = new StringBuilder(BuildOutput.STEP_INDENT + "Warning: fetch failed for " + task.repoName()
                + " at " + task.hostPath());
        if (progress.detail() != null && !progress.detail().isEmpty()) msg.append(" (").append(progress.detail()).append(')');
        msg.append("; keeping the existing checkout");
        for (var line : progress.details()) msg.append('\n').append(BuildOutput.STEP_INDENT).append("  ").append(line);
        return msg.toString();
    }

    private static void cloneSequentially(List<CloneTask> tasks, SpawnConfig config) {
        var policy = resolvePolicy(config);
        var limits = config.gitTransfer();

        if (policy == ClonePolicy.ASK && System.console() == null) {
            BuildOutput.note("Skipping clone of " + tasks.size() + " repo(s) — set auto-clone-repos: always in config.yaml to clone automatically.");
            return;
        }

        if (policy == ClonePolicy.ASK) {
            BuildOutput.note("Local repo clones are kept in sync and shared across containers — builds copy from disk instead of the network.");
        }

        for (var task : tasks) {
            if (policy == ClonePolicy.NEVER) {
                break;
            }

            if (policy == ClonePolicy.ASK) {
                var answer = promptClone(task.url, task.targetDir);
                switch (answer) {
                    case YES -> {}
                    case NO -> { continue; }
                    case ALWAYS -> {
                        policy = ClonePolicy.ALWAYS;
                        config.setAutoCloneRepos("always");
                        config.save();
                        BuildOutput.note("Saved preference: auto-clone-repos: always");
                    }
                    case NEVER -> {
                        policy = ClonePolicy.NEVER;
                        config.setAutoCloneRepos("never");
                        config.save();
                        BuildOutput.note("Saved preference: auto-clone-repos: never");
                        continue;
                    }
                }
            }

            BuildOutput.stepStart("Cloning " + task.repoName + "...");
            boolean existed = Files.exists(task.targetDir);
            var result = GitRemoteUtils.hostGitTransfer(task.targetDir.getParent(), limits,
                    progress -> BuildOutput.stepProgress(progress.summary()), task.cloneArgs());
            if (result.success()) {
                BuildOutput.stepDone();
            } else {
                BuildOutput.stepBreak();
                // A clone stopped part way is no checkout: never leave it for a later build to use.
                if (!existed) deletePartialClone(task.targetDir);
                System.err.println(BuildOutput.STEP_INDENT + "Warning: clone failed for " + GitCommandException.scrub(task.url)
                        + (result.reason() == null ? "" : " (" + result.reason() + ")"));
                for (var line : result.details()) System.err.println(BuildOutput.STEP_INDENT + "  " + line);
            }
        }
    }

    private static void deletePartialClone(Path dir) {
        try {
            FileTrees.delete(dir);
        } catch (IOException e) {
            System.err.println(BuildOutput.STEP_INDENT + "Warning: could not remove the incomplete clone at " + dir
                    + ": " + e.getMessage());
        }
    }

    private static ClonePolicy resolvePolicy(SpawnConfig config) {
        return switch (config.getAutoCloneRepos()) {
            case "always" -> ClonePolicy.ALWAYS;
            case "never" -> ClonePolicy.NEVER;
            default -> ClonePolicy.ASK;
        };
    }

    private static CloneAnswer promptClone(String url, Path targetDir) {
        var console = System.console();
        if (console == null) return CloneAnswer.NO;

        System.out.print("Clone " + url + " into " + targetDir + "? (y/n/always/never): ");
        var input = console.readLine();
        if (input == null) return CloneAnswer.NO;
        return switch (input.strip().toLowerCase()) {
            case "y", "yes" -> CloneAnswer.YES;
            case "always" -> CloneAnswer.ALWAYS;
            case "never" -> CloneAnswer.NEVER;
            default -> CloneAnswer.NO;
        };
    }

    static Path resolveCloneTarget(String repoName, SpawnConfig config) {
        var override = config.getRepoPaths().get(repoName);
        if (override != null && !override.isEmpty()) {
            return Path.of(HostResourceSetup.expandHostTilde(override));
        }
        var hostPaths = config.getHostPaths();
        if (hostPaths.isEmpty()) return null;
        var basePath = HostResourceSetup.expandHostTilde(hostPaths.get(0));
        return Path.of(basePath, repoName);
    }

    static List<ImageDef.RepoEntry> deduplicateByUrl(List<ImageDef.RepoEntry> repos) {
        var seen = new LinkedHashMap<String, ImageDef.RepoEntry>();
        for (var repo : repos) {
            if (repo.isHostSource()) continue; // Upstream access belongs to the user on the host.
            var normalized = GitRemoteUtils.normalizeGitUrl(repo.getUrl());
            seen.putIfAbsent(normalized, repo);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * The repos a host-side refresh may clone or fetch for {@code imageDef}'s chain. Project-local
     * definitions contribute none: a host clone of their URL (possibly a local path) would be
     * shared with the build, so their repos are only ever cloned inside the container (#765).
     */
    public static List<ImageDef.RepoEntry> collectAllRepos(ImageDef imageDef, Map<String, ImageDef> defs) {
        var repos = new ArrayList<ImageDef.RepoEntry>();
        if (imageDef.getProjectRoot() == null) repos.addAll(imageDef.getRepos());
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            if (ancestor.getProjectRoot() == null) repos.addAll(ancestor.getRepos());
        }
        return repos;
    }

    public static List<ImageDef.RepoEntry> collectAllRepos(List<ImageDef> imageDefs, Map<String, ImageDef> defs) {
        var repos = new ArrayList<ImageDef.RepoEntry>();
        for (var imageDef : imageDefs) {
            repos.addAll(collectAllRepos(imageDef, defs));
        }
        return repos;
    }

    /**
     * The depth a host checkout keeps, by normalized URL: the deepest any template asks for, and no
     * entry (all of history) as soon as one template asks for all of it, since every template naming
     * the URL clones from the same host checkout. Project-local definitions use none, so they do not count.
     */
    public static Map<String, Integer> hostDepths(Map<String, ImageDef> defs) {
        return hostDepths(defs.values().stream().filter(d -> d.getProjectRoot() == null)
                .flatMap(d -> d.getRepos().stream()).toList());
    }

    static Map<String, Integer> hostDepths(List<ImageDef.RepoEntry> repos) {
        var depths = new HashMap<String, Integer>();
        var full = new HashSet<String>();
        for (var repo : repos) {
            if (repo == null || repo.getUrl() == null) continue;
            var url = GitRemoteUtils.normalizeGitUrl(repo.getUrl());
            if (repo.getDepth() == null) full.add(url);
            else depths.merge(url, repo.getDepth(), Math::max);
        }
        depths.keySet().removeAll(full);
        return depths;
    }

    /**
     * Marks a host checkout isx cloned shallow, with the depth it keeps. Only such a checkout has its
     * depth managed: one without the mark is the user's, shallow or not, and only ever plainly fetched.
     */
    static final String DEPTH_KEY = "isx.depth";

    private static Integer managedDepth(Path hostPath) {
        try {
            var value = GitRemoteUtils.hostGitExec(hostPath, "config", "--local", "--get", DEPTH_KEY);
            return value == null ? null : Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * {@code managedDepth} is the depth isx cloned the checkout with ({@link #DEPTH_KEY}), null for a
     * user's checkout; {@code depth} is the one the templates ask for now, null for all of history.
     */
    record FetchTask(String repoName, Path hostPath, String remoteName, Integer managedDepth, Integer depth) {
        GitRemoteUtils.ExecResult fetch(GitTransferConfig limits, Consumer<GitProcess.Progress> progress) {
            if (managedDepth == null) return transfer(limits, progress, "--", remoteName);
            if (depth != null) {
                var result = transfer(limits, progress, "--depth", depth.toString(), "--", remoteName);
                if (result.success() && !depth.equals(managedDepth)) git("config", DEPTH_KEY, depth.toString());
                return result;
            }
            // A template now wants all of history: make it the regular clone it would have been.
            // Unmarked last: a failure on the way leaves it marked, and the next refresh repeats
            // these steps, which are safe to repeat.
            var shallowState = GitRemoteUtils.hostGitExec(hostPath, "rev-parse", "--is-shallow-repository");
            if (shallowState == null) {
                return new GitRemoteUtils.ExecResult(false, "", "could not tell whether the checkout is shallow", null);
            }
            var shallow = "true".equals(shallowState);
            var result = shallow ? transfer(limits, progress, "--unshallow", "--", remoteName)
                    : transfer(limits, progress, "--", remoteName);
            if (result.success()) result = git("remote", "set-branches", remoteName, "*");
            if (result.success()) result = transfer(limits, progress, "--", remoteName);
            if (result.success()) result = git("config", "--unset", DEPTH_KEY);
            return result;
        }

        /** {@code git fetch --progress} with {@code args}, under {@code limits}. */
        private GitRemoteUtils.ExecResult transfer(GitTransferConfig limits, Consumer<GitProcess.Progress> progress,
                                                   String... args) {
            var fetch = new ArrayList<>(List.of("fetch", "--progress"));
            fetch.addAll(List.of(args));
            return GitRemoteUtils.hostGitTransfer(hostPath, limits, progress, fetch.toArray(String[]::new));
        }

        private GitRemoteUtils.ExecResult git(String... args) {
            var result = GitRemoteUtils.hostGitExecResult(hostPath, args);
            // A local step may fail without a word from Git: name it.
            return result.success() || result.reason() != null ? result
                    : new GitRemoteUtils.ExecResult(false, result.output(), "git " + args[0] + " failed", null);
        }
    }

    private record CloneTask(String repoName, String url, Path targetDir, Integer depth, String branch) {
        String[] cloneArgs() {
            var args = new ArrayList<>(List.of("clone", "--progress"));
            if (depth != null) args.addAll(List.of("--depth", depth.toString(), "-c", DEPTH_KEY + "=" + depth));
            if (branch != null) args.addAll(List.of("--branch", branch));
            args.addAll(List.of("--", url, targetDir.getFileName().toString()));
            return args.toArray(String[]::new);
        }
    }

    private enum ClonePolicy { ASK, ALWAYS, NEVER }
    private enum CloneAnswer { YES, NO, ALWAYS, NEVER }
}
