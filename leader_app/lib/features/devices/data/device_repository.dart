import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/http/dio_client.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';

final deviceRepositoryProvider = FutureProvider<DeviceRepository>((ref) async {
  final dio = await ref.watch(dioClientProvider.future);
  return DeviceRepository(dio);
});

class DeviceRepository {
  const DeviceRepository(this._dio);

  final Dio _dio;

  /// GET /api/device?keyword=&status=&page=1&pageSize=200
  /// 响应: { success: true, data: [...], total, page, pageSize }
  Future<List<DeviceModel>> getDevices({
    String? keyword,
    String? status,
    int page = 1,
    int pageSize = 200,
  }) async {
    try {
      final response = await _dio.get<dynamic>(
        '/api/device',
        queryParameters: {
          if (keyword != null && keyword.isNotEmpty) 'keyword': keyword,
          if (status != null && status.isNotEmpty) 'status': status,
          'page': page,
          'pageSize': pageSize,
        },
      );
      final data = response.data;
      List<dynamic> items = [];
      if (data is List) {
        items = data;
      } else if (data is Map) {
        items = (data['data'] ?? data['items'] ?? data['list'] ?? [])
            as List<dynamic>;
      }
      return items
          .whereType<Map<String, dynamic>>()
          .map(DeviceModel.fromJson)
          .toList();
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  /// GET /api/device/{deviceId}/channels
  /// 响应: { success: true, data: [{ channelId, ... }] }
  /// 返回首个通道的 channelId（执法仪通常单通道）。
  Future<String?> getFirstChannelId(String deviceId) async {
    try {
      final response = await _dio.get<dynamic>(
        '/api/device/$deviceId/channels',
      );
      final data = response.data;
      List<dynamic> items = [];
      if (data is Map) {
        items = (data['data'] ?? data['list'] ?? []) as List<dynamic>;
      } else if (data is List) {
        items = data;
      }
      if (items.isEmpty) return null;
      final first = items.first;
      if (first is Map) {
        return first['channelId']?.toString() ??
            first['id']?.toString();
      }
      return null;
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }
}
