# Configuration

`isx init` writes your settings and credentials.
This page says where they live, how a credential gets several accounts that templates and instances choose between, and what isx caches on the host.

| File | What it holds |
| --- | --- |
| `~/.config/incus-spawn/config.yaml` | Auth credentials and global settings, including `host-routes:` |
| `~/.config/incus-spawn/mcp.yaml` | What `isx mcp` lets agents do (see [Delegating from an agent](MCP.md#delegating-from-an-agent-on-your-host-mcp)) |
| `~/.config/incus-spawn/ssh/` | The managed SSH key pair, per-instance config, and known_hosts |
| `~/.config/incus-spawn/images/*.yaml` | User-level template definitions |
| `~/.config/incus-spawn/tools/*.yaml` | User-level tool definitions |
| `.incus-spawn/images/*.yaml` | Project-local template definitions |
| `.incus-spawn/tools/*.yaml` | Project-local tool definitions |

## Credential accounts

You can configure a credential more than once under different names, for example a personal and a client Claude subscription, or two GitHub identities, and choose which one each instance uses.
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
  default: personal
```

You do not need to write this by hand: `isx init` adds, renames and removes accounts and sets the default.
Re-run it any time to change them.

An instance uses, most specific first:

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
Editing `accounts:` later only affects instances branched after that.

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

Everything in `acme-42` runs as Acme's accounts.
For a one-off, choose per branch: `isx branch review-1 --account github=acme-bot`.

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

Changes take effect on the instance's next request, with no restart, and switching GitHub accounts also updates its git identity.
The exception is switching between Claude auth modes (Pro/Max, API key, Vertex), which needs a template built for that mode ([#866](https://github.com/Sanne/incus-spawn/issues/866)).

Changing a default moves every instance that follows it; `isx init` lists them first and lets you keep them where they are.
Renaming an account in `isx init` updates the instances using it, and removing one tells you what still uses it.

An instance left pointing at a missing account stops working rather than using another one.
`isx doctor` and `isx account show` point these out.

### Inheriting the outer instance's account

An isx running inside an isx instance serves its own instances from its own `config.yaml`.
The reserved account `inherit` lets them use the outer instance's account for one credential instead.

Select it like any other account, in any of these ways:

- `isx branch inner-1 --account github=inherit`;
- `isx account set inner-1 github=inherit`;
- a template's `accounts:`;
- `default: inherit` under `github:` in the inner `config.yaml`.

The inner proxy then presents the outer instance's proof token in place of the inner instance's own, so the outer proxy serves the outer instance's account for that credential only.
The inner proxy checks its instance's current proof before substituting the outer instance's proof.
CA trust and the outer network policy still apply.

It shares nothing else.
A nested process with no proof, a forged proof-shaped token, or an independent account gets no inherited credential.
Outside an isx instance, `inherit` is refused when you branch, and so is any request that relies on it.
You cannot name an account of your own `inherit`.

A template whose own `accounts:` says `claude: inherit` builds Claude for an API key, the form the outer proxy turns into whichever account it serves.
The git identity of an instance that inherits GitHub is the outer account's when it starts inheriting.
If the outer instance later switches GitHub accounts, branch again or run `isx account set` to pick up the new one.

## Git cache

Builds can read Git objects directly from linked host checkouts without changing them or copying their object stores first.
Full clones select all remote branches and tags; shallow clones retain their configured depth.

For full clones, a bounded cache of clean snapshots avoids repeated downloads and packing, including when a linked checkout exists.
Normal upstream updates add new objects to the verified cache without repacking its history.
The cache has two limits, shown here with their defaults:

```yaml
git-cache:
  snapshot-max-mib: 4096
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

- Load the key into `ssh-agent`.
  isx adds `-o BatchMode=yes` to ssh or to your `core.sshCommand`, so ssh does not ask for a passphrase; for a build, only a global `core.sshCommand` applies.
- Keep the host in `~/.ssh/known_hosts`, so ssh has no host key to ask about.
  isx makes any other ssh fail rather than ask (OpenSSH 8.4 or later).
- Do not count on a password prompt: Git fails at once instead of asking for one.

A transfer that stops making progress is ended without waiting for it:

```yaml
git-transfer:
  stall: 5m      # ended after this long with no output, no I/O and next to no CPU in Git or its helpers
  timeout: 3h    # ended after this long in any case
```

These are the defaults; `0` turns a bound off.
Quiet work such as resolving deltas still uses the CPU, so it is not a stall.
An ended `isx update-all` fetch is a warning, and the checkout stays as it was.
An ended build preparation falls back to cloning inside the build.

## Search paths for templates and tools

`config.yaml` also holds git remote auto-management via `host-paths` and `repo-paths` (see [Git Remotes](../README.md#git-remotes)), and a `searchPaths` list for loading templates and tools from external directories.
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

Resolution order, later sources overriding earlier ones with the same name:

1. Built-in (bundled with isx)
2. User (`~/.config/incus-spawn/`)
3. Search paths (in listed order)
4. Project-local (`.incus-spawn/`)

## TUI live refresh

The TUI updates live as instances are started, stopped, created or deleted elsewhere.
To turn this off:

```yaml
tui-live-refresh: false
```

## Proxy ready timeout

`isx proxy install` and `isx proxy start` wait for the proxy to become healthy after starting the service.
The default is 30 seconds; a healthy proxy answers at once, so a generous default costs nothing.
To change it:

```yaml
ready-timeouts:
  proxy: 1m               # durations: 30s, 1m, 2m ...
```

## Caching

The proxy and build system cache downloads on the host, shared by all templates and branches.
Only content that can be verified is cached: isx checks everything against its digest or upstream checksum before storing it.

Proxy caches, from instance traffic:

- **Container image layers**: OCI blobs from Docker Hub, GHCR and Quay, keyed by SHA256 content digest.
- **Maven and Gradle artifacts**: release JARs, POMs and plugins from Maven Central and the Gradle plugin portal, verified against the upstream checksum and re-confirmed with upstream as they age (a `HEAD` on Maven Central, the checksum file elsewhere).
  When the repository is unreachable, the proxy serves cached artifacts unconfirmed, so builds keep working offline.
- **Gradle distributions**: verified against the upstream `.sha256` sidecar, and re-confirmed against it the same way.
- **npm tarballs**: from `registry.npmjs.org`, verified against the version's published SHA-512 integrity, or its SHA-1 shasum for old versions without one.
  The proxy re-confirms them against it the same way, so a version removed upstream stops being served.

How recently a cached Maven or Gradle artifact, or npm tarball, must have been confirmed with upstream is configurable:

- Within `fresh` of its last confirmation, the proxy serves it straight from the cache.
- Within `max-stale`, the proxy serves it at once and confirms it again in the background; a copy upstream has changed or withdrawn is evicted for the next request.
- Older than that, the proxy confirms it before serving it.

Gradle Plugin Portal artifacts are always confirmed first, since the portal can delete a published version.
The defaults:

```yaml
artifact-cache:
  fresh: 2h        # durations: 30m, 2h, 7d ...
  max-stale: 7d
```

Setting both to `0` confirms every hit before serving it.
This is not recommended: Maven Central does not allow published artifacts to change, so it only adds latency.

Build-time caches:

- **DNF packages**, shared by every build, so builds reuse each other's downloads.
- **Tool downloads**, reused by rebuilds.

All caches live under `~/.cache/incus-spawn/`.
Nothing expires by age or size; an artifact is dropped only when upstream changes or withdraws it.
