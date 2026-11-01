# WP-07B follow-up — hardware keyboard focus routed to browser address bar

**Status:** OPEN / deferred after the first Secure Paste IME UI improvement.  
**Source:** Galaxy S23 Ultra / One UI 8 user field report, 2026-10-31.  
**Related work:** [PR #20](https://github.com/NeonBee/ClipVault/pull/20), `SecurePasteImeService.kt`.  
**GitHub issue:** Not created; GitHub Issues is disabled for `NeonBee/ClipVault` (GitHub API returned HTTP 410 on attempted issue creation). Preserve this file as a distinct backlog item until an issue tracker is available.

## User-observed behavior

With a hardware keyboard connected while the ClipVault Secure Paste panel was shown, typed text was received by the browser's **address input field**, rather than by the ClipVault keyboard UI's intended search field. Browser identity, the exact focus/tap sequence, the current editor package, and key event traces were not captured. The exact cause is **unverified**; do not attribute it solely to One UI or ClipVault.

This is different from the separately observed DeX hardware-keyboard **English-only / Hangul composition** limitation. It is a hardware input/focus-routing issue, not a failure of tapping a stored clip to secure-paste.

## Expected behavior / security invariant

- When the user explicitly focuses **Search** in the ClipVault IME, physical keyboard input edits that search field.
- When ClipVault search is not focused, the IME must not capture unrelated hardware keystrokes. The target editor remains under platform control.
- Tapping a Vault entry inserts **once** via `InputConnection.commitText` only; no system clipboard, Shizuku, accessibility, or raw-key interception fallback.
- A target app must not unexpectedly receive vault content through background UI focus changes.

## Targeted test plan (no secrets)

1. With a disposable web text field visible in DeX and Samsung Keyboard selected, test hardware keyboard input and note actual focus.
2. Switch to ClipVault Secure Paste. Tap its **Search** field explicitly, enter harmless ASCII text, and note whether Search or the browser **address bar** receives it.
3. Repeat with Search **not** focused; compare another editor (e.g. notes) and phone mode with the same hardware keyboard.
4. Repeat after returning from the biometric unlock Activity and after Recent/Pinned tabs are switched.
5. Note the active app/editor package and focus state as **metadata only**. Never log key values, copied text, URLs, or clipboard contents.
6. Separately test Hangul composition; do not mix the result with focus-routing diagnosis.

## Acceptance

- [ ] Reproduction is isolated to a specific focus path and platform mode.
- [ ] Explicit Search focus reliably receives typed query on the connected hardware keyboard.
- [ ] Unfocused Search does not interfere with ordinary target-editor typing.
- [ ] No wrong-target secure paste, duplicate commit, or clipboard fallback.
- [ ] Real-device verification and CI regression coverage recorded before closing.

## Scheduling

Defer implementation until the initial UI presentation pass in PR #20 has finished. This issue does not invalidate the user's successful Shizuku, biometric, wrong-editor, standard paste and auto-lock tests.
