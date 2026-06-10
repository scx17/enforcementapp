import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/config/constants.dart';
import 'package:hdc_mobile/core/notifications/notification_service.dart';
import 'package:hdc_mobile/core/signalr/hub_connection_manager.dart';
import 'package:hdc_mobile/shared/models/alarm_model.dart';

final alarmListProvider =
    NotifierProvider<AlarmListNotifier, List<AlarmModel>>(
  AlarmListNotifier.new,
);

/// 今日告警总数
final todayAlarmCountProvider = Provider<int>((ref) {
  final alarms = ref.watch(alarmListProvider);
  final today = DateTime.now();
  return alarms.where((a) {
    final d = a.occurredAt;
    return d.year == today.year &&
        d.month == today.month &&
        d.day == today.day;
  }).length;
});

/// 未读告警数
final unreadAlarmCountProvider = Provider<int>((ref) {
  return ref.watch(alarmListProvider).where((a) => !a.isRead).length;
});

class AlarmListNotifier extends Notifier<List<AlarmModel>> {
  StreamSubscription<Map<String, dynamic>>? _sub;

  @override
  List<AlarmModel> build() {
    ref.onDispose(() => _sub?.cancel());

    final hub = ref.read(hubConnectionManagerProvider);
    _sub = hub.onAlarm.listen(_handleAlarm);

    return [];
  }

  void _handleAlarm(Map<String, dynamic> data) {
    final alarm = AlarmModel.fromJson(data);
    var updated = [alarm, ...state];
    // 超出上限时裁剪
    if (updated.length > AppConstants.maxAlarmCount) {
      updated = updated.sublist(0, AppConstants.maxAlarmCount);
    }
    state = updated;

    // 弹系统通知
    ref.read(notificationServiceProvider).showAlarm(alarm);
  }

  void markAsRead(String alarmId) {
    state = state.map((a) {
      if (a.id == alarmId) return a.copyWith(isRead: true);
      return a;
    }).toList();
  }

  void markAllAsRead() {
    state = state.map((a) => a.copyWith(isRead: true)).toList();
  }

  void clear() {
    state = [];
  }
}
