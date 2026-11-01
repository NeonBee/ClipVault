# WP-08 Council Review Matrix / 독립 리뷰 기록지

**State:** CHAIR SYNTHESIS WRITTEN / MAINTAINER APPROVAL PENDING. Two attributable review messages exist. See the [chair synthesis](WP08_CHAIR_SYNTHESIS.md). This document remains the decision ledger and does *not* claim six independently completed votes.

Reviewers should add their own independent positions in PR comments (or into this file in a subsequent commit). Do not fabricate names, simulate approvals, or collapse genuine disagreement into consensus.

## 0. Received reviews (actual evidence, not simulated seats)

| Contributor | Scope | Explicit stance | Permanent PR reference |
| --- | --- | --- | --- |
| Grok / xAI | **S2 Android & Background Execution** | APPROVE WITH CONDITIONS | [#6557023992](https://github.com/NeonBee/ClipVault/pull/21#issuecomment-6557023992) |
| Claude Opus 5.5 | **S1/S3/S4/S5 cross-review**, some S6 QA/adversarial commentary | REVISE (minor) | [#6557039054](https://github.com/NeonBee/ClipVault/pull/21#issuecomment-6557039054) |

Both were posted through maintainer **NeonBee** account as participant comments. Two submissions are **not** six independent seat votes; a dedicated independent red-team review is not yet evidenced. No model benchmark or user-device ML test was run by reviewers. The Chair has reconciled the positions into **recommendations**: [WP08_CHAIR_SYNTHESIS.md](WP08_CHAIR_SYNTHESIS.md).

## 1. Required perspectives and dissent questions

| Seat | Suggested initial position to challenge (NOT a vote) | Killer question | Evidence to request |
| --- | --- | --- | --- |
| S1 — Security | No decrypted jobs after lock; no alternate persistent key | Can a derived vector or model cache outlive `lockVault()`? | SQLCipher + WAL + screen-off test, secret scanning/log review |
| S2 — Android | Foreground unlocked batches first; WorkManager only to request work | What happens when a 30s auto-lock fires mid-LLM? | lifecycle trace with lockEpoch; Doze/charging tests |
| S3 — ML | Rules baseline first, compare embedding and LLM on ambiguous samples | Do semantic models actually beat deterministic rules? | split labeled eval corpus; precision/recall and latency deltas |
| S4 — Data | Separate inferred-label provenance from manual `clip_tags` | Can model relabel erase human overrides or orphan vectors? | schema migration + backup/restore + secure-delete checks |
| S5 — UX | Top-right categories and 2-level navigation, no IME text search | Is category tapping faster than search for 30+ items? | smartphone/DeX usability timing, long labels, accessibility |
| S6 — QA / Red Team | Fail closed on undecidable or malicious strings | Can malicious clip text force a LOW label or wrong-target insert? | synthetic attack corpus, Shizuku + clipboard regression |

**Explicit dissent to consider:**
- S5 may prefer immediate folder navigation, while S3 warns rules-only folders could be inaccurate. The release gate must differentiate **reliable browsing** from **perfect classification**. The `Unclassified` folder is mandatory if models are absent.
- S2 may want WorkManager every hour, while S1 rejects unlocking in the background. A scheduling wake-up and access authorization are separate.
- S3 may propose a small LLM for richer labels, while S1/S2 raise attack surface and resource overhead. Only measured benefit can overcome this cost.
- S4 may prefer reusing `tags` directly, while S1/S5 require clear provenance and human overrides. Avoid migration that overwrites user data.

## 2. Copy/paste independent reviewer reply

```markdown
### WP-08 Council — S[1-6] / [your reviewer identity or review model]

**Review scope and basis:** [specific commit/doc/code and any sources read]
**Decision stance:** [APPROVE AS-IS / APPROVE WITH CONDITIONS / REVISE / BLOCK / ABSTAIN]
**Preferred architecture:** [A rules only / B rules + embeddings / C selected LLM / D always-on locked]
**Proposed UX:** [folder-first / hybrid / inline-search restoration / alternative]
**Hard blockers:** [specific, falsifiable]
**Strongest argument against my own recommendation:** [required]
**Security and privacy concerns:** [including whether plaintext/vectors survive lock]
**Battery and latency concerns:** [evidence or TBD]
**Requested tests:** [measurable, with synthetic or consented test data]
**Decisions C1–C10 I can sign off:** [IDs and conditions]
**Uncertainty/confidence:** [high/medium/low, and why]
**Follow-ups / dependencies:** [owners and gates; no unsupported dates]
```

A reviewer must explicitly note if no device benchmark, model trial, or external security audit was performed. **No test results from this RFC are actual runtime measurements.**

## 3. Decision ledger (chair recommendation recorded, maintainer to approve)

**The “Actual decision” column remains OPEN** because the human maintainer has not approved the chair's proposals. The reasoning and per-ID proposals C1–C10 are in [the chair synthesis](WP08_CHAIR_SYNTHESIS.md).

| ID | Question | Candidate decision (for debate only) | Actual decision | Owner / evidence |
| --- | --- | --- | --- | --- |
| C1 | IME search replacement | Category-first + remove broken input after folders viable | **OPEN** | — |
| C2 | Single vs multiple tags | One primary + multiple secondary | **OPEN** | — |
| C3 | Type / sensitivity / value | Three independent axes, UNKNOWN ≠ LOW | **OPEN** | — |
| C4 | Inference layers | Rules baseline → embeddings benchmark → LLM opt-in | **OPEN** | — |
| C5 | Vault lock and schedule | Unlocked-only; locked skip, no alternate key | **OPEN** | — |
| C6 | DB / migration | SQLCipher inferred-label provenance separate from manual tags | **OPEN** | — |
| C7 | Previews | Mask HIGH/UNKNOWN by default after unlock | **OPEN** | — |
| C8 | Device / quality gates | Signed-off S23 Ultra + DeX + adversarial corpus | **OPEN** | — |
| C9 | Model packaging / licensing | Offline runtime and explicit model provenance | **OPEN** | — |
| C10 | Failure behavior | Never hide unclassified items or downgrade on error | **OPEN** | — |

### Disagreement register

| Topic | Position A | Position B | Evidence requested | Status |
| --- | --- | --- | --- | --- |
| Background unlocked inference | Only explicit foreground session | Bounded deferred batches while app open | Lock-state + thermal/UX measurements | OPEN |
| Inferred labels | Dedicated provenance table | Reuse existing `clip_tags` with origin field | Migration complexity / manual-override semantics | OPEN |
| LLM deployment | No LLM before rules/embeddings data | Tiny LLM from day one | Synthetic accuracy vs memory and battery | OPEN |
| Preview masking | Mask HIGH and UNKNOWN | Show snippets after biometric unlock | Threat model and user experience | OPEN |
| Search fallback | Folders only | Separate authenticated Activity search | Browser focus and IME return tests | OPEN |

## 4. Architecture evaluation form

Scores remain **TBD** until independent review and/or measured evidence. Do not assign fictional precision, recall, battery savings or latency.

| Criterion | A Rules | B + Embeddings | C + Tiny LLM | D Locked-state AI |
| --- | --- | --- | --- | --- |
| Existing biometric trust unchanged | Likely yes | Yes if unlocked-only | Yes if unlocked-only | Requires new trust model |
| Works with model not installed | Yes | Rules fallback | Rules fallback | Not sufficient |
| Accuracy for arbitrary Korean/English private text | TBD | TBD | TBD | TBD |
| Password/credential false-negative safety | Rule tests required | Rule override required | Rule override required | Security model blocked |
| Model size / app startup | Minimal | TBD | TBD | TBD |
| Battery / thermal | TBD | TBD | TBD | TBD |
| Offline-only | Yes | Yes if packaged correctly | Yes if packaged correctly | Depends on design |
| Human corrections preserved | Must implement | Must implement | Must implement | Must implement |

## 5. Minimum acceptance test set proposed to Council

- **P0 security:** offline/no INTERNET permission, no fallback system clipboard, Shizuku listener & distinct accepted count unchanged during direct paste, direct insert exactly once.
- **P0 lock:** password/credential examples in synthetic corpus; screen-off and background auto-lock invalidate in-flight classification and prevent later SQLCipher writes; no retained secret/vector in logging/prefs/notifications/cache.
- **P0 DB:** schema v3 backward compatibility (and v3→v4 migration **only if justified**) with encrypted synthetic rows and zero data loss, idempotent reassignment, human override not overwritten, trash/delete cascade, WAL + FTS5/derived metadata secure cleanup.
- **P1 UI:** folder mode and back navigation in compact phone and DeX, no nested hardware-key text requirement, no fourth item obscured, locked metadata hidden, high/unknown previews handled according to chosen policy.
- **P1 ML quality:** deterministic-only baseline vs embedding/LLM, Korean/English URL/SSH/secret/ambiguous string corpus with provenance; collect per-category confusion matrix and high-risk false negatives.
- **P1 performance:** time/battery/RSS/thermal measured on S23 Ultra across 10/100/1000 synthetic items; do not infer capability from PC GPU tests.
- **P1 scheduling:** WP-08A on-save existing rules, on-unlock catch-up and manual only; no periodic worker in A; optional B-stage 10min foreground / 1h best-effort evaluation; cancellation on lock.
- **P2 portability:** One UI versions, other IME layouts, large fonts/landscape, backup/restore and model version upgrade behavior.

**Do not execute `connectedDebugAndroidTest`, `adb uninstall` or `pm clear` on the user's populated personal Galaxy S23 Ultra.** Use CI emulator, synthetic fixtures and non-destructive on-device tests.

## 6. Close-out criteria

The Council PR remains **Draft/open** until:
1. actual reviews are attributed (Grok S2; Claude cross-S1/S3/S4/S5) and any remaining S6 or independent S1 seats are either completed or explicitly marked absent/accepted as residual risk;
2. conflicts and blockers are documented and resolved or intentionally deferred;
3. the maintainer records decisions C1–C10 with supporting rationale;
4. WP-08A / B / C implementation boundaries and security gates are accepted.

**No WP-08 feature is implemented by this documentation PR.** Actual decision recording and user sign-off must not be inferred from a Chair report alone. Any approved Council scope should result in separate narrowly scoped implementation PRs.
