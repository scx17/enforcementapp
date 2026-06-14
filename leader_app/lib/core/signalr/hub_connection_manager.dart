import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
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

  // 集群对讲半双工事件
  final _floorGrantedController = StreamController<String>.broadcast();
  final _floorDeniedController =
      StreamController<Map<String, dynamic>>.broadcast();
  final _speakerChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  final _conferenceAudioController = StreamController<Uint8List>.broadcast();
  final _conferenceEventController =
      StreamController<Map<String, dynamic>>.broadcast();

  /// 发言权授予，载荷 channelId。
  Stream<String> get onFloorGranted => _floorGrantedController.stream;

  /// 发言权被拒，载荷 {channelId, holder}。
  Stream<Map<String, dynamic>> get onFloorDenied =>
      _floorDeniedController.stream;

  /// 说话人变化，载荷 {channelId, deviceId, speaking}。
  Stream<Map<String, dynamic>> get onSpeakerChanged =>
      _speakerChangedController.stream;

  /// 会议组音频帧（已解码 PCM）。
  Stream<Uint8List> get onConferenceAudio => _conferenceAudioController.stream;

  /// 会议事件，载荷 {type, conferenceId, ...}。
  Stream<Map<String, dynamic>> get onConferenceEvent =>
      _conferenceEventController.stream;

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

    // ── 集群对讲半双工 ──
    conn.on(HubEvents.floorGranted, (args) {
      if (_disposed || args == null || args.isEmpty) return;
      _floorGrantedController.add(args.first?.toString() ?? '');
    });
    conn.on(HubEvents.floorDenied,
        (args) => _emitJson(_floorDeniedController, args));
    conn.on(HubEvents.speakerChanged,
        (args) => _emitJson(_speakerChangedController, args));
    conn.on(HubEvents.conferenceEvent,
        (args) => _emitJson(_conferenceEventController, args));
    conn.on(HubEvents.conferenceAudio, (args) {
      if (_disposed || args == null || args.isEmpty) return;
      final b64 = args.first?.toString() ?? '';
      if (b64.isEmpty) return;
      try {
        _conferenceAudioController.add(base64Decode(b64));
      } on FormatException {
        // 丢弃损坏帧
      }
    });
  }

  /// 后端这些通知发的是 JSON 字符串（兼容 Android String handler）；
  /// 也兜底直接收到 Map 的情况。
  void _emitJson(
      StreamController<Map<String, dynamic>> controller, List<Object?>? args) {
    if (_disposed || args == null || args.isEmpty) return;
    final raw = args.first;
    Map<String, dynamic>? map;
    if (raw is String) {
      try {
        final decoded = json.decode(raw);
        if (decoded is Map) map = decoded.cast<String, dynamic>();
      } on FormatException {
        map = null;
      }
    } else if (raw is Map) {
      map = raw.cast<String, dynamic>();
    }
    if (map != null) controller.add(map);
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

  /// 加入设备通知组并刷新后端在线时间（领导作组长时供硬退出兜底巡检判断在线）。
  Future<void> joinDeviceNotificationGroup(String deviceId) async {
    if (!isConnected) return;
    await _connection
        ?.invoke(HubMethods.joinDeviceNotificationGroup, args: [deviceId]);
  }

  /// 请求发言权（半双工 PTT）。
  Future<void> requestFloor(String conferenceId, String deviceId) async {
    if (!isConnected) return;
    await _connection
        ?.invoke(HubMethods.requestFloor, args: [conferenceId, deviceId]);
  }

  /// 释放发言权。
  Future<void> releaseFloor(String conferenceId, String deviceId) async {
    if (!isConnected) return;
    await _connection
        ?.invoke(HubMethods.releaseFloor, args: [conferenceId, deviceId]);
  }

  void dispose() {
    _disposed = true;
    _connection?.stop();
    _alarmController.close();
    _deviceOnlineController.close();
    _deviceOfflineController.close();
    _talkEndedController.close();
    _floorGrantedController.close();
    _floorDeniedController.close();
    _speakerChangedController.close();
    _conferenceAudioController.close();
    _conferenceEventController.close();
  }
}
