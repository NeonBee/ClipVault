# WP-06S — Samsung DeX QuickPaste launcher alias PoC

Status: **implementation PR / real-device validation PENDING** (2026-10-27).

This is a **WP-06 follow-up**, not WP-07. WP-07 is reserved for the separate secure
copy / retrieval-only IME plan (`WP07_SECURE_TEXT_BOUNDARY.md`).

## Why

On a Galaxy S23 Ultra (SM-S918N, Android 16 / One UI 8.0), Samsung's
**키 직접 설정 → 앱 단축키** picker contains a configurable `Cmd + letter`
slot. The user selected **Cmd + P → ClipVault**, and app launch worked.
However, the picker did **not** expose the existing `quick_paste` dynamic
launcher shortcut because it is not a separate launcher activity.

Important modifier observation:
- `Ctrl → Ctrl`, `Cmd → Cmd` (unmodified mappings): **app shortcut did not work**
  in the observed setup.
- Changing to `Ctrl → Cmd` and `Cmd → Ctrl`: **ClipVault launched**.
- The physical key combination, incoming Android modifier state, and influence
  of keyboard firmware/layout have **not** been characterized. Do not claim
  that the user only needs to press a particular physical key combination.
- The user frequently controls **Windows remotely from DeX**. Globally
  swapping Cmd/Ctrl could interfere with Windows Ctrl-based shortcuts and
  Meta/Windows-key passthrough. This is a **compatibility risk, not yet an
  observed remote-client failure**. Never require or automatically change
  the user's keyboard mappings.

See the Drive collaboration report, WP-06S S01/S02 and R01–R04, for original
observations and the outstanding field tests.

## Minimal approach

Keep the **one APK** and the existing QuickPaste UI/authentication flow.
Add a *distinct, opt-in* launcher entry:

- `<activity-alias name=".quickpaste.QuickPasteLauncherAlias"` targets
  `QuickPasteActivity` with `MAIN/LAUNCHER` and label
  **ClipVault Quick paste**.
- `android:enabled="false"` by default. The additional icon is not shown
  until the user explicitly enables it from **ClipVault → Settings →
  DeX / hardware keyboard → Show separate Quick paste app icon**.
- `android:exported="true"` on the alias is **required** for launchers and
  the Samsung system app picker to reach it. The underlying
  `QuickPasteActivity` stays **`exported=false`**.
- Toggling uses `PackageManager.setComponentEnabledSetting` with
  `DONT_KILL_APP`; current state comes from PackageManager, not from a
  duplicate preferences toggle. Disabling removes the optional launcher
  entry without uninstalling or clearing vault data.
- Existing app icon's dynamic `quick_paste` shortcut is unchanged.

### Security boundary

An exported alias is a **new public activity entry point**: other apps can
request that the QuickPaste window be opened when the option is enabled.
This does **not** by itself expose vault records; existing QuickPaste code
enforces the shared vault lock/biometric CryptoObject path, `FLAG_SECURE`,
recents exclusion, and explicit user selection before the clipboard copy.
Opening the window is not authorization to read the vault.

Do not accept external extras as a search query, text for copying, or an
unlock token. Do not add `INTERNET`, AccessibilityService, keyboard input
logging, root, or arbitrary Shizuku input hooks. Android background activity
launch restrictions are **not** a substitute for security validation.

## Safe test setup

Use the current `feature/wp06s-quickpaste-launcher` PR checkout and the
already proven incremental deploy helper (existing vault data preserved):

```powershell
git fetch origin
git switch feature/wp06s-quickpaste-launcher
git pull --ff-only origin feature/wp06s-quickpaste-launcher
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1
```

**Never uninstall or clear ClipVault data to resolve a debug signing mismatch.**
Do not use `connectedDebugAndroidTest` on a personal-vault device; CI runs
instrumentation on a disposable emulator.

1. Before enabling the setting, confirm no separate **ClipVault Quick paste**
   icon or selectable DeX app shortcut exists.
2. Unlock ClipVault, open **Settings → DeX / hardware keyboard** and toggle
   **Show separate Quick paste app icon** ON.
3. Check the launcher and the **키 직접 설정 → 앱 단축키** app selector:
   is there a **separate** Quick paste target, or does Samsung group the two
   aliases by package? Screenshots should not contain vault data.
4. If shown separately, map the spare key slot to Quick paste. Return to a
   Chrome text field and press the *actual physical* keyboard combination.
   Confirm QuickPaste appears, takes search focus, and isn't Main Vault.
5. Repeat from Samsung Notes / Termux with a different window focused.
6. With the vault locked, ensure strong biometrics are still required. Try
   double-invocation, Esc close, and focus return. Check background/foreground
   tasks and verify no unexpected clipboard write occurs merely on open.
7. Disable the setting and confirm the extra icon/selection entry disappears.
   The original ClipVault launcher icon and long-press Quick paste shortcut
   must continue to work.
8. **Remote Windows compatibility**: test original Ctrl/Cmd mappings versus
   swapped mapping with Windows remote-control client, documenting Ctrl+C,
   Ctrl+V, Ctrl+Tab, Alt+Tab and Windows-key behavior. Prefer retaining
   original mappings even if a DeX hotkey cannot be configured.

## Evaluation

| Case | Expected | Status |
| --- | --- | --- |
| A01 | Alias OFF after clean install | CI test; device PENDING |
| A02 | Opt-in ON creates secondary launcher icon | PENDING |
| A03 | Samsung picker sees secondary entry separately | PENDING |
| A04 | Chrome focused → physical hotkey opens QuickPaste | PENDING |
| A05 | Locked vault still requires biometrics | PENDING |
| A06 | Esc/focus and double-invocation safe | PENDING |
| A07 | Opt-out removes alias, original shortcut unaffected | PENDING |
| R01 | Default key mappings' app shortcut behavior recorded | observed failure; independent recheck PENDING |
| R02 | Swapped Ctrl/Cmd mappings allow ClipVault launch | user observed PASS |
| R03 | Remote Windows control modifier compatibility | PENDING |

**GO** only if A02–A07 are confirmed on the Samsung target device and the
remote Windows keyboard use is acceptable. If Samsung groups aliases by
package and no second target is selectable, reject this technique as a DeX
hotkey solution; the extra launcher icon can be disabled. An alternative
notification QuickPaste action should be considered separately.

References:
- Android: https://developer.android.com/guide/topics/manifest/activity-alias-element
- Android: https://developer.android.com/reference/android/content/pm/PackageManager
- `docs/WP06_DEX_UX.md` and `docs/WP07_SECURE_TEXT_BOUNDARY.md`

## Samsung S03 field observation — 2026-10-27 (separate icon visible; hotkey launches Main)

User report from One UI 8.0 / Samsung DeX:
- The optional **ClipVault Quick paste** launcher icon **does appear** after enabling it (A02 PASS).
- Selecting that icon in **키 직접 설정 → 앱 단축키** registers **ClipVault**, and triggering the bound key **opens Main Vault**, not QuickPaste (A03/A04 FAIL for this build).
- It is NOT yet established whether directly **tapping the second launcher icon** opens QuickPaste. This is the necessary discriminating check before modifying app code.

### Root cause hypothesis (not yet independently verified)

The alias is a distinct **launcher component inside the SAME application package**. Samsung's app-shortcut UI might store only the chosen **package name**, or may canonicalize the selection to that package's default `MAIN/LAUNCHER` entry. If the user selects the second icon but the OS ultimately launches `dev.clipvault.app.MainActivity`, this cannot be fixed by changing the alias label, icon or its taskAffinity. Android's `PackageManager.getLaunchIntentForPackage(packageName)` API, if used by the OEM, explicitly returns a front-door activity for the *package*, not an arbitrary selected shortcut component. This particular Samsung implementation has NOT been source-confirmed: hypothesis only.

### Two controlled, data-safe tests

**Test D01 — tap the secondary launcher icon directly.**
If it opens the QuickPaste search window (instead of Main Vault), the alias target is functioning; Samsung's **app shortcut mapping**, not ClipVault's launcher alias routing, is the likely fault.

**Test D02 — explicit alias launch over ADB** (ensure Settings opt-in is ON):

```powershell
adb devices
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n dev.clipvault.app.debug/dev.clipvault.app.quickpaste.QuickPasteLauncherAlias
```

This does NOT uninstall, clear, or rewrite the vault. On non-debug builds substitute the actually installed package name. If D02 opens QuickPaste correctly while the Samsung bound key opens Main, record A03/A04 as **Samsung component-collapsing/launch-routing limitation**. If D01/D02 also launch Main or fail, inspect the final merged manifest and alias target in the installed APK before changing the architecture.

**Do not** reorder/remove the normal Main Vault launcher to trick an ambiguous package-based resolver; that would break default app launching. **Do not** make Ctrl/Cmd exchange mandatory: Windows remote-control compatibility remains an independent blocker R03. A second package/companion APK would be a distinct security architecture with IPC and signing considerations, not an automatic follow-on fix.

A CI note: Android CI run #46 `instrumentation` succeeded, but `verify` failed on six new `MissingTranslation` lint findings in `values-fa`. The translations were added in a follow-up commit; run #46 does not establish a complete CI pass.
