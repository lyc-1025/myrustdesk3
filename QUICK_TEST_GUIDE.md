# 隐私模式快速测试指南

## 🚨 重要：点击隐私模式没有任何反应的问题

如果点击隐私模式按钮后完全没有任何反应和日志，问题可能在以下几个方面：

## 步骤 1：运行诊断脚本

```powershell
cd d:\rustdesk-master
.\diagnose_privacy_mode.ps1
```

这个脚本会自动检查：
- ADB 连接状态
- 应用安装情况
- 应用运行状态
- 悬浮窗权限
- MainService 状态
- 日志输出
- Rust 库加载情况

## 步骤 2：重新编译并安装（包含新的诊断日志）

```powershell
cd d:\rustdesk-master\flutter

# 清理旧构建
flutter clean

# 构建调试版本
flutter build apk --debug

# 卸载旧版本
adb uninstall com.carriez.flutter_hbb

# 安装新版本
adb install build/app/outputs/flutter-apk/app-debug.apk
```

## 步骤 3：启动应用并监控日志

### 3.1 清空日志并开始监控

```powershell
# 清空日志
adb logcat -c

# 监控所有 DEBUG_PRIVACY 日志
adb logcat | Select-String "DEBUG_PRIVACY"
```

### 3.2 启动 Android 应用

在 Android 设备上启动"公证办理"应用。

**预期看到的日志：**
```
DEBUG_PRIVACY: ===== MainService onCreate called =====
DEBUG_PRIVACY: Initializing FFI (JNI context)...
DEBUG_PRIVACY: FFI initialized successfully
```

如果看到这些日志，说明：
- ✅ 新版本 APK 已正确安装
- ✅ MainService 已启动
- ✅ JNI 上下文已初始化

如果**没有看到**这些日志：
- ❌ 可能还在使用旧版本 APK
- ❌ 需要重新编译安装

## 步骤 4：测试隐私模式

### 4.1 PC 端连接到 Android

1. 在 PC 端的 RustDesk 客户端中输入 Android 设备的 ID
2. 点击连接
3. 等待连接成功

### 4.2 点击隐私模式按钮

在远程控制窗口中点击隐私模式按钮。

**预期看到的日志（完整流程）：**

```
# Rust 层（被控端服务器）
DEBUG_PRIVACY: android turn_on_privacy called, conn_id: 123
DEBUG_PRIVACY: Attempting to turn on Android privacy mode, conn_id: 123
DEBUG_PRIVACY: About to call JNI - toggle_privacy_mode with true

# MainService（JNI 调用）
DEBUG_PRIVACY: ===== rustSetByName called =====
DEBUG_PRIVACY: name=toggle_privacy_mode, arg1=true, arg2=
DEBUG_PRIVACY: MainService received toggle_privacy_mode: true
DEBUG_PRIVACY: arg1=true, arg2=
DEBUG_PRIVACY: Starting PrivacyModeService...
DEBUG_PRIVACY: PrivacyModeService start call completed

# PrivacyModeService
DEBUG_PRIVACY: startPrivacyMode called, isActive=false
DEBUG_PRIVACY: Creating intent to start PrivacyModeService
DEBUG_PRIVACY: Starting foreground service (Android 8.0+)
DEBUG_PRIVACY: Service start initiated successfully
DEBUG_PRIVACY: ===== PrivacyModeService onCreate called =====
DEBUG_PRIVACY: isActive set to true
DEBUG_PRIVACY: Android M+ detected, checking overlay permission: true
DEBUG_PRIVACY: Overlay permission granted
DEBUG_PRIVACY: Creating foreground notification...
DEBUG_PRIVACY: Foreground notification created successfully
DEBUG_PRIVACY: Creating black overlay...
DEBUG_PRIVACY: Black overlay created successfully
DEBUG_PRIVACY: ===== Privacy mode activated successfully =====

# Rust 层（成功确认）
DEBUG_PRIVACY: JNI call succeeded
DEBUG_PRIVACY: Privacy mode successfully enabled
```

## 步骤 5：可能的问题和解决方案

### 问题 1：完全没有任何 DEBUG_PRIVACY 日志

**原因：** 使用的是旧版本 APK

**解决：** 
1. 确认重新编译
2. 确认卸载旧版本
3. 确认安装新版本
4. 重启应用

### 问题 2：看到 MainService onCreate 日志，但点击隐私模式后没有 rustSetByName 日志

**原因：** 
- Rust 层没有调用 JNI
- PC 端没有发送隐私模式命令
- Android 端报告不支持隐私模式

**解决：**
1. 检查 PC 端是否有错误提示
2. 查看 Rust 层日志：`adb logcat | Select-String "rustdesk|privacy"`
3. 检查 Android 端是否报告支持隐私模式

### 问题 3：看到 rustSetByName 日志，但没有 PrivacyModeService 日志

**原因：**
- PrivacyModeService 启动失败
- 权限问题

**解决：**
1. 检查悬浮窗权限：`adb shell appops get com.carriez.flutter_hbb SYSTEM_ALERT_WINDOW`
2. 如果权限被拒绝，手动授予：设置 → 应用 → 公证办理 → 显示在其他应用上层

### 问题 4：看到 "Overlay permission not granted" 日志

**原因：** 没有悬浮窗权限

**解决：**
1. 打开 Android 设置
2. 进入应用管理 → 公证办理
3. 找到"显示在其他应用上层"权限
4. 开启该权限
5. 重新测试

## 步骤 6：收集诊断信息

如果问题仍然存在，请收集以下信息：

```powershell
# 1. 完整日志（从应用启动到点击隐私模式）
adb logcat -c
adb logcat > full_log.txt
# 然后启动应用，连接，点击隐私模式，等待 30 秒后 Ctrl+C 停止

# 2. 应用信息
adb shell dumpsys package com.carriez.flutter_hbb > package_info.txt

# 3. 进程信息
adb shell ps | Select-String "flutter_hbb" > process_info.txt

# 4. 权限信息
adb shell dumpsys package com.carriez.flutter_hbb | Select-String "permission" > permissions.txt
```

## 常见日志关键词

监控这些关键词可以帮助诊断：

```powershell
# 综合监控
adb logcat | Select-String "DEBUG_PRIVACY|toggle_privacy|rustSetByName|PrivacyModeService"

# 只监控错误
adb logcat *:E | Select-String "flutter_hbb"

# 监控 Rust 层
adb logcat | Select-String "rustdesk|hbb_common"

# 监控权限问题
adb logcat | Select-String "permission|Permission|SYSTEM_ALERT_WINDOW"
```

## 预期结果

如果一切正常：
1. ✅ Android 屏幕显示全黑遮罩层，带有提示文字
2. ✅ PC 端可以看到 Android 屏幕内容
3. ✅ PC 端可以正常控制 Android 设备
4. ✅ Android 端用户无法看到真实屏幕内容

## 联系支持

如果按照以上步骤仍然无法解决问题，请提供：
1. 诊断脚本的输出
2. 完整的日志文件
3. Android 版本和设备型号
4. PC 端是否有错误提示
