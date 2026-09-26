# Architecture

ClipVault uses one Android application module with explicit boundaries instead of a dependency-injection framework.

## Layers

- **Compose presentation:** a single `MainActivity`, Navigation Compose destinations, `VaultViewModel`, StateFlow and Paging 3.
- **Application container:** owns UI settings and the encrypted backup manager. Database ownership remains in `ClipVaultApp` so lock transitions can close SQLCipher deterministically.
- **Encrypted persistence:** `VaultRepository` is the synchronized SQLCipher boundary. Schema v3 contains clips, collections, tags, links, capture rules and an FTS5 index with the persistent FTS5 `secure-delete` option. The v2 → v3 migration enables it, rebuilds the index and truncates the WAL so terms of clips deleted under v2 are purged.
- **Locked-state ingress:** `SecurePendingStore` uses a separate non-exportable Keystore AES key and HMAC lookup key. It can accept clipboard text while the biometric database key is absent.
- **Capture bridge:** a foreground service talks to a clipboard-only Shizuku UserService over AIDL protocol v3. The bridge exposes read, listener, capability-probe and error-code operations only; it cannot execute arbitrary shell commands.
  - *Shell only:* the app refuses to bind when `Shizuku.getUid()` is not 2000, the service refuses every call when its own UID is not 2000, and automatic Sui initialization is disabled. Health reports `DEGRADED:BACKEND_NOT_SHELL`.
  - *User scoping:* the UserService tag is `clipvault.clipboard.v3.u<userId>`. The first calling app UID binds a service instance; the hidden API receives that UID's Android user, also for system_server listener callbacks. Other UIDs get `CALLER_MISMATCH`.
  - *Exact signatures:* `HiddenClipboardApi` calls `IClipboard.getPrimaryClip` and `addPrimaryClipChangedListener` only when name, return type and every parameter match a known AOSP variant (API 24–28, 29–33, late 33 with attribution tag, 34+ with device ID). Unknown or ambiguous vendor shapes fail closed with `GET_PRIMARY_CLIP_SIGNATURE_UNSUPPORTED` or `LISTENER_SIGNATURE_UNSUPPORTED`; a signature without a user parameter is refused for users other than 0.
  - *Health:* `BridgeHealth` separates `NO_SHIZUKU`, `PERMISSION_REQUIRED`, `BINDER_CONNECTED`, `API_PROBING`, `READY_EVENT`, `READY_POLL` and `DEGRADED`. A bound binder whose read probe fails is `DEGRADED`, not ready, and is re-probed with exponential backoff. The sanitized state code is shown in diagnostics and the notification.
  - *Payload limit:* text over 128,000 UTF-16 chars is rejected whole, never truncated, both in the UserService and in the capture coordinator. Diagnostics show `CAPTURE_REJECTED_TOO_LARGE`.
- **Native analysis:** one JNI call returns normalized content, flags, sensitivity, canonical URL and domain. The Java fallback preserves capture if the native library cannot load.

## Data invariants

- `content_hash` is unique over normalized UTF-8 content.
- A repeated capture updates `last_captured_at` and `capture_count`; it does not duplicate content.
- Normal queries exclude `deleted_at`; Trash queries include only deleted rows.
- Retention soft-deletes only unpinned clips and uses `last_captured_at`.
- Trash purge is irreversible after 30 days.
- FTS triggers update whenever content, title, note or domain changes.
- A v1 migration is transactional and seeds first/last capture timestamps from `created_at`.

## Lock lifecycle

The database key exists only while the vault is unlocked. `lockVault()` immediately makes the repository unavailable and closes SQLCipher on the serialized I/O executor. Background capture continues into encrypted staging. Maintenance requested while locked is applied after the next biometric unlock.

## Offline boundary

The manifest intentionally omits `android.permission.INTERNET`. File exchange is limited to user-selected Storage Access Framework URIs. CI dumps APK permissions and fails if the network permission appears.
