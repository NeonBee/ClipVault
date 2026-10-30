# WP-07A — Secure Copy PoC (Android text-selection ingress)

**상태: PR #19 Draft — CI #97 verify/instrumentation PASS (코드 기준). Secure Copy 저장·클립보드 비간섭 실기기 PASS (관측 범위). 삼성 인터넷/Obsidian은 PROCESS_TEXT 직접 팝업에서는 미노출이지만 ACTION_SEND Android 공유 시트에서 Secure Copy 표시·동작 PASS. 추가 보안 에지 검증은 별도 (2026-10-30).**

[WP-07 설계](WP07_SECURE_TEXT_BOUNDARY.md)의 A 단계만 구현한다. B 단계의 IME / direct paste는 이 PR에 없다. Android 사용자가 앱 내부에서 텍스트를 길게 누르고 **텍스트 선택** → toolbar overflow의 **Secure Copy to ClipVault** 항목을 명시적으로 실행하거나, **공유** → **Secure Copy to ClipVault** 대상으로 명시적으로 보내는 경우에만 동작한다. 타사 앱이 컨텍스트 메뉴에 PROCESS_TEXT를 반드시 노출하는 것은 아니다.

## 범위

| 경로 | 현재 계약 |
| --- | --- |
| `ACTION_PROCESS_TEXT` / `text/plain` | **새 exported SecureCopyActivity**에서만 수신. 원래 MainActivity의 PROCESS_TEXT 필터/처리 제거 |
| `ACTION_SEND` / `text/plain` | **Secure Copy to ClipVault**로 명시된 별도 공유 대상만 동일 SecureCopyActivity로 수신. 기존 MainActivity의 일반 **ClipVault** 공유 대상과 자동 캡처 동작을 유지한다. 일반 대상을 선택하면 보안 직접 저장이 아니므로 혼동 금지 |
| Selection / share payload | `PROCESS_TEXT`는 `Intent.EXTRA_PROCESS_TEXT`, `SEND`는 `Intent.EXTRA_TEXT`의 CharSequence만 허용. `ACTION_SEND_MULTIPLE`, STREAM/URI, HTML, 누락/공백만/과도하게 큰 입력은 거절 (상한 128,000 UTF-16 코드 유닛). Intent 수신 단계에서 사용자가 선택한 공백·개행을 임의로 잘라내지 않음. **단, 실제 SQLCipher 저장 시 기존 `NativeClassifier.analyze()`의 정규화(양끝 공백 제거, 연속 개행 정리)가 적용됨** — 완전한 원문 바이트 동일성은 이번 PoC의 보장 범위 밖 |
| 인증 | 사용자가 'Save securely'를 누른 후, 잠긴 Vault는 기존 `BiometricVaultUnlock` + `CryptoObject`를 이용해 Strong biometric로 열어야 저장 가능. 새 Vault 등록은 메인 앱에서만 허용 |
| 저장 | `ClipVaultApp.saveExplicitSecureText()` → `VaultRepository.insert()` → SQLCipher; 동일 IO executor와 lock epoch 내 인증/잠금 상태 확인 |
| 실패/취소 | 저장 없이 종료 또는 오류. **일반 capture / PendingStore / ClipboardManager fallback 없음**. SHARE를 선택할 때도 사용자 확인 화면을 거쳐야 하며, 취소·인증 실패에서 기존 일반 공유 경로로 재전송하지 않는다 |
| 프라이버시 | 실제 선택한 텍스트를 UI/Toast/최근 앱 썸네일/저장 상태/로그/SharedPreferences에 표시·저장하지 않음. `FLAG_SECURE`, excludeFromRecents. 받은 Activity Intent 참조에서 payload 제거 |
| 기기 전환 | 인증 도중 앱이 백그라운드로 가면 정상 foreground 상태가 확인되지 않는 한 저장하지 않음. 일반 background/close에서는 텍스트 참조 폐기 |
| 분리 | 기존 MainActivity의 일반 `ACTION_SEND`와 기존 Shizuku clipboard capture는 현재 구현 유지. 별도로 등록한 Secure Copy 공유 대상만 보안 직접 저장 경로를 사용한다. **Secure Copy**는 사용자가 명시적으로 선택했기 때문에 일반 캡처의 자동 민감정보 스킵 규칙을 재사용하지 않음 |

주의: 일반 Android PROCESS_TEXT 호출은 외부 앱이 선택 텍스트를 ClipVault에 전달하는 **IPC**이므로 보내는 앱/OS는 그 텍스트를 이미 알고 있다. 수신 시 임시 String 객체가 RAM에 생성될 수 있고 JVM String 메모리를 확정적으로 0으로 지울 수 없다. `FLAG_SECURE`가 모든 vendor/OS의 비밀 유지를 보장하지 않는다. 타사 앱의 클립보드 접근·IME 기록까지 차단했다고 주장하지 않는다.

## 테스트 게이트

- [x] **CI unit**: 잠금 상태, lock epoch 변경, 정상 unlocked 상태에서 direct insert decision — [CI #95](https://github.com/NeonBee/ClipVault/actions/runs/40892641530) verify PASS.
- [x] **CI instrumentation**: PROCESS_TEXT / SEND exact multilingual/whitespace payload, missing/invalid/oversize/URI reject, PROCESS_TEXT → 전용 Activity *한 곳만* 등록, SEND → SecureCopyActivity + MainActivity **두 대상 구분** — [CI #95](https://github.com/NeonBee/ClipVault/actions/runs/40892641530) instrumentation PASS.
- [x] **Manual Samsung share (사용자 관측 범위)**: 삼성 인터넷 / Obsidian에서 텍스트 선택 → **Android 공유 시트** → **Secure Copy to ClipVault** 표시·저장 **PASS**. 기존 일반 **ClipVault** 대상과의 시트별 라벨 구분·기타 앱 범위 확대는 별도로 확인한다.
- [ ] **Manual auth**: 잠긴 Vault에서 Save → strong biometric success 후 선택 문자열만 저장되는지; 취소·오류·unprovisioned는 미저장 확인.
- [ ] **Manual no-clipboard**: **실기기 관측 범위 PASS** — Secure Copy 전후 system primary clip·Samsung Keyboard clipboard history 값 유지, 신규 Secure Copy 텍스트 미노출. 아직 Shizuku 캡처 이벤트 미발생의 독립 확인 및 모든 앱/경로에 대한 반복 검증은 미완료.
- [ ] **Manual edge**: 개행/한국어/URL/코드, 긴 텍스트, read-only selection, 키보드·DeX·폰 화면 및 취소/잠금 중단.
- [ ] **Security**: 앱 자체 `ClipboardManager.setPrimaryClip()` 호출 없음, 원래 캡처 정책과 분리, default permission 변화 없음.

**릴리스 금지 조건:** 위 Samsung clipboard non-interference 게이트를 통과하기 전까지 'Secure Copy bypasses the system clipboard'라는 사용자 대상 보안 주장을 완료로 표기하지 않는다. 다음 WP-07B Secure Paste는 별도 구현/리뷰다.

## Samsung DeX / One UI 8.0 selection-toolbar 실기기 관측 (2026-10-29)

**환경:** Galaxy S23 Ultra / One UI 8.0, WP-07A PR #19 debug APK, owner profile. 사용자 스크린샷 및 직접 조작 결과 기록.

| 진입점 | PROCESS_TEXT 선택 메뉴에 독립 'Secure Copy to ClipVault' 항목 | Secure Save 실행 및 Vault 저장 |
| --- | --- | --- |
| ChatGPT 앱 내부 텍스트 선택/우클릭 | **PASS** — 실제 메뉴에 독립 항목 표시 | **PASS** — 직접 선택 후 저장 성공 |
| Microsoft Edge에서 텍스트 선택 | **PASS (사용자 보고)** — Secure Copy 항목 표시 | **PASS (선택 이후 저장 기능 관측 범위)** |
| 삼성 메시지(Samsung Messages) 앱 | **PASS (사용자 보고)** — Secure Copy 항목 표시 | **PASS (선택 이후 저장 기능 관측 범위)** |
| 삼성 인터넷 텍스트 선택 | **FAIL** — 기본 텍스트 선택 팝업에 독립 PROCESS_TEXT 항목 미표시 | **PASS (별도 공유 시트)** — 공유 → Secure Copy to ClipVault 선택·저장 |
| Obsidian 텍스트 선택 | **FAIL** — 기본 텍스트 선택 팝업에 독립 PROCESS_TEXT 항목 미표시 | **PASS (별도 공유 시트)** — 공유 → Secure Copy to ClipVault 선택·저장 |
| 삼성 선택 메뉴의 **사전** → Android 앱 선택기 → ClipVault | 사전 경유로 ClipVault 표시 | **PASS** — 선택 텍스트가 Vault에 저장됨 |
| ADB `cmd package query-activities -a android.intent.action.PROCESS_TEXT -t text/plain` | **PASS** — `dev.clipvault.app.debug/dev.clipvault.app.securecopy.SecureCopyActivity` 검색됨 | 컴포넌트 직접 실행 확인 |

**One UI 앱 관리 검증:** 삼성 설정의 선택 메뉴 앱 관리 화면에 ClipVault가 표시되고 유일한 활성 앱으로 ON이지만, 삼성 인터넷/Obsidian 선택 메뉴의 독립 항목은 계속 미표시. ADB `settings get global process_text_manager_apps`에도 `dev.clipvault.app.securecopy.SecureCopyActivity#`가 반영됐지만 결과는 동일했다.

**해석:** Android가 PROCESS_TEXT를 해결할 수 있고 **적어도 ChatGPT 앱은 호출 가능한 선택 메뉴 항목을 구성**한다. 반면 삼성 인터넷/Obsidian에서의 UI 노출은 미지원/호출 앱별 필터링 또는 메뉴 구현 차이로 보인다. **One UI 전체가 PROCESS_TEXT를 차단한다거나, 삼성용 허용 목록만 수정하면 해결된다고 주장하지 않는다.** 정확한 메뉴 구현·패키지 가시성 원인은 아직 확정하지 않았다.

**추가 보안 실기기 관측 (2026-10-29 사용자 보고):** 기존 PROCESS_TEXT 및 최근 추가한 보안 저장 경로를 사용한 테스트에서 **시스템 클립보드 기존 값 유지**, **삼성 키보드 클립보드 기록 유지**, **Secure Copy 새 텍스트가 양쪽에 나타나지 않음** — **PASS (사용자가 수행한 테스트 범위)**. 공유/선택 경로별 전체 앱 매트릭스, 별도 Shizuku 캡처 이벤트 부재, 인증 취소·잠금 중단 등은 아직 완전히 검증되지 않았다. Android 전체 또는 모든 앱에서 비간섭이 보장된다는 뜻이 아니다.

**공유 호환 구현 (삼성 인터넷/Obsidian 실기기 PASS):** 두 앱에서는 기본 텍스트 선택 팝업에 **PROCESS_TEXT 독립 항목이 없지만**, **공유 → Android 공유 시트 → Secure Copy to ClipVault**가 정상 표시되고 인증·저장 및 기존 클립보드 비간섭 테스트도 기대대로 동작했다. 따라서 이 두 앱에서 **공유 시트가 검증된 호환 진입점**이다. PROCESS_TEXT 독립 팝업 미표시는 앱별 UX 제한으로만 남긴다. 기존 **공유 → ClipVault**는 MainActivity.capture()와 pending-store가 남아 있는 **일반 기록 경로**이다. 동일한 보안 기능으로 오인해서는 안 되며 공유 메뉴의 앱 표시 방식은 실기기에서 확인해야 한다.

**기능 검증과 보안 검증 분리:** 전용 Secure Copy Activity에서는 일반 clipboard로 fallback하지 않는다. 사용자가 확인한 경로의 저장·클립보드 비간섭은 PASS이나, 두 가지 진입점(PROCESS_TEXT, ACTION_SEND)이 각 앱에서 어떻게 표시되는지는 앱별로 다르다. 이번 결과만으로 제조사 전체 또는 모든 입력/공유 앱에서 동작을 보장하지 않는다.

### CI #95와 앱별 메뉴 노출의 분리 (2026-10-29)

- [CI #95](https://github.com/NeonBee/ClipVault/actions/runs/40892641530) 및 [CI #97](https://github.com/NeonBee/ClipVault/actions/runs/40897259392): **verify PASS + instrumentation PASS**. Manifest의 `ACTION_PROCESS_TEXT` 단일 수신자 및 `ACTION_SEND` 보안/일반 공유 두 수신자 테스트 포함.
- **Edge 및 삼성 메시지: PASS** — 텍스트 선택 흐름에서 Secure Copy 메뉴 표시. 앞서 확인한 ChatGPT 경로에서도 표시·저장 성공.
- **삼성 인터넷 및 Obsidian: PROCESS_TEXT 독립 팝업 노출 FAIL / Android ACTION_SEND 공유 시트 PASS** — One UI 앱 관리 허용 및 패키지 관리자 등록은 정상이며, 기본 텍스트 선택 팝업에는 독립 항목이 없지만 공유 시트에서는 Secure Copy 대상이 나타나고 저장된다. **공유 시트까지 미노출이라는 이전 기록을 정정**한다. 더 이상 공유 Intent 문제로 분류하지 않는다.
- 남은 문제를 **앱별 PROCESS_TEXT 선택 팝업 노출 제한**으로만 분류한다. 현재 로그만으로 삼성 인터넷과 Obsidian의 내부 코드 구현이 동일하거나 One UI가 전역적으로 차단한다고 확정하지 않는다. 임의의 시스템 설정 변경·추가 권한으로 우회하려 하지 않는다.

## Windows 데이터 보존형 설치

```powershell
git fetch origin
git switch feature/wp07a-secure-copy-poc
git pull --ff-only origin feature/wp07a-secure-copy-poc
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1
```

소중한 Vault가 설치된 스마트폰에 `connectedDebugAndroidTest`, `adb uninstall`, `pm clear`를 사용하지 않는다. 서명이 다른 CI APK를 기존 앱에 강제로 대체하지 않는다.
