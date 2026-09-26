# Changelog

## Unreleased (DeX fork hardening)

- Pinned the audit baseline at upstream `ee1f24af3010dd26be7d95545ea9dbf0fd703622` (ClipVault 2.0.0).
- Updated SQLCipher Android from 4.17.0 to 4.19.0 stable and AndroidX SQLite from 2.6.2 to 2.7.1, matching the SQLCipher 4.19 integration guide (2.7.0+).
- Marked `gradlew` executable in git so CI `./gradlew` steps no longer fail with exit code 126.
- Moved CI to `android-actions/setup-android@v4` with SDK packages passed as action input; v3 failed on current runners installing the removed `tools` package. Enabled KVM for the emulator job so instrumentation tests no longer run on a software-only emulator.

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
