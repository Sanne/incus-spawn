package dev.incusspawn.git;

import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

public final class HostRepoRefresh {

    private HostRepoRefresh() {}

    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    private enum FetchState { FETCHING, DONE, FAILED }
    private record TaskProgress(FetchState state, String detail) {}

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
                output.accept("Warning: " + e.getMessage());
                continue;
            }

            if (hostPath != null && Files.isDirectory(hostPath) && GitRemoteUtils.isGitRepo(hostPath)) {
                var remoteName = GitRemoteUtils.matchingRemoteName(hostPath, repo.getUrl());
                if (remoteName != null) {
                    toFetch.add(new FetchTask(repoName, hostPath, remoteName));
                } else {
                    output.accept("  Host repo " + hostPath + " has no remote matching " + repo.getUrl() + ", skipping refresh");
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
            output.accept("Refreshing " + toFetch.size() + " host repo(s)...");
            fetchInParallel(toFetch, output);
        }

        if (!toClone.isEmpty()) {
            cloneSequentially(toClone, config, autoConfirm, output);
        }
    }

    private static void fetchInParallel(List<FetchTask> tasks, Consumer<String> output) {
        if (isAnsiTerminal()) {
            fetchAnimated(tasks);
        } else {
            fetchPlain(tasks, output);
        }
    }

    private static boolean isAnsiTerminal() {
        if (System.console() == null) return false;
        var term = System.getenv("TERM");
        return term != null && !term.equals("dumb");
    }

    private static void fetchAnimated(List<FetchTask> tasks) {
        var out = System.out;
        var states = new AtomicReferenceArray<TaskProgress>(tasks.size());

        for (int i = 0; i < tasks.size(); i++) {
            states.set(i, new TaskProgress(FetchState.FETCHING, null));
            out.println(formatFetchLine(tasks.get(i), states.get(i), 0));
        }
        out.flush();

        var lock = new Object();
        var frame = new int[]{0};
        Runnable redraw = () -> {
            synchronized (lock) {
                redrawLines(tasks, states, out, frame[0]++);
            }
        };

        var ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "fetch-progress");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(redraw, 80, 80, TimeUnit.MILLISECONDS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks.size(); i++) {
                int idx = i;
                var task = tasks.get(i);
                executor.submit(() -> {
                    try {
                        var result = GitRemoteUtils.hostGitExecResult(task.hostPath, "fetch", "--", task.remoteName);
                        if (result.success()) {
                            states.set(idx, new TaskProgress(FetchState.DONE, null));
                        } else {
                            states.set(idx, new TaskProgress(FetchState.FAILED, extractGitError(result.output())));
                        }
                    } catch (Exception e) {
                        states.set(idx, new TaskProgress(FetchState.FAILED, e.getMessage()));
                    }
                });
            }
        }

        ticker.shutdownNow();
        try { ticker.awaitTermination(200, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        synchronized (lock) {
            redrawLines(tasks, states, out, 0);
        }
    }

    private static void redrawLines(List<FetchTask> tasks, AtomicReferenceArray<TaskProgress> states,
                                    PrintStream out, int frame) {
        var sb = new StringBuilder();
        sb.append("\033[").append(tasks.size()).append('A');
        for (int i = 0; i < tasks.size(); i++) {
            sb.append('\r').append("\033[2K");
            sb.append(formatFetchLine(tasks.get(i), states.get(i), frame));
            sb.append('\n');
        }
        out.print(sb);
        out.flush();
    }

    private static String formatFetchLine(FetchTask task, TaskProgress progress, int frame) {
        var sb = new StringBuilder("  ");
        switch (progress.state()) {
            case FETCHING -> sb.append(SPINNER[frame % SPINNER.length]).append(" \033[2mFetching\033[0m ");
            case DONE     -> sb.append("\033[32m✓\033[0m Fetched  ");
            case FAILED   -> sb.append("\033[31m✗\033[0m \033[31mFailed\033[0m   ");
        }
        sb.append(' ');
        sb.append(task.repoName);
        sb.append(" \033[2m(").append(task.hostPath).append(")\033[0m");
        if (progress.state() == FetchState.FAILED && progress.detail() != null && !progress.detail().isEmpty()) {
            sb.append("  \033[31m").append(progress.detail()).append("\033[0m");
        }
        return sb.toString();
    }

    private static void fetchPlain(List<FetchTask> tasks, Consumer<String> output) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = tasks.stream()
                    .map(task -> executor.submit(() -> {
                        try {
                            var result = GitRemoteUtils.hostGitExecResult(task.hostPath, "fetch", "--", task.remoteName);
                            if (result.success()) {
                                output.accept("  Fetched " + task.repoName + " (" + task.hostPath + ")");
                            } else {
                                var detail = extractGitError(result.output());
                                var msg = "  Warning: fetch failed for " + task.repoName + " at " + task.hostPath;
                                if (!detail.isEmpty()) msg += ": " + detail;
                                output.accept(msg);
                            }
                        } catch (Exception e) {
                            output.accept("  Warning: fetch failed for " + task.repoName + " at " + task.hostPath + ": " + e);
                        }
                    }))
                    .toList();
            for (var future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    // logged inside the task
                }
            }
        }
    }

    private static void cloneSequentially(List<CloneTask> tasks, SpawnConfig config,
                                          boolean autoConfirm, Consumer<String> output) {
        var policy = resolvePolicy(config);

        if (policy == ClonePolicy.ASK && (autoConfirm || System.console() == null)) {
            output.accept("  Skipping clone of " + tasks.size() + " repo(s) — set auto-clone-repos: always in config.yaml to clone automatically");
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
                        output.accept("  Saved preference: auto-clone-repos: always");
                    }
                    case NEVER -> {
                        policy = ClonePolicy.NEVER;
                        config.setAutoCloneRepos("never");
                        config.save();
                        output.accept("  Saved preference: auto-clone-repos: never");
                        continue;
                    }
                }
            }

            output.accept("  Cloning " + task.url + " into " + task.targetDir + "...");
            var result = GitRemoteUtils.hostGitExecResult(task.targetDir.getParent(),
                    "clone", "--", task.url, task.targetDir.getFileName().toString());
            if (result.success()) {
                output.accept("  Cloned " + task.repoName);
            } else {
                var detail = extractGitError(result.output());
                var msg = "  Warning: clone failed for " + task.url;
                if (!detail.isEmpty()) msg += ": " + detail;
                output.accept(msg);
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

    private record FetchTask(String repoName, Path hostPath, String remoteName) {}
    private record CloneTask(String repoName, String url, Path targetDir) {}

    private enum ClonePolicy { ASK, ALWAYS, NEVER }
    private enum CloneAnswer { YES, NO, ALWAYS, NEVER }
}
