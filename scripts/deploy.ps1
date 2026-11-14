# Copyright 2026 WireGuard Android Contributors
# SPDX-License-Identifier: Apache-2.0
#
# 装机部署脚本：先构建 debug APK，再通过 adb 安装到所有已连接设备。
# debug 包由 Gradle 用 debug keystore 自动签名，无需任何签名配置即可装机。

$ErrorActionPreference = "Stop"

# 加载通用函数
. "$PSScriptRoot\_common.ps1"

$DebugOutputDir = Join-Path $UIBuildDir 'apk\debug'
$packageName    = 'com.wireguard.android.debug'
$activityName   = 'com.wireguard.android.activity.MainActivity'

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "WireGuard Android 装机部署" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 检查 adb 可用性
$sdk = Get-AndroidSdk
$adb = $null
if ($sdk) {
    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate) { $adb = $candidate }
}
if (-not $adb) {
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) { $adb = $adbCmd.Source }
}
if (-not $adb) {
    Write-Host "错误：adb 不可用，请确保 Android SDK 的 platform-tools 在 PATH 中" -ForegroundColor Red
    exit 1
}
Write-Host "  adb: $adb" -ForegroundColor Gray

# 构建 debug APK
Write-Host ""
Write-Host "[1/3] 构建 debug APK..." -ForegroundColor Yellow
Invoke-WinBuild -Task ':ui:assembleDebug'

$versionInfo = Get-CurrentVersion
$debugApk = Find-LatestApk -SearchRoot $DebugOutputDir -Filter '*-debug.apk' -Preferred 'ui-debug.apk'
if (-not $debugApk) {
    Write-Host "  X 构建报成功但未找到 debug APK：$DebugOutputDir" -ForegroundColor Red
    exit 1
}
Write-Host "  APK: $($debugApk.FullName)" -ForegroundColor Gray

# 枚举已连接设备
Write-Host ""
Write-Host "[2/3] 检测设备..." -ForegroundColor Yellow
$deviceLines = & $adb devices 2>&1
$devices = @($deviceLines |
    Where-Object { $_ -match '^\S+\s+device$' } |
    ForEach-Object { ($_ -split '\s+')[0] })
if ($devices.Count -eq 0) {
    Write-Host "  X 未检测到任何已连接的设备，请确保设备已连接并开启 USB 调试" -ForegroundColor Red
    exit 1
}
Write-Host "  当前已连接设备（$($devices.Count) 台）：" -ForegroundColor Gray
foreach ($dev in $devices) {
    Write-Host "    - $dev" -ForegroundColor Green
}

# 逐台安装并启动
Write-Host ""
Write-Host "[3/3] 安装并启动（全部设备）..." -ForegroundColor Yellow
$okCount = 0
foreach ($dev in $devices) {
    Write-Host "  ▶ $dev" -ForegroundColor Cyan

    # 安装（覆盖同版本，保留数据）
    $installOut = & $adb -s $dev install -r $debugApk.FullName 2>&1 | Out-String
    if ($installOut -notmatch 'Success') {
        $reason = ($installOut -split "`n" | Where-Object { $_ -match 'Failure|error|failed' } |
            Select-Object -First 1)
        if (-not $reason) { $reason = ($installOut.Trim() -split "`n")[0] }
        Write-Host "    X 安装失败，跳过此设备：$reason" -ForegroundColor Red
        continue
    }
    Write-Host "    √ 安装完成" -ForegroundColor Green

    # 启动主界面
    $startOut = & $adb -s $dev shell am start -n "$packageName/$activityName" 2>&1 | Out-String
    if ($startOut -match 'Error|Exception') {
        Write-Host "    ! 启动失败，请在该设备手动拉起应用：$($startOut.Trim())" -ForegroundColor Yellow
    } else {
        Write-Host "    √ 应用已启动" -ForegroundColor Green
    }
    $okCount++
}
Write-Host ""
Write-Host "  部署结果：$okCount/$($devices.Count) 台成功" -ForegroundColor Gray

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "部署完成！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  应用：WireGuard for Android (debug)" -ForegroundColor Gray
Write-Host "  版本：$($versionInfo.VersionName)" -ForegroundColor Gray
Write-Host ""
Write-Host "提示：首次启动需授予 VPN 权限" -ForegroundColor Yellow
