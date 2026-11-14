# Copyright 2026 WireGuard Android Contributors
# SPDX-License-Identifier: Apache-2.0
#
# 构建签名 Release APK（带版本号递增）。
# 每次执行会递增 patch 版本号（如 1.0.5 → 1.0.6），写回 gradle.properties，
# 然后用 apksigner 对 assembleRelease 产出的未签名 APK 补签。
#
# 签名配置从仓库根目录的 .env 读取（已 gitignore），键名见 .env.example：
#   ANDROID_KEYSTORE / ANDROID_KEY_ALIAS / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_PASSWORD
# 也可用同名环境变量。

$ErrorActionPreference = "Stop"

# 加载通用函数
. "$PSScriptRoot\_common.ps1"

$ReleaseOutputDir = Join-Path $UIBuildDir 'apk\release'

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "WireGuard Android 构建签名 APK" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 递增版本号
Write-Host "[版本递增]" -ForegroundColor Yellow
$versionInfo = Update-BuildVersion
Write-Host ""

# 校验签名配置（在编译前检查，避免白等一次构建）
Write-Host "[1/3] 检查签名配置..." -ForegroundColor Yellow
$signing = Get-SigningConfig
if (-not (Test-SigningConfig -Config $signing)) {
    Write-Host ""
    Write-Host "已中止：未配置签名，无法产出可发布的签名 APK。" -ForegroundColor Red
    Write-Host "  复制 .env.example 为 .env 并填写证书信息后重试。" -ForegroundColor Gray
    Write-Host "  版本号已递增，如需回退请手动调整 gradle.properties。" -ForegroundColor Yellow
    exit 1
}
$apksigner = Join-Path (Get-BuildToolsDir) 'apksigner.bat'
Write-Host "  √ 签名配置完整" -ForegroundColor Green
Write-Host ""

# 编译未签名 Release APK
Write-Host "[2/3] 执行 Gradle assembleRelease..." -ForegroundColor Yellow
Write-Host "  项目: $RepoRoot" -ForegroundColor Gray
Write-Host ""
Invoke-WinBuild -Task ':ui:assembleRelease'

$unsignedApk = Find-LatestApk -SearchRoot $ReleaseOutputDir -Filter '*unsigned.apk' -Preferred 'ui-release-unsigned.apk'
if (-not $unsignedApk) {
    Write-Host "  X 构建报成功但未找到未签名 APK：$ReleaseOutputDir" -ForegroundColor Red
    exit 1
}
Write-Host "  未签名 APK：$($unsignedApk.FullName)" -ForegroundColor Gray

# 补签并重命名为标准格式：WireGuardRelease-<versionName>.apk
Write-Host ""
Write-Host "[3/3] 签名并重命名..." -ForegroundColor Yellow
$targetApk = Join-Path $ReleaseOutputDir "WireGuardRelease-$($versionInfo.VersionName).apk"
try {
    & $apksigner sign `
        --ks $signing.Keystore `
        --ks-key-alias $signing.KeyAlias `
        --ks-pass "pass:$($signing.StorePassword)" `
        --key-pass "pass:$($signing.KeyPassword)" `
        --out $targetApk `
        $unsignedApk.FullName
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  X apksigner 签名失败（退出码 $LASTEXITCODE）" -ForegroundColor Red
        exit $LASTEXITCODE
    }
} catch {
    Write-Host "  X 签名失败：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

if (-not (Test-ApkSigned -ApkPath $targetApk)) {
    Write-Host "  X 签名后校验失败：$targetApk" -ForegroundColor Red
    exit 1
}
Write-Host "  √ 签名完成并校验通过：WireGuardRelease-$($versionInfo.VersionName).apk" -ForegroundColor Green

# 强制校验 minSdkVersion
Assert-ApkMinSdk -ApkPath $targetApk

$apkItem = Get-Item $targetApk
$sizeMb = [math]::Round($apkItem.Length / 1MB, 1)

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "构建成功！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  产物: $targetApk" -ForegroundColor Gray
Write-Host "  大小: $sizeMb MB" -ForegroundColor Gray
Write-Host "  时间: $($apkItem.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))" -ForegroundColor Gray
Write-Host "  版本: $($versionInfo.VersionName) (code: $($versionInfo.VersionCode))" -ForegroundColor Gray
