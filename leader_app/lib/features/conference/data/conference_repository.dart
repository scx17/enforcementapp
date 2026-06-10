import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/http/dio_client.dart';
import 'package:hdc_mobile/shared/models/device_model.dart';

final conferenceRepositoryProvider =
    FutureProvider<ConferenceRepository>((ref) async {
  final dio = await ref.watch(dioClientProvider.future);
  return ConferenceRepository(dio);
});

class ConferenceRepository {
  const ConferenceRepository(this._dio);

  final Dio _dio;

  /// POST /api/conference 创建会议，Members 为设备 JSON 数组。
  /// 返回新会议 id。
  Future<int> create({
    required String name,
    required List<DeviceModel> members,
  }) async {
    try {
      final membersJson = jsonEncode(
        members
            .map((d) => {'deviceId': d.deviceId, 'name': d.name})
            .toList(),
      );
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/conference',
        data: {
          'name': name,
          'members': membersJson,
          'maxMembers': members.length > 20 ? members.length : 20,
        },
      );
      final data = response.data ?? {};
      if (data['success'] == false) {
        throw AppException(0, data['error']?.toString() ?? '创建会议失败');
      }
      final conf = data['data'];
      final id = conf is Map ? conf['id'] ?? conf['Id'] : null;
      if (id == null) throw const AppException(0, '会议创建响应缺少 id');
      return id is int ? id : int.parse(id.toString());
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  /// POST /api/conference/{id}/start 开始会议（触发向成员设备推送入会邀请）。
  Future<void> start(int id) async {
    try {
      await _dio.post<dynamic>('/api/conference/$id/start');
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  /// POST /api/conference/{id}/end 结束会议（通知成员设备离会）。
  Future<void> end(int id) async {
    try {
      await _dio.post<dynamic>('/api/conference/$id/end');
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }
}
