---
paths:
  - "README.md"
  - "DESIGN.md"
  - "design/**"
  - "docs/**"
  - "site/**"
---

# Writing prose: README, DESIGN.md, docs, the website

The reader has little patience.
They skim, read a sentence or two where the page seems to answer them, and leave.
Some know Incus and TLS well, some are new to both, and many read English as a second language.
Every rule here serves that reader.
The home page (`site/index.html`) is the reference example of the shape and the voice.

The Shape and Voice rules are checked by `DocsShapeTest` and `DocsOneSentencePerLineTest` (`cli`) on `README.md` and `docs/*.md`.
`DocsListsTest` checks that the pages' lists mirror the code's: top-level commands, built-in tools, and tools with a proxy credential.
A fact that is a list in the code is a list in the docs, and the test is what keeps them equal.
`docs/dev/` is maintainer notes and is exempt from the budgets, and DESIGN.md and its `design/` pages keep their own register.

## Clarity comes first

Every other rule on this page serves this one, and no test checks it.
A sentence is clear when a newcomer to isx, reading in a second language, takes its one meaning on the first pass.
An expert reading the same sentence finds nothing left open either.
When a clear sentence and a short one conflict, the clear one wins.
The budgets under Shape are ceilings, never targets: a sentence that passes them and makes the reader guess has failed.
These rules hold for every text a person reads: the user pages, the website, DESIGN.md and `design/`, a PR description and a review comment.

**Negate the verb.**
Write "isx does not filter the connections", not "isx filters nothing".
A reader takes "isx filters" as a yes, and has to reverse it at "nothing".
This holds for a verb of action: filter, list, match, grant, approve, rebuild.
"Has no", "needs no" and "there is no" are plain English and stay, and so does "nothing" as the subject ("nothing it changes reaches the template").

**A verb names what it acts on.**
"Filters" what: requests, callers, ports?
"Does not approve" what: a template, an instance, a command?
The reader should never have to carry the object over from an earlier sentence.

**A place and a direction are named.**
"On the way", "on the way out", "in between" and "along the path" leave the reader to rebuild the route.
Say from where and to where: "as the request leaves the instance", "between the instance and the host service".

**Literal words, not idiom or understatement.**
"Under the hood", "on its own", "says nothing about" and "at no cost" each have a literal form that is as short.
The check is a translator, a machine one included: would the meaning survive?
Understatement is the same fault in a warning: "may be surprising" where the truth is "deletes the instance".

**State the consequence.**
Two facts joined by "and" leave the reader to work out why they sit together.
In a warning that inference is the message, so write it, with the true connective (Voice, "A connective must be true").

**The worked example** is a sentence that passed every budget:

> Many development services trust a loopback caller completely, and isx filters nothing on the way.

It negates the object, names neither what is filtered nor where, and stops before its point.
Rewritten:

> Many development services trust any caller on loopback completely.
> isx does not filter the connections it passes to the service, so an instance can do whatever the service allows.

The rewrite is nearly twice as long and is the better text.
It is in `docs/INTEGRATIONS.md`, "What exposing a port gives away".

## Shape

**A paragraph holds one idea**: at most four sentences and about 60 words.
Past that it becomes a list, a table or a subsection.
Two ideas get two paragraphs.

**A sentence is about 20 words and never over 40.**
A parenthesis holds an example, a path or a flag, never a clause with a verb of its own.
"Say" and "e.g." asides become a sentence of their own, or go.

**Facts that are a list get a list.**
An item is one or two sentences.
Fields, flags, modes and commands get a table, one row each.
Ten independent facts in one paragraph is the shape to avoid.

**A page opens with two or three sentences**: what this is, and when you need it.
A section opens with one sentence saying what it is for.
The reader decides from that opener whether to read on.

**One sentence per line in the source** (CLAUDE.md "Markdown source").
A 40-word sentence is then a 300-character line, visible in every diff.

## Voice

**"You" is the reader, "the agent" is the agent, isx and the proxy do things.**
No passive where isx or the proxy is the actor: "isx removes the remote", not "the remote is removed".

**No dash joins two parts of a sentence.**
Not an em-dash, not an en-dash, not a spaced double hyphen.
A full stop or a colon takes its place.
A hyphen still joins words (copy-on-write), and a range is written "9 to 10".

**Styling is rare: bold only for a defined term, code font only for what the reader types or reads back.**
Never bold or italic for emphasis inside a sentence.
If a sentence needs emphasis, it needs rewriting.
A list item's lead-in may be bold when the list defines terms; a list of plain statements needs none, the line break already sets each off.
Code font marks a command, a flag, a key, a file name or a value the reader will see verbatim.
A concept ("orphan", "session", "the proxy") is a word, never a code span, and a UI label ("tick MCP control") is plain text.
A page where every line carries a code span or a bold run reads like a log, and the eye has nothing to rest on.

**One word per concept.**
- *template*: the image you prepare once (`isx build`).
- *branch*: an instance made by `isx branch`; use it when that is the point, as in "the agent's branch".
- *instance*: the generic thing isx runs and the CLI names; the default noun in the docs.
- *machine*: the pitch word, on the home page and in the README's opening; not in reference pages.
- *container*: only where it contrasts with a VM; an instance is a container or a VM.
- *host*: your computer, where isx and the proxy run.
- *agent*: the AI coding agent inside an instance.

**No issue numbers, class names or "now" in README and docs.**
They are for DESIGN.md, which records why.
A user page says what is, never what changed.
A roadmap item, or a limitation a user may still meet, may link to its issue, as a link.

**A connective must be true.**
"So", "therefore", "because", "which means" claim that the second statement follows from the first.
Use one only when it does; the reader checks, and a false "so" reads as a non sequitur.
Two statements that merely sit together get a full stop, or the real relation spelled out.
The isx.run credentials section is the worked example: "the agent can do anything inside its machine, with no approval steps to babysit.
*So* the machine holds nothing worth stealing" claims the second follows from the first, when the truth runs the other way: the freedom is *safe only because* the machine holds nothing worth stealing.
Write the true direction.

**Lead with the reason, then the mechanism.**
A section opens with why it exists for the reader (the agent must be free to act, so its machine cannot hold secrets), and names the implementation (the proxy, copy-on-write, Incus) afterwards, as what makes it work.

**Short factual sentences, plain words.**
Short is the second virtue: "Clarity comes first" decides when the two conflict.
"Terminates TLS" becomes "adds the credentials as the requests leave the machine".

**Say what is meant before using the word.**
Introduce "branch" (what `isx branch` makes) before using it as a noun; the same for template, remote, worktree.

**"Instant", not a time.**
Branching is sub-second and the project is proud of it; "in seconds" undersells it and invites a comparison.
The value is creating a new machine, never disposing of one.

## Documents

**The home page** is the pitch, read once, quickly.
It says what isx is worth, not how it works.

**The README** is the newcomer's path, and nothing else.
Its order: the pitch, Quick Start, the core concepts at about 150 words each with a pointer to their page, the CLI table, further reading.
Argument, reference and history go to a `docs/` page.
`DocsLinksTest` keeps it short.

**A `docs/` page** is a reference.
Its reader arrived from a search with a question and wants the answer, not the story.
An opener, sections with one-sentence openers, tables for the enumerable.

**One page, one reader.**
A person setting something up, a programmer writing against an interface, and a maintainer wanting to know why are three readers, and a page serves one of them.
`docs/MCP.md` is for the person; `docs/MCP-PROTOCOL.md` is for the programmer; DESIGN.md is for the maintainer.
A fact the page's reader never acts on does not belong on it: a header name, a file under `/run`, a lock, a helper's name, how a registration reads its secret.
If a reader would never type it or read it back, it goes to the programmer's page or to DESIGN.md.

**Re-read the page when a feature appends a section.**
The shape tests pass sentence by sentence while a page drifts into a second register, which is how `docs/MCP.md` grew past 3,800 words with a Python snippet and protocol field tables.
After adding a section, read the page's opener and ask whether its reader wanted the section.
A page over about 2,000 words of prose has usually gained a second reader and wants splitting.
A release note ("after upgrading, restart", "before downgrading") is never a user page's business; it goes to `docs/dev/`.

**`docs/dev/`** is maintainer notes and is not published.
DESIGN.md and its `design/` pages are the record of why, and the one place for issue numbers and internals.

## Splitting a sentence

**Splitting a sentence** (#1243) changes its form, never its meaning.
These are the only word changes a split may make:
- the joining `;`, `,`, `:` or `—` becomes a full stop, and the next word takes a capital letter;
- a plain "and" at the join is dropped;
- a relative "which" or "who" becomes "This", "It" or "They", naming the same thing.

A connective that claims a relation ("so", "but", "because", "which means", "therefore") stays, as the first word of the new sentence.
A name written in lower case (`dnf`, `vfkit`) keeps its case, so a split before one is a line break only, or not made.
If the sentence does not split within these changes, it stays long.
Never start a new line with `#`, `-`, `+`, `>`, `|`, `<` or a number followed by `.` or `)` and a space, which would turn the sentence into a block of its own.
