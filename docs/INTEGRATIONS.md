# Driving isx from other tools

Tools that run coding agents in parallel, such as Orca and herdr, can give each agent an isx instance of its own.
This page shows how to connect them, what isx needs from any other tool, and how a program on your host is reached from instances.

## How a tool connects

Which way a tool connects depends on what it can do, not on a choice you make.

| The tool | What isx does | Example |
|----------|---------------|---------|
| Runs a command to create a machine and another to destroy it | The tool drives isx through ordinary commands | Orca |
| Only keeps a list of SSH machines | isx adds each instance to the tool's list, and removes it when you destroy the instance | herdr |

Either way, you add the matching isx tool (`herdr` or `orca`) to a template and build that template, as the sections below show.
Agents still get no credentials in their instance: the proxy on your host adds the credentials to their requests, as for any branch.

## herdr

[herdr](https://herdr.dev) runs agents in terminal workspaces, on your host or on SSH machines it lists.

1. Install herdr on your host.
2. Add the `herdr` tool to a template and build it:

   ```yaml
   name: tpl-herdr
   parent: tpl-minimal
   tools:
     - herdr
   ```

3. Branch it: `isx branch agent-1 --from tpl-herdr`.

isx adds `agent-1` to herdr as a machine when the instance first starts, and herdr starts its server in the instance.
`herdr --machine agent-1 agent list` then reaches that server.
When the instance is stopped, connecting to it from herdr starts it.

When you destroy the instance, isx removes the machine from herdr.
A machine you added to herdr yourself is never removed by isx.

herdr always checks a machine's host key, which isx records on your host on every start.
A machine added by an older isx reaches its instance again after that instance's next start.
From then on herdr can start that instance too.

An open herdr reconnects to its machines by itself, so it starts a stopped instance again within two minutes.
To keep an instance stopped, run `herdr machine disable` with the id `herdr machine list` shows, and `herdr machine enable` later.

In the TUI, the instance's details (F3) say isx added it to herdr.

## Orca

[Orca](https://github.com/stablyai/orca) can run each workspace on a machine of its own, through an environment recipe.
A recipe is four commands: one creates the machine, and three suspend, resume and destroy it.
isx's support for Orca is a first version: setting it up takes a few steps, which later releases will remove.

1. Build a template with the `orca` tool, which Orca's server needs, and with your repository cloned as the template's working directory (`workdir`):

   ```yaml
   name: tpl-orca
   parent: tpl-java
   tools:
     - orca
   repos:
     - url: https://github.com/you/your-project.git
       path: ~/your-project
   ```

2. Let Orca destroy the instances it creates, in `~/.config/incus-spawn/config.yaml`.
   The recipe also uses this permission to destroy an instance that a failed create left behind:

   ```yaml
   drivers:
     orca:
       destroy: true
   ```

3. Download the recipe script for your isx version into a directory on your `PATH`.
   It needs `bash`, `jq`, and `sha256sum` or `shasum`:

   ```shell
   mkdir -p ~/.local/bin
   curl -fsSLo ~/.local/bin/isx-orca-recipe https://raw.githubusercontent.com/Sanne/incus-spawn/v<version>/integrations/orca/isx-orca-recipe
   chmod +x ~/.local/bin/isx-orca-recipe
   ```

   Make sure `~/.local/bin` is on the PATH of the Orca desktop app.

4. Add a recipe to your repository's `orca.yaml`, naming the template:

   ```yaml
   environmentRecipes:
     - id: isx
       name: isx sandbox
       create: isx-orca-recipe create --from tpl-orca
       suspend: isx-orca-recipe suspend
       resume: isx-orca-recipe resume
       destroy: isx-orca-recipe destroy
   ```

5. In Orca, enable **Settings → Experimental → Cloud VM**.
   Check that **isx sandbox** appears under **Recipes**, then select it under **Run on** when creating a worktree.
   If it does not appear, make sure `orca.yaml` is in your repository's main checkout, not only in a worktree.

   To check the setup without creating an instance:

   ```shell
   orca vm recipe doctor isx --repo-path /absolute/path/to/your/repo --json
   ```

Each workspace then gets an instance named from Orca's runtime id, such as `orca-3f2a6c1e0b5d`.
Orca reaches the instance over SSH, adds the workspace's Git worktree under the template's workdir, and runs its agents in that worktree.
The recipe supports Orca's worktree mode only, in which Orca makes the worktree itself.

A workspace's instance gets what its template gives any branch: an instance of a [coordinator template](MCP.md#a-coordinator-in-an-instance) is a coordinator, whose agent may control other instances.
To make workspaces that are not coordinators, name in the recipe's `--from` a child template whose `coordinator:` holds only `isx-control-capability: false`.

### Faster first connections (experimental)

On the first connection to each new instance, Orca uploads its server and installs Node.
You can include them in your template instead, so new instances skip that step.

1. Let Orca finish connecting to one instance.
   In that instance, save Orca's installed server to an archive:

   ```shell
   cd ~/.orca-remote && tar -czf ~/orca-server-cache.tar.gz orcad-* runtimes/node-*
   ```

2. On your computer, copy the archive out and unpack it, replacing `INSTANCE` with that instance's name:

   ```shell
   scp INSTANCE:/home/agentuser/orca-server-cache.tar.gz .
   mkdir -p ~/.cache/incus-spawn/orca-server-cache
   tar -xzf orca-server-cache.tar.gz -C ~/.cache/incus-spawn/orca-server-cache
   ```

3. Run `isx templates edit tpl-orca` and change its `- orca` tool entry to the one below, adding the host resource:

   ```yaml
   tools:
     - orca:
         server-cache: /home/agentuser/orca-server-cache
   host-resources:
     - source: ~/.cache/incus-spawn/orca-server-cache
       path: /home/agentuser/orca-server-cache
       mode: copy
   ```

4. Run `isx build tpl-orca`.

The saved server only works with the Orca version it came from.
After you update Orca, repeat these steps.
To go back to the default, remove `server-cache` and the host resource, then rebuild.

## Any other tool

A tool with commands to create and destroy a machine can use isx the same way as the Orca recipe does:

| Step | Command |
|------|---------|
| Create | `isx branch <name> --from <template> --owner <tool> --owner-id <workspace-id> --format=json` |
| Suspend | `isx stop <name>` |
| Resume | `isx start <name>` |
| Destroy | `isx destroy <name> --owner <tool> --owner-id <workspace-id>` |

`<tool>` is a stable name for the tool, such as `my-runner`: lowercase letters, digits and `-`, at most 32 characters.

`<workspace-id>` is that tool's identifier for this particular workspace or runtime, not the tool's name or an isx-generated value.
Use the tool's existing workspace ID, or generate a unique ID when allocating a workspace and save it for later lifecycle calls.

Pass the same `<workspace-id>` to create and destroy; isx records it on the instance and checks it before deleting the instance.
The id must contain 1 to 128 letters, digits, or `.`, `_`, `:`, `-`.

For example, a runner creating its workspace `job-42` could use:

```shell
isx branch runner-job-42 --from tpl-java --owner my-runner --owner-id job-42 --format=json
# Later, when deleting that workspace:
isx destroy runner-job-42 --owner my-runner --owner-id job-42
```

That example also needs `drivers: {my-runner: {destroy: true}}` in the isx configuration.
The Orca recipe passes `--owner` and `--owner-id` for you.

`isx branch --format=json` prints how to reach the instance over SSH, including a `ProxyCommand` that starts a stopped instance.
The [CLI reference](CLI.md#isx-branch-fields) lists its fields.

`isx list` shows which tool owns each instance, and a tool can destroy only the instances it owns, once `drivers:` lets it.
A tool that only knows SSH machines can reach any instance whose template installs `sshd`, as `ssh <name>`.

## A service on the host

A service listening only on your host's loopback, such as Ollama, can be made reachable from every instance.
List its port in `~/.config/incus-spawn/config.yaml`:

```yaml
exposed-host-ports: [11434]
```

Instances then reach it at `host.isx.internal:11434`, for example with `OLLAMA_HOST=host.isx.internal:11434` in the template's `env:`.

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

In that case the proxy cannot bind that port on its own address, and `isx doctor` warns that instances reach whatever program holds the port.

## A controller on the host

A controller is a program of your own on the host, such as one that agents report to, and instances can reach it by a name you choose.
The proxy carries the calls, so this works in `--proxy-only` mode too.
The proxy also tells the controller which instance is calling.
List the name in `~/.config/incus-spawn/config.yaml`:

```yaml
host-routes:
  work.isx.internal:
    target: 127.0.0.1:7070       # on this host's loopback: localhost, a 127.x.x.x address or [::1]
    templates: [tpl-isx-agent]   # required: only instances of these templates may call it
```

An instance then calls `https://work.isx.internal/...`, sending the secret isx gave it at its last start as the header `X-Isx-Instance-Secret`.
That secret is in the file `$ISX_INSTANCE_SECRET_FILE` names.

The proxy checks the secret and the instance's address together, and the instance's template against `templates:`.
It forwards the request over plain HTTP to the target, with the headers `X-Isx-Instance` and `X-Isx-Template` naming the calling instance and its template.

The proxy first removes any `X-Isx-*` header the instance sent, so the controller never sees the instance's secret.
Any other request gets an error and never reaches the controller: a wrong or missing secret, a template the route does not list, an instance built from a project-local definition, a WebSocket.

Names must end in `.internal` and `templates:` must list at least one template.
The proxy skips an entry that is wrong in any way, with a warning in its log.

### Checking the route secret

The controller must check the route secret before it trusts `X-Isx-Instance` and `X-Isx-Template`.

The proxy also sends `X-Isx-Route-Secret`, a secret of this route alone that only this host holds.
The secret is in the file `~/.config/incus-spawn/host-routes/<name>.secret` (here `work.isx.internal.secret`).
The proxy creates that file, readable only by you, the first time it serves the route.
No instance ever has the route secret, and the proxy removes it from the controller's response headers.

Compare the header with the file's content in constant time, and refuse the request when they do not match, for example in Python:

```python
expected = open(os.path.expanduser("~/.config/incus-spawn/host-routes/work.isx.internal.secret")).read().strip()
if not hmac.compare_digest(request.headers.get("X-Isx-Route-Secret", ""), expected):
    return 403
```

### Rules for the controller

These rules keep the route secret known to the proxy and the controller only.

- Treat a request without the secret as one that did not come through the proxy: it came from another program on this host, for example.
- Keep the controller listening on loopback only: `127.0.0.1`, never `0.0.0.0`, because instances that are not `--proxy-only` reach a `0.0.0.0` listener directly at the bridge address.
- Never include the secret in a response, especially in a response body.
  The proxy removes the secret from response headers, but relays the body unchanged.
- To rotate the secret, delete the file; the proxy makes a new one at once.
- Rotate the secret too if the controller was not running while another program held its port: that program received the secret.
- Keep the secret file, its directory and `~/.config/incus-spawn` owned by you, the file readable by nobody else, and the directories writable by nobody else.
  The proxy refuses (503) every request to a route that breaks this.
- isx makes the config directory writable by you alone, whatever your umask.
  A config directory that an older isx made under a umask of 002 may be writable by your group only when that group's ID is your user ID.
  Otherwise the proxy refuses it, so run `chmod go-w ~/.config/incus-spawn` once.
- Do not give anyone else write access to the directories above `~/.config/incus-spawn`, by mode or by POSIX ACL: the proxy does not check those directories and does not see access granted with `setfacl`.
- Once you fix the ownership or permissions with `chmod` or `chown`, the proxy serves the route again at once on Linux.
  On macOS, run `isx proxy restart`, since the proxy's file watcher there does not see permission changes (a rewritten or deleted secret it does see).
