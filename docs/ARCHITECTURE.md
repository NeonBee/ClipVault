# Architecture

ClipVault uses one Android application module with explicit boundaries instead of a dependency-injection framework.

## Layers

- **Compose presentation:** a single `MainActivity`, Navigation Compose destinations, `VaultViewModel`, StateFlow and Paging 3.
- **Application container:** owns UI settings and the encrypted backup manager. Database ownership remains in `ClipVaultApp` so lock transitions can close SQLCipher deterministically.
- **Encrypted persistence:** `VaultRepository` is the synchronized SQLCipher boundary. Schema v3 contains clips, collections, tags, links, capture rules and an FTS5 index with the persistent FTS5 `secure-delete` option. The v2 → v3 migration enables it, rebuilds the index and truncates the WAL so terms of clips deleted under v2 are purged.
- **Locked-state ingress:** `SecurePendingStore` uses a separate non-exportable Keystore AES key and HMAC lookup key. It can accept clipboard text while the biometric database key is absent.
- **Capture bridge:** a foreground service talks to a clipboard-only Shizuku UserService over AIDL v2. The bridge exposes read and listener operations only; it cannot execute arbitrary shell commands.
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
