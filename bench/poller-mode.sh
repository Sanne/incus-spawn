#!/bin/bash
# bench/poller-mode.sh — isx ssh-proxy end to end under two builds, against a fake Incus (#2129)
#
# Runs `<build> ssh-proxy x` many times with hyperfine against bench/fake-incus-exec.py, which
# answers the instance read, the exec, its four websockets and /wait at once. It prints each
# build's minimum, median, p99 and mean over the runs under 1 s, and every run over 1 s: those
# are a wait only a timer ended (5 s: OutputDrain's ceiling; 2 s: the stdin forwarder's join).
#
# The fake takes /run/incus/unix.socket, where isx looks first, so it needs sudo, and the host
# must have no /run/incus, which the script creates and removes. Run it in a quiet window:
# other builds move the numbers.
#
# Requires: hyperfine, jq, python3, two isx binaries (native builds).
#
# Usage:
#   bench/poller-mode.sh <build A> <build B> [runs, default 1000]
set -euo pipefail
a=$1 b=$2 runs=${3:-1000}
here=$(cd "$(dirname "$0")" && pwd)
out=$(mktemp -d)
# /run/incus is removed afterwards, so it must be this script's own: never an installed Incus's
[ ! -e /run/incus ] || { echo "/run/incus exists: not on a host with Incus" >&2; exit 1; }
sudo mkdir /run/incus
sudo chmod 777 /run/incus
python3 -I "$here/fake-incus-exec.py" /run/incus/unix.socket & fake=$!
trap 'kill $fake 2>/dev/null || true; sudo rm -rf /run/incus; rm -rf "$out"' EXIT
sleep 1
export HOME="$out/home"
mkdir -p "$HOME"
for bin in "$a" "$b"; do "$bin" ssh-proxy x </dev/null >/dev/null || { echo "$bin ssh-proxy failed against the fake" >&2; exit 1; }; done
hyperfine -N --warmup 20 --runs "$runs" --export-json "$out/result.json" "$a ssh-proxy x" "$b ssh-proxy x" >/dev/null
jq -r 'def ms: . * 10000 | round / 10;
  .results[] | (.times|sort) as $t | ($t|length) as $n | [$t[]|select(. < 1)] as $ok
  | "\(.command): runs \($n), min \($t[0]|ms) ms, median \($t[$n/2|floor]|ms) ms, p99 \($t[$n*0.99|floor]|ms) ms, mean under 1 s \(if ($ok|length) > 0 then ($ok|add)/($ok|length)|ms else "none" end) ms, over 1 s: \([$t[]|select(. > 1)|.*1000|round])"' "$out/result.json"
