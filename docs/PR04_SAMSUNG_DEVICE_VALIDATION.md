# PR-04 — Samsung / DeX Device Compatibility Validation

Validation date: 2026-09-27  
Target branch baseline: `hardening/base`  
Target device: Samsung Galaxy S23 Ultra  
Desktop mode: Samsung DeX  
Android / One UI version: not recorded in this validation session

## Scope

PR-04 validates the protocol-v3 Shizuku clipboard bridge on the Samsung target device before starting PR-05 QuickPasteActivity.

The code under test includes:

- shell-only Shizuku backend
- per-user bridge scoping
- exact hidden-`IClipboard` signature matching
- event/poll fallback
- capture lifecycle shutdown/restart handling
- biometric vault flow
- Samsung DeX clipboard capture

## Results

| Check | Result | Observation |
| --- | --- | --- |
| App build / install | PASS | Local debug build installed and launched successfully. |
| Shizuku permission / bridge startup | PASS | Shizuku permission granted; bridge was usable and did not enter a backend-rejected state. |
| Biometric vault unlock | PASS | Vault opened successfully with fingerprint authentication. |
| Clipboard read / capture | PASS | A copied URL was captured and appeared in Collections. |
| Repeated clipboard capture | PASS | Multiple successive clipboard changes were captured as expected. |
| Capture OFF | PASS | Clipboard changes made while capture was disabled were not persisted. |
| Capture ON after OFF | PASS | Capture resumed normally after re-enabling it. |
| Vault lock / unlock path | PASS | Capture behavior across vault lock/unlock matched the expected pending/persistence path. |
| Shizuku disconnect / restart recovery | PASS | Capture recovered after the Shizuku path was restarted/re-established. |
| Samsung DeX clipboard capture | PASS | Clipboard capture worked in DeX, including keyboard-driven copy operations. |
| Secure Folder clipboard | EXPECTED LIMITATION | Clipboard operations inside Samsung Secure Folder were not visible to the owner-profile ClipVault instance. This is treated as a profile/Knox isolation boundary, not a bridge-v3 defect. |
| Screen lock / unlock | NOT YET RECORDED | Required by the original PR-04 gate; run once and record result. |
| Doze / wake | NOT YET RECORDED | Required by the original PR-04 gate; run once and record result. |
| Oversized payload rejection (>128,000 UTF-16 chars) | NOT YET RECORDED | Contract/CI coverage exists, but the real-device rejection path has not yet been recorded. |
| READY_EVENT vs READY_POLL | NOT DIRECTLY OBSERVED | Current Settings UI only exposes Shizuku Ready/Offline; it does not display the healthy bridge subtype. Functional capture is confirmed. |

## Secure Folder boundary

Samsung Secure Folder runs in an isolated profile/container. The owner-profile ClipVault instance does not attempt to cross that profile boundary.

For this fork, the expected behavior is therefore:

```text
Owner profile clipboard
  -> owner-profile ClipVault capture

Secure Folder clipboard
  -X-> owner-profile ClipVault
```

Cross-profile clipboard capture is out of scope for PR-04. If Secure Folder support is ever desired, it should be treated as a separate deployment/compatibility problem rather than widening the current bridge privileges.

## PR-04 exit criteria

Already satisfied:

- [x] Samsung target device can bind and use the Shizuku clipboard bridge.
- [x] Real clipboard content is captured and persisted.
- [x] Capture disable/enable lifecycle behaves correctly.
- [x] Shizuku restart/recovery behaves correctly.
- [x] Samsung DeX clipboard capture works.
- [x] Secure Folder behavior is classified as an expected profile-isolation limitation.

Remaining before final closure:

- [ ] Record screen lock -> unlock behavior.
- [ ] Record doze/wake behavior.
- [ ] Record real-device oversized-payload rejection.
- [ ] Optional: expose or collect `READY_EVENT` / `READY_POLL` for the compatibility matrix.

## Decision

No bridge-v3 blocker has been found on the tested Samsung Galaxy S23 Ultra / DeX path.

PR-05 QuickPasteActivity may be prepared in parallel, but PR-04 should be marked fully closed only after the three remaining real-device gate checks above are recorded.
