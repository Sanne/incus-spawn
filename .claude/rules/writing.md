---
paths:
  - "README.md"
  - "DESIGN.md"
  - "docs/**"
  - "site/**"
---

# Writing prose: README, DESIGN.md, docs, the website

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
"Terminates TLS" becomes "adds the credentials as the requests leave the machine".
The README is the reference; the home page is read once, quickly.

**Say what is meant before using the word.**
Introduce "branch" (what `isx branch` makes) before using it as a noun; the same for template, remote, worktree.

**"Instant", not a time.**
Branching is sub-second and the project is proud of it; "in seconds" undersells it and invites a comparison.
The value is creating a new machine, never disposing of one.
