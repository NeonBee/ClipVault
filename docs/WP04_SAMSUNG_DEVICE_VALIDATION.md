# WP-04 — Samsung / DeX Device Compatibility Validation

> Terminology: `WP-04` is the roadmap Work Package. GitHub pull requests are referenced separately as `PR #N`.

Validation date: 2026-09-27  
Target branch baseline: `hardening/base`  
Target device: Samsung Galaxy S23 Ultra  
Desktop mode: Samsung DeX  
Android / One UI version: not recorded in this validation session

## Scope

WP-04 validates the protocol-v3 Shizuku clipboard bridge on the Samsung target device before starting WP-05 QuickPasteActivity.

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
| READY_EVENT vs READY_POLL | PENDING RECORD | The original session could not observe it because the Settings UI only showed Shizuku Ready/Offline. Diagnostics now show **Bridge mode**; the value is recorded below after re-collection on the device. Functional capture is confirmed, so this is non-blocking for WP-04. |

## Device record

Status: **PENDING** — the two values below were not recorded in the original session and must be
collected from the same Galaxy S23 Ultra. They matter because the exact hidden-`IClipboard` signature
table differs by API level (API 34+ uses the 4-argument `getPrimaryClip`), and the design's WP-04
checklist separates the event listener from the polling fallback.

Collect with the device connected over adb, the debug build installed and capture enabled:

```sh
scripts/device-validation-record.sh --run-test
```

The script prints a markdown table (model, Android/API, One UI, build, security patch, Shizuku server
user, bridge mode, last sanitized error, real-device test result as PASS / SKIPPED / FAIL from the
connected-test XML, so a run where every test was skipped by `Assume` is not reported as PASS). It
reads no clipboard content.
Replace this section's table with its output. For a release build, read **Bridge mode** and
**Android build** from Settings > Diagnostics instead.

| Item | Value |
| --- | --- |
| Model | Galaxy S23 Ultra |
| Android (API) | PENDING |
| One UI | PENDING |
| Build | PENDING |
| Bridge mode | PENDING (`READY_EVENT` expected when the listener signature resolves; `READY_POLL:<reason>` means polling fallback) |

## Secure Folder boundary

Samsung Secure Folder runs in an isolated profile/container. The owner-profile ClipVault instance does not attempt to cross that profile boundary.

For this fork, the expected behavior is therefore:

```text
Owner profile clipboard
  -> owner-profile ClipVault capture

Secure Folder clipboard
  -X-> owner-profile ClipVault
```

Cross-profile clipboard capture is out of scope for WP-04. If Secure Folder support is ever desired, it should be treated as a separate deployment/compatibility problem rather than widening the current bridge privileges.

## WP-04 exit criteria

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

- [x] Expose `READY_EVENT` / `READY_POLL` explicitly: Settings > Diagnostics shows **Bridge mode** and **Android build**.
- [ ] Record the Android / One UI version the validation ran on (see "Device record").
- [ ] Record the observed bridge mode (see "Device record").

## Decision

WP-04 passes on the tested Samsung Galaxy S23 Ultra / DeX path. No bridge-v3 blocker was found in the defined real-device compatibility gate.

The owner-profile bridge intentionally does not cross the Samsung Secure Folder profile boundary. This limitation does not block WP-05.

WP-04 may be closed and development may proceed to WP-05 QuickPasteActivity.
