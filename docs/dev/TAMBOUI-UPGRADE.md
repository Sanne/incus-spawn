# TamboUI upgrade reviews

The sections below record each upgrade's review, newest last.

## 0.6.0

The shared `tamboui.version` property upgraded the CLI's TUI, toolkit, and Panama backend dependencies from 0.5.0 to 0.6.0.
The upstream release was published on October 12, 2026.
Source: [TamboUI 0.6.0 release notes](https://github.com/tamboui/tamboui/releases/tag/v0.6.0).

## Compatibility and benefits

`Tui.handleEvent` treats `TickEvent` as a refresh pulse and does not use its removed elapsed-time methods or constructor.
No tick API migration is needed.
The frame pipeline optimizations apply without application changes; upstream's reported speedup is not an isx benchmark.
Terminal-error recovery and Panama locale parsing fixes apply to our existing runner and backend.
TamboUI now percent-encodes C0, DEL and C1 controls in OSC 8 link parameters, and it stopped escaping `;` and `\` there.
The second change is the one AI Help sees: 0.5.0 wrote an answer URL containing `;` or `\` as `\;` or `\\`, so the link opened the wrong address.
The control-character encoding is a backstop, since our own `TerminalLink` strips from a link what `OutputFormat.isControl` names (C0, DEL, C1, line separators and bidi controls) before building the styled span.
It turns the same characters into spaces in the answer text around a link, line feeds and tabs apart (#1413).

## Improvements to consider

| Priority | Opportunity | Application code and reason |
| --- | --- | --- |
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
Our own wrapping now measures terminal cells with `CharWidth` too (#1412): `HelpChatModalTest` checks that a wide answer and link label keep every character, and `SegmentWrapTest` that no break splits an emoji.

## 0.7.0

The runner's config sets tamboui's `KeyboardProtocol.LEGACY`, and `LegacyKeyboardBackend` pushes nothing whatever a config says: 0.7.0 pushes kitty flags 5 by default, which would sit over the flags 0 that `LegacyKeyboardBackend` pushes.
`TuiRunner` clears the screen before its first frame, so `Tui.openRunner` is gone.
Ctrl+L stays, since tamboui clears again only on a resize.
The `KeyboardProtocol` decoder, isx's own class, stays too.
Tamboui now reads Esc and Ctrl+C in the CSI u and modifyOtherKeys encodings, but our decoder stays.
It still delivers a key release as an event, where a legacy terminal sends none, and `KeyEvent.isKey` matches it (tamboui#640).
A shifted key keeps the Shift modifier, so Alt+`!` arrives as Alt+Shift with `!` as its shifted form, where the legacy encoding has no Shift on it, and `DigitKeys` would read it as Shift+F1.
Shift with a letter that reports no shifted form arrives as `a` with Shift, not `A`.
`KeyEvent.isChar` does match the shifted form, so a plain character check is unaffected.
Probed by running `KeyboardProtocolTest` against tamboui's parser alone: five of ten tests failed, on releases, those two Shift cases and two timing checks.
Still open from 0.6.0: Paragraph hanging indentation for `ModalRenderer.wrapText`, and `RenderClock` for the spinner.
