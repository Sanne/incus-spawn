---
title: "Review a coding agent's commits from its sandbox"
description: Fetch an agent's commits from its isx sandbox to your host with plain git, review exactly what it did, and take only what you want.
---

# Review and take an agent's commits from an isx sandbox

An agent in an isx machine works in its own clone of your project, and its work reaches your host as commits.
This guide takes you from an agent's finished task to its commits reviewed and merged in your checkout.
You use plain `git fetch`, `git diff` and `git cherry-pick`, and never a directory the agent can still change.

## Before you start

You need a running machine whose template clones your project, such as `fix-login` from [Run Claude Code in an isx sandbox](claude-code.md).
You also need a checkout of the same project on your host.
[Write an isx template for your project](project-template.md) shows how a template clones it.

## Add the machine as a remote

In your checkout on the host, add a git remote named after the machine:

```shell
git remote add fix-login isx://fix-login/~/myproject
```

The URL names the machine, then the clone's path inside it, where `~` is the agent's home.
[isx:// URLs](../../README.md#isx-urls) has the other forms.

## Let isx add the remote for you

Tell isx where your checkouts live, in `~/.config/incus-spawn/config.yaml`:

```yaml
host-paths:
  - ~/projects
```

Each later `isx branch` then adds a remote named after the new machine to each checkout under `~/projects` whose remote matches a repository the machine clones.
`isx destroy` removes it again, so you skip `git remote add` altogether.
[Automatic remotes](../../README.md#automatic-remotes) says how isx matches a checkout.

## Fetch and review

Fetch while the machine is running, then list the branches it holds:

```shell
git fetch fix-login
git branch -r --list 'fix-login/*'
```

The agent may commit on a branch of its own rather than the one it found.
Compare the branch it used with yours, here `fix-login/fix-login`:

```shell
git log --oneline HEAD..fix-login/fix-login
git diff HEAD...fix-login/fix-login
```

The commits you fetched cannot change any more, whatever the agent does next.
A later fetch brings what the agent committed since.

## Take what you want

Take every commit with a merge, or pick single ones:

```shell
git merge fix-login/fix-login
git cherry-pick <commit>
```

To hand the agent a branch of yours, push it the same way: `git push fix-login my-branch`.

## Check that it worked

`git log` on your branch shows the agent's commits, under the name the agent commits as.
`git remote -v` lists `fix-login` with its `isx://` URL.

## Clean up

Fetch everything you want before you destroy the machine, since its clone goes with it:

```shell
isx destroy fix-login
```

With `host-paths`, the remote goes too; otherwise remove it with `git remote remove fix-login`.
