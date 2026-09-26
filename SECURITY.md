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
- Foreground notifications contain status only, never clipboard content.
- Copied-back clips use `EXTRA_IS_SENSITIVE` where supported.
- Vault lock drops repository access immediately and closes SQLCipher on the serialized I/O executor.
- Screen-off/background timeout and explicit notification lock are supported.
- Likely OTP, password/token phrases and payment numbers are skipped by default.

## Shizuku boundary

The Shizuku UserService exposes a small AIDL v2 interface: protocol version, read current text, register/unregister clipboard listener and destroy. It does not accept shell commands or expose general system services. Hidden clipboard APIs are accessed reflectively because public Android APIs prohibit continuous background clipboard reads; this can break on vendor ROMs and is not itself a security guarantee.

## Network boundary

The application does not request `android.permission.INTERNET`. It contains no analytics, telemetry, account, advertising or cloud client. CI inspects the packaged APK and fails if the network permission appears.

## Limitations

ClipVault cannot protect plaintext from a compromised OS, root process, malicious accessibility service, process injection or memory inspection while the vault is unlocked. It is not a replacement for a separately audited password manager. See [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) for the complete boundary.
