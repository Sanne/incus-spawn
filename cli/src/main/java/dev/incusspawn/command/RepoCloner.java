package dev.incusspawn.command;

import dev.incusspawn.Platform;
import dev.incusspawn.config.HostResourceSetup;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.git.GitCommandException;
import dev.incusspawn.git.GitPreparation;
import dev.incusspawn.git.HostRepoSources;
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
 * Prepares and clones a template's repositories, then primes them after raw host references are detached.
 */
class RepoCloner {

    private final IncusClient incus;
    private GitPreparation preparation;
    private final java.util.Map<Integer, GitPreparation.Prepared> prepared = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, String> preparationTimings = new java.util.concurrent.ConcurrentHashMap<>();
    /** By repo: why its Git preparation failed, isx's words then Git's quoted, for a warning after the group. */
    final java.util.Map<Integer, GitCommandException> preparationFailures = new java.util.concurrent.ConcurrentHashMap<>();

    RepoCloner(IncusClient incus) {
        this.incus = incus;
    }

    RepoCloner(IncusClient incus, GitPreparation preparation) {
        this(incus);
        this.preparation = preparation;
    }

    // 4 WebSocket fds (stdin, stdout, stderr, control) + 1 /wait long-poll per non-PTY exec
    private static final int CONNECTIONS_PER_EXEC = 5;
    // Half the 48-connection valve, leaving room for concurrent TUI/status activity
    private static final int MACOS_TUNNEL_BUDGET = 24;

    /** A preparation request, prepared repository, or shallow checkout, attached by the repo's worker. */
    record RepoReference(String deviceName, String containerPath, String source, String skipReason,
                         GitPreparation.Request request, GitPreparation.Prepared prepared) {
        RepoReference(String deviceName, String containerPath, String source, String skipReason) {
            this(deviceName, containerPath, source, skipReason, null, null);
        }
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
                for (int idx : attached) releasePrepared(idx);
                attached.clear();
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
     * clone finishes <em>and</em> every raw shallow reference has been detached, so
     * priming pipelines with other clean preparations and network clones. A shallow reference is
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
     * <p>Full clones mount only a standalone repository prepared from the declared source's authorized refs.
     * Shallow clones retain the transport/fixup path from a discovered checkout.
     */
    void cloneRepos(Container container, ImageDef imageDef, java.util.Map<String, ImageDef> defs,
                    MachineType machineType) {
        // Only the destinations this layer declares first in its chain (#1394): one an ancestor
        // declared is already in the copy, or was cloned by that ancestor's layer in a build from
        // scratch, and a repeat within the template is the same clone.
        var declared = imageDef.getRepos().stream().map(ImageDef::repoDestination).collect(java.util.stream.Collectors.toSet());
        var repos = new ArrayList<ImageDef.RepoEntry>();
        for (var resolved : ImageDef.chainRepos(imageDef, defs)) {
            if (java.util.Objects.equals(resolved.template(), imageDef.getName())) {
                repos.add(resolved.repo());
            } else if (declared.contains(ImageDef.repoDestination(resolved.repo()))) {
                BuildOutput.ok("Inherited " + repoDisplayName(resolved.repo()) + " (from " + resolved.template() + ")");
            }
        }
        if (repos.isEmpty()) return;
        preparationTimings.clear();
        preparationFailures.clear();

        try (var reposGroup = BuildOutput.group("Repositories", repos.size() + " to prepare")) {

            var config = SpawnConfig.load();

            // Phase 1 (serial, host only): find the host checkout each repo can clone from.
            var refs = new RepoReference[repos.size()];
            var projectLocal = imageDef.getProjectRoot() != null;
            var deviceNames = new java.util.HashSet<String>();
            var hostSources = new java.util.LinkedHashMap<String, String>();
            for (int i = 0; i < repos.size(); i++) {
                var repo = repos.get(i);
                if (repo.sourceProblem() != null) throw new IncusException(repo.sourceProblem());
                if (repo.isHostSource()) {
                    if (projectLocal) throw new IncusException("project-local templates cannot prepare host repositories");
                    try {
                        var source = repo.hostSourcePath().toRealPath();
                        hostSources.put(ImageDef.repoDestination(repo), source.toString());
                        refs[i] = new RepoReference("ref-host-" + i, "/mnt/isx-host-repo-" + i,
                                source.toString(), null);
                    } catch (java.io.IOException e) {
                        throw new IncusException("Cannot read host-source " + repo.getHostSource() + ": " + e.getMessage());
                    }
                } else refs[i] = projectLocal
                        ? RepoReference.skipped("project-local template, host checkouts are not shared")
                        : planReference(repos.get(i).getUrl(), config);
                // Shallow selection keeps the established transport/fixup path, with no host fetch.
                if (repos.get(i).getDepth() != null && refs[i] != null && refs[i].request() != null) {
                    var r = refs[i];
                    refs[i] = r.request().checkout() == null ? null
                            : new RepoReference(r.deviceName(), r.containerPath(),
                                    HostResourceSetup.translateForVm(r.request().checkout().toString()), null);
                }
                if (refs[i] != null && refs[i].hasDevice() && !deviceNames.add(refs[i].deviceName())) {
                    // Two entries for one URL can prepare different revisions concurrently.
                    // They must not PATCH the same device or remove each other's mount.
                    var r = refs[i];
                    refs[i] = new RepoReference(r.deviceName() + "-" + i, r.containerPath() + "-" + i,
                            r.source(), r.skipReason(), r.request(), r.prepared());
                }
            }
            if (!hostSources.isEmpty() || java.util.Arrays.stream(refs).anyMatch(r -> r != null && r.request() != null)) {
                try {
                    preparation = new GitPreparation(config.gitCache(), config.gitTransfer());
                } catch (Exception e) {
                    if (!hostSources.isEmpty()) throw new IncusException("Host repository preparation unavailable: " + e.getMessage());
                    for (int i = 0; i < refs.length; i++) {
                        if (refs[i] != null && refs[i].request() != null) {
                            refs[i] = RepoReference.skipped("Git preparation unavailable: " + e.getMessage());
                        }
                    }
                }
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
            // Only raw shallow checkouts expose host-private objects. Their gate includes planned
            // mounts, so a prime cannot race a shallow worker that has not attached its checkout yet.
            int rawReferences = (int) java.util.stream.IntStream.range(0, refs.length)
                    .filter(i -> !repos.get(i).isHostSource() && rawReference(refs[i])).count();
            var referencesDetached = new java.util.concurrent.CountDownLatch(rawReferences);
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
            // Complete guest authorization before any untrusted prime can alter the guest's Git/tools.
            // Only these short remote checks synchronize; slow host preparation never gates other primes.
            var accessChecked = new java.util.concurrent.CountDownLatch(repos.size());
            try {
                TerminalProgress.run(repos.size(), repos.size(),
                        idx -> prepareOne(container, repos.get(idx), refs[idx], states, idx, failureSeen,
                                limiter, referencesDetached, mounts, accessChecked),
                        (idx, frame) -> formatStepLine(repoDisplayName(repos.get(idx)),
                                repos.get(idx).sourceDescription(), states.get(idx), frame, "Ready"),
                        idx -> plainStepLine(repoDisplayName(repos.get(idx)), states.get(idx), "Ready", "prepare"),
                        System.out::println);
            } finally {
                // Phase 3 (serial): remove any reference a worker could not detach.
                mounts.removeLeftovers();
            }

            preparationTimings.forEach((idx, timing) -> BuildOutput.note(repoDisplayName(repos.get(idx)) + ": " + timing));
            // isx's words, then Git's quoted: the step lines hold one line each.
            preparationFailures.forEach((idx, failed) -> BuildOutput.warn(repoDisplayName(repos.get(idx))
                    + ": " + failed.getMessage() + (repos.get(idx).isHostSource() ? "." : "; falling back to a clone in the guest."),
                    failed.details().toArray(String[]::new)));

            assertNoStepFailures(repos, states, "prepare");
            if (!hostSources.isEmpty()) HostRepoSources.record(incus, container.name(), hostSources);
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
        prepareOne(container, repo, planned, states, idx, failureSeen, limiter, referencesDetached, mounts,
                new java.util.concurrent.CountDownLatch(1));
    }

    private void prepareOne(Container container, ImageDef.RepoEntry repo, RepoReference planned,
                            AtomicReferenceArray<StepProgress> states, int idx, AtomicBoolean failureSeen,
                            java.util.concurrent.Semaphore limiter, java.util.concurrent.CountDownLatch referencesDetached,
                            ReferenceMounts mounts, java.util.concurrent.CountDownLatch accessChecked) {
        var clone = CloneResult.FAILED;
        var detachedOk = true;
        var ref = planned;
        var hasReference = planned != null && planned.hasDevice();
        String advertisement = null;
        try {
            if (hasReference && planned.request() != null) {
                limiter.acquireUninterruptibly();
                try {
                    advertisement = checkTemplateAccess(container, repo, states, idx);
                    if (advertisement == null) ref = RepoReference.skipped("template account could not access the repository");
                } catch (Exception e) {
                    ref = RepoReference.skipped("could not check template repository access");
                } finally { limiter.release(); }
            }
        } finally { accessChecked.countDown(); }
        awaitUninterruptibly(accessChecked);
        try {
            limiter.acquireUninterruptibly();
            try {
                if (repo.isHostSource() && hasReference && ref.hasDevice()) {
                    ref = prepareHostReference(repo, planned, states, idx);
                    if (ref.prepared() != null) prepared.put(idx, ref.prepared());
                } else if (hasReference && ref.hasDevice() && planned.request() != null) {
                    ref = prepareReference(repo, planned, states, idx, advertisement);
                    if (ref.prepared() != null) prepared.put(idx, ref.prepared());
                }
                long attaching = System.nanoTime();
                if (hasReference && ref.hasDevice()) ref = mounts.attach(idx, ref);
                long attachMillis = (System.nanoTime() - attaching) / 1_000_000;
                long start = System.nanoTime();
                // Never leave a preparation phase or its frozen progress up while the guest clones.
                states.set(idx, StepProgress.running("Cloning"));
                clone = cloneOne(container, repo, ref, states, idx);
                var snapshot = prepared.get(idx);
                preparationTimings.merge(idx, (snapshot == null ? "" : snapshot.timingSummary() + ", ")
                        + "attach=" + attachMillis + "ms, guest=" + (System.nanoTime() - start) / 1_000_000 + "ms",
                        (a, b) -> a + ", " + b);
            } finally {
                limiter.release();
            }
        } finally {
            if (hasReference) {
                try {
                    long detaching = System.nanoTime();
                    if (ref.hasDevice()) detachedOk = mounts.detach(idx);
                    preparationTimings.merge(idx,
                            "detach=" + (System.nanoTime() - detaching) / 1_000_000 + "ms", (a, b) -> a + ", " + b);
                    if (detachedOk) releasePrepared(idx);
                    // Before the count-down, so a prime woken by it already sees the failure.
                    if (!detachedOk) failureSeen.set(true);
                } finally {
                    if (!repo.isHostSource() && rawReference(planned)) referencesDetached.countDown();
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
        String note = clone.usedReference()
                ? ref.prepared() == null ? "via host reference (shallow, depth " + repo.getDepth() + ")"
                    : "via " + ref.prepared().strategy()
                : reason != null ? reason : repo.getDepth() != null ? "via remote (shallow, depth " + repo.getDepth() + ")" : null;
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

    private static boolean rawReference(RepoReference ref) {
        return ref != null && ref.hasDevice() && ref.request() == null && ref.prepared() == null;
    }

    String checkTemplateAccess(Container container, ImageDef.RepoEntry repo,
                                   AtomicReferenceArray<StepProgress> states, int idx) {
        states.set(idx, StepProgress.running("Checking template repository access"));
        long start = System.nanoTime();
        var access = container.shAsUser("agentuser", "export GIT_TERMINAL_PROMPT=0; "
                + "if [ -z \"${GIT_SSH_COMMAND+x}${GIT_SSH+x}\" ]; then export GIT_SSH_COMMAND='ssh -o BatchMode=yes'; fi; "
                + "git -c protocol.allow=never -c protocol.https.allow=always -c protocol.ssh.allow=always"
                + " -c protocol.git.allow=always -c protocol.file.allow=never ls-remote --symref -- "
                + shellQuote(repo.getUrl()) + " HEAD 'refs/heads/*' 'refs/tags/*'");
        preparationTimings.put(idx, "access=" + (System.nanoTime() - start) / 1_000_000 + "ms");
        return access.success() ? access.stdout() : null;
    }

    RepoReference prepareReference(ImageDef.RepoEntry repo, RepoReference ref,
                                   AtomicReferenceArray<StepProgress> states, int idx, String advertisement) {
        try {
            var request = new GitPreparation.Request(repo.getUrl(), repo.getBranch(), ref.request().checkout());
            var result = preparation.prepare(request, new GitPreparation.Phases() {
                @Override public void accept(String phase) { states.set(idx, StepProgress.running(phase)); }
                // Git's progress goes after the repo's name, which a narrow terminal must not cut off.
                @Override public void progress(String phase, String detail) {
                    states.set(idx, StepProgress.running(phase, detail));
                }
            });
            boolean accepted = false;
            try {
                if (!result.matchesAdvertisement(advertisement)) {
                    return RepoReference.skipped("template and host repository refs differ");
                }
                var ready = new RepoReference(ref.deviceName(), ref.containerPath(),
                        HostResourceSetup.translateForVm(result.repository().toString()), null, null, result);
                accepted = true;
                return ready;
            } finally {
                if (!accepted) result.close();
            }
        } catch (Exception e) {
            if (e instanceof GitCommandException failed && !failed.details().isEmpty()) preparationFailures.put(idx, failed);
            return RepoReference.skipped("Git preparation unavailable: " + e.getMessage());
        }
    }

    private RepoReference prepareHostReference(ImageDef.RepoEntry repo, RepoReference ref,
                                               AtomicReferenceArray<StepProgress> states, int idx) {
        try {
            var result = preparation.prepareHost(java.nio.file.Path.of(ref.source()), repo.getBranch(),
                    phase -> states.set(idx, StepProgress.running(phase)));
            return new RepoReference(ref.deviceName(), ref.containerPath(),
                    HostResourceSetup.translateForVm(result.repository().toString()), null, null, result);
        } catch (Exception e) {
            if (e instanceof GitCommandException failed && !failed.details().isEmpty()) preparationFailures.put(idx, failed);
            return RepoReference.skipped("Host source export failed: " + e.getMessage());
        }
    }

    private void releasePrepared(int idx) {
        var result = prepared.remove(idx);
        if (result != null) {
            try { result.close(); }
            catch (Exception e) { preparationTimings.merge(idx, "Git staging cleanup deferred", (a, b) -> a + "; " + b); }
        }
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
                var clone = container.shAsUser("agentuser", freshDestination(expandedPath) + " && "
                        + awaitMount(ref.containerPath()) + " && " + (ref.prepared() == null
                                ? buildCloneCommand(repo, ref.containerPath()) : preparedCloneCommand(repo, ref)));
                var last = clone;
                if (clone.success() && ref.prepared() != null) {
                    usedReference = true;
                } else if (clone.success() && repo.getDepth() != null) {
                    last = container.shAsUser("agentuser", shallowFixupCommand(repo));
                    usedReference = last.success();
                }
                if (!usedReference) {
                    var error = last.exitCode() == MOUNT_TIMEOUT_EXIT && last == clone
                            ? "not mounted in the guest within " + MOUNT_WAIT_SECONDS + "s" : gitError(last);
                    fallbackReason = "could not use the host reference" + (error.isEmpty() ? "" : ": " + error);
                    // Only what this attempt cloned: a checkout that was already there is not ours to remove.
                    if (!(last == clone && last.exitCode() == DESTINATION_EXISTS_EXIT)) {
                        container.shAsUser("agentuser", "rm -rf " + shellQuote(expandedPath));
                    }
                }
            }

            if (!usedReference) {
                if (repo.isHostSource()) {
                    states.set(idx, StepProgress.failed(fallbackReason != null ? fallbackReason
                            : ref != null ? ref.skipReason() : "Host source was not prepared", null));
                    return CloneResult.FAILED;
                }
                var clone = container.shAsUser("agentuser", buildCloneCommand(repo, null));
                if (!clone.success()) {
                    states.set(idx, StepProgress.failed(gitError(clone), combinedOutput(clone)));
                    return CloneResult.FAILED;
                }

                // Enforce the broad future-fetch contract for a full clone, including tag pins.
                // A shallow repo keeps its narrow refspec, so a later fetch won't pull other histories.
                if (repo.getDepth() == null) {
                    var repoPath = shellQuote(expandHome(repo.getPath()));
                    var restore = container.shAsUser("agentuser",
                            "git -C " + repoPath + " remote set-branches origin '*'");
                    if (!restore.success()) {
                        states.set(idx, StepProgress.failed(gitError(restore), combinedOutput(restore)));
                        return CloneResult.FAILED;
                    }
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
        for (int i = 0; i < repos.size(); i++) {
            var progress = states.get(i);
            // Require an explicit DONE: a step left RUNNING or unset (e.g. a task that
            // returned without recording a result) is a failure, not a silent success.
            if (progress != null && progress.state() == StepState.DONE) continue;

            var name = repoDisplayName(repos.get(i));
            var detail = progress == null || progress.detail() == null || progress.detail().isEmpty()
                    ? verb + " failed" : progress.detail();
            errors.add(name + ": " + detail);

            // Print the full captured output so the diagnostic isn't reduced to the
            // concise inline line. Done here, after the animated batch, to avoid
            // interleaving multi-line logs with the live progress display.
            if (progress != null && progress.log() != null && !progress.log().isBlank()) {
                System.err.println(styled(BOLD, "─── " + verb + " output: " + name + " ───"));
                System.err.println(progress.log().strip());
                System.err.println(styled(BOLD, "─── end " + verb + " output: " + name + " ───"));
            }
        }
        if (!errors.isEmpty()) {
            throw new IncusException("Failed to " + verb + " " + errors.size()
                    + " repo(s):\n  " + String.join("\n  ", errors));
        }
    }

    private static String repoDisplayName(ImageDef.RepoEntry repo) {
        var name = repo.sourceName();
        return name.isEmpty() ? repo.sourceDescription() : name;
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

    /** Exit status of {@link #freshDestination} when the clone's destination already exists. */
    static final int DESTINATION_EXISTS_EXIT = 3;

    /** Fails with {@link #DESTINATION_EXISTS_EXIT} when {@code path} exists and is not an empty
     *  directory (which git clones into), so a failed clone's cleanup never removes a checkout it
     *  did not create. */
    static String freshDestination(String path) {
        var p = shellQuote(path);
        return "{ [ ! -e " + p + " ] || { [ -d " + p + " ] && [ -z \"$(ls -A " + p + ")\" ]; } || { echo " + shellQuote("fatal: destination path '" + path
                + "' already exists") + " >&2; exit " + DESTINATION_EXISTS_EXIT + "; }; }";
    }

    private static String buildCloneCommand(ImageDef.RepoEntry repo, String referencePath) {
        var cmd = new StringBuilder("git clone");
        if (referencePath != null) {
            // Only shallow clones use a raw host reference. Full clones use preparedCloneCommand.
            cmd.append(" --no-local --depth ").append(repo.getDepth());
        } else {
            if (repo.getDepth() != null) cmd.append(" --single-branch");
            if (repo.getDepth() != null) cmd.append(" --depth ").append(repo.getDepth());
            if (repo.hasBranch()) {
                cmd.append(" --branch ").append(shellQuote(repo.getBranch()));
            }
        }
        cmd.append(" -- ").append(shellQuote(referencePath != null ? referencePath : repo.getUrl()));
        var path = shellQuote(expandHome(repo.getPath()));
        cmd.append(" ").append(path);
        return cmd.toString();
    }

    /** Copy an already selected store; verify the independent bytes before any prime can run. */
    static String preparedCloneCommand(ImageDef.RepoEntry repo, RepoReference ref) {
        var path = shellQuote(expandHome(repo.getPath()));
        var command = new StringBuilder("git clone --no-hardlinks");
        if (repo.hasBranch()) command.append(" --branch ").append(shellQuote(repo.getBranch()));
        command.append(" -- ").append(shellQuote(ref.containerPath())).append(' ').append(path)
                .append(repo.isHostSource() ? "" : " && git -C " + path + " remote set-url origin " + shellQuote(repo.getUrl()))
                .append(" && { g=$(git -C ").append(path).append(" rev-parse --absolute-git-dir) && ")
                .append("[ ! -e \"$g/objects/info/alternates\" ] && ")
                .append("{ [ \"$(find \"$g/objects\" -type f | wc -l)\" -eq ")
                .append(ref.prepared().checksums().size()).append(" ] || { echo 'fatal: prepared Git object inventory changed' >&2; false; }; } && (cd \"$g\" && ");
        if (ref.prepared().checksums().isEmpty()) command.append("true");
        else {
            var checksums = new StringBuilder();
            ref.prepared().checksums().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e -> checksums.append(e.getValue()).append("  ").append(e.getKey()).append('\n'));
            // BSD sha256sum requires an explicit input file: '-' reads the piped manifest.
            command.append("printf '%s' ").append(shellQuote(checksums.toString()))
                    .append(" | { if command -v sha256sum >/dev/null 2>&1; then sha256sum -c -;")
                    .append(" else shasum -a 256 -c -; fi; } >/dev/null")
                    .append(" || { echo 'fatal: prepared Git pack checksum failed' >&2; false; }");
        }
        command.append("); }");
        if (repo.isHostSource()) {
            // Preserve every exported branch as a local branch before removing the temporary origin.
            // No remote points at the host mount or at its corporate upstream in the finished guest.
            var git = "git -C " + path;
            command.append(" && { for ref in $(").append(git)
                    .append(" for-each-ref --format='%(refname)' refs/remotes/origin/); do ")
                    .append("[ \"$ref\" = refs/remotes/origin/HEAD ] && continue; ")
                    .append(git).append(" update-ref \"refs/heads/${ref#refs/remotes/origin/}\" \"$ref\" || exit 1; done; }")
                    .append(" && ").append(git).append(" remote remove origin");
        }
        return command.toString();
    }

    /**
     * After a shallow clone from a host reference: point origin at the real remote, narrow its
     * refspec to the configured branch (else the remote's default), fetch that branch at the
     * repo's depth and check it out, then keep only the refs a shallow network clone would have.
     * Commits the reference already has do not travel again.
     */
    private static String shallowFixupCommand(ImageDef.RepoEntry repo) {
        var git = "git -C " + shellQuote(expandHome(repo.getPath()));
        return git + " remote set-url origin " + shellQuote(repo.getUrl())
                // The clone copied the host branch's tags too; a tag on unpushed work would keep it.
                // The fetch below follows the remote's own, as a network clone does.
                + " && " + deleteRefs(git, "refs/tags", "")
                + " && " + resolveBranch(repo, "$(" + git + " ls-remote --symref origin HEAD"
                        + " | sed -n 's|^ref: refs/heads/\\([^[:space:]]*\\)[[:space:]]*HEAD$|\\1|p')")
                + " && [ -n \"$b\" ]"
                // k is the branch, else empty for a tag (#1350): a configured name origin has no branch
                // of (ls-remote exit 2; any other failure is the remote's), which keeps only its own refspec.
                + (repo.hasBranch()
                        ? " && { k=\"$b\"; " + git + " ls-remote --quiet --exit-code origin \"refs/heads/$b\" >/dev/null"
                                + " || { [ $? = 2 ] && k=; }; }"
                        : " && k=\"$b\"")
                + " && if [ -n \"$k\" ]; then " + git + " remote set-branches origin \"$b\";"
                + " else " + git + " config remote.origin.fetch " + TAG_REFSPEC + "; fi"
                + (repo.hasBranch()
                        ? " && " + namingBranchOrTag(git + NO_AUTO_GC + " fetch --quiet --depth " + repo.getDepth() + " origin")
                                + " && " + checkoutBranchOrTag(git, "[ -n \"$k\" ]", null)
                        : " && " + git + NO_AUTO_GC + " fetch --quiet --depth " + repo.getDepth() + " origin"
                                + " && " + checkoutTracking(git))
                // Drop what the clone took over from the host checkout (its own branch, which may
                // hold unpushed work, and its origin/HEAD), leaving the refs a network clone has
                // (for a tag, no branch at all: an empty k matches no ref),
                + " && { " + git + " remote set-head origin -d >/dev/null 2>&1; true; }"
                + " && " + deleteOtherBranches(git)
                + " && " + deleteRefs(git, "refs/remotes", " | grep -vxF \"delete refs/remotes/origin/$k\"")
                + (repo.hasBranch() ? "" : " && " + git + " remote set-head origin \"$b\"")
                // and the objects only those refs reached.
                + " && " + stripUnreachableObjects(git);
    }

    /**
     * Shell deleting the refs under {@code namespaces}, but those {@code keep} (a pipe stage, or
     * empty) filters out of its {@code delete <ref>} lines. They are listed first, so a failing
     * for-each-ref fails the command instead of leaving every ref in place; not pipefail, which
     * would also fail on a {@code keep} grep that filtered out every line.
     */
    private static String deleteRefs(String git, String namespaces, String keep) {
        return "d=$(" + git + " for-each-ref --format='delete %(refname)' " + namespaces + ")"
                + " && { [ -z \"$d\" ] || printf '%s\\n' \"$d\"" + keep + " | " + git + " update-ref --stdin; }";
    }

    /**
     * Shell deleting every local branch but the one HEAD is on (all of them when it is detached)
     * with {@code branch -D}, which also drops their config sections: a host checkout's branch
     * name can say what it was working on. Listed first, as in {@link #deleteRefs}.
     */
    private static String deleteOtherBranches(String git) {
        return "l=$(" + git + " for-each-ref --format='%(if)%(HEAD)%(then)%(else)%(refname:strip=2)%(end)' refs/heads)"
                + " && { [ -z \"$l\" ] || " + git + " branch -q -D -- $l; }";
    }

    /**
     * Shell leaving in a clone's object store only what its refs reach, as a network clone has
     * (#1362). A clone from a host checkout copies the host's whole store: its unpushed commits,
     * stash and once-staged blobs, its commit-graph naming them, and any stray file there (a
     * {@code tmp_pack_*} an interrupted fetch left), and its branches' values in packed-refs,
     * shadowed by origin's but still naming its commits. Run it last, once the refs are a network
     * clone's: it keeps whatever they reach. It writes one pack of those objects into a new
     * store and swaps it in, so nothing copied survives, then fails unless that store is the one
     * pack, holding exactly as many objects as the refs reach, and every shallow boundary is in
     * it; the caller then falls back to a network clone. A store with alternates or promisor
     * packs, which no pack of its own makes whole, is refused.
     */
    static String stripUnreachableObjects(String git) {
        return "g=$(" + git + " rev-parse --absolute-git-dir) && o=\"$g/objects\" && n=\"$g/objects.strip\""
                + " && [ ! -e \"$o/info/alternates\" ] && ! { ls -d \"$o\"/pack/*.promisor 2>/dev/null | grep -q .; }"
                + " && " + git + " reflog expire --expire=now --all"
                // packed-refs keeps what a loose ref shadows: the host's main, ahead of origin's.
                + " && " + git + " pack-refs --all"
                // prune drops the shallow boundaries the refs no longer reach, the host's among them.
                + " && { [ ! -e \"$g/shallow\" ] || " + git + " prune --expire=now; }"
                + " && rm -rf \"$n\" && mkdir -p \"$n/pack\""
                + " && " + git + " pack-objects --all --reflog --indexed-objects --delta-base-offset -q \"$n/pack/pack\" </dev/null >/dev/null"
                + " && rm -rf \"$o\" && mv \"$n\" \"$o\""
                + " && [ \"$(ls -A \"$o\")\" = pack ] && [ \"$(ls -A \"$o/pack\" | sed 's/\\.[a-z]*$//' | sort -u | sed -n '$=')\" = 1 ]"
                + " && c=$(" + git + " count-objects -v) && r=$(" + git + " rev-list --objects --all --reflog --indexed-objects --count)"
                + " && [ \"$(printf '%s\\n' \"$c\" | sed -n 's/^in-pack: //p')\" = \"$r\" ]"
                + " && { [ ! -e \"$g/shallow\" ] || { s=$(" + git + " cat-file --batch-check < \"$g/shallow\")"
                + " && ! printf '%s\\n' \"$s\" | grep -q ' missing$'; }; }";
    }

    /** Between {@code git} and its command: no automatic gc, which would write objects behind the strip. */
    private static final String NO_AUTO_GC = " -c gc.auto=0 -c maintenance.auto=false";

    /** Shell setting {@code b} to the repo's configured branch, else to {@code remoteDefault}'s output. */
    private static String resolveBranch(ImageDef.RepoEntry repo, String remoteDefault) {
        return "b=" + (repo.hasBranch() ? shellQuote(repo.getBranch()) : remoteDefault);
    }

    /** The refspec of tag {@code $b} alone, as {@code clone --single-branch --branch <tag>} sets it. */
    private static final String TAG_REFSPEC = "\"+refs/tags/$b:refs/tags/$b\"";

    /** The error for a configured name that is neither a branch nor a tag of origin's. */
    private static final String NO_BRANCH_OR_TAG = "fatal: origin has no branch or tag '$b'";

    /** Check out branch {@code $b} tracking origin's. */
    private static String checkoutTracking(String git) {
        return git + " checkout -B \"$b\" --track \"origin/$b\"";
    }

    /**
     * Check out a configured {@code $b} as {@code clone --branch} does: a branch tracking origin's
     * when {@code isBranch} succeeds, else the tag detached, once {@code isTag} (or null) has.
     */
    private static String checkoutBranchOrTag(String git, String isBranch, String isTag) {
        return "if " + isBranch + "; then " + checkoutTracking(git) + ";"
                + " else " + (isTag == null ? "" : isTag + " && ") + git + " checkout -q --detach \"refs/tags/$b\"; fi";
    }

    /**
     * Runs {@code fetch}, a fetch of {@code $b} as a branch or a tag; when the remote has no such ref
     * its error names both, rather than only the tag a misspelt branch was last tried as (git's
     * message is matched in the C locale).
     */
    private static String namingBranchOrTag(String fetch) {
        return "{ e=$(LC_ALL=C " + fetch + " 2>&1) || { case \"$e\" in"
                + " *\"couldn't find remote ref\"*) echo \"" + NO_BRANCH_OR_TAG + "\" >&2 ;;"
                + " *) printf '%s\\n' \"$e\" >&2 ;; esac; false; }; }";
    }

    /** The host checkout {@code cloneUrl} can clone from, decided on the host alone: a planned
     *  reference, a skipped one saying why there is none, or null where none is configured. */
    RepoReference planReference(String cloneUrl, SpawnConfig config) {
        try {
            if (!GitPreparation.isRemoteUrl(cloneUrl)) return RepoReference.skipped("host preparation requires a network Git remote");
            var repoName = GitRemoteUtils.repoNameFromUrl(cloneUrl);
            if (repoName.isEmpty()) return null;

            var hostPath = GitRemoteUtils.resolveHostRepoPath(repoName, config);
            if (hostPath != null && (!Files.isDirectory(hostPath) || !GitRemoteUtils.isGitRepo(hostPath)
                    || !GitRemoteUtils.anyRemoteMatches(hostPath, cloneUrl))) {
                hostPath = null;
            }

            return new RepoReference(GitRemoteUtils.referenceDeviceName(repoName, cloneUrl),
                    GitRemoteUtils.referenceContainerPath(repoName, cloneUrl),
                    hostPath == null ? null : HostResourceSetup.translateForVm(hostPath.toString()), null,
                    new GitPreparation.Request(cloneUrl, null, hostPath), null);
        } catch (Exception e) {
            System.err.println("Warning: could not set up repo reference: " + e.getMessage());
            return null;
        }
    }
}
