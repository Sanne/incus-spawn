---
name: validate-on-real-incus
description: Validate an isx change end-to-end against a real Incus daemon on this Linux host -- set one up, build, branch, inspect, compare against the base commit, and time it. Use when a change touches how isx talks to Incus (instance writes, devices, network, storage, lifecycle, the proxy's view of instances), when a PR says it was only tested against FakeIncusDaemon, or when asked to validate a PR "for real".
---

# Validate on a real Incus

`FakeIncusDaemon` proves what isx *asks* for, not what Incus *does* with it. Incus has semantics a
fake easily gets wrong: a profile device cannot be removed from one instance, only masked;
PATCH merges while PUT replaces; `expanded_devices` differs from `devices`; options vary between
Incus versions. Anything that depends on those needs a real daemon.

## 1. Check what CI already proved

CI runs isx against a real Incus on every PR: the `isx-integration-tests-native` job in
`.github/workflows/test-integration.yml`, described in `.claude/rules/ci.md`.

```shell
gh pr checks <n>                       # did the real-Incus job pass?
gh api repos/Sanne/incus-spawn/actions/jobs/<job-id> \
  --jq '.steps[] | select(.conclusion=="failure") | .name'
```

If that job failed, find out whether the failure is in the area the change touched, and compare it
with recent runs on `main` (`gh run list --workflow test-integration.yml`) before blaming the change.
Then grep `.github/scripts/` and the workflow for the feature. What CI does not exercise is what
needs checking by hand. CI also runs its own Incus version (Zabbly's latest), not the one a distro
ships, so version-dependent behaviour is not covered.

## 2. Set up

```shell
./install.sh                    # JVM build of the working tree to ~/.local/bin (fast enough)
scripts/local-incus.sh          # Incus + isx init as CI does it; idempotent
```

This session does not gain the `incus-admin` group the script adds, so wrap every `incus` and
`isx` command in `sg incus-admin -c "..."`, as CI does. Then:

```shell
sg incus-admin -c "isx proxy start"                 # foreground: run it ALONE in the background
sg incus-admin -c "isx build tpl-minimal --yes"
```

Never chain anything after `isx proxy start`: a command queued behind it runs later, whenever the
proxy stops, e.g. a build that starts while you are tearing things down.

## 3. Exercise the change

Branch with `--no-start`, then `incus start` it. A branch that starts also attaches a shell, and
without a TTY that loops on "Connection lost -- reconnecting" until killed. Cover every path the
change touches, typically:

- plain branch; `--cpu/--memory/--disk`; `--proxy-only`; `--airgap`
- `--account <ns>=<account>`, with and without the template pinning accounts
- a source with `user.incus-spawn.instance-mode=kvm` plus `kvm`/`vhost-vsock` devices (you can add
  them to an `incus copy` of a template), branched by default, `--kvm` and `--no-kvm`
- the TUI (`isx` inside `tmux new-session -d -x 200 -y 50`, drive with `tmux send-keys`, read with
  `tmux capture-pane -p`), since it has its own branch flow in `ListCommand`

### What to inspect

- `incus config show <n> --expanded` and `incus query /1.0/instances/<n> | jq .expanded_devices`.
  **Always the expanded view**: a device that is gone from `devices` can still come from a profile.
  That is how airgap branches kept their NIC unnoticed (#813).
- Behaviour from inside, not just config: `incus exec <n> -- ip -4 -br addr`, `ip route`, and a
  `curl` to the internet as well as to an intercepted domain.
- Which account the proxy serves: pin the instance to a missing account
  (`incus config set <n> user.incus-spawn.account.claude=ghost`), signal the proxy
  (`kill -USR1 <pid>`, the pid is in `/health`), and expect `Account 'ghost' is not configured` from
  that instance only. It proves the proxy maps the instance's source address to its own pins.
- Raw Incus semantics, when unsure what a request will do:
  `incus query -X PATCH /1.0/instances/<n> -d '{"config":{"k":null}}'` against a throwaway instance.

## 4. Compare against the base commit

A bug you find may predate the change. Build the base in a worktree (`$SCRATCH` being any scratch
directory outside the repo) and run the same scenario:

```shell
git worktree add "$SCRATCH/base" <base-sha>
(cd "$SCRATCH/base" && mvn -q package -DskipTests)
sg incus-admin -c "java -jar $SCRATCH/base/cli/target/quarkus-app/quarkus-run.jar branch ..."
```

## 5. Time it, when the change is about speed

`bench/trace-branch.sh` records the daemon's events around one `isx branch`. Build native for both
sides (`mvn package -Dnative -DskipTests -pl cli -am`). The script takes the newest
`cli/target/incus-spawn-*-runner`, so copy the base runner in as `incus-spawn-base-runner` and
`touch` whichever side should run next. Alternate a few runs of each; a single run is noise.
Compare the writes before `PUT .../state`, the time of the start request, and any gap after
"Stopping forkfile" (a push still in flight makes the start wait a full second).

## Pitfalls

- `mvn verify -DskipITs=false` is not this: it runs `isx init` against the host and its template
  build IT has no definition to build. Use the CLI as above.
- `pkill -f <pattern>` also kills the shell whose command line contains the pattern. Kill by pid.
- Inside an isx instance, the outer isx's DNS and MITM proxy sit behind this one. A certificate
  issued by "incus-spawn MITM CA" might be the *outer* one: check which domains the inner proxy
  actually overrides (`incus network get incusbr0 raw.dnsmasq`) before reading a result.

## Report

Say which scenarios ran and which did not, what was compared against the base, and file anything
found that the change did not cause as its own issue rather than folding it into the review.

Clean up the instances you created (`incus delete -f ...`).
