import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/http/api_exception.dart';
import 'package:hdc_mobile/core/http/dio_client.dart';

/// 一次对讲会话。flvUrl/audioUrl 为设备侧下行音频（用于「听」）。
class TalkSession {
  const TalkSession({
    required this.talkId,
    this.flvUrl,
    this.audioUrl,
    this.pushAudioUrl,
  });

  final String talkId;
  final String? flvUrl;
  final String? audioUrl;
  final String? pushAudioUrl;

  /// 原生播放器可解析的下行音频地址（听设备）。
  String? get nativeAudioUrl => flvUrl ?? audioUrl;

  factory TalkSession.fromJson(Map<String, dynamic> json) {
    return TalkSession(
      talkId: json['talkId']?.toString() ?? '',
      flvUrl: json['flvUrl']?.toString(),
      audioUrl: json['audioUrl']?.toString(),
      pushAudioUrl: json['pushAudioUrl']?.toString(),
    );
  }
}

final talkRepositoryProvider = FutureProvider<TalkRepository>((ref) async {
  final dio = await ref.watch(dioClientProvider.future);
  return TalkRepository(dio);
});

class TalkRepository {
  const TalkRepository(this._dio);

  final Dio _dio;

  /// POST /api/talk/start  body: { deviceId, channelId }
  Future<TalkSession> startTalk({
    required String deviceId,
    String? channelId,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        '/api/talk/start',
        data: {
          'deviceId': deviceId,
          if (channelId != null && channelId.isNotEmpty) 'channelId': channelId,
        },
      );
      final data = response.data ?? {};
      if (data['success'] == false) {
        throw AppException(0, data['message']?.toString() ?? '对讲建立失败');
      }
      return TalkSession.fromJson(data);
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  /// POST /api/talk/stop/{talkId}
  Future<void> stopTalk(String talkId) async {
    try {
      await _dio.post<dynamic>('/api/talk/stop/$talkId');
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }
}
