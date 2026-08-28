package dev.incusspawn.git;

import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.TerminalProgress;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

public final class HostRepoRefresh {

    private HostRepoRefresh() {}

    enum FetchState { FETCHING, DONE, FAILED }
    record TaskProgress(FetchState state, String detail) {}

    public static void refresh(List<ImageDef.RepoEntry> repos, SpawnConfig config,
                               boolean cloneMissing, boolean autoConfirm,
                               Consumer<String> output) {
        if (repos.isEmpty()) return;
        if (config.getHostPaths().isEmpty() && config.getRepoPaths().isEmpty()) return;

        var deduplicated = deduplicateByUrl(repos);
        var toFetch = new ArrayList<FetchTask>();
        var toClone = new ArrayList<CloneTask>();

        for (var repo : deduplicated) {
            var repoName = GitRemoteUtils.repoNameFromUrl(repo.getUrl());
            if (repoName.isEmpty()) continue;

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
                    toFetch.add(new FetchTask(repoName, hostPath, remoteName));
                } else {
                    BuildOutput.note("Host repo " + hostPath + " has no remote matching " + repo.getUrl() + ", skipping.");
                }
            } else if (cloneMissing) {
                var targetDir = resolveCloneTarget(repoName, config);
                if (targetDir != null) {
                    toClone.add(new CloneTask(repoName, repo.getUrl(), targetDir));
                }
            }
        }

        if (toFetch.isEmpty() && toClone.isEmpty()) return;

        if (!toFetch.isEmpty()) {
            BuildOutput.step("Refreshing " + toFetch.size() + " host " + (toFetch.size() == 1 ? "repo:" : "repos:"));
            fetchInParallel(toFetch, output);
        }

        if (!toClone.isEmpty()) {
            cloneSequentially(toClone, config, autoConfirm);
        }
    }

    private static void fetchInParallel(List<FetchTask> tasks, Consumer<String> output) {
        var states = new AtomicReferenceArray<TaskProgress>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            states.set(i, new TaskProgress(FetchState.FETCHING, null));
        }

        // Fetches are lightweight; run them all at once (no concurrency cap).
        TerminalProgress.run(tasks.size(), tasks.size(),
                idx -> {
                    var task = tasks.get(idx);
                    try {
                        var result = GitRemoteUtils.hostGitExecResult(task.hostPath(), "fetch", "--", task.remoteName());
                        if (result.success()) {
                            states.set(idx, new TaskProgress(FetchState.DONE, null));
                        } else {
                            states.set(idx, new TaskProgress(FetchState.FAILED, extractGitError(result.output())));
                        }
                    } catch (Exception e) {
                        states.set(idx, new TaskProgress(FetchState.FAILED, e.getMessage()));
                    }
                },
                (idx, frame) -> formatFetchLine(tasks.get(idx), states.get(idx), frame),
                idx -> plainFetchLine(tasks.get(idx), states.get(idx)),
                output);
    }

    static String formatFetchLine(FetchTask task, TaskProgress progress, int frame) {
        var sb = new StringBuilder(BuildOutput.STEP_INDENT);
        switch (progress.state()) {
            case FETCHING -> sb.append(TerminalProgress.SPINNER[frame % TerminalProgress.SPINNER.length])
                    .append(" \033[2mFetching\033[0m ");
            case DONE     -> sb.append("\033[32m✓\033[0m Fetched  ");
            case FAILED   -> sb.append("\033[31m✗\033[0m \033[31mFailed\033[0m   ");
        }
        sb.append(' ');
        sb.append(task.repoName());
        sb.append(" \033[2m(").append(task.hostPath()).append(")\033[0m");
        if (progress.state() == FetchState.FAILED && progress.detail() != null && !progress.detail().isEmpty()) {
            sb.append("  \033[31m").append(progress.detail()).append("\033[0m");
        }
        return sb.toString();
    }

    private static String plainFetchLine(FetchTask task, TaskProgress progress) {
        if (progress.state() == FetchState.DONE) {
            return BuildOutput.STEP_INDENT + "Fetched " + task.repoName() + " (" + task.hostPath() + ")";
        }
        var msg = BuildOutput.STEP_INDENT + "Warning: fetch failed for " + task.repoName() + " at " + task.hostPath();
        if (progress.detail() != null && !progress.detail().isEmpty()) {
            msg += ": " + progress.detail();
        }
        return msg;
    }

    private static void cloneSequentially(List<CloneTask> tasks, SpawnConfig config,
                                          boolean autoConfirm) {
        var policy = resolvePolicy(config);

        if (policy == ClonePolicy.ASK && (autoConfirm || System.console() == null)) {
            BuildOutput.note("Skipping clone of " + tasks.size() + " repo(s) — set auto-clone-repos: always in config.yaml to clone automatically.");
            return;
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
            var result = GitRemoteUtils.hostGitExecResult(task.targetDir.getParent(),
                    "clone", "--", task.url, task.targetDir.getFileName().toString());
            if (result.success()) {
                BuildOutput.stepDone();
            } else {
                BuildOutput.stepBreak();
                var detail = extractGitError(result.output());
                var msg = "Warning: clone failed for " + task.url;
                if (!detail.isEmpty()) msg += ": " + detail;
                System.err.println(BuildOutput.STEP_INDENT + msg);
            }
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
            var normalized = GitRemoteUtils.normalizeGitUrl(repo.getUrl());
            seen.putIfAbsent(normalized, repo);
        }
        return new ArrayList<>(seen.values());
    }

    public static List<ImageDef.RepoEntry> collectAllRepos(ImageDef imageDef, Map<String, ImageDef> defs) {
        var repos = new ArrayList<>(imageDef.getRepos());
        for (var ancestor : ImageDef.ancestors(imageDef, defs)) {
            repos.addAll(ancestor.getRepos());
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

    static String extractGitError(String text) {
        if (text == null || text.isEmpty()) return "";
        for (var line : text.split("\n")) {
            var trimmed = line.strip();
            if (trimmed.startsWith("fatal:") || trimmed.startsWith("error:")) {
                return trimmed;
            }
        }
        var lines = text.strip().split("\n");
        return lines[lines.length - 1].strip();
    }

    record FetchTask(String repoName, Path hostPath, String remoteName) {}
    private record CloneTask(String repoName, String url, Path targetDir) {}

    private enum ClonePolicy { ASK, ALWAYS, NEVER }
    private enum CloneAnswer { YES, NO, ALWAYS, NEVER }
}
