import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:media_kit/media_kit.dart';
import 'package:media_kit_video/media_kit_video.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/devices/application/device_provider.dart';
import 'package:hdc_mobile/features/monitor/application/monitor_controller.dart';
import 'package:hdc_mobile/features/monitor/presentation/playback_page.dart';
import 'package:hdc_mobile/features/talk/presentation/intercom_page.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';
import 'package:hdc_mobile/shared/models/stream_session.dart';
import 'package:hdc_mobile/shared/utils/device_label.dart';
import 'package:hdc_mobile/shared/utils/live_low_latency.dart';

class MonitorPage extends ConsumerWidget {
  const MonitorPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final monitor = ref.watch(monitorControllerProvider);
    final controller = ref.read(monitorControllerProvider.notifier);
    final deviceAsync = ref.watch(deviceListProvider);
    final onlineDevices =
        deviceAsync.valueOrNull?.where((d) => d.isOnline).toList() ?? [];

    final activeSlot = monitor.slots[monitor.activeSlot];

    return Scaffold(
      backgroundColor: Colors.black,
      body: Column(
        children: [
          // 视频墙主区
          Expanded(
            child: Stack(
              children: [
                _VideoWall(
                  layout: monitor.layout,
                  slots: monitor.slots,
                  activeSlot: monitor.activeSlot,
                  onTapSlot: controller.setActiveSlot,
                  onClearSlot: controller.stopSlot,
                  onRetrySlot: controller.retrySlot,
                  onFullscreen: (i) => _openFullscreen(context, monitor.slots[i]),
                ),
                // 顶部覆盖层：时间戳 + 布局切换
                SafeArea(
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Row(
                      children: [
                        _LiveTimestamp(),
                        const Spacer(),
                        _LayoutToggle(
                          current: monitor.layout,
                          onChanged: controller.setLayout,
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
          // 工具栏（作用于当前激活槽）
          _Toolbar(
            onTalkPressed: (activeSlot.device?.isOnline ?? false)
                ? () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) =>
                            IntercomPage(device: activeSlot.device!),
                      ),
                    )
                : null,
            onPlaybackPressed: activeSlot.device != null
                ? () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) =>
                            PlaybackPage(device: activeSlot.device!),
                      ),
                    )
                : null,
            onSnapshotPressed: null, // 截图 — 后续
            onFullscreenPressed: activeSlot.hasSession
                ? () => _openFullscreen(context, activeSlot)
                : null,
            onSplitPressed: () => controller.setLayout(
              monitor.layout == MonitorLayout.single
                  ? MonitorLayout.quad
                  : MonitorLayout.single,
            ),
          ),
          // 设备选择横向滚动（添加到当前激活槽）
          _DeviceSelector(
            devices: onlineDevices,
            selectedDeviceId: activeSlot.device?.deviceId,
            onSelect: (device) => controller.playInSlot(
              index: monitor.activeSlot,
              device: device,
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _openFullscreen(BuildContext context, SlotState slot) async {
    final session = slot.session;
    if (session == null) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => _FullscreenPlayer(session: session, device: slot.device),
      ),
    );
  }
}

/// 按布局排列视窗网格。
class _VideoWall extends StatelessWidget {
  const _VideoWall({
    required this.layout,
    required this.slots,
    required this.activeSlot,
    required this.onTapSlot,
    required this.onClearSlot,
    required this.onRetrySlot,
    required this.onFullscreen,
  });

  final MonitorLayout layout;
  final List<SlotState> slots;
  final int activeSlot;
  final void Function(int) onTapSlot;
  final void Function(int) onClearSlot;
  final void Function(int) onRetrySlot;
  final void Function(int) onFullscreen;

  bool get _showActiveBorder => layout.slotCount > 1;

  Widget _cell(int index) {
    return _VideoSlot(
      key: ValueKey('slot-$index'),
      slot: slots[index],
      isActive: index == activeSlot && _showActiveBorder,
      onTap: () => onTapSlot(index),
      onLongPress: slots[index].hasSession ? () => onFullscreen(index) : null,
      onClear: () => onClearSlot(index),
      onRetry: () => onRetrySlot(index),
    );
  }

  @override
  Widget build(BuildContext context) {
    return switch (layout) {
      MonitorLayout.single => _cell(0),
      MonitorLayout.dual => Column(
          children: [
            Expanded(child: _cell(0)),
            const SizedBox(height: 3),
            Expanded(child: _cell(1)),
          ],
        ),
      MonitorLayout.quad => Column(
          children: [
            Expanded(
              child: Row(
                children: [
                  Expanded(child: _cell(0)),
                  const SizedBox(width: 3),
                  Expanded(child: _cell(1)),
                ],
              ),
            ),
            const SizedBox(height: 3),
            Expanded(
              child: Row(
                children: [
                  Expanded(child: _cell(2)),
                  const SizedBox(width: 3),
                  Expanded(child: _cell(3)),
                ],
              ),
            ),
          ],
        ),
    };
  }
}

/// 单个视窗：自持 Player，按 slot 状态渲染空/加载/错误/播放。
class _VideoSlot extends StatefulWidget {
  const _VideoSlot({
    super.key,
    required this.slot,
    required this.isActive,
    required this.onTap,
    required this.onLongPress,
    required this.onClear,
    required this.onRetry,
  });

  final SlotState slot;
  final bool isActive;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;
  final VoidCallback onClear;
  final VoidCallback onRetry;

  @override
  State<_VideoSlot> createState() => _VideoSlotState();
}

class _VideoSlotState extends State<_VideoSlot> with WidgetsBindingObserver {
  late final Player _player;
  late final VideoController _controller;

  String? _openedUrl;
  String? _playerError;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _player = Player(
      configuration: const PlayerConfiguration(
        bufferSize: 8 * 1024 * 1024,
      ),
    );
    _controller = VideoController(_player);
    _player.stream.error.listen((msg) {
      if (!mounted) return;
      setState(() => _playerError = msg);
    });
    _applyLowLatencyThenSync();
  }

  Future<void> _applyLowLatencyThenSync() async {
    await applyLiveLowLatency(_player);
    await _syncSession();
  }

  @override
  void didUpdateWidget(covariant _VideoSlot oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.slot.session?.streamId != widget.slot.session?.streamId) {
      _syncSession();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 退后台暂停省流，回前台恢复
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive) {
      _player.pause();
    } else if (state == AppLifecycleState.resumed) {
      if (_openedUrl != null) _player.play();
    }
  }

  Future<void> _syncSession() async {
    final url = widget.slot.session?.nativeUrl;
    if (url == null) {
      if (_openedUrl != null) {
        await _player.stop();
        _openedUrl = null;
      }
      return;
    }
    if (url == _openedUrl) return;
    _openedUrl = url;
    _playerError = null;
    await _player.open(Media(url), play: true);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _player.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final slot = widget.slot;
    return GestureDetector(
      onTap: widget.onTap,
      onLongPress: widget.onLongPress,
      child: Container(
        color: Colors.black,
        foregroundDecoration: widget.isActive
            ? BoxDecoration(
                border: Border.all(color: AppColors.primary, width: 2),
              )
            : null,
        child: _buildContent(slot),
      ),
    );
  }

  Widget _buildContent(SlotState slot) {
    if (slot.isLoading) {
      return const _SlotMessage(
        spinner: true,
        text: '连接中…',
      );
    }
    if (slot.error != null) {
      return _ErrorView(message: slot.error!, onRetry: widget.onRetry);
    }
    if (slot.isEmpty) {
      return const _SlotMessage(
        icon: Icons.add_circle_outline,
        text: '点击下方选择设备',
      );
    }
    if (_playerError != null) {
      return _ErrorView(
        message: '视频播放失败：$_playerError',
        onRetry: widget.onRetry,
      );
    }
    if (slot.hasSession) {
      return Stack(
        children: [
          Positioned.fill(
            child: Video(
              controller: _controller,
              fit: BoxFit.contain,
              controls: NoVideoControls,
            ),
          ),
          // 左上角：LIVE + 设备短名
          if (slot.device != null)
            Positioned(
              top: 6,
              left: 6,
              child: _SlotBadge(device: slot.device!),
            ),
          // 右上角：关闭按钮
          Positioned(
            top: 6,
            right: 6,
            child: GestureDetector(
              onTap: widget.onClear,
              child: Container(
                padding: const EdgeInsets.all(4),
                decoration: const BoxDecoration(
                  color: Colors.black54,
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.close, color: Colors.white, size: 14),
              ),
            ),
          ),
        ],
      );
    }
    return const SizedBox.shrink();
  }
}

class _SlotBadge extends StatelessWidget {
  const _SlotBadge({required this.device});

  final DeviceModel device;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
      decoration: BoxDecoration(
        color: Colors.black54,
        borderRadius: BorderRadius.circular(3),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
            decoration: BoxDecoration(
              color: AppColors.liveBadge,
              borderRadius: BorderRadius.circular(2),
            ),
            child: const Text(
              'LIVE',
              style: TextStyle(
                color: Colors.white,
                fontSize: 8,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(width: 6),
          Text(
            formatDeviceShort(device),
            style: const TextStyle(color: Colors.white, fontSize: 11),
          ),
        ],
      ),
    );
  }
}

class _SlotMessage extends StatelessWidget {
  const _SlotMessage({
    this.icon,
    this.spinner = false,
    required this.text,
  });

  final IconData? icon;
  final bool spinner;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (spinner)
            const SizedBox(
              width: 28,
              height: 28,
              child: CircularProgressIndicator(
                color: AppColors.primary,
                strokeWidth: 3,
              ),
            )
          else if (icon != null)
            Icon(icon, color: AppColors.textMuted, size: 40),
          const SizedBox(height: 12),
          Text(
            text,
            style: const TextStyle(color: AppColors.textSecondary, fontSize: 13),
          ),
        ],
      ),
    );
  }
}

class _ErrorView extends StatelessWidget {
  const _ErrorView({required this.message, this.onRetry});

  final String message;
  final VoidCallback? onRetry;

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
              style: const TextStyle(color: AppColors.alarm, fontSize: 12),
              textAlign: TextAlign.center,
            ),
          ),
          if (onRetry != null) ...[
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh, size: 16),
              label: const Text('重试'),
              style: OutlinedButton.styleFrom(
                foregroundColor: AppColors.primary,
                side: const BorderSide(color: AppColors.primary),
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _LiveTimestamp extends StatefulWidget {
  @override
  State<_LiveTimestamp> createState() => _LiveTimestampState();
}

class _LiveTimestampState extends State<_LiveTimestamp> {
  late final Stream<DateTime> _clockStream;

  @override
  void initState() {
    super.initState();
    _clockStream = Stream.periodic(
      const Duration(seconds: 1),
      (_) => DateTime.now(),
    );
  }

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<DateTime>(
      stream: _clockStream,
      initialData: DateTime.now(),
      builder: (_, snap) {
        final now = snap.data ?? DateTime.now();
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
          decoration: BoxDecoration(
            color: Colors.black54,
            borderRadius: BorderRadius.circular(4),
          ),
          child: Text(
            DateFormat('yyyy-MM-dd HH:mm:ss').format(now),
            style: const TextStyle(
              color: AppColors.textPrimary,
              fontSize: 12,
              fontFamily: 'monospace',
            ),
          ),
        );
      },
    );
  }
}

class _LayoutToggle extends StatelessWidget {
  const _LayoutToggle({required this.current, required this.onChanged});

  final MonitorLayout current;
  final void Function(MonitorLayout) onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.black54,
        borderRadius: BorderRadius.circular(4),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _LayoutButton(
            label: '1',
            isActive: current == MonitorLayout.single,
            onTap: () => onChanged(MonitorLayout.single),
          ),
          _LayoutButton(
            label: '2',
            isActive: current == MonitorLayout.dual,
            onTap: () => onChanged(MonitorLayout.dual),
          ),
          _LayoutButton(
            label: '4',
            isActive: current == MonitorLayout.quad,
            onTap: () => onChanged(MonitorLayout.quad),
          ),
        ],
      ),
    );
  }
}

class _LayoutButton extends StatelessWidget {
  const _LayoutButton({
    required this.label,
    required this.isActive,
    required this.onTap,
  });

  final String label;
  final bool isActive;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        decoration: BoxDecoration(
          color: isActive ? AppColors.primary : Colors.transparent,
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(
          label,
          style: const TextStyle(color: Colors.white, fontSize: 12),
        ),
      ),
    );
  }
}

class _Toolbar extends StatelessWidget {
  const _Toolbar({
    required this.onTalkPressed,
    required this.onPlaybackPressed,
    required this.onSnapshotPressed,
    required this.onFullscreenPressed,
    required this.onSplitPressed,
  });

  final VoidCallback? onTalkPressed;
  final VoidCallback? onPlaybackPressed;
  final VoidCallback? onSnapshotPressed;
  final VoidCallback? onFullscreenPressed;
  final VoidCallback? onSplitPressed;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: AppColors.card,
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
        children: [
          _ToolButton(
            icon: Icons.mic_outlined,
            label: '对讲',
            onTap: onTalkPressed,
          ),
          _ToolButton(
            icon: Icons.replay_outlined,
            label: '回放',
            onTap: onPlaybackPressed,
          ),
          _ToolButton(
            icon: Icons.photo_camera_outlined,
            label: '截图',
            onTap: onSnapshotPressed,
          ),
          _ToolButton(
            icon: Icons.fullscreen_outlined,
            label: '全屏',
            onTap: onFullscreenPressed,
          ),
          _ToolButton(
            icon: Icons.grid_view_outlined,
            label: '分屏',
            onTap: onSplitPressed,
          ),
        ],
      ),
    );
  }
}

class _ToolButton extends StatelessWidget {
  const _ToolButton({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: onTap == null ? 0.4 : 1.0,
      child: GestureDetector(
        onTap: onTap,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, color: AppColors.textSecondary, size: 22),
            const SizedBox(height: 4),
            Text(
              label,
              style: const TextStyle(
                color: AppColors.textSecondary,
                fontSize: 11,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _DeviceSelector extends StatelessWidget {
  const _DeviceSelector({
    required this.devices,
    required this.selectedDeviceId,
    required this.onSelect,
  });

  final List<DeviceModel> devices;
  final String? selectedDeviceId;
  final void Function(DeviceModel) onSelect;

  @override
  Widget build(BuildContext context) {
    if (devices.isEmpty) {
      return Container(
        color: AppColors.card,
        height: 72,
        child: const Center(
          child: Text(
            '暂无在线设备',
            style: TextStyle(color: AppColors.textMuted, fontSize: 13),
          ),
        ),
      );
    }

    return Container(
      color: AppColors.card,
      height: 80,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        itemCount: devices.length,
        separatorBuilder: (_, sep) => const SizedBox(width: 8),
        itemBuilder: (_, i) {
          final d = devices[i];
          final isSelected = d.deviceId == selectedDeviceId;
          return GestureDetector(
            onTap: () => onSelect(d),
            child: Container(
              width: 100,
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
              decoration: BoxDecoration(
                color: isSelected
                    ? AppColors.primary.withValues(alpha: 0.15)
                    : AppColors.surface,
                borderRadius: BorderRadius.circular(6),
                border: Border.all(
                  color: isSelected ? AppColors.primary : Colors.transparent,
                ),
              ),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.videocam_outlined,
                    color:
                        isSelected ? AppColors.primary : AppColors.textSecondary,
                    size: 18,
                  ),
                  const SizedBox(height: 4),
                  Text(
                    formatDeviceShort(d),
                    style: TextStyle(
                      color: isSelected
                          ? AppColors.textPrimary
                          : AppColors.textSecondary,
                      fontSize: 11,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

/// 全屏播放（支持横屏）。自持独立 Player，退出时恢复竖屏。
class _FullscreenPlayer extends StatefulWidget {
  const _FullscreenPlayer({required this.session, this.device});

  final StreamSession session;
  final DeviceModel? device;

  @override
  State<_FullscreenPlayer> createState() => _FullscreenPlayerState();
}

class _FullscreenPlayerState extends State<_FullscreenPlayer> {
  late final Player _player;
  late final VideoController _controller;
  String? _error;

  @override
  void initState() {
    super.initState();
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
      DeviceOrientation.portraitUp,
    ]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);
    _player = Player(
      configuration: const PlayerConfiguration(bufferSize: 8 * 1024 * 1024),
    );
    _controller = VideoController(_player);
    _player.stream.error.listen((msg) {
      if (!mounted) return;
      setState(() => _error = msg);
    });
    _applyLowLatencyThenOpen();
  }

  Future<void> _applyLowLatencyThenOpen() async {
    await applyLiveLowLatency(_player);
    final url = widget.session.nativeUrl;
    if (url != null) {
      await _player.open(Media(url), play: true);
    }
  }

  @override
  void dispose() {
    _player.dispose();
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
    ]);
    SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: GestureDetector(
        onTap: () => Navigator.of(context).maybePop(),
        child: Stack(
          children: [
            Positioned.fill(
              child: _error != null
                  ? _ErrorView(message: '视频播放失败：$_error')
                  : Video(
                      controller: _controller,
                      fit: BoxFit.contain,
                      controls: NoVideoControls,
                    ),
            ),
            SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  children: [
                    GestureDetector(
                      onTap: () => Navigator.of(context).maybePop(),
                      child: Container(
                        padding: const EdgeInsets.all(8),
                        decoration: const BoxDecoration(
                          color: Colors.black54,
                          shape: BoxShape.circle,
                        ),
                        child: const Icon(
                          Icons.close,
                          color: Colors.white,
                          size: 20,
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    if (widget.device != null)
                      Text(
                        formatDeviceLabel(widget.device!),
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
