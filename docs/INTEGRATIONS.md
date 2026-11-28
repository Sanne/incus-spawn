# Driving isx from other tools

Tools that run coding agents in parallel, such as Orca and herdr, can give each agent an isx instance of its own.
This page says what isx does with each tool, links to the guides for herdr and Orca, says what isx needs from any other tool, and shows how a program on your host is reached from instances.

## How a tool connects

Which way a tool connects depends on what it can do, not on a choice you make.

| The tool | What isx does | Example |
|----------|---------------|---------|
| Runs a command to create a machine and another to destroy it | The tool drives isx through ordinary commands | Orca |
| Only keeps a list of SSH machines | isx adds each instance to the tool's list, and removes it when you destroy the instance | herdr, Rex |

Either way, you add the matching isx tool (`herdr`, `rex` or `orca`) to a template and build that template, as the herdr and Orca guides and the Rex section below show.
Agents still get no credentials in their instance: the proxy on your host adds the credentials to their requests, as for any branch.

## herdr

[herdr](https://herdr.dev) runs agents in terminal workspaces, on your host or on SSH machines it lists.
[Use herdr with isx sandboxes](guides/herdr.md) walks through the setup: the `herdr` tool in a template, and a branch of it.

isx adds an instance to herdr as a machine when the instance first starts, and herdr starts its server in the instance.
When you destroy the instance, isx removes the machine from herdr.
A machine you added to herdr yourself is never removed by isx.

herdr always checks a machine's host key, which isx records on your host on every start.
A machine added by an older isx reaches its instance again after that instance's next start.
From then on herdr can start that instance too.

When isx stops the instance, it disables the machine in herdr first.
herdr then shows the machine dimmed and does not reconnect to it, so the instance stays stopped.
While it is disabled, `herdr --machine <name>` refuses to reach the instance.

Starting the instance with isx enables the machine again.
So does enabling it in herdr, and herdr's connection then starts the instance.
If the instance stops without isx, for example when its guest shuts down, the machine stays enabled and an open herdr starts the instance again.

## Rex

[Rex](https://pkgs.superlogical.com/rex/) is a terminal multiplexer whose client lists hosts and reaches their sessions over SSH.

1. Install Rex on your host, Linux or macOS.
   isx works only with Superlogical's Rex, and leaves the unrelated Perl tool also named `rex` alone.
2. Add the `rex` tool to a template and build it:

   ```yaml
   name: tpl-rex
   parent: tpl-minimal
   tools:
     - rex
   ```

3. Branch it: `isx branch agent-1 --from tpl-rex`.

isx adds `agent-1` to your Rex hosts when the instance first starts.
The instance's Rex server starts at boot with a session named `main`, so `rex -S agent-1 ls` lists it.

When you destroy the instance, isx removes the host from Rex.
A host you added to Rex yourself is never removed or replaced by isx, even one with the instance's name.

Rex does not start a stopped instance.
Start it with `isx start agent-1` or `isx shell agent-1`, and Rex reaches it again.

In the TUI, the instance's details (F3) say isx added it to Rex.

## Orca

[Orca](https://github.com/stablyai/orca) can run each workspace on a machine of its own, through an environment recipe.
A recipe is four commands: one creates the machine, and three suspend, resume and destroy it.
isx ships a recipe script, `isx-orca-recipe`, and [Run Orca workspaces in isx sandboxes](guides/orca.md) walks through the setup.

The script runs the commands of [Any other tool](#any-other-tool) below, with `orca` as the owner and Orca's runtime id as the workspace id.
Each workspace gets an instance named from that id, such as `orca-3f2a6c1e0b5d`.

Destroying an instance needs `drivers: {orca: {destroy: true}}` in the isx configuration, which the script also uses to destroy an instance that a failed create left behind.
The script supports Orca's worktree mode only, in which Orca makes the workspace's Git worktree itself, under the template's workdir.

A workspace's instance gets what its template gives any branch: an instance of a [coordinator template](MCP.md#a-coordinator-in-an-instance) is a coordinator, whose agent may control other instances.
To make workspaces that are not coordinators, name in the recipe's `--from` a child template whose `coordinator:` holds only `isx-control-capability: false`.

## Any other tool

A tool with commands to create and destroy a machine can use isx the same way as the Orca recipe does:

| Step | Command |
|------|---------|
| Create | `isx branch <name> --from <template> --owner <tool> --owner-id <workspace-id> --format=json` |
| Suspend | `isx stop <name>` |
| Resume | `isx start <name>` |
| Run a command | `isx exec <name> -- <command> [args...]` |
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

Making a service on your host, such as Ollama, reachable from every instance is a setting in `config.yaml`.
See [A service on the host](CONFIGURATION.md#a-service-on-the-host) on the Configuration page.

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

The file is `/run/isx/instance-secret`, readable by the instance user, and the secret is not a credential for anything upstream.
A restart replaces the secret.
After a reboot isx did not perform, the instance has no secret until you next open it with `isx shell`, `isx start` or `ssh <name>`.

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
