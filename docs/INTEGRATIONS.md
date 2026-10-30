# Driving isx from other tools

Tools that run coding agents in parallel, such as Orca and herdr, can give each agent an isx instance of its own.
This page shows how to connect them, what isx needs from any other tool, and how a program on your host is reached from instances.

## How a tool connects

Which way a tool connects depends on what it can do, not on a choice you make.

| The tool | What isx does | Example |
|----------|---------------|---------|
| Runs a command to create a machine and another to destroy it | The tool drives isx through ordinary commands | Orca |
| Only keeps a list of SSH machines | isx adds each instance to the tool's list, and removes it again | herdr |

Either way, you put the tool's isx tool in a template, build it, and the rest follows.
Agents still get no credentials in their instance: the proxy on your host adds them, as for any branch.

## herdr

[herdr](https://herdr.dev) runs agents in terminal workspaces, on your machine or on SSH machines it lists.

1. Install herdr on your host.
2. Add the `herdr` tool to a template and build it:

   ```yaml
   name: tpl-herdr
   parent: tpl-minimal
   tools:
     - herdr
   ```

3. Branch it: `isx branch agent-1 --from tpl-herdr`.

isx adds `agent-1` to herdr as a machine on its first start, and herdr starts its server there.
`herdr --machine agent-1 agent list` then reaches it.
When you destroy the instance, isx removes the machine from herdr.
A machine you added to herdr yourself is never removed by isx.

## Orca

[Orca](https://github.com/stablyai/orca) can run each workspace on a machine of its own, through an environment recipe.
A recipe is four commands: one creates the machine, and three suspend, resume and destroy it.
This is a first version: setting it up takes a few steps, which later releases will remove.

1. Build a template with the `orca` tool, which Orca's server needs, and with your repository cloned as its workdir:

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
   The recipe also uses this to take back an instance a failed create left:

   ```yaml
   drivers:
     orca:
       destroy: true
   ```

3. Download the recipe script for your isx version onto your `PATH`.
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

Each workspace then gets an instance named after Orca's runtime, such as `orca-3f2a6c1e0b5d`.
Orca reaches it over SSH, adds its worktree under the template's workdir, and runs its agents there.
The recipe supports Orca's worktree mode only, in which Orca makes the worktree itself.

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

`<tool>` is a stable name for the orchestrator, such as `my-runner`.
`<workspace-id>` is that tool's identifier for this particular workspace or runtime, not the tool's name or an isx-generated value.
Use the tool's existing workspace ID, or generate a unique ID when allocating a workspace and save it for later lifecycle calls.

Pass the same value to create and destroy; isx records it on the instance and checks it before deleting.
It must contain 1 to 128 letters, digits, or `.`, `_`, `:`, `-`.

For example, a runner creating its workspace `job-42` could use:

```shell
isx branch runner-job-42 --from tpl-java --owner my-runner --owner-id job-42 --format=json
# Later, when deleting that workspace:
isx destroy runner-job-42 --owner my-runner --owner-id job-42
```

That example also needs `drivers: {my-runner: {destroy: true}}` in the isx configuration.
The Orca recipe passes these for you.

`isx branch --format=json` prints how to reach the instance over SSH, including a `ProxyCommand` that starts a stopped instance.
The [CLI reference](CLI.md#isx-branch-fields) lists its fields.
`isx list` shows which tool owns each instance, and the tool can only destroy its own, once `drivers:` lets it.
A tool that only knows SSH machines can reach any instance whose template installs `sshd`, as `ssh <name>`.

## A controller on the host

A program of your own on the host, such as a coordinator that agents report to, can be reached from instances by a name you choose.
The proxy carries the calls, so this works in `--proxy-only` mode too, and tells the controller which instance is calling.
List the name in `~/.config/incus-spawn/config.yaml`:

```yaml
host-routes:
  work.isx.internal:
    target: 127.0.0.1:7070       # on this host's loopback: localhost, a 127.x.x.x address or [::1]
    templates: [tpl-isx-agent]   # required: only instances of these templates may call it
```

An instance then calls `https://work.isx.internal/...`, sending the secret isx gave it at its last start as `X-Isx-Instance-Secret`.
That secret is in the file `$ISX_INSTANCE_SECRET_FILE` names.

The proxy checks the secret and the instance's address together, and the instance's template against `templates:`.
It forwards the request over plain HTTP to the target with `X-Isx-Instance` and `X-Isx-Template` set to who called.

The proxy first removes any `X-Isx-*` header the instance sent, so the controller never sees the instance's secret.
Anything else gets an error and never reaches the controller: a wrong or missing secret, a template the route does not list, an instance built from a project-local definition, a WebSocket.

Names must end in `.internal` and `templates:` must list at least one template.
The proxy skips an entry that is wrong in any way, with a warning in its log.

### Checking the route secret

The controller must check the route secret before it trusts those headers.

The proxy also sends `X-Isx-Route-Secret`, a secret of this route alone that only this host holds.
It is in `~/.config/incus-spawn/host-routes/<name>.secret` (here `work.isx.internal.secret`), which the proxy creates readable by you only the first time it serves the route.
No instance ever has it, and the proxy removes it from the controller's response headers.

Compare it in constant time, and refuse the request when it does not match, for example in Python:

```python
expected = open(os.path.expanduser("~/.config/incus-spawn/host-routes/work.isx.internal.secret")).read().strip()
if not hmac.compare_digest(request.headers.get("X-Isx-Route-Secret", ""), expected):
    return 403
```

### Rules for the controller

These rules keep the secret with the proxy and the controller, and nowhere else.

- Treat a request without the secret as one that did not come through the proxy: another program on this host, for example.
- Keep the controller listening on loopback only: `127.0.0.1`, never `0.0.0.0`, which instances that are not `--proxy-only` reach at the bridge gateway.
- Never echo the secret back, in a response body least of all.
  The proxy removes it from response headers, but relays the body as it is.
- To rotate the secret, delete the file; the proxy makes a new one at once.
- Rotate it too if the controller was down while something else held its port: whatever held it received the secret.
- Keep the secret file, its directory and `~/.config/incus-spawn` owned by you, the file readable by nobody else, and the directories writable by nobody else.
  The proxy refuses (503) every request to a route that breaks this.
  The config directory may be writable by your own private group, as a umask of 002 makes it, never by a group you share.
- Keep the directories above `~/.config/incus-spawn` free of write access for anyone else, without relying on POSIX ACLs: the proxy does not check those directories and does not see `setfacl`.
- Once you fix one with `chmod` or `chown`, the proxy serves the route again at once on Linux.
  On macOS, run `isx proxy restart`, since its file watcher does not see permission changes (a rewritten or deleted secret it does see).
