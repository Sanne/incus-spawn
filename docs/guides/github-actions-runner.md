---
description: Run a self-hosted GitHub Actions runner in an isx sandbox, a Linux machine of its own, with its Actions cache downloads kept on your host.
---

# Run a self-hosted GitHub Actions runner in an isx sandbox

This guide takes you from an installed isx to a self-hosted GitHub Actions runner in a machine of its own.
The runner takes your repository's jobs and runs them there, and your keys and tokens stay on your host.

isx also keeps on your host a copy of each cache that `actions/cache` restores.
The first job that restores a cache still downloads it from Azure, and the jobs after it restore it from your disk.

## Before you start

You need isx installed and set up with `isx init`: the [Quick Start](../../README.md#quick-start) covers both.
You also need admin rights on the GitHub repository whose jobs the runner takes, since only an admin can add a runner to it.

Choose a repository whose workflows you trust, such as a private one.
A job runs with the same access as an agent in an isx machine, as [Why the machine holds no credential](#why-the-machine-holds-no-credential) explains.
GitHub advises against self-hosted runners on public repositories, where a pull request from a fork can run code on the runner.

## Keep the Actions cache on the host

`actions/cache` downloads each cache from Azure blob storage, at an address under `blob.core.windows.net`.
Add that domain to `~/.config/incus-spawn/config.yaml` on the host:

```yaml
confirmed-cache:
  domains:
    - "*.blob.core.windows.net"
```

From then on, isx keeps a copy of each cache a runner downloads, and serves it again only after Azure confirms that the copy is current.
A job that may no longer read the cache gets Azure's refusal, never the copy.

[A cache for any HTTPS domain](../CONFIGURATION.md#a-cache-for-any-https-domain) says what else it changes, such as the space it takes, and how to empty it.

## Create the template

Create a template for the runner:

```shell
isx templates new tpl-actions-runner
```

Your editor opens a short file.
Fill it in with the packages the runner needs:

```yaml
name: tpl-actions-runner
description: A self-hosted GitHub Actions runner
parent: tpl-minimal
packages:
  - libicu
  - krb5-libs
  - openssl3-libs
  - git
  - tar
  - perl-Digest-SHA
```

The runner needs `libicu`, `krb5-libs` and `openssl3-libs` to start, and `actions/checkout` uses `git`.
`perl-Digest-SHA` provides the `shasum` that checks the runner's download.
Add the packages your jobs build with, such as `java-25-openjdk-devel` or `nodejs`.
If the build finds no `openssl3-libs`, your `tpl-minimal` is older than the base image your isx uses: rebuild both with `isx build tpl-actions-runner --with-parents`.

Then build it:

```shell
isx build tpl-actions-runner
```

[Write an isx template for your project](project-template.md) explains these fields, and [Image schema fields](../TEMPLATES.md#image-schema-fields) lists the others.

## Branch the runner's machine

```shell
isx branch gh-runner --from tpl-actions-runner
```

A shell opens in the machine, in its home directory.
`isx shell gh-runner` opens another one later.

## Register the runner

On GitHub, open the repository's Settings, then Actions, Runners, and click New self-hosted runner.
Choose Linux, and the architecture of your host's processor: x64 for Intel or AMD, ARM64 for Apple or another Arm processor.
GitHub's page shows the [commands that add a runner](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/add-runners), with a registration token that is valid for one hour.

In the machine's shell, run the commands under Download, in order.
Then run the `./config.sh` command under Configure, and press Enter at each question to keep its default.
The runner's name defaults to the machine's, `gh-runner`.

## Run it as a service

Install and start the runner as a service:

```shell
cd ~/actions-runner
sudo ./svc.sh install
sudo ./svc.sh start
```

The service starts again with the machine, after `isx stop gh-runner` and `isx start gh-runner`.
It trusts isx's proxy like your shell does, since isx gives every system service the same trust settings: [Environment bindings](../FEDORA.md#environment-bindings) lists them.

## Check that it works

On GitHub, the repository's Runners page lists `gh-runner` as Idle.

Add a workflow that runs on the runner and caches a file, as `.github/workflows/runner-check.yml` on the repository's default branch.
The Actions tab shows its Run workflow button only once the file is there:

```yaml
on: workflow_dispatch
jobs:
  check:
    runs-on: self-hosted
    steps:
      - uses: actions/checkout@v5
      - uses: actions/cache@v4
        with:
          path: big
          key: runner-check-v1
      - run: test -f big/file || { mkdir -p big && head -c 200M /dev/urandom > big/file; }
```

Run it from the repository's Actions tab, and wait for each run to finish before the next:

1. The first run finds no cache, and `actions/cache` saves one after its last step.
2. The second run restores the cache from Azure.
   Meanwhile, isx downloads a copy of the same cache to your host.
3. On the host, run `isx doctor` until its `Confirmed cache` line shows about 200 MiB, the size of the cache.
4. Run the workflow a third time.
   It restores the cache from your host: in the log of its `actions/cache` step, the `Received` line shows a speed close to your disk's, not your internet connection's.

## Why the machine holds no credential

isx puts none of your keys or tokens into the machine, so a job cannot read one there.
isx keeps them on the host, and adds them to a request as the request leaves the machine: [Credential Isolation](../../README.md#credential-isolation) explains how.

The runner's registration, which `./config.sh` writes, is the machine's own secret: it lets the machine take the jobs of that one repository.

A job can still act through isx's proxy with each account isx serves the machine, as an agent can, even on a template that installs no tool with a credential.
That is why the repository's workflows must be ones you trust.
Never add secrets to the machine yourself: a job reads what the machine holds.

## Restrict what the runner reaches

To keep the jobs off the rest of the internet, branch the runner's machine with `--proxy-only`, one of the [network modes](../../README.md#network-modes).
Run this in place of the branch command above, before you register the runner:

```shell
isx branch gh-runner --from tpl-actions-runner --proxy-only
```

The machine then reaches only the sites isx's proxy serves, and a job cannot download from any other site.
`--airgap` takes the runner offline.

The sites the proxy serves include GitHub only while isx has a GitHub account, from `isx init` or `isx account add github`.
Without one, the runner never connects.
They include Azure's cache storage only while `confirmed-cache:` lists it.

## Clean up

To remove the runner, stop its service and unregister it, in `~/actions-runner` in the machine:

```shell
sudo ./svc.sh uninstall
./config.sh remove
```

`./config.sh remove` asks for a removal token: GitHub's Runners page shows it when you [remove the runner](https://docs.github.com/en/actions/how-tos/manage-runners/self-hosted-runners/remove-runners) there.
Then destroy the machine:

```shell
isx destroy gh-runner
```

## Next steps

- Have an agent work on the same repository in a machine of its own: [Run Claude Code in an isx sandbox](claude-code.md).
