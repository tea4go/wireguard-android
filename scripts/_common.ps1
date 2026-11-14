# Copyright 2026 WireGuard Android Contributors
# SPDX-License-Identifier: Apache-2.0
#
# 构建脚本通用函数（Android / Gradle 版）。
# 与 harmony 版对应：版本号读写、Gradle 调用、APK 校验、签名配置读取。

$ErrorActionPreference = "Stop"

$script:RepoRoot        = Split-Path -Parent $PSScriptRoot
$script:GradleProps     = Join-Path $script:RepoRoot 'gradle.properties'
$script:UIBuildDir      = Join-Path $script:RepoRoot 'ui\build\outputs'

<#
.SYNOPSIS
  读取 gradle.properties 中的某个属性值
.OUTPUTS
  [string] 属性值；不存在返回空字符串
#>
function Get-GradleProperty {
    param([Parameter(Mandatory = $true)][string]$Name)

    if (-not (Test-Path -LiteralPath $script:GradleProps)) {
        Write-Host "错误：未找到 gradle.properties：$script:GradleProps" -ForegroundColor Red
        return ''
    }

    foreach ($line in [System.IO.File]::ReadAllLines($script:GradleProps)) {
        if ($line -match "^\s*$([regex]::Escape($Name))\s*=\s*(.*?)\s*$") {
            return $Matches[1]
        }
    }
    return ''
}

<#
.SYNOPSIS
  写入 gradle.properties 中的某个属性值
.DESCRIPTION
  只替换目标行，保留其余内容、顺序与 CRLF 换行；UTF-8 无 BOM 写回。
.OUTPUTS
  [bool] 成功返回 $true
#>
function Set-GradleProperty {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )

    if (-not (Test-Path -LiteralPath $script:GradleProps)) {
        Write-Host "错误：未找到 gradle.properties：$script:GradleProps" -ForegroundColor Red
        return $false
    }

    try {
        $content = [System.IO.File]::ReadAllText($script:GradleProps)
        $pattern = "(?m)^\s*$([regex]::Escape($Name))\s*=.*$"
        $replacement = "$Name=$Value"
        $evaluator = [System.Text.RegularExpressions.MatchEvaluator] {
            param($m)
            return $replacement
        }
        $updated = [regex]::Replace($content, $pattern, $evaluator)

        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($script:GradleProps, $updated, $utf8NoBom)
        return $true
    } catch {
        Write-Host "错误：写入 gradle.properties 失败：$_" -ForegroundColor Red
        return $false
    }
}

<#
.SYNOPSIS
  仅读取当前版本号（不修改）
.OUTPUTS
  [hashtable] 包含 VersionName 和 VersionCode
#>
function Get-CurrentVersion {
    $versionName = Get-GradleProperty 'wireguardVersionName'
    $codeText = Get-GradleProperty 'wireguardVersionCode'

    if ([string]::IsNullOrWhiteSpace($versionName)) {
        Write-Host "错误：无法从 gradle.properties 读取 wireguardVersionName" -ForegroundColor Red
        exit 1
    }

    $versionCode = 0
    if (-not [int]::TryParse($codeText, [ref]$versionCode)) {
        Write-Host "错误：wireguardVersionCode 不是合法整数：$codeText" -ForegroundColor Red
        exit 1
    }

    return @{
        VersionName = $versionName
        VersionCode = $versionCode
    }
}

<#
.SYNOPSIS
  构建时递增版本号并写回 gradle.properties
.DESCRIPTION
  与 harmony 版一致：versionName 按 x.y.z 的 patch 段 +1（如 1.0.5 → 1.0.6）。
  不做到 9 进位——Android 的 versionName 允许长段（如 1.0.20260315），
  进位会把日期段改写成语义错误的 1.1.0。
  versionCode 直接 +1，保证严格单调递增（Play 商店要求）。
.OUTPUTS
  [hashtable] 包含 VersionName 和 VersionCode
#>
function Update-BuildVersion {
    $current = Get-CurrentVersion

    $parts = $current.VersionName.Split('.')
    if ($parts.Length -lt 3) {
        Write-Host "错误：versionName 格式应为 x.y.z，实际为 $($current.VersionName)" -ForegroundColor Red
        exit 1
    }

    $patch = 0
    if (-not [int]::TryParse($parts[2], [ref]$patch)) {
        Write-Host "错误：versionName 的 patch 段不是整数：$($parts[2])" -ForegroundColor Red
        exit 1
    }
    $parts[2] = [string]($patch + 1)
    $newVersionName = $parts -join '.'
    $newVersionCode = $current.VersionCode + 1

    Write-Host "  当前版本：$($current.VersionName) (code: $($current.VersionCode))" -ForegroundColor Gray
    Write-Host "  构建版本：$newVersionName (code: $newVersionCode)" -ForegroundColor Green

    if (-not (Set-GradleProperty 'wireguardVersionName' $newVersionName)) { exit 1 }
    if (-not (Set-GradleProperty 'wireguardVersionCode' ([string]$newVersionCode))) { exit 1 }

    return @{
        VersionName = $newVersionName
        VersionCode = $newVersionCode
    }
}

<#
.SYNOPSIS
  定位 Android SDK 根目录
.OUTPUTS
  [string] SDK 路径；未找到返回空字符串
#>
function Get-AndroidSdk {
    $candidates = @()
    foreach ($name in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (-not [string]::IsNullOrWhiteSpace($value)) { $candidates += $value }
    }
    $candidates += @(
        'C:\DevDisk\DevTools\AndroidSDK'
        "$env:LOCALAPPDATA\Android\Sdk"
    )

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'platform-tools'))) {
            return $candidate
        }
    }
    return ''
}

<#
.SYNOPSIS
  定位最新 build-tools 目录（须含 apksigner.bat）
.OUTPUTS
  [string] build-tools 路径；未找到返回空字符串
#>
function Get-BuildToolsDir {
    $sdk = Get-AndroidSdk
    if (-not $sdk) { return '' }

    $root = Join-Path $sdk 'build-tools'
    if (-not (Test-Path -LiteralPath $root)) { return '' }

    $dir = Get-ChildItem $root -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if (-not $dir) { return '' }
    return $dir.FullName
}

<#
.SYNOPSIS
  读取仓库根目录的 .env（KEY=VALUE，# 开头为注释）
.OUTPUTS
  [hashtable] 键值表
#>
function Read-DotEnv {
    $envFile = Join-Path $script:RepoRoot '.env'
    $result = @{}
    if (-not (Test-Path -LiteralPath $envFile)) { return $result }

    foreach ($line in [System.IO.File]::ReadAllLines($envFile)) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $idx = $trimmed.IndexOf('=')
        if ($idx -lt 1) { continue }
        $key = $trimmed.Substring(0, $idx).Trim()
        $value = $trimmed.Substring($idx + 1).Trim()
        if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $result[$key] = $value
    }
    return $result
}

<#
.SYNOPSIS
  读取签名配置：优先 .env，其次进程环境变量
.OUTPUTS
  [hashtable] Keystore / KeyAlias / StorePassword / KeyPassword；缺失项为空字符串
#>
function Get-SigningConfig {
    $dotenv = Read-DotEnv

    function Pick([string]$Key) {
        if ($dotenv.ContainsKey($Key) -and $dotenv[$Key]) { return $dotenv[$Key] }
        $envValue = [Environment]::GetEnvironmentVariable($Key)
        if ([string]::IsNullOrWhiteSpace($envValue)) { return '' }
        return $envValue
    }

    return @{
        Keystore      = Pick 'ANDROID_KEYSTORE'
        KeyAlias      = Pick 'ANDROID_KEY_ALIAS'
        StorePassword = Pick 'ANDROID_KEYSTORE_PASSWORD'
        KeyPassword   = Pick 'ANDROID_KEY_PASSWORD'
    }
}

<#
.SYNOPSIS
  校验签名配置是否完整；不完整时列出缺失项并返回 $false
#>
function Test-SigningConfig {
    param([Parameter(Mandatory = $true)][hashtable]$Config)

    $missing = @()
    if (-not $Config.Keystore)      { $missing += 'ANDROID_KEYSTORE' }
    if (-not $Config.KeyAlias)      { $missing += 'ANDROID_KEY_ALIAS' }
    if (-not $Config.StorePassword) { $missing += 'ANDROID_KEYSTORE_PASSWORD' }
    if (-not $Config.KeyPassword)   { $missing += 'ANDROID_KEY_PASSWORD' }

    if ($missing.Count -gt 0) {
        Write-Host "  ! 签名配置不完整，缺少：$($missing -join '、')" -ForegroundColor Yellow
        Write-Host "    请在仓库根目录的 .env 中补齐（参考 .env.example）。" -ForegroundColor Gray
        return $false
    }
    if (-not (Test-Path -LiteralPath $Config.Keystore)) {
        Write-Host "  ! keystore 文件不存在：$($Config.Keystore)" -ForegroundColor Yellow
        return $false
    }
    return $true
}

<#
.SYNOPSIS
  调用 scripts/build-win.ps1 执行 Gradle 任务（复用其 JDK/SDK/NDK/Go 环境探测）
.PARAMETER Task
  Gradle 任务名，如 :ui:assembleRelease
.PARAMETER Clean
  传入则以 clean 先清理
#>
function Invoke-WinBuild {
    param(
        [Parameter(Mandatory = $true)][string]$Task,
        [switch]$Clean
    )

    $buildWin = Join-Path $PSScriptRoot 'build-win.ps1'
    if (-not (Test-Path -LiteralPath $buildWin)) {
        Write-Host "错误：未找到 $buildWin" -ForegroundColor Red
        exit 1
    }

    $params = @{ Task = $Task }
    if ($Clean) { $params['Clean'] = $true }

    try {
        & $buildWin @params
    } catch {
        Write-Host ""
        Write-Host "========================================" -ForegroundColor Red
        Write-Host "构建失败：$($_.Exception.Message)" -ForegroundColor Red
        Write-Host "========================================" -ForegroundColor Red
        exit 1
    }
}

<#
.SYNOPSIS
  在 APK 目录下查找最新产物
.PARAMETER SearchRoot
  搜索根目录
.PARAMETER Filter
  文件名过滤表达式
.PARAMETER Exclude
  排除的文件名表达式（可为空）
.PARAMETER Preferred
  优先精确匹配的文件名；存在则直接返回，避免与重命名过的旧产物混淆
.OUTPUTS
  [System.IO.FileInfo] 最新匹配文件；未找到返回 $null
#>
function Find-LatestApk {
    param(
        [Parameter(Mandatory = $true)][string]$SearchRoot,
        [Parameter(Mandatory = $true)][string]$Filter,
        [string]$Exclude,
        [string]$Preferred
    )

    if (-not (Test-Path -LiteralPath $SearchRoot)) { return $null }

    # Gradle 原始产物名是固定的；重命名后的产物（WireGuard*）也匹配 *unsigned.apk，
    # 若只按通配符查找会误取上一轮遗留的旧文件。
    if ($Preferred) {
        $exact = Join-Path $SearchRoot $Preferred
        if (Test-Path -LiteralPath $exact) { return Get-Item -LiteralPath $exact }
    }

    $items = Get-ChildItem -Path $SearchRoot -Recurse -File -Filter $Filter
    if ($Exclude) {
        $items = $items | Where-Object { $_.Name -notlike $Exclude }
    }
    return $items | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

<#
.SYNOPSIS
  强制校验 APK 的 minSdkVersion
.DESCRIPTION
  APK 的 minSdkVersion 由 ui/build.gradle.kts 的 minSdk 决定。
  用 aapt2 dump badging 读取，断言必须等于期望值，不符即中止。
.PARAMETER ApkPath
  APK 文件路径
.PARAMETER Expected
  期望的 minSdkVersion，默认 24
#>
function Assert-ApkMinSdk {
    param(
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [int]$Expected = 24
    )

    if (-not (Test-Path -LiteralPath $ApkPath)) {
        Write-Host "  X minSdkVersion 校验失败：APK 不存在 $ApkPath" -ForegroundColor Red
        exit 1
    }

    $buildTools = Get-BuildToolsDir
    if (-not $buildTools) {
        Write-Host "  ! 未找到 build-tools，跳过 minSdkVersion 校验" -ForegroundColor Yellow
        return
    }
    $aapt2 = Join-Path $buildTools 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $aapt2)) {
        Write-Host "  ! 未找到 aapt2，跳过 minSdkVersion 校验" -ForegroundColor Yellow
        return
    }

    $badging = & $aapt2 dump badging $ApkPath 2>&1 | Out-String
    if ($badging -notmatch "minSdkVersion:'(\d+)'") {
        Write-Host "  X 无法从 APK 读取 minSdkVersion" -ForegroundColor Red
        exit 1
    }

    $actual = [int]$Matches[1]
    if ($actual -ne $Expected) {
        Write-Host "  X minSdkVersion 校验失败：期望 $Expected，实际 $actual" -ForegroundColor Red
        Write-Host "    根因通常是 ui/build.gradle.kts 的 minSdk 未设为 $Expected。" -ForegroundColor Yellow
        exit 1
    }

    Write-Host "  √ minSdkVersion 校验通过：$actual" -ForegroundColor Green
}

<#
.SYNOPSIS
  检查 APK 是否已签名
.OUTPUTS
  [bool] 已签名返回 $true
#>
function Test-ApkSigned {
    param([Parameter(Mandatory = $true)][string]$ApkPath)

    if (-not (Test-Path -LiteralPath $ApkPath)) { return $false }

    $buildTools = Get-BuildToolsDir
    if (-not $buildTools) { return $false }
    $apksigner = Join-Path $buildTools 'apksigner.bat'
    if (-not (Test-Path -LiteralPath $apksigner)) { return $false }

    # apksigner 未签名时向 stderr 输出并以非 0 退出；$ErrorActionPreference=Stop
    # 会把 stderr 变成终止性异常，这里局部降级为 Continue 后按退出码判定。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $apksigner verify $ApkPath 2>&1 | Out-Null
        return ($LASTEXITCODE -eq 0)
    } finally {
        $ErrorActionPreference = $previous
    }
}
