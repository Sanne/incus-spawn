package dev.incusspawn.incus;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a btrfs pool holds on disk for instances, compared with what Incus has a record of (#717).
 *
 * <p>The two can drift apart. A rename that moves the subvolume but leaves the record under the old
 * name, followed by a delete of that record, leaves the data on disk with nothing pointing at it: an
 * <em>orphan</em>. Incus cannot see it, so neither can isx, until something tries to create an
 * instance of the same name and fails with "file exists" (for a template rebuild, after the whole
 * build). The stage before that is a <em>dangling record</em>: an instance whose subvolume is not at
 * its path. Deleting such a record is exactly what strands the data, so {@link IncusClient#delete}
 * refuses it.
 *
 * <p>The on-disk side is a names-only parse of {@code btrfs subvolume list}. {@link BtrfsUsage#parse}
 * cannot be reused: it joins with qgroup rows and drops any subvolume without one (quotas off or
 * inconsistent), which would hide orphans and invent dangling records.
 */
public final class InstanceSubvolumes {

    private InstanceSubvolumes() {}

    /** The two instance kinds a pool keeps a top-level subvolume directory for. */
    public enum Kind {
        CONTAINER("containers", "container"),
        VM("virtual-machines", "virtual-machine");

        /** Directory under the pool that holds this kind's subvolumes. */
        public final String dir;
        /** The kind as Incus names it: an instance's {@code type} and a volume's {@code type}. */
        final String apiType;

        Kind(String dir, String apiType) {
            this.dir = dir;
            this.apiType = apiType;
        }

        static Kind fromDir(String dir) {
            for (var k : values()) if (k.dir.equals(dir)) return k;
            return null;
        }

        static Kind fromApiType(String type) {
            for (var k : values()) if (k.apiType.equals(type)) return k;
            return null;
        }
    }

    /**
     * An instance subvolume by the name it has on disk. That is the instance name in the default
     * project and {@code <project>_<name>} in any other.
     */
    public record Ref(Kind kind, String name) implements Comparable<Ref> {
        private static final Comparator<Ref> ORDER =
                Comparator.comparing(Ref::name).thenComparing(Ref::kind);

        public String path() {
            return kind.dir + "/" + name;
        }

        @Override
        public int compareTo(Ref o) {
            return ORDER.compare(this, o);
        }
    }

    /**
     * The comparison for one pool. {@code orphans} are subvolumes Incus has no instance or volume
     * for; {@code dangling} are default-project instances on this pool with no subvolume.
     */
    public record Scan(String pool, Set<Ref> orphans, Set<Ref> dangling) {
        public boolean isOrphan(String name) {
            return hasName(orphans, name);
        }

        public boolean isDangling(String name) {
            return hasName(dangling, name);
        }

        private static boolean hasName(Set<Ref> refs, String name) {
            return refs.stream().anyMatch(r -> r.name().equals(name));
        }

        public boolean isClean() {
            return orphans.isEmpty() && dangling.isEmpty();
        }
    }

    /**
     * The instance subvolumes of {@code pool} in {@code btrfs subvolume list} output. Only top-level
     * ones count: the path must be {@code <kind dir>/<name>} directly under the pool, which is either
     * the filesystem root (a loop-backed pool) or reached through {@code …/storage-pools/<pool>} (the
     * appliance's data disk, or a pool on a subvolume of the host's filesystem). That anchoring is
     * what keeps a guest running its own Incus -- whose pool is nested inside the guest's rootfs,
     * {@code containers/tpl-isx/rootfs/…/storage-pools/x/containers/y} -- from reporting {@code y}.
     */
    static Set<Ref> parse(String subvolumeListOutput, String pool) {
        return parseWithIds(subvolumeListOutput, pool).keySet();
    }

    /** {@link #parse}, keeping each subvolume's btrfs ID (its level-0 qgroup is {@code 0/<id>}). */
    static Map<Ref, Long> parseWithIds(String subvolumeListOutput, String pool) {
        var refs = new LinkedHashMap<Ref, Long>();
        for (var line : subvolumeListOutput.split("\n")) {
            var fields = line.strip().split("\\s+", 3);
            var idx = line.indexOf(" path ");
            if (fields.length < 3 || !fields[0].equals("ID") || idx < 0) continue;
            long id;
            try {
                id = Long.parseLong(fields[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            var ref = refFromPath(line.substring(idx + " path ".length()).strip(), pool);
            if (ref != null) refs.put(ref, id);
        }
        return refs;
    }

    static Ref refFromPath(String path, String pool) {
        var parts = path.split("/");
        if (parts.length < 2) return null;
        var kind = Kind.fromDir(parts[parts.length - 2]);
        var name = parts[parts.length - 1];
        if (kind == null || name.isEmpty()) return null;
        int prefix = parts.length - 2;
        if (prefix == 0) return new Ref(kind, name);
        if (prefix < 2 || !parts[prefix - 1].equals(pool) || !parts[prefix - 2].equals("storage-pools")) return null;
        // Nothing above that may itself be inside an instance or another pool: a guest's own pool
        // can share this one's name (containers/tpl-isx/rootfs/…/storage-pools/cow/containers/…).
        for (int i = 0; i < prefix - 2; i++) {
            if (parts[i].equals("storage-pools") || Kind.fromDir(parts[i]) != null) return null;
        }
        return new Ref(kind, name);
    }

    /**
     * Compare what is on disk with what Incus knows. {@code volumes} are the pool's instance volumes
     * across all projects, as on-disk names; {@code instances} the default-project instances whose
     * root disk is on this pool.
     *
     * @return null when the listing cannot be trusted: an empty one while Incus has instances on the
     *         pool is far more likely a listing that failed quietly (the agent answers an empty
     *         section when {@code btrfs} errors) than a pool whose every subvolume vanished, and
     *         reading it literally would call every instance dangling.
     */
    static Scan compare(String pool, Set<Ref> onDisk, Set<Ref> volumes, Set<Ref> instances) {
        if (onDisk.isEmpty() && !instances.isEmpty()) return null;
        var orphans = new TreeSet<Ref>();
        for (var ref : onDisk) {
            if (!volumes.contains(ref) && !instances.contains(ref)) orphans.add(ref);
        }
        var dangling = new TreeSet<Ref>();
        for (var ref : instances) {
            if (!onDisk.contains(ref)) dangling.add(ref);
        }
        return new Scan(pool, orphans, dangling);
    }
}
