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
| Screen lock / unlock | PASS | Capture behavior after screen lock/unlock matched the expected bridge lifecycle and recovered normally. |
| Doze / wake | PASS | Capture recovered and continued normally after the device entered an idle/sleep state and woke. |
| Oversized payload rejection (>128,000 UTF-16 chars) | PASS | The oversized clipboard payload was rejected as designed rather than persisted/truncated. |
| READY_EVENT vs READY_POLL | NOT DIRECTLY OBSERVED | Current Settings UI only exposes Shizuku Ready/Offline; it does not display the healthy bridge subtype. Functional capture is confirmed, so this is non-blocking for PR-04. |

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

Satisfied:

- [x] Samsung target device can bind and use the Shizuku clipboard bridge.
- [x] Real clipboard content is captured and persisted.
- [x] Capture disable/enable lifecycle behaves correctly.
- [x] Vault lock/unlock path behaves correctly.
- [x] Shizuku restart/recovery behaves correctly.
- [x] Screen lock/unlock recovery behaves correctly.
- [x] Doze/wake recovery behaves correctly.
- [x] Oversized clipboard payloads are rejected on the real device.
- [x] Samsung DeX clipboard capture works.
- [x] Secure Folder behavior is classified as an expected profile-isolation limitation.

Non-blocking diagnostic follow-up:

- [ ] Optional: expose or collect `READY_EVENT` / `READY_POLL` explicitly for future compatibility matrices.

## Decision

PR-04 passes on the tested Samsung Galaxy S23 Ultra / DeX path. No bridge-v3 blocker was found in the defined real-device compatibility gate.

The owner-profile bridge intentionally does not cross the Samsung Secure Folder profile boundary. This limitation does not block PR-05.

PR-04 may be closed and development may proceed to PR-05 QuickPasteActivity.
