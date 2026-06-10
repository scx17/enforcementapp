import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:signalr_netcore/signalr_client.dart';
import 'package:hdc_mobile/core/config/app_config.dart';
import 'package:hdc_mobile/core/config/constants.dart';
import 'package:hdc_mobile/core/signalr/hub_events.dart';

final hubConnectionManagerProvider = Provider<HubConnectionManager>((ref) {
  final manager = HubConnectionManager();
  ref.onDispose(manager.dispose);
  return manager;
});

class HubConnectionManager {
  HubConnectionManager()
      : _alarmController = StreamController.broadcast(),
        _deviceOnlineController = StreamController.broadcast(),
        _deviceOfflineController = StreamController.broadcast(),
        _talkEndedController = StreamController.broadcast();

  final StreamController<Map<String, dynamic>> _alarmController;
  final StreamController<String> _deviceOnlineController;
  final StreamController<String> _deviceOfflineController;
  final StreamController<String> _talkEndedController;

  HubConnection? _connection;
  bool _disposed = false;

  Stream<Map<String, dynamic>> get onAlarm => _alarmController.stream;
  Stream<String> get onDeviceOnline => _deviceOnlineController.stream;
  Stream<String> get onDeviceOffline => _deviceOfflineController.stream;

  /// 对讲结束事件，载荷为 talkId（可能为空字符串）。
  Stream<String> get onTalkEnded => _talkEndedController.stream;

  HubConnectionState get state =>
      _connection?.state ?? HubConnectionState.Disconnected;

  bool get isConnected => state == HubConnectionState.Connected;

  Future<void> connect() async {
    if (_disposed) return;
    if (isConnected) return;

    _connection = HubConnectionBuilder()
        .withUrl(AppConfig.instance.hubUrl)
        .withAutomaticReconnect(
          retryDelays: List.filled(
            10,
            AppConstants.hubReconnectDelay.inMilliseconds,
          ),
        )
        .build();

    _registerHandlers();

    _connection!.onclose(({Exception? error}) {
      if (!_disposed) {
        // 自动重连由 withAutomaticReconnect 处理
      }
    });

    try {
      await _connection!.start();
    } on Exception {
      // 连接失败：自动重连会在后台重试
    }
  }

  Future<void> disconnect() async {
    await _connection?.stop();
    _connection = null;
  }

  void _registerHandlers() {
    final conn = _connection;
    if (conn == null) return;

    conn.on(HubEvents.newAlarm, (args) {
      if (_disposed || args == null || args.isEmpty) return;
      final data = args.first;
      if (data is Map<String, dynamic>) {
        _alarmController.add(data);
      }
    });

    conn.on(HubEvents.deviceOnline, (args) {
      if (_disposed || args == null || args.isEmpty) return;
      final deviceId = args.first?.toString() ?? '';
      if (deviceId.isNotEmpty) {
        _deviceOnlineController.add(deviceId);
      }
    });

    conn.on(HubEvents.deviceOffline, (args) {
      if (_disposed || args == null || args.isEmpty) return;
      final deviceId = args.first?.toString() ?? '';
      if (deviceId.isNotEmpty) {
        _deviceOfflineController.add(deviceId);
      }
    });

    conn.on(HubEvents.talkEnded, (args) {
      if (_disposed) return;
      final payload = (args != null && args.isNotEmpty) ? args.first : null;
      final talkId = payload is Map
          ? payload['talkId']?.toString() ?? ''
          : payload?.toString() ?? '';
      _talkEndedController.add(talkId);
    });
  }

  /// 加入设备对讲音频组。
  Future<void> joinTalkGroup(String deviceId) async {
    if (!isConnected) return;
    await _connection?.invoke(HubMethods.joinTalkGroup, args: [deviceId]);
  }

  /// 上行一帧麦克风音频（Base64 PCM16 8kHz 单声道）到设备。
  Future<void> sendAudioToDevice(String deviceId, String base64Pcm) async {
    if (!isConnected) return;
    await _connection?.invoke(
      HubMethods.sendAudioToDevice,
      args: [deviceId, base64Pcm],
    );
  }

  /// 加入集群会议音频组。
  Future<void> joinConferenceGroup(String conferenceId) async {
    if (!isConnected) return;
    await _connection?.invoke(HubMethods.joinConferenceGroup,
        args: [conferenceId]);
  }

  /// 离开集群会议音频组。
  Future<void> leaveConferenceGroup(String conferenceId) async {
    if (!isConnected) return;
    await _connection?.invoke(HubMethods.leaveConferenceGroup,
        args: [conferenceId]);
  }

  /// 上行一帧麦克风音频到会议组（喊话）。
  Future<void> sendConferenceAudio(String conferenceId, String base64Pcm) async {
    if (!isConnected) return;
    await _connection?.invoke(
      HubMethods.sendConferenceAudio,
      args: [conferenceId, base64Pcm],
    );
  }

  void dispose() {
    _disposed = true;
    _connection?.stop();
    _alarmController.close();
    _deviceOnlineController.close();
    _deviceOfflineController.close();
    _talkEndedController.close();
  }
}
