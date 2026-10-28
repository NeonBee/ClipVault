# WP-06S — Samsung DeX QuickPaste launcher alias PoC

**최종 상태 (2026-10-28): PR [#16](https://github.com/NeonBee/ClipVault/pull/16) `hardening/base` 병합 완료 (`edcf79c`), S23 Ultra / One UI 8 DeX 실기기에서 삼성 앱 단축키 → QuickPaste, Main 아이콘 유지, OFF/ON 전환, 최소화·완전 종료 후 창 크기 복원 및 인증 경계 관측 PASS.** [최종 검증표](WP06_COMPLETION.md).

> 아래 내용은 최초 PoC부터 실패·원복·실기기 재검증까지의 **시점별 실험 기록**이다. 앞부분의 `FAIL`·`PENDING`·`before merge` 문장은 이후 수정 전 당시 상태를 기술한 것이며 최종 PR 상태가 아니다. Samsung의 내부 단축키 API와 다른 OS 빌드 호환성은 입증하지 않았다. Windows 원격제어 Ctrl/Cmd 검증은 사용자가 **SKIP**한 항목이며 PASS가 아니다.

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
| A02 | Opt-in ON creates secondary launcher icon | **PASS** — user tested on S23 Ultra / DeX |
| A03 | Samsung picker distinguishes the QuickPaste launch target | **PASS for outcome with INFO filter** — user reports shortcut now opens QuickPaste; the exact picker label after remapping was not separately reported |
| A04 | Hardware hotkey opens QuickPaste | **PASS for user-observed shortcut invocation with INFO filter**; Chrome-specific focus test not separately reported |
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
- **D01 PASS:** the user directly tapped the separate **ClipVault Quick paste** icon and the intended QuickPaste opened normally.
- **D02 PASS:** explicit `adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n dev.clipvault.app.debug/dev.clipvault.app.quickpaste.QuickPasteLauncherAlias` also opened QuickPaste normally.
- Therefore the alias target/registration and local launch routing work; Samsung's key-to-app selection or launch resolution is the leading failure boundary, not an app-level QuickPaste target crash.

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

CI history: Android run #46 `instrumentation` PASS / `verify` FAIL on six Persian `MissingTranslation` findings. Translation fix commit `254bf91` followed; subsequent **run #48 SUCCESS** for head `38d2bb3` (before this documentation-only status update).

### Follow-up triage — D01/D02 PASS, DeX key mapping still routes to Main (2026-10-27)

**Evidence**: On the S23 Ultra / DeX, (1) direct tap on `ClipVault Quick paste` opened QuickPaste; (2) explicit adb launch of `dev.clipvault.app.quickpaste.QuickPasteLauncherAlias` opened QuickPaste. Selecting it under Samsung `키 직접 설정 → 앱 단축키` nonetheless records the regular ClipVault app and its hotkey opens Main Vault.

**Conclusion / boundary**: Alias registration, exported entry access, and target activity dispatch have passed both direct-launch checks. The observed remaining problem lies along Samsung's *app-shortcut picker → saved selection → hotkey execution* path. The hypothesis that One UI resolves by *package default launcher* rather than the selected alias component is plausible, **not verified from Samsung implementation source**. Do not mark a `same-package app hotkey to QuickPaste` as supported.

**Next decision**: Keep the alias PoC as Draft and **do not merge as a working global-hotkey feature**. Options requiring separate exploration: (A) preserve one APK and offer a configurable default-entry behavior only with explicit user impact review (it may break normal ClipVault opening); (B) design a separate-package companion launcher with authenticated IPC and minimized exposure, only if worth the extra package/security/maintenance cost; (C) use notification QuickPaste action / existing long-press shortcut as low-risk fallbacks. Any Ctrl/Cmd remapping must also pass remote Windows use (R03) before recommendation.


## CATEGORY_INFO priority experiment (PR #16 follow-up, 2026-10-28)

**Historical experiment checkpoint (superseded by merged PR #16 and completion matrix): code implemented, CI #55 PASS, target device shortcut PASS. See the final status at the beginning of this document.**

The Android SDK documents that `PackageManager.getLaunchIntentForPackage(packageName)`
finds the first matching `ACTION_MAIN + CATEGORY_INFO` entry, then searches
`ACTION_MAIN + CATEGORY_LAUNCHER`. This does **not** establish what One UI
actually calls for its `키 직접 설정 → 앱 단축키` feature.

Implementation: the opt-in `QuickPasteLauncherAlias` now has two **separate**
`MAIN` intent filters:
- `CATEGORY_LAUNCHER`: the existing dedicated icon and direct launch path.
- `CATEGORY_INFO`: experimental front-door priority for *package-based*
  `getLaunchIntentForPackage` callers.

The MainActivity `MAIN/LAUNCHER` filter is unchanged, as is the
`QuickPasteActivity` `exported=false` property. The alias stays manifest
`enabled=false` unless the user turns on **Settings → DeX / hardware keyboard →
Show separate Quick paste app icon**. A matching Android instrumentation test
asserts `getLaunchIntentForPackage()` resolves Main when the alias is OFF,
QuickPaste alias when ON, and Main again after OFF.

### Why this is risky (read before testing)

Using `CATEGORY_INFO` as a quick-search route is a **nonstandard workaround**:
this category normally represents the application's information/front-door entry.
If the OS or another app uses the `getLaunchIntentForPackage()` algorithm,
its generic **Open app** command may now open **QuickPaste instead of Main Vault**
whenever this toggle is ON, even though the original home-screen Main launcher
icon continues to target Main explicitly.

This workaround is not intended for release until device testing confirms a
useful outcome and no surprising package-opening behavior. Do not make the
Ctrl/Cmd global remap mandatory: this remains an open Windows remote-control
compatibility risk.

### Real-device validation, no reset/reinstall

1. Get the newest PR #16 branch and run
   `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1`
   as in the previous section. Keep the SAME local debug signing environment
   to preserve Vault/Keystore data.
2. Leave the **Show separate Quick paste app icon** switch **OFF**:
   normal ClipVault icon → Main Vault; existing dynamic Quick paste shortcut
   still works.
3. Switch **ON**. The second icon should still open QuickPaste when tapped.
   The original ClipVault Main icon should still open Main Vault when tapped.
4. In **키 직접 설정 → 앱 단축키**, remove the previous `Cmd+P → ClipVault`
   entry and **reselect** ClipVault Quick paste, rather than assuming the
   old OS shortcut cached target was updated. Test the physical hotkey while
   Chrome is foregrounded.
5. Record whether Samsung's picker displays `ClipVault` or
   `ClipVault Quick paste`, and whether the hotkey opens Main or QuickPaste.
6. Test an ordinary package-based **Open** action from Android settings /
   an app-info screen if it offers such an action. Record whether it opens
   QuickPaste (side effect) or Main. Do not confuse the explicit Main launcher
   icon with a generic package-level launch.
7. With the vault LOCKED, verify strong biometrics still protect QuickPaste.
   Check Esc/focus return and Ctrl/Cmd behavior when remoting into Windows.
8. **Rollback:** switch OFF; confirm Main Vault returns for generic Open
   actions and the second icon disappears. Disabling the alias does not
   uninstall the package, clear data, or alter keyboard remapping.

Useful read-only checks on a debug installation with alias enabled:

```powershell
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.INFO -p dev.clipvault.app.debug
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p dev.clipvault.app.debug
```

For proof of `getLaunchIntentForPackage()` precedence, prefer the
instrumentation test: the `cmd package resolve-activity` shell command is
not guaranteed to implement the exact same package-launch algorithm. An
explicit `am start -n ...QuickPasteLauncherAlias` only checks the alias
itself and does NOT prove Samsung app hotkey routing.

### Decision criteria

- **PASS**: Samsung hotkey from another app launches QuickPaste while the
  original Main icon remains usable; verify side effects and remote Ctrl/Cmd.
- **PARTIAL**: Android PackageManager prioritizes INFO alias, but Samsung
  continues to open Main; this demonstrates the OEM picker does not follow
  that package algorithm (or uses a cached mapping).
- **NO-GO**: Main/QuickPaste default-opening behavior becomes confusing or
  cannot be safely isolated. Revert the INFO filter or leave the toggle OFF.
  Continue analyzing the separate-package companion launcher or other
  minimal-permission entry mechanisms.

Official API:
https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String)


### CATEGORY_INFO real-device result — PASS (2026-10-28)

**User-confirmed on Galaxy S23 Ultra / One UI 8 / DeX:**
- With the optional QuickPaste launcher switch **ON**, tapping the existing
  ClipVault Main icon still opens **Main Vault**.
- Samsung's configured physical-keyboard **app hotkey opens QuickPaste**, as
  intended; the earlier behavior of opening Main is resolved on this device.
- Turning the option **OFF and back ON** preserves the expected behavior.
- The user's tested **ordinary Open app actions remain unchanged**. This is a
  valuable compatibility observation, *not* proof that every possible Android
  caller of `PackageManager.getLaunchIntentForPackage()` behaves identically.

**Automated verification:** GitHub Actions **run #55 SUCCESS** (`verify` and
`instrumentation` both passed) on commit `c177474`, before this
documentation-only record.

**Scope of success:** A single APK plus opt-in `CATEGORY_INFO` launcher
alias is sufficient for the observed Samsung DeX keyboard-to-QuickPaste UX.
Do not pursue a companion APK merely to address the resolved routing symptom.

**Remaining release gates (not claimed PASS):**
- With the vault **LOCKED**, ensure QuickPaste keeps strong biometric
  authentication and never displays/copies protected content early.
- Repeated hotkey invocations, Esc close, focus return to the previous app,
  and operation after reboot/keyboard reconnect.
- Windows **remote-control** with the user's physical Ctrl/Cmd mapping;
  verify Ctrl+C, Ctrl+V, Ctrl+Tab, Alt+Tab and Win/Meta key behavior.
- Cross-version/model One UI compatibility; Samsung's internal app-shortcut
  implementation is still not independently identified.

**Security boundaries unchanged:** `QuickPasteActivity` remains
`exported=false`, the optional alias is public only when enabled,
`FLAG_SECURE` and biometric vault unlocking remain in place, and no
AccessibilityService, root, network access or key logging was introduced.

**Design consequence:** Continue PR #16 review as a successful device PoC,
not a universal Android global-hotkey API. Keep it Draft until the remaining
security and remote-control compatibility gates are reviewed. To undo the
experiment safely, switch the optional entry OFF without uninstalling or
resetting vault data.

## DeX resized QuickPaste window resets after minimize + shortcut (2026-10-28)

**Device observation (S23 Ultra / One UI 8 / DeX):** After narrowing and positioning
QuickPaste as a small companion window, minimizing and invoking Samsung's
working keyboard shortcut returned a **tall default window** rather than the
user-adjusted dimensions. This is separate from the resolved CATEGORY_INFO
routing: the correct QuickPaste Activity does open.

### Grounded cause and first fix

1. Previous `QuickPasteActivity.onStop()` called `close()`, which invokes
   `finishAndRemoveTask()` on ordinary task switch/minimize. That intentionally
   destroyed the very freeform task that could have held the custom bounds.
2. **Samsung's official One UI 8 DeX FAQ** says that reopened apps reset their
   previous window size and position to defaults. This OS behavior must not
   be described as something a normal Activity can override unconditionally:
   https://www.samsung.com/us/support/answer/ANS10001972/
3. The new experiment preserves the existing `singleTask` activity/task on
   ordinary `onStop()` (not finishing); **it clears the in-memory query and
   decrypted results immediately** and resets the one-shot biometric prompt
   latch for the next foreground session. No clip text or window dimensions
   are written to disk. Explicit Esc, Close, successful copy or Open Main
   continue to finish and remove the task.

**Why this might help:** A minimized but still existing freeform task can be
brought forward by the same QuickPaste launcher instead of re-created at the
manifest's initial `560×620dp` sizing. Android `singleTask` and `onNewIntent`
normally support bringing a running task to the foreground. However, One UI's
window policy may still override the geometry even for a retained task, so
**do not mark window-size retention PASS without on-device observation**.

### Safe device acceptance test (PENDING)

- [x] W01: Open via physical hotkey with opt-in alias ON, then shrink
  QuickPaste to a narrow, short panel and move it aside.
- [x] W02: Press the DeX window's **minimize** control (not Esc or Close),
  then call the *same* Samsung shortcut. Does the **same size and location**
  return, rather than the vertically extended default?
- [ ] W03: Repeat W02 three times, including when another app's input has
  focus. Query should be blank on reentry; no previous result list remains
  visible without an unlocked vault.
- [x] W04: With the vault LOCKED/background auto-lock elapsed, hotkey
  reopening must require strong biometrics and show **no cached clip content**
  until the vault is unlocked.
- [ ] W05: With the panel open, press **Esc** or copy and close; relaunch
  with hotkey. This is a fresh window: full geometry restoration after an
  *explicit close* is not promised by this first fix.
- [ ] W06: Verify DeX taskbar/minimized instance doesn't multiply with
  repeated hotkeys. Turning the launcher option OFF retains normal Main icon
  and remains a data-preserving rollback.

If W02 still resets bounds while W03/W04 pass, the cause includes One UI 8's
launch-geometry policy beyond ClipVault's own `onStop` handling. Next
investigation would be bounded per-display geometry persistence plus
`ActivityOptions.setLaunchBounds()` from a controlled launcher activity,
or a small default DeX window setting (Samsung's documented launch dimensions).
Those require a separate review because hotkey launches come from the
Samsung system rather than ClipVault itself.

Implementation note: Keeping the task alive is **not** keeping decrypted UI
state alive. The ViewModel's `clear()` cancels searches and replaces results
with the LOCKED state during `onStop`; it re-queries only if the shared vault
is still unlocked on foreground return. The app-wide background auto-lock and
screen-off locks remain in force.

### DeX minimize + hotkey retest — PASS on target device (2026-10-28)

User tested the WP-06S change after installing the new APK and reports:
- **W01/W02: PASS (user observed)** — the manually resized QuickPaste panel now **retains the adjusted DeX window size** on minimize and hotkey re-entry, rather than expanding to the tall default.
- **W04: PASS (user observed)** — the hotkey/minimize flow did **not bypass the vault authentication gate**. No bypass was observed on the device. The report does not independently verify process death, reboot persistence, or every timing edge case.
- **W03/W05/W06: still PENDING** — precise three-cycle/focus checks, explicit Esc-close geometry, and multi-window instance count were not reported. Windows remote-control Ctrl/Cmd compatibility (R03) remains PENDING.

The implementation retains the DeX task in `QuickPasteActivity.onStop()` but immediately drops its in-memory search query and decrypted results, while explicit close/copy continues to finish. **Do not persist clip contents to save window state.**

**CI follow-up:** Android Actions run **#58** for commit `98c2a60`: `verify` PASS, `instrumentation` FAIL because the old test `QuickPasteActivityInstrumentedTest.leavingTheWindowFinishesIt` still asserted `DESTROYED` after `onStop`. That assertion conflicts with the new intentional task-preservation policy (the actual lifecycle reached `CREATED`). Commit `b298f56` replaces it with a test for `CREATED` and wiped search results/query while locked. A later CI run must pass before merging; this is **test expectation drift**, not evidence of a biometric bypass.

### Final extra DeX checks and next interaction request (2026-10-28)

**User-confirmed W05 additional result:** Even after fully closing QuickPaste and invoking the hotkey again, the manually configured window size **restored successfully** on the tested S23 Ultra/One UI 8 device. This extends the earlier minimized-task recovery success. Do **not** infer the same for every DeX version or after process reboot. W05 is PASS for observed geometry; it is not a blanket test of every explicit-close security edge case.

**R03 scope update:** The user explicitly chose to **skip Windows remote-control compatibility testing as a PR #16 merge gate**. This was a requested test-scope decision, *not* proof that remote Windows shortcuts are unaffected.

**External Windows-layout Bluetooth keyboard observation:** With the user's previous Samsung physical-keyboard `Ctrl → Cmd` and `Cmd → Ctrl` swap, the **right Ctrl now acts like Windows/Meta**. Expected consequence (requires exact key-map confirmation): the physical Windows key, normally presented as Meta/Cmd to Android, may now behave as **Ctrl**. Verify in a text editor using physical Win+A / Win+C / Win+V; actual behavior can depend on keyboard firmware and right-side modifiers. Neither ClipVault nor this PR remaps keys.

**Next feature requested — Ditto-like pinned QuickPaste (NOT implemented by PR #16):** User wants copying a selected item not to immediately dismiss QuickPaste and ultimately wants a panel that remains above other DeX windows. These are two distinct contracts: (1) optional **keep open after copy**, achievable in existing Activity but not topmost; (2) true **Always on Top**, which may require a separately opted-in `TYPE_APPLICATION_OVERLAY` window and Android's `SYSTEM_ALERT_WINDOW` special permission. Do not claim pinning is possible via a simple Activity flag. One UI 8 changed/removed classic DeX's native pin-on-top affordance according to community reports, so the exact S23 behavior must be verified. The foreground search and locked/decrypted-content boundary must remain intact; the extra overlay permission is **not** part of WP-06S. Treat this as a separate security-reviewed follow-up, not scope creep into the working shortcut PR.
