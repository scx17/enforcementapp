import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/devices/data/device_repository.dart';
import 'package:hdc_mobile/features/monitor/data/stream_repository.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';
import 'package:hdc_mobile/shared/models/stream_session.dart';
import 'package:hdc_mobile/shared/utils/device_label.dart';

/// 录像回放页。选择日期 + 时间段后向后端请求 GB28181 回放流。
///
/// 说明：GB 回放是后端 SIP 实时推送的 FLV 流，不是可随机定位的文件，
/// 因此当前仅支持 播放/暂停；快进快退(seek)需后端提供 SIP 回放控制接口。
class PlaybackPage extends ConsumerStatefulWidget {
  const PlaybackPage({super.key, required this.device});

  final DeviceModel device;

  @override
  ConsumerState<PlaybackPage> createState() => _PlaybackPageState();
}

class _PlaybackPageState extends ConsumerState<PlaybackPage> {
  late final Player _player;
  late final VideoController _controller;

  DateTime _date = DateTime.now();
  TimeOfDay _start = const TimeOfDay(hour: 0, minute: 0);
  TimeOfDay _end = const TimeOfDay(hour: 23, minute: 59);

  StreamSession? _session;
  bool _isLoading = false;
  String? _error;
  String? _playerError;
  bool _isPaused = false;

  Timer? _elapsedTimer;
  Duration _elapsed = Duration.zero;

  @override
  void initState() {
    super.initState();
    _player = Player(
      configuration: const PlayerConfiguration(bufferSize: 8 * 1024 * 1024),
    );
    _controller = VideoController(_player);
    _player.stream.error.listen((msg) {
      if (!mounted) return;
      setState(() => _playerError = msg);
    });

    // 默认时间段：今天则取最近 1 小时，否则整天
    final now = DateTime.now();
    _start = const TimeOfDay(hour: 0, minute: 0);
    _end = TimeOfDay(hour: now.hour, minute: now.minute);
  }

  @override
  void dispose() {
    _elapsedTimer?.cancel();
    final streamId = _session?.streamId;
    if (streamId != null) {
      // fire-and-forget 停流
      ref.read(streamRepositoryProvider.future).then(
            (repo) => repo.stopPlayback(streamId),
            onError: (_) {},
          );
    }
    _player.dispose();
    super.dispose();
  }

  DateTime get _startDateTime => DateTime(
        _date.year,
        _date.month,
        _date.day,
        _start.hour,
        _start.minute,
      );

  DateTime get _endDateTime => DateTime(
        _date.year,
        _date.month,
        _date.day,
        _end.hour,
        _end.minute,
      );

  Future<void> _startPlayback() async {
    if (!_endDateTime.isAfter(_startDateTime)) {
      setState(() => _error = '结束时间必须晚于开始时间');
      return;
    }

    // 停掉上一次回放
    final prev = _session?.streamId;
    if (prev != null) {
      final repo = await ref.read(streamRepositoryProvider.future);
      await repo.stopPlayback(prev);
    }

    setState(() {
      _isLoading = true;
      _error = null;
      _playerError = null;
      _session = null;
    });

    try {
      var cid = widget.device.channelId;
      if (cid == null || cid.isEmpty) {
        final deviceRepo = await ref.read(deviceRepositoryProvider.future);
        cid = await deviceRepo.getFirstChannelId(widget.device.deviceId);
      }
      if (cid == null || cid.isEmpty) {
        setState(() {
          _isLoading = false;
          _error = '该设备无可用通道';
        });
        return;
      }

      final repo = await ref.read(streamRepositoryProvider.future);
      final session = await repo.startPlayback(
        deviceId: widget.device.deviceId,
        channelId: cid,
        startTime: _startDateTime,
        endTime: _endDateTime,
      );
      if (!mounted) return;

      final url = session.nativeUrl;
      if (url == null) {
        setState(() {
          _isLoading = false;
          _error = '回放流地址为空';
        });
        return;
      }

      await _player.open(Media(url), play: true);
      if (!mounted) return;
      setState(() {
        _session = session;
        _isLoading = false;
        _isPaused = false;
      });
      _startElapsedTimer();
    } on AppException catch (e) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _error = e.message;
      });
    }
  }

  void _startElapsedTimer() {
    _elapsedTimer?.cancel();
    _elapsed = Duration.zero;
    _elapsedTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted || _isPaused) return;
      setState(() => _elapsed += const Duration(seconds: 1));
    });
  }

  Future<void> _togglePause() async {
    await _player.playOrPause();
    setState(() => _isPaused = !_isPaused);
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _date,
      firstDate: DateTime.now().subtract(const Duration(days: 90)),
      lastDate: DateTime.now(),
    );
    if (picked != null) setState(() => _date = picked);
  }

  Future<void> _pickTime({required bool isStart}) async {
    final picked = await showTimePicker(
      context: context,
      initialEntryMode: TimePickerEntryMode.input,
      initialTime: isStart ? _start : _end,
    );
    if (picked != null) {
      setState(() => isStart ? _start = picked : _end = picked);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.scaffold,
      appBar: AppBar(
        title: const Text('录像回放'),
        backgroundColor: AppColors.scaffold,
      ),
      body: Column(
        children: [
          _DeviceBar(device: widget.device),
          _buildSelectors(),
          _buildPlayer(),
          if (_session != null) _buildControlBar(),
        ],
      ),
    );
  }

  Widget _buildSelectors() {
    final df = DateFormat('yyyy-MM-dd');
    final today = DateTime.now();
    final yesterday = today.subtract(const Duration(days: 1));
    final isToday = _sameDay(_date, today);
    final isYesterday = _sameDay(_date, yesterday);

    return Container(
      color: AppColors.card,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _DateChip(
                label: '今天',
                active: isToday,
                onTap: () => setState(() => _date = today),
              ),
              const SizedBox(width: 8),
              _DateChip(
                label: '昨天',
                active: isYesterday,
                onTap: () => setState(() => _date = yesterday),
              ),
              const SizedBox(width: 8),
              _DateChip(
                label: !isToday && !isYesterday ? df.format(_date) : '选日期',
                active: !isToday && !isYesterday,
                icon: Icons.calendar_today_outlined,
                onTap: _pickDate,
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: _TimeField(
                  label: '开始',
                  value: _start.format(context),
                  onTap: () => _pickTime(isStart: true),
                ),
              ),
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 8),
                child: Text('—', style: TextStyle(color: AppColors.textMuted)),
              ),
              Expanded(
                child: _TimeField(
                  label: '结束',
                  value: _end.format(context),
                  onTap: () => _pickTime(isStart: false),
                ),
              ),
              const SizedBox(width: 12),
              ElevatedButton(
                onPressed: _isLoading ? null : _startPlayback,
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppColors.primary,
                  foregroundColor: Colors.white,
                  padding:
                      const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
                ),
                child: const Text('回放'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildPlayer() {
    return AspectRatio(
      aspectRatio: 16 / 9,
      child: Container(
        color: Colors.black,
        child: Builder(
          builder: (_) {
            if (_isLoading) {
              return const Center(
                child: CircularProgressIndicator(color: AppColors.primary),
              );
            }
            if (_error != null) {
              return _PlaybackError(message: _error!);
            }
            if (_playerError != null) {
              return _PlaybackError(message: '播放失败：$_playerError');
            }
            if (_session != null) {
              return Video(
                controller: _controller,
                fit: BoxFit.contain,
                controls: NoVideoControls,
              );
            }
            return const Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.history_outlined,
                      color: AppColors.textMuted, size: 48),
                  SizedBox(height: 12),
                  Text(
                    '选择日期与时间段后点击「回放」',
                    style:
                        TextStyle(color: AppColors.textSecondary, fontSize: 13),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildControlBar() {
    final windowSeconds =
        _endDateTime.difference(_startDateTime).inSeconds.clamp(1, 1 << 31);
    final progress = (_elapsed.inSeconds / windowSeconds).clamp(0.0, 1.0);

    return Container(
      color: AppColors.card,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Column(
        children: [
          LinearProgressIndicator(
            value: progress,
            backgroundColor: AppColors.surface,
            color: AppColors.primary,
            minHeight: 4,
          ),
          const SizedBox(height: 10),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '已播 ${_fmtDuration(_elapsed)}',
                style: const TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 12,
                  fontFamily: 'monospace',
                ),
              ),
              GestureDetector(
                onTap: _togglePause,
                child: Container(
                  width: 44,
                  height: 44,
                  decoration: const BoxDecoration(
                    color: AppColors.primary,
                    shape: BoxShape.circle,
                  ),
                  child: Icon(
                    _isPaused ? Icons.play_arrow : Icons.pause,
                    color: Colors.white,
                  ),
                ),
              ),
              Text(
                '${_start.format(context)} – ${_end.format(context)}',
                style: const TextStyle(
                  color: AppColors.textMuted,
                  fontSize: 12,
                  fontFamily: 'monospace',
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  bool _sameDay(DateTime a, DateTime b) =>
      a.year == b.year && a.month == b.month && a.day == b.day;

  String _fmtDuration(Duration d) {
    final h = d.inHours.toString().padLeft(2, '0');
    final m = (d.inMinutes % 60).toString().padLeft(2, '0');
    final s = (d.inSeconds % 60).toString().padLeft(2, '0');
    return '$h:$m:$s';
  }
}

class _DeviceBar extends StatelessWidget {
  const _DeviceBar({required this.device});

  final DeviceModel device;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: AppColors.surface,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          const Icon(Icons.videocam_outlined,
              color: AppColors.textSecondary, size: 18),
          const SizedBox(width: 8),
          Text(
            formatDeviceLabel(device),
            style: const TextStyle(
              color: AppColors.textPrimary,
              fontSize: 14,
              fontWeight: FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}

class _DateChip extends StatelessWidget {
  const _DateChip({
    required this.label,
    required this.active,
    required this.onTap,
    this.icon,
  });

  final String label;
  final bool active;
  final VoidCallback onTap;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
        decoration: BoxDecoration(
          color: active ? AppColors.primary : AppColors.surface,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (icon != null) ...[
              Icon(icon,
                  size: 13,
                  color: active ? Colors.white : AppColors.textSecondary),
              const SizedBox(width: 4),
            ],
            Text(
              label,
              style: TextStyle(
                color: active ? Colors.white : AppColors.textSecondary,
                fontSize: 13,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TimeField extends StatelessWidget {
  const _TimeField({
    required this.label,
    required this.value,
    required this.onTap,
  });

  final String label;
  final String value;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(6),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              '$label  $value',
              style: const TextStyle(
                color: AppColors.textPrimary,
                fontSize: 13,
              ),
            ),
            const Icon(Icons.access_time,
                size: 15, color: AppColors.textMuted),
          ],
        ),
      ),
    );
  }
}

class _PlaybackError extends StatelessWidget {
  const _PlaybackError({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.error_outline, color: AppColors.alarm, size: 36),
          const SizedBox(height: 10),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24),
            child: Text(
              message,
              style: const TextStyle(color: AppColors.alarm, fontSize: 13),
              textAlign: TextAlign.center,
            ),
          ),
        ],
      ),
    );
  }
}
