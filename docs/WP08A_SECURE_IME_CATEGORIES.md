# WP-08A — Secure Paste IME category browsing (first implementation slice)

**Status:** Draft PR, not ready for merge. Implemented as a first vertical slice following the maintainer-approved [Council #21](https://github.com/NeonBee/ClipVault/pull/21). No new AI model/embeddings, schema migration, periodical worker, key policy or network permission.

## Why

WP-07B IME's nested `EditText` hardware search can route physical keyboard characters into the host browser address/editor instead of the IME search. WP-08A replaces this unreliable feature with touch-first categorization. This does not claim to fix DeX Hangul typing.

## Implemented

- IME upper right **✦ AI Categories** toggles between Recent/Pinned and folder root. Folder row taps are **navigation only**, never `InputConnection.commitText`.
- **Current conservative fixed folders** only use existing saved `NativeClassifier` `clips.flags`: All, Sensitive, Development (CODE/JSON/GITHUB), Links, Contact Info (EMAIL/PHONE), Long Notes (LONG_TEXT), Unclassified. Overlapping flags mean multiple folder memberships, not multiple copies of the clip.
- Left glyph is a deterministic category icon. Sensitive takes precedence in the default icon if both link and sensitive flags exist; pinned takes precedence over the category icon. **The icon is not a sensitivity/security policy.**
- Uses existing synchronized SQLCipher `VaultRepository.query(ClipQuery)`, including indexed flag masks, with new parameterized `excludedFlags` support for the Unclassified folder. This avoids loading the full decrypted Vault to infer Unclassified on the client.
- Query results have existing `ClipPage.nextOffset` and `hasMore` pagination. **Load more** gives access beyond 30 clips while preserving the same editor/session, lock epoch and selected folder; 30 items per read. No hidden hard cap on logical browsing beyond standard repository limits.
- Removed the nested IME `EditText`, soft-input focus manipulation and hardware-search logic. Main Library's search remains unchanged.
- List and folder names/counts are not loaded when locked. Unlock remains strong biometric. All stored content that reaches a leaf tap uses existing `SecurePasteTransport.commit`, not the clipboard.

### Important limitations and policy

These folders represent **only existing stored flags**, not a new semantic classifier. In particular Finance, Identity and Credential-specific folders are **not yet implemented**; current Sensitive is a broad existing flag that may include explicit Secure Copy. An item without any recognized taxonomy flags is accessible in Unclassified. Ordinary LANG/OTHER flags do not exclude it.

**Do not relabel existing clips as LOW based only on missing SENSITIVE.** Existing rules can miss credentials. No automatic capture behavior was modified. The default `skip_sensitive` setting still applies to capture, but WP-07A Secure Copy may explicitly store sensitive values.

Upcoming WP-08A steps: establish JNI/Java fallback parity, add Korean/SSH/JWT/token-URL and finance/identity rule coverage while separating *browse labels* from capture-skip-sensitive policy, implement versioned backfill under unlocked/lockEpoch constraints, preserve human override and encrypted backup. These are **not yet implemented** by this slice.

## Required validation

1. **CI** `verify` + `instrumentation`, and unit tests for category overlap, excluded mask, icon priority and query builder.
2. **Galaxy S23 Ultra / One UI 8**: tapping ✦ → Development/Links/Sensitive/Unclassified → leaf clip → paste exactly once; folder tap alone must **not** type into the browser address field.
3. **31+ sample clips** in one folder, use Load more for 31st item; ensure current editor target and lockEpoch unchanged during paging.
4. **Lock**: on screen off and background lock, clear list; no count, no preview. Biometric cancellation cannot access category contents.
5. **Shizuku + Samsung/system clipboard**: no new events or history from category navigation and Secure Paste; positive-control ordinary Copy remains separately detectable.
6. **Empty folder**: back control still returns to categories; all clips remain accessible via All.
7. **Compact/landscape/DeX**: no overlapping system nav glyphs; no extra IME keyboard; fourth item reachable by scroll; light/dark text and accessibility labels.

**Personal-device safety:** never run `connectedDebugAndroidTest`, `adb uninstall`, or `pm clear` on the populated S23 Ultra. Only non-destructive signed-in-place updates and manual/synthetic tests.
