#!/bin/bash
# bench/vm-disk-bus.sh — A VM root disk's bus and cache mode, measured inside the guest (#810)
#
# For each storage pool, bus and cache mode asked for, copies a stopped VM onto the pool, sets
# the root disk's io.bus and io.cache, and measures, then deletes the copy:
#   boot      start request until the first exec answers, polled every 50 ms (median of --boots)
#   fio       on a 4 GiB file in the guest's root, O_DIRECT through libaio, --runtime s each:
#             4k random read and write at queue depth 1 (IOPS, mean completion latency) and 32,
#             1M sequential read and write at depth 8 (MB/s)
#   clone     `git clone --no-local` of /srv/quarkus.git with its checkout, then sync (median)
#   unpack    `tar xf /srv/tree.tar`, then sync (median)
#   rm        removing both trees, then sync (median)
# The guest's caches are dropped before each workload. Everything is plain `incus`: no isx.
#
# Requires: Linux, the `incus` client with access to the daemon, `jq`, and a stopped VM whose
#           guest boots on all three buses (a stock Fedora image does: virtio-blk is built in,
#           nvme and virtio-scsi are in its initramfs; the isx base VM image's kernel has
#           virtio-blk only), with fio and git installed, /srv/quarkus.git a bare repository and
#           /srv/tree.tar a tarball, and its root found by label or UUID rather than device name.
#
# Usage:
#   bench/vm-disk-bus.sh benchbase
#   bench/vm-disk-bus.sh benchbase --pools=cow,default --buses=virtio-blk,nvme --caches=none
#   bench/vm-disk-bus.sh benchbase --plan="cow:virtio-blk:none cow:nvme:none"
# Results: a table on stdout, and every fio JSON and timing under --out (default /tmp/vm-disk-bus).
set -euo pipefail
export LC_ALL=C

POOLS="cow"
BUSES="virtio-scsi,virtio-blk,nvme"
CACHES="none"
PLAN=""
RUNS=3
BOOTS=3
RUNTIME=15
OUT=/tmp/vm-disk-bus
TIMEOUT=300

die() { echo "Error: $*" >&2; exit 2; }

BASE="${1:-}"
[ -n "$BASE" ] && [ "${BASE#--}" = "$BASE" ] || die "Usage: bench/vm-disk-bus.sh <stopped-vm> [--pools=a,b] [--buses=a,b] [--caches=a,b] [--plan='pool:bus:cache ...'] [--runs=N] [--boots=N] [--runtime=S] [--out=DIR]"
shift
while [ $# -gt 0 ]; do
    case "$1" in
        --pools=*) POOLS="${1#*=}" ;;
        --buses=*) BUSES="${1#*=}" ;;
        --caches=*) CACHES="${1#*=}" ;;
        --plan=*) PLAN="${1#*=}" ;;
        --runs=*) RUNS="${1#*=}" ;;
        --boots=*) BOOTS="${1#*=}" ;;
        --runtime=*) RUNTIME="${1#*=}" ;;
        --out=*) OUT="${1#*=}" ;;
        *) die "unknown argument $1" ;;
    esac
    shift
done

command -v jq >/dev/null || die "needs jq"
read -r type status <<<"$(incus list "^${BASE}\$" -c ts -f csv | tr ',' ' ')"
[ "${type:-}" = VIRTUAL-MACHINE ] || die "$BASE is not a virtual machine"
[ "${status:-}" = STOPPED ] || die "$BASE must be stopped: the copies are made from a stopped VM"

if [ -z "$PLAN" ]; then
    for p in ${POOLS//,/ }; do for b in ${BUSES//,/ }; do for c in ${CACHES//,/ }; do
        PLAN+="$p:$b:$c "
    done; done; done
fi
mkdir -p "$OUT"

now() { echo "$EPOCHREALTIME"; }
median() { sort -g | awk '{ v[NR] = $1 } END { if (NR == 0) print ""; else if (NR % 2) print v[(NR + 1) / 2]; else print (v[NR / 2] + v[NR / 2 + 1]) / 2 }'; }
gexec() { incus exec "$COPY" -- "$@"; }

# Seconds from the start request until exec answers.
boot() {
    local t0 t
    t0=$(now)
    incus start "$COPY" || return 1
    until incus exec "$COPY" -- true >/dev/null 2>&1; do
        t=$(now)
        awk -v a="$t0" -v b="$t" -v m="$TIMEOUT" 'BEGIN { exit !(b - a > m) }' && return 1
        sleep 0.05
    done
    t=$(now)
    awk -v a="$t0" -v b="$t" 'BEGIN { printf "%.2f\n", b - a }'
}

# Seconds a guest shell command takes, timed in the guest after dropping its caches.
timed() {
    gexec sh -c 'sync; echo 3 > /proc/sys/vm/drop_caches; s=$(date +%s.%N); '"$1"' || exit 1; e=$(date +%s.%N); awk -v a="$s" -v b="$e" "BEGIN { printf \"%.2f\n\", b - a }"'
}

fio_job() { # name rw bs depth
    gexec fio --name="$1" --filename=/root/fio.dat --size=4G --direct=1 --ioengine=libaio \
        --rw="$2" --bs="$3" --iodepth="$4" --numjobs=1 --time_based --runtime="$RUNTIME" \
        --ramp_time=2 --randrepeat=0 --output-format=json
}

printf '%-8s %-12s %-10s %6s | %8s %8s %8s %8s %9s %9s %7s %7s | %7s %7s %6s\n' \
    pool bus cache boot rr1-iops rr1-us rw1-iops rw1-us rr32-iops rw32-iops seqr-MB seqw-MB clone unpack rm
for v in $PLAN; do
    IFS=: read -r pool bus cache <<<"$v"
    COPY="bench-disk-$$-${pool}-${bus}-${cache}"
    tag="$OUT/${pool}-${bus}-${cache}"
    trap 'incus delete -f "$COPY" >/dev/null 2>&1 || true' EXIT
    incus copy "$BASE" "$COPY" -s "$pool" >/dev/null
    incus config device set "$COPY" root io.bus="$bus" io.cache="$cache"

    boots=()
    for i in $(seq "$BOOTS"); do
        boots+=("$(boot)") || die "$COPY did not answer exec within ${TIMEOUT}s"
        [ "$i" -lt "$BOOTS" ] && incus stop "$COPY"
    done
    printf '%s\n' "${boots[@]}" > "$tag.boot"
    seen=$(gexec sh -c 'findmnt -no SOURCE /')
    echo "$seen" > "$tag.device"

    # Lay the file out once, so no job measures its allocation.
    gexec fio --name=layout --filename=/root/fio.dat --size=4G --rw=write --bs=1M --direct=1 \
        --ioengine=libaio --iodepth=8 --output-format=json >/dev/null
    fio_job rr1 randread 4k 1 > "$tag.rr1.json"
    fio_job rw1 randwrite 4k 1 > "$tag.rw1.json"
    fio_job rr32 randread 4k 32 > "$tag.rr32.json"
    fio_job rw32 randwrite 4k 32 > "$tag.rw32.json"
    fio_job seqr read 1M 8 > "$tag.seqr.json"
    fio_job seqw write 1M 8 > "$tag.seqw.json"
    gexec rm -f /root/fio.dat

    : > "$tag.clone"; : > "$tag.unpack"; : > "$tag.rm"
    for i in $(seq "$RUNS"); do
        timed 'git clone -q --no-local /srv/quarkus.git /root/w && sync' >> "$tag.clone"
        timed 'mkdir /root/u && tar xf /srv/tree.tar -C /root/u && sync' >> "$tag.unpack"
        timed 'rm -rf /root/w /root/u && sync' >> "$tag.rm"
    done
    incus delete -f "$COPY" >/dev/null
    trap - EXIT

    iops() { jq -r ".jobs[0].$2.iops | floor" "$tag.$1.json"; }
    lat() { jq -r ".jobs[0].$2.clat_ns.mean / 1000 | floor" "$tag.$1.json"; }
    mbs() { jq -r ".jobs[0].$2.bw_bytes / 1000000 | floor" "$tag.$1.json"; }
    printf '%-8s %-12s %-10s %6s | %8s %8s %8s %8s %9s %9s %7s %7s | %7s %7s %6s   (%s)\n' \
        "$pool" "$bus" "$cache" "$(printf '%s\n' "${boots[@]}" | median)" \
        "$(iops rr1 read)" "$(lat rr1 read)" "$(iops rw1 write)" "$(lat rw1 write)" \
        "$(iops rr32 read)" "$(iops rw32 write)" "$(mbs seqr read)" "$(mbs seqw write)" \
        "$(median < "$tag.clone")" "$(median < "$tag.unpack")" "$(median < "$tag.rm")" "$seen"
done
