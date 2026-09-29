# WP-06 DeX UX

용어:
- `PR #N`은 GitHub Pull Request 번호를 의미한다.
- `WP-NN`은 설계 Work Package 번호를 의미한다(`DEX_FORK_AUDIT_DESIGN.md` §21 참조).

## 범위

설계 §21의 WP-06 범위는 다음과 같다.

- 자유 창(freeform) 크기
- 포커스 처리
- 작업표시줄 / 알림 / Quick Settings 진입점
- DeX 밀도(density)

WP-05 실기기 검증(`WP05_QUICK_PASTE.md`의 Known behaviour)에서 다음 두 항목이 이관되었다.

1. QuickPaste 자유 창의 최소/기본 크기(360×400dp / 560×620dp)가 목표 UX보다 크다.
2. QuickPaste에서 잠금을 해제한 뒤 창을 열어둔 상태로 약 5초 이상 기다렸다가 MainActivity로 전환하면, 설정된 background auto-lock은 30초임에도 다시 생체 인증을 요구한다.

## 1단계 — 잠금 사유 진단

2번 현상은 fail-closed 방향의 문제이므로 원인을 추측해서 정책을 바꾸지 않는다. 먼저 열려 있던 vault가 잠기는 모든 경로에 사유 코드를 기록한다.

| 사유 코드 | 발생 조건 |
| --- | --- |
| `AUTO_LOCK_TIMEOUT` | 프로세스 공통 auto-lock 타이머가 만료됨. 시작된 ClipVault Activity가 하나도 없을 때만 타이머가 동작한다. |
| `SCREEN_OFF` | `ACTION_SCREEN_OFF` 수신. |
| `DEVICE_LOCKED_MAIN` | `MainActivity.onResume`에서 `KeyguardManager.isDeviceLocked()`가 true로 확인됨. |
| `DEVICE_LOCKED_QUICK_PASTE` | `QuickPasteActivity.onStart`에서 `isDeviceLocked()`가 true로 확인됨. |
| `NOTIFICATION` | 알림의 "Lock now" 동작으로 잠금. |
| `USER` | 앱 내부 잠금 버튼으로 잠금. |
| `PROCESS_RESTART` | vault가 열린 상태에서 이전 프로세스가 종료됨(kill, crash, reboot). 이 경우 `lockVault()`가 실행되지 않으므로 unlock 시 기록한 open marker가 다음 프로세스 시작 때 남아 있는지를 검사해 기록한다. |
| `OTHER` | 그 외 호출 경로(주로 테스트). |

각 이벤트에는 다음 정보만 저장한다.

- 잠금 사유
- 발생 시각
- 현재 프로세스에서 unlock된 뒤 경과 시간
- 시작된 ClipVault Activity 수
- `PowerManager.isInteractive()`
- `isKeyguardLocked`
- `isDeviceLocked`
- Samsung DeX desktop mode 여부(`Configuration.semDesktopModeEnabled` reflection, 진단 전용)

`PowerManager.isInteractive()`는 **기기 전원/상호작용 상태**이며 특정 디스플레이의 물리적 on/off 상태를 의미하지 않는다. DeX에서는 외부 디스플레이가 동작하는 동안 휴대폰 패널이 꺼져 있을 수 있으므로 해석에 주의한다.

클립보드 내용, 검색어, key material은 저장하지 않는다. 최근 8건만 app preferences의 `lock_log`에 최신순으로 유지한다. 이미 잠긴 상태에서 반복해서 들어오는 keyguard/lock 호출은 새 이벤트로 기록하지 않는다.

### 확인 위치

- 설정 > 진단 > `Lock MM-dd HH:mm:ss` 행
  - 설정 화면에 들어가려면 먼저 vault를 unlock해야 한다.
  - lock log 자체는 vault가 잠겨도 유지된다.
- `scripts/device-validation-record.ps1`
  - debug build에서 `run-as`를 이용해 읽기 전용으로 `Lock log` 표를 출력한다.

## WP-04 실기기 재현 절차 — SM-S918N / Samsung DeX

기존 데이터를 보존하기 위해 빌드는 반드시 제자리 설치한다.

```powershell
adb install -r <apk>
```

**uninstall 또는 app data clear는 하지 않는다.**

그 다음 아래 순서로 재현한다.

1. DeX에서 작업표시줄의 Quick paste 바로가기로 QuickPaste를 열고 생체 인증으로 unlock한다.
2. QuickPaste 창을 그대로 열어둔 채 약 10초 기다린다.
   - 관찰된 약 5초 경계보다는 길고,
   - 설정된 30초 background auto-lock보다는 짧은 시간이다.
3. ClipVault MainActivity를 연다.
4. 다시 생체 인증을 요구하면 인증을 완료한다.
5. 아래 스크립트를 실행한다.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\device-validation-record.ps1
```

6. 출력된 `Lock log` 표의 가장 위 행을 확인한다. 또는 설정 > 진단에서 가장 최근 `Lock` 행을 확인한다.
7. 해당 행에 `DeX` 플래그가 표시되는지도 확인한다.
   - DeX 사용 중인데 표시되지 않는다면 현재 One UI build에서 해당 reflection을 사용할 수 없는 것으로 본다.

## 최신 Lock log 해석

| 가장 최근 기록 | 의미 / 다음 조치 |
| --- | --- |
| `DEVICE_LOCKED_MAIN` + `device locked` | DeX 사용 중인데도 keyguard가 기기를 locked로 판정한 경우다. 현재 fail-closed 정책을 유지할지, Activity가 올라간 display를 고려하도록 범위를 좁힐지 결정해야 한다. 단, DeX mode 자체를 인증 요소로 사용해서는 안 된다(§20.10). |
| `DEVICE_LOCKED_QUICK_PASTE` + `device locked` | QuickPaste 시작 시점에 keyguard가 기기를 locked로 판정했다. MainActivity 경로와 동일하게 DeX/keyguard 의미론을 확인한다. |
| `SCREEN_OFF` | `ACTION_SCREEN_OFF`로 vault가 잠겼다. DeX에서 휴대폰 패널 상태 변화가 이 broadcast를 발생시키는지 확인한다. 화면 꺼짐 잠금 정책을 변경하려면 별도 보안 결정을 거쳐야 한다. |
| `AUTO_LOCK_TIMEOUT` + `windows 0` | 화면상 창이 남아 있는 것처럼 보였지만 Android lifecycle상 모든 ClipVault Activity가 stopped 상태가 되어 auto-lock 타이머가 동작한 경우다. Activity counting 또는 QuickPaste의 stop/close 동작을 조정한다. |
| `PROCESS_RESTART` | vault가 열린 상태에서 앱 프로세스가 종료됐다. low-memory kill 또는 crash 여부를 `adb logcat -b crash` 등으로 확인한다. |
| 새 기록 없음 | vault 자체는 잠기지 않았는데 biometric prompt가 다시 나타난 경우다. MainActivity/QuickPaste의 UI state 또는 BiometricPrompt 시작 조건을 별도로 추적한다. |

## 이 단계의 완료 조건

1단계의 목적은 정책 수정이 아니라 **약 5초 재인증 현상의 실제 잠금 경로를 확정하는 것**이다.

실기기 재현 결과에서 최신 lock reason을 확보한 뒤 다음 WP-06 작업으로 넘어간다.

- QuickPaste 최소/기본 창 크기 및 DeX density 조정
- 포커스 동작 조정
- 알림 / Quick Settings / 작업표시줄 진입점
- 확인된 원인에 따른 session continuity / lock 정책 조정

## 2단계 — compact 높이 레이아웃과 QuickPaste 최소 창 크기

### 메인 vault 라이브러리

- 라이브러리 화면이 실제로 받는 높이가 **420dp 미만**이면 inline 검색창을 숨긴다. 상단 바, 검색창, 필터 칩이 약 180dp 를 차지하므로, 이 기준 아래에서는 목록 높이가 240dp 미만으로 줄어든다.
- 검색은 TopAppBar 의 Advanced Search(조정 아이콘)로 계속 할 수 있다. 같은 검색어(`query.search`)를 편집한다.
- 예외: 검색어가 입력돼 있으면 좁아도 검색창을 유지한다. 필터가 걸린 상태가 보이지 않으면 결과가 왜 줄었는지 알 수 없고 지울 방법도 없기 때문이다.
- 판정은 `LibraryLayout.showInlineSearch` 한 곳에서 하며, unit test 와 compose test(`LibraryCompactHeightComposeTest`)로 확인한다.

### QuickPaste 최소 창 크기

- manifest `<layout>` 최소 크기를 360×400dp → **280×260dp** 로 줄였다. 기본 크기 560×620dp 는 그대로 둔다. Compose 쪽에는 별도 최소 크기 제약이 없으므로 manifest 값이 유일한 하한이다.
- 잠금·설정 화면은 상단 여백을 48dp → 16dp 로 줄이고 세로 스크롤을 줘서, 260dp 높이에서도 버튼이 잘리지 않는다.
- `QuickPasteCompactLayoutTest` 가 최소 크기에서 다음을 확인한다(2단계 시점에는 280×260dp, 3단계에서 240×260dp 로 변경).
  - 검색 필드 포커스, 첫 행, 키 안내 표시
  - ↓ 로 보이는 범위 밖의 행을 선택하면 목록이 스크롤됨
  - Enter 복사, Esc 닫기
  - 잠금 화면 Unlock 버튼 도달

### 실기기 확인 (SM-S918N, DeX)

`adb install -r` 로 덮어 설치한다(삭제 금지).

| 확인 | 기대 결과 | 결과 |
| --- | --- | --- |
| QuickPaste 창을 가장 작게 줄이기 | 약 280×260dp 에서 멈춤 | PASS (2026-09-29) |
| 최소 크기에서 입력, ↑/↓, Enter | 검색 필드·목록·키 안내가 보이고, 선택 행이 보이는 범위로 스크롤, Enter 로 복사 후 닫힘 | PASS (2026-09-29) |
| 최소 크기에서 Esc | 창 닫힘 | PASS (2026-09-29) |
| 최소 크기에서 잠긴 상태로 열기 | Unlock·Close 버튼 도달 가능 | PASS (2026-09-29) |
| 메인 앱 창 높이를 낮게 줄이기 | inline 검색창이 사라지고 목록이 더 길어짐. 상단 조정 아이콘으로 Advanced Search 진입 가능 | PARTIAL (2026-09-29) — 검색창 숨김과 Advanced Search 진입은 확인. 다만 더 줄이면 clip 이 하나도 안 보이고 일부 액션이 잘림 |
| 낮은 창에서 Advanced Search 로 검색어 입력 후 돌아오기 | 검색어가 있는 동안 inline 검색창이 다시 보이고 지울 수 있음 | PASS (2026-09-29) — 의도대로 유지됨 |

## 3단계 — 최소 사용 가능 viewport

2단계 실기기 관측(PR #13, 2026-09-29)에서 2가지 문제가 확인되었다.

1. MainActivity 에 `<layout>` 이 없어 freeform 창 크기 하한이 전혀 없었다. 창을 계속 줄이면 **필터 칩만 보이고 clip 은 하나도 보이지 않는** 상태까지 허용된다.
2. 폭이 좁아지면 `LibraryScreen` 의 TopAppBar 액션 4개(Trash·Sort·Advanced Search·Lock)가 title 을 밀어내 잘린다.

두 문제는 성격이 다르다. 1번은 창 자체의 크기 문제이고, 2번은 폭 문제이므로 **높이 하한과 폭 하한을 모두 manifest `<layout>` 로 지정**한다. Compose 레벨에서 최소 레이아웃을 유지하는 대신 창을 아예 줄이지 못하게 하는 이유는, 사용자가 창을 줄였는데 아무 반응이 없는 상태가 더 나쁘기 때문이다.

### 높이 하한 384dp

`LibraryLayout` 의 예산으로 도출한다.

| 구성 요소 | 근거 | dp |
| --- | --- | --- |
| TopAppBar | Material 3 small top app bar | 64 |
| 필터 칩 LazyRow | FilterChip 32 + contentPadding 8×2 | 48 |
| ClipCard 최소 | padding 16×2 + title 20 + body 1줄 24 + 액션 행 48 + gap 9×2 | 142 |
| ClipList padding | LazyColumn contentPadding 12×2 | 24 |
| bottom menu | Material 3 navigation bar | 80 |
| **합계** | | **358** |
| **선택한 하한** | 358 + 본문 2줄 card(+24) = 382dp를 넘도록 정렬 | **384** |

이 값은 추정이 아니다. `LibraryLayout.MIN_USABLE_HEIGHT_BUDGET_DP` 로 코드에 고정하고 `LibraryLayoutTest` 가
"하한 ≥ 예산 + 24dp"를 검사하므로, library 행이 늘어 예산을 넘어가면 테스트가 실패한다.
manifest 는 Kotlin 상수를 읽을 수 없으므로 **`<layout>` 값이 이 예산과 어긋나면 테스트는 잡아내지 못한다.**
값을 바꿀 때는 양쪽을 같이 고쳐야 한다.

`COMPACT_HEIGHT_DP = 420`(inline 검색창 숨김 기준)은 별개의 기준이므로 그대로 둔다. 즉 384~420dp 구간에서는
창은 더 줄일 수 없지만 검색창은 숨겨진다.

### 폭 하한 320dp

TopAppBar 액션 IconButton 4개(48×4 = 192dp) + 읽을 만한 title 슬롯(112dp) + 여백(16dp) = 320dp.
이보다 좁으면 액션이 overflow 되어 잠금 버튼을 포함한 필수 조작이 사라진다.

### QuickPaste 최소 폭 240dp

2단계 관측에서 QuickPaste 최소 크기(280×260dp)는 "보조창으로 써도 충분하다"는 확인을 받았고,
**가로 폭만** 추가 축소를 원했다. 세로 260dp 는 그대로 둔다.

240dp 에서 content 폭은 208dp 이다.

- `Close` + `Unlock vault` 버튼 합이 약 190dp 로 빡빡하게 들어간다. 잘린 Unlock 버튼은 두 줄로 바꾼
  것보다 나쁘므로 잠금·설정 화면의 버튼 행을 `Row` 에서 `FlowRow` 로 바꿔 줄바꿈을 허용한다.
- 키 안내 문구(`"↑↓ select · Enter copy · Esc close"`)가 208dp 에서 2줄로 감긴다. 높이는 LazyColumn 이
  `weight(1f)` 로 흡수하므로 목록이 한 행 줄어들 뿐 창이 깨지지 않는다.

### 실기기 확인 (SM-S918N, DeX) — 3단계

`adb install -r` 로 덮어 설치한다(삭제 금지).

| 확인 | 기대 결과 | 결과 |
| --- | --- | --- |
| 메인 창을 가장 작게 줄이기 | 약 384dp 에서 멈춤 | PENDING |
| 최소 크기에서 필터 칩 + clip 1개 + bottom menu | 셋이 동시에 보임 | PENDING |
| 최소 크기에서 TopAppBar 액션 4개 | Trash·Sort·Advanced Search·Lock 이 모두 보임 | PENDING |
| QuickPaste 창을 가장 작게 줄이기 | 약 240×260dp 에서 멈춤 | PENDING |
| QuickPaste 최소 폭에서 잠긴 상태로 열기 | Close·Unlock 이 잘리지 않고 둘 다 보임 또는 줄바꿈 | PENDING |
| QuickPaste 최소 폭에서 키 안내 문구 | 잘리지 않고 읽힘(줄바꿈 허용) | PENDING |
