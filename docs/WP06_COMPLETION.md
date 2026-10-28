# WP-06 / WP-06S / WP-06P-A — 완료 판정 및 검증 기준

> **최종 상태 (2026-10-28): Samsung Galaxy S23 Ultra (SM-S918N), Android 16 / One UI 8.0, Samsung DeX 경로에서 핵심 UX 구현 및 실기기 검증 완료.**
>
> 기준 브랜치: `hardening/base`. 이 문서는 *현재 상태의 source of truth*이며, 이전 PoC 문서의 시행착오·실패 CI·당시의 PENDING 표현은 **당시 시점의 기록**으로 해석한다. 이 판정은 다른 Android 기기/One UI 버전에서의 보편적 호환성 인증이나 완전한 보안 감사를 의미하지 않는다.

## 1. 범위 및 병합 증거

| 범위 | PR | 최종 판정 |
| --- | --- | --- |
| 잠금 사유 진단 (WP-06 단계 1) | [#12](https://github.com/NeonBee/ClipVault/pull/12) | 병합; 비민감 잠금 이유·최근 8건 표시 |
| Compact 라이브러리 및 최소 QuickPaste (WP-06 단계 2) | [#13](https://github.com/NeonBee/ClipVault/pull/13) | 병합; 이후 단계 3에서 디자인 보완 |
| Main/QuickPaste 최소 usable freeform 크기, compact 돋보기 (WP-06 단계 3) | [#15](https://github.com/NeonBee/ClipVault/pull/15) | 병합, 실기기 PASS |
| Samsung DeX 전역 앱 단축키 (WP-06S) | [#16](https://github.com/NeonBee/ClipVault/pull/16) | 병합 `edcf79c`, 실기기 PASS |
| 복사 후 창 유지 (WP-06P-A) | [#17](https://github.com/NeonBee/ClipVault/pull/17) | 병합 `81546b4`, 실기기 PASS, [CI #79](https://github.com/NeonBee/ClipVault/actions/runs/40662764365) **verify + instrumentation 모두 성공** |

관련 계획: [WP-06 원문](WP06_DEX_UX.md) · [WP-06S 실험 기록](WP06S_GLOBAL_SHORTCUT_POC.md) · [WP-06P-A 실험 기록](WP06P_PERSISTENT_QUICKPASTE_DESIGN.md).

## 2. 현재 구현 계약

1. **Main Vault:** freeform 최소 **320×384dp**. compact usable 높이에서는 빠른 검색이 사라지는 대신 좌상단 돋보기로 축약되고, 탭하면 검색 필드가 펼쳐지며 포커스를 받는다. 검색어가 있는 상태에서도 접을 수 있고 검색 상태는 유지된다.
2. **QuickPaste:** freeform 최소 **240×260dp**, 기본 레이아웃 **560×620dp**. Vault 잠금 상태라면 기존 `BIOMETRIC_STRONG` + `CryptoObject` 경계를 사용한다. 검색 → 방향키/클릭 → 명시적 복사. 붙여넣기는 사용자가 대상 앱에서 직접 수행하며 자동 주입/자동 paste가 없다.
3. **전역 DeX 단축키:** 기존 Main 런처를 유지하고, 사용자가 Settings에서 활성화한 단일 APK의 `QuickPasteLauncherAlias`에 `MAIN/LAUNCHER`와 **`MAIN/INFO`** 진입점을 제공한다. 삼성 앱 단축키가 실제로 QuickPaste를 호출하는 것을 타깃 기기에서 확인했다. Alias는 기본 `disabled`, `exported=true`이며 *실제* `QuickPasteActivity`는 `exported=false`이다. 이 entry는 열기 요청만 허용하고 clip 내용 제공/인증 우회를 허용하지 않는다.
4. **창 크기/수명주기:** QuickPaste가 최소화·백그라운드로 이동하면 일반적으로 Activity 작업을 바로 제거하지 않지만 `onStop()`에서 검색어·복호화된 검색 결과를 `viewModel.clear()`로 지운다. 다시 단축키로 호출할 때 **사용자 크기 복원이 타깃 실기기에서 PASS**. 완전히 닫은 뒤 재호출해도 크기가 복원된다는 사용자 관측이 있다. **크기 유지와 메모리 데이터 유지가 동일하다고 취급해서는 안 된다.**
5. **복사 후 창 유지:** `Keep open after copy` 옵션 **기본 OFF**. OFF에서는 클립보드에 복사하고 창을 닫는다. ON에서는 복사 후 창을 유지해 연속 검색/복사가 가능하다. 선택 결과는 기존 `SensitiveClipboard.write`를 사용하므로 **시스템 클립보드에 평문이 올라간다**. 옵션은 앱 내부에 Boolean만 저장하고 clip·query는 저장하지 않는다.
6. **Compact 토글:** 컨텐츠 너비 **≤480dp** 또는 높이 **≤420dp**이면 하단 칩 대신 검색창 오른쪽 `LockOpen`(OFF) / `Lock`(ON) 아이콘 버튼을 사용한다. 이 아이콘은 **Vault 잠금이 아니라 복사 후 창 유지**를 의미한다. 일반 창에서는 라벨이 있는 하단 `FilterChip`을 유지한다.
7. **안전 경계:** `FLAG_SECURE`, no-recents/thumbnail, screen-off/auto-lock, Vault 잠금 시 검색 상태 삭제, no `INTERNET`, no Accessibility, no overlay 권한을 유지한다. 앱이 일반 클립보드에 내보낸 데이터의 이후 수명은 ClipVault가 보장하지 않는다.

## 3. 실기기 검증 범위

| ID | 항목 | 결과 |
| --- | --- | --- |
| DEX-01 | Main compact 돋보기 전환, 검색어 유지, 필수 액션과 clip 카드 표시 | **PASS** (PR #15 기기 기록) |
| DEX-02 | QuickPaste 최소 크기 및 잠금 해제 버튼 접근 | **PASS** (WP-06 기기 기록) |
| DEX-03 | Main 아이콘 실행과 Samsung 물리 키보드 앱 단축키가 각각 Main/QuickPaste로 라우팅 | **PASS** (PR #16 사용자 검증) |
| DEX-04 | 단축키 alias OFF/ON 전환, 기존 일반 앱 열기 동작 회귀 없음 | **PASS** (PR #16 사용자 검증 범위) |
| DEX-05 | QuickPaste 최소화 후 호출 시 창 크기 유지 | **PASS** (PR #16 사용자 검증) |
| DEX-06 | QuickPaste 완전 종료 후 호출 시 창 크기 유지 | **PASS** (PR #16 사용자 검증) |
| SEC-01 | 위 QuickPaste 최소화/재호출 흐름에서 Vault 인증 우회 관측 없음 | **PASS (관측 범위)** |
| DEX-07 | 복사 후 창 유지 ON/OFF, 연속 복사, 기존 복사 후 닫기 | **PASS** (PR #17 사용자 검증) |
| DEX-08 | 최소 크기에서 우측 토글 표시·동작, 자물쇠 아이콘 | **PASS** (PR #17 사용자 검증) |
| CI-01 | PR #17 최종 단위·Lint·APK 확인 / Emulator 계측 테스트 | **PASS** ([#79](https://github.com/NeonBee/ClipVault/actions/runs/40662764365)) |

타깃 사용자 보고 및 해당 PR 기록에 근거한 판정이다. OS 내부의 삼성 단축키 라우팅 API 자체는 공개적으로 확인되지 않았으며, 한 기기에서의 성공을 Android 전체 동작으로 일반화하지 않는다.

## 4. 완료 범위 밖의 항목 / 다음 작업

- **세션 재인증 진단:** WP-05/06에서 QuickPaste → Main 전환 시 약 5초 후 예상보다 빠른 생체인증을 요구한 관측이 있었다. 잠금 사유 로깅은 구현되었지만 **해당 타이밍의 근본 원인이 확정·해결됐다고 기록하지 않는다**. 보수적/fail-closed 동작이므로 별도 비차단 과제로 추적한다.
- **반복 호출/중복 창·정확한 포커스 복귀, 재부팅·프로세스 소멸 후 크기 복원:** 정의된 모든 에지 케이스가 검증된 것은 아니다. 필수 사용자 플로우 성공과 구분한다.
- **Windows 원격제어 Ctrl/Cmd 호환성:** 사용자가 이번 병합의 필수 검증에서 **제외(SKIPPED)** 했다. 정상 동작이 증명된 것으로 표기하지 않는다. 물리 Windows/Right Ctrl 키 해석은 삼성 기기 설정과 키보드 펌웨어에 의존한다.
- **Always on Top / overlay:** 사용자가 현 단계에서 **명시적으로 범위 제외**했다. 일반 Activity가 다른 앱 위에 항상 남는다고 주장하지 않는다.
- **다른 제조사/One UI 빌드 호환성 및 별도 릴리스 보안 감사:** 아직 일반화하지 않는다.
- **WP-07 Secure Copy / Secure IME:** [별도 계획](WP07_SECURE_TEXT_BOUNDARY.md)으로 미구현. 일반 복사/QuickPaste는 시스템 클립보드 경로를 사용한다.
- **WP-08 StrongBox:** 선택적 보안 강화 계획. WP-06 완료 판정의 일부가 아니다.

## 5. 업데이트/검증 작업 시 주의

- 기존 Vault를 쓰는 기기에서는 동일한 디버그 서명을 유지하며 `scripts/deploy-debug.ps1` 또는 `adb install -r` 사용.
- **사용자 데이터가 담긴 실기기에서 `connectedDebugAndroidTest`, `adb uninstall`, `pm clear`를 사용하지 않는다.** 테스트는 CI의 임시 에뮬레이터에서 실행한다.
- 이 문서의 완료 상태와 충돌하는 이전 문서 내 `Draft`, `PENDING`, `before merge` 기록은 시점별 PoC 기록이다. 추가 구현 또는 새로운 미검증 발견이 생기면 해당 항목만 명확히 수정하고, 과거 오류 이력은 삭제하지 않는다.
