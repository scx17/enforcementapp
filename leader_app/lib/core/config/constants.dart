abstract final class AppConstants {
  /// HTTP 连接超时（秒）
  static const Duration connectTimeout = Duration(seconds: 8);

  /// HTTP 接收超时（秒）
  static const Duration receiveTimeout = Duration(seconds: 15);

  /// 最大同时预览路数
  static const int maxVideoSlots = 4;

  /// Cookie 文件目录名
  static const String cookieDirName = 'cookies';

  /// SignalR 重连间隔
  static const Duration hubReconnectDelay = Duration(seconds: 3);

  /// 告警最大保留条数
  static const int maxAlarmCount = 200;
}
