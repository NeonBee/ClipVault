# WP-06P-A — Keep QuickPaste open after copy

Status: **implementation / device validation pending** (2026-10-28).
This is a *separate stacked PR* based on WP-06S PR #16
(`feature/wp06s-quickpaste-launcher`). It should be rebased on
`hardening/base` after PR #16 merges.

## Scope and explicit exclusion

The user selected **only** the optional **Keep open after copy** behavior.
True **Always on Top**, `SYSTEM_ALERT_WINDOW`, a floating overlay, pinning,
AccessibilityService, key interception, auto-paste, a second APK and Windows
remote-control testing are **not included** in this implementation.

QuickPaste remains an ordinary DeX application window and can be obscured
when another app takes focus. This option is not labelled "Always on Top".

## Behavior

- Default **OFF**: selecting a result with Enter or a mouse/touch click copies
  to the Android clipboard using `SensitiveClipboard.write()`, then closes
  with `finishAndRemoveTask()` as before.
- Opt-in **ON**: the same action copies, then leaves the QuickPaste task and
  search UI open for another selection. The search query and selection remain
  available while the window stays foregrounded. No automatic paste into
  another window occurs; the user switches to the target window to paste.
- Toggle is a small `FilterChip` next to the existing keyboard hint in the
  QuickPaste search pane. It can be changed while the window is open.
- The preference is stored in app-private `ClipVaultApp.settings()` using
  `PREF_QUICK_PASTE_KEEP_OPEN` and defaults to `false`. Only a Boolean is
  stored; **no query, clip text, or clipboard content is persisted**.
- Esc, Close and the normal Main Vault routing still close QuickPaste.
- `QuickPasteActivity.onStop()` from PR #16 retains DeX window geometry while
  calling `viewModel.clear()`, wiping queries/decrypted result records
  immediately when hidden. Existing Vault lock, device screen-off and
  `BiometricPrompt` rules are unchanged.
- Clipboard remains Android's ordinary sensitive clipboard; each copy
  replaces the previous value. The keep-open flag does not prevent other
  apps with clipboard privileges from observing a copied value.

## Verification

| Test | Expected | Status |
|---|---|---|
| P01 | New/previous user defaults to OFF and copy closes | Unit test; DeX pending |
| P02 | Toggle ON and consecutive Enter/click copies keep QuickPaste open | Unit/Compose coverage; DeX pending |
| P03 | Toggle OFF while open restores copy-and-close | Unit/Compose coverage; DeX pending |
| P04 | 240×260dp window still exposes a toggle, search and first result | Compose instrumentation CI pending |
| P05 | Esc/Close explicitly closes regardless of toggle | Prior behavior; DeX regression pending |
| P06 | Minimize/background wipes all decrypted search state | WP-06S instrumentation; regression pending |
| P07 | Locked vault cannot copy without strong biometric authentication | WP-06S device evidence; regression pending |
| P08 | Preference survives closing and reopening the Activity | Implementation; DeX pending |
| P09 | No overlay/Accessibility/root/INTERNET permissions added | Manifest review pending |
| P10 | Existing DeX shortcut and Main Vault icon continue to work | WP-06S device evidence; regression pending |

Automated checks:
- `QuickPasteCopyPolicyTest`: default close vs user opt-in keep-open.
- `QuickPasteCompactLayoutTest`: toggle visible/toggles within 240×260dp.
- Existing `QuickPasteActivityInstrumentedTest`: minimized task retained,
  sanitized query/results, and `FLAG_SECURE`.

## Real-device test (do not clear personal Vault data)

After CI passes, check out the stacked branch, build and update *in place*:

```powershell
git fetch origin
git switch feature/wp06p-keep-open-after-copy
git pull --ff-only origin feature/wp06p-keep-open-after-copy
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1
```

1. With **Keep open after copy** OFF, select and copy one result:
   QuickPaste closes as before.
2. Reopen via the Samsung DeX shortcut; turn **Keep open after copy** ON.
3. Copy a result using Enter, then another result via mouse click; verify
   QuickPaste remains open and clipboard changes to the last selected result.
4. Explicitly press Esc. Reopen and verify the keep-open option is still ON.
5. Verify minimizing hides decrypted queries/results and returning while
   locked requires strong biometrics; no sensitive data must appear early.
6. Confirm the resized window remains usable and no extra special permission
   is prompted.

**Do not** run `connectedDebugAndroidTest`, uninstall or `pm clear`
against the personal-Vault device: instrumentation uses a disposable CI
emulator; uninstall/clear can destroy encrypted Vault data/Keystore state.
