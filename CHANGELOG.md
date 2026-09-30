# Changelog

## Unreleased (DeX fork hardening)

- Pinned the audit baseline at upstream `ee1f24af3010dd26be7d95545ea9dbf0fd703622` (ClipVault 2.0.0).
- Updated SQLCipher Android from 4.17.0 to 4.19.0 stable and AndroidX SQLite from 2.6.2 to 2.7.1, matching the SQLCipher 4.19 integration guide (2.7.0+).
- Moved the vault to schema v3: FTS5 `secure-delete` is enabled, v2 indexes are rebuilt, and a busy-aware `wal_checkpoint(TRUNCATE)` runs after migration, hard delete, Trash purge, edit and replace-restore (deferred to commit inside transactions) so deleted clips leave no terms in FTS shadow tables or the WAL. Added forensic deletion instrumentation tests.
- Replaced the Shizuku bridge with protocol v3: shell-UID-only backend with Sui auto-init disabled, per-user UserService tags and single-caller binding, exact AOSP signature adapters for `getPrimaryClip` and `addPrimaryClipChangedListener`, capability probe, structured error codes, a `BridgeHealth` state machine shown in diagnostics and the notification, and a 128,000-char payload limit that rejects instead of truncating.
- Marked `gradlew` executable in git so CI `./gradlew` steps no longer fail with exit code 126.
- Moved CI to `android-actions/setup-android@v4` with SDK packages passed as action input; v3 failed on current runners installing the removed `tools` package. Enabled KVM for the emulator job so instrumentation tests no longer run on a software-only emulator.
- Added Settings > Diagnostics rows for bridge mode (`READY_EVENT` / `READY_POLL` / `DEGRADED:<reason>`) and Android build, plus `scripts/device-validation-record.ps1` (Windows PowerShell 5.1 / 7) to collect device validation records without reading clipboard content. Its `-RunTest` installs with `adb install -r` and runs `am instrument`; it never uninstalls or clears the app.
- Fixed Diagnostics rows squeezing the label into a one-character column when the value is long (e.g. Android build); label and value now share the row by weight.
- Added `QuickPasteActivity` (WP-05), reached from the launcher / DeX taskbar "Quick paste" shortcut: a compact search window with FTS search, ↑/↓/PgUp/PgDn selection, Enter to restore the clip with `EXTRA_IS_SENSITIVE`, and Esc to close. It uses `FLAG_SECURE`, has no recents entry or thumbnail, keeps the query in memory only and closes when hidden. A locked vault goes through the same BiometricPrompt + CryptoObject unlock as the main window; there is no plaintext quick cache and no enrollment from QuickPaste. No auto-paste: return to the app and press Ctrl+V.
- WP-06 compact layouts: below 420dp the library quick-search field collapses into a top-left search affordance instead of disappearing into Advanced Search. Tapping it expands and focuses the same inline quick search; it can be collapsed again without clearing an active query, which is indicated by a badge. Advanced Search remains a separate action. QuickPaste freeform minimum was reduced from 360×400dp to 280×260dp in step 2 and then to 240×260dp in step 3.
- WP-06 minimum usable viewport: MainActivity had no freeform `<layout>` at all, so the main window could be resized until the library showed filter chips but no clip, and until the four top bar actions overflowed. Added a `320×384dp` floor, derived from the library budget in `LibraryLayout` (top bar 64 + filter chips 48 + one clip card 142 + list padding 24 + bottom menu 80 = 358, plus room for a two-line card body) and asserted by `LibraryLayoutTest`. The QuickPaste minimum width drops from 280dp to 240dp at the same 260dp height; the lock and setup panes wrap their buttons with `FlowRow` so the Unlock button cannot be clipped at that width.
- Guarded `VaultNavigationComposeTest`, which deletes `clipvault.db`, so it skips on a device with a provisioned real vault.
- Added lock reason diagnostics (WP-06 step 1): every vault lock records a sanitized reason code (`AUTO_LOCK_TIMEOUT`, `SCREEN_OFF`, `DEVICE_LOCKED_MAIN`, `DEVICE_LOCKED_QUICK_PASTE`, `NOTIFICATION`, `USER`, `PROCESS_RESTART`) with time since unlock, started windows, interactive (device power state), keyguard, device-locked and DeX flags. The Diagnostics screen keeps showing them across lock and unlock. The last 8 are shown in Settings > Diagnostics and in `scripts/device-validation-record.ps1`. No clipboard content is recorded.
- Moved auto-lock and screen-off locking from `MainActivity` into `ClipVaultApp`. The timer now starts only when no ClipVault window is visible, so QuickPaste over the main window no longer lets the vault lock underneath it, and a vault unlocked from QuickPaste alone still auto-locks. The main window now shows a vault unlocked by QuickPaste instead of the lock screen. An unlock that finishes after every window has stopped arms the timer, and a lock during an unlock (screen off, keyguard) stops the late database open from reopening the vault or the late success callback from showing the vault as unlocked.

## 2.0.0

- Rebuilt the application UI in Kotlin and Jetpack Compose with adaptive navigation, Paging 3 and Persian/English layouts.
- Added System, Light, Dark and AMOLED modes, Dynamic Color, six accent palettes, reduced motion and adjustable font scale.
- Migrated SQLCipher to schema v2 with FTS5, metadata, repeated-capture counts, collections, tags, capture rules and 30-day Trash.
- Expanded native classification and sensitive-content detection.
- Upgraded the Shizuku bridge to AIDL protocol v2 with event callbacks and adaptive polling fallback.
- Added share/process-text targets, a Quick Settings tile, diagnostics and retention maintenance after unlock.
- Added versioned Argon2id + AES-256-GCM local backup and transactional merge/replace restore.
- Added advanced search, bulk favorite/pin/move/tag/export actions, multi-select Trash recovery and confirmed invalid-key reset.
- Added CI unit/lint/native/offline checks plus emulator coverage for migration, backup, rules, classifier, query, retention, every theme and primary Compose navigation.
- Added an opt-in physical-device test for the live Shizuku protocol-v2 UserService bridge.

## 1.0.0

- Initial encrypted clipboard capture release.
