# WP-06P — Ditto-style persistent QuickPaste and Always on Top

Status: **design/scoping only, not implemented** (2026-10-28).
Base dependency: WP-06S PR #16, still Draft / unmerged.
Goal: support repetitive copying and, if securely possible, a window remaining
above other DeX windows. These are **different features**, not one switch.

## Device evidence and prior UX

Galaxy S23 Ultra / One UI 8 / Samsung DeX:

- `CATEGORY_INFO` launcher alias makes Samsung's hardware keyboard hotkey open
  QuickPaste without a second APK. Existing Main icon still opens Main.
- A resized QuickPaste window survives minimize → hotkey, **and the user has
  confirmed the size is restored after fully closing and relaunching**.
- No authentication bypass was observed in these tests.
- Current copy action closes intentionally:
  `QuickPasteActivity.copySelected()` calls
  `SensitiveClipboard.write(this, item.content)` and then `close()`, which
  invokes `finishAndRemoveTask()`.
- The user's Windows-layout Bluetooth keyboard had Samsung system remapping
  `Ctrl → Cmd`, `Cmd → Ctrl`, allowing app hotkeys that did not work with
  identity bindings. The physical **right Ctrl acts like Win/Meta**; the
  physical Win key may now be acting as Ctrl, subject to actual keyboard scan
  mapping. The user explicitly **skipped remote Windows interoperability**
  as a mandatory check; this is a scope decision, not a compatibility PASS.

## Two independent feature contracts

### WP-06P-A — Copy and keep panel open (no new permissions)

Opt-in, **OFF by default**: `Keep QuickPaste open after copy`.

- When off: retain exact existing Enter/tap → sensitive clipboard → close.
- When on: Enter/tap copies the selected clip (with the existing sensitive
  clipboard hint) and shows a confirmation, but leaves the QuickPaste Activity
  open for another search/selection. Esc and Close explicitly exit.
- Changing focus to another app can put an ordinary Activity behind it. **This
  is not Always on Top** and must not be labelled/presented as pinned.
- Prioritize keyboard UX: no automatic paste, the user controls what app gets
  `Ctrl+V`. Repeated copy overwrites the system clipboard as usual.
- If the app stops/minimizes, drop decrypted result list/query immediately.
  If the Vault locks, enforce the existing BIOMETRIC_STRONG/CryptoObject gate,
  whether or not the panel is configured to remain after copy.
- Validate with Compose/instrumentation tests and DeX mouse/keyboard tests.

### WP-06P-B — Actual 'always above other app windows'

An ordinary Android `Activity` does not expose a supported always-on-top
flag analogous to Windows' topmost window feature. Samsung One UI 8 DeX may
also no longer present classic DeX's native pin-on-top option (community
reports; **check the actual target device and OS build first**).

The standard Android avenue is a `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`
window, which uses a distinct, user-granted **SYSTEM_ALERT_WINDOW** / 'Appear
on top' special permission. Overlay windows are drawn above regular
application windows but below important system windows, including IME/status
bar. **Do not silently add this permission in WP-06S or WP-06P-A**.

Before any implementation:
- Check native DeX window chrome and Good Lock/MultiStar settings on the
  S23 Ultra for a trustworthy zero-permission option. 'Pin options menu'
  is **not** 'keep the whole window always above other apps'.
- Verify a window overlay can be rendered above other apps on the correct
  DeX external display, with secure handling of lockscreen/multiple displays,
  focus, input methods and user-initiated disable/revocation.
- Resolve the focus contradiction: typing into QuickPaste's search requires
  keyboard focus in the panel, while copying into another app requires focus
  in that app. An overlay can be topmost yet interfere with text input unless
  carefully designed.
- Threat-model overlay tapjacking, obscured touches, screen-capture protection,
  sensitive content visibility, process restart, vault lock, auto-lock, and
  device screen-off. Keep explicit close, no raw key collection, no Accessibility,
  no root, no network permission, no generalized Shizuku input hook.
- Require a security review and explicit opt-in *before* shipping such a
  privileged UI. Design a downgrade/fallback if permission is revoked.

## Acceptance / test matrix

| ID | Criterion | State |
|---|---|---|
| P01 | Default single copy still closes QuickPaste | BASELINE PASS |
| P02 | Opt-in multiple successive copies without reopening | NOT IMPLEMENTED |
| P03 | Locked/background QuickPaste never retains decrypted list or bypasses biometrics | DESIGN GATE |
| P04 | True overlay remains visually above other windows after target app takes focus | POC PENDING |
| P05 | QuickPaste search focus and target app Ctrl+V operate deliberately without key interception | POC PENDING |
| P06 | Additional overlay permission only with informed user opt-in; revocation/rollback works | POC PENDING |
| P07 | One UI 8 DeX keyboard launcher alias/Main Vault icon unaffected | REGRESSION GATE |
| P08 | Resized window size persists on minimize and full close | DEVICE PASS in WP-06S |
| P09 | User opted to SKIP Windows remote-control modifier-compatibility gate | SKIPPED, NOT PASS |

## Recommended implementation order

1. Finalize WP-06S CI/security review separately; retain current copy-close
   default and do not fold unrelated privileges into PR #16.
2. Build WP-06P-A as a new, independently reviewable PR with an opt-in
   keep-after-copy setting. This addresses repeated Ditto-style clipboard use
   but **not true always-on-top**.
3. Investigate WP-06P-B with a permission/security/DeX-display PoC and only
   implement if results justify the extra special permission.

Sources:
- Android SYSTEM_ALERT_WINDOW: https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW
- Android WindowManager TYPE_APPLICATION_OVERLAY: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- Samsung official DeX windowing: https://www.samsung.com/us/support/answer/ANS10002880/
- Samsung DeX pin-top removal reports (not official API contract): https://r1.community.samsung.com/t5/samsung-dex/no-more-pinning-apps-on-top/m-p/35964441/highlight/true
- WP-06S current research and implementation: `docs/WP06S_GLOBAL_SHORTCUT_POC.md`
