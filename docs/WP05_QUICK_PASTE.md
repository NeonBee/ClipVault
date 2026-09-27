# WP-05 QuickPasteActivity

Terminology: `PR #N` is a GitHub Pull Request number; `WP-NN` is a design Work Package (see `DEX_FORK_AUDIT_DESIGN.md` §21).

## Design mapping (§16 DeX Quick Paste, §18 Copy-back, §20 invariants)

| Design item | Implementation |
| --- | --- |
| compact window | Manifest `<layout>` 560×620dp (min 360×400dp) for freeform/DeX launches; content capped at 640dp wide. Phones show it full screen. Fine-tuning belongs to WP-06. |
| `FLAG_SECURE` | Set before any content; `setRecentsScreenshotEnabled(false)` on API 33+. |
| recents preview 없음 | `excludeFromRecents`, `autoRemoveFromRecents`, own `taskAffinity`, `finishAndRemoveTask()` on close. |
| search autofocus | Search field requests focus and the soft keyboard when the vault is ready. |
| recent history | Empty query lists the newest 50 clips (pinned first, same order as the library). |
| FTS search | `VaultRepository.query` with the existing prefix FTS5 query, 120 ms debounce, 50 results. |
| arrow navigation | ↑/↓, PgUp/PgDn (5 rows), Ctrl+Home/End. Home/End without Ctrl stay in the text field. |
| Enter select | Enter / numpad Enter / IME Go copies the selected row. Mouse: one click copies that row. On the lock pane Enter starts the unlock unless a focused button takes it (Enter on a focused Close closes). |
| Esc close | Esc and Back close the window from every pane, whether the search field, a button or the window root has focus (handled in the parent-first preview phase). |
| clipboard restore 후 `finish()` | `SensitiveClipboard.write` with `EXTRA_IS_SENSITIVE`, then `finishAndRemoveTask()`. |
| search query persistence 없음 | Query and results live only in `QuickPasteViewModel`; no saved state, no preferences. Cleared on close, lock and when the window is hidden (hidden = closed). |
| locked → biometric | Same `BiometricVaultUnlock` (BiometricPrompt + CryptoObject) as MainActivity; prompt opens automatically once. Cancel closes the window. No enrollment from QuickPaste. |
| plaintext quick cache 금지 | None exists. Without an open vault the window renders no vault content. |
| auto paste 없음 (v1) | Copy only. User returns to the original app and presses Ctrl+V. |

Entry point: a dynamic launcher shortcut "Quick paste" (long-press the app icon, or the DeX taskbar icon). `QuickPasteActivity` is not exported. Notification action, Quick Settings entry and taskbar pinning are WP-06.

Related fix: auto-lock and screen-off lock moved from `MainActivity` to `ClipVaultApp` (`VaultAutoLock`). Before this, opening QuickPaste over the main window armed MainActivity's timer and could lock the vault underneath QuickPaste, and a vault unlocked from QuickPaste alone would not auto-lock.

Unlock race (review of PR #11): the database opens on the IO executor after the biometric prompt, so the window can stop while the vault is still locked. `openVault` therefore calls `VaultAutoLock.onVaultUnlocked()`, which arms the timer when no window is visible. Every `lockVault()` bumps a lock epoch; `BiometricVaultUnlock` captures it before the prompt and `openVault(key, epoch)` refuses (wiping the key) if a screen-off, keyguard or explicit lock happened in between, both before and after SQLCipher opens. The success callback re-checks the epoch and `isUnlocked` inside the main-thread callback (`UnlockOutcomeGate`), so a lock that lands after the open but before the callback runs (e.g. a 0 ms auto-lock during maintenance) reports "interrupted" instead of flipping the UI back to unlocked.

## Known behaviour

- A restored clip is captured again by the running capture service, like a copy from the main window: the existing row's count and `last_captured_at` update, so it moves to the top of recents. Self-copy suppression is a separate test item in design §22.
- The soft keyboard may learn typed queries; ClipVault does not persist them. Compose offers no `IME_FLAG_NO_PERSONALIZED_LEARNING` switch without dropping to a View-based field.

## Device check (Samsung DeX)

Fill in on the WP-04 device (SM-S918N) after installing the debug build with `adb install -r` (never uninstall).

| Check | Expected | Result |
| --- | --- | --- |
| Long-press ClipVault icon (phone and DeX taskbar) | "Quick paste" shortcut is listed | PENDING |
| Open with vault locked | Biometric prompt appears; cancel closes the window | PENDING |
| Unlock from QuickPaste | Recent clips listed, search field focused | PENDING |
| Type a word, ↑/↓, Enter | Matching list; Enter closes the window; Ctrl+V in another DeX app pastes the clip | PENDING |
| Esc | Window closes | PENDING |
| Screenshot / recents | Screenshot blocked; no Quick paste card in recents | PENDING |
| Window size in DeX | Opens as a compact freeform window, resizable without losing the query | PENDING |
| Minimise QuickPaste, wait > auto-lock timeout, open main app | Vault locked | PENDING |
| Unlock from QuickPaste, then open main app | Main app shows the vault, no second prompt | PENDING |
