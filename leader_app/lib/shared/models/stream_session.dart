class StreamSession {
  const StreamSession({
    required this.streamId,
    required this.deviceId,
    required this.channelId,
    this.wsFlvUrl,
    this.flvUrl,
    this.rtspUrl,
    this.hlsUrl,
  });

  final String streamId;
  final String? wsFlvUrl;
  final String? flvUrl;
  final String? rtspUrl;
  final String? hlsUrl;
  final String deviceId;
  final String channelId;

  factory StreamSession.fromJson(Map<String, dynamic> json) {
    return StreamSession(
      streamId: json['streamId']?.toString() ??
          json['id']?.toString() ??
          '',
      deviceId: json['deviceId']?.toString() ?? '',
      channelId: json['channelId']?.toString() ?? '',
      wsFlvUrl: json['wsFlvUrl']?.toString() ??
          json['ws_flv_url']?.toString(),
      flvUrl: json['flvUrl']?.toString() ??
          json['flv_url']?.toString(),
      rtspUrl: json['rtspUrl']?.toString() ??
          json['rtsp_url']?.toString(),
      hlsUrl: json['hlsUrl']?.toString() ??
          json['hls_url']?.toString(),
    );
  }

  /// 原生播放器（media_kit / libmpv）可解析的 URL 优先级。
  ///
  /// 注意：libmpv 不支持 WebSocket-FLV（ws://），那是浏览器 flv.js 的能力。
  /// 移动端必须用 HTTP-FLV、RTSP 或 HLS。优先 HTTP-FLV（低延迟）。
  String? get nativeUrl => flvUrl ?? rtspUrl ?? hlsUrl ?? wsFlvUrl;
}
