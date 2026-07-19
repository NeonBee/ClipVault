# `.cvault` backup format

The container is binary-header + encrypted UTF-8 JSON. No plaintext temporary file is created.

## Header (big-endian)

| Field | Value |
|---|---|
| Magic | five bytes `CVLT2` |
| Container version | 32-bit integer, currently `1` |
| Argon2 memory | `65536` KiB |
| Argon2 iterations | `3` |
| Argon2 parallelism | `1` |
| Salt length + salt | `16` + random bytes |
| Nonce length + nonce | `12` + random bytes |

The complete encoded header is AES-GCM additional authenticated data. Argon2id derives a 32-byte key. The remaining stream is AES-256-GCM ciphertext containing a versioned JSON object with the manifest, non-secret settings, collections, tags, clips, clip-tag links and capture rules.

Android Keystore entries, biometric envelopes, Shizuku grants and signing material are never exported.

Unknown future JSON fields are ignored. A payload version newer than the reader is rejected before modifying the database. Imports are applied inside one SQLCipher transaction; replace mode clears vault rows only after authentication and confirmation.
