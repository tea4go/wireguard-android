# Copyright 2026 WireGuard Android Contributors
# SPDX-License-Identifier: Apache-2.0
#
# 构建未签名 Release APK（ui-release-unsigned.apk）。
# 未签名 APK 无法直接装机（adb install 会报 INSTALL_PARSE_FAILED_NO_CERTIFICATES），
# 仅用于验证编译能否通过。装机请用 deploy.ps1，签名发布请用 build-signed.ps1。

$ErrorActionPreference = "Stop"

# 加载通用函数
. "$PSScriptRoot\_common.ps1"

$ReleaseOutputDir = Join-Path $UIBuildDir 'apk\release'

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "WireGuard Android 构建未签名 APK" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 显示当前版本号（不递增）
$versionInfo = Get-CurrentVersion
Write-Host "  当前版本：$($versionInfo.VersionName) (code: $($versionInfo.VersionCode))" -ForegroundColor Gray
Write-Host "  注意：未签名构建不递增版本号" -ForegroundColor Yellow
Write-Host ""

Write-Host "[1/2] 执行 Gradle assembleRelease..." -ForegroundColor Yellow
Write-Host "  项目: $RepoRoot" -ForegroundColor Gray
Write-Host ""
Invoke-WinBuild -Task ':ui:assembleRelease'

# 检查产物
Write-Host ""
Write-Host "[2/2] 检查产物..." -ForegroundColor Yellow
$apk = Find-LatestApk -SearchRoot $ReleaseOutputDir -Filter '*unsigned.apk' -Preferred 'ui-release-unsigned.apk'
if (-not $apk) {
    Write-Host "  X 构建报成功但未找到未签名 APK：$ReleaseOutputDir" -ForegroundColor Red
    exit 1
}

# 强制校验 minSdkVersion
Assert-ApkMinSdk -ApkPath $apk.FullName

# 复制为便于分发的标准名；保留 Gradle 原始产物，避免重复构建时被移走
$targetApk = Join-Path $ReleaseOutputDir "WireGuardUnsigned-$($versionInfo.VersionName).apk"
try {
    Copy-Item -LiteralPath $apk.FullName -Destination $targetApk -Force
    Write-Host "  √ APK 输出：WireGuardUnsigned-$($versionInfo.VersionName).apk" -ForegroundColor Green
} catch {
    Write-Host "  ! 复制失败，保留原名：$_" -ForegroundColor Yellow
    $targetApk = $apk.FullName
}

$apkItem = Get-Item $targetApk
$sizeMb = [math]::Round($apkItem.Length / 1MB, 1)

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "构建成功！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  产物: $targetApk" -ForegroundColor Gray
Write-Host "  大小: $sizeMb MB" -ForegroundColor Gray
Write-Host "  时间: $($apkItem.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))" -ForegroundColor Gray
Write-Host ""
Write-Host "提示：未签名 APK 无法直接装机，装机请用 deploy.ps1，签名发布请用 build-signed.ps1。" -ForegroundColor Yellow
