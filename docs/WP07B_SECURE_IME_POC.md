# WP-07B — Secure Paste secondary IME PoC

Status: **implementation PR / NOT device-certified / NOT a release guarantee**. WP-07A Secure Copy has already merged in PR #19 (CI #116 green; Galaxy S23 Ultra / One UI 8 targeted observations PASS). This page covers the separate Secure Paste egress experiment.

## Scope and trust boundary

- **Daily IME remains Samsung Keyboard.** This is an opt-in, retrieval-only Android `InputMethodService` activated by the user from the platform keyboard picker.
- Unlocking uses a dedicated, non-exported `AppCompatActivity` with the existing `BiometricVaultUnlock` (`BIOMETRIC_STRONG + CryptoObject`) and SQLCipher key envelope. The Activity does not receive/retain the selected clip, editor token or plaintext from the IME.
- The IME queries the existing `VaultRepository` on the shared serialized IO executor, displays up to 30 recent/pinned clips, and supports search using a **hardware keyboard**. There is deliberately **no custom QWERTY**. On touchscreen-only devices users can browse recent/pinned; on-screen search editing is deferred.
- Selection runs `InputConnection.commitText(content, 1)` directly into the currently focused normal text editor. **No `ClipboardManager`, `SensitiveClipboard`, capture, pending-store, accessibility, Shizuku, overlay, shell or network fallback.** The existing QuickPaste normal copy path is unchanged.
- `TYPE_NULL`, password variations, number, phone, date/time editors, invalid/missing input connections, mismatched editor package, stale lock epoch, keyguard lock and concurrent taps **fail closed**.
- On reported commit success the IME discards the in-memory decrypted list and tries `switchToPreviousInputMethod()` (API 28+). If that fails, use **Keyboards** to manually switch back.
- IME query/result state is not persisted in `SavedState`, preferences, logs, analytics, notifications or recent tasks. The IME window uses `FLAG_SECURE`; the unlock Activity is also secure and excluded from recents.
- The **target application receives plaintext**, and a positive `commitText` return is not proof that the target persisted the text. A third-party target may still store, log, transmit or echo input.

## Android setup and happy path (S23 Ultra / One UI 8 / DeX)

1. Install debug from this branch only with **the same debug signing key** using `scripts/deploy-debug.ps1` (`adb install -r`). **Never uninstall, clear data or run connected instrumentation on a phone with a populated vault.**
2. Open Android/Samsung keyboard settings → keyboard list / manage keyboards; explicitly enable **ClipVault Secure Paste**. The exact One UI path/wording may vary. Do **not** make it the permanent default IME.
3. In a disposable notes/editor test field (no passwords or live confidential data), focus the field. Open the system keyboard switcher and choose **ClipVault Secure Paste**.
4. If the Vault is locked, select **Unlock**, authenticate via strong biometrics, return to the original app/editor, and refocus it if Android lost focus. Tap **Refresh** if needed; a new editor connection must be established. No secret is passed between the authentication Activity and IME.
5. Browse **Recent** or **Pinned**, or type a query with a physical keyboard. Select an item. Verify that it appears **exactly once** in the focused editor.
6. Verify automatic previous-keyboard return. If unsupported, manually select Samsung Keyboard via **Keyboards**.

## Verification gates (distinct from implementation)

- [ ] CI `verify` / `instrumentation` green on this PR. Unit tests cover ordinary editors, unsupported/password fields, epoch/lock/package mismatch, no connection, exactly-once `commitText`, rejection and exception without fallback. Emulator IME registration/interaction and lifecycle still require instrumentation or manual emulator checks.
- [ ] One UI **phone and DeX**, and **hardware keyboard**: IME enable/switch and keyboard return/picker fallback.
- [ ] Strong biometric successful unlock, cancellation, key invalidation, unprovisioned account; no unauthorized row on lock.
- [ ] Normal text, Korean, Unicode emoji, newline, URL, code, long text, multi-tap, editor switch, app backgrounding and screen-off while loading.
- [ ] Unsupported/read-only/`TYPE_NULL`/password editor rejects with no clipboard writes; target app may override or reject `commitText`.
- [ ] Preserve existing **system primary clipboard** value and **Samsung Keyboard clipboard history** after Secure Paste.
- [ ] In `READY_EVENT` Shizuku mode, run a benign **ordinary Copy positive control** (previously observed listener +3 and distinct capture +1 on the target device), then an isolated Secure Paste and verify `debug_bridge_event_count` Δ0, `debug_capture_accept_count` Δ0. In `READY_POLL` mode only distinct accepted capture is an applicable counter. Repeat with both successful and rejected inserts. These counters are observations, not universal proof of all OS transitions.
- [ ] Confirm no plaintext in logs, notification, app recents or UI after IME hide/lock/return; no paste to the wrong app or twice.
- [ ] Do not run `connectedDebugAndroidTest`, `adb uninstall` or `pm clear` on the personal S23 Ultra. Use CI emulator and manual device probes, recording results separately.

## Known limitations of this first vertical slice

- On-screen QWERTY search is deliberately absent; a proper mobile search entry scheme and visual polish are follow-up work.
- An Android Activity is used to host biometric authentication. Some OEMs may not restore the prior editor focus or IME window automatically; reselect the field and refresh. This is a **device verification gate**.
- Android `InputConnection` success is a request/acceptance signal only. The current implementation does not support rich text, read-only editors, password fields, numeric fields or automated clipboard comparisons.
- The preceding WP-07A entry path still uses `ACTION_PROCESS_TEXT` or the explicit secure `ACTION_SEND` receiver. The IME is **egress-only**, not a general keyboard read/capture service.

## User device follow-up — HTML editor matrix and hyperlink selection (2026-10-31)

Manual observation reported by the user, Galaxy S23 Ultra / One UI 8; not automatically verified by CI.

- Source page `clipvault_wp07ab_selectable_source.html`: **A** plain text + line breaks **PASS**; **B** plain-text URL **PASS**; **C** HTML `<a>` long-press *share link* → `No valid text` and no Secure Copy **(known ingress compatibility limitation, source Intent not captured)**; **C** selecting/dragg​ing the *visible URL text* and then using Secure Copy → **PASS**; **D** rich text lost bold/italic formatting, **expected text/plain behavior**. The comparison supports a difference in sharing action/payload rather than a generic URL-text failure. Do not claim the incoming share was definitely URI or a specific MIME type without measuring its metadata.
- Destination page `clipvault_wp07b_field_test.html`: user reports **all HTML editor examples behaved as expected**. The page contains normal text and multiline areas (expected insert), password and number fields (expected PoC reject), read-only and disabled fields (expected no modification; keyboard may be unavailable), and a browser `contenteditable` area (compatibility exploration). **Overall manual expectation check PASS**; individual insert/skip results and browser version were not separately itemized, so do not upgrade all cases to individually instrumented security proofs.
- Earlier hardware-keyboard/DeX visibility was BLOCKED, but the later user report below observes a visible ClipVault on-screen IME panel in DeX. This **partially supersedes** the visibility blocker; do not mark the entire DeX/hardware-input matrix PASS because Korean physical-keyboard typing remains problematic.
- Remaining cross-cutting gates: lock epoch, lifecycle/focus switches, direct clipboard non-interference with Shizuku positive control, editor switching and exactly-once insertion. No personal-device instrumentation/uninstall/clear-data.

## User device follow-up — security gates PASS, DeX physical-keyboard Korean input issue (2026-10-31)

Reported manually by the user on the Galaxy S23 Ultra / One UI 8. No raw keyboard events, editor package, keycodes, IME state dumps or log contents were collected.

| Gate | Observed result | Assessment |
| --- | --- | --- |
| Secure Paste Shizuku clipboard-event non-interference | User states already checked, without unexpected events | **PASS (user-observed tested scope)**; raw before/after counters for this run not provided |
| Lock and strong-biometric security boundary | User states already checked, no problem | **PASS (user-observed tested scope)**; not every lifecycle race independently enumerated |
| Destination editor switching / wrong-target insertion | User reports no wrong-target paste | **PASS (user-observed tested scope)** |
| DeX on-screen ClipVault IME visible while hardware keyboard connected | User reports the on-screen keyboard could be invoked in this configuration | **Visible in this tested state**; earlier 'IME unavailable with keyboard' report cannot be generalized across conditions |
| Hardware keyboard Hangul input while on-screen IME is active in DeX | User reports forced English-only input | **OPEN: DeX/IME Korean typing limitation**, separate from Secure Paste direct-insert and clipboard security gates |

**Code review and likely scope:** `SecurePasteImeService` registers as the active `InputMethodService`; its own search `EditText` uses `setShowSoftInputOnFocus(false)`, and there is no Hangul key-composition implementation. Therefore Korean hardware-key input cannot be assumed to work like Samsung Keyboard while ClipVault is the active IME. The report does not specify whether English-only characters appeared in the ClipVault search field or the target app's editor, so do not prematurely attribute the exact root cause to Samsung DeX or to a specific keyboard setting.

**Safe reproduction/isolation:** With an ordinary disposable text editor in DeX, compare hardware keyboard Hangul output **(1)** while Samsung Keyboard is the selected IME, **(2)** while ClipVault Secure Paste IME is selected and visible, and **(3)** after successful Secure Paste and return to Samsung Keyboard. Note whether the active input field is ClipVault Search or the target editor, whether the 한/영 key and configured language-switch shortcuts work, and whether the IME picker/return is available. Do not log actual typed secrets.

**Product decision:** WP-07B is a **retrieval-only** secondary IME, not an alternative Hangul-capable typing engine. For ordinary Korean hardware typing, switch back to Samsung Keyboard via the IME picker (or the existing successful-paste `switchToPreviousInputMethod()` path). Treat Korean search while ClipVault is active as a UX compatibility follow-up, **not as permission to record keys or implement a general keyboard**, and do not weaken system clipboard non-interference for a fallback.

This DeX behavior is an **open usability limitation** even though the above three security gates have been observed to pass. Do not claim complete release readiness until remaining CI and device/keyboard matrix gates are addressed.

Official API references: [Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method), [InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService), [InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection).
