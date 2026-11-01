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

These folders represent **only existing stored flags**, not a new semantic classifier. Finance, Identity and Credential-specific folders are **now supported as browse-only candidate classes** (see Phase 2). They are not authoritative sensitivity decisions; current Sensitive is still the original legacy flag. An item without any recognized taxonomy flags is accessible in Unclassified. Ordinary LANG/OTHER flags do not exclude it.

**Do not relabel existing clips as LOW based only on missing SENSITIVE.** Existing rules can miss credentials. No automatic capture behavior was modified. The default `skip_sensitive` setting still applies to capture, but WP-07A Secure Copy may explicitly store sensitive values.

Remaining WP-08A steps: measure JNI/Java parity and Korean/credential candidate false-positive/negative rates, validate unlocked versioned backfill in CI/on-device, add explicit manual classification overrides and encrypted-backup coverage. No model or new automatic skip-sensitive capture policy is included.

## Phase 2 implemented: browse-only candidate classifier + authenticated backfill

**Actual code in this draft PR:**

1. `NativeClassifier.analyze()` and `fallbackAnalyze()` now both OR **`BrowseClassifier.additionalFlags(normalized)`** into their returned `TextAnalysis.flags`. Existing native/JNI and Java fallback normalized content and legacy `analysis.sensitive` are unchanged. C++ core has **not** been rewritten; the shared Java browse augmenter runs identically after either core path. An Android instrumentation corpus asserts full native/fallback `flags` and `sensitive` parity. This is an intentional architecture choice, not an assertion that equivalent C++ regexes were added.
2. New browse-only candidate flags: `KOREAN`, `CREDENTIAL`, `SSH_COMMAND`, `FINANCE`, `IDENTITY`. Credential candidates include PEM header, SSH public-key format, JWT-like token, known sample prefixes, URL query token-like parameters and Korean keywords. SSH commands are development candidates, **not** automatically secrets. Finance and identity use explicit context terms, not unverified digit-only declarations. No arbitrary opaque-string password classifier or generative model has been introduced.
3. Keyboard folder taxonomy adds **Credentials & Keys**, **Finance**, **Identity**, and maps SSH commands to Development. Existing `Sensitive` remains the **pre-existing** `SENSITIVE` flag; new `CREDENTIAL` is **browse-only** and does not implicitly change the user's auto-capture skip setting.
4. IME list cards with pre-existing `SENSITIVE` **or** new `CREDENTIAL` flags now mask potentially content-derived titles and previews as "Protected clip · tap to paste." This masking is UX only, not a crypto boundary or authoritative risk classification. Item tap still requires the original `SecurePasteTransport` checks.
5. Existing stored rows (including encrypted legacy rows) receive new classification signals through `VaultRepository.backfillBrowseSignalsAfter()`. Updates are **transactional**, at most 32 rows per batch, and touch `clips.flags` only. This does not trigger the FTS content/title/note/domain update triggers. The original 18 legacy flags, content, manual tags, collections and capture policy remain unchanged.
6. `ClipVaultApp.scheduleBrowseBackfill()` runs only following successful strong-biometric Vault unlock. Each small SQLCipher batch is guarded by `lockEpoch` synchronization; lock/screen-off prevents further access or writes. A **non-secret** local completion marker `browse_classifier_version` is written only after the entire backfill completes. Partial/rejected work is retried on a later unlock, idempotently. There is **no WorkManager periodic classifier**, persistent alternate key, remote model, nor decrypted cache outside the Vault.
7. Tests added for Korean, SSH, credential candidates, tokenized URL, capture-sensitive noninterference, Java/native parity, category icon/multi-folder behavior, and version-1 encrypted DB migration followed by browse backfill.

**Limitations awaiting separate WP-08A work:** Fine-grained false-positive/negative evaluation, validation of new string pattern quality on a Korean/English synthetic corpus, manual category overrides and explicit reclassify command UI, device performance measurements, and further IME reliability testing. Rules do not establish whether a stored value is truly a credential/finance/identity secret; the UI must never claim `LOW` from absence of a match.

## Required validation

1. **CI** `verify` + `instrumentation`, and unit tests for category overlap, excluded mask, icon priority and query builder.
2. **Galaxy S23 Ultra / One UI 8**: tapping ✦ → Development/Links/Sensitive/Unclassified → leaf clip → paste exactly once; folder tap alone must **not** type into the browser address field.
3. **31+ sample clips** in one folder, use Load more for 31st item; ensure current editor target and lockEpoch unchanged during paging.
4. **Lock**: on screen off and background lock, clear list; no count, no preview. Biometric cancellation cannot access category contents.
5. **Shizuku + Samsung/system clipboard**: no new events or history from category navigation and Secure Paste; positive-control ordinary Copy remains separately detectable.
6. **Empty folder**: back control still returns to categories; all clips remain accessible via All.
7. **Compact/landscape/DeX**: no overlapping system nav glyphs; no extra IME keyboard; fourth item reachable by scroll; light/dark text and accessibility labels.

**Personal-device safety:** never run `connectedDebugAndroidTest`, `adb uninstall`, or `pm clear` on the populated S23 Ultra. Only non-destructive signed-in-place updates and manual/synthetic tests.
