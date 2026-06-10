import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:hdc_mobile/core/config/app_config.dart';
import 'package:hdc_mobile/core/signalr/hub_connection_manager.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/auth/application/auth_controller.dart';

class SettingsPage extends ConsumerStatefulWidget {
  const SettingsPage({super.key});

  @override
  ConsumerState<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends ConsumerState<SettingsPage> {
  bool _alarmNotification = true;

  @override
  Widget build(BuildContext context) {
    final authAsync = ref.watch(authControllerProvider);
    final authState = authAsync.valueOrNull ?? const AuthState();
    final hub = ref.read(hubConnectionManagerProvider);
    final isHubConnected = hub.isConnected;

    return Scaffold(
      backgroundColor: AppColors.scaffold,
      appBar: AppBar(title: const Text('我的')),
      body: ListView(
        children: [
          const SizedBox(height: 8),
          // 用户信息卡片
          _UserCard(
            displayName: authState.displayName ?? authState.userName ?? '未知用户',
            userName: authState.userName ?? '',
          ),
          const SizedBox(height: 12),
          // 连接状态
          _SectionCard(
            title: '连接状态',
            children: [
              _InfoRow(
                label: '服务器',
                value: AppConfig.instance.serverUrl,
                valueMaxLines: 2,
              ),
              _InfoRow(
                label: '实时推送',
                valueWidget: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Container(
                      width: 8,
                      height: 8,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: isHubConnected
                            ? AppColors.online
                            : AppColors.offline,
                      ),
                    ),
                    const SizedBox(width: 6),
                    Text(
                      isHubConnected ? '已连接' : '未连接',
                      style: TextStyle(
                        color: isHubConnected
                            ? AppColors.online
                            : AppColors.textMuted,
                        fontSize: 13,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          // 偏好设置
          _SectionCard(
            title: '偏好设置',
            children: [
              _SwitchRow(
                label: '告警通知',
                description: '接收设备告警推送',
                value: _alarmNotification,
                onChanged: (v) => setState(() => _alarmNotification = v),
              ),
            ],
          ),
          const SizedBox(height: 12),
          // 关于
          _SectionCard(
            title: '关于',
            children: [
              _InfoRow(
                label: '应用版本',
                value: 'v1.0.0',
              ),
              _InfoRow(
                label: '平台',
                value: AppConfig.instance.orgName,
              ),
            ],
          ),
          const SizedBox(height: 24),
          // 退出登录
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: OutlinedButton(
              onPressed: authState.isLoading
                  ? null
                  : () => _handleLogout(context),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.alarm,
                side: const BorderSide(color: AppColors.alarm, width: 1),
                minimumSize: const Size(double.infinity, 48),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
              child: authState.isLoading
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        valueColor:
                            AlwaysStoppedAnimation(AppColors.alarm),
                      ),
                    )
                  : const Text('退出登录'),
            ),
          ),
          const SizedBox(height: 32),
        ],
      ),
    );
  }

  Future<void> _handleLogout(BuildContext ctx) async {
    final confirmed = await showDialog<bool>(
      context: ctx,
      builder: (dialogCtx) => AlertDialog(
        backgroundColor: AppColors.card,
        title: const Text(
          '确认退出',
          style: TextStyle(color: AppColors.textPrimary),
        ),
        content: const Text(
          '退出登录后需重新输入账号密码',
          style: TextStyle(color: AppColors.textSecondary),
        ),
        actions: [
          TextButton(
            onPressed: () => dialogCtx.pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => dialogCtx.pop(true),
            child: const Text(
              '退出',
              style: TextStyle(color: AppColors.alarm),
            ),
          ),
        ],
      ),
    );

    if (confirmed != true) return;
    await ref.read(authControllerProvider.notifier).logout();
    if (!mounted) return;
    // ignore: use_build_context_synchronously
    context.go('/login');
  }
}

class _UserCard extends StatelessWidget {
  const _UserCard({required this.displayName, required this.userName});

  final String displayName;
  final String userName;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          // 头像
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              gradient: const LinearGradient(
                colors: [Color(0xFF1F6FEB), Color(0xFF0D4C9E)],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              borderRadius: BorderRadius.circular(28),
            ),
            child: Center(
              child: Text(
                displayName.isNotEmpty ? displayName[0].toUpperCase() : 'U',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 24,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ),
          const SizedBox(width: 14),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                displayName,
                style: const TextStyle(
                  color: AppColors.textPrimary,
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                '@$userName',
                style: const TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _SectionCard extends StatelessWidget {
  const _SectionCard({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 4, bottom: 8),
            child: Text(
              title,
              style: const TextStyle(
                color: AppColors.textMuted,
                fontSize: 12,
                fontWeight: FontWeight.w500,
                letterSpacing: 0.5,
              ),
            ),
          ),
          Container(
            decoration: BoxDecoration(
              color: AppColors.card,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Column(
              children: children.indexed.map((entry) {
                final (i, child) = entry;
                return Column(
                  children: [
                    child,
                    if (i < children.length - 1)
                      const Divider(
                        height: 1,
                        indent: 16,
                        endIndent: 16,
                      ),
                  ],
                );
              }).toList(),
            ),
          ),
        ],
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({
    required this.label,
    this.value,
    this.valueWidget,
    this.valueMaxLines = 1,
  });

  final String label;
  final String? value;
  final Widget? valueWidget;
  final int valueMaxLines;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
      child: Row(
        crossAxisAlignment: valueMaxLines > 1
            ? CrossAxisAlignment.start
            : CrossAxisAlignment.center,
        children: [
          Text(
            label,
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 14,
            ),
          ),
          const Spacer(),
          if (valueWidget != null)
            valueWidget!
          else
            Flexible(
              child: Text(
                value ?? '',
                style: const TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 13,
                ),
                maxLines: valueMaxLines,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.end,
              ),
            ),
        ],
      ),
    );
  }
}

class _SwitchRow extends StatelessWidget {
  const _SwitchRow({
    required this.label,
    required this.description,
    required this.value,
    required this.onChanged,
  });

  final String label;
  final String description;
  final bool value;
  final void Function(bool) onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: const TextStyle(
                    color: AppColors.textSecondary,
                    fontSize: 14,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  description,
                  style: const TextStyle(
                    color: AppColors.textMuted,
                    fontSize: 11,
                  ),
                ),
              ],
            ),
          ),
          Switch(
            value: value,
            onChanged: onChanged,
            activeThumbColor: AppColors.primary,
            activeTrackColor: AppColors.primary.withValues(alpha: 0.4),
          ),
        ],
      ),
    );
  }
}
