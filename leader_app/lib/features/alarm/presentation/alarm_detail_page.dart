import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/devices/application/device_provider.dart';
import 'package:hdc_mobile/features/monitor/application/monitor_controller.dart';
import 'package:hdc_mobile/features/monitor/presentation/playback_page.dart';
import 'package:hdc_mobile/features/talk/presentation/intercom_page.dart';
import 'package:hdc_mobile/shared/models/alarm_model.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';

class AlarmDetailPage extends ConsumerWidget {
  const AlarmDetailPage({super.key, required this.alarm});

  final AlarmModel alarm;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final devices = ref.watch(deviceListProvider).valueOrNull ?? [];
    final matched =
        devices.where((d) => d.deviceId == alarm.deviceId).toList();
    // 找不到真实设备时，用告警信息构造最小设备对象供对讲/回放使用
    final device = matched.isNotEmpty
        ? matched.first
        : DeviceModel(
            deviceId: alarm.deviceId,
            name: alarm.deviceName ?? '',
            status: 'OFF',
            customCodeDisplay: alarm.customCodeDisplay,
          );
    final isOnline = matched.isNotEmpty && matched.first.isOnline;

    return Scaffold(
      backgroundColor: AppColors.scaffold,
      appBar: AppBar(
        backgroundColor: AppColors.scaffold,
        title: const Text('告警详情'),
      ),
      body: ListView(
        children: [
          _Banner(alarm: alarm),
          const SizedBox(height: 8),
          _InfoRow(label: '设备名称', value: _deviceLabel()),
          _InfoRow(label: '告警类型', value: alarm.type),
          _InfoRow(
            label: '告警级别',
            valueWidget: _LevelBadge(level: alarm.level),
          ),
          _InfoRow(
            label: '发生时间',
            value: DateFormat('yyyy-MM-dd HH:mm:ss').format(alarm.occurredAt),
          ),
          // 详情页属于 debug 上下文，可显示完整 GB28181 编号
          _InfoRow(label: '设备编号', value: alarm.deviceId),
          _InfoRow(
            label: '处理状态',
            valueWidget: const _StatusBadge(),
          ),
          if (alarm.message?.isNotEmpty == true) ...[
            const SizedBox(height: 8),
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 16),
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: AppColors.card,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    '告警描述',
                    style: TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    alarm.message!,
                    style: const TextStyle(
                      color: AppColors.textPrimary,
                      fontSize: 14,
                      height: 1.5,
                    ),
                  ),
                ],
              ),
            ),
          ],
          const SizedBox(height: 24),
          _Actions(
            device: device,
            isOnline: isOnline,
            onLivePressed: isOnline
                ? () {
                    final monitor =
                        ref.read(monitorControllerProvider.notifier);
                    final active =
                        ref.read(monitorControllerProvider).activeSlot;
                    monitor.playInSlot(index: active, device: device);
                    context.go('/monitor');
                  }
                : null,
          ),
        ],
      ),
    );
  }

  String _deviceLabel() {
    if (alarm.deviceName?.isNotEmpty == true) return alarm.deviceName!;
    if (alarm.customCodeDisplay?.isNotEmpty == true) {
      return alarm.customCodeDisplay!;
    }
    return alarm.deviceId;
  }
}

class _Banner extends StatelessWidget {
  const _Banner({required this.alarm});

  final AlarmModel alarm;

  @override
  Widget build(BuildContext context) {
    final color = switch (alarm.level) {
      'high' => AppColors.alarm,
      'mid' => AppColors.warning,
      _ => AppColors.primary,
    };
    return Container(
      width: double.infinity,
      color: color.withValues(alpha: 0.12),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
      child: Row(
        children: [
          Icon(Icons.warning_amber_rounded, color: color, size: 32),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  alarm.type,
                  style: TextStyle(
                    color: color,
                    fontSize: 18,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  DateFormat('MM-dd HH:mm:ss').format(alarm.occurredAt),
                  style: const TextStyle(
                    color: AppColors.textSecondary,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, this.value, this.valueWidget});

  final String label;
  final String? value;
  final Widget? valueWidget;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AppColors.divider)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 88,
            child: Text(
              label,
              style: const TextStyle(
                color: AppColors.textSecondary,
                fontSize: 13,
              ),
            ),
          ),
          Expanded(
            child: valueWidget ??
                Text(
                  value ?? '',
                  style: const TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 13,
                    fontWeight: FontWeight.w500,
                  ),
                ),
          ),
        ],
      ),
    );
  }
}

class _LevelBadge extends StatelessWidget {
  const _LevelBadge({required this.level});

  final String level;

  @override
  Widget build(BuildContext context) {
    final (color, text) = switch (level) {
      'high' => (AppColors.alarm, '紧急'),
      'mid' => (AppColors.warning, '重要'),
      _ => (AppColors.primary, '一般'),
    };
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(
          text,
          style: TextStyle(
            color: color,
            fontSize: 11,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge();

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        decoration: BoxDecoration(
          color: AppColors.alarm.withValues(alpha: 0.15),
          borderRadius: BorderRadius.circular(4),
        ),
        child: const Text(
          '待处理',
          style: TextStyle(
            color: AppColors.alarm,
            fontSize: 11,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }
}

class _Actions extends StatelessWidget {
  const _Actions({
    required this.device,
    required this.isOnline,
    required this.onLivePressed,
  });

  final DeviceModel device;
  final bool isOnline;
  final VoidCallback? onLivePressed;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Row(
        children: [
          Expanded(
            child: FilledButton.icon(
              onPressed: onLivePressed,
              icon: const Icon(Icons.videocam, size: 18),
              label: const Text('实时视频'),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.primary,
                padding: const EdgeInsets.symmetric(vertical: 13),
              ),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: OutlinedButton.icon(
              onPressed: isOnline
                  ? () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => IntercomPage(device: device),
                        ),
                      )
                  : null,
              icon: const Icon(Icons.mic, size: 18),
              label: const Text('对讲'),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.warning,
                side: const BorderSide(color: AppColors.warning),
                padding: const EdgeInsets.symmetric(vertical: 13),
              ),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: OutlinedButton.icon(
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute<void>(
                  builder: (_) => PlaybackPage(device: device),
                ),
              ),
              icon: const Icon(Icons.replay, size: 18),
              label: const Text('回放'),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.textSecondary,
                side: const BorderSide(color: AppColors.divider),
                padding: const EdgeInsets.symmetric(vertical: 13),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
