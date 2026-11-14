# Copyright 2026 WireGuard Android Contributors
# SPDX-License-Identifier: Apache-2.0
#
# 构建上架 Google Play 用的 AAB 包（WireGuardRelease-<versionName>.aab）。
# 用 `gradlew bundleRelease` 打包，再用 jarsigner 签名。
#
# 签名说明：
#   - Play 上架必须用「发布密钥」签名（.jks/.keystore + 别名 + 口令）。
#   - 签名配置从仓库根目录的 .env 读取（已 gitignore），键名见 .env.example。
#   - 未配置签名时仍会产出未签名 AAB，但仅供验证编译，无法上传。
#
# 版本号：
#   不自增版本号，直接读取 gradle.properties 当前版本。
#   版本号统一由 build-signed.ps1 在签名 APK 时递增，避免多个脚本重复递增导致版本跳号。

$ErrorActionPreference = "Stop"

# 加载通用函数
. "$PSScriptRoot\_common.ps1"

$BundleOutputDir = Join-Path $UIBuildDir 'bundle\release'

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "WireGuard Android 构建上架 AAB 包" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 1. 检查签名配置
Write-Host "[1/5] 检查签名配置..." -ForegroundColor Yellow
$signing = Get-SigningConfig
$isSigned = Test-SigningConfig -Config $signing
if ($isSigned) {
    Write-Host "  √ 签名配置完整（产出签名包）" -ForegroundColor Green
} else {
    Write-Host "  ! 未配置签名：将产出未签名包，无法上传" -ForegroundColor Yellow
    Write-Host "    上架前需在 .env 中配置发布密钥。" -ForegroundColor Gray
}
Write-Host ""

# 2. 版本号处理（只读，不递增）
Write-Host "[2/5] 读取版本号..." -ForegroundColor Yellow
$versionInfo = Get-CurrentVersion
Write-Host "  当前版本：$($versionInfo.VersionName) (code: $($versionInfo.VersionCode))" -ForegroundColor Gray
Write-Host "  注意：AAB 包构建不递增版本号，版本号统一由 build-signed.ps1 管理" -ForegroundColor Yellow
Write-Host ""

# 3. 执行构建
Write-Host "[3/5] 执行 Gradle bundleRelease..." -ForegroundColor Yellow
Write-Host "  项目: $RepoRoot" -ForegroundColor Gray
Write-Host ""
Invoke-WinBuild -Task ':ui:bundleRelease'

# 4. 定位并签名产物
Write-Host ""
Write-Host "[4/5] 检查产物..." -ForegroundColor Yellow
$aab = Get-ChildItem -Path $BundleOutputDir -Recurse -File -Filter '*.aab' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike 'WireGuard*' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $aab) {
    Write-Host "  X 构建报成功但未找到 .aab 产物：$BundleOutputDir" -ForegroundColor Red
    exit 1
}

$outName = "WireGuardRelease-$($versionInfo.VersionName).aab"
$finalOutput = Join-Path $BundleOutputDir $outName

if ($isSigned) {
    $jarsigner = Join-Path $env:JAVA_HOME 'bin\jarsigner.exe'
    if (-not (Test-Path -LiteralPath $jarsigner)) {
        $jarsignerCmd = Get-Command jarsigner -ErrorAction SilentlyContinue
        if ($jarsignerCmd) { $jarsigner = $jarsignerCmd.Source }
    }
    if (-not $jarsigner -or -not (Test-Path -LiteralPath $jarsigner)) {
        Write-Host "  X 未找到 jarsigner，无法签名 AAB" -ForegroundColor Red
        exit 1
    }

    Write-Host "  签名中..." -ForegroundColor Gray
    # jarsigner 原地签名：先复制到目标名，再对该副本签名，避免污染 Gradle 原始产物
    Copy-Item -LiteralPath $aab.FullName -Destination $finalOutput -Force
    try {
        & $jarsigner -keystore $signing.Keystore `
            -storepass $signing.StorePassword `
            -keypass $signing.KeyPassword `
            -sigalg SHA256withRSA -digestalg SHA-256 `
            $finalOutput $signing.KeyAlias
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  X jarsigner 签名失败（退出码 $LASTEXITCODE）" -ForegroundColor Red
            exit $LASTEXITCODE
        }
    } catch {
        Write-Host "  X 签名失败：$($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
    Write-Host "  √ 签名完成：$outName" -ForegroundColor Green
} else {
    try {
        Copy-Item -LiteralPath $aab.FullName -Destination $finalOutput -Force
        Write-Host "  √ 产物已输出：$outName（未签名）" -ForegroundColor Green
    } catch {
        Write-Host "  ! 复制失败，保留原名：$_" -ForegroundColor Yellow
        $finalOutput = $aab.FullName
        $outName = $aab.Name
    }
}

$aabItem = Get-Item $finalOutput
$sizeMb = [math]::Round($aabItem.Length / 1MB, 1)

Write-Host ""
Write-Host "[5/5] 完成" -ForegroundColor Yellow
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "构建成功！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  产物: $finalOutput" -ForegroundColor Gray
Write-Host "  文件名: $outName" -ForegroundColor Gray
Write-Host "  大小: $sizeMb MB" -ForegroundColor Gray
Write-Host ""
if ($isSigned) {
    Write-Host "已签名 AAB 包，可上传 Google Play Console 提交审核。" -ForegroundColor Green
} else {
    Write-Host "警告：此为未签名包，不能上架！" -ForegroundColor Red
    Write-Host "上架需先在 .env 配置发布密钥后重新构建。" -ForegroundColor Yellow
}
