# Threat model

## Protected against

- Casual inspection of application files while the vault is locked.
- Copying the SQLCipher database without the random 256-bit database key.
- Using the biometric wrapping key without a successful strong biometric prompt.
- Plaintext Android cloud backup or device-transfer backup.
- Clipboard text leaking through application logs, notifications, screenshots or recent-app previews.
- Offline backup tampering or incorrect passphrases through AES-GCM authentication.

## Not protected against

- A compromised OS, root process, malicious accessibility service, injected process or active memory inspection while the vault is unlocked.
- A hostile or modified Shizuku installation.
- Vendor ROM changes that break hidden clipboard interfaces.
- Someone coercing the user to authenticate or observing text after it is copied back to the system clipboard.
- Vendor/system clipboard history, keyboard clipboard UI, cross-device clipboard features or other platform-permitted consumers after plaintext has been copied back to the system clipboard.
- Loss of the Keystore key after biometric enrollment changes. There is no recovery backdoor.

## System clipboard boundary

The Android/system clipboard is treated as an **untrusted egress boundary**, not as part of ClipVault's encrypted trust boundary.

Current copy-back behavior intentionally keeps the system clipboard for compatibility. `EXTRA_IS_SENSITIVE` is a display/privacy hint where supported; it is not treated as a confidentiality control and does not make the system clipboard private.

Therefore security claims must distinguish between:

- plaintext protected inside ClipVault's encrypted storage and authenticated runtime boundary; and
- plaintext explicitly exported to the system clipboard, whose subsequent lifetime is controlled by Android, the vendor ROM, the active keyboard and other platform-permitted components.

WP-07 defines a planned secure text path that avoids the system clipboard for explicit sensitive-text transfers:

- **Secure Copy:** selected text enters ClipVault through an Android text-processing action and is written to the vault without `ClipboardManager`.
- **Secure Paste:** a retrieval-only ClipVault IME inserts selected vault text through `InputConnection` without `ClipboardManager`.

These paths are **not implemented yet** and must not be described as current release guarantees until the clipboard non-interference gate in `WP07_SECURE_TEXT_BOUNDARY.md` passes.

The secure path must fail closed: if direct ingest or direct insert is unavailable, ClipVault must not silently fall back to the system clipboard. The user may still choose the separate normal Copy path explicitly.

## Sensitive-content policy

Likely OTPs, password phrases, API tokens and Luhn-valid payment numbers are skipped by default. Detection is conservative and cannot guarantee that all secrets are recognized. User rules add deterministic domain/content blocking but are not a substitute for reviewing what is stored.

## Backup policy

`.cvault` files depend entirely on the user passphrase. A minimum of 12 characters is enforced, but a long unique phrase is strongly recommended. There is no password reset or escrow. Replace imports require an unlocked vault, a typed `REPLACE` confirmation and fresh strong biometric authentication.
