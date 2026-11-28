---
description: Run each Orca workspace in an isx sandbox of its own, created when you open the workspace and destroyed with it, through isx's environment recipe.
---

# Run Orca workspaces in isx sandboxes

This guide takes you from an installed isx to Orca running each workspace in an isx machine of its own.
[Orca](https://github.com/stablyai/orca) creates a machine for a workspace through an environment recipe, and destroys it with the workspace.
isx ships that recipe as a script.

isx's support for Orca is a first version: setting it up takes a few steps, which later releases will remove.

## Before you start

You need isx set up with `isx init`: the [Quick Start](../../README.md#quick-start) covers it, and builds `tpl-agent`, a template with your coding agent.
You also need the Orca desktop app on your host, and `bash`, `jq`, and `sha256sum` or `shasum`, which the recipe script runs.

## Make a template for your repository

Create a template with the `orca` tool, which Orca's server needs, and your repository cloned as its working directory (`workdir`):

```shell
isx templates new tpl-orca
```

Fill in the file your editor opens:

```yaml
name: tpl-orca
description: My project, for Orca workspaces
parent: tpl-agent
tools:
  - orca
repos:
  - url: https://github.com/you/your-project.git
    path: ~/your-project
workdir: ~/your-project
```

Then build it:

```shell
isx build tpl-orca
```

[Write an isx template for your project](project-template.md) adds the packages your project builds with.

## Let Orca destroy its instances

Add this to `~/.config/incus-spawn/config.yaml`, so Orca may destroy the instances it creates:

```yaml
drivers:
  orca:
    destroy: true
```

[Orca](../INTEGRATIONS.md#orca) on the integrations page says what else the recipe destroys with it, and [Any other tool](../INTEGRATIONS.md#any-other-tool) what an owner may and may not destroy.

## Install the recipe script

Download the recipe script of your isx release into a directory on your `PATH`, with the version `isx --version` shows in place of `<version>`.
A development build's version names no release, so with one, use the latest release's version:

```shell
mkdir -p ~/.local/bin
curl -fsSLo ~/.local/bin/isx-orca-recipe https://raw.githubusercontent.com/Sanne/incus-spawn/v<version>/integrations/orca/isx-orca-recipe
chmod +x ~/.local/bin/isx-orca-recipe
```

Make sure `~/.local/bin` and the directories holding `isx` and `jq` are all on the `PATH` of the Orca desktop app, not only of your shell.
On a Mac, an app started from the Dock does not get your shell's `PATH`, so a directory such as `/opt/homebrew/bin` may be missing there.

## Add the recipe to your repository

Add a recipe to your repository's `orca.yaml`, naming the template:

```yaml
environmentRecipes:
  - id: isx
    name: isx sandbox
    create: isx-orca-recipe create --from tpl-orca
    suspend: isx-orca-recipe suspend
    resume: isx-orca-recipe resume
    destroy: isx-orca-recipe destroy
```

Put `orca.yaml` in your repository's main checkout, not only in a worktree, or Orca does not find the recipe.

## Check that it works

In Orca, enable Settings → Experimental → Cloud VM.
Check that isx sandbox appears under Recipes.

Orca can also check the recipe without creating an instance:

```shell
orca vm recipe doctor isx --repo-path /absolute/path/to/your/repo --json
```

It reports `"ok": true` with a warning for each of the four commands, "Command is not a repo-relative path": the recipe script is on your `PATH`, not in your repository, which is how isx ships it.

Add `--provision` to run the recipe once: it branches an instance from your template, checks what the recipe returned, and destroys the instance again.

## Open a workspace

Select isx sandbox under Run on when you create a worktree in Orca.

The recipe branches an instance named from Orca's runtime id, such as `orca-3f2a6c1e0b5d`, and `isx list` shows it with `orca` in its `OWNER` column.
Orca reaches the instance over SSH and runs its agents in the workspace's Git worktree there.
[Orca](../INTEGRATIONS.md#orca) on the integrations page says where that worktree goes and which of Orca's modes the recipe supports.

When Orca suspends the workspace, the recipe stops the instance, and resuming it starts the instance again.
When you delete the workspace, the recipe destroys its instance.

## Faster first connections (experimental)

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

## Next steps

- Keep a workspace's agent from controlling other instances when your template is a coordinator template: [Orca](../INTEGRATIONS.md#orca) on the integrations page shows how.
- Take the agents' commits back to your host: [Review and take an agent's commits](agent-commits.md).
- Drive isx from a tool of your own the way the recipe does: [Any other tool](../INTEGRATIONS.md#any-other-tool).
