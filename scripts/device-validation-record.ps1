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
    ShizukuRealDeviceInstrumentedTest 를 gradlew.bat 으로 실행하고, 결과 XML 의 실행·skip·실패 수로
    PASS / SKIPPED / FAIL 을 판정한다.

.PARAMETER OutFile
    출력 markdown 을 UTF-8 파일로도 저장한다.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\device-validation-record.ps1 -RunTest
#>
[CmdletBinding()]
param(
    [string]$Package = 'dev.clipvault.app.debug',
    [switch]$RunTest,
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

$shizukuUser = ''
foreach ($line in (Invoke-Adb shell ps -A -o USER,NAME)) {
    if ($line -match 'shizuku_server') { $shizukuUser = ($line -split '\s+')[0]; break }
}

$prefsXml = (Invoke-Adb shell run-as $Package cat shared_prefs/clipvault_settings.xml) -join "`n"
$bridgeState = Get-Pref $prefsXml 'bridge_state'
$lastError = Get-Pref $prefsXml 'last_capture_error'

$testResult = '실행 안 함(-RunTest 로 실행)'
if ($RunTest) {
    $root = Split-Path -Parent $PSScriptRoot
    $results = [System.IO.Path]::Combine($root, 'app', 'build', 'outputs', 'androidTest-results', 'connected')
    # 이전 실행의 XML 이 결과로 섞이지 않게 지운다.
    if (Test-Path -LiteralPath $results) { Remove-Item -LiteralPath $results -Recurse -Force }

    $gradle = Join-Path $root 'gradlew.bat'
    $gradleOk = Invoke-Gradle $gradle @('-p', $root, '--no-daemon', '-q', 'connectedDebugAndroidTest',
        '-Pandroid.testInstrumentationRunnerArguments.class=dev.clipvault.app.clipboard.ShizukuRealDeviceInstrumentedTest')

    # Assume 로 skip 된 테스트도 Gradle 은 성공으로 끝나므로, XML 의 실행·skip·실패 수로 판정한다.
    $xmlFile = $null
    if (Test-Path -LiteralPath $results) {
        $xmlFile = Get-ChildItem -LiteralPath $results -Recurse -Filter 'TEST-*.xml' | Select-Object -First 1
    }
    if ($null -eq $xmlFile) {
        $testResult = 'FAIL (결과 XML 없음, Gradle 성공={0})' -f $gradleOk
    } else {
        [xml]$report = Get-Content -LiteralPath $xmlFile.FullName -Raw -Encoding UTF8
        $suite = $report.SelectSingleNode('//testsuite')
        $count = { param($name) $value = 0; [void][int]::TryParse([string]$suite.GetAttribute($name), [ref]$value); $value }
        $tests = & $count 'tests'
        $skipped = & $count 'skipped'
        $failed = (& $count 'failures') + (& $count 'errors')
        $executed = $tests - $skipped
        $counts = '실행 {0}, skip {1}, 실패 {2}' -f $executed, $skipped, $failed
        if ($failed -gt 0 -or -not $gradleOk) {
            $testResult = "FAIL ($counts)"
        } elseif ($executed -eq 0) {
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
