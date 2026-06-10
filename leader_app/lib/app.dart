import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/notifications/notification_service.dart';
import 'package:hdc_mobile/core/signalr/hub_connection_manager.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/auth/application/auth_controller.dart';
import 'package:hdc_mobile/features/devices/application/device_provider.dart';
import 'package:hdc_mobile/features/monitor/application/monitor_controller.dart';
import 'package:hdc_mobile/router/app_router.dart';

class LeaderApp extends ConsumerStatefulWidget {
  const LeaderApp({super.key});

  @override
  ConsumerState<LeaderApp> createState() => _LeaderAppState();
}

class _LeaderAppState extends ConsumerState<LeaderApp> {
  @override
  void initState() {
    super.initState();
    _initNotifications();
  }

  Future<void> _initNotifications() async {
    final service = ref.read(notificationServiceProvider);
    service.onAlarmTap = _handleAlarmTap;
    await service.init();
  }

  /// 通知点击：找到设备→在激活槽点播→跳监控页；找不到则跳告警页。
  void _handleAlarmTap(String deviceId) {
    final router = ref.read(routerProvider);
    final devices = ref.read(deviceListProvider).valueOrNull ?? [];
    final match = devices.where((d) => d.deviceId == deviceId).toList();

    if (match.isNotEmpty) {
      final monitor = ref.read(monitorControllerProvider.notifier);
      final active = ref.read(monitorControllerProvider).activeSlot;
      monitor.playInSlot(index: active, device: match.first);
      router.go('/monitor');
    } else {
      router.go('/alarm');
    }
  }

  @override
  Widget build(BuildContext context) {
    final router = ref.watch(routerProvider);

    // 登录后自动建立 SignalR 连接
    ref.listen(authControllerProvider, (_, next) {
      final isLoggedIn = next.valueOrNull?.isLoggedIn ?? false;
      final hub = ref.read(hubConnectionManagerProvider);
      if (isLoggedIn) {
        hub.connect();
      } else {
        hub.disconnect();
      }
    });

    return MaterialApp.router(
      title: '执法监控',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark,
      routerConfig: router,
    );
  }
}
