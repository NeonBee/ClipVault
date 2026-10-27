<#
.SYNOPSIS
    Build, safely reinstall and launch the ClipVault debug APK on a selected Android device.

.DESCRIPTION
    Windows PowerShell 5.1 / PowerShell 7.
    Defaults to an incremental :app:assembleDebug, adb install -r, then MainActivity launch.
    Never uninstalls the app, clears its data, runs device instrumentation or starts Shizuku.
    The local debug signing key must match the already installed APK to preserve vault data.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1 -SkipBuild
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1 -LaunchOnly
.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-debug.ps1 -Serial R5CWXXXXXXX
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$LaunchOnly,
    [switch]$NoLaunch,
    [string]$Serial
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

if ($LaunchOnly -and $NoLaunch) {
    throw 'Use either -LaunchOnly or -NoLaunch, not both.'
}
if ($LaunchOnly -and $SkipBuild) {
    throw '-LaunchOnly already skips build and installation; remove -SkipBuild.'
}

$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$Package = 'dev.clipvault.app.debug'
$Component = "$Package/dev.clipvault.app.MainActivity"

function Find-Adb {
    $command = Get-Command 'adb' -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $candidates = @()
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($sdk)) {
            $candidates += (Join-Path $sdk 'platform-tools\adb.exe')
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw 'adb not found. Install Android SDK Platform-Tools or configure ANDROID_HOME / PATH.'
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory=$true)][string]$Executable,
        [Parameter(Mandatory=$true)][string[]]$Arguments,
        [Parameter(Mandatory=$true)][string]$Description
    )
    # Windows PowerShell 5.1 may turn native stderr into ErrorRecord objects when
    # ErrorActionPreference=Stop. Use the native process exit code as the source of truth.
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) {
        throw "$Description failed (exit code $exitCode)."
    }
}

if (-not $LaunchOnly -and -not $SkipBuild) {
    $Gradle = Join-Path $Root 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) {
        throw "Gradle wrapper missing: $Gradle"
    }

    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and
        -not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $studioJbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
        if (Test-Path -LiteralPath (Join-Path $studioJbr 'bin\java.exe')) {
            $env:JAVA_HOME = $studioJbr
        }
    }
    if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME) -and
        -not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $defaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        if (Test-Path -LiteralPath $defaultSdk -PathType Container) {
            $env:ANDROID_HOME = $defaultSdk
        }
    }
}

$Adb = Find-Adb

# Never guess the target if more than one device is connected.
$previous = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $deviceLines = @(& $Adb devices)
    $adbExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previous
}
if ($adbExit -ne 0) { throw 'adb devices failed.' }

$connected = @(
    foreach ($line in $deviceLines) {
        if ([string]$line -match '^\s*(\S+)\s+device(?:\s|$)') { $Matches[1] }
    }
)
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    if ($connected -notcontains $Serial) {
        throw "Requested device '$Serial' is not connected and authorized. Check adb devices."
    }
    $Device = $Serial
} else {
    if ($connected.Count -ne 1) {
        throw ("Expected exactly one authorized device, found {0}. Use -Serial if multiple devices are connected; check USB debugging / adb devices." -f $connected.Count)
    }
    $Device = $connected[0]
}

Write-Host "[ClipVault] Device: $Device"
Write-Host "[ClipVault] Repository: $Root"

if ($LaunchOnly) {
    Write-Host '[ClipVault] Launch only (no build, no install).'
    Invoke-Checked -Executable $Adb -Arguments @('-s', $Device, 'shell', 'am', 'start', '-n', $Component) -Description 'Launch'
    Write-Host '[ClipVault] Done.'
    return
}

if (-not $SkipBuild) {
    Write-Host '[ClipVault] Incremental debug build (no clean, no device tests).'
    Invoke-Checked -Executable $Gradle -Arguments @(':app:assembleDebug') -Description 'Gradle build'
} else {
    Write-Host '[ClipVault] Build skipped; existing APK will be used. It may not include recent code changes.'
}

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "Debug APK not found: $Apk. Run without -SkipBuild to create it."
}
$apkInfo = Get-Item -LiteralPath $Apk
Write-Host ("[ClipVault] APK: {0} ({1:N1} MB; modified {2})" -f $Apk, ($apkInfo.Length / 1MB), $apkInfo.LastWriteTime)

Write-Host '[ClipVault] Installing with adb install -r (preserves existing app data).'
try {
    Invoke-Checked -Executable $Adb -Arguments @('-s', $Device, 'install', '-r', $Apk) -Description 'Safe APK replacement'
} catch {
    throw ("Installation failed. The existing app was NOT uninstalled or cleared. " +
        "A different debug signing certificate can cause INSTALL_FAILED_UPDATE_INCOMPATIBLE. " +
        "Do not uninstall to work around this: doing so loses the vault and Keystore key. Details: " + $_.Exception.Message)
}

if (-not $NoLaunch) {
    Write-Host '[ClipVault] Launching Main Vault (QuickPaste remains available through the launcher shortcut).'
    Invoke-Checked -Executable $Adb -Arguments @('-s', $Device, 'shell', 'am', 'start', '-n', $Component) -Description 'Launch'
}
Write-Host '[ClipVault] Done. Vault data was not deliberately deleted or reset.'
