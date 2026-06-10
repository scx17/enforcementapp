import 'package:flutter_dotenv/flutter_dotenv.dart';

class AppConfig {
  AppConfig._();
  static final AppConfig _instance = AppConfig._();
  static AppConfig get instance => _instance;

  String get serverUrl {
    final url = dotenv.env['SERVER_URL'] ?? '';
    if (url.isEmpty) {
      throw StateError('SERVER_URL not set in .env');
    }
    // Remove trailing slash
    return url.endsWith('/') ? url.substring(0, url.length - 1) : url;
  }

  String get orgName => dotenv.env['ORG_NAME'] ?? '执法监控';

  String get hubUrl => '$serverUrl/hubs/monitor';
}
