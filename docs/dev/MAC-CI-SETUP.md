# The Mac Mini as a CI runner

The project's Mac Mini runs `unit-tests-macos` and `launcher-macos` for trusted code, beside its work as the agents' Mac runner (#2298).
Until the steps below are done, both jobs run on GitHub's `macos-15` as before: nothing routes to the Mini while its heartbeat is off.
Why it works this way is in design/testing.md "The Mac Mini runs the trusted macOS jobs".

## On the Mini

The machine side lives with the agent runner's setup, in isx-workflow's `MAC-SETUP.md`, section "The CI runner user".
It covers the standard macOS user the jobs run as, the homes it must not read, the shared lock, and the job-started and job-completed hooks.
The hooks are what refuse untrusted code, so the runner is not registered until they are installed.

## On GitHub, by Sanne

1. **Approval for outside contributors.**
   Under Settings, Actions, General, "Approval for running fork pull request workflows from contributors", choose "Require approval for all external contributors".
   A fork's run still never reaches the Mini, but this way none runs at all until someone has read it.
2. **Register the runner.**
   Under Settings, Actions, Runners, "New self-hosted runner", choose macOS and ARM64, and run the commands shown as the CI runner user.
   Give it the extra label `isx-mac`, which the workflows name beside `self-hosted`, `macOS` and `ARM64`.
   Install it as a service of that user (`./svc.sh install`, then `./svc.sh start`).
   Keep the Mini on macOS 15, the version of `macos-15`; every routed job's first step prints the version it ran on.
3. **Turn the heartbeat on.**
   Under Settings, Secrets and variables, Actions, Variables, add the repository variable `MAC_RUNNER_ENABLED` with the value `true`.
   Within 10 minutes the "Mac runner heartbeat" workflow shows a run on the Mini, and the next PR's `mac-route` job says the macOS jobs run on the project's Mac runner.

No secret is needed: the routing reads the heartbeat's runs with the run's own read-only token.

## Turning it off

Delete `MAC_RUNNER_ENABLED`, or set it to anything but `true`.
The heartbeat stops, and within 25 minutes every macOS job runs on `macos-15` again.
A job already sent to the Mini finishes there, or waits there until the Mini is back.
A rerun of failed jobs does not route again: it keeps the first attempt's choice, so a job that ran on the Mini goes back there whatever the variable says, and waits up to a day if the Mini is down.
Use "Re-run all jobs" instead, which routes again and puts the jobs on `macos-15`.

## When something is wrong

| What you see | What it means |
|---|---|
| A macOS job failed at "Take the Mac runner's turn" with "recorded no turn" | The job reached the Mini without its job-started hook: check the hook in the runner's `.env`. |
| A macOS job failed before its first step, "Set up job" | The hook refused it: untrusted code, or a home the runner user can read. Its log says which. |
| A routed job is green with a notice, and its `-fallback` job ran | The agent runner held the Mini, and GitHub's runner ran the job. Nothing to do. |
| Every macOS job runs on `macos-15` | The heartbeat is off or stale: check the variable and the "Mac runner heartbeat" runs. |
| A rerun macOS job waits for the Mini | A rerun of failed jobs keeps its first route. Cancel it and use "Re-run all jobs". |
