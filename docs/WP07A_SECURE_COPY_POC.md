# WP-07A — Secure Copy PoC (Android text-selection ingress)

**상태: implementation PR / CI + Samsung 실기기 검증 전 (2026-10-29).**

[WP-07 설계](WP07_SECURE_TEXT_BOUNDARY.md)의 A 단계만 구현한다. B 단계의 IME / direct paste는 이 PR에 없다. Android 사용자가 앱 내부에서 텍스트를 길게 누르고 **텍스트 선택** → toolbar overflow의 **Secure Copy to ClipVault** 항목을 명시적으로 실행할 때만 동작한다. 타사 앱이 컨텍스트 메뉴에 PROCESS_TEXT를 반드시 노출하는 것은 아니다.

## 범위

| 경로 | 현재 계약 |
| --- | --- |
| `ACTION_PROCESS_TEXT` / `text/plain` | **새 exported SecureCopyActivity**에서만 수신. 원래 MainActivity의 PROCESS_TEXT 필터/처리 제거 |
| Selection payload | `Intent.EXTRA_PROCESS_TEXT` 문자열만 허용; 공백만 있거나 누락/지나치게 큰 입력은 거절 (상한 128,000 UTF-16 코드 유닛). Intent 수신 단계에서 사용자가 선택한 공백·개행을 임의로 잘라내지 않음. **단, 실제 SQLCipher 저장 시 기존 `NativeClassifier.analyze()`의 정규화(양끝 공백 제거, 연속 개행 정리)가 적용됨** — 완전한 원문 바이트 동일성은 이번 PoC의 보장 범위 밖 |
| 인증 | 사용자가 'Save securely'를 누른 후, 잠긴 Vault는 기존 `BiometricVaultUnlock` + `CryptoObject`를 이용해 Strong biometric로 열어야 저장 가능. 새 Vault 등록은 메인 앱에서만 허용 |
| 저장 | `ClipVaultApp.saveExplicitSecureText()` → `VaultRepository.insert()` → SQLCipher; 동일 IO executor와 lock epoch 내 인증/잠금 상태 확인 |
| 실패/취소 | 저장 없이 종료 또는 오류. **일반 capture / PendingStore / ClipboardManager / ACTION_SEND fallback 없음** |
| 프라이버시 | 실제 선택한 텍스트를 UI/Toast/최근 앱 썸네일/저장 상태/로그/SharedPreferences에 표시·저장하지 않음. `FLAG_SECURE`, excludeFromRecents. 받은 Activity Intent 참조에서 payload 제거 |
| 기기 전환 | 인증 도중 앱이 백그라운드로 가면 정상 foreground 상태가 확인되지 않는 한 저장하지 않음. 일반 background/close에서는 텍스트 참조 폐기 |
| 분리 | 일반 `ACTION_SEND`와 기존 Shizuku clipboard capture는 현재 구현 유지. **Secure Copy**는 사용자가 명시적으로 선택했기 때문에 일반 캡처의 자동 민감정보 스킵 규칙을 재사용하지 않음 |

주의: 일반 Android PROCESS_TEXT 호출은 외부 앱이 선택 텍스트를 ClipVault에 전달하는 **IPC**이므로 보내는 앱/OS는 그 텍스트를 이미 알고 있다. 수신 시 임시 String 객체가 RAM에 생성될 수 있고 JVM String 메모리를 확정적으로 0으로 지울 수 없다. `FLAG_SECURE`가 모든 vendor/OS의 비밀 유지를 보장하지 않는다. 타사 앱의 클립보드 접근·IME 기록까지 차단했다고 주장하지 않는다.

## 테스트 게이트

- [ ] **CI unit**: 잠금 상태, lock epoch 변경, 정상 unlocked 상태에서 direct insert decision.
- [ ] **CI instrumentation**: exact multilingual/whitespace payload, missing/invalid/oversize reject, PROCESS_TEXT → 전용 Activity *한 곳만* 등록.
- [ ] **Manual Samsung**: 삼성 노트 / 삼성 인터넷 / Chrome / Gmail / Discord / Telegram / Termux 중 해당 앱에서 텍스트 선택/overflow 메뉴 사용 가능 여부를 따로 기록.
- [ ] **Manual auth**: 잠긴 Vault에서 Save → strong biometric success 후 선택 문자열만 저장되는지; 취소·오류·unprovisioned는 미저장 확인.
- [ ] **Manual no-clipboard**: Secure Copy 전후 system primary clip 동일, Samsung Keyboard clipboard history에 새 항목 미생성, Shizuku capture 이벤트 미발생 확인.
- [ ] **Manual edge**: 개행/한국어/URL/코드, 긴 텍스트, read-only selection, 키보드·DeX·폰 화면 및 취소/잠금 중단.
- [ ] **Security**: 앱 자체 `ClipboardManager.setPrimaryClip()` 호출 없음, 원래 캡처 정책과 분리, default permission 변화 없음.

**릴리스 금지 조건:** 위 Samsung clipboard non-interference 게이트를 통과하기 전까지 'Secure Copy bypasses the system clipboard'라는 사용자 대상 보안 주장을 완료로 표기하지 않는다. 다음 WP-07B Secure Paste는 별도 구현/리뷰다.

## Samsung DeX / One UI 8.0 selection-toolbar 실기기 관측 (2026-10-29)

**환경:** Galaxy S23 Ultra / One UI 8.0, WP-07A PR #19 debug APK, owner profile. 사용자 스크린샷 및 직접 조작 결과 기록.

| 진입점 | PROCESS_TEXT 선택 메뉴에 독립 'Secure Copy to ClipVault' 항목 | Secure Save 실행 및 Vault 저장 |
| --- | --- | --- |
| ChatGPT 앱 내부 텍스트 선택/우클릭 | **PASS** — 실제 메뉴에 독립 항목 표시 | **PASS** — 직접 선택 후 저장 성공 |
| 삼성 인터넷 텍스트 선택 | **FAIL** — 일반 메뉴에 독립 항목 미표시 | 해당 메뉴에서 직접 테스트 불가 |
| Obsidian 텍스트 선택 | **FAIL** — 일반 메뉴에 독립 항목 미표시 | 해당 메뉴에서 직접 테스트 불가 |
| 삼성 선택 메뉴의 **사전** → Android 앱 선택기 → ClipVault | 사전 경유로 ClipVault 표시 | **PASS** — 선택 텍스트가 Vault에 저장됨 |
| ADB `cmd package query-activities -a android.intent.action.PROCESS_TEXT -t text/plain` | **PASS** — `dev.clipvault.app.debug/dev.clipvault.app.securecopy.SecureCopyActivity` 검색됨 | 컴포넌트 직접 실행 확인 |

**One UI 앱 관리 검증:** 삼성 설정의 선택 메뉴 앱 관리 화면에 ClipVault가 표시되고 유일한 활성 앱으로 ON이지만, 삼성 인터넷/Obsidian 선택 메뉴의 독립 항목은 계속 미표시. ADB `settings get global process_text_manager_apps`에도 `dev.clipvault.app.securecopy.SecureCopyActivity#`가 반영됐지만 결과는 동일했다.

**해석:** Android가 PROCESS_TEXT를 해결할 수 있고 **적어도 ChatGPT 앱은 호출 가능한 선택 메뉴 항목을 구성**한다. 반면 삼성 인터넷/Obsidian에서의 UI 노출은 미지원/호출 앱별 필터링 또는 메뉴 구현 차이로 보인다. **One UI 전체가 PROCESS_TEXT를 차단한다거나, 삼성용 허용 목록만 수정하면 해결된다고 주장하지 않는다.** 정확한 메뉴 구현·패키지 가시성 원인은 아직 확정하지 않았다.

**기능 검증과 보안 검증 분리:** ClipboardManager에 새 clip이 올라가지 않았는지, 삼성 키보드 히스토리에 잔류하는지, 잠금·취소·에지 케이스가 fail-closed인지 각각 별도로 검증해야 한다. **일반 ACTION_SEND는 지금도 일반 capture/pending 경로이므로 아직 Secure Copy 호환 fallback이 아니다.**

## Windows 데이터 보존형 설치

```powershell
git fetch origin
git switch feature/wp07a-secure-copy-poc
git pull --ff-only origin feature/wp07a-secure-copy-poc
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1
```

소중한 Vault가 설치된 스마트폰에 `connectedDebugAndroidTest`, `adb uninstall`, `pm clear`를 사용하지 않는다. 서명이 다른 CI APK를 기존 앱에 강제로 대체하지 않는다.
