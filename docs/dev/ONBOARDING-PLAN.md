# Onboarding: the first run

How a new user gets from an installed package to a first agent, and what is left for later.

**Status: proposed, not built.**
This page is the plan agreed on 2026-11-20.
Each section says what exists today and what changes.
It is kept here, outside `design/`, because `isx ask` reads every `design/` page as a description of what isx does.
When a piece lands, its section moves to the `design/` page of its area, rewritten to describe what the code does.

## Why the first run changes

People who installed isx when it was small find it easy, because they learned each feature as it arrived.
A person installing it today meets all of them at once, and experienced developers and administrators were seen to struggle.

Today `isx init` runs about fifteen titled steps.
Ten are host plumbing the person has no decision in: Incus, user namespace mappings, storage and network, the firewall, the proxy, its CA, the SSH key, DNS, the Git remote helper and the services.
Between them it asks what only an established user can answer: template search paths, code directories, a fork of the templates repository, and MCP approvals.
It then ends with work still to do.
Its closing box sends the person to a web page to copy YAML into a file, then to build it, then to branch it.

The first run should end with an agent running in a machine of its own, and ask only what that needs.

## Minimal init

A first run of `isx init` asks its questions first, then works with nobody watching, in this order:

1. It reads the host without changing it: which agents are installed and signed in, which credentials exist, and what the plumbing still needs.
2. It says in a few lines what the run will do and how long it takes, and starts the downloads in the background.
3. It asks for the user's preferred agent, with the one found on the host preselected.
4. It sets up the one credential that agent spends, and verifies it.
   A credential found on the host is confirmed with one answer, and a `gh` login found there is offered in the same answer.
5. On Linux it asks for the sudo password, the last thing it asks.
6. It does the host plumbing, shown as one progress block, and installs the services.
7. It builds the built-in agent template, waiting first for any download still running.
8. It prints the completion message, then asks whether to open a first agent now.

**Every question comes before the long work.**
The plumbing and the first build take minutes, and a question in the middle of them waits for a person who has left.
This gives up something: a person whose plumbing then fails has already given a credential.
The credential is saved on the host, so the next run does not ask for it again.

**The downloads start before the first question.**
The base image is the longest wait of a first run, and on macOS the VM's image is another.
Both are fetched by the host into its download cache (`DownloadCache`), checked against their pinned checksums, and need neither Incus nor sudo.
So init starts them as soon as it has told the person, and they run while the person answers.
The preferred agent's own download starts when that answer is given.
These rules hold for the background downloads:

- They print nothing while a question is on screen.
  When a step needs a file that is still downloading, that step shows the download's progress and waits.
- A download that failed in the background is not reported while the person answers.
  The step that needs the file retries it in the foreground and reports the failure there.
- `isx init --plan` downloads nothing, since it only reads.
- A person who stops init early keeps what finished.
  An unfinished file is never used, and the next run recovers from it without help; see "Recovering an interrupted download".
- On a fresh Linux host init continues in a second process under `sg incus-admin`.
  Only one process downloads a file, and the other waits for it under a `HostLock`.

This gives up one thing: a person who leaves at the first question has already downloaded several hundred megabytes into the cache.

**Recovering an interrupted download.**
Today `DownloadCache` writes to a temporary file with a random name, and moves it into place only after its checksum matches.
So a partial file is never used, and the next run downloads the file again.
Two things are missing today, and a first run that downloads in the background makes both more likely to be met:

- A process that is killed leaves its temporary file behind, since only a `finally` block deletes it, and nothing found in the code removes it later.
  Each interrupted run can leave several hundred megabytes in the cache.
- The next run starts from byte 0, though the cache can already resume a transfer that broke off within one run.

The change: a download in progress is kept under a name derived from its URL, held under a `HostLock`.
A later run that finds one continues it from where it stopped, when the server can resume it, and from byte 0 otherwise.
The checksum is checked over the whole file as today, and a file that fails it is deleted and fetched once more from byte 0.
Temporary files of the old kind that no process holds are deleted when the cache is next used.

**The first agent opens only when the person says so.**
An agent takes the whole terminal, so opening one unasked would hide the completion message.
Init prints that message first, with the command that opens an agent later, and asks as its last line:

```
Open your first agent now? [Y/n]
```

Without a terminal it does not ask and opens nothing.

**The services are installed without a question.**
Today init asks whether to install them, and nearly everyone wants them; `--no-service` still declines.

**The plumbing is one block.**
Each of today's steps keeps its work and its planner (`InitCommand.InitStep`), so `isx init --plan` and `--no-sudo` read as they do now.
What changes is what a run shows: one line per step as it completes, with no hint paragraph.
The explanation of each step stays in `--plan`, where the person who wants it already looks.

**A failed step still stops the run and says what failed.**
The single block gives up the per-step explanation on screen, so a failure prints the step's hint with its error.

**Init is still safe to run again.**
A second run does not ask the first run's questions again.
It shows the deferred steps as a menu, described below.

## The preferred agent

Init asks which coding agent the person prefers, from the agents isx supports, and sets up only that one.

**Setting an agent up does not install it in any template.**
A template installs an agent only by listing it in `tools:`, as today.
The answer decides two things: which credential init sets up on the host, and which agent the built-in first template lists.
A template that does not list an agent gets none, whatever the preference is.

**The answer is stored.**
It is one key in `config.yaml`, the user's preferred agent.
The built-in agent template reads it, so the first agent needs no YAML written by hand.
It is also the first field of a personal profile; see "Templates: the person's preferences are not a layer" below.

**The host's own setup preselects the answer.**
Init looks for each supported agent on `PATH` and for its configuration directory.
An agent that is installed and signed in becomes the default choice.

**The question promises neither the host's installation nor an install everywhere.**
"Use it as your agent?" reads as if isx would drive the program on the host, which it does not.
"Install it in your isx machines?" reads as if every template would get it, which is also wrong.
The wording says what init does, which is to prepare the host for templates that list the agent:

```
Found Claude Code on this host, signed in with a subscription.
Set up isx for Claude Code, so templates that include it can run it? [Y/n/other]
```

**Pi and opencode spend the Claude credential by default.**
Choosing either leads to the Claude credential step, unless the person names another provider.

## Reusing what the host already has

A person who already uses a coding agent has answered most of init's credential questions once.
Init reads those answers, shows what it found, and asks once before using any of it.

Today init already reads `ANTHROPIC_API_KEY` and the OAuth token from the environment, the `gh` login through `gh auth token`, and `gcloud` for Vertex AI (`CredentialSetup`).
It does not read the agents' own configuration.

What init reads, per agent:

- Whether it is installed and signed in.
- The kind of account: a subscription, an API key, or a cloud provider with its project and region.
  This replaces the question of which account type to set up.
- The model the person chose, so the first template uses the same one.
- The Git identity of the host, as the suggested identity for the agent's commits.

**A subscription login is detected, never copied.**
Claude Code's stored session is a short-lived token and a refresh token.
If the refresh token rotates on use, the proxy and the host's Claude Code would sign each other out.
Whether it rotates is not verified, and the design does not depend on it.
Init detects the subscription and runs `claude setup-token`, which it can already do, so the proxy holds a long-lived token of its own.
The person confirms once in the browser, and pastes nothing.

**An API key, a Vertex project and region, and the `gh` token are taken directly.**
Each is shown masked (`maskSecret`) with where it was found, and one question covers them all.
What is taken goes to the host's `config.yaml` like any other credential, and never into a guest.

**The rest of an agent's setup is not copied by minimal init.**
An agent's MCP server entries often hold tokens, and its hooks and skills name host paths.
Copying them is a deferred step that the person asks for, with each entry shown.

This gives up some convenience: the person's first isx agent does not have their host agent's MCP servers or skills.
In return nothing reaches a guest that the person did not look at.

## Git in the first run

Git setup is two separate things today, and only one of them is plumbing.

**The host's side is plumbing.**
The Git Remote Helper step installs what lets the host fetch an agent's commits from an instance.
It asks nothing, and stays in the plumbing block.

**The agent's Git identity comes from the GitHub credential.**
At build, the `gh` tool asks GitHub whose token the instance's account holds, and writes that name and email to the instance's `.gitconfig` (`GhSetup.configureGitIdentity`).
Without a GitHub credential no identity is written, and the build warns.

Minimal init defers the GitHub credential, so this needs a decision, or the first agent cannot commit.
The plan:

- A `gh` login found on the host is offered in the credential answer of step 4.
  When the person accepts it, the first agent has GitHub access and that account's identity, as today.
- Otherwise init offers the host's own `git config` name and email as the identity of the agent's commits, in the same answer.
  The first agent can then commit, and the person fetches its commits to the host.
  It has no GitHub access until the person adds a GitHub credential.
- A dedicated GitHub account for the agent, which the README recommends, is a deferred step.
  The closing message names it.

The second case needs something isx does not have: an identity that no GitHub token supplies.
It is one more key in `config.yaml`, used only when no GitHub account gives an identity.
This is an open point, not yet agreed.

## Everything else is deferred

These leave the first run:

| Step | When the person meets it |
| --- | --- |
| Template search paths | When they first want a template that is not built in |
| The templates repository fork | The same moment |
| Code directories | When a template first names a repository they have on the host |
| Agent access over MCP | `isx mcp setup`, which exists |
| Further credentials | `isx account add`, or when a build or branch first needs one |
| GitHub access for the agent, with an account of its own | From the closing message, or when a template first needs it |
| Copying an agent's MCP servers and skills | When they ask for it |

**A second `isx init` is a menu of these.**
`isx init --plan` already knows each step and its state.
The menu shows the same entries: what is set up, what is not, and one line on what each is for.

**A command that needs a deferred step offers it at that moment.**
`isx branch` already refuses a template whose credential is missing (`CredentialCheck`).
At a terminal it offers that credential's setup in place of the refusal.
Without a terminal it refuses as it does today.

This gives up the tour: a first run no longer shows everything isx can do.
The closing box and the TUI name the deferred steps, so they can still be found.

## Built-in agent templates

Today the built-in templates are `tpl-minimal`, `tpl-dev` and `tpl-java`, and none installs an agent.
A first agent therefore needs a hand-written template.

isx ships one built-in agent template, whose agent is the person's stored preference.
Init builds it and branches it, and the Quick Start no longer asks anyone to write YAML before their first agent runs.
Writing a template becomes the second thing a person learns, when they want a project's tools and repositories.

## Templates: the person's preferences are not a layer

This section is the larger change and is not designed in detail yet.

Today a person's preferences are a template in the middle of the inheritance chain.
In the shared templates repository, `tpl-java` is the parent of `tpl-mydev`, described as "edit this!", and `tpl-mydev` is the parent of seven project templates.
To change their agent or model, a person edits the middle of a forked repository, and their fork then differs from upstream for good.

Three changes are proposed:

- **A personal profile.**
  The agent, its model, and the person's own tools and packages are declared once, per user.
  A template still opts in: it asks for "the user's preferred agent" where today it names one, and the profile says which agent that is.
  A template that does not ask gets nothing from the profile.
  Project templates then hold only what the project needs, and are shared unedited.
  The open questions are how a template asks, and where the profile sits in the copy-on-write layers so that project images still share a base.
- **A catalog in place of a fork.**
  A person adds an upstream template by name and gets a read-only definition, with local files only for what they override.
  Forking stays for people who contribute templates.
- **A view of the chain.**
  For any template, one view shows where each definition comes from, what it inherits, and what overrides what.
  Definitions come from four places today: built in, `~/.config/incus-spawn/`, search paths and a project's `.incus-spawn/`.

## Order of work

1. Minimal init: the plumbing as one block, and the deferred steps as a menu on a second run.
2. The preferred agent, stored, with the built-in agent template.
3. Init ends by building and branching that template.
4. Detection of the host's agents and their account kind, with one confirmation.
5. Deferred steps offered when a command first needs them.
6. The personal profile, the catalog and the chain view, each after a design of its own.

Items 1 to 3 remove and reorder what exists, and they change the first ten minutes.
