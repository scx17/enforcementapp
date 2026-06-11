import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_sound/flutter_sound.dart';
import 'package:media_kit/media_kit.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/signalr/hub_connection_manager.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/devices/data/device_repository.dart';
import 'package:hdc_mobile/features/talk/data/talk_repository.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';
import 'package:hdc_mobile/shared/utils/device_label.dart';
import 'package:hdc_mobile/shared/utils/live_low_latency.dart';

/// 单兵对讲页。
///
/// 「说」：按住话筒录 PCM16 8kHz → SignalR SendAudioToDevice 上行。
/// 「听」：media_kit 播放 talk/start 返回的下行音频流。
class IntercomPage extends ConsumerStatefulWidget {
  const IntercomPage({super.key, required this.device});

  final DeviceModel device;

  @override
  ConsumerState<IntercomPage> createState() => _IntercomPageState();
}

enum _TalkPhase { connecting, listening, talking, error, ended }

class _IntercomPageState extends ConsumerState<IntercomPage> {
  static const int _sampleRate = 8000;

  final FlutterSoundRecorder _recorder = FlutterSoundRecorder();
  Player? _player;

  TalkSession? _session;
  _TalkPhase _phase = _TalkPhase.connecting;
  String? _errorMsg;
  bool _muted = false;

  StreamController<Uint8List>? _audioController;
  StreamSubscription<Uint8List>? _audioSub;
  StreamSubscription<String>? _talkEndedSub;

  Timer? _callTimer;
  Duration _callDuration = Duration.zero;
  bool _recorderOpen = false;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    // 麦克风权限
    final status = await Permission.microphone.request();
    if (!status.isGranted) {
      _fail('未授予麦克风权限，无法对讲');
      return;
    }

    try {
      // 解析通道
      var cid = widget.device.channelId;
      if (cid == null || cid.isEmpty) {
        final deviceRepo = await ref.read(deviceRepositoryProvider.future);
        cid = await deviceRepo.getFirstChannelId(widget.device.deviceId);
      }

      // 建立对讲会话
      final repo = await ref.read(talkRepositoryProvider.future);
      final session = await repo.startTalk(
        deviceId: widget.device.deviceId,
        channelId: cid,
      );
      if (!mounted) return;
      _session = session;

      // 监听远端挂断
      final hub = ref.read(hubConnectionManagerProvider);
      await hub.connect();
      await hub.joinTalkGroup(widget.device.deviceId);
      _talkEndedSub = hub.onTalkEnded.listen((talkId) {
        if (!mounted) return;
        if (talkId.isEmpty || talkId == session.talkId) {
          _endByRemote();
        }
      });

      // 播放下行音频（听设备）
      final audioUrl = session.nativeAudioUrl;
      if (audioUrl != null) {
        _player = Player(
          configuration:
              const PlayerConfiguration(bufferSize: 4 * 1024 * 1024),
        );
        await applyLiveLowLatency(_player!);
        await _player!.open(Media(audioUrl), play: true);
      }

      // 打开录音器
      await _recorder.openRecorder();
      _recorderOpen = true;

      if (!mounted) return;
      setState(() => _phase = _TalkPhase.listening);
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
    // 给用户一个「已结束」的瞬间反馈后退出
    Future.delayed(const Duration(milliseconds: 800), () {
      if (mounted) Navigator.of(context).maybePop();
    });
  }

  Future<void> _startTalking() async {
    if (_phase != _TalkPhase.listening || !_recorderOpen) return;
    final session = _session;
    if (session == null) return;

    final hub = ref.read(hubConnectionManagerProvider);
    final deviceId = widget.device.deviceId;

    _audioController = StreamController<Uint8List>();
    _audioSub = _audioController!.stream.listen((chunk) {
      if (chunk.isEmpty) return;
      // fire-and-forget 上行
      hub.sendAudioToDevice(deviceId, base64Encode(chunk));
    });

    await _recorder.startRecorder(
      toStream: _audioController!.sink,
      codec: Codec.pcm16,
      sampleRate: _sampleRate,
      numChannels: 1,
    );
    if (!mounted) return;
    setState(() => _phase = _TalkPhase.talking);
  }

  Future<void> _stopTalking() async {
    if (_phase != _TalkPhase.talking) return;
    if (_recorderOpen) await _recorder.stopRecorder();
    await _audioSub?.cancel();
    await _audioController?.close();
    _audioSub = null;
    _audioController = null;
    if (!mounted) return;
    setState(() => _phase = _TalkPhase.listening);
  }

  void _toggleMute() {
    setState(() => _muted = !_muted);
    _player?.setVolume(_muted ? 0 : 100);
  }

  Future<void> _hangUp() async {
    await _cleanup();
    if (mounted) Navigator.of(context).maybePop();
  }

  Future<void> _cleanup() async {
    _callTimer?.cancel();
    await _audioSub?.cancel();
    await _audioController?.close();
    if (_recorderOpen) {
      try {
        await _recorder.stopRecorder();
      } on Exception {
        // ignore
      }
      await _recorder.closeRecorder();
      _recorderOpen = false;
    }
    await _player?.dispose();
    _player = null;
    final talkId = _session?.talkId;
    if (talkId != null && talkId.isNotEmpty) {
      try {
        final repo = await ref.read(talkRepositoryProvider.future);
        await repo.stopTalk(talkId);
      } on AppException {
        // ignore
      }
    }
  }

  @override
  void dispose() {
    _talkEndedSub?.cancel();
    // 同步触发清理（不等待）
    unawaited(_cleanup());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.scaffold,
      appBar: AppBar(
        backgroundColor: AppColors.scaffold,
        title: const Text('单兵对讲'),
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: _hangUp,
        ),
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
    final isEnded = _phase == _TalkPhase.ended;

    return Column(
      children: [
        const Spacer(),
        // 计时
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
            _TalkPhase.talking => '说话中',
            _TalkPhase.listening => '收听中',
            _TalkPhase.ended => '对讲已结束',
            _TalkPhase.error => '',
          },
          style: const TextStyle(color: AppColors.textSecondary, fontSize: 14),
        ),
        const Spacer(),
        // PTT 按钮
        GestureDetector(
          onTapDown: isConnecting || isEnded ? null : (_) => _startTalking(),
          onTapUp: isConnecting || isEnded ? null : (_) => _stopTalking(),
          onTapCancel: isConnecting || isEnded ? null : _stopTalking,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 150),
            width: 160,
            height: 160,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: isTalking
                  ? AppColors.primary
                  : AppColors.primary.withValues(alpha: 0.15),
              border: Border.all(
                color: AppColors.primary,
                width: isTalking ? 3 : 2,
              ),
            ),
            child: Icon(
              Icons.mic,
              size: 56,
              color: isTalking ? Colors.white : AppColors.primary,
            ),
          ),
        ),
        const SizedBox(height: 16),
        Text(
          isConnecting ? '请稍候' : '按住 说话',
          style: const TextStyle(color: AppColors.textMuted, fontSize: 13),
        ),
        const Spacer(),
        // 底部控制：静音 / 挂断
        Padding(
          padding: const EdgeInsets.only(bottom: 32),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              _CircleButton(
                icon: _muted ? Icons.volume_off : Icons.volume_up,
                label: _muted ? '已静音' : '扬声器',
                color: AppColors.surface,
                iconColor: AppColors.textSecondary,
                onTap: isConnecting || isEnded ? null : _toggleMute,
              ),
              const SizedBox(width: 40),
              _CircleButton(
                icon: Icons.call_end,
                label: '挂断',
                color: AppColors.alarm,
                iconColor: Colors.white,
                onTap: _hangUp,
              ),
            ],
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
              width: 56,
              height: 56,
              decoration: BoxDecoration(color: color, shape: BoxShape.circle),
              child: Icon(icon, color: iconColor, size: 24),
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
