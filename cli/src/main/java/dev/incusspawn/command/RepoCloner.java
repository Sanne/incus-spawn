package dev.incusspawn.command;

import dev.incusspawn.Platform;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.CpuInfo;
import dev.incusspawn.util.TerminalProgress;
import dev.incusspawn.command.BuildProgress.StepProgress;
import dev.incusspawn.command.BuildProgress.StepState;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static dev.incusspawn.command.BuildProgress.combinedOutput;
import static dev.incusspawn.command.BuildProgress.formatStepLine;
import static dev.incusspawn.command.BuildProgress.lastNonEmptyLine;
import static dev.incusspawn.command.BuildProgress.plainStepLine;
import static dev.incusspawn.command.GuestProvisioning.expandHome;
import static dev.incusspawn.incus.Container.shellQuote;
import static dev.incusspawn.util.BuildOutput.BOLD;
import static dev.incusspawn.util.BuildOutput.styled;

/**
 * Clones a template's repos into the build instance, each from a host checkout where one is
 * available, and primes them.
 */
class RepoCloner {

    private final IncusClient incus;

    RepoCloner(IncusClient incus) {
        this.incus = incus;
    }

    // 4 WebSocket fds (stdin, stdout, stderr, control) + 1 /wait long-poll per non-PTY exec
    private static final int CONNECTIONS_PER_EXEC = 5;
    // Half the 48-connection valve, leaving room for concurrent TUI/status activity
    private static final int MACOS_TUNNEL_BUDGET = 24;

    /** A host checkout to clone a repo from: planned up front on the host, attached by the repo's worker. */
    record RepoReference(String deviceName, String containerPath, String source, String skipReason) {
        static RepoReference skipped(String reason) { return new RepoReference(null, null, null, reason); }
        boolean hasDevice() { return deviceName != null; }
    }

    /** How long a clone waits for its reference to be mounted in the guest (#1233). */
    static final int MOUNT_WAIT_SECONDS = 30;
    /** The exit status of {@code timeout} when a reference was not mounted in time. */
    private static final int MOUNT_TIMEOUT_EXIT = 124;

    /** The hotplug slots Incus gives a running VM; each attached reference takes one until detached. */
    static final int VM_HOTPLUG_SLOTS = 8;

    /**
     * The references a VM can hold attached at once. {@link #VM_HOTPLUG_SLOTS} is only where the
     * budget starts: whatever else is hot-plugged takes slots too, so an attach Incus refuses for
     * want of a slot retires one permit for good and waits for another, and only when the budget
     * would reach zero does the repo fall back to the network.
     */
    static final class HotplugSlots {
        private final java.util.concurrent.Semaphore permits;
        private final java.util.concurrent.atomic.AtomicInteger budget;

        HotplugSlots(int budget) {
            this.permits = new java.util.concurrent.Semaphore(budget);
            this.budget = new java.util.concurrent.atomic.AtomicInteger(budget);
        }

        void acquire() { permits.acquireUninterruptibly(); }

        void release() { permits.release(); }

        /** Retire the permit just taken, keeping it from ever being released; false if it is the last one. */
        boolean retire() {
            return budget.getAndUpdate(b -> b > 1 ? b - 1 : b) > 1;
        }
    }

    /** Attaches and detaches the host references of one {@link #cloneRepos} run. */
    interface ReferenceMounts {
        /** Attach {@code ref}, returning it, or a skipped reference saying why the repo clones from the network. */
        RepoReference attach(int idx, RepoReference ref);

        /** Detach an attached reference; false if it is still mounted. */
        boolean detach(int idx);

        ReferenceMounts NONE = new ReferenceMounts() {
            public RepoReference attach(int idx, RepoReference ref) { return ref; }
            public boolean detach(int idx) { return true; }
        };
    }

    /**
     * Each reference is attached under a hotplug slot, which stays taken until it is detached.
     * Attaches and detaches share one lock: a removal rewrites the whole device map, and a device
     * added meanwhile makes Incus refuse it (#1234). Its retries are bounded, so a burst of
     * parallel attaches could otherwise fail a detach.
     */
    private final class DeviceMounts implements ReferenceMounts {
        private final Container container;
        private final RepoReference[] refs;
        private final MachineType machineType;
        private final HotplugSlots slots;
        private final java.util.Set<Integer> attached = java.util.concurrent.ConcurrentHashMap.newKeySet();

        DeviceMounts(Container container, RepoReference[] refs, MachineType machineType, int planned) {
            this.container = container;
            this.refs = refs;
            this.machineType = machineType;
            this.slots = new HotplugSlots(machineType == MachineType.VM
                    ? Math.min(planned, VM_HOTPLUG_SLOTS) : Integer.MAX_VALUE);
        }

        public RepoReference attach(int idx, RepoReference ref) {
            var refArgs = new java.util.ArrayList<>(java.util.List.of(
                    "source=" + ref.source(), "path=" + ref.containerPath(), "readonly=true"));
            HostResourceSetup.addShiftIfSupported(refArgs, machineType);
            while (true) {
                slots.acquire();
                try {
                    synchronized (this) {
                        incus.deviceAdd(container.name(), ref.deviceName(), "disk", refArgs.toArray(String[]::new));
                        attached.add(idx);
                    }
                    return ref;
                } catch (Exception e) {
                    var noSlot = e instanceof IncusClient.NoHotplugSlotException;
                    if (noSlot && slots.retire()) continue;
                    slots.release();
                    return RepoReference.skipped(noSlot ? "no free PCI hotplug slot for the host reference"
                            : "could not mount the host reference: " + e.getMessage());
                }
            }
        }

        public synchronized boolean detach(int idx) {
            try {
                incus.deviceRemove(container.name(), refs[idx].deviceName());
                attached.remove(idx);
                return true;
            } catch (Exception e) {
                return false;
            } finally {
                // Even when the device stays: a permit never released could leave a waiting attach
                // stuck. The slot it still holds only makes a later attach fail and retire one.
                slots.release();
            }
        }

        /** Remove any reference a worker could not detach. */
        synchronized void removeLeftovers() {
            if (attached.isEmpty()) return;
            try {
                incus.devicesRemoveAll(container.name(),
                        attached.stream().map(idx -> refs[idx].deviceName()).toList());
            } catch (Exception e) {
                System.err.println("Warning: failed to remove reference device: " + e.getMessage());
            }
        }
    }

    /**
     * Clone git repos declared in the image definition as agentuser.
     *
     * <p>Repos are cloned concurrently (bounded to high-performance core count
     * and, on macOS, a vsock tunnel connection budget) with an animated
     * per-repo progress display. Each repo's
     * declared {@code prime} command runs in the same worker once that repo's
     * clone finishes <em>and</em> every host reference has been detached, so
     * priming still pipelines with the remaining network clones. A reference is
     * the host checkout's whole working tree (untracked files, unpushed work),
     * and a prime command is arbitrary code with network access, so no prime may
     * run while one is mounted (#765). Which repos have a reference is decided
     * up front on the host; each worker attaches its own reference just before
     * its clone and detaches it as soon as the clone is done, so a VM never
     * holds more references than it has PCI hotplug slots ({@link HotplugSlots},
     * #826). Attaches and detaches are serialized by a lock, because removing a
     * device rewrites the whole device map, and one added meanwhile makes Incus
     * refuse it (#1234): its retries are bounded, and parallel attaches could
     * exhaust them.
     * A failed detach fails the build rather than prime next to it.
     *
     * <p>Project-local definitions get no host references at all: their repos
     * are cloned from the network, since the reference would hand the host
     * checkout to prime commands the cloned repository itself controls.
     *
     * <p>When a matching host-side checkout is available (via SpawnConfig
     * host-path/repo-paths), the clone runs locally from the mounted reference
     * ({@code git clone --no-hardlinks}) instead of fetching from the remote.
     * This copies pack files directly — no repack or dissociation needed.  After
     * the local clone, the remote URL is fixed to the real origin and a
     * {@code git fetch} picks up any commits added since the last host refresh.
     */
    void cloneRepos(Container container, ImageDef imageDef, MachineType machineType) {
        var repos = imageDef.getRepos();
        if (repos.isEmpty()) return;

        try (var reposGroup = BuildOutput.group("Repositories", repos.size() + " to prepare")) {

            var config = SpawnConfig.load();

            // Phase 1 (serial, host only): find the host checkout each repo can clone from.
            var refs = new RepoReference[repos.size()];
            var projectLocal = imageDef.getProjectRoot() != null;
            for (int i = 0; i < repos.size(); i++) {
                refs[i] = projectLocal
                        ? RepoReference.skipped("project-local template, host checkouts are not shared")
                        : planReference(repos.get(i).getUrl(), config);
            }
            var planned = java.util.Arrays.stream(refs).filter(r -> r != null && r.hasDevice())
                    .map(RepoReference::containerPath).toArray(String[]::new);
            if (planned.length > 0) {
                try {
                    container.exec(java.util.stream.Stream.concat(java.util.stream.Stream.of("mkdir", "-p"),
                            java.util.Arrays.stream(planned)).toArray(String[]::new));
                } catch (Exception e) {
                    var skipped = RepoReference.skipped("could not mount the host reference: " + e.getMessage());
                    for (int i = 0; i < refs.length; i++) {
                        if (refs[i] != null && refs[i].hasDevice()) refs[i] = skipped;
                    }
                    planned = new String[0];
                }
            }
            // Counts planned references: each worker counts its own down once its attach
            // is resolved and anything it attached is detached.
            var referencesDetached = new java.util.concurrent.CountDownLatch(planned.length);
            var mounts = new DeviceMounts(container, refs, machineType, planned.length);

            // Phase 2 (parallel, bounded): clone each repo from its reference (local)
            // or the remote (fallback), restore the fetch refspec, then prime it —
            // all in one worker so priming starts as soon as that repo's clone
            // finishes rather than waiting at a barrier for the whole clone batch.
            var states = new AtomicReferenceArray<StepProgress>(repos.size());
            for (int i = 0; i < repos.size(); i++) {
                states.set(i, StepProgress.running("Cloning"));
            }
            int concurrency = repoConcurrency(repos.size());
            // Bounded here rather than by TerminalProgress: a worker waiting for the references to
            // be detached must not hold a slot, or the clones that detach them could never start.
            var limiter = new java.util.concurrent.Semaphore(concurrency);
            var failureSeen = new AtomicBoolean(false);
            try {
                TerminalProgress.run(repos.size(), repos.size(),
                        idx -> prepareOne(container, repos.get(idx), refs[idx], states, idx, failureSeen,
                                limiter, referencesDetached, mounts),
                        (idx, frame) -> formatStepLine(repoDisplayName(repos.get(idx)),
                                repos.get(idx).getUrl(), states.get(idx), frame, "Ready"),
                        idx -> plainStepLine(repoDisplayName(repos.get(idx)), states.get(idx), "Ready", "prepare"),
                        System.out::println);
            } finally {
                // Phase 3 (serial): remove any reference a worker could not detach.
                mounts.removeLeftovers();
            }

            assertNoStepFailures(repos, states, "prepare");
        }
    }

    /** Clone a repo and, on success, immediately prime it — recording progress/failure
     *  in {@code states[idx]} rather than throwing. {@code failureSeen} is a shared
     *  best-effort fail-fast flag: once any repo has failed the build will abort, so a
     *  clone that finishes afterwards skips its (potentially expensive) prime rather
     *  than doing work that will be thrown away. Primes already in flight run to
     *  completion — this only gates launching new ones. */
    void prepareOne(Container container, ImageDef.RepoEntry repo, RepoReference ref,
                    AtomicReferenceArray<StepProgress> states, int idx, AtomicBoolean failureSeen) {
        prepareOne(container, repo, ref, states, idx, failureSeen,
                new java.util.concurrent.Semaphore(1), new java.util.concurrent.CountDownLatch(0), ReferenceMounts.NONE);
    }

    /** As above, with the coordination {@link #cloneRepos} needs: {@code limiter} bounds the
     *  concurrent clones and primes, {@code mounts} attaches this repo's host reference just before
     *  its clone and detaches it once the clone is done, and no prime starts before
     *  {@code referencesDetached} reaches zero. */
    void prepareOne(Container container, ImageDef.RepoEntry repo, RepoReference planned,
                    AtomicReferenceArray<StepProgress> states, int idx, AtomicBoolean failureSeen,
                    java.util.concurrent.Semaphore limiter, java.util.concurrent.CountDownLatch referencesDetached,
                    ReferenceMounts mounts) {
        var clone = CloneResult.FAILED;
        var detachedOk = true;
        var ref = planned;
        var hasReference = planned != null && planned.hasDevice();
        try {
            limiter.acquireUninterruptibly();
            try {
                if (hasReference) ref = mounts.attach(idx, planned);
                clone = cloneOne(container, repo, ref, states, idx);
            } finally {
                limiter.release();
            }
        } finally {
            if (hasReference) {
                try {
                    if (ref.hasDevice()) detachedOk = mounts.detach(idx);
                    // Before the count-down, so a prime woken by it already sees the failure.
                    if (!detachedOk) failureSeen.set(true);
                } finally {
                    referencesDetached.countDown();
                }
            }
        }
        if (!detachedOk) {
            states.set(idx, StepProgress.failed("could not detach host reference " + ref.deviceName()
                    + "; refusing to run prime commands while it is mounted", null));
            return;
        }
        if (!clone.success()) {
            failureSeen.set(true);
            return; // failure already recorded in states[idx]
        }

        // Why the repo was cloned from the network rather than a reference, if one was meant to be used.
        var reason = clone.usedReference() ? null
                : clone.fallbackReason() != null ? clone.fallbackReason()
                : ref != null ? ref.skipReason() : null;
        String note = clone.usedReference() ? "via host reference" : reason;
        boolean highlight = reason != null;
        if (repo.hasPrime()) {
            if (referencesDetached.getCount() > 0) {
                states.set(idx, StepProgress.running("Waiting to prime"));
                awaitUninterruptibly(referencesDetached);
            }
            if (failureSeen.get()) {
                // Another repo already failed; don't start priming a build that's
                // going to abort. The clone itself succeeded, so say so.
                var skipNote = note == null ? "priming skipped" : note + "; priming skipped";
                states.set(idx, highlight ? StepProgress.doneHighlight(skipNote)
                        : StepProgress.done(skipNote));
                return;
            }
            states.set(idx, StepProgress.running("Priming"));
            boolean primed;
            limiter.acquireUninterruptibly();
            try {
                primed = primeOne(container, repo, states, idx);
            } finally {
                limiter.release();
            }
            if (!primed) {
                failureSeen.set(true);
                return; // failure recorded
            }
        }
        states.set(idx, highlight ? StepProgress.doneHighlight(note) : StepProgress.done(note));
    }

    /** How many repos clone or prime at once: high-performance cores, and on macOS the vsock tunnel budget. */
    int repoConcurrency(int repoCount) {
        int maxFromTunnel = Platform.isMacOS()
                ? MACOS_TUNNEL_BUDGET / CONNECTIONS_PER_EXEC
                : Integer.MAX_VALUE;
        return Math.min(repoCount, Math.min(CpuInfo.highPerfCores(), maxFromTunnel));
    }

    private static void awaitUninterruptibly(java.util.concurrent.CountDownLatch latch) {
        var interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** {@code fallbackReason} says why an attached reference was not cloned from, or is null. */
    private record CloneResult(boolean success, boolean usedReference, String fallbackReason) {
        static final CloneResult FAILED = new CloneResult(false, false, null);
    }

    /** Clone a single repo. On failure records it in {@code states[idx]} and returns
     *  {@link CloneResult#FAILED}; on success returns without setting a terminal state
     *  (the caller finalizes it once priming, if any, is done). */
    private CloneResult cloneOne(Container container, ImageDef.RepoEntry repo, RepoReference ref,
                                 AtomicReferenceArray<StepProgress> states, int idx) {
        try {
            boolean usedReference = false;
            String fallbackReason = null;

            if (ref != null && ref.hasDevice()) {
                var expandedPath = expandHome(repo.getPath());
                var clone = container.shAsUser("agentuser",
                        awaitMount(ref.containerPath()) + " && " + buildCloneCommand(repo, ref.containerPath()));
                var last = clone;
                if (clone.success()) {
                    // Point origin at the real remote, fetch current refs, and
                    // detect the remote's default branch.  Objects are already
                    // local (copied from the reference's pack files), so only ref
                    // advertisements travel the network.  set-head --auto is needed
                    // because the local clone inherits HEAD from the host checkout,
                    // which may be on a different branch than the remote's default.
                    var clonePath = shellQuote(expandedPath);
                    var fixup = container.shAsUser("agentuser",
                            "git -C " + clonePath + " remote set-url origin " + shellQuote(repo.getUrl())
                                    + " && git -C " + clonePath + " fetch --quiet origin"
                                    + " && git -C " + clonePath + " remote set-head origin --auto");
                    last = fixup;
                    if (fixup.success()) {
                        String resolveExpr;
                        if (repo.getBranch() != null && !repo.getBranch().isBlank()) {
                            resolveExpr = "b=" + shellQuote(repo.getBranch());
                        } else {
                            resolveExpr = "b=$(git -C " + clonePath
                                    + " for-each-ref --format='%(symref:lstrip=3)' refs/remotes/origin/HEAD)";
                        }
                        var checkout = container.shAsUser("agentuser",
                                resolveExpr + " && git -C " + clonePath
                                        + " checkout -B \"$b\" --track \"origin/$b\"");
                        usedReference = checkout.success();
                        last = checkout;
                    }
                }
                if (!usedReference) {
                    var error = last.exitCode() == MOUNT_TIMEOUT_EXIT && last == clone
                            ? "not mounted in the guest within " + MOUNT_WAIT_SECONDS + "s" : gitError(last);
                    fallbackReason = "could not use the host reference" + (error.isEmpty() ? "" : ": " + error);
                    container.shAsUser("agentuser", "rm -rf " + shellQuote(expandedPath));
                }
            }

            if (!usedReference) {
                var clone = container.shAsUser("agentuser", buildCloneCommand(repo, null));
                if (!clone.success()) {
                    states.set(idx, StepProgress.failed(gitError(clone), combinedOutput(clone)));
                    return CloneResult.FAILED;
                }

                // Widen the fetch refspec that --single-branch narrowed, so the
                // clone behaves like a regular one.  The local-clone path doesn't
                // use --single-branch, so it skips this.
                var repoPath = shellQuote(expandHome(repo.getPath()));
                var restore = container.shAsUser("agentuser",
                        "git -C " + repoPath + " remote set-branches origin '*'");
                if (!restore.success()) {
                    states.set(idx, StepProgress.failed(gitError(restore), combinedOutput(restore)));
                    return CloneResult.FAILED;
                }
            }

            return new CloneResult(true, usedReference, fallbackReason);
        } catch (Exception e) {
            states.set(idx, StepProgress.failed(e.getMessage(), null));
            return CloneResult.FAILED;
        }
    }

    /** Run a repo's prime command. Returns true on success; on failure records it in
     *  {@code states[idx]} and returns false. */
    private boolean primeOne(Container container, ImageDef.RepoEntry repo,
                             AtomicReferenceArray<StepProgress> states, int idx) {
        try {
            var expanded = expandHome(repo.getPath());
            var result = container.shAsUser("agentuser",
                    "cd " + shellQuote(expanded) + " && " + repo.getPrime());
            if (result.success()) return true;
            // Prime output is not git, so use the last meaningful line for the
            // concise inline label; the full log is preserved and printed on failure.
            var combined = combinedOutput(result);
            states.set(idx, StepProgress.failed(lastNonEmptyLine(combined), combined));
            return false;
        } catch (Exception e) {
            states.set(idx, StepProgress.failed(e.getMessage(), null));
            return false;
        }
    }

    private static void assertNoStepFailures(List<ImageDef.RepoEntry> repos,
                                             AtomicReferenceArray<StepProgress> states, String verb) {
        var errors = new ArrayList<String>();
        var output = new StringBuilder();
        for (int i = 0; i < repos.size(); i++) {
            var progress = states.get(i);
            // Require an explicit DONE: a step left RUNNING or unset (e.g. a task that
            // returned without recording a result) is a failure, not a silent success.
            if (progress != null && progress.state() == StepState.DONE) continue;

            var name = repoDisplayName(repos.get(i));
            var detail = progress == null || progress.detail() == null || progress.detail().isEmpty()
                    ? verb + " failed" : progress.detail();
            errors.add(name + ": " + detail);

            // Collect the full captured output for the failure report and print it
            // to stderr. Done here, after the animated batch, to avoid interleaving
            // multi-line logs with the live progress display.
            if (progress != null && progress.log() != null && !progress.log().isBlank()) {
                var header = "─── " + verb + " output: " + name + " ───";
                var footer = "─── end " + verb + " output: " + name + " ───";
                System.err.println(styled(BOLD, header));
                System.err.println(progress.log().strip());
                System.err.println(styled(BOLD, footer));
                output.append(header).append('\n');
                output.append(progress.log().strip()).append('\n');
                output.append(footer).append("\n\n");
            }
        }
        if (!errors.isEmpty()) {
            throw (IncusException) new IncusException("Failed to " + verb + " " + errors.size()
                    + " repo(s):\n  " + String.join("\n  ", errors))
                    .withStepOutput(output.toString());
        }
    }

    private static String repoDisplayName(ImageDef.RepoEntry repo) {
        var name = GitRemoteUtils.repoNameFromUrl(repo.getUrl());
        return name.isEmpty() ? repo.getUrl() : name;
    }

    /** Extract a concise error line from a failed git exec (stderr first, then stdout). */
    private static String gitError(IncusClient.ExecResult result) {
        var err = firstGitError(result.stderr());
        return !err.isEmpty() ? err : firstGitError(result.stdout());
    }

    /** First fatal:/error: line from git output, else the last non-empty line. */
    static String firstGitError(String text) {
        if (text == null || text.isEmpty()) return "";
        String lastNonEmpty = "";
        for (var line : text.split("\n")) {
            var trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("fatal:") || trimmed.startsWith("error:")) return trimmed;
            lastNonEmpty = trimmed;
        }
        return lastNonEmpty;
    }

    /**
     * Shell that waits until {@code path} is a mount point, exiting {@link #MOUNT_TIMEOUT_EXIT} after
     * {@link #MOUNT_WAIT_SECONDS}. A VM's incus-agent mounts a hot-plugged share some time after
     * Incus has added the device, so a clone started at once found an empty directory (#1233); a
     * container's disk is mounted by the add, so there the loop ends at its first check. Run in the
     * clone's own exec, it costs no request.
     */
    static String awaitMount(String path) {
        return awaitMount(path, MOUNT_WAIT_SECONDS);
    }

    /** As above; {@code timeout} (monotonic, and exiting 124) bounds the loop, and a guest without
     *  {@code mountpoint} cannot be waited on, so it is not: the clone runs at once, as it always did. */
    static String awaitMount(String path, int seconds) {
        return "{ ! command -v mountpoint >/dev/null 2>&1 || timeout " + seconds
                + " sh -c 'until mountpoint -q \"$1\"; do sleep 0.1; done' sh " + shellQuote(path) + "; }";
    }

    private static String buildCloneCommand(ImageDef.RepoEntry repo, String referencePath) {
        var cmd = new StringBuilder("git clone");
        if (referencePath != null) {
            // Local clone from mounted host reference — copies pack files
            // directly.  Branch checkout is handled separately after the
            // remote URL is fixed up and a fetch supplies current refs.
            cmd.append(" --no-hardlinks");
        } else {
            cmd.append(" --single-branch");
            if (repo.getBranch() != null && !repo.getBranch().isBlank()) {
                cmd.append(" --branch ").append(shellQuote(repo.getBranch()));
            }
        }
        cmd.append(" -- ").append(shellQuote(referencePath != null ? referencePath : repo.getUrl()));
        cmd.append(" ").append(shellQuote(expandHome(repo.getPath())));
        return cmd.toString();
    }

    /** The host checkout {@code cloneUrl} can clone from, decided on the host alone: a planned
     *  reference, a skipped one saying why there is none, or null where none is configured. */
    RepoReference planReference(String cloneUrl, SpawnConfig config) {
        try {
            var repoName = GitRemoteUtils.repoNameFromUrl(cloneUrl);
            if (repoName.isEmpty()) return null;

            var hostPath = GitRemoteUtils.resolveHostRepoPath(repoName, config);
            if (hostPath == null) return null;
            if (!Files.isDirectory(hostPath) || !GitRemoteUtils.isGitRepo(hostPath)
                    || !GitRemoteUtils.anyRemoteMatches(hostPath, cloneUrl)) {
                return RepoReference.skipped("no local reference found to speedup cloning");
            }

            return new RepoReference(GitRemoteUtils.referenceDeviceName(repoName, cloneUrl),
                    GitRemoteUtils.referenceContainerPath(repoName, cloneUrl),
                    HostResourceSetup.translateForVm(hostPath.toString()), null);
        } catch (Exception e) {
            System.err.println("Warning: could not set up repo reference: " + e.getMessage());
            return null;
        }
    }
}
