# The TUI's visual language

These are the rules every screen and dialog of the TUI follows, so that a new one looks like the rest without a decision of its own.
The terminal output of commands has its own rules, in [Terminal output and the TUI](terminal.md) "Terminal Output Visual Language".

## What the rules are for

A person reads the TUI to find one instance and act on it.
So the rows are the loudest thing on the screen, and everything around them is quieter than they are.
Three rules follow from that, and the sections below apply them.

- **Colour and weight are spent on what the person came for.**
  A running instance, the row under the cursor and a warning get them.
  A column header, a border, a hint and a disabled key do not.
- **Nothing is said by colour alone.**
  Every state has a glyph or a word, so the screen reads the same on a terminal with no colour.
- **The screen never waits for decoration.**
  A frame is complete before anything moves over it, and a key is handled at once.

## Colour

A colour is named by the role it plays, never by its hue, and the roles are the fields of `TuiTheme`.
Code asks the theme for a role and never writes a colour of its own.

| Role | Theme fields | Used for |
|---|---|---|
| Text | terminal default, `highlightFg` | A name, a value, what is read first |
| Quiet text | `textDim` | A secondary column, a description, a label of a hint |
| Faint text | `textFaint` | A column header, a disabled key, a placeholder, the version |
| Accent | `accent`, `modalAccent`, `barKeyFg` | The brand chip, the focused panel's title, the showing tab, a key to press |
| Second accent | `accentAlt` | The far end of the logo's gradient, and nothing else |
| Good | `statusRunning`, `statusSuccess` | Running, done, enough storage |
| Caution | `statusWarning` | An operation under way, a stale template, low storage |
| Bad | `statusFailure`, `modalWarn` | A failure, a destructive dialog, critical storage |
| Rule | `panelBorderUnfocused` | A panel's rule, a scrollbar track, the unused part of the gauge |
| Raised surface | `modalBg` | A dialog, a tab that is not showing |
| Input | `inputBg`, `inputInactiveBg` | A text field, focused or not |
| Selection | `highlightBg` | The row under the cursor, the showing tab |

**One accent.**
The accent means "this is where you are, or what you can press".
A second decorative colour would compete with the three state colours, which each mean something.

**State colours mean state.**
Green, amber and red are used only for good, caution and bad.
A decoration never takes one of them, so a green cell always says something is running or done.

**Primary text is the terminal's own.**
A name in a table is drawn in the terminal's default foreground, so it follows the person's colour scheme.
The theme sets a foreground only to make text quieter or to give it a meaning.

### Fills

A filled cell draws the eye more than any foreground colour does, so the TUI fills five things and no others.

- The row under the cursor.
- A dialog, and the inputs in it.
- The brand chip.
- Each tab, as wide as its label and one cell at each side.
- A row that just changed state, for under a second (see "Motion").

The header, the tab bar, the context line and the key bar are not bands.
A tab is filled although the bar is not, because a row of unfilled labels did not show where one label ends and the next begins (`Templates 5   Instances 6`).
The showing tab has the selection's fill, which says "you are here" for a row as well, and the others the raised surface.
On sixteen colours only the showing tab is filled, and the gaps separate the others.
They were filled before this page, and the key bar, a full-width cyan strip, was the loudest thing on a screen whose subject is the rows.
What this gives up is the Midnight Commander look that some people read at once as "a key bar".
The bar keeps its place on the last row and its `F1` to `F10` order, which is what that habit relies on.

### Colour depth

The theme is written in RGB, and every colour is fitted to what the terminal is known to draw before it is written.
tamboui writes an RGB colour as a 24-bit escape whatever the terminal supports, and Termius drew every one of them in the same green (#1681).
`ColorDepth` has three levels.

- `TRUECOLOR`: the colour as written.
  It is used only when the terminal says it draws 24-bit colour, or when `ISX_COLORS=truecolor` says so.
- `INDEXED`: the nearest entry of the 256-colour palette, from its colour cube or its grey ramp.
  This is the fallback, and what an SSH session gets, since SSH does not pass `COLORTERM` on.
- `ANSI`: the sixteen ANSI colours, for a terminal that has no palette.
  No tint exists there, so a dialog is told from the screen by its frame alone, and nothing blends.

Two places fit colours, and both are needed.
`TuiTheme.of` fits every role when the theme is made, so a dialog rendered in a test holds the colours a terminal would get.
`ColorFit` is the last step of every frame the runner draws, and fits whatever a blend left in the buffer.
With it, no 24-bit escape reaches a terminal that has not proven it reads one, whatever a widget or an effect draws.

Surfaces are the terminal's own background moved a few percent towards white on a dark terminal, or towards black on a light one.
The terminal is asked for its background, and `TuiTheme.DARK_BACKGROUND` or `LIGHT_BACKGROUND` stands in when it does not answer.
The amounts are chosen so that two surfaces that sit on each other stay apart after fitting to 256 colours, which `TuiThemeTest` checks.

What the fallback gives up is smoothness.
A fade drawn in 256 colours moves through the grey ramp in visible steps, and the logo's gradient becomes two or three colours.
Nothing else differs between the depths.

## Text

Weight and case say what a piece of text is, in the same way everywhere.

- **Bold** is for the one thing a line is about: the row under the cursor, the name on the context line, a dialog's title, a key to press.
- **Upper case** is for a table's column headers only, drawn faint.
- **Italic and dimmed** together mark a row that is being deleted, and dimmed alone a row that is stopping or restarting.
- **Underline** marks a link that a click opens.

A key is written as it is pressed (`F7`, `Enter`, `Esc`, `Space`), in the accent and bold, and its label follows in quiet text.
An action that opens a dialog ends in `…`, and one that acts at once does not.

## Glyphs

A glyph has one meaning, and `Glyphs` holds them.
Where two meanings share a glyph, the column tells them apart, as with the marked row and the running instance.

| Glyph | Meaning | Where |
|---|---|---|
| `●` | Running | The STATUS column, the header's count |
| `○` | Stopped | The STATUS column |
| `◐` | Between states, or an operation under way while motion is off | The STATUS column |
| `⠋` and its frames | An operation under way | The STATUS column, the context line, a progress dialog |
| `●` | A marked row | The cursor gutter's second cell |
| `▸` | The cursor, and a dialog's focused field | The cursor gutter's first cell |
| `✓` `✗` | Work that ended well, or failed | The context line, the status line |
| `⚠` | Something to look at | The header |
| `!` `△` `↑` `◌` | A template that is outdated, changed, behind its parent, or without a definition | The BUILT column, with a legend under the table |
| `━` `─` | The used and the unused part of the storage gauge | The header |

The gauge is a heavy line as far as the pool is used and a light line beyond, so the weight says the level where there is no colour.

## Layout

The screen is read top to bottom: where am I, what is there, what is selected, what can I press.

```
 isx  1.2.3  ● 2 containers running                  Storage ━━━━━━━━───── 63%  63G/100G
 Overview   Templates 5   Instances 6   Work                             ←/→ Switch view
 Templates ─────────────────────────────────────────────────────────────────────────────
   NAME            BUILT          DISK    DESCRIPTION
 ▸ tpl-minimal     1 week ago     -       Minimal Fedora

 Instances ─────────────────────────────────────────────────────────────────────────────
   NAME            STATUS         IP      PARENT        RUNTIME     AGE
   quarkus-42871   ● Running              tpl-quarkus   container   1h ago
   docs-site       ○ Stopped              tpl-minimal   container   2 days ago
 ───────────────────────────────────────────────────────────────────────────────────────
 tpl-minimal  Minimal Fedora  Space Mark
 F1 Info   F2 Shell   F3 Details   F4 Branch…   F5 Build…   F6 Rename…   F7 Stop   …
```

**A panel is a rule and a title, not a box.**
A box around each table spent two columns and a row on lines that say nothing, and two boxes stacked drew four horizontal lines between the tables' rows.
A panel has one rule across its top that carries its title, and the next panel's rule ends it.
The last panel is ended by a rule with no title, above the messages and the keys, so a warning under the list is not read as one of its rows.
A terminal shorter than 16 rows has no such rule and gives the row to the lists.
The table inside keeps the width it had in a box, through one cell of padding at each side, so the widths at which the OWNER and PURPOSE columns appear are unchanged.
The focused panel's title is bold and in the accent, and only the focused panel shows the cursor, so focus reads without colour.

**Two stacked panels split the height in its middle.**
A panel grows past its half only into rows the other panel's list leaves empty, less one, so the rule between them stays where it is while lists change ([Terminal output and the TUI](terminal.md) "The main screen's tabs").

**A dialog is a raised surface with a thin rounded frame.**
The screen behind it is dimmed, which already says the dialog is on top, so it has no shadow and no double line.
A destructive dialog's frame and message are in the bad colour.

**Every dialog has the same shape.**
Its content starts on the first row inside the frame, one blank row follows it, and its keys are on the last row.
Before, some dialogs opened with a blank row and some closed with one, so two dialogs of the same kind sat differently on the screen.
A dialog that scrolls has no blank row, since its content fills what the screen gives it.

```
╭ Rename 'dev-1' ────────────────────────────────────╮
│ New name:                                          │
│ dev-1                                              │
│                                                    │
│  Enter Confirm   Esc Cancel                        │
╰────────────────────────────────────────────────────╯
```

- **The title** is in sentence case and names what the dialog acts on in quotes: `Rename 'dev-1'`, `Actions for 'dev-1'`, `Credential accounts of 'review-1'`.
- **A label** (`Status:`, `Parent:`) is in quiet text and its value in the dialog's text colour, so the value is what is read.
  The accent is not used for a label: it means a key to press or where the person is.
- **A state** in a dialog has the glyph it has in the table (`● Running`).
- **A list to choose from** marks the current line with `▸` in the accent, and a line picked by a key shows that key as a key (`▸ 1  Rebuild tpl-dev`).
- **Text that does not fit is wrapped**, never cut at the frame.
- **Work under way** has the spinner in the caution colour, in a dialog as in the STATUS column.

**The key bar fits the terminal in one of three forms.**
From 100 columns each key stands apart from its label (`F1 Info`) and the bar is spread over the row.
From 89 columns the two are together (`F1Info`), told apart by colour and weight.
Narrower, each key gets an equal share and a label is cut, as it was before this page.
What a narrow terminal gives up is whole labels, since ten of them do not fit in 80 columns.

**A table's columns are as wide as what they hold.**
A column is as wide as its widest cell (`ColumnFit`), measured over every row and not only the rows a search leaves, so the columns hold still while a person types.
Before, spare width was shared evenly whatever the cells held, so a name was cut (`bench-nativ`) beside a column of blanks.

- **With room to spare,** each column stands up to three cells apart from the next, and the rest goes to the column of free text (DESCRIPTION, PURPOSE, DONE), or stays blank at the end.
- **An instance's name is never cut.**
  It is what a person acts on, and two cut names can read the same.
- **Without room, the free text is cut first, and then whole columns are left out** (`ColumnFit.kept`).
  The Instances table leaves out RUNTIME, IP, DISK and PARENT in that order, then OWNER, PURPOSE and AGE.
  A column left out says less than three columns cut to stubs, and the instance details still show it.
- **Only then is a column cut,** each down to its floor in a set order, the status before the name.
- **A cut cell ends in `…`,** so a cut value is never taken for a whole one.
  This holds in a table narrower than every floor too: the columns give the rest up from the right, and the table's own layout never narrows them unmarked.
- **IP keeps room for an address.**
  The room counts when columns are left out, so an instance that starts never costs the table a column.
  A table with room has the same widths before the address arrives and after.
  Where the content alone fills the table, the address takes its room from the gaps between columns, and they shift.

What this gives up is columns that line up between the two panels of the Overview, which they did not do before either.

**An empty panel says what to do next.**
With no instance, the panel shows the logo, "No instances yet" and the key that makes one, in its middle.
When a search is why the panel is empty, it says "No instance matches the search" and names the key that ends the search, since the pool is not empty.

## Motion

Motion says that something is happening or has just happened, and is never decoration over idle content.
`Motion` draws all of it, over a frame that is already complete.

| What moves | When | How long |
|---|---|---|
| A spinner in the STATUS column, beside "Running:" and in a progress dialog | While an operation is under way | As long as it lasts |
| A band of the caution colour crossing the name of what is being worked on | The same | One pass every 1.8 s |
| A tint on a row, fading | The row's status changed, or the row is new | 0.9 s |

**The shimmer's band is in the caution colour.**
That colour already says "under way" in the STATUS cell beside the name, so the band adds no meaning of its own.
A band from grey to white was tried first, and at 256 colours it had four steps of the grey ramp to move through, which nobody saw.
A change of hue survives the fitting.
A row that is stopping or restarting is dimmed, and the band is not, or the terminal would dim the band with the row.

**Nothing moves in answer to a key.**
A dialog, a tab and the screen itself are drawn whole in the first frame after the key that opens them.
A dialog that faded in over 0.16 s was tried, and it read as a slower TUI rather than as an effect, which is the opposite of what isx is for.
So motion is kept for what isx is waiting on or has just seen change, and never for what the person just asked for.

**Motion never delays anything.**
The frame under an effect is the finished one, and a key pressed while a row is tinted acts on it at once.

**An idle TUI draws nothing.**
The TUI is left open for days, so motion asks for frames only while something moves, 25 a second, and stops asking when it ends.
With nothing under way the TUI wakes as often as it did before this page.

**Motion can be turned off.**
`ISX_MOTION=off` leaves every glyph still: the spinner is `◐`, and nothing is tinted or shimmers.
A terminal with sixteen colours gets the spinner alone, since nothing blends there.
In a test nothing moves, because motion starts only when a runner draws on a terminal, so a snapshot is the same on every run.

What motion costs is output: a shimmer redraws a few cells 25 times a second for as long as an operation lasts.
Only the cells that changed are written, which over SSH is a few hundred bytes a second.

The effects are written in `Motion` rather than taken from `tamboui-tfx`.
Its effects are entrances and exits, which the TUI does not have, and its fade paints every cell of an area in one colour.
`Motion` blends each cell from that cell's own colour.

## Trying a change

`scripts/tui-demo.sh` runs the real TUI on a real terminal over a made-up listing, with no Incus and no isx setup.
Everything it shows comes from a `FakeIncusDaemon` in a temporary home, so a key that stops or destroys changes only that.
A key that would leave the TUI for a shell, a build or an editor returns to it at once.

```shell
scripts/tui-demo.sh                          # a few templates and instances, one mid-restart
scripts/tui-demo.sh empty                    # no instance yet
ISX_THEME=light scripts/tui-demo.sh          # the light theme
ISX_COLORS=256 scripts/tui-demo.sh           # as a terminal without truecolor draws it
ISX_MOTION=off scripts/tui-demo.sh           # nothing moves
```

The same three variables work on `isx` itself.

Stopping an instance in the demo (`F7`) shows the tint on its row, and the `bench-native` row shows the spinner and the shimmer for as long as the demo runs.

## Adding to the TUI

A new screen, dialog or column follows from the sections above, and these are the questions to ask of it.

- Does every colour come from a role of `TuiTheme`, and does every state colour mean that state?
- Does it read with no colour: is each state a glyph from `Glyphs` or a word?
- Does it fill anything beyond the selected row, a tab, a dialog and its inputs?
- Is the loudest thing on it the thing the person came for?
- Does anything move while nothing is happening, or in answer to a key?
- Does a dialog start on its first row and end in a blank row and its keys, with quiet labels and nothing cut at its frame?
- Does a table size its columns through `ColumnFit`, with the column a person reads first cut last?
- Is it snapshotted at 80 by 24, and looked at in `scripts/tui-demo.sh` in both themes and at `ISX_COLORS=256`?
