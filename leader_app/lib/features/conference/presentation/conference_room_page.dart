import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_sound/flutter_sound.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/signalr/hub_connection_manager.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/auth/application/auth_controller.dart';
import 'package:hdc_mobile/features/conference/data/conference_repository.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';
import 'package:hdc_mobile/shared/utils/device_label.dart';

/// S08 集群对讲 — 会议室（半双工 PTT）。
///
/// 领导作为平等参与者：按住抢麦(RequestFloor)→获权(FloorGranted)后录 8k PCM
/// 上行(SendConferenceAudio)；收听其他成员音频(ConferenceAudio)经扬声器播放；
/// SpeakerChanged 显示当前说话人。同一时刻仅一人发言。
class ConferenceRoomPage extends ConsumerStatefulWidget {
  const ConferenceRoomPage({
    super.key,
    required this.conferenceId,
    required this.name,
    required this.members,
  });

  final int conferenceId;
  final String name;
  final List<DeviceModel> members;

  @override
  ConsumerState<ConferenceRoomPage> createState() =>
      _ConferenceRoomPageState();
}

class _ConferenceRoomPageState extends ConsumerState<ConferenceRoomPage> {
  static const int _sampleRate = 8000;

  final FlutterSoundRecorder _recorder = FlutterSoundRecorder();
  final FlutterSoundPlayer _player = FlutterSoundPlayer();
  bool _recorderOpen = false;
  bool _playerOpen = false;

  bool _pttActive = false; // 已按下（抢麦中或已授权）
  bool _floorHeld = false; // 已获发言权（正在上行）
  bool _ended = false;

  String _myId = 'leader';
  String? _currentSpeaker; // 收听方：当前说话人 deviceId

  StreamController<Uint8List>? _audioController;
  StreamSubscription<Uint8List>? _audioSub;
  final List<StreamSubscription<dynamic>> _hubSubs = [];
  Timer? _heartbeat; // 发言权 2.5s 续约
  Timer? _floorTimeout; // 抢麦 3s 兜底
  Timer? _speakerTimeout; // 说话人 6s 自超时
  Timer? _presenceTimer; // 25s 刷新在线（供组长硬退出兜底巡检）
  Timer? _timer;
  Duration _duration = Duration.zero;

  String get _confId => widget.conferenceId.toString();

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final userName =
        ref.read(authControllerProvider).valueOrNull?.userName ?? 'cmd';
    _myId = 'leader-$userName';

    final status = await Permission.microphone.request();
    final hub = ref.read(hubConnectionManagerProvider);
    await hub.connect();
    await hub.joinConferenceGroup(_confId);
    // 注册在线（领导作组长），并每 25s 刷新；硬退出后超宽限由后端自动转交
    await hub.joinDeviceNotificationGroup(_myId);
    _presenceTimer = Timer.periodic(const Duration(seconds: 25), (_) {
      hub.joinDeviceNotificationGroup(_myId);
    });

    // 收听播放
    await _player.openPlayer();
    await _player.startPlayerFromStream(
      codec: Codec.pcm16,
      interleaved: true,
      numChannels: 1,
      sampleRate: _sampleRate,
      bufferSize: 4096,
    );
    _playerOpen = true;

    if (status.isGranted) {
      await _recorder.openRecorder();
      _recorderOpen = true;
    }

    // ── SignalR 半双工事件订阅 ──
    _hubSubs.add(hub.onFloorGranted.listen(_onFloorGranted));
    _hubSubs.add(hub.onFloorDenied.listen(_onFloorDenied));
    _hubSubs.add(hub.onSpeakerChanged.listen(_onSpeakerChanged));
    _hubSubs.add(hub.onConferenceAudio.listen(_onConferenceAudio));
    _hubSubs.add(hub.onConferenceEvent.listen(_onConferenceEvent));

    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() => _duration += const Duration(seconds: 1));
    });
    if (mounted) setState(() {});
  }

  // ── 收听 ──
  void _onConferenceAudio(Uint8List pcm) {
    if (_ended || !_playerOpen || pcm.isEmpty) return;
    _player.uint8ListSink?.add(pcm);
  }

  void _onSpeakerChanged(Map<String, dynamic> data) {
    final cid = data['channelId']?.toString() ?? data['cid']?.toString() ?? '';
    if (cid != _confId) return;
    final deviceId = data['deviceId']?.toString() ?? '';
    final speaking = data['speaking'] == true;
    _speakerTimeout?.cancel();
    if (speaking && deviceId.isNotEmpty && deviceId != _myId) {
      setState(() => _currentSpeaker = deviceId);
      _speakerTimeout = Timer(const Duration(seconds: 6), () {
        if (mounted) setState(() => _currentSpeaker = null);
      });
    } else {
      setState(() => _currentSpeaker = null);
    }
  }

  void _onConferenceEvent(Map<String, dynamic> data) {
    final type = data['type']?.toString() ?? data['Type']?.toString() ?? '';
    if (type == 'ended') _endConference();
  }

  // ── 发言权 ──
  void _onFloorGranted(String cid) {
    if (cid != _confId || !_pttActive) return;
    _floorTimeout?.cancel();
    _floorHeld = true;
    _startUplink();
    if (mounted) setState(() {});
  }

  void _onFloorDenied(Map<String, dynamic> data) {
    final cid = data['channelId']?.toString() ?? data['cid']?.toString() ?? '';
    if (cid != _confId) return;
    _floorTimeout?.cancel();
    _pttActive = false;
    _floorHeld = false;
    final holder = data['holder']?.toString() ?? '';
    if (mounted) {
      setState(() {});
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('${holder.isEmpty ? '他人' : '对方'} 正在说话'),
          duration: const Duration(seconds: 1),
        ),
      );
    }
  }

  Future<void> _startTalking() async {
    if (!_recorderOpen || _pttActive || _ended) return;
    final hub = ref.read(hubConnectionManagerProvider);
    _pttActive = true;
    setState(() {});
    await hub.requestFloor(_confId, _myId);
    // 3s 抢麦兜底
    _floorTimeout?.cancel();
    _floorTimeout = Timer(const Duration(seconds: 3), () {
      if (_pttActive && !_floorHeld) {
        _pttActive = false;
        if (mounted) setState(() {});
      }
    });
  }

  Future<void> _startUplink() async {
    if (!_recorderOpen) return;
    final hub = ref.read(hubConnectionManagerProvider);
    _audioController = StreamController<Uint8List>();
    _audioSub = _audioController!.stream.listen((chunk) {
      if (chunk.isEmpty) return;
      hub.sendConferenceAudio(_confId, base64Encode(chunk));
    });
    await _recorder.startRecorder(
      toStream: _audioController!.sink,
      codec: Codec.pcm16,
      sampleRate: _sampleRate,
      numChannels: 1,
    );
    // 2.5s 发言权续约（后端 5s 超时）
    _heartbeat?.cancel();
    _heartbeat = Timer.periodic(const Duration(milliseconds: 2500), (_) {
      hub.requestFloor(_confId, _myId);
    });
  }

  Future<void> _stopTalking() async {
    if (!_pttActive && !_floorHeld) return;
    final hub = ref.read(hubConnectionManagerProvider);
    _pttActive = false;
    _floorHeld = false;
    _heartbeat?.cancel();
    _floorTimeout?.cancel();
    if (_recorderOpen) {
      try {
        await _recorder.stopRecorder();
      } on Exception {
        // 忽略停止异常
      }
    }
    await _audioSub?.cancel();
    await _audioController?.close();
    _audioSub = null;
    _audioController = null;
    await hub.releaseFloor(_confId, _myId);
    if (mounted) setState(() {});
  }

  Future<void> _endConference() async {
    if (_ended) return;
    _ended = true;
    await _stopTalking();
    final hub = ref.read(hubConnectionManagerProvider);
    await hub.leaveConferenceGroup(_confId);
    try {
      final repo = await ref.read(conferenceRepositoryProvider.future);
      await repo.end(widget.conferenceId);
    } on AppException {
      // 结束接口失败不阻塞退出
    }
    if (mounted) Navigator.of(context).maybePop();
  }

  @override
  void dispose() {
    _timer?.cancel();
    _heartbeat?.cancel();
    _floorTimeout?.cancel();
    _speakerTimeout?.cancel();
    _presenceTimer?.cancel();
    for (final s in _hubSubs) {
      s.cancel();
    }
    _audioSub?.cancel();
    _audioController?.close();
    if (_recorderOpen) {
      _recorder.stopRecorder().whenComplete(() => _recorder.closeRecorder());
    }
    if (_playerOpen) {
      _player.stopPlayer().whenComplete(() => _player.closePlayer());
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) _endConference();
      },
      child: Scaffold(
        backgroundColor: AppColors.scaffold,
        appBar: AppBar(
          backgroundColor: AppColors.scaffold,
          title: const Text('集群对讲'),
          automaticallyImplyLeading: false,
        ),
        body: Column(
          children: [
            _Header(
              name: widget.name,
              count: widget.members.length + 1,
              duration: _duration,
            ),
            if (_currentSpeaker != null) _speakerBanner(),
            Expanded(child: _buildMembers()),
            _buildControls(),
          ],
        ),
      ),
    );
  }

  String _speakerLabel(String deviceId) {
    DeviceModel? d;
    for (final m in widget.members) {
      if (m.deviceId == deviceId) {
        d = m;
        break;
      }
    }
    if (d != null) return formatDeviceShort(d);
    if (deviceId.startsWith('leader')) return '指挥';
    return '设备${deviceId.length >= 4 ? deviceId.substring(deviceId.length - 4) : deviceId}';
  }

  Widget _speakerBanner() {
    return Container(
      width: double.infinity,
      color: const Color(0xFFFB8C00),
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Icon(Icons.volume_up, color: Colors.white, size: 20),
          const SizedBox(width: 8),
          Text(
            '${_speakerLabel(_currentSpeaker!)} 正在讲话',
            style: const TextStyle(
              color: Colors.white,
              fontSize: 16,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildMembers() {
    return GridView.count(
      crossAxisCount: 2,
      padding: const EdgeInsets.all(16),
      childAspectRatio: 1.4,
      mainAxisSpacing: 12,
      crossAxisSpacing: 12,
      children: [
        _MemberCard(
          title: '我（指挥）',
          subtitle: _floorHeld ? '讲话中' : (_pttActive ? '抢麦中' : '待命'),
          speaking: _floorHeld,
          isSelf: true,
        ),
        ...widget.members.map(
          (d) => _MemberCard(
            title: formatDeviceShort(d),
            subtitle: _currentSpeaker == d.deviceId ? '讲话中' : '收听中',
            speaking: _currentSpeaker == d.deviceId,
            isSelf: false,
          ),
        ),
      ],
    );
  }

  Widget _buildControls() {
    final talking = _floorHeld;
    final pending = _pttActive && !_floorHeld;
    return Container(
      color: AppColors.card,
      padding: EdgeInsets.fromLTRB(
        16,
        18,
        16,
        18 + MediaQuery.of(context).padding.bottom,
      ),
      child: Column(
        children: [
          GestureDetector(
            onTapDown: _ended ? null : (_) => _startTalking(),
            onTapUp: _ended ? null : (_) => _stopTalking(),
            onTapCancel: _ended ? null : _stopTalking,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 150),
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 18),
              decoration: BoxDecoration(
                color: talking
                    ? AppColors.primary
                    : AppColors.primary.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.primary, width: 1.5),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.mic,
                    color: talking ? Colors.white : AppColors.primary,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    !_recorderOpen
                        ? '无麦克风权限'
                        : talking
                            ? '讲话中 松开结束'
                            : pending
                                ? '抢麦中…'
                                : '按住 讲话',
                    style: TextStyle(
                      color: talking ? Colors.white : AppColors.primary,
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 14),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _ended ? null : _endConference,
              icon: const Icon(Icons.call_end),
              label: const Text('退出对讲'),
              style: FilledButton.styleFrom(
                backgroundColor: AppColors.alarm,
                padding: const EdgeInsets.symmetric(vertical: 13),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({
    required this.name,
    required this.count,
    required this.duration,
  });

  final String name;
  final int count;
  final Duration duration;

  @override
  Widget build(BuildContext context) {
    final m = duration.inMinutes.toString().padLeft(2, '0');
    final s = (duration.inSeconds % 60).toString().padLeft(2, '0');
    return Container(
      width: double.infinity,
      color: AppColors.surface,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                name,
                style: const TextStyle(
                  color: AppColors.textPrimary,
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                '$count 人参与',
                style: const TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 12,
                ),
              ),
            ],
          ),
          Text(
            '$m:$s',
            style: const TextStyle(
              color: AppColors.online,
              fontSize: 16,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}

class _MemberCard extends StatelessWidget {
  const _MemberCard({
    required this.title,
    required this.subtitle,
    required this.speaking,
    required this.isSelf,
  });

  final String title;
  final String subtitle;
  final bool speaking;
  final bool isSelf;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
          color: speaking ? AppColors.online : Colors.transparent,
        ),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          CircleAvatar(
            radius: 22,
            backgroundColor: isSelf
                ? AppColors.primary.withValues(alpha: 0.2)
                : AppColors.surface,
            child: Icon(
              isSelf ? Icons.campaign : Icons.person,
              color: isSelf ? AppColors.primary : AppColors.textSecondary,
              size: 22,
            ),
          ),
          const SizedBox(height: 8),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            child: Text(
              title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: AppColors.textPrimary,
                fontSize: 13,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
          const SizedBox(height: 2),
          Text(
            subtitle,
            style: TextStyle(
              color: speaking ? AppColors.online : AppColors.textMuted,
              fontSize: 11,
            ),
          ),
        ],
      ),
    );
  }
}
