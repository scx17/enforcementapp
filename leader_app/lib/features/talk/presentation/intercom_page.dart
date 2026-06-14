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
import 'package:hdc_mobile/features/talk/data/talk_repository.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';
import 'package:hdc_mobile/shared/utils/device_label.dart';

/// 单兵对讲页（SignalR 1对1，全双工）。
///
/// 指挥常开麦：录 PCM16 8kHz → SendAudioToDevice(设备) 上行；
/// 设备音频经 TalkAudio 下行 → FlutterSoundPlayer 播放。
/// 设备端按「对讲模式」：双工自动应答全双工 / 单工来电+按住回话。
class IntercomPage extends ConsumerStatefulWidget {
  const IntercomPage({super.key, required this.device});

  final DeviceModel device;

  @override
  ConsumerState<IntercomPage> createState() => _IntercomPageState();
}

enum _TalkPhase { connecting, talking, error, ended }

class _IntercomPageState extends ConsumerState<IntercomPage> {
  static const int _sampleRate = 8000;

  final FlutterSoundRecorder _recorder = FlutterSoundRecorder();
  final FlutterSoundPlayer _player = FlutterSoundPlayer();
  bool _recorderOpen = false;
  bool _playerOpen = false;

  _TalkPhase _phase = _TalkPhase.connecting;
  String? _errorMsg;
  String _talkId = '';
  String _myId = 'leader';

  StreamController<Uint8List>? _audioController;
  StreamSubscription<Uint8List>? _audioSub;
  StreamSubscription<Uint8List>? _downSub;
  StreamSubscription<String>? _talkEndedSub;

  Timer? _callTimer;
  Duration _callDuration = Duration.zero;

  String get _deviceId => widget.device.deviceId;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final status = await Permission.microphone.request();
    if (!status.isGranted) {
      _fail('未授予麦克风权限，无法对讲');
      return;
    }
    try {
      final userName =
          ref.read(authControllerProvider).valueOrNull?.userName ?? 'cmd';
      _myId = 'leader-$userName';

      final hub = ref.read(hubConnectionManagerProvider);
      await hub.connect();
      // 收设备上行音频：指挥须在自己的 device_notify 组
      await hub.joinDeviceNotificationGroup(_myId);

      // 下行播放（设备→指挥）
      await _player.openPlayer();
      await _player.startPlayerFromStream(
        codec: Codec.pcm16,
        interleaved: true,
        numChannels: 1,
        sampleRate: _sampleRate,
        bufferSize: 4096,
      );
      _playerOpen = true;
      _downSub = hub.onTalkAudio.listen((pcm) {
        if (_playerOpen && pcm.isNotEmpty) _player.uint8ListSink?.add(pcm);
      });

      // 发起对讲信令
      final repo = await ref.read(talkRepositoryProvider.future);
      _talkId = await repo.p2pStart(
        fromDeviceId: _myId,
        fromName: userName,
        toDeviceId: _deviceId,
      );

      // 对端挂断 → 结束
      _talkEndedSub = hub.onTalkEnded.listen((talkId) {
        if (!mounted) return;
        if (talkId.isEmpty || talkId == _talkId) _endByRemote();
      });

      // 上行常开麦（全双工）
      await _recorder.openRecorder();
      _recorderOpen = true;
      _audioController = StreamController<Uint8List>();
      _audioSub = _audioController!.stream.listen((chunk) {
        if (chunk.isNotEmpty) {
          hub.sendAudioToDevice(_deviceId, base64Encode(chunk));
        }
      });
      await _recorder.startRecorder(
        toStream: _audioController!.sink,
        codec: Codec.pcm16,
        sampleRate: _sampleRate,
        numChannels: 1,
      );

      if (!mounted) return;
      setState(() => _phase = _TalkPhase.talking);
      _startCallTimer();
    } on AppException catch (e) {
      _fail(e.message);
    }
  }

  void _startCallTimer() {
    _callTimer?.cancel();
    _callTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() => _callDuration += const Duration(seconds: 1));
    });
  }

  void _fail(String message) {
    if (!mounted) return;
    setState(() {
      _phase = _TalkPhase.error;
      _errorMsg = message;
    });
  }

  void _endByRemote() {
    if (_phase == _TalkPhase.ended) return;
    setState(() => _phase = _TalkPhase.ended);
    _callTimer?.cancel();
    _stopMedia();
    Future.delayed(const Duration(milliseconds: 800), () {
      if (mounted) Navigator.of(context).maybePop();
    });
  }

  Future<void> _hangUp() async {
    await _cleanup(notifyPeer: true);
    if (mounted) Navigator.of(context).maybePop();
  }

  Future<void> _stopMedia() async {
    await _audioSub?.cancel();
    await _audioController?.close();
    _audioSub = null;
    _audioController = null;
    if (_recorderOpen) {
      try {
        await _recorder.stopRecorder();
      } on Exception {
        // ignore
      }
    }
  }

  Future<void> _cleanup({required bool notifyPeer}) async {
    _callTimer?.cancel();
    await _stopMedia();
    if (notifyPeer && _talkId.isNotEmpty) {
      try {
        final repo = await ref.read(talkRepositoryProvider.future);
        await repo.p2pEnd(talkId: _talkId, toDeviceId: _deviceId);
      } on AppException {
        // ignore
      }
    }
  }

  @override
  void dispose() {
    _talkEndedSub?.cancel();
    _downSub?.cancel();
    unawaited(_cleanup(notifyPeer: true));
    if (_recorderOpen) _recorder.closeRecorder();
    if (_playerOpen) {
      _player.stopPlayer().whenComplete(() => _player.closePlayer());
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.scaffold,
      appBar: AppBar(
        backgroundColor: AppColors.scaffold,
        title: const Text('单兵对讲'),
        leading: IconButton(icon: const Icon(Icons.close), onPressed: _hangUp),
      ),
      body: SafeArea(
        child: Column(
          children: [
            _DeviceHeader(device: widget.device, phase: _phase),
            Expanded(child: _buildBody()),
          ],
        ),
      ),
    );
  }

  Widget _buildBody() {
    if (_phase == _TalkPhase.error) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, color: AppColors.alarm, size: 44),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Text(
                _errorMsg ?? '对讲失败',
                style: const TextStyle(color: AppColors.alarm, fontSize: 14),
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 20),
            OutlinedButton(
              onPressed: () => Navigator.of(context).maybePop(),
              child: const Text('返回'),
            ),
          ],
        ),
      );
    }

    final isConnecting = _phase == _TalkPhase.connecting;
    final isTalking = _phase == _TalkPhase.talking;

    return Column(
      children: [
        const Spacer(),
        Text(
          _fmtDuration(_callDuration),
          style: const TextStyle(
            color: AppColors.textPrimary,
            fontSize: 30,
            fontWeight: FontWeight.w300,
            fontFeatures: [FontFeature.tabularFigures()],
          ),
        ),
        const SizedBox(height: 6),
        Text(
          switch (_phase) {
            _TalkPhase.connecting => '正在建立对讲…',
            _TalkPhase.talking => '通话中（免提·对方按键回话）',
            _TalkPhase.ended => '对讲已结束',
            _TalkPhase.error => '',
          },
          style: const TextStyle(color: AppColors.textSecondary, fontSize: 14),
        ),
        const Spacer(),
        Container(
          width: 160,
          height: 160,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: isTalking
                ? AppColors.primary.withValues(alpha: 0.2)
                : AppColors.primary.withValues(alpha: 0.1),
            border: Border.all(color: AppColors.primary, width: 2),
          ),
          child: Icon(
            isConnecting ? Icons.hourglass_top : Icons.graphic_eq,
            size: 56,
            color: AppColors.primary,
          ),
        ),
        const SizedBox(height: 16),
        const Text(
          '免提直接说话；对方按住对讲键回话',
          style: TextStyle(color: AppColors.textMuted, fontSize: 13),
        ),
        const Spacer(),
        Padding(
          padding: const EdgeInsets.only(bottom: 32),
          child: _CircleButton(
            icon: Icons.call_end,
            label: '挂断',
            color: AppColors.alarm,
            iconColor: Colors.white,
            onTap: _hangUp,
          ),
        ),
      ],
    );
  }

  String _fmtDuration(Duration d) {
    final m = d.inMinutes.toString().padLeft(2, '0');
    final s = (d.inSeconds % 60).toString().padLeft(2, '0');
    return '$m:$s';
  }
}

class _DeviceHeader extends StatelessWidget {
  const _DeviceHeader({required this.device, required this.phase});

  final DeviceModel device;
  final _TalkPhase phase;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: AppColors.surface,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          const Icon(Icons.person_outline,
              color: AppColors.textSecondary, size: 20),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  formatDeviceLabel(device),
                  style: const TextStyle(
                    color: AppColors.textPrimary,
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  device.isOnline ? '在线' : '离线',
                  style: TextStyle(
                    color:
                        device.isOnline ? AppColors.online : AppColors.offline,
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

class _CircleButton extends StatelessWidget {
  const _CircleButton({
    required this.icon,
    required this.label,
    required this.color,
    required this.iconColor,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final Color color;
  final Color iconColor;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: onTap == null ? 0.4 : 1.0,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          GestureDetector(
            onTap: onTap,
            child: Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(color: color, shape: BoxShape.circle),
              child: Icon(icon, color: iconColor, size: 26),
            ),
          ),
          const SizedBox(height: 6),
          Text(
            label,
            style: const TextStyle(
              color: AppColors.textSecondary,
              fontSize: 12,
            ),
          ),
        ],
      ),
    );
  }
}
