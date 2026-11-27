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
An `isx init` step's title and hint are values of `isx init --plan` too, its `title` and `purpose`: a reworded hint keeps the purpose it had in `InitCommand.PURPOSES` or a `HostSetup` `*_PURPOSE` constant, and a title is not reworded.

## The person meets a flow, not a file

A person reads a flow from its first line to its last, in one terminal.
Review a change to interface text the same way: run the flow, or read its transcript, from the top.
A message that reads well in its source file can repeat the line before it, or use a word the flow has not introduced yet.

## The rules

The examples are from the review of the first run of `isx init` (#2528) and from the wording Sanne chose on #2673, which applied these rules after a first pass had applied them too literally.
Each rule below says where it applies, and a text outside that place is not made to follow it.

**What is asked comes before why, and each answer says what it does.**
`Set up isx for Claude Code, so templates that include it can run it? (Y/n/other)` let `n` choose no agent at all, where a person meant "another one".
The review asked for `Use Claude Code? (Y = yes, o = choose another, n = no agent)`.
Spell the answers out only where one means more than yes or no.
Plain yes and no stay `(Y/n)`, with any other answer after it: `(Y/n, or type another directory)`, not `(Y = yes, n = no, or another directory)`.
What the text says an answer does is what it does: an intro that ends "Press Enter to skip it for now" must not be followed by a question whose default runs a program.

**A prompt says what Enter does in one of two forms.**
Where Enter takes one of the answers, that answer is in brackets: `Choice [1]:`, `Choice [Y]:`, `(Y/n)`.
Where Enter does something no answer does, the prompt ends in it: `Choice, or Enter to skip:`, `Paste the token, or Enter to go back:`.
Where Enter does nothing, the prompt says nothing of it: `Choice:`.
The first run once asked `Choice (Enter to choose Claude):` one screen and `Choice [1]:` the next, for the same thing.
`Prompts.choiceEnterTakes`, `Prompts.choiceEnterDoes` and `Prompts.choice` build every `Choice` prompt, `choose` and `chooseDefault` included, and `ChoicePromptGateTest` fails a `"Choice` literal anywhere else in main source (#2593).

**A step that starts something says what will happen, in order, before it asks.**
"Run 'claude setup-token' now?" did not say that a program takes the terminal, opens a browser and prints a token to paste.
The step should list those three events, then ask.

**The point comes before the mechanism.**
"isx sets this host up for one agent: its account, and a first template that installs it" opens with isx's plan.
Say what the person gets and what they are asked, and leave the mechanism out unless it is what they need to decide.
Leave out what does not matter at this step: the Claude model was shown in the agent step, where nobody chooses it.
A hint says what the person gets and where their credential stays, and the route through the proxy is for the docs.
"They stay on this Mac: isx adds them to requests on the way out" is enough, where "isx adds it to requests as they leave an instance, and never puts it inside one" explained the mechanism.

**No isx term before the text has introduced it.**
"template", "proxy", "placeholder" and "branch" mean nothing to a person on their first run.
"instances hold a placeholder, and the proxy adds the real credential to each request" uses three of them in one line.
Introduce a term in the person's words before using it, or do not use it.

**One word for one thing, from one vocabulary.**
Every interface text uses the words of the "One word per concept" rule in `writing.md` "Voice", the first run included (Sanne, #2673):

| Thing | Its word | Never |
| --- | --- | --- |
| What an agent runs in | instance | machine, container, box |
| The computer isx is installed on, in the first run | this Mac; on Linux, this computer | host |
| The computer isx is installed on, everywhere else | host | |

"instance" is the noun the CLI names, so a person who learns it in the first run reads it in every command afterwards.
The first run's intro explains it before any other text uses it: "Your agent is ready to open in an instance: its own isolated Linux system."
The first run once had its own word, "isx machine", and the closing message had to say that isx calls it an "instance": two words for one thing.
A text never switches between two words for one thing.

"The first run" is narrow: what `isx init` shows a person while it walks them through their first setup, from its intro to its closing box.
It is not `isx init --help` or `--plan`, and not an error from code that other commands share, such as a sign-in's.
Everywhere else the computer is the host, as `writing.md` "One word per concept" says, and "host" is the clearer word where a text means the host as against an instance: "Set up isx on the host", "neither of Codex's sign-in callback ports is free on this host".
A first pass on #2673 read "the first run" as everything `isx init` prints and replaced "host" in those two texts; Sanne asked for "host" back.

**One outcome, one line, in the person's words.**
"Token generation complete.", "Verifying OAuth token", "OAuth token verified." and "Claude auth configuration saved" were four lines for one outcome.
They should be one line, `BuildOutput.ok("Token verified and saved on this Mac")`, which prints `✓ Token verified and saved on this Mac`, and nothing else.
A header, an outcome line and a status line may be a fragment, and the lines of one block take the same form: `✓ Proxy certificate ready` above `✓ SSH access to instances ready`, and `✓ Setup complete` or `! One step left: start isx's services` as a closing box's header.
A line that explains or advises stays a full sentence.
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
A verb that says what happened is often shorter than a negated one: "The ChatGPT sign-in timed out after 15 minutes", not "isx did not receive a sign-in within 15 minutes".
Name the actor where the person would not otherwise know who acts.
A line about a thing makes that thing its subject, and a line of progress needs none: "The proxy is running with old settings" and "Restarting it to load the new ones", not "isx's settings changed after the isx proxy started".
isx is never the subject only so that a line has one: "Clone your templates to ~/…?", not "isx would clone your copy to this directory".

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

## The transcript gate

Two gates read interface text's string literals for its form alone: `StepLabelGateTest` holds a label bare, with no `...` and, for a live step, an `ok` or a `success`, no period, and `RawOutputGateTest` fails a print literal indented by hand.
`TranscriptGate` (cli tests) reads what a text says, in the transcripts of the main flows as a terminal draws them, since a message is assembled from several literals.
`InitTranscriptsTest` runs it over every branch of the first run, and `AccountAddTranscriptsTest` over every path of `isx account add`; each writes its transcripts to `cli/target/` for a person to read.
Each is worded for its platform through `Platform.wordedAs`, the paths of `isx account add` once as each, so a Mac's text is read on Linux; a branch that says the other platform's "this Mac" or "this computer" fails (#2588).
It checks what is mechanical: the line width, the words a flow must not use (the "Never" column above) and a step number shown once.
It also flags a negated noun ("none", "nothing", "nobody", "no one", and "no" before a word other than "longer", "more" or "less").
It does not flag what `writing.md` "Negate the verb" keeps, in any tense: "has no", "needs no", "there is no", "nothing" at the start of a sentence, "costs nothing" and "does nothing".
It reads a line together with the line whose sentence it continues, since the flows wrap a sentence by hand; a blank line, a box's edge or the person's answer ends a sentence.
A use that is meant, such as the option label "None for now", is listed in `TranscriptGate.MEANT` with what it labels, and matches only a whole line where it is that label.
A new flow with a transcript test runs the same gate, with the words that flow never uses.
Terms used before they are introduced need a reviewer, and so does every flow without a transcript test.
