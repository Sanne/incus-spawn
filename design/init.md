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

1. It prints a welcome in its banner (`printFirstRunIntro`): two short lines saying what the person came for and that it takes no more than 5 minutes, then the run as three numbered stages (the questions, isx setting up the computer and building an example template, and the agent ready to open in an isx machine).
   The stages are sentences with isx as the actor: an earlier form labelled its rows "You answer", "isx then" and "You get", which read as orders.
   It is kept to seven lines of text, so it leaves out what isx installs (the Linux VM, Incus, the proxy) and what a template is for, which the setup block and the closing box say where they happen; on Linux it keeps the sudo password, which the person must be ready for.
   It starts the downloads of `tpl-minimal`'s build in the background (`BackgroundDownloads`, `BuildDownloads.plan` with nothing counted as built).
   The questions it lists are those this run asks: an account already set up is left out.
2. It asks for the preferred agent (`askAgent`).
   One the person already prefers, else one `HostAgentProbe` found signed in, else one found installed, is offered and chosen by Enter, with `o` for the list and `n` for no agent; the prompt says what each letter does.
   With none found the list is shown once, and Enter chooses nothing: an answer that is no number of the list gets one line saying which numbers there are, so isx never picks a vendor for the person.
   The list shows the agents found on this computer first, each marked as found, then the others, each group by the name shown (`InitCommand.agentsInListOrder`), so no order of isx's own ranks one vendor above another.
   "None for now" is a choice, and stores nothing.
   The answer starts the agent's own pinned downloads, less those already started.
   An agent with a question of its own (`ToolSetup.firstRunQuestion`) is asked it next, unless a `tpl-agent` is kept (#2536).
   Pi and opencode ask which provider: Claude, OpenAI, Gemini, or another, typed and checked against the parameter's pattern.
   The answer is the agent's parameter in the `tpl-agent.yaml` init writes (`- pi: {provider: openai}`), and Claude's writes none, being the default.
   So the next step finds that provider's account through the template, and a provider isx does not manage has no account step.
   The question belongs to the tool, so init names no agent.
3. It sets up the account the agent spends (`setUpAgentCredential`).
   Which one is not named in init: `agentGaps` runs `CredentialCheck.missingByCredential` over the example template for the agent, as a build of it would, and each gap runs the credential step `isx account add` runs for it.
   So Pi and opencode reach the Claude step through the credential their tool declares, and a new agent needs nothing here.
   A step that is not the agent's own is introduced by one line saying so (`InitCommand.sayWhoseAccount`), since its questions name another product: Pi is asked about Claude.
   For an agent with a `provider` parameter whose kept `tpl-agent` decided the provider, it also says to choose "None for now" for another provider, and links the agent's page, which shows how.
   The OpenAI step of a first run for an agent other than Codex offers only an API key, since a ChatGPT session gives Pi and opencode no key (#2525).
   When the account is GitHub's (Copilot), the step is the GitHub question of the next step, which is then not asked a second time.
   For any other agent, GitHub's account is the next step's alone, even when a `tpl-agent.yaml` an earlier run wrote lists `gh` (`agentGaps`), so that question always has its own header and count.
   A namespace that already has a usable account is not asked again.
4. It asks as which GitHub account the agent acts (`CredentialSetup.offerGitHubForAgent`), and after "None for now" asks the Git identity question of design/accounts.md "A Git identity no account supplies".
5. On Linux it asks for sudo, and installs the dependencies.
6. It runs the plumbing as one block (`firstRunPlumbing`): on Linux the dependencies first, then today's steps that ask nothing, the Git remote helper and DNS among them, then the services.
   The block shows one line per outcome (#2528): it binds `Output.detailsLogged`, so a step line, a note and a list go to the client log, and are shown only before a warning or a question that follows them.
   A host command's output goes to the log too (`InitCommand.hostOutputToLog`), with the last lines of a failed one shown under its step.
   sudo asks again with the terminal released only when its credentials expired, so its prompt is never drawn over.
   The proxy service's lifecycle lines stay in its own log, and a continuation under `sg` carries on the block under its parent's header.
   DNS is inside the block, since no step before it adds a search path, whose tools' domains it writes.
7. It writes `tpl-agent.yaml` for the agent (`ExampleTemplate`), or with no agent nothing, and builds it, or `tpl-minimal`.
   The build shows its live steps, outcomes and warnings, its details in the log, as the block of setup does.
   It still warns of every account the template lacks, the one the person skipped included, though the completion message names it too: Sanne chose the warning over saying it once (#2528).
   Its step labels name no container or image ("Starting from tpl-minimal", "Restarting the build", "Stopping the build"), as every build's now do.
   A `tpl-agent` already there is said to be kept only when it installs another agent than the one chosen.
   A template with no coding agent in its chain says nothing of agents' Git identity: `tpl-minimal`, built first for `tpl-agent`, told a person who had just chosen their own GitHub account that its agents had no identity (`AgentGitIdentity.applyAndReport`).
   It lists the `gh` tool after the agent only when a usable GitHub account is saved by then, so the first agent uses the account the person gave and a person who skipped it gets no missing-credential warning (#2535).
   A definition already named `tpl-agent`, in the person's directory or a search path, is built as it is and nothing is written, since a second one would make the build refuse both.
   Only trusted definitions decide the first template: a project's `.incus-spawn/` in the working directory is a cloned repository's.
   When a build there would read a project's template or tool for a layer of the chain, init builds and opens nothing, and says to build from a directory with no `.incus-spawn/` of its own.
8. It prints the completion message, then asks `Run 'isx branch my-first --from tpl-agent' now?`, which branches `my-first` (or the first free `my-first-<n>`) from the template.

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
The box holds the result and the next command: the template, built or not, which is where "template" is explained in every variant of the box, and the command that opens the agent in a new isx machine (`InitCommand.firstRunNextSteps`).
When more is left, it lists it as numbered steps, one command to a line, in the order that works: start the services, build the template, add the agent's account, open the agent.
Its title reads "Setup complete" only when opening the agent is all that is left, and otherwise counts the steps left; a build that failed is named.
An agent that needs something of the person at its first start says so under its step (`ToolSetup.firstStartNote`): Antigravity CLI on a Google account signs in from the instance.
Under the box come the commands for later: return to the machine, list the machines, edit the template, the templates guide and `isx init`.
Its last line says that isx's commands and docs call an isx machine an "instance".
No line is wider than the box, which `InitFirstRunTest#everyLineOfTheClosingBoxEndsAtItsBorderAsTheTerminalDrawsIt` holds with escapes on: a link is an OSC 8 string, whose URL the terminal does not draw, and padding counted it until #2528.

**A run that stops half way says how to go on.**
A setup step that fails ends the run with its error, after a line saying that the agent and the accounts set up are saved and that `isx init`, run again, keeps the agent on Enter and asks only for the accounts skipped.

**The agent opens only when the person says so.**
An agent takes the whole terminal, so opening one unasked would hide the completion message, which comes first.

## Skipping the agent's credential

A person may not have the credential at hand, or may want to see isx work first.
Init then does everything else, so the agent opens as soon as the credential is added.
The build inside init runs with `BuildCommand.askForAccounts` off, so it does not ask again for the credential just declined, and warns as a build without a terminal does.
The completion message says the agent has no account yet, with the command that adds one, and init does not ask to open the agent, which could not sign in.
The first `isx branch` at a terminal asks for it (#2405).

A Claude Code subscription found on the host answers which kind of account to set up (`CredentialSetup.setupClaudeSubscription`): it is re-issued with `claude setup-token`, never copied, since the proxy and the host's Claude Code would otherwise share one refresh token.
The step says what will happen before it asks: the command runs and opens the browser, the person approves, then isx reads the token, or the person pastes it when isx cannot.
Its answers are `Y` (run it here), `n` (run it by hand), `o` (another kind of account) and `s` (skip), and Enter at the paste goes back to them.
With `Y`, isx reads the token the command prints, so the person does not copy a wrapped token of about 108 characters back into the terminal (#2543).
The command draws a full-screen program and needs a terminal, which the JVM cannot open, so it runs under `script(1)`: `script -q /dev/null claude setup-token` on macOS, `script -q -e -f -c 'claude setup-token' /dev/null` with util-linux.
`script` gives it a pseudo-terminal with the keyboard's settings and window size and logs to `/dev/null`, and isx copies every byte of its stdout to the screen while it reads it (`SetupTokenCapture`).
Nothing is written to disk: the token is held in memory until it is verified, then saved in `config.yaml` like a pasted one.
Claude Code places words with cursor moves, wraps the token at the window's width and may redraw only the cells that changed, so isx reads a small screen model rather than matching the bytes.
Each reading is a candidate: up to three whole ones are verified, longest first, and the first that verifies is saved with one line saying so.
When none is read or none verifies, the step says so and the person pastes the token, told the prefix it starts with.
When `script` is missing (Fedora ships it as `util-linux-script`), the command gets the terminal itself and the person pastes the token too.
A reading that did not verify is never saved without the person's answer at that paste.
Before a run, isx keeps the terminal's settings (`stty -g`) and restores them after a timeout or an interrupt, since a `script` that dies on SIGTERM may leave the terminal raw.
Masking the token on the screen, so it stays out of the scrollback, is left for later: masking a wrapped token inside another program's drawing is harder than reading it.
A pasted token whose shape is off is warned of before its check; a token read from `CLAUDE_CODE_OAUTH_TOKEN` was never pasted, so nothing is said of a paste.
A credential in the environment, or accounts already saved, take the full step instead, which reads and keeps those.

## A GitHub account in the first run

The step is about identity: as which account the agent deals with other people on GitHub.
It has three options, each saying what it gives and what it costs: an account of the agent's own (recommended), the person's own account through the host's `gh` login, and "None for now".
There is no default, so Enter asks again, and the `gh` login is taken only when the person types its number.
With no signed-in `gh`, the second option is absent, and a line says how to get it.
The agent's own account gets a guide that starts from being signed in as that account, which a browser signed in as the person is not.
So isx neither opens the pre-filled token page nor prints a link to it: a click opens the browser that is signed in as the person.
The guide's second step names the page where it is used, GitHub's page for a new token, and says isx has an address for it that fills in the permissions.
At the token prompt `c` copies that address to the clipboard, to paste in the window signed in as the agent, and `u` prints it.
`c` is offered only where a clipboard command reaches the person's own screen (`CredentialSetup.clipboardCommand`: `pbcopy`, `wl-copy`, `xclip` or `xsel`, and never over SSH), which gives up clicking for everyone and copying for a person on a remote terminal.
Enter at that prompt goes back to the three options.
A token that verified asks which email the agent's commits carry: GitHub's private address, `<id>+<login>@users.noreply.github.com`, or the account's primary one, Enter taking the private one.
With no primary address to read, the private one is taken without asking, so a token without the email permission no longer asks for another token.
A token that still belongs to the `gh` login is a mistake the guide cannot rule out, so isx names it and asks `Use it anyway?` before the email question (#2544).
No, Enter included, goes back to the token prompt, and `isx account add github` asks the same.
The two logins are compared without case, as GitHub compares them; without a signed-in `gh`, the step has nothing to compare with and does not ask.
A namespace that already holds accounts, usable or not, gets the full step, which keeps them.

## The first run's words (#2528)

Sanne reviewed the first run screen by screen on a Mac, and the text it prints follows nine rules agreed then (#2528, #2542).
What an agent runs in is an "isx machine", the computer is "this Mac" or "this computer" (`Platform.thisComputer`), and the closing message bridges to "instance" once.
That word is the first run's alone: a credential step shared with `isx account add` says "instance" outside it (`CredentialSetup.machines`), and nothing else in isx was renamed.
What is asked comes before why, a step that starts something says what will happen first, and each answer says what it does.
A step header shows its number once, as `[2/3]`.
The count is the run's own: before the agent is chosen it counts the questions for the agent offered first, and with none to offer it shows the most a run asks: the agent, its account and GitHub (`InitCommand.questionsFor`).
So the total can drop at the next header and never reads `+`; it grows only when the person turns down the agent offered for one with an account to set up.
After the choice it counts again, so a Copilot run, whose account step is the GitHub question, reads `[2/2]`, and the intro's first stage lists the same questions.
With no agent chosen, the GitHub step and the Git identity question speak of isx machines, never of "the agent" (`CredentialSetup.noAgent`).
A check that fails is answered with one letter per meaning across the step: `Y` tries again and `s` skips, as in the step's own menu.
Every key the first run accepts is checked before it is saved, or its definition says why it cannot be (#2566).
OpenAI's key is checked with `GET /v1/models`, and a tool's secret with the `check.url` its configuration entry declares (`SecretCheck`), sent with the header its own auth entry for that host injects.
That host must be a literal domain of such an entry, so a check never sends a key where the proxy does not, and `ToolDefValidator` refuses any other.
`SecretCheckTest` holds every built-in secret to a check or a `check.none` reason.
The check always comes from the definition the proxy uses, loaded without the project's `.incus-spawn/tools/` (`CredentialSetup.toolsOf`), since a cloned repository's definition could otherwise name its own host and receive a real key.
A 2xx verifies a key, a 401 rejects it, and so does a 400 that calls it invalid or expired; any other answer, a 403 included, lets the person save it unverified.
`s` after a failed check skips the account and saves nothing of the step.
Claude's and GitHub's steps check their credentials in code; Bob's, Atlassian's and TypeSafe's keys declare `none`, since no documented request checks them.
A pasted key nothing checks (a user's tool that declares no check, or `none`) is reported in one line, `saved on this computer, not checked`, with the command that replaces it (#2528).
Every pasted secret gets a receipt first, `Received N characters`, with its masked ends (`CredentialSetup.received`, #2565).
A secret is not echoed, so the receipt is the only sign that the paste arrived, and it comes before a check that can take a while and before the GitHub step's email question, which is asked ahead of the outcome line.
It carries no tick, which is kept for a credential that was checked, and it costs a second line beside the outcome line.
A ChatGPT sign-in by one-time code that did not finish offers the same `Y` and `s`, and `Y` asks for a new code, since a code expires by itself (`CredentialSetup.signInChatGptWithDeviceCode`).
Saving unverified (`u`) is offered only when the check could not run or reach the service (`CredentialSetup.AuthResult.reached`), since a credential the service rejected fails the same way in every isx machine.
A namespace with a hosted sign-in (Gemini's Google account) offers its kinds as the Claude step does, each with a line under it and "None for now", and offers the hosted kind only to the agents that can use it (`ToolSetup.HostedSignIn.tools`): a first run for Gemini CLI never offers Antigravity CLI's Google sign-in.
No line under a step header, its indent included, is wider than 62 columns (.claude/rules/interface-text.md "A line fits its frame"): text built from names breaks through `Lines.wrap`.
`InitTranscriptsTest` renders every branch of the first run through the terminal's own output, escapes on, and reduces it to what a terminal draws; it writes one transcript per branch to `cli/target/init-transcripts/` and fails a line wider than the frame, or a word of the rule file's "Never" column before the closing message's line.
The transcripts are how a branch nobody walks at a terminal is read and reviewed.
Only the host is the test's: what is found, what a check answers, and markers where the plumbing, the build and the branch run.

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
- The completion message names `isx init`, whose menu lists each deferred step.
- `InitFirstRunTest#everyStepIsInTheFirstRunOrInTheMenu` holds that every step `isx init --plan` lists, on Linux and on macOS, is in the first run or in the menu, so a step cannot be dropped from both.
