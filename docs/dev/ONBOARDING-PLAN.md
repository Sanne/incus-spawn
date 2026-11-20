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
   A credential found on the host is confirmed with one answer.
   The person may skip this step; see "Skipping the agent's credential".
5. It offers to set up a GitHub account for the agent, which the person may skip; see "Git in the first run".
6. On Linux it asks for the sudo password, the last thing it asks.
7. It does the host plumbing, shown as one progress block, and installs the services.
8. It builds the built-in agent template, or `tpl-minimal` when the person chose no agent, waiting first for any download still running.
9. It prints the completion message, then asks whether to open a first agent now.

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
Temporary files of the old kind are deleted when the cache is next used, once they are old enough that no download can still be writing them.
Today's code takes no lock on such a file, so its age is the only sign that an older isx is not still writing it.

**The first agent opens only when the person says so.**
An agent takes the whole terminal, so opening one unasked would hide the completion message.
Init prints that message first, with the command that opens an agent later, and asks as its last line:

```
Open your first agent now? [Y/n]
```

Without a terminal it does not ask and opens nothing.

**Skipping the agent's credential.**
A person may not have the credential at hand, or may want to see isx work before giving one.
Init then does everything else: the plumbing, the downloads and the build of the agent template.
The slow work is done, so the agent opens as soon as the credential is added.

What changes is the end of the run:

- The build inside init does not ask for the credential again, since the person has just declined it.
- The completion message says the agent has no account yet, and gives the command that adds one.
- Init does not ask whether to open a first agent, because it could not sign in.
- The first `isx branch` at a terminal asks for the credential, as "Missing accounts are asked for when a build needs them" describes.

**On a fresh Linux host the answers are given once, to the first process.**
There init's first plumbing step adds the user to `incus-admin`, and init then runs its whole command again as a new process under `sg incus-admin` (`InitCommand.continueInGroup`).
Today that process is told only that it is a continuation.
Under this plan it must ask nothing, since the questions came before the plumbing:

- Every answer that is a setting is written to `config.yaml` before the handover: the preferred agent, the credential, the GitHub account and the commit identity.
  The continuation reads them there.
- An answer that stores nothing is passed to the continuation beside the marker it already gets: that the questions were asked, and which were declined.
  These are the agent's credential declined, the GitHub account skipped, the identity answered No, and "none for now".
  None of them is a secret.
- The continuation asks no question of the first run, and treats a declined one as declined.
- The first process stays alive until the continuation ends, as today, so the downloads it started go on.

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

**What the preference means.**
It answers one question for isx: which agent to use where nothing names one.
Three things read it:

- Init, to know which credential to offer.
- The built-in agent template, which lists that agent.
- Later, a template that asks for the user's preferred agent in place of naming one.

It does not limit anything.
A template that names another agent installs that agent, and its account is asked for when the template is built.
A person may use several agents, and the preference is only the one isx reaches for by default.

**Nobody has to choose one.**
"None for now" is one of the choices.
Init then skips the agent's credential, builds `tpl-minimal` in place of the agent template, and ends with a plain machine the person can open a shell in.
An agent found on the host is chosen by Enter.
When none is found, no choice is selected by Enter, so isx does not pick a vendor for the person.

**With no preference, whatever needs one asks for it.**
A build of the built-in agent template, or of a template that asks for the user's preferred agent, asks the question at a terminal.
Without a terminal it refuses and names the command that sets the preference.

**The preference can be changed later, with one command.**
Templates that read it are then out of date and are rebuilt, as after any change to a definition.
The agent a built template holds does not change until then.

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
- The Git identity of the host, offered for the agent's commits only as "Git in the first run" describes.

**A subscription login is detected, never copied.**
Claude Code's stored session is a short-lived token and a refresh token.
If the refresh token rotates on use, the proxy and the host's Claude Code would sign each other out.
Whether it rotates is not verified, and the design does not depend on it.
Init detects the subscription and runs `claude setup-token`, so the proxy holds a long-lived token of its own.
Today init can run that command, and the person then pastes the token it prints (`CredentialSetup.setupClaudeOauth`).
What the detection saves is the question of which kind of account to set up, not the paste.
Reading the token without a paste is new work, and belongs to the new first run (#2412).
It is done only if the command's output can be read while the command keeps the terminal, and the paste stays otherwise.

**An API key and a Vertex project and region are taken directly.**
Each is shown masked (`maskSecret`) with where it was found, and one question covers them.
The `gh` login is a choice of the GitHub step, never part of this answer.
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

Init keeps this to one step that the person may skip, and never decides the identity for them.

**Init offers a GitHub account, and the person may skip it.**
The step says what the account is for: the agent's access to GitHub, and the name on its commits.
A `gh` login found on the host is offered as one of the choices, agreed on 2026-11-20.
Using it must be an explicit request: the choice names the account's login, says that the agent would act with that account's own access, and is taken only when the person types it.
Enter never chooses it.
With an account set up, the first agent has GitHub access and that account's identity, as today.

**A person who skips is asked about the commits, and must answer.**
Without a GitHub account nothing supplies an identity, and an agent with none cannot commit.
Init then offers the host's own `git config` name and email, and shows both:

```
No GitHub account: the agent has no name to commit under.
Let it commit as Jane Doe <jane@example.org>, the identity of this host? [y/N]
```

The answer is No unless the person types yes, and Enter is No.
Committing under the person's own name is acceptable only as a choice they made and saw.
The choice is recorded in `config.yaml` as a key of its own, used only when no GitHub account gives an identity.
A person who answers No gets an agent that cannot commit yet, and the closing message says so with the command that changes it.

**Without a terminal the identity is never assumed.**
A flag gives the answer, as for init's other questions that are not secrets, and without it the answer is No.

**Every instance that commits under the host's identity says so.**
`isx doctor` and the template's build report name the identity and where it came from, so the choice stays visible after init.

## Missing accounts are asked for when a build needs them

A template may need an account that is not configured: its agent's, GitHub's, or that of any other tool that declares a credential.
Today a build warns that the template cannot be branched, and `isx branch` refuses it (`CredentialCheck.missingByCredential`).

At a terminal, the build asks for each missing account before it starts, one after another, with the setup that `isx account add` runs for it.
The person may skip any of them, and the build then goes on and warns as today.
`isx branch` does the same in place of its refusal.

**No namespace is named in this code.**
What is missing comes from `CredentialCheck`, which reads the credentials the template's tools declare.
Each one's setup is the step its tool already provides.
So a new tool with a credential is asked for the same way, with no change here.

**Without a terminal nothing is asked.**
The build warns and the branch refuses, as today, each naming the command that sets the account up.

## Everything else is deferred

These leave the first run:

| Step | When the person meets it |
| --- | --- |
| Template search paths | When they first want a template that is not built in |
| The templates repository fork | The same moment |
| Code directories | When a template first names a repository they have on the host |
| Agent access over MCP | `isx mcp setup`, which exists |
| Further accounts, GitHub's included when it was skipped | `isx account add`, or when a build or branch first needs one |
| Copying an agent's MCP servers and skills | When they ask for it |

**A second `isx init` is a menu of these.**
`isx init --plan` already knows each step and its state.
The menu shows the same entries: what is set up, what is not, and one line on what each is for.

**A command that needs a deferred step offers it at that moment.**
For accounts this is "Missing accounts are asked for when a build needs them" above.

This gives up the tour: a first run no longer shows everything isx can do.
The closing box and the TUI name the deferred steps, so they can still be found.

## Built-in agent templates

Today the built-in templates are `tpl-minimal`, `tpl-dev` and `tpl-java`, and none installs an agent.
A first agent therefore needs a hand-written template.

isx ships one built-in agent template, whose agent is the person's stored preference.
Init builds it and offers to open it, and the Quick Start no longer asks anyone to write YAML before their first agent runs.
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

## Building blocks, in the order to build them

Each block below is a change of its own, with its own tests, and most are useful before the new first run exists.
The new init is then mostly an arrangement of them.
Each has an issue, which names the issues it depends on and those that wait for it.
Only two orderings are required: #2404 before #2411, and every other block before #2412.
Until #2409 lands, #2411 covers the base image and the VM's image alone, since the agent template's own downloads are not known before the preferred agent is.

**First, three that depend on nothing and help today's users:**

1. **Downloads that survive an interruption.** (#2404)
   "Recovering an interrupted download": the named partial file, the lock, the resume across runs and the removal of orphaned temporary files.
   Every build gains from it.
2. **Missing accounts asked for at build and branch.** (#2405)
   "Missing accounts are asked for when a build needs them", for any credential a tool declares.
   No step of init can be deferred safely until this exists.
3. **Init's steps in a compact form.** (#2406)
   One line per step as it completes, and the step's hint only when it fails.
   It changes what a run shows and nothing it does.

**Then four that the new first run is assembled from:**

4. **One step of init run by name, and the menu.** (#2407)
   Each deferred step becomes something a person can run alone, as `isx mcp setup` already is.
   A second `isx init` lists them with their state, from what `--plan` already reads.
5. **A read-only probe of the host's agents.** (#2408)
   For each supported agent: whether it is installed, whether it is signed in, the kind of account and the model.
   It changes nothing and holds no secret, so it is tested against made-up home directories.
   `isx init --plan` and `isx doctor` report it first, before init acts on it.
6. **The user's preferred agent.** (#2409)
   The key in `config.yaml`, the command that shows and sets it, and the way a template asks for it.
   The built-in agent template is the first to ask.
   This is the largest block: the loader, the editor schema, the out-of-date check and the docs lists all learn the new construct.
7. **A Git identity that no GitHub account supplies.** (#2410)
   The key in `config.yaml`, its explicit question, and where it is reported.
   Today only the `gh` tool writes an identity, so a template without `gh` needs another place to write one.

**Then two that only the new first run uses:**

8. **Downloads started ahead of the build.** (#2411)
   Given a template, what its build will download, fetched in the background with progress a later step can show.
   It needs block 1.
9. **The new first run.** (#2412)
   The order of "Minimal init", the questions before the work, the services without a question, and the closing question.

**After those:** the personal profile beyond the agent, the catalog and the chain view, each after a design of its own.

**To check before block 9:** that the agent template builds without its credential.
Today's build warns that such a template cannot be branched, which suggests it does, but this was not run.
