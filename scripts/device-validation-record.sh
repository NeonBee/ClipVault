#!/bin/sh
# 기기 검증 기록 수집: Android/One UI 버전과 bridge 모드(READY_EVENT / READY_POLL / DEGRADED)를
# markdown 으로 출력한다. clipboard 내용은 읽지 않는다.
#
# 사용:
#   scripts/device-validation-record.sh [--package dev.clipvault.app.debug] [--run-test]
# 전제:
#   - adb 로 기기 1대 연결, ClipVault debug 빌드 설치, capture 가 켜진 상태로 bridge 연결 완료
#   - bridge 모드는 debug(run-as 가능) 빌드에서만 자동으로 읽힌다. release 는 설정 > 진단 화면을 본다
set -eu

PKG="dev.clipvault.app.debug"
RUN_TEST=0
while [ $# -gt 0 ]; do
    case "$1" in
        --package) PKG="$2"; shift 2 ;;
        --run-test) RUN_TEST=1; shift ;;
        *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
    esac
done

command -v adb >/dev/null 2>&1 || { echo "adb 가 PATH 에 없습니다" >&2; exit 1; }
DEVICES=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
[ "$DEVICES" -eq 1 ] || { echo "연결된 기기가 1대여야 합니다(현재 $DEVICES대)" >&2; exit 1; }

prop() { adb shell getprop "$1" | tr -d '\r'; }
pref() {
    adb shell run-as "$PKG" cat shared_prefs/clipvault_settings.xml 2>/dev/null | tr -d '\r' \
        | sed -n "s:.*<string name=\"$1\">\(.*\)</string>.*:\1:p" | head -1
}

ONEUI_RAW=$(prop ro.build.version.oneui)
if [ -n "$ONEUI_RAW" ]; then
    ONEUI="$((ONEUI_RAW / 10000)).$(((ONEUI_RAW % 10000) / 100)) ($ONEUI_RAW)"
else
    ONEUI="해당 없음(삼성 기기 아님 또는 속성 없음)"
fi
SHIZUKU_USER=$(adb shell ps -A -o USER,NAME 2>/dev/null | tr -d '\r' | awk '/shizuku_server/ {print $1; exit}')
BRIDGE_STATE=$(pref bridge_state)
LAST_ERROR=$(pref last_capture_error)

TEST_RESULT="실행 안 함(--run-test 로 실행)"
if [ "$RUN_TEST" -eq 1 ]; then
    ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
    RESULTS="$ROOT/app/build/outputs/androidTest-results/connected"
    # 이전 실행의 XML 이 결과로 섞이지 않게 지운다.
    rm -rf "$RESULTS"
    GRADLE_OK=1
    "$ROOT/gradlew" -p "$ROOT" --no-daemon -q connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=dev.clipvault.app.clipboard.ShizukuRealDeviceInstrumentedTest \
        >/dev/null 2>&1 || GRADLE_OK=0
    # Assume 로 skip 된 테스트도 Gradle 은 성공으로 끝나므로, XML 의 실행·skip·실패 수로 판정한다.
    XML=$(find "$RESULTS" -name 'TEST-*.xml' 2>/dev/null | head -1)
    if [ -z "$XML" ]; then
        TEST_RESULT="FAIL (결과 XML 없음, Gradle 성공=$GRADLE_OK)"
    else
        attr() { sed -n "s/.*<testsuite[^>]* $1=\"\([0-9]*\)\".*/\1/p" "$XML" | head -1; }
        TESTS=$(attr tests); SKIPPED=$(attr skipped); FAILURES=$(attr failures); ERRORS=$(attr errors)
        EXECUTED=$(( ${TESTS:-0} - ${SKIPPED:-0} ))
        COUNTS="실행 $EXECUTED, skip ${SKIPPED:-0}, 실패 $(( ${FAILURES:-0} + ${ERRORS:-0} ))"
        if [ $(( ${FAILURES:-0} + ${ERRORS:-0} )) -gt 0 ] || [ "$GRADLE_OK" -eq 0 ]; then
            TEST_RESULT="FAIL ($COUNTS)"
        elif [ "$EXECUTED" -eq 0 ]; then
            TEST_RESULT="SKIPPED ($COUNTS: Shizuku 미실행·권한 없음 등으로 bridge 검증 0건)"
        else
            TEST_RESULT="PASS ($COUNTS)"
        fi
    fi
fi

cat <<MD
## Device record

수집 시각: $(date -u +%Y-%m-%dT%H:%M:%SZ)

| 항목 | 값 |
| --- | --- |
| Model | $(prop ro.product.model) ($(prop ro.product.device)) |
| Android | $(prop ro.build.version.release) (API $(prop ro.build.version.sdk)) |
| One UI | $ONEUI |
| Build | $(prop ro.build.display.id) |
| Security patch | $(prop ro.build.version.security_patch) |
| Shizuku server user | ${SHIZUKU_USER:-미확인(Shizuku 미실행)} |
| Bridge mode | ${BRIDGE_STATE:-미확인(capture 서비스 중지, 또는 release 빌드 → 설정 > 진단 확인)} |
| Last sanitized error | ${LAST_ERROR:-없음} |
| ShizukuRealDeviceInstrumentedTest | $TEST_RESULT |
MD
