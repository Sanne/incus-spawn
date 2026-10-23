# Configuration

`isx init` writes your settings and credentials; this page says where they live, how a credential gets several accounts that templates and instances choose between, and what isx caches on the host.

- `~/.config/incus-spawn/config.yaml` -- auth credentials and global settings, including `host-routes:`
- `~/.config/incus-spawn/mcp.yaml` -- what `isx mcp` lets agents do (see [Delegating from an agent](MCP.md#delegating-from-an-agent-on-your-host-mcp))
- `~/.config/incus-spawn/ssh/` -- managed SSH key pair, per-instance config, and known_hosts
- `~/.config/incus-spawn/images/*.yaml` -- user-level template definitions
- `~/.config/incus-spawn/tools/*.yaml` -- user-level tool definitions
- `.incus-spawn/images/*.yaml` -- project-local template definitions
- `.incus-spawn/tools/*.yaml` -- project-local tool definitions

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

You don't need to write this by hand: `isx init` adds, renames and removes accounts and sets the default.
Re-run it any time to change them.

An instance uses, most specific first:

1. its own choice: `isx branch --account`, `isx account set`, or **a** in the TUI;
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

Everything in `acme-42` now runs as Acme's accounts.
For a one-off, choose per branch: `isx branch review-1 --account github=acme-bot`.

### Managing an instance's accounts

```shell
isx account list                        # accounts, and which instances use each
isx account add github                  # add or replace a GitHub token or account
isx account show review-1               # what review-1 uses, and why
isx account set review-1 github=acme-bot
isx account unset review-1 github       # follow the default again
```

In the TUI, **F3** on an instance shows its accounts and **a** changes them.
The branch dialog (**F4**) lets you pick accounts too.

Changes take effect on the instance's next request, with no restart, and switching GitHub accounts also updates its git identity.
The exception is switching between Claude auth modes (Pro/Max, API key, Vertex), which needs a template built for that mode ([#866](https://github.com/Sanne/incus-spawn/issues/866)).

Changing a default moves every instance that follows it; `isx init` lists them first and lets you keep them where they are.
Renaming an account in `isx init` updates the instances using it, and removing one tells you what still uses it.
An instance left pointing at a missing account stops working rather than using another one; `isx doctor` and `isx account show` point these out.

### Inheriting the outer instance's account

An isx running inside an isx instance serves its own instances from its own `config.yaml`.
To let them use the outer instance's account for one credential instead, choose the reserved account `inherit` for it, like any other account: `isx branch inner-1 --account github=inherit`, `isx account set inner-1 github=inherit`, a template's `accounts:`, or `default: inherit` under `github:` in the inner `config.yaml`.
The inner proxy then presents the outer instance's proof token in place of the inner instance's own, so the outer proxy serves the outer instance's account for that credential only.
Outside an isx instance, `inherit` is refused when you branch, and so is any request that relies on it.
`inherit` cannot be used as the name of an account of your own.

The inner proxy checks its instance's current proof before substituting the outer instance's proof.
A nested process with no proof, a forged proof-shaped token, or an independent account gets no inherited credential.
CA trust and the outer network policy still apply.

A template whose own `accounts:` says `claude: inherit` builds Claude for an API key, the form the outer proxy turns into whichever account it serves.
The git identity of an instance that inherits GitHub is the outer account's when it starts inheriting; if the outer instance later switches GitHub accounts, branch again or run `isx account set` to pick up the new one.

Builds can read Git objects directly from linked host checkouts without changing them or copying their object stores first.
Full clones select all remote branches and tags; shallow clones retain their configured depth.
For full clones, a bounded cache of clean snapshots avoids repeated downloads and packing, including when a linked checkout exists.
Normal upstream updates add new objects to the verified cache without repacking its history:

```yaml
git-cache:
  snapshot-max-mib: 4096
  max-age-days: 7
```

These are the defaults.
Set either limit to `0` to disable persistent retention.
A snapshot larger than `snapshot-max-mib` is used for its build but not kept, so every build clones and verifies that repository in full again; the build warns when this happens, with the snapshot's size and the setting to raise, and also on every build while retention is disabled.
It also warns when keeping a snapshot evicts other recently used ones to stay within `snapshot-max-mib`, since repositories built in turn would then evict each other and be cloned in full on every build.
Active builds keep their temporary data until their reference mounts are detached.
See [Git preparation](dev/GIT-PREPARATION.md) for details.

Git transfers on the host (`isx update-all` fetching linked checkouts, and a build preparing a clean snapshot) show their elapsed time and Git's progress as they run.
Git does not stop to ask for a password: it fails at once instead.
The failure says what isx changed and what to set up, then quotes Git's own output under `Git's output:`, each line marked with `|`.
Nor does ssh ask for a passphrase or about a host key: isx adds `-o BatchMode=yes` to ssh or to your `core.sshCommand` (for a build, only a global one applies), and makes any other ssh fail rather than ask (OpenSSH 8.4 or later).
So load the key into `ssh-agent` and keep the host in `~/.ssh/known_hosts`; when SSH fails, isx says what it did.
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

The `config.yaml` also supports git remote auto-management via `host-paths` and `repo-paths` (see [Git Remotes](../README.md#git-remotes)), and a `searchPaths` list for loading templates and tools from external directories.
Each directory should contain `images/` and/or `tools/` subdirectories following the same YAML schema as the built-in definitions.
Tilde (`~`) expansion is supported for all path settings:

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

Resolution order (later sources override earlier ones with the same name):
1. Built-in (bundled with isx)
2. User (`~/.config/incus-spawn/`)
3. Search paths (in listed order)
4. Project-local (`.incus-spawn/`)

The TUI updates live as instances are started, stopped, created or deleted elsewhere.
To turn this off:

```yaml
tui-live-refresh: false
```

`isx proxy install` and `isx proxy start` wait for the proxy to become healthy after starting the service.
The default is 30 seconds; a healthy proxy answers at once, so a generous default costs nothing.
To change it:

```yaml
ready-timeouts:
  proxy: 1m               # durations: 30s, 1m, 2m ...
```

## Caching

The proxy and build system cache downloads on the host, shared by all templates and branches.
Only content that can be verified is cached; everything is checked against its digest or upstream checksum before it's stored.

Proxy caches (from container traffic):

- **Container image layers** — OCI blobs from Docker Hub, GHCR, and Quay, keyed by SHA256 content digest
- **Maven and Gradle artifacts** — release JARs, POMs, and plugins from Maven Central and the Gradle plugin portal, verified against the upstream checksum and re-confirmed with upstream (a `HEAD` on Maven Central, the checksum file elsewhere) as it ages; see below.
  When the repository is unreachable, cached artifacts are served unconfirmed so builds keep working offline
- **Gradle distributions** — verified against the upstream `.sha256` sidecar, and re-confirmed against it the same way
- **npm tarballs** — from `registry.npmjs.org`, verified against the version's published SHA-512 integrity (its SHA-1 shasum for old versions without one), and re-confirmed against it the same way, so a version removed upstream stops being served

How recently a cached Maven or Gradle artifact, or npm tarball, must have been confirmed with upstream is configurable.
Within `fresh` of its last confirmation it is served straight from the cache.
Within `max-stale` it is served at once and confirmed again in the background, and a copy upstream has changed or withdrawn is evicted for the next request.
Older than that, it is confirmed before it is served.
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

- **DNF packages**, shared by every build, so builds reuse each other's downloads
- **Tool downloads**, reused by rebuilds

All caches live under `~/.cache/incus-spawn/`.
Nothing expires by age or size; an artifact is dropped only when upstream changes or withdraws it.
