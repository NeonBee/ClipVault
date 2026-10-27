# Windows 실기기 검증: 빌드·설치·실행 자동화

ClipVault / Samsung DeX용 debug APK를 반복 배포하는 절차다. Windows PowerShell 5.1 / PowerShell 7 환경을 지원한다.

> **데이터 보존:** `adb uninstall`, `adb shell pm clear` 및 실기기에서의 `gradlew connectedDebugAndroidTest`를 실행하지 않는다. Android 테스트 태스크가 완료 뒤 앱을 uninstall하면 Vault DB, Keystore 키, Shizuku 권한을 잃을 수 있다. 별도의 CI 에뮬레이터에서 실행하는 것은 무방하다.

## 1. 최초 준비

- Android Studio + Android SDK Platform-Tools(ADB), SDK Platform `37.0`, NDK `27.2.12479018`, CMake `3.22.1`
- JDK 17 또는 21 (Android Studio 내 `jbr` 활용 가능)
- 휴대폰에서 USB 디버깅 승인 또는 이미 연결된 ADB 무선 디버깅
- 중요한 Vault가 있다면 먼저 암호화된 `.cvault` 백업을 별도로 보관

PR #15의 compact 검색 변경을 검증하려면 해당 PR 브랜치로 이동하여 최신 코드를 가져온다.
`main`에는 아직 PR #15 수정사항이 없다.

~~~powershell
git fetch origin
git switch feature/wp06-usable-viewport
git pull --ff-only origin feature/wp06-usable-viewport
adb devices
~~~

`adb devices` 출력이 `device` 상태여야 한다. `unauthorized`라면 휴대폰에서 USB 디버깅을 승인한다.
자동 스크립트는 PATH 외에도 `ANDROID_HOME`, `ANDROID_SDK_ROOT`, 표준 Android SDK 설치 경로를 탐색한다.
JDK/SDK 환경 변수가 없으면 Android Studio의 표준 JBR/SDK 위치를 찾는다.

## 2. 평소에는 한 명령

저장소 루트의 PowerShell에서:

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1
~~~

이 명령은 다음을 순서대로 수행한다.

1. 승인된 ADB 기기가 정확히 한 대인지 확인 (여러 대면 임의 선택 금지).
2. `gradlew.bat :app:assembleDebug`로 **증분 빌드** (clean이나 실기기 테스트를 하지 않음).
3. `app\build\outputs\apk\debug\app-debug.apk` 확인.
4. `adb -s <serial> install -r <apk>`로 기존 설치를 **데이터 보존 방식**으로 갱신.
5. `dev.clipvault.app.debug/dev.clipvault.app.MainActivity` 실행.

관리자 권한은 필요 없다. `-ExecutionPolicy Bypass`는 해당 PowerShell 프로세스에만 적용된다.

### 빠른 옵션

| 용도 | 명령의 마지막에 추가할 옵션 |
| --- | --- |
| 변경한 소스 빌드 후 설치/실행 | 기본 실행 (옵션 없음) |
| **기존 APK 재설치**, 빌드 생략 | `-SkipBuild` |
| 설치된 **앱만 열기**, 빌드/설치 생략 | `-LaunchOnly` |
| 설치까지만 하고 열지 않기 | `-NoLaunch` |
| ADB 기기 여러 대 중 지정 | `-Serial R5CWXXXXXXX` |

예시:

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1 -SkipBuild
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1 -LaunchOnly
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1 -Serial R5CWXXXXXXX
~~~

**주의:** `-SkipBuild`는 마지막에 생성한 로컬 APK를 다시 설치할 뿐, 새 git 변경사항을 빌드하지 않는다.
PR #15 검증 전에는 기본 명령으로 한 번 빌드해야 최신 코드가 반영된다.

## 3. PR #15 DeX compact 검색 확인

스크립트는 빌드·설치·앱 실행까지만 자동화한다. DeX 창 크기, 생체인증, 검색 동작은 실기기에서 확인한다.

| 검증 동작 | 기대 결과 |
| --- | --- |
| Main Vault 높이를 작게 조정 (Compose 사용 가능 높이 420dp 미만) | inline 검색 대신 상단 좌측 돋보기 노출 |
| 돋보기 클릭 | inline 검색창이 펼쳐지고 검색어 입력 focus |
| 검색어 입력 후 검색창 다시 접기 | 검색 필터/결과 유지, 돋보기에 active query badge |
| 창 높이 복구 | 420dp 이상에서 일반 inline 검색창 자동 복귀 |

420dp는 앱 내부 사용 가능 높이이므로 DeX 창 테두리 바깥 치수와 다를 수 있다.
Main Vault의 freeform 최소 크기(320×384dp), QuickPaste 최소 크기(240×260dp)도 유지되는지 확인한다.

QuickPaste는 `exported=false` Activity이므로 직접 `adb shell am start`로 시작하는 방식에 의존하지 않는다.
앱 아이콘이나 DeX 작업표시줄 아이콘을 길게 눌러 **Quick paste** 단축키로 연다.

검증 결과는 `docs/WP06_DEX_UX.md`의 3단계 체크리스트에 PASS/PARTIAL/FAIL로 기록한다.
세션 관련 진단은 기존 `scripts/device-validation-record.ps1`을 사용한다.

## 4. 문제 해결 및 안전 주의

- **기기 없음 / unauthorized / offline:** `adb devices` 확인, 휴대폰에서 USB 디버깅 승인.
- **여러 기기:** `-Serial`로 명시적으로 지정.
- **무선 디버깅:** Android 설정에서 기능을 켠 뒤 `adb pair <ip:pair-port>`, `adb connect <ip:connect-port>`로 연결한다. 휴대폰 핫스팟 사용 시 무선 디버깅이 비활성화되는 모델은 USB를 쓴다. `adb tcpip 5555`를 공공 네트워크에 상시 노출하지 않는다.
- **빌드 실패:** JDK, SDK Platform, NDK, CMake 버전과 Gradle 의존성 다운로드 환경을 확인.
- **APK 없음:** 기본 명령으로 한 번 빌드.
- **INSTALL_FAILED_UPDATE_INCOMPATIBLE:** 서명이 다른 APK다. 앱을 삭제해서 해결하지 않는다. 동일 debug keystore를 사용하던 로컬 환경으로 돌아가야 기존 데이터를 보존하면서 갱신할 수 있다.
- **GitHub Actions `clipvault-debug` APK:** CI 러너에서 서명한 debug APK는 로컬 PC의 APK와 서명이 다를 수 있다. 기존 Vault를 보존해야 한다면 CI 산출물을 무조건 덮어 설치하지 않는다.
- **앱 설치 후 수집 안 됨:** Shizuku 서버가 shell 사용자로 실행 중인지, ClipVault capture가 켜져 있는지 확인. 배포 스크립트는 Shizuku 권한을 변경하거나 기기 보안 잠금을 우회하지 않는다.

`adb install -r`은 앱 삭제/데이터 초기화 없이 설치하려는 방법이지만, APK 자체의 데이터 마이그레이션까지 되돌리지는 못한다.
중요한 데이터는 암호화 백업을 따로 보관한다.

## 5. 수동으로 실행한다면

~~~powershell
.\gradlew.bat :app:assembleDebug
adb devices
adb -s <serial> install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> shell am start -n dev.clipvault.app.debug/dev.clipvault.app.MainActivity
~~~
