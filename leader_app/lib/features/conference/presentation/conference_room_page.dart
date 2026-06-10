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
import 'package:hdc_mobile/features/conference/data/conference_repository.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';
import 'package:hdc_mobile/shared/utils/device_label.dart';

/// S08 集群对讲 — 会议室（喊话中）。
///
/// 喊话为领导→设备单向广播：本页按住话筒录 8k PCM → SendConferenceAudio。
/// 成员设备收到 InviteToConference 后自动入会播放。
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
  bool _recorderOpen = false;
  bool _talking = false;
  bool _ended = false;

  StreamController<Uint8List>? _audioController;
  StreamSubscription<Uint8List>? _audioSub;

  Timer? _timer;
  Duration _duration = Duration.zero;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    final status = await Permission.microphone.request();
    final hub = ref.read(hubConnectionManagerProvider);
    await hub.connect();
    await hub.joinConferenceGroup(widget.conferenceId.toString());

    if (status.isGranted) {
      await _recorder.openRecorder();
      _recorderOpen = true;
    }

    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() => _duration += const Duration(seconds: 1));
    });
    if (mounted) setState(() {});
  }

  Future<void> _startTalking() async {
    if (!_recorderOpen || _talking || _ended) return;
    final hub = ref.read(hubConnectionManagerProvider);
    final confId = widget.conferenceId.toString();

    _audioController = StreamController<Uint8List>();
    _audioSub = _audioController!.stream.listen((chunk) {
      if (chunk.isEmpty) return;
      hub.sendConferenceAudio(confId, base64Encode(chunk));
    });

    await _recorder.startRecorder(
      toStream: _audioController!.sink,
      codec: Codec.pcm16,
      sampleRate: _sampleRate,
      numChannels: 1,
    );
    if (!mounted) return;
    setState(() => _talking = true);
  }

  Future<void> _stopTalking() async {
    if (!_talking) return;
    if (_recorderOpen) await _recorder.stopRecorder();
    await _audioSub?.cancel();
    await _audioController?.close();
    _audioSub = null;
    _audioController = null;
    if (!mounted) return;
    setState(() => _talking = false);
  }

  Future<void> _endConference() async {
    if (_ended) return;
    _ended = true;
    await _stopTalking();
    final hub = ref.read(hubConnectionManagerProvider);
    final confId = widget.conferenceId.toString();
    await hub.leaveConferenceGroup(confId);
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
    _audioSub?.cancel();
    _audioController?.close();
    if (_recorderOpen) {
      _recorder.stopRecorder().whenComplete(() => _recorder.closeRecorder());
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
          title: const Text('集群喊话'),
          automaticallyImplyLeading: false,
        ),
        body: Column(
          children: [
            _Header(name: widget.name, count: widget.members.length, duration: _duration),
            Expanded(child: _buildMembers()),
            _buildControls(),
          ],
        ),
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
          subtitle: _talking ? '喊话中' : '待命',
          speaking: _talking,
          isSelf: true,
        ),
        ...widget.members.map(
          (d) => _MemberCard(
            title: formatDeviceShort(d),
            subtitle: '收听中',
            speaking: false,
            isSelf: false,
          ),
        ),
      ],
    );
  }

  Widget _buildControls() {
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
                color: _talking
                    ? AppColors.primary
                    : AppColors.primary.withValues(alpha: 0.15),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.primary, width: 1.5),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.campaign,
                    color: _talking ? Colors.white : AppColors.primary,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    _recorderOpen ? '按住 喊话' : '无麦克风权限',
                    style: TextStyle(
                      color: _talking ? Colors.white : AppColors.primary,
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
              label: const Text('结束喊话'),
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
