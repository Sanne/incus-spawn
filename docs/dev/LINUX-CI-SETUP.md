# The Linux server as a CI runner

The project's Linux server can run `unit-tests` for trusted code, in a fresh isx instance for each job (#2519).
GitHub's `ubuntu-26.04` runs it otherwise, as before: nothing routes to the server while its heartbeat is off.
Why it works this way is in design/testing.md "A fresh isx instance runs unit-tests for trusted code".

Everything below is done by Sanne, on the server as the user that runs isx, and on GitHub.
Do it after the PR that adds `.github/linux-runner/` is merged: the template installs its hook from its clone of `main`.

## 1. A token for the runner's registrations

The server registers a new runner for every job, which takes a token with administration rights on the repository.

1. On GitHub, under Settings, Developer settings, Fine-grained personal access tokens, generate a token.
   Resource owner `Sanne`, repository access "Only select repositories" with `Sanne/incus-spawn` alone.
   Repository permissions: Administration "Read and write" (runner registrations) and Actions "Read and write" (a pause queues a heartbeat).
   Nothing else.
2. On the server, store it where only you can read it:

   ```shell
   install -d -m 700 ~/.config/isx-ci-runner
   (umask 077; cat > ~/.config/isx-ci-runner/token)   # paste the token, then Ctrl-D
   ```

The supervisor refuses a token file that is not mode 600 or 400.
The token never enters an instance: each job gets a just-in-time runner configuration, valid for that one job.

## 2. The template

1. Add the definitions to `searchPaths` in `~/.config/incus-spawn/config.yaml`, beside the `incus-spawn-templates` checkout that defines `graalvm`:

   ```yaml
   searchPaths:
     - ~/incus-spawn-templates
     - ~/incus-spawn/.github/linux-runner
   ```

   Build it from a checkout of `main`: the template's definition is read from that directory when it builds.
2. Build it:

   ```shell
   isx build tpl-isx-ci-linux
   ```

   It downloads GraalVM and the Actions runner once into isx's download cache, clones the repository and primes `~/.m2` with a build.
   isx gives every instance a proof for each of the server's accounts, whatever its tools, and a ChatGPT sign-in's look-alikes when the default `openai` account is one.
   Before the runner starts, the template's `drop-credentials.sh` removes them, and the runner does not start while one is left.
3. Rebuild it now and then, weekly or after a dependency bump, so each job's checkout and Maven have little to fetch.
   Rebuild it after the runner's pin moves (`tools/github-actions-runner.yaml`), or each job downloads the new runner first.

## 3. The supervisor

```shell
install -D -m 0755 ~/incus-spawn/.github/linux-runner/isx-ci-runner.sh ~/.local/bin/isx-ci-runner
install -D -m 0644 ~/incus-spawn/.github/linux-runner/isx-ci-runner@.service ~/.config/systemd/user/isx-ci-runner@.service
systemctl --user daemon-reload
loginctl enable-linger "$USER"   # runs while nobody is logged in
```

Install it from `main` again after a change to it: the unit runs the installed copy, never the checkout.
The instance gets 8 cores and 16 GiB by default.
Set `ISX_CI_CPUS` and `ISX_CI_MEMORY` in the unit (`systemctl --user edit isx-ci-runner@1`) to fit the server beside the agents' boxes.

Then start the service:

```shell
systemctl --user enable --now isx-ci-runner@1
```

It registers a runner, branches `isx-ci-linux-1` and waits for a job.
Under Settings, Actions, Runners, the runner shows as idle.
`isx-ci-runner once` does the same for one job in the foreground, with the service stopped, to try the setup by hand.

Check once that the job will hold no proof.
While the runner waits for a job, this must list no `proof-tokens`, `credential-secret` or `instance-secret`, and print no credential helper:

```shell
isx exec isx-ci-linux-1 -- sh -c 'ls /run/isx; git config --system --get-regexp "^credential\." ; echo end'
```

Two things would undo that, so keep them away from the runner:

- **`isx account reset-proofs` on a CI instance.** It writes the proofs back into the running instance, and the job could then use the server's accounts. If you suspect a runner's instance, destroy it (`isx destroy isx-ci-linux-1 --skip-confirmation`); the supervisor makes a fresh one.
- **`exposed-host-ports:` in the server's `config.yaml`.** Every instance with network reaches a listed host port with no caller identity, a CI job's included. List none on the server.

## 4. Turn the heartbeat on

Under Settings, Secrets and variables, Actions, Variables, add the repository variable `LINUX_RUNNER_ENABLED` with the value `true`.
Within 10 minutes the "Linux runner heartbeat" workflow shows a run on the server, and the journal shows its instance destroyed and the next one made.
The next same-repository PR's `linux-route` job says unit-tests runs on the project's Linux runner.

The approval of outside contributors' runs, set for the Mac Mini (docs/dev/MAC-CI-SETUP.md), covers this runner too.

## 5. Measure before relying on it

In a quiet window, with no box building or testing, dispatch "Integration Tests" on `main` from the Actions tab.
Its `unit-tests` runs on the server; compare its time with the same job of a recent run on `ubuntu-26.04`.
Keep the runner on only if it is faster, and tune the cores and memory from what the run shows.
Record the numbers in docs/dev/PERFORMANCE-NOTES.md.

## Pausing it for a quiet window

```shell
isx-ci-runner pause     # the running job finishes; no new one starts
isx-ci-runner status
isx-ci-runner resume
```

A pause destroys an idle runner at once and queues a heartbeat that no runner takes, so the route sends new jobs to GitHub's runner straight away.
A job running when the pause came finishes, in at most about 25 minutes.

## Turning it off

Delete `LINUX_RUNNER_ENABLED`, or set it to anything but `true`, and stop the service with `systemctl --user disable --now isx-ci-runner@1`.
Within 25 minutes every unit-tests job runs on `ubuntu-26.04` again.
A rerun of failed jobs does not route again: it keeps the first attempt's choice, so a job that ran on the server goes back there, and waits up to a day if the runner is gone.
Use "Re-run all jobs" instead, which routes again.

## When something is wrong

| What you see | What it means |
|---|---|
| unit-tests failed in "Set up job" with "The project's Linux runner refused this job" | The hook refused untrusted code, as it should; the log says why. |
| unit-tests failed at "Use the instance's GraalVM" | The template's GraalVM is not the line the workflow expects: rebuild the template after the `graalvm` tool moves. |
| Every unit-tests job runs on `ubuntu-26.04` | The heartbeat is off or stale: check the variable, the "Linux runner heartbeat" runs and `journalctl --user -u isx-ci-runner@1`. |
| The journal repeats "could not register a runner" | The token is missing, expired or lacks Administration write. |
| A routed unit-tests job waits for a runner | The service stopped after the route chose the server. Start it, or cancel the run and "Re-run all jobs". |
