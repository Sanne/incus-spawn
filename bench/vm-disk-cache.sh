#!/bin/bash
# bench/vm-disk-cache.sh — A VM disk's host cache mode (io.cache), per context (#2198)
#
# #810 compared io.cache on the whole root disk and found no gain on a clone or an unpack. This
# measures the places where a cache mode other than the one Incus picks by itself might still pay:
#
#   tmp       /tmp on the root disk, with io.cache unset as isx leaves it (`default`) and on `none`
#             (--tmp-roots), against /tmp on a second disk device of its own
#             (a block volume on the same pool) in `none`, `writeback` and `unsafe`. Workloads in
#             /tmp: an offline rpm transaction (rpm --root), a git clone, a tar unpack, removing
#             those trees, and a Maven build of /srv/build with its output in /tmp. Also the cost
#             of the extra device: creating and attaching the volume, and the boot with it.
#   branches  N copies of one VM on the pool, as `isx branch` makes them, all with the same
#             io.cache, started and measured one after the other with the host's caches dropped
#             once before the first: reading every file under /usr and the Maven build, per
#             branch, with the host's page cache growth (Cached in /proc/meminfo) after each. With
#             `writeback`, whether branches 2..N read the template's blocks from the host's cache.
#   build     the Maven build on the root disk per mode, with the host page cache the VM's disk
#             holds afterwards (fincore on the volume's file), QEMU's resident memory, and both
#             again after the host is made to allocate most of its available memory.
#   template  Sanne's idea: a template build on `unsafe`, made safe before anyone uses it.
#             An offline `dnf install` of /srv/rpms on the root disk per mode, then a clean
#             `incus stop`, then `sync` of the pool on the host (what flushing it all on shutdown
#             costs), then io.cache set back to `none` and a boot that checks the packages.
#
# Every copy gets its io.cache set explicitly. With it unset, as isx leaves it, Incus picks
# `writeback` on a btrfs or ZFS pool and `none` on `dir`, so on btrfs the `writeback` rows are
# today's configuration. In `branches`, the host's Cached growth also counts the guest's own page
# cache (Incus backs guest RAM with a memfd); the root image's own share is the fincore column.
#
# Every guest workload runs after dropping the guest's caches, and, except within `branches`,
# after `sync` and dropping the host's caches too, so no cache below the guest serves it (#810's
# `none` rows were served by one). A nested host's own host cache is out of reach: run this on
# bare metal when there is one. Everything is plain `incus`: no isx.
#
# Requires: Linux, root via sudo (for the host's drop_caches and fincore), the `incus` client
#           with access to the daemon, `jq`, `fincore` (util-linux), `python3` (for `build`), and
#           a stopped VM with its root found by label or UUID, which has: git, tar, rpm, dnf, a JDK and `mvn` on PATH,
#           /srv/quarkus.git a bare repository, /srv/tree.tar a tarball, /srv/build a Maven
#           project whose dependencies are in /root/.m2 (so `mvn -o` builds it), /srv/rpms a set
#           of rpms not installed in the guest that `dnf install` resolves alone, offline, and
#           mkfs for the root's filesystem type. The pool must be btrfs or dir: fincore reads
#           the VM's disk file (root.img) under the pool's source.
#
# Usage:
#   bench/vm-disk-cache.sh benchbase tmp
#   bench/vm-disk-cache.sh benchbase branches --branches=4 --caches=none,writeback
#   bench/vm-disk-cache.sh benchbase build,template --caches=none,writeback,unsafe
#   bench/vm-disk-cache.sh benchbase all --pool=cow --runs=3
# Results: one table per context on stdout, and every raw timing under --out
# (default /tmp/vm-disk-cache).
set -euo pipefail
export LC_ALL=C

POOL=cow
CACHES="none,writeback,unsafe"
RUNS=3
BOOTS=3
BRANCHES=4
TMP_ROOTS="default,none"
TMP_SIZE=20GiB
OUT=/tmp/vm-disk-cache
TIMEOUT=300

die() { echo "Error: $*" >&2; exit 2; }
usage="Usage: bench/vm-disk-cache.sh <stopped-vm> <tmp|branches|build|template|all>[,...] [--pool=P] [--caches=a,b] [--runs=N] [--boots=N] [--branches=N] [--tmp-roots=default,none] [--tmp-size=S] [--out=DIR]"

BASE="${1:-}"; CONTEXTS="${2:-}"
[ -n "$BASE" ] && [ "${BASE#--}" = "$BASE" ] && [ -n "$CONTEXTS" ] && [ "${CONTEXTS#--}" = "$CONTEXTS" ] || die "$usage"
shift 2
while [ $# -gt 0 ]; do
    case "$1" in
        --pool=*) POOL="${1#*=}" ;;
        --caches=*) CACHES="${1#*=}" ;;
        --runs=*) RUNS="${1#*=}" ;;
        --boots=*) BOOTS="${1#*=}" ;;
        --branches=*) BRANCHES="${1#*=}" ;;
        --tmp-size=*) TMP_SIZE="${1#*=}" ;;
        --tmp-roots=*) TMP_ROOTS="${1#*=}" ;;
        --out=*) OUT="${1#*=}" ;;
        *) die "unknown argument $1" ;;
    esac
    shift
done
[ "$CONTEXTS" = all ] && CONTEXTS=tmp,branches,build,template
for c in ${CONTEXTS//,/ }; do
    case "$c" in tmp|branches|build|template) ;; *) die "unknown context $c; $usage" ;; esac
done

command -v jq >/dev/null || die "needs jq"
command -v fincore >/dev/null || die "needs fincore (util-linux)"
case ",$CONTEXTS," in *,build,*) command -v python3 >/dev/null || die "needs python3, for the build context's host allocation" ;; esac
sudo -n true 2>/dev/null || die "needs passwordless sudo, for the host's drop_caches and fincore"
read -r type status <<<"$(incus list "^${BASE}\$" -c ts -f csv | tr ',' ' ')"
[ "${type:-}" = VIRTUAL-MACHINE ] || die "$BASE is not a virtual machine"
[ "${status:-}" = STOPPED ] || die "$BASE must be stopped: the copies are made from a stopped VM"
DRIVER=$(incus storage show "$POOL" | awk '/^driver:/ { print $2 }')
POOL_SRC=$(incus storage get "$POOL" source)
case "$DRIVER" in btrfs|dir) ;; *) die "pool $POOL is $DRIVER; only btrfs and dir keep a VM's disk in a file fincore can read" ;; esac
# A btrfs pool on a loop file mounts under Incus's own directory, not at its source.
POOL_DIR=/var/lib/incus/storage-pools/$POOL
mkdir -p "$OUT"

COPIES=()
VOLUMES=()
cleanup() {
    for c in "${COPIES[@]}"; do incus delete -f "$c" >/dev/null 2>&1 || true; done
    for v in "${VOLUMES[@]}"; do incus storage volume delete "$POOL" "$v" >/dev/null 2>&1 || true; done
}
trap cleanup EXIT

now() { echo "$EPOCHREALTIME"; }
since() { awk -v a="$1" -v b="$(now)" 'BEGIN { printf "%.2f\n", b - a }'; }
median() { sort -g | awk '{ v[NR] = $1 } END { if (NR == 0) print "-"; else if (NR % 2) print v[(NR + 1) / 2]; else print (v[NR / 2] + v[NR / 2 + 1]) / 2 }'; }
host_drop() { sync; echo 3 | sudo tee /proc/sys/vm/drop_caches >/dev/null; }
host_cached_mib() { awk '/^Cached:/ { printf "%d\n", $2 / 1024 }' /proc/meminfo; }
disk_file() { echo "$POOL_DIR/virtual-machines/$1/root.img"; }
# MiB of the VM's root disk file held in the host's page cache.
disk_cached_mib() { sudo fincore -b -n -o RES "$(disk_file "$1")" | awk '{ printf "%d\n", $1 / 1048576 }'; }
qemu_rss_mib() {
    local pid
    pid=$(cat "/run/incus/$1/qemu.pid" 2>/dev/null || sudo cat "/run/incus/$1/qemu.pid")
    awk '/^VmRSS:/ { printf "%d\n", $2 / 1024 }' "/proc/$pid/status"
}

gexec() { incus exec "$1" -- "${@:2}"; }

# Seconds from the start request until exec answers.
boot() {
    local t0
    t0=$(now)
    incus start "$1" || return 1
    until incus exec "$1" -- true >/dev/null 2>&1; do
        awk -v a="$t0" -v b="$(now)" -v m="$TIMEOUT" 'BEGIN { exit !(b - a > m) }' && return 1
        sleep 0.05
    done
    since "$t0"
}

# Seconds a guest shell command takes, timed in the guest after dropping its caches (and the
# host's, unless KEEP_HOST_CACHE is set).
timed() {
    local vm=$1 cmd=$2
    gexec "$vm" sh -c 'sync; echo 3 > /proc/sys/vm/drop_caches'
    [ -n "${KEEP_HOST_CACHE:-}" ] || host_drop
    gexec "$vm" sh -c 's=$(date +%s.%N); { '"$cmd"'; } >/root/bench-last.log 2>&1 || exit 1; e=$(date +%s.%N); awk -v a="$s" -v b="$e" "BEGIN { printf \"%.2f\n\", b - a }"' && return
    echo "Error: in $vm, this failed: $cmd" >&2
    gexec "$vm" tail -n 20 /root/bench-last.log >&2 || true
    return 1
}

copy_vm() { # name cache
    COPIES+=("$1")
    incus copy "$BASE" "$1" -s "$POOL" >/dev/null
    # `default` leaves io.cache unset, as isx does, so Incus picks it by the pool.
    if [ "$2" = default ]; then
        incus config device set "$1" root io.bus=virtio-blk
    else
        incus config device set "$1" root io.bus=virtio-blk io.cache="$2"
    fi
}
drop_vm() { incus delete -f "$1" >/dev/null; }

# The Maven build: a fresh copy of /srv/build under $1, built offline, then removed.
MVN_BUILD='rm -rf DIR/b && cp -a /srv/build DIR/b && cd DIR/b && mvn -o -q -B package -DskipTests && sync'
mvn_in() { echo "${MVN_BUILD//DIR/$1}"; }

# --- tmp ----------------------------------------------------------------------------------------
ctx_tmp() {
    echo
    echo "## tmp: /tmp on the root disk, or on a disk of its own (pool $POOL, $DRIVER)"
    printf '%-16s %8s %6s | %7s %7s %7s %7s %7s\n' config dev-s boot rpm clone unpack rm mvn
    local configs="" c
    for c in ${TMP_ROOTS//,/ }; do configs+=" root:$c"; done
    for c in ${CACHES//,/ }; do configs+=" disk:$c"; done
    for v in $configs; do
        IFS=: read -r where cache <<<"$v"
        local vm="bench-cache-$$-tmp-$where-$cache" tag="$OUT/tmp-$where-$cache" dev=-
        # The root stays on `none` under a disk of its own, as it was measured (#2198).
        copy_vm "$vm" "$([ "$where" = root ] && echo "$cache" || echo none)"
        if [ "$where" = disk ]; then
            local vol="$vm-tmp" t0
            t0=$(now)
            VOLUMES+=("$vol")
            incus storage volume create "$POOL" "$vol" --type=block size="$TMP_SIZE" >/dev/null
            incus config device add "$vm" tmp disk pool="$POOL" source="$vol" io.bus=virtio-blk io.cache="$cache" >/dev/null
            dev=$(since "$t0")
        fi
        local boots=() i
        for i in $(seq "$BOOTS"); do
            boots+=("$(boot "$vm")") || die "$vm did not answer exec within ${TIMEOUT}s"
            [ "$i" -lt "$BOOTS" ] && incus stop "$vm"
        done
        printf '%s\n' "${boots[@]}" > "$tag.boot"
        if [ "$where" = disk ]; then
            # The disk Incus names by its serial, formatted like the root, mounted over /tmp.
            gexec "$vm" sh -c '
                d=/dev/disk/by-id/virtio-incus_tmp; [ -e "$d" ] || d=$(ls /dev/disk/by-id/*incus_tmp | head -n1)
                fs=$(findmnt -no FSTYPE /); mkfs -t "$fs" -q "$d" 2>/dev/null || mkfs -t "$fs" "$d" >/dev/null
                mount "$d" /tmp && chmod 1777 /tmp' || die "$vm: could not mount its /tmp disk"
        fi
        gexec "$vm" sh -c 'findmnt -no SOURCE,FSTYPE /tmp || findmnt -no SOURCE,FSTYPE /' > "$tag.device"
        : > "$tag.rpm"; : > "$tag.clone"; : > "$tag.unpack"; : > "$tag.rm"; : > "$tag.mvn"
        for i in $(seq "$RUNS"); do
            timed "$vm" 'mkdir -p /tmp/r && rpm --root /tmp/r --initdb && rpm --root /tmp/r -i --nodeps --noscripts --nosignature /srv/rpms/*.rpm && sync' >> "$tag.rpm"
            timed "$vm" 'git clone -q --no-local /srv/quarkus.git /tmp/w && sync' >> "$tag.clone"
            timed "$vm" 'mkdir /tmp/u && tar xf /srv/tree.tar -C /tmp/u && sync' >> "$tag.unpack"
            timed "$vm" 'rm -rf /tmp/w /tmp/u /tmp/r && sync' >> "$tag.rm"
            timed "$vm" "$(mvn_in /tmp)" >> "$tag.mvn"
            gexec "$vm" rm -rf /tmp/b
        done
        drop_vm "$vm"
        [ "$where" = disk ] && incus storage volume delete "$POOL" "$vm-tmp" >/dev/null
        printf '%-16s %8s %6s | %7s %7s %7s %7s %7s   (%s)\n' "$where:$cache" "$dev" \
            "$(printf '%s\n' "${boots[@]}" | median)" "$(median < "$tag.rpm")" "$(median < "$tag.clone")" \
            "$(median < "$tag.unpack")" "$(median < "$tag.rm")" "$(median < "$tag.mvn")" "$(cat "$tag.device")"
    done
}

# --- branches -----------------------------------------------------------------------------------
ctx_branches() {
    echo
    echo "## branches: $BRANCHES copies of one VM read the same blocks, one after the other (pool $POOL, $DRIVER)"
    printf '%-10s %6s | %7s %9s %9s | %7s %9s %9s\n' cache branch usr usr-MiB+ disk-MiB mvn mvn-MiB+ disk-MiB
    local cache i
    for cache in ${CACHES//,/ }; do
        [ "$cache" = unsafe ] && continue # reads behave as with writeback; unsafe differs only in flushes
        local vms=()
        for i in $(seq "$BRANCHES"); do
            vms+=("bench-cache-$$-br-$cache-$i")
            copy_vm "${vms[-1]}" "$cache"
        done
        host_drop
        for i in $(seq "$BRANCHES"); do
            local vm=${vms[$((i - 1))]} tag="$OUT/branches-$cache-$i" c0 c1 c2 d1 d2 usr mvn
            boot "$vm" > "$tag.boot" || die "$vm did not answer exec within ${TIMEOUT}s"
            c0=$(host_cached_mib)
            usr=$(KEEP_HOST_CACHE=1 timed "$vm" 'find /usr -xdev -type f -exec cat {} + > /dev/null')
            c1=$(host_cached_mib); d1=$(disk_cached_mib "$vm")
            mvn=$(KEEP_HOST_CACHE=1 timed "$vm" "$(mvn_in /root)")
            c2=$(host_cached_mib); d2=$(disk_cached_mib "$vm")
            echo "$usr $mvn $c0 $c1 $c2 $d1 $d2" > "$tag.txt"
            printf '%-10s %6s | %7s %9s %9s | %7s %9s %9s\n' "$cache" "$i" \
                "$usr" "$((c1 - c0))" "$d1" "$mvn" "$((c2 - c1))" "$d2"
            incus stop "$vm"
        done
        for vm in "${vms[@]}"; do drop_vm "$vm"; done
    done
}

# --- build --------------------------------------------------------------------------------------
ctx_build() {
    echo
    echo "## build: the Maven build on the root disk, and the host memory it leaves behind (pool $POOL, $DRIVER)"
    printf '%-10s | %7s | %9s %9s | %9s %9s %8s\n' cache mvn disk-MiB qemu-MiB disk-MiB' 'p qemu-MiB' 'p alloc-s
    local cache
    for cache in ${CACHES//,/ }; do
        local vm="bench-cache-$$-build-$cache" tag="$OUT/build-$cache" i
        copy_vm "$vm" "$cache"
        boot "$vm" > "$tag.boot" || die "$vm did not answer exec within ${TIMEOUT}s"
        : > "$tag.mvn"
        for i in $(seq "$RUNS"); do timed "$vm" "$(mvn_in /root)" >> "$tag.mvn"; done
        # What stays held once the guest drops its own caches (free page reporting returns those).
        gexec "$vm" sh -c 'sync; echo 3 > /proc/sys/vm/drop_caches'
        sleep 10
        local d0 q0 d1 q1 avail t0 alloc
        d0=$(disk_cached_mib "$vm"); q0=$(qemu_rss_mib "$vm")
        # Make the host allocate all but 512 MiB of what it has available, and time it.
        avail=$(awk '/^MemAvailable:/ { printf "%d\n", $2 / 1024 - 512 }' /proc/meminfo)
        t0=$(now)
        python3 -c "import sys; b = bytearray($avail << 20); [b.__setitem__(i, 1) for i in range(0, len(b), 4096)]"
        alloc=$(since "$t0")
        d1=$(disk_cached_mib "$vm"); q1=$(qemu_rss_mib "$vm")
        echo "$d0 $q0 $d1 $q1 $alloc $avail" > "$tag.mem"
        drop_vm "$vm"
        printf '%-10s | %7s | %9s %9s | %9s %9s %8s\n' "$cache" "$(median < "$tag.mvn")" "$d0" "$q0" "$d1" "$q1" "$alloc"
    done
}

# --- template -----------------------------------------------------------------------------------
ctx_template() {
    echo
    echo "## template: a package install on the root disk, then stop, host sync and a safe boot (pool $POOL, $DRIVER)"
    printf '%-10s | %7s %7s %7s %7s | %s\n' cache dnf stop sync total check
    local cache
    for cache in ${CACHES//,/ }; do
        local tag="$OUT/template-$cache" i
        : > "$tag.txt"
        for i in $(seq "$RUNS"); do
            local vm="bench-cache-$$-tpl-$cache-$i" dnf t0 stop sync check
            copy_vm "$vm" "$cache"
            boot "$vm" >/dev/null || die "$vm did not answer exec within ${TIMEOUT}s"
            dnf=$(timed "$vm" 'dnf -y -q --disablerepo="*" --setopt=install_weak_deps=False install /srv/rpms/*.rpm')
            t0=$(now); incus stop "$vm"; stop=$(since "$t0")
            t0=$(now); sudo sync -f "$POOL_DIR"; sync=$(since "$t0")
            incus config device set "$vm" root io.cache=none
            boot "$vm" >/dev/null || die "$vm did not boot again on io.cache=none"
            check=$(gexec "$vm" sh -c 'n=0; for f in /srv/rpms/*.rpm; do rpm -q "$(rpm -qp --qf "%{NAME}" "$f")" >/dev/null || n=$((n + 1)); done; echo "missing=$n"')
            echo "$dnf $stop $sync $check" >> "$tag.txt"
            drop_vm "$vm"
        done
        printf '%-10s | %7s %7s %7s %7s | %s\n' "$cache" \
            "$(awk '{ print $1 }' "$tag.txt" | median)" "$(awk '{ print $2 }' "$tag.txt" | median)" \
            "$(awk '{ print $3 }' "$tag.txt" | median)" "$(awk '{ print $1 + $2 + $3 }' "$tag.txt" | median)" \
            "$(awk '{ print $4 }' "$tag.txt" | sort -u | paste -sd,)"
    done
}

echo "# vm-disk-cache: $BASE on $POOL ($DRIVER, source $POOL_SRC), caches $CACHES, runs $RUNS, $(nproc) host CPUs, $(awk '/^MemTotal:/ { printf "%d MiB\n", $2 / 1024 }' /proc/meminfo)"
losetup -l -O NAME,BACK-FILE,DIO 2>/dev/null | grep -F "$POOL_SRC" || true
for c in ${CONTEXTS//,/ }; do "ctx_$c"; done
