import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/signalr/hub_connection_manager.dart';
import 'package:hdc_mobile/features/devices/data/device_repository.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';

final deviceListProvider =
    AsyncNotifierProvider<DeviceListNotifier, List<DeviceModel>>(
  DeviceListNotifier.new,
);

class DeviceListNotifier extends AsyncNotifier<List<DeviceModel>> {
  StreamSubscription<String>? _onlineSub;
  StreamSubscription<String>? _offlineSub;

  @override
  Future<List<DeviceModel>> build() async {
    ref.onDispose(() {
      _onlineSub?.cancel();
      _offlineSub?.cancel();
    });

    final repo = await ref.watch(deviceRepositoryProvider.future);
    final devices = await repo.getDevices();

    // 订阅 SignalR 在线状态变化
    final hub = ref.read(hubConnectionManagerProvider);
    _onlineSub = hub.onDeviceOnline.listen(_handleDeviceOnline);
    _offlineSub = hub.onDeviceOffline.listen(_handleDeviceOffline);

    return devices;
  }

  Future<void> refresh() async {
    state = const AsyncLoading();
    try {
      final repo = await ref.read(deviceRepositoryProvider.future);
      final devices = await repo.getDevices();
      state = AsyncData(devices);
    } on Object catch (e, st) {
      state = AsyncError(e, st);
    }
  }

  void _handleDeviceOnline(String deviceId) {
    state.whenData((devices) {
      state = AsyncData(
        devices.map((d) {
          if (d.deviceId == deviceId) return d.copyWith(status: 'online');
          return d;
        }).toList(),
      );
    });
  }

  void _handleDeviceOffline(String deviceId) {
    state.whenData((devices) {
      state = AsyncData(
        devices.map((d) {
          if (d.deviceId == deviceId) return d.copyWith(status: 'offline');
          return d;
        }).toList(),
      );
    });
  }
}

/// 统计：在线数
final onlineCountProvider = Provider<int>((ref) {
  return ref
      .watch(deviceListProvider)
      .valueOrNull
      ?.where((d) => d.isOnline)
      .length ?? 0;
});

/// 统计：离线数
final offlineCountProvider = Provider<int>((ref) {
  return ref
      .watch(deviceListProvider)
      .valueOrNull
      ?.where((d) => !d.isOnline)
      .length ?? 0;
});
