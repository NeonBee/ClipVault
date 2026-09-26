# ClipVault DeX/Ditto Fork — 코드 감사 결과 및 설계서

- 작성일: 2026-09-26
- 감사 대상: `EntropyRoot/ClipVault`
- 감사 기준 커밋: `ee1f24af3010dd26be7d95545ea9dbf0fd703622`
- 기준 버전: ClipVault 2.0.0
- 대상 fork: `NeonBee/ClipVault`
- 목적: 부트로더 언락 없이 Shizuku를 이용하고, Samsung DeX에서 Ditto에 가까운 클립보드 히스토리 경험을 제공하는 보안 중심 fork 설계
- 상태: 구현 전 감사 완료 / hardening 설계 기준 문서

> 이 문서는 코드 감사 결과와 fork 설계의 기준점이다. 기능 추가보다 보안 경계와 최소 권한을 우선한다.

---

## 1. 결론

`EntropyRoot/ClipVault`는 처음부터 새 클립보드 매니저를 만드는 것보다 보안 저장 계층의 출발점으로 적합하다.

유지할 핵심:

- SQLCipher 암호화 DB
- Android Keystore 기반 DB key wrapping
- `BiometricPrompt + CryptoObject` cryptographic binding
- biometric enrollment 변경 시 wrapping key 무효화
- 잠긴 동안 별도 AES-GCM staging DB
- `INTERNET` permission 부재
- Android cloud backup / device transfer 차단
- `FLAG_SECURE`
- clipboard-only Shizuku AIDL surface
- adaptive polling fallback
- FTS5 검색

구현 전 P0:

1. upstream 라이선스 상태 확인
2. FTS5 secure-delete + schema migration
3. Shizuku bridge v3
4. shell-only backend 기본 정책
5. Binder payload 상한
6. Samsung/Android 16 실기기 compatibility gate

---

## 2. 보안 경계

```text
Android Clipboard
       │
       │ hidden IClipboard
       ▼
Shizuku UserService
PrivilegedClipboardService
       │ AIDL Binder
       ▼
ClipboardCaptureService
       │
  ┌────┴─────┐
  │          │
UNLOCKED   LOCKED
  │          │
  ▼          ▼
SQLCipher  SecurePendingStore
           AES-256-GCM
                │
                │ biometric unlock
                ▼
           SQLCipher DB
```

DB key:

```text
Android Keystore KEK
      │ AES-GCM wrapping
      ▼
wrapped SQLCipher DEK
      │ BiometricPrompt + CryptoObject
      ▼
32-byte DEK
      │
      ▼
SQLCipher DB
```

---

# 3. 감사 1 — Build / Dependency

## 3.1 SQLCipher

현재 artifact:

```toml
sqlcipher = "4.17.0"
module = "net.zetetic:sqlcipher-android"
```

deprecated된 `android-database-sqlcipher`가 아니라 새 binding이다.

**판정: PASS**

fork에서는 stable `4.19.0` 업데이트를 우선 검토한다. SQLCipher 5 beta는 초기 범위에서 제외한다.

## 3.2 AndroidX Biometric

현재 `androidx.biometric:biometric:1.1.0`.

버전 숫자만 보고 기술부채로 판단하지 않는다. 현재 구현은 `BIOMETRIC_STRONG`과 `CryptoObject`를 정상 사용한다.

**판정: PASS**

## 3.3 BouncyCastle

`VaultBackupManager.kt`에서 Argon2id KDF 구현에 실제 사용한다.

```text
Argon2BytesGenerator
Argon2Parameters.ARGON2_id
```

**판정: PASS**

## 3.4 R8 / reflection

`proguard-rules.pro`는 Shizuku UserService와 AIDL Stub을 keep한다.

**판정: PASS**

---

# 4. 감사 2 — AndroidManifest

좋은 점:

- `INTERNET` permission 없음
- `ACCESS_NETWORK_STATE` transitive permission 제거
- `usesCleartextTraffic=false`
- `allowBackup=false`
- cloud backup / device transfer 차단
- `ClipboardCaptureService exported=false`
- FGS `specialUse`
- Quick Settings Tile은 system permission으로 보호
- ShizukuProvider 설정은 Shizuku 공식 contract와 일치

ShizukuProvider:

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:multiprocess="false"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

`exported=true` 자체가 취약점은 아니다.

### 추적 항목

`MainActivity`가 `ACTION_SEND`, `ACTION_PROCESS_TEXT`를 받는다.

fork에서는:

- 입력 길이 제한
- MIME 재검증
- 예상하지 못한 URI/extra 무시
- 외부 Intent와 vault security state 분리

를 추가한다.

---

# 5. 감사 3 — Keystore / Biometric / Memory

## 5.1 Cryptographic binding

실제 경로:

```text
BiometricPrompt
      │
      ▼
CryptoObject(Cipher)
      │
      ▼
Keystore authenticated operation
      │
      ▼
wrapped DEK decrypt
```

단순 boolean gate가 아니다.

**판정: PASS**

## 5.2 Biometric enrollment invalidation

`VaultKeyManager`:

```java
.setUserAuthenticationRequired(true)
.setInvalidatedByBiometricEnrollment(true)
```

새 biometric이 등록되면 기존 wrapping key를 무효화한다. `KeyPermanentlyInvalidatedException`도 처리한다.

**판정: PASS**

## 5.3 DB key zeroization

`ClipVaultApp.openVault()`는:

```java
try {
    ...
} finally {
    Arrays.fill(databaseKey, (byte) 0);
}
```

를 사용한다.

SQLCipher에는:

```sql
PRAGMA cipher_memory_security = ON
```

을 적용한다.

**판정: PASS**

## 5.4 Lock

현재:

```java
unlocked = false;
ioExecutor.execute(() -> {
    VaultRepository closing = repository;
    repository = null;
    if (closing != null) closing.close();
});
```

논리 접근 차단은 즉시지만 실제 DB close는 IO queue 뒤에서 수행된다.

권장 상태:

```text
UNLOCKED → LOCKING → LOCKED
```

**판정: PASS + hardening**

## 5.5 SecurePendingStore

Vault locked 동안:

```text
clipboard
   ↓
AES-256-GCM
   ↓
pending_encrypted.db
```

unlock 후 SQLCipher로 import하고 consumed row를 삭제한다.

**판정: PASS**

pending key가 biometric-bound가 아닌 것은 background capture를 위한 의도된 trade-off다.

## 5.6 StrongBox

현재 강제 사용 없음.

권장:

```text
StrongBox 가능 → StrongBox
불가능 → TEE / Android Keystore fallback
```

**판정: Optional hardening**

---

# 6. 감사 4 — SQLCipher / WAL / FTS5

## 6.1 WAL

```sql
PRAGMA journal_mode = WAL
```

SQLCipher DB의 WAL page 역시 encrypted boundary 안에 있다.

**판정: PASS**

## 6.2 secure_delete

```sql
PRAGMA secure_delete = ON
```

일반 table에 대한 삭제 흔적 감소에 도움이 된다.

**판정: PASS**

## 6.3 FTS5 secure-delete

현재:

```sql
CREATE VIRTUAL TABLE clips_fts USING fts5(...)
```

SQLite 일반 `secure_delete=ON`만으로 FTS5 shadow table의 삭제 흔적까지 보장하지 않는다.

권장:

```sql
INSERT INTO clips_fts(clips_fts, rank)
VALUES('secure-delete', 1);
```

기존 DB migration 시:

```sql
INSERT INTO clips_fts(clips_fts)
VALUES('rebuild');
```

검토.

권장 schema:

```text
SCHEMA_VERSION 2
      ↓
SCHEMA_VERSION 3
- FTS5 secure-delete
- FTS rebuild
- migration instrumentation test
```

**판정: 수정 필요**

---

# 7. 감사 5 — Shizuku / hidden Clipboard API

장기 유지보수에서 가장 큰 위험 영역이다.

## 7.1 현재 구조

```text
normal app
   │
   ▼
Shizuku UserService
   │ shell/root identity
   ▼
android.os.ServiceManager
   │
   ▼
android.content.IClipboard
```

Shizuku API dependency 자체가 deprecated된 것은 아니다. 문제는 그 위에서 사용하는 Android hidden clipboard contract다.

## 7.2 잘한 점 — AIDL surface

현재:

```aidl
interface IClipboardBridge {
    void destroy();
    String readText();
    int protocolVersion();
    boolean registerListener(IClipboardListener listener);
    void unregisterListener(IClipboardListener listener);
}
```

없는 것:

```text
exec(command)
runShell(command)
readFile(path)
writeFile(path)
getSystemService(name)
```

즉 privileged surface를 clipboard에 한정한다.

**판정: PASS / 반드시 유지**

## 7.3 UserService lifecycle

현재:

```java
.daemon(false)
.processNameSuffix("clipboard_bridge")
.version(2)
.tag("clipvault.clipboard.v2")
```

그리고 close 시 user service를 unbind/destroy한다.

**판정: PASS**

## 7.4 핵심 위험 — hidden method resolution

현재:

```java
for (Method method : target.getClass().getMethods()) {
    if (method.getName().equals(name)) {
        method.setAccessible(true);
        return method;
    }
}
```

즉 메서드 이름만 맞는 첫 overload를 선택한다.

위험:

- vendor ROM overload
- Android version signature 변경
- 동일 type argument 순서 변경
- 추가 int/string argument
- 알 수 없는 object parameter를 null로 채움

또 실패가 대부분 `return null`로 뭉개져 empty clipboard와 API break를 구별하기 어렵다.

**판정: 수정 필요**

---

# 8. Shizuku bridge v3 설계

권장 AIDL 개념:

```aidl
interface IClipboardBridge {
    void destroy();
    int protocolVersion();

    int probeCapabilities();
    int lastErrorCode();

    String readText();
    boolean registerListener(IClipboardListener listener);
    void unregisterListener(IClipboardListener listener);
}
```

Capability 예:

```text
CAP_READ
CAP_EVENT_LISTENER
CAP_POLL_FALLBACK
CAP_USER_SCOPED
CAP_PAYLOAD_LIMIT
```

Error 예:

```text
OK
SERVICE_MANAGER_UNAVAILABLE
CLIPBOARD_SERVICE_MISSING
I_CLIPBOARD_STUB_MISSING
GET_PRIMARY_CLIP_SIGNATURE_UNSUPPORTED
LISTENER_SIGNATURE_UNSUPPORTED
SECURITY_EXCEPTION
INVOCATION_FAILED
TRANSACTION_TOO_LARGE
BACKEND_NOT_SHELL
```

clipboard 내용 자체는 log/error에 넣지 않는다.

---

# 9. Exact signature adapter

`findMethod(name)` 대신 알려진 signature를 명시적으로 매칭한다.

예:

```text
Legacy:
getPrimaryClip(String, int)

Modern:
getPrimaryClip(String, String, int, int)
```

구조:

```java
interface ClipboardApiAdapter {
    ClipData read(...);
    boolean register(...);
}
```

초기화 시 한 번 resolve하고 매 read마다 method discovery를 반복하지 않는다.

---

# 10. Listener / polling

현재 listener Binder transaction code는 현재 AOSP의 single-method listener contract와 맞는다.

그러나 hidden contract이므로 v3에서는 실제 probe 결과에 따라:

```text
READY_EVENT
READY_POLL
DEGRADED
```

로 구분한다.

event listener 실패 시 adaptive polling fallback은 유지한다.

---

# 11. Root backend 제한

Shizuku backend에 따라 UserService는 shell 또는 root identity로 실행될 수 있다.

clipboard read에는 root가 필요 없다.

원칙:

```text
필요 권한 = shell
실제 권한 = root
→ 최소권한 위반
```

fork 기본 정책:

```text
Shizuku UID == 2000(shell) → 허용
root/Sui backend → 기본 거부
```

root 허용 옵션은 초기 MVP 범위에서 만들지 않는다.

---

# 12. Multi-user userId 수정

현재 privileged service는:

```java
Process.myUid() / 100_000
```

으로 userId를 계산한다.

UserService가 shell UID로 실행되므로 main user 외 multi-user/work profile에서는 부정확할 수 있다.

v3:

- per-user UserService tag
- caller user를 bind/registration 시 고정
- system callback 시 caller가 system_server로 바뀔 수 있으므로 callback 순간 재계산하지 않음

목표:

```text
App user 0 ↔ bridge user 0
Work profile user 10 ↔ bridge user 10
```

---

# 13. Binder payload limit

현재:

```aidl
String readText()
```

로 text 전체를 Binder transaction에 싣는다.

oversized clipboard는 `TransactionTooLargeException` 위험이 있다.

초기 정책 예:

```text
MAX_BRIDGE_CHARS = 128,000
```

상한 초과 시 silent truncate보다 명시적 reject를 우선한다.

```text
CAPTURE_REJECTED_TOO_LARGE
```

대형 payload 지원이 필요해질 때만 ParcelFileDescriptor/shared memory를 별도 설계한다.

---

# 14. Bridge health state

현재 Binder 연결 성공과 clipboard hidden API 실제 성공을 충분히 구별하지 않는다.

권장 상태:

```text
NO_SHIZUKU
  ↓
PERMISSION_REQUIRED
  ↓
BINDER_CONNECTED
  ↓
API_PROBING
  ↓
READY_EVENT / READY_POLL / DEGRADED
```

UI/notification에는 내용이 아닌 상태와 오류 유형만 표시한다.

---

# 15. 목표 architecture

```text
Android / Samsung DeX
        │
        ▼
Clipboard Bridge v3
- Shizuku shell only
- exact signature adapter
- capability probe
        │
        ▼
Capture Coordinator
- dedupe
- payload limit
- health state
        │
   ┌────┴────┐
   │         │
unlocked   locked
   │         │
   ▼         ▼
SQLCipher  Pending AES-GCM
4.19           │
FTS secure     │ unlock
   └─────┬─────┘
         ▼
     Vault domain
         │
         ▼
 QuickPasteActivity
 search / ↑↓ / Enter
         │
         ▼
 ClipboardManager
         │
         ▼
     user Ctrl+V
```

---

# 16. DeX Quick Paste

신규 `QuickPasteActivity`:

- compact window
- `FLAG_SECURE`
- search autofocus
- recent history
- arrow navigation
- Enter select
- Esc close
- clipboard restore 후 `finish()`
- search query persistence 없음
- recents preview 없음

Vault locked 시:

```text
QuickPaste
   ↓
locked?
   ├─ no → search
   └─ yes → BiometricPrompt → DB open → search
```

biometric boundary를 우회하는 plaintext quick cache는 만들지 않는다.

---

# 17. Auto-lock

현재 최대 auto-lock이 5분으로 clamp되어 있다.

DeX에서는 다음 옵션을 검토한다.

```text
30 sec
1 min
5 min
15 min
30 min
screen lock immediately
```

단:

```text
DeX 연결 == 자동 unlock
```

은 금지한다.

DeX mode는 authentication factor가 아니다.

---

# 18. Copy-back / auto paste

선택 시 system clipboard에 복원하고 `EXTRA_IS_SENSITIVE=true`를 유지한다.

v1에서는 자동 paste를 하지 않는다.

이유:

- 추가 input privilege 필요 가능
- AccessibilityService surface 증가
- 현재 최소권한 설계 훼손

사용 흐름:

```text
QuickPaste → Enter → clipboard restore → 원래 앱 → Ctrl+V
```

---

# 19. Global shortcut 전략

Android 일반 앱에는 Windows의 global hotkey와 동일한 안정 API가 없다.

Phase 1:

- notification action
- Quick Settings tile
- launcher/taskbar shortcut

Phase 2에서만 global keyboard 실험.

AccessibilityService를 사용한다면 optional/disabled-by-default component로 분리한다.

Shizuku bridge에는 clipboard 이외의 범용 input API를 추가하지 않는다.

---

# 20. Fork 보안 invariant

1. `INTERNET` permission 추가 금지
2. clipboard plaintext log 금지
3. DB key persistent plaintext 저장 금지
4. QuickPaste용 plaintext cache 금지
5. Shizuku AIDL clipboard-only 유지
6. 필요 없는 root 사용 금지
7. global shortcut 때문에 범용 shell/input API 추가 금지
8. fail-closed 우선, 상태는 사용자에게 명확히 표시
9. Trash와 Permanent Delete 의미 구분
10. DeX mode를 authentication 수단으로 사용 금지

---

# 21. 구현 단계

용어 규칙:

- `PR #N`: GitHub의 실제 Pull Request 번호. 예: `PR #6`.
- `WP-NN`: 설계/로드맵상의 Work Package 번호. GitHub PR 번호와 독립적이다.
- 기존 문서의 `PR-01`~`PR-08` 표기는 혼동 방지를 위해 `WP-01`~`WP-08`로 전환한다.

## WP-01 Baseline / dependency

- audit baseline SHA 고정
- SQLCipher 4.19 stable 검토
- debug/release R8 build
- 기존 tests green

## WP-02 DB deletion hardening

- schema v3
- FTS5 secure-delete
- FTS rebuild migration
- deletion instrumentation tests

## WP-03 Shizuku bridge v3

- protocol v3
- shell-only backend
- exact signature adapter
- capability/error state
- payload cap
- user scoping
- adaptive polling 유지

## WP-04 Device compatibility

- Samsung target device
- Android 16
- bind/read/listener/polling
- binder death/restart
- screen lock
- doze/wake
- oversized payload reject

## WP-05 QuickPasteActivity

- FTS search
- keyboard nav
- copy-back
- `FLAG_SECURE`
- locked → biometric

## WP-06 DeX UX

- freeform window sizing
- focus
- taskbar/notification/Quick Settings invocation
- DeX density

## WP-07 Optional global shortcut

- 별도 feature flag
- AccessibilityService 사용 시 최소 기능
- disabled by default

## WP-08 Optional StrongBox

- capability detection
- StrongBox preference
- TEE fallback
- key backend 표시

---

# 22. 테스트 전략

Unit:

- known hidden API signatures
- unknown/overloaded signatures
- null service
- reflection errors
- dedupe
- self-copy suppression
- payload limit
- event/poll fallback

Instrumentation:

- biometric-bound key
- enrollment invalidation
- corrupted envelope
- missing Keystore key
- lock closes DB
- pending import/delete
- wrong SQLCipher key
- WAL reopen
- FTS migration/secure-delete
- Shizuku shell UID requirement
- root backend refusal
- protocol mismatch
- binder death/restart

---

# 23. Threat model

보호:

- locked 상태의 app file theft
- SQLCipher DB 탈취
- pending DB 탈취
- Android cloud backup
- recents screenshot
- 일반 background app
- 삭제된 FTS 흔적 장기 보존 감소

보호 범위 밖:

- root/OS compromise
- malicious kernel
- app process injection
- unlocked 상태 active memory dump
- malicious AccessibilityService
- malicious/modified Shizuku
- coercive biometric authentication
- system clipboard로 복원한 이후 다른 privileged component의 접근

---

# 24. 감사 판정표

| 영역 | 판정 | 조치 |
|---|---|---|
| SQLCipher artifact | 🟢 | 신형 binding 유지 |
| SQLCipher version | 🟡 | 4.19 stable 검토 |
| AndroidX Biometric | 🟢 | 유지 |
| BouncyCastle | 🟢 | Argon2id에 실제 사용 |
| Manifest network | 🟢 | INTERNET 없음 유지 |
| Backup/device transfer | 🟢 | 차단 유지 |
| CaptureService exposure | 🟢 | exported=false |
| ShizukuProvider | 🟢 | 공식 contract |
| Biometric CryptoObject | 🟢 | 유지 |
| Enrollment invalidation | 🟢 | 유지 |
| DB key zeroization | 🟢 | 유지 |
| SQLCipher close | 🟢/🟡 | state machine 개선 |
| Pending AES-GCM | 🟢 | 유지 |
| StrongBox | 🟡 | optional |
| WAL confidentiality | 🟢 | 유지 |
| SQLite secure_delete | 🟢 | 유지 |
| FTS5 secure-delete | 🟠 | 추가 필요 |
| Shizuku API | 🟢 | dependency 자체는 문제 없음 |
| Shizuku runtime/vendor | 🟠 | 실기기 gate |
| AIDL privilege surface | 🟢 | clipboard-only 유지 |
| hidden API resolution | 🟠 | exact adapter 필요 |
| bridge health reporting | 🟠 | capability/error state |
| Binder payload | 🟠 | 상한 필요 |
| root backend | 🟠 | shell-only |
| multi-user | 🟠 | v3에서 수정 |
| security tests | 🟡 | 확대 |
| upstream license | 🔴 Gate | 공개 배포 전 확인 |

---

# 25. 구현 시작 Gate

Repository:

```text
[ ] upstream license/permission 확인
[x] baseline commit 고정
```

Build:

```text
[ ] debug build
[ ] release R8 build
[ ] current tests pass
```

Security:

```text
[ ] SQLCipher stable update 검토
[ ] FTS secure-delete migration
[ ] bridge v3 implementation
```

Device:

```text
[ ] Samsung/DeX Shizuku bind
[ ] clipboard read
[ ] event or polling
[ ] screen lock
[ ] doze/wake
```

---

# 26. 구현 순서

```text
1. baseline build
2. current tests
3. SQLCipher stable update
4. schema v3 / FTS secure-delete
5. bridge v3
6. Samsung compatibility test
7. QuickPasteActivity
8. DeX keyboard UX
9. global shortcut experiment
```

QuickPaste UI보다 storage/bridge hardening을 먼저 완료한다.

---

# 27. 코드 학습 포인트

Android IPC:

```text
AIDL
Binder
ServiceConnection
RemoteCallbackList
Binder calling UID
transaction size
```

Android security:

```text
Keystore
KeyGenParameterSpec
BIOMETRIC_STRONG
CryptoObject
key invalidation
StrongBox
```

DB:

```text
SQLCipher
SQLite WAL
secure_delete
FTS5 shadow table
trigger
migration
transaction
```

Process identity:

```text
app UID
shell UID 2000
root UID 0
Android userId
SELinux context
```

Reliability:

```text
event-driven
polling fallback
exponential backoff
binder death
protocol version
capability negotiation
```

---

# 28. 주요 감사 대상 코드

- `app/src/main/java/dev/clipvault/app/security/VaultKeyManager.java`
- `app/src/main/java/dev/clipvault/app/security/SecurePendingStore.java`
- `app/src/main/java/dev/clipvault/app/data/VaultRepository.java`
- `app/src/main/java/dev/clipvault/app/clipboard/ShizukuController.java`
- `app/src/main/java/dev/clipvault/app/clipboard/PrivilegedClipboardService.java`
- `app/src/main/java/dev/clipvault/app/clipboard/ClipboardCaptureService.java`
- `app/src/main/kotlin/dev/clipvault/app/MainActivity.kt`
- `app/src/main/kotlin/dev/clipvault/app/backup/VaultBackupManager.kt`
- `app/src/main/AndroidManifest.xml`
- `app/proguard-rules.pro`
- `gradle/libs.versions.toml`

감사 기준 upstream:

https://github.com/EntropyRoot/ClipVault/tree/ee1f24af3010dd26be7d95545ea9dbf0fd703622

---

# 29. 다음 작업

이 문서를 기준으로 이후 worker는 다음 순서로 작업한다.

```text
보안 storage hardening
        ↓
privilege bridge hardening
        ↓
Samsung/DeX compatibility
        ↓
QuickPaste UX
```

기능 편의를 위해 기존 security boundary를 넓히는 변경은 별도 설계 검토 없이 허용하지 않는다.
