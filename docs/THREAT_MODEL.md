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
- Loss of the Keystore key after biometric enrollment changes. There is no recovery backdoor.

## Sensitive-content policy

Likely OTPs, password phrases, API tokens and Luhn-valid payment numbers are skipped by default. Detection is conservative and cannot guarantee that all secrets are recognized. User rules add deterministic domain/content blocking but are not a substitute for reviewing what is stored.

## Backup policy

`.cvault` files depend entirely on the user passphrase. A minimum of 12 characters is enforced, but a long unique phrase is strongly recommended. There is no password reset or escrow. Replace imports require an unlocked vault, a typed `REPLACE` confirmation and fresh strong biometric authentication.
