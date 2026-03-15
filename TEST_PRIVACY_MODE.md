# 隐私模式问题诊断

## 问题现象
- PC 端点击隐私模式按钮
- 按钮立即恢复，不显示勾选
- Android 端完全没有收到 `toggle_privacy_mode` 消息

## 当前日志分析

### Android 端日志（有）
```
DEBUG_PRIVACY: ===== MainService onCreate called =====
DEBUG_PRIVACY: FFI initialized successfully
DEBUG_PRIVACY: ===== rustSetByName called =====
DEBUG_PRIVACY: name=add_connection (连接建立)
DEBUG_PRIVACY: name=half_scale (缩放设置)
DEBUG_PRIVACY: name=stop_capture (停止捕获)
```

### Android 端日志（缺失）
```
DEBUG_PRIVACY: toggle_privacy_mode request received  ← 这条日志从未出现！
```

## 问题定位

问题不在 Android 端，而在 **PC 端**！

### 可能原因 1：Flutter 检查失败

查看 `flutter/lib/common/widgets/toolbar.dart:790`：

```dart
if (pi.platform == kPeerPlatformAndroid) {
    const androidImplKey = 'privacy_mode_impl_android';
    return [
      getDefaultMenu((sid, opt) async {
        bind.sessionTogglePrivacyMode(
            sessionId: sid, implKey: androidImplKey, on: privacyModeState.isEmpty);
        togglePrivacyModeTime = DateTime.now();
      })
    ];
  }
```

**检查点：`pi.platform` 是否等于 "Android"？**

### 可能原因 2：版本检查失败

查看 `src/client/io_loop.rs:1101`：

```rust
if lc.version >= hbb_common::get_version_number("1.2.4")
```

PC 端可能检测到 Android 版本 < 1.2.4，所以不发送命令。

### 可能原因 3：隐私模式特性未报告

Android 端在连接时需要报告支持隐私模式：

```rust
pi.features = Some(Features {
    privacy_mode: privacy_mode::is_privacy_mode_supported(),  ← 需要返回 true
    ...
})
```

## 诊断步骤

### 步骤 1：添加 PC 端日志（需要修改 PC 端代码）

在 `src/ui_session_interface.rs:388` 添加日志：

```rust
pub fn toggle_privacy_mode(&self, impl_key: String, on: bool) {
    log::info!("DEBUG_PRIVACY: PC端 toggle_privacy_mode called: impl_key={}, on={}", impl_key, on);
    println!("DEBUG_PRIVACY: PC端 toggle_privacy_mode called: impl_key={}, on={}", impl_key, on);
    
    let mut misc = Misc::new();
    misc.set_toggle_privacy_mode(TogglePrivacyMode {
        impl_key,
        on,
        ..Default::default()
    });
    let mut msg_out = Message::new();
    msg_out.set_misc(misc);
    self.send(Data::Message(msg_out));
    
    log::info!("DEBUG_PRIVACY: PC端 Message sent");
}
```

### 步骤 2：检查 Android 报告的信息

运行以下命令并重新连接：

```powershell
adb logcat -c
adb logcat | Select-String "Features|platform|privacy_mode.*supported|version"
```

查找类似这样的日志：
- `platform: "Android"`
- `privacy_mode: true` 或 `privacy_mode: false`
- 版本号信息

### 步骤 3：临时解决方案 - 修改 Flutter 代码

如果问题是检查失败，可以临时添加更多日志到 Flutter：

在 `flutter/lib/common/widgets/toolbar.dart:790` 前添加：

```dart
debugPrint('DEBUG_PRIVACY: Flutter检查 pi.platform=${pi.platform}, kPeerPlatformAndroid=$kPeerPlatformAndroid');
debugPrint('DEBUG_PRIVACY: privacyModeState=${privacyModeState.value}');

if (pi.platform == kPeerPlatformAndroid) {
    debugPrint('DEBUG_PRIVACY: Android检查通过，创建隐私模式菜单');
    // ... 现有代码
}
```

然后在菜单点击时添加：

```dart
getDefaultMenu((sid, opt) async {
    debugPrint('DEBUG_PRIVACY: Flutter准备调用 sessionTogglePrivacyMode');
    debugPrint('DEBUG_PRIVACY: sessionId=$sid, implKey=$androidImplKey, on=${privacyModeState.isEmpty}');
    
    bind.sessionTogglePrivacyMode(
        sessionId: sid, implKey: androidImplKey, on: privacyModeState.isEmpty);
    
    debugPrint('DEBUG_PRIVACY: Flutter调用完成');
    togglePrivacyModeTime = DateTime.now();
})
```

### 步骤 4：检查 Flutter 到 Rust 的桥接

在 `src/flutter_ffi.rs:340` 添加日志：

```rust
pub fn session_toggle_privacy_mode(session_id: SessionID, impl_key: String, on: bool) {
    log::info!("DEBUG_PRIVACY: flutter_ffi session_toggle_privacy_mode called");
    log::info!("DEBUG_PRIVACY: session_id={:?}, impl_key={}, on={}", session_id, impl_key, on);
    
    if let Some(session) = sessions::get_session_by_session_id(&session_id) {
        log::info!("DEBUG_PRIVACY: Session found, calling session.toggle_privacy_mode");
        session.toggle_privacy_mode(impl_key, on);
        log::info!("DEBUG_PRIVACY: session.toggle_privacy_mode called");
    } else {
        log::error!("DEBUG_PRIVACY: Session NOT found for session_id={:?}", session_id);
    }
}
```

## 快速测试

### 不需要修改 PC 端的测试

1. 在 PC 端，打开 RustDesk 的日志文件（如果有）
2. 或者运行 PC 端时带上详细日志参数
3. 点击隐私模式
4. 查看 PC 端是否有任何错误或警告

### Android 端测试

保持日志监控运行：

```powershell
adb logcat | Select-String "DEBUG_PRIVACY|platform|version|Features|supported"
```

断开并重新连接，观察：
1. Android 报告的 platform 值
2. Android 报告的 features 信息
3. 版本号

## 预期结果

### 如果一切正常，应该看到：

**连接时：**
```
platform: "Android"
privacy_mode: true
version: 1.3.x (或更高)
```

**点击隐私模式时（PC 端）：**
```
DEBUG_PRIVACY: Flutter准备调用 sessionTogglePrivacyMode
DEBUG_PRIVACY: flutter_ffi session_toggle_privacy_mode called  
DEBUG_PRIVACY: PC端 toggle_privacy_mode called
DEBUG_PRIVACY: PC端 Message sent
```

**点击隐私模式时（Android 端）：**
```
DEBUG_PRIVACY: toggle_privacy_mode request received: on=true
DEBUG_PRIVACY: android turn_on_privacy called
DEBUG_PRIVACY: ===== rustSetByName called =====
DEBUG_PRIVACY: name=toggle_privacy_mode, arg1=true
DEBUG_PRIVACY: ===== Privacy mode activated successfully =====
```

## 最可能的原因

基于当前的表现（点击立即返回，没有勾选），最可能的原因是：

1. **PC 端检查失败** - 某个条件不满足，根本没有发送命令
2. **平台识别错误** - PC 端没有识别到 Android 平台
3. **版本不兼容** - PC 端认为 Android 版本过低

需要添加 PC 端日志来确定具体原因。
