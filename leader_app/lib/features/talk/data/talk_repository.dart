import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:hdc_mobile/core/config/app_config.dart';
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
  ///
  /// 后端对 flvUrl/audioUrl 返回 nginx 代理相对路径 `/media/...`，原生播放器
  /// 无法解析相对路径，故对以 `/` 开头的相对路径补上 serverUrl 转绝对地址。
  String? get nativeAudioUrl {
    final raw = flvUrl ?? audioUrl;
    if (raw == null || raw.isEmpty) return null;
    if (raw.startsWith('/')) {
      return '${AppConfig.instance.serverUrl}$raw';
    }
    return raw;
  }

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

  /// POST /api/talk/p2p/start — 发起 1对1 对讲，返回 talkId。
  /// 设备收 IncomingTalk：双工自动应答全双工 / 单工来电+按住回话。
  Future<String> p2pStart({
    required String fromDeviceId,
    required String fromName,
    required String toDeviceId,
  }) async {
    try {
      final resp = await _dio.post<Map<String, dynamic>>(
        '/api/talk/p2p/start',
        data: {
          'fromDeviceId': fromDeviceId,
          'fromName': fromName,
          'toDeviceId': toDeviceId,
        },
      );
      final data = resp.data ?? {};
      if (data['success'] == false) {
        throw AppException(0, data['error']?.toString() ?? '发起对讲失败');
      }
      return data['talkId']?.toString() ?? '';
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }

  /// POST /api/talk/p2p/end — 结束 1对1 对讲。
  Future<void> p2pEnd({required String talkId, required String toDeviceId}) async {
    try {
      await _dio.post<dynamic>('/api/talk/p2p/end', data: {
        'talkId': talkId,
        'toDeviceId': toDeviceId,
      });
    } on DioException catch (e) {
      throw toAppException(e);
    }
  }
}
