import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:latlong2/latlong.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/theme/app_theme.dart';
import 'package:hdc_mobile/features/map/data/map_repository.dart';
import 'package:hdc_mobile/features/monitor/application/monitor_controller.dart';
import 'package:hdc_mobile/features/monitor/presentation/playback_page.dart';
import 'package:hdc_mobile/features/talk/presentation/intercom_page.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';
import 'package:hdc_mobile/shared/utils/gcj02.dart';

/// S13 地图实时位置（F8）。
///
/// 底图复用平台同款高德在线栅格瓦片（GCJ-02，无需 key）；设备坐标 WGS-84 转 GCJ-02 后标注。
/// 后端无 LocationUpdate 事件，采用 10s 轮询 /api/map/devices 刷新。
class MapPage extends ConsumerStatefulWidget {
  const MapPage({super.key});

  @override
  ConsumerState<MapPage> createState() => _MapPageState();
}

class _MapPageState extends ConsumerState<MapPage> {
  // 高德在线街图瓦片（与平台 utils/map.js 同源）
  static const String _amapStreet =
      'https://wprd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&style=7&x={x}&y={y}&z={z}';
  static const LatLng _shenzhen = LatLng(22.5431, 114.0579);

  final MapController _mapController = MapController();
  Timer? _pollTimer;
  List<DeviceLocation> _devices = [];
  bool _loading = true;
  String? _error;
  bool _fittedOnce = false;

  @override
  void initState() {
    super.initState();
    _load();
    _pollTimer = Timer.periodic(const Duration(seconds: 10), (_) => _load());
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _mapController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final repo = await ref.read(mapRepositoryProvider.future);
      final list = await repo.getDeviceLocations();
      if (!mounted) return;
      setState(() {
        _devices = list;
        _loading = false;
        _error = null;
      });
      if (!_fittedOnce && list.isNotEmpty) {
        _fittedOnce = true;
        WidgetsBinding.instance.addPostFrameCallback((_) => _fitAll());
      }
    } on AppException catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.message;
      });
    }
  }

  void _fitAll() {
    final points = _devices
        .map((d) => Gcj02.wgs84ToLatLng(d.lng, d.lat))
        .toList();
    if (points.isEmpty) return;
    if (points.length == 1) {
      _mapController.move(points.first, 15);
      return;
    }
    _mapController.fitCamera(
      CameraFit.coordinates(
        coordinates: points,
        padding: const EdgeInsets.all(60),
        maxZoom: 16,
      ),
    );
  }

  void _onTapDevice(DeviceLocation d) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.card,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => _DeviceSheet(location: d),
    );
  }

  @override
  Widget build(BuildContext context) {
    final online = _devices.where((d) => d.isOnline).length;
    final offline = _devices.length - online;

    return Scaffold(
      backgroundColor: AppColors.scaffold,
      appBar: AppBar(
        backgroundColor: AppColors.scaffold,
        title: const Text('实时位置'),
        actions: [
          IconButton(
            icon: const Icon(Icons.my_location),
            tooltip: '查看全部',
            onPressed: _fitAll,
          ),
        ],
      ),
      body: Stack(
        children: [
          FlutterMap(
            mapController: _mapController,
            options: const MapOptions(
              initialCenter: _shenzhen,
              initialZoom: 11,
              minZoom: 3,
              maxZoom: 18,
            ),
            children: [
              TileLayer(
                urlTemplate: _amapStreet,
                subdomains: const ['1', '2', '3', '4'],
                maxZoom: 18,
                userAgentPackageName: 'com.hdcollection.leader',
                tileProvider: NetworkTileProvider(),
              ),
              MarkerLayer(
                markers: _devices
                    .map(
                      (d) => Marker(
                        point: Gcj02.wgs84ToLatLng(d.lng, d.lat),
                        width: 90,
                        height: 56,
                        alignment: Alignment.topCenter,
                        child: _DeviceMarker(
                          location: d,
                          onTap: () => _onTapDevice(d),
                        ),
                      ),
                    )
                    .toList(),
              ),
            ],
          ),
          // 图例
          Positioned(
            top: 12,
            left: 12,
            child: _Legend(online: online, offline: offline),
          ),
          if (_loading)
            const Positioned(
              top: 12,
              right: 12,
              child: SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: AppColors.primary,
                ),
              ),
            ),
          if (_error != null)
            Positioned(
              bottom: 16,
              left: 16,
              right: 16,
              child: Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: AppColors.alarm.withValues(alpha: 0.9),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Text(
                  '位置加载失败：$_error',
                  style: const TextStyle(color: Colors.white, fontSize: 12),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _DeviceMarker extends StatelessWidget {
  const _DeviceMarker({required this.location, required this.onTap});

  final DeviceLocation location;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = location.isOnline ? AppColors.primary : AppColors.offline;
    final label = location.name?.isNotEmpty == true
        ? location.name!
        : location.deviceId;
    return GestureDetector(
      onTap: onTap,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.6),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: Colors.white, fontSize: 10),
            ),
          ),
          Icon(Icons.location_on, color: color, size: 30),
        ],
      ),
    );
  }
}

class _Legend extends StatelessWidget {
  const _Legend({required this.online, required this.offline});

  final int online;
  final int offline;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.scaffold.withValues(alpha: 0.85),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: AppColors.divider),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _dot(AppColors.primary),
          Text('在线 $online',
              style: const TextStyle(
                  color: AppColors.textSecondary, fontSize: 12)),
          const SizedBox(width: 12),
          _dot(AppColors.offline),
          Text('离线 $offline',
              style: const TextStyle(
                  color: AppColors.textSecondary, fontSize: 12)),
        ],
      ),
    );
  }

  Widget _dot(Color c) => Container(
        margin: const EdgeInsets.only(right: 4),
        width: 8,
        height: 8,
        decoration: BoxDecoration(color: c, shape: BoxShape.circle),
      );
}

class _DeviceSheet extends ConsumerWidget {
  const _DeviceSheet({required this.location});

  final DeviceLocation location;

  DeviceModel get _device => DeviceModel(
        deviceId: location.deviceId,
        name: location.name ?? '',
        status: location.status,
        channelId: location.channelId,
      );

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final d = _device;
    final label = location.name?.isNotEmpty == true
        ? location.name!
        : location.deviceId;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.videocam,
                  color: location.isOnline
                      ? AppColors.primary
                      : AppColors.offline,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    label,
                    style: const TextStyle(
                      color: AppColors.textPrimary,
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                Text(
                  location.isOnline ? '在线' : '离线',
                  style: TextStyle(
                    color: location.isOnline
                        ? AppColors.online
                        : AppColors.offline,
                    fontSize: 13,
                  ),
                ),
              ],
            ),
            if (location.address?.isNotEmpty == true) ...[
              const SizedBox(height: 8),
              Text(
                location.address!,
                style: const TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 13,
                ),
              ),
            ],
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: location.isOnline
                        ? () {
                            final monitor =
                                ref.read(monitorControllerProvider.notifier);
                            final active = ref
                                .read(monitorControllerProvider)
                                .activeSlot;
                            monitor.playInSlot(index: active, device: d);
                            Navigator.of(context).pop();
                            context.go('/monitor');
                          }
                        : null,
                    icon: const Icon(Icons.videocam, size: 18),
                    label: const Text('实时'),
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.primary,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: location.isOnline
                        ? () {
                            Navigator.of(context).pop();
                            Navigator.of(context).push(
                              MaterialPageRoute<void>(
                                builder: (_) => IntercomPage(device: d),
                              ),
                            );
                          }
                        : null,
                    icon: const Icon(Icons.mic, size: 18),
                    label: const Text('对讲'),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: AppColors.warning,
                      side: const BorderSide(color: AppColors.warning),
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () {
                      Navigator.of(context).pop();
                      Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => PlaybackPage(device: d),
                        ),
                      );
                    },
                    icon: const Icon(Icons.replay, size: 18),
                    label: const Text('回放'),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: AppColors.textSecondary,
                      side: const BorderSide(color: AppColors.divider),
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
