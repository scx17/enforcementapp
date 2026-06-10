import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/alarm/application/alarm_provider.dart';
import 'package:hdc_mobile/features/alarm/presentation/alarm_detail_page.dart';
import 'package:hdc_mobile/shared/models/alarm_model.dart';
import 'package:hdc_mobile/shared/widgets/empty_state.dart';

enum _AlarmFilter { all, high, mid, low }

class AlarmPage extends ConsumerStatefulWidget {
  const AlarmPage({super.key});

  @override
  ConsumerState<AlarmPage> createState() => _AlarmPageState();
}

class _AlarmPageState extends ConsumerState<AlarmPage> {
  _AlarmFilter _filter = _AlarmFilter.all;

  @override
  Widget build(BuildContext context) {
    final alarms = ref.watch(alarmListProvider);
    final unread = ref.watch(unreadAlarmCountProvider);
    final filtered = _applyFilter(alarms);

    return Scaffold(
      backgroundColor: AppColors.scaffold,
      appBar: AppBar(
        title: Row(
          children: [
            const Text('告警通知'),
            if (unread > 0) ...[
              const SizedBox(width: 8),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: AppColors.alarm,
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Text(
                  '$unread',
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ],
        ),
        actions: [
          if (alarms.isNotEmpty)
            TextButton(
              onPressed: () =>
                  ref.read(alarmListProvider.notifier).markAllAsRead(),
              child: const Text(
                '全部已读',
                style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
              ),
            ),
        ],
      ),
      body: Column(
        children: [
          // 过滤器
          _FilterBar(
            current: _filter,
            alarms: alarms,
            onChanged: (f) => setState(() => _filter = f),
          ),
          // 列表
          Expanded(
            child: filtered.isEmpty
                ? const EmptyState(
                    icon: Icons.notifications_off_outlined,
                    message: '暂无告警',
                    description: '这里会实时显示设备告警信息',
                  )
                : ListView.builder(
                    padding: const EdgeInsets.only(bottom: 24),
                    itemCount: filtered.length,
                    itemBuilder: (_, i) => _AlarmCard(
                      alarm: filtered[i],
                      onTap: () {
                        ref
                            .read(alarmListProvider.notifier)
                            .markAsRead(filtered[i].id);
                        Navigator.of(context).push(
                          MaterialPageRoute<void>(
                            builder: (_) =>
                                AlarmDetailPage(alarm: filtered[i]),
                          ),
                        );
                      },
                    ),
                  ),
          ),
        ],
      ),
    );
  }

  List<AlarmModel> _applyFilter(List<AlarmModel> alarms) {
    return switch (_filter) {
      _AlarmFilter.all => alarms,
      _AlarmFilter.high => alarms.where((a) => a.isHigh).toList(),
      _AlarmFilter.mid => alarms.where((a) => a.isMid).toList(),
      _AlarmFilter.low => alarms.where((a) => a.isLow).toList(),
    };
  }
}

class _FilterBar extends StatelessWidget {
  const _FilterBar({
    required this.current,
    required this.alarms,
    required this.onChanged,
  });

  final _AlarmFilter current;
  final List<AlarmModel> alarms;
  final void Function(_AlarmFilter) onChanged;

  @override
  Widget build(BuildContext context) {
    final filters = [
      (filter: _AlarmFilter.all, label: '全部', count: alarms.length),
      (
        filter: _AlarmFilter.high,
        label: '紧急',
        count: alarms.where((a) => a.isHigh).length
      ),
      (
        filter: _AlarmFilter.mid,
        label: '重要',
        count: alarms.where((a) => a.isMid).length
      ),
      (
        filter: _AlarmFilter.low,
        label: '一般',
        count: alarms.where((a) => a.isLow).length
      ),
    ];

    return Container(
      color: AppColors.card,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: filters.map((item) {
          final isActive = current == item.filter;
          return Expanded(
            child: GestureDetector(
              onTap: () => onChanged(item.filter),
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 150),
                margin: const EdgeInsets.symmetric(horizontal: 3),
                padding: const EdgeInsets.symmetric(vertical: 8),
                decoration: BoxDecoration(
                  color: isActive
                      ? AppColors.primary.withValues(alpha: 0.15)
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(
                    color: isActive
                        ? AppColors.primary.withValues(alpha: 0.5)
                        : Colors.transparent,
                  ),
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      '${item.count}',
                      style: TextStyle(
                        color: isActive
                            ? AppColors.primary
                            : AppColors.textSecondary,
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      item.label,
                      style: TextStyle(
                        color: isActive
                            ? AppColors.primary
                            : AppColors.textMuted,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        }).toList(),
      ),
    );
  }
}

class _AlarmCard extends StatelessWidget {
  const _AlarmCard({required this.alarm, required this.onTap});

  final AlarmModel alarm;
  final VoidCallback onTap;

  Color get _levelColor => switch (alarm.level) {
        'high' => AppColors.alarm,
        'mid' => AppColors.warning,
        _ => AppColors.textSecondary,
      };

  String get _levelLabel => switch (alarm.level) {
        'high' => '紧急',
        'mid' => '重要',
        _ => '一般',
      };

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
        decoration: BoxDecoration(
          color: AppColors.card,
          borderRadius: BorderRadius.circular(8),
          border: Border(
            left: BorderSide(
              color: alarm.isRead ? Colors.transparent : _levelColor,
              width: 3,
            ),
          ),
        ),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 级别标签
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                decoration: BoxDecoration(
                  color: _levelColor.withValues(alpha: 0.15),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(
                  _levelLabel,
                  style: TextStyle(
                    color: _levelColor,
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              const SizedBox(width: 10),
              // 内容
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            alarm.deviceName ??
                                alarm.customCodeDisplay ??
                                alarm.deviceId,
                            style: TextStyle(
                              color: alarm.isRead
                                  ? AppColors.textSecondary
                                  : AppColors.textPrimary,
                              fontSize: 14,
                              fontWeight: alarm.isRead
                                  ? FontWeight.normal
                                  : FontWeight.w500,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        if (!alarm.isRead)
                          Container(
                            width: 8,
                            height: 8,
                            decoration: const BoxDecoration(
                              shape: BoxShape.circle,
                              color: AppColors.alarm,
                            ),
                          ),
                      ],
                    ),
                    if (alarm.message != null) ...[
                      const SizedBox(height: 4),
                      Text(
                        alarm.message!,
                        style: const TextStyle(
                          color: AppColors.textSecondary,
                          fontSize: 12,
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ],
                    const SizedBox(height: 6),
                    Text(
                      DateFormat('MM-dd HH:mm:ss').format(alarm.occurredAt),
                      style: const TextStyle(
                        color: AppColors.textMuted,
                        fontSize: 11,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
