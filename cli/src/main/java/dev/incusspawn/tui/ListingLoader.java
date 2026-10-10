package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing;
import dev.incusspawn.command.BuildCommand;
import dev.incusspawn.Platform;
import dev.incusspawn.Warnings;
import dev.incusspawn.config.BuildSource;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.proxy.ProxyHealthCheck;
import dev.incusspawn.vm.VmManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static dev.incusspawn.tui.DiskUsageModel.fillMissingReferenced;
import static dev.incusspawn.tui.DiskUsageModel.hasSuspiciousStamps;
import static dev.incusspawn.tui.DiskUsageModel.hasUnstampedBuiltTemplate;
import static dev.incusspawn.tui.DiskUsageModel.nearestStampedAncestorRfer;
import static dev.incusspawn.tui.DiskUsageModel.referencedDelta;
import static dev.incusspawn.tui.DiskUsageModel.restampFromLive;
import static dev.incusspawn.tui.DiskUsageModel.sharedBaseBytes;
import dev.incusspawn.command.InstanceListing.InstanceInfo;
import dev.incusspawn.command.InstanceListing.TemplateInfo;

/**
 * Loads what the TUI shows: the Incus listing merged with the template definitions, the disk
 * model and its accounting checks, the pool gauge, and the proxy and appliance status. It writes
 * the rows into {@link Tui}, which draws them and keeps the selection; the bookkeeping only the
 * loading needs (stamps, accounting, the pool it measures) is its own.
 */
final class ListingLoader {

    private final Tui tui;

    ListingLoader(Tui tui) {
        this.tui = tui;
    }

    // Live rfer values backfilled in memory by the last full reload; re-applied by light
    // refreshes, which don't probe, so an unstamped template keeps its figure between them.
    private java.util.Map<String, Long> backfilledReferenced = java.util.Map.of();

    /** Per built template, the file its build stamped, and the stamp it was read from. */
    private Map<String, StampedFile> templatesBuiltFrom = Map.of();

    private record StampedFile(String stamp, String file) {}

    // Resolved once and cached: the pool name is constant for a TUI session, so we
    // avoid re-probing the storage-pool list (an extra HTTP call) on every reload.
    String usagePoolName;

    // Whether the resolved pool is the CoW (btrfs/zfs) pool. Only then do per-row usage
    // figures mean "exclusive bytes" and the shared base fold below make sense.
    private boolean usagePoolIsCow;

    // Whether the resolved pool is specifically btrfs. Only btrfs exposes per-subvolume referenced
    // (rfer) accounting, so the referenced-delta model is gated on this (see canUseReferencedModel).
    private boolean usagePoolIsBtrfs;

    // The kernel's view of the btrfs pool's qgroup accounting, refreshed each reload (cheap: a sysfs
    // read, or one agent round trip on macOS). While it reads `untrusted()` every size the pool
    // reports — stamped or live, rfer or exclusive — is frozen at some stale value, so no stamp is
    // backfilled and no base weight folded from it; refreshAccountingStatus() also kicks off the
    // background repair. Null only when there is no btrfs pool to ask about (non-btrfs pool, or the
    // pool name isn't resolved yet); a status that simply couldn't be read is a non-null UNAVAILABLE.
    // Both cases are treated as trusted, so sizes are taken at face value as before the check existed.
    private dev.incusspawn.incus.BtrfsUsage.QgroupStatus qgroupStatus;

    // True from the moment inconsistent accounting is seen until it reads consistent again — the
    // transition is what triggers a one-off re-validation of the stamps (revalidateStampsIfNeeded),
    // since any stamp recorded while the counters were frozen is wrong. Deliberately latched across
    // a spell of unreadable status (a flaky agent): we still don't know the repair landed, so the
    // eventual consistent read must still trigger revalidation. It does NOT keep driving the fast
    // poll cadence though — see refreshAccountingStatus.
    private boolean accountingRepairPending;

    private boolean stampsSuspect;

    // The "a derived template's stamp equals its parent's" heuristic runs at most once per session:
    // it's how a pool poisoned before this check existed gets healed, but a live probe that comes
    // back identical must not be repeated every refresh.
    private boolean poisonHeuristicSpent;

    private boolean accountingWarningShown;

    // Reloads can come ~1/s during activity, and on macOS the status read is an agent round trip
    // (up to the client's 5s watchdog if the agent is wedged) — so re-read it on a cadence, not
    // per reload: quickly while a repair is pending (to notice the flag clearing), rarely otherwise.
    private long accountingCheckMs;

    private static final long ACCOUNTING_CHECK_INTERVAL_MS = 30_000;

    private static final long ACCOUNTING_REPAIR_POLL_MS = 2_000;

    // Set true once we've shown the low-space warning for the current session,
    // so the reminder doesn't clobber every other status message on each refresh.
    private boolean storageWarningShown;

    private boolean applianceSkewFirstLoad = true;

    // Defer the first proxy health check so the TUI renders immediately instead of
    // stalling up to 500ms on the connect timeout when the proxy isn't running.
    private static final long PROXY_AUTH_INITIAL_DEFER_MS = 500;

    private long proxyAuthCheckMs = System.currentTimeMillis()
            - Tui.PROXY_AUTH_CHECK_INTERVAL_MS + PROXY_AUTH_INITIAL_DEFER_MS;

    /**
     * Reload all data from Incus and image definitions. Populates both the
     * template panel (from ImageDef + Incus state) and the instance panel
     * (non-template instances only).
     */
    void reloadData() {
        // This reload reads everything, so it satisfies every request raised before it. Requests
        // raised while it runs (events arriving mid-reload) set the flag again and still get served.
        // Start follow-ups stay scheduled: they exist for an IPv4 that appears after this read.
        tui.liveRefreshRequested.set(false);
        tui.dataGeneration++;
        tui.lastDataLoadMs = System.currentTimeMillis();
        // The instance listing (GET /1.0/instances?recursion=2) is the heaviest single call.
        // Run it in the background while the main thread does filesystem I/O and pool probes.
        var instancesFuture = java.util.concurrent.CompletableFuture.supplyAsync(this::collectEntries);

        // Re-read tool defs from disk alongside the image defs below, so edited tool YAML
        // is reflected in the "△ definition changed" flag. Must precede addFallbacks(),
        // which re-populates the freshly cleared cache. Its warnings reach the log itself.
        tui.toolDefLoader.reload();
        // Through Warnings rather than straight into the log: each reload finds the same
        // problems again, and Warnings reports a message once until the user presses 'r'.
        tui.imageLayers = dev.incusspawn.config.ImageDef.loadLayers(Warnings::warn);
        tui.imageDefs = tui.imageLayers.defs();
        // Tool conflicts don't flow through image loadAll; surface them here too so
        // the TUI warns about duplicate tool names instead of only failing at build.
        for (var conflict : tui.toolDefLoader.conflicts()) {
            Warnings.warn(conflict.shortMessage());
        }

        // Pool usage runs here (before the merge) so the base-image weight can be attributed
        // to the root template before the row lists are snapshotted for rendering.
        refreshPoolUsage();

        List<InstanceInfo> allInstances;
        try {
            allInstances = instancesFuture.join();
        } catch (java.util.concurrent.CompletionException e) {
            var cause = e.getCause();
            if (cause instanceof Error err) throw err;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }

        allInstances = clearStalePendingOps(allInstances);
        mergeInstances(allInstances);

        refreshProxyAuthError();
        refreshApplianceSkew();
        refreshAccountingStatus();
        applyDiskModel(true);
        tui.publishRows();
    }

    /**
     * Swap in the instance listing from a light refresh (see {@link #startLiveRefresh}). Unlike
     * {@link #reloadData()} it re-reads nothing from disk and runs no probes: the definitions
     * can't have changed because an instance did, and the accounting reads keep their cadence.
     */
    void applyLiveInstances(List<InstanceInfo> allInstances, IncusClient.PoolUsage usage) {
        tui.lastDataLoadMs = System.currentTimeMillis();
        if (usage != null) applyPoolUsage(usage);
        mergeInstances(allInstances);
        applyDiskModel(false);
        tui.publishRows();
    }

    /**
     * Clear a pending-op marker no process holds the lock for: the process that set it crashed.
     * Returns the listing with those markers blanked, so the UI doesn't render stale indicators
     * until the next reload. Safe off the UI thread (lock files and Incus calls only).
     */
    List<InstanceInfo> clearStalePendingOps(List<InstanceInfo> allInstances) {
        var clearedInstances = new java.util.HashSet<String>();
        for (var inst : allInstances) {
            if (!inst.pendingOp().isEmpty()
                    && !tui.backgroundTasks.hasRunningTask(inst.name())
                    && !tui.lockManager.isHeldByOther(inst.name())) {
                try {
                    var cleanupLock = tui.lockManager.tryAcquire(inst.name(), "cleanup");
                    if (cleanupLock.isPresent()) {
                        try (var lock = cleanupLock.get()) {
                            tui.incus.clearPendingOperation(inst.name());
                            clearedInstances.add(inst.name());
                        }
                    }
                } catch (java.io.UncheckedIOException ignored) {}
            }
        }
        // Override pendingOp in allInstances for entries we just cleared,
        // so the UI doesn't render stale indicators until the next reload.
        if (!clearedInstances.isEmpty()) {
            allInstances = allInstances.stream()
                    .map(inst -> clearedInstances.contains(inst.name())
                            ? new InstanceInfo(inst.name(), inst.status(), inst.project(), inst.profile(),
                                    inst.created(), inst.runtime(), inst.parent(), inst.limitsCpu(),
                                    inst.limitsMemory(), inst.rootSize(), inst.ipv4(), inst.networkMode(),
                                    inst.architecture(), inst.buildVersion(), inst.definitionSha(),
                                    inst.type(), inst.buildSourceJson(), "", inst.defaultAction(),
                                    inst.diskUsage(), inst.referencedBytes(), inst.instanceMode(),
                                    inst.kvmEnabled(), inst.mcp(), inst.mcpCaller())
                            : inst)
                    .toList();
        }

        return allInstances;
    }

    /** The file a built template's build stamped, or null. */
    String builtFrom(String template) {
        var stamped = templatesBuiltFrom.get(template);
        return stamped == null ? null : stamped.file();
    }

    /** Merge the Incus listing with the image definitions into the two panels' entry lists. */
    // Package-private, with buildTemplateRowData and buildContextLine, for the wiring tests
    void mergeInstances(List<InstanceInfo> allInstances) {
        // Build template panel data by merging ImageDef definitions with Incus state
        tui.templateEntries = new ArrayList<>();
        var templateNames = new java.util.HashSet<String>();
        var builtFrom = new java.util.HashMap<String, StampedFile>();
        for (var def : tui.imageDefs.values()) {
            var name = def.getName();
            // Find matching Incus instance
            InstanceInfo match = null;
            for (var inst : allInstances) {
                if (inst.name().equals(name)) {
                    match = inst;
                    break;
                }
            }
            if (match != null) {
                tui.templateEntries.add(new TemplateInfo(name, def.getDescription(),
                        match.created().isEmpty() ? "built" : match.created(), match.runtime(),
                        match.buildVersion(), match.definitionSha(), match.pendingOp(),
                        match.parent(), match.diskUsage(), match.referencedBytes(), match.instanceMode()));
                templateNames.add(name);
                // A live refresh brings the same stamp again: parse it only when it changed
                var json = match.buildSourceJson();
                var previous = templatesBuiltFrom.get(name);
                builtFrom.put(name, previous != null && java.util.Objects.equals(json, previous.stamp())
                        ? previous : new StampedFile(json, BuildSource.sourceOf(json, name)));
            } else {
                tui.templateEntries.add(new TemplateInfo(name, def.getDescription(), "not built", "", "", "", "", "", -1, -1, ""));
            }
        }
        // Add out-of-scope templates (built but not in current definition scope)
        var storedNames = new java.util.HashSet<String>();
        for (var inst : allInstances) {
            if (templateNames.contains(inst.name())) continue;
            if (inst.name().endsWith(BuildCommand.REBUILDING_SUFFIX)) continue;
            if (!Metadata.TYPE_BASE.equals(inst.type())) continue;

            var buildSource = BuildSource.fromJson(inst.buildSourceJson());
            if (buildSource == null) continue;

            for (var entry : buildSource.getDefinitions().entrySet()) {
                tui.imageDefs.putIfAbsent(entry.getKey(), entry.getValue());
            }
            tui.toolDefLoader.addFallbacks(buildSource.getTools());

            tui.templateEntries.add(new TemplateInfo(inst.name(), buildSource.descriptionFor(inst.name()),
                    inst.created().isEmpty() ? "built" : inst.created(), inst.runtime(),
                    inst.buildVersion(), inst.definitionSha(), inst.pendingOp(), inst.parent(), inst.diskUsage(),
                    inst.referencedBytes(), inst.instanceMode()));
            templateNames.add(inst.name());
            storedNames.add(inst.name());
        }
        tui.storedSourceTemplates = storedNames;
        templatesBuiltFrom = builtFrom;

        // Instance panel: exclude template instances (they're shown in the template panel)
        tui.entries = new ArrayList<>();
        tui.actionsCache = new java.util.HashMap<>();
        tui.defaultActionRef = new java.util.HashMap<>();
        for (var inst : allInstances) {
            if (!templateNames.contains(inst.name())) {
                tui.entries.add(inst);
                tui.actionsCache.put(inst.name(), tui.instanceActions.resolveActionsForInstance(inst));
                var defAction = tui.instanceActions.resolveDefaultActionRef(inst);
                if (defAction != null) {
                    tui.defaultActionRef.put(inst.name(), defAction);
                }
            }
        }

        tui.liveInstanceNames = allInstances.stream().map(InstanceInfo::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Attribute disk weight to the rows. {@code probe} allows the live reads (stamp revalidation,
     * rfer backfill); a light refresh passes false and reuses the last backfill instead.
     */
    private void applyDiskModel(boolean probe) {
        // Stamps taken from consistent accounting stay valid whatever the flag says now (templates
        // are immutable), so the delta model keeps running through a repair. What must wait for the
        // counters to be trustworthy is anything *read live*: backfilling a missing stamp, healing a
        // suspect one, or folding the pool's shared remainder onto the root.
        boolean accountingTrusted = qgroupStatus == null || !qgroupStatus.untrusted();
        if (probe) {
            backfilledReferenced = java.util.Map.of();
            if (accountingTrusted) {
                revalidateStampsIfNeeded();
                fillMissingReferencedSizes();
            }
        } else if (accountingTrusted && !backfilledReferenced.isEmpty()) {
            tui.templateEntries = fillMissingReferenced(tui.templateEntries, backfilledReferenced);
        }
        if (canUseReferencedModel()) {
            applyReferencedTemplateDeltas();
        } else if (accountingTrusted) {
            foldBaseWeightIntoRootTemplate();
        } else {
            tui.baseTemplateName = null;
        }
    }

    /**
     * Refresh the cached storage-pool usage for the gauge, and raise a one-shot
     * status warning when the pool crosses the critical threshold. Prefers a CoW
     * pool but falls back to any usable pool so the gauge also works on dir pools.
     */
    private void refreshPoolUsage() {
        try {
            if (usagePoolName == null) {
                var probe = tui.incus.probeCowPool();
                var cow = probe.poolName();
                usagePoolName = cow != null ? cow : tui.incus.findUsablePool();
                usagePoolIsCow = cow != null;
                usagePoolIsBtrfs = probe.isBtrfs();
            }
            applyPoolUsage(usagePoolName == null ? null : tui.incus.getPoolUsageBytes(usagePoolName));
        } catch (Exception e) {
            applyPoolUsage(null);
        }
    }

    private void applyPoolUsage(IncusClient.PoolUsage usage) {
        tui.poolUsage = usage;
        if (tui.poolUsage == null || tui.poolUsage.totalBytes() == 0) {
            storageWarningShown = false;
            return;
        }
        if (tui.poolUsage.percent() >= IncusClient.PoolUsage.CRIT_PERCENT) {
            if (!storageWarningShown && tui.statusMessage == null) {
                tui.statusMessage = "⚠ Storage " + tui.poolUsage.percent()
                        + "% full — press C to reclaim space (stale templates, unused images, caches)"
                        + (Platform.isMacOS() ? ", or grow it with 'isx vm resize'" : "");
                storageWarningShown = true;
            }
        } else {
            storageWarningShown = false;
        }
    }

    private void refreshProxyAuthError() {
        long now = System.currentTimeMillis();
        if (now - proxyAuthCheckMs < Tui.PROXY_AUTH_CHECK_INTERVAL_MS) return;
        proxyAuthCheckMs = now;
        try {
            tui.proxyInfo = ProxyHealthCheck.fetchProxyInfo(ProxyHealthCheck.healthAddress(tui.incus), 500);
        } catch (Exception e) {
            tui.proxyInfo = null;
        }
    }

    private void refreshApplianceSkew() {
        if (!Platform.isMacOS()) { tui.applianceSkewMessage = null; return; }
        if (applianceSkewFirstLoad) { applianceSkewFirstLoad = false; return; }
        try {
            var skew = VmManager.applianceSkew();
            tui.applianceSkewMessage = skew == null ? null
                    : "Appliance " + skew.running() + " — restart VM for " + skew.installed();
        } catch (Exception e) {
            tui.applianceSkewMessage = null;
        }
    }

    /**
     * Attribute the pool's shared base weight to the root template. btrfs reports each row's usage
     * as <em>exclusive</em> (blocks unique to that one subvolume), so the imported base image and
     * every block shared down a CoW chain belong to no row — they surface only in the pool total.
     * We fold that remainder ({@code pool.used} minus the sum of every row's unique usage) into the
     * single root template (the built template with no parent), so the base reads at its real weight
     * and the rows roughly reconcile with the gauge. Only for CoW pools; skipped when usage is
     * unknown or the root is ambiguous (zero or several templates with no parent), rather than
     * misattributing the bytes to the wrong row.
     */
    private void foldBaseWeightIntoRootTemplate() {
        tui.baseTemplateName = null;
        if (!usagePoolIsCow || tui.poolUsage == null || tui.poolUsage.usedBytes() <= 0) return;

        long unique = 0;
        for (var t : tui.templateEntries) if (t.diskUsage() > 0) unique += t.diskUsage();
        for (var e : tui.entries) if (e.diskUsage() > 0) unique += e.diskUsage();
        long base = sharedBaseBytes(tui.poolUsage.usedBytes(), unique);
        if (base <= 0) return;

        int rootIdx = -1;
        for (int i = 0; i < tui.templateEntries.size(); i++) {
            var t = tui.templateEntries.get(i);
            if (t.diskUsage() < 0) continue;                            // not built — no subvolume yet
            if (!t.isRoot()) continue;                                  // derived — not a root
            if (rootIdx >= 0) return;                                   // ambiguous: >1 root, don't guess
            rootIdx = i;
        }
        if (rootIdx < 0) return;

        var r = tui.templateEntries.get(rootIdx);
        long folded = (r.diskUsage() < 0 ? 0 : r.diskUsage()) + base;
        tui.templateEntries.set(rootIdx, r.withDiskUsage(folded));
        tui.baseTemplateName = r.name();
    }

    /**
     * Attribute disk weight using each template's stamped btrfs <em>referenced</em> size (rfer),
     * shown as a delta from its parent: {@code delta = rfer(node) − rfer(parent)}, and for the root
     * template (no template parent) the delta is its own rfer — which is the base-image weight, so
     * the base reads at its real size and derived templates show only what they added (e.g. a tools
     * layer). Unlike {@link #foldBaseWeightIntoRootTemplate}, no shared remainder is dumped onto the
     * root: rfer already distributes it correctly down the chain. Instances are left on their
     * exclusive usage — for a branch with no descendants that already equals its delta from the
     * template, and it comes free from the Incus API.
     *
     * <p>Only used when every built template carries the {@link Metadata#DISK_REFERENCED} stamp (see
     * {@link #canUseReferencedModel}); otherwise the fold fallback runs. Sets {@link #baseTemplateName}
     * to the root template so the CoW delete note and {@code ~} marker still apply.
     */
    private void applyReferencedTemplateDeltas() {
        tui.baseTemplateName = null;
        var rferOf = new java.util.HashMap<String, Long>();
        for (var t : tui.templateEntries) if (t.referencedBytes() >= 0) rferOf.put(t.name(), t.referencedBytes());

        // Definitional parent links (from on-disk YAML) survive template deletion, unlike the built
        // rows: if an intermediate template is deleted, its immediate parent row is gone but the chain
        // is still walkable, so we can subtract the nearest *surviving* ancestor instead of over-
        // subtracting a phantom parent. See nearestStampedAncestorRfer.
        var defParentOf = new java.util.HashMap<String, String>();
        if (tui.imageDefs != null) {
            for (var e : tui.imageDefs.entrySet()) defParentOf.put(e.getKey(), e.getValue().getParent());
        }

        String rootName = null;
        boolean rootAmbiguous = false;
        for (int i = 0; i < tui.templateEntries.size(); i++) {
            var t = tui.templateEntries.get(i);
            // Unstamped: either not built, or a built derived template whose stamp is missing (e.g. a
            // transient btrfs read failure at build time). Leave its diskUsage on the exclusive value
            // set at load — a per-row fallback, rather than dropping the whole model to the fold.
            if (t.referencedBytes() < 0) continue;
            var p = t.parent();
            boolean isRoot = t.isRoot();
            if (isRoot) {
                if (rootName != null) rootAmbiguous = true;
                rootName = t.name();
            }
            Long parentRfer = isRoot ? null : nearestStampedAncestorRfer(p, rferOf, defParentOf);
            long delta = referencedDelta(t.referencedBytes(), parentRfer, isRoot);
            tui.templateEntries.set(i, t.withDiskUsage(delta));
        }
        if (!rootAmbiguous) tui.baseTemplateName = rootName;
    }

    private boolean canUseReferencedModel() {
        // usagePoolIsBtrfs already implies a named CoW pool (it's set from probe.isBtrfs()).
        return DiskUsageModel.canUseReferencedModel(usagePoolIsBtrfs, tui.templateEntries);
    }

    /**
     * Backfill any built template that's missing its {@link Metadata#DISK_REFERENCED} stamp with a
     * single <em>live</em> btrfs read, so the referenced-delta model still applies to that row (and,
     * if the missing one is the root, to the whole panel) instead of falling back to the shared-base
     * fold. This heals both pre-feature templates (built before rfer stamping) and the rare build
     * whose stamp failed to record — with no rebuild required.
     *
     * <p>Deliberately lazy and light, to honour the "privileged read is rare, not per-refresh"
     * posture: it runs the probe <em>only</em> when there is an actual gap (an unstamped built
     * template), and uses the non-sync flavour ({@link dev.incusspawn.incus.BtrfsUsage#probe(String,
     * boolean)} with {@code sync=false}) — no forced filesystem commit, cheap enough for a refresh
     * cadence. An existing stamp is never overwritten (templates are immutable, so the stamp is
     * authoritative and free), and the overlay is in-memory only (a rebuild re-stamps permanently).
     * If the read is unavailable (dir pool, no sudoers rule, agent down) the gap stays and the fold
     * fallback handles it.
     */
    private void fillMissingReferencedSizes() {
        if (!usagePoolIsBtrfs || usagePoolName == null) return;
        if (!hasUnstampedBuiltTemplate(tui.templateEntries)) return;       // no gap — skip the probe entirely
        var live = dev.incusspawn.incus.BtrfsUsage.probe(usagePoolName, false);   // non-sync: light enough for refresh
        if (live.isEmpty()) return;
        backfilledReferenced = live;
        tui.templateEntries = fillMissingReferenced(tui.templateEntries, live);
    }

    /**
     * Read the pool's qgroup accounting status and, if it's inconsistent, start the repair — a
     * background {@code btrfs quota rescan}, throttled inside {@code BtrfsUsage} so calling this on
     * every reload is fine. The kernel clears the flag when the rescan completes (seconds on a
     * developer-sized pool); the reload that first sees it consistent again marks the stamps
     * suspect, because any recorded while the counters were frozen are wrong.
     */
    private void refreshAccountingStatus() {
        if (!usagePoolIsBtrfs || usagePoolName == null) {
            qgroupStatus = null;
            return;
        }
        long now = System.currentTimeMillis();
        // Poll fast only while a repair is pending AND the last read actually told us something.
        // If the status has become unreadable (a wedged agent on macOS, each read costing up to its
        // 5s watchdog), polling every 2s buys nothing and just hammers a failing channel — so back
        // off to the normal cadence until it answers again.
        boolean canObserveRepair = accountingRepairPending && qgroupStatus != null && qgroupStatus.available();
        long interval = canObserveRepair ? ACCOUNTING_REPAIR_POLL_MS : ACCOUNTING_CHECK_INTERVAL_MS;
        if (qgroupStatus != null && now - accountingCheckMs < interval) return;
        accountingCheckMs = now;
        qgroupStatus = dev.incusspawn.incus.BtrfsUsage.repairIfInconsistent(usagePoolName);
        if (qgroupStatus.untrusted()) {
            accountingRepairPending = true;
            if (!accountingWarningShown && tui.statusMessage == null) {
                tui.statusMessage = "Repairing disk accounting (btrfs quota rescan) — sizes may be stale for a moment";
                accountingWarningShown = true;
            }
        } else if (accountingRepairPending && qgroupStatus.available()) {
            accountingRepairPending = false;
            stampsSuspect = true;
        }
    }

    /**
     * Re-validate stamped referenced sizes against one live read and re-stamp any that differ.
     * Runs only when there's reason to doubt them: right after a repair observed this session
     * (the stamps may have been recorded from frozen counters), or — at most once per session —
     * when they <em>look</em> poisoned: a built derived template stamped with exactly its parent's
     * value (see {@link #hasSuspiciousStamps}). That second trigger is what heals a pool that went
     * inconsistent before this check existed, without a rebuild. Corrected stamps are persisted so
     * the next launch doesn't probe again; the privileged read stays out of the healthy path.
     */
    private void revalidateStampsIfNeeded() {
        if (!usagePoolIsBtrfs || usagePoolName == null) return;
        boolean run = stampsSuspect;
        if (!run && !poisonHeuristicSpent && hasSuspiciousStamps(tui.templateEntries)) {
            run = true;
            poisonHeuristicSpent = true;
        }
        if (!run) return;
        stampsSuspect = false;
        var live = dev.incusspawn.incus.BtrfsUsage.probe(usagePoolName, false);
        if (live.isEmpty()) return;
        var corrected = restampFromLive(tui.templateEntries, live);
        for (int i = 0; i < corrected.size(); i++) {
            var before = tui.templateEntries.get(i);
            var after = corrected.get(i);
            if (after.referencedBytes() == before.referencedBytes()) continue;
            try {
                tui.incus.configSet(after.name(), Metadata.DISK_REFERENCED, String.valueOf(after.referencedBytes()));
            } catch (Exception ignored) {
                // Best-effort persistence: the corrected value is used for this session regardless.
            }
        }
        tui.templateEntries = corrected;
    }

    List<InstanceInfo> collectEntries() {
        return InstanceListing.collectEntries(tui.incus.listJson());
    }
}
