<#
.SYNOPSIS
    WP-04 기기 검증 기록 수집: Android/One UI 버전과 bridge 모드(READY_EVENT / READY_POLL / DEGRADED)를
    markdown 표로 출력한다. clipboard 내용은 읽지 않는다.

.DESCRIPTION
    Windows PowerShell 5.1 과 PowerShell 7 에서 동작한다.
    전제: adb 로 기기 1대 연결, ClipVault debug 빌드 설치, capture 가 켜진 상태로 bridge 연결 완료.
    bridge 모드는 debug(run-as 가능) 빌드에서만 자동으로 읽힌다. release 빌드는 설정 > 진단 화면을 본다.

.PARAMETER Package
    대상 applicationId. 기본값은 debug 빌드의 dev.clipvault.app.debug.

.PARAMETER RunTest
    ShizukuRealDeviceInstrumentedTest 를 `adb shell am instrument` 로 실행하고, AndroidJUnitRunner 상태
    코드로 PASS / SKIPPED / FAIL 을 판정한다. 앱과 앱 데이터는 지우지 않는다.
    Gradle 의 connectedDebugAndroidTest 는 테스트 뒤 앱을 uninstall 해 vault 데이터·Keystore 키·
    Shizuku 권한을 없애므로 쓰지 않는다. 대신 installDebug / installDebugAndroidTest(adb install -r,
    데이터 보존)로 현재 checkout 의 빌드를 덮어 설치한다. 서명이 다르면 설치가 실패할 뿐 기존 앱은
    지우지 않는다.

.PARAMETER NoInstall
    -RunTest 에서 설치 단계를 건너뛰고 이미 설치된 앱·테스트 APK 로만 실행한다.

.PARAMETER OutFile
    출력 markdown 을 UTF-8 파일로도 저장한다.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\device-validation-record.ps1 -RunTest
#>
[CmdletBinding()]
param(
    [string]$Package = 'dev.clipvault.app.debug',
    [switch]$RunTest,
    [switch]$NoInstall,
    [string]$OutFile
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Find-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidates = @()
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path ([string]$env:LOCALAPPDATA) 'Android\Sdk'))) {
        if ($sdk) { $candidates += (Join-Path $sdk 'platform-tools\adb.exe') }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    throw 'adb 를 찾지 못했습니다. PATH 에 platform-tools 를 추가하거나 ANDROID_HOME 을 설정하세요.'
}

$Adb = Find-Adb

function Invoke-Adb {
    # adb 의 stdout 줄 배열을 CR 없이 돌려준다. 실패해도 빈 값으로 처리한다.
    # Windows PowerShell 5.1 은 ErrorActionPreference=Stop 에서 네이티브 명령의 stderr 를 종료 오류로
    # 바꾼다(예: release 빌드의 "run-as: package not debuggable"). 이 함수 안에서만 Continue 로 두고
    # stderr 레코드는 버린다.
    $ErrorActionPreference = 'Continue'
    $output = & $Adb @args 2>&1 | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }
    if ($null -eq $output) { return @() }
    return @($output | ForEach-Object { ([string]$_).TrimEnd("`r") })
}

function Invoke-Gradle([string]$Gradle, [string[]]$Arguments) {
    # Invoke-Adb 와 같은 이유로 stderr 를 종료 오류로 바꾸지 않는다. 성공 여부는 exit code 로 본다.
    $ErrorActionPreference = 'Continue'
    & $Gradle @Arguments *> $null
    return ($LASTEXITCODE -eq 0)
}

function Get-Prop([string]$Name) {
    return ((Invoke-Adb shell getprop $Name) -join '').Trim()
}

function Get-Pref([string]$Xml, [string]$Name) {
    $pattern = '<string name="' + [regex]::Escape($Name) + '">(.*?)</string>'
    $match = [regex]::Match($Xml, $pattern)
    if ($match.Success) { return $match.Groups[1].Value }
    return ''
}

function Or-Default([string]$Value, [string]$Fallback) {
    if ([string]::IsNullOrEmpty($Value)) { return $Fallback }
    return $Value
}

$devices = @(Invoke-Adb devices | Where-Object { $_ -match "^\S+\s+device$" })
if ($devices.Count -ne 1) {
    throw ("연결된 기기가 1대여야 합니다(현재 {0}대)." -f $devices.Count)
}

$oneUiRaw = Get-Prop 'ro.build.version.oneui'
$oneUiValue = 0
if ([int]::TryParse($oneUiRaw, [ref]$oneUiValue) -and $oneUiValue -gt 0) {
    $oneUi = '{0}.{1} ({2})' -f [math]::Floor($oneUiValue / 10000), [math]::Floor(($oneUiValue % 10000) / 100), $oneUiRaw
} else {
    $oneUi = '해당 없음(삼성 기기 아님 또는 속성 없음)'
}

# Shizuku 서버는 shell(ADB) 또는 root 계정의 "shizuku_server" 프로세스다. 매니저 앱(u0_aNNN)만
# 떠 있으면 서버는 멈춘 상태다. 따옴표 필수: PowerShell 은 USER,NAME,ARGS 를 배열로 보고 세 인자로 나눈다.
$shizukuUser = ''
$shizukuManagerOnly = $false
foreach ($line in (Invoke-Adb shell ps -A -o 'USER,NAME,ARGS')) {
    if ($line -notmatch 'shizuku') { continue }
    $user = ($line.Trim() -split '\s+')[0]
    if ($user -eq 'shell' -or $user -eq 'root') { $shizukuUser = $user; break }
    $shizukuManagerOnly = $true
}
if (-not $shizukuUser -and $shizukuManagerOnly) { $shizukuUser = '미실행(매니저 앱만 실행 중, 서버 시작 필요)' }

$prefsXml = (Invoke-Adb shell run-as $Package cat shared_prefs/clipvault_settings.xml) -join "`n"
$bridgeState = Get-Pref $prefsXml 'bridge_state'
$lastError = Get-Pref $prefsXml 'last_capture_error'

$testResult = '실행 안 함(-RunTest 로 실행)'
if ($RunTest) {
    $testPackage = "$Package.test"
    $runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
    $installOk = $true
    if (-not $NoInstall) {
        $root = Split-Path -Parent $PSScriptRoot
        $gradle = Join-Path $root 'gradlew.bat'
        # adb install -r: 앱 데이터·Shizuku 권한 유지. connectedDebugAndroidTest 는 uninstall 하므로 금지.
        $installOk = Invoke-Gradle $gradle @('-p', $root, '--no-daemon', '-q', 'installDebug', 'installDebugAndroidTest')
    }
    $instrumentations = (Invoke-Adb shell pm list instrumentation) -join "`n"
    if (-not $installOk) {
        $testResult = 'FAIL (설치 실패: 설치된 앱과 서명이 다를 수 있음. 기존 앱은 지우지 않았음)'
    } elseif ($instrumentations -notmatch [regex]::Escape($runner)) {
        $testResult = "FAIL (테스트 APK $testPackage 가 설치되어 있지 않음. -NoInstall 없이 실행)"
    } else {
        $raw = Invoke-Adb shell am instrument -r -w -e class `
            dev.clipvault.app.clipboard.ShizukuRealDeviceInstrumentedTest $runner
        # AndroidJUnitRunner 상태 코드: 1 시작, 0 성공, -1 오류, -2 실패, -3 무시, -4 Assume skip.
        $passed = 0; $skipped = 0; $failed = 0
        foreach ($line in $raw) {
            if ($line -match '^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)') {
                switch ([int]$Matches[1]) {
                    0 { $passed++ }
                    -1 { $failed++ }
                    -2 { $failed++ }
                    -3 { $skipped++ }
                    -4 { $skipped++ }
                }
            }
        }
        $crashed = (($raw -join "`n") -match 'INSTRUMENTATION_FAILED|Process crashed')
        $counts = '실행 {0}, skip {1}, 실패 {2}' -f ($passed + $failed), $skipped, $failed
        if ($failed -gt 0 -or $crashed) {
            $testResult = "FAIL ($counts)"
        } elseif ($passed -eq 0) {
            $testResult = "SKIPPED ($counts`: Shizuku 미실행·권한 없음 등으로 bridge 검증 0건)"
        } else {
            $testResult = "PASS ($counts)"
        }
    }
}

$rows = @(
    @('Model', ('{0} ({1})' -f (Get-Prop 'ro.product.model'), (Get-Prop 'ro.product.device'))),
    @('Android', ('{0} (API {1})' -f (Get-Prop 'ro.build.version.release'), (Get-Prop 'ro.build.version.sdk'))),
    @('One UI', $oneUi),
    @('Build', (Get-Prop 'ro.build.display.id')),
    @('Security patch', (Get-Prop 'ro.build.version.security_patch')),
    @('Shizuku server user', (Or-Default $shizukuUser '미확인(Shizuku 미실행)')),
    @('Bridge mode', (Or-Default $bridgeState '미확인(capture 서비스 중지, 또는 release 빌드 → 설정 > 진단 확인)')),
    @('Last sanitized error', (Or-Default $lastError '없음')),
    @('ShizukuRealDeviceInstrumentedTest', $testResult)
)

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('## Device record')
$lines.Add('')
$lines.Add(('수집 시각: {0}' -f [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')))
$lines.Add('')
$lines.Add('| 항목 | 값 |')
$lines.Add('| --- | --- |')
foreach ($row in $rows) { $lines.Add(('| {0} | {1} |' -f $row[0], $row[1])) }

$lines | ForEach-Object { Write-Output $_ }
if ($OutFile) {
    [System.IO.File]::WriteAllLines($OutFile, $lines.ToArray(), (New-Object System.Text.UTF8Encoding $false))
}
