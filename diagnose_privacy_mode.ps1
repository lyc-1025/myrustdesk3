# RustDesk 隐私模式诊断脚本

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "RustDesk Android 隐私模式诊断工具" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 1. 检查 ADB 连接
Write-Host "[1/8] 检查 ADB 设备连接..." -ForegroundColor Yellow
$devices = adb devices
if ($devices -match "device$") {
    Write-Host "✓ ADB 设备已连接" -ForegroundColor Green
} else {
    Write-Host "✗ 未检测到 ADB 设备" -ForegroundColor Red
    exit
}

# 2. 检查应用是否安装
Write-Host "`n[2/8] 检查 RustDesk 应用..." -ForegroundColor Yellow
$package = adb shell pm list packages | Select-String "carriez.flutter_hbb"
if ($package) {
    Write-Host "✓ 应用已安装: $package" -ForegroundColor Green
    
    # 获取应用版本和安装时间
    $versionName = adb shell dumpsys package com.carriez.flutter_hbb | Select-String "versionName"
    $installTime = adb shell dumpsys package com.carriez.flutter_hbb | Select-String "firstInstallTime"
    $updateTime = adb shell dumpsys package com.carriez.flutter_hbb | Select-String "lastUpdateTime"
    
    Write-Host "  版本信息: $versionName" -ForegroundColor Gray
    Write-Host "  首次安装: $installTime" -ForegroundColor Gray
    Write-Host "  最后更新: $updateTime" -ForegroundColor Gray
} else {
    Write-Host "✗ 应用未安装" -ForegroundColor Red
    exit
}

# 3. 检查应用是否正在运行
Write-Host "`n[3/8] 检查应用运行状态..." -ForegroundColor Yellow
$running = adb shell ps | Select-String "flutter_hbb"
if ($running) {
    Write-Host "✓ 应用正在运行" -ForegroundColor Green
    Write-Host "  进程信息: $running" -ForegroundColor Gray
} else {
    Write-Host "✗ 应用未运行 - 请先启动应用！" -ForegroundColor Red
}

# 4. 检查悬浮窗权限
Write-Host "`n[4/8] 检查悬浮窗权限..." -ForegroundColor Yellow
$overlayPermission = adb shell appops get com.carriez.flutter_hbb SYSTEM_ALERT_WINDOW
if ($overlayPermission -match "allow") {
    Write-Host "✓ 悬浮窗权限已授予" -ForegroundColor Green
} else {
    Write-Host "✗ 悬浮窗权限未授予: $overlayPermission" -ForegroundColor Red
    Write-Host "  请手动授予权限：设置 → 应用 → 公证办理 → 显示在其他应用上层" -ForegroundColor Yellow
}

# 5. 检查 MainService 是否运行
Write-Host "`n[5/8] 检查 MainService 状态..." -ForegroundColor Yellow
$mainService = adb shell dumpsys activity services | Select-String "MainService"
if ($mainService) {
    Write-Host "✓ MainService 正在运行" -ForegroundColor Green
} else {
    Write-Host "✗ MainService 未运行" -ForegroundColor Red
    Write-Host "  请确保有 PC 端连接到 Android 设备" -ForegroundColor Yellow
}

# 6. 测试日志输出
Write-Host "`n[6/8] 测试日志输出（5秒）..." -ForegroundColor Yellow
Write-Host "  监听关键词：privacy, MainService, flutter_hbb, DEBUG" -ForegroundColor Gray

adb logcat -c  # 清空日志
Start-Sleep -Seconds 1

# 启动后台日志收集
$job = Start-Job -ScriptBlock {
    adb logcat
}

Start-Sleep -Seconds 5
Stop-Job -Job $job

$logs = Receive-Job -Job $job
Remove-Job -Job $job

$privacyLogs = $logs | Select-String -Pattern "privacy|Privacy|MainService|DEBUG_PRIVACY|toggle_privacy" -CaseSensitive:$false
$logCount = ($privacyLogs | Measure-Object).Count

if ($logCount -gt 0) {
    Write-Host "✓ 检测到 $logCount 条相关日志" -ForegroundColor Green
    Write-Host "`n最近的日志片段:" -ForegroundColor Gray
    $privacyLogs | Select-Object -First 10 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
} else {
    Write-Host "✗ 未检测到任何相关日志" -ForegroundColor Red
}

# 7. 检查 Rust 库是否加载
Write-Host "`n[7/8] 检查 Rust 原生库..." -ForegroundColor Yellow
$libs = adb shell "cat /proc/`$(pidof com.carriez.flutter_hbb)/maps" | Select-String "librustdesk.so"
if ($libs) {
    Write-Host "✓ Rust 原生库已加载" -ForegroundColor Green
} else {
    Write-Host "✗ Rust 原生库未加载" -ForegroundColor Red
}

# 8. 生成诊断报告
Write-Host "`n[8/8] 生成完整诊断报告..." -ForegroundColor Yellow

$reportPath = "d:\rustdesk-master\privacy_mode_diagnosis_report.txt"
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

@"
========================================
RustDesk 隐私模式诊断报告
生成时间: $timestamp
========================================

1. 应用信息:
$package
$versionName
$installTime
$updateTime

2. 运行状态:
进程: $running
MainService: $mainService

3. 权限状态:
悬浮窗权限: $overlayPermission

4. 原生库状态:
$libs

5. 最近日志 (最多显示 50 条):
$($privacyLogs | Select-Object -First 50 | Out-String)

========================================
诊断建议:
"@ | Out-File -FilePath $reportPath -Encoding UTF8

# 添加诊断建议
$suggestions = @()

if ($overlayPermission -notmatch "allow") {
    $suggestions += "- 需要授予悬浮窗权限"
}

if (-not $mainService) {
    $suggestions += "- MainService 未运行，请确保有设备连接"
}

if ($logCount -eq 0) {
    $suggestions += "- 未检测到日志输出，可能是旧版本 APK，建议重新编译安装"
}

if ($suggestions.Count -gt 0) {
    $suggestions | ForEach-Object { Add-Content -Path $reportPath -Value $_ }
    Write-Host "`n诊断建议:" -ForegroundColor Yellow
    $suggestions | ForEach-Object { Write-Host $_ -ForegroundColor Yellow }
} else {
    Add-Content -Path $reportPath -Value "所有检查项正常，但隐私模式仍未工作。建议查看完整日志。"
}

Write-Host "`n✓ 诊断报告已保存到: $reportPath" -ForegroundColor Green

# 提供下一步操作建议
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "下一步操作建议:" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "`n1. 实时监控隐私模式日志:" -ForegroundColor White
Write-Host "   adb logcat -c" -ForegroundColor Gray
Write-Host "   adb logcat | Select-String 'DEBUG_PRIVACY|toggle_privacy|MainService.*privacy'" -ForegroundColor Gray

Write-Host "`n2. 监控所有 Rust 日志:" -ForegroundColor White
Write-Host "   adb logcat | Select-String 'rustdesk|hbb_common'" -ForegroundColor Gray

Write-Host "`n3. 监控 MainService 的所有活动:" -ForegroundColor White
Write-Host "   adb logcat MainService:D *:S" -ForegroundColor Gray

Write-Host "`n4. 如果仍然没有日志，重新编译安装:" -ForegroundColor White
Write-Host "   cd d:\rustdesk-master\flutter" -ForegroundColor Gray
Write-Host "   flutter clean" -ForegroundColor Gray
Write-Host "   flutter build apk --debug" -ForegroundColor Gray
Write-Host "   adb uninstall com.carriez.flutter_hbb" -ForegroundColor Gray
Write-Host "   adb install build/app/outputs/flutter-apk/app-debug.apk" -ForegroundColor Gray

Write-Host "`n5. 测试步骤:" -ForegroundColor White
Write-Host "   a. 启动 Android 应用（公证办理）" -ForegroundColor Gray
Write-Host "   b. PC 端连接到 Android 设备" -ForegroundColor Gray
Write-Host "   c. 在监控日志的同时，点击 PC 端的隐私模式按钮" -ForegroundColor Gray
Write-Host "   d. 观察日志输出" -ForegroundColor Gray

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "诊断完成！" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
