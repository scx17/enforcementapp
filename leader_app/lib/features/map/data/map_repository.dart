import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/http/dio_client.dart';

/// 设备地图位置（坐标为 WGS-84，显示前需转 GCJ-02）。
class DeviceLocation {
  const DeviceLocation({
    required this.deviceId,
    required this.channelId,
    required this.name,
    required this.status,
    required this.lng,
    required this.lat,
    this.address,
  });

  final String deviceId;
  final String channelId;
  final String? name;
  final String status;
  final double lng;
  final double lat;
  final String? address;

  bool get isOnline {
    final s = status.toUpperCase();
    return s == 'ON' || s == 'ONLINE' || s == '1' || s == 'TRUE';
  }

  static DeviceLocation? fromJson(Map<String, dynamic> json) {
    final lng = _toDouble(json['longitude'] ?? json['Longitude']);
    final lat = _toDouble(json['latitude'] ?? json['Latitude']);
    if (lng == null || lat == null || (lng == 0 && lat == 0)) return null;
    return DeviceLocation(
      deviceId: json['deviceId']?.toString() ?? json['DeviceId']?.toString() ?? '',
      channelId:
          json['channelId']?.toString() ?? json['ChannelId']?.toString() ?? '',
      name: json['name']?.toString() ?? json['Name']?.toString(),
      status: json['status']?.toString() ?? json['Status']?.toString() ?? 'OFF',
      lng: lng,
      lat: lat,
      address: json['address']?.toString() ?? json['Address']?.toString(),
    );
  }

  static double? _toDouble(dynamic v) {
    if (v == null) return null;
    if (v is num) return v.toDouble();
    return double.tryParse(v.toString());
  }
}

final mapRepositoryProvider = FutureProvider<MapRepository>((ref) async {
  final dio = await ref.watch(dioClientProvider.future);
  return MapRepository(dio);
});

class MapRepository {
  const MapRepository(this._dio);

  final Dio _dio;

  /// GET /api/map/devices — 返回有定位的设备列表（无 LocationUpdate 事件，前端轮询刷新）。
  Future<List<DeviceLocation>> getDeviceLocations() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>('/api/map/devices');
      final data = response.data ?? {};
      if (data['success'] == false) {
        throw AppException(0, data['message']?.toString() ?? '获取设备位置失败');
      }
      final list = (data['data'] as List?) ?? [];
      return list
          .whereType<Map<String, dynamic>>()
          .map(DeviceLocation.fromJson)
          .whereType<DeviceLocation>()
          .toList();
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }
}
