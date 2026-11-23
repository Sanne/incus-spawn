# Host setup: the first run of `isx init`

How a person gets from an installed package to a first agent, and what init leaves for later.
The plan this came from is docs/dev/ONBOARDING-PLAN.md; the rest of init's design is in DESIGN.md "Goals", under "Idempotent setup".

## The first run asks first, then works (#2412)

A person installing isx today meets every feature at once, and experienced developers and administrators were seen to struggle with a walk of about fifteen steps that ended with YAML to copy from a web page.
So a first run asks only what a first agent needs, and ends with that agent built.

A run is a first run when it has a terminal, prints the `table` format and init never completed on the host (`InitCommand.isFirstRun`: no init-complete marker, of any version), or when `--all` is given.
A host that completed init once gets the menu of #2407 instead, never the first run again, so an upgrade that bumps `INIT_VERSION` asks nothing new.
A run without a terminal, or in a machine format, keeps the walk it always had: the agent-driven install of docs/AGENT-INSTALL.md and CI's `isx init </dev/null` see no change.

`runFirstRun` takes these steps, in order:

1. It prints what the run will do and starts the downloads of `tpl-minimal`'s build in the background (`BackgroundDownloads`, `BuildDownloads.plan` with nothing counted as built).
2. It asks for the preferred agent (`askAgent`).
   One the person already prefers, else one `HostAgentProbe` found signed in, else one found installed, is offered and chosen by Enter, with `n` for none and `other` for the list.
   With none found the list is shown, and Enter chooses nothing: it shows the list again, so isx never picks a vendor for the person.
   "None for now" is a choice, and stores nothing.
   The answer starts the agent's own pinned downloads, less those already started.
3. It sets up the account the agent spends (`setUpAgentCredential`).
   Which one is not named in init: `agentGaps` runs `CredentialCheck.missingByCredential` over the example template for the agent, as a build of it would, and each gap runs the credential step `isx account add` runs for it.
   So Pi and opencode reach the Claude step through the credential their tool declares, and a new agent needs nothing here.
   A step that is not the agent's own is introduced by one line saying so (`InitCommand.sayWhoseAccount`), since its questions name another product: Pi is asked about Claude.
   For an agent with a `provider` parameter the line also says how to spend another provider's account.
   When the account is GitHub's (Copilot), the step is the GitHub question of the next step, which is then not asked a second time.
   A namespace that already has a usable account is not asked again.
4. It offers a GitHub account (`CredentialSetup.offerGitHubForAgent`), and after a skip asks the Git identity question of design/accounts.md "A Git identity no account supplies".
5. On Linux it asks for sudo, and installs the dependencies.
6. It runs the plumbing as one block (`firstRunPlumbing`): today's steps that ask nothing, the Git remote helper and DNS among them, then the services.
   DNS is inside the block, since no step before it adds a search path, whose tools' domains it writes.
7. It writes `tpl-agent.yaml` for the agent (`ExampleTemplate`), or with no agent nothing, and builds it, or `tpl-minimal`.
   It lists the `gh` tool after the agent only when a usable GitHub account is saved by then, so the first agent uses the account the person gave and a person who skipped it gets no missing-credential warning (#2535).
   A definition already named `tpl-agent`, in the person's directory or a search path, is built as it is and nothing is written, since a second one would make the build refuse both.
   Only trusted definitions decide the first template: a project's `.incus-spawn/` in the working directory is a cloned repository's.
   When a build there would read a project's template or tool for a layer of the chain, init builds and opens nothing, and says to build from a directory with no `.incus-spawn/` of its own.
8. It prints the completion message, then asks `Open your first agent now?`, which branches `my-first` (or the first free `my-first-<n>`) from the template.

**Every question comes before the long work.**
The plumbing and the build take minutes, and a question in the middle waits for a person who has left.
This gives up something: a person whose plumbing then fails has already given a credential, which is saved on the host so the next run does not ask for it again.

**The services are installed without a question** in a first run, since nearly everyone wants them; `--no-service` still declines.
Without a terminal they still need `--service`, as before, so a scripted install changes nothing.
Under `--all` they are installed without a question too, as `--all` is a first run.

**Some runs build nothing.**
An init `requireInit` started for another command writes the template and builds nothing, since that command carries on with its own work.
A first run whose proxy is not a running service builds nothing either, since a build needs the proxy.
The completion message then names the build command.

**The completion message is what the person does next, in order** (#2528).
It says what is ready, then lists what is left as numbered steps, one command to a line, in the order that works: start the services, build the template, add the agent's account, open the agent (`InitCommand.firstRunNextSteps`).
It reads `Setup complete!` only when opening the agent is all that is left, and a build that failed is named.
An agent that needs something of the person at its first start says so under its step (`ToolSetup.firstStartNote`): Antigravity CLI on a Google account signs in from the instance.
Then come the commands to return to the machine, list every machine and edit the template, and the templates guide.
No line is wider than the box, which `InitFirstRunTest#noLineOfTheClosingMessageIsWiderThanItsBox` holds on every path.

**A run that stops half way says how to go on.**
A setup step that fails ends the run with its error, after a line saying that the agent and the accounts set up are saved and that `isx init`, run again, asks only what was skipped.

**The agent opens only when the person says so.**
An agent takes the whole terminal, so opening one unasked would hide the completion message, which comes first.

## Skipping the agent's credential

A person may not have the credential at hand, or may want to see isx work first.
Init then does everything else, so the agent opens as soon as the credential is added.
The build inside init runs with `BuildCommand.askForAccounts` off, so it does not ask again for the credential just declined, and warns as a build without a terminal does.
The completion message says the agent has no account yet, with the command that adds one, and init does not ask to open the agent, which could not sign in.
The first `isx branch` at a terminal asks for it (#2405).

A Claude Code subscription found on the host answers which kind of account to set up (`CredentialSetup.setupClaudeSubscription`): it is re-issued with `claude setup-token`, never copied, since the proxy and the host's Claude Code would otherwise share one refresh token.
The person pastes the token the command prints.
Reading it without the paste would mean reading the command's output while it keeps the terminal, which needs a pseudo-terminal the JVM cannot open, or a `script(1)` log that would hold the token on disk; the plan allowed the paste to stay in that case.
A credential in the environment, or accounts already saved, take the full step instead, which reads and keeps those.

## A GitHub account in the first run

The step says what the account is for: the agent's access to GitHub, and the name on its commits.
The host's `gh` login is one of its choices, agreed on 2026-11-20, named by its login and saying that the agent would act with that account's own access.
It is taken only when the person types its number: Enter skips GitHub and never takes it.
A token of the agent's own account is the first choice, with the guide `isx account add github` shows.
A namespace that already holds accounts, usable or not, gets the full step, which keeps them.

## The continuation under `sg` asks nothing

On a fresh Linux host the first plumbing step adds the user to `incus-admin`, and init runs its command line again under `sg incus-admin` (DESIGN.md "Goals", `InitCommand.continueInGroup`).
The questions came before, so the continuation asks none of them.
What is a setting is in config.yaml by then: the preferred agent, the credential, the GitHub account and the Git identity.
What stores nothing is passed beside `ISX_INIT_CONTINUED`, in `ISX_INIT_ANSWERS` (`InitCommand.FirstRunAnswers`): that the questions were asked, and which were declined, the agent's credential, the GitHub account, the identity and "none for now".
None of them is a secret.
The parent stays alive until the continuation ends, so the downloads it started go on, and the continuation's build waits for a file still downloading under the cache's lock.
The closing offer to open the agent is the continuation's, since it holds the group the branch needs.

## Nothing is taken from experienced users

The first run gets shorter, and isx does not get smaller.

- Every step a first run leaves out runs by name, `isx init credentials | search-paths | code-directories | mcp`, and is in the menu of a later run (#2407).
- `isx init --all` asks every step in turn, those left out included, on a first run or a later one.
  They are asked with the first run's questions, before the plumbing, so DNS still follows the search paths.
- The flags that answer init's questions stay; in a first run, a step whose answer flag is given runs with that answer.
- The completion message lists each deferred step in one line, with its command.
- `InitFirstRunTest#everyStepIsInTheFirstRunOrInTheMenu` holds that every step `isx init --plan` lists, on Linux and on macOS, is in the first run or in the menu, so a step cannot be dropped from both.
