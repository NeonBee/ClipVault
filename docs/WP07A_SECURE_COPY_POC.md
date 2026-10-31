# WP-07A — Secure Copy PoC (Android text-selection ingress)

**상태: PR #19 실기기 2개 Secure Copy 진입점 모두 독립 PASS (2026-10-31). CI #113 verify/instrumentation SUCCESS (head `ec9941a`). CI #114 verify SUCCESS / instrumentation FAILURE (별도 로그 분석 필요). Galaxy S23 Ultra / One UI 8 실기기에서 인증·중단·시스템/삼성 클립보드 비간섭(사용자가 테스트한 범위) PASS. 일반 Copy 양성 대조군 3회에서 Shizuku 원시 콜백 +3회씩, distinct capture +1회씩 관측했고 Secure Copy 3회에서 두 카운터 모두 변화 없음: 관측 범위의 Shizuku 비간섭 PASS. 이후 추가 검증에서 `ACTION_PROCESS_TEXT`와 보안 `ACTION_SEND`도 각각 독립적으로 Vault 저장·Shizuku 이벤트 증가 0·distinct capture 증가 0·시스템/삼성 키보드 기록 유지 모두 PASS. 다른 ROM/시점·검증하지 않은 앱에 일반화하지 않음. 삼성 인터넷 PROCESS_TEXT 메뉴는 폰 모드의 … 확장에서는 표시되나 DeX에서는 미표시; Obsidian은 두 모드 모두 미표시. 두 앱의 Secure Copy 공유 시트 대체 경로는 앞선 실기기 PASS.**

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
- [x] **Manual auth (사용자 관측 범위)**: 잠긴 Vault에서 Save → strong biometric success 후 선택 문자열만 저장되는지; 취소·오류·unprovisioned는 미저장 확인.
- [x] **Manual no-clipboard (타깃 실기기 관측 범위)**: **PASS** — Secure Copy 전후 system primary clip·Samsung Keyboard clipboard history 값 유지, 신규 Secure Copy 텍스트 미노출. **추가 Shizuku 카운터 양성 대조군 PASS:** 일반 복사마다 listener +3 / distinct captures +1, Secure Copy 3회에서는 두 카운터 모두 변화 없음. 모든 앱·경로·기기에 대한 보장은 아님.
- [ ] **Manual edge**: 개행/한국어/URL/코드, 긴 텍스트, read-only selection, 키보드·DeX·폰 화면 및 취소/잠금 중단.
- [ ] **Security**: 앱 자체 `ClipboardManager.setPrimaryClip()` 호출 없음, 원래 캡처 정책과 분리, default permission 변화 없음.

**릴리스 문구 범위:** 타깃 실기기에서 `ACTION_PROCESS_TEXT` 및 보안 `ACTION_SEND` 각각의 Shizuku·시스템/삼성 키보드 클립보드 비간섭을 관측했으므로 이 검증 항목은 PASS다. 다만 모든 제조사/OS 및 모든 앱에서 비간섭을 보장한다는 식의 절대적 문구는 사용하지 않는다. 다음 WP-07B Secure Paste는 별도 구현/리뷰다.

## PR #19 병합 전 보안 강화 및 최종 게이트 (2026-10-30)

- **엄격한 입력 검증:** 명시적 plain-text 요청이라도 `EXTRA_STREAM`이나 `Intent.data` URI가 함께 존재하는 혼합 입력은 거절한다. 첨부파일을 저장하지 않았는데 전부 안전하게 저장한 것으로 오해하는 문제를 방지한다. 일반 텍스트 공유의 정상 동작은 유지한다.
- **취소·잠금 경합:** Activity의 요청 활성 플래그를 IO executor의 SQLCipher insert 직전, lock epoch 보호 구간에서 재검사한다. 현재 기기가 잠겼는지도 해당 시점에 확인한다. 이는 *쓰기 시작 전*의 취소를 차단하는 게이트이며, 이미 시작된 SQLCipher insert의 원자적 rollback을 보장하는 것은 아니다.
- **취소 UI:** `SAVING` 상태에서는 Cancel 버튼과 Back 취소를 제공하지 않는다. 확정된 Save 이후의 in-flight insert가 이미 완료됐을 수 있으므로 '취소 성공'이라고 오인시키지 않기 위함이다. 인증 중에는 기존 Cancel 동작 유지.
- **자동 테스트:** 추가 unit은 비활성 요청의 precommit 거절을 검사한다. 추가 instrumentation은 텍스트와 STREAM/URI가 섞인 Intent를 거절하는지 확인한다. CI 통과는 UI/Intent 코드 경로 검증이지 모든 보안 타이밍/실기기 결과의 대체가 아니다.
- **최신 이전 CI:** [#98](https://github.com/NeonBee/ClipVault/actions/runs/40999161835) verify/instrumentation SUCCESS (head `08cc4d6`). 최신 변경 커밋의 새 CI 결과를 별도로 확인한다.

**병합 시 고려할 남은 검증 범위:** 실기기에서 인증 성공/취소·백그라운드 중단·시스템/삼성/클립보드 및 Shizuku 카운터 비간섭은 관측 범위 PASS. 양쪽 진입점의 독립 비간섭 검증도 PASS. 다만 미등록 Vault, 모든 lock-epoch 경합 타이밍, 장문/모든 소스 앱 및 다른 ROM은 별도 검증이 필요하다. 원문 정규화(양끝 공백 제거·연속 개행 정리)는 현재 PoC의 알려진 한계로 유지한다.

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

## 2026-10-31 삼성 S23 Ultra 실기기 재검증 / 폼팩터 차이

사용자가 최신 WP-07A 실기기 구동에서 다음을 확인함. **검증 범위는 수행한 수동 케이스에 한정**하며 모든 장치·타이밍의 보안 보장을 의미하지 않음.

| 체크 | 실기기 관측 | 판정 |
| --- | --- | --- |
| 1. 인증 성공/취소/실패 시 저장 결과 | 예상대로 인증·중단 동작 | **PASS (사용자 관측)** |
| 2. Home/배경 전환/화면 꺼짐/잠금 중단 | 예상대로 저장 차단/중단 | **PASS (사용자 관측)** |
| 3. 시스템 primary clip + 삼성 키보드 히스토리 비간섭 | 기존 값 유지, Secure Copy 텍스트 기록 미출현 | **PASS (사용자 관측)** |
| 4. Shizuku bridge 이벤트 부재 | 일반 Copy 3회에 콜백 +3·캡처 +1씩 증가, Secure Copy 3회 중에는 두 카운터 변화 없음 | **PASS (이번 실기기 관측 범위)** |

**직접 PROCESS_TEXT 선택 메뉴 노출(중요한 정정):**

| 앱 | 스마트폰 화면 | Samsung DeX 화면 | 대체 진입점 |
| --- | --- | --- | --- |
| Samsung Internet | 텍스트 선택 → **…(더보기)** → **Secure Copy 표시** | 텍스트 선택 메뉴에서 **미표시** | 이전 실기기 검증에서 Android 공유 시트 **PASS** |
| Obsidian | **미표시** | **미표시** | 이전 실기기 검증에서 Android 공유 시트 **PASS** |

삼성 인터넷은 Android PROCESS_TEXT 자체를 전역 차단하는 앱이 **아니다**. 동일 앱의 화면 모드별 외부 액션 표시 차이까지 확인됨. 원인이 One UI의 DeX 메뉴 정책인지, 앱의 선택 메뉴 구현인지, 오버플로우/메뉴 제한인지는 미확정. Obsidian은 두 모드 모두 직접 팝업에서 미표시. **Android 공유 시트의 별도 Secure Copy 대상 기능과 직접 선택 메뉴 노출을 혼동하지 않는다.**

### 2026-10-31 Shizuku 이벤트 카운터 양성 대조군 검증 결과

**실기기 사용자가 직접 보고한 누적 카운터** (테스트 대상 Galaxy S23 Ultra / One UI 8.0, 최신 디버그 진단):

| 실험 | `debug_bridge_event_count` | `debug_capture_accept_count` |
| --- | --- | --- |
| 일반 Copy, 첫 측정 | 3 | 3 |
| 일반 Copy, 두 번째 측정 | 6 (**+3**) | 4 (**+1**) |
| 일반 Copy, 세 번째 측정 | 9 (**+3**) | 5 (**+1**) |
| 별도 Secure Copy 3회 | **변동 없음** | **변동 없음** |

해석: **양성 대조군**(일반 Copy)에 두 측정기가 반복적으로 반응했으며, 이후 Secure Copy를 세 번 시도하는 동안에는 추가 Shizuku 콜백·distinct capture 관측이 없었다. 따라서 **해당 실기기·실험 시간/경로의 clipboard-event 비간섭: PASS (사용자 관측)**. 단, Secure Copy 3회의 앱별 `PROCESS_TEXT` / `ACTION_SEND` 개별 구성은 기록되지 않았으므로 두 진입점 모두에 대한 독립 3회 검증으로 일반화하지 않는다.

원시 리스너 카운터는 OS의 변경 콜백 **호출 횟수**이고 distinct 카운터는 `ClipboardCaptureCoordinator.accept()` 중복 제거 후 캡처 전달 횟수다. `+3/+1`은 한 번의 복사에 여러 콜백이 온다는 관측과 부합하지만, OS 알림 중복·리스너 등록 등 구체적인 3배 원인은 확정하지 않았다. 이 결과만으로 *모든* 장치·ROM에서 clipboard를 경유하지 않는다고 입증하거나 OS의 일시적 상태를 보장하지 않는다.

**CI 상태:** [#113](https://github.com/NeonBee/ClipVault/actions/runs/41170793653)에서 `verify` 및 `instrumentation` 모두 SUCCESS (커밋 `ec9941a`). 이후 [#114](https://github.com/NeonBee/ClipVault/actions/runs/41171281150)에서는 verify SUCCESS / instrumentation FAILURE가 관측됨. 이후 커밋의 CI 결과는 별도로 확인한다.

### 2026-10-31 PROCESS_TEXT / 보안 ACTION_SEND 독립 진입점 검증 — 최종 추가 기록

**사용자가 실제 기기에서 각각의 진입점으로 수행해 보고한 결과** (Galaxy S23 Ultra / One UI 8.0, WP-07A 디버그 빌드). 아래 두 경로는 기존의 "Secure Copy 3회 (경로 미구분)" 기록과 달리 명시적으로 구분됨.

| 검증 항목 | `ACTION_PROCESS_TEXT` 선택 메뉴 직접 Secure Copy | 보안 `ACTION_SEND` 공유 시트 Secure Copy |
| --- | --- | --- |
| SQLCipher Vault에서 선택 문자열 저장 확인 | **PASS** | **PASS** |
| `debug_bridge_event_count` 증가량 | **0 — PASS** | **0 — PASS** |
| `debug_capture_accept_count` 증가량 | **0 — PASS** | **0 — PASS** |
| 기존 Android system primary clipboard 유지 | **PASS** | **PASS** |
| 기존 Samsung Keyboard clipboard history 유지 | **PASS** | **PASS** |
| 경로별 실기기 결론 | **PASS (관측 범위)** | **PASS (관측 범위)** |

실험에 사용한 **개별 소스 앱**, 실행별 **절대 카운터 시작/종료값**, 디바이스의 정밀 타임스탬프는 이번 사용자 보고에 없으므로 추측하여 기록하지 않는다. 다만 2개 경로를 각각 확인했다는 사실 및 경로별 각 체크의 PASS는 명시적으로 제공됨.

앞서 별도 실험에서 일반 Copy를 반복할 때 raw listener events가 3→6→9, distinct captures가 3→4→5로 증가했으며, 이번 경로별 테스트에서는 Secure Copy가 두 계측값을 변화시키지 않았다. 이 양성 대조군은 해당 실기기의 수집 기능이 작동했음을 뒷받침한다. 다만 시간적으로 완전히 동일한 제어 실험이라고 주장하거나 모든 OS·IME·앱의 클립보드 동작까지 보증하지 않는다.

**검증 판정:** WP-07A의 두 가지 보안 진입 경로에 대한 실기기 클립보드 비간섭 체크 **완료 (PASS, 대상 기기 및 테스트한 경로 한정)**. 일반 `ACTION_SEND`의 별도 `ClipVault` 대상은 일반 캡처 경로이므로 결과에 포함되지 않는다.

### 진단에 'debug capture 미실행 또는 구버전'이 표시될 때

2026-10-31에 확인한 초기화 버그: 카운터는 기존 구현에서 **첫 이벤트 전에는 prefs에 기록되지 않아서 0과 미활성 상태를 구분하지 못했음**. 이제 디버그 `ClipboardCaptureService.onCreate()`에서 두 카운터를 즉시 `0`으로 초기화하며, 기존 누적 값은 보존한다.

Windows PowerShell에서 최신 브랜치로 `git pull --ff-only` 후 `scripts/deploy-debug.ps1`로 **덮어 설치**한다. Shizuku 앱에서 서버 실행 상태·ClipVault 권한 확인, ClipVault Main의 **Capture** 토글을 **OFF → ON**하여 서비스를 재생성한다. `scripts/device-validation-record.ps1` 실행 시, 새 APK/서비스가 실행됐으면 `Debug Shizuku listener events | 0` 및 `Debug distinct clipboard captures | 0` 또는 기존 누적 숫자가 표시된다. Bridge mode가 `READY_EVENT` 또는 `READY_POLL`인지 확인한다.

카운터 숫자가 나와도 **일반 복사 컨트롤 테스트에서 실제 카운터 증가를 검증해야** Secure Copy 이벤트 부재의 관측으로 해석할 수 있다. `READY_POLL`은 listener 이벤트 수가 증가하지 않을 수 있으므로 capture counter 중심으로 확인한다. 실기기에서 전체 Android instrumentation, uninstall, clear-data를 실행하지 않는다.

### Shizuku clipboard-event 비간섭 계측 방법 (디버그 APK 전용)

PR #19 후속 커밋의 `ClipboardCaptureService`는 **콘텐츠·해시·길이·시각 없이** 두 누적 숫자만 앱 private prefs에 쓴다.

- `debug_bridge_event_count`: Shizuku Binder 리스너가 전달한 callback 수. `READY_EVENT`에서는 핵심 지표이며, `READY_POLL` 모드에서는 0인 것이 정상일 수 있다.
- `debug_capture_accept_count`: 로컬 리스너·Shizuku 이벤트·Shizuku 폴링을 합쳐 중복 제거 후 capture()에 전달된 변경 텍스트 건수. **실제 SQLCipher 저장 건수나 이벤트 내용의 증거는 아님**.
- `bridge_state`: `READY_EVENT` / `READY_POLL` / DEGRADED 등. 모드를 먼저 확인할 것.

기존 데이터·서명을 유지하는 `scripts/deploy-debug.ps1`로 **해당 변경을 포함한 로컬 디버그 APK를 덮어 설치**한 뒤, 캡처를 ON 하고 다음 명령을 실행한다(Windows PowerShell).

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\device-validation-record.ps1 -OutFile .\wp07a-before.md
# 1) 일반 Copy로 'WP07A-control-<임의 숫자>' 같은 무해하고 고유한 문자열을 복사한다.
# 2) Shizuku bridge/capture counter가 증가하는지 확인한다. 증가하지 않으면
#    이벤트 관측 장치가 준비되지 않았으므로 Secure Copy 무변화는 PASS가 아니다.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\device-validation-record.ps1 -OutFile .\wp07a-control.md
# 3) 무해한 *다른* 문자열을 Secure Copy 저장한다. 일반 Copy를 섞지 않는다.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\device-validation-record.ps1 -OutFile .\wp07a-secure.md
```

각 파일의 `Bridge mode`, `Debug Shizuku listener events`, `Debug distinct clipboard captures` 수치와 증감 비교.
`READY_EVENT`에서 일반 복사 시 이벤트 카운터가 증가하고 Secure Copy 동안 증가하지 않으면 **테스트 시간 동안 별도 Shizuku callback이 없었다는 관측**을 남길 수 있다. `READY_POLL`에서는 listener count가 의미 없고, 일반 Copy로 `capture_accept` 증가를 확인한 다음 Secure Copy 중 수치가 변하지 않는지 본다. 이때도 **변경된 클립보드 값이 중복 제거되었거나 폴링 간격 사이 잠시 생겼다가 지워졌을 가능성**이 있으므로, 이벤트 전부의 부재를 확정하지 않는다. 카운터가 증가하면 다른 앱 이벤트/기존 clipboard 변화 여부를 재점검하고 민감정보를 logcat에 노출하지 말 것.

**금지:** 실기기에 `connectedDebugAndroidTest`, `adb uninstall`, `pm clear`를 실행하지 않는다. 일반 ClipVault 공유 대상은 별도 일반 capture 경로이므로 통제 실험에서 사용하지 않는다.

## Windows 데이터 보존형 설치

```powershell
git fetch origin
git switch feature/wp07a-secure-copy-poc
git pull --ff-only origin feature/wp07a-secure-copy-poc
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1
```

소중한 Vault가 설치된 스마트폰에 `connectedDebugAndroidTest`, `adb uninstall`, `pm clear`를 사용하지 않는다. 서명이 다른 CI APK를 기존 앱에 강제로 대체하지 않는다.
