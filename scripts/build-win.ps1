<#
.SYNOPSIS
    Builds the WireGuard Android APK on Windows 11.

.DESCRIPTION
    Upstream builds the native libraries on Linux/macOS: tunnel/tools/libwg-go's
    Makefile needs flock(1) and fetches a Linux-hosted Go toolchain, and
    elf-cleaner needs a POSIX mmap(2) host compiler. Neither works on Windows.

    This script provisions a native Windows Go toolchain (verified download,
    patched with the same CLOCK_BOOTTIME fix the Makefile applies) and exports
    WG_GOROOT/WG_GO_EXE. tunnel/tools/CMakeLists.txt takes a WIN32 branch that
    cross-compiles wireguard-go with it and skips elf-cleaner, which is a no-op
    for these artifacts at api-level 24.

.PARAMETER Task
    Gradle task to run. Defaults to :ui:assembleDebug.

.PARAMETER JdkHome
    JDK 17+ home. Auto-detected when omitted.

.PARAMETER AndroidSdk
    Android SDK root. Auto-detected from ANDROID_HOME/ANDROID_SDK_ROOT when omitted.

.PARAMETER NdkVersion
    NDK version to build against. Defaults to the newest NDK installed.

.PARAMETER GoRoot
    Existing patched GOROOT to reuse instead of downloading Go.

.PARAMETER SkipSubmodules
    Skip `git submodule update --init`.

.PARAMETER Clean
    Run `gradlew clean` before the build task.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\build-win.ps1
#>

[CmdletBinding()]
param(
    [string]$Task = ':ui:assembleDebug',
    [string]$JdkHome,
    [string]$AndroidSdk,
    [string]$NdkVersion,
    [string]$GoRoot,
    [switch]$SkipSubmodules,
    [switch]$Clean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot

function Write-Step([string]$Message) {
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "    $Message" -ForegroundColor Green
}

function Write-Note([string]$Message) {
    Write-Host "    $Message" -ForegroundColor DarkGray
}

function Get-EnvOrDefault([string]$Name, [string]$Default) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
    return $value
}

# ---------------------------------------------------------------------------
# Git submodules (wireguard-tools + elf-cleaner live in tunnel/tools)
# ---------------------------------------------------------------------------
if (-not $SkipSubmodules) {
    Write-Step 'Checking git submodules'
    $status = & git -C $RepoRoot submodule status
    if ($LASTEXITCODE -ne 0) { throw 'git submodule status failed.' }
    if ($status -match '^-') {
        Write-Note 'Initializing submodules (network required)...'
        & git -C $RepoRoot submodule update --init --depth 1
        if ($LASTEXITCODE -ne 0) { throw 'git submodule update failed.' }
    }
    Write-Ok 'Submodules present'
}

# ---------------------------------------------------------------------------
# JDK 17+
# ---------------------------------------------------------------------------
Write-Step 'Locating JDK 17+'

function Get-JavaMajor([string]$JavaExe) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $JavaExe -version 2>&1 | Out-String
    } catch {
        return 0
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

if (-not $JdkHome) {
    $candidates = @()
    foreach ($name in @('JAVA_HOME', 'JDK_HOME')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) { $candidates += $value }
    }
    $candidates += @(
        'C:\DevDisk\DevTools\jdk17\jdk-17.0.20.1+1'
        'C:\Program Files\Eclipse Adoptium\jdk-17*'
        'C:\Program Files\Java\jdk-17*'
        'C:\Program Files\Java\jdk-2*'
        'C:\Program Files\Android\Android Studio\jbr'
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-17*"
    )
    foreach ($candidate in $candidates) {
        $resolved = Get-Item -Path $candidate -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($resolved -and (Test-Path (Join-Path $resolved.FullName 'bin\java.exe'))) {
            if ((Get-JavaMajor (Join-Path $resolved.FullName 'bin\java.exe')) -ge 17) {
                $JdkHome = $resolved.FullName
                break
            }
        }
    }
}

if (-not $JdkHome) {
    throw 'No JDK 17+ found. Pass -JdkHome <path> or set JAVA_HOME.'
}
$JavaExe = Join-Path $JdkHome 'bin\java.exe'
$javaMajor = Get-JavaMajor $JavaExe
if ($javaMajor -lt 17) {
    throw "JDK at $JdkHome is version $javaMajor; 17 or newer is required."
}
Write-Ok "JDK $javaMajor at $JdkHome"

# ---------------------------------------------------------------------------
# Android SDK + NDK
# ---------------------------------------------------------------------------
Write-Step 'Locating Android SDK'

if (-not $AndroidSdk) {
    $AndroidSdk = Get-EnvOrDefault 'ANDROID_HOME' (Get-EnvOrDefault 'ANDROID_SDK_ROOT' '')
}
if (-not $AndroidSdk -and (Test-Path "$env:LOCALAPPDATA\Android\Sdk")) {
    $AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not $AndroidSdk -or -not (Test-Path $AndroidSdk)) {
    throw 'Android SDK not found. Pass -AndroidSdk <path> or set ANDROID_HOME.'
}
Write-Ok "SDK at $AndroidSdk"

$NdkRoot = Join-Path $AndroidSdk 'ndk'
if (-not (Test-Path $NdkRoot)) {
    throw "No NDK under $NdkRoot. Install one with: sdkmanager `"ndk;28.2.13676358`""
}
if (-not $NdkVersion) {
    $NdkVersion = Get-ChildItem $NdkRoot -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe') } |
        Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty Name
}
if (-not $NdkVersion) {
    throw "No usable NDK with a windows-x86_64 LLVM toolchain under $NdkRoot."
}
$NdkHome = Join-Path $NdkRoot $NdkVersion
Write-Ok "NDK $NdkVersion at $NdkHome"

# ---------------------------------------------------------------------------
# Windows Go toolchain for wireguard-go
#
# The Makefile hardcodes hosts it supports and downloads a Linux-hosted Go, so
# cross-compiling requires a native Windows Go. Same version, same patch.
# ---------------------------------------------------------------------------
Write-Step 'Preparing Windows Go toolchain'

$makefile = Join-Path $RepoRoot 'tunnel\tools\libwg-go\Makefile'
$goVersion = (Select-String -Path $makefile -Pattern '^GO_VERSION\s*:=\s*(\S+)').Matches.Groups[1].Value
if (-not $goVersion) { throw "Could not read GO_VERSION from $makefile" }

# SHA-256 of go<version>.windows-amd64.zip, from https://go.dev/dl/?mode=json&include=all
$goSha256 = @{
    '1.24.3' = 'be9787cb08998b1860fe3513e48a5fe5b96302d358a321b58e651184fa9638b3'
}
if (-not $goSha256.ContainsKey($goVersion)) {
    throw "No known SHA-256 for Go $goVersion on windows-amd64. Add it to `$goSha256 in this script (see https://go.dev/dl/?mode=json&include=all)."
}

$toolchainRoot = Join-Path (Get-EnvOrDefault 'GRADLE_USER_HOME' (Join-Path $HOME '.gradle')) 'caches\golang'
$goExtractDir = Join-Path $toolchainRoot "go$goVersion-windows-amd64"
$goRootCandidate = Join-Path $goExtractDir 'go'
# Written only after extraction and patching both succeed, so an interrupted
# run is retried instead of silently reusing an unpatched toolchain.
$goReadyMarker = Join-Path $goExtractDir '.patched'

if ($GoRoot) {
    $goRootCandidate = (Resolve-Path $GoRoot).Path
    Write-Ok "Using supplied GOROOT at $goRootCandidate"
} elseif (Test-Path $goReadyMarker) {
    Write-Ok "Reusing Go $goVersion at $goRootCandidate"
} else {
    $archive = Join-Path $toolchainRoot "go$goVersion.windows-amd64.zip"
    New-Item -ItemType Directory -Force -Path $toolchainRoot | Out-Null

    if (-not (Test-Path $archive)) {
        $url = "https://dl.google.com/go/go$goVersion.windows-amd64.zip"
        Write-Note "Downloading $url"
        $tmp = "$archive.part"
        Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
        Move-Item -Force $tmp $archive
    }

    Write-Note 'Verifying SHA-256...'
    $actual = (Get-FileHash -Path $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $goSha256[$goVersion]) {
        Remove-Item -Force $archive
        throw "Go archive checksum mismatch (expected $($goSha256[$goVersion]), got $actual). Deleted; re-run to redownload."
    }

    Write-Note 'Extracting...'
    if (Test-Path $goExtractDir) { Remove-Item -Recurse -Force $goExtractDir }
    New-Item -ItemType Directory -Force -Path $goExtractDir | Out-Null
    Expand-Archive -Path $archive -DestinationPath $goExtractDir -Force

    # Same fix the Makefile applies: timers must use CLOCK_BOOTTIME so expired
    # timers are accounted for after the device sleeps.
    $patchFile = Join-Path $RepoRoot 'tunnel\tools\libwg-go\goruntime-boottime-over-monotonic.diff'
    $patchExe = $null
    $gitCmd = Get-Command git -ErrorAction SilentlyContinue
    if ($gitCmd) {
        # git.exe ships in <Git>\cmd or <Git>\mingw64\bin; patch.exe is in <Git>\usr\bin.
        $dir = Split-Path -Parent $gitCmd.Source
        for ($i = 0; $i -lt 4 -and $dir; $i++) {
            $candidate = Join-Path $dir 'usr\bin\patch.exe'
            if (Test-Path $candidate) { $patchExe = $candidate; break }
            $dir = Split-Path -Parent $dir
        }
    }
    if (-not $patchExe) {
        throw 'patch.exe not found (ships with Git for Windows). Install Git for Windows, or pass -GoRoot with an already-patched GOROOT.'
    }

    Write-Note 'Applying CLOCK_BOOTTIME runtime patch...'
    & $patchExe -p1 -f -N -r- -d $goRootCandidate -i $patchFile | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to apply $patchFile to GOROOT (exit $LASTEXITCODE)."
    }
    New-Item -ItemType File -Force -Path $goReadyMarker | Out-Null
    Write-Ok "Installed Go $goVersion at $goRootCandidate"
}

$goExe = Join-Path $goRootCandidate 'bin\go.exe'
if (-not (Test-Path $goExe)) { throw "Go executable missing at $goExe" }
$goReportedVersion = (& $goExe version).Split(' ')[2]
Write-Ok "go $goReportedVersion"

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
Write-Step "Configuring build environment"
$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:ANDROID_NDK_HOME = $NdkHome
$env:WG_GOROOT = $goRootCandidate
$env:WG_GO_EXE = $goExe
if ([string]::IsNullOrWhiteSpace($env:GOPROXY)) {
    $env:GOPROXY = 'https://goproxy.cn,direct'
    Write-Note "GOPROXY defaulted to $env:GOPROXY"
}
Write-Ok "JAVA_HOME=$JdkHome"
Write-Ok "ANDROID_HOME=$AndroidSdk"
Write-Ok "WG_GOROOT=$goRootCandidate"

# gradlew.bat is gitignored upstream, so fall back to the wrapper jar directly.
$gradlewBat = Join-Path $RepoRoot 'gradlew.bat'
$wrapperJar = Join-Path $RepoRoot 'gradle\wrapper\gradle-wrapper.jar'

function Invoke-Gradle([string[]]$GradleArgs) {
    if (Test-Path $gradlewBat) {
        & $gradlewBat @GradleArgs
    } else {
        & $JavaExe -classpath $wrapperJar org.gradle.wrapper.GradleWrapperMain @GradleArgs
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE (task: $($GradleArgs -join ' '))"
    }
}

Push-Location $RepoRoot
try {
    if ($Clean) {
        Write-Step 'Cleaning'
        Invoke-Gradle @('clean')
    }

    Write-Step "Building with Gradle task $Task"
    Invoke-Gradle @($Task, '--console=plain')
} finally {
    Pop-Location
}

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------
Write-Step 'Build artifacts'
$uiBuild = Join-Path $RepoRoot 'ui\build\outputs\apk'
if (Test-Path $uiBuild) {
    $apks = Get-ChildItem $uiBuild -Recurse -Filter '*.apk' |
        Sort-Object LastWriteTime -Descending
    if ($apks) {
        foreach ($apk in $apks) {
            $sizeMb = [math]::Round($apk.Length / 1MB, 1)
            Write-Host "    $($apk.FullName) ($sizeMb MB)" -ForegroundColor Green
        }
    } else {
        Write-Note "No APK under $uiBuild"
    }
} else {
    Write-Note "No APK output directory at $uiBuild"
}

Write-Host ''
Write-Host 'Build succeeded.' -ForegroundColor Green
