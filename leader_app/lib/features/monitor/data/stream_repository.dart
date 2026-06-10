import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/http/dio_client.dart';
import 'package:hdc_mobile/shared/models/stream_session.dart';

final streamRepositoryProvider =
    FutureProvider<StreamRepository>((ref) async {
  final dio = await ref.watch(dioClientProvider.future);
  return StreamRepository(dio);
});

class StreamRepository {
  const StreamRepository(this._dio);

  final Dio _dio;

  /// GET /api/media-stream/play?deviceId=&channelId=
  /// 响应: { success, streamId, flvUrl, wsFlvUrl, rtspUrl }
  Future<StreamSession> startPlay({
    required String deviceId,
    required String channelId,
  }) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        '/api/media-stream/play',
        queryParameters: {
          'deviceId': deviceId,
          'channelId': channelId,
        },
      );
      final data = response.data ?? {};
      if (data['success'] == false) {
        throw AppException(
          0,
          data['message']?.toString() ?? '点播失败',
        );
      }
      // 后端 streamId 在顶层，补上 deviceId/channelId 供回收使用
      return StreamSession.fromJson({
        ...data,
        'deviceId': deviceId,
        'channelId': channelId,
      });
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  /// POST /api/media-stream/stop/{streamId}
  Future<void> stopPlay(String streamId) async {
    try {
      await _dio.post<dynamic>('/api/media-stream/stop/$streamId');
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  /// POST /api/media-stream/playback
  /// body: { deviceId, channelId, startTime, endTime }
  /// 响应: { success, streamId, flvUrl, wsFlvUrl }
  Future<StreamSession> startPlayback({
    required String deviceId,
    required String channelId,
    required DateTime startTime,
    required DateTime endTime,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/media-stream/playback',
        data: {
          'deviceId': deviceId,
          'channelId': channelId,
          'startTime': startTime.toIso8601String(),
          'endTime': endTime.toIso8601String(),
        },
      );
      final data = response.data ?? {};
      if (data['success'] == false) {
        throw AppException(0, data['message']?.toString() ?? '回放失败');
      }
      return StreamSession.fromJson({
        ...data,
        'deviceId': deviceId,
        'channelId': channelId,
      });
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  /// POST /api/media-stream/playback/stop/{streamId}
  Future<void> stopPlayback(String streamId) async {
    try {
      await _dio.post<dynamic>('/api/media-stream/playback/stop/$streamId');
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }
}
