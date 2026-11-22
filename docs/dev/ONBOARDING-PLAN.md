# Onboarding: the first run

How a new user gets from an installed package to a first agent, and what is left for later.

**Status: built, except "Templates: the person's preferences are not a layer".**
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

## Nothing is taken from experienced users

Built in #2412; described in design/init.md "Nothing is taken from experienced users".

## Minimal init

Built in #2412; described in design/init.md, with "Skipping the agent's credential" and "The continuation under `sg` asks nothing".

## The preferred agent

Init asks which coding agent the person prefers, from the agents isx supports, and sets up only that one.

**Setting an agent up does not install it in any template.**
A template installs an agent only by listing it in `tools:`, as today.
The answer decides two things: which credential init sets up on the host, and which agent the example template that init writes lists.
A template that does not list an agent gets none, whatever the preference is.

**What the preference means.**
It answers one question for isx: which agent to start a new user with.
Two things read it:

- Init, to know which credential to offer.
- Init again, to write the example template; see "An example template written by init".

It does not limit anything.
A template installs the agent it names, whichever that is, and its account is asked for when the template is built.
A person may use several agents.

**There is no template syntax for it yet**, decided on 2026-11-20.
A template names a concrete agent, as today, and no template can ask for "the user's preferred agent".
The example template init writes names the chosen agent outright.
This gives up shared templates that follow each user's preference: `tpl-quarkus` and its like keep naming one agent.
In return a template stays a file that says what it installs, and nothing new has to be learned to read one.
A syntax may come later, with the personal profile below.

**Nobody has to choose one.**
"None for now" is one of the choices.
Init then skips the agent's credential, writes no example template, builds `tpl-minimal`, and ends with a plain machine the person can open a shell in.
An agent found on the host is chosen by Enter.
When none is found, no choice is selected by Enter, so isx does not pick a vendor for the person.

**The preference can be changed later, with one command.**
Changing it changes no template.
The example template is the person's own file by then, and they edit it like any other.

**The answer is stored.**
It is one key in `config.yaml`, the user's preferred agent, holding the agent's name and nothing else.
It may later become the first field of a personal profile; see "Templates: the person's preferences are not a layer" below.

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

Built in #2412; described in design/init.md "A GitHub account in the first run" and design/accounts.md "A Git identity no account supplies".

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
"Nothing is taken from experienced users" says how each capability is still found.

## An example template written by init

Today the built-in templates are `tpl-minimal`, `tpl-dev` and `tpl-java`, and none installs an agent.
A first agent therefore needs a template the person writes by hand, from a web page.

Init writes that template for them, as `~/.config/incus-spawn/images/tpl-agent.yaml`.
It is an ordinary template in the person's own directory, to start with and to copy from:

- It names the chosen agent in `tools:` and as its `default-action`, with `tpl-minimal` as its parent.
- Its first line is the schema modeline, so an editor completes and checks it.
- It holds a few commented lines that show where packages and repositories go, each with the page that explains it.

Init builds it and offers to open it, so the Quick Start no longer asks anyone to write YAML before their first agent runs.
The person then has a working file to read and change, which is how the next template gets written.

**Init never overwrites a template.**
When `tpl-agent.yaml` is already there, init leaves it as it is and builds it.

**The file is the person's from the moment it is written.**
isx does not update it, and a later change of the preferred agent does not touch it.
This gives up the fixes a built-in template would get with each release.
In return nothing the person reads is hidden in the binary.

**What init writes is tested.**
For each supported agent, the generated file loads and validates as a template.

## Templates: the person's preferences are not a layer

This section is the larger change and is not designed in detail yet.

Today a person's preferences are a template in the middle of the inheritance chain.
In the shared templates repository, `tpl-java` is the parent of `tpl-mydev`, described as "edit this!", and `tpl-mydev` is the parent of seven project templates.
To change their agent or model, a person edits the middle of a forked repository, and their fork then differs from upstream for good.

Three changes are proposed:

- **A personal profile.**
  The agent, its model, and the person's own tools and packages are declared once, per user.
  A template would still opt in, by asking for "the user's preferred agent" where today it names one.
  No such syntax exists yet; see "The preferred agent".
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
Until #2409 lands, #2411 covers the base image and the VM's image alone, since the example template's own downloads are not known before the preferred agent is.

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
   The key in `config.yaml`, the command that shows and sets it, and the example template init writes for it.
   No template syntax, so the loader and the editor schema for templates do not change.
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

**To check before block 9:** that the example template builds without its credential.
Today's build warns that such a template cannot be branched, which suggests it does, but this was not run.
