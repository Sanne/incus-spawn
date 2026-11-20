# Configuration

`isx init` writes your settings and credentials.
This page says where they live, how a credential gets several accounts that templates and instances choose between, which host services instances can reach, and what isx caches on the host.

| File | What it holds |
| --- | --- |
| `~/.config/incus-spawn/config.yaml` | Auth credentials and global settings, including [`host-routes:`](INTEGRATIONS.md#a-controller-on-the-host), [`exposed-host-ports:`](#a-service-on-the-host) and `drivers:` |
| `~/.config/incus-spawn/mcp.yaml` | What `isx mcp` lets agents do (see [Delegating from an agent](MCP.md#delegating-from-an-agent-on-your-host-mcp)) |
| `~/.config/incus-spawn/ssh/` | The managed SSH key pair and the per-instance SSH config |
| `~/.config/incus-spawn/images/*.yaml` | User-level template definitions |
| `~/.config/incus-spawn/tools/*.yaml` | User-level tool definitions |
| `.incus-spawn/images/*.yaml` | Project-local template definitions, the ones a cloned repository ships in its `.incus-spawn/` directory |
| `.incus-spawn/tools/*.yaml` | Project-local tool definitions |

## Editor completion

Each of these YAML files has a JSON Schema, which editors that run the YAML language server use to complete keys, show what each one does and underline a mistake.
Add its line as the first line of the file.

| File | First line |
| --- | --- |
| A template | `# yaml-language-server: $schema=https://isx.run/schema/template.json` |
| A tool | `# yaml-language-server: $schema=https://isx.run/schema/tool.json` |
| `config.yaml` | `# yaml-language-server: $schema=https://isx.run/schema/config.json` |
| `mcp.yaml` | `# yaml-language-server: $schema=https://isx.run/schema/mcp.json` |

`isx templates new` writes the template line for you.
isx rewrites `config.yaml` when you run `isx init`, which drops a comment, so map that schema to the file in your editor's settings instead.

The `config.yaml` schema does not flag a key it does not know, since a tool you install may add a section of its own.
The other three flag a misspelled key.

## Credential accounts

You can configure a credential more than once under different names, for example a personal and a client Claude subscription, or two GitHub identities.
Each named copy is an account, and you choose which account each instance uses.
You can even switch a running instance to another account, with no restart.

```yaml
claude:
  accounts:
    personal:
      type: oauth
      oauthToken: "sk-ant-oat01-..."
    acme:
      type: vertex
      cloudMlRegion: europe-west1
      vertexProjectId: acme-prod
  default: personal
github:
  accounts:
    personal:
      token: "ghp_..."
    acme-bot:
      token: "ghp_..."
      email: "bot@acme.example"
      name: "Acme CI"
  default: personal
```

You do not need to write this by hand: `isx init` adds, renames and removes accounts and sets the default.
Re-run it any time to change them.

isx ignores a key it does not read in a tool's credential section, such as `openai.token` written where `openai.apiKey` is meant.
`isx init`, `isx account add` and `isx doctor` name the ignored key, and so does the error when a template needs that credential.

A subscription account serves Claude Code in your instances, but not [`isx ask`](CLI.md#isx-ask), which needs an API key.
Claude Max and Team plans include [monthly API credits](https://platform.claude.com/docs/en/about-claude/api-credits-for-subscribers) for an API key, claimed on claude.ai under Settings > Billing.

Add the key as a second `claude` account with `type: api-key`, and keep the subscription as the default.
`isx init` offers this when it saves a subscription.

An instance uses the account from the first of these that is set:

1. its own choice: `isx branch --account`, `isx account set`, or the `a` key in the TUI;
2. its template's `accounts:`;
3. the credential's `default`.

```yaml
name: tpl-acme
parent: tpl-java
accounts:
  claude: acme
  github: acme-bot
```

When you branch from a template, the new instance takes the template's account choices and keeps them.
Editing the template's `accounts:` later affects only instances branched after the edit.

### Example: keeping a client's work on the client's accounts

```shell
isx build tpl-acme
isx branch acme-42 --from tpl-acme
isx account show acme-42
```

```
acme-42 (branched from tpl-acme):

  claude  acme -- Google Cloud Vertex AI (region: europe-west1, project: acme-prod)
          Pinned by template tpl-acme's accounts: setting, copied onto this instance when it was branched.

  github  acme-bot -- commits as bot@acme.example
          Pinned by template tpl-acme's accounts: setting, copied onto this instance when it was branched.
```

Everything in `acme-42` uses Acme's accounts.
For a single instance, choose the account when you branch: `isx branch review-1 --account github=acme-bot`.

### Managing an instance's accounts

```shell
isx account list                        # accounts, and which instances use each
isx account add github                  # add or replace a GitHub token or account
isx account show review-1               # what review-1 uses, and why
isx account set review-1 github=acme-bot
isx account unset review-1 github       # follow the default again
```

In the TUI, F3 on an instance shows its accounts and the `a` key changes them.
The branch dialog (F4) lets you pick accounts too.

Changes take effect on the instance's next request, with no restart.
An instance switched to another GitHub account takes that account's git identity on its next start or shell.
The exception is switching between Claude auth modes (Pro/Max, API key, Vertex), which needs a template built for that mode ([#866](https://github.com/Sanne/incus-spawn/issues/866)).

The `name` and `email` fields on a GitHub account override the git identity that would otherwise come from the GitHub profile.
When unset, they are derived from the GitHub API.
`isx init` does not prompt for `name`; write it by hand.

Changing a default switches every instance that follows it to the new account; `isx init` lists those instances first and lets you keep them on their current account.

Renaming an account in `isx init` updates the instances using that account, and when you remove an account, `isx init` tells you what still uses it.

An instance set to an account that no longer exists stops working rather than using another account.
`isx doctor` and `isx account show` report such instances.

### Inheriting the outer instance's account

An isx running inside an isx instance serves its own instances from its own `config.yaml`.
The reserved account `inherit` lets those inner instances use, for one credential, the account of the outer instance, which is the instance that isx runs in.

Select `inherit` like any other account, in any of these ways:

- `isx branch inner-1 --account github=inherit`;
- `isx account set inner-1 github=inherit`;
- a template's `accounts:`;
- `default: inherit` under `github:` in the inner `config.yaml`.

isx gives each instance a proof, a token that the instance sends in place of a credential.
With `inherit`, the inner proxy presents the outer instance's proof in place of the inner instance's own proof, so the outer proxy serves the outer instance's account for that credential only.

The inner proxy checks its instance's current proof before substituting the outer instance's proof.
CA trust and the outer network policy still apply.

`inherit` does not share anything else.
A nested process with no proof, a forged proof-shaped token, or an independent account gets no inherited credential.
Outside an isx instance, `inherit` is refused when you branch, and so is any request that relies on it.
You cannot name an account of your own `inherit`.

`isx doctor` counts an inherited default as configured inside an instance whose secret `isx doctor` can read, and warns about the default otherwise.

A template whose own `accounts:` says `claude: inherit` builds Claude for an API key, the form of request that the outer proxy converts to whichever account it serves.

The git identity of an instance that inherits GitHub is the outer account's at the time the instance starts inheriting.
If the outer instance later switches GitHub accounts, branch again or run `isx account set` to take the new account's identity.

## Git cache

Builds can read Git objects directly from linked host checkouts without changing the checkouts or copying their object stores first.

A full clone's snapshot, a clean copy of the repository that the build prepares on the host, holds the branch or tag the template asks for, or else the remote's default branch, with all its history.
The instance fetches other branches itself when it needs them; shallow clones retain their configured depth.

Each build's copy also gets the tags in that history, so `git describe` gives the same answer as on a regular clone.

For full clones, a bounded cache of clean snapshots avoids repeated downloads and packing, including when a linked checkout exists.
Normal upstream updates add new objects to the verified cache without repacking its history.
The cache has two limits, shown here with their defaults:

```yaml
git-cache:
  snapshot-max-mib: 16384
  max-age-days: 7
```

Set either limit to `0` to disable persistent retention.
A snapshot larger than `snapshot-max-mib` is used for its build but not kept, so every build clones and verifies that repository in full again.

The build warns:

- when a snapshot is too large to keep, with the snapshot's size and the setting to raise;
- on every build while retention is disabled;
- when keeping a snapshot evicts other recently used ones to stay within `snapshot-max-mib`, since repositories built in turn would then evict each other and be cloned in full on every build.

Active builds keep their temporary data until their reference mounts are detached.
See [Git preparation](dev/GIT-PREPARATION.md) for details.

## Git transfers on the host

Git transfers on the host are `isx update-all` fetching linked checkouts, and a build preparing a clean snapshot.
They show their elapsed time and Git's progress as they run.
Neither Git nor ssh stops to ask a question: a transfer that would ask fails at once instead.

What you see when a transfer fails:

- What isx changed and what to set up.
- Git's own output under `Git's output:`, each line marked with `|`.
- When SSH fails, what isx did.

What to do before a transfer:

- Load the key into `ssh-agent`: ssh does not ask for a passphrase.
- Keep the host in `~/.ssh/known_hosts`: ssh does not ask about a host key.
- Do not rely on a password prompt: Git fails at once instead of asking for one.

Where it can, isx adds `-o BatchMode=yes` to ssh or to your `core.sshCommand`, which stops both ssh prompts; for a build, only a global `core.sshCommand` applies.
isx makes any other ssh, such as a custom `GIT_SSH` or a wrapper, fail rather than ask (OpenSSH 8.4 or later).

isx ends a transfer that stops making progress, instead of waiting for the transfer to finish:

```yaml
git-transfer:
  stall: 5m      # ended after this long with no output, no I/O and next to no CPU in Git or its helpers
  timeout: 3h    # ended after this long in any case
```

These are the defaults; `0` turns a bound off.
Quiet work such as resolving deltas still uses the CPU, so it is not a stall.
An ended `isx update-all` fetch is a warning, and the checkout stays unchanged.
An ended build preparation falls back to cloning inside the build.

## Search paths for templates and tools

`config.yaml` also holds `host-paths` and `repo-paths`, with which isx manages git remotes for you (see [Git Remotes](../README.md#git-remotes)), and a `searchPaths` list for loading templates and tools from external directories.
Each directory holds `images/` and/or `tools/` subdirectories following the same YAML schema as the built-in definitions.
Every path setting expands a tilde (`~`):

```yaml
searchPaths:
  - ~/my-templates
  - /absolute/path/to/templates
```

```
my-templates/
  images/
    quarkus.yaml
  tools/
    gradle.yaml
```

isx loads definitions in this order, and a later source overrides an earlier definition with the same name:

1. Built-in (bundled with isx)
2. User (`~/.config/incus-spawn/`)
3. Search paths (in listed order)
4. Project-local (`.incus-spawn/`)

## External tools that drive isx

`drivers:` says what an external tool, a program outside isx that creates instances for you, such as an orchestrator, may do to the instances it owns.
An external tool owns an instance it branched with `isx branch --owner <name>`.
isx does not grant an external tool any permission unless you list the permission here:

```yaml
drivers:
  orca:
    destroy: true    # isx destroy --owner orca may delete orca's instances
```

| Key | Default | What it does |
|-----|---------|--------------|
| `destroy` | `false` | Lets `isx destroy --owner <name>` delete the instances that tool owns |

An external tool never destroys an instance it does not own, such as your own branch of one of its instances.
An entry isx cannot read does not grant any permission, and isx says which entry and why.

## A service on the host

A service listening only on your host's loopback, such as Ollama, can be made reachable from every instance.
List its port in `~/.config/incus-spawn/config.yaml`:

```yaml
exposed-host-ports: [11434]
```

Instances then reach it at `host.isx.internal:11434`, for example with this in the template:

```yaml
env:
  - name: OLLAMA_HOST
    value: host.isx.internal:11434
```

The proxy listens on that port at the bridge address, the host address where instances already reach the proxy.
It passes each connection unchanged to the same port on `127.0.0.1`, or on `[::1]` when nothing answers on `127.0.0.1`.
Nothing is installed in the instance, and the service keeps listening on loopback alone.

| What | How it behaves |
|---|---|
| Who can connect | Every instance with network, including one built from a cloned repository's own template |
| Other machines on your network | Refused, even when your firewall lets them reach the bridge address |
| `--proxy-only` and `--airgap` | Cannot reach any exposed port |
| What passes | Raw TCP: no TLS, no headers added, no credentials, no path filter |
| What the service sees | A caller on loopback |
| Unlisted loopback ports | Stay unreachable |
| A port isx uses (443, 18443, 18080, 53) | Refused, with a warning in the proxy's log |

### What exposing a port gives away

Expose a port only when you would let any instance use that service as you do.
Many development services trust any caller on loopback completely.
isx does not filter the connections it passes to the service, so an instance can do whatever the service allows.

There is no list of templates and no caller identity, so every instance with network gets the same access.

Ollama is an example of the access an exposed port gives.
Its API has no authentication, and it can pull, create, push and delete models.
Exposing 11434 gives every instance all of those operations, and the use of any cloud account Ollama is signed in to.

A service that already listens on every address, or on the bridge address, was reachable from instances before you listed its port, where your firewall allows it.
Listing its port does not change how instances reach it.

On Linux the proxy cannot bind such a port on its own address, and `isx doctor` warns that instances reach whatever program holds the port.
On macOS the proxy binds the port all the same, so instances reach the service through the proxy and there is nothing to warn about.

## Host CA propagation

By default an instance trusts only the isx CA and Fedora's roots.
On a host behind TLS inspection, or for an isx inside another isx, you can make isx install the host's own CA anchors in every instance too:

```yaml
host-ca:
  propagate: true
```

See [Host CAs](FEDORA.md#host-cas-opt-in) for what isx installs and where.

## Preferred agent

isx keeps the coding agent you prefer as one key in `config.yaml`.
`isx agent` shows it and sets it:

```yaml
preferred-agent: claude
```

It limits nothing: a template installs the agent its `tools:` list names, whatever you prefer.
Changing it changes no template.

## TUI live refresh

The TUI updates live as instances are started, stopped, created or deleted elsewhere.
To turn this off:

```yaml
tui-live-refresh: false
```

## Proxy ready timeout

`isx proxy install` and `isx proxy start` wait for the proxy to become healthy after starting the service.
The default is 30 seconds; a healthy proxy answers at once, so a generous default does not slow a healthy start.
To change it:

```yaml
ready-timeouts:
  proxy: 1m               # durations: 30s, 1m, 2m ...
```

The same section holds `container` and `vm`: how long a start waits for the instance to answer before the start fails.
Their defaults are 30s and 2m.

## Storage pool

Every instance lives on one btrfs copy-on-write pool, which `isx init` creates when Incus has none.
On Linux, how isx backs the pool depends on the filesystem under `/var/lib/incus`.

| Host filesystem | Backing | Size |
|---|---|---|
| btrfs | a subvolume of that filesystem | shares the filesystem's free space |
| anything else | a sparse loop file, `/var/lib/incus/disks/cow.img` | 100 GiB, thin-provisioned |

A loop file on btrfs would nest one btrfs inside another.
Every write in an instance would then be copied twice, with two sets of metadata.

isx mounts the loop file to commit every 300 seconds, not 30, since instances hold disposable working data.
isx also compresses data inside the pool, since no layer under the pool compresses it.

On macOS the pool lives on the appliance's own disk, the appliance being the Linux VM that isx runs; `isx vm resize` grows it.

A subvolume pool has no cap of its own, so it can fill the host's disk.
`isx doctor` warns when the pool is at least 90% full and has less than 5 GiB free.
Each instance's `--disk` limit still applies.

A subvolume pool also changes the host filesystem as a whole.
Incus enables btrfs quotas to enforce the `--disk` limit, and they cover the whole filesystem.
Each time Incus starts on the host, it mounts the filesystem with `user_subvol_rm_allowed`.
That option lets a user delete any subvolume they can write to, not only in the pool.

`isx init` sets up a new pool only.
For a pool that is already a loop file on btrfs, `isx doctor` warns and links to the steps for moving it.

## Caching

The proxy and build system cache downloads on the host, shared by all templates and branches.
isx caches only content it can verify: isx checks everything against its digest or upstream checksum before storing it.

Proxy caches, from instance traffic:

- **Container image layers**: OCI blobs from Docker Hub, GHCR and Quay, keyed by SHA256 content digest.
- **Container image manifests**: from the same registries, so pulling a cached image does not count against Docker Hub's pull limit.
  The proxy confirms each one with a `HEAD` request carrying your token, which Docker Hub does not count as a pull.
  When the registry throttles or is down, the proxy serves a public tag at its last known version.
- **Maven and Gradle artifacts**: release JARs, POMs and plugins from Maven Central, the Gradle plugin portal and the [repositories you declare](#more-maven-repositories).
  The proxy checks each against the upstream checksum, and checks it with upstream again as it ages.
  When the repository is unreachable, the proxy serves cached artifacts unconfirmed, so builds keep working offline.
- **Gradle distributions**: verified against the `.sha256` file upstream publishes beside the distribution, and re-confirmed against it the same way.
- **npm tarballs**: from `registry.npmjs.org`, verified against the version's published SHA-512 integrity, or its SHA-1 shasum for old versions without one.
  The proxy re-confirms the tarballs against that value the same way, so the proxy stops serving a version removed upstream.

How recently a cached Maven or Gradle artifact, or npm tarball, must have been confirmed with upstream is configurable:

- Within `fresh` of its last confirmation, the proxy serves the artifact from the cache without asking upstream.
- Within `max-stale`, the proxy serves the artifact at once and confirms it again in the background; the proxy evicts a copy that upstream has changed or withdrawn, so the next request does not receive that copy.
- Older than `max-stale`, the proxy confirms the artifact before serving it.

Every repository can withdraw a release, Maven Central included, for example a release that turns out to hold malware.
So these durations are how long the proxy may go on serving a release after upstream withdrew it.
Gradle Plugin Portal artifacts are always confirmed first, because authors delete versions there often.
The defaults:

```yaml
artifact-cache:
  fresh: 2h        # durations: 30m, 2h, 7d ... (Maven Central: 12h while this section is absent)
  max-stale: 7d
```

Maven Central gets a longer `fresh` of 12 hours while `config.yaml` has no `artifact-cache:` section.
Each confirmation is a request to Central, so this keeps a busy host further from Central's rate limit.
In exchange, the proxy may serve a release Central withdrew for up to 12 hours.

Once you write `artifact-cache:`, even one key of it, its durations apply to Central too.

Setting both to `0` makes the proxy confirm every cached artifact before serving it.
On Maven Central this mostly adds latency: Central never changes a published artifact, and withdraws one only rarely.
Without isx, Maven never checks a release again once it is in `~/.m2`, so any finite duration notices a withdrawal sooner.

### Registry rate limits

A build that gets HTTP 429 (Too Many Requests) was refused by the registry, not by isx.
Registries such as Maven Central and Docker Hub count requests per public address or per account, so a host on a shared office or CI address reaches the limit sooner.

Sonatype's advice to heavy users of Central is to put a shared cache between their builds and Central, so that Central sees fewer requests from one address ([Sonatype's explanation](https://central.sonatype.org/faq/429-no-repo-manager/)).
The proxy is that cache for every template and instance on the host: ten instances that build the same project ask Central once.

While a registry refuses requests, the proxy behaves as follows:

- It stops asking that registry for a while: for the time the registry says in its `Retry-After` header, at most 60 seconds, else 30 seconds.
- It serves the artifacts it has cached in the meantime, so a build that has run before keeps working.
- For a Maven, Gradle or npm artifact it has not cached, it waits and asks again, within the time the build tool allows.
  When that time is not enough, the build tool gets the registry's own answer, with how long to wait.
- A container registry's 429 is passed on as it came, because Docker Hub's limit lasts hours, longer than a client waits.

What you give up: a copy served during the pause is not confirmed with the registry.
If the registry withdrew that release in the last hours, the proxy may still serve it, as it does when the registry is unreachable.

The proxy cannot lift the limit.
A host on a throttled address with an empty cache is still refused, until the limit ends or you name a [mirror](#a-mirror-for-a-maven-repository).

What improves is that the registry sees fewer requests, builds keep working from the cache, and the message says whose limit it is.

To see a throttle, look for the registry's name in the proxy log, in the table of `isx proxy activity` and in the warnings of `isx doctor`.
Each says that the limit is the registry's.
`isx doctor` keeps warning for 30 minutes after the registry's last 429.

Container image manifests are cached too.
Docker Hub counts a pull of an image manifest, but not a `HEAD` request for one, according to [Docker's documentation](https://docs.docker.com/docker-hub/usage/pulls/).

Pulling an image you already pulled therefore does not count against the limit of 100 pulls per 6 hours that Docker Hub gives an anonymous address, and works during a throttle for a public tag.

The same handling applies to every [repository you declare](#more-maven-repositories) and to the npm registry.
For Central, `fresh` and `max-stale` above decide how many confirmations the proxy sends.

The cache cannot cover a build that runs on the host itself, since only traffic from instances passes through the proxy.
Sonatype names such traffic that bypasses the cache as the usual cause of a limit that a cache did not prevent ([Sonatype's explanation](https://central.sonatype.org/faq/429-has-repo-manager/)).

### More Maven repositories

The proxy caches artifacts from other Maven repositories once you declare them in `config.yaml`.
Your builds then get the same cache and rate limit handling for Google's Maven repository, JitPack, Clojars or your company's repository manager.
Only `config.yaml` can declare one, never a template, since the cache serves every instance on the host.

```yaml
maven-repositories:
  - url: https://maven.google.com/
    access: public
    confirm: sidecar           # or checksum-header, or etag-sha1
    replaces-releases: false
    fresh: 2h                  # optional, 0 by default
    max-stale: 7d              # optional, 0 by default
  - url: https://artifactory.example.com/artifactory/libs-release/
    access: private
    credentials: artifactory   # the credential namespace isx injects there
    confirm: checksum-header
    replaces-releases: false
```

The proxy cannot find out these facts by itself, so each entry states them:

- `url`: the repository, up to where the Maven coordinates begin.
  A path under no declared URL is relayed uncached.
- `access`: `public`, or `private` with the `credentials` namespace whose account isx injects there.
- `confirm`: how a cached copy is checked against upstream, one of these three.
  - `checksum-header`: the server sends an `X-Checksum-SHA1` header with each artifact, as Artifactory does.
  - `etag-sha1`: the server sends the artifact's SHA-1 as its `ETag`, as a Nexus 3 hosted repository does.
    A Nexus 3 proxy repository, or a group serving from one, sends its upstream's `ETag` instead.
    Those artifacts are then checked by their `.sha1` file, or served uncached where that is unsound.
  - `sidecar`: only when the repository publishes each artifact and its `.sha1` file together and never replaces them.
- `replaces-releases`: `true` when a release can be uploaded again with other content.
  Such a repository is confirmed before every serve, and never served offline.
- `fresh` and `max-stale`: as in `artifact-cache:` above, for this repository alone.
  Both are `0` by default, so every hit is confirmed first until you raise them.

The proxy trusts these declarations.
Declared wrongly as never replacing releases, a repository can get a stale artifact served from the cache, for up to its `max-stale` and whenever upstream cannot be reached.
A repository described too cautiously is only slower.
The proxy log names any entry isx ignores because it does not hold together.

Every instance shares a public repository's cache.
A request to it that carries its own credential is never cached, in case the repository is private after all.

A private repository is cached only through a credential isx injects, from a tool with a proxy credential for that domain.

It needs `confirm: checksum-header` or `etag-sha1`, since reading its `.sha1` file does not show that the credential may still read the artifact.
The proxy keeps a separate cache per credential, and confirms every hit with upstream using that credential.
An account that loses access is refused at once.

### A mirror for a Maven repository

A cold build fetches every artifact once, so it fails while Maven Central rate-limits your host.
You can name a mirror the proxy asks first for an artifact it has not cached yet.
When the mirror does not have the artifact, or cannot serve it, the proxy asks the repository itself.

```yaml
maven-mirrors:
  - repository: https://repo.maven.apache.org/maven2/
    mirror: https://maven-central.storage-download.googleapis.com/maven2/
  - repository: https://repo1.maven.org/maven2/
    mirror: https://maven-central.storage-download.googleapis.com/maven2/
```

`repository` names a repository the proxy caches: one of Maven Central's two names, the Gradle plugin portal, or a public repository you declared above.
Central's two names are separate repositories, so a mirror for both needs two entries.
Only `config.yaml` can name a mirror, never a template.
A private repository, or one that replaces releases, cannot take a mirror.

The proxy does not send the mirror your build's headers, so your credentials do not reach it.
It checks a mirrored artifact against the mirror's checksum, and confirms it with the repository at its next use.
A copy the repository disagrees with is dropped, and the repository's own is fetched.
The proxy log names each artifact fetched from a mirror.

Naming a mirror means trusting its operator for the build that first fetches an artifact.
Until the repository confirms that copy, you get the bytes the mirror serves, not the repository's.
A release the repository withdrew comes back from a mirror that still holds it, until the next use evicts it again.

`maven-metadata.xml` and SNAPSHOTs still come only from the repository.

Build-time caches:

- **DNF packages**, shared by every build, so builds reuse each other's downloads.
- **Tool downloads**, reused by rebuilds.

The DNF cache is a volume in the Incus storage pool; every other cache lives under `~/.cache/incus-spawn/`.
`isx clean cache` removes them all.
Nothing expires by age or size; the proxy drops an artifact once a confirmation finds that upstream changed or withdrew it.
