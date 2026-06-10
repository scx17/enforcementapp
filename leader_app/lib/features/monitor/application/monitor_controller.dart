import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/features/devices/data/device_repository.dart';
import 'package:hdc_mobile/features/monitor/data/stream_repository.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';
import 'package:hdc_mobile/shared/models/stream_session.dart';

/// 视频墙布局。slotCount 决定同时显示的视窗数量。
enum MonitorLayout {
  single,
  dual,
  quad;

  int get slotCount => switch (this) {
        MonitorLayout.single => 1,
        MonitorLayout.dual => 2,
        MonitorLayout.quad => 4,
      };
}

/// 单个视窗（slot）的状态。空槽 device == null。
class SlotState {
  const SlotState({
    this.device,
    this.session,
    this.isLoading = false,
    this.error,
  });

  final DeviceModel? device;
  final StreamSession? session;
  final bool isLoading;
  final String? error;

  bool get isEmpty => device == null;
  bool get hasSession => session != null;

  static const SlotState empty = SlotState();

  SlotState copyWith({
    DeviceModel? device,
    StreamSession? session,
    bool clearSession = false,
    bool? isLoading,
    String? error,
    bool clearError = false,
  }) {
    return SlotState(
      device: device ?? this.device,
      session: clearSession ? null : session ?? this.session,
      isLoading: isLoading ?? this.isLoading,
      error: clearError ? null : error ?? this.error,
    );
  }
}

class MonitorState {
  const MonitorState({
    this.layout = MonitorLayout.single,
    this.slots = const [SlotState.empty],
    this.activeSlot = 0,
  });

  final MonitorLayout layout;
  final List<SlotState> slots;
  final int activeSlot;

  MonitorState copyWith({
    MonitorLayout? layout,
    List<SlotState>? slots,
    int? activeSlot,
  }) {
    return MonitorState(
      layout: layout ?? this.layout,
      slots: slots ?? this.slots,
      activeSlot: activeSlot ?? this.activeSlot,
    );
  }
}

final monitorControllerProvider =
    NotifierProvider<MonitorController, MonitorState>(MonitorController.new);

class MonitorController extends Notifier<MonitorState> {
  @override
  MonitorState build() => const MonitorState();

  /// 切换布局。收缩时停掉超出新槽数的流；扩张时补空槽。
  Future<void> setLayout(MonitorLayout layout) async {
    if (layout == state.layout) return;
    final count = layout.slotCount;
    final current = state.slots;

    // 停掉将被裁剪掉的槽
    if (count < current.length) {
      for (var i = count; i < current.length; i++) {
        await _stopSession(current[i].session?.streamId);
      }
    }

    final next = List<SlotState>.generate(
      count,
      (i) => i < current.length ? current[i] : SlotState.empty,
    );
    final active = state.activeSlot >= count ? 0 : state.activeSlot;
    state = state.copyWith(layout: layout, slots: next, activeSlot: active);
  }

  void setActiveSlot(int index) {
    if (index < 0 || index >= state.slots.length) return;
    state = state.copyWith(activeSlot: index);
  }

  /// 在指定槽点播设备。channelId 为空时先取首个通道。
  Future<void> playInSlot({
    required int index,
    required DeviceModel device,
  }) async {
    if (index < 0 || index >= state.slots.length) return;

    // 先停掉该槽已有的流
    await _stopSession(state.slots[index].session?.streamId);

    _updateSlot(
      index,
      SlotState(device: device, isLoading: true),
    );

    try {
      var cid = device.channelId;
      if (cid == null || cid.isEmpty) {
        final deviceRepo = await ref.read(deviceRepositoryProvider.future);
        cid = await deviceRepo.getFirstChannelId(device.deviceId);
        if (cid == null || cid.isEmpty) {
          _updateSlot(
            index,
            SlotState(device: device, error: '该设备无可用通道'),
          );
          return;
        }
      }

      final repo = await ref.read(streamRepositoryProvider.future);
      final session = await repo.startPlay(
        deviceId: device.deviceId,
        channelId: cid,
      );
      // 点播期间用户可能已切换/清空该槽，落库前校验设备一致
      if (index >= state.slots.length ||
          state.slots[index].device?.deviceId != device.deviceId) {
        await _stopSession(session.streamId);
        return;
      }
      _updateSlot(index, SlotState(device: device, session: session));
    } on AppException catch (e) {
      _updateSlot(index, SlotState(device: device, error: e.message));
    }
  }

  /// 清空某个槽并停流。
  Future<void> stopSlot(int index) async {
    if (index < 0 || index >= state.slots.length) return;
    final streamId = state.slots[index].session?.streamId;
    _updateSlot(index, SlotState.empty);
    await _stopSession(streamId);
  }

  /// 重试当前槽的设备点播。
  Future<void> retrySlot(int index) async {
    if (index < 0 || index >= state.slots.length) return;
    final device = state.slots[index].device;
    if (device == null) return;
    await playInSlot(index: index, device: device);
  }

  /// 停掉全部流（退出监控页或登出时调用）。
  Future<void> stopAll() async {
    for (final slot in state.slots) {
      await _stopSession(slot.session?.streamId);
    }
    state = state.copyWith(
      slots: List<SlotState>.filled(state.layout.slotCount, SlotState.empty),
      activeSlot: 0,
    );
  }

  void _updateSlot(int index, SlotState slot) {
    if (index < 0 || index >= state.slots.length) return;
    final next = [...state.slots];
    next[index] = slot;
    state = state.copyWith(slots: next);
  }

  Future<void> _stopSession(String? streamId) async {
    if (streamId == null) return;
    try {
      final repo = await ref.read(streamRepositoryProvider.future);
      await repo.stopPlay(streamId);
    } on AppException {
      // 停流失败不影响 UI
    }
  }
}
