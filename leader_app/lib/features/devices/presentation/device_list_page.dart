import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/alarm/application/alarm_provider.dart';
import 'package:hdc_mobile/features/devices/application/device_provider.dart';
import 'package:hdc_mobile/features/monitor/application/monitor_controller.dart';
import 'package:hdc_mobile/features/conference/presentation/member_select_page.dart';
import 'package:hdc_mobile/features/monitor/presentation/playback_page.dart';
import 'package:hdc_mobile/features/talk/presentation/intercom_page.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';
import 'package:hdc_mobile/shared/utils/device_label.dart';
import 'package:hdc_mobile/shared/widgets/empty_state.dart';
import 'package:hdc_mobile/shared/widgets/status_indicator.dart';

class DeviceListPage extends ConsumerStatefulWidget {
  const DeviceListPage({super.key});

  @override
  ConsumerState<DeviceListPage> createState() => _DeviceListPageState();
}

class _DeviceListPageState extends ConsumerState<DeviceListPage> {
  final _searchCtrl = TextEditingController();
  String _searchQuery = '';

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final deviceAsync = ref.watch(deviceListProvider);
    final onlineCount = ref.watch(onlineCountProvider);
    final offlineCount = ref.watch(offlineCountProvider);
    final todayAlarms = ref.watch(todayAlarmCountProvider);

    return Scaffold(
      backgroundColor: AppColors.scaffold,
      appBar: AppBar(
        title: const Text('设备列表'),
        actions: [
          IconButton(
            icon: const Icon(Icons.campaign_outlined),
            tooltip: '集群喊话',
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (_) => const MemberSelectPage(),
              ),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.refresh_outlined),
            onPressed: () => ref.read(deviceListProvider.notifier).refresh(),
          ),
        ],
      ),
      body: Column(
        children: [
          // 统计卡片
          _StatsRow(
            onlineCount: onlineCount,
            offlineCount: offlineCount,
            todayAlarms: todayAlarms,
          ),
          const SizedBox(height: 8),
          // 搜索框
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: TextField(
              controller: _searchCtrl,
              onChanged: (v) => setState(() => _searchQuery = v.trim()),
              style: const TextStyle(color: AppColors.textPrimary, fontSize: 14),
              decoration: InputDecoration(
                hintText: '搜索设备名称或编号',
                prefixIcon: const Icon(
                  Icons.search,
                  color: AppColors.textSecondary,
                  size: 20,
                ),
                suffixIcon: _searchQuery.isNotEmpty
                    ? IconButton(
                        icon: const Icon(
                          Icons.clear,
                          color: AppColors.textSecondary,
                          size: 18,
                        ),
                        onPressed: () {
                          _searchCtrl.clear();
                          setState(() => _searchQuery = '');
                        },
                      )
                    : null,
              ),
            ),
          ),
          const SizedBox(height: 12),
          // 设备列表
          Expanded(
            child: deviceAsync.when(
              loading: () => const Center(
                child: CircularProgressIndicator(color: AppColors.primary),
              ),
              error: (e, _) => EmptyState(
                icon: Icons.wifi_off_outlined,
                message: '加载失败',
                description: e.toString(),
                action: ElevatedButton(
                  onPressed: () =>
                      ref.read(deviceListProvider.notifier).refresh(),
                  child: const Text('重试'),
                ),
              ),
              data: (devices) {
                final filtered = _filter(devices);
                if (filtered.isEmpty) {
                  return const EmptyState(
                    icon: Icons.devices_other_outlined,
                    message: '没有找到设备',
                    description: '尝试修改搜索条件',
                  );
                }
                return RefreshIndicator(
                  color: AppColors.primary,
                  backgroundColor: AppColors.card,
                  onRefresh: () =>
                      ref.read(deviceListProvider.notifier).refresh(),
                  child: ListView.separated(
                    padding: const EdgeInsets.only(
                      left: 16,
                      right: 16,
                      bottom: 24,
                    ),
                    itemCount: filtered.length,
                    separatorBuilder: (_, sep) => const SizedBox(height: 8),
                    itemBuilder: (_, i) => _DeviceCard(device: filtered[i]),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  List<DeviceModel> _filter(List<DeviceModel> devices) {
    if (_searchQuery.isEmpty) return devices;
    final q = _searchQuery.toLowerCase();
    return devices.where((d) {
      final label = formatDeviceLabel(d).toLowerCase();
      final id = d.deviceId.toLowerCase();
      final code = (d.customCode ?? '').toLowerCase();
      return label.contains(q) || id.contains(q) || code.contains(q);
    }).toList();
  }
}

class _StatsRow extends StatelessWidget {
  const _StatsRow({
    required this.onlineCount,
    required this.offlineCount,
    required this.todayAlarms,
  });

  final int onlineCount;
  final int offlineCount;
  final int todayAlarms;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
      child: Row(
        children: [
          Expanded(
            child: _StatCard(
              label: '在线',
              value: '$onlineCount',
              valueColor: AppColors.online,
              icon: Icons.videocam_outlined,
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: _StatCard(
              label: '离线',
              value: '$offlineCount',
              valueColor: AppColors.offline,
              icon: Icons.videocam_off_outlined,
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: _StatCard(
              label: '今日告警',
              value: '$todayAlarms',
              valueColor: todayAlarms > 0 ? AppColors.alarm : AppColors.textSecondary,
              icon: Icons.notifications_outlined,
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: _StatCard(
              label: '执勤中',
              value: '$onlineCount',
              valueColor: AppColors.primary,
              icon: Icons.shield_outlined,
            ),
          ),
        ],
      ),
    );
  }
}

class _StatCard extends StatelessWidget {
  const _StatCard({
    required this.label,
    required this.value,
    required this.valueColor,
    required this.icon,
  });

  final String label;
  final String value;
  final Color valueColor;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 10),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        children: [
          Icon(icon, color: valueColor, size: 18),
          const SizedBox(height: 6),
          Text(
            value,
            style: TextStyle(
              color: valueColor,
              fontSize: 20,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            label,
            style: const TextStyle(
              color: AppColors.textMuted,
              fontSize: 11,
            ),
          ),
        ],
      ),
    );
  }
}

class _DeviceCard extends ConsumerWidget {
  const _DeviceCard({required this.device});

  final DeviceModel device;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final label = formatDeviceLabel(device);
    final shortCode = device.customCodeDisplay ?? device.customCode ?? '';

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: device.isOnline
              ? AppColors.primary.withValues(alpha: 0.15)
              : Colors.transparent,
        ),
      ),
      child: Row(
        children: [
          // 设备头像
          Stack(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: AppColors.surface,
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(
                  Icons.videocam_outlined,
                  color: device.isOnline
                      ? AppColors.primary
                      : AppColors.textMuted,
                  size: 24,
                ),
              ),
              Positioned(
                right: 2,
                bottom: 2,
                child: StatusIndicator(isOnline: device.isOnline, size: 8),
              ),
            ],
          ),
          const SizedBox(width: 12),
          // 设备信息
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: const TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 2),
                Row(
                  children: [
                    if (shortCode.isNotEmpty) ...[
                      Text(
                        shortCode,
                        style: const TextStyle(
                          color: AppColors.textMuted,
                          fontSize: 11,
                        ),
                      ),
                      const SizedBox(width: 8),
                    ],
                    Text(
                      device.isOnline ? '在线' : '离线',
                      style: TextStyle(
                        color: device.isOnline
                            ? AppColors.online
                            : AppColors.offline,
                        fontSize: 11,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          // 操作按钮
          _DeviceActions(device: device),
        ],
      ),
    );
  }
}

class _DeviceActions extends ConsumerWidget {
  const _DeviceActions({required this.device});

  final DeviceModel device;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final playbackBtn = _ActionButton(
      label: '回放',
      color: AppColors.textSecondary,
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => PlaybackPage(device: device),
        ),
      ),
    );

    if (device.isOnline) {
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _ActionButton(
            label: '预览',
            color: AppColors.primary,
            onTap: () {
              // 在当前激活槽点播该设备，并切到监控 tab
              final monitor = ref.read(monitorControllerProvider.notifier);
              final activeSlot = ref.read(monitorControllerProvider).activeSlot;
              monitor.playInSlot(index: activeSlot, device: device);
              context.go('/monitor');
            },
          ),
          const SizedBox(width: 6),
          _ActionButton(
            label: '对讲',
            color: AppColors.warning,
            onTap: () => Navigator.of(context).push(
              MaterialPageRoute<void>(
                builder: (_) => IntercomPage(device: device),
              ),
            ),
          ),
          const SizedBox(width: 6),
          playbackBtn,
        ],
      );
    }
    return playbackBtn;
  }
}

class _ActionButton extends StatelessWidget {
  const _ActionButton({
    required this.label,
    required this.color,
    required this.onTap,
  });

  final String label;
  final Color color;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: onTap == null ? 0.4 : 1.0,
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(4),
            border: Border.all(color: color.withValues(alpha: 0.3)),
          ),
          child: Text(
            label,
            style: TextStyle(
              color: color,
              fontSize: 12,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
      ),
    );
  }
}
