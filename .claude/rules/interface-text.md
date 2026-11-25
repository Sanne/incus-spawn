---
paths:
  # Every source that words a text a person reads in a terminal or the TUI (#2542).
  # Package globs, so a new command, step or route is covered without editing this list.
  - "cli/src/main/java/dev/incusspawn/IncusSpawn*.java"
  - "cli/src/main/java/dev/incusspawn/command/**"
  - "cli/src/main/java/dev/incusspawn/tui/**"
  # Tool descriptions and refusals: a model reads them, and a person sees them quoted.
  - "cli/src/main/java/dev/incusspawn/mcp/**"
  # The `isx doctor --explain` notes and the `isx update-base` errors.
  - "cli/src/main/java/dev/incusspawn/ai/**"
  - "cli/src/main/java/dev/incusspawn/baseimage/**"
  - "common/src/main/java/dev/incusspawn/lifecycle/**"
  - "common/src/main/java/dev/incusspawn/tool/**"
  - "common/src/main/java/dev/incusspawn/incus/**"
  - "common/src/main/java/dev/incusspawn/vm/**"
  - "common/src/main/java/dev/incusspawn/config/**"
  - "common/src/main/java/dev/incusspawn/git/**"
  - "common/src/main/java/dev/incusspawn/ssh/**"
  - "common/src/main/java/dev/incusspawn/util/**"
  - "common/src/main/java/dev/incusspawn/Platform.java"
  - "common/src/main/java/dev/incusspawn/Warnings.java"
  # What the proxy answers a guest (the 403 bodies) and the ChatGPT sign-in's prompts.
  - "common/src/main/java/dev/incusspawn/proxy/**"
  - "proxy/src/main/java/dev/incusspawn/proxy/**"
  # The descriptions of built-in tools and templates.
  - "common/src/main/resources/tools/**"
  - "common/src/main/resources/images/**"
  # Scripts isx installs in the guest, which print advice there.
  - "common/src/main/resources/isx-trust"
  - "common/src/main/resources/git-remote-isx"
  # The install scripts, which a person runs before isx exists.
  - "get-isx.sh"
  - "install.sh"
---

# Writing interface text: prompts, output, warnings, the TUI

This file is for the text isx shows a person: prompts and the `isx init` wizard, command output, warnings and errors, the TUI, `--help`, `isx doctor` findings, the proxy's 403 bodies and `isx mcp` tool descriptions.
It holds what the prose rules do not say.
The principles are shared, and they are in `.claude/rules/writing.md`: "What the reader needs comes first", "A clear sentence" and the "One word per concept" rule under "Voice" hold here in full.
Read those sections before rewording a flow.
Which output call prints a text is `.claude/rules/terminal-output.md`; this file is how the text is worded.

Output a script reads is a contract and is never reworded: the field names and values of `--format=plain|json` stay as they are (`.claude/rules/commands.md`).

## The person meets a flow, not a file

A person reads a flow from its first line to its last, in one terminal.
Review a change to interface text the same way: run the flow, or read its transcript, from the top.
A message that reads well in its source file can repeat the line before it, or use a word the flow has not introduced yet.

## The rules

The examples are from the review of the first run of `isx init` (#2528), which asked for each change shown; until that work lands, the code still prints the old text.

**What is asked comes before why, and each answer says what it does.**
`Set up isx for Claude Code, so templates that include it can run it? (Y/n/other)` let `n` choose no agent at all, where a person meant "another one".
The review asked for `Use Claude Code? (Y = yes, o = choose another, n = no agent)`.
What the text says an answer does is what it does: an intro that ends "Press Enter to skip it for now" must not be followed by a question whose default runs a program.

**A prompt says what Enter does in one of two forms.**
Where Enter takes one of the answers, that answer is in brackets: `Choice [1]:`, `Choice [Y]:`, `(Y/n)`.
Where Enter does something no answer does, the prompt ends in it: `Choice, or Enter to skip:`, `Paste the token, or Enter to go back:`.
Where Enter does nothing, the prompt says nothing of it: `Choice:`.
The first run once asked `Choice (Enter to choose Claude):` one screen and `Choice [1]:` the next, for the same thing.

**A step that starts something says what will happen, in order, before it asks.**
"Run 'claude setup-token' now?" did not say that a program takes the terminal, opens a browser and prints a token to paste.
The step should list those three events, then ask.

**The point comes before the mechanism.**
"isx sets this host up for one agent: its account, and a first template that installs it" opens with isx's plan.
Say what the person gets and what they are asked, and leave the mechanism out unless it is what they need to decide.
Leave out what does not matter at this step: the Claude model was shown in the agent step, where nobody chooses it.

**No isx term before the text has introduced it.**
"template", "proxy", "placeholder" and "branch" mean nothing to a person on their first run.
"instances hold a placeholder, and the proxy adds the real credential to each request" uses three of them in one line.
Introduce a term in the person's words before using it, or do not use it.

**One word for one thing, from one vocabulary.**
The first run (`isx init` and what it prints until its closing message) has its own words, which Sanne set in #2528:

| Thing | In the first run | Never in the first run |
| --- | --- | --- |
| What an agent runs in | isx machine | instance, machine alone, container, box |
| The computer isx is installed on | this Mac; on Linux, this computer | host |

The first run's closing message is the one place it says that isx commands and docs call an isx machine an "instance".
Every other interface text uses the words of the "One word per concept" rule in `writing.md` "Voice", where "instance" is the noun the CLI names.
A text never switches between two words for one thing.

**One outcome, one line, in the person's words.**
"Token generation complete.", "Verifying OAuth token", "OAuth token verified." and "Claude auth configuration saved" were four lines for one outcome.
They should be one line, `BuildOutput.ok("Token verified and saved on this Mac")`, which prints `✓ Token verified and saved on this Mac`, and nothing else.
An `ok` label takes no period and no `✓` of its own: `TerminalOutput` adds the mark, and `StepLabelGateTest` fails a label that ends in a period.
When a program isx runs gives advice meant for someone using its output by hand, such as "Store this token securely", isx answers it in a `BuildOutput.stepNote` under the `ok` line: "You do not need to store or export it. isx keeps it."

**Input the person cannot see gets a receipt.**
A pasted secret is not echoed, so `Received 108 characters (sk-ant-oat01-...AwAA)` is the only sign that the paste arrived, and its length and masked ends show whether it is the right one.
It is not an outcome line, so the rule above does not remove it: the first review of the first run did, and the GitHub step then went from the paste to its next question with nothing said (#2565).
It is plain, with no tick: a tick is for a credential that was checked.

**A warning says what it means for the person and what to do, and is printed only where it applies.**
A person who chose "your own account" for GitHub, and with it their Git identity, was then warned during the build that agents "have no Git identity to commit under".
Before adding a warning, check each path that reaches it: a warning that contradicts the person's last answer teaches them to ignore every warning.

**Name the actor, and negate the verb, never the noun.**
Write "does not own any", not "owns none" (`writing.md` "Negate the verb").
This is the fault that keeps coming back: one review found "No machine ever holds it" and "A new account owns none yet", both by an author who knew the rule.
Do not deny something nobody asked about.

**Name what the person will see, in that screen's own labels.**
A pointer to a TUI tab, a key or a button uses the label the person will read there.
A step that sends the person to a web page uses that page's labels: "Repository access", "All repositories", "Generate token", not "choose the repos to grant".

**A sentence changes what the person does or expects.**
If they would act the same without it, cut it.
Ask this of every line.

**Do not explain a word with itself.**
"The token reaches what the agent's account can reach" tells the person nothing.
Going vague to avoid recommending a workflow is still going vague: state the facts and leave the choice, as in "A new account does not own any repositories yet, so its list is empty."

**A line fits its frame.**
A line wider than the terminal wraps, loses its indent and breaks the block it belongs to.
The first run's frame is its step box: `InitCommand.BOX_WIDTH`, 62 columns between the borders, so a box is 64 columns wide.
A line under a step header, its indent included, is at most 62 columns.
A TUI label stays within its pane.

**A step header shows its number once.**
`[2/3]` in the header's corner, without a leading `2` before the title.

**Separate the blocks of a long step.**
An explanation, a list of steps and the question are three blocks, with a blank line between them, as the GitHub step lays out its numbered choices.

## No test reads the wording yet

Two gates read interface text's string literals for its form alone: `StepLabelGateTest` holds a label bare, with no `...` and, for a live step, an `ok` or a `success`, no period, and `RawOutputGateTest` fails a print literal indented by hand.
No test reads what a text says.
`DocsShapeTest` exists because the prose rules alone did not hold for the docs, and these rules will not hold alone either.
The gate that is worth having reads generated transcripts of the main flows, not Java string literals, since a message is assembled from several of them.
It checks what is mechanical: the line width, the words a flow must not use (the first run's "Never" column above, before the closing message's line) and a step number shown once.
It also flags a negated noun ("none", "nothing", "nobody", and "no" before a noun), with a marked exception for each use that is meant, such as the option label "None for now".
It does not flag what `writing.md` "Negate the verb" keeps: "has no", "needs no", "there is no", "nothing" as the subject, "costs nothing" and "does nothing".
Terms used before they are introduced need a reviewer.
Until that gate exists, a review checks every rule here.
