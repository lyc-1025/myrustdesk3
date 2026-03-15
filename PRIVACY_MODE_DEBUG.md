# Android 隐私模式调试指南

## 问题描述
PC端点击隐私模式后，Android端没有反应，没有出现黑色遮罩层。

## 调试步骤

### 1. 检查权限
Android 隐私模式需要**悬浮窗权限**（SYSTEM_ALERT_WINDOW）才能显示黑屏遮罩。

#### 检查方法：
1. 在 Android 设备上：设置 → 应用 → 公证办理 → 权限
2. 查找"显示在其他应用上层"或"悬浮窗"权限
3. 确保该权限已开启

#### 如果权限未授予：
- Android 会显示通知提示需要权限
- 或显示 Toast 提示："隐私模式需要悬浮窗权限，请在设置中开启"

### 2. 查看日志

#### 使用 adb logcat 查看详细日志：

```bash
# 查看所有隐私模式相关日志
adb logcat | grep "DEBUG_PRIVACY"

# 查看 Rust 层日志
adb logcat | grep "privacy"

# 查看 MainService 日志
adb logcat MainService:D *:S

# 查看 PrivacyModeService 日志
adb logcat PrivacyModeService:D *:S
```

### 3. 日志分析

#### 正常的日志流程应该是：

**Rust 层 (被控端)：**
```
DEBUG_PRIVACY: android turn_on_privacy called, conn_id: XXX
DEBUG_PRIVACY: Attempting to turn on Android privacy mode
DEBUG_PRIVACY: About to call JNI - toggle_privacy_mode with true
DEBUG_PRIVACY: JNI call succeeded
DEBUG_PRIVACY: Privacy mode successfully enabled
```

**Android 原生层 (MainService)：**
```
DEBUG_PRIVACY: MainService received toggle_privacy_mode: true
DEBUG_PRIVACY: Starting PrivacyModeService...
DEBUG_PRIVACY: PrivacyModeService start call completed
```

**Android 服务层 (PrivacyModeService)：**
```
DEBUG_PRIVACY: startPrivacyMode called, isActive=false
DEBUG_PRIVACY: Creating intent to start PrivacyModeService
DEBUG_PRIVACY: Starting foreground service (Android 8.0+)
DEBUG_PRIVACY: Service start initiated successfully
DEBUG_PRIVACY: PrivacyModeService onCreate called
DEBUG_PRIVACY: isActive set to true
DEBUG_PRIVACY: Android M+ detected, checking overlay permission: true
DEBUG_PRIVACY: Overlay permission granted
DEBUG_PRIVACY: Creating foreground notification...
DEBUG_PRIVACY: Foreground notification created successfully
DEBUG_PRIVACY: Creating black overlay...
DEBUG_PRIVACY: Black overlay created successfully
DEBUG_PRIVACY: ===== Privacy mode activated successfully =====
```

### 4. 常见问题及解决方案

#### 问题 1：权限未授予
**日志特征：**
```
DEBUG_PRIVACY: Overlay permission not granted - CANNOT SHOW BLACK SCREEN
```

**解决方案：**
1. 打开 Android 设置
2. 进入应用管理 → 公证办理
3. 找到"显示在其他应用上层"权限并开启
4. 重新尝试开启隐私模式

#### 问题 2：JNI 调用失败
**日志特征：**
```
DEBUG_PRIVACY: JNI call failed: XXX
```

**可能原因：**
- MainService 未正确初始化
- JVM 上下文丢失

**解决方案：**
- 重启 MainService（断开所有连接后重新连接）
- 重启 Android 应用

#### 问题 3：Service 启动失败
**日志特征：**
```
DEBUG_PRIVACY: Failed to start service
```

**可能原因：**
- Android 8.0+ 需要前台服务权限
- 电池优化限制了后台服务

**解决方案：**
1. 关闭电池优化：设置 → 电池 → 电池优化 → 公证办理 → 不优化
2. 允许后台运行：设置 → 应用 → 公证办理 → 电池 → 允许后台活动

#### 问题 4：黑屏创建失败
**日志特征：**
```
DEBUG_PRIVACY: Failed to create black overlay
```

**可能原因：**
- WindowManager 无法添加视图
- 系统版本兼容性问题

**解决方案：**
- 检查 Android 版本（需要 Android 6.0+）
- 查看详细错误堆栈

### 5. 网络协议检查

确认 PC 端是否正确发送了隐私模式开启命令：

#### PC 端检查：
1. 确认点击了隐私模式按钮
2. 查看是否有错误提示
3. 检查连接状态是否正常

#### Android 端检查：
查看是否收到 `toggle_privacy_mode` 消息：
```bash
adb logcat | grep "toggle_privacy_mode"
```

### 6. 重新构建应用

如果修改了代码，需要重新构建 Android 应用：

```bash
# 进入 flutter 目录
cd flutter

# 清理旧的构建
flutter clean

# 重新构建 APK
flutter build apk --release
# 或者构建调试版本
flutter build apk --debug

# 安装到设备
flutter install
```

### 7. 完整测试流程

1. **准备工作：**
   - 确保 Android 设备已授予悬浮窗权限
   - 启动 adb logcat 监控日志
   - 确保 Android 端 MainService 正在运行

2. **测试步骤：**
   - PC 端连接到 Android 设备
   - PC 端点击隐私模式按钮
   - 观察 Android 屏幕是否出现黑屏
   - 检查日志输出

3. **验证结果：**
   - ✅ Android 屏幕显示黑色遮罩层和提示文字
   - ✅ PC 端可以看到 Android 屏幕内容
   - ✅ PC 端可以正常控制 Android 设备
   - ✅ 日志显示所有步骤成功

## 技术细节

### 隐私模式工作原理

1. **PC 端（控制端）** 点击隐私模式按钮
2. 发送 `TogglePrivacyMode` 消息到 **Android 端（被控端）**
3. Android 端 Rust 层接收消息，调用 `turn_on_privacy()`
4. 通过 JNI 调用 `MainService.rustSetByName("toggle_privacy_mode", "true")`
5. `MainService` 启动 `PrivacyModeService`
6. `PrivacyModeService` 创建全屏黑色 TextView 遮罩层
7. 遮罩层显示在 Android 屏幕上，阻止用户看到真实内容
8. PC 端仍然可以看到真实屏幕内容并进行控制

### 关键代码位置

- Rust 实现：`src/privacy_mode/android.rs`
- JNI 接口：`libs/scrap/src/android/ffi.rs`
- Android Service：`flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt`
- 隐私模式服务：`flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/PrivacyModeService.kt`
- 服务器端处理：`src/server/connection.rs`

## 联系支持

如果按照以上步骤仍然无法解决问题，请提供：
1. 完整的 adb logcat 日志（包含 DEBUG_PRIVACY 标签）
2. Android 版本和设备型号
3. 是否已授予悬浮窗权限
4. PC 端是否有错误提示
