import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/shared/models/alarm_model.dart';

final notificationServiceProvider =
    Provider<NotificationService>((ref) => NotificationService());

/// 本地系统通知封装。告警到达时弹系统通知，点击回调 deviceId。
class NotificationService {
  final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();

  bool _inited = false;

  /// 通知点击回调，载荷为 deviceId。
  void Function(String deviceId)? onAlarmTap;

  static const AndroidNotificationDetails _androidDetails =
      AndroidNotificationDetails(
    'alarm_channel',
    '告警通知',
    channelDescription: '设备告警实时推送',
    importance: Importance.high,
    priority: Priority.high,
  );

  static const DarwinNotificationDetails _iosDetails =
      DarwinNotificationDetails();

  Future<void> init() async {
    if (_inited) return;

    const android = AndroidInitializationSettings('@mipmap/ic_launcher');
    const ios = DarwinInitializationSettings(
      requestAlertPermission: true,
      requestBadgePermission: true,
      requestSoundPermission: true,
    );

    await _plugin.initialize(
      const InitializationSettings(android: android, iOS: ios),
      onDidReceiveNotificationResponse: (response) {
        final deviceId = response.payload;
        if (deviceId != null && deviceId.isNotEmpty) {
          onAlarmTap?.call(deviceId);
        }
      },
    );

    // Android 13+ / iOS 运行时通知权限
    await _plugin
        .resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>()
        ?.requestNotificationsPermission();
    await _plugin
        .resolvePlatformSpecificImplementation<
            IOSFlutterLocalNotificationsPlugin>()
        ?.requestPermissions(alert: true, badge: true, sound: true);

    _inited = true;
  }

  Future<void> showAlarm(AlarmModel alarm) async {
    final deviceLabel = alarm.deviceName?.isNotEmpty == true
        ? alarm.deviceName!
        : (alarm.customCodeDisplay ?? alarm.deviceId);
    final title = '${_levelText(alarm.level)} · $deviceLabel';
    final body = alarm.message?.isNotEmpty == true
        ? alarm.message!
        : '设备触发${alarm.type}告警';

    await _plugin.show(
      alarm.id.hashCode,
      title,
      body,
      const NotificationDetails(android: _androidDetails, iOS: _iosDetails),
      payload: alarm.deviceId,
    );
  }

  String _levelText(String level) => switch (level) {
        'high' => '紧急告警',
        'mid' => '重要告警',
        _ => '告警',
      };
}
