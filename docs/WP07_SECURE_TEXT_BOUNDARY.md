# WP-07 Secure Text Boundary

상태: **Decision / Planned — not implemented yet**

이 문서는 ClipVault가 단순한 encrypted clipboard history를 넘어, 민감한 텍스트가 가능한 한 Android/System clipboard를 통과하지 않고 vault에 들어오고 나가도록 하는 보안 경계와 UX 방향을 고정한다.

## 1. 결정 요약

ClipVault는 다음 원칙을 채택한다.

1. **System clipboard는 신뢰 경계 밖의 egress로 취급한다.**
   - 현재 `SensitiveClipboard`를 통한 일반 복사는 호환성을 위한 경로다.
   - `ClipDescription.EXTRA_IS_SENSITIVE`는 UI 노출 완화 힌트일 뿐, 기밀성 경계를 제공하는 것으로 간주하지 않는다.
   - 시스템 clipboard에 평문이 올라간 뒤의 vendor history, keyboard UI, cross-device 기능, 다른 허용된 consumer 동작은 ClipVault가 통제하지 못한다.

2. **민감 텍스트에는 별도의 Secure Copy / Secure Paste 경로를 제공한다.**
   - Secure Copy: 선택된 텍스트를 Android text-selection action을 통해 ClipVault로 직접 전달한다.
   - Secure Paste: ClipVault 보조 IME가 vault 검색 결과를 `InputConnection.commitText(...)`로 현재 입력 필드에 직접 삽입한다.
   - 두 경로 모두 정상 동작 시 `ClipboardManager`를 사용하지 않는다.

3. **Accessibility Service는 이 기능을 위해 요구하지 않는다.**
   - 다른 앱 UI 감시, selection interception, 자동 paste를 위해 Accessibility 권한을 추가하지 않는다.
   - 더 넓은 권한을 얻기 위해 보안 경계를 확장하는 것보다, Android가 제공하는 좁은 공식 integration surface를 사용한다.

4. **Samsung Keyboard를 대체하지 않는다.**
   - Samsung Keyboard는 일상 입력용 primary IME로 유지한다.
   - ClipVault IME는 vault retrieval/direct-insert 전용의 보조 IME다.
   - 항목 삽입 후 가능한 경우 이전 IME로 복귀한다.

5. **기존 일반 clipboard 경로는 제거하지 않는다.**
   - 일반 Copy/Paste는 호환성 경로로 유지한다.
   - 사용자가 민감정보를 명시적으로 다룰 때 Secure path를 선택할 수 있게 한다.

핵심 원칙:

> ClipVault should request the least privileged integration necessary. Sensitive text should enter and leave the vault without traversing the system clipboard whenever possible.

## 2. 문제 정의

현재 QuickPaste와 Main Vault의 저장소/인증 경계가 강하더라도, 복호화된 값을 system clipboard로 다시 복사하는 순간 이후의 평문 수명은 ClipVault가 완전히 통제하지 못한다.

현재 호환성 경로:

```text
ClipVault encrypted vault
        |
        | unlock / select
        v
SensitiveClipboard
        |
        v
Android / vendor system clipboard
        |
        +--> target app
        +--> keyboard clipboard UI/history
        +--> vendor cross-device features
        +--> other platform-permitted consumers
```

따라서 vault-at-rest 보안만 강화해도 end-to-end text handling의 가장 약한 경계는 system clipboard가 될 수 있다.

## 3. 목표 아키텍처

### 3.1 Secure Copy — direct ingress

사용자가 다른 앱에서 텍스트를 선택한 뒤 contextual text-selection toolbar의 ClipVault action을 선택한다.

```text
Source app selected text
        |
        | ACTION_PROCESS_TEXT
        v
ClipVault secure-ingress Activity
        |
        | existing vault unlock gate
        v
VaultRepository
        |
        v
SQLCipher
```

요구사항:

- `android.intent.action.PROCESS_TEXT` / `text/plain` 기반의 명시적인 사용자 동작만 처리한다.
- 선택 텍스트를 system clipboard에 쓰지 않는다.
- clipboard capture pipeline을 우회하므로 같은 텍스트가 Samsung/Android clipboard history에 생성되어서는 안 된다.
- vault가 잠긴 경우 durable write 전에 기존 strong-biometric unlock boundary를 거친다.
- secure-ingress 전용으로 별도의 silent persistence 경로를 추가하지 않는다. locked-state staging 재사용 여부는 별도 보안 결정 없이 자동 확대하지 않는다.
- Activity/task 흔적, 로그, notification, saved-state에 평문을 남기지 않는다.
- source app이 `PROCESS_TEXT`를 제공하지 않거나 자체 selection UI를 쓰는 경우 이 기능을 강제하지 않는다.

### 3.2 Secure Paste — direct egress

ClipVault를 secondary `InputMethodService`로 등록하고, vault retrieval UI만 제공한다.

```text
Target input field
        ^
        | InputConnection.commitText(...)
        |
ClipVault retrieval IME
        ^
        | search / select
        |
ClipVault encrypted vault
```

요구사항:

- 일반 keyboard를 재구현하지 않는다.
- vault search / recent / pinned 등 retrieval surface만 제공한다.
- 선택된 항목은 `InputConnection`을 통해 현재 editor에 직접 넣는다.
- secure paste에서는 `ClipboardManager`를 사용하지 않는다.
- 삽입 후 지원되는 경우 이전 IME(Samsung Keyboard 등)로 복귀한다.
- IME가 활성화되어 있다는 이유만으로 일반 키 입력을 기록, 저장, 분석하지 않는다.
- password manager/autofill과 경쟁하는 credential manager가 아니라 general-purpose secure text retrieval surface로 유지한다.

### 3.3 QuickPaste와 역할 분리

```text
                     Encrypted Vault
                    /       |        \
                   /        |         \
          Main Vault    QuickPaste   Secure IME
          관리/정리       DeX 검색      Mobile/DeX retrieval
                                         |
                                  Direct Insert
```

- **Main Vault:** 검색, 편집, 태그, 삭제, 정책 관리.
- **QuickPaste:** DeX/freeform 환경의 Ditto-style 빠른 검색. 현재는 clipboard restore 경로를 유지한다.
- **Secure IME:** 앱 레이아웃을 떠나지 않고 검색 후 direct insert.
- **Secure Copy action:** source app의 selection toolbar에서 direct vault ingress.

QuickPaste와 Secure IME는 가능한 한 동일한 search/repository 계층을 공유하고, 출력 transport만 달리한다.

## 4. 명시적으로 하지 않는 것

WP-07에서는 다음 접근을 기본 설계로 채택하지 않는다.

- Samsung Keyboard 내부 Clipboard 버튼 hook/override
- vendor keyboard private API injection
- Accessibility Service로 다른 앱의 selection/click 감시
- Accessibility `ACTION_SET_TEXT` 기반의 일반 paste 자동화
- overlay로 시스템 selection toolbar를 흉내 내어 기존 UI를 가로채는 방식
- system clipboard를 비공개/private clipboard로 오인하는 보안 주장
- IME 활성화만으로 모든 앱/모든 input field에서 direct insert가 가능하다는 보장

Samsung/One UI 고유 기능은 compatibility test 대상일 뿐 인증 요소나 보안 경계가 아니다.

## 5. 권한 및 신뢰 경계

목표는 기능 대비 최소 권한이다.

예상 integration surface:

- 기존 biometric / Android Keystore
- 기존 Shizuku capture boundary
- `ACTION_PROCESS_TEXT` receiver Activity
- 사용자가 명시적으로 활성화하는 ClipVault `InputMethodService`

이 기능을 위해 새로 요구하지 않는 것:

- Accessibility Service
- screen content capture
- arbitrary shell execution
- `INTERNET` permission
- 다른 앱 프로세스 injection/hooking

주의: IME 자체도 사용자가 높은 신뢰를 부여하는 Android component다. 따라서 ClipVault IME는 기능 범위를 retrieval/direct-insert로 최소화하고, 일반 입력 수집 기능을 추가하지 않는 것을 보안 요구사항으로 둔다.

## 6. 실패 시 정책

Secure path는 fail-open으로 일반 clipboard에 자동 fallback하지 않는다.

예:

- `PROCESS_TEXT` source 호환 실패 → 사용자에게 Secure Copy가 불가능함을 표시. 자동 일반 Copy 금지.
- vault unlock 실패/취소 → 저장하지 않고 source app으로 복귀.
- `InputConnection` 없음/commit 실패 → clipboard에 자동 복사하지 않고 실패 표시.
- IME 전환/복귀 실패 → 현재 상태를 표시하되 plaintext를 clipboard에 쓰지 않는다.

사용자가 별도의 **일반 Copy** 동작을 선택한 경우에만 기존 clipboard 호환성 경로를 사용한다.

## 7. 검증 항목

### 7.1 Secure Copy compatibility matrix

최소 Samsung 실기기에서 다음 앱을 확인한다.

- Samsung Notes
- Samsung Internet
- Chrome
- Gmail
- Discord
- Telegram
- Termux

각 앱에서 기록:

- text selection 가능 여부
- ClipVault action 표시 여부
- overflow 포함 위치
- 선택 텍스트 정확성
- secure save 성공 여부
- 저장 뒤 Samsung Keyboard clipboard panel에 해당 텍스트가 없는지

Android/One UI가 toolbar action의 정확한 위치를 보장한다고 가정하지 않는다.

### 7.2 Secure Paste validation

- Samsung Keyboard -> ClipVault IME 전환
- locked / unlocked vault 동작
- search / recent 결과
- direct insert 결과
- clipboard before/after 값 비교
- Samsung Keyboard clipboard history non-interference
- 이전 IME 복귀
- multiline / Unicode / Korean / URL / code snippet
- unsupported editor / read-only field fail-closed
- DeX physical keyboard 환경

### 7.3 Clipboard non-interference gate

Secure Copy 또는 Secure Paste 시 다음을 모두 확인해야 한다.

1. ClipVault가 `ClipboardManager.setPrimaryClip()`을 호출하지 않는다.
2. 작업 전후 system primary clip이 의도치 않게 변경되지 않는다.
3. Samsung Keyboard clipboard UI/history에 secure-path payload가 새 항목으로 나타나지 않는다.
4. 기존 Shizuku clipboard capture가 secure-path payload를 새 system-clipboard event로 관측하지 않는다.

이 검증을 통과하기 전에는 "system clipboard bypass"를 release security claim으로 사용하지 않는다.

## 8. 단계 분리

### WP-07A — Secure Copy PoC
- `ACTION_PROCESS_TEXT` receiver
- direct vault ingest
- lock/unlock boundary
- plaintext lifecycle 검토

### WP-07B — Secure IME PoC
- 최소 `InputMethodService`
- vault search/recent
- `InputConnection.commitText`
- previous-IME return

### WP-07C — Security hardening
- fail-closed behavior
- clipboard non-interference tests
- IME plaintext lifecycle
- task/recents/logging review

### WP-07D — Samsung/Android compatibility
- handset + DeX compatibility matrix
- selection toolbar variance
- editor compatibility
- Samsung clipboard residue test

## 9. 현재 구현과의 관계

이 문서는 **결정사항과 다음 구현 경계**를 정의한다. 작성 시점의 ClipVault는 아직 WP-07 Secure Copy/Secure Paste를 구현하지 않았다.

현재 구현의 copy-back 경로는 계속 system clipboard를 사용하므로, 그 경로에 대해서는 "ClipVault encrypted storage에서 안전하게 보관된다"는 주장과 "clipboard로 내보낸 뒤에도 ClipVault가 기밀성을 보장한다"는 주장을 구분해야 한다.

관련 문서:

- `SECURITY.md`
- `docs/THREAT_MODEL.md`
- `docs/ARCHITECTURE.md`
- `docs/WP05_QUICK_PASTE.md`
- `docs/WP06_DEX_UX.md`
