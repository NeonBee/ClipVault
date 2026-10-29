# Security policy

## Reporting a vulnerability

Please do not publish exploitable details in a public issue. Use GitHub's private vulnerability reporting for this repository and include the affected version, Android version, device/ROM, reproduction steps and the expected security boundary.

## Key model

The SQLCipher database key is 32 random bytes. It is never stored as plaintext. Enrollment encrypts it with an AES-256-GCM key generated inside Android Keystore. That wrapping key is non-exportable, requires user authentication for every use and is invalidated when biometric enrollment changes.

Unlock uses AndroidX `BiometricPrompt` with `BIOMETRIC_STRONG` and passes the initialized cipher as a `CryptoObject`. Successful UI authentication without the authenticated cipher is insufficient to open the database.

There is intentionally no recovery key, server escrow or bypass. Losing or invalidating the Keystore key makes the existing vault unrecoverable unless the user has an encrypted `.cvault` export and its passphrase.

## Storage

- Main vault: SQLCipher, `cipher_memory_security=ON`, `secure_delete=ON`, foreign keys and encrypted FTS5 with FTS5 `secure-delete` (schema v3), so permanently deleted clips leave no terms in the FTS shadow tables.
- Locked-state staging: a separate SQLite database whose payloads are individually encrypted with AES-256-GCM; deduplication uses an independent Keystore HMAC key.
- Local backup: Argon2id (64 MiB, 3 iterations, parallelism 1) derives an AES-256-GCM file key. The header is authenticated as AAD.
- Android backup and device transfer: disabled through manifest and extraction rules.

## Runtime protections

- `FLAG_SECURE` blocks screenshots and recent-app previews.
- QuickPaste has **no persistent plaintext query/result cache**: it shows content only while the vault is unlocked through `BiometricPrompt` + `CryptoObject`, cannot enroll a key, and its real `QuickPasteActivity` remains `exported=false`, recents-excluded and thumbnail-protected. On ordinary background `onStop()`, it **keeps the DeX task alive** (for resized-window restoration) while clearing the search query and decrypted result state; on vault lock it also drops exposed results. Esc/explicit Close finish the task; copying closes it by default, but the user can opt in to **Keep open after copy**. It only writes selected content to the **system** clipboard and never injects input or auto-pastes.
- Foreground notifications contain status only, never clipboard content.
- The opt-in `QuickPasteLauncherAlias` is a separate **exported launcher entry** (`MAIN/LAUNCHER` + `MAIN/INFO`) and is disabled by default. Other apps may open the QuickPaste window through it while enabled; opening does **not** grant vault reads or bypass authentication. The alias does not export `QuickPasteActivity` itself.
- Copied-back clips use `EXTRA_IS_SENSITIVE` where supported.
- **The Android/system clipboard is outside ClipVault's encrypted trust boundary.** `EXTRA_IS_SENSITIVE` is treated as a display/privacy hint, not as a confidentiality guarantee. Once plaintext is copied back, vendor clipboard history, keyboard clipboard UI, cross-device clipboard features and other platform-permitted handling are outside ClipVault's control.
- Vault lock drops repository access immediately and closes SQLCipher on the serialized I/O executor.
- Screen-off/background timeout and explicit notification lock are supported. The background timeout starts only when no ClipVault window is visible, covers vaults unlocked from QuickPaste, and is armed when an unlock completes after the window was already hidden. A lock during an unlock invalidates it (lock epoch), so a late database open cannot reopen the vault.
- Likely OTP, password/token phrases and payment numbers are skipped by default.
- Lock diagnostics keep only reason codes, timestamps and device-state flags for the last 8 locks; no clipboard content, search text or key material. Samsung DeX detection is used for diagnostics only and never as an authentication factor.

## Secure text roadmap (WP-07A PoC; WP-07B planned)

WP-07 defines an optional path for sensitive text that avoids the Android/system clipboard in both directions:

- **Secure Copy:** explicit text selection is delivered to ClipVault through Android text-processing integration and stored in the authenticated vault without using `ClipboardManager`.
- **Secure Paste:** a retrieval-only ClipVault secondary IME inserts a selected vault item directly into the current editor through `InputConnection`.

This path is intentionally designed without Accessibility Service, Samsung Keyboard hooks, arbitrary UI injection or a new network boundary. Samsung Keyboard remains the normal primary IME; ClipVault's planned IME is an on-demand retrieval surface only.

WP-07A has a **draft PoC implementation** of a separately confirmed `ACTION_PROCESS_TEXT` ingress Activity which writes only to an unlocked SQLCipher vault; it does not use the background clipboard capture or pending staging path. The app does not yet claim this path is independently proven private on Samsung/other ROMs: the on-device clipboard non-interference and cross-app compatibility matrix remain **pending**. WP-07B Secure Paste/IME is not implemented. The existing normal Copy/QuickPaste restore path still exposes plaintext to the system clipboard.

See [docs/WP07_SECURE_TEXT_BOUNDARY.md](docs/WP07_SECURE_TEXT_BOUNDARY.md), [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md), and the [WP-06 implementation/verification boundary](docs/WP06_COMPLETION.md).

## Shizuku boundary

The Shizuku UserService exposes a small AIDL v3 interface: protocol version, read current text, register/unregister clipboard listener, capability probe, last error code and destroy. It does not accept shell commands or expose general system services. It runs only under the ADB shell UID 2000; root and Sui backends are refused. Each Android user gets its own service instance bound to one app UID, and clipboard text over 128,000 chars is rejected rather than truncated. Error codes and diagnostics never contain clipboard content. Hidden clipboard APIs are accessed reflectively because public Android APIs prohibit continuous background clipboard reads; this can break on vendor ROMs and is not itself a security guarantee.

## Network boundary

The application does not request `android.permission.INTERNET`. It contains no analytics, telemetry, account, advertising or cloud client. CI inspects the packaged APK and fails if the network permission appears.

## Limitations

ClipVault cannot protect plaintext from a compromised OS, root process, malicious accessibility service, process injection or memory inspection while the vault is unlocked. It also cannot protect plaintext after the user explicitly sends it through the normal system clipboard path. It is not a replacement for a separately audited password manager. See [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) for the complete boundary.
