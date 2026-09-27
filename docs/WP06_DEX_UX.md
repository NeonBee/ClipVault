# WP-06 DeX UX

Terminology: `PR #N` is a GitHub Pull Request number; `WP-NN` is a design Work Package (see `DEX_FORK_AUDIT_DESIGN.md` §21).

Scope (design §21 WP-06): freeform window sizing, focus, taskbar / notification / Quick Settings invocation, DeX density. Carried over from WP-05 device validation (`WP05_QUICK_PASTE.md`, Known behaviour):

1. QuickPaste freeform minimum/default size (360×400dp / 560×620dp) is larger than wanted.
2. After unlocking from QuickPaste and leaving it open, switching to MainActivity after roughly five seconds asks for biometrics again, although the configured auto-lock is 30 s.

## Step 1 — lock reason diagnostics (this PR)

Item 2 is fail-closed, so the fix must not guess. Every path that can drop the unlocked vault now records a reason:

| Reason | Trigger |
| --- | --- |
| `AUTO_LOCK_TIMEOUT` | Process-wide timer; armed only when no ClipVault activity is started. |
| `SCREEN_OFF` | `ACTION_SCREEN_OFF`. |
| `DEVICE_LOCKED_MAIN` | `MainActivity.onResume` saw `KeyguardManager.isDeviceLocked()`. |
| `DEVICE_LOCKED_QUICK_PASTE` | `QuickPasteActivity.onStart` saw `isDeviceLocked()`. |
| `NOTIFICATION` | "Lock now" notification action. |
| `USER` | Lock button in the app. |
| `PROCESS_RESTART` | The previous process ended while the vault was open (kill, crash, reboot). No `lockVault()` runs in that case, so an open marker written at unlock is detected at the next process start. |
| `OTHER` | Any other caller (tests). |

Each event stores only: reason, time, time since unlock in this process, number of started ClipVault activities, screen interactive, `isKeyguardLocked`, `isDeviceLocked`, and Samsung DeX desktop mode (`Configuration.semDesktopModeEnabled`, reflection, diagnostics only). No clipboard content, key material or search text. The last 8 events are kept in app preferences (`lock_log`), newest first. Only transitions from unlocked to locked are recorded, so repeated keyguard checks while already locked do not flood the log.

Where to read it:

- Settings > Diagnostics > "Lock MM-dd HH:mm:ss" rows (unlock first to reach Settings; the log survives locking).
- `scripts/device-validation-record.ps1` prints a "Lock log" table (debug build, `run-as`, read only).

### Reproduction on the WP-04 device (SM-S918N, DeX)

Install the build in place (`adb install -r`, never uninstall). Then:

1. In DeX, open Quick paste from the taskbar shortcut and unlock.
2. Leave Quick paste open; wait about 10 s (longer than the ~5 s threshold, shorter than the 30 s auto-lock).
3. Open ClipVault (main window). If it asks for biometrics, authenticate.
4. Run `powershell -ExecutionPolicy Bypass -File scripts\device-validation-record.ps1` and paste the "Lock log" table, or copy the newest "Lock" row from Diagnostics.

How to read the top row:

| Top row | Meaning / next step |
| --- | --- |
| `DEVICE_LOCKED_MAIN`, device locked | The keyguard considers the device locked while DeX runs (e.g. Samsung "lock after screen timeout" on the handset). Policy decision needed: keep fail-closed, or scope the check to the display the activity is on. DeX must stay a non-authentication factor (§20.10). |
| `SCREEN_OFF` | The handset display turning off in DeX sends screen-off. Consider whether handset screen-off in DeX should lock (it should unless the design is changed). |
| `AUTO_LOCK_TIMEOUT`, windows 0 | A ClipVault window stopped although it looked open (DeX freeform lifecycle). Fix activity counting / QuickPaste close-on-stop behaviour. |
| `PROCESS_RESTART` | The process died; look for low-memory kills or crashes (`adb logcat -b crash`). |
| no new row | The vault was not locked; the prompt came from something else (e.g. MainActivity state). Report it. |
