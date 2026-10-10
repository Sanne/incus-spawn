package dev.incusspawn.tui;

import dev.incusspawn.command.InstanceListing.TemplateInfo;

import java.util.ArrayList;
import java.util.Map;

import static dev.incusspawn.command.InstanceListing.isRootParent;

/**
 * How the TUI attributes disk weight to templates: the referenced-size model on btrfs, where each
 * template shows its delta from the nearest stamped ancestor, and the checks deciding when its
 * stamps can be trusted. Pure functions of the rows; the live probes stay with the TUI.
 */
final class DiskUsageModel {

    private DiskUsageModel() {}

    /**
     * The rfer of the nearest ancestor that still exists <em>and</em> is stamped, starting at
     * {@code parent} and climbing the definitional chain. When an intermediate template is deleted its
     * row (and rfer) vanish, but the YAML chain remains — so we skip the missing link and subtract the
     * next surviving ancestor, keeping each derived row a delta rather than double-counting the shared
     * base. Returns {@code null} when no ancestor survives (the whole chain above was deleted), so the
     * caller falls back to showing full rfer — which correctly re-absorbs the now-unattributed base.
     * A {@code seen} guard makes a cyclic/self-referential YAML parent terminate instead of looping.
     */
    static Long nearestStampedAncestorRfer(String parent, Map<String, Long> rferOf,
                                           Map<String, String> defParentOf) {
        var seen = new java.util.HashSet<String>();
        String name = parent;
        while (!isRootParent(name) && seen.add(name)) {
            Long r = rferOf.get(name);
            if (r != null) return r;                    // present and stamped
            name = defParentOf.get(name);               // deleted -> climb toward the base
        }
        return null;
    }

    /**
     * Whether the referenced-size (rfer) model can be used this refresh. Requires a btrfs CoW pool
     * and that every built <em>root</em> template (no template parent) carries the
     * {@link Metadata#DISK_REFERENCED} stamp — the root is where the base-image weight lands, so
     * without its rfer the base can't be attributed and the shared-base fold is the better model.
     *
     * <p>A built <em>derived</em> template that lacks a stamp is deliberately <em>not</em>
     * disqualifying: {@link #applyReferencedTemplateDeltas} leaves each such row on its exclusive
     * usage, so one missing stamp degrades that single row instead of collapsing the whole display
     * to the fold. (This matters because a transient btrfs read failure at build time can drop a
     * lone stamp; the old all-or-nothing gate turned that into every template reading ~0.) A missing
     * stamp (pre-feature install, or a build whose stamp failed) is backfilled live before this gate
     * runs — see {@link #fillMissingReferencedSizes}.
     */
    static boolean canUseReferencedModel(boolean poolIsBtrfs, java.util.List<TemplateInfo> templates) {
        if (!poolIsBtrfs) return false;
        boolean anyBuiltRoot = false;
        for (var t : templates) {
            if (!t.isBuilt()) continue;
            if (!t.isRoot()) continue;
            anyBuiltRoot = true;
            if (t.referencedBytes() < 0) return false;                 // base weight would be lost — use the fold
        }
        return anyBuiltRoot;
    }

    /**
     * Whether any built derived template carries exactly its (built, stamped) parent's referenced
     * size. A layer that added literally nothing is not a thing — even an empty template writes its
     * env file — so equal stamps mean both were recorded from frozen accounting (every subvolume
     * then reports the value it inherited at snapshot time). This is the signature of the failure,
     * not a normal state, so it's safe to act on.
     */
    static boolean hasSuspiciousStamps(java.util.List<TemplateInfo> templates) {
        var rferOf = new java.util.HashMap<String, Long>();
        for (var t : templates) if (t.isBuilt() && t.referencedBytes() >= 0) rferOf.put(t.name(), t.referencedBytes());
        for (var t : templates) {
            if (!t.isBuilt() || t.isRoot() || t.referencedBytes() < 0) continue;
            var parentRfer = rferOf.get(t.parent());
            if (parentRfer != null && parentRfer == t.referencedBytes()) return true;
        }
        return false;
    }

    /**
     * Return {@code templates} with every built row's referenced size replaced by {@code live}'s
     * (a name→rfer map) where it differs and the live value is positive. Unlike
     * {@link #fillMissingReferenced}, this overwrites existing stamps — it's only ever called when
     * they're suspect. Rows absent from {@code live} and not-built rows are left untouched.
     */
    static java.util.List<TemplateInfo> restampFromLive(java.util.List<TemplateInfo> templates,
                                                        java.util.Map<String, Long> live) {
        var out = new ArrayList<>(templates);
        for (int i = 0; i < out.size(); i++) {
            var t = out.get(i);
            if (!t.isBuilt()) continue;
            var r = live.get(t.name());
            if (r != null && r > 0 && r != t.referencedBytes()) out.set(i, t.withReferencedBytes(r));
        }
        return out;
    }

    /** Whether any built template lacks a stamped referenced size — the trigger for a live backfill. */
    static boolean hasUnstampedBuiltTemplate(java.util.List<TemplateInfo> templates) {
        for (var t : templates) {
            if (t.isBuilt() && t.referencedBytes() < 0) return true;
        }
        return false;
    }

    /**
     * Return {@code templates} with each built-but-unstamped row's referenced size filled from
     * {@code live} (a name→rfer map). Existing stamps and not-built rows are left untouched; a name
     * absent from {@code live} or mapped to a non-positive value is skipped (keeps it unstamped, so
     * it degrades per-row rather than showing a bogus size).
     */
    static java.util.List<TemplateInfo> fillMissingReferenced(java.util.List<TemplateInfo> templates,
                                                              java.util.Map<String, Long> live) {
        var out = new java.util.ArrayList<>(templates);
        for (int i = 0; i < out.size(); i++) {
            var t = out.get(i);
            if (!t.isBuilt() || t.referencedBytes() >= 0) continue;
            var r = live.get(t.name());
            if (r != null && r > 0) out.set(i, t.withReferencedBytes(r));
        }
        return out;
    }

    /**
     * Compact per-row disk cell, e.g. "~3.1G", or "-" when usage is unknown
     * (dir pools / stopped instances report nothing). The leading "~" flags that
     * the figure is approximate and excludes CoW-shared blocks.
     */
    /**
     * Bytes that live in the pool but in no single row: the imported base image plus every
     * CoW-shared block. btrfs reports per-row usage as <em>exclusive</em>, so shared blocks are
     * counted in the pool total yet belong to no row. Clamped at 0 (rounding / metadata slack can
     * make the row sum momentarily exceed the reported pool usage).
     */
    static long sharedBaseBytes(long poolUsedBytes, long sumRowUnique) {
        return Math.max(0, poolUsedBytes - sumRowUnique);
    }

    /**
     * A template's displayed disk weight under the referenced-size model: its own referenced bytes
     * for a root template (that's the base-image weight), otherwise the delta over its parent's
     * referenced bytes (what this layer added), clamped at zero. When the parent's rfer is unknown
     * (out of scope or unstamped) we show the full rfer rather than over-subtracting.
     */
    static long referencedDelta(long rfer, Long parentRfer, boolean isRoot) {
        if (isRoot || parentRfer == null) return rfer;
        return Math.max(0, rfer - parentRfer);
    }

    /**
     * Whether {@code name} is the recorded parent of any listed row — i.e. an instance or template
     * was branched or derived from it and still exists. Package-private and pure for testing.
     */
    static boolean hasDescendant(String name, java.util.Collection<String> rowParents) {
        if (name == null || name.isEmpty()) return false;
        for (var p : rowParents) if (name.equals(p)) return true;
        return false;
    }
}
