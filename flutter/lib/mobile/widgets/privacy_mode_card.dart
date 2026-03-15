import 'package:flutter/material.dart';
import 'package:flutter_hbb/common.dart';
import 'package:flutter_hbb/mobile/widgets/dialog.dart';
import 'package:flutter_hbb/mobile/pages/server_page.dart';
import 'package:get/get.dart';

/// 黑屏模式控制卡片
/// 允许用户直接在 Android 端控制黑屏，无需依赖 PC 端
class PrivacyModeCard extends StatefulWidget {
  const PrivacyModeCard({Key? key}) : super(key: key);

  @override
  State<PrivacyModeCard> createState() => _PrivacyModeCardState();
}

class _PrivacyModeCardState extends State<PrivacyModeCard> {
  final RxBool _isPrivacyModeOn = false.obs;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    debugPrint('DEBUG_PRIVACY: PrivacyModeCard 组件已加载！');
    _checkPrivacyModeStatus();
  }

  /// 检查当前黑屏模式状态
  Future<void> _checkPrivacyModeStatus() async {
    // TODO: 可以通过 platform channel 查询 PrivacyModeService 的状态
    // 目前假设初始状态为关闭
  }

  /// 切换黑屏模式
  Future<void> _togglePrivacyMode() async {
    if (_isLoading) return;

    setState(() {
      _isLoading = true;
    });

    try {
      final newState = !_isPrivacyModeOn.value;
      debugPrint('DEBUG_PRIVACY: Android端手动切换黑屏模式: $newState');

      if (newState) {
        // 开启前确认无障碍服务已就绪，避免服务崩溃中断连接
        await gFFI.invokeMethod("check_service");
        if (!gFFI.serverModel.inputOk) {
          showToast('请先在“材料保密授权”中开启无障碍服务');
          return;
        }
        // 黑屏依赖修改系统设置权限以调低亮度
        if (!await gFFI.serverModel.ensureWriteSettingsForPrivacy()) {
          showToast('请完成“自动办理授权”后再试');
          return;
        }
      }

      // 直接调用 Android 原生方法
      await gFFI.invokeMethod(
        'set_by_name',
        {'name': 'toggle_privacy_mode', 'value': newState.toString()},
      );

      _isPrivacyModeOn.value = newState;
      
      showToast(newState ? '黑屏模式已开启' : '黑屏模式已关闭');
      debugPrint('DEBUG_PRIVACY: 黑屏模式切换成功: $newState');
    } catch (e) {
      debugPrint('DEBUG_PRIVACY: 切换黑屏模式失败: $e');
      showToast('切换失败: $e');
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    debugPrint('DEBUG_PRIVACY: PrivacyModeCard build 方法被调用');
    return PaddingCard(
      title: '🔒 黑屏模式',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 说明文字
          Text(
            '开启后，手机屏幕将显示黑屏，但 PC 端仍可正常查看和控制',
            style: TextStyle(
              fontSize: 14,
              color: Colors.grey[600],
            ),
          ),
          SizedBox(height: 16),

          // 开关按钮
          Obx(() => SwitchListTile(
                title: Text(
                  _isPrivacyModeOn.value ? '黑屏模式：已开启' : '黑屏模式：已关闭',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                  ),
                ),
                subtitle: Text(
                  _isPrivacyModeOn.value 
                      ? '手机屏幕已黑屏，PC 端可正常查看'
                      : '点击开启以隐藏手机屏幕内容',
                  style: TextStyle(fontSize: 12),
                ),
                value: _isPrivacyModeOn.value,
                onChanged: _isLoading ? null : (value) => _togglePrivacyMode(),
                activeColor: Colors.green,
                contentPadding: EdgeInsets.zero,
              )),

          // 或者使用大按钮样式（二选一）
          // SizedBox(height: 8),
          // Obx(() => ElevatedButton.icon(
          //       onPressed: _isLoading ? null : _togglePrivacyMode,
          //       icon: Icon(
          //         _isPrivacyModeOn.value ? Icons.visibility_off : Icons.visibility,
          //       ),
          //       label: Text(
          //         _isPrivacyModeOn.value ? '关闭黑屏模式' : '开启黑屏模式',
          //         style: TextStyle(fontSize: 16),
          //       ),
          //       style: ElevatedButton.styleFrom(
          //         backgroundColor:
          //             _isPrivacyModeOn.value ? Colors.orange : Colors.green,
          //         foregroundColor: Colors.white,
          //         minimumSize: Size(double.infinity, 50),
          //         shape: RoundedRectangleBorder(
          //           borderRadius: BorderRadius.circular(8),
          //         ),
          //       ),
          //     )),

          // 警告提示
          if (_isPrivacyModeOn.value) ...[
            SizedBox(height: 12),
            Container(
              padding: EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.orange[50],
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.orange[300]!),
              ),
              child: Row(
                children: [
                  Icon(Icons.warning_amber, color: Colors.orange[700], size: 20),
                  SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '黑屏模式已激活，您的屏幕内容已被隐藏',
                      style: TextStyle(
                        color: Colors.orange[900],
                        fontSize: 12,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],

          // 权限提示
          SizedBox(height: 12),
          Text(
            '💡 提示：首次使用需要授予"显示在其他应用上层"权限',
            style: TextStyle(
              fontSize: 12,
              color: Colors.blue[700],
              fontStyle: FontStyle.italic,
            ),
          ),
        ],
      ),
    );
  }
}
