# WP-08 Council 의장 종합안 / Chair Synthesis

> **Status: CHAIR RECOMMENDATION / MAINTAINER DECISION PENDING**  
> **Chair:** ChatGPT (PR #20 및 WP-07A/B 맥락 통합·근거 검증·의견 조정 담당)  
> **Date:** 2026-11-01  
> **PR:** [#21](https://github.com/NeonBee/ClipVault/pull/21) / base `hardening/base`  
> **Caution:** 아래의 결정은 의장 권고이지, 독립 리뷰어의 전원 승인이나 사용자의 최종 채택이 아니다. 실기기 ML 측정도 수행되지 않았다.

## 1. 실제 접수된 Council 의견과 범위

두 건의 참가자 리뷰가 **메인테이너 계정으로 게시**되어 있다. 각각 출처를 기록한다.

1. **Grok / xAI, S2 Android & Background**, [PR 댓글 #6557023992](https://github.com/NeonBee/ClipVault/pull/21#issuecomment-6557023992): **APPROVE WITH CONDITIONS**. 규칙 기반 우선, 폴더 탐색, 잠긴 Vault 무인 분석 금지, 10분 정확한 WorkManager 주기 불가, on-unlock/foreground 접근, 임베딩·LLM은 측정 후.
2. **Claude Opus 5.5, S1·S3·S4·S5 교차 검토(S6 적대적 위험도 언급)**, [PR 댓글 #6557039054](https://github.com/NeonBee/ClipVault/pull/21#issuecomment-6557039054): **REVISE (minor)**. RFC에 기존 `NativeClassifier`·`clips.flags`·`SmartFilter`를 누락했다고 지적. 규칙/한국어/자격증명 시그니처 우선, WP-08A 무주기, 추론 파생물과 수동 태그 분리, 폴더와 IME 검색 제거를 한 구현 PR에서 처리, 생성형 LLM은 별도 RFC를 선호.

**공정한 표기:** 제출된 것은 실제 **2명의/2모델의 리뷰 메시지**다. Claude의 교차 검토를 4~5개 *독립 투표*로 환산하지 않는다. 별도의 S6 전담 레드팀 검토, 모델 측정, 독립 보안 감사가 수행되었다고 표시하지 않는다. 두 리뷰의 일부 제안은 서로 다르며 아래에 그대로 보존한다.

## 2. 의장이 코드로 확인한 사실 (의견과 구분)

- `NativeClassifier.java`에는 `LINK`, `GITHUB`, `EMAIL`, `PHONE`, `CODE`, `JSON`, `SENSITIVE` 등 비트 플래그 **0~17(18개)**와 JNI/Java fallback `analyze()`가 이미 있다. 한국어 언어 플래그나 한국어 민감 키워드 규칙은 현재 확인한 Java fallback에 없다. **C++ 구현의 개별 시그니처에 대한 완전한 부재 주장은 별도 테스트로 확정**해야 한다.
- `VaultRepository.java` SQLCipher **schema v3**의 `clips.flags`와 `idx_clips_flags`, `tags`/`clip_tags`/`collections`, `clips_fts`, `ClipQuery.requiredFlag` 및 태그/컬렉션 필터는 실제로 존재한다. `insert(String)`, `edit()`, `mergeImportedClip()`는 `NativeClassifier.analyze()`를 호출한다.
- `ClipVaultApp.capture()`는 잠겨 있을 때도 **사용자가 방금 복사한 입력 텍스트를 메모리에서 분석**한 다음, `PREF_SKIP_SENSITIVE`가 기본 true이고 `analysis.sensitive`이면 자동 캡처를 건너뛴다. 저장하기로 결정한 텍스트는 Vault가 열려 있으면 SQLCipher에, 잠겼으면 별도의 `SecurePendingStore` 암호화 스테이징에 기록한다. **이것은 잠긴 Vault의 기존 내용을 복호화하는 권한이 아니다.**
- `saveExplicitSecureText()`는 사용자 요청 활성/키가드/`lockEpoch`를 확인하고 `VaultRepository.insert(rawText)`로 저장한다. 따라서 명시적 Secure Copy와 자동 캡처의 민감정보 기본 skip 동작은 서로 다르다.
- `VaultKeyManager.java` + `BiometricVaultUnlock.kt`는 CryptoObject/강력 생체인증 키 경계를 유지한다. `lockVault`로 SQLCipher 접근은 취소되고 lock epoch가 바뀐다. `RetentionWorker`가 있다는 이유만으로 일반 잠금 상태에서 분류 작업이 Vault를 읽을 수 있는 것은 아니다.
- `docs/BACKUP_FORMAT.md`는 `.cvault`의 암호화된 JSON payload에 clips, tags, clip-tag links, collections 등이 들어간다고 기록한다. 복원 시 `mergeImportedClip()`에서 분석을 재수행한다. **수동 override가 실제 백업/복원되는지**와 추론 라벨 백업 정책은 새 형식을 확정하기 전에 테스트한다.
- `SecurePasteImeService.kt`는 기존 `EditText`를 IME 내부에 표시하고, 현재 검색어 입력이 브라우저 주소창으로 빠져나가는 실기기 문제가 있다. `InputConnection.commitText`로 항목을 직접 삽입하는 경로는 유지한다.
- Manifest/CI 설계는 INTERNET 권한이 없는 오프라인 앱을 전제한다. 추후 ML 라이브러리의 **병합된 최종 APK manifest**를 검사해야 한다.

**중요 보완:** RFC의 “현재 저장된 클립 분류에 새 규칙 엔진이 필요하다”는 인상은 정확하지 않다. 기존 NativeClassifier 확장이 더 작은 변경이다. 다만 새로운 민감 시그니처를 기존 `analysis.sensitive`로 바로 연결하면 **자동 캡처의 기본 skip 범위도 함께 달라지므로**, “보관된 값 표시용 라벨”과 “자동 캡처 금지 판정”의 정책을 의식적으로 분리해야 한다. 사용자 동의 없이 수집 정책을 확대/축소하지 않는다.

## 3. 가장 강한 공통 합의 / Chair synthesis

1. **WP-08A의 목적은 “AI가 먼저”가 아니라 “타이핑 없이 Vault 클립 찾기”**다. 상단 AI Categories 아이콘 → 카테고리 → 클립 → 기존 Secure Paste. Recent/Pinned/All/Unclassified 유지.
2. 기존 분류 결과(`flags`)와 인덱스를 재사용하고, 한국어·SSH/키·토큰 URL·신원/금융 후보 등의 규칙을 **평가 가능한 순서로** 확장한다. 사용자 보고 전 보안 라벨을 확신하지 않는다.
3. **신규 클립은 기존 on-save 분류 경로**를 이용한다. 규칙 버전 변경에 따른 **기존 저장 클립 backfill**만 잠금 해제 후/사용자 수동 실행하며, lockEpoch 변경 시 취소한다.
4. **WP-08A에서 별도 10분/1시간 주기 WorkManager 분류를 기본으로 도입하지 않는다.** 사용자 원안은 폐기하지 않고 WP-08B의 실측·정책 검토 항목으로 보류한다. WorkManager의 최소 주기 15분과 실제 실행 지연은 별도 제약이다. 무엇보다 job wake-up ≠ biometric unlock이다.
5. **생성형 LLM과 모델 동반 배포는 WP-08A 범위 밖**이다. WP-08B에서 임베딩 분류가 규칙 대비 충분한 개선을 증명하면 소규모 온디바이스 모델을 검토하고, 자유 생성형 LLM은 **별도 RFC 승인 전까지 보류**한다.
6. 기존 강력 생체인증/오프라인/SQLCipher/IME direct-insert 보안 경계는 회귀 불가 조건이다. `UNKNOWN != LOW`; 자동 모델은 고위험으로 분류된 값을 자동으로 저위험으로 내릴 수 없다.

## 4. 쟁점별 차이와 조정 판단

| 충돌 | Grok / xAI | Claude Opus 5.5 | 의장 권고 | 근거/유보 |
| --- | --- | --- | --- | --- |
| 자동 분류 출처 저장 | WP-08A부터 provenance schema로 향후 ML 확장 준비 | WP-08A는 `clips.flags` 재사용; 모델 확률/출처 테이블은 B에서 | **A에서 v4 migration은 기본 요구 아님**. flags + 수동 태그를 명확히 구분하고, 별도 모델 provenance는 B 시 결정 | A가 수동 override와 버전 업그레이드를 **lossless**하게 처리하지 못하면 최소한의 SQLCipher 메타데이터 변경 허용 |
| 1h periodic worker | job은 가능하나 잠겨 있으면 skip | A부터 주기 옵션 제외 | **A는 no periodic worker** | 30초 자동 잠금 때문에 실효성이 낮고 false expectation 유발 |
| LLM 범위 | C는 나중에 opt-in | C는 WP-08 범위 제외 | **B까지만 이번 결정**. C는 미래의 별도 RFC | 사용자의 “초경량 LLM” 비전 자체를 취소하는 것은 아님 |
| HIGH/UNKNOWN 미리보기 | 초기 RFC에서 HIGH/UNKNOWN 마스킹 제안, Grok 댓글에는 상세 입장 없음 | SENSITIVE와 opaque 후보만 마스킹; UNKNOWN 전체 마스킹 반대 | **민감·opaque 후보 마스킹 기본**, 나머지 UNKNOWN은 인증 후 표시 가능 여부를 보안/UX 테스트로 결정 | “UNKNOWN ≠ LOW”는 위험 분류 규칙이지 모든 UNKNOWN을 숨기라는 뜻은 아님 |
| 인라인 검색 | 폴더가 충분하면 제거, 별도 검색 Activity 향후 검토 | 폴더 제거를 같은 PR에서, 기존 Library 검색 재사용 | **폴더와 IME 검색 제거를 하나의 WP-08A PR**에서 | 30개 고정 제한 해제·페이징/더 보기 필수 |
| 여러 폴더 소속 | 의견 미확정 | 여러 flags 소속 + 하나의 대표 아이콘 | **다중 분류·단일 대표 아이콘** | 우선순위 매핑 deterministic/테스트 가능하게 |

**원칙:** 리뷰어 주장이나 의장 권고가 테스트로 확인된 기능을 대체하지 않는다. S1 보안 독립 시각과 S6 전담 adversarial 검토가 더 있으면 충돌 재개 가능.

## 5. C1–C10 의장 결정 권고안 (사용자 최종 승인 전)

| ID | Chair recommendation | 상태 / 승인 필요 |
| --- | --- | --- |
| **C1** 검색 제거 시점 | 폴더 탐색·페이징·All/Unclassified 확보와 **동일 구현 PR**에서 입력용 IME `EditText` 제거. 메인 Library 검색은 별도 사용처로 유지 | **권고 채택 후보** |
| **C2** 분류/아이콘 | 기존 flags 기반 **다중 소속**, UI 아이콘은 명시적인 deterministic 우선순위로 하나만 표기. 하위 폴더와 빈 항목/미분류 접근 가능 | **권고 채택 후보** |
| **C3** 3개 축 | `Type`(다중), `Sensitivity`(HIGH/MEDIUM/LOW/UNKNOWN), `Utility`(별도). 모델은 규칙의 확정 HIGH 위험을 **내릴 수 없음**. 사용자 수정 시 명시적 확인과 추적 필요 | **권고 채택 후보**; 분류 계산/정책 결합 테스트 필요 |
| **C4** ML | `NativeClassifier` 규칙 확장 → 질적/양적 baseline → WP-08B embedding experiment → 생성형 LLM은 별도 RFC | **권고 채택 후보** |
| **C5** 주기·잠금 | WP-08A: on-save 기존 분석 + unlocked-on-open backfill + 수동. **10min/1h 자동 재분류 없음**. locked-state decrypt/alternate key 금지 | **권고 채택 후보**, UX 설정 기대치 명시 |
| **C6** 저장·백업 | WP-08A: `clips.flags` 재사용, 기존 수동 tag/collection 보존, 파생 flags 재계산. **v4 불필요 여부는 상세 설계 확인 후 결정**; 버전 및 수동 override 충돌 처리 필수. B에서 provenance/vectors SQLCipher 저장 여부 결정 | **조건부** (version/restore lifecycle 설계 필요) |
| **C7** 민감 미리보기 | Lock 상태에서는 목록·태그·count 노출 없음. Unlock 후 HIGH/opaque 후보 내용 가리기 기본; UNKNOWN 전부 마스킹은 보류 | **조건부** (실기기 UX/보안 테스트) |
| **C8** 완료 검증 | 30개 초과 접근, 10/100/1000 synthetic baseline, 한국어/영어·SSH/JWT/token URL/Luhn 반례, lock epoch 중단, CI, Galaxy S23 Ultra/One UI/DeX, Shizuku Δ0 및 타겟 한 번 삽입 | **검증 계획**; 합격 임곗값 측정 전에 사전 정의 |
| **C9** 런타임·라이선스 | A에는 **새 ML 모델 의존성 없음**. B에서 오프라인 모델 라이선스/크기/RSS/열/전력 및 merged manifest INTERNET 권한 검증 | **조건부** (후속 벤치마크) |
| **C10** 실패/미분류 | `Recent`, `Pinned`, `All`, `Unclassified` 접근 항상 가능. 잘못된 라벨·모델 부재는 탐색 차단이나 임의 LOW 다운그레이드로 이어지면 안 됨 | **권고 채택 후보** |

표의 `권고`는 작성자의 현재 조정안이지 maintainer 승인 표시가 아니다. 리뷰어 2건이 10개를 모두 승인했다는 뜻도 아니다.

## 6. WP-08A의 구체적 구현 경계 (의장 제안)

**WP-08A: “기존 규칙 태그 → 키보드 폴더 탐색” 한 가지 목표.**

1. 현행 `NativeClassifier` JNI/Java fallback 분류 비트와 메인 앱 SmartFilter 매핑을 목록화하여 동등성 테스트를 만든다. 한국어 민감어와 자격증명/개발 문자열을 **합성 데이터**로 평가한다. 신형 signature가 민감 자동-capture skip을 바꾸는 경우 별도 정책/사용자 동의를 요구한다.
2. 고정 taxonomy 정의: `CREDENTIAL`, `FINANCE`, `IDENTITY`, `DEVELOPMENT`, `LINK`, `PRIVATE_NOTE`, `OTHER`; 별도 `UNCLASSIFIED` browse. 단일 아이콘 우선순위 표와 다중 flags→folder 매핑, 수동 태그 override 표시(자동 값과 시각적으로 구분) 정의.
3. IME 상단 오른쪽 모드 아이콘, Recent/Pinned/All/AI Categories, 상위 폴더 → 항목 리스트 → 기존 `SecurePasteTransport` 경로. 필수: **offset paging** (지금 최대 `.page(30,0)`에서 31번째 항목 접근성 필수). 폴더 이동은 `commitText` 호출 금지.
4. 폴더 모드가 동작하는 같은 PR에서 **검색 입력창**과 hardware-only 검색 암시 문구 제거. 기존 메인 앱 Library 검색은 보존; 필요 시 보안 재검증된 접근 경로만 제공.
5. 규칙 버전 변경 시 기존 클립은 unlock 후 작은 배치로 backfill. `lockEpoch` 바뀌면 쿼리·쓰기 취소, 더 이상 DB 접근하지 않음. 중복 실행은 idempotent, 실패한 항목은 다음 unlock에 재검사. 필요하면 암호화 DB 내부 버전 메타데이터 설계; migrate 변경이 요구되는지 검토.
6. 반응성·보안 검증: 폴더 미리보기/잠금 카운트, 마스킹, 수동 override, 기존 백업, FTS secure-delete 영향, SQLCipher 데이터 유실 0, Shizuku/system clipboard/삼성 키보드 값 유지, direct paste **정확히 한 번**.
7. S23 Ultra 실기기와 CI에서 테스트하되 **`connectedDebugAndroidTest`/`pm clear`/`adb uninstall`을 실제 개인 Vault 기기에 실행하지 않음**.

**명확한 비포함:** Embedding runtime/model download, 생성형 LLM, periodic background decrypt, alternate crypto envelope, plaintext/vector persistence outside Vault, auto-capture 민감정보 정책 변경, IME 일반 키 입력 처리.

## 7. WP-08B / 이후로 미루는 질문

- 임베딩/소형 분류 head가 현행+개선 규칙 대비 한국어/영어 불명확 문장에 **어느 정도** 성능 향상을 주는지? (실측 없음)
- 모델 패키지와 단말 성능: APK size, memory P50/P95, 발열, 배터리, batch latency. S23 Ultra 실측 필요.
- 임베딩 결과 영속화 여부와 모델 버전 교체·삭제 시 안전 처리. SQLCipher 외 저장 금지.
- 10분/1시간 정책: unlocked foreground/manual 외 어떤 시나리오에서 실질적 가치가 있는지 실기기로 확인. WorkManager wake ≠ key authorization.
- 매우 작은 생성형 LLM이 꼭 필요한 경우 **별도 Council RFC**로 위험/성능/출력 정책 검토.
- 물리 키보드 DeX 한글 입력 문제는 별도로 추적하며 WP-08A의 탐색 기능과 결합하지 않음.

## 8. PR #21 종료 기준과 후속 의사결정

- [x] 접수된 **실제 2개 리뷰**의 출처·차이·상충점을 문서화
- [x] 기존 분류 기능 누락 지적을 의장 정정안에 반영
- [x] C1–C10에 구체적 의장 권고와 조건 기재
- [ ] **사람 메인테이너**가 범위/예산/잠금 정책/미리보기 기본값 승인
- [ ] 필요한 독립 S1/S6 리뷰 또는 위험 인수 판단 명시
- [ ] WP-08A의 실제 정량 합격 기준(성능·민감도 FN, UX 탐색 성능) 합의
- [ ] 이후 별도 **WP-08A 구현 PR** 생성

**결론:** WP-08A는 “Local AI 도입” PR이 아니라 **기존 NativeClassifier를 활용한 Folder-first Secure Paste 탐색 PR**이어야 한다. 임베딩은 WP-08B의 측정 가능한 후보, LLM은 미확정 후속 연구다. 기존 암호화/생체인증/클립보드 비간섭 정책의 후퇴를 허용하지 않는다.
