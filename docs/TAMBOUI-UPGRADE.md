# TamboUI 0.6.0 review

The shared `tamboui.version` property upgrades the CLI's TUI, toolkit, and Panama backend dependencies from 0.5.0 to 0.6.0.
The upstream release was published on October 12, 2026.
Source: [TamboUI 0.6.0 release notes](https://github.com/tamboui/tamboui/releases/tag/v0.6.0).

## Compatibility and benefits

`Tui.handleEvent` treats `TickEvent` as a refresh pulse and does not use its removed elapsed-time methods or constructor.
No tick API migration is needed.
The frame pipeline optimizations apply without application changes; upstream's reported speedup is not an isx benchmark.
Terminal-error recovery and Panama locale parsing fixes apply to our existing runner and backend.
TamboUI now percent-encodes C0, DEL and C1 controls in OSC 8 link parameters, and it stopped escaping `;` and `\` there.
The second change is the one AI Help sees: 0.5.0 wrote an answer URL containing `;` or `\` as `\;` or `\\`, so the link opened the wrong address.
The control-character encoding is a backstop, since our own `TerminalLink` strips C0 and DEL controls from a link before building the styled span.

## Improvements to consider

| Priority | Opportunity | Application code and reason |
| --- | --- | --- |
| High | Use terminal column widths throughout custom wrapping (#1412) | `ModalRenderer.wrapWords` and `TerminalLink.displayLength` / `wrapSegments` count UTF-16 units with `String.length()` and split by string indices; CJK widths and surrogate pairs can still cause clipping or splitting despite upstream widget fixes. |
| Medium | Use Paragraph's new hanging indentation for plain wrapped content | `ModalRenderer.wrapText` maintains its own indentation and wrapping; evaluate replacing it with widget wrapping where the measured height and scrolling remain consistent. |
| Medium | Use monotonic animation timing | `HelpChatModal.renderLoading` selects spinner frames with `System.currentTimeMillis()`; an accumulated `RenderClock.tick()` duration would avoid jumps when the system clock changes. |
| Medium | Add deterministic terminal input scenarios | Upstream tape playback now supports function keys, modifiers, and screen-text waits; evaluate it for TUI smoke tests involving F-keys, Shift+Tab, Ctrl+S, modal navigation, and paste. |
| Low | Consider Markdown rendering for AI answers | `HelpChatModal.answerLines` currently renders plain text with detected links; the upstream nested-list code-block fix makes a Markdown widget more useful if we choose richer formatting. |

These are follow-up opportunities, separate from the dependency upgrade.
The current website recorder in `site/demo/record.py` also captures shells and guest applications, so a TUI-specific tape facility would complement it.

## Regression coverage

The existing TUI tests compare rendered screens against committed golden files and exercise modal navigation and actions.
Golden files are not regenerated to accept changes.
`HelpChatModalTest` checks at 40 and 80 columns that a wrapped answer link keeps a URL containing `;` and `\` intact and lets no ESC, BEL or C1 control reach the terminal.
That test fails on 0.5.0.
It also checks that a wrapped question of CJK, ZWJ-sequence and flag emoji renders every character and that Backspace deletes a whole emoji.
That one passes on 0.5.0 as well: none of the 0.6.0 Unicode fixes changes what the AI Help input shows, so it guards the upgrade rather than proving it.
