# WP-06P-A — Keep QuickPaste open after copy

**Final status (2026-10-28): merged in [PR #17](https://github.com/NeonBee/ClipVault/pull/17) (merge `81546b4`) to `hardening/base`; target Samsung DeX device behavior PASS and final [CI #79](https://github.com/NeonBee/ClipVault/actions/runs/40662764365) verify + instrumentation PASS.** See [WP-06 completion/evidence](WP06_COMPLETION.md).

> This document includes **historical PoC and failed-CI investigation notes**. Any `pending`, `Draft`, or `before merge` wording in those later chronological sections describes the state *then*, not the current status. The feature remains opt-in; true Always on Top is **out of scope**.

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
- On normal-sized windows, the option is a labeled `FilterChip` next to the keyboard
  hint (the layout the user tested). In a compact window (content width ≤480dp
  **or** height ≤420dp) the option moves to an accessible trailing search-field
  toggle. The normal leading search icon is removed in compact mode so the
  input field and result list do not lose space. The icon has selected tint.
  This is layout detection, not a system keyboard mapping change.
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
| P01 | New/previous user defaults to OFF and copy closes | **PASS** — unit/CI #79 and user device verification |
| P02 | Toggle ON and consecutive Enter/click copies keep QuickPaste open | **PASS** — user device verification; CI #79 |
| P03 | Toggle OFF while open restores copy-and-close | **PASS** — user device verification; CI #79 |
| P04 | 240×260dp window still exposes a toggle, search and first result | **PASS** — compact toggle device verification; CI #79 |
| P05 | Esc/Close explicitly closes regardless of toggle | Prior behavior retained; CI #79; no separately reported exhaustive device edge-case matrix |
| P06 | Minimize/background wipes all decrypted search state | **PASS** — WP-06S instrumentation and target-device no-auth-bypass observation; not an independent security audit |
| P07 | Locked vault cannot copy without strong biometric authentication | **PASS (observed scope)** — existing biometric gate and no device bypass observed |
| P08 | Preference survives closing and reopening the Activity | Implemented as app-private Boolean preference; exact reboot/power-loss matrix not separately documented |
| P09 | No overlay/Accessibility/root/INTERNET permissions added | **PASS** — PR diff/manifest/CI permission checks |
| P10 | Existing DeX shortcut and Main Vault icon continue to work | **PASS** — target-device routing verification |

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

## Device report and CI regression correction (2026-10-28)

**User's device result:** On the Samsung DeX real device, **all user-requested
behaviors worked as expected**: opt-in copying without closing, normal default
copy-and-close, and the existing QuickPaste flow. This is an observed UX PASS
on the tested configuration, not a claim of universal compatibility.

**CI review:** run #66 `verify` passed but `instrumentation` failed only
`QuickPasteCompactLayoutTest.keepOpenSettingTogglesWithinTheMinimumWindow`:
the footer `FilterChip` was off-screen in the synthetic 240×260dp panel.
This exposed a real small-viewport affordance concern even though the user's
larger window worked normally. Fixed in commit `480a25f`: on short windows
(height ≤320dp), the switch is an accessible `IconToggleButton` integrated
into the search field; on normal windows, preserve the user-tested footer chip.
This is **not** an always-on-top function and adds no permission. CI #67 must
confirm the minimum-size regression test before merging.

PR #17 remains Draft and stacked on PR #16. Do not merge until CI passes.

## Real device report: bottom toggle persists at minimum size (2026-10-28)

The user reported the keep-open toggle **still appearing in the bottom
footer instead of the right side of the search box** when the DeX window
was minimized to its smallest size. The current installed APK revision was
not independently identified, so both an older build and the height-only
layout branch are viable explanations. The prior code switched to the
search trailing slot only if `BoxWithConstraints.maxHeight <= 320.dp`, which
may not hold in Samsung's actual Activity content coordinates.

**Fix:** detect either small width (≤480dp) or small height (≤420dp), replace
rather than add to the search icon space, and keep only a compact key hint
below the list. Add tests for 240×260dp, narrow-but-tall 420×620dp, and
regular 560×620dp windows. Distinct Compose semantics tags confirm the icon
exists and the footer chip does *not* exist in compact layouts; normal mode
keeps the user-tested footer chip.

**CI history:** run #68 had `verify` PASS and one instrumentation FAIL
(`QuickPasteCompactLayoutTest.searchListHintAndKeysFitTheMinimumWindow`),
where item 4 was not visible at 240×260dp after adding an extra search-field
icon. Compact mode now **replaces** the leading search icon with the toggle,
so the text field retains its original icon-space footprint.

Do not mark this layout correction PASS until both the new CI and target
Samsung DeX device re-test. Update debug APK *in place*, without clearing
Vault data, and verify the installed version is built from this PR's latest
head commit.

## Compact control icon preference and device verification (2026-10-28)

The user confirmed on the real Samsung DeX device that the **compact-layout
toggle now appears at the right of the search box**, and the keep-open
functionality continues to work as expected (**device PASS** for layout and
behavior). The user prefers a **lock icon** over the previous copy icon.

UI-only follow-up: the compact toggle displays an **open lock when OFF** and
a **closed lock when ON**. On/off tint and checked semantics follow the
existing `keepOpenAfterCopy` Boolean. The accessible label remains
**Keep open after copy**, NOT **Lock Vault**; this option only controls
whether copying closes QuickPaste. Vault authentication, actual locking
and background content clearing remain unchanged. Normal windows retain
the labeled footer chip. No new permission or Always on Top behavior.

The icon change was committed *after* the user's functional device test.
Verify the new icons on an updated APK before marking this visual mapping
PASS. PR #17 remains Draft and stacked on PR #16 pending CI/final review.
